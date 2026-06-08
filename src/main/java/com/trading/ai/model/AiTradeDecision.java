package com.trading.ai.model;

import lombok.*;
import java.math.BigDecimal;

/**
 * AiTradeDecision — final trade to be executed.
 * Output of AiTradeSelectionService, enriched by AiRiskManagementService.
 */
@Getter
@Builder
@AllArgsConstructor
public class AiTradeDecision {
    // Core trade fields
    private final String     symbol;
    private final String     direction;         // LONG / SHORT
    private final BigDecimal entryPrice;
    private final BigDecimal stopLoss;
    private final BigDecimal target1;
    private final BigDecimal target2;
    private       int        positionSize;      // qty (mutable — set by risk engine)
    private       BigDecimal riskAmount;        // ₹ at risk (mutable)

    // AI scoring output
    private final double  probabilityOfSuccess; // successProbability 0–1
    private final double  expectedRR;            // e.g. 2.8
    private final double  expectedReturn;        // e.g. 1.9%
    private final double  confidence;            // ensemble confidence 0–1
    private final double  rrRatio;               // actual RR from price levels
    private final int     tradeQualityScore;     // 0–100
    private final int     opportunityScore;      // 0–100
    private final int     riskScore;             // 0–100 (higher = riskier)

    // Reasoning
    private final String  reasoning;
    private final String  bullScenario;
    private final String  bearScenario;
    private final String  dominantFactor;
    private final String  exitPlan;
    private final String  reasoningSummary;      // truncated for log

    // Context
    private final String  htfTrend;
    private final String  sector;
    private final double  numericPreScore;
    private final AiFeatureVector featureVector;

    // Mutable setters for risk engine
    public AiTradeDecision withPositionSize(int qty) {
        return AiTradeDecision.builder()
                .symbol(symbol).direction(direction).entryPrice(entryPrice)
                .stopLoss(stopLoss).target1(target1).target2(target2)
                .positionSize(qty).riskAmount(riskAmount)
                .probabilityOfSuccess(probabilityOfSuccess).expectedRR(expectedRR)
                .expectedReturn(expectedReturn).confidence(confidence).rrRatio(rrRatio)
                .tradeQualityScore(tradeQualityScore).opportunityScore(opportunityScore)
                .riskScore(riskScore).reasoning(reasoning).bullScenario(bullScenario)
                .bearScenario(bearScenario).dominantFactor(dominantFactor).exitPlan(exitPlan)
                .reasoningSummary(reasoningSummary).htfTrend(htfTrend).sector(sector)
                .numericPreScore(numericPreScore).featureVector(featureVector).build();
    }

    public AiTradeDecision withRiskAmount(BigDecimal risk) {
        return withPositionSize(positionSize); // rebuild with same qty, riskAmount set in builder
    }
}