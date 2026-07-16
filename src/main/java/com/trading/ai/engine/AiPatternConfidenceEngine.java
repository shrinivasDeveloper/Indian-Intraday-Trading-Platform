package com.trading.ai.engine;

import com.trading.ai.model.AiCandidate;
import com.trading.domain.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "ai.trading.enabled", havingValue = "true")
@Slf4j
public class AiPatternConfidenceEngine {

    public record ConfidenceResult(
            int totalScore, int patternScore, int confirmationScore, int volumeScore,
            int trendScore, int priceActionScore, int vwapScore,
            int bullishPatterns, int bearishPatterns, String dominantPattern, String reason
    ) {
        public boolean meetsThreshold(int threshold) {
            return totalScore >= threshold;
        }
    }

    public ConfidenceResult score(AiCandidate candidate,
                                  List<Candle> daily,
                                  List<Candle> candles5m) {
        double[] f = candidate.getFeatureVector().getFeatures();
        String direction = candidate.getSuggestedDirection();
        int n = daily.size();

        PatternScore ps = computePatternScore(f, direction);
        if (ps.score == 0) {
            log.debug("[AI-CONF] {} {} GATE1 FAIL — no daily pattern confirmed",
                    candidate.getSymbol(), direction);
            return new ConfidenceResult(0, 0, 0, 0, 0, 0, 0,
                    0, 0, "NONE", "GATE1_FAIL: no daily pattern");
        }

        int n5 = candles5m != null ? candles5m.size() : 0;
        if (n5 >= 1) {
            Candle last5m  = candles5m.get(n5 - 1);
            double open5   = last5m.getOpen().doubleValue();
            double close5  = last5m.getClose().doubleValue();
            double body5   = Math.abs(close5 - open5);
            double range5  = last5m.getHigh().doubleValue() - last5m.getLow().doubleValue();
            double bodyRatio5 = range5 > 0 ? body5 / range5 : 0;

            boolean bull5m = close5 > open5 && bodyRatio5 > 0.25;
            boolean bear5m = close5 < open5 && bodyRatio5 > 0.25;

            boolean candleConfirms = "LONG".equals(direction)  ? bull5m
                    : "SHORT".equals(direction) ? bear5m
                    : false;

            if (!candleConfirms) {
                log.debug("[AI-CONF] {} {} GATE2 FAIL — 5m candle not confirming "
                                + "(5m open={} close={} bodyRatio={})",
                        candidate.getSymbol(), direction,
                        String.format("%.2f", open5),
                        String.format("%.2f", close5),
                        String.format("%.2f", bodyRatio5));
                return new ConfidenceResult(0, ps.score, 0, 0, 0, 0, 0,
                        ps.bullCount, ps.bearCount, ps.dominant,
                        "GATE2_FAIL: 5m candle not confirming direction");
            }

            // ══════════════════════════════════════════════════════════
            // MANDATORY GATE 3 — Intraday Momentum Consistency (NEW, per
            // explicit user request). Gate 2 above can pass on a single,
            // possibly noisy 5-min candle, with zero corroboration from
            // what happened just before it. This gate requires at least
            // 2 of the last 3 completed 5-min candles to be directionally
            // aligned with the trade - filtering lone-candle noise.
            //
            // Deliberately 2-of-3, not 3-of-3 or a longer streak: this
            // is an INSTANT check using candles that already exist the
            // moment the stock is evaluated - it never waits for a
            // future candle, so it adds zero entry delay and carries no
            // risk of missing a genuine, fast-developing move. A
            // stricter requirement (e.g. all 3, or a longer run) would
            // start rejecting valid fast moves where only the most
            // recent 1-2 candles show the real move just starting -
            // exactly the missed-opportunity risk this must avoid.
            if (n5 >= 3) {
                int alignedCount = 0;
                for (int c = n5 - 3; c < n5; c++) {
                    Candle candle = candles5m.get(c);
                    double cOpen  = candle.getOpen().doubleValue();
                    double cClose = candle.getClose().doubleValue();
                    boolean cBull = cClose > cOpen;
                    boolean cBear = cClose < cOpen;
                    if ("LONG".equals(direction)  && cBull) alignedCount++;
                    if ("SHORT".equals(direction) && cBear) alignedCount++;
                }
                if (alignedCount < 2) {
                    log.debug("[AI-CONF] {} {} GATE3 FAIL — only {}/3 recent 5m " +
                                    "candles aligned with {}", candidate.getSymbol(),
                            direction, alignedCount, direction);
                    return new ConfidenceResult(0, ps.score, 0, 0, 0, 0, 0,
                            ps.bullCount, ps.bearCount, ps.dominant,
                            "GATE3_FAIL: intraday momentum not consistent (" +
                                    alignedCount + "/3 candles aligned)");
                }
            }
            // If fewer than 3 candles exist yet, Gate 3 is skipped
            // (same fail-open principle as Gate 2 above, which also
            // only runs when n5 >= 1) - never blocks a trade purely due
            // to insufficient early-session history.
        }

        int confirm = computeConfirmationScore(daily, direction, n);
        int vol = computeVolumeScore(daily, direction, n);
        int trend = computeTrendScore(daily, direction, n);
        int priceAction = computePriceActionScore(f, daily, direction, n);
        int vwapBonus = computeVwapScore(candles5m, direction);

        int total = ps.score + confirm + vol + trend + priceAction + vwapBonus;
        total = Math.min(100, Math.max(0, total));

        String reason = String.format(
                "Pattern=%d/50 Candle=%d/20 Vol=%d/10 Trend=%d/10 PA=%d/10 VWAP=%d/5 [%s]",
                ps.score, confirm, vol, trend, priceAction, vwapBonus, ps.dominant);

        log.debug("[AI-CONF] {} {} total={} | {}", candidate.getSymbol(), direction, total, reason);

        return new ConfidenceResult(total, ps.score, confirm, vol, trend, priceAction, vwapBonus,
                ps.bullCount, ps.bearCount, ps.dominant, reason);
    }

    private int computeVwapScore(List<Candle> candles5m, String direction) {
        try {
            if (candles5m == null || candles5m.isEmpty()) return 0;
            double cumPV = 0, cumVol = 0;
            for (Candle c : candles5m) {
                double typicalPrice = (c.getHigh().doubleValue()
                        + c.getLow().doubleValue()
                        + c.getClose().doubleValue()) / 3.0;
                double v = c.getVolume();
                cumPV  += typicalPrice * v;
                cumVol += v;
            }
            if (cumVol <= 0) return 0;
            double vwap = cumPV / cumVol;
            double ltp  = candles5m.get(candles5m.size() - 1).getClose().doubleValue();

            double pctPast;
            if ("LONG".equals(direction)) {
                if (ltp <= vwap) return 0;
                pctPast = (ltp - vwap) / vwap;
            } else if ("SHORT".equals(direction)) {
                if (ltp >= vwap) return 0;
                pctPast = (vwap - ltp) / vwap;
            } else {
                return 0;
            }

            if (pctPast > 0.008) return 5;
            if (pctPast > 0.003) return 3;
            if (pctPast > 0.0)   return 1;
            return 0;
        } catch (Exception e) {
            return 0;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // COMPONENT 1: CLEAN PATTERN STRUCTURE (0–50)
    // ═══════════════════════════════════════════════════════════════════════

    record PatternScore(int score, int bullCount, int bearCount, String dominant) {}

    /**
     * FIX (per explicit user request, made PERMANENT: "understand
     * market context and fix permanently, I will not change this
     * logic in future").
     *
     * PROBLEM FOUND: the previous version awarded the full 50/50
     * points for ANY single confirmed pattern, with zero distinction
     * between a lone signal and genuine multi-pattern confluence -
     * bullCount/bearCount were tracked but never actually used in the
     * score.
     *
     * WHY A RAW PATTERN COUNT WOULD STILL BE WRONG: not all 15
     * patterns are methodologically independent. detectOrderBlock()
     * internally REQUIRES a BOS condition before it even looks for an
     * order block - so BOS + OrderBlock both firing is largely the
     * SAME underlying structural break counted twice, not two
     * independent confirmations. CHOCH is itself a type of structure
     * break related to BOS; FVG typically forms during the same
     * impulsive move that causes a BOS. A naive count would let 3
     * correlated SMC-family patterns (describing one single event)
     * outscore a genuinely stronger case: 1 SMC pattern + 1 Wyckoff
     * pattern + 1 classical chart pattern agreeing - three truly
     * independent methodologies.
     *
     * THE FIX: patterns are grouped into 6 methodologically-
     * independent FAMILIES. The score is driven by how many DISTINCT
     * FAMILIES confirm, not raw pattern count - this is what genuine
     * confluence actually means, and correctly can't be inflated by
     * multiple correlated patterns from the same underlying event.
     *
     *   Family 1 (SMC Structure):   BOS, CHOCH, OrderBlock, FVG
     *   Family 2 (Liquidity):       SweepLow, SweepHigh, SRFlip
     *   Family 3 (Wyckoff):         Accum/Dist
     *   Family 4 (Chart Patterns):  TriplePattern, H&S
     *   Family 5 (Trend Geometry):  Triangle, Channel, TrendlineD, TrendlineI
     *   Family 6 (Supply/Demand):   S/D Zone
     *
     * SCORE CURVE (diminishing returns, not linear - the first family
     * already qualified the trade via the earlier discovery-stage
     * gate requiring at least 1 pattern; each additional INDEPENDENT
     * family adds genuine but decreasing marginal confidence):
     *   1 family confirmed  -> 30/50 (60%) - a single methodology,
     *                          already gated elsewhere, not yet
     *                          independently corroborated
     *   2 families confirmed -> 40/50 (80%) - genuine cross-methodology
     *                          agreement
     *   3+ families confirmed -> 50/50 (100%) - strong, rare, genuine
     *                          multi-methodology confluence (3 of 6
     *                          total families is a deliberately
     *                          meaningful bar, not an easy one)
     */
    private PatternScore computePatternScore(double[] f, String direction) {
        if (f.length < 70) return new PatternScore(0, 0, 0, "NONE");

        // Pattern priority order — first confirmed pattern wins the
        // "dominant" label for dashboard display. Order reflects SMC
        // hierarchy - unchanged from before.
        Object[][] patternDefs = {
                {60, "BOS",           1}, {61, "CHOCH",         1},
                {62, "OrderBlock",    1}, {63, "FVG",           1},
                {64, "Accum/Dist",    3},
                {47, "S/D Zone",      6},
                {65, "TriplePattern", 4}, {66, "H&S",           4},
                {67, "Triangle",      5}, {68, "Channel",       5},
                {69, "TrendlineD",    5},
                {54, "SweepLow",      2}, {55, "SweepHigh",     2},
                {56, "SRFlip",        2}, {58, "TrendlineI",    5},
        };

        boolean isLong = "LONG".equals(direction);
        int bullCount = 0, bearCount = 0;
        String dominant = "NONE";

        java.util.Set<Integer> bullFamilies = new java.util.HashSet<>();
        java.util.Set<Integer> bearFamilies = new java.util.HashSet<>();

        for (Object[] pd : patternDefs) {
            int    idx    = (int)    pd[0];
            String name   = (String) pd[1];
            int    family = (int)    pd[2];
            if (idx >= f.length) continue;
            double val = f[idx];

            if (isLong && val > 0.5) {
                bullCount++;
                bullFamilies.add(family);
                if (dominant.equals("NONE")) dominant = name;
            }
            if (!isLong && val < -0.5) {
                bearCount++;
                bearFamilies.add(family);
                if (dominant.equals("NONE")) dominant = name;
            }
        }

        int distinctFamilies = isLong ? bullFamilies.size() : bearFamilies.size();

        int score = switch (Math.min(distinctFamilies, 3)) {
            case 0 -> 0;   // no pattern confirmed at all - GATE1 fails upstream
            case 1 -> 30;  // single methodology - real, but not independently corroborated
            case 2 -> 40;  // two independent methodologies agree
            default -> 50; // 3+ independent methodologies - genuine, strong confluence
        };

        return new PatternScore(score, bullCount, bearCount, dominant);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // COMPONENT 2: CONFIRMATION CANDLE QUALITY (0–20)
    // ═══════════════════════════════════════════════════════════════════════

    private int computeConfirmationScore(List<Candle> daily, String direction, int n) {
        if (n < 5) return 0;
        Candle last = daily.get(n - 1);

        double open  = last.getOpen().doubleValue();
        double close = last.getClose().doubleValue();
        double high  = last.getHigh().doubleValue();
        double low   = last.getLow().doubleValue();
        double body  = Math.abs(close - open);
        double range = high - low;
        if (range == 0) return 0;
        double bodyRatio = body / range;

        double atr = 0;
        for (int i = Math.max(0, n - 10); i < n - 1; i++) {
            atr += daily.get(i).getHigh().doubleValue() - daily.get(i).getLow().doubleValue();
        }
        atr /= Math.min(10, n - 1);

        int score = 0;

        boolean candleBull = close > open;
        boolean candleBear = close < open;
        if ("LONG".equals(direction)  && candleBull) score += 8;
        if ("SHORT".equals(direction) && candleBear) score += 8;

        if (bodyRatio > 0.5) score += 4;
        else if (bodyRatio > 0.3) score += 2;

        double closePos = range > 0 ? (close - low) / range : 0.5;
        if ("LONG".equals(direction)  && closePos > 0.6) score += 4;
        if ("SHORT".equals(direction) && closePos < 0.4) score += 4;

        if (atr > 0 && body < atr * 2) score += 4;

        return Math.min(20, score);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // COMPONENT 3: VOLUME VALIDATION (0–10)
    // ═══════════════════════════════════════════════════════════════════════

    private int computeVolumeScore(List<Candle> daily, String direction, int n) {
        if (n < 11) return 5;

        double avgVol = 0;
        for (int i = n - 11; i < n - 1; i++) {
            avgVol += daily.get(i).getVolume();
        }
        avgVol /= 10;
        if (avgVol == 0) return 0;

        double lastVol  = daily.get(n - 1).getVolume();

        int score = 0;

        if (lastVol > avgVol * 1.3) score += 5;
        else if (lastVol > avgVol) score += 3;
        else if (lastVol > avgVol * 0.7) score += 1;

        double upVol = 0, downVol = 0;
        int upCnt = 0, downCnt = 0;
        for (int i = n - 10; i < n; i++) {
            double v  = daily.get(i).getVolume();
            double c  = daily.get(i).getClose().doubleValue();
            double o  = daily.get(i).getOpen().doubleValue();
            if (c > o) { upVol += v; upCnt++; }
            else        { downVol += v; downCnt++; }
        }
        double avgUpVol   = upCnt   > 0 ? upVol   / upCnt   : 1;
        double avgDownVol = downCnt > 0 ? downVol / downCnt : 1;

        if ("LONG".equals(direction)  && avgUpVol > avgDownVol * 1.2) score += 5;
        else if ("LONG".equals(direction) && avgUpVol > avgDownVol)   score += 3;
        if ("SHORT".equals(direction) && avgDownVol > avgUpVol * 1.2) score += 5;
        else if ("SHORT".equals(direction) && avgDownVol > avgUpVol)  score += 3;

        return Math.min(10, score);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // COMPONENT 4: TREND ALIGNMENT (0–10)
    // ═══════════════════════════════════════════════════════════════════════

    private int computeTrendScore(List<Candle> daily, String direction, int n) {
        if (n < 16) return 5;

        double price15dAgo = daily.get(n - 16).getClose().doubleValue();
        double priceNow    = daily.get(n - 1).getClose().doubleValue();
        if (price15dAgo == 0) return 5;

        double ret15d = (priceNow - price15dAgo) / price15dAgo;

        double ema15 = 0;
        double k = 2.0 / 16;
        for (int i = n - 16; i < n; i++) {
            double c = daily.get(i).getClose().doubleValue();
            ema15 = i == n - 16 ? c : c * k + ema15 * (1 - k);
        }
        boolean emaRising = priceNow > ema15;

        int score = 0;

        if ("LONG".equals(direction)) {
            if (ret15d > 0.03 && emaRising)  score += 10;
            else if (ret15d > 0 && emaRising) score += 7;
            else if (ret15d > -0.05)          score += 4;
            else                              score += 2;
        }

        if ("SHORT".equals(direction)) {
            if (ret15d < -0.03 && !emaRising) score += 10;
            else if (ret15d < 0 && !emaRising) score += 7;
            else if (ret15d < 0.05)             score += 4;
            else                                score += 2;
        }

        return Math.min(10, score);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // COMPONENT 5: PRICE ACTION QUALITY (0–10)
    // ═══════════════════════════════════════════════════════════════════════

    private int computePriceActionScore(double[] f, List<Candle> daily,
                                        String direction, int n) {
        if (n < 20) return 5;

        int score = 0;

        if (f.length > 56 && f[56] > 0.5) score += 2;

        double atrSum = 0;
        for (int i = n - 10; i < n; i++) {
            atrSum += daily.get(i).getHigh().doubleValue() - daily.get(i).getLow().doubleValue();
        }
        double avgAtr = atrSum / 10;
        double lastAtr = daily.get(n-1).getHigh().doubleValue() - daily.get(n-1).getLow().doubleValue();
        if (avgAtr > 0 && lastAtr < avgAtr * 2.0) score += 3;

        if (f.length > 59 && f[59] > 0.66) score += 3;
        else if (f.length > 59 && f[59] > 0.33) score += 1;

        if (f.length > 57) {
            double cp = f[57];
            if ("LONG".equals(direction)  && cp < -0.2) score += 2;
            if ("SHORT".equals(direction) && cp > 0.2)  score += 2;
        }

        return Math.min(10, score);
    }
}