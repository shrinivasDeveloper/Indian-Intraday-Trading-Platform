package com.trading.ai.engine;

import com.trading.ai.data.AiMarketDataService;
import com.trading.ai.data.AiMarketDataService.AiSRLevel;
import com.trading.ai.data.AiMarketDataService.AiStructureLevels;
import com.trading.ai.model.AiCandidate;
import com.trading.ai.model.AiPrediction;
import com.trading.ai.model.AiTradeDecision;
import com.trading.papertrading.model.PaperAccount;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * AiRiskAssessmentEngine
 *
 * Position sizing, stop-loss placement, and target calculation.
 * Entirely owned by the AI module.
 * Uses AI's own S/R levels from AiMarketDataService — not HighRR's.
 *
 * RULES:
 *   - Risk 1% of capital per trade
 *   - SL: just beyond nearest AI S/R level + ATR noise floor
 *   - T1: nearest S/R level at minimum 2R
 *   - T2: T1 distance × 1.6 beyond entry
 *   - Max SL: 1.5% from entry
 */
@Service
@ConditionalOnProperty(name = "ai.trading.enabled", havingValue = "true")
@Slf4j
public class AiRiskAssessmentEngine {

    private static final double RISK_PCT       = 0.01;  // 1% capital risk per trade
    private static final double MIN_RR         = 2.0;   // minimum target RR
    private static final double MAX_SL_PCT     = 0.015; // max 1.5% SL from entry
    private static final double SL_BUFFER_PCT  = 0.0015;// 0.15% buffer beyond zone
    private static final double T2_MULTIPLIER  = 1.6;   // T2 = T1-dist × 1.6

    private final AiMarketDataService aiData;
    private final PaperAccount        paperAccount;
    private final AiConfidenceScoringEngine    confidenceEngine;
    private final AiTradeQualityScoringEngine  qualityEngine;

    @Value("${trading.capital:100000}")
    private double defaultCapital;

    public AiRiskAssessmentEngine(AiMarketDataService aiData,
                                  PaperAccount paperAccount,
                                  AiConfidenceScoringEngine confidenceEngine,
                                  AiTradeQualityScoringEngine qualityEngine) {
        this.aiData           = aiData;
        this.paperAccount     = paperAccount;
        this.confidenceEngine = confidenceEngine;
        this.qualityEngine    = qualityEngine;
    }

    /**
     * Apply full risk assessment to a candidate.
     * Returns AiTradeDecision with all price levels and position size set.
     * Returns null if risk assessment fails (e.g. RR < 2.0, SL too wide).
     */
    public AiTradeDecision assess(AiCandidate candidate, AiPrediction prediction,
                                  String regime) {
        String symbol    = candidate.getSymbol();
        double ltp       = candidate.getLtp();
        boolean isLong   = "LONG".equals(candidate.getSuggestedDirection());
        double capital   = resolveCapital();

        // ── Entry price (0.05% buffer for market order slippage) ─────────
        double entryD = isLong ? ltp * 1.0005 : ltp * 0.9995;
        BigDecimal entry = bd(entryD, 2);
        double entryDbl  = entry.doubleValue();

        // ── Stop-loss: AI's own S/R + ATR floor ──────────────────────────
        AiStructureLevels structure = aiData.getStructure(symbol);
        double atrDist = structure != null ? structure.atr14() : entryDbl * 0.008;

        double slD;
        if (structure != null) {
            if (isLong) {
                AiSRLevel supp = aiData.nearestSupportBelow(symbol, entryDbl);
                slD = supp != null
                        ? supp.price() * (1 - SL_BUFFER_PCT)
                        : entryDbl - atrDist;
            } else {
                AiSRLevel res = aiData.nearestResistanceAbove(symbol, entryDbl);
                slD = res != null
                        ? res.price() * (1 + SL_BUFFER_PCT)
                        : entryDbl + atrDist;
            }
        } else {
            slD = isLong ? entryDbl - atrDist : entryDbl + atrDist;
        }

        // Apply ATR noise floor
        double atrFloor = isLong ? entryDbl - atrDist : entryDbl + atrDist;
        slD = isLong ? Math.min(slD, atrFloor) : Math.max(slD, atrFloor);

        // Apply max SL cap
        double maxSlDist = entryDbl * MAX_SL_PCT;
        if (isLong  && (entryDbl - slD) > maxSlDist) slD = entryDbl - maxSlDist;
        if (!isLong && (slD - entryDbl) > maxSlDist) slD = entryDbl + maxSlDist;

        BigDecimal sl      = bd(slD, isLong ? RoundingMode.FLOOR : RoundingMode.CEILING);
        double riskPerShare = Math.abs(entry.subtract(sl).doubleValue());
        if (riskPerShare <= 0) {
            log.debug("[AI-RISK] {} zero risk — rejected", symbol);
            return null;
        }

        // ── Position sizing: 1% capital risk ─────────────────────────────
        double riskAmt = capital * RISK_PCT;
        int qty = (int) Math.floor(riskAmt / riskPerShare);
        if (qty <= 0) {
            log.debug("[AI-RISK] {} qty=0 (risk={} riskPerShare={}) — rejected",
                    symbol,
                    String.format("%.2f", riskAmt),
                    String.format("%.2f", riskPerShare));
            return null;
        }

        // ── Targets ───────────────────────────────────────────────────────
        BigDecimal t1, t2;
        if (structure != null) {
            // T1: nearest AI S/R level at ≥ 2R
            AiSRLevel t1Level = isLong
                    ? aiData.nearestResistanceAbove(symbol, entryDbl)
                    : aiData.nearestSupportBelow(symbol, entryDbl);

            if (t1Level != null) {
                double t1Dist = Math.abs(t1Level.price() - entryDbl);
                double t1RR   = t1Dist / riskPerShare;
                if (t1RR >= MIN_RR) {
                    t1 = bd(t1Level.price(), 2);
                } else {
                    // Fallback arithmetic T1
                    t1 = isLong
                            ? bd(entryDbl + riskPerShare * MIN_RR, 2)
                            : bd(entryDbl - riskPerShare * MIN_RR, 2);
                }
            } else {
                t1 = isLong
                        ? bd(entryDbl + riskPerShare * MIN_RR, 2)
                        : bd(entryDbl - riskPerShare * MIN_RR, 2);
            }
        } else {
            t1 = isLong
                    ? bd(entryDbl + riskPerShare * MIN_RR, 2)
                    : bd(entryDbl - riskPerShare * MIN_RR, 2);
        }

        // T2: T1-distance × 1.6 beyond entry
        double t1Dist = Math.abs(t1.doubleValue() - entryDbl);
        t2 = isLong
                ? bd(entryDbl + t1Dist * T2_MULTIPLIER, 2)
                : bd(entryDbl - t1Dist * T2_MULTIPLIER, 2);

        // ── Final RR check ─────────────────────────────────────────────────
        double rrRatio = t1Dist / riskPerShare;
        if (rrRatio < MIN_RR) {
            log.debug("[AI-RISK] {} RR={} < {} — rejected",
                    symbol,
                    String.format("%.1f", rrRatio),
                    String.format("%.1f", MIN_RR));
            return null;
        }

        // ── Confidence and quality scoring ────────────────────────────────
        // Pass direction so contradiction check is direction-aware
        double confidence   = confidenceEngine.computeConfidence(
                prediction.getConfidence(),
                candidate.getFeatureVector().getFeatures(),
                candidate.getSuggestedDirection());
        int qualityScore    = qualityEngine.scoreTradeQuality(candidate, prediction, rrRatio);

        log.debug("[AI-RISK] {} {} entry={} sl={} t1={} t2={} RR={} qty={} risk=₹{} conf={}% quality={}",
                symbol, candidate.getSuggestedDirection(),
                String.format("%.2f", entryDbl),
                String.format("%.2f", slD),
                String.format("%.2f", t1.doubleValue()),
                String.format("%.2f", t2.doubleValue()),
                String.format("%.1f", rrRatio),
                qty,
                String.format("%.0f", riskAmt),
                String.format("%.0f", confidence * 100),
                qualityScore);

        return AiTradeDecision.builder()
                .symbol(symbol)
                .direction(candidate.getSuggestedDirection())
                .entryPrice(entry)
                .stopLoss(sl)
                .target1(t1)
                .target2(t2)
                .positionSize(qty)
                .riskAmount(bd(riskAmt, 2))
                .probabilityOfSuccess(prediction.getSuccessProbability())
                .expectedRR(prediction.getExpectedRR())
                .expectedReturn(prediction.getExpectedReturn())
                .confidence(confidence)
                .rrRatio(rrRatio)
                .tradeQualityScore(qualityScore)
                .opportunityScore((int) candidate.getNumericScore())
                .riskScore(100 - qualityScore)
                .reasoning(prediction.getReasoning())
                .bullScenario("HTF trend + AI pattern detected — continuation expected")
                .bearScenario("Setup fails if key S/R level breaks on volume")
                .dominantFactor(candidate.getHtfTrend())
                .exitPlan("T1 at structural level. Trail SL after T1. EOD exit 15:05.")
                .reasoningSummary(prediction.getReasoning().substring(0,
                        Math.min(80, prediction.getReasoning().length())))
                .htfTrend(candidate.getHtfTrend())
                .sector(candidate.getSector())
                .numericPreScore(candidate.getNumericScore())
                .featureVector(candidate.getFeatureVector())
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    private double resolveCapital() {
        try { return paperAccount.getCapital().doubleValue(); }
        catch (Exception e) { return defaultCapital; }
    }

    private BigDecimal bd(double v, int scale) {
        return BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP);
    }

    private BigDecimal bd(double v, RoundingMode mode) {
        return BigDecimal.valueOf(v).setScale(2, mode);
    }
}