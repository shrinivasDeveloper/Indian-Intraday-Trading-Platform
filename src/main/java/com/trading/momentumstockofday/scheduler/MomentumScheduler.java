package com.trading.momentumstockofday.scheduler;

import com.trading.momentumstockofday.config.MomentumConfig;
import com.trading.momentumstockofday.domain.MomentumCandidate;
import com.trading.momentumstockofday.domain.MomentumTrade;
import com.trading.momentumstockofday.repository.MomentumTradeRepository;
import com.trading.momentumstockofday.service.MomentumCandleService;
import com.trading.momentumstockofday.service.MomentumSelectionService;
import com.trading.momentumstockofday.service.MomentumTradingService;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.context.event.EventListener;
import com.trading.events.CandleCompleteEvent;

/**
 * MomentumScheduler - orchestrates the complete strategy flow.
 *
 * INDEPENDENCE (per explicit requirement): completely separate
 * @Scheduled jobs, own thread (Spring's default scheduler pool is
 * shared infrastructure, same as every other strategy already uses -
 * not strategy-specific logic). Zero calls into any existing
 * strategy's scheduler or service.
 */
@Component
@Slf4j
public class MomentumScheduler {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final MomentumConfig config;
    private final MomentumSelectionService selectionService;
    private final MomentumCandleService candleService;
    private final MomentumTradingService tradingService;
    private final MomentumTradeRepository repository;

    // FIX (per explicit user request: react to real candle completions
    // immediately instead of waiting up to 30 seconds for the next poll,
    // "without affecting any strategies and momentum as well"). This
    // lock ensures tick()'s entire body - whether triggered by the
    // scheduler below OR the new event-driven listener - can NEVER run
    // concurrently from two different threads at once. Without this,
    // a genuine race condition could let both paths pass the "no active
    // trade yet" check simultaneously and attempt a duplicate entry.
    private final ReentrantLock tickLock = new ReentrantLock();

    // FIX (per explicit request: "2 trades per day without affecting
    // existing strategy... update in momentum strategy"). Replaced the
    // original single-trade boolean with a counter, compared against
    // the new, configurable maxTradesPerDay (default 2). Purely
    // additive to this already-independent module - zero impact on
    // AI, News, Swing, or Hero-or-Zero.
    private final AtomicInteger tradesToday = new AtomicInteger(0);

    // Symbols already traded today are excluded from re-entry
    // consideration on a second pass through the candidate list -
    // prevents entering the SAME stock twice in one day.
    private final Set<String> tradedSymbolsToday = ConcurrentHashMap.newKeySet();

    // FIX (confirmed real issue found via production logs): GOKEX was
    // re-attempted 3 times within ~2 seconds, each time re-fetching
    // identical, unchanged trend-filter data via a real Kite API call -
    // the event-driven trigger fires a full monitoring pass on every
    // tracked symbol's candle completion, not just the one that
    // actually changed, so a candidate that JUST failed a gate can be
    // immediately re-attempted again before any of its underlying data
    // has genuinely changed. This is a pure efficiency fix - it does
    // NOT change any gate's own logic, only how often a recently-failed
    // symbol gets re-evaluated.
    private final Map<String, Long> lastFailedEntryAttempt = new ConcurrentHashMap<>();
    private static final long FAILED_ENTRY_COOLDOWN_MS = 60_000; // 1 minute

    private final AtomicBoolean selectionAttemptedToday = new AtomicBoolean(false);
    private volatile LocalDate lastResetDate = null;

    // FEATURE 2 (Rescanning for New Momentum Stocks, per explicit user
    // spec): tracks when the candidate list was last (re)scanned, so a
    // fresh scan can run every 15 minutes during market hours,
    // REPLACING the candidate list entirely each time.
    private volatile java.time.LocalDateTime lastScanTime = null;
    private static final long RESCAN_INTERVAL_MINUTES = 15;

    @Getter
    private volatile List<MomentumCandidate> todaysCandidates = List.of();

    private final AtomicReference<MomentumTrade> activeTrade = new AtomicReference<>();

    public MomentumScheduler(MomentumConfig config, MomentumSelectionService selectionService,
                             MomentumCandleService candleService,
                             MomentumTradingService tradingService,
                             MomentumTradeRepository repository) {
        this.config = config;
        this.selectionService = selectionService;
        this.candleService = candleService;
        this.tradingService = tradingService;
        this.repository = repository;
    }

    @PostConstruct
    public void init() {
        log.info("[MOMENTUM-SCHEDULER] Initialised. enabled={} selectionTime={} " +
                        "topSectors={} topStocksPerSector={} riskReward=1:{} maxTradesPerDay={}",
                config.isEnabled(), config.getSelectionTime(), config.getTopSectorsCount(),
                config.getTopStocksPerSector(), config.getRiskRewardRatio(),
                config.getMaxTradesPerDay());

        // Restart recovery - correctly re-arm the trade counter and
        // traded-symbols set from real DB state, and resume monitoring
        // any still-ACTIVE trade.
        var todaysTrades = repository.findToday();
        tradesToday.set(todaysTrades.size());
        todaysTrades.forEach(t -> tradedSymbolsToday.add(t.getSymbol()));

        var activeTrades = repository.findActive();
        if (!activeTrades.isEmpty()) {
            activeTrade.set(activeTrades.get(0));
            selectionAttemptedToday.set(true);
            // FIX: without this, the 15-minute rescan would never
            // trigger again after a restart (dueForRescan requires
            // lastScanTime != null, and nothing else would ever set it
            // once selectionAttemptedToday is already true).
            lastScanTime = java.time.LocalDateTime.now(IST);
            log.info("[MOMENTUM-SCHEDULER] Restart recovery: found 1 ACTIVE trade ({}), " +
                            "{}/{} trades taken today - monitoring will resume automatically",
                    activeTrades.get(0).getSymbol(), tradesToday.get(), config.getMaxTradesPerDay());

            // FIX (per explicit user investigation: "no auto square off
            // happened for momentum and AI... why"). Makes this exact
            // scenario unmistakable in future logs, even with limited
            // log retention: if the app restarts AFTER the configured
            // force-exit time, this line makes that fact explicit and
            // searchable, rather than silently relying on the next
            // tick to (correctly) handle it. Zero change to actual
            // behavior - the existing tick() logic already handles
            // this correctly on its own; this purely adds visibility.
            java.time.LocalTime nowAtRestart = java.time.LocalTime.now(IST);
            java.time.LocalTime forceExitTimeAtRestart = java.time.LocalTime.parse(config.getForceExitTime());
            if (!nowAtRestart.isBefore(forceExitTimeAtRestart)) {
                log.warn("[MOMENTUM-SCHEDULER] RESTART DETECTED AFTER FORCE-EXIT TIME ({} vs " +
                                "configured {}) - an active trade was still found in our database. " +
                                "The very next scheduled tick will immediately reconcile against the " +
                                "real broker position and force-exit if genuinely still open. If the " +
                                "broker already closed this position while the app was offline " +
                                "(e.g. Zerodha's own MIS auto square-off), reconciliation will detect " +
                                "and correctly record that instead.",
                        nowAtRestart, config.getForceExitTime());
            }
        } else if (!todaysTrades.isEmpty()) {
            selectionAttemptedToday.set(true);
            lastScanTime = java.time.LocalDateTime.now(IST);
            log.info("[MOMENTUM-SCHEDULER] Restart recovery: {}/{} trades already taken today",
                    tradesToday.get(), config.getMaxTradesPerDay());
        }
    }

    /** Runs frequently; internally gates on the exact configured time
     *  and the once-per-day guard, matching the same proven pattern
     *  already used by every other strategy's scheduler this session.
     *  FIX: entire body now wrapped in tickLock, so this can safely be
     *  called from either the scheduled poll below OR the new event-
     *  driven trigger, never both at once.
     *  FIX (found during production-readiness review): uses tryLock(),
     *  not the blocking lock() - if the event-driven trigger currently
     *  holds the lock, this scheduled cycle is skipped gracefully
     *  rather than blocking the scheduler thread. The event-driven
     *  path (or the next 30-second cycle) is already doing the same
     *  work, so nothing is lost by skipping. */
    @Scheduled(fixedRate = 30000)
    public void tick() {
        if (!config.isEnabled()) return;
        if (!tickLock.tryLock()) {
            log.debug("[MOMENTUM-SCHEDULER] Skipped this scheduled cycle - the event-driven " +
                    "trigger is currently processing the same work");
            return;
        }
        try {
            tickInternal();
        } finally {
            tickLock.unlock();
        }
    }

    /**
     * FIX (per explicit user request: "why every 30 second not live
     * ticks... please fix it without affecting the strategy flow...
     * without affecting any strategies and momentum as well"). Reacts
     * to a real candle completing immediately, instead of waiting up
     * to 30 seconds for the next scheduled poll - cutting the
     * worst-case detection delay from "up to 30 seconds" down to
     * essentially immediate. Calls the EXACT SAME tick() method used
     * by the scheduler above (protected by the same tickLock) - reuses
     * 100% of the existing gating logic (daily reset, EOD exit,
     * reconciliation, active-trade monitoring, daily cap, selection/
     * rescan, breakout monitoring) with zero duplication, so nothing
     * about the existing strategy flow changes - this purely adds a
     * FASTER way to trigger the same, unchanged logic. Filters to only
     * 5-minute candles (matching the strategy's own candle granularity
     * - reacting to 1-minute candles would trigger far too often for
     * no benefit) for symbols Momentum currently tracks. Wrapped so
     * this can never throw back into the shared CandleCompleteEvent
     * pipeline other strategies also depend on.
     */
    @EventListener
    public void onCandleCompleteForBreakoutCheck(CandleCompleteEvent event) {
        try {
            if (!config.isEnabled()) return;
            var candle = event.getCandle();
            if (candle == null || !"5minute".equals(candle.getTimeframe())) return;
            String symbol = candle.getTradingSymbol();
            if (symbol == null) return;
            boolean isTracked = todaysCandidates.stream()
                    .anyMatch(c -> symbol.equals(c.getSymbol()));
            if (!isTracked) return;

            // FIX (found during production-readiness review): uses
            // tryLock(), not the blocking lock() - this listener likely
            // runs on the same shared async thread pool
            // CandleAggregatorService uses for other strategies too.
            // Blocking here while waiting for the scheduled tick to
            // finish could tie up threads that shared infrastructure
            // depends on. If the lock isn't immediately available, this
            // specific event is skipped gracefully - the scheduled
            // 30-second poll (or the next candle event) will still
            // catch the same opportunity shortly after.
            if (!tickLock.tryLock()) {
                log.debug("[MOMENTUM-SCHEDULER] Skipped event-driven check for {} - the " +
                        "scheduled poll is currently running (will catch this shortly)", symbol);
                return;
            }
            try {
                tickInternal();
            } finally {
                tickLock.unlock();
            }
        } catch (Exception e) {
            log.warn("[MOMENTUM-SCHEDULER] Event-driven breakout check failed (non-fatal, the " +
                    "next scheduled 30-second poll will still catch this): {}", e.getMessage());
        }
    }

    /**
     * The exact, complete, UNCHANGED tick() body from before this fix -
     * extracted as its own method purely so both the scheduled poll and
     * the new event-driven trigger can share it (and the same lock)
     * without any duplicated logic. Zero behavioral change from the
     * original tick() - every line below is byte-for-byte identical to
     * what tick() itself contained before this fix.
     */
    private void tickInternal() {
        LocalDate today = LocalDate.now(IST);
        if (!today.equals(lastResetDate)) {
            tradesToday.set(0);
            tradedSymbolsToday.clear();
            lastFailedEntryAttempt.clear(); // FIX: clear cooldown tracking for the new day too
            selectionAttemptedToday.set(false);
            todaysCandidates = List.of();
            activeTrade.set(null);
            lastScanTime = null; // FIX: reset rescan timer for the new day
            lastResetDate = today;
        }

        LocalTime now = LocalTime.now(IST);
        LocalTime selectionTime = LocalTime.parse(config.getSelectionTime());
        LocalTime forceExitTime = LocalTime.parse(config.getForceExitTime());

        // Mandatory EOD exit - same safety principle as every other
        // strategy this session, independently implemented here.
        MomentumTrade active = activeTrade.get();
        if (active != null && !now.isBefore(forceExitTime)) {
            // FIX (confirmed serious bug found via direct user report:
            // a position stuck ACTIVE forever with no exit order ever
            // appearing at the broker). activeTrade was being cleared
            // unconditionally here regardless of whether the exit
            // below genuinely succeeded - a single transient failure
            // (network blip, API error) would silently and permanently
            // abandon the position, since the only reference needed to
            // retry it was destroyed anyway. Now only clears if the
            // exit genuinely succeeded - otherwise the next scheduled
            // tick (30 seconds later) will correctly retry.
            boolean exited = tradingService.exitTrade(active, "FORCE_EXIT_EOD");
            if (exited) {
                activeTrade.set(null);
            } else {
                log.warn("[MOMENTUM-SCHEDULER] EOD force-exit failed for {} - keeping it " +
                        "active so the next tick retries (see MOMENTUM-TRADE log above for " +
                        "the specific failure reason)", active.getSymbol());
            }
            return;
        }

        // If a trade is currently open, first reconcile against the
        // REAL broker position (FIX: this safety net was built but
        // never actually wired in - now genuinely checked every cycle,
        // same "broker is truth" principle as Swing's own reconciliation).
        // If reconciliation finds it was closed externally, treat it
        // the same as a normal close - fall through to see if another
        // trade can be taken today.
        if (active != null) {
            boolean genuinelyActive = tradingService.reconcileWithBroker(active);
            if (!genuinelyActive) {
                activeTrade.set(null); // reconciliation already corrected the DB record
            }
        }

        // If a trade is currently open, monitor it. If it just CLOSED
        // (monitorActiveTrade returns false), fall through to see if
        // the daily cap allows resuming monitoring for another trade -
        // this is the core of the 2-trades-per-day enhancement.
        active = activeTrade.get(); // re-read - reconciliation above may have cleared it
        if (active != null) {
            boolean stillActive = tradingService.monitorActiveTrade(active);
            if (stillActive) return;
            activeTrade.set(null); // closed - fall through below
        }

        // Daily cap reached - nothing more to do today.
        if (tradesToday.get() >= config.getMaxTradesPerDay()) {
            return;
        }

        // Step 1: run selection at the configured time, then RESCAN
        // every 15 minutes thereafter - per explicit user spec: "A
        // fresh momentum scan is performed every 15 minutes during
        // market hours. Only newly identified momentum stocks from
        // each scan should be added for monitoring. Stocks from
        // previous scans should no longer be monitored after a new
        // scan begins." Each rescan REPLACES todaysCandidates entirely.
        // An active trade is never affected by this - the active-trade
        // check above already returned early if one exists, so a
        // rescan can only ever touch the WATCHLIST, never a live position.
        boolean dueForFirstScan = !selectionAttemptedToday.get() && !now.isBefore(selectionTime);
        boolean dueForRescan = selectionAttemptedToday.get() && lastScanTime != null &&
                java.time.Duration.between(lastScanTime, java.time.LocalDateTime.now(IST))
                        .toMinutes() >= RESCAN_INTERVAL_MINUTES;

        if ((dueForFirstScan || dueForRescan) && now.isBefore(forceExitTime)) {
            selectionAttemptedToday.set(true); // set BEFORE calling out, so a slow/failed
            // call can never cause a repeat attempt
            List<MomentumCandidate> fresh = selectionService.selectCandidates();
            lastScanTime = java.time.LocalDateTime.now(IST);
            String scanType = dueForRescan ? "RESCAN" : "Initial scan";
            if (fresh.isEmpty()) {
                log.warn("[MOMENTUM-SCHEDULER] {} ran but found 0 candidates this cycle - " +
                                "watchlist cleared, will rescan again in {} minutes",
                        scanType, RESCAN_INTERVAL_MINUTES);
            } else {
                log.info("[MOMENTUM-SCHEDULER] {} complete - {} new candidate(s), replacing " +
                        "previous watchlist entirely (previous candidates are no longer " +
                        "monitored, per spec)", scanType, fresh.size());
            }
            todaysCandidates = fresh; // REPLACES the previous list entirely - per spec
            // FIX (per explicit user request: rebuild candle sourcing to
            // use the live CandleCompleteEvent stream): tell the event
            // listener which symbols to buffer candles for - it only
            // buffers symbols Momentum currently tracks, not all ~500
            // Nifty500 symbols the event stream publishes for.
            candleService.updateTrackedSymbols(
                    fresh.stream().map(MomentumCandidate::getSymbol).collect(java.util.stream.Collectors.toSet()));
            return; // let the NEXT tick begin monitoring, keeps each tick simple/fast
        }

        // Step 2: continuous monitoring, in STRICT sector-priority order.
        // Per spec: "Always monitor the first-ranked sector first. Only
        // if no valid trade is found should the strategy move to the
        // second sector, and then to the third... never skip ahead."
        if (!todaysCandidates.isEmpty() && !now.isBefore(selectionTime) && now.isBefore(forceExitTime)) {
            monitorForBreakout();
        }
    }

    /**
     * Walks the 9 candidates in STRICT priority order (already sorted
     * correctly by MomentumSelectionService: sector-1's 3 stocks first,
     * then sector-2's, then sector-3's) - takes the FIRST valid
     * breakout found among symbols NOT already traded today (per the
     * 2-trades-per-day enhancement, a stock already traded once today
     * is excluded from being re-entered on a second pass).
     */
    private void monitorForBreakout() {
        for (MomentumCandidate candidate : todaysCandidates) {
            if (tradedSymbolsToday.contains(candidate.getSymbol())) continue; // already traded today

            // FIX (confirmed real issue from production logs): skip a
            // symbol that failed an entry attempt within the last
            // minute - its underlying trend-filter/margin data genuinely
            // hasn't had a chance to change yet, so immediately re-
            // attempting is pure waste (redundant Kite API calls,
            // unnecessary rate-limit pressure). Does not affect a
            // symbol's first attempt, or any attempt after the cooldown
            // has genuinely passed.
            Long lastFail = lastFailedEntryAttempt.get(candidate.getSymbol());
            if (lastFail != null && System.currentTimeMillis() - lastFail < FAILED_ENTRY_COOLDOWN_MS) {
                continue;
            }

            MomentumCandleService.EvaluationResult result = candleService.evaluate(candidate);
            candidate.setValidConsolidation(result.validConsolidation());
            candidate.setConsolidationHigh(result.consolidationHigh());
            candidate.setConsolidationLow(result.consolidationLow());
            candidate.setConsolidationCandles(result.candles());
            candidate.setLastEvaluationNote(result.note());

            if (result.validConsolidation() && result.breakoutTriggered()) {
                log.info("[MOMENTUM-SCHEDULER] BREAKOUT: {} (sector #{} '{}', {}) - {} " +
                                "[trade {}/{} today]", candidate.getSymbol(), candidate.getSectorRank(),
                        candidate.getSector(), candidate.getDirection(), result.note(),
                        tradesToday.get() + 1, config.getMaxTradesPerDay());
                try {
                    MomentumTrade trade = tradingService.enterBreakout(candidate,
                            result.consolidationHigh(), result.consolidationLow());
                    activeTrade.set(trade);
                    tradesToday.incrementAndGet();
                    tradedSymbolsToday.add(candidate.getSymbol());
                } catch (Exception e) {
                    log.error("[MOMENTUM-SCHEDULER] Breakout entry FAILED for {}: {} - " +
                                    "continuing to monitor remaining candidates this cycle",
                            candidate.getSymbol(), e.getMessage());
                    // FIX: record the failure so this symbol isn't
                    // immediately re-attempted again on the very next
                    // trigger with unchanged data.
                    lastFailedEntryAttempt.put(candidate.getSymbol(), System.currentTimeMillis());
                    continue; // per spec's priority order - if THIS stock's entry fails,
                    // still respect priority and only move to the NEXT candidate
                    // in strict order, not skip ahead arbitrarily
                }
                return; // one trade taken this cycle - stop for now (may resume later if
                // the daily cap still allows another)
            }
        }
    }
}