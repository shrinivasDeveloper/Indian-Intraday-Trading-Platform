// ═══════════════════════════════════════════════════════════════════════════════
// FILE: src/main/java/com/trading/strategy/ORBStrategy.java
// MODIFIED — adds StrategyValidationTracker integration
// Changes: added tracker field + buildBuySteps/buildSellSteps in generateSignal()
// ═══════════════════════════════════════════════════════════════════════════════
package com.trading.strategy;

import com.trading.domain.Candle;
import com.trading.domain.enums.TradeDirection;
import com.trading.validation.StrategyValidationTracker;
import com.trading.validation.ValidationStepResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Strategy 4 — ORB + VWAP + Sector + Nifty (9:30–12:30 only)
 *
 * Runs INDEPENDENTLY. Does NOT need 7-gate scanner.
 *
 * ALL 9 conditions must pass for BUY:
 *   1. Time: 9:30–12:30
 *   2. Nifty Bullish: niftyBullish=true + change ≥+0.3% + HH/HL in 15min
 *   3. Stock price > VWAP
 *   4. Sector in Top 2 (sectorIsTop=true) + sectorChangePct ≥+0.5% + sectorAlignedBull
 *   5. Stock breaks ORH (first 15min high)
 *   6. Retest: price came back to ORH or VWAP before current candle
 *   7. Confirmation candle: body ≥55%, close near high
 *   8. HH/HL in last 3 × 5min candles
 *   9. Volume ≥1.5× average
 *
 * MODIFICATION: All 9 conditions are now recorded to StrategyValidationTracker
 * every evaluation cycle (every 5min candle) for real-time debug visibility
 * on the dashboard.
 */
@Component
@Slf4j
public class ORBStrategy implements TradingStrategy {

    // ── NEW: Inject validation tracker ──────────────────────────────────────
    @Autowired
    private StrategyValidationTracker tracker;

    @Value("${strategy.orb.nifty-min-change-pct:0.3}")
    private double niftyMinChangePct;

    @Value("${strategy.orb.sector-min-change-pct:0.5}")
    private double sectorMinChangePct;

    @Value("${strategy.orb.volume-min-multiplier:1.5}")
    private double volumeMultiplier;

    @Value("${strategy.orb.retest-tolerance-pct:0.3}")
    private double retestTolPct;

    @Value("${strategy.orb.body-ratio-min:0.55}")
    private double bodyRatioMin;

    @Value("${strategy.orb.wick-ratio-max:0.35}")
    private double wickRatioMax;

    @Value("${strategy.orb.rr:2.5}")
    private double rr;

    @Value("${strategy.orb.entry-start:09:30}")
    private String entryStart;

    @Value("${strategy.orb.entry-end:12:30}")
    private String entryEnd;

    @Value("${strategy.orb.orb-candles:3}")
    private int orbCandles;

    @Override
    public String name() { return "ORB_VWAP_SECTOR"; }

    @Override
    public Optional<TradeSignal> generateSignal(String symbol,
                                                List<Candle> candles5m,
                                                List<Candle> candles15m,
                                                TradingStrategy.MarketContext ctx) {

        // ── CONDITION 1: Time filter ─────────────────────────────────────────
        // Record a minimal step list if we're outside the window
        boolean c1_time = withinTime();
        if (!c1_time) {
            tracker.record(name(), symbol, "BUY", List.of(
                    step(1, "TIME_WINDOW",
                            "Time Window (" + entryStart + "–" + entryEnd + ")",
                            false,
                            "Now=" + LocalTime.now(ZoneId.of("Asia/Kolkata")) + " — outside window")
            ));
            return Optional.empty();
        }

        // Data sufficiency (not tracked — can't evaluate any step without data)
        if (candles5m.size() < orbCandles + 5) return Optional.empty();
        if (candles15m.size() < 6)             return Optional.empty();

        Candle     cur  = candles5m.get(0);
        BigDecimal vwap = ctx.vwap();
        if (vwap.compareTo(BigDecimal.ZERO) == 0) return Optional.empty();

        // ── Opening Range: oldest orbCandles 5-min candles (9:15 – 9:30) ────
        // candles5m is newest-first; oldest = last entries in the list
        int        start     = Math.max(0, candles5m.size() - orbCandles);
        List<Candle> orbPeriod = candles5m.subList(start, candles5m.size());
        BigDecimal orbH = orbPeriod.stream()
                .map(Candle::getHigh).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal orbL = orbPeriod.stream()
                .map(Candle::getLow).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        if (orbH.compareTo(BigDecimal.ZERO) == 0 || orbL.compareTo(BigDecimal.ZERO) == 0)
            return Optional.empty();

        // Average volume of last 20 candles (excluding current)
        double avgVol = candles5m.subList(1, Math.min(21, candles5m.size()))
                .stream().mapToLong(Candle::getVolume).average().orElse(1);

        double volRatio = avgVol > 0 ? (double) cur.getVolume() / avgVol : 0;

        // ══ BUY: Compute all 9 conditions ═══════════════════════════════════
        boolean c2_nifty  = ctx.niftyBullish()
                && ctx.niftyChangePct() >= niftyMinChangePct
                && hasHHHL(candles15m.subList(0, Math.min(6, candles15m.size())));
        boolean c3_vwap   = cur.getClose().compareTo(vwap) > 0;
        boolean c4_sector = ctx.sectorIsTop()
                && ctx.sectorChangePct() >= sectorMinChangePct
                && ctx.sectorAlignedBull();
        boolean c5_orb    = cur.getClose().compareTo(orbH) > 0;
        boolean c6_retest = retestOccurred(
                candles5m.subList(1, Math.min(5, candles5m.size())), orbH, vwap);
        boolean c7_candle = isStrongBullCandle(cur);
        boolean c8_struct = hasHHHL(candles5m.subList(0, Math.min(4, candles5m.size())));
        boolean c9_vol    = cur.getVolume() >= avgVol * volumeMultiplier;

        // ══ BUY: Build and record validation steps ═══════════════════════════
        List<ValidationStepResult> buySteps = new ArrayList<>();
        buySteps.add(step(1, "TIME_WINDOW",
                "Time Window (" + entryStart + "–" + entryEnd + ")", true,
                "✓ Within window at " + LocalTime.now(ZoneId.of("Asia/Kolkata"))));
        buySteps.add(step(2, "NIFTY_BULLISH",
                "Nifty Bullish + HH/HL (≥+" + niftyMinChangePct + "%)", c2_nifty,
                "bullish=" + ctx.niftyBullish()
                        + " chg=" + fmt(ctx.niftyChangePct()) + "%"
                        + " need≥+" + niftyMinChangePct + "%"));
        buySteps.add(step(3, "PRICE_VS_VWAP",
                "Price > VWAP", c3_vwap,
                "price=" + r2(cur.getClose()) + " vwap=" + r2(vwap)));
        buySteps.add(step(4, "SECTOR_TOP",
                "Sector Top-2 + Aligned Bull (≥+" + sectorMinChangePct + "%)", c4_sector,
                "isTop=" + ctx.sectorIsTop()
                        + " chg=" + fmt(ctx.sectorChangePct()) + "%"
                        + " aligned=" + ctx.sectorAlignedBull()));
        buySteps.add(step(5, "ORB_BREAKOUT",
                "Price Breaks ORB High", c5_orb,
                "price=" + r2(cur.getClose()) + " orbH=" + r2(orbH)
                        + " orbRange=" + r2(orbH.subtract(orbL))));
        buySteps.add(step(6, "RETEST",
                "Retest of ORH or VWAP (±" + retestTolPct + "%)", c6_retest,
                "checked prev 4 candles for touch of orbH=" + r2(orbH)
                        + " or vwap=" + r2(vwap)));
        buySteps.add(step(7, "STRONG_BULL_CANDLE",
                "Strong Bull Candle (body≥" + pct(bodyRatioMin) + "%, wick≤" + pct(wickRatioMax) + "%)",
                c7_candle,
                "bullish=" + cur.isBullish() + " close=" + r2(cur.getClose())
                        + " open=" + r2(cur.getOpen())));
        buySteps.add(step(8, "HHHL_STRUCTURE",
                "HH/HL Structure in Last 3 × 5min Candles", c8_struct,
                "need ≥2 consecutive HH/HL pairs in last 3 candles"));
        buySteps.add(step(9, "VOLUME",
                "Volume ≥ " + volumeMultiplier + "× Average", c9_vol,
                "vol=" + cur.getVolume() + " avg=" + Math.round(avgVol)
                        + " ratio=" + String.format("%.2f", volRatio) + "×"
                        + " need≥" + volumeMultiplier + "×"));

        tracker.record(name(), symbol, "BUY", buySteps);

        log.debug("[ORB] {} BUY nifty={} vwap={} sector={} orb={} retest={} candle={} struct={} vol={}",
                symbol, c2_nifty, c3_vwap, c4_sector, c5_orb, c6_retest, c7_candle, c8_struct, c9_vol);

        if (c2_nifty && c3_vwap && c4_sector && c5_orb && c6_retest && c7_candle && c8_struct && c9_vol) {
            BigDecimal sl     = orbL.multiply(new BigDecimal("0.999"));
            BigDecimal risk   = cur.getClose().subtract(sl);
            if (risk.compareTo(BigDecimal.ZERO) <= 0) return Optional.empty();
            BigDecimal target = cur.getClose().add(risk.multiply(BigDecimal.valueOf(rr)));
            log.info("[ORB] BUY {} orbH={} entry={} sl={} target={}",
                    symbol, orbH, cur.getClose(), sl, target);
            return Optional.of(new TradeSignal(
                    TradeDirection.LONG, cur.getClose(), sl, target, 85, name()));
        }

        // ══ SELL: Compute all mirror conditions ══════════════════════════════
        boolean s2 = ctx.niftyBearish()
                && ctx.niftyChangePct() <= -niftyMinChangePct
                && hasLHLL(candles15m.subList(0, Math.min(6, candles15m.size())));
        boolean s3 = cur.getClose().compareTo(vwap) < 0;
        boolean s4 = ctx.sectorIsBottom()
                && ctx.sectorChangePct() <= -sectorMinChangePct
                && ctx.sectorAlignedBear();
        boolean s5 = cur.getClose().compareTo(orbL) < 0;
        boolean s6 = retestOccurred(
                candles5m.subList(1, Math.min(5, candles5m.size())), orbL, vwap);
        boolean s7 = isStrongBearCandle(cur);
        boolean s8 = hasLHLL(candles5m.subList(0, Math.min(4, candles5m.size())));

        // ══ SELL: Build and record validation steps ═══════════════════════════
        List<ValidationStepResult> sellSteps = new ArrayList<>();
        sellSteps.add(step(1, "TIME_WINDOW",
                "Time Window (" + entryStart + "–" + entryEnd + ")", true,
                "✓ Within window"));
        sellSteps.add(step(2, "NIFTY_BEARISH",
                "Nifty Bearish + LH/LL (≤-" + niftyMinChangePct + "%)", s2,
                "bearish=" + ctx.niftyBearish()
                        + " chg=" + fmt(ctx.niftyChangePct()) + "%"
                        + " need≤-" + niftyMinChangePct + "%"));
        sellSteps.add(step(3, "PRICE_VS_VWAP",
                "Price < VWAP", s3,
                "price=" + r2(cur.getClose()) + " vwap=" + r2(vwap)));
        sellSteps.add(step(4, "SECTOR_BOTTOM",
                "Sector Bottom-2 + Aligned Bear (≤-" + sectorMinChangePct + "%)", s4,
                "isBottom=" + ctx.sectorIsBottom()
                        + " chg=" + fmt(ctx.sectorChangePct()) + "%"
                        + " aligned=" + ctx.sectorAlignedBear()));
        sellSteps.add(step(5, "ORB_BREAKDOWN",
                "Price Breaks ORB Low", s5,
                "price=" + r2(cur.getClose()) + " orbL=" + r2(orbL)));
        sellSteps.add(step(6, "RETEST",
                "Retest of ORL or VWAP (±" + retestTolPct + "%)", s6,
                "checked prev 4 candles for touch of orbL=" + r2(orbL)
                        + " or vwap=" + r2(vwap)));
        sellSteps.add(step(7, "STRONG_BEAR_CANDLE",
                "Strong Bear Candle (body≥" + pct(bodyRatioMin) + "%, wick≤" + pct(wickRatioMax) + "%)",
                s7,
                "bearish=" + cur.isBearish() + " close=" + r2(cur.getClose())
                        + " open=" + r2(cur.getOpen())));
        sellSteps.add(step(8, "LHLL_STRUCTURE",
                "LH/LL Structure in Last 3 × 5min Candles", s8,
                "need ≥2 consecutive LH/LL pairs in last 3 candles"));
        sellSteps.add(step(9, "VOLUME",
                "Volume ≥ " + volumeMultiplier + "× Average", c9_vol,
                "vol=" + cur.getVolume() + " avg=" + Math.round(avgVol)
                        + " ratio=" + String.format("%.2f", volRatio) + "×"
                        + " need≥" + volumeMultiplier + "×"));

        tracker.record(name(), symbol, "SELL", sellSteps);

        if (s2 && s3 && s4 && s5 && s6 && s7 && s8 && c9_vol) {
            BigDecimal sl     = orbH.multiply(new BigDecimal("1.001"));
            BigDecimal risk   = sl.subtract(cur.getClose());
            if (risk.compareTo(BigDecimal.ZERO) <= 0) return Optional.empty();
            BigDecimal target = cur.getClose().subtract(risk.multiply(BigDecimal.valueOf(rr)));
            log.info("[ORB] SELL {} orbL={} entry={} sl={} target={}",
                    symbol, orbL, cur.getClose(), sl, target);
            return Optional.of(new TradeSignal(
                    TradeDirection.SHORT, cur.getClose(), sl, target, 85, name()));
        }

        return Optional.empty();
    }

    // ── Helper builders ──────────────────────────────────────────────────────

    private ValidationStepResult step(int num, String id, String label,
                                      boolean passed, String detail) {
        return new ValidationStepResult(num, id, label, passed, detail);
    }

    private BigDecimal r2(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v.setScale(2, RoundingMode.HALF_UP);
    }

    private String fmt(double v) { return String.format("%.2f", v); }

    private int pct(double ratio) { return (int) Math.round(ratio * 100); }

    // ── Condition helpers (UNCHANGED from original) ──────────────────────────

    private boolean retestOccurred(List<Candle> prev,
                                   BigDecimal level, BigDecimal vwap) {
        BigDecimal tol  = level.multiply(BigDecimal.valueOf(retestTolPct / 100.0));
        BigDecimal vtol = vwap.compareTo(BigDecimal.ZERO) > 0
                ? vwap.multiply(BigDecimal.valueOf(retestTolPct / 100.0))
                : BigDecimal.ZERO;
        for (Candle c : prev) {
            boolean atLevel = c.getLow().subtract(level).abs().compareTo(tol) <= 0
                    || c.getHigh().subtract(level).abs().compareTo(tol) <= 0;
            boolean atVwap  = vwap.compareTo(BigDecimal.ZERO) > 0
                    && (c.getLow().subtract(vwap).abs().compareTo(vtol) <= 0
                    || c.getHigh().subtract(vwap).abs().compareTo(vtol) <= 0);
            if (atLevel || atVwap) return true;
        }
        return false;
    }

    private boolean isStrongBullCandle(Candle c) {
        if (!c.isBullish()) return false;
        BigDecimal range = c.getHigh().subtract(c.getLow());
        if (range.compareTo(BigDecimal.ZERO) == 0) return false;
        double bodyR = c.getClose().subtract(c.getOpen())
                .divide(range, MathContext.DECIMAL32).doubleValue();
        double uwR   = c.getHigh().subtract(c.getClose())
                .divide(range, MathContext.DECIMAL32).doubleValue();
        return bodyR >= bodyRatioMin && uwR <= wickRatioMax;
    }

    private boolean isStrongBearCandle(Candle c) {
        if (!c.isBearish()) return false;
        BigDecimal range = c.getHigh().subtract(c.getLow());
        if (range.compareTo(BigDecimal.ZERO) == 0) return false;
        double bodyR = c.getOpen().subtract(c.getClose())
                .divide(range, MathContext.DECIMAL32).doubleValue();
        double lwR   = c.getClose().subtract(c.getLow())
                .divide(range, MathContext.DECIMAL32).doubleValue();
        return bodyR >= bodyRatioMin && lwR <= wickRatioMax;
    }

    private boolean hasHHHL(List<Candle> c) {
        if (c.size() < 3) return false;
        int count = 0;
        for (int i = 0; i < c.size() - 1; i++)
            if (c.get(i).getHigh().compareTo(c.get(i + 1).getHigh()) > 0
                    && c.get(i).getLow().compareTo(c.get(i + 1).getLow()) > 0) count++;
        return count >= 2;
    }

    private boolean hasLHLL(List<Candle> c) {
        if (c.size() < 3) return false;
        int count = 0;
        for (int i = 0; i < c.size() - 1; i++)
            if (c.get(i).getHigh().compareTo(c.get(i + 1).getHigh()) < 0
                    && c.get(i).getLow().compareTo(c.get(i + 1).getLow()) < 0) count++;
        return count >= 2;
    }

    private boolean withinTime() {
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        return !now.isBefore(LocalTime.parse(entryStart))
                && !now.isAfter(LocalTime.parse(entryEnd));
    }
}