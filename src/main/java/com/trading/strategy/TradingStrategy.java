package com.trading.strategy;

import com.trading.analysis.service.PatternDetectionService;
import com.trading.analysis.service.TechnicalAnalysisService;
import com.trading.domain.Candle;
import com.trading.domain.enums.TradeDirection;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * TradingStrategy — base interface for strategies 2, 3, 4.
 *
 * Strategy 1 is SevenGateScannerService (existing, unchanged).
 * Strategies 2-4 implement this interface and run independently.
 *
 * MarketContext is a nested record — always reference as TradingStrategy.MarketContext.
 * It uses plain boolean fields (niftyBullish, niftyBearish) — NO .regime() method.
 */
public interface TradingStrategy {

    /** Unique name used in logs and trade records */
    String name();

    /**
     * Check this strategy's own conditions independently.
     * Returns signal ONLY if ALL conditions pass.
     *
     * @param symbol     NSE symbol e.g. "RELIANCE"
     * @param candles5m  5-min candles, index 0 = most recent
     * @param candles15m 15-min candles, index 0 = most recent
     * @param ctx        market data from shared service caches
     */
    Optional<TradeSignal> generateSignal(String symbol,
                                         List<Candle> candles5m,
                                         List<Candle> candles15m,
                                         MarketContext ctx);

    // ── What a strategy returns when all conditions pass ─────────────────

    record TradeSignal(
            TradeDirection direction,
            BigDecimal     entryPrice,
            BigDecimal     stopLoss,
            BigDecimal     target,
            double         score,        // 0-100
            String         strategyName
    ) {}

    // ── Market data passed to all strategies ─────────────────────────────
    // NOTE: Always reference as TradingStrategy.MarketContext in implementing classes.

    record MarketContext(
            boolean    niftyBullish,      // Nifty: EMA aligned up + HH/HL
            boolean    niftyBearish,      // Nifty: EMA aligned down + LH/LL
            double     niftyChangePct,    // Nifty % change from open (positive=up)
            double     niftyAtrPct,       // Nifty ATR% (volatility gauge)

            String     sectorName,        // sector name for this stock
            double     sectorChangePct,   // sector avg % change from open
            boolean    sectorAlignedBull, // ≥60% green + RS > 1.0
            boolean    sectorAlignedBear, // ≥60% red + RS < 1.0
            boolean    sectorIsTop,       // top 2 strongest sectors today
            boolean    sectorIsBottom,    // bottom 2 weakest sectors today
            double     sectorRS,          // relative strength vs Nifty

            BigDecimal vwap,              // VWAP from TechnicalAnalysisService
            boolean    vwapConfluence,    // price within 0.3% of VWAP

            PatternDetectionService.PatternResult  pattern,   // detected pattern
            TechnicalAnalysisService.TechnicalStructure structure // S/R zones etc
    ) {}
}