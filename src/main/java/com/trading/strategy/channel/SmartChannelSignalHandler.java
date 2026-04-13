package com.trading.strategy.channel;

import com.trading.events.SmartChannelPullbackSignalEvent;
import com.trading.events.TradeApprovedEvent;
import com.trading.events.TradeExecutionResultEvent;
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
 * SmartChannelSignalHandler — bridges SmartChannelPullbackStrategy signals
 * to the existing trade execution pipeline.
 *
 * PIPELINE:
 *   SmartChannelPullbackStrategy
 *     → SmartChannelPullbackSignalEvent  (published)
 *     → SmartChannelSignalHandler        (this class — gates check)
 *       ├─ Stale signal guard (>30s → reject)
 *       ├─ Circuit breaker check
 *       ├─ Slot manager check (phase1Count < maxPhase1Concurrent)
 *       └─ TradeApprovedEvent            (published if all gates pass)
 *         → PaperTradeExecutionService   (PAPER mode)
 *         → TradeExecutionService        (LIVE mode)
 *
 * CLOSE CALLBACK:
 *   Listens for TradeExecutionResultEvent to:
 *     1. Release phase1 slot / notify RiskManagementService
 *     2. Notify SmartChannelPullbackStrategy to release active signal lock
 *
 * LOGGING:
 *   [INFO]  Signal received, approved, rejected
 *   [WARN]  Stale signal, slot limit, CB active
 *   [DEBUG] Gate checks, slot counts
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SmartChannelSignalHandler {

    private static final String STRATEGY_NAME = "SMART_CHANNEL_PULLBACK_V2";
    // Reject signals older than 30 seconds
    private static final long STALE_THRESHOLD_SEC = 30;

    private final ApplicationEventPublisher     publisher;
    private final CircuitBreakerService         circuitBreaker;
    private final RiskManagementService         riskManagement;
    private final SectorClassificationService   sectorClassify;
    private final SmartChannelPullbackStrategy  strategy;

    @Value("${trading.max-phase1-concurrent:3}")
    private int maxPhase1Concurrent;

    @Value("${trading.mode:PAPER}")
    private String tradingMode;

    @Value("${trading.capital:100000}")
    private BigDecimal capital;

    // Track open trades from this strategy: symbol → entryTime
    private final Map<String, Instant> openTrades = new ConcurrentHashMap<>();

    // ─────────────────────────────────────────────────────────────────────
    // Signal reception
    // ─────────────────────────────────────────────────────────────────────

    @EventListener
    @Async("tradingExecutor")
    public void onSignal(SmartChannelPullbackSignalEvent event) {

        String symbol = event.getTradingSymbol();

        log.info("[INFO] Signal received: {} | dir={} entry={} sl={} T1={} score={} decision={}",
                symbol,
                event.getDirection(),
                event.getEntryPrice(),
                event.getStopLoss(),
                event.getTarget1(),
                event.getTotalScore(),
                event.getDecision());

        // ── GATE: Stale signal check ──────────────────────────────────────
        if (event.isStale()) {
            log.warn("[WARN] Stale signal rejected for {} (>{}s old)", symbol, STALE_THRESHOLD_SEC);
            strategy.onSignalClosed(symbol);
            return;
        }

        // ── GATE: Already have open trade for this symbol ─────────────────
        if (openTrades.containsKey(symbol)) {
            log.debug("[SCPH] {} already has open trade — skipping duplicate signal", symbol);
            return;
        }

        // ── GATE: Circuit breaker ─────────────────────────────────────────
        CircuitBreakerService.Permission cb = circuitBreaker.checkPermission(capital);
        if (!cb.isAllowed()) {
            log.warn("[WARN] Circuit breaker blocked signal for {}: {}", symbol, cb.reason());
            strategy.onSignalClosed(symbol);
            return;
        }

        // ── GATE: Phase-1 slot limit ──────────────────────────────────────
        int currentPhase1 = riskManagement.getPhase1Count();
        if (currentPhase1 >= maxPhase1Concurrent) {
            log.warn("[WARN] Phase-1 slot limit reached ({}/{}): {} blocked",
                    currentPhase1, maxPhase1Concurrent, symbol);
            strategy.onSignalClosed(symbol);
            return;
        }

        // ── All gates passed → publish TradeApprovedEvent ─────────────────
        log.info("[INFO] All gates passed for {} — publishing TradeApprovedEvent", symbol);

        // Record the trade entry in circuit breaker
        circuitBreaker.recordTradeEntered();

        TradeApprovedEvent approved = new TradeApprovedEvent(
                this,
                symbol,
                event.getInstrumentToken(),
                event.getDirection(),
                event.getEntryPrice(),
                event.getStopLoss(),
                event.getTarget1(),    // Primary target (T1)
                event.getQuantity(),
                event.getRiskAmount(),
                BigDecimal.valueOf(event.getProbabilityScore()),
                STRATEGY_NAME,
                event.getTimeStopMinutes()
        );

        openTrades.put(symbol, event.getSignalTimestamp());
        publisher.publishEvent(approved);

        log.info("[INFO] TradeApprovedEvent published: {} | qty={} | risk=₹{} | RR=2:1/3:1",
                symbol, event.getQuantity(), event.getRiskAmount());
    }

    // ─────────────────────────────────────────────────────────────────────
    // Trade close callback — release locks
    // ─────────────────────────────────────────────────────────────────────

    @EventListener
    @Async("tradingExecutor")
    public void onTradeResult(TradeExecutionResultEvent event) {
        String symbol = event.getTradingSymbol();

        if (!openTrades.containsKey(symbol)) return;
        if (!"CLOSED".equals(event.getStatus())) return;

        openTrades.remove(symbol);

        // Release active signal lock in strategy
        strategy.onSignalClosed(symbol);

        String exitReason = event.getExitReason() != null ? event.getExitReason() : "UNKNOWN";
        BigDecimal pnl    = event.getNetPnl() != null ? event.getNetPnl() : BigDecimal.ZERO;

        log.info("[INFO] Exit executed: {} | reason={} | P&L=₹{:.2f}",
                symbol, exitReason, pnl.doubleValue());

        // Log weak momentum warning if time-stopped
        if (exitReason.contains("TIME_STOP") || exitReason.contains("GLOBAL")) {
            log.warn("[WARN] Weak momentum → EXIT: {} closed by time stop", symbol);
        }
    }

    public int getOpenTradeCount() { return openTrades.size(); }
}