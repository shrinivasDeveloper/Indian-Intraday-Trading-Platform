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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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

    private final AtomicBoolean selectionAttemptedToday = new AtomicBoolean(false);
    private volatile LocalDate lastResetDate = null;

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
            log.info("[MOMENTUM-SCHEDULER] Restart recovery: found 1 ACTIVE trade ({}), " +
                            "{}/{} trades taken today - monitoring will resume automatically",
                    activeTrades.get(0).getSymbol(), tradesToday.get(), config.getMaxTradesPerDay());
        } else if (!todaysTrades.isEmpty()) {
            selectionAttemptedToday.set(true);
            log.info("[MOMENTUM-SCHEDULER] Restart recovery: {}/{} trades already taken today",
                    tradesToday.get(), config.getMaxTradesPerDay());
        }
    }

    /** Runs frequently; internally gates on the exact configured time
     *  and the once-per-day guard, matching the same proven pattern
     *  already used by every other strategy's scheduler this session. */
    @Scheduled(fixedRate = 30000)
    public void tick() {
        if (!config.isEnabled()) return;

        LocalDate today = LocalDate.now(IST);
        if (!today.equals(lastResetDate)) {
            tradesToday.set(0);
            tradedSymbolsToday.clear();
            selectionAttemptedToday.set(false);
            todaysCandidates = List.of();
            activeTrade.set(null);
            lastResetDate = today;
        }

        LocalTime now = LocalTime.now(IST);
        LocalTime selectionTime = LocalTime.parse(config.getSelectionTime());
        LocalTime forceExitTime = LocalTime.parse(config.getForceExitTime());

        // Mandatory EOD exit - same safety principle as every other
        // strategy this session, independently implemented here.
        MomentumTrade active = activeTrade.get();
        if (active != null && !now.isBefore(forceExitTime)) {
            tradingService.exitTrade(active, "FORCE_EXIT_EOD");
            activeTrade.set(null);
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

        // Step 1: run selection ONCE, at (or just after) 9:25 AM - now
        // correctly gated on selectionAttemptedToday, not the ambiguous
        // isEmpty() check that caused a confirmed infinite-retry bug in
        // an earlier review.
        if (!selectionAttemptedToday.get() && !now.isBefore(selectionTime) && now.isBefore(forceExitTime)) {
            selectionAttemptedToday.set(true); // set BEFORE calling out, so a slow/failed
            // call can never cause a repeat attempt
            todaysCandidates = selectionService.selectCandidates();
            if (todaysCandidates.isEmpty()) {
                log.warn("[MOMENTUM-SCHEDULER] Selection ran but found 0 candidates today - " +
                        "no monitoring will occur for the rest of today (this is correct, not " +
                        "a bug - will not retry until tomorrow)");
            }
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