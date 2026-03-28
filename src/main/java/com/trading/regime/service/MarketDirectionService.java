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
 * MarketDirectionService — Institutional MTF Tide Logic
 *
 * WHY THE OLD LOGIC FAILED TODAY (March 25, 2026):
 *   Old condition: price > EMA20 > EMA50 > EMA200 (strict stacking)
 *   Actual:        price=23286, EMA20=23001, EMA50=22907, EMA200=23178
 *   EMA order:     EMA50 < EMA20 < EMA200  → strict stacking FAILS → SIDEWAYS
 *
 *   But reality: ALL 12 sectors green +1-2.5%, Nifty clearly UP.
 *   Price IS above EMA200. This is a recovery/breakout scenario that the
 *   strict stacking rule was never designed to handle.
 *
 * NEW MTF TIDE APPROACH:
 *   Three independent checks — all must agree:
 *
 *   1. TIDE  (Long-term):   price vs EMA200
 *      → price > EMA200  =  riding the long-term tide UP
 *      → price < EMA200  =  swimming against the tide DOWN
 *
 *   2. WAVE  (Medium-term): EMA20 vs EMA50
 *      → EMA20 > EMA50  =  short-term average above medium = momentum UP
 *      → EMA20 < EMA50  =  short-term average below medium = momentum DOWN
 *
 *   3. RIPPLE (Immediate):  price vs EMA20 (with 0.1% buffer)
 *      → price > EMA20  =  price pulling above recent average = UP
 *      → price < EMA20  =  price sinking below recent average = DOWN
 *
 *   Plus: ATR check (0.25%–3.5%), no doji, soft structure confirmation (50%)
 *
 * TODAY'S RESULT:
 *   Tide:   23286 > 23178 ✓ UP
 *   Wave:   23001 > 22907 ✓ UP
 *   Ripple: 23286 > 23001 ✓ UP
 *   → BULLISH ✓  (was: SIDEWAYS ✗)
 */
@Service
@Slf4j
public class MarketDirectionService {

    private final InstrumentCacheService instrumentCache;

    public MarketDirectionService(InstrumentCacheService instrumentCache) {
        this.instrumentCache = instrumentCache;
    }

    public enum Direction { BULLISH, BEARISH, SIDEWAYS }

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
            String    failReason
    ) {
        public boolean isTradeable() {
            return direction == Direction.BULLISH || direction == Direction.BEARISH;
        }
        public boolean isLong()  { return direction == Direction.BULLISH; }
        public boolean isShort() { return direction == Direction.BEARISH; }
    }

    // index 0 = NEWEST, index size-1 = OLDEST
    private final Deque<Candle> niftyBuffer = new ArrayDeque<>();

    @Getter
    private volatile MarketDirectionResult currentDirection = new MarketDirectionResult(
            Direction.SIDEWAYS, false, false, false, false,
            0, 0, 0, 0, 0, "Waiting for 15min candle data"
    );

    // ════════════════════════════════════════════════════════════════════════
    // Startup warm-up
    // ════════════════════════════════════════════════════════════════════════

    public void preloadCandles(List<Candle> historicalCandles) {
        if (historicalCandles == null || historicalCandles.isEmpty()) return;
        synchronized (niftyBuffer) {
            niftyBuffer.clear();
            for (Candle c : historicalCandles) {
                niftyBuffer.addFirst(c);
                if (niftyBuffer.size() > 300)
                    ((ArrayDeque<Candle>) niftyBuffer).removeLast();
            }
            log.info("[MDS] Buffer pre-loaded with {} Nifty 15-min candles", niftyBuffer.size());
        }
        recalculate();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Live feed
    // ════════════════════════════════════════════════════════════════════════

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
        recalculate();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Core MTF calculation
    // ════════════════════════════════════════════════════════════════════════

    private void recalculate() {
        List<Candle> candles;
        synchronized (niftyBuffer) {
            candles = new ArrayList<>(niftyBuffer);
        }

        if (candles.size() < 200) {
            setResult(Direction.SIDEWAYS, false, false,
                    0, 0, 0, 0, 0,
                    "Need 200 candles, have " + candles.size());
            return;
        }

        double ema20  = ema(candles, 20);
        double ema50  = ema(candles, 50);
        double ema200 = ema(candles, 200);
        double atr    = atr(candles, 14);
        double price  = candles.get(0).getClose().doubleValue();
        double atrPct = price > 0 ? atr / price * 100 : 0;

        // ATR health — wider bounds handle elevated-VIX days
        if (atrPct < 0.25) {
            setResult(Direction.SIDEWAYS, false, false,
                    atrPct, atrPct, ema20, ema50, ema200,
                    "ATR too low: " + f2(atrPct) + "% (frozen market)");
            return;
        }
        if (atrPct > 3.5) {
            setResult(Direction.SIDEWAYS, false, false,
                    atrPct, atrPct, ema20, ema50, ema200,
                    "ATR too high: " + f2(atrPct) + "% (chaotic market — avoid)");
            return;
        }

        // No doji on last 2 candles
        if (isDoji(candles.get(0)) || isDoji(candles.get(1))) {
            setResult(Direction.SIDEWAYS, false, false,
                    atrPct, atrPct, ema20, ema50, ema200,
                    "Doji/spinning top on Nifty — awaiting directional candle");
            return;
        }

        // ── MTF checks ──────────────────────────────────────────────────────────

        // TIDE: price vs EMA200 (long-term trend)
        boolean tideUp   = price > ema200;
        boolean tideDown = price < ema200;

        // WAVE: EMA20 vs EMA50 (medium momentum)
        boolean waveUp   = ema20 > ema50;
        boolean waveDown = ema20 < ema50;

        // RIPPLE: price vs EMA20 with 0.1% buffer (immediate)
        boolean rippleUp   = price > ema20 * 1.001;
        boolean rippleDown = price < ema20 * 0.999;

        // STRUCTURE: soft HH/HL or LH/LL on 8 candles (50% threshold)
        boolean structUp   = hhhl(candles, 8);
        boolean structDown = lhll(candles, 8);

        boolean allBull = tideUp   && waveUp   && rippleUp;
        boolean allBear = tideDown && waveDown && rippleDown;

        Direction dir;
        String    reason = null;

        if (allBull) {
            dir = Direction.BULLISH;
            if (!structUp) reason = "MTF fully bullish, HH/HL structure forming";
        } else if (allBear) {
            dir = Direction.BEARISH;
            if (!structDown) reason = "MTF fully bearish, LH/LL structure forming";
        } else {
            dir = Direction.SIDEWAYS;
            // Explain what's contradictory
            if (tideUp && waveUp && !rippleUp)
                reason = "Tide+Wave bullish but price pulled back below EMA20 — wait for bounce";
            else if (tideDown && waveDown && !rippleDown)
                reason = "Tide+Wave bearish but price bounced above EMA20 — wait for rejection";
            else if (tideUp && !waveUp)
                reason = "Price above EMA200 but EMA20<EMA50 — recovery in progress, not yet bullish";
            else if (tideDown && !waveDown)
                reason = "Price below EMA200 but EMA20>EMA50 — dead-cat bounce, still bearish structure";
            else
                reason = String.format("Mixed — price=%.0f EMA20=%.0f EMA50=%.0f EMA200=%.0f",
                        price, ema20, ema50, ema200);
        }

        setResult(dir, dir == Direction.BULLISH, dir == Direction.BEARISH,
                atrPct, atrPct, ema20, ema50, ema200, reason);

        log.info("[MDS] Dir={} price={} EMA20={} EMA50={} EMA200={} ATR={}% " +
                        "tide={} wave={} ripple={}{}",
                dir, f0(price), f0(ema20), f0(ema50), f0(ema200), f2(atrPct),
                tideUp ? "↑" : tideDown ? "↓" : "—",
                waveUp ? "↑" : waveDown ? "↓" : "—",
                rippleUp ? "↑" : rippleDown ? "↓" : "—",
                reason != null ? " | " + reason : "");
    }

    private void setResult(Direction dir, boolean bull, boolean bear,
                           double atr1, double atr2,
                           double ema20, double ema50, double ema200,
                           String reason) {
        currentDirection = new MarketDirectionResult(
                dir, bull, bear, bull, bear,
                atr1, atr2, ema20, ema50, ema200, reason);
    }

    // ── HH+HL: soft 50% threshold ─────────────────────────────────────────────

    private boolean hhhl(List<Candle> c, int n) {
        if (c.size() < n) return false;
        int hh = 0, hl = 0;
        for (int i = 0; i < n - 1; i++) {
            if (c.get(i).getHigh().compareTo(c.get(i + 1).getHigh()) > 0) hh++;
            if (c.get(i).getLow().compareTo(c.get(i + 1).getLow())   > 0) hl++;
        }
        return hh >= (n - 1) * 0.5 && hl >= (n - 1) * 0.5;
    }

    private boolean lhll(List<Candle> c, int n) {
        if (c.size() < n) return false;
        int lh = 0, ll = 0;
        for (int i = 0; i < n - 1; i++) {
            if (c.get(i).getHigh().compareTo(c.get(i + 1).getHigh()) < 0) lh++;
            if (c.get(i).getLow().compareTo(c.get(i + 1).getLow())   < 0) ll++;
        }
        return lh >= (n - 1) * 0.5 && ll >= (n - 1) * 0.5;
    }

    // ── Doji: body < 10% of range ─────────────────────────────────────────────

    private boolean isDoji(Candle c) {
        BigDecimal range = c.getHigh().subtract(c.getLow());
        if (range.compareTo(BigDecimal.ZERO) == 0) return true;
        BigDecimal body = c.getOpen().subtract(c.getClose()).abs();
        return body.divide(range, java.math.MathContext.DECIMAL32)
                .compareTo(new BigDecimal("0.10")) < 0;
    }

    // ── EMA — correct: seed oldest, iterate toward newest ─────────────────────

    private double ema(List<Candle> candles, int p) {
        if (candles.size() < p) return 0.0;
        double k      = 2.0 / (p + 1);
        int    warmup = Math.min(2 * p, candles.size());
        double e      = candles.get(warmup - 1).getClose().doubleValue();
        for (int i = warmup - 2; i >= 0; i--)
            e = candles.get(i).getClose().doubleValue() * k + e * (1 - k);
        return e;
    }

    // ── ATR — unchanged, correct ──────────────────────────────────────────────

    private double atr(List<Candle> c, int p) {
        int n = Math.min(p, c.size() - 1);
        if (n == 0) return 0;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double tr = Math.max(
                    c.get(i).getHigh().subtract(c.get(i).getLow()).doubleValue(),
                    Math.max(
                            Math.abs(c.get(i).getHigh().subtract(c.get(i + 1).getClose()).doubleValue()),
                            Math.abs(c.get(i).getLow().subtract(c.get(i + 1).getClose()).doubleValue())
                    ));
            sum += tr;
        }
        return sum / n;
    }

    private String f0(double v) { return String.format("%.0f", v); }
    private String f2(double v) { return String.format("%.2f", v); }

    public boolean needsMoreCandles() {
        synchronized (niftyBuffer) { return niftyBuffer.size() < 200; }
    }

    public int getBufferSize() {
        synchronized (niftyBuffer) { return niftyBuffer.size(); }
    }
}