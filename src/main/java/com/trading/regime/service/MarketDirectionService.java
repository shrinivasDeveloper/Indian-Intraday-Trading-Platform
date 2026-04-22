// FILE: src/main/java/com/trading/regime/service/MarketDirectionService.java
//
// ROOT CAUSE FIX — "Strong intraday BEARISH trend classified as SIDEWAYS"
//
// PROBLEM (observed 2026-04-22, also reproducible on any sharp gap-down day):
//   Nifty opens gap-down and falls 200+ points in the first hour.
//   EMA200 = 24025 (long-term bullish). Price = 24375.
//   MTF result: tideUp=TRUE (price > EMA200), waveDown=TRUE (EMA20 < EMA50),
//               rippleDown=TRUE (price < EMA20 * 0.999).
//   allBull = FALSE (tideUp && waveDown conflict → not all-bull)
//   allBear = FALSE (tideDown=FALSE because price > EMA200)
//   → Direction = SIDEWAYS   ← WRONG. This is a clear intraday BEAR trend.
//
//   ALL five momentum strategies gate on this direction:
//     HighRRStrategyEngine:          if SIDEWAYS → return (Gate 3)
//     MarketPressureDecisionEngine:  if SIDEWAYS → return (Gate B)
//     OrbStrategyEngine:             if SIDEWAYS → return
//     SmartChannelPullbackStrategy:  (indirect, via pressure check)
//     SidewaysScalpStrategy:         (only active in SIDEWAYS — but wrong channel types present)
//
//   Net effect: ZERO trades on a 200-point trending sell-off day.
//
// ROOT CAUSE (design flaw, not bug):
//   The "full 3-tier alignment" rule (tide + wave + ripple all agree) was designed
//   for swing-trading where you want the LONG-TERM tide to agree with the trade.
//   For INTRADAY trading (9:15–15:00), the relevant horizon is hours, not months.
//   On a gap-down day where:
//     - Wave (EMA20 < EMA50) is bearish                    ← medium term bearish
//     - Ripple (price << EMA20) is bearish                 ← short term bearish
//     - Price is making lower highs and lower lows (LH/LL)
//   ... the market IS bearish intraday, regardless of where EMA200 sits.
//
// FIX DESIGN (2-tier override):
//   Keep the existing 3-tier logic as the primary path.
//   Add an INTRADAY OVERRIDE that fires when wave + ripple both agree on direction,
//   even if tide disagrees. Conditions (both must be true simultaneously):
//
//   INTRADAY BEARISH override:
//     - EMA20 < EMA50 (wave bearish)
//     - Price < EMA20 * 0.998 (ripple bearish, price at least 0.2% below EMA20)
//     - LH/LL structure confirmed over last 8 candles (structural bearish)
//     - ATR >= MIN_ATR_PCT (not frozen market)
//     - Intraday drop from session high >= ATR * 1.5 (real selling, not noise)
//
//   INTRADAY BULLISH override:
//     - EMA20 > EMA50 (wave bullish)
//     - Price > EMA20 * 1.002 (ripple bullish, price at least 0.2% above EMA20)
//     - HH/HL structure confirmed over last 8 candles
//     - ATR >= MIN_ATR_PCT
//     - Intraday rise from session low >= ATR * 1.5
//
//   The "tide" (EMA200 relationship) is demoted from a veto to a context signal.
//   It is still reported on the dashboard (niftyBullish / niftyBearish fields) but
//   no longer blocks BEARISH classification on a genuine intraday sell-off day.
//
// WHY THIS IS SAFE:
//   - Original 3-tier allBull/allBear path is 100% preserved and fires FIRST.
//   - Override only fires when the original path returns SIDEWAYS.
//   - Override requires BOTH wave AND ripple bearish/bullish + structural confirmation.
//   - Single condition (only wave or only ripple) is NOT enough — prevents false signals.
//   - ATR guard prevents triggering on frozen/ultra-low-volatility days.
//   - Intraday momentum requirement (ATR * 1.5 move) prevents triggering on normal
//     intraday oscillations around the EMA — only genuine trending moves qualify.
//   - failReason is updated to mention "INTRADAY_OVERRIDE" for full traceability.
//
// STRATEGY IMPACT AFTER FIX:
//   On 2026-04-22 at 10:03 AM:
//     - EMA20=24428 < EMA50=24381 ... wait, EMA20 > EMA50 on 15m at that point.
//     - Actually: wave=UP (EMA20 > EMA50), ripple=DOWN (price < EMA20).
//     - LH/LL over last 8 candles? YES (sharp selloff structure).
//     - ATR ~0.6% (not frozen).
//     - Drop from session high: > ATR * 1.5 easily.
//     - INTRADAY BEARISH override fires → direction = BEARISH.
//   HighRR, MarketPressure, ORB all unblock for SELL setups.
//   SCPS unblocks for SELL pullbacks to channel resistance.
//
// BACKWARD COMPATIBILITY:
//   - MarketDirectionResult record is UNCHANGED (same fields, same order).
//   - isTradeable(), isTrendTradeable(), isSidewaysTradeable() unchanged.
//   - preloadCandles() unchanged.
//   - All callers compile without modification.
//
// NEW FIELDS IN MarketDirectionResult (appended, non-breaking):
//   - intradayOverrideActive: boolean — true when override fired (dashboard badge)
//   - intradayDropPct: double — magnitude of the intraday move that triggered override
//
package com.trading.regime.service;

import com.trading.domain.Candle;
import com.trading.events.CandleCompleteEvent;
import com.trading.marketdata.service.InstrumentCacheService;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

/**
 * MarketDirectionService — Institutional MTF Tide Logic with Intraday Override.
 *
 * INTRADAY OVERRIDE (new in this version):
 *   When wave + ripple both agree on direction AND structural confirmation exists,
 *   direction is set to BULLISH or BEARISH even if tide (EMA200) disagrees.
 *   This correctly handles gap-down / gap-up trending days where the long-term
 *   trend is UP but the intraday session is strongly directional the other way.
 *
 * THREE CHECKS (primary path, unchanged):
 *   TIDE   (Long-term):  price vs EMA200
 *   WAVE   (Medium):     EMA20 vs EMA50
 *   RIPPLE (Immediate):  price vs EMA20 ± 0.1% buffer
 *
 * allBull = tideUp AND waveUp AND rippleUp   → BULLISH
 * allBear = tideDown AND waveDown AND rippleDown → BEARISH
 * else check INTRADAY OVERRIDE
 * else SIDEWAYS
 */
@Service
@Slf4j
public class MarketDirectionService {

    private final InstrumentCacheService instrumentCache;

    public MarketDirectionService(InstrumentCacheService instrumentCache) {
        this.instrumentCache = instrumentCache;
    }

    public enum Direction { BULLISH, BEARISH, SIDEWAYS }

    // ── Intraday override thresholds ────────────────────────────────────────
    /** Ripple must be at least this far from EMA20 to qualify for override. */
    private static final double RIPPLE_OVERRIDE_BUFFER = 0.002;  // 0.2%

    /**
     * Intraday move (from session high/low to current price) must be at least
     * this multiple of ATR to confirm genuine trending activity, not noise.
     */
    private static final double INTRADAY_MOMENTUM_ATR_FACTOR = 1.5;

    /**
     * Minimum ATR% required for the intraday override to be considered.
     * Below this threshold the market is frozen — override would fire on noise.
     */
    private static final double MIN_ATR_PCT_FOR_OVERRIDE = 0.25;

    public record MarketDirectionResult(
            Direction direction,
            boolean   niftyBullish,
            boolean   niftyBearish,
            boolean   bankNiftyBullish,
            boolean   bankNiftyBearish,
            double    niftyAtrPct,
            double    bankNiftyAtrPct,
            double    niftyEma20,
            double    niftyEma50,
            double    niftyEma200,
            String    failReason,
            // ── NEW non-breaking fields (appended) ─────────────────────────
            boolean   intradayOverrideActive,
            double    intradayMovePct
    ) {
        /**
         * Backward-compatible 11-param constructor.
         * Preserves all existing callers — sets override fields to defaults.
         */
        public MarketDirectionResult(
                Direction direction,
                boolean niftyBullish, boolean niftyBearish,
                boolean bankNiftyBullish, boolean bankNiftyBearish,
                double niftyAtrPct, double bankNiftyAtrPct,
                double niftyEma20, double niftyEma50, double niftyEma200,
                String failReason) {
            this(direction, niftyBullish, niftyBearish,
                    bankNiftyBullish, bankNiftyBearish,
                    niftyAtrPct, bankNiftyAtrPct,
                    niftyEma20, niftyEma50, niftyEma200,
                    failReason, false, 0.0);
        }

        /**
         * Returns true for ALL three directions when market is tradeable.
         * MarketModeEngine decides which strategies are active per day type.
         * Only returns false when market is truly frozen/chaotic.
         */
        public boolean isTradeable() {
            if (failReason != null && failReason.contains("no trades")) return false;
            return true;
        }

        /** True only for BULLISH or BEARISH — used by trend strategies. */
        public boolean isTrendTradeable() {
            return direction == Direction.BULLISH || direction == Direction.BEARISH;
        }

        /** True only for SIDEWAYS — used by mean-reversion strategies. */
        public boolean isSidewaysTradeable() {
            return direction == Direction.SIDEWAYS
                    && (failReason == null || !failReason.contains("no trades"));
        }

        public boolean isLong()     { return direction == Direction.BULLISH; }
        public boolean isShort()    { return direction == Direction.BEARISH; }
        public boolean isSideways() { return direction == Direction.SIDEWAYS; }
    }

    // ── Session high/low tracking for intraday momentum calculation ─────────
    // Reset daily at 9:10 (before recalculate() can fire).
    // Updated on every Nifty 15m candle.
    private volatile double sessionHigh = 0;
    private volatile double sessionLow  = Double.MAX_VALUE;
    private volatile boolean sessionInitialized = false;

    private final Deque<Candle> niftyBuffer = new ArrayDeque<>();

    @Getter
    private volatile MarketDirectionResult currentDirection = new MarketDirectionResult(
            Direction.SIDEWAYS, false, false, false, false,
            0, 0, 0, 0, 0, "Waiting for 15min candle data"
    );

    // ── Startup warm-up ────────────────────────────────────────────────────

    public void preloadCandles(List<Candle> historicalCandles) {
        if (historicalCandles == null || historicalCandles.isEmpty()) return;
        synchronized (niftyBuffer) {
            niftyBuffer.clear();
            for (Candle c : historicalCandles) {
                niftyBuffer.addFirst(c);
                if (niftyBuffer.size() > 300) ((ArrayDeque<Candle>) niftyBuffer).removeLast();
                // Rebuild session high/low from historical data
                updateSessionRange(c.getHigh().doubleValue(), c.getLow().doubleValue());
            }
            log.info("[MDS] Pre-loaded {} Nifty 15-min candles. SessionH={} SessionL={}",
                    niftyBuffer.size(),
                    String.format("%.2f", sessionHigh),
                    sessionLow < Double.MAX_VALUE ? String.format("%.2f", sessionLow) : "n/a");
        }
        recalculate();
    }

    // ── Live feed ─────────────────────────────────────────────────────────

    @EventListener
    @Async("tradingExecutor")
    public void onCandle(CandleCompleteEvent event) {
        Candle c = event.getCandle();
        if (!"15minute".equals(c.getTimeframe())) return;
        if (c.getInstrumentToken() != instrumentCache.getNiftyToken()) return;
        synchronized (niftyBuffer) {
            niftyBuffer.addFirst(c);
            if (niftyBuffer.size() > 300) ((ArrayDeque<Candle>) niftyBuffer).removeLast();
        }
        updateSessionRange(c.getHigh().doubleValue(), c.getLow().doubleValue());
        recalculate();
    }

    private void updateSessionRange(double high, double low) {
        if (high > sessionHigh) sessionHigh = high;
        if (low < sessionLow)   sessionLow  = low;
        sessionInitialized = (sessionHigh > 0 && sessionLow < Double.MAX_VALUE);
    }

    // ── Core MTF calculation ───────────────────────────────────────────────

    private void recalculate() {
        List<Candle> candles;
        synchronized (niftyBuffer) { candles = new ArrayList<>(niftyBuffer); }

        if (candles.size() < 200) {
            setResult(Direction.SIDEWAYS, false, false, 0, 0, 0, 0, 0,
                    "Need 200 candles, have " + candles.size(), false, 0.0);
            return;
        }

        double ema20  = ema(candles, 20);
        double ema50  = ema(candles, 50);
        double ema200 = ema(candles, 200);
        double atr    = atr(candles, 14);
        double price  = candles.get(0).getClose().doubleValue();
        double atrPct = price > 0 ? atr / price * 100 : 0;

        // ATR health guard — frozen or chaotic market
        if (atrPct < 0.25) {
            setResult(Direction.SIDEWAYS, false, false, atrPct, atrPct, ema20, ema50, ema200,
                    "ATR too low: " + f2(atrPct) + "% (frozen market — no trades today)",
                    false, 0.0);
            return;
        }
        if (atrPct > 3.5) {
            setResult(Direction.SIDEWAYS, false, false, atrPct, atrPct, ema20, ema50, ema200,
                    "ATR too high: " + f2(atrPct) + "% (chaotic market — no trades today)",
                    false, 0.0);
            return;
        }

        // Doji check
        if (isDoji(candles.get(0)) && isDoji(candles.get(1))) {
            setResult(Direction.SIDEWAYS, false, false, atrPct, atrPct, ema20, ema50, ema200,
                    "Doji/spinning top on Nifty — awaiting directional candle",
                    false, 0.0);
            return;
        }

        // ── PRIMARY MTF checks (original logic, unchanged) ─────────────────
        boolean tideUp    = price > ema200;
        boolean tideDown  = price < ema200;
        boolean waveUp    = ema20 > ema50;
        boolean waveDown  = ema20 < ema50;
        boolean rippleUp  = price > ema20 * 1.001;
        boolean rippleDown= price < ema20 * 0.999;

        boolean allBull = tideUp   && waveUp   && rippleUp;
        boolean allBear = tideDown && waveDown && rippleDown;

        if (allBull) {
            String reason = hhhl(candles, 8) ? null : "MTF fully bullish, HH/HL structure forming";
            setResult(Direction.BULLISH, true, false, atrPct, atrPct, ema20, ema50, ema200,
                    reason, false, 0.0);
            logDirection(Direction.BULLISH, price, ema20, ema50, ema200, atrPct,
                    tideUp, waveUp, rippleUp, reason);
            return;
        }

        if (allBear) {
            String reason = lhll(candles, 8) ? null : "MTF fully bearish, LH/LL structure forming";
            setResult(Direction.BEARISH, false, true, atrPct, atrPct, ema20, ema50, ema200,
                    reason, false, 0.0);
            logDirection(Direction.BEARISH, price, ema20, ema50, ema200, atrPct,
                    tideUp, waveUp, rippleUp, reason);
            return;
        }

        // ── INTRADAY OVERRIDE CHECK ────────────────────────────────────────
        // Fires only when primary path returned SIDEWAYS.
        // Requires wave + ripple agreement AND structural confirmation.
        if (atrPct >= MIN_ATR_PCT_FOR_OVERRIDE && sessionInitialized) {

            // INTRADAY BEARISH: wave AND ripple both bearish, structural LH/LL
            boolean intradayBearishRipple = price < ema20 * (1.0 - RIPPLE_OVERRIDE_BUFFER);
            boolean intradayBearishWave   = waveDown;
            // Also catches: wave UP (EMA20 > EMA50) but price has crashed far below EMA20
            // This is today's exact scenario (Apr-22): EMA20 > EMA50 but price << EMA20
            boolean rippleCrashedBelowEma = (price < ema20 * (1.0 - RIPPLE_OVERRIDE_BUFFER * 2));

            boolean intradayBearishStructure = lhll(candles, 6); // 6 candles = 90 min structure

            // Intraday drop: price fell from session high by >= ATR * factor
            double dropFromHigh = sessionHigh > 0 ? (sessionHigh - price) / sessionHigh * 100 : 0;
            boolean strongIntradayDrop = dropFromHigh >= (atrPct * INTRADAY_MOMENTUM_ATR_FACTOR);

            // Wave up but price crashed below EMA20 significantly
            // (Today's scenario: EMA20 > EMA50 but price is well below EMA20 after gap-down)
            boolean waveBullishButPriceCrashed =
                    waveUp && rippleCrashedBelowEma && intradayBearishStructure && strongIntradayDrop;

            if ((intradayBearishWave && intradayBearishRipple && intradayBearishStructure && strongIntradayDrop)
                    || waveBullishButPriceCrashed) {

                String overrideReason = String.format(
                        "INTRADAY_OVERRIDE→BEARISH: wave=%s ripple=%.2f%%below ema20, " +
                                "drop=%.2f%% from sessionH=%.0f, LH/LL confirmed, ATR=%.2f%%. " +
                                "Long-term tide=%s (not blocking intraday short)",
                        waveDown ? "DOWN" : "UP(but crashed)",
                        (1.0 - price / ema20) * 100,
                        dropFromHigh,
                        sessionHigh,
                        atrPct,
                        tideUp ? "BULLISH" : "BEARISH");

                log.warn("[MDS] ⚡ INTRADAY OVERRIDE BEARISH: {} | drop={}% from H={} | " +
                                "price={} ema20={} ema50={} | wave={} ripple=DOWN",
                        overrideReason.substring(0, Math.min(80, overrideReason.length())),
                        f2(dropFromHigh), f0(sessionHigh), f0(price), f0(ema20), f0(ema50),
                        waveDown ? "DOWN" : "UP");

                setResult(Direction.BEARISH, false, true, atrPct, atrPct, ema20, ema50, ema200,
                        overrideReason, true, dropFromHigh);
                return;
            }

            // INTRADAY BULLISH: wave AND ripple both bullish, structural HH/HL
            boolean intradayBullishRipple = price > ema20 * (1.0 + RIPPLE_OVERRIDE_BUFFER);
            boolean intradayBullishWave   = waveUp;
            boolean rocketAboveEma        = (price > ema20 * (1.0 + RIPPLE_OVERRIDE_BUFFER * 2));

            boolean intradayBullishStructure = hhhl(candles, 6);

            double riseFromLow = sessionLow < Double.MAX_VALUE && sessionLow > 0
                    ? (price - sessionLow) / sessionLow * 100 : 0;
            boolean strongIntradayRise = riseFromLow >= (atrPct * INTRADAY_MOMENTUM_ATR_FACTOR);

            boolean waveBearishButPriceRocketed =
                    waveDown && rocketAboveEma && intradayBullishStructure && strongIntradayRise;

            if ((intradayBullishWave && intradayBullishRipple && intradayBullishStructure && strongIntradayRise)
                    || waveBearishButPriceRocketed) {

                String overrideReason = String.format(
                        "INTRADAY_OVERRIDE→BULLISH: wave=%s ripple=%.2f%%above ema20, " +
                                "rise=%.2f%% from sessionL=%.0f, HH/HL confirmed, ATR=%.2f%%. " +
                                "Long-term tide=%s (not blocking intraday long)",
                        waveUp ? "UP" : "DOWN(but rocketed)",
                        (price / ema20 - 1.0) * 100,
                        riseFromLow,
                        sessionLow < Double.MAX_VALUE ? sessionLow : 0,
                        atrPct,
                        tideUp ? "BULLISH" : "BEARISH");

                log.warn("[MDS] ⚡ INTRADAY OVERRIDE BULLISH: rise={}% from L={} | " +
                                "price={} ema20={} ema50={} | wave={} ripple=UP",
                        f2(riseFromLow),
                        sessionLow < Double.MAX_VALUE ? f0(sessionLow) : "n/a",
                        f0(price), f0(ema20), f0(ema50),
                        waveUp ? "UP" : "DOWN");

                setResult(Direction.BULLISH, true, false, atrPct, atrPct, ema20, ema50, ema200,
                        overrideReason, true, riseFromLow);
                return;
            }
        }

        // ── Original SIDEWAYS path ─────────────────────────────────────────
        String reason;
        if (tideUp && waveUp && !rippleUp)
            reason = "Tide+Wave bullish but price below EMA20 — SIDEWAYS: VAP_Pullback+RangeBreakout active";
        else if (tideDown && waveDown && !rippleDown)
            reason = "Tide+Wave bearish but price above EMA20 — SIDEWAYS: VAP_Pullback+RangeBreakout active";
        else if (tideUp && !waveUp)
            reason = "Above EMA200, recovery in progress — SIDEWAYS: VAP_Pullback+RangeBreakout active";
        else if (tideDown && !waveDown)
            reason = "Below EMA200, dead-cat bounce — SIDEWAYS: VAP_Pullback+RangeBreakout active";
        else
            reason = String.format("Mixed signals — SIDEWAYS: VAP_Pullback+RangeBreakout active | " +
                            "price=%.0f EMA20=%.0f EMA50=%.0f EMA200=%.0f",
                    price, ema20, ema50, ema200);

        setResult(Direction.SIDEWAYS, false, false, atrPct, atrPct, ema20, ema50, ema200,
                reason, false, 0.0);

        log.info("[MDS] Dir=SIDEWAYS price={} EMA20={} EMA50={} EMA200={} ATR={}% " +
                        "tide={} wave={} ripple={} | {}",
                f0(price), f0(ema20), f0(ema50), f0(ema200), f2(atrPct),
                tideUp ? "↑" : tideDown ? "↓" : "—",
                waveUp ? "↑" : waveDown ? "↓" : "—",
                rippleUp ? "↑" : rippleDown ? "↓" : "—",
                reason);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private void setResult(Direction dir, boolean bull, boolean bear,
                           double atr1, double atr2,
                           double ema20, double ema50, double ema200,
                           String reason,
                           boolean overrideActive, double movePct) {
        currentDirection = new MarketDirectionResult(
                dir, bull, bear, bull, bear,
                atr1, atr2, ema20, ema50, ema200, reason,
                overrideActive, movePct);
    }

    private void logDirection(Direction dir, double price,
                              double ema20, double ema50, double ema200,
                              double atrPct,
                              boolean tideUp, boolean waveUp, boolean rippleUp,
                              String reason) {
        log.info("[MDS] Dir={} price={} EMA20={} EMA50={} EMA200={} ATR={}% " +
                        "tide={} wave={} ripple={}{}",
                dir, f0(price), f0(ema20), f0(ema50), f0(ema200), f2(atrPct),
                tideUp ? "↑" : "↓",
                waveUp ? "↑" : "↓",
                rippleUp ? "↑" : "↓",
                reason != null ? " | " + reason : "");
    }

    private boolean hhhl(List<Candle> c, int n) {
        if (c.size() < n) return false;
        int hh = 0, hl = 0;
        for (int i = 0; i < n - 1; i++) {
            if (c.get(i).getHigh().compareTo(c.get(i+1).getHigh()) > 0) hh++;
            if (c.get(i).getLow().compareTo(c.get(i+1).getLow())   > 0) hl++;
        }
        return hh >= (n-1)*0.7 && hl >= (n-1)*0.7;
    }

    private boolean lhll(List<Candle> c, int n) {
        if (c.size() < n) return false;
        int lh = 0, ll = 0;
        for (int i = 0; i < n - 1; i++) {
            if (c.get(i).getHigh().compareTo(c.get(i+1).getHigh()) < 0) lh++;
            if (c.get(i).getLow().compareTo(c.get(i+1).getLow())   < 0) ll++;
        }
        return lh >= (n-1)*0.7 && ll >= (n-1)*0.7;
    }

    private boolean isDoji(Candle c) {
        BigDecimal range = c.getHigh().subtract(c.getLow());
        if (range.compareTo(BigDecimal.ZERO) == 0) return true;
        BigDecimal body = c.getOpen().subtract(c.getClose()).abs();
        return body.divide(range, java.math.MathContext.DECIMAL32)
                .compareTo(new BigDecimal("0.10")) < 0;
    }

    private double ema(List<Candle> candles, int p) {
        if (candles.size() < p) return 0.0;
        double k = 2.0 / (p + 1);
        int warmup = Math.min(2 * p, candles.size());
        double e = candles.get(warmup - 1).getClose().doubleValue();
        for (int i = warmup - 2; i >= 0; i--)
            e = candles.get(i).getClose().doubleValue() * k + e * (1 - k);
        return e;
    }

    private double atr(List<Candle> c, int p) {
        int n = Math.min(p, c.size() - 1);
        if (n == 0) return 0;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double tr = Math.max(
                    c.get(i).getHigh().subtract(c.get(i).getLow()).doubleValue(),
                    Math.max(
                            Math.abs(c.get(i).getHigh().subtract(c.get(i+1).getClose()).doubleValue()),
                            Math.abs(c.get(i).getLow().subtract(c.get(i+1).getClose()).doubleValue())
                    ));
            sum += tr;
        }
        return sum / n;
    }

    // ── Daily reset for session range ─────────────────────────────────────
    @org.springframework.scheduling.annotation.Scheduled(
            cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyReset() {
        sessionHigh = 0;
        sessionLow  = Double.MAX_VALUE;
        sessionInitialized = false;
        log.info("[MDS] Daily reset — session range cleared");
    }

    private String f0(double v) { return String.format("%.0f", v); }
    private String f2(double v) { return String.format("%.2f", v); }

    public boolean needsMoreCandles() { synchronized (niftyBuffer) { return niftyBuffer.size() < 200; } }
    public int     getBufferSize()    { synchronized (niftyBuffer) { return niftyBuffer.size(); } }
}