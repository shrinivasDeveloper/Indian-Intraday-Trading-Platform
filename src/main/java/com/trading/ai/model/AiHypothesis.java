package com.trading.ai.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import java.util.List;

/**
 * AiHypothesis — output of HypothesisEngine.evaluate()
 * Bull and bear scenario scores derived from feature directionality.
 */
@Getter
@AllArgsConstructor
public class AiHypothesis {
    private final int          bullScore;       // 0–100
    private final int          bearScore;       // 0–100
    private final int          conviction;      // bullScore - bearScore (-100 to +100)
    private final List<String> keyBullFactors;  // top 3 bull factors
    private final List<String> keyBearFactors;  // top 3 bear factors
    private final String       hypothesisText;  // plain-English summary

    public static AiHypothesis neutral() {
        return new AiHypothesis(50, 50, 0,
                List.of("No dominant bull factors"),
                List.of("No dominant bear factors"),
                "Neutral — no clear directional bias");
    }
}