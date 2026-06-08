package com.trading.ai.engine.proprietary;

import java.util.ArrayList;
import java.util.List;

import com.trading.ai.model.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * HypothesisEngine
 *
 * Generates bullish and bearish scenario scores for each candidate.
 * This is the proprietary "reasoning" layer — no LLM required.
 *
 * HOW IT WORKS:
 *   Bull score: weighted sum of features supporting the trade direction.
 *   Bear score: weighted sum of features opposing the trade direction.
 *   Conviction = bull - bear (normalised to 0–100).
 *
 *   For each feature, we know its "direction" — whether a high value
 *   is bullish or bearish for a LONG trade. E.g.:
 *     - HTF trend BULLISH (feature > 0) → adds to bull score
 *     - Near resistance (feature C2 low) → adds to bear score for LONG
 *     - RVOL high → adds to bull score (participation)
 *     - Volume spike → adds to bull score if direction aligned
 *
 *   Feature weights are loaded from ai_feature_importance table,
 *   updated weekly based on what actually predicted trade success.
 *
 * OUTPUT per candidate:
 *   - bullScore (0–100): strength of bull case
 *   - bearScore (0–100): strength of bear case
 *   - conviction (int): bullScore - bearScore
 *   - keyBullFactors: top 3 factors supporting bull case
 *   - keyBearFactors: top 3 factors supporting bear case
 *   - hypothesisText: plain-English scenario summary
 */
@Service
@ConditionalOnProperty(name = "ai.trading.enabled", havingValue = "true")
public class HypothesisEngine {

    private static final String[] FEATURE_NAMES = {
            "EMA20 proximity","EMA50 proximity","EMA200 proximity","EMA stack",
            "Candle body","Upper wick","Lower wick","5m momentum",
            "15m momentum","1H momentum","From day high","From day low",
            "RVOL","Volume trend","Volume spike","Daily volume",
            "Bull/bear vol","OBV slope","Volume at level","Delta volume",
            "Support distance","Resistance distance","HTF trend","Support strength",
            "Resistance strength","S/R flip","Trendline","Channel","Liquidity sweep",
            "Days since swing high","Days since swing low","S/R congestion",
            "Nifty direction","Nifty ATR","BNF direction","VIX","Market breadth",
            "Time of day","Nifty beta","Relative strength","Regime","CB headroom",
            "Sector change","Sector rank","Sector RS","Sector RVOL",
            "Sector aligned","Sector peers","Sector momentum","Sector concentration",
            "News score","News category","News freshness","News corroboration",
            "News-tech alignment","Historical win rate","Avg R-multiple",
            "Times this week","Last outcome","Score stability"
    };

    // For LONG trade: feature direction (+1 = bullish, -1 = bearish)
    private static final double[] LONG_DIRECTION = {
            +1, +1, +1, +1, +1, -0.5, +0.5, +1, +1, +1, -0.5, +0.5, // A
            +1, +1, +1, +0.5, +1, +1, +0, +1,                         // B
            +1, -1, +1, +1, -0.5, +1, +1, +1, +1, -0.5, +0.5, -1,    // C
            +1, +1, +0.5, -1, +1, 0, 0, +1, +1, +1,                   // D
            +1, 0, +1, +1, +1, +1, +1, +1,                             // E
            +1, +1, -1, +1, +1,                                         // F
            +1, +1, -0.5, +1, +1                                        // G
    };

    /**
     * Evaluate bull and bear hypothesis for a single candidate.
     */
    public AiHypothesis evaluate(AiCandidate candidate, double[] featureImportance) {
        double[] f    = candidate.getFeatureVector().getFeatures();
        boolean isLong = "LONG".equals(candidate.getSuggestedDirection());
        double dirMul  = isLong ? 1.0 : -1.0;

        double bullScore = 0, bearScore = 0;
        List<String> bullFactors = new java.util.ArrayList<>(), bearFactors = new java.util.ArrayList<>();

        for (int i = 0; i < Math.min(f.length, LONG_DIRECTION.length); i++) {
            double fImportance = featureImportance.length > i ? featureImportance[i] : 1.0;
            double directionality = LONG_DIRECTION[i] * dirMul;
            double contribution = f[i] * directionality * fImportance;

            if (contribution > 0.2) {
                bullScore += contribution;
                if (bullFactors.size() < 3 && i < FEATURE_NAMES.length) {
                    bullFactors.add(FEATURE_NAMES[i] + " (+)");
                }
            } else if (contribution < -0.2) {
                bearScore += Math.abs(contribution);
                if (bearFactors.size() < 3 && i < FEATURE_NAMES.length) {
                    bearFactors.add(FEATURE_NAMES[i] + " (-)");
                }
            }
        }

        // Normalise to 0–100
        double total = bullScore + bearScore;
        bullScore = total > 0 ? Math.min(100, bullScore / total * 100) : 50;
        bearScore = total > 0 ? Math.min(100, bearScore / total * 100) : 50;
        int conviction = (int)(bullScore - bearScore);

        String direction = isLong ? "LONG" : "SHORT";
        String text = String.format("%s HYPOTHESIS: Bull=%d Bear=%d Conviction=%d | " +
                        "Bull factors: %s | Bear factors: %s",
                direction, (int)bullScore, (int)bearScore, conviction,
                bullFactors.isEmpty() ? "none" : String.join(", ", bullFactors),
                bearFactors.isEmpty() ? "none" : String.join(", ", bearFactors));

        return new AiHypothesis(
                (int)bullScore, (int)bearScore, conviction,
                bullFactors, bearFactors, text
        );
    }


}