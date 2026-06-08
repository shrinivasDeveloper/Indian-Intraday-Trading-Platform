package com.trading.ai.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * AiPrediction — output of ProprietaryMLEngine.predict()
 *
 * Replaces raw Score=85 with actionable trading metrics:
 *   successProbability = 72%    (P(trade wins, R ≥ 1.0))
 *   expectedRR         = 2.8    (E[R-multiple])
 *   expectedReturn     = 1.9%   (expectedRR × avg SL%)
 *   confidence         = 81%    (ensemble agreement)
 */
@Getter
@AllArgsConstructor
public class AiPrediction {
    private final double successProbability;  // 0.0–1.0
    private final double confidence;          // 0.0–1.0  (ensemble agreement)
    private final double expectedRR;          // e.g. 2.8
    private final double expectedReturn;      // e.g. 1.9  (percentage)
    private final String reasoning;           // plain-English explanation
    private final String modelUsed;           // GBM+RF_ENSEMBLE / GBM_ONLY / NUMERIC_FALLBACK

    // Legacy: probability is successProbability
    public double getProbability() { return successProbability; }

    @Override
    public String toString() {
        return String.format("P=%.0f%% RR=%.1f Ret=%.1f%% Conf=%.0f%% [%s]",
                successProbability*100, expectedRR, expectedReturn, confidence*100, modelUsed);
    }
}