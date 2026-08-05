package com.trading.momentumstockofday.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MomentumGateStatusService — Dashboard gate visibility (per explicit
 * user request).
 *
 * PURPOSE: gives the dashboard a real-time, per-symbol, per-gate
 * PASS/FAIL view of the validation pipeline. This service NEVER makes
 * any trading decision — it only RECORDS what the existing, unchanged
 * logic elsewhere already decided, at the exact moment each gate is
 * evaluated. Every recording call is additive: a single line placed
 * immediately after an existing check, never replacing or altering
 * that check's own if/continue/return/throw behavior.
 *
 * TWO CATEGORIES OF GATES, per explicit design discussion with the
 * user:
 *   SCANNING gates (consolidation, positioning, prior-move, price-
 *     crossed, conviction, pullback V1-V4) - run every ~30s for every
 *     watchlist candidate regardless of whether a trade ever fires.
 *     These update live, continuously.
 *   ENTRY gates (structural risk, sizing, margin, trend filter, both
 *     S/R gates, fresh-price, order-book, volume-profile, order-flow)
 *     - only exist at the moment a signal fires and entry is
 *     attempted. For a symbol that hasn't signaled yet, these
 *     genuinely have NO result to show - PENDING is the honest state,
 *     not a fabricated PASS.
 *
 * Data is in-memory only (matches the real-time, session-scoped
 * nature of every other live dashboard panel in this app - candle
 * buffers, depth history, etc. are all in-memory too) - cleared once
 * per day alongside the scheduler's own daily reset.
 */
@Service
public class MomentumGateStatusService {

    public enum GateState { PASS, FAIL, PENDING }

    public record GateResult(GateState state, String reason, Instant updatedAt) {}

    /** symbol -> gateName -> result. LinkedHashMap per symbol
     *  preserves gate evaluation order for clean dashboard rendering. */
    private final Map<String, Map<String, GateResult>> statusBySymbol = new ConcurrentHashMap<>();

    public void record(String symbol, String gateName, boolean pass, String reason) {
        statusBySymbol
                .computeIfAbsent(symbol, k -> new ConcurrentHashMap<>())
                .put(gateName, new GateResult(pass ? GateState.PASS : GateState.FAIL,
                        reason, Instant.now()));
    }

    /** Force-resets every entry gate to PENDING for a symbol - called
     *  at the START of every entry attempt so a gate not reached this
     *  attempt (because an earlier gate already threw) correctly shows
     *  "not evaluated this time" instead of a stale PASS/FAIL left
     *  over from a previous, unrelated attempt. Uses unconditional
     *  put() deliberately - this method's entire purpose is to
     *  overwrite, not preserve, whatever was there before. */
    public void initEntryGatesPending(String symbol) {
        Map<String, GateResult> gates = statusBySymbol.computeIfAbsent(symbol, k -> new ConcurrentHashMap<>());
        for (String gateName : ENTRY_GATE_NAMES) {
            gates.put(gateName, new GateResult(GateState.PENDING,
                    "Not yet evaluated this attempt", Instant.now()));
        }
    }

    public static final String[] SCANNING_GATE_NAMES = {
            "CONSOLIDATION_FOUND", "POSITIONING", "PRIOR_MOVE", "PRICE_CROSSED_LEVEL",
            "CONVICTION_RANGE", "CONVICTION_VOLUME", "CONVICTION_CLOSE_STRENGTH",
            "PULLBACK_CONFLUENCE", "PULLBACK_TOUCH_NOT_BROKEN",
            "PULLBACK_REJECTION_CANDLE", "PULLBACK_VOLUME_CHARACTER"
    };

    public static final String[] ENTRY_GATE_NAMES = {
            "STRUCTURAL_RISK", "SKIP_RULE_TIER_CEILING", "NOISE_FLOOR", "POSITION_SIZING",
            "MARGIN_CHECK", "TREND_FILTER", "DAILY_SR_GATE", "THIRTY_MIN_SR_GATE",
            "FRESH_PRICE_CHECK", "ORDER_BOOK_GATE", "VOLUME_PROFILE_GATE", "ORDER_FLOW_GATE"
    };

    /** Full snapshot for the dashboard - symbol -> ordered gate map
     *  (scanning gates first, then entry gates, matching real
     *  execution order). */
    public Map<String, Map<String, GateResult>> getAllStatus() {
        Map<String, Map<String, GateResult>> snapshot = new LinkedHashMap<>();
        for (var entry : statusBySymbol.entrySet()) {
            Map<String, GateResult> ordered = new LinkedHashMap<>();
            for (String g : SCANNING_GATE_NAMES) {
                if (entry.getValue().containsKey(g)) ordered.put(g, entry.getValue().get(g));
            }
            for (String g : ENTRY_GATE_NAMES) {
                if (entry.getValue().containsKey(g)) ordered.put(g, entry.getValue().get(g));
            }
            snapshot.put(entry.getKey(), ordered);
        }
        return snapshot;
    }

    public Map<String, GateResult> getStatusFor(String symbol) {
        return statusBySymbol.getOrDefault(symbol, Map.of());
    }

    /** Daily reset hook - called once at market open alongside the
     *  scheduler's own existing daily reset, per the same pattern
     *  already used for pullbackLoggedToday etc. */
    public void resetDaily() {
        statusBySymbol.clear();
    }

    /** Removes a symbol's status when it's dropped from the
     *  watchlist by retention/rescan - keeps the dashboard from
     *  showing stale entries for stocks no longer being monitored. */
    public void removeSymbol(String symbol) {
        statusBySymbol.remove(symbol);
    }
}