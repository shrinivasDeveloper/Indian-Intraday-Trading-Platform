package com.trading.ai.engine;

import com.trading.ai.data.AiMarketDataService;
import com.trading.ai.data.AiMarketDataService.AiSRLevel;
import com.trading.ai.data.AiMarketDataService.AiStructureLevels;
import com.trading.ai.model.AiCandidate;
import com.trading.ai.model.AiPrediction;
import com.trading.ai.model.AiTradeDecision;
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
 *
 * SL METHOD: Same price-based tiered SL as NEWS_CATALYST_V1 (proven working).
 *   ≤ ₹130  → 2.0% SL   ≤ ₹170  → 1.7% SL   ≤ ₹200  → 1.3% SL
 *   ≤ ₹400  → 1.0% SL   ≤ ₹700  → 0.7% SL   ≤ ₹1200 → 0.6% SL
 *   > ₹1200 → 0.5% SL
 *
 * This ensures:
 *   - Cheap stocks (₹50-130) get wider SL — they're more volatile in %
 *   - Expensive stocks (₹1200+) get tighter SL — less % movement needed
 *   - Consistent with news strategy — same risk model across AI trades
 *   - No dependency on S/R structure data being available
 *
 * T1 = SL distance × 2.0  (2:1 RR minimum)
 * T2 = SL distance × 3.2  (T1dist × 1.6 = 3.2R)
 * Risk per trade = 1% of capital
 *
 * INDEPENDENCE FIX (cleanup audit): removed PaperAccount dependency —
 * that's the shared, cross-strategy capital pool other (removed)
 * strategies also drew from. Capital is now resolved from
 * AiNewsCapitalLedger, the same independent ledger AiTradeManagementEngine
 * and NewsTradingStrategy already use — so AI's position sizing is no
 * longer entangled with any other strategy's capital usage, and
 * PaperAccount has zero remaining callers from the AI module.
 */
@Service
@ConditionalOnProperty(name = "ai.trading.enabled", havingValue = "true")
@Slf4j
public class AiRiskAssessmentEngine {

    private static final double RISK_PCT      = 0.01;  // 1% capital risk per trade
    private static final double MIN_RR        = 2.0;   // T1 minimum RR
    private static final double T2_MULTIPLIER = 1.6;   // T2 = T1-dist × 1.6 = 3.2R

    private final AiMarketDataService aiData;
    private final com.trading.ai.execution.AiNewsCapitalLedger capitalLedger;
    private final AiConfidenceScoringEngine   confidenceEngine;
    private final AiTradeQualityScoringEngine qualityEngine;

    @Value("${trading.capital:100000}")
    private double defaultCapital;

    public AiRiskAssessmentEngine(AiMarketDataService aiData,
                                  com.trading.ai.execution.AiNewsCapitalLedger capitalLedger,
                                  AiConfidenceScoringEngine confidenceEngine,
                                  AiTradeQualityScoringEngine qualityEngine) {
        this.aiData           = aiData;
        this.capitalLedger    = capitalLedger;
        this.confidenceEngine = confidenceEngine;
        this.qualityEngine    = qualityEngine;
    }

    /**
     * Price-based tiered SL — identical to NEWS_CATALYST_V1.
     * Cheaper stocks need wider SL% due to higher tick volatility.
     */
    private double computeSlPct(double price) {
        if      (price <= 130)  return 0.020;  // 2.0%
        else if (price <= 170)  return 0.017;  // 1.7%
        else if (price <= 200)  return 0.013;  // 1.3%
        else if (price <= 400)  return 0.010;  // 1.0%
        else if (price <= 700)  return 0.007;  // 0.7%
        else if (price <= 1200) return 0.006;  // 0.6%
        else                    return 0.005;  // 0.5%
    }

    /**
     * Apply full risk assessment to a candidate.
     * Returns AiTradeDecision with all price levels and position size set.
     * Returns null if RR < 2.0 or quantity is zero.
     */
    public AiTradeDecision assess(AiCandidate candidate, AiPrediction prediction,
                                  String regime) {
        String  symbol  = candidate.getSymbol();
        double  ltp     = candidate.getLtp();
        boolean isLong  = "LONG".equals(candidate.getSuggestedDirection());
        double  capital = resolveCapital();

        // FIX (found via direct user investigation of repeated "risk engine
        // could not size a valid trade" blocks on genuinely good candidates
        // like UTIAMC, PNB, ULTRACEMCO, BANKBARODA - later confirmed one of
        // these, UTIAMC, DID trade successfully later that same day,
        // proving the block was a momentary data issue, not a permanent
        // one). candidate.getLtp() is a SNAPSHOTTED price from earlier in
        // the pipeline (discovery/scoring stage) - by the time this method
        // runs, that snapshot can occasionally be stale, zero, or otherwise
        // invalid, which makes entry and SL both compute to the same
        // degenerate value below (riskPerShare=0). Rather than immediately
        // rejecting a genuinely good, high-scoring candidate on a
        // momentary data hiccup, re-fetch a FRESH live price directly
        // before giving up - using the same aiData dependency this class
        // already has, zero new wiring needed.
        if (ltp <= 0) {
            log.warn("[AI-RISK] {} snapshotted LTP was invalid ({}) - re-fetching a fresh " +
                    "live price before rejecting", symbol, ltp);
            ltp = aiData.getLtp(symbol);
            if (ltp <= 0) {
                log.warn("[AI-RISK] {} - fresh live price also invalid ({}) - genuinely " +
                        "no valid price available right now, rejecting", symbol, ltp);
                return null;
            }
            log.info("[AI-RISK] {} - fresh live price Rs.{} is valid, proceeding with sizing",
                    symbol, ltp);
        }

        // ── Entry: 0.05% buffer for market order slippage ────────────────
        double     entryDbl = isLong ? ltp * 1.0005 : ltp * 0.9995;
        BigDecimal entry    = bd(entryDbl, 2);
        entryDbl = entry.doubleValue();

        // ── SL: price-based tiered (same as news strategy) ───────────────
        double slPct        = computeSlPct(entryDbl);
        double slDist       = entryDbl * slPct;
        double slD          = isLong ? entryDbl - slDist : entryDbl + slDist;
        BigDecimal sl       = bd(slD, isLong ? RoundingMode.FLOOR : RoundingMode.CEILING);
        double riskPerShare = Math.abs(entryDbl - sl.doubleValue());

        if (riskPerShare <= 0) {
            log.debug("[AI-RISK] {} zero riskPerShare — rejected", symbol);
            return null;
        }

        // ── Position sizing: 1% capital risk, CAPPED by available capital ───
        // FIX (critical, found before going live): the risk-only formula
        // below can size a position whose total VALUE (qty × entry) exceeds
        // available capital entirely — this was true even at the original
        // ₹1L default (e.g. a ₹1400 stock at 0.5% SL sizes to ~₹1,98,800
        // position value, almost double the capital), it just rarely showed
        // up. At smaller capital amounts it becomes the common case, not
        // the exception. qty is now capped by both risk AND affordability.
        double riskAmt        = capital * RISK_PCT;
        int    riskBasedQty   = (int) Math.floor(riskAmt / riskPerShare);
        int    affordableQty  = (int) Math.floor(capital / entryDbl);
        int    qty            = Math.min(riskBasedQty, affordableQty);
        if (qty <= 0) {
            log.debug("[AI-RISK] {} qty=0 (riskAmt={} riskPerShare={} riskBasedQty={} " +
                            "affordableQty={}) — rejected",
                    symbol,
                    String.format("%.2f", riskAmt),
                    String.format("%.2f", riskPerShare),
                    riskBasedQty, affordableQty);
            return null;
        }
        if (affordableQty < riskBasedQty) {
            log.info("[AI-RISK] {} qty capped by capital: risk-based={} → affordable={} " +
                            "(entry={} capital={})",
                    symbol, riskBasedQty, affordableQty,
                    String.format("%.2f", entryDbl), String.format("%.0f", capital));
        }

        // ── T1: 2:1 RR (same as news strategy) ───────────────────────────
        double     t1Dist = riskPerShare * MIN_RR;
        BigDecimal t1     = isLong
                ? bd(entryDbl + t1Dist, 2)
                : bd(entryDbl - t1Dist, 2);

        // ── T2: T1-distance × 1.6 (3.2R) ─────────────────────────────────
        BigDecimal t2 = isLong
                ? bd(entryDbl + t1Dist * T2_MULTIPLIER, 2)
                : bd(entryDbl - t1Dist * T2_MULTIPLIER, 2);

        double rrRatio = t1Dist / riskPerShare; // always MIN_RR (2.0)

        // ── Confidence and quality scoring ────────────────────────────────
        double confidence = confidenceEngine.computeConfidence(
                prediction.getConfidence(),
                candidate.getFeatureVector().getFeatures(),
                candidate.getSuggestedDirection());
        int qualityScore = qualityEngine.scoreTradeQuality(candidate, prediction, rrRatio);

        log.debug("[AI-RISK] {} {} entry={} sl={}({}%) t1={} t2={} RR={} qty={} risk=₹{}",
                symbol, candidate.getSuggestedDirection(),
                String.format("%.2f", entryDbl),
                String.format("%.2f", sl.doubleValue()),
                String.format("%.1f", slPct * 100),
                String.format("%.2f", t1.doubleValue()),
                String.format("%.2f", t2.doubleValue()),
                String.format("%.1f", rrRatio),
                qty,
                String.format("%.0f", riskAmt));

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
        try { return capitalLedger.getAvailableCapital("AI_TRADING_V2").doubleValue(); }
        catch (Exception e) { return defaultCapital; }
    }

    private BigDecimal bd(double v, int scale) {
        return BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP);
    }

    private BigDecimal bd(double v, RoundingMode mode) {
        return BigDecimal.valueOf(v).setScale(2, mode);
    }
}