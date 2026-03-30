// ═══════════════════════════════════════════════════════════════════════════════
// FILE: src/main/java/com/trading/validation/SymbolValidationLog.java
// NEW FILE — no changes to existing code needed
// ═══════════════════════════════════════════════════════════════════════════════
package com.trading.validation;

import java.time.LocalTime;
import java.util.List;

/**
 * The latest validation run for one stock under one strategy.
 * Stored in StrategyValidationTracker and served via ValidationController.
 *
 * @param symbol           e.g. "RELIANCE"
 * @param strategy         e.g. "ORB_VWAP_SECTOR"
 * @param lastChecked      IST time of the most recent evaluation
 * @param direction        "BUY", "SELL", or "BOTH"
 * @param steps            ordered list of step results (step 1 = first condition)
 * @param failedAtStep     0 = all passed, N = failed at step N
 * @param firstFailedLabel human label of the first failed step (null if all pass)
 * @param totalChecksToday number of times this symbol was evaluated today
 * @param passesAllSteps   number of times all steps passed today (= signals fired)
 */
public record SymbolValidationLog(
        String                     symbol,
        String                     strategy,
        LocalTime                  lastChecked,
        String                     direction,
        List<ValidationStepResult> steps,
        int                        failedAtStep,
        String                     firstFailedLabel,
        int                        totalChecksToday,
        int                        passesAllSteps
) {}