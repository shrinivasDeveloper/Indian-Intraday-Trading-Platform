package com.trading.swing.auto.service;

import com.trading.swing.auto.domain.DailyBar;
import com.trading.swing.auto.repository.DailyBarRepository;
import com.trading.swing.config.ManualSwingConfig;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

/**
 * BhavcopyBackfillService - populates swing_auto_daily_bars with enough
 * trading history for sector/momentum analysis (Rule 1/2/3 need daily,
 * weekly, monthly, and yearly performance - roughly 252 trading days).
 *
 * Deliberately gradual, not a blocking startup operation: fetches ONE
 * trading day at a time with a configurable delay between requests, so
 * a fresh deployment doesn't hammer nseindia.com with ~252 rapid
 * requests (which would very plausibly get the server's IP temporarily
 * blocked - NSE's anti-bot measures are real and this is an unofficial
 * access pattern, not a stable contract).
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

    private volatile boolean backfillInProgress = false;

    public BhavcopyBackfillService(NseDataClient nseClient, BhavcopyParser parser,
                                   DailyBarRepository repo, ManualSwingConfig config) {
        this.nseClient = nseClient;
        this.parser = parser;
        this.repo = repo;
        this.config = config;
    }

    @PostConstruct
    public void init() {
        int existingDays = repo.countDistinctDatesStored();
        log.info("[BHAVCOPY-BACKFILL] {} trading day(s) of history already stored. Target: {} days.",
                existingDays, config.getBackfillTargetDays());
        if (existingDays < config.getBackfillTargetDays()) {
            startBackfillAsync();
        }
    }

    @Async("manualSwingBackfillExecutor")
    public void startBackfillAsync() {
        if (backfillInProgress) {
            log.debug("[BHAVCOPY-BACKFILL] Already running - skip duplicate start");
            return;
        }
        backfillInProgress = true;
        try {
            runBackfill();
        } finally {
            backfillInProgress = false;
        }
    }

    private void runBackfill() {
        LocalDate cursor = LocalDate.now().minusDays(1); // start from yesterday, walk backward
        int targetDays = config.getBackfillTargetDays();
        int fetched = 0;
        int consecutiveEmptyDays = 0;

        log.info("[BHAVCOPY-BACKFILL] Starting gradual backfill - target {} trading days, " +
                        "{}ms delay between requests (this will take a while by design, to stay " +
                        "respectful of NSE's servers - check back later for progress)",
                targetDays, config.getBackfillDelayMs());

        while (fetched < targetDays && consecutiveEmptyDays < 10) {
            if (cursor.getDayOfWeek() == DayOfWeek.SATURDAY || cursor.getDayOfWeek() == DayOfWeek.SUNDAY) {
                cursor = cursor.minusDays(1);
                continue;
            }
            if (repo.hasDataForDate(cursor)) {
                cursor = cursor.minusDays(1);
                fetched++; // already have this one, counts toward target
                continue;
            }

            try {
                byte[] zipBytes = nseClient.downloadBhavcopy(cursor);
                if (zipBytes != null) {
                    List<DailyBar> bars = parser.parse(zipBytes, cursor);
                    if (!bars.isEmpty()) {
                        repo.saveAll(bars);
                        fetched++;
                        consecutiveEmptyDays = 0;
                    } else {
                        consecutiveEmptyDays++; // likely a market holiday, not an error
                    }
                } else {
                    consecutiveEmptyDays++;
                }
            } catch (Exception e) {
                log.warn("[BHAVCOPY-BACKFILL] Failed for {}: {}", cursor, e.getMessage());
                consecutiveEmptyDays++;
            }

            cursor = cursor.minusDays(1);
            try { Thread.sleep(config.getBackfillDelayMs()); } catch (InterruptedException ignored) {}
        }

        log.info("[BHAVCOPY-BACKFILL] Backfill pass complete - {} trading day(s) now stored, " +
                "earliest date: {}", repo.countDistinctDatesStored(), repo.findEarliestDateStored());
    }

    /**
     * Daily incremental fetch - just today's bhavcopy, called by
     * AutoSwingScheduler before running the selection engine each
     * afternoon. Cheap (one file), unlike the gradual historical backfill.
     */
    public void fetchToday() {
        LocalDate today = LocalDate.now();
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