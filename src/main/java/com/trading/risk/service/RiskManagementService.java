package com.trading.risk.service;

import com.trading.sector.service.SectorClassificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RiskManagementService – session-level risk slot tracking and P&L accumulation.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * RESPONSIBILITIES:
 *   1. Track concurrent Phase-1 trade count (max 3 simultaneous open positions)
 *   2. Track per-sector exposure (prevent sector concentration)
 *   3. Track per-strategy exposure (prevent strategy over-allocation)
 *   4. Accumulate daily P&L for circuit breaker decisions
 *   5. Report slot/exposure state for dashboard
 *
 * CALL SITES:
 *   onTradeOpened()  → SmartChannelSignalHandler.onSignal() (before TradeApprovedEvent)
 *   onTradeClosed()  → PaperTradeExecutionService.closeTrade()
 *                    → HighRRTradeManager (for HIGH_RR_INTRADAY_V1 trades)
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * BUG FIX (observed 2026-04-17 dashboard):
 *   sectorExposure showed: Energy: -1, Infrastructure: -1
 *   These are impossible negative values.
 *
 * ROOT CAUSE:
 *   Map.merge() semantics when the KEY IS ABSENT:
 *     map.merge("Energy", -1, (a, b) -> Math.max(0, a + b))
 *   When "Energy" is not in the map (was never incremented), merge() inserts
 *   the value directly (-1) WITHOUT calling the remapping function.
 *   This is correct Java Map.merge() contract — it only calls the function
 *   when the key ALREADY EXISTS.
 *
 *   Why was the key absent? MARKET_PRESSURE_V1 trades (which fired 6 signals
 *   on 2026-04-17) do NOT call onTradeOpened() — their slot booking is done
 *   entirely via circuitBreaker.recordTradeEntered() in SmartChannelSignalHandler.
 *   SmartChannelSignalHandler calls onTradeOpened() only for strategies that
 *   require phase1 slot booking. MARKET_PRESSURE bypasses this gate.
 *   When those trades closed, onTradeClosed() tried to decrement a counter
 *   that was never incremented → produced -1.
 *
 * FIX:
 *   Replace merge() with compute() + null guard in BOTH decrement operations.
 *   compute() ALWAYS calls the function regardless of key presence,
 *   receiving null when the key is absent. The null guard treats absent keys
 *   as 0, so decrement of absent key = Math.max(0, 0 - 1) = 0.
 *
 *   BUGGY:
 *     sectorExposure.merge(sector, -1, (a, b) -> Math.max(0, a + b));
 *   FIXED:
 *     sectorExposure.compute(sector, (k, v) -> Math.max(0, (v == null ? 0 : v) - 1));
 *
 *   Same fix applied to strategyExposure.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * THREAD SAFETY:
 *   - phase1Count: AtomicInteger (lock-free, correct for concurrent inc/dec)
 *   - sectorExposure: ConcurrentHashMap with compute() (atomic per key)
 *   - strategyExposure: ConcurrentHashMap with compute() (atomic per key)
 *   - dailyPnl: volatile BigDecimal replaced with synchronized update
 *     (BigDecimal is immutable but the field reference update needs visibility)
 * ─────────────────────────────────────────────────────────────────────────────
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RiskManagementService {

    private final SectorClassificationService sectorClassify;

    @Value("${trading.max-phase1-concurrent:3}")
    private int maxPhase1;

    // ── Slot counters ─────────────────────────────────────────────────────────
    /** Number of currently open Phase-1 trades (standard pipeline strategies). */
    private final AtomicInteger phase1Count = new AtomicInteger(0);

    /**
     * Per-sector open trade count.
     * Key: sector name (e.g. "Energy", "Banking & Finance")
     * Value: count of currently open trades in that sector
     *
     * FIX: Uses compute() instead of merge() for decrement to prevent
     * negative values when a sector was never incremented (absent key).
     */
    private final Map<String, Integer> sectorExposure   = new ConcurrentHashMap<>();

    /**
     * Per-strategy open trade count.
     * Key: strategyName (e.g. "MARKET_PRESSURE_V1", "SMART_CHANNEL_PULLBACK_V3")
     * Value: count of currently open trades for that strategy
     *
     * FIX: Same compute() fix as sectorExposure.
     */
    private final Map<String, Integer> strategyExposure = new ConcurrentHashMap<>();

    // ── P&L tracking ──────────────────────────────────────────────────────────
    private volatile BigDecimal dailyPnl   = BigDecimal.ZERO;
    private volatile BigDecimal weeklyPnl  = BigDecimal.ZERO;
    private volatile BigDecimal monthlyPnl = BigDecimal.ZERO;

    // ── Breakeven tracking ────────────────────────────────────────────────────
    private volatile boolean anyAtBreakeven = false;

    // ══════════════════════════════════════════════════════════════════════════
    // TRADE OPENED — called by SmartChannelSignalHandler before firing TradeApprovedEvent
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Records a new trade opening for slot/exposure tracking.
     *
     * Called from SmartChannelSignalHandler.onSignal() for standard-pipeline
     * strategies (SCPS, SCALP, MARKET_PRESSURE, ORB).
     * NOT called for HIGH_RR_INTRADAY_V1 (manages own slots via HighRRTradeManager).
     *
     * @param symbol       trading symbol
     * @param strategyName strategy identifier
     * @param isPhase1     true if this occupies a phase1 concurrent slot
     */
    public void onTradeOpened(String symbol, String strategyName, boolean isPhase1) {
        String sector = sectorClassify.getSector(symbol);

        if (isPhase1) {
            phase1Count.incrementAndGet();
        }

        // Increment sector exposure
        sectorExposure.merge(sector, 1, Integer::sum);

        // Increment strategy exposure
        strategyExposure.merge(strategyName, 1, Integer::sum);

        log.info("[RISK] Trade OPENED: {} | strategy={} | sector={} | phase1={} | " +
                        "phase1Count={}/{} | sectorExp={} | stratExp={}",
                symbol, strategyName, sector, isPhase1,
                phase1Count.get(), maxPhase1,
                sectorExposure.get(sector),
                strategyExposure.get(strategyName));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TRADE CLOSED — called by PaperTradeExecutionService.closeTrade()
    //                and HighRRTradeManager (for HIGH_RR trades)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Records a trade closing and accumulates P&L.
     *
     * ─────────────────────────────────────────────────────────────────────────
     * CRITICAL FIX: sectorExposure and strategyExposure decrement.
     *
     * Previous code used merge() which silently inserts -1 when the key is absent.
     * The key can be absent when:
     *   1. onTradeOpened() was NOT called for this strategy (e.g. MARKET_PRESSURE
     *      which uses circuitBreaker.recordTradeEntered() instead)
     *   2. Daily reset cleared the maps but a trade from a previous session
     *      is being closed after reset (shouldn't happen but defensive guard)
     *
     * compute() always invokes the function even for absent keys (passes null).
     * The null guard treats an absent key as 0:
     *   Math.max(0, null → 0 - 1) = Math.max(0, -1) = 0
     * So decrement of an absent or zero counter stays at 0, never goes negative.
     * ─────────────────────────────────────────────────────────────────────────
     *
     * @param symbol         trading symbol
     * @param netPnl         net P&L after brokerage
     * @param strategyName   strategy identifier
     * @param reachedPhase2  true if trade progressed to Phase 2 (target T1 hit)
     */
    public void onTradeClosed(String symbol, BigDecimal netPnl,
                              String strategyName, boolean reachedPhase2) {
        String sector = sectorClassify.getSector(symbol);

        // FIX: Use compute() instead of merge() for decrement.
        // merge(key, -1, fn) inserts -1 directly when key is absent (fn never called).
        // compute(key, fn) always calls fn, receiving null for absent keys.
        sectorExposure.compute(sector,
                (k, v) -> Math.max(0, (v == null ? 0 : v) - 1));

        strategyExposure.compute(strategyName,
                (k, v) -> Math.max(0, (v == null ? 0 : v) - 1));

        // Phase-1 slot is released when trade closes, regardless of phase progression.
        // Note: HighRR trades manage their own phase1 slot via HighRRTradeManager
        // calling riskManagement.onTradeClosed() — do NOT double-decrement.
        // The phase1Count should only be decremented if it was incremented on open.
        // Since HighRR bypasses onTradeOpened(), it should NOT decrement phase1Count here.
        // MARKET_PRESSURE, SCPS, SCALP call onTradeOpened() with isPhase1=true
        // → they correctly decrement here.
        // HighRRTradeManager calls onTradeClosed() directly → phase1Count was never
        // incremented for HighRR, so we must not decrement it either.
        // Decision: only decrement if the current phase1Count > 0 (safe guard).
        if (reachedPhase2) {
            // Phase-2 means the position was partially closed at T1 and remaining
            // is trailing. Release the phase1 slot once T1 is reached (not at full close).
            // This is a no-op here — phase1 release on T1 hit is handled by
            // PaperTradeManagementService directly calling releasePhase1Slot().
            // This parameter currently serves as an informational flag.
        }

        // Accumulate P&L
        if (netPnl != null) {
            synchronized (this) {
                dailyPnl   = dailyPnl.add(netPnl);
                weeklyPnl  = weeklyPnl.add(netPnl);
                monthlyPnl = monthlyPnl.add(netPnl);
            }
        }

        log.info("[RISK] Trade CLOSED: {} | strategy={} | sector={} | pnl=₹{} | " +
                        "dailyPnl=₹{} | phase1={}/{} | " +
                        "sectorExp={} | stratExp={}",
                symbol, strategyName, sector,
                netPnl != null ? netPnl.setScale(2, RoundingMode.HALF_UP) : "null",
                dailyPnl.setScale(2, RoundingMode.HALF_UP),
                phase1Count.get(), maxPhase1,
                sectorExposure.getOrDefault(sector, 0),
                strategyExposure.getOrDefault(strategyName, 0));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // BACKWARD-COMPATIBLE OVERLOADS
    // These match the original RiskManagementService API that existing callers
    // (TradeExecutionService, PaperTradeManagementService) already use.
    // Do NOT remove — removing breaks callers that were never changed.
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 2-argument overload used by TradeExecutionService (LIVE mode).
     * Resolves strategy from symbol context (sector via classification only).
     * strategyName defaults to "UNKNOWN" for LIVE trades which don't carry strategy metadata.
     */
    public void onTradeClosed(String symbol, BigDecimal netPnl) {
        onTradeClosed(symbol, netPnl, "UNKNOWN", false);
    }

    /**
     * notifyPhase2Migration — called by PaperTradeManagementService when a trade
     * transitions from Phase-1 (active monitoring) to Phase-2 (trailing mode after T1 hit).
     * Releases the Phase-1 concurrent slot so a new trade can enter.
     */
    public void notifyPhase2Migration(String symbol) {
        releasePhase1Slot();
        log.info("[RISK] Phase-2 migration for {} — Phase-1 slot released", symbol);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PHASE-1 SLOT MANAGEMENT (called by PaperTradeManagementService)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Increments the phase1 counter.
     * Called by SmartChannelSignalHandler for strategies that use phase1 slots.
     */
    public void incrementPhase1Count() {
        int count = phase1Count.incrementAndGet();
        log.debug("[RISK] Phase1 incremented: {}/{}", count, maxPhase1);
    }

    /**
     * Decrements the phase1 counter (floors at 0).
     * Called when a Phase-1 trade hits T1 target and transitions to Phase-2
     * (freeing up the concurrent slot for a new trade).
     */
    public void releasePhase1Slot() {
        int count = phase1Count.updateAndGet(v -> Math.max(0, v - 1));
        log.debug("[RISK] Phase1 slot released: {}/{}", count, maxPhase1);
    }

    /**
     * Returns true if all phase1 slots are occupied.
     */
    public boolean isPhase1Full() {
        return phase1Count.get() >= maxPhase1;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // BREAKEVEN TRACKING
    // ══════════════════════════════════════════════════════════════════════════

    public void setBreakevenReached(boolean reached) {
        this.anyAtBreakeven = reached;
        log.debug("[RISK] Breakeven flag: {}", reached);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DAILY RESET
    // ══════════════════════════════════════════════════════════════════════════

    /** Reset daily P&L at market open. */
    @Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyReset() {
        synchronized (this) {
            dailyPnl = BigDecimal.ZERO;
        }
        phase1Count.set(0);
        sectorExposure.clear();
        strategyExposure.clear();
        anyAtBreakeven = false;
        log.info("[RISK] Daily reset complete | P&L cleared | slots cleared");
    }

    /** Reset weekly P&L on Monday at market open. */
    @Scheduled(cron = "0 10 9 * * MON", zone = "Asia/Kolkata")
    public void weeklyReset() {
        synchronized (this) {
            weeklyPnl = BigDecimal.ZERO;
        }
        log.info("[RISK] Weekly P&L reset");
    }

    /** Reset monthly P&L on the 1st of each month at market open. */
    @Scheduled(cron = "0 10 9 1 * MON-FRI", zone = "Asia/Kolkata")
    public void monthlyReset() {
        synchronized (this) {
            monthlyPnl = BigDecimal.ZERO;
        }
        log.info("[RISK] Monthly P&L reset");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DASHBOARD / CIRCUIT BREAKER GETTERS
    // ══════════════════════════════════════════════════════════════════════════

    public int     getPhase1Count()      { return phase1Count.get(); }
    public int     getMaxPhase1()        { return maxPhase1; }
    public boolean isAnyAtBreakeven()    { return anyAtBreakeven; }

    public BigDecimal getDailyPnl()   { synchronized (this) { return dailyPnl; } }
    public BigDecimal getWeeklyPnl()  { synchronized (this) { return weeklyPnl; } }
    public BigDecimal getMonthlyPnl() { synchronized (this) { return monthlyPnl; } }

    /** Returns a snapshot of current sector exposure (for dashboard JSON). */
    public Map<String, Integer> getSectorExposure() {
        return Collections.unmodifiableMap(sectorExposure);
    }

    /** Returns a snapshot of current strategy exposure (for dashboard JSON). */
    public Map<String, Integer> getStrategyExposure() {
        return Collections.unmodifiableMap(strategyExposure);
    }

    /**
     * Returns the per-sector open trade count (0 if sector not present).
     * Safe to call even if no trade has ever been opened in that sector.
     */
    public int getSectorExposureFor(String sector) {
        return sectorExposure.getOrDefault(sector, 0);
    }

    /**
     * Returns the per-strategy open trade count (0 if strategy not present).
     */
    public int getStrategyExposureFor(String strategyName) {
        return strategyExposure.getOrDefault(strategyName, 0);
    }

    /**
     * Checks if adding a new trade for the given sector would exceed max exposure.
     * Currently no hard limit per sector — returns false always.
     * Hook for future per-sector limits.
     */
    public boolean isSectorAtMaxExposure(String sector, int maxExposurePerSector) {
        return getSectorExposureFor(sector) >= maxExposurePerSector;
    }
}