package com.trading.backtest.model;

import com.trading.backtest.service.StrategyBacktestEngine;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * BacktestJob — tracks state of a single async backtest run.
 *
 * States: QUEUED → RUNNING → DONE / FAILED
 */
@Getter
public class BacktestJob {

    public enum Status { QUEUED, RUNNING, DONE, FAILED }

    private final String    jobId;
    private final LocalDate startDate;
    private final LocalDate endDate;
    private final BigDecimal capital;
    private final List<String> strategies;
    private final Instant   createdAt;

    @Setter private volatile Status  status    = Status.QUEUED;
    @Setter private volatile String  error     = null;

    // Progress tracking
    private final AtomicInteger processedSymbols = new AtomicInteger(0);
    private volatile int totalSymbols = 0;
    private volatile String currentSymbol = "—";

    // Result (set when DONE)
    @Setter private volatile StrategyBacktestEngine.StrategyBacktestResult result = null;

    private volatile Instant startedAt  = null;
    private volatile Instant finishedAt = null;

    public BacktestJob(String jobId, LocalDate startDate, LocalDate endDate,
                       BigDecimal capital, List<String> strategies) {
        this.jobId     = jobId;
        this.startDate = startDate;
        this.endDate   = endDate;
        this.capital   = capital;
        this.strategies= strategies;
        this.createdAt = Instant.now();
    }

    public void start(int total) {
        this.status       = Status.RUNNING;
        this.totalSymbols = total;
        this.startedAt    = Instant.now();
    }

    public void tickSymbol(String symbol) {
        this.currentSymbol = symbol;
        this.processedSymbols.incrementAndGet();
    }

    public void finish(StrategyBacktestEngine.StrategyBacktestResult r) {
        this.result      = r;
        this.status      = Status.DONE;
        this.finishedAt  = Instant.now();
    }

    public void fail(String err) {
        this.error      = err;
        this.status     = Status.FAILED;
        this.finishedAt = Instant.now();
    }

    /** 0–100 */
    public int progressPct() {
        if (totalSymbols == 0) return 0;
        return Math.min(100, processedSymbols.get() * 100 / totalSymbols);
    }

    /** Estimated seconds remaining */
    public long etaSeconds() {
        if (startedAt == null || processedSymbols.get() == 0) return -1;
        long elapsed = Instant.now().getEpochSecond() - startedAt.getEpochSecond();
        double secPerSymbol = (double) elapsed / processedSymbols.get();
        int remaining = totalSymbols - processedSymbols.get();
        return (long)(remaining * secPerSymbol);
    }

    public long elapsedSeconds() {
        if (startedAt == null) return 0;
        Instant end = finishedAt != null ? finishedAt : Instant.now();
        return end.getEpochSecond() - startedAt.getEpochSecond();
    }
}