package com.trading.swing.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.TriggerContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/**
 * ManualSwingConfig — the dedicated Configuration component for this
 * module, as explicitly required by the independence section of the
 * spec ("Create separate: ... Configuration"). Every tunable value the
 * module needs lives here, bound from the manual-swing.* namespace in
 * application.yml — nothing hard-coded anywhere else in the module.
 *
 * FIX (found during a "did this touch anything else" validation pass):
 * the application has @EnableScheduling but NO custom TaskScheduler bean
 * defined anywhere — meaning AI's EOD force-close, News's EOD force-
 * close, BTST's crons, and any new @Scheduled method all share Spring's
 * single-threaded default scheduler, executing strictly one at a time.
 * This module's order-fill polling uses blocking Thread.sleep() calls
 * (up to ~30s total per attempt cycle) — on the shared default
 * scheduler, that could have delayed a critical AI/News EOD exit if the
 * timing happened to overlap. A dedicated, separate TaskScheduler bean
 * here means this module's scheduler runs entirely on its own thread,
 * never contending with any existing strategy's cron jobs — referenced
 * explicitly via @Scheduled(scheduler = "manualSwingTaskScheduler", ...)
 * on ManualSwingScheduler.
 */
@Component
@ConfigurationProperties(prefix = "manual-swing")
@Getter
@Setter
public class ManualSwingConfig {

    /** Quick-exit profit threshold, percent. Default 5.0. */
    private double profitTargetPct = 5.0;

    /** Unconditional force-exit time, the day after purchase. Default 09:20. */
    private String forceExitTime = "09:20";

    private String marketOpenTime = "09:15";
    private String marketCloseTime = "15:30";

    /** Scheduler tick interval, milliseconds. Default 60s. */
    private long monitoringIntervalMs = 60000;

    /** Order fill-confirmation poll interval, milliseconds. Default 3s. */
    private long orderPollIntervalMs = 3000;

    /** Max poll attempts before giving up on fill confirmation. Default 10. */
    private int orderPollMaxAttempts = 10;

    /** Per-symbol buy-lock expiry, milliseconds — see duplicate-protection
     *  docs on ManualSwingTradingService. Default 10s, generous for a
     *  single HTTP request/response cycle, short enough not to lock out
     *  a genuinely new buy attempt for long. */
    private long buyLockExpiryMs = 10000;

    /**
     * Dedicated scheduler thread for this module only — see the class-
     * level FIX note above. 1 thread is intentional: this module's own
     * monitoring tick already has a re-entrancy guard (only one tick
     * runs at a time), so there's never a need for more than one thread
     * here; the point is isolation from other strategies, not parallelism
     * within this module.
     */
    @Bean(name = "manualSwingTaskScheduler")
    public ThreadPoolTaskScheduler manualSwingTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("manual-swing-scheduler-");
        scheduler.initialize();
        return scheduler;
    }

    /**
     * Separate executor specifically for the bhavcopy backfill's @Async
     * background task — deliberately NOT the same thread as
     * manualSwingTaskScheduler, since the backfill can run for an
     * extended period (many sequential, deliberately delayed HTTP
     * fetches) and must never block the monitoring scheduler's own
     * 60-second tick from running on time.
     */
    @Bean(name = "manualSwingBackfillExecutor")
    public org.springframework.core.task.TaskExecutor manualSwingBackfillExecutor() {
        org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor =
                new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setThreadNamePrefix("manual-swing-backfill-");
        executor.initialize();
        return executor;
    }

    /**
     * FIX (found during prompt-vs-output verification): AutoSwingScheduler
     * was running on the SAME manualSwingTaskScheduler thread as
     * ManualSwingScheduler's time-critical exit monitoring (target hits,
     * the 9:20 AM force-exit). The auto-selection process makes 2 NSE
     * HTTP calls (shareholding + financial results) PER momentum-passing
     * stock in EVERY qualifying sector — easily 30-40+ sequential network
     * calls in a real run. On a single shared thread, that selection run
     * would have BLOCKED exit-monitoring ticks for ACTIVE trades — the
     * exact same class of problem the original AI/News scheduler-
     * isolation fix was meant to prevent, recreated within this module
     * between its own two schedulers. Dedicated thread fixes it, same
     * principle as manualSwingBackfillExecutor above.
     */
    @Bean(name = "manualSwingAutoSelectionScheduler")
    public ThreadPoolTaskScheduler manualSwingAutoSelectionScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("manual-swing-autoselect-");
        scheduler.initialize();
        return scheduler;
    }

    // ═══════════════════════════════════════════════════════════════════
    // AUTO STOCK SELECTION — small-cap-focused automated swing trading.
    // Disabled by default (autoTradeEnabled=false) — must be explicitly
    // turned on. Every threshold below comes directly from the spec's
    // Rule 2/3/4 sections; nothing here is invented.
    // ═══════════════════════════════════════════════════════════════════

    /** Master switch — auto-selection does nothing at all unless true. */
    private boolean autoTradeEnabled = false;

    /** The 3:00 PM IST trigger time. */
    private String autoTradeCheckTime = "15:00";

    /** Fixed capital for the auto-selected trade. Default ₹10,000 per spec. */
    private double autoTradeCapital = 10000.0;

    /** How many trading days of bhavcopy history to backfill. ~252 = 1 year,
     *  needed for Rule 2's yearly-performance qualification. */
    private int backfillTargetDays = 252;

    /** Delay between each backfill day's HTTP fetch, milliseconds —
     *  deliberately conservative to stay respectful of NSE's servers
     *  during the one-time historical backfill. */
    private long backfillDelayMs = 2000;

    /** Rule 1: how many top-ranked sectors to evaluate before giving up. */
    private int topSectorsToEvaluate = 4;

    /** Rule 2 sector qualification thresholds — directly from the spec. */
    private double sectorDailyMinPct = 5.0;
    private double sectorDailyMaxPct = 6.0;
    private double sectorWeeklyMinPct = 15.0;
    private double sectorMonthlyOverWeeklyMarginPct = 5.0;
    private double sectorYearlyMinPct = 60.0;

    /** Rule 4: mandatory promoter holding floor. */
    private double minPromoterHoldingPct = 60.0;
}