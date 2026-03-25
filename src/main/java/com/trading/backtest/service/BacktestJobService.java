package com.trading.backtest.service;

import com.trading.backtest.model.BacktestJob;
import com.trading.marketdata.service.InstrumentCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * BacktestJobService — manages async backtest jobs.
 *
 * Flow:
 *   1. submit() → creates BacktestJob with unique ID, launches @Async task
 *   2. @Async method runs in background thread (tradingExecutor pool)
 *   3. Caller polls GET /api/backtest/status/{id} for progress
 *   4. When done → GET /api/backtest/result/{id} returns full result
 *
 * Jobs are kept in memory for 2 hours then auto-cleared.
 * Max 2 concurrent jobs to protect Railway memory.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BacktestJobService {

    private final StrategyBacktestEngine engine;
    private final InstrumentCacheService instrumentCache;

    // Active + recent jobs — kept in memory
    private final Map<String, BacktestJob> jobs = new ConcurrentHashMap<>();

    private static final int MAX_CONCURRENT_JOBS = 2;
    private static final long JOB_RETENTION_SECS = 2 * 60 * 60; // 2 hours

    // ── Submit new job ────────────────────────────────────────────────

    public BacktestJob submit(LocalDate startDate, LocalDate endDate,
                              BigDecimal capital, List<String> strategies) {

        // Check concurrent limit
        long running = jobs.values().stream()
                .filter(j -> j.getStatus() == BacktestJob.Status.RUNNING).count();
        if (running >= MAX_CONCURRENT_JOBS) {
            throw new IllegalStateException(
                    "Max " + MAX_CONCURRENT_JOBS + " concurrent backtests allowed. " +
                            "Please wait for the current one to finish.");
        }

        // Clean up old jobs
        evictOldJobs();

        String jobId = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        BacktestJob job = new BacktestJob(jobId, startDate, endDate, capital, strategies);
        jobs.put(jobId, job);

        log.info("[BT-JOB] Submitted job={} from={} to={} strategies={}",
                jobId, startDate, endDate, strategies);

        // Launch in background — @Async returns immediately
        runAsync(job);

        return job;
    }

    /** Get job by ID */
    public Optional<BacktestJob> getJob(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    /** All jobs (for listing) */
    public List<BacktestJob> getAllJobs() {
        return new ArrayList<>(jobs.values());
    }

    // ── Background execution ──────────────────────────────────────────

    @Async("tradingExecutor")
    public void runAsync(BacktestJob job) {
        log.info("[BT-JOB] Starting job={}", job.getJobId());
        try {
            // Resolve Nifty500 symbols
            List<String> symbols = resolveNifty500Symbols();

            if (symbols.isEmpty()) {
                job.fail("Instrument cache is empty. Please ensure the app is authenticated " +
                        "and instruments are loaded before running backtest.");
                return;
            }

            log.info("[BT-JOB] job={} — {} Nifty500 symbols to process", job.getJobId(), symbols.size());

            // Run backtest (blocking, ~3-4 hours for 500 stocks × 1 year)
            StrategyBacktestEngine.StrategyBacktestResult result =
                    engine.runOnAllStocks(
                            symbols,
                            job.getStartDate(),
                            job.getEndDate(),
                            job.getCapital(),
                            job.getStrategies(),
                            job);

            job.finish(result);
            log.info("[BT-JOB] job={} DONE — {} trades in {}s",
                    job.getJobId(), result.totalTrades(), job.elapsedSeconds());

        } catch (Exception e) {
            log.error("[BT-JOB] job={} FAILED: {}", job.getJobId(), e.getMessage(), e);
            job.fail(e.getMessage());
        }
    }

    // ── Resolve Nifty500 symbols ──────────────────────────────────────

    private List<String> resolveNifty500Symbols() {
        List<Long> tokens = instrumentCache.buildNifty500Tokens();
        List<String> symbols = new ArrayList<>();
        for (Long token : tokens) {
            String sym = instrumentCache.getSymbol(token);
            if (sym == null || sym.isBlank()
                    || sym.startsWith("UNKNOWN")
                    || sym.contains(" ")      // index tokens e.g. "NIFTY 50"
                    || sym.contains("NIFTY")
                    || sym.contains("VIX")
                    || sym.contains("BANK")) {
                continue;
            }
            symbols.add(sym.toUpperCase());
        }
        return symbols.stream().distinct().sorted().collect(Collectors.toList());
    }

    // ── Evict old completed jobs ──────────────────────────────────────

    private void evictOldJobs() {
        long cutoff = System.currentTimeMillis() / 1000 - JOB_RETENTION_SECS;
        jobs.entrySet().removeIf(entry -> {
            BacktestJob j = entry.getValue();
            boolean expired = (j.getStatus() == BacktestJob.Status.DONE
                    || j.getStatus() == BacktestJob.Status.FAILED)
                    && j.getCreatedAt().getEpochSecond() < cutoff;
            if (expired) log.debug("[BT-JOB] Evicted old job={}", j.getJobId());
            return expired;
        });
    }
}