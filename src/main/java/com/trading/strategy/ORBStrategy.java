package com.trading.strategy;

import com.trading.domain.Candle;
import com.trading.domain.enums.TradeDirection;
import com.trading.validation.StrategyValidationTracker;
import com.trading.validation.ValidationStepResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Strategy 4 — ORB + VWAP + Sector + Nifty (9:30–13:00 IST only)
 *
 * Runs INDEPENDENTLY — does NOT need 7-gate scanner.
 *
 * ═══════════════════════════════════════════════════════════════════
 * FIXES APPLIED (v7.2 — builds on v7.1):
 * ═══════════════════════════════════════════════════════════════════
 *
 * FIX A (v7.2) — BUG #8: Do NOT record TIME_WINDOW failure to tracker.
 *
 *   ROOT CAUSE (confirmed from dashboard screenshot + log analysis):
 *     After 13:00 IST, StrategyEvaluatorService still calls generateSignal()
 *     for modes that include ORB_VWAP_SECTOR (e.g. DOUBLE_DISTRIBUTION).
 *     withinTime() returns false → the old code recorded a FAIL@1 TIME_WINDOW
 *     entry to StrategyValidationTracker every 5 minutes for every stock.
 *     StrategyValidationTracker.record() previously used unconditional Map.put()
 *     → each FAIL@1 overwrote the morning's meaningful evaluation (FAIL@2–9).
 *     By 6:43 PM, ALL stocks showed "FAIL@1 at 15:25" — no morning data visible.
 *
 *   FIX:
 *     Remove tracker.record() from the TIME_WINDOW early-return block entirely.
 *     TIME_WINDOW is a schedule gate, not a trading condition — it carries zero
 *     diagnostic value. The StrategyValidationTracker (also fixed in v7.2) now
 *     preserves the deepest attempt, but this defence-in-depth ensures ORB
 *     never writes shallow entries that compete with meaningful ones.
 *
 *   RESULT:
 *     The tracker now only receives ORB records when the strategy actually
 *     attempted condition evaluation (conditions 2–9). The morning's FAIL@5
 *     (Sector not aligned) survives intact until 9:15 AM next day when the
 *     tracker resets.
 *
 * ───────────────────────────────────────────────────────────────────
 * FIXES APPLIED (v7.1):
 * ───────────────────────────────────────────────────────────────────
 *
 * FIX 1 — BUG #1: orbPeriod now timestamp-filtered to today's 9:15–9:30 candles.
 *   OLD: candles5m.subList(size-orbCandles, size) = oldest 3 entries in buffer.
 *        After WarmupService pre-loads 300 historical candles, oldest 3 = YESTERDAY.
 *        orbH/orbL were computed from yesterday's afternoon data → every C5 failed.
 *   NEW: filter candles by candleTime.atZone(IST): date=today, time ∈ [09:15, 09:30).
 *        If no candles found in that window → return empty (IB not yet formed).
 *        If orbH or orbL is zero after filtering → return empty (bad data guard).
 *
 * FIX 2 — BUG #2: SL changed from orbL to current candle low.
 *   OLD: sl = orbL × 0.999  → SL is 0.5–2% below entry → always fails Gate 7.5.
 *   NEW: sl = cur.getLow() × 0.9995 → SL is 0.05–0.3% below entry (breakout candle low).
 *        For SELL: sl = cur.getHigh() × 1.0005 (mirror).
 *
 * FIX 3 — BUG #3: C8 structure check now excludes the signal candle itself.
 *   OLD: hasHHHL(candles5m.subList(0, 4)) — tautological (breakout candle always HH).
 *   NEW: hasHHHL(candles5m.subList(1, 5)) — checks 3 candles BEFORE the signal.
 *
 * FIX 4 — volumeMultiplier default changed from 1.5 to 1.2.
 *   Configurable via: strategy.orb.volume-min-multiplier (default now 1.2).
 *
 * FIX 5 — C4 sector filter: removed sectorIsTop() requirement.
 *   NEW: sectorChangePct ≥ 0.4% AND sectorAlignedBull. isTop is informational only.
 *
 * FIX 6 — retestTolPct default changed from 0.3 to 0.4.
 *   Configurable via: strategy.orb.retest-tolerance-pct (default now 0.4).
 *
 * FIX 7 — BUG #6: SELL block now uses s9 instead of c9_vol for clarity.
 *
 * FIX 8 — BUG #7: tracker injected via constructor (@RequiredArgsConstructor).
 *
 * UNCHANGED from original:
 *   C1  time window (9:30–13:00)
 *   C2  Nifty bullish + HH/HL in 15m
 *   C3  price > VWAP
 *   C6  retest ± retestTolPct
 *   C7  strong bull candle (body ≥ 55%, wick ≤ 35%)
 *   All SELL mirror conditions follow same fixes.
 *   All tracker.record() calls preserved for conditions 2–9.
 * ═══════════════════════════════════════════════════════════════════
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ORBStrategy implements TradingStrategy {

    private final StrategyValidationTracker tracker;

    @Value("${strategy.orb.nifty-min-change-pct:0.3}")
    private double niftyMinChangePct;

    @Value("${strategy.orb.sector-min-change-pct:0.4}")
    private double sectorMinChangePct;

    @Value("${strategy.orb.volume-min-multiplier:1.2}")
    private double volumeMultiplier;

    @Value("${strategy.orb.retest-tolerance-pct:0.4}")
    private double retestTolPct;

    @Value("${strategy.orb.body-ratio-min:0.55}")
    private double bodyRatioMin;

    @Value("${strategy.orb.wick-ratio-max:0.35}")
    private double wickRatioMax;

    @Value("${strategy.orb.rr:2.5}")
    private double rr;

    @Value("${strategy.orb.entry-start:09:30}")
    private String entryStart;

    @Value("${strategy.orb.entry-end:13:00}")
    private String entryEnd;

    @Value("${strategy.orb.orb-candles:3}")
    private int orbCandles;

    // ORB observation window: 9:15–9:30 IST (fixed constants, not configurable)
    private static final ZoneId    IST       = ZoneId.of("Asia/Kolkata");
    private static final LocalTime ORB_START = LocalTime.of(9, 15);
    private static final LocalTime ORB_END   = LocalTime.of(9, 30);   // exclusive

    @Override
    public String name() { return "ORB_VWAP_SECTOR"; }

    @Override
    public Optional<TradeSignal> generateSignal(String symbol,
                                                List<Candle> candles5m,
                                                List<Candle> candles15m,
                                                TradingStrategy.MarketContext ctx) {

        // ── CONDITION 1: Time filter ─────────────────────────────────────────
        //
        // FIX A (v7.2): Do NOT record to tracker when outside time window.
        //
        // OLD code wrote FAIL@1 to StrategyValidationTracker here, which
        // overwrote meaningful morning evaluations (FAIL@2–9) every 5 minutes
        // after 13:00. By end-of-day ALL stocks showed FAIL@1 at 15:25 PM.
        //
        // NEW: simply return empty. TIME_WINDOW is a schedule gate, not a
        // trading condition. The tracker should only contain records where the
        // strategy actually attempted to evaluate conditions 2–9.
        if (!withinTime()) {
            return Optional.empty();   // ← no tracker.record() here (FIX A)
        }

        // Data sufficiency — cannot evaluate without enough candles
        if (candles5m.size() < 6)  return Optional.empty();
        if (candles15m.size() < 6) return Optional.empty();

        Candle     cur  = candles5m.get(0);
        BigDecimal vwap = ctx.vwap();
        if (vwap == null || vwap.compareTo(BigDecimal.ZERO) == 0) return Optional.empty();

        // ── FIX 1: Opening Range — timestamp-filtered to today's 9:15–9:30 ──
        //
        // candles5m is newest-first. After WarmupService pre-loads 300 historical
        // candles, the tail of the list contains YESTERDAY's data. Using subList
        // from the end would give wrong orbH/orbL (yesterday's afternoon prices).
        //
        // Instead: filter by (date=today AND time ∈ [09:15, 09:30)).
        // exclusive end: a candle whose period starts at exactly 09:30 is NOT in
        // the ORB window — it is the first SIGNAL candle.
        LocalDate today = LocalDate.now(IST);
        List<Candle> orbPeriod = candles5m.stream()
                .filter(c -> {
                    if (c.getCandleTime() == null) return false;
                    var zdt = c.getCandleTime().atZone(IST);
                    LocalDate cd = zdt.toLocalDate();
                    LocalTime ct = zdt.toLocalTime();
                    return cd.equals(today)
                            && !ct.isBefore(ORB_START)
                            && ct.isBefore(ORB_END);
                })
                .collect(Collectors.toList());

        if (orbPeriod.isEmpty()) {
            log.debug("[ORB] {} no today 9:15–9:30 candles in buffer — skipping", symbol);
            return Optional.empty();
        }

        BigDecimal orbH = orbPeriod.stream()
                .map(Candle::getHigh).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal orbL = orbPeriod.stream()
                .map(Candle::getLow).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);

        if (orbH.compareTo(BigDecimal.ZERO) == 0 || orbL.compareTo(BigDecimal.ZERO) == 0) {
            log.debug("[ORB] {} orbH or orbL is zero — bad candle data", symbol);
            return Optional.empty();
        }

        // Average volume of last 20 candles (excluding current)
        double avgVol  = candles5m.subList(1, Math.min(21, candles5m.size()))
                .stream().mapToLong(Candle::getVolume).average().orElse(1);
        double volRatio = avgVol > 0 ? (double) cur.getVolume() / avgVol : 0;

        // ══ BUY: Compute all conditions ══════════════════════════════════════

        boolean c2_nifty = ctx.niftyBullish()
                && ctx.niftyChangePct() >= niftyMinChangePct
                && hasHHHL(candles15m.subList(0, Math.min(6, candles15m.size())));

        boolean c3_vwap  = cur.getClose().compareTo(vwap) > 0;

        // FIX 5: removed sectorIsTop() — now only requires changePct ≥ sectorMinChangePct
        //        AND sectorAlignedBull. isTop is logged but not required.
        boolean c4_sector = ctx.sectorChangePct() >= sectorMinChangePct
                && ctx.sectorAlignedBull();

        boolean c5_orb   = cur.getClose().compareTo(orbH) > 0;

        boolean c6_retest = retestOccurred(
                candles5m.subList(1, Math.min(5, candles5m.size())), orbH, vwap);

        boolean c7_candle = isStrongBullCandle(cur);

        // FIX 3: subList starts at index 1 (skip signal candle) to avoid tautology.
        // Index 0 is the breakout candle — its high is always > prev highs when C5 passed.
        // Checking candles[1..4] genuinely tests momentum leading into the breakout.
        boolean c8_struct = hasHHHL(candles5m.subList(1, Math.min(5, candles5m.size())));

        // FIX 4: volumeMultiplier default is now 1.2 (was 1.5)
        boolean c9_vol   = cur.getVolume() >= avgVol * volumeMultiplier;

        // ══ BUY: Build and record validation steps ════════════════════════════
        //
        // tracker.record() IS called here — these are meaningful evaluations.
        // The strategy passed the time window check and is actively evaluating.
        List<ValidationStepResult> buySteps = new ArrayList<>();
        buySteps.add(step(1, "TIME_WINDOW",
                "Time Window (" + entryStart + "–" + entryEnd + ")", true,
                "✓ Within window at " + LocalTime.now(IST)));
        buySteps.add(step(2, "NIFTY_BULLISH",
                "Nifty Bullish + HH/HL (≥+" + niftyMinChangePct + "%)", c2_nifty,
                "bullish=" + ctx.niftyBullish()
                        + " chg=" + fmt(ctx.niftyChangePct()) + "%"
                        + " need≥+" + niftyMinChangePct + "%"));
        buySteps.add(step(3, "PRICE_VS_VWAP",
                "Price > VWAP", c3_vwap,
                "price=" + r2(cur.getClose()) + " vwap=" + r2(vwap)));
        buySteps.add(step(4, "SECTOR_ALIGNED",
                "Sector Aligned Bull + change≥+" + sectorMinChangePct + "%", c4_sector,
                "isTop=" + ctx.sectorIsTop()        // informational only — not required
                        + " chg=" + fmt(ctx.sectorChangePct()) + "%"
                        + " need≥+" + sectorMinChangePct + "%"
                        + " aligned=" + ctx.sectorAlignedBull()));
        buySteps.add(step(5, "ORB_BREAKOUT",
                "Price Breaks ORB High", c5_orb,
                "price=" + r2(cur.getClose()) + " orbH=" + r2(orbH)
                        + " orbRange=" + r2(orbH.subtract(orbL))
                        + " orbCandles=" + orbPeriod.size()));
        buySteps.add(step(6, "RETEST",
                "Retest of ORH or VWAP (±" + retestTolPct + "%)", c6_retest,
                "checked prev 4 candles for touch of orbH=" + r2(orbH)
                        + " or vwap=" + r2(vwap)));
        buySteps.add(step(7, "STRONG_BULL_CANDLE",
                "Strong Bull Candle (body≥" + pct(bodyRatioMin) + "%, wick≤" + pct(wickRatioMax) + "%)",
                c7_candle,
                "bullish=" + cur.isBullish()
                        + " close=" + r2(cur.getClose())
                        + " open=" + r2(cur.getOpen())));
        buySteps.add(step(8, "HHHL_STRUCTURE",
                "HH/HL in 3 Candles Before Breakout", c8_struct,
                "need ≥2 consecutive HH/HL pairs in candles[1..4] (excl. signal candle)"));
        buySteps.add(step(9, "VOLUME",
                "Volume ≥ " + volumeMultiplier + "× Average", c9_vol,
                "vol=" + cur.getVolume() + " avg=" + Math.round(avgVol)
                        + " ratio=" + String.format("%.2f", volRatio) + "×"
                        + " need≥" + volumeMultiplier + "×"));

        tracker.record(name(), symbol, "BUY", buySteps);

        log.debug("[ORB] {} BUY orbH={} orbL={} orbCandles={} | "
                        + "nifty={} vwap={} sector={} orb={} retest={} candle={} struct={} vol={}",
                symbol, r2(orbH), r2(orbL), orbPeriod.size(),
                c2_nifty, c3_vwap, c4_sector, c5_orb, c6_retest, c7_candle, c8_struct, c9_vol);

        if (c2_nifty && c3_vwap && c4_sector && c5_orb && c6_retest && c7_candle && c8_struct && c9_vol) {

            // FIX 2: SL = current candle low × 0.9995 (not orbL × 0.999).
            // orbL-based SL was 0.5–2% from entry → RR ≈ 1.6 → Gate 7.5 rejection.
            // Breakout-candle-low SL is 0.05–0.3% from entry → RR comfortably ≥ 2.5.
            BigDecimal sl   = cur.getLow().multiply(new BigDecimal("0.9995"));
            BigDecimal risk = cur.getClose().subtract(sl);
            if (risk.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("[ORB] BUY {} zero/negative risk (close={} sl={}) — skipping",
                        symbol, cur.getClose(), sl);
                return Optional.empty();
            }
            BigDecimal target = cur.getClose().add(risk.multiply(BigDecimal.valueOf(rr)));

            log.info("[ORB] BUY SIGNAL {} | orbH={} orbL={} | entry={} sl={} target={} "
                            + "risk={}pts RR={}× | sector={} chg={}%",
                    symbol, r2(orbH), r2(orbL),
                    r2(cur.getClose()), r2(sl), r2(target),
                    r2(risk), rr,
                    ctx.sectorName(), fmt(ctx.sectorChangePct()));

            return Optional.of(new TradeSignal(
                    TradeDirection.LONG, cur.getClose(), sl, target, 85, name()));
        }

        // ══ SELL: Compute all mirror conditions ═══════════════════════════════

        boolean s2 = ctx.niftyBearish()
                && ctx.niftyChangePct() <= -niftyMinChangePct
                && hasLHLL(candles15m.subList(0, Math.min(6, candles15m.size())));

        boolean s3 = cur.getClose().compareTo(vwap) < 0;

        // FIX 5 mirror: removed sectorIsBottom() requirement
        boolean s4 = ctx.sectorChangePct() <= -sectorMinChangePct
                && ctx.sectorAlignedBear();

        boolean s5 = cur.getClose().compareTo(orbL) < 0;

        boolean s6 = retestOccurred(
                candles5m.subList(1, Math.min(5, candles5m.size())), orbL, vwap);

        boolean s7 = isStrongBearCandle(cur);

        // FIX 3 mirror: skip signal candle
        boolean s8 = hasLHLL(candles5m.subList(1, Math.min(5, candles5m.size())));

        // FIX 7: renamed from c9_vol → s9 on SELL side for clarity
        boolean s9 = cur.getVolume() >= avgVol * volumeMultiplier;

        // ══ SELL: Build and record validation steps ════════════════════════════
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
        sellSteps.add(step(4, "SECTOR_ALIGNED",
                "Sector Aligned Bear + change≤-" + sectorMinChangePct + "%", s4,
                "isBottom=" + ctx.sectorIsBottom()     // informational only
                        + " chg=" + fmt(ctx.sectorChangePct()) + "%"
                        + " need≤-" + sectorMinChangePct + "%"
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
                "bearish=" + cur.isBearish()
                        + " close=" + r2(cur.getClose())
                        + " open=" + r2(cur.getOpen())));
        sellSteps.add(step(8, "LHLL_STRUCTURE",
                "LH/LL in 3 Candles Before Breakdown", s8,
                "need ≥2 consecutive LH/LL pairs in candles[1..4] (excl. signal candle)"));
        sellSteps.add(step(9, "VOLUME",
                "Volume ≥ " + volumeMultiplier + "× Average", s9,
                "vol=" + cur.getVolume() + " avg=" + Math.round(avgVol)
                        + " ratio=" + String.format("%.2f", volRatio) + "×"
                        + " need≥" + volumeMultiplier + "×"));

        tracker.record(name(), symbol, "SELL", sellSteps);

        if (s2 && s3 && s4 && s5 && s6 && s7 && s8 && s9) {

            // FIX 2 mirror: SL = current candle high × 1.0005
            BigDecimal sl   = cur.getHigh().multiply(new BigDecimal("1.0005"));
            BigDecimal risk = sl.subtract(cur.getClose());
            if (risk.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("[ORB] SELL {} zero/negative risk (close={} sl={}) — skipping",
                        symbol, cur.getClose(), sl);
                return Optional.empty();
            }
            BigDecimal target = cur.getClose().subtract(risk.multiply(BigDecimal.valueOf(rr)));

            log.info("[ORB] SELL SIGNAL {} | orbH={} orbL={} | entry={} sl={} target={} "
                            + "risk={}pts RR={}× | sector={} chg={}%",
                    symbol, r2(orbH), r2(orbL),
                    r2(cur.getClose()), r2(sl), r2(target),
                    r2(risk), rr,
                    ctx.sectorName(), fmt(ctx.sectorChangePct()));

            return Optional.of(new TradeSignal(
                    TradeDirection.SHORT, cur.getClose(), sl, target, 85, name()));
        }

        return Optional.empty();
    }

    // ── Private: ValidationStepResult builder ────────────────────────────────

    private ValidationStepResult step(int num, String id, String label,
                                      boolean passed, String detail) {
        return new ValidationStepResult(num, id, label, passed, detail);
    }

    // ── Private: formatting helpers ──────────────────────────────────────────

    private BigDecimal r2(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v.setScale(2, RoundingMode.HALF_UP);
    }

    private String fmt(double v)    { return String.format("%.2f", v); }
    private int    pct(double ratio){ return (int) Math.round(ratio * 100); }

    // ── Private: condition helpers ─────────────────────────────────────────────

    /**
     * Retest check: did price touch orbLevel or VWAP (±retestTolPct%) in prev candles?
     * Scans LOW and HIGH of each candle against the level and VWAP within tolerance.
     */
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
                    ||  c.getHigh().subtract(vwap).abs().compareTo(vtol) <= 0);
            if (atLevel || atVwap) return true;
        }
        return false;
    }

    /**
     * Strong bull candle: must be bullish, body ≥ bodyRatioMin, upper wick ≤ wickRatioMax.
     */
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

    /**
     * Strong bear candle: must be bearish, body ≥ bodyRatioMin, lower wick ≤ wickRatioMax.
     */
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

    /**
     * HH/HL structure: list is newest-first (c.get(0) = most recent).
     * Returns true if ≥ 2 consecutive pairs satisfy newer.high > older.high
     * AND newer.low > older.low.
     * Requires ≥ 3 candles.
     */
    private boolean hasHHHL(List<Candle> c) {
        if (c.size() < 3) return false;
        int count = 0;
        for (int i = 0; i < c.size() - 1; i++)
            if (c.get(i).getHigh().compareTo(c.get(i + 1).getHigh()) > 0
                    && c.get(i).getLow().compareTo(c.get(i + 1).getLow()) > 0) count++;
        return count >= 2;
    }

    /**
     * LH/LL structure: list is newest-first.
     * Returns true if ≥ 2 consecutive pairs satisfy newer.high < older.high
     * AND newer.low < older.low.
     * Requires ≥ 3 candles.
     */
    private boolean hasLHLL(List<Candle> c) {
        if (c.size() < 3) return false;
        int count = 0;
        for (int i = 0; i < c.size() - 1; i++)
            if (c.get(i).getHigh().compareTo(c.get(i + 1).getHigh()) < 0
                    && c.get(i).getLow().compareTo(c.get(i + 1).getLow()) < 0) count++;
        return count >= 2;
    }

    /**
     * Time window check: returns true when current IST time is within the
     * configured entry window [entryStart, entryEnd].
     */
    private boolean withinTime() {
        LocalTime now = LocalTime.now(IST);
        return !now.isBefore(LocalTime.parse(entryStart))
                && !now.isAfter(LocalTime.parse(entryEnd));
    }
}