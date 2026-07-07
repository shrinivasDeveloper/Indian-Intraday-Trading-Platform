package com.trading.swing.controller;

import com.trading.swing.auto.service.AutoStockSelectionEngine;
import com.trading.swing.dto.BuySwingRequest;
import com.trading.swing.dto.InstrumentSearchResult;
import com.trading.swing.dto.SwingTradeResponse;
import com.trading.swing.service.ManualSwingOrderClient;
import com.trading.swing.service.ManualSwingTradingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * ManualSwingTradingController - a completely separate API surface
 * (/api/swing/**) from the existing dashboard controller. No shared
 * routes, no shared request/response types.
 */
@RestController
@RequestMapping("/api/swing")
@Slf4j
public class ManualSwingTradingController {

    private final ManualSwingTradingService service;
    private final AutoStockSelectionEngine autoSelectionEngine;

    public ManualSwingTradingController(ManualSwingTradingService service,
                                        AutoStockSelectionEngine autoSelectionEngine) {
        this.service = service;
        this.autoSelectionEngine = autoSelectionEngine;
    }

    /**
     * ADDED (per explicit request: "if not taken can we add the reason
     * in dashboard why its not taken"). Returns the most recent Auto
     * Swing selection run's summary - real rejection-stage counts and
     * the final outcome, so a "no trade today" result is genuinely
     * debuggable from the dashboard instead of only a server log line.
     * Returns null (empty body) if auto-selection hasn't run yet today.
     */
    @GetMapping("/auto-selection/last-run")
    public ResponseEntity<AutoStockSelectionEngine.SelectionRunSummary> getLastAutoSelectionRun() {
        return ResponseEntity.ok(autoSelectionEngine.getLastRunSummary());
    }

    @GetMapping("/instruments/search")
    public ResponseEntity<List<InstrumentSearchResult>> searchInstruments(
            @RequestParam("q") String query) {
        return ResponseEntity.ok(service.searchInstruments(query));
    }

    @PostMapping("/buy")
    public ResponseEntity<?> buy(@RequestBody BuySwingRequest request) {
        try {
            SwingTradeResponse result = service.placeBuy(request);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            log.warn("[SWING-API] BUY rejected (validation): {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", e.getMessage()));
        } catch (ManualSwingOrderClient.ManualSwingOrderException e) {
            log.error("[SWING-API] BUY failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "error", e.getMessage()));
        } catch (Exception e) {
            log.error("[SWING-API] BUY failed unexpectedly: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "error", "Unexpected error: " + e.getMessage()));
        }
    }

    @GetMapping("/trades")
    public ResponseEntity<List<SwingTradeResponse>> getTrades(
            @RequestParam(value = "filter", required = false, defaultValue = "ALL") String filter) {
        return ResponseEntity.ok(service.getTrades(filter));
    }
}