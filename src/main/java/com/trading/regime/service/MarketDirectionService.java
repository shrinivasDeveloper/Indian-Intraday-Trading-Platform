// FILE: src/main/java/com/trading/regime/service/MarketDirectionService.java
// MODIFIED — isTradeable() returns true for SIDEWAYS too.
// Added: isTrendTradeable(), isSidewaysTradeable(), isSideways()
// Sideways failReason updated to mention which strategies still run.
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
 * v3.0 CHANGE: isTradeable() now returns true for ALL three directions.
 * The MarketModeEngine (new) decides WHICH strategies run for each day type.
 *
 * THREE CHECKS (unchanged logic):
 *   TIDE   (Long-term):  price vs EMA200
 *   WAVE   (Medium):     EMA20 vs EMA50
 *   RIPPLE (Immediate):  price vs EMA20 ± 0.1% buffer
 *
 * allBull = tideUp AND waveUp AND rippleUp   → BULLISH
 * allBear = tideDown AND waveDown AND rippleDown → BEARISH
 * else                                        → SIDEWAYS
 *
 * NEW HELPER METHODS:
 *   isTrendTradeable()    → BULLISH or BEARISH (for trend strategies)
 *   isSidewaysTradeable() → SIDEWAYS (for mean-reversion strategies)
 *   isSideways()          → SIDEWAYS specifically
 *
 * FROZEN/CHAOTIC market (ATR out of range or doji):
 *   Sets SIDEWAYS with failReason containing "no trades"
 *   StrategyEvaluatorService checks this phrase to hard-stop all strategies.
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
        /**
         * Returns true for ALL three directions (BULLISH, BEARISH, SIDEWAYS).
         * MarketModeEngine decides which strategies are active per day type.
         * Only returns false when market is truly frozen/chaotic (ATR extremes).
         */
        public boolean isTradeable() {
            // "no trades" phrase in failReason = frozen or chaotic market (hard stop)
            if (failReason != null && failReason.contains("no trades")) return false;
            return true;
        }

        /** True only for BULLISH or BEARISH — used by trend strategies */
        public boolean isTrendTradeable() {
            return direction == Direction.BULLISH || direction == Direction.BEARISH;
        }

        /** True only for SIDEWAYS — used by mean-reversion strategies */
        public boolean isSidewaysTradeable() {
            return direction == Direction.SIDEWAYS
                    && (failReason == null || !failReason.contains("no trades"));
        }

        public boolean isLong()     { return direction == Direction.BULLISH; }
        public boolean isShort()    { return direction == Direction.BEARISH; }
        public boolean isSideways() { return direction == Direction.SIDEWAYS; }
    }

    private final Deque<Candle> niftyBuffer = new ArrayDeque<>();

    @Getter
    private volatile MarketDirectionResult currentDirection = new MarketDirectionResult(
            Direction.SIDEWAYS, false, false, false, false,
            0, 0, 0, 0, 0, "Waiting for 15min candle data"
    );

    // ═════════════════════════════════════════════════════════════════════
    // Startup warm-up
    // ═════════════════════════════════════════════════════════════════════

    public void preloadCandles(List<Candle> historicalCandles) {
        if (historicalCandles == null || historicalCandles.isEmpty()) return;
        synchronized (niftyBuffer) {
            niftyBuffer.clear();
            for (Candle c : historicalCandles) {
                niftyBuffer.addFirst(c);
                if (niftyBuffer.size() > 300) ((ArrayDeque<Candle>) niftyBuffer).removeLast();
            }
            log.info("[MDS] Pre-loaded {} Nifty 15-min candles", niftyBuffer.size());
        }
        recalculate();
    }

    // ═════════════════════════════════════════════════════════════════════
    // Live feed
    // ═════════════════════════════════════════════════════════════════════

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

    // ═════════════════════════════════════════════════════════════════════
    // Core MTF calculation (unchanged from original)
    // ═════════════════════════════════════════════════════════════════════

    private void recalculate() {
        List<Candle> candles;
        synchronized (niftyBuffer) { candles = new ArrayList<>(niftyBuffer); }

        if (candles.size() < 200) {
            setResult(Direction.SIDEWAYS, false, false, 0, 0, 0, 0, 0,
                    "Need 200 candles, have " + candles.size());
            return;
        }

        double ema20  = ema(candles, 20);
        double ema50  = ema(candles, 50);
        double ema200 = ema(candles, 200);
        double atr    = atr(candles, 14);
        double price  = candles.get(0).getClose().doubleValue();
        double atrPct = price > 0 ? atr / price * 100 : 0;

        // ATR health — frozen or chaotic market: hard stop on ALL strategies
        if (atrPct < 0.25) {
            setResult(Direction.SIDEWAYS, false, false, atrPct, atrPct, ema20, ema50, ema200,
                    "ATR too low: " + f2(atrPct) + "% (frozen market — no trades today)");
            return;
        }
        if (atrPct > 3.5) {
            setResult(Direction.SIDEWAYS, false, false, atrPct, atrPct, ema20, ema50, ema200,
                    "ATR too high: " + f2(atrPct) + "% (chaotic market — no trades today)");
            return;
        }

        // Doji check
        if (isDoji(candles.get(0)) || isDoji(candles.get(1))) {
            setResult(Direction.SIDEWAYS, false, false, atrPct, atrPct, ema20, ema50, ema200,
                    "Doji/spinning top on Nifty — awaiting directional candle");
            return;
        }

        // MTF checks
        boolean tideUp    = price > ema200;
        boolean tideDown  = price < ema200;
        boolean waveUp    = ema20 > ema50;
        boolean waveDown  = ema20 < ema50;
        boolean rippleUp  = price > ema20 * 1.001;
        boolean rippleDown= price < ema20 * 0.999;

        boolean allBull = tideUp   && waveUp   && rippleUp;
        boolean allBear = tideDown && waveDown && rippleDown;

        Direction dir;
        String    reason = null;

        if (allBull) {
            dir = Direction.BULLISH;
            if (!hhhl(candles, 8)) reason = "MTF fully bullish, HH/HL structure forming";
        } else if (allBear) {
            dir = Direction.BEARISH;
            if (!lhll(candles, 8)) reason = "MTF fully bearish, LH/LL structure forming";
        } else {
            dir = Direction.SIDEWAYS;
            // Updated reasons: explicitly state which strategies remain active
            if (tideUp && waveUp && !rippleUp)
                reason = "Tide+Wave bullish but price below EMA20 — SIDEWAYS: VAP_Pullback+RangeBreakout active";
            else if (tideDown && waveDown && !rippleDown)
                reason = "Tide+Wave bearish but price above EMA20 — SIDEWAYS: VAP_Pullback+RangeBreakout active";
            else if (tideUp && !waveUp)
                reason = "Above EMA200, recovery in progress — SIDEWAYS: VAP_Pullback+RangeBreakout active";
            else if (tideDown && !waveDown)
                reason = "Below EMA200, dead-cat bounce — SIDEWAYS: VAP_Pullback+RangeBreakout active";
            else
                reason = String.format("Mixed signals — SIDEWAYS: VAP_Pullback+RangeBreakout active | price=%.0f EMA20=%.0f EMA50=%.0f EMA200=%.0f",
                        price, ema20, ema50, ema200);
        }

        setResult(dir, dir == Direction.BULLISH, dir == Direction.BEARISH,
                atrPct, atrPct, ema20, ema50, ema200, reason);

        log.info("[MDS] Dir={} price={} EMA20={} EMA50={} EMA200={} ATR={}% tide={} wave={} ripple={}{}",
                dir, f0(price), f0(ema20), f0(ema50), f0(ema200), f2(atrPct),
                tideUp ? "↑" : tideDown ? "↓" : "—",
                waveUp ? "↑" : waveDown ? "↓" : "—",
                rippleUp ? "↑" : rippleDown ? "↓" : "—",
                reason != null ? " | " + reason : "");
    }

    private void setResult(Direction dir, boolean bull, boolean bear,
                           double atr1, double atr2, double ema20, double ema50, double ema200, String reason) {
        currentDirection = new MarketDirectionResult(dir, bull, bear, bull, bear, atr1, atr2, ema20, ema50, ema200, reason);
    }

    private boolean hhhl(List<Candle> c, int n) {
        if (c.size() < n) return false;
        int hh = 0, hl = 0;
        for (int i = 0; i < n - 1; i++) {
            if (c.get(i).getHigh().compareTo(c.get(i+1).getHigh()) > 0) hh++;
            if (c.get(i).getLow().compareTo(c.get(i+1).getLow())   > 0) hl++;
        }
        return hh >= (n-1)*0.5 && hl >= (n-1)*0.5;
    }

    private boolean lhll(List<Candle> c, int n) {
        if (c.size() < n) return false;
        int lh = 0, ll = 0;
        for (int i = 0; i < n - 1; i++) {
            if (c.get(i).getHigh().compareTo(c.get(i+1).getHigh()) < 0) lh++;
            if (c.get(i).getLow().compareTo(c.get(i+1).getLow())   < 0) ll++;
        }
        return lh >= (n-1)*0.5 && ll >= (n-1)*0.5;
    }

    private boolean isDoji(Candle c) {
        BigDecimal range = c.getHigh().subtract(c.getLow());
        if (range.compareTo(BigDecimal.ZERO) == 0) return true;
        BigDecimal body = c.getOpen().subtract(c.getClose()).abs();
        return body.divide(range, java.math.MathContext.DECIMAL32).compareTo(new BigDecimal("0.10")) < 0;
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

    private String f0(double v) { return String.format("%.0f", v); }
    private String f2(double v) { return String.format("%.2f", v); }

    public boolean needsMoreCandles() { synchronized (niftyBuffer) { return niftyBuffer.size() < 200; } }
    public int     getBufferSize()    { synchronized (niftyBuffer) { return niftyBuffer.size(); } }
}