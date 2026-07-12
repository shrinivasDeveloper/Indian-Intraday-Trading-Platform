package com.trading.swing.auto.service;

import com.trading.swing.auto.domain.DailyBar;
import com.trading.swing.auto.repository.DailyBarRepository;
import com.trading.swing.config.ManualSwingConfig;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * BhavcopyBackfillService - populates swing_auto_daily_bars with enough
 * trading history for sector/momentum analysis (Rule 1/2/3 need daily,
 * weekly, monthly, and yearly performance - roughly 252 trading days).
 *
 * FIX (found via direct user report, then TWO consecutive real
 * deployment failures on the same underlying problem): the original
 * bug was this class calling its own @Async method directly
 * (self-invocation), which bypasses Spring's proxy entirely - the
 * "async" backfill was actually running SYNCHRONOUSLY on the main
 * startup thread, blocking Spring Boot's entire startup sequence
 * (confirmed: 668 seconds instead of ~30) until the full 252-day
 * backfill completed.
 *
 * Two self-reference-based fix attempts (@Lazy constructor injection,
 * then ApplicationContext.getBean() called from within @PostConstruct)
 * BOTH still triggered Spring's circular-reference detection and
 * crashed the app entirely - a bean cannot safely resolve a reference
 * to ITSELF while still in the middle of its own @PostConstruct.
 *
 * The genuinely bulletproof fix: the actual @Async execution now lives
 * in a completely SEPARATE bean (BhavcopyBackfillRunner) - this class
 * simply injects and calls it, exactly like any other normal service
 * dependency, with zero possibility of any self-reference or cycle.
 *
 * Resumable: persists progress (earliest/latest date backfilled) so a
 * restart continues from where it left off rather than starting over.
 */
@Service
@Slf4j
public class BhavcopyBackfillService {

    private final NseDataClient nseClient;
    private final BhavcopyParser parser;
    private final DailyBarRepository repo;
    private final ManualSwingConfig config;
    private final BhavcopyBackfillRunner runner;

    public BhavcopyBackfillService(NseDataClient nseClient, BhavcopyParser parser,
                                   DailyBarRepository repo, ManualSwingConfig config,
                                   BhavcopyBackfillRunner runner) {
        this.nseClient = nseClient;
        this.parser = parser;
        this.repo = repo;
        this.config = config;
        this.runner = runner;
    }

    @PostConstruct
    public void init() {
        int existingDays = repo.countDistinctDatesStored();
        log.info("[BHAVCOPY-BACKFILL] {} trading day(s) of history already stored. Target: {} days.",
                existingDays, config.getBackfillTargetDays());
        if (existingDays < config.getBackfillTargetDays()) {
            // Genuinely a different bean - zero self-reference, zero
            // cycle risk. @Async on BhavcopyBackfillRunner correctly
            // takes effect since this is a real, external bean call.
            runner.startBackfillAsync();
        }
    }

    /**
     * Daily incremental fetch - just today's bhavcopy, called by
     * AutoSwingScheduler before running the selection engine each
     * afternoon. Cheap (one file), unlike the gradual historical backfill.
     */
    public void fetchToday() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        if (repo.hasDataForDate(today)) return;
        byte[] zipBytes = nseClient.downloadBhavcopy(today);
        if (zipBytes == null) {
            log.warn("[BHAVCOPY-BACKFILL] Could not fetch today's ({}) bhavcopy - sector/momentum " +
                    "analysis this cycle will use the most recent available data instead", today);
            return;
        }
        List<DailyBar> bars = parser.parse(zipBytes, today);
        if (!bars.isEmpty()) repo.saveAll(bars);
    }
}