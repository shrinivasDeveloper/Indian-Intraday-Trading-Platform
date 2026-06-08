package com.trading.ai.model;

import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * AiTradeOutcome — recorded when a trade closes.
 * Fed into AiLearningService and ProprietaryMLEngine for continuous learning.
 */
@Getter
@Builder
@AllArgsConstructor
public class AiTradeOutcome {
    private final String     symbol;
    private final String     direction;
    private final BigDecimal entryPrice;
    private final BigDecimal exitPrice;
    private final BigDecimal pnl;
    private final double     rMultiple;       // actual R achieved
    private final String     exitReason;      // SL_HIT / TARGET_1 / TARGET_2 / EOD / CONDITION_EXIT
    private final String     outcomeType;     // WIN / LOSS / BREAKEVEN
    private final double     confidence;      // AI confidence at entry
    private final int        qualityScore;    // trade quality score at entry
    private final String     reasoning;       // AI reasoning at entry
    private final String     dominantFactor;  // what drove entry decision
    private final double[]   featureVectorAtEntry; // 60 features — for ML retraining
    private final String     featureVectorJson;    // serialised for MySQL
    private final Instant    entryTime;
    private final Instant    exitTime;
    private final String     regime;          // market regime at entry
}