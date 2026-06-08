package com.trading.ai.model;

import lombok.*;

/**
 * AiReasonedOpportunity — a candidate after going through the reasoning engine.
 * Contains ML prediction output + hypothesis evaluation.
 * Used by AiTradeSelectionService to pick final trades.
 */
@Getter
@Builder
@AllArgsConstructor
public class AiReasonedOpportunity {
    private final AiCandidate candidate;
    private final boolean     shouldTrade;
    private final String      direction;
    private final double      confidence;
    private final int         tradeQualityScore;
    private final int         opportunityScore;
    private final int         riskScore;
    private final double      probabilityOfSuccess;
    private final double      rrRatio;
    private final double      expectedRR;
    private final double      expectedReturn;
    private final String      reasoning;
    private final String      bullScenario;
    private final String      bearScenario;
    private final String      dominantFactor;
    private final String      exitPlan;
    private final String      rejectReason;  // set when shouldTrade=false
}