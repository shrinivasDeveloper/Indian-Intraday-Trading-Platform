package com.trading.ai.model;

import com.trading.domain.Candle;
import com.trading.strategy.smc.SmcInstitutionalStructureService;
import lombok.AllArgsConstructor;
import lombok.Getter;
import java.util.List;

/**
 * AiFeatureVector — 60-feature numeric vector for one symbol at one candle close.
 * Built by AiFeatureEngineeringService. Passed to ProprietaryMLEngine for scoring.
 */
@Getter
@AllArgsConstructor
public class AiFeatureVector {
    private final String         symbol;
    private final double         ltp;
    private final double[]       features;      // 60 numeric features
    private final String         sector;
    private final SmcInstitutionalStructureService.HtfStructure smcStructure;
    private final AiMarketContext marketContext;
    private final List<Candle>   candles5m;
    private final List<Candle>   candles15m;
    private final List<Candle>   candlesDaily;
}