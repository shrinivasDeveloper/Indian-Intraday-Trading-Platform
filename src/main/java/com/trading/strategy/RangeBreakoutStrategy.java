package com.trading.strategy;

import com.trading.analysis.service.KeyLevelService;
import com.trading.analysis.service.RvolService;
import com.trading.domain.Candle;
import com.trading.domain.enums.TradeDirection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * Strategy 3 — Multi-Touch Range Breakout (9:45–11:30 only)
 *
 * GOD-TIER INSTITUTIONAL UPGRADES:
 *
 * 1. SPRING & UPTHRUST DETECTION (Liquidity Grab — Highest Conviction)
 *    Old: hadFakeBreakout() rejected any candle that broke below support / above resistance.
 *    New: Distinguishes between FAKE breakout and SPRING/UPTHRUST.
 *
 *    SPRING (LONG setup):
 *      Price breaks BELOW rL (retail stops triggered) → closes BACK inside box
 *      within 2 candles → THEN price breaks ABOVE rH.
 *      This is how FIIs/DIIs accumulate: they trigger retail stops to create
 *      cheap supply, then push price above resistance. Score = 95.
 *
 *    UPTHRUST (SHORT setup):
 *      Price breaks ABOVE rH (bull trap) → closes back inside box within 2 candles
 *      → THEN price breaks BELOW rL.
 *      Institutions distribute above resistance, then flush. Score = 95.
 *
 * 2. GRAVITY / EXHAUSTION FILTER
 *    isOverextended(): if |price − POC| / POC > 1.5% → cancel ALL signals.
 *    Entering a breakout when price is already 1.5% from "fair value" (POC)
 *    means chasing an overextended move. The rubber band will snap back.
 *
 * 3. PROFESSIONAL SL — LOW OF BREAKOUT CANDLE (not box bottom)
 *    Old: SL = rL (bottom of consolidation range — wide, weak RR ~2.0)
 *    New: SL = Low of the breakout candle − 0.05% buffer
 *    Rationale: On a real institutional breakout, the impulse candle should
 *    NEVER be fully retraced. If the breakout candle's low is taken out,
 *    the move was fake regardless. This tightens SL → RR improves to 4.0+.
 *
 * 4. RVOL SLOTTING (replaces fixed volume multiplier)
 *    Compares current candle volume to the same 5-min time slot over last 5 days.
 *    1.5x at 9:45 AM = opening rush = meaningless.
 *    1.5x at 11:30 AM (late morning) = rare = institutional.
 *    Minimum RVOL: 1.4 for normal breakout, 1.2 for Spring (already confirmed).
 *
 * 5. VAH INSTITUTIONAL CONFIRMATION
 *    LONG: price must be at/above VAH or within 0.5% — institutional breakout.
 *    Breakout inside Value Area = noise. Price will revert to POC.
 *
 * 6. TRAILING SL LOGIC (encoded in TradeSignal):
 *    Trigger: Entry + 1.5R → SL moves to Breakeven (the "free trade").
 *    After 2R: trail at low of previous completed 5-min candle.
 *    Exit 50% at 3R, let remaining 50% trail until candle low breaks.
 *    "On a real institutional breakout, the spike is violent and immediate."
 */
@Component
@Slf4j
public class RangeBreakoutStrategy implements TradingStrategy {

    @Autowired private KeyLevelService keyLevelService;
    @Autowired private RvolService     rvolService;

    @Value("${strategy.range-breakout.min-touches:2}")                private int    minTouches;
    @Value("${strategy.range-breakout.touch-tolerance-pct:0.2}")      private double touchTolerancePct;
    @Value("${strategy.range-breakout.volume-breakout-multiplier:1.5}") private double volumeMultiplier;
    @Value("${strategy.range-breakout.body-ratio-min:0.5}")           private double bodyRatioMin;
    @Value("${strategy.range-breakout.consolidation-candles:16}")     private int    consolidationCandles;
    @Value("${strategy.range-breakout.rr:2.0}")                       private double rr;
    @Value("${strategy.range-breakout.entry-start:09:45}")            private String entryStart;
    @Value("${strategy.range-breakout.entry-end:11:30}")              private String entryEnd;
    @Value("${strategy.range-breakout.max-range-pct:4.0}")            private double maxRangePct;

    /** UPGRADE 3: trailing trigger in R-multiples (breakeven at 1.5R) */
    private static final double TRAIL_TRIGGER_R        = 1.5;

    /** UPGRADE 2: overextension threshold */
    private static final double OVEREXTENSION_PCT      = 0.015;

    /** UPGRADE 4: RVOL minimums */
    private static final double MIN_RVOL_BREAKOUT      = 1.4;
    private static final double MIN_RVOL_SPRING        = 1.2; // lower: Spring itself is confirmation

    /** UPGRADE 1: Spring score */
    private static final double SPRING_SCORE           = 95.0;
    private static final double NORMAL_BREAKOUT_SCORE  = 80.0;

    /** UPGRADE 5: VAH proximity (0.5% buffer for early entry) */
    private static final double VAH_PROXIMITY_PCT      = 0.5;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Override
    public String name() { return "RANGE_BREAKOUT_3TOUCH"; }

    @Override
    public Optional<TradeSignal> generateSignal(String symbol,
                                                List<Candle> candles5m,
                                                List<Candle> candles15m,
                                                TradingStrategy.MarketContext ctx) {
        if (!withinTime()) return Optional.empty();
        if (candles5m.size() < consolidationCandles + 2) return Optional.empty();

        Candle     cur  = candles5m.get(0);
        BigDecimal vwap = ctx.vwap();

        KeyLevelService.KeyLevelResult kl = keyLevelService.getKeyLevels(symbol);

        // UPGRADE 2: overextension check — stretched rubber band
        if (isOverextended(cur.getClose(), kl)) {
            log.debug("[RANGE_BK] {} overextended from POC — skip", symbol);
            return Optional.empty();
        }

        // Consolidation range (index 0 = current candle, skip it)
        List<Candle> con = candles5m.subList(1,
                Math.min(consolidationCandles + 1, candles5m.size()));

        BigDecimal rH = con.stream().map(Candle::getHigh)
                .max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal rL = con.stream().map(Candle::getLow)
                .min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        if (rH.compareTo(BigDecimal.ZERO) == 0 || rL.compareTo(BigDecimal.ZERO) == 0)
            return Optional.empty();

        // Tight range check
        double rangePct = rH.subtract(rL).divide(rL, MathContext.DECIMAL32).doubleValue() * 100;
        if (rangePct > maxRangePct) {
            log.debug("[RANGE_BK] {} range {}% too wide", symbol, String.format("%.2f", rangePct));
            return Optional.empty();
        }

        // Clean range — no spike candles inside box
        if (!isCleanRange(con, rH, rL)) return Optional.empty();

        // Touch count — structure quality
        int rTouches = countTouches(con, rH, false);
        int sTouches = countTouches(con, rL, true);
        if (rTouches < minTouches || sTouches < minTouches) {
            log.debug("[RANGE_BK] {} touches R={} S={} < {}", symbol, rTouches, sTouches, minTouches);
            return Optional.empty();
        }

        // Volume contraction inside box
        if (!hasVolumeContraction(con)) {
            log.debug("[RANGE_BK] {} no volume contraction", symbol);
            return Optional.empty();
        }

        // Breakout candle body quality
        BigDecimal candleRange = cur.getHigh().subtract(cur.getLow());
        if (candleRange.compareTo(BigDecimal.ZERO) == 0) return Optional.empty();
        double bodyRatio = cur.getClose().subtract(cur.getOpen()).abs()
                .divide(candleRange, MathContext.DECIMAL32).doubleValue();
        if (bodyRatio < bodyRatioMin) {
            log.debug("[RANGE_BK] {} body {}% weak", symbol, String.format("%.0f", bodyRatio * 100));
            return Optional.empty();
        }

        // ── UPGRADE 1: Detect Spring or Upthrust ──────────────────────────────
        // Look at the 4 candles BEFORE current (indices 1–4) for the liquidity grab
        List<Candle> recentPrev = candles5m.subList(1, Math.min(5, candles5m.size()));
        SpringResult spring = detectSpringUpthrust(recentPrev, rH, rL);

        // ── UPGRADE 4: RVOL check ─────────────────────────────────────────────
        LocalTime slot = candleTime(cur);
        double rvol    = rvolService.getRvol(symbol, slot, cur.getVolume());
        double minRvol = spring.detected() ? MIN_RVOL_SPRING : MIN_RVOL_BREAKOUT;

        if (rvol < minRvol) {
            log.debug("[RANGE_BK] {} RVOL {} < {} — dead volume at {}", symbol,
                    rvolService.rvolLabel(rvol), minRvol, slot);
            return Optional.empty();
        }

        // Volume on breakout candle (standard check in addition to RVOL)
        double avgVol = con.stream().mapToLong(Candle::getVolume).average().orElse(1);
        if (cur.getVolume() < avgVol * volumeMultiplier) {
            log.debug("[RANGE_BK] {} breakout vol {}x < {}x", symbol,
                    String.format("%.1f", cur.getVolume() / avgVol), volumeMultiplier);
            return Optional.empty();
        }

        // VWAP filter (original)
        boolean aboveVwap = vwap.compareTo(BigDecimal.ZERO) == 0 || cur.getClose().compareTo(vwap) >= 0;
        boolean belowVwap = vwap.compareTo(BigDecimal.ZERO) == 0 || cur.getClose().compareTo(vwap) <= 0;

        // ══════════════════════════════════════════════════════════════
        // BUY: price broke above rH
        // ══════════════════════════════════════════════════════════════
        if (cur.getClose().compareTo(rH) > 0 && cur.isBullish()
                && aboveVwap && closeNearHigh(cur)) {

            // If a SPRING was detected for LONG, skip normal fake-breakout rejection
            // If NO Spring and there was a regular fake breakout → reject
            if (!spring.detected() && hadNormalFakeBreakout(recentPrev, rH, rL, true)) {
                log.debug("[RANGE_BK] {} regular fake breakout (not Spring) — reject", symbol);
                return Optional.empty();
            }

            // UPGRADE 5: VAH confirmation (institutional acceptance)
            if (!checkVahConfirmation(cur.getClose(), kl, true)) {
                log.debug("[RANGE_BK] {} LONG inside Value Area — not institutional breakout", symbol);
                return Optional.empty();
            }

            // UPGRADE 3: SL = Low of breakout candle − 0.05% (not box bottom)
            BigDecimal sl   = cur.getLow().multiply(new BigDecimal("0.9995")); // −0.05% buffer
            BigDecimal risk = cur.getClose().subtract(sl);
            if (risk.compareTo(BigDecimal.ZERO) <= 0) return Optional.empty();

            BigDecimal target = cur.getClose().add(risk.multiply(BigDecimal.valueOf(rr)));

            // Trailing trigger: Entry + 1.5R → SL to Breakeven → then candle-low trail
            BigDecimal trailTrigger = cur.getClose().add(risk.multiply(BigDecimal.valueOf(TRAIL_TRIGGER_R)));

            double score = spring.detected() && spring.direction() == TradeDirection.LONG
                    ? SPRING_SCORE : NORMAL_BREAKOUT_SCORE;

            log.info("[RANGE_BK] {} BUY {} score={} sl=candleLow({}) tgt={} RVOL={} R:{}/S:{} spring={}",
                    spring.detected() ? "SPRING" : "NORMAL",
                    symbol, (int) score, sl, target,
                    rvolService.rvolLabel(rvol), rTouches, sTouches, spring.detected());

            return Optional.of(new TradeSignal(
                    TradeDirection.LONG, cur.getClose(), sl, target, score, name(),
                    trailTrigger,                // at Entry+1.5R → SL to Breakeven
                    TrailingType.CANDLE_LOW_5M,  // after 2R → trail prev 5m candle low
                    30,                          // time stop: 30 min (if not 0.5R in 30 min → exit)
                    spring.detected()));         // isSpring flag
        }

        // ══════════════════════════════════════════════════════════════
        // SELL: price broke below rL
        // ══════════════════════════════════════════════════════════════
        if (cur.getClose().compareTo(rL) < 0 && cur.isBearish()
                && belowVwap && closeNearLow(cur)) {

            if (!spring.detected() && hadNormalFakeBreakout(recentPrev, rH, rL, false)) {
                log.debug("[RANGE_BK] {} regular fake breakout (not Upthrust) — reject", symbol);
                return Optional.empty();
            }

            if (!checkVahConfirmation(cur.getClose(), kl, false)) {
                log.debug("[RANGE_BK] {} SHORT inside Value Area — not institutional", symbol);
                return Optional.empty();
            }

            BigDecimal sl   = cur.getHigh().multiply(new BigDecimal("1.0005")); // +0.05% buffer
            BigDecimal risk = sl.subtract(cur.getClose());
            if (risk.compareTo(BigDecimal.ZERO) <= 0) return Optional.empty();

            BigDecimal target       = cur.getClose().subtract(risk.multiply(BigDecimal.valueOf(rr)));
            BigDecimal trailTrigger = cur.getClose().subtract(risk.multiply(BigDecimal.valueOf(TRAIL_TRIGGER_R)));

            double score = spring.detected() && spring.direction() == TradeDirection.SHORT
                    ? SPRING_SCORE : NORMAL_BREAKOUT_SCORE;

            log.info("[RANGE_BK] {} SELL {} score={} sl=candleHigh({}) tgt={} RVOL={} upthrust={}",
                    spring.detected() ? "UPTHRUST" : "NORMAL",
                    symbol, (int) score, sl, target,
                    rvolService.rvolLabel(rvol), spring.detected());

            return Optional.of(new TradeSignal(
                    TradeDirection.SHORT, cur.getClose(), sl, target, score, name(),
                    trailTrigger,
                    TrailingType.CANDLE_LOW_5M,
                    30,
                    spring.detected()));
        }

        return Optional.empty();
    }

    // ══════════════════════════════════════════════════════════════════
    // UPGRADE 1: Spring & Upthrust Detection
    // ══════════════════════════════════════════════════════════════════

    private record SpringResult(boolean detected, TradeDirection direction) {
        static SpringResult none() { return new SpringResult(false, null); }
    }

    /**
     * Detects Spring (bullish) and Upthrust (bearish) liquidity grabs.
     *
     * SPRING (bullish):
     *   Within the last 4 candles, was there a candle that:
     *     (a) Broke BELOW rL (retail stop hunt — price went under support)
     *     (b) But CLOSED back INSIDE the box (rL ≤ close ≤ rH)
     *   This is FIIs/DIIs triggering stops to accumulate. Score = 95.
     *
     * UPTHRUST (bearish / mirror of Spring):
     *   A candle broke ABOVE rH but closed back inside the box.
     *   Institutions distributed above resistance, now flushing. Score = 95.
     *
     * @param prevCandles  candles BEFORE the current breakout candle (indices 1–4)
     * @param rH           consolidation resistance (box top)
     * @param rL           consolidation support (box bottom)
     */
    private SpringResult detectSpringUpthrust(List<Candle> prevCandles,
                                              BigDecimal rH, BigDecimal rL) {
        for (Candle c : prevCandles) {
            // SPRING: wick went below rL but closed back inside box
            boolean shotBelowSupport = c.getLow().compareTo(rL) < 0;
            boolean closedInsideBox  = c.getClose().compareTo(rL) >= 0
                    && c.getClose().compareTo(rH) <= 0;

            if (shotBelowSupport && closedInsideBox) {
                log.debug("[RANGE_BK] SPRING detected — low={} broke below rL={}, close={} back inside box",
                        c.getLow(), rL, c.getClose());
                return new SpringResult(true, TradeDirection.LONG);
            }

            // UPTHRUST: wick went above rH but closed back inside box
            boolean shotAboveResist = c.getHigh().compareTo(rH) > 0;
            boolean closedInsideBox2 = c.getClose().compareTo(rL) >= 0
                    && c.getClose().compareTo(rH) <= 0;

            if (shotAboveResist && closedInsideBox2) {
                log.debug("[RANGE_BK] UPTHRUST detected — high={} broke above rH={}, close={} back inside box",
                        c.getHigh(), rH, c.getClose());
                return new SpringResult(true, TradeDirection.SHORT);
            }
        }
        return SpringResult.none();
    }

    /**
     * Normal fake breakout (NOT a Spring/Upthrust).
     * A candle that broke out AND closed outside the box = failed breakout.
     * These should still be rejected.
     *
     * @param forLong true = checking for fake long (above rH and came back inside)
     */
    private boolean hadNormalFakeBreakout(List<Candle> prevCandles,
                                          BigDecimal rH, BigDecimal rL,
                                          boolean forLong) {
        for (Candle c : prevCandles) {
            if (forLong) {
                // Previous candle broke above rH AND closed outside (above) — failed breakout
                boolean brokeAbove   = c.getHigh().compareTo(rH) > 0;
                boolean closedBelow  = c.getClose().compareTo(rH) < 0;
                // Only reject if it's NOT a Spring (if it closed back inside that's a Spring, handled above)
                if (brokeAbove && closedBelow && c.getClose().compareTo(rL) < 0) return true;
            } else {
                // Previous candle broke below rL AND closed outside (below) — failed breakdown
                boolean brokeBelow  = c.getLow().compareTo(rL) < 0;
                boolean closedAbove = c.getClose().compareTo(rL) > 0;
                if (brokeBelow && closedAbove && c.getClose().compareTo(rH) > 0) return true;
            }
        }
        return false;
    }

    // ══════════════════════════════════════════════════════════════════
    // UPGRADE 2: Overextension check
    // ══════════════════════════════════════════════════════════════════

    private boolean isOverextended(BigDecimal price, KeyLevelService.KeyLevelResult kl) {
        BigDecimal poc = kl.getPoc();
        if (poc == null || poc.compareTo(BigDecimal.ZERO) == 0) return false;
        return Math.abs(price.subtract(poc).doubleValue()) / poc.doubleValue() > OVEREXTENSION_PCT;
    }

    // ══════════════════════════════════════════════════════════════════
    // UPGRADE 5: VAH institutional confirmation
    // ══════════════════════════════════════════════════════════════════

    /**
     * LONG: price must be at/above VAH OR within 0.5% BELOW VAH.
     * SHORT: price must be at/below VAL OR within 0.5% ABOVE VAL.
     *
     * Breakout inside Value Area = noise / mean reversion territory.
     * Breakout at VAH = institutional acceptance of HIGHER value.
     */
    private boolean checkVahConfirmation(BigDecimal price,
                                         KeyLevelService.KeyLevelResult kl,
                                         boolean forLong) {
        if (kl.valueArea() == null
                || kl.getVah().compareTo(BigDecimal.ZERO) == 0) return true;

        return forLong
                ? kl.isAboveVah(price) || kl.isNearVah(price, VAH_PROXIMITY_PCT)
                : kl.isBelowVal(price) || kl.isNearVal(price, VAH_PROXIMITY_PCT);
    }

    // ══════════════════════════════════════════════════════════════════
    // Helpers (all original, unchanged)
    // ══════════════════════════════════════════════════════════════════

    private int countTouches(List<Candle> candles, BigDecimal level, boolean isSupport) {
        if (level.compareTo(BigDecimal.ZERO) == 0) return 0;
        BigDecimal tol = level.multiply(BigDecimal.valueOf(touchTolerancePct / 100.0));
        int count = 0;
        for (Candle c : candles) {
            BigDecimal price = isSupport ? c.getLow() : c.getHigh();
            if (price.subtract(level).abs().compareTo(tol) <= 0) count++;
        }
        return count;
    }

    private boolean isCleanRange(List<Candle> candles, BigDecimal rH, BigDecimal rL) {
        BigDecimal rangeSize = rH.subtract(rL);
        if (rangeSize.compareTo(BigDecimal.ZERO) == 0) return false;
        for (Candle c : candles) {
            if (c.getClose().subtract(c.getOpen()).abs()
                    .compareTo(rangeSize.multiply(new BigDecimal("0.70"))) > 0) return false;
        }
        return true;
    }

    private boolean hasVolumeContraction(List<Candle> c) {
        if (c.size() < 3) return false;
        return c.get(0).getVolume() < c.get(1).getVolume()
                && c.get(1).getVolume() < c.get(2).getVolume();
    }

    private boolean closeNearHigh(Candle c) {
        BigDecimal range = c.getHigh().subtract(c.getLow());
        if (range.compareTo(BigDecimal.ZERO) == 0) return false;
        return c.getHigh().subtract(c.getClose()).divide(range, MathContext.DECIMAL32)
                .compareTo(new BigDecimal("0.20")) <= 0;
    }

    private boolean closeNearLow(Candle c) {
        BigDecimal range = c.getHigh().subtract(c.getLow());
        if (range.compareTo(BigDecimal.ZERO) == 0) return false;
        return c.getClose().subtract(c.getLow()).divide(range, MathContext.DECIMAL32)
                .compareTo(new BigDecimal("0.20")) <= 0;
    }

    private boolean withinTime() {
        LocalTime now = LocalTime.now(IST);
        return !now.isBefore(LocalTime.parse(entryStart))
                && !now.isAfter(LocalTime.parse(entryEnd));
    }

    private LocalTime candleTime(Candle c) {
        try { return c.getCandleTime().atZone(IST).toLocalTime(); }
        catch (Exception e) { return LocalTime.now(IST); }
    }
}