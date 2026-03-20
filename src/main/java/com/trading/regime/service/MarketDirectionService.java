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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gate 1 — Market Direction using NIFTY ONLY (15-minute candles).
 *
 * Checks:
 *   1. Price above EMA20 > EMA50 > EMA200 (uptrend) or below all (downtrend)
 *   2. Last 10 candles show HH+HL (uptrend) or LH+LL (downtrend)
 *   3. ATR% between 0.5% and 2.5% (healthy movement range)
 *   4. No doji/spinning top in last 2 candles
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
            // kept for API compatibility — always mirrors nifty values now
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

    // Only Nifty buffer needed now
    private final Deque<Candle> niftyBuffer = new ArrayDeque<>();

    @Getter
    private volatile MarketDirectionResult currentDirection = new MarketDirectionResult(
            Direction.SIDEWAYS, false, false, false, false,
            0, 0, 0, 0, 0, "Waiting for 15min candle data"
    );

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

    private void recalculate() {
        List<Candle> candles;
        synchronized (niftyBuffer) {
            candles = new ArrayList<>(niftyBuffer);
        }

        if (candles.size() < 200) {
            setResult(Direction.SIDEWAYS, false, false,
                    0.0, 0.0, 0.0, 0.0, 0.0, "Need 200 candles, have " + candles.size());
            return;
        }

        double ema20  = ema(candles, 20);
        double ema50  = ema(candles, 50);
        double ema200 = ema(candles, 200);
        double atr    = atr(candles, 14);
        double price  = candles.get(0).getClose().doubleValue();
        double atrPct = price > 0 ? atr / price * 100 : 0;

        // ATR health check — market must be moving but not wildly
        if (atrPct < 0.5) {
            setResult(Direction.SIDEWAYS, false, false,
                    atrPct, atrPct, ema20, ema50, ema200,
                    "ATR too low: " + String.format("%.2f", atrPct) + "% (market frozen)");
            return;
        }
        if (atrPct > 2.5) {
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

        // HH/HL or LH/LL pattern on last 10 candles
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
            if (bullEma) reason = "EMA bullish but no HH/HL pattern";
            else if (bearEma) reason = "EMA bearish but no LH/LL pattern";
            else reason = "EMAs not stacked — no clear trend";
        }

        setResult(dir, bullPattern, bearPattern,
                atrPct, atrPct, ema20, ema50, ema200, reason);

        if (dir != Direction.SIDEWAYS) {
            log.info("Market direction: {} | EMA20={} EMA50={} EMA200={} ATR={}%",
                    dir,
                    String.format("%.0f", ema20),
                    String.format("%.0f", ema50),
                    String.format("%.0f", ema200),
                    String.format("%.2f", atrPct));
        }
    }

    private void setResult(Direction dir,
                           boolean niftyBull, boolean niftyBear,
                           double niftyAtrPct, double bnAtrPct,
                           double ema20, double ema50, double ema200,
                           String reason) {
        currentDirection = new MarketDirectionResult(
                dir, niftyBull, niftyBear,
                niftyBull, niftyBear,  // bankNifty mirrors nifty now
                niftyAtrPct, bnAtrPct, ema20, ema50, ema200, reason);
    }

    // ── HH+HL pattern (60% of candles must show it) ───────────────────

    private boolean hhhl(List<Candle> candles, int n) {
        if (candles.size() < n) return false;
        int hh = 0, hl = 0;
        for (int i = 0; i < n - 1; i++) {
            if (candles.get(i).getHigh().compareTo(candles.get(i+1).getHigh()) > 0) hh++;
            if (candles.get(i).getLow().compareTo(candles.get(i+1).getLow())   > 0) hl++;
        }
        return hh >= (n-1) * 0.6 && hl >= (n-1) * 0.6;
    }

    // ── LH+LL pattern (60% of candles must show it) ───────────────────

    private boolean lhll(List<Candle> candles, int n) {
        if (candles.size() < n) return false;
        int lh = 0, ll = 0;
        for (int i = 0; i < n - 1; i++) {
            if (candles.get(i).getHigh().compareTo(candles.get(i+1).getHigh()) < 0) lh++;
            if (candles.get(i).getLow().compareTo(candles.get(i+1).getLow())   < 0) ll++;
        }
        return lh >= (n-1) * 0.6 && ll >= (n-1) * 0.6;
    }

    // ── Doji: body < 10% of range ─────────────────────────────────────

    private boolean isDoji(Candle c) {
        BigDecimal range = c.getHigh().subtract(c.getLow());
        if (range.compareTo(BigDecimal.ZERO) == 0) return true;
        BigDecimal body = c.getOpen().subtract(c.getClose()).abs();
        return body.divide(range, java.math.MathContext.DECIMAL32)
                .compareTo(new BigDecimal("0.10")) < 0;
    }

    // ── EMA calculation ───────────────────────────────────────────────

    private double ema(List<Candle> c, int p) {
        if (c.size() < p) return 0;
        double k = 2.0 / (p + 1);
        double e = c.get(c.size() - p).getClose().doubleValue();
        for (int i = c.size() - p + 1; i < c.size(); i++)
            e = c.get(i).getClose().doubleValue() * k + e * (1 - k);
        return e;
    }

    // ── ATR calculation ───────────────────────────────────────────────

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
}