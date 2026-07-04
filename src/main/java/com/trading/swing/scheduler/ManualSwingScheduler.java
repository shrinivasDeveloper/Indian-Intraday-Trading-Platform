package com.trading.swing.scheduler;

import com.trading.swing.config.ManualSwingConfig;
import com.trading.swing.domain.ManualSwingTrade;
import com.trading.swing.repository.ManualSwingTradeRepository;
import com.trading.swing.service.ManualSwingTradingService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ManualSwingScheduler - the ONLY scheduler for this module. Never
 * interacts with AI or News in any way; runs entirely on its own
 * dedicated cycle, monitoring only this module's own ACTIVE trades.
 *
 * Market-hours gating: skips entirely outside configured hours. Resuming
 * "automatically on the next trading day" falls out naturally from this
 * gate re-opening each morning - no separate day-boundary logic needed.
 */
@Component
@Slf4j
public class ManualSwingScheduler {

    private final ManualSwingTradingService service;
    private final ManualSwingTradeRepository repo;
    private final ManualSwingConfig config;

    private LocalTime marketOpen;
    private LocalTime marketClose;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean wasInMarketHours = false;

    public ManualSwingScheduler(ManualSwingTradingService service,
                                ManualSwingTradeRepository repo,
                                ManualSwingConfig config) {
        this.service = service;
        this.repo = repo;
        this.config = config;
    }

    @PostConstruct
    public void init() {
        this.marketOpen  = LocalTime.parse(config.getMarketOpenTime());
        this.marketClose = LocalTime.parse(config.getMarketCloseTime());
        log.info("[SWING-SCHEDULER] Scheduler started - monitoring window {}-{}, tick every {}ms",
                marketOpen, marketClose, config.getMonitoringIntervalMs());
    }

    @PreDestroy
    public void onShutdown() {
        log.info("[SWING-SCHEDULER] Scheduler stopped - no further monitoring ticks will run " +
                "until the application restarts. All trade state remains safely in the database.");
    }

    /**
     * Fixed-rate monitoring tick. Configurable interval - see
     * manual-swing.monitoring-interval-ms (default 60s: this is a swing-
     * trade module, not a tick-level intraday strategy, so a 1-minute
     * cadence is more than enough granularity).
     *
     * Runs on the dedicated "manualSwingTaskScheduler" bean (defined in
     * ManualSwingConfig), NOT Spring's shared default scheduler thread -
     * see that bean's docstring for why this matters: this method's
     * order-fill polling blocks for up to ~30s per attempt cycle, and the
     * shared default scheduler is also where AI's and News's EOD force-
     * close cron jobs run. Without this isolation, a poorly-timed overlap
     * could have delayed a critical EOD exit.
     */
    @Scheduled(fixedRateString = "${manual-swing.monitoring-interval-ms:60000}",
            scheduler = "manualSwingTaskScheduler")
    public void monitorActiveTrades() {
        // FIX (found via direct log review: this scheduler logged
        // "Market open - monitoring resumed" and proceeded to check
        // trades on a SATURDAY, since inMarketHours below only ever
        // checked the CLOCK TIME, with zero day-of-week or holiday
        // awareness - the exact same bug class already fixed in
        // ManualSwingTradingService.checkAndExitIfNeeded() and
        // AutoSwingScheduler, but missed here since this is a separate,
        // third file with its own independent market-hours check.
        // checkAndExitIfNeeded() itself would have correctly no-op'd
        // due to that earlier fix (so no incorrect sell could actually
        // happen), but this outer log was still misleading and this
        // still reached a method call that immediately returns -
        // fixed at the source here instead.
        if (com.trading.swing.service.MarketHolidayChecker.isMarketClosedToday()) {
            if (wasInMarketHours) {
                log.info("[SWING-SCHEDULER] Market closed (weekend/holiday) - monitoring " +
                        "stopped, will automatically resume next real trading day");
            }
            wasInMarketHours = false;
            return;
        }

        LocalTime now = LocalTime.now();
        boolean inMarketHours = !now.isBefore(marketOpen) && !now.isAfter(marketClose);

        if (!inMarketHours) {
            if (wasInMarketHours) {
                log.info("[SWING-SCHEDULER] Market closed - monitoring stopped for today, " +
                        "will automatically resume next trading day at {}", marketOpen);
            }
            wasInMarketHours = false;
            return;
        }
        if (!wasInMarketHours) {
            log.info("[SWING-SCHEDULER] Market open - monitoring resumed for this trading day");
            wasInMarketHours = true;
        }

        // Re-entrancy guard: if a previous tick's monitoring pass is still
        // running (e.g. a slow order-fill poll), skip this tick entirely
        // rather than overlapping two monitoring passes.
        if (!running.compareAndSet(false, true)) {
            log.debug("[SWING-SCHEDULER] Previous monitoring pass still running - skip this tick");
            return;
        }

        try {
            List<ManualSwingTrade> active = repo.findActive();
            if (active.isEmpty()) return;

            log.debug("[SWING-SCHEDULER] Monitoring {} active trade(s)", active.size());
            for (ManualSwingTrade trade : active) {
                try {
                    service.checkAndExitIfNeeded(trade);
                } catch (Exception e) {
                    // One trade's failure must never block monitoring of the
                    // others in the same cycle.
                    log.error("[SWING-SCHEDULER] Error monitoring trade {} ({}) - " +
                                    "continuing with remaining trades: {}",
                            trade.getTradeId(), trade.getSymbol(), e.getMessage());
                }
            }
        } finally {
            running.set(false);
        }
    }
}