package com.trading.swing.auto.scheduler;

import com.trading.swing.auto.domain.StockCandidate;
import com.trading.swing.auto.service.AutoStockSelectionEngine;
import com.trading.swing.auto.service.BhavcopyBackfillService;
import com.trading.swing.config.ManualSwingConfig;
import com.trading.swing.repository.ManualSwingTradeRepository;
import com.trading.swing.service.ManualSwingTradingService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AutoSwingScheduler - the 3:00 PM IST trigger for automated stock
 * selection and buying. Exactly the trade flow the spec describes:
 *
 *   1. Check whether a MANUAL swing trade already exists today.
 *   2. If yes - skip automated execution entirely.
 *   3. If no - run the complete stock selection engine (Rules 1-4),
 *      buy the highest-confidence stock with the fixed Rs.10,000 capital,
 *      exiting next trading day via the EXISTING manual-swing exit
 *      logic (same scheduler, same rules - "configurable exit logic"
 *      per the spec, already built and validated earlier in this module).
 *
 * Runs on its OWN dedicated "manualSwingAutoSelectionScheduler" thread -
 * separate from ManualSwingScheduler's time-critical exit-monitoring
 * thread (manualSwingTaskScheduler). FIX (found during prompt-vs-output
 * verification): this process makes 2 NSE HTTP calls per momentum-
 * passing candidate stock - easily 30-40+ sequential calls in a real
 * sector evaluation. Sharing a thread with exit monitoring would have
 * blocked target-hit and 9:20 AM force-exit checks for any ACTIVE trade
 * for the full duration of a selection run - exactly the class of
 * problem the original AI/News scheduler-isolation fix was meant to
 * prevent, recreated within this module between its own two schedulers.
 *
 * Disabled by default (manual-swing.auto-trade-enabled=false) - must be
 * explicitly turned on, given the scope and real-money nature of fully
 * automated stock selection.
 */
@Component
@Slf4j
public class AutoSwingScheduler {

    private final ManualSwingTradeRepository tradeRepo;
    private final AutoStockSelectionEngine selectionEngine;
    private final ManualSwingTradingService tradingService;
    private final BhavcopyBackfillService backfillService;
    private final ManualSwingConfig config;

    private LocalTime triggerTime;
    private final AtomicBoolean alreadyRanToday = new AtomicBoolean(false);
    private volatile LocalDate lastRunDate = null;

    public AutoSwingScheduler(ManualSwingTradeRepository tradeRepo,
                              AutoStockSelectionEngine selectionEngine,
                              ManualSwingTradingService tradingService,
                              BhavcopyBackfillService backfillService,
                              ManualSwingConfig config) {
        this.tradeRepo = tradeRepo;
        this.selectionEngine = selectionEngine;
        this.tradingService = tradingService;
        this.backfillService = backfillService;
        this.config = config;
    }

    @PostConstruct
    public void init() {
        this.triggerTime = LocalTime.parse(config.getAutoTradeCheckTime());
        log.info("[AUTO-SWING-SCHEDULER] Initialised. autoTradeEnabled={} triggerTime={} capital=Rs.{}",
                config.isAutoTradeEnabled(), triggerTime, config.getAutoTradeCapital());
    }

    /**
     * Checked every monitoring tick (reuses the existing 60s cadence -
     * no need for a separate, more frequent schedule just to catch one
     * specific minute of the day). Fires exactly once per trading day,
     * the first tick at or after the configured trigger time.
     *
     * Runs on "manualSwingAutoSelectionScheduler" - a SEPARATE dedicated
     * thread from "manualSwingTaskScheduler" (used by
     * ManualSwingScheduler's time-critical exit monitoring). FIX (found
     * during prompt-vs-output verification): this method makes 2 NSE
     * HTTP calls per momentum-passing candidate stock across an entire
     * qualifying sector - easily 30-40+ sequential calls in a real run.
     * On the same thread as exit monitoring, that would have blocked
     * target-hit and 9:20 AM force-exit checks for any ACTIVE trade for
     * the full duration of a selection run.
     */
    @Scheduled(fixedRateString = "${manual-swing.monitoring-interval-ms:60000}",
            scheduler = "manualSwingAutoSelectionScheduler")
    public void checkAndRunAutoSelection() {
        if (!config.isAutoTradeEnabled()) return;

        // FIX (same confirmed bug class found in ManualSwingTradingService's
        // exit monitoring): this scheduler had zero weekend awareness -
        // once the clock passed the 3pm trigger time on a Saturday or
        // Sunday, it would have proceeded to check for a manual trade and
        // potentially run full auto-selection on a day the market is
        // closed. NOTE: this only catches weekends, not genuine mid-week
        // market holidays - no holiday-calendar data source is wired into
        // this system, a separate, pre-existing limitation.
        // FIX (per explicit follow-up: "all the holiday also handled in
        // this strategy please check"). Upgraded from a weekend-only
        // check to also cover genuine NSE/BSE trading holidays - see
        // MarketHolidayChecker's class docstring for the honest caveat
        // on how that holiday list was compiled and its limitations.
        if (com.trading.swing.service.MarketHolidayChecker.isMarketClosedToday()) {
            return;
        }

        LocalDate today = LocalDate.now();
        if (!today.equals(lastRunDate)) {
            alreadyRanToday.set(false); // new day - reset the once-per-day guard
            lastRunDate = today;
            // REMOVED: clearFundamentalsCache() call - AutoStockSelectionEngine
            // no longer has this method, since Rule 4/fundamentals (and the
            // Yahoo Finance service backing it) were fully removed per explicit
            // instruction. Nothing else needs to happen here on a new day for
            // this scheduler beyond resetting the once-per-day guard above.
        }

        LocalTime now = LocalTime.now();
        if (now.isBefore(triggerTime)) return;
        if (!alreadyRanToday.compareAndSet(false, true)) return; // already ran today

        log.info("[AUTO-SWING-SCHEDULER] {} reached - running manual-trade check", triggerTime);

        // Step 1 - the actual gate the spec leads with
        if (tradeRepo.existsManualTradeToday()) {
            log.info("[AUTO-SWING-SCHEDULER] A manual swing trade already exists today - " +
                    "skipping automated execution entirely, exactly as specified");
            return;
        }
        if (tradeRepo.existsAutoTradeToday()) {
            log.info("[AUTO-SWING-SCHEDULER] An automated trade already exists today - skip " +
                    "(belt-and-suspenders check; should not normally trigger given the " +
                    "once-per-day guard above)");
            return;
        }

        log.info("[AUTO-SWING-SCHEDULER] No manual trade today - running the complete stock " +
                "selection engine (Rules 1-4)");

        // Ensure today's price data is available before scoring sectors -
        // cheap, single-file fetch, separate from the gradual historical backfill.
        backfillService.fetchToday();

        Optional<StockCandidate> pick = selectionEngine.selectBestStock();
        if (pick.isEmpty()) {
            log.warn("[AUTO-SWING-SCHEDULER] No qualifying stock found today - no automated " +
                    "trade will be placed");
            return;
        }

        StockCandidate candidate = pick.get();
        int qty = computeQuantity(candidate);
        if (qty <= 0) {
            log.error("[AUTO-SWING-SCHEDULER] Computed quantity {} for {} is not viable " +
                            "(capital Rs.{} insufficient even for 1 share at last close Rs.{}) - skipping",
                    qty, candidate.symbol(), config.getAutoTradeCapital(), candidate.lastClose());
            return;
        }

        try {
            // Default 8% target - the spec gives an explicit target% input
            // only for the MANUAL UI flow; the AUTO path has no stated
            // target%, so this reuses the same exit mechanism already
            // built and validated for manual trades (the spec's own
            // "configurable exit logic" pointer), with a reasonable
            // default consistent with genuine swing-trade sizing.
            tradingService.placeAutoBuy(candidate.symbol(), candidate.exchange(),
                    candidate.companyName(), qty, BigDecimal.valueOf(8.0));
            log.info("[AUTO-SWING-SCHEDULER] Automated BUY placed: {} qty={} sector={} confidence={}/100 - {}",
                    candidate.symbol(), qty, candidate.sectorName(),
                    candidate.confidenceScore(), candidate.scoreBreakdown());
        } catch (Exception e) {
            log.error("[AUTO-SWING-SCHEDULER] Automated BUY failed for {}: {}",
                    candidate.symbol(), e.getMessage(), e);
        }
    }

    /**
     * Fixed Rs.10,000 capital / the candidate's most recent stored close
     * price, floored. The ACTUAL fill price (which may differ slightly
     * by 3pm) is what placeAutoBuy's existing, already-validated
     * fill-confirmation flow records - this is only the order quantity
     * to request, sized from the best price estimate available at
     * selection time.
     */
    private int computeQuantity(StockCandidate candidate) {
        if (candidate.lastClose() == null || candidate.lastClose().signum() <= 0) return 0;
        return config.getAutoTradeCapital() > 0
                ? BigDecimal.valueOf(config.getAutoTradeCapital())
                .divide(candidate.lastClose(), 0, RoundingMode.DOWN)
                .intValue()
                : 0;
    }
}