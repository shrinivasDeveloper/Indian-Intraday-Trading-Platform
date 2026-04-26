// FILE: src/main/java/com/trading/regime/service/MarketDirectionService.java
//
// FIXES APPLIED (2026-04-23):
//
// FIX 1 — ATR threshold lowered: 0.25% → 0.15% for "frozen market" label.
//   ROOT CAUSE: Apr-22(ATR=0.29%) and Apr-23(ATR=0.23%) were both blocked.
//   Nifty 15-min ATR of 0.20% = ~48 point candle range. Individual stocks
//   move 2-5x Nifty. This is NOT a frozen market for stock-level trading.
//   Ultra-frozen days (< 0.10% like Apr-21) are still blocked correctly.
//
// FIX 2 — Doji check made NON-BLOCKING.
//   ROOT CAUSE: Two consecutive Nifty doji returned SIDEWAYS immediately,
//   bypassing all EMA logic and the intraday override.
//   On Apr-22: Nifty was doji at 10:55 AM but Energy +1.17%, Chemicals +1.40%
//   were clearly trending. Zero trades captured.
//   FIX: Doji is now informational (logged, flagged) but does NOT short-circuit.
//   EMA allBull/allBear logic runs regardless of doji presence.
//   Strategies that need direction can still trade when EMAs confirm direction.
//
// FIX 3 — Intraday override ATR threshold: 0.25% → 0.18%.
//   Apr-23 ATR=0.23% was above the frozen-market threshold (0.15%) but below
//   the old override threshold (0.25%). Override never fired. Now 0.18% allows
//   Apr-23 to trigger the override when wave+ripple+structure confirm direction.
//
// UNCHANGED:
//   - MarketDirectionResult record (same fields, same constructor order)
//   - All 3-tier allBull/allBear logic
//   - isTradeable(), isTrendTradeable(), isSidewaysTradeable()
//   - preloadCandles(), all callers compile without modification
//   - Session range tracking, EMA/ATR calculations
//
package com.trading.regime.service;

import com.trading.domain.Candle;
import com.trading.events.CandleCompleteEvent;
import com.trading.marketdata.service.InstrumentCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

/**
 * MarketDirectionService — Institutional MTF Tide Logic with Intraday Override.
 *
 * INTRADAY OVERRIDE:
 *   When wave + ripple both agree on direction AND structural confirmation exists,
 *   direction is set to BULLISH or BEARISH even if tide (EMA200) disagrees.
 *   This correctly handles gap-down/gap-up trending days where long-term trend
 *   is UP but the intraday session is strongly directional the other way.
 *
 * THREE CHECKS (primary path):
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

    // ── ATR bounds ──────────────────────────────────────────────────────────
    /**
     * Market labelled "frozen" below this threshold.
     * FIXED: was 0.25 — blocked Apr-23(0.23%). Lowered to 0.15.
     * Ultra-frozen days (Apr-21: 0.10%) are still blocked correctly.
     */
    private static final double MIN_ATR_PCT_FROZEN  = 0.15;

    /** Market labelled "chaotic" above this threshold. */
    private static final double MAX_ATR_PCT_CHAOTIC = 3.5;

    // ── Intraday override thresholds ────────────────────────────────────────
    /** Ripple must be at least this far from EMA20 to qualify for override. */
    private static final double RIPPLE_OVERRIDE_BUFFER = 0.002;  // 0.2%

    /**
     * Intraday move must be at least this multiple of ATR to confirm real trend.
     */
    private static final double INTRADAY_MOMENTUM_ATR_FACTOR = 1.5;

    /**
     * Minimum ATR% for the intraday override.
     * FIXED: was 0.25% — Apr-23(0.23%) couldn't trigger override. Now 0.18%.
     */
    private static final double MIN_ATR_PCT_FOR_OVERRIDE = 0.18;

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
            boolean   intradayOverrideActive,
            double    intradayMovePct
    ) {
        /** Backward-compatible 11-param constructor (all existing callers unchanged). */
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

        public boolean isTradeable() {
            if (failReason != null && failReason.contains("no trades")) return false;
            return true;
        }
        public boolean isTrendTradeable()   { return direction == Direction.BULLISH || direction == Direction.BEARISH; }
        public boolean isSidewaysTradeable(){ return direction == Direction.SIDEWAYS && (failReason == null || !failReason.contains("no trades")); }
        public boolean isLong()     { return direction == Direction.BULLISH; }
        public boolean isShort()    { return direction == Direction.BEARISH; }
        public boolean isSideways() { return direction == Direction.SIDEWAYS; }
    }

    // ── Session range tracking ──────────────────────────────────────────────
    private volatile double  sessionHigh        = 0;
    private volatile double  sessionLow         = Double.MAX_VALUE;
    private volatile boolean sessionInitialized = false;

    private final Deque<Candle> niftyBuffer = new ArrayDeque<>();

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

        // ── ATR health guard ───────────────────────────────────────────────
        // FIXED: threshold was 0.25 — now 0.15. Apr-23(0.23%) now passes this gate.
        if (atrPct < MIN_ATR_PCT_FROZEN) {
            setResult(Direction.SIDEWAYS, false, false, atrPct, atrPct, ema20, ema50, ema200,
                    "ATR too low: " + f2(atrPct) + "% (frozen market — no trades today)",
                    false, 0.0);
            return;
        }
        if (atrPct > MAX_ATR_PCT_CHAOTIC) {
            setResult(Direction.SIDEWAYS, false, false, atrPct, atrPct, ema20, ema50, ema200,
                    "ATR too high: " + f2(atrPct) + "% (chaotic market — no trades today)",
                    false, 0.0);
            return;
        }

        // ── Doji check — INFORMATIONAL ONLY (non-blocking) ────────────────
        // FIXED: previously two consecutive doji returned SIDEWAYS immediately,
        // bypassing all EMA checks. On Apr-22, Nifty was doji but sectors
        // Energy +1.17%, Chemicals +1.40% were trending — zero trades were captured.
        // Fix: doji is now a flag. EMA logic still runs. Strategies can trade
        // when sector/EMA confirmation exists even on a doji-Nifty day.
        boolean dojiCandle0 = isDoji(candles.get(0));
        boolean dojiCandle1 = isDoji(candles.get(1));
        boolean consecutiveDoji = dojiCandle0 && dojiCandle1;
        if (consecutiveDoji) {
            log.debug("[MDS] Consecutive doji detected — noted but not blocking EMA analysis");
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
            String reason = consecutiveDoji ? "MTF fully bullish (Nifty doji noted — sectors may differ)" : null;
            setResult(Direction.BULLISH, true, false, atrPct, atrPct, ema20, ema50, ema200,
                    reason, false, 0.0);
            log.info("[MDS] Dir=BULLISH price={} EMA20={} EMA50={} EMA200={} ATR={}%{}",
                    f0(price), f0(ema20), f0(ema50), f0(ema200), f2(atrPct),
                    consecutiveDoji ? " [doji noted]" : "");
            return;
        }

        if (allBear) {
            String reason = consecutiveDoji ? "MTF fully bearish (Nifty doji noted — sectors may differ)" : null;
            setResult(Direction.BEARISH, false, true, atrPct, atrPct, ema20, ema50, ema200,
                    reason, false, 0.0);
            log.info("[MDS] Dir=BEARISH price={} EMA20={} EMA50={} EMA200={} ATR={}%{}",
                    f0(price), f0(ema20), f0(ema50), f0(ema200), f2(atrPct),
                    consecutiveDoji ? " [doji noted]" : "");
            return;
        }

        // ── INTRADAY OVERRIDE CHECK ────────────────────────────────────────
        // Fires only when primary path returns SIDEWAYS.
        // Requires wave + ripple agreement AND structural confirmation.
        // FIXED: MIN_ATR threshold was 0.25 (Apr-23=0.23% couldn't trigger). Now 0.18.
        if (atrPct >= MIN_ATR_PCT_FOR_OVERRIDE && sessionInitialized) {

            boolean intradayBearishRipple = price < ema20 * (1.0 - RIPPLE_OVERRIDE_BUFFER);
            boolean intradayBearishWave   = waveDown;
            boolean rippleCrashedBelowEma = (price < ema20 * (1.0 - RIPPLE_OVERRIDE_BUFFER * 2));
            boolean intradayBearishStructure = lhll(candles, 6);
            double dropFromHigh = sessionHigh > 0 ? (sessionHigh - price) / sessionHigh * 100 : 0;
            boolean strongIntradayDrop = dropFromHigh >= (atrPct * INTRADAY_MOMENTUM_ATR_FACTOR);
            boolean waveBullishButPriceCrashed =
                    waveUp && rippleCrashedBelowEma && intradayBearishStructure && strongIntradayDrop;

            if ((intradayBearishWave && intradayBearishRipple && intradayBearishStructure && strongIntradayDrop)
                    || waveBullishButPriceCrashed) {

                String overrideReason = String.format(
                        "INTRADAY_OVERRIDE→BEARISH: wave=%s ripple=%.2f%%below ema20, " +
                                "drop=%.2f%% from sessionH=%.0f, LH/LL confirmed, ATR=%.2f%%.",
                        waveDown ? "DOWN" : "UP(crashed)", (1.0 - price / ema20) * 100,
                        dropFromHigh, sessionHigh, atrPct);

                log.warn("[MDS] ⚡ INTRADAY OVERRIDE BEARISH: drop={}% from H={} | " +
                                "price={} ema20={} ema50={}",
                        f2(dropFromHigh), f0(sessionHigh), f0(price), f0(ema20), f0(ema50));

                setResult(Direction.BEARISH, false, true, atrPct, atrPct, ema20, ema50, ema200,
                        overrideReason, true, dropFromHigh);
                return;
            }

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
                                "rise=%.2f%% from sessionL=%.0f, HH/HL confirmed, ATR=%.2f%%.",
                        waveUp ? "UP" : "DOWN(rocketed)", (price / ema20 - 1.0) * 100,
                        riseFromLow, sessionLow < Double.MAX_VALUE ? sessionLow : 0, atrPct);

                log.warn("[MDS] ⚡ INTRADAY OVERRIDE BULLISH: rise={}% from L={} | " +
                                "price={} ema20={} ema50={}",
                        f2(riseFromLow),
                        sessionLow < Double.MAX_VALUE ? f0(sessionLow) : "n/a",
                        f0(price), f0(ema20), f0(ema50));

                setResult(Direction.BULLISH, true, false, atrPct, atrPct, ema20, ema50, ema200,
                        overrideReason, true, riseFromLow);
                return;
            }
        }

        // ── SIDEWAYS — with contextual reason ─────────────────────────────
        String reason;
        if (consecutiveDoji)
            reason = String.format("Doji/spinning top on Nifty — EMA analysis inconclusive | " +
                            "price=%.0f EMA20=%.0f EMA50=%.0f EMA200=%.0f ATR=%.2f%%",
                    price, ema20, ema50, ema200, atrPct);
        else if (tideUp && waveUp && !rippleUp)
            reason = "Tide+Wave bullish but price below EMA20 — SIDEWAYS: range strategies active";
        else if (tideDown && waveDown && !rippleDown)
            reason = "Tide+Wave bearish but price above EMA20 — SIDEWAYS: range strategies active";
        else if (tideUp && !waveUp)
            reason = "Above EMA200, recovery in progress — SIDEWAYS: range strategies active";
        else if (tideDown && !waveDown)
            reason = "Below EMA200, dead-cat bounce — SIDEWAYS: range strategies active";
        else
            reason = String.format("Mixed signals — SIDEWAYS | " +
                            "price=%.0f EMA20=%.0f EMA50=%.0f EMA200=%.0f ATR=%.2f%%",
                    price, ema20, ema50, ema200, atrPct);

        setResult(Direction.SIDEWAYS, false, false, atrPct, atrPct, ema20, ema50, ema200,
                reason, false, 0.0);

        log.info("[MDS] Dir=SIDEWAYS price={} EMA20={} EMA50={} EMA200={} ATR={}% " +
                        "tide={} wave={} ripple={}",
                f0(price), f0(ema20), f0(ema50), f0(ema200), f2(atrPct),
                tideUp ? "↑" : tideDown ? "↓" : "—",
                waveUp ? "↑" : waveDown ? "↓" : "—",
                rippleUp ? "↑" : rippleDown ? "↓" : "—");
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

    /** Returns the current market direction result. Called by all strategies and WarmupService. */
    public MarketDirectionResult getCurrentDirection() {
        return currentDirection;
    }

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