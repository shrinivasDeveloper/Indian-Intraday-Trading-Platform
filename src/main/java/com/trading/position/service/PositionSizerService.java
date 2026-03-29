package com.trading.position.service;

import com.trading.execution.service.MarginCheckService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * PositionSizerService — 1% risk rule with hard ceiling enforcement.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * FLAW 4 FIX — Hard Risk Ceiling (₹1,000 per trade is a ceiling, not a target):
 *
 *   PROBLEM 1 — MarginCheckService.recommendedQty() bypass:
 *     The formula computes qty with FLOOR rounding (correct).
 *     But margin.recommendedQty() is an opaque value — it can return any integer
 *     including one LARGER than the formula-derived qty if the margin service has
 *     a bug or rounds up internally. The old code blindly used margin.recommendedQty()
 *     without re-checking the risk ceiling.
 *
 *   PROBLEM 2 — DECIMAL32 precision loss:
 *     MathContext.DECIMAL32 has only 7 significant digits. On edge cases:
 *       capital=99999.99, riskPerTrade=0.01, slDist=9.50
 *       riskAmt = 999.9999 → DECIMAL32 rounds to 1000.000
 *       qty = 1000.000 / 9.50 = 105.26... → FLOOR → 105
 *       actualRisk = 105 * 9.50 = 997.50 ✓ (safe)
 *     But if intermediate DECIMAL32 rounding pushes riskAmt UP slightly:
 *       riskAmt = 1000.001 (DECIMAL32 artifact) → qty = 105.26... → 105
 *       → still safe in this case, but not guaranteed on all inputs.
 *     FIX: Use DECIMAL64 for intermediate arithmetic, FLOOR for final qty.
 *
 *   PROBLEM 3 — No post-adjustment verification:
 *     After margin adjustment, actual risk was never re-verified.
 *
 *   SOLUTION: After ALL quantity adjustments (formula, max-position cap, margin),
 *   apply a mandatory final check:
 *
 *     hardCeiling = capital × riskPerTrade  (e.g. ₹1,000 on ₹1L capital)
 *     actualRisk  = finalQty × slDist
 *     if actualRisk > hardCeiling:
 *         finalQty = floor(hardCeiling / slDist)   ← re-floor with DECIMAL64
 *
 *   This is a second line of defence. Normal operation never triggers it.
 *   It only activates when margin service or floating point conspires to push
 *   qty above the mathematically safe value.
 *
 *   Additionally: if margin.recommendedQty() > formulaQty, we cap at formulaQty.
 *   Margin service can reduce qty (insufficient margin) but never increase it.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * FLAW 2 FIX — Dynamic Impact-Cost Slippage:
 *
 *   Added ImpactCostCalculator inner class.
 *   Called by PaperTradeExecutionService to compute entry slippage dynamically
 *   based on the stock's ATR% and volume stress (actual vs average volume).
 *
 *   Formula (proxy for NSE impact cost):
 *     normalizedAtr  = atr / entryPrice
 *     volumeStress   = max(1.0, avgVol / currentVol)  — scarcity premium
 *     rawSlip        = BASE + ATR_WEIGHT × normalizedAtr + VOL_WEIGHT × (volumeStress − 1)
 *     slipPct        = clamp(rawSlip, MIN_SLIP, MAX_SLIP)
 *
 *   Calibration:
 *     BASE=0.0003   (3 bps — minimum for any market order on NSE)
 *     ATR_WEIGHT=0.15  (15% of ATR% = impact from intraday range)
 *     VOL_WEIGHT=0.001 (0.1% per unit of volume stress)
 *     MIN_SLIP=0.0003  (3 bps — Nifty50 liquid stock, normal session)
 *     MAX_SLIP=0.005   (50 bps — illiquid mid-cap, high-ATR session)
 *
 *   Examples:
 *     RELIANCE (ATR%=0.8%, volume=2×avg) → 0.03% + 0.12% + 0 = 0.15% → clamp 0.15%
 *     Mid-cap (ATR%=2.5%, volume=0.3×avg) → 0.03% + 0.375% + 0.07% = 0.475% → clamp 0.475%
 *     Illiquid (ATR%=4%, volume=0.1×avg) → 0.03% + 0.6% + 0.09% = 0.72% → clamp 0.5%
 * ═══════════════════════════════════════════════════════════════════════
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PositionSizerService {

    private final MarginCheckService marginCheckService;

    @Value("${trading.risk-per-trade:0.01}")
    private BigDecimal riskPerTrade;

    @Value("${trading.max-position-pct:0.20}")
    private BigDecimal maxPositionPct;

    // ══════════════════════════════════════════════════════════════════════════
    // Main position calculation
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Calculates position size with strict 1% risk ceiling enforcement.
     *
     * Steps:
     *   1. Formula qty   = floor(capital × riskPerTrade / slDist)   [DECIMAL64]
     *   2. Max-pct cap   = floor(capital × maxPositionPct / entry)
     *   3. qty           = min(formulaQty, maxPctQty)
     *   4. Margin check  = min(qty, margin.recommendedQty())         [never increases qty]
     *   5. Hard ceiling  = if actualRisk > hardCeiling → re-floor    [FLAW 4 FIX]
     */
    public PositionSize calculate(BigDecimal capital, BigDecimal entry,
                                  BigDecimal stopLoss, String symbol,
                                  String direction) {
        // ── Step 0: validate inputs ────────────────────────────────────────────
        BigDecimal slDist = entry.subtract(stopLoss).abs();
        if (slDist.compareTo(BigDecimal.ZERO) == 0)
            return PositionSize.invalid("Stop-loss distance is zero");
        if (capital.compareTo(BigDecimal.ZERO) <= 0)
            return PositionSize.invalid("Capital is zero");

        // FLAW 4 FIX: hard ceiling in rupees — this is the absolute maximum risk per trade
        BigDecimal hardCeiling = capital.multiply(riskPerTrade, MathContext.DECIMAL64);

        // ── Step 1: formula quantity (DECIMAL64 for precision) ─────────────────
        // FLOOR is mathematically correct — ceiling would guarantee >1% risk.
        int formulaQty = hardCeiling
                .divide(slDist, MathContext.DECIMAL64)
                .setScale(0, RoundingMode.FLOOR)
                .intValue();

        // ── Step 2: max-position cap ───────────────────────────────────────────
        int maxPctQty = capital.multiply(maxPositionPct, MathContext.DECIMAL64)
                .divide(entry, MathContext.DECIMAL64)
                .setScale(0, RoundingMode.FLOOR)
                .intValue();

        // ── Step 3: take the more conservative of the two ─────────────────────
        int qty = Math.min(formulaQty, maxPctQty);
        if (qty <= 0)
            return PositionSize.invalid("Calculated quantity is zero (capital=" + capital
                    + " slDist=" + slDist + " entry=" + entry + ")");

        // ── Step 4: margin check — can only REDUCE qty, never increase ─────────
        MarginCheckService.MarginResult margin =
                marginCheckService.check(symbol, direction, qty, entry);
        int marginQty = margin.recommendedQty();

        // FLAW 4 FIX: margin service must never push qty above formulaQty
        if (marginQty > qty) {
            log.warn("[SIZER] {} MarginService returned {} > formulaQty {} — capping at formula",
                    symbol, marginQty, qty);
            marginQty = qty;
        }
        qty = marginQty;
        if (qty <= 0) return PositionSize.invalid("Insufficient margin");

        // ── Step 5: Hard ceiling enforcement (FLAW 4 FIX) ─────────────────────
        // Re-verify actual risk after all adjustments. This catches edge cases where
        // DECIMAL32 artifacts, margin rounding, or future code changes could push
        // actual risk above the declared ceiling.
        BigDecimal actualRisk = slDist.multiply(BigDecimal.valueOf(qty), MathContext.DECIMAL64);
        if (actualRisk.compareTo(hardCeiling) > 0) {
            // Re-floor under DECIMAL64 precision — guarantees ≤ ceiling
            int safeQty = hardCeiling
                    .divide(slDist, MathContext.DECIMAL64)
                    .setScale(0, RoundingMode.FLOOR)
                    .intValue();
            log.warn("[SIZER] {} Hard ceiling enforced: qty {} → {} " +
                            "(actualRisk ₹{} > ceiling ₹{})",
                    symbol, qty, safeQty,
                    String.format("%.2f", actualRisk.doubleValue()),
                    String.format("%.2f", hardCeiling.doubleValue()));
            qty       = safeQty;
            actualRisk = slDist.multiply(BigDecimal.valueOf(qty), MathContext.DECIMAL64);
        }

        if (qty <= 0) return PositionSize.invalid("Quantity zero after ceiling enforcement");

        // ── Step 6: final metrics ──────────────────────────────────────────────
        BigDecimal actualRiskPct = actualRisk
                .divide(capital, MathContext.DECIMAL64)
                .multiply(BigDecimal.valueOf(100));

        // Sanity assertion — should never fire, but logs if ceiling logic has a bug
        if (actualRiskPct.doubleValue() > riskPerTrade.doubleValue() * 100 + 0.001) {
            log.error("[SIZER] CEILING VIOLATED: {} actual risk {}% > cap {}% — BUG",
                    symbol,
                    String.format("%.4f", actualRiskPct.doubleValue()),
                    String.format("%.4f", riskPerTrade.doubleValue() * 100));
        }

        log.info("[SIZER] {}: qty={} risk=₹{} ({}%) entry={} sl={} ceiling=₹{}",
                symbol, qty,
                String.format("%.2f", actualRisk.doubleValue()),
                actualRiskPct.setScale(3, RoundingMode.HALF_UP),
                entry, stopLoss,
                String.format("%.2f", hardCeiling.doubleValue()));

        return PositionSize.valid(qty, hardCeiling, actualRisk, actualRiskPct,
                entry.multiply(BigDecimal.valueOf(qty)));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // FLAW 2 FIX — Dynamic Impact Cost Calculator
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Computes dynamic entry slippage percentage based on ATR and volume stress.
     *
     * This replaces the flat ENTRY_SLIP=0.0005 constant. PaperTradeExecutionService
     * calls this instead of using the hardcoded constant.
     *
     * Formula derivation (proxy for NSE impact cost):
     *   normalizedAtr = atr / entryPrice          → volatility as % of price
     *   volumeStress  = max(1.0, avgVol / curVol) → scarcity premium (≥1.0)
     *   rawSlip = BASE + (ATR_WEIGHT × normalizedAtr) + (VOL_WEIGHT × (volumeStress − 1))
     *   slipPct = clamp(rawSlip, MIN_SLIP, MAX_SLIP)
     *
     * Parameters (all configurable via application.yml):
     *   paper-trading.impact-cost.base:         0.0003  (3 bps floor)
     *   paper-trading.impact-cost.atr-weight:   0.15
     *   paper-trading.impact-cost.vol-weight:   0.001
     *   paper-trading.impact-cost.min-slip:     0.0003
     *   paper-trading.impact-cost.max-slip:     0.005   (50 bps ceiling)
     */
    public static final class ImpactCostCalculator {

        // Defaults — overridden by @Value in PaperTradeExecutionService
        private static final double BASE_SLIP   = 0.0003;
        private static final double ATR_WEIGHT  = 0.15;
        private static final double VOL_WEIGHT  = 0.001;
        private static final double MIN_SLIP    = 0.0003;
        private static final double MAX_SLIP    = 0.005;

        private ImpactCostCalculator() {}

        /**
         * Computes impact-cost-adjusted entry slippage percentage.
         *
         * @param entryPrice  proposed entry price (BigDecimal, NSE tick-aligned)
         * @param atr         14-period ATR in rupees (from TechnicalAnalysisService or ArmedStock)
         * @param currentVol  volume of the current/signal candle
         * @param avgVol20    20-candle rolling average volume
         * @param basePct     configurable BASE override (pass ≤0 to use default)
         * @param atrWeight   configurable ATR_WEIGHT override (pass ≤0 to use default)
         * @param volWeight   configurable VOL_WEIGHT override (pass ≤0 to use default)
         * @param minSlip     configurable MIN_SLIP override (pass ≤0 to use default)
         * @param maxSlip     configurable MAX_SLIP override (pass ≤0 to use default)
         * @return slippage percentage (e.g. 0.0025 = 0.25%)
         */
        public static double compute(double entryPrice, double atr,
                                     long currentVol, double avgVol20,
                                     double basePct, double atrWeight,
                                     double volWeight, double minSlip, double maxSlip) {
            // Apply config overrides or defaults
            double base    = basePct   > 0 ? basePct   : BASE_SLIP;
            double atrW    = atrWeight > 0 ? atrWeight : ATR_WEIGHT;
            double volW    = volWeight > 0 ? volWeight : VOL_WEIGHT;
            double minS    = minSlip   > 0 ? minSlip   : MIN_SLIP;
            double maxS    = maxSlip   > 0 ? maxSlip   : MAX_SLIP;

            if (entryPrice <= 0) return base;

            // Volatility component: ATR as fraction of price
            double normalizedAtr = atr / entryPrice;

            // Volume stress component: how scarce is liquidity right now?
            // volumeStress=1 if current volume ≥ average (no stress).
            // volumeStress=3 if current volume is 1/3 of average (illiquid).
            double volumeStress = (avgVol20 > 0 && currentVol > 0)
                    ? Math.max(1.0, avgVol20 / (double) currentVol)
                    : 1.0;

            double rawSlip = base
                    + (atrW * normalizedAtr)
                    + (volW * (volumeStress - 1.0));

            // Clamp to [minSlip, maxSlip]
            return Math.max(minS, Math.min(maxS, rawSlip));
        }

        /**
         * Convenience overload using all default parameters.
         * Use this when no yml overrides are available.
         */
        public static double compute(double entryPrice, double atr,
                                     long currentVol, double avgVol20) {
            return compute(entryPrice, atr, currentVol, avgVol20,
                    BASE_SLIP, ATR_WEIGHT, VOL_WEIGHT, MIN_SLIP, MAX_SLIP);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PositionSize record
    // ══════════════════════════════════════════════════════════════════════════

    public record PositionSize(
            boolean    valid,
            int        quantity,
            BigDecimal intendedRisk,
            BigDecimal actualRisk,
            BigDecimal actualRiskPct,
            BigDecimal totalValue,
            String     invalidReason
    ) {
        public static PositionSize valid(int q, BigDecimal ir, BigDecimal ar,
                                         BigDecimal arp, BigDecimal tv) {
            return new PositionSize(true, q, ir, ar, arp, tv, null);
        }

        public static PositionSize invalid(String reason) {
            return new PositionSize(false, 0, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, reason);
        }

        public boolean isValid() { return valid; }
    }
}