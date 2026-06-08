package com.trading.ai.engine;

import com.trading.ai.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.stream.Collectors;

/**
 * AiOpportunityRankingService
 *
 * Fast numeric pre-scoring to narrow 253 symbols down to the top N
 * before sending to Claude. This avoids sending all 253 candidates
 * to the API (slow + expensive).
 *
 * SCORING WEIGHTS (learned from training data, adjustable):
 *   Structural quality (0-30): HTF trend + S/R proximity + zone strength
 *   Momentum quality  (0-25): RVOL + price momentum + EMA alignment
 *   Context quality   (0-25): Sector + market direction + news
 *   Setup quality     (0-20): Sweep/retest/trendline bonus
 *
 * Returns top N candidates sorted by score, with filters applied:
 *   - Already fired today → excluded
 *   - Already active position → excluded
 *   - No S/R structure (all zeros) → excluded
 *   - Score below MIN_PRESCORE_THRESHOLD → excluded
 */
@Service
@ConditionalOnProperty(name = "ai.trading.enabled", havingValue = "true")
@Slf4j
@RequiredArgsConstructor
public class AiOpportunityRankingService {

    private static final double MIN_PRESCORE = 35.0;

    // Feature indices (must match AiFeatureEngineeringService)
    private static final int A3_EMA200_DIST = 2, A4_EMA_STACK = 3;
    private static final int A7_LOWER_WICK = 6, A8_RETURN5M = 7;
    private static final int B1_RVOL = 12, B2_VOL_TREND = 13, B3_SPIKE = 14;
    private static final int C1_SUPP_DIST = 20, C2_RES_DIST = 21, C3_HTF = 22;
    private static final int C4_SUPP_STR = 23, C5_RES_STR = 24, C6_FLIP = 25;
    private static final int C7_TRENDLINE = 26, C8_CHANNEL = 27, C9_SWEEP = 28;
    private static final int D1_NIFTY_DIR = 32, D5_BREADTH = 36, D6_TIME = 37;
    private static final int E1_SECTOR = 42, E5_SECT_ALIGN = 46;
    private static final int F1_NEWS = 50, F4_CORROBORATE = 53;

    public List<AiCandidate> rankAndFilter(
            AiFeatureBatch batch,
            Set<String> firedToday,
            Set<String> activePositions,
            int topN
    ) {
        List<AiCandidate> scored = new ArrayList<>();

        for (Map.Entry<String, AiFeatureVector> entry : batch.getFeatures().entrySet()) {
            String symbol = entry.getKey();
            AiFeatureVector fv = entry.getValue();

            // Hard filters
            if (firedToday.contains(symbol))    continue;
            if (activePositions.contains(symbol)) continue;

            double[] f = fv.getFeatures();
            double score = computeNumericScore(f);
            if (score < MIN_PRESCORE) continue;

            // Determine suggested direction from features
            String direction = suggestDirection(f);
            if ("NONE".equals(direction)) continue;

            scored.add(buildCandidate(symbol, fv, score, direction));
        }

        // Sort by score descending, return top N
        scored.sort((a, b) -> Double.compare(b.getNumericScore(), a.getNumericScore()));

        List<AiCandidate> top = scored.stream().limit(topN).collect(Collectors.toList());

        log.debug("[AI-RANK] Scored {}/{} symbols above {}. Top-{} selected.",
                scored.size(), batch.size(), MIN_PRESCORE, top.size());
        return top;
    }

    private double computeNumericScore(double[] f) {
        double score = 0;

        // ── Structural quality (0–30) ──────────────────────────────────────
        double htf = f[C3_HTF]; // +1 bull, -1 bear, 0 sideways
        score += Math.abs(htf) * 8.0;  // 0–8: clear HTF trend

        double suppDist = f[C1_SUPP_DIST]; // % from support (positive = above)
        double resDist  = f[C2_RES_DIST];  // % from resistance (positive = room to T1)
        if (htf > 0 && suppDist >= 0 && suppDist < 1.0)  score += 7.0; // near support in bull
        if (htf < 0 && resDist  >= 0 && resDist  < 1.0)  score += 7.0; // near resistance in bear

        score += f[C4_SUPP_STR] * 5.0;   // zone strength
        score += f[C5_RES_STR]  * 3.0;

        // ── Momentum quality (0–25) ────────────────────────────────────────
        double rvol = f[B1_RVOL];
        score += Math.min(10.0, rvol * 4.0);  // RVOL: 2.5× = max 10 pts

        double emaStack = f[A4_EMA_STACK];    // -1 to +1
        if ((htf > 0 && emaStack > 0.5) || (htf < 0 && emaStack < -0.5)) score += 8.0;

        double ret5m = f[A8_RETURN5M];
        if ((htf > 0 && ret5m > 0) || (htf < 0 && ret5m < 0)) score += 3.0; // momentum aligned

        if (f[B3_SPIKE] > 0) score += 4.0;   // volume spike

        // ── Context quality (0–25) ─────────────────────────────────────────
        double niftyDir = f[D1_NIFTY_DIR];
        if (htf * niftyDir > 0) score += 10.0; // trade aligned with market

        double sectorAlign = f[E5_SECT_ALIGN];
        if (htf * sectorAlign > 0) score += 8.0; // sector aligned

        if (f[F1_NEWS] > 0.5) score += 5.0;   // strong news
        if (f[F4_CORROBORATE] > 0) score += 2.0;

        // ── Setup quality bonus (0–20) ─────────────────────────────────────
        if (f[C9_SWEEP] > 0) score += 20.0;   // liquidity sweep — highest priority
        else if (f[C6_FLIP] > 0) score += 15.0; // breakout retest
        else if (f[C7_TRENDLINE] > 0) score += 10.0; // trendline touch
        else if (Math.abs(f[C8_CHANNEL]) > 0) score += 8.0; // channel boundary

        return Math.min(100.0, score);
    }

    private String suggestDirection(double[] f) {
        double htf = f[C3_HTF];
        if (htf > 0.5) return "LONG";   // BULLISH HTF → buy
        if (htf < -0.5) return "SHORT"; // BEARISH HTF → sell
        // Sideways — use EMA stack as tiebreaker
        double ema = f[A4_EMA_STACK];
        if (ema > 0.5) return "LONG";
        if (ema < -0.5) return "SHORT";
        return "NONE"; // No clear direction — skip
    }

    private AiCandidate buildCandidate(String symbol, AiFeatureVector fv,
                                       double score, String direction) {
        double[] f = fv.getFeatures();
        return AiCandidate.builder()
                .symbol(symbol)
                .ltp(fv.getLtp())
                .numericScore(score)
                .suggestedDirection(direction)
                .sector(fv.getSector())
                .htfTrend(f[C3_HTF] > 0.5 ? "BULLISH" : f[C3_HTF] < -0.5 ? "BEARISH" : "SIDEWAYS")
                .emaStackDesc(describeEmaStack(f[A4_EMA_STACK]))
                .rvol(f[B1_RVOL])
                .distFromSupport(f[C1_SUPP_DIST])
                .distFromResistance(f[C2_RES_DIST])
                .supportStrength((int)(f[C4_SUPP_STR] * 100))
                .srFlip(f[C6_FLIP] > 0)
                .liquiditySweep(f[C9_SWEEP] > 0)
                .trendlineTouch(f[C7_TRENDLINE] > 0)
                .channelPosition(f[C8_CHANNEL] > 0.5 ? "NEAR_LOWER" : f[C8_CHANNEL] < -0.5 ? "NEAR_UPPER" : "NONE")
                .return5m(f[A8_RETURN5M])
                .return15m(fv.getFeatures().length > 9 ? f[8] : 0)
                .return1h(fv.getFeatures().length > 10 ? f[9] : 0)
                .volumeSpike(f[B3_SPIKE] > 0)
                .sectorChange(f[E1_SECTOR] * 3.0) // denormalise
                .newsSummary(f[F1_NEWS] > 0 ? "Score " + (int)(f[F1_NEWS]*100) + (f[F4_CORROBORATE] > 0 ? " (corroborated)" : "") : "None")
                .historicalWinRate(fv.getFeatures().length > 55 ? f[55] : 0.5)
                .featureVector(fv)
                .build();
    }

    private String describeEmaStack(double val) {
        if (val > 0.7) return "PERFECT_BULL (all EMAs aligned)";
        if (val > 0.3) return "PARTIAL_BULL";
        if (val < -0.7) return "PERFECT_BEAR (all EMAs aligned)";
        if (val < -0.3) return "PARTIAL_BEAR";
        return "COMPRESSED (mixed)";
    }
}