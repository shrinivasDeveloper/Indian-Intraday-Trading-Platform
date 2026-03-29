package com.trading.risk.service;

import com.trading.events.ProbabilityScoreEvent;
import com.trading.events.TradeApprovedEvent;
import com.trading.papertrading.service.PaperTradeManagementService;
import com.trading.position.service.PositionSizerService;
import com.trading.sector.service.SectorClassificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RiskManagementService — 10-2-3 Slot Manager implementation.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * GATE STRUCTURE (applied in order — first failure rejects the signal):
 *
 *   Gate 1  — Circuit Breaker       (daily count, P&L caps, profit lock)
 *   Gate 2  — Sector Limit          (max 2 active trades per Nifty sector)
 *   Gate 3  — Strategy Diversity    (max 2 trades per strategy name per day)
 *   Gate 4  — Phase-1 Concurrency   (max 3 simultaneous full-risk trades)
 *             A 4th trade is only allowed if at least one existing trade
 *             has migrated to Phase-2 (Breakeven) or beyond.
 *   Gate 5  — Valid entry / SL
 *   Gate 6  — Position sizing (1% risk rule + margin check)
 *
 * ═══════════════════════════════════════════════════════════════════════
 * COUNTERS (all reset daily at 8:45 IST):
 *
 *   sectorExposure    Map<sector, openCount>
 *                     Incremented on trade entry, decremented on close.
 *
 *   strategyExposure  Map<strategyName, openCount>
 *                     Tracks *daily* trades per strategy (not just open ones).
 *                     Counts entries, not just open positions — once 2 trades
 *                     have been taken by a strategy today, no more regardless
 *                     of how many are still open.
 *                     Decrements on close so the count reflects active exposure.
 *
 *   phase1Count       AtomicInteger — currently open trades in Phase-1.
 *                     Incremented on entry, decremented when:
 *                       (a) trade migrates to Phase-2 via notifyPhase2Migration()
 *                       (b) trade closes (if it never reached Phase-2)
 *
 * ═══════════════════════════════════════════════════════════════════════
 * PHASE-1 CONCURRENCY (Gate 4):
 *
 *   PaperTradeManagementService.notifyPhase2Migration(symbol) is called
 *   by moveSlToBreakeven() to decrement phase1Count when a trade moves
 *   to Phase-2. This is the contract between the two services.
 *
 *   The 4th trade check:
 *     if phase1Count >= maxPhase1Concurrent (3):
 *         check paperManagement.isAnyTradeAtBreakevenOrBeyond()
 *         if NO → reject
 *         if YES → allow (one slot is effectively "safer")
 *
 * ═══════════════════════════════════════════════════════════════════════
 * PRESERVED FROM ORIGINAL:
 *   - onTradeClosed() releases both sector and strategy slots
 *   - timeStopMinutes pipeline (passes through to TradeApprovedEvent)
 *   - All daily reset logic
 */
@Service
@Slf4j
public class RiskManagementService {

    private final ApplicationEventPublisher    publisher;
    private final CircuitBreakerService        circuitBreaker;
    private final PositionSizerService         positionSizer;
    private final SectorClassificationService  sectorClassify;
    private final PaperTradeManagementService  paperManagement;

    // @Lazy on paperManagement to break the circular dependency:
    // RiskManagementService ← PaperTradeExecutionService ← PaperTradeManagementService → RiskManagementService
    public RiskManagementService(
            ApplicationEventPublisher publisher,
            CircuitBreakerService circuitBreaker,
            PositionSizerService positionSizer,
            SectorClassificationService sectorClassify,
            @Lazy PaperTradeManagementService paperManagement) {
        this.publisher       = publisher;
        this.circuitBreaker  = circuitBreaker;
        this.positionSizer   = positionSizer;
        this.sectorClassify  = sectorClassify;
        this.paperManagement = paperManagement;
    }

    @Value("${trading.capital:100000}")
    private String capitalStr;

    // ── 10-2-3 Slot Manager config ─────────────────────────────────────────────

    /** REQ 1b: Max trades per strategy name per day. Default 2. */
    @Value("${trading.max-trades-per-strategy:2}")
    private int maxTradesPerStrategy;

    /** REQ 1c: Max concurrent Phase-1 (full-risk) trades. Default 3. */
    @Value("${trading.max-phase1-concurrent:3}")
    private int maxPhase1Concurrent;

    /** REQ 1d: Max concurrent trades per Nifty sector. Default 2. */
    @Value("${trading.max-sector-trades:2}")
    private int maxSectorTrades;

    // ── Counters ───────────────────────────────────────────────────────────────

    /** sector → number of currently open trades in that sector */
    private final Map<String, Integer> sectorExposure   = new ConcurrentHashMap<>();

    /**
     * strategyName → number of trades taken by that strategy today (open + closed).
     * We track daily count (not just open) to enforce the diversity rule:
     * "Max 2 trades per strategy per day" regardless of whether they are still open.
     */
    private final Map<String, Integer> strategyExposure = new ConcurrentHashMap<>();

    /**
     * Number of currently open trades still in Phase-1 (full risk, SL not at breakeven).
     * Decremented when a trade migrates to Phase-2 via notifyPhase2Migration().
     */
    private final AtomicInteger phase1Count = new AtomicInteger(0);

    private BigDecimal capital() { return new BigDecimal(capitalStr); }

    // ══════════════════════════════════════════════════════════════════════════
    // Gate every signal through all 6 checks
    // ══════════════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tradingExecutor")
    public void onProbabilityScore(ProbabilityScoreEvent event) {
        if (!"EXECUTE".equals(event.getDecision())) return;

        String     sym      = event.getTradingSymbol();
        String     strategy = event.getStrategyName() != null
                ? event.getStrategyName() : "UNKNOWN";
        BigDecimal cap      = capital();

        // ── Gate 1: Circuit Breaker ────────────────────────────────────────────
        CircuitBreakerService.Permission perm = circuitBreaker.checkPermission(cap);
        if (!perm.isAllowed()) {
            log.warn("[RISK] REJECTED {}: CB — {}", sym, perm.reason());
            return;
        }

        // ── Gate 2: Sector Limit (max 2 per sector) ────────────────────────────
        String sector = sectorClassify.getSector(sym);
        int    currentSectorCount = sectorExposure.getOrDefault(sector, 0);
        if (currentSectorCount >= maxSectorTrades) {
            log.warn("[RISK] REJECTED {}: sector '{}' already has {}/{} trades",
                    sym, sector, currentSectorCount, maxSectorTrades);
            return;
        }

        // ── Gate 3: Strategy Diversity (max 2 per strategy per day) ───────────
        int currentStrategyCount = strategyExposure.getOrDefault(strategy, 0);
        if (currentStrategyCount >= maxTradesPerStrategy) {
            log.warn("[RISK] REJECTED {}: strategy '{}' already has {}/{} trades today",
                    sym, strategy, currentStrategyCount, maxTradesPerStrategy);
            return;
        }

        // ── Gate 4: Phase-1 Concurrency (max 3 full-risk, 4th needs a Phase-2) ─
        int currentPhase1 = phase1Count.get();
        if (currentPhase1 >= maxPhase1Concurrent) {
            // A 4th trade is only allowed if at least one existing trade has
            // already migrated to Phase-2 (slAtBreakeven=true), meaning its
            // effective risk is ₹0 — freeing up one "risk slot."
            boolean hasBreakevenTrade = paperManagement.isAnyTradeAtBreakevenOrBeyond();
            if (!hasBreakevenTrade) {
                log.warn("[RISK] REJECTED {}: phase1Count={}/{} and no trade at breakeven yet. " +
                                "Wait for one of the {} open trades to reach 1.5R.",
                        sym, currentPhase1, maxPhase1Concurrent, currentPhase1);
                return;
            }
            log.info("[RISK] Gate 4 PASSED {}: phase1Count={} >= {} BUT a trade is at breakeven — " +
                            "allowing 4th entry.",
                    sym, currentPhase1, maxPhase1Concurrent);
        }

        // ── Gate 5: Valid entry and SL ─────────────────────────────────────────
        if (event.getEntryPrice() == null
                || event.getEntryPrice().compareTo(BigDecimal.ZERO) == 0
                || event.getStopLoss() == null
                || event.getStopLoss().compareTo(BigDecimal.ZERO) == 0) {
            log.warn("[RISK] REJECTED {}: entry or SL is zero/null", sym);
            return;
        }

        // ── Gate 6: Position sizing (1% risk rule + margin check) ─────────────
        PositionSizerService.PositionSize size = positionSizer.calculate(
                cap, event.getEntryPrice(), event.getStopLoss(),
                sym, event.getDirection().name());
        if (!size.isValid()) {
            log.warn("[RISK] REJECTED {}: sizing — {}", sym, size.invalidReason());
            return;
        }

        // ── All gates passed — commit counters and publish ─────────────────────
        sectorExposure.merge(sector, 1, Integer::sum);
        strategyExposure.merge(strategy, 1, Integer::sum);
        phase1Count.incrementAndGet();
        circuitBreaker.recordTradeEntered();

        publisher.publishEvent(new TradeApprovedEvent(this,
                sym, event.getInstrumentToken(),
                event.getDirection(), event.getEntryPrice(),
                event.getStopLoss(), event.getTarget(),
                size.quantity(), size.actualRisk(),
                event.getTotalScore(), event.getStrategyName(),
                event.getTimeStopMinutes()));

        log.info("[RISK] APPROVED: {} strategy={} sector={} dir={} qty={} entry={} sl={} " +
                        "target={} sectorCount={}/{} stratCount={}/{} phase1={}/{} timeStop={}",
                sym, strategy, sector, event.getDirection(), size.quantity(),
                event.getEntryPrice(), event.getStopLoss(), event.getTarget(),
                sectorExposure.getOrDefault(sector, 0), maxSectorTrades,
                strategyExposure.getOrDefault(strategy, 0), maxTradesPerStrategy,
                phase1Count.get(), maxPhase1Concurrent,
                event.getTimeStopMinutes() > 0 ? event.getTimeStopMinutes() + "min" : "none");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Called by PaperTradeExecutionService on every trade close
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Releases all exposure slots and records P&L to the circuit breaker.
     * Also decrements phase1Count if the trade never migrated to Phase-2.
     *
     * @param symbol    the closed symbol
     * @param pnl       net P&L (after brokerage)
     * @param strategy  strategy name — needed to release strategy slot
     * @param reachedPhase2  true if trade was at breakeven+ when closed,
     *                       false if it closed while still in Phase-1
     */
    public void onTradeClosed(String symbol, BigDecimal pnl,
                              String strategy, boolean reachedPhase2) {
        circuitBreaker.recordPnl(pnl);

        // Release sector slot
        String sector = sectorClassify.getSector(symbol);
        sectorExposure.merge(sector, -1, (a, b) -> Math.max(0, a + b));

        // Release strategy slot
        if (strategy != null) {
            strategyExposure.merge(strategy, -1, (a, b) -> Math.max(0, a + b));
        }

        // Decrement phase1Count only if the trade was still in Phase-1 when closed
        // (i.e. never reached breakeven). If it reached Phase-2, phase1Count was
        // already decremented by notifyPhase2Migration() at the time of migration.
        if (!reachedPhase2) {
            phase1Count.updateAndGet(v -> Math.max(0, v - 1));
        }

        log.debug("[RISK] Closed {}: sector='{}' released, strategy='{}' released, " +
                        "reachedPhase2={}, phase1Count={}",
                symbol, sector, strategy, reachedPhase2, phase1Count.get());
    }

    /**
     * Backward-compatible overload for callers that don't yet pass strategy/phase2 info.
     * Assumes the trade was in Phase-1 (conservative — always decrements phase1Count).
     */
    public void onTradeClosed(String symbol, BigDecimal pnl) {
        onTradeClosed(symbol, pnl, null, false);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Called by PaperTradeManagementService when a trade moves to Phase-2
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Decrements phase1Count when a trade migrates from Phase-1 → Phase-2
     * (SL moved to breakeven). This immediately frees a Phase-1 concurrency slot,
     * potentially allowing the 4th trade to enter.
     */
    public void notifyPhase2Migration(String symbol) {
        int prev = phase1Count.getAndUpdate(v -> Math.max(0, v - 1));
        log.info("[RISK] {} migrated to Phase-2 (breakeven). phase1Count: {} → {}",
                symbol, prev, phase1Count.get());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Dashboard helpers
    // ══════════════════════════════════════════════════════════════════════════

    public Map<String, Integer> getSectorExposure()   { return java.util.Collections.unmodifiableMap(sectorExposure); }
    public Map<String, Integer> getStrategyExposure() { return java.util.Collections.unmodifiableMap(strategyExposure); }
    public int                  getPhase1Count()      { return phase1Count.get(); }
    public int                  getMaxPhase1()        { return maxPhase1Concurrent; }

    // ══════════════════════════════════════════════════════════════════════════
    // Daily reset at 8:45 IST
    // ══════════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 45 8 * * MON-FRI", zone = "Asia/Kolkata")
    public void resetDaily() {
        sectorExposure.clear();
        strategyExposure.clear();
        phase1Count.set(0);
        log.info("[RISK] Daily reset — sectorExposure, strategyExposure, phase1Count cleared");
    }
}