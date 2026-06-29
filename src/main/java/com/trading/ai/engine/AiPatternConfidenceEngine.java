package com.trading.ai.engine;

import com.trading.ai.model.AiCandidate;
import com.trading.domain.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AiPatternConfidenceEngine — 100-point confidence scoring for daily patterns.
 *
 * Replaces the multi-layer composite system as the PRIMARY scoring mechanism.
 * Score is based purely on STOCK-SPECIFIC price action — no market regime,
 * no Nifty direction, no sector direction used as score inputs.
 *
 * Score breakdown:
 *   1. Clean Pattern Structure   = 50 pts — how well the pattern fits its definition
 *   2. Confirmation Candle       = 20 pts — latest candle validates the setup
 *   3. Volume Validation         = 10 pts — last 10 candles volume supports pattern
 *   4. Trend Alignment           = 10 pts — last 15 days trend supports formation
 *   5. Price Action Quality      = 10 pts — S/R behavior, chart cleanliness
 *
 * Total = 100 pts. Minimum to trade: 75 (TRENDING), 85 (RANGING).
 * CHOPPY: no execution regardless of score.
 */
@Component
@ConditionalOnProperty(name = "ai.trading.enabled", havingValue = "true")
@Slf4j
public class AiPatternConfidenceEngine {

    public record ConfidenceResult(
            int totalScore,            // 0–100 (up to 105 internally before the 100 cap)
            int patternScore,          // 0–50
            int confirmationScore,     // 0–20
            int volumeScore,           // 0–10
            int trendScore,            // 0–10
            int priceActionScore,      // 0–10
            int vwapScore,             // 0–5  NEW — see Component 6 below
            int bullishPatterns,       // count of confirmed bullish daily patterns
            int bearishPatterns,       // count of confirmed bearish daily patterns
            String dominantPattern,    // name of strongest pattern detected
            String reason              // short explanation
    ) {
        public boolean meetsThreshold(int threshold) {
            return totalScore >= threshold;
        }
    }

    /**
     * Compute 100-point confidence score for a candidate.
     *
     * @param candidate  The AI candidate with feature vector (f[0]-f[79])
     * @param daily      1-year daily candles for this stock
     * @return ConfidenceResult with breakdown
     */
    public ConfidenceResult score(AiCandidate candidate,
                                  List<Candle> daily,
                                  List<Candle> candles5m) {
        double[] f = candidate.getFeatureVector().getFeatures();
        String direction = candidate.getSuggestedDirection();
        int n = daily.size();

        // ══════════════════════════════════════════════════════════════════
        // MANDATORY GATE 1 — At least 1 confirmed daily pattern required.
        // If no daily pattern is detected, return score = 0 immediately.
        // No pattern = no trade. The pattern is the qualification.
        // ══════════════════════════════════════════════════════════════════
        PatternScore ps = computePatternScore(f, direction);
        if (ps.score == 0) {
            log.debug("[AI-CONF] {} {} GATE1 FAIL — no daily pattern confirmed",
                    candidate.getSymbol(), direction);
            return new ConfidenceResult(0, 0, 0, 0, 0, 0, 0,
                    0, 0, "NONE", "GATE1_FAIL: no daily pattern");
        }

        // ══════════════════════════════════════════════════════════════════
        // MANDATORY GATE 2 — Latest 5-minute candle must confirm direction.
        // Daily pattern qualifies the stock (Gate 1).
        // 5-minute candle confirms the precise entry timing (Gate 2).
        //
        // LONG:  latest 5m candle must close above open (bullish 5m candle)
        // SHORT: latest 5m candle must close below open (bearish 5m candle)
        //
        // A doji or counter-direction 5m candle = entry not ready yet.
        // Stock stays on watchlist. Next cycle re-evaluates with new 5m candle.
        // ══════════════════════════════════════════════════════════════════
        int n5 = candles5m != null ? candles5m.size() : 0;
        if (n5 >= 1) {
            Candle last5m  = candles5m.get(n5 - 1);
            double open5   = last5m.getOpen().doubleValue();
            double close5  = last5m.getClose().doubleValue();
            double body5   = Math.abs(close5 - open5);
            double range5  = last5m.getHigh().doubleValue() - last5m.getLow().doubleValue();
            double bodyRatio5 = range5 > 0 ? body5 / range5 : 0;

            // Require at least 25% body ratio — eliminates dojis and indecision candles
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
        }

        // Both gates passed — compute remaining components
        // ── Component 2: Confirmation Candle (0–20) ─────────────────────────
        int confirm = computeConfirmationScore(daily, direction, n);

        // ── Component 3: Volume Validation (0–10) ───────────────────────────
        int vol = computeVolumeScore(daily, direction, n);

        // ── Component 4: Trend Alignment (0–10) ─────────────────────────────
        int trend = computeTrendScore(daily, direction, n);

        // ── Component 5: Price Action Quality (0–10) ────────────────────────
        int priceAction = computePriceActionScore(f, daily, direction, n);

        // ── Component 6: VWAP Alignment (0–5) — NEW, purely additive ────────
        // Deliberately a BONUS ONLY, never a penalty — misalignment scores 0,
        // never negative. This means nothing that previously qualified can
        // now fail purely because of VWAP; it can only help a marginal setup
        // clear the threshold. total is still capped at 100 below, so an
        // already-maxed setup is completely unaffected — this only matters
        // for candidates currently below 100.
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

    /**
     * Component 6: VWAP Alignment (0–5, bonus only, never a penalty).
     * Intraday VWAP computed from candles5m (typical price × volume,
     * cumulative from market open). Proportional to actual distance past
     * VWAP, not flat — rewards genuine separation more than a marginal
     * graze (added when scaling to a 500+ stock universe, where a binary
     * bonus stopped meaningfully differentiating quality):
     *   >0.8% past VWAP in trade direction  → +5 (strong, clear conviction)
     *   0.3–0.8% past                        → +3 (decent separation)
     *   0.0–0.3% past                        → +1 (barely there, weak)
     *   Not aligned, or no data               → +0 — never subtracted.
     */
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

            // UPDATED: proportional to actual distance from VWAP, not flat.
            // With a 500+ stock universe, a binary "aligned = +5" treats a
            // stock barely 0.05% past VWAP the same as one with 1%+ clear
            // separation — exactly the kind of noise a much larger candidate
            // pool will surface more of. Scaling by distance means only
            // genuine, clear separation earns the full bonus; a marginal
            // graze gets a small nudge, not the same reward.
            double pctPast;
            if ("LONG".equals(direction)) {
                if (ltp <= vwap) return 0; // not aligned — unchanged, no bonus
                pctPast = (ltp - vwap) / vwap;
            } else if ("SHORT".equals(direction)) {
                if (ltp >= vwap) return 0; // not aligned — unchanged, no bonus
                pctPast = (vwap - ltp) / vwap;
            } else {
                return 0;
            }

            if (pctPast > 0.008) return 5; // >0.8% clear — strong, clear conviction
            if (pctPast > 0.003) return 3; // 0.3–0.8% — decent separation
            if (pctPast > 0.0)   return 1; // 0.0–0.3% — barely there, weak signal
            return 0;
        } catch (Exception e) {
            return 0; // any failure — no bonus, no penalty, no impact on existing scoring
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // COMPONENT 1: CLEAN PATTERN STRUCTURE (0–50)
    // RULE: Any single confirmed daily pattern = immediate 50/50 points.
    // The pattern itself is the qualification. The remaining 4 components
    // (candle, volume, trend, price action) validate the quality of the entry.
    // The dominant pattern name is recorded for dashboard display.
    // ═══════════════════════════════════════════════════════════════════════

    record PatternScore(int score, int bullCount, int bearCount, String dominant) {}

    private PatternScore computePatternScore(double[] f, String direction) {
        if (f.length < 70) return new PatternScore(0, 0, 0, "NONE");

        // Pattern priority order — first confirmed pattern wins the 50 points
        // and its name is recorded as dominant. Order reflects SMC hierarchy.
        Object[][] patternDefs = {
                // f index, name
                {60, "BOS"},
                {61, "CHOCH"},
                {62, "OrderBlock"},
                {63, "FVG"},
                {64, "Accum/Dist"},
                {47, "S/D Zone"},
                {65, "TriplePattern"},
                {66, "H&S"},
                {67, "Triangle"},
                {68, "Channel"},
                {69, "TrendlineD"},
                {54, "SweepLow"},
                {55, "SweepHigh"},
                {56, "SRFlip"},
                {58, "TrendlineI"},
        };

        boolean isLong = "LONG".equals(direction);
        int bullCount = 0, bearCount = 0;
        String dominant = "NONE";

        for (Object[] pd : patternDefs) {
            int    idx  = (int)    pd[0];
            String name = (String) pd[1];
            if (idx >= f.length) continue;
            double val = f[idx];

            if (isLong && val > 0.5)  { bullCount++; if (dominant.equals("NONE")) dominant = name; }
            if (!isLong && val < -0.5) { bearCount++; if (dominant.equals("NONE")) dominant = name; }
        }

        // Any single confirmed pattern = full 50 points immediately
        boolean anyPattern = isLong ? bullCount > 0 : bearCount > 0;
        int score = anyPattern ? 50 : 0;

        return new PatternScore(score, bullCount, bearCount, dominant);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // COMPONENT 2: CONFIRMATION CANDLE QUALITY (0–20)
    // Latest daily candle must validate the setup.
    // Checks: size vs ATR, close position, direction, no doji/exhaustion.
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
        double bodyRatio = body / range; // 0 = doji, 1 = marubozu

        // ATR from last 10 candles
        double atr = 0;
        for (int i = Math.max(0, n - 10); i < n - 1; i++) {
            atr += daily.get(i).getHigh().doubleValue() - daily.get(i).getLow().doubleValue();
        }
        atr /= Math.min(10, n - 1);

        int score = 0;

        // 1. Candle direction matches trade signal (8 pts)
        boolean candleBull = close > open;
        boolean candleBear = close < open;
        if ("LONG".equals(direction)  && candleBull) score += 8;
        if ("SHORT".equals(direction) && candleBear) score += 8;

        // 2. Body ratio — not doji (indecision) and not tiny (4 pts)
        if (bodyRatio > 0.5) score += 4;      // strong body
        else if (bodyRatio > 0.3) score += 2; // moderate body
        // doji (< 0.2) = 0 pts

        // 3. Close position in candle range (4 pts)
        double closePos = range > 0 ? (close - low) / range : 0.5;
        if ("LONG".equals(direction)  && closePos > 0.6) score += 4; // closed in upper range
        if ("SHORT".equals(direction) && closePos < 0.4) score += 4; // closed in lower range

        // 4. Candle not overextended — body < 2× ATR (4 pts)
        if (atr > 0 && body < atr * 2) score += 4;  // reasonable size
        // If body > 2× ATR = exhaustion candle = late entry = 0 pts

        return Math.min(20, score);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // COMPONENT 3: VOLUME VALIDATION (0–10)
    // Analyze last 10 candles volume behavior.
    // Pattern should have supporting volume profile.
    // ═══════════════════════════════════════════════════════════════════════

    private int computeVolumeScore(List<Candle> daily, String direction, int n) {
        if (n < 11) return 5; // not enough data — neutral

        double avgVol = 0;
        for (int i = n - 11; i < n - 1; i++) {
            avgVol += daily.get(i).getVolume();
        }
        avgVol /= 10;
        if (avgVol == 0) return 0;

        double lastVol  = daily.get(n - 1).getVolume();
        double lastClose = daily.get(n - 1).getClose().doubleValue();
        double lastOpen  = daily.get(n - 1).getOpen().doubleValue();

        int score = 0;

        // 1. Signal candle has above-average volume (5 pts)
        if (lastVol > avgVol * 1.3) score += 5;      // strong volume
        else if (lastVol > avgVol) score += 3;        // above average
        else if (lastVol > avgVol * 0.7) score += 1; // below average

        // 2. Volume on direction candles in last 10 days (5 pts)
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

        // LONG: up-day volume should dominate = institutions buying
        if ("LONG".equals(direction)  && avgUpVol > avgDownVol * 1.2) score += 5;
        else if ("LONG".equals(direction) && avgUpVol > avgDownVol)   score += 3;
        // SHORT: down-day volume should dominate = institutions selling
        if ("SHORT".equals(direction) && avgDownVol > avgUpVol * 1.2) score += 5;
        else if ("SHORT".equals(direction) && avgDownVol > avgUpVol)  score += 3;

        return Math.min(10, score);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // COMPONENT 4: TREND ALIGNMENT (0–10)
    // Analyze previous 15 trading days for trend structure.
    // Pattern should form within a supportive trend context.
    // Reversal patterns (H&S, Triple) score well AGAINST trend.
    // Continuation patterns (BOS, Trendline) score well WITH trend.
    // ═══════════════════════════════════════════════════════════════════════

    private int computeTrendScore(List<Candle> daily, String direction, int n) {
        if (n < 16) return 5; // not enough data

        double price15dAgo = daily.get(n - 16).getClose().doubleValue();
        double priceNow    = daily.get(n - 1).getClose().doubleValue();
        if (price15dAgo == 0) return 5;

        double ret15d = (priceNow - price15dAgo) / price15dAgo;

        // Compute 15-day EMA direction (rough approximation)
        double ema15 = 0;
        double k = 2.0 / 16;
        for (int i = n - 16; i < n; i++) {
            double c = daily.get(i).getClose().doubleValue();
            ema15 = i == n - 16 ? c : c * k + ema15 * (1 - k);
        }
        boolean emaRising = priceNow > ema15;

        int score = 0;

        // LONG setup: trend supports buying
        if ("LONG".equals(direction)) {
            if (ret15d > 0.03 && emaRising)  score += 10; // strong uptrend
            else if (ret15d > 0 && emaRising) score += 7;  // mild uptrend
            else if (ret15d > -0.05)          score += 4;  // slight pullback — OK for reversal
            else                              score += 2;  // downtrend — needs strong reversal pattern
        }

        // SHORT setup: trend supports selling
        if ("SHORT".equals(direction)) {
            if (ret15d < -0.03 && !emaRising) score += 10; // strong downtrend
            else if (ret15d < 0 && !emaRising) score += 7;  // mild downtrend
            else if (ret15d < 0.05)             score += 4;  // slight bounce — OK for distribution
            else                                score += 2;  // uptrend — needs strong reversal
        }

        return Math.min(10, score);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // COMPONENT 5: PRICE ACTION QUALITY (0–10)
    // Evaluates: S/R behavior, chart cleanliness, breakout quality.
    // ═══════════════════════════════════════════════════════════════════════

    private int computePriceActionScore(double[] f, List<Candle> daily,
                                        String direction, int n) {
        if (n < 20) return 5;

        int score = 0;

        // 1. S/R flip confirms direction (2 pts)
        if (f.length > 56 && f[56] > 0.5) score += 2;

        // 2. Not in noisy market — ATR consistency (3 pts)
        // If daily ATR is relatively stable (not wildly spiking) = clean chart
        double atrSum = 0;
        for (int i = n - 10; i < n; i++) {
            atrSum += daily.get(i).getHigh().doubleValue() - daily.get(i).getLow().doubleValue();
        }
        double avgAtr = atrSum / 10;
        double lastAtr = daily.get(n-1).getHigh().doubleValue() - daily.get(n-1).getLow().doubleValue();
        if (avgAtr > 0 && lastAtr < avgAtr * 2.0) score += 3; // not a spike day

        // 3. Higher-quality intraday pattern composite (3 pts)
        // f[59] = daily pattern confidence composite (0-1)
        if (f.length > 59 && f[59] > 0.66) score += 3;
        else if (f.length > 59 && f[59] > 0.33) score += 1;

        // 4. Channel position supports entry (2 pts)
        // f[57] = price position in 20-day daily range
        if (f.length > 57) {
            double cp = f[57]; // normalised -1 to +1
            if ("LONG".equals(direction)  && cp < -0.2) score += 2; // near range low
            if ("SHORT".equals(direction) && cp > 0.2)  score += 2; // near range high
        }

        return Math.min(10, score);
    }
}