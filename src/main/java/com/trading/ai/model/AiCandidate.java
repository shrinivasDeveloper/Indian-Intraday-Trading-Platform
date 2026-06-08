package com.trading.ai.model;

import lombok.*;

/**
 * AiCandidate — a symbol that passed numeric pre-screening.
 * Enriched with ML prediction and hypothesis before trade selection.
 */
@Getter
@Builder
@AllArgsConstructor
public class AiCandidate {
    // Identity
    private final String         symbol;
    private final double         ltp;
    private final String         sector;
    private final AiFeatureVector featureVector;

    // Direction
    private final String         suggestedDirection;  // LONG / SHORT

    // Numeric pre-score (from AiOpportunityRankingService)
    private final double         numericScore;        // 0–100

    // ML output (from ProprietaryMLEngine)
    private final double         mlProbability;       // successProbability
    private final double         mlConfidence;
    private final String         mlModelUsed;
    private final String         mlReasoning;

    // Hypothesis (from HypothesisEngine)
    private final AiHypothesis   hypothesis;

    // Feature summary fields (for logging and dashboard)
    private final String  htfTrend;
    private final double  rvol;
    private final double  distFromSupport;
    private final double  distFromResistance;
    private final int     supportStrength;
    private final boolean liquiditySweep;
    private final boolean srFlip;
    private final boolean trendlineTouch;
    private final String  channelPosition;
    private final double  return5m;
    private final double  return15m;
    private final double  return1h;
    private final boolean volumeSpike;
    private final double  sectorChange;
    private final String  newsSummary;
    private final double  historicalWinRate;
    private final String  emaStackDesc;
}