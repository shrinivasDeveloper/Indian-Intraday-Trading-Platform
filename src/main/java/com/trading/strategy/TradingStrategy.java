package com.trading.strategy;

import com.trading.analysis.service.TechnicalAnalysisService;
import com.trading.domain.Candle;
import com.trading.domain.enums.TradeDirection;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * TradingStrategy — base interface for all strategy implementations.
 *
 * CURRENT STATE:
 *   No active strategy implementations exist. All previous strategies
 *   (ORBStrategy, RangeBreakoutStrategy, PullbackDetectionService,
 *   AutoModeStrategy) have been deleted. This interface is retained
 *   as the contract for the next strategy engine.
 *
 * TO ADD A NEW STRATEGY:
 *   1. Implement this interface
 *   2. Annotate with @Service
 *   3. In generateSignal(), return Optional.of(TradeSignal) when conditions met
 *   4. Publish ProbabilityScoreEvent via ApplicationEventPublisher
 *   5. RiskManagementService gates the event and publishes TradeApprovedEvent
 *   6. PaperTradeExecutionService executes the approved trade
 *
 * TrailingType per strategy convention:
 *   Range breakout  → CANDLE_LOW_5M  (trail prev 5m candle low, trigger at 1.5R)
 *   Trend           → VWAP_MINUS_01  (trail at VWAP−0.1%, trigger at 2R)
 *   Reversal        → NONE           (fixed target = POC, exit 100% there)
 *   ORB             → CANDLE_LOW_5M  (trigger at 2R)
 *   Pullback        → BREAKEVEN_ONLY (SL → breakeven once prev high cleared)
 */
public interface TradingStrategy {

    String name();

    Optional<TradeSignal> generateSignal(String symbol,
                                         List<Candle> candles5m,
                                         List<Candle> candles15m,
                                         MarketContext ctx);

    // ── TrailingType enum ────────────────────────────────────────────────────

    enum TrailingType {
        /** Trail SL behind the low of the previous completed 5-minute candle */
        CANDLE_LOW_5M,

        /** Trail SL at VWAP − 0.1% (for trend trades) */
        VWAP_MINUS_01,

        /**
         * Move SL to breakeven only — no continuous trailing.
         * Used for reversals where target = POC.
         */
        BREAKEVEN_ONLY,

        /** No trailing — use TradeManagementService default logic */
        NONE
    }

    // ── TradeSignal record ───────────────────────────────────────────────────

    record TradeSignal(
            TradeDirection direction,
            BigDecimal     entryPrice,
            BigDecimal     stopLoss,
            BigDecimal     target,
            double         score,
            String         strategyName,

            /** Price at which trailing SL activates. ZERO = use config default */
            BigDecimal   trailingTriggerPrice,

            /** How to trail after trigger. NONE = use TradeManagementService defaults */
            TrailingType trailingType,

            /**
             * Auto-exit if trade has not reached 0.5R profit within this many minutes.
             * 0 = no strategy time stop (fall back to StrategyConfig.global.globalTimeStop).
             */
            int timeStopMinutes,

            /**
             * True if this is a Spring (stop-hunt below support then breaks resistance)
             * or Upthrust (stop-hunt above resistance then breaks support) pattern.
             */
            boolean isSpring
    ) {
        /**
         * Backward-compatible 6-param constructor.
         * Defaults to NONE trailing, no time stop, not a spring.
         */
        public TradeSignal(TradeDirection direction, BigDecimal entryPrice,
                           BigDecimal stopLoss, BigDecimal target,
                           double score, String strategyName) {
            this(direction, entryPrice, stopLoss, target, score, strategyName,
                    BigDecimal.ZERO, TrailingType.NONE, 0, false);
        }

        /** Compute 1R distance in rupees */
        public BigDecimal oneR() {
            if (entryPrice == null || stopLoss == null) return BigDecimal.ZERO;
            return entryPrice.subtract(stopLoss).abs();
        }

        /** Compute N-R target price */
        public BigDecimal targetAtR(double rMultiple) {
            BigDecimal r = oneR();
            if (r.compareTo(BigDecimal.ZERO) == 0) return target;
            BigDecimal rDist = r.multiply(BigDecimal.valueOf(rMultiple));
            return direction == TradeDirection.LONG
                    ? entryPrice.add(rDist)
                    : entryPrice.subtract(rDist);
        }

        /** Did the signal specify trailing behaviour? */
        public boolean hasTrailing() { return trailingType != TrailingType.NONE; }

        /** Was a time-stop specified? */
        public boolean hasTimeStop() { return timeStopMinutes > 0; }
    }

    // ── MarketContext record ─────────────────────────────────────────────────

    record MarketContext(
            boolean    niftyBullish,
            boolean    niftyBearish,
            double     niftyChangePct,
            double     niftyAtrPct,

            String     sectorName,
            double     sectorChangePct,
            boolean    sectorAlignedBull,
            boolean    sectorAlignedBear,
            boolean    sectorIsTop,
            boolean    sectorIsBottom,
            double     sectorRS,

            BigDecimal vwap,
            boolean    vwapConfluence,

            TechnicalAnalysisService.TechnicalStructure structure
    ) {}
}