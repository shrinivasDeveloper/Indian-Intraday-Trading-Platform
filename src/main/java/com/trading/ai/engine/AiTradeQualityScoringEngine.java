package com.trading.ai.engine;

import com.trading.ai.model.AiCandidate;
import com.trading.ai.model.AiPrediction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * AiTradeQualityScoringEngine
 *
 * Scores trade setup quality 0–100.
 *
 * SCORING BREAKDOWN:
 *   ML probability quality  : 30 pts max
 *   Risk/reward ratio        : 20 pts max
 *   EMA stack alignment      : 15 pts max
 *   Volume confirmation      : 15 pts max
 *   AI pattern quality       : 10 pts max
 *   Sector alignment         : 10 pts max
 *   ─────────────────────────────────────
 *   Total possible           : 100 pts
 *
 * Fully owned by AI module — no external strategy dependencies.
 */
@Service
@ConditionalOnProperty(name = "ai.trading.enabled", havingValue = "true")
@Slf4j
public class AiTradeQualityScoringEngine {

    /**
     * Score the quality of a trade setup 0–100.
     * Higher score = higher quality setup = better chance of success.
     *
     * @param candidate  scored candidate from AiOpportunityDiscoveryEngine
     * @param prediction ML prediction from AiProbabilityEngine
     * @param rrRatio    actual RR from price levels (entry to T1 / SL distance)
     * @return quality score 0–100
     */
    public int scoreTradeQuality(AiCandidate candidate,
                                 AiPrediction prediction,
                                 double rrRatio) {
        double[] f = candidate.getFeatureVector().getFeatures();
        if (f == null) return 0;

        int score = 0;

        // ── ML probability quality (30 pts) ───────────────────────────────
        score += (int)(prediction.getSuccessProbability() * 30);

        // ── RR quality (20 pts) ───────────────────────────────────────────
        if      (rrRatio >= 3.0) score += 20;
        else if (rrRatio >= 2.5) score += 15;
        else if (rrRatio >= 2.0) score += 10;
        else                     score += 5;

        // ── EMA stack alignment (15 pts) ──────────────────────────────────
        if      (f.length > 3 && Math.abs(f[3]) > 0.7) score += 15;
        else if (f.length > 3 && Math.abs(f[3]) > 0.3) score += 8;

        // ── Volume confirmation (15 pts) ──────────────────────────────────
        if      (f.length > 16 && f[16] > 2.0) score += 15;
        else if (f.length > 16 && f[16] > 1.3) score += 8;

        // ── AI pattern quality (10 pts) ───────────────────────────────────
        if      (f.length > 54 && (f[54] > 0 || f[55] > 0)) score += 10; // liquidity sweep
        else if (f.length > 56 && f[56] > 0)                 score += 7;  // SR flip
        else if (f.length > 58 && f[58] > 0)                 score += 5;  // trendline touch

        // ── Sector alignment (10 pts) ─────────────────────────────────────
        if (f.length > 30 && (f[30] > 0 || f[31] > 0)) score += 10;

        int finalScore = Math.min(100, score);
        log.debug("[AI-QUALITY] {} score={} (P={}% RR={})",
                candidate.getSymbol(), finalScore,
                String.format("%.0f", prediction.getSuccessProbability() * 100),
                String.format("%.1f", rrRatio));
        return finalScore;
    }
}