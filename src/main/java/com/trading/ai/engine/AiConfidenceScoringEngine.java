package com.trading.ai.engine;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * AiConfidenceScoringEngine
 *
 * Multi-factor confidence calculation for the AI module.
 * Detects signal contradictions and reduces confidence accordingly.
 *
 * CONTRADICTION LOGIC (direction-aware):
 *   A contradiction means the pattern CONTRADICTS the TRADE DIRECTION.
 *
 *   f[54] = sweep_low  = BULLISH pattern → contradicts SHORT trades
 *   f[55] = sweep_high = BEARISH pattern → contradicts LONG  trades
 *   f[30] = sector_bull                  → contradicts SHORT trades
 *   f[31] = sector_bear                  → contradicts LONG  trades
 *
 *   Having BOTH f[54] and f[55] active simultaneously is NOT a contradiction
 *   — it means the stock is RANGING (equal lows + equal highs). This was the
 *   root bug causing penalty=55 on every candidate in ranging markets.
 *
 * Fully owned by the AI module — no external strategy dependencies.
 */
@Service
@ConditionalOnProperty(name = "ai.trading.enabled", havingValue = "true")
@Slf4j
public class AiConfidenceScoringEngine {

    /**
     * Compute final confidence score.
     * Direction-aware: only penalises patterns that CONTRADICT the trade direction.
     *
     * @param mlConfidence  raw confidence from AiProbabilityEngine (0–1)
     * @param features      60-feature vector
     * @param direction     "LONG" or "SHORT"
     * @return final confidence (0–1), minimum 0.10
     */
    public double computeConfidence(double mlConfidence,
                                    double[] features,
                                    String direction) {
        if (features == null || features.length < 60) return mlConfidence;

        boolean isLong  = "LONG".equalsIgnoreCase(direction);
        boolean isShort = "SHORT".equalsIgnoreCase(direction);

        int penalty = 0;

        // ── Contradiction 1: Liquidity sweep contradicts trade direction ───
        // f[54] = sweep_low  (bullish reversal pattern) → bad for SHORT
        // f[55] = sweep_high (bearish reversal pattern) → bad for LONG
        // NOTE: both being > 0 simultaneously = RANGING, not a contradiction
        if (isShort && features[54] > 0.5 && features[55] < 0.3) {
            // Bullish sweep detected but we are going SHORT — genuine contradiction
            penalty += 20;
            log.debug("[AI-CONF] {} | Sweep-low contradicts SHORT direction — penalty=20",
                    direction);
        }
        if (isLong && features[55] > 0.5 && features[54] < 0.3) {
            // Bearish sweep detected but we are going LONG — genuine contradiction
            penalty += 20;
            log.debug("[AI-CONF] {} | Sweep-high contradicts LONG direction — penalty=20",
                    direction);
        }

        // ── Contradiction 2: Sector alignment contradicts trade direction ──
        // f[30] = sector_bullish aligned
        // f[31] = sector_bearish aligned
        if (isShort && features[30] > 0.5 && features[31] < 0.3) {
            // Sector is strongly bullish but we are going SHORT
            penalty += 15;
            log.debug("[AI-CONF] {} | Sector bullish contradicts SHORT — penalty=15",
                    direction);
        }
        if (isLong && features[31] > 0.5 && features[30] < 0.3) {
            // Sector is strongly bearish but we are going LONG
            penalty += 15;
            log.debug("[AI-CONF] {} | Sector bearish contradicts LONG — penalty=15",
                    direction);
        }

        // ── Contradiction 3: EMA vs momentum mismatch ─────────────────────
        // f[3] = EMA stack (positive = bullish, negative = bearish)
        // f[8] = 1-candle momentum
        if (features[3] > 0.5 && features[8] < -0.4) {
            // EMA fully bullish but last candle strongly down — momentum divergence
            penalty += 10;
            log.debug("[AI-CONF] EMA bullish but momentum negative — penalty=10");
        }
        if (features[3] < -0.5 && features[8] > 0.4) {
            // EMA fully bearish but last candle strongly up — momentum divergence
            penalty += 10;
            log.debug("[AI-CONF] EMA bearish but momentum positive — penalty=10");
        }

        // ── Contradiction 4: RSI extreme vs trade direction ────────────────
        // f[13] = RSI normalised (0=oversold, 1=overbought, 0.5=neutral)
        if (isLong && features[13] > 0.75) {
            // RSI overbought — late long entry, poor risk/reward
            penalty += 8;
            log.debug("[AI-CONF] RSI overbought for LONG entry — penalty=8");
        }
        if (isShort && features[13] < 0.25) {
            // RSI oversold — late short entry, poor risk/reward
            penalty += 8;
            log.debug("[AI-CONF] RSI oversold for SHORT entry — penalty=8");
        }

        double finalConf = mlConfidence - (penalty / 100.0);
        double result = Math.max(0.10, Math.min(1.0, finalConf));

        if (penalty > 0) {
            log.debug("[AI-CONF] {} direction={} mlConf={} penalty={} finalConf={}",
                    "candidate", direction,
                    String.format("%.2f", mlConfidence),
                    penalty,
                    String.format("%.2f", result));
        }

        return result;
    }

    /**
     * Backward-compatible overload for callers that do not pass direction yet.
     * Defaults to no direction-based contradiction checking.
     */
    public double computeConfidence(double mlConfidence, double[] features) {
        return computeConfidence(mlConfidence, features, "UNKNOWN");
    }
}