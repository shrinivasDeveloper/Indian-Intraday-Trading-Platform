package com.trading.strategy.channel;

import com.trading.events.SmartChannelPullbackSignalEvent;
import com.trading.events.TradeApprovedEvent;
import com.trading.events.TradeExecutionResultEvent;
import com.trading.marketdata.service.MarketPressureDecisionEngine;
import com.trading.risk.service.CircuitBreakerService;
import com.trading.risk.service.RiskManagementService;
import com.trading.sector.service.SectorClassificationService;
import com.trading.strategy.highrr.HighRRStrategyEngine;
import com.trading.strategy.orb.OrbStrategyEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SmartChannelSignalHandler — Central signal router for ALL strategies.
 *
 * STRATEGIES HANDLED:
 *   1. SMART_CHANNEL_PULLBACK_V3  → SmartChannelPullbackStrategy
 *   2. SCALP_PRESSURE_V2          → SidewaysScalpStrategy
 *   3. MARKET_PRESSURE_V1         → MarketPressureDecisionEngine
 *   4. HIGH_RR_INTRADAY_V1        → HighRRStrategyEngine
 *   5. ORB_BREAKOUT_V1            → OrbStrategyEngine  ← NEW (minimal addition)
 *
 * CHANGES vs previous version:
 *   1. Added OrbStrategyEngine injection (one new final field)
 *   2. Added "ORB_BREAKOUT_V1" constant
 *   3. Registered in switch statements: releaseStrategyLock(), onSignal() phase1 bypass
 *   All other logic is identical to the previous fixed version.
 *
 * FIX PRESERVED: openTrades keyed by "strategy:symbol" composite key, so different
 *   strategies can simultaneously trade the same symbol.
 *
 * ORB NOTES:
 *   - ORB bypasses the phase1Count gate (same treatment as HighRR).
 *     ORB manages its own 2-trade/day limit via OrbDataService.markTriggered().
 *   - No changes to execution pipeline; TradeApprovedEvent routes to
 *     PaperTradeExecutionService (PAPER) or TradeExecutionService (LIVE) as normal.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SmartChannelSignalHandler {

    private static final long STALE_SEC = 30L;

    // ── Strategy name constants ───────────────────────────────────────────────
    private static final String SCPS   = "SMART_CHANNEL_PULLBACK_V3";
    private static final String SCALP  = "SCALP_PRESSURE_V2";
    private static final String PRESS  = "MARKET_PRESSURE_V1";
    private static final String HIGHRR = "HIGH_RR_INTRADAY_V1";
    private static final String ORB    = "ORB_BREAKOUT_V1"; // ← NEW

    private final ApplicationEventPublisher    publisher;
    private final CircuitBreakerService        circuitBreaker;
    private final RiskManagementService        riskManagement;
    private final SectorClassificationService  sectorClassify;
    private final SmartChannelPullbackStrategy scpsStrategy;
    private final SidewaysScalpStrategy        scalpStrategy;
    private final MarketPressureDecisionEngine pressureEngine;
    private final HighRRStrategyEngine         highRREngine;
    private final OrbStrategyEngine            orbEngine;    // ← NEW

    @Value("${trading.max-phase1-concurrent:3}") private int maxPhase1;
    @Value("${trading.mode:PAPER}")               private String tradingMode;
    @Value("${trading.capital:100000}")           private BigDecimal capital;

    /**
     * openTrades: keyed by "strategy:symbol" composite key.
     * This allows multiple strategies to independently trade the same symbol.
     * e.g. "ORB_BREAKOUT_V1:RELIANCE" and "SMART_CHANNEL_PULLBACK_V3:RELIANCE"
     * can coexist without blocking each other.
     */
    private final Map<String, Instant> openTrades    = new ConcurrentHashMap<>();
    private final Map<String, String>  tradeStrategy = new ConcurrentHashMap<>();

    @EventListener
    @Async("tradingExecutor")
    public void onSignal(SmartChannelPullbackSignalEvent event) {
        String symbol   = event.getTradingSymbol();
        String strategy = event.getStrategyName();
        String tradeKey = strategy + ":" + symbol; // composite key (fixed)

        log.info("[HANDLER] Signal: {} from {} | dir={} entry={} sl={} T1={} score={}",
                symbol, strategy, event.getDirection(),
                event.getEntryPrice(), event.getStopLoss(),
                event.getTarget1(), event.getTotalScore());

        // Gate 1: stale signal (>30 seconds old)
        if (event.isStale()) {
            log.warn("[HANDLER] Stale signal rejected: {} from {}", symbol, strategy);
            releaseStrategyLock(symbol, strategy);
            return;
        }

        // Gate 2: same strategy already has open trade for this symbol
        if (openTrades.containsKey(tradeKey)) {
            log.debug("[HANDLER] {} already has open {} trade — rejecting duplicate signal",
                    symbol, strategy);
            return;
        }

        // Gate 3: circuit breaker
        CircuitBreakerService.Permission cb = circuitBreaker.checkPermission(capital);
        if (!cb.isAllowed()) {
            log.warn("[HANDLER] CB blocked {} from {}: {}", symbol, strategy, cb.reason());
            releaseStrategyLock(symbol, strategy);
            return;
        }

        // Gate 4: phase-1 slot limit
        // HighRR and ORB bypass this gate — they manage their own daily trade limits.
        // SCPS, SCALP, PRESS go through the shared phase1Count gate.
        if (!HIGHRR.equals(strategy) && !ORB.equals(strategy)) {
            int phase1 = riskManagement.getPhase1Count();
            if (phase1 >= maxPhase1) {
                log.warn("[HANDLER] Phase-1 limit ({}/{}) — {} from {} blocked",
                        phase1, maxPhase1, symbol, strategy);
                releaseStrategyLock(symbol, strategy);
                return;
            }
        }

        // All gates passed
        log.info("[HANDLER] ✅ All gates passed: {} from {} → TradeApprovedEvent", symbol, strategy);
        circuitBreaker.recordTradeEntered();

        publisher.publishEvent(new TradeApprovedEvent(
                this, symbol, event.getInstrumentToken(), event.getDirection(),
                event.getEntryPrice(), event.getStopLoss(), event.getTarget1(),
                event.getQuantity(), event.getRiskAmount(),
                BigDecimal.valueOf(event.getProbabilityScore()),
                strategy, event.getTimeStopMinutes()));

        openTrades.put(tradeKey, event.getSignalTimestamp());
        tradeStrategy.put(tradeKey, strategy);
        log.info("[HANDLER] TradeApproved published: {} | {} | qty={} | risk=₹{}",
                symbol, strategy, event.getQuantity(), event.getRiskAmount());
    }

    @EventListener
    @Async("tradingExecutor")
    public void onTradeResult(TradeExecutionResultEvent event) {
        String symbol = event.getTradingSymbol();
        if (!"CLOSED".equals(event.getStatus())) return;

        // Find and remove the matching strategy:symbol entry
        String removedKey = null;
        for (String key : openTrades.keySet()) {
            if (key.endsWith(":" + symbol)) {
                removedKey = key;
                break;
            }
        }

        if (removedKey == null) return;

        String closedStrategy = tradeStrategy.remove(removedKey);
        openTrades.remove(removedKey);

        BigDecimal pnl = event.getNetPnl() != null ? event.getNetPnl() : BigDecimal.ZERO;
        String reason  = event.getExitReason() != null ? event.getExitReason() : "UNKNOWN";
        String emoji   = pnl.compareTo(BigDecimal.ZERO) > 0 ? "✅" : "❌";

        log.info("[HANDLER] {} Closed: {} | strategy={} | reason={} | P&L=₹{}",
                emoji, symbol, closedStrategy, reason,
                String.format("%.2f", pnl.doubleValue()));

        releaseStrategyLock(symbol, closedStrategy);
    }

    private void releaseStrategyLock(String symbol, String strategy) {
        if (strategy == null) { releaseAll(symbol); return; }
        switch (strategy) {
            case SCPS   -> scpsStrategy.onSignalClosed(symbol);
            case SCALP  -> scalpStrategy.onSignalClosed(symbol);
            case PRESS  -> pressureEngine.onSignalClosed(symbol);
            case HIGHRR -> highRREngine.onSignalClosed(symbol);
            case ORB    -> orbEngine.onSignalClosed(symbol);  // ← NEW
            default     -> releaseAll(symbol);
        }
    }

    private void releaseAll(String symbol) {
        scpsStrategy.onSignalClosed(symbol);
        scalpStrategy.onSignalClosed(symbol);
        pressureEngine.onSignalClosed(symbol);
        highRREngine.onSignalClosed(symbol);
        orbEngine.onSignalClosed(symbol); // ← NEW
    }

    public int getOpenTradeCount() { return openTrades.size(); }

    public Map<String, String> getActiveTradesByStrategy() {
        return Collections.unmodifiableMap(tradeStrategy);
    }
}