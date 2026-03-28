package com.trading.strategy;

import com.trading.analysis.service.PatternDetectionService;
import com.trading.analysis.service.TechnicalAnalysisService;
import com.trading.domain.Candle;
import com.trading.domain.enums.TradeDirection;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * TradingStrategy — base interface for all strategies.
 *
 * UPGRADES (Institutional Risk Migration):
 *
 * TradeSignal now carries trailing and time-stop metadata so
 * TradeManagementService / PaperTradeManagementService can implement
 * strategy-specific risk migration without hard-coding it:
 *
 *   trailingTriggerPrice  → price at which trailing SL activates
 *   trailingType          → HOW to trail (candle low, VWAP, none)
 *   timeStopMinutes       → exit if trade not ≥ 0.5R after N minutes
 *   isSpring              → Spring/Upthrust flag (score 95 high conviction)
 *
 * TrailingType per strategy:
 *   RANGE_BREAKOUT  → CANDLE_LOW_5M  (trail prev 5m candle low, trigger at 1.5R)
 *   AUTO_MODE TREND → VWAP_MINUS_01  (trail at VWAP−0.1%, trigger at 2R)
 *   AUTO_MODE REV   → NONE           (fixed target = POC, exit 100% there)
 *   ORB             → CANDLE_LOW_5M  (trigger at 2R)
 *   PULLBACK        → BREAKEVEN_ONLY (SL → breakeven once prev high cleared)
 *
 * NOTE TO TradeManagementService: read TrailingType from the trade's
 *   strategyName + trailingTriggerPrice to implement dynamic trailing.
 *   The existing config (trail-start-r, trail-atr-multiplier etc.) is the
 *   FALLBACK for strategies that don't specify a trailing type.
 */
public interface TradingStrategy {

    String name();

    Optional<TradeSignal> generateSignal(String symbol,
                                         List<Candle> candles5m,
                                         List<Candle> candles15m,
                                         MarketContext ctx);

    // ── TrailingType enum ──────────────────────────────────────────────────────

    enum TrailingType {
        /** Trail SL behind the LOW of the previous completed 5-minute candle */
        CANDLE_LOW_5M,

        /** Trail SL at VWAP − 0.1% (for trend trades — lets price breathe) */
        VWAP_MINUS_01,

        /**
         * Move SL to BREAKEVEN only — no continuous trailing.
         * Used for reversals: target = POC, take 100% profit there.
         */
        BREAKEVEN_ONLY,

        /** No trailing — use existing TradeManagementService logic */
        NONE
    }

    // ── TradeSignal record ─────────────────────────────────────────────────────

    record TradeSignal(
            TradeDirection direction,
            BigDecimal     entryPrice,
            BigDecimal     stopLoss,
            BigDecimal     target,
            double         score,
            String         strategyName,

            // ── Institutional trailing metadata (NEW) ─────────────────────────
            /** Price at which trailing SL activates (e.g. Entry + 1.5R). ZERO = use config default */
            BigDecimal   trailingTriggerPrice,

            /** How to trail after trigger. NONE = use TradeManagementService defaults */
            TrailingType trailingType,

            /**
             * Auto-exit if trade has not reached 0.5R profit within this many minutes.
             * 0 = no time stop (use 15:00 IST force-close only).
             * Pro rule: if big money isn't pushing immediately, context has changed.
             */
            int timeStopMinutes,

            /**
             * True if this is a Spring (below support stop-hunt then breaks resistance)
             * or Upthrust (above resistance bull trap then breaks support) pattern.
             * These are highest-conviction setups — score = 95.
             */
            boolean isSpring
    ) {
        /**
         * Backward-compatible 6-param constructor (used by existing code that doesn't
         * yet pass trailing metadata). Defaults to NONE trailing and no time stop.
         */
        public TradeSignal(TradeDirection direction, BigDecimal entryPrice,
                           BigDecimal stopLoss, BigDecimal target,
                           double score, String strategyName) {
            this(direction, entryPrice, stopLoss, target, score, strategyName,
                    BigDecimal.ZERO, TrailingType.NONE, 0, false);
        }

        /** Convenience: compute 1R distance in rupees */
        public BigDecimal oneR() {
            if (entryPrice == null || stopLoss == null) return BigDecimal.ZERO;
            return entryPrice.subtract(stopLoss).abs();
        }

        /** Convenience: compute N-R target price */
        public BigDecimal targetAtR(double rMultiple) {
            BigDecimal r = oneR();
            if (r.compareTo(BigDecimal.ZERO) == 0) return target;
            BigDecimal rDist = r.multiply(BigDecimal.valueOf(rMultiple));
            return direction == TradeDirection.LONG
                    ? entryPrice.add(rDist)
                    : entryPrice.subtract(rDist);
        }

        /** Convenience: did the signal specify trailing behaviour? */
        public boolean hasTrailing() { return trailingType != TrailingType.NONE; }

        /** Convenience: was a time-stop specified? */
        public boolean hasTimeStop() { return timeStopMinutes > 0; }
    }

    // ── MarketContext ──────────────────────────────────────────────────────────

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

            PatternDetectionService.PatternResult  pattern,
            TechnicalAnalysisService.TechnicalStructure structure
    ) {}
}