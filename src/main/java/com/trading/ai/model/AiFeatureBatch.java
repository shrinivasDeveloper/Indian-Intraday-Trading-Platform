package com.trading.ai.model;

import lombok.Getter;
import java.util.Map;

/**
 * AiFeatureBatch — all symbol feature vectors for one evaluation cycle.
 * Built once per candle close, passed through the full pipeline.
 */
@Getter
public class AiFeatureBatch {
    private final Map<String, AiFeatureVector> features;
    private final AiMarketContext              marketContext;

    public AiFeatureBatch(Map<String, AiFeatureVector> features,
                          AiMarketContext marketContext) {
        this.features      = features;
        this.marketContext = marketContext;
    }

    public int size() { return features.size(); }
    public AiFeatureVector get(String symbol) { return features.get(symbol); }
}