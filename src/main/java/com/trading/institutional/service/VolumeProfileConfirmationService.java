package com.trading.institutional.service;

import com.trading.marketdata.service.MarketDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * VolumeProfileConfirmationService — Institutional Confirmation Engine,
 * module 1 of 3 (per explicit user spec).
 *
 * HARD-GATE MODEL (per explicit user request - REPLACES the original
 * scoring version): NO scoring, NO weighted points, NO probability
 * threshold. Every single condition listed in the original spec's
 * "award confidence for" list is now an INDIVIDUALLY MANDATORY gate -
 * ALL must be true for PASS. Every condition in the "reduce confidence
 * if" list is now an INDIVIDUAL HARD-FAIL trigger - ANY being true
 * causes immediate FAIL, regardless of everything else. This is
 * intentionally strict: with 10 mandatory BUY conditions all required
 * simultaneously, genuine passes will be rare by design, not a bug -
 * this was confirmed explicitly with the user before implementing.
 *
 * OBJECTIVE, DATA SOURCE, and ACCEPTANCE-PROXY notes are UNCHANGED
 * from the original version; only the DECISION mechanism (scoring ->
 * strict AND-gate) changed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VolumeProfileConfirmationService {

    private final MarketDataService marketData;

    private static final double VALUE_AREA_PCT = 0.70;
    private static final double HVN_MULTIPLE = 1.5;
    private static final double LVN_MULTIPLE = 0.5;
    private static final int ACCEPTANCE_LOOKBACK_SAMPLES = 3;
    private static final int DEVELOPING_POC_SHIFT_LOOKBACK = 5;

    public record VolumeProfileMetrics(double poc, double vah, double val,
                                       List<Double> hvnLevels, List<Double> lvnLevels,
                                       double totalSessionVolume, double bucketSize) {}

    /** NO score field - per explicit user request, pure PASS/FAIL with
     *  itemized reason codes for diagnostic visibility only (reason
     *  codes do NOT feed into any decision, they only explain it). */
    public record ConfirmationResult(boolean pass, List<String> reasonCodes) {}

    public VolumeProfileMetrics computeProfile(String symbol) {
        Map<Double, Long> histogram = marketData.getVolumeProfile(symbol);
        if (histogram.isEmpty()) return null;

        long totalVolume = histogram.values().stream().mapToLong(Long::longValue).sum();
        if (totalVolume <= 0) return null;

        double poc = histogram.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(0.0);

        List<Double> sortedPrices = new ArrayList<>(histogram.keySet());
        Collections.sort(sortedPrices);
        int pocIdx = sortedPrices.indexOf(poc);
        int lowIdx = pocIdx, highIdx = pocIdx;
        long accumulated = histogram.get(poc);
        while (accumulated < totalVolume * VALUE_AREA_PCT
                && (lowIdx > 0 || highIdx < sortedPrices.size() - 1)) {
            long belowVol = lowIdx > 0 ? histogram.getOrDefault(sortedPrices.get(lowIdx - 1), 0L) : -1;
            long aboveVol = highIdx < sortedPrices.size() - 1
                    ? histogram.getOrDefault(sortedPrices.get(highIdx + 1), 0L) : -1;
            if (aboveVol >= belowVol && highIdx < sortedPrices.size() - 1) {
                highIdx++;
                accumulated += histogram.get(sortedPrices.get(highIdx));
            } else if (lowIdx > 0) {
                lowIdx--;
                accumulated += histogram.get(sortedPrices.get(lowIdx));
            } else {
                break;
            }
        }
        double val = sortedPrices.get(lowIdx);
        double vah = sortedPrices.get(highIdx);

        double meanBucketVol = histogram.values().stream().mapToLong(Long::longValue).average().orElse(0);
        List<Double> hvn = new ArrayList<>();
        List<Double> lvn = new ArrayList<>();
        if (meanBucketVol > 0) {
            for (var e : histogram.entrySet()) {
                if (e.getValue() > meanBucketVol * HVN_MULTIPLE) hvn.add(e.getKey());
                else if (e.getValue() < meanBucketVol * LVN_MULTIPLE) lvn.add(e.getKey());
            }
        }
        Collections.sort(hvn);
        Collections.sort(lvn);

        double bucketSize = marketData.getVolumeProfileBucketSize(symbol);
        return new VolumeProfileMetrics(poc, vah, val, hvn, lvn, totalVolume, bucketSize);
    }

    public ConfirmationResult validateBreakout(String symbol, String direction, double breakoutPrice,
                                               List<Long> recentCandleVolumes, long breakoutCandleVolume) {
        boolean isLong = "LONG".equals(direction);
        List<String> reasons = new ArrayList<>();

        VolumeProfileMetrics m = computeProfile(symbol);
        if (m == null) {
            return new ConfirmationResult(false, List.of("FAIL_NO_VOLUME_PROFILE_DATA"));
        }

        List<MarketDataService.PocSnapshot> pocHistory = marketData.getDevelopingPocHistory(symbol);
        boolean pocShiftedFavorably = false;
        if (pocHistory.size() > DEVELOPING_POC_SHIFT_LOOKBACK) {
            double pocNow = pocHistory.get(pocHistory.size() - 1).poc();
            double pocBefore = pocHistory.get(pocHistory.size() - 1 - DEVELOPING_POC_SHIFT_LOOKBACK).poc();
            pocShiftedFavorably = isLong ? pocNow > pocBefore : pocNow < pocBefore;
        }
        boolean acceptedAbovePoc = false, acceptedAboveVah = false,
                acceptedBelowPoc = false, acceptedBelowVal = false;
        boolean rejectedFromLevel = false;
        if (pocHistory.size() >= ACCEPTANCE_LOOKBACK_SAMPLES) {
            List<MarketDataService.PocSnapshot> lastN = pocHistory.subList(
                    pocHistory.size() - ACCEPTANCE_LOOKBACK_SAMPLES, pocHistory.size());
            acceptedAbovePoc = lastN.stream().allMatch(s -> s.poc() > m.poc());
            acceptedAboveVah = lastN.stream().allMatch(s -> s.poc() > m.vah());
            acceptedBelowPoc = lastN.stream().allMatch(s -> s.poc() < m.poc());
            acceptedBelowVal = lastN.stream().allMatch(s -> s.poc() < m.val());
            rejectedFromLevel = isLong
                    ? lastN.stream().allMatch(s -> s.poc() <= m.vah())
                    : lastN.stream().allMatch(s -> s.poc() >= m.val());
        }

        double avgRecentVol = recentCandleVolumes.isEmpty() ? 0 :
                recentCandleVolumes.stream().mapToLong(Long::longValue).average().orElse(0);
        boolean volumeExceedsAvg = avgRecentVol > 0 && breakoutCandleVolume > avgRecentVol;

        boolean throughLvn = m.lvnLevels().stream()
                .anyMatch(l -> isLong ? (l > m.poc() && l <= breakoutPrice)
                        : (l < m.poc() && l >= breakoutPrice));
        boolean movingAwayFromHvn = m.hvnLevels().stream()
                .noneMatch(l -> Math.abs(l - breakoutPrice) <= m.bucketSize() * 2);
        boolean trappedInHvn = m.hvnLevels().stream()
                .anyMatch(l -> Math.abs(l - breakoutPrice) <= m.bucketSize());
        boolean outsideValueArea = isLong ? breakoutPrice > m.vah() : breakoutPrice < m.val();
        boolean trappedInValueArea = breakoutPrice <= m.vah() && breakoutPrice >= m.val();

        boolean pass;
        if (isLong) {
            boolean c1 = breakoutPrice > m.poc();                reasons.add((c1?"PASS":"FAIL")+"_PRICE_ABOVE_POC");
            boolean c2 = acceptedAbovePoc;                       reasons.add((c2?"PASS":"FAIL")+"_ACCEPTED_ABOVE_POC");
            boolean c3 = breakoutPrice > m.vah();                reasons.add((c3?"PASS":"FAIL")+"_PRICE_ABOVE_VAH");
            boolean c4 = acceptedAboveVah;                       reasons.add((c4?"PASS":"FAIL")+"_ACCEPTED_ABOVE_VAH");
            boolean c5 = pocShiftedFavorably;                    reasons.add((c5?"PASS":"FAIL")+"_DEVELOPING_POC_SHIFTING_UP");
            boolean c6 = throughLvn;                             reasons.add((c6?"PASS":"FAIL")+"_EXPANDS_THROUGH_LVN");
            boolean c7 = throughLvn && acceptedAbovePoc;         reasons.add((c7?"PASS":"FAIL")+"_LVN_TO_ACCEPTANCE_TRANSITION");
            boolean c8 = outsideValueArea;                       reasons.add((c8?"PASS":"FAIL")+"_OUTSIDE_VALUE_AREA");
            boolean c9 = volumeExceedsAvg;                       reasons.add((c9?"PASS":"FAIL")+"_VOLUME_EXCEEDS_AVG5");
            boolean c10 = movingAwayFromHvn && acceptedAbovePoc; reasons.add((c10?"PASS":"FAIL")+"_MOVING_AWAY_FROM_HVN");

            boolean r1 = breakoutPrice < m.poc();  if (r1) reasons.add("HARDFAIL_PRICE_BELOW_POC");
            boolean r2 = rejectedFromLevel;        if (r2) reasons.add("HARDFAIL_REPEATED_REJECTION_FROM_VAH");
            boolean r3 = trappedInValueArea;       if (r3) reasons.add("HARDFAIL_TRAPPED_INSIDE_VALUE_AREA");
            boolean r4 = trappedInHvn;             if (r4) reasons.add("HARDFAIL_TRAPPED_INSIDE_HVN");
            boolean r5 = !volumeExceedsAvg;        if (r5) reasons.add("HARDFAIL_WEAK_BREAKOUT_VOLUME");

            pass = c1 && c2 && c3 && c4 && c5 && c6 && c7 && c8 && c9 && c10
                    && !r1 && !r2 && !r3 && !r4 && !r5;
        } else {
            boolean c1 = breakoutPrice < m.poc();                reasons.add((c1?"PASS":"FAIL")+"_PRICE_BELOW_POC");
            boolean c2 = acceptedBelowPoc;                       reasons.add((c2?"PASS":"FAIL")+"_ACCEPTED_BELOW_POC");
            boolean c3 = breakoutPrice < m.val();                reasons.add((c3?"PASS":"FAIL")+"_PRICE_BELOW_VAL");
            boolean c4 = acceptedBelowVal;                       reasons.add((c4?"PASS":"FAIL")+"_ACCEPTED_BELOW_VAL");
            boolean c5 = pocShiftedFavorably;                    reasons.add((c5?"PASS":"FAIL")+"_DEVELOPING_POC_SHIFTING_DOWN");
            boolean c6 = throughLvn;                             reasons.add((c6?"PASS":"FAIL")+"_EXPANDS_THROUGH_LVN");
            boolean c7 = outsideValueArea;                       reasons.add((c7?"PASS":"FAIL")+"_OUTSIDE_VALUE_AREA");
            boolean c8 = volumeExceedsAvg;                       reasons.add((c8?"PASS":"FAIL")+"_VOLUME_EXCEEDS_AVG");
            boolean c9 = movingAwayFromHvn && acceptedBelowPoc;  reasons.add((c9?"PASS":"FAIL")+"_MOVING_AWAY_FROM_HVN");

            boolean r1 = breakoutPrice > m.poc();  if (r1) reasons.add("HARDFAIL_PRICE_ABOVE_POC");
            boolean r2 = rejectedFromLevel;        if (r2) reasons.add("HARDFAIL_REPEATED_REJECTION_FROM_VAL");
            boolean r3 = trappedInValueArea;       if (r3) reasons.add("HARDFAIL_TRAPPED_INSIDE_VALUE_AREA");
            boolean r4 = !volumeExceedsAvg;        if (r4) reasons.add("HARDFAIL_WEAK_BREAKDOWN_VOLUME");

            pass = c1 && c2 && c3 && c4 && c5 && c6 && c7 && c8 && c9
                    && !r1 && !r2 && !r3 && !r4;
        }

        log.info("[VOLUME-PROFILE] {} {} hard-gate validation: pass={} reasons={}",
                symbol, direction, pass, reasons);
        return new ConfirmationResult(pass, reasons);
    }
}