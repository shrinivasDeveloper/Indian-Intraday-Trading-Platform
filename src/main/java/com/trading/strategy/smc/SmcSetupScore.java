package com.trading.strategy.smc;

import com.trading.domain.enums.TradeDirection;

import java.math.BigDecimal;
import java.util.List;

/**
 * SmcSetupScore — immutable result produced by SmcAnalyser for one symbol.
 *
 * Holds:
 *  - Pass/fail for each of the 5 hard rules
 *  - Individual score components (max 9 total)
 *  - Entry, SL, and target prices calculated from ATR
 *  - Human-readable reason strings for logging and dashboard
 */
public record SmcSetupScore(

        // ── Identity ───────────────────────────────────────────────────────────
        String         symbol,
        TradeDirection direction,

        // ── Hard rule outcomes ─────────────────────────────────────────────────
        boolean rule1HtfTrend,       // 4H price vs EMA50
        boolean rule2Structure,      // 1H HH/HL or LH/LL
        boolean rule3Fvg,            // 15min fresh FVG
        boolean rule4Adx,            // ADX > 20
        boolean rule5LiqSweep,       // Liquidity sweep confirmed

        // ── Score components (sum = totalScore) ────────────────────────────────
        int scoreStrongFvg,          // +2 gap/ATR >= 0.5
        int scoreVwapSide,           // +2 price on correct VWAP side
        int scoreSrDistance,         // +1 price > 1% from S/R
        int scoreVolume,             // +1 volume > 1.5x 20-day avg
        int scoreAdx25,              // +1 ADX > 25
        int scoreSweepFresh,         // +1 sweep <= 2 candles
        int scoreFvgFresh,           // +1 FVG age <= 3 candles
        int totalScore,              // sum of above

        // ── Prices (set only when all 5 rules pass) ────────────────────────────
        BigDecimal entryPrice,
        BigDecimal stopLoss,
        BigDecimal target1,          // 2.5R target
        BigDecimal target2,          // 3.5R extended target

        // ── Raw data for logging ───────────────────────────────────────────────
        double atr14,
        double adxValue,
        int    fvgAgeCandels,
        int    sweepAgeCandles,
        double vwap,
        double currentPrice,

        // ── Reasons (one string per passed rule / score component) ─────────────
        List<String> reasons,

        // ── Failure reason when any hard rule fails ────────────────────────────
        String failReason

) {

    /** True when all 5 hard rules pass AND totalScore >= minScore */
    public boolean isQualified(int minScore) {
        return rule1HtfTrend && rule2Structure && rule3Fvg
                && rule4Adx && rule5LiqSweep
                && totalScore >= minScore;
    }

    /** True when all 5 hard rules pass (regardless of score) */
    public boolean passesAllRules() {
        return rule1HtfTrend && rule2Structure && rule3Fvg
                && rule4Adx && rule5LiqSweep;
    }

    /** One-line summary for logging */
    public String toLogString() {
        if (!passesAllRules()) {
            return String.format("%s REJECTED — %s", symbol, failReason);
        }
        return String.format(
                "%s %s score=%d/9 entry=%.2f sl=%.2f t1=%.2f | ADX=%.1f FVG_age=%d sweep_age=%d",
                symbol, direction, totalScore,
                entryPrice.doubleValue(), stopLoss.doubleValue(), target1.doubleValue(),
                adxValue, fvgAgeCandels, sweepAgeCandles);
    }

    // ── Factory: FAILED setup ─────────────────────────────────────────────────

    public static SmcSetupScore failed(String symbol, String reason) {
        return new SmcSetupScore(
                symbol, null,
                false, false, false, false, false,
                0, 0, 0, 0, 0, 0, 0, 0,
                null, null, null, null,
                0, 0, 0, 0, 0, 0,
                List.of(), reason);
    }
}