package com.trading.dualentry.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DualEntryGateStatusService — Dashboard gate visibility for the
 * Dual-Entry strategy (per explicit user request), mirroring
 * MomentumGateStatusService's proven pattern exactly. Purely
 * observational - never makes any decision, only records what the
 * unchanged gate logic in DualEntryTradingService already decided.
 * Own, fully isolated in-memory map - zero shared state with
 * MomentumGateStatusService.
 */
@Service
public class DualEntryGateStatusService {

    public enum GateState { PASS, FAIL, PENDING }
    public record GateResult(GateState state, String reason, Instant updatedAt) {}

    private final Map<String, Map<String, GateResult>> statusBySymbol = new ConcurrentHashMap<>();

    public static final String[] GATE_NAMES = {
            "PRICE_BREAKOUT", "STRUCTURAL_RISK", "SKIP_RULE", "NOISE_FLOOR", "POSITION_SIZING",
            "MARGIN_CHECK", "TREND_FILTER", "DAILY_SR_GATE", "THIRTY_MIN_SR_GATE", "FRESH_PRICE_CHECK",
            "ORDER_BOOK_GATE", "MARKET_PROFILE_GATE", "VOLUME_PROFILE_GATE", "ORDER_FLOW_GATE"
    };

    public void record(String symbol, String gateName, boolean pass, String reason) {
        statusBySymbol.computeIfAbsent(symbol, k -> new ConcurrentHashMap<>())
                .put(gateName, new GateResult(pass ? GateState.PASS : GateState.FAIL, reason, Instant.now()));
    }

    /** Force-resets every gate to PENDING at the start of each entry
     *  attempt - same fix pattern already proven for Momentum's
     *  dashboard (unconditional put, not putIfAbsent, so a gate never
     *  reached this attempt never shows a stale prior result). */
    public void initGatesPending(String symbol) {
        Map<String, GateResult> gates = statusBySymbol.computeIfAbsent(symbol, k -> new ConcurrentHashMap<>());
        for (String g : GATE_NAMES) {
            gates.put(g, new GateResult(GateState.PENDING, "Not yet evaluated this attempt", Instant.now()));
        }
    }

    public Map<String, Map<String, GateResult>> getAllStatus() {
        Map<String, Map<String, GateResult>> snapshot = new LinkedHashMap<>();
        for (var entry : statusBySymbol.entrySet()) {
            Map<String, GateResult> ordered = new LinkedHashMap<>();
            for (String g : GATE_NAMES) if (entry.getValue().containsKey(g)) ordered.put(g, entry.getValue().get(g));
            snapshot.put(entry.getKey(), ordered);
        }
        return snapshot;
    }

    public void resetDaily() { statusBySymbol.clear(); }
    public void removeSymbol(String symbol) { statusBySymbol.remove(symbol); }
}