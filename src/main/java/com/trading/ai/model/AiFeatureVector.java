package com.trading.ai.model;

import com.trading.domain.Candle;
import lombok.AllArgsConstructor;
import lombok.Getter;
import java.util.List;

/**
 * AiFeatureVector — 60-feature numeric vector for one symbol at one candle close.
 *
 * Built by AiOpportunityDiscoveryEngine using ONLY AiMarketDataService data.
 * Zero dependency on SmcInstitutionalStructureService or HighRRStructureService.
 *
 * PREVIOUS VERSION imported: com.trading.strategy.smc.SmcInstitutionalStructureService
 * THIS VERSION: HtfStructure replaced by AI's own AiMarketDataService.AiStructureLevels
 * accessed via AiMarketDataService.getStructure() — kept as Object to avoid circular imports.
 */
@Getter
@AllArgsConstructor
public class AiFeatureVector {
    private final String           symbol;
    private final double           ltp;
    private final double[]         features;      // 60 numeric features
    private final String           sector;
    private final Object           aiStructure;   // AiMarketDataService.AiStructureLevels (AI-owned)
    private final AiMarketContext  marketContext;
    private final List<Candle>     candles5m;
    private final List<Candle>     candles15m;
    private final List<Candle>     candlesDaily;
}