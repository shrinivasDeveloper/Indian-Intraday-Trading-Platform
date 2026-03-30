// ═══════════════════════════════════════════════════════════════════════════════
// FILE: src/main/java/com/trading/validation/ValidationStepResult.java
// NEW FILE — no changes to existing code needed
// ═══════════════════════════════════════════════════════════════════════════════
package com.trading.validation;

/**
 * A single step in a strategy's validation pipeline.
 *
 * @param stepNum  1-based step number (0 = pre-check)
 * @param stepId   machine-readable ID used in JS filtering (e.g. "NIFTY_BULLISH")
 * @param label    human-readable label shown in the dashboard
 * @param passed   true = PASS, false = FAIL
 * @param detail   diagnostic detail string (price values, ratios, etc.)
 */
public record ValidationStepResult(
        int    stepNum,
        String stepId,
        String label,
        boolean passed,
        String detail
) {}