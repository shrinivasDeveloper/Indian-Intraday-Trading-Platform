package com.trading.swing.auto.service;

import com.trading.swing.auto.domain.DailyBar;
import com.trading.swing.auto.repository.DailyBarRepository;
import com.trading.swing.config.ManualSwingConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * BhavcopyBackfillRunner - the actual @Async execution logic, in its
 * own, completely separate bean.
 *
 * FIX (found via TWO consecutive real deployment failures on the same
 * self-invocation problem): the original bug was BhavcopyBackfillService
 * calling its own @Async method directly, bypassing Spring's proxy.
 * The first fix attempt (@Lazy constructor self-injection) and the
 * second attempt (ApplicationContext.getBean() called from within
 * @PostConstruct) BOTH still triggered Spring's circular-reference
 * detection - a bean cannot safely resolve a reference to ITSELF while
 * still in the middle of its own @PostConstruct initialization, even
 * indirectly through ApplicationContext.
 *
 * The genuinely bulletproof fix: move the @Async method to a
 * COMPLETELY SEPARATE bean. There is no possible self-reference here
 * at all - BhavcopyBackfillService simply injects and calls a
 * different class's method, exactly like calling any other service,
 * with zero risk of any cycle.
 */
@Component
@Slf4j
public class BhavcopyBackfillRunner {

    private final NseDataClient nseClient;
    private final BhavcopyParser parser;
    private final DailyBarRepository repo;
    private final ManualSwingConfig config;

    private volatile boolean backfillInProgress = false;

    public BhavcopyBackfillRunner(NseDataClient nseClient, BhavcopyParser parser,
                                  DailyBarRepository repo, ManualSwingConfig config) {
        this.nseClient = nseClient;
        this.parser = parser;
        this.repo = repo;
        this.config = config;
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
        LocalDate cursor = LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(1); // start from yesterday, walk backward
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
}