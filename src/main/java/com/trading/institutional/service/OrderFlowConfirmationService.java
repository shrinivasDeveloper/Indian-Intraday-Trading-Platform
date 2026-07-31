package com.trading.institutional.service;

import com.trading.marketdata.service.MarketDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * OrderFlowConfirmationService — Institutional Confirmation Engine,
 * module 2 of 3 (per explicit user spec).
 *
 * OBJECTIVE: confirm that actual EXECUTED trades support a Momentum
 * BUY/SELL signal that has ALREADY been generated. This module NEVER
 * generates trades — Momentum's own scheduler, trading service, and
 * every existing gate (including the Order Book module) remain
 * completely untouched by this class.
 *
 * DATA SOURCE / HONEST METHOD NOTE: Zerodha's tick feed does not flag
 * each trade as buyer- or seller-initiated. This is inferred using the
 * standard TICK RULE (Lee-Ready style), already implemented in
 * MarketDataService.recordOrderFlow(): a trade at/above the best ask
 * is classified as an aggressive buy; at/below the best bid, an
 * aggressive sell; otherwise, price direction versus the prior tick is
 * used as the fallback. This is a well-established, standard technique
 * for approximating trade aggressor side from tick + depth data — not
 * a guess, but also not a certainty the exchange itself confirms;
 * stated plainly rather than oversold.
 *
 * Snapshots are sampled at ~1-second cadence (spec's own "update every
 * second"), each representing that second's incremental buy/sell
 * volume plus the running cumulative delta.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderFlowConfirmationService {

    private final MarketDataService marketData;

    private static final int MIN_SAMPLES_REQUIRED = 10;   // ~10 seconds of history minimum
    private static final int TREND_LOOKBACK = 3;           // "increasing over previous 3 updates"
    private static final int HH_LL_LOOKBACK = 15;          // ~15s window for Higher-High/Lower-Low
    private static final double IMBALANCE_RATIO_THRESHOLD = 1.5;
    private static final double CLIMAX_VOLUME_MULTIPLE = 3.0; // sample vol > 3x recent avg -> climax
    private static final double ABSORPTION_OPPOSING_VOLUME_MULTIPLE = 1.5; // opposing side heavy...
    private static final double ABSORPTION_MAX_PRICE_MOVE_PCT = 0.0008;    // ...yet price barely moved

    public enum Dominance { BUYERS_DOMINANT, SELLERS_DOMINANT, BALANCED }

    public record ConfirmationResult(Dominance dominance, int confidenceScore, List<String> reasonCodes) {}

    public ConfirmationResult validateSignal(String symbol, String direction) {
        boolean isLong = "LONG".equals(direction);
        List<String> reasons = new ArrayList<>();
        int score = 0;

        List<MarketDataService.OrderFlowSnapshot> history = marketData.getOrderFlowHistory(symbol);
        if (history.size() < MIN_SAMPLES_REQUIRED) {
            return new ConfirmationResult(Dominance.BALANCED, 0, List.of(
                    "NO_ORDERFLOW_DATA - only " + history.size() + " sample(s), need >=" +
                            MIN_SAMPLES_REQUIRED + " (~" + MIN_SAMPLES_REQUIRED + "s of history)"));
        }

        var latest = history.get(history.size() - 1);
        long delta = latest.buyVolume() - latest.sellVolume();
        double buyVsSellRatio = latest.sellVolume() > 0
                ? (double) latest.buyVolume() / latest.sellVolume() : Double.MAX_VALUE;
        double sellVsBuyRatio = latest.buyVolume() > 0
                ? (double) latest.sellVolume() / latest.buyVolume() : Double.MAX_VALUE;

        // Rising/falling cumulative delta - compare now vs a few
        // samples back.
        var deltaRef = history.get(Math.max(0, history.size() - 1 - TREND_LOOKBACK));
        boolean cumulativeDeltaRising = latest.cumulativeDelta() > deltaRef.cumulativeDelta();
        boolean cumulativeDeltaFalling = latest.cumulativeDelta() < deltaRef.cumulativeDelta();

        // "Increasing over previous 3 updates" - each of the last 3
        // samples' relevant volume must be >= the one before it.
        List<MarketDataService.OrderFlowSnapshot> lastN = history.subList(
                history.size() - TREND_LOOKBACK, history.size());
        boolean buyVolumeIncreasing = true, sellVolumeIncreasing = true;
        for (int i = 1; i < lastN.size(); i++) {
            if (lastN.get(i).buyVolume() < lastN.get(i - 1).buyVolume()) buyVolumeIncreasing = false;
            if (lastN.get(i).sellVolume() < lastN.get(i - 1).sellVolume()) sellVolumeIncreasing = false;
        }

        // Aggressive buyers/sellers dominance across a short recent window.
        List<MarketDataService.OrderFlowSnapshot> hhllWindow = history.subList(
                Math.max(0, history.size() - HH_LL_LOOKBACK), history.size());
        long windowBuyVol = hhllWindow.stream().mapToLong(MarketDataService.OrderFlowSnapshot::buyVolume).sum();
        long windowSellVol = hhllWindow.stream().mapToLong(MarketDataService.OrderFlowSnapshot::sellVolume).sum();
        boolean aggressiveBuyers = windowBuyVol > windowSellVol;
        boolean aggressiveSellers = windowSellVol > windowBuyVol;
        double windowRatio = windowSellVol > 0 ? (double) windowBuyVol / windowSellVol
                : (windowBuyVol > 0 ? Double.MAX_VALUE : 1.0);
        boolean strongBuyImbalance = windowRatio >= IMBALANCE_RATIO_THRESHOLD;
        boolean strongSellImbalance = windowSellVol > 0 && windowBuyVol > 0
                && (1.0 / windowRatio) >= IMBALANCE_RATIO_THRESHOLD;

        // Higher-High / Lower-Low over the same window (price field,
        // self-contained - no dependency on Momentum's candle buffer).
        double windowHighPrice = hhllWindow.stream().mapToDouble(MarketDataService.OrderFlowSnapshot::price).max().orElse(latest.price());
        double windowLowPrice = hhllWindow.stream().mapToDouble(MarketDataService.OrderFlowSnapshot::price).min().orElse(latest.price());
        boolean priceHigherHigh = latest.price() >= windowHighPrice;
        boolean priceLowerLow = latest.price() <= windowLowPrice;
        long windowDeltaHigh = hhllWindow.stream().mapToLong(MarketDataService.OrderFlowSnapshot::cumulativeDelta).max().orElse(latest.cumulativeDelta());
        long windowDeltaLow = hhllWindow.stream().mapToLong(MarketDataService.OrderFlowSnapshot::cumulativeDelta).min().orElse(latest.cumulativeDelta());
        boolean deltaHigherHigh = latest.cumulativeDelta() >= windowDeltaHigh;
        boolean deltaLowerLow = latest.cumulativeDelta() <= windowDeltaLow;

        // Absorption: the OPPOSING side traded heavily (>=1.5x its own
        // recent average) yet price barely moved - the confirming side
        // absorbed that supply/demand without giving ground.
        double avgBuyVol = hhllWindow.stream().mapToLong(MarketDataService.OrderFlowSnapshot::buyVolume).average().orElse(0);
        double avgSellVol = hhllWindow.stream().mapToLong(MarketDataService.OrderFlowSnapshot::sellVolume).average().orElse(0);
        double priceMovePct = deltaRef.price() > 0 ? Math.abs(latest.price() - deltaRef.price()) / deltaRef.price() : 1.0;
        boolean buyerAbsorption = avgSellVol > 0 && latest.sellVolume() > avgSellVol * ABSORPTION_OPPOSING_VOLUME_MULTIPLE
                && priceMovePct < ABSORPTION_MAX_PRICE_MOVE_PCT && latest.price() >= deltaRef.price();
        boolean sellerAbsorption = avgBuyVol > 0 && latest.buyVolume() > avgBuyVol * ABSORPTION_OPPOSING_VOLUME_MULTIPLE
                && priceMovePct < ABSORPTION_MAX_PRICE_MOVE_PCT && latest.price() <= deltaRef.price();

        // Exhaustion: price still extending in the trade's favor, but
        // the confirming side's own volume is FADING over the last 3
        // samples - interest drying up even as price pushes on.
        boolean buyingExhaustion = priceHigherHigh && !buyVolumeIncreasing
                && lastN.get(lastN.size() - 1).buyVolume() < lastN.get(0).buyVolume();
        boolean sellingExhaustion = priceLowerLow && !sellVolumeIncreasing
                && lastN.get(lastN.size() - 1).sellVolume() < lastN.get(0).sellVolume();

        // Climax: an extreme one-sample volume spike, often marking a
        // blow-off / capitulation rather than healthy continuation.
        boolean buyingClimax = avgBuyVol > 0 && latest.buyVolume() > avgBuyVol * CLIMAX_VOLUME_MULTIPLE;
        boolean sellingClimax = avgSellVol > 0 && latest.sellVolume() > avgSellVol * CLIMAX_VOLUME_MULTIPLE;

        // Delta divergence: price makes a new extreme in the trade's
        // direction, but cumulative delta does NOT confirm it.
        boolean deltaDivergenceOnBuy = priceHigherHigh && !deltaHigherHigh;
        boolean deltaDivergenceOnSell = priceLowerLow && !deltaLowerLow;

        if (isLong) {
            if (delta > 0) { score += 3; reasons.add("POSITIVE_DELTA(+3)"); }
            if (cumulativeDeltaRising) { score += 4; reasons.add("RISING_CUMULATIVE_DELTA(+4)"); }
            if (latest.buyVolume() > latest.sellVolume()) { score += 3; reasons.add("BUY_VOL_GT_SELL_VOL(+3)"); }
            if (buyVolumeIncreasing) { score += 3; reasons.add("BUY_VOLUME_INCREASING_3(+3)"); }
            if (aggressiveBuyers) { score += 4; reasons.add("AGGRESSIVE_BUYERS_LIFTING_ASK(+4)"); }
            if (strongBuyImbalance) { score += 4; reasons.add("STRONG_BUY_IMBALANCE(+4)"); }
            if (buyerAbsorption) { score += 4; reasons.add("BUYER_ABSORPTION(+4)"); }
            if (!sellerAbsorption) { score += 2; reasons.add("NO_SELLER_ABSORPTION(+2)"); }
            if (!buyingExhaustion) { score += 3; reasons.add("NO_BUYING_EXHAUSTION(+3)"); }
            if (priceHigherHigh) { score += 3; reasons.add("PRICE_HIGHER_HIGH(+3)"); }
            if (deltaHigherHigh) { score += 3; reasons.add("DELTA_HIGHER_HIGH(+3)"); }

            if (deltaDivergenceOnBuy) { score -= 6; reasons.add("REDUCE_DELTA_DIVERGENCE(-6)"); }
            if (buyingExhaustion) { score -= 5; reasons.add("REDUCE_BUYING_EXHAUSTION(-5)"); }
            if (sellerAbsorption) { score -= 5; reasons.add("REDUCE_SELLER_ABSORPTION_PRESENT(-5)"); }
            if (buyingClimax) { score -= 3; reasons.add("CAUTION_BUYING_CLIMAX(-3)"); }
        } else {
            if (delta < 0) { score += 3; reasons.add("NEGATIVE_DELTA(+3)"); }
            if (cumulativeDeltaFalling) { score += 4; reasons.add("FALLING_CUMULATIVE_DELTA(+4)"); }
            if (latest.sellVolume() > latest.buyVolume()) { score += 3; reasons.add("SELL_VOL_GT_BUY_VOL(+3)"); }
            if (sellVolumeIncreasing) { score += 3; reasons.add("SELL_VOLUME_INCREASING_3(+3)"); }
            if (aggressiveSellers) { score += 4; reasons.add("AGGRESSIVE_SELLERS_HITTING_BID(+4)"); }
            if (strongSellImbalance) { score += 4; reasons.add("STRONG_SELL_IMBALANCE(+4)"); }
            if (sellerAbsorption) { score += 4; reasons.add("SELLER_ABSORPTION(+4)"); }
            if (!buyerAbsorption) { score += 2; reasons.add("NO_BUYER_ABSORPTION(+2)"); }
            if (!sellingExhaustion) { score += 3; reasons.add("NO_SELLING_EXHAUSTION(+3)"); }
            if (priceLowerLow) { score += 3; reasons.add("PRICE_LOWER_LOW(+3)"); }
            if (deltaLowerLow) { score += 3; reasons.add("DELTA_LOWER_LOW(+3)"); }

            if (deltaDivergenceOnSell) { score -= 6; reasons.add("REDUCE_DELTA_DIVERGENCE(-6)"); }
            if (sellingExhaustion) { score -= 5; reasons.add("REDUCE_SELLING_EXHAUSTION(-5)"); }
            if (buyerAbsorption) { score -= 5; reasons.add("REDUCE_BUYER_ABSORPTION_PRESENT(-5)"); }
            if (sellingClimax) { score -= 3; reasons.add("CAUTION_SELLING_CLIMAX(-3)"); }
        }

        score = Math.max(0, Math.min(35, score));

        Dominance dominance;
        if (isLong && aggressiveBuyers && score >= 18) dominance = Dominance.BUYERS_DOMINANT;
        else if (!isLong && aggressiveSellers && score >= 18) dominance = Dominance.SELLERS_DOMINANT;
        else dominance = Dominance.BALANCED;

        log.info("[ORDER-FLOW] {} {} validation: dominance={} score={}/35 reasons={}",
                symbol, direction, dominance, score, reasons);
        return new ConfirmationResult(dominance, score, reasons);
    }
}