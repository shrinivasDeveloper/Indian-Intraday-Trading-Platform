// ═══════════════════════════════════════════════════════════════════════════════
// FILE: src/main/java/com/trading/validation/ValidationController.java
// NEW FILE — no changes to existing code needed
// ═══════════════════════════════════════════════════════════════════════════════
package com.trading.validation;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for the dashboard's Validation Debug tab.
 *
 * Endpoints:
 *   GET /api/validation/steps              → all logs grouped by strategy
 *   GET /api/validation/steps/{strategy}   → logs for one strategy
 *   GET /api/validation/failures           → step failure frequency heatmap
 *   GET /api/validation/summary            → quick stats (total symbols tracked)
 */
@RestController
@RequestMapping("/api/validation")
@RequiredArgsConstructor
public class ValidationController {

    private final StrategyValidationTracker tracker;

    // ─── GET /api/validation/steps ───────────────────────────────────────────
    // Returns: { "ORB_VWAP_SECTOR": [ { symbol, strategy, steps, failedAtStep, ... } ] }
    @GetMapping("/steps")
    public ResponseEntity<Map<String, List<SymbolValidationLog>>> getAllSteps() {
        return ResponseEntity.ok(tracker.getAllLogs());
    }

    // ─── GET /api/validation/steps/{strategy} ────────────────────────────────
    // Returns list of SymbolValidationLog for one strategy
    @GetMapping("/steps/{strategy}")
    public ResponseEntity<List<SymbolValidationLog>> getByStrategy(
            @PathVariable String strategy) {
        return ResponseEntity.ok(tracker.getByStrategy(strategy));
    }

    // ─── GET /api/validation/failures ────────────────────────────────────────
    // Returns: { "ORB_VWAP_SECTOR:NIFTY_BULLISH": 47, "ORB_VWAP_SECTOR:VOLUME": 23, ... }
    // Use this to build the "Most Common Failure" heatmap on the dashboard.
    @GetMapping("/failures")
    public ResponseEntity<Map<String, Integer>> getFailureFrequency() {
        return ResponseEntity.ok(tracker.getFailureFrequency());
    }

    // ─── GET /api/validation/summary ─────────────────────────────────────────
    // Returns lightweight summary for the header stat card
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getSummary() {
        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("totalSymbolsTracked", tracker.getTotalSymbolsTracked());
        summary.put("allLogsCount",        tracker.getAllLogs().values().stream()
                .mapToLong(List::size).sum());
        return ResponseEntity.ok(summary);
    }
}