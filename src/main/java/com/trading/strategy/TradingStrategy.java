package com.trading.strategy;

import com.trading.analysis.service.PatternDetectionService;
import com.trading.analysis.service.TechnicalAnalysisService;
import com.trading.domain.Candle;
import com.trading.domain.enums.MarketRegime;
import com.trading.domain.enums.TradeDirection;
import com.trading.sector.service.SectorStrengthService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface TradingStrategy {

    String name();

    Optional<TradeSignal> generateSignal(String symbol,
                                          List<Candle> candles1m,
                                          List<Candle> candles15m,
                                          MarketContext ctx);

    record TradeSignal(
        TradeDirection direction,
        BigDecimal     entryPrice,
        BigDecimal     stopLoss,
        BigDecimal     target,
        double         score,
        String         strategyName
    ) {}

    record MarketContext(
        MarketRegime                                    regime,
        SectorStrengthService.SectorData                sector,
        TechnicalAnalysisService.TechnicalStructure     structure,
        PatternDetectionService.PatternResult           pattern
    ) {}
}
