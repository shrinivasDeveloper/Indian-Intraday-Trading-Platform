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
 * Gate 1 — Market Direction using NIFTY ONLY (15-minute candles).
 *
 * Checks:
 *   1. Price above EMA20 > EMA50 > EMA200 (uptrend) or below all (downtrend)
 *   2. Last 10 candles show HH+HL (uptrend) or LH+LL (downtrend)
 *   3. ATR% between 0.3% and 3.0% (healthy movement range)
 *   4. No doji/spinning top in last 2 candles
 *
 * BUGS FIXED vs previous version:
 *
 *   1. ema() iterated in the wrong direction.
 *      Buffer layout: index 0 = NEWEST, index size-1 = OLDEST.
 *      Old code seeded at candle[size-p] (100th newest for p=200) and iterated
 *      from index size-p+1 toward size-1 (going OLDER). This gave more weight
 *      to OLD prices — the exact opposite of EMA. In a downtrend, old prices
 *      are higher, so EMA200 came out > EMA20, the stacking condition failed,
 *      and direction was always SIDEWAYS even with 300 candles loaded.
 *
 *      Fixed: seeds at the OLDEST candle of the 2p warmup window, iterates
 *      toward index 0 (NEWEST), so recent prices get more weight. Correct EMA.
 *
 *   2. Added preloadCandles() for startup warm-up via NiftyHistoricalLoaderService.
 *
 *   3. ATR thresholds relaxed: min 0.3% (was 0.5%), max 3.0% (was 2.5%)
 *      to accommodate elevated-VIX days and pre-market historical candles.
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
            // kept for API compatibility — mirrors nifty values
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

    // index 0 = NEWEST candle, index size-1 = OLDEST candle
    private final Deque<Candle> niftyBuffer = new ArrayDeque<>();

    @Getter
    private volatile MarketDirectionResult currentDirection = new MarketDirectionResult(
            Direction.SIDEWAYS, false, false, false, false,
            0, 0, 0, 0, 0, "Waiting for 15min candle data"
    );

    // ════════════════════════════════════════════════════════════════════════
    // Startup warm-up — called by NiftyHistoricalLoaderService
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Pre-loads historical Nifty 15-min candles so EMA200 is available immediately.
     *
     * @param historicalCandles List sorted OLDEST → NEWEST
     */
    public void preloadCandles(List<Candle> historicalCandles) {
        if (historicalCandles == null || historicalCandles.isEmpty()) {
            log.warn("[MDS] preloadCandles called with empty list — skipping");
            return;
        }

        synchronized (niftyBuffer) {
            niftyBuffer.clear();
            // Iterating oldest→newest and calling addFirst() each time
            // results in the NEWEST being at index 0 (the front of the deque). ✓
            for (Candle c : historicalCandles) {
                niftyBuffer.addFirst(c);
                if (niftyBuffer.size() > 300) {
                    ((ArrayDeque<Candle>) niftyBuffer).removeLast();
                }
            }
            log.info("[MDS] Buffer pre-loaded with {} Nifty 15-min candles", niftyBuffer.size());
        }

        recalculate();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Live feed — fires on every completed 15-min Nifty candle
    // ════════════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tradingExecutor")
    public void onCandle(CandleCompleteEvent event) {
        Candle c = event.getCandle();
        if (!"15minute".equals(c.getTimeframe())) return;
        if (c.getInstrumentToken() != instrumentCache.getNiftyToken()) return;

        synchronized (niftyBuffer) {
            niftyBuffer.addFirst(c); // newest at front
            if (niftyBuffer.size() > 300) ((ArrayDeque<Candle>) niftyBuffer).removeLast();
        }

        recalculate();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Direction calculation
    // ════════════════════════════════════════════════════════════════════════

    private void recalculate() {
        List<Candle> candles;
        synchronized (niftyBuffer) {
            candles = new ArrayList<>(niftyBuffer);
        }

        if (candles.size() < 200) {
            setResult(Direction.SIDEWAYS, false, false,
                    0.0, 0.0, 0.0, 0.0, 0.0,
                    "Need 200 candles, have " + candles.size() +
                            " — NiftyHistoricalLoaderService pre-loads this on startup");
            return;
        }

        double ema20  = ema(candles, 20);
        double ema50  = ema(candles, 50);
        double ema200 = ema(candles, 200);
        double atr    = atr(candles, 14);
        double price  = candles.get(0).getClose().doubleValue();
        double atrPct = price > 0 ? atr / price * 100 : 0;

        // ATR health check — market must be moving but not wildly
        // Thresholds relaxed vs original (0.5/2.5) to handle elevated-VIX days
        if (atrPct < 0.3) {
            setResult(Direction.SIDEWAYS, false, false,
                    atrPct, atrPct, ema20, ema50, ema200,
                    "ATR too low: " + String.format("%.2f", atrPct) + "% (market frozen)");
            return;
        }
        if (atrPct > 3.0) {
            setResult(Direction.SIDEWAYS, false, false,
                    atrPct, atrPct, ema20, ema50, ema200,
                    "ATR too high: " + String.format("%.2f", atrPct) + "% (market chaotic)");
            return;
        }

        // Doji check on last 2 candles
        if (isDoji(candles.get(0)) || isDoji(candles.get(1))) {
            setResult(Direction.SIDEWAYS, false, false,
                    atrPct, atrPct, ema20, ema50, ema200,
                    "Doji/indecision on Nifty last 2 candles");
            return;
        }

        // EMA stacking check
        boolean bullEma = price > ema20 && ema20 > ema50 && ema50 > ema200;
        boolean bearEma = price < ema20 && ema20 < ema50 && ema50 < ema200;

        // Structure confirmation on last 10 candles
        boolean bullPattern = bullEma && hhhl(candles, 10);
        boolean bearPattern = bearEma && lhll(candles, 10);

        Direction dir;
        String reason = null;

        if (bullPattern) {
            dir = Direction.BULLISH;
        } else if (bearPattern) {
            dir = Direction.BEARISH;
        } else {
            dir = Direction.SIDEWAYS;
            if (bullEma)       reason = "EMA bullish but HH/HL pattern < 60% (choppy uptrend)";
            else if (bearEma)  reason = "EMA bearish but LH/LL pattern < 60% (choppy downtrend)";
            else               reason = "EMAs not stacked — EMA20=" + String.format("%.0f", ema20)
                        + " EMA50=" + String.format("%.0f", ema50)
                        + " EMA200=" + String.format("%.0f", ema200);
        }

        setResult(dir, bullPattern, bearPattern,
                atrPct, atrPct, ema20, ema50, ema200, reason);

        log.info("[MDS] Direction={} | price={} EMA20={} EMA50={} EMA200={} ATR={}% | {}",
                dir,
                String.format("%.0f", price),
                String.format("%.0f", ema20),
                String.format("%.0f", ema50),
                String.format("%.0f", ema200),
                String.format("%.2f", atrPct),
                reason != null ? reason : "OK");
    }

    private void setResult(Direction dir,
                           boolean niftyBull, boolean niftyBear,
                           double niftyAtrPct, double bnAtrPct,
                           double ema20, double ema50, double ema200,
                           String reason) {
        currentDirection = new MarketDirectionResult(
                dir, niftyBull, niftyBear,
                niftyBull, niftyBear, // bankNifty mirrors nifty
                niftyAtrPct, bnAtrPct, ema20, ema50, ema200, reason);
    }

    // ── HH+HL pattern (60% of n-1 consecutive pairs must show it) ─────────────
    // candles.get(i) is NEWER than candles.get(i+1)
    // newer.high > older.high  =  Higher High ✓

    private boolean hhhl(List<Candle> candles, int n) {
        if (candles.size() < n) return false;
        int hh = 0, hl = 0;
        for (int i = 0; i < n - 1; i++) {
            if (candles.get(i).getHigh().compareTo(candles.get(i + 1).getHigh()) > 0) hh++;
            if (candles.get(i).getLow().compareTo(candles.get(i + 1).getLow())   > 0) hl++;
        }
        return hh >= (n - 1) * 0.6 && hl >= (n - 1) * 0.6;
    }

    // ── LH+LL pattern ─────────────────────────────────────────────────────────
    // newer.high < older.high  =  Lower High ✓

    private boolean lhll(List<Candle> candles, int n) {
        if (candles.size() < n) return false;
        int lh = 0, ll = 0;
        for (int i = 0; i < n - 1; i++) {
            if (candles.get(i).getHigh().compareTo(candles.get(i + 1).getHigh()) < 0) lh++;
            if (candles.get(i).getLow().compareTo(candles.get(i + 1).getLow())   < 0) ll++;
        }
        return lh >= (n - 1) * 0.6 && ll >= (n - 1) * 0.6;
    }

    // ── Doji: body < 10% of range ─────────────────────────────────────────────

    private boolean isDoji(Candle c) {
        BigDecimal range = c.getHigh().subtract(c.getLow());
        if (range.compareTo(BigDecimal.ZERO) == 0) return true;
        BigDecimal body = c.getOpen().subtract(c.getClose()).abs();
        return body.divide(range, java.math.MathContext.DECIMAL32)
                .compareTo(new BigDecimal("0.10")) < 0;
    }

    // ── EMA — FIXED ───────────────────────────────────────────────────────────
    //
    // Buffer: index 0 = NEWEST, index size-1 = OLDEST.
    //
    // Correct approach:
    //   Use up to 2p candles as the calculation window (p warmup + p "live").
    //   Start with the OLDEST candle (index warmup-1) as the seed.
    //   Iterate from index warmup-2 DOWN TO 0 (moving toward NEWEST).
    //   This gives progressively more weight to recent prices — correct EMA. ✓
    //
    // Old (broken) approach:
    //   Seeded at candle[size-p] and iterated TOWARD size-1 (toward OLDER).
    //   This gave more weight to OLD prices — backwards EMA. ✗

    private double ema(List<Candle> candles, int p) {
        if (candles.size() < p) return 0.0;

        double k      = 2.0 / (p + 1);
        int    warmup = Math.min(2 * p, candles.size()); // window size (max 2p)
        int    start  = warmup - 1;                      // index of oldest candle in window

        // Seed with the oldest candle in the window
        double e = candles.get(start).getClose().doubleValue();

        // Walk from start-1 down to 0 (oldest → newest)
        for (int i = start - 1; i >= 0; i--) {
            e = candles.get(i).getClose().doubleValue() * k + e * (1 - k);
        }

        // e is now the EMA value as of candles.get(0) (the most recent candle) ✓
        return e;
    }

    // ── ATR (unchanged — correct as-is) ──────────────────────────────────────
    // candles.get(i+1) = older candle = "previous close" for True Range. ✓

    private double atr(List<Candle> candles, int p) {
        int n = Math.min(p, candles.size() - 1);
        if (n == 0) return 0;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double tr = Math.max(
                    candles.get(i).getHigh().subtract(candles.get(i).getLow()).doubleValue(),
                    Math.max(
                            Math.abs(candles.get(i).getHigh().subtract(candles.get(i + 1).getClose()).doubleValue()),
                            Math.abs(candles.get(i).getLow().subtract(candles.get(i + 1).getClose()).doubleValue())
                    ));
            sum += tr;
        }
        return sum / n;
    }

    // ── Public helpers ────────────────────────────────────────────────────────

    public boolean needsMoreCandles() {
        synchronized (niftyBuffer) { return niftyBuffer.size() < 200; }
    }

    public int getBufferSize() {
        synchronized (niftyBuffer) { return niftyBuffer.size(); }
    }
}