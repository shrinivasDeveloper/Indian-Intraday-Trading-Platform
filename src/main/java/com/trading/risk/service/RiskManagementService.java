package com.trading.risk.service;

import com.trading.papertrading.service.PaperTradeManagementService;
import com.trading.sector.service.SectorClassificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * RiskManagementService — 10-2-3 Slot Manager.
 *
 * This service manages concurrency slot counters for the paper trading pipeline.
 * The former StrategyEvaluatorService (which published ProbabilityScoreEvent and
 * fired onProbabilityScore) has been removed along with all strategy classes
 * (ORBStrategy, RangeBreakoutStrategy, PullbackDetectionService, AutoModeStrategy,
 * SevenGateScannerService, PatternDetectionService, KeyLevelService,
 * ProbabilityEngine, BacktestController, BacktestJobService).
 *
 * Current responsibilities:
 *   - Slot counter management (phase1Count, sectorExposure, strategyExposure)
 *   - onTradeClosed()         — called by PaperTradeExecutionService on every close
 *   - notifyPhase2Migration() — called by PaperTradeManagementService when SL → BE
 *   - Dashboard helpers       — read by DashboardController
 *
 * The TradeApprovedEvent listener in PaperTradeExecutionService is still wired
 * and will activate automatically when a new strategy engine is added that
 * publishes TradeApprovedEvent. No changes to execution services are needed.
 */
@Service
@Slf4j
public class RiskManagementService {

    private final CircuitBreakerService       circuitBreaker;
    private final SectorClassificationService sectorClassify;
    private final PaperTradeManagementService paperManagement;

    public RiskManagementService(
            CircuitBreakerService circuitBreaker,
            SectorClassificationService sectorClassify,
            @Lazy PaperTradeManagementService paperManagement) {
        this.circuitBreaker  = circuitBreaker;
        this.sectorClassify  = sectorClassify;
        this.paperManagement = paperManagement;
    }

    @Value("${trading.max-phase1-concurrent:3}")
    private int maxPhase1Concurrent;

    private final Map<String, Integer> sectorExposure   = new ConcurrentHashMap<>();
    private final Map<String, Integer> strategyExposure = new ConcurrentHashMap<>();
    private final AtomicInteger        phase1Count      = new AtomicInteger(0);

    private final ReentrantLock slotLock = new ReentrantLock(true);

    // ══════════════════════════════════════════════════════════════════════════
    // Phase-2 Migration callback — called by PaperTradeManagementService
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Called when a paper trade's SL migrates to breakeven (Phase-2).
     * Decrements the phase1Count so a new trade can potentially be opened.
     * This is the 10-2-3 slot manager's "free slot on breakeven" mechanic.
     *
     * @param symbol the trading symbol that reached Phase-2
     */
    public void notifyPhase2Migration(String symbol) {
        slotLock.lock();
        try {
            int prev = phase1Count.getAndUpdate(v -> Math.max(0, v - 1));
            log.info("[RISK] {} → Phase-2. phase1Count: {} → {}", symbol, prev, phase1Count.get());
        } finally {
            slotLock.unlock();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Trade close callback — called by PaperTradeExecutionService
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Records P&L with CircuitBreakerService and releases all slot counters.
     *
     * @param symbol         trading symbol
     * @param pnl            net P&L of the closed trade
     * @param strategy       strategy name (used to decrement strategyExposure)
     * @param reachedPhase2  if true, phase1Count was already decremented at
     *                       migration time — do NOT decrement again to avoid
     *                       the counter going negative
     */
    public void onTradeClosed(String symbol, BigDecimal pnl,
                              String strategy, boolean reachedPhase2) {
        circuitBreaker.recordPnl(pnl);
        slotLock.lock();
        try {
            String sector = sectorClassify.getSector(symbol);
            sectorExposure.merge(sector,   -1, (a, b) -> Math.max(0, a + b));
            if (strategy != null) {
                strategyExposure.merge(strategy, -1, (a, b) -> Math.max(0, a + b));
            }
            if (!reachedPhase2) {
                phase1Count.updateAndGet(v -> Math.max(0, v - 1));
            }
            log.debug("[RISK] Closed {}: sector/strat released, reachedPhase2={}, phase1={}",
                    symbol, reachedPhase2, phase1Count.get());
        } finally {
            slotLock.unlock();
        }
    }

    /**
     * Backward-compatible overload — assumes Phase-1 close (decrements phase1Count).
     * Used by TradeExecutionService (live mode) and any legacy callers.
     */
    public void onTradeClosed(String symbol, BigDecimal pnl) {
        onTradeClosed(symbol, pnl, null, false);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Dashboard helpers
    // ══════════════════════════════════════════════════════════════════════════

    public Map<String, Integer> getSectorExposure()   { return Collections.unmodifiableMap(sectorExposure); }
    public Map<String, Integer> getStrategyExposure() { return Collections.unmodifiableMap(strategyExposure); }
    public int                  getPhase1Count()      { return phase1Count.get(); }
    public int                  getMaxPhase1()        { return maxPhase1Concurrent; }

    // ══════════════════════════════════════════════════════════════════════════
    // Daily reset
    // ══════════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 45 8 * * MON-FRI", zone = "Asia/Kolkata")
    public void resetDaily() {
        slotLock.lock();
        try {
            sectorExposure.clear();
            strategyExposure.clear();
            phase1Count.set(0);
            log.info("[RISK] Daily reset — all slot counters cleared");
        } finally {
            slotLock.unlock();
        }
    }
}