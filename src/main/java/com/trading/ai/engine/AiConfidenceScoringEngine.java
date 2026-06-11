package com.trading.ai.engine;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * AiConfidenceScoringEngine
 *
 * Multi-factor confidence calculation for the AI module.
 * Detects signal contradictions and reduces confidence accordingly.
 * Fully owned by the AI module — no external strategy dependencies.
 */
@Service
@ConditionalOnProperty(name = "ai.trading.enabled", havingValue = "true")
@Slf4j
public class AiConfidenceScoringEngine {

    // Feature contradiction pairs [bull_feature, bear_feature, penalty_pct]
    private static final int[][] CONTRADICTIONS = {
            {3,  -1, 15},  // EMA bullish but momentum negative
            {30, 31, 20},  // sector aligned bull AND bear simultaneously
            {54, 55, 25},  // sweep low AND sweep high on same candle
            {8,  13, 10},  // momentum up but RSI overbought
    };

    /**
     * Compute final confidence score combining ML confidence + contradiction penalty.
     *
     * @param mlConfidence  confidence from AiProbabilityEngine (0–1)
     * @param features      60-feature vector from AiOpportunityDiscoveryEngine
     * @return final confidence (0–1), minimum 0.1
     */
    public double computeConfidence(double mlConfidence, double[] features) {
        if (features == null || features.length < 60) return mlConfidence;

        int penalty = 0;
        for (int[] c : CONTRADICTIONS) {
            int bullIdx = c[0], bearIdx = c[1], pen = c[2];
            if (bearIdx < 0) {
                // Special case: bull feature high but momentum negative
                if (bullIdx < features.length
                        && features[bullIdx] > 0.3
                        && features[8] < -0.3) {
                    penalty += pen;
                    log.debug("[AI-CONF] Contradiction: f[{}]={:.2f} vs momentum={:.2f} penalty={}",
                            bullIdx, features[bullIdx], features[8], pen);
                }
            } else {
                if (bullIdx < features.length
                        && bearIdx < features.length
                        && features[bullIdx] > 0.3
                        && features[bearIdx] > 0.3) {
                    penalty += pen;
                    log.debug("[AI-CONF] Contradiction: f[{}]={:.2f} vs f[{}]={:.2f} penalty={}",
                            bullIdx, features[bullIdx], bearIdx, features[bearIdx], pen);
                }
            }
        }

        double finalConf = mlConfidence - (penalty / 100.0);
        return Math.max(0.1, Math.min(1.0, finalConf));
    }
}