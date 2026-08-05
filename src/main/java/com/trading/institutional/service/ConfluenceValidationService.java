package com.trading.institutional.service;

import com.trading.marketdata.service.MarketDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * ConfluenceValidationService — Institutional Confirmation Engine,
 * final stage (per explicit user spec).
 *
 * HARD-GATE MODEL (per explicit user request - REPLACES the original
 * 40/35/25=100-point scoring version): NO scoring, NO weighted
 * calculations, NO probability-based decisions, NO compensating
 * threshold. Every confirmation module is now an independent MANDATORY
 * gate - Volume Profile, Order Flow, and Order Book must ALL
 * independently PASS (their own internal conditions are themselves
 * now strict AND-gates, see each module) for a trade to be allowed.
 * A trade is executed ONLY if every required gate passes; otherwise
 * it is rejected immediately. There is no "Trade Quality" tier system
 * anymore either - that concept was purely score-derived and has no
 * meaning without a score.
 *
 * This engine NEVER creates trading signals; it only validates a
 * BUY/SELL the Momentum Strategy has already produced. Momentum's own
 * scheduler, trading service, and every existing gate remain
 * completely untouched by this class.
 *
 * Two thresholds NOT specified in the original spec (liquidity
 * minimum, max spread) remain disclosed, clearly-labeled defaults -
 * unchanged from the original version, still hard gates (not scored).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConfluenceValidationService {

    private final MarketDataService marketData;
    private final VolumeProfileConfirmationService volumeProfileService;
    private final OrderFlowConfirmationService orderFlowService;

    private static final long MIN_LIQUIDITY_AGGREGATE_QTY = 500;
    private static final double MAX_SPREAD_PCT = 0.005; // 0.5% of mid-price

    public record ConfluenceResult(
            boolean executeAllowed,
            List<String> failReasons,
            VolumeProfileConfirmationService.ConfirmationResult volumeProfile,
            OrderFlowConfirmationService.ConfirmationResult orderFlow,
            OrderBookConfirmationService.ConfirmationResult orderBook) {}

    /**
     * Validate an ALREADY-GENERATED Momentum BUY/SELL signal.
     * ob: the Order Book result, computed ONCE by the caller (per the
     *   duplicate-evaluation fix) and passed in here rather than
     *   recomputed - unchanged from the prior deduplication fix.
     */
    public ConfluenceResult validate(String symbol, String direction, double breakoutPrice,
                                     List<Long> recentCandleVolumes, long breakoutCandleVolume,
                                     OrderBookConfirmationService.ConfirmationResult ob) {
        boolean isLong = "LONG".equals(direction);
        List<String> failReasons = new ArrayList<>();

        var vp = volumeProfileService.validateBreakout(symbol, direction, breakoutPrice,
                recentCandleVolumes, breakoutCandleVolume);
        var of = orderFlowService.validateSignal(symbol, direction);

        // ── Each module is an independent MANDATORY gate ──
        if (!vp.pass()) failReasons.add("VOLUME_PROFILE_GATE_FAILED");

        boolean directionConfirmed = isLong
                ? of.dominance() == OrderFlowConfirmationService.Dominance.BUYERS_DOMINANT
                : of.dominance() == OrderFlowConfirmationService.Dominance.SELLERS_DOMINANT;
        if (!directionConfirmed) failReasons.add("ORDER_FLOW_GATE_FAILED");

        if (ob.spoofDetected()) failReasons.add("ORDER_BOOK_SPOOFING_DETECTED");
        else if (!ob.pass()) failReasons.add("ORDER_BOOK_GATE_FAILED");

        // ── Fail-safe gates (unrelated thresholds not covered by the
        //    three modules themselves - liquidity, spread) ──
        var depth = marketData.getDepth(symbol);
        if (depth == null || depth.bids().isEmpty() || depth.asks().isEmpty()) {
            failReasons.add("NO_LIQUIDITY_DATA");
        } else {
            long liquidity = depth.bids().stream().mapToLong(l -> (long) l.quantity()).sum()
                    + depth.asks().stream().mapToLong(l -> (long) l.quantity()).sum();
            if (liquidity < MIN_LIQUIDITY_AGGREGATE_QTY) {
                failReasons.add(String.format("LIQUIDITY_BELOW_THRESHOLD(%d<%d)",
                        liquidity, MIN_LIQUIDITY_AGGREGATE_QTY));
            }
            double bestBid = depth.bids().get(0).price();
            double bestAsk = depth.asks().get(0).price();
            double mid = (bestBid + bestAsk) / 2.0;
            double spreadPct = mid > 0 ? (bestAsk - bestBid) / mid : 1.0;
            if (spreadPct > MAX_SPREAD_PCT) {
                failReasons.add(String.format("SPREAD_TOO_WIDE(%.3f%%>%.3f%%)",
                        spreadPct * 100, MAX_SPREAD_PCT * 100));
            }
        }

        // ── EXECUTION RULE: ALL gates must pass, no exceptions, no
        //    compensation between modules ──
        boolean executeAllowed = failReasons.isEmpty();

        log.info("[CONFLUENCE] {} {} - HARD-GATE: VP={} OF={}({}) OB={} executeAllowed={} " +
                        "failReasons={}", symbol, direction, vp.pass(), directionConfirmed, of.dominance(),
                ob.pass(), executeAllowed, failReasons);

        return new ConfluenceResult(executeAllowed, failReasons, vp, of, ob);
    }
}