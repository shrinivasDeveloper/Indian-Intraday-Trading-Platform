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
 * HARD-GATE MODEL (per explicit user request - REPLACES the original
 * scoring version): NO scoring. Every condition in the original
 * spec's BUY/SELL "award confidence for" list is now an individually
 * mandatory gate - ALL must be true. Every "reduce confidence"
 * condition is now an individual hard-fail trigger - ANY being true
 * causes immediate FAIL. Metric computation (delta, cumulative delta,
 * absorption, exhaustion, climax, divergence, HH/LL) is UNCHANGED from
 * the original version; only the decision mechanism changed.
 *
 * Data-source / tick-rule honesty note carried over unchanged from the
 * original version - see MarketDataService.recordOrderFlow().
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderFlowConfirmationService {

    private final MarketDataService marketData;

    private static final int MIN_SAMPLES_REQUIRED = 10;
    private static final int TREND_LOOKBACK = 3;
    private static final int HH_LL_LOOKBACK = 15;
    private static final double IMBALANCE_RATIO_THRESHOLD = 1.5;
    private static final double CLIMAX_VOLUME_MULTIPLE = 3.0;
    private static final double ABSORPTION_OPPOSING_VOLUME_MULTIPLE = 1.5;
    private static final double ABSORPTION_MAX_PRICE_MOVE_PCT = 0.0008;

    public enum Dominance { BUYERS_DOMINANT, SELLERS_DOMINANT, BALANCED }

    /** NO score field - per explicit user request, pure dominance +
     *  itemized reason codes (diagnostic only, not decision-driving). */
    public record ConfirmationResult(Dominance dominance, List<String> reasonCodes) {}

    public ConfirmationResult validateSignal(String symbol, String direction) {
        boolean isLong = "LONG".equals(direction);
        List<String> reasons = new ArrayList<>();

        List<MarketDataService.OrderFlowSnapshot> history = marketData.getOrderFlowHistory(symbol);
        if (history.size() < MIN_SAMPLES_REQUIRED) {
            return new ConfirmationResult(Dominance.BALANCED, List.of(
                    "FAIL_NO_ORDERFLOW_DATA - only " + history.size() + " sample(s), need >=" +
                            MIN_SAMPLES_REQUIRED));
        }

        var latest = history.get(history.size() - 1);
        long delta = latest.buyVolume() - latest.sellVolume();

        var deltaRef = history.get(Math.max(0, history.size() - 1 - TREND_LOOKBACK));
        boolean cumulativeDeltaRising = latest.cumulativeDelta() > deltaRef.cumulativeDelta();
        boolean cumulativeDeltaFalling = latest.cumulativeDelta() < deltaRef.cumulativeDelta();

        List<MarketDataService.OrderFlowSnapshot> lastN = history.subList(
                history.size() - TREND_LOOKBACK, history.size());
        boolean buyVolumeIncreasing = true, sellVolumeIncreasing = true;
        for (int i = 1; i < lastN.size(); i++) {
            if (lastN.get(i).buyVolume() < lastN.get(i - 1).buyVolume()) buyVolumeIncreasing = false;
            if (lastN.get(i).sellVolume() < lastN.get(i - 1).sellVolume()) sellVolumeIncreasing = false;
        }

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

        double windowHighPrice = hhllWindow.stream().mapToDouble(MarketDataService.OrderFlowSnapshot::price).max().orElse(latest.price());
        double windowLowPrice = hhllWindow.stream().mapToDouble(MarketDataService.OrderFlowSnapshot::price).min().orElse(latest.price());
        boolean priceHigherHigh = latest.price() >= windowHighPrice;
        boolean priceLowerLow = latest.price() <= windowLowPrice;
        long windowDeltaHigh = hhllWindow.stream().mapToLong(MarketDataService.OrderFlowSnapshot::cumulativeDelta).max().orElse(latest.cumulativeDelta());
        long windowDeltaLow = hhllWindow.stream().mapToLong(MarketDataService.OrderFlowSnapshot::cumulativeDelta).min().orElse(latest.cumulativeDelta());
        boolean deltaHigherHigh = latest.cumulativeDelta() >= windowDeltaHigh;
        boolean deltaLowerLow = latest.cumulativeDelta() <= windowDeltaLow;

        double avgBuyVol = hhllWindow.stream().mapToLong(MarketDataService.OrderFlowSnapshot::buyVolume).average().orElse(0);
        double avgSellVol = hhllWindow.stream().mapToLong(MarketDataService.OrderFlowSnapshot::sellVolume).average().orElse(0);
        double priceMovePct = deltaRef.price() > 0 ? Math.abs(latest.price() - deltaRef.price()) / deltaRef.price() : 1.0;
        boolean buyerAbsorption = avgSellVol > 0 && latest.sellVolume() > avgSellVol * ABSORPTION_OPPOSING_VOLUME_MULTIPLE
                && priceMovePct < ABSORPTION_MAX_PRICE_MOVE_PCT && latest.price() >= deltaRef.price();
        boolean sellerAbsorption = avgBuyVol > 0 && latest.buyVolume() > avgBuyVol * ABSORPTION_OPPOSING_VOLUME_MULTIPLE
                && priceMovePct < ABSORPTION_MAX_PRICE_MOVE_PCT && latest.price() <= deltaRef.price();

        boolean buyingExhaustion = priceHigherHigh && !buyVolumeIncreasing
                && lastN.get(lastN.size() - 1).buyVolume() < lastN.get(0).buyVolume();
        boolean sellingExhaustion = priceLowerLow && !sellVolumeIncreasing
                && lastN.get(lastN.size() - 1).sellVolume() < lastN.get(0).sellVolume();

        boolean deltaDivergenceOnBuy = priceHigherHigh && !deltaHigherHigh;
        boolean deltaDivergenceOnSell = priceLowerLow && !deltaLowerLow;

        boolean pass;
        if (isLong) {
            boolean c1 = delta > 0;                    reasons.add((c1?"PASS":"FAIL")+"_POSITIVE_DELTA");
            boolean c2 = cumulativeDeltaRising;         reasons.add((c2?"PASS":"FAIL")+"_RISING_CUMULATIVE_DELTA");
            boolean c3 = latest.buyVolume() > latest.sellVolume(); reasons.add((c3?"PASS":"FAIL")+"_BUY_VOL_GT_SELL_VOL");
            boolean c4 = buyVolumeIncreasing;           reasons.add((c4?"PASS":"FAIL")+"_BUY_VOLUME_INCREASING_3");
            boolean c5 = aggressiveBuyers;              reasons.add((c5?"PASS":"FAIL")+"_AGGRESSIVE_BUYERS_LIFTING_ASK");
            boolean c6 = strongBuyImbalance;            reasons.add((c6?"PASS":"FAIL")+"_STRONG_BUY_IMBALANCE");
            boolean c7 = buyerAbsorption;                reasons.add((c7?"PASS":"FAIL")+"_BUYER_ABSORPTION");
            boolean c8 = !sellerAbsorption;              reasons.add((c8?"PASS":"FAIL")+"_NO_SELLER_ABSORPTION");
            boolean c9 = !buyingExhaustion;              reasons.add((c9?"PASS":"FAIL")+"_NO_BUYING_EXHAUSTION");
            boolean c10 = priceHigherHigh;               reasons.add((c10?"PASS":"FAIL")+"_PRICE_HIGHER_HIGH");
            boolean c11 = deltaHigherHigh;                reasons.add((c11?"PASS":"FAIL")+"_DELTA_HIGHER_HIGH");

            boolean r1 = deltaDivergenceOnBuy; if (r1) reasons.add("HARDFAIL_DELTA_DIVERGENCE");
            boolean r2 = buyingExhaustion;     if (r2) reasons.add("HARDFAIL_BUYING_EXHAUSTION");
            boolean r3 = sellerAbsorption;     if (r3) reasons.add("HARDFAIL_SELLER_ABSORPTION_PRESENT");

            pass = c1 && c2 && c3 && c4 && c5 && c6 && c7 && c8 && c9 && c10 && c11
                    && !r1 && !r2 && !r3;
        } else {
            boolean c1 = delta < 0;                     reasons.add((c1?"PASS":"FAIL")+"_NEGATIVE_DELTA");
            boolean c2 = cumulativeDeltaFalling;         reasons.add((c2?"PASS":"FAIL")+"_FALLING_CUMULATIVE_DELTA");
            boolean c3 = latest.sellVolume() > latest.buyVolume(); reasons.add((c3?"PASS":"FAIL")+"_SELL_VOL_GT_BUY_VOL");
            boolean c4 = sellVolumeIncreasing;           reasons.add((c4?"PASS":"FAIL")+"_SELL_VOLUME_INCREASING_3");
            boolean c5 = aggressiveSellers;              reasons.add((c5?"PASS":"FAIL")+"_AGGRESSIVE_SELLERS_HITTING_BID");
            boolean c6 = strongSellImbalance;            reasons.add((c6?"PASS":"FAIL")+"_STRONG_SELL_IMBALANCE");
            boolean c7 = sellerAbsorption;                reasons.add((c7?"PASS":"FAIL")+"_SELLER_ABSORPTION");
            boolean c8 = !buyerAbsorption;                reasons.add((c8?"PASS":"FAIL")+"_NO_BUYER_ABSORPTION");
            boolean c9 = !sellingExhaustion;              reasons.add((c9?"PASS":"FAIL")+"_NO_SELLING_EXHAUSTION");
            boolean c10 = priceLowerLow;                  reasons.add((c10?"PASS":"FAIL")+"_PRICE_LOWER_LOW");
            boolean c11 = deltaLowerLow;                  reasons.add((c11?"PASS":"FAIL")+"_DELTA_LOWER_LOW");

            boolean r1 = deltaDivergenceOnSell; if (r1) reasons.add("HARDFAIL_DELTA_DIVERGENCE");
            boolean r2 = sellingExhaustion;     if (r2) reasons.add("HARDFAIL_SELLING_EXHAUSTION");
            boolean r3 = buyerAbsorption;       if (r3) reasons.add("HARDFAIL_BUYER_ABSORPTION_PRESENT");

            pass = c1 && c2 && c3 && c4 && c5 && c6 && c7 && c8 && c9 && c10 && c11
                    && !r1 && !r2 && !r3;
        }

        Dominance dominance = pass
                ? (isLong ? Dominance.BUYERS_DOMINANT : Dominance.SELLERS_DOMINANT)
                : Dominance.BALANCED;

        log.info("[ORDER-FLOW] {} {} hard-gate validation: dominance={} reasons={}",
                symbol, direction, dominance, reasons);
        return new ConfirmationResult(dominance, reasons);
    }
}