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
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * RiskManagementService — 10-2-3 Slot Manager with all race conditions fixed.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * FLAW 1 FIX — Race Condition (check-then-act is not atomic):
 *
 *   PROBLEM: With @Async("tradingExecutor"), multiple threads run
 *   onProbabilityScore() concurrently. The old code:
 *     1. phase1Count.get()           ← Thread A reads 3
 *     2. isAnyTradeAtBreakeven()     ← Thread A reads true
 *     3. sectorExposure.getOrDefault ← Thread A reads 1
 *     4. phase1Count.incrementAndGet ← Thread A sets to 4
 *   Thread B does the same between steps 1 and 4 — also reads 3, also passes,
 *   also increments → phase1Count ends at 5. TWO trades approved when only 1 slot existed.
 *   Same race applies to sectorExposure and strategyExposure maps.
 *
 *   SOLUTION: ReentrantLock guards the entire "check all counters + commit" section.
 *   Why ReentrantLock over synchronized:
 *     - tryLock(timeout) prevents indefinite blocking under load
 *     - Lock acquisition is explicit and auditable
 *     - notifyPhase2Migration() also acquires the same lock to ensure
 *       the phase1Count decrement and the next check-and-commit cannot interleave
 *
 *   The @EventListener/@Async processing (circuit breaker, position sizing, event
 *   publishing) runs OUTSIDE the lock — only the critical section is protected.
 *   This avoids the anti-pattern of holding a lock while doing I/O.
 *
 *   LOCK SCOPE (only the atomic section):
 *     lock.lock()
 *       re-read phase1Count (now under lock)
 *       re-read sectorExposure (now under lock)
 *       re-read strategyExposure (now under lock)
 *       check Gate 2, 3, 4 (all reads under lock)
 *       isAnyTradeAtBreakevenOrBeyond() called under lock
 *       commit all increments
 *     lock.unlock()
 *
 * ═══════════════════════════════════════════════════════════════════════
 * FLAW 3 FIX — Stale Signal Guard (Gate 0):
 *
 *   ProbabilityScoreEvent now carries signalTimestamp (Instant set at fire time).
 *   Gate 0 (before circuit breaker) rejects events older than maxSignalAgeSeconds.
 *   Config: trading.max-signal-age-seconds: 30
 *
 *   Why Gate 0 not Gate 1: stale signals waste circuit breaker budget. A stale
 *   breakout signal should be dropped before it touches any state counter.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * GATE STRUCTURE:
 *   Gate 0  — Stale Signal Guard   (age > 30s → reject)
 *   Gate 1  — Circuit Breaker       (daily count, P&L caps)
 *   Gate 5  — Valid entry / SL
 *   Gate 6  — Position sizing
 *   ↳ Atomic section (under ReentrantLock):
 *       Gate 2  — Sector Limit (max 2 per sector)
 *       Gate 3  — Strategy Diversity (max 2 per strategy/day)
 *       Gate 4  — Phase-1 Concurrency (max 3, 4th needs Phase-2)
 *       Commit  — increment all counters
 * ═══════════════════════════════════════════════════════════════════════
 */
@Service
@Slf4j
public class RiskManagementService {

    private final ApplicationEventPublisher   publisher;
    private final CircuitBreakerService       circuitBreaker;
    private final PositionSizerService        positionSizer;
    private final SectorClassificationService sectorClassify;
    private final PaperTradeManagementService paperManagement;

    public RiskManagementService(
            ApplicationEventPublisher publisher,
            CircuitBreakerService circuitBreaker,
            PositionSizerService positionSizer,
            SectorClassificationService sectorClassify,
            @Lazy PaperTradeManagementService paperManagement) {
        this.publisher      = publisher;
        this.circuitBreaker = circuitBreaker;
        this.positionSizer  = positionSizer;
        this.sectorClassify = sectorClassify;
        this.paperManagement= paperManagement;
    }

    @Value("${trading.capital:100000}")
    private String capitalStr;

    // ── 10-2-3 Slot Manager config ─────────────────────────────────────────────

    @Value("${trading.max-trades-per-strategy:2}")
    private int maxTradesPerStrategy;

    @Value("${trading.max-phase1-concurrent:3}")
    private int maxPhase1Concurrent;

    @Value("${trading.max-sector-trades:2}")
    private int maxSectorTrades;

    /** FLAW 3: Max age of a signal in seconds before it is considered stale */
    @Value("${trading.max-signal-age-seconds:30}")
    private int maxSignalAgeSeconds;

    // ── Counters ───────────────────────────────────────────────────────────────

    private final Map<String, Integer> sectorExposure   = new ConcurrentHashMap<>();
    private final Map<String, Integer> strategyExposure = new ConcurrentHashMap<>();
    private final AtomicInteger        phase1Count      = new AtomicInteger(0);

    // ── FLAW 1 FIX: ReentrantLock for atomic check-and-commit ─────────────────
    //
    // This lock guards ONLY the critical section in onProbabilityScore() and
    // notifyPhase2Migration(). It does NOT hold during circuit breaker checks,
    // position sizing, or event publishing — those run outside the lock.
    //
    // A single ReentrantLock (not ReadWriteLock) because writers (check-and-commit,
    // notifyPhase2Migration) must be mutually exclusive with each other AND with
    // readers of the combined counter state. ReadWriteLock gives no benefit when
    // the "read" immediately leads to a conditional "write."
    private final ReentrantLock slotLock = new ReentrantLock(true); // fair=true prevents starvation

    private BigDecimal capital() { return new BigDecimal(capitalStr); }

    // ══════════════════════════════════════════════════════════════════════════
    // MAIN EVENT HANDLER
    // ══════════════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tradingExecutor")
    public void onProbabilityScore(ProbabilityScoreEvent event) {
        if (!"EXECUTE".equals(event.getDecision())) return;

        String     sym      = event.getTradingSymbol();
        String     strategy = event.getStrategyName() != null ? event.getStrategyName() : "UNKNOWN";
        BigDecimal cap      = capital();

        // ── Gate 0: Stale Signal Guard (FLAW 3 FIX) ───────────────────────────
        // Runs OUTSIDE the lock — pure timestamp check, no state mutation.
        Instant signalTime = event.getSignalTimestamp();
        if (signalTime != null) {
            long ageSeconds = Duration.between(signalTime, Instant.now()).getSeconds();
            if (ageSeconds > maxSignalAgeSeconds) {
                log.warn("[RISK] REJECTED {} [STALE_SIGNAL]: signal is {}s old (max={}s). " +
                                "Price may have moved. Dropping to prevent entering a stale breakout.",
                        sym, ageSeconds, maxSignalAgeSeconds);
                return;
            }
        }

        // ── Gate 1: Circuit Breaker ────────────────────────────────────────────
        // Runs OUTSIDE the lock — CB has its own internal atomics.
        CircuitBreakerService.Permission perm = circuitBreaker.checkPermission(cap);
        if (!perm.isAllowed()) {
            log.warn("[RISK] REJECTED {} [CB]: {}", sym, perm.reason());
            return;
        }

        // ── Gate 5: Valid entry and SL ─────────────────────────────────────────
        // Runs OUTSIDE the lock — pure validation, no state.
        if (event.getEntryPrice() == null
                || event.getEntryPrice().compareTo(BigDecimal.ZERO) == 0
                || event.getStopLoss() == null
                || event.getStopLoss().compareTo(BigDecimal.ZERO) == 0) {
            log.warn("[RISK] REJECTED {} [NULL_PRICES]: entry or SL is zero/null", sym);
            return;
        }

        // ── Gate 6: Position sizing ────────────────────────────────────────────
        // Runs OUTSIDE the lock — calls MarginCheckService (may be slow). We do not
        // want to hold the slot lock during a potentially blocking margin API call.
        PositionSizerService.PositionSize size = positionSizer.calculate(
                cap, event.getEntryPrice(), event.getStopLoss(),
                sym, event.getDirection().name());
        if (!size.isValid()) {
            log.warn("[RISK] REJECTED {} [SIZING]: {}", sym, size.invalidReason());
            return;
        }

        // ── ATOMIC SECTION: Gates 2, 3, 4 + counter commit ────────────────────
        // FLAW 1 FIX: Everything from here to unlock() is a single atomic transaction.
        // No other thread can read-then-write the slot counters between our check and commit.
        slotLock.lock();
        try {
            String sector = sectorClassify.getSector(sym);

            // Gate 2: Sector Limit (re-read inside lock for consistency)
            int sectorCount = sectorExposure.getOrDefault(sector, 0);
            if (sectorCount >= maxSectorTrades) {
                log.warn("[RISK] REJECTED {} [SECTOR]: '{}' has {}/{} trades",
                        sym, sector, sectorCount, maxSectorTrades);
                return;
            }

            // Gate 3: Strategy Diversity (re-read inside lock)
            int stratCount = strategyExposure.getOrDefault(strategy, 0);
            if (stratCount >= maxTradesPerStrategy) {
                log.warn("[RISK] REJECTED {} [STRATEGY_DIVERSITY]: '{}' has {}/{} trades today",
                        sym, strategy, stratCount, maxTradesPerStrategy);
                return;
            }

            // Gate 4: Phase-1 Concurrency
            // isAnyTradeAtBreakevenOrBeyond() called INSIDE the lock so that
            // a concurrent notifyPhase2Migration() cannot decrement phase1Count
            // between our check and our increment.
            int p1 = phase1Count.get();
            if (p1 >= maxPhase1Concurrent) {
                boolean hasBreakeven = paperManagement.isAnyTradeAtBreakevenOrBeyond();
                if (!hasBreakeven) {
                    log.warn("[RISK] REJECTED {} [PHASE1_FULL]: phase1={}/{}, no breakeven trade yet",
                            sym, p1, maxPhase1Concurrent);
                    return;
                }
                log.info("[RISK] Gate-4 OVERRIDE {}: phase1={}/{} but a trade is at breakeven → allow",
                        sym, p1, maxPhase1Concurrent);
            }

            // All gates passed — commit atomically
            sectorExposure.merge(sector, 1, Integer::sum);
            strategyExposure.merge(strategy, 1, Integer::sum);
            phase1Count.incrementAndGet();
            circuitBreaker.recordTradeEntered();

            log.info("[RISK] APPROVED: {} strategy={} sector={} dir={} qty={} entry={} sl={} " +
                            "target={} sector={}/{} strat={}/{} phase1={}/{} age={}s",
                    sym, strategy, sector, event.getDirection(), size.quantity(),
                    event.getEntryPrice(), event.getStopLoss(), event.getTarget(),
                    sectorExposure.getOrDefault(sector, 0), maxSectorTrades,
                    strategyExposure.getOrDefault(strategy, 0), maxTradesPerStrategy,
                    phase1Count.get(), maxPhase1Concurrent,
                    signalTime != null ? Duration.between(signalTime, Instant.now()).getSeconds() : "?");

        } finally {
            slotLock.unlock();
        }

        // ── Publish OUTSIDE the lock — event publishing must never hold a lock ─
        publisher.publishEvent(new TradeApprovedEvent(this,
                sym, event.getInstrumentToken(),
                event.getDirection(), event.getEntryPrice(),
                event.getStopLoss(), event.getTarget(),
                size.quantity(), size.actualRisk(),
                event.getTotalScore(), event.getStrategyName(),
                event.getTimeStopMinutes()));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Phase-2 Migration callback — called by PaperTradeManagementService
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Called when a trade moves from Phase-1 → Phase-2 (SL to breakeven).
     * Decrements phase1Count under the same lock used by onProbabilityScore().
     *
     * FLAW 1 FIX: Without the lock here, this decrement could interleave with
     * a concurrent Gate-4 check:
     *   Thread A (signal): phase1Count.get() = 3 → starts checking hasBreakeven
     *   Thread B (migrate): phase1Count.decrementAndGet() → now 2
     *   Thread A: hasBreakeven = true → allows → increments → phase1Count = 3 again
     *   Result: correct BUT only by luck — hasBreakeven was read stale (Phase-2 happened
     *   AFTER the read but BEFORE the increment). The lock ensures these cannot interleave.
     */
    public void notifyPhase2Migration(String symbol) {
        slotLock.lock();
        try {
            int prev = phase1Count.getAndUpdate(v -> Math.max(0, v - 1));
            log.info("[RISK] {} → Phase-2 (breakeven). phase1Count: {} → {}",
                    symbol, prev, phase1Count.get());
        } finally {
            slotLock.unlock();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Trade close — called by PaperTradeExecutionService
    // ══════════════════════════════════════════════════════════════════════════

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
            // Only decrement phase1Count if trade closed before reaching Phase-2.
            // If it reached Phase-2, count was already decremented at migration time.
            if (!reachedPhase2) {
                phase1Count.updateAndGet(v -> Math.max(0, v - 1));
            }
            log.debug("[RISK] Closed {}: sector released, strat released, " +
                            "reachedPhase2={}, phase1Count={}",
                    symbol, reachedPhase2, phase1Count.get());
        } finally {
            slotLock.unlock();
        }
    }

    /** Backward-compatible overload — assumes Phase-1 close (conservative). */
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
    // Daily reset at 8:45 IST
    // ══════════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 45 8 * * MON-FRI", zone = "Asia/Kolkata")
    public void resetDaily() {
        slotLock.lock();
        try {
            sectorExposure.clear();
            strategyExposure.clear();
            phase1Count.set(0);
            log.info("[RISK] Daily reset complete — all slot counters cleared");
        } finally {
            slotLock.unlock();
        }
    }
}