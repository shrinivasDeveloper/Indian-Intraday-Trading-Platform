package com.trading.strategy.channel;

import com.trading.events.SmartChannelPullbackSignalEvent;
import com.trading.events.TradeApprovedEvent;
import com.trading.events.TradeExecutionResultEvent;
import com.trading.marketdata.service.MarketPressureDecisionEngine;
import com.trading.risk.service.CircuitBreakerService;
import com.trading.risk.service.RiskManagementService;
import com.trading.sector.service.SectorClassificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SmartChannelSignalHandler — bridges ALL channel + pressure strategy signals
 * to the existing trade execution pipeline.
 *
 * HANDLES SIGNALS FROM:
 *   1. SmartChannelPullbackStrategy  (SMART_CHANNEL_PULLBACK_V3)
 *   2. SidewaysScalpStrategy         (SCALP_PRESSURE_V2)
 *   3. MarketPressureDecisionEngine  (MARKET_PRESSURE_V1)
 *
 * GATES (in order):
 *   1. Stale signal guard (>30s old → reject)
 *   2. Duplicate symbol guard (no double-entry for same symbol)
 *   3. Circuit breaker check
 *   4. Phase-1 slot limit (maxPhase1Concurrent)
 *   → TradeApprovedEvent → PaperTradeExecutionService / TradeExecutionService
 */
@Service @Slf4j @RequiredArgsConstructor
public class SmartChannelSignalHandler {

    private static final long STALE_SEC = 30L;

    private final ApplicationEventPublisher    publisher;
    private final CircuitBreakerService        circuitBreaker;
    private final RiskManagementService        riskManagement;
    private final SectorClassificationService  sectorClassify;
    private final SmartChannelPullbackStrategy scpsStrategy;
    private final SidewaysScalpStrategy        scalpStrategy;
    private final MarketPressureDecisionEngine pressureEngine;

    @Value("${trading.max-phase1-concurrent:3}") private int maxPhase1;
    @Value("${trading.mode:PAPER}")               private String tradingMode;
    @Value("${trading.capital:100000}")           private BigDecimal capital;

    private final Map<String, Instant> openTrades = new ConcurrentHashMap<>();

    @EventListener @Async("tradingExecutor")
    public void onSignal(SmartChannelPullbackSignalEvent event) {
        String symbol   = event.getTradingSymbol();
        String strategy = event.getStrategyName();

        log.info("[HANDLER] Signal from {}: {} | dir={} entry={} sl={} T1={} score={}",
                strategy, symbol, event.getDirection(),
                event.getEntryPrice(), event.getStopLoss(),
                event.getTarget1(), event.getTotalScore());

        // Gate 1: stale
        if (event.isStale()) {
            log.warn("[HANDLER] Stale signal rejected: {} from {} (>{}s old)", symbol, strategy, STALE_SEC);
            releaseStrategyLock(symbol, strategy);
            return;
        }

        // Gate 2: duplicate
        if (openTrades.containsKey(symbol)) {
            log.debug("[HANDLER] {} already has open trade — skipping duplicate from {}", symbol, strategy);
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
        int phase1 = riskManagement.getPhase1Count();
        if (phase1 >= maxPhase1) {
            log.warn("[HANDLER] Phase-1 limit reached ({}/{}): {} from {} blocked", phase1, maxPhase1, symbol, strategy);
            releaseStrategyLock(symbol, strategy);
            return;
        }

        // All gates passed
        log.info("[HANDLER] ✅ All gates passed for {} from {} — publishing TradeApprovedEvent", symbol, strategy);
        circuitBreaker.recordTradeEntered();

        publisher.publishEvent(new TradeApprovedEvent(
                this, symbol, event.getInstrumentToken(), event.getDirection(),
                event.getEntryPrice(), event.getStopLoss(), event.getTarget1(),
                event.getQuantity(), event.getRiskAmount(),
                BigDecimal.valueOf(event.getProbabilityScore()),
                strategy, event.getTimeStopMinutes()));

        openTrades.put(symbol, event.getSignalTimestamp());
        log.info("[HANDLER] TradeApprovedEvent published: {} | strategy={} | qty={} | risk=₹{}",
                symbol, strategy, event.getQuantity(), event.getRiskAmount());
    }

    @EventListener @Async("tradingExecutor")
    public void onTradeResult(TradeExecutionResultEvent event) {
        String symbol = event.getTradingSymbol();
        if (!openTrades.containsKey(symbol) || !"CLOSED".equals(event.getStatus())) return;

        openTrades.remove(symbol);

        // Release all strategy locks (each guards its own active set)
        scpsStrategy.onSignalClosed(symbol);
        scalpStrategy.onSignalClosed(symbol);
        pressureEngine.onSignalClosed(symbol);

        BigDecimal pnl = event.getNetPnl() != null ? event.getNetPnl() : BigDecimal.ZERO;
        String reason  = event.getExitReason() != null ? event.getExitReason() : "UNKNOWN";

        log.info("[HANDLER] Trade closed: {} | reason={} | P&L=₹{}", symbol, reason,
                String.format("%.2f", pnl.doubleValue()));

        if (reason.contains("TIME_STOP") || reason.contains("GLOBAL")) {
            log.warn("[HANDLER] Weak momentum → EXIT: {} closed by time stop", symbol);
        }
    }

    private void releaseStrategyLock(String symbol, String strategy) {
        if (strategy != null && strategy.startsWith("SCALP")) {
            scalpStrategy.onSignalClosed(symbol);
        } else if (strategy != null && strategy.startsWith("MARKET_PRESSURE")) {
            pressureEngine.onSignalClosed(symbol);
        } else {
            scpsStrategy.onSignalClosed(symbol);
        }
    }

    public int getOpenTradeCount() { return openTrades.size(); }
}