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
 * ══════════════════════════════════════════════════════════════════
 * FIXES (v7.2):
 * ══════════════════════════════════════════════════════════════════
 *
 * FIX 1 — "Best attempt" preservation (was: always overwrite with latest).
 *
 *   ROOT CAUSE:
 *     Original code used Map.put(symbol, newEntry) unconditionally.
 *     After 13:00 IST, ORBStrategy records TIME_WINDOW FAIL@1 every 5 minutes
 *     (once per candle × 294 stocks × ~48 candles between 13:00 and 15:25).
 *     By end-of-day every stock showed FAIL@1 at 15:25 — ALL morning evaluations
 *     (which reached FAIL@2, FAIL@4, FAIL@6, etc.) were silently lost.
 *
 *   FIX:
 *     "Best attempt" = the evaluation that advanced furthest through the pipeline.
 *     Depth is measured as follows:
 *       failedAtStep == 0   → all steps passed           (depth = MAX = Integer.MAX_VALUE)
 *       failedAtStep == N   → failed at step N            (depth = N)
 *     On each record() call:
 *       - If no existing record → always store.
 *       - If new record reached a DEEPER step than the existing one → store.
 *       - If new record is shallower (e.g. FAIL@1 vs existing FAIL@5) → KEEP EXISTING.
 *     Counters (totalChecksToday, passesAllSteps) are always incremented regardless.
 *     failureFrequency heatmap always counts every failure regardless.
 *
 *   RESULT:
 *     Dashboard now shows the MOST INFORMATIVE evaluation per stock, not the last.
 *     A stock that reached FAIL@6 (Liquidity) at 10:15 will still show that at 6 PM,
 *     even though 48 subsequent FAIL@1 (TIME_WINDOW) evaluations ran after 13:00.
 *
 * FIX 2 — Preserve last-passed record when a stock passes all steps intra-day.
 *
 *   If a stock PASSED all steps at some point and later fails (e.g. after it
 *   exits the time window), the all-pass record is preserved.
 *   Rule: an all-pass entry (failedAt=0) is NEVER overwritten by a failure entry.
 *
 * FIX 3 — Thread safety: counter increments are now atomic-safe regardless of
 *   whether record() returns early (counters extracted to separate update step).
 *
 * HOW IT WORKS (unchanged):
 *   Every time a strategy evaluates a stock (every 5-min candle), it calls
 *   tracker.record(strategy, symbol, direction, steps). The tracker updates
 *   failure frequency and check/pass counters on every call, then conditionally
 *   updates the displayed log entry using the depth-preserving logic above.
 *
 * RESET (unchanged): Clears at 9:15 IST daily.
 *
 * THREAD SAFETY: All maps are ConcurrentHashMap; AtomicInteger for counters.
 * ══════════════════════════════════════════════════════════════════
 */
@Service
@Slf4j
public class StrategyValidationTracker {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // strategy → symbol → BEST-DEPTH log (FIX 1: no longer unconditional overwrite)
    private final Map<String, Map<String, SymbolValidationLog>> latestLogs
            = new ConcurrentHashMap<>();

    // strategy → symbol → total evaluation count today
    private final Map<String, Map<String, AtomicInteger>> checkCounts
            = new ConcurrentHashMap<>();

    // strategy → symbol → count of times ALL steps passed today
    private final Map<String, Map<String, AtomicInteger>> passCounts
            = new ConcurrentHashMap<>();

    // "strategy:stepId" → total FAIL count today (for the heatmap)
    // Always incremented regardless of depth-preservation logic.
    private final Map<String, AtomicInteger> failureFrequency
            = new ConcurrentHashMap<>();

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Record the results of one evaluation cycle for a stock.
     *
     * Always:
     *   - Increments totalChecksToday counter.
     *   - Increments passesAllSteps counter if all steps passed.
     *   - Increments failureFrequency for the first failing step.
     *
     * Conditionally (FIX 1 + FIX 2):
     *   - Overwrites the stored log only if the new record is deeper (or equal depth).
     *   - Never overwrites an all-pass record with any failure.
     *
     * @param strategy  strategy name constant (e.g. "ORB_VWAP_SECTOR")
     * @param symbol    trading symbol (e.g. "RELIANCE")
     * @param direction "BUY", "SELL", or "BOTH"
     * @param steps     ordered list of ValidationStepResult objects
     */
    public void record(String strategy, String symbol,
                       String direction, List<ValidationStepResult> steps) {

        // ── Step 1: find first failing step ─────────────────────────────────
        int    failedAt  = 0;   // 0 = all passed
        String failLabel = null;
        for (ValidationStepResult s : steps) {
            if (!s.passed()) {
                failedAt  = s.stepNum();
                failLabel = s.label();
                // FIX 3: always count failures in heatmap regardless of depth logic
                String freqKey = strategy + ":" + s.stepId();
                failureFrequency
                        .computeIfAbsent(freqKey, k -> new AtomicInteger())
                        .incrementAndGet();
                break; // only the FIRST failure counts
            }
        }

        boolean allPassed = (failedAt == 0);

        // ── Step 2: update check / pass counters (always, FIX 3) ─────────────
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

        // ── Step 3: depth-preserving store (FIX 1 + FIX 2) ─────────────────
        Map<String, SymbolValidationLog> stratLogs =
                latestLogs.computeIfAbsent(strategy, k -> new ConcurrentHashMap<>());

        SymbolValidationLog existing = stratLogs.get(symbol);

        if (existing != null) {
            // FIX 2: never overwrite an all-pass record with a failure
            if (existing.failedAtStep() == 0 && !allPassed) {
                // Keep the all-pass record — just update its counter fields
                // by re-storing with updated check/pass counts but same steps
                stratLogs.put(symbol, new SymbolValidationLog(
                        existing.symbol(),
                        existing.strategy(),
                        existing.lastChecked(),   // preserve original time
                        existing.direction(),
                        existing.steps(),
                        existing.failedAtStep(),
                        existing.firstFailedLabel(),
                        checks,
                        passes
                ));
                return;
            }

            // FIX 1: only overwrite if new record is at least as deep
            // Depth: allPass=MAX, failAt N = N. Higher = deeper into pipeline.
            int existingDepth = existing.failedAtStep() == 0
                    ? Integer.MAX_VALUE
                    : existing.failedAtStep();
            int newDepth = allPassed
                    ? Integer.MAX_VALUE
                    : failedAt;

            if (newDepth < existingDepth) {
                // New record failed earlier — keep existing deeper record.
                // But still update the counter fields on the existing entry.
                stratLogs.put(symbol, new SymbolValidationLog(
                        existing.symbol(),
                        existing.strategy(),
                        existing.lastChecked(),   // preserve time of best attempt
                        existing.direction(),
                        existing.steps(),
                        existing.failedAtStep(),
                        existing.firstFailedLabel(),
                        checks,
                        passes
                ));
                return;
            }
            // new depth >= existing depth → fall through and overwrite
        }

        // ── Step 4: store the new record ────────────────────────────────────
        SymbolValidationLog entry = new SymbolValidationLog(
                symbol, strategy, LocalTime.now(IST),
                direction, List.copyOf(steps),
                failedAt, failLabel, checks, passes
        );
        stratLogs.put(symbol, entry);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Getters — called by ValidationController
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * All logs, grouped by strategy.
     *
     * Sort order (unchanged from original):
     *   1. All-pass entries first (failedAtStep == 0).
     *   2. Then by failedAtStep DESC (closest to a signal first).
     *   3. Tiebreak: symbol alphabetical.
     *
     * With FIX 1 active, "closest to a signal" now meaningfully reflects the
     * BEST attempt of the day — not just the last attempt.
     */
    public Map<String, List<SymbolValidationLog>> getAllLogs() {
        Map<String, List<SymbolValidationLog>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, SymbolValidationLog>> entry
                : latestLogs.entrySet()) {
            List<SymbolValidationLog> list = new ArrayList<>(entry.getValue().values());
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
     * Sorted descending so the most-failing step is first.
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
     * Total symbols tracked today across all strategies.
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