// ═══════════════════════════════════════════════════════════════════════════════
// FILE: src/main/java/com/trading/validation/StrategyValidationTracker.java
// NEW FILE — no changes to existing code needed
// ═══════════════════════════════════════════════════════════════════════════════
package com.trading.validation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Central in-memory store for per-symbol, per-strategy validation step results.
 *
 * HOW IT WORKS:
 * Every time a strategy evaluates a stock (every 5 min candle), it calls
 * tracker.record(strategy, symbol, direction, steps).  The tracker stores
 * the LATEST evaluation per symbol, updates counters, and tracks failure
 * frequency so the dashboard can show which step fails most often.
 *
 * RESET: Clears at 9:15 IST daily so only today's data is shown.
 *
 * THREAD SAFETY: All maps are ConcurrentHashMap; AtomicInteger for counters.
 */
@Service
@Slf4j
public class StrategyValidationTracker {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // strategy → symbol → latest log
    private final Map<String, Map<String, SymbolValidationLog>> latestLogs
            = new ConcurrentHashMap<>();

    // strategy → symbol → total evaluation count today
    private final Map<String, Map<String, AtomicInteger>> checkCounts
            = new ConcurrentHashMap<>();

    // strategy → symbol → count of times ALL steps passed today
    private final Map<String, Map<String, AtomicInteger>> passCounts
            = new ConcurrentHashMap<>();

    // "strategy:stepId" → total FAIL count today (for the heatmap)
    private final Map<String, AtomicInteger> failureFrequency
            = new ConcurrentHashMap<>();

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Record the results of one evaluation cycle for a stock.
     *
     * @param strategy  strategy name constant (e.g. "ORB_VWAP_SECTOR")
     * @param symbol    trading symbol (e.g. "RELIANCE")
     * @param direction "BUY", "SELL", or "BOTH"
     * @param steps     ordered list of ValidationStepResult objects
     */
    public void record(String strategy, String symbol,
                       String direction, List<ValidationStepResult> steps) {

        // Find the first failing step
        int    failedAt   = 0;
        String failLabel  = null;
        for (ValidationStepResult s : steps) {
            if (!s.passed()) {
                failedAt  = s.stepNum();
                failLabel = s.label();
                // Increment failure frequency for this strategy+step
                String freqKey = strategy + ":" + s.stepId();
                failureFrequency
                        .computeIfAbsent(freqKey, k -> new AtomicInteger())
                        .incrementAndGet();
                break; // only count the FIRST failure
            }
        }

        boolean allPassed = (failedAt == 0);

        // Update check / pass counters
        Map<String, AtomicInteger> stratChecks =
                checkCounts.computeIfAbsent(strategy, k -> new ConcurrentHashMap<>());
        Map<String, AtomicInteger> stratPasses =
                passCounts.computeIfAbsent(strategy, k -> new ConcurrentHashMap<>());

        int checks = stratChecks
                .computeIfAbsent(symbol, k -> new AtomicInteger())
                .incrementAndGet();
        int passes = allPassed
                ? stratPasses.computeIfAbsent(symbol, k -> new AtomicInteger()).incrementAndGet()
                : stratPasses.computeIfAbsent(symbol, k -> new AtomicInteger()).get();

        SymbolValidationLog logEntry = new SymbolValidationLog(
                symbol, strategy, LocalTime.now(IST),
                direction, List.copyOf(steps),
                failedAt, failLabel, checks, passes
        );

        latestLogs
                .computeIfAbsent(strategy, k -> new ConcurrentHashMap<>())
                .put(symbol, logEntry);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Getters — called by ValidationController
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * All logs, grouped by strategy.
     * Each strategy's list is sorted: fully-passing stocks first, then by
     * failedAtStep ascending (= stocks that fail latest = closest to firing).
     */
    public Map<String, List<SymbolValidationLog>> getAllLogs() {
        Map<String, List<SymbolValidationLog>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, SymbolValidationLog>> entry
                : latestLogs.entrySet()) {
            List<SymbolValidationLog> list = new ArrayList<>(entry.getValue().values());
            // Sort: all-pass first (failedAt=0), then by failedAtStep DESC
            // (stocks that fail LATEST are closest to a signal → show them first)
            list.sort(Comparator
                    .comparingInt((SymbolValidationLog l) ->
                            l.failedAtStep() == 0 ? Integer.MAX_VALUE : l.failedAtStep())
                    .reversed()
                    .thenComparing(SymbolValidationLog::symbol));
            result.put(entry.getKey(), list);
        }
        return result;
    }

    /**
     * Logs for one strategy only.
     */
    public List<SymbolValidationLog> getByStrategy(String strategy) {
        Map<String, SymbolValidationLog> logs =
                latestLogs.getOrDefault(strategy, Map.of());
        List<SymbolValidationLog> list = new ArrayList<>(logs.values());
        list.sort(Comparator
                .comparingInt((SymbolValidationLog l) ->
                        l.failedAtStep() == 0 ? Integer.MAX_VALUE : l.failedAtStep())
                .reversed()
                .thenComparing(SymbolValidationLog::symbol));
        return list;
    }

    /**
     * Failure frequency map: "strategy:stepId" → count.
     * Sorted descending by count so the most-failing step is first.
     */
    public Map<String, Integer> getFailureFrequency() {
        Map<String, Integer> result = new LinkedHashMap<>();
        failureFrequency.entrySet().stream()
                .sorted(Map.Entry.<String, AtomicInteger>comparingByValue(
                        Comparator.comparingInt(AtomicInteger::get)).reversed())
                .forEach(e -> result.put(e.getKey(), e.getValue().get()));
        return result;
    }

    /**
     * Total number of symbols being tracked today (all strategies combined).
     */
    public int getTotalSymbolsTracked() {
        return (int) latestLogs.values().stream()
                .mapToLong(m -> m.size())
                .sum();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Daily reset at 9:15 IST — clears all data from previous trading day
    // ─────────────────────────────────────────────────────────────────────────

    @Scheduled(cron = "0 15 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyReset() {
        latestLogs.clear();
        checkCounts.clear();
        passCounts.clear();
        failureFrequency.clear();
        log.info("[ValidationTracker] Daily reset complete — tracking cleared for new session");
    }
}