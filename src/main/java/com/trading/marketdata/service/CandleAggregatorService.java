package com.trading.marketdata.service;

import com.trading.domain.Candle;
import com.trading.domain.enums.TimeFrame;
import com.trading.events.CandleCompleteEvent;
import com.trading.events.TickReceivedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CandleAggregatorService — Builds 5-min and 15-min candles from live ticks.
 *
 * BUGS FIXED:
 *   1. Volume = cumulative day total instead of per-candle delta.
 *      FIXED: volumeAtOpen tracked. perCandleVolume = currentDayVolume - volumeAtOpen.
 *
 *   2. Compile error: stale.volume is a method volume(), not a field.
 *      FIXED: Changed stale.volume to stale.volume() (method call on inner class).
 *
 *   3. ConcurrentModificationException: mutating openCandles while iterating.
 *      FIXED: Collect stale keys first, remove outside iteration.
 *
 *   4. No daily reset → stale volumeAtOpen values persisted across days.
 *      FIXED: @Scheduled reset at 9:00 IST.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CandleAggregatorService {

    private final ApplicationEventPublisher publisher;

    // key = instrumentToken + ":" + timeframe + ":" + periodStartEpoch
    private final Map<String, OpenCandle> openCandles = new ConcurrentHashMap<>();

    // ── Tick processing ───────────────────────────────────────────────────

    @EventListener
    @Async("tradingExecutor")
    public void onTick(TickReceivedEvent tick) {
        BigDecimal ltp = tick.getLastTradedPrice();
        if (ltp == null || ltp.compareTo(BigDecimal.ZERO) == 0) return;

        for (TimeFrame tf : TimeFrame.values()) {
            if (tf == TimeFrame.DAY) continue;

            Instant start  = align(tick.getTickTimestamp(), tf.minutes);
            String  prefix = tick.getInstrumentToken() + ":" + tf.name() + ":";
            String  key    = prefix + start.getEpochSecond();

            openCandles.compute(key, (k, existing) ->
                    existing == null
                            ? new OpenCandle(tick, start, tf)
                            : existing.update(tick)
            );

            // BUG 3 FIX: collect stale keys BEFORE modifying map
            closeStaleCandlesFor(tick.getInstrumentToken(), tf, start, prefix);
        }
    }

    // ── Stale candle publication ──────────────────────────────────────────

    private void closeStaleCandlesFor(long token, TimeFrame tf,
                                      Instant currentStart, String prefix) {
        // Step 1: collect stale keys without modifying map
        List<String> staleKeys = new ArrayList<>();
        for (String k : openCandles.keySet()) {
            if (!k.startsWith(prefix)) continue;
            long epoch = Long.parseLong(k.substring(prefix.length()));
            if (epoch < currentStart.getEpochSecond()) {
                staleKeys.add(k);
            }
        }

        // Step 2: remove and publish OUTSIDE the iteration loop
        for (String k : staleKeys) {
            OpenCandle stale = openCandles.remove(k);
            if (stale != null) {
                Candle candle = stale.build();
                log.debug("[AGG] Candle complete: {} {} O={} H={} L={} C={} V={}",
                        stale.symbol, tf.zerodhaInterval,
                        stale.open, stale.high, stale.low, stale.close,
                        stale.volume()); // BUG 2 FIX: volume() is a method call
                publisher.publishEvent(new CandleCompleteEvent(this, candle));
            }
        }
    }

    // ── Period alignment ──────────────────────────────────────────────────

    private Instant align(Instant t, int mins) {
        long sec = t.getEpochSecond();
        long p   = (long) mins * 60;
        return Instant.ofEpochSecond((sec / p) * p);
    }

    // ── Daily reset ───────────────────────────────────────────────────────

    @Scheduled(cron = "0 0 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyReset() {
        openCandles.clear();
        log.info("[AGG] Daily reset — all open candles cleared");
    }

    // ── OpenCandle ────────────────────────────────────────────────────────

    /**
     * Tracks OHLCV for one in-progress candle period.
     *
     * VOLUME FIX (BUG 1):
     *   Zerodha ticks send volumeTradedToday = cumulative day total.
     *   Per-candle volume = currentDayVolume - volumeAtOpen
     *   volumeAtOpen = captured from the FIRST tick of this candle period.
     *
     * COMPILE FIX (BUG 2):
     *   volume() is a method (not a field) because it is computed dynamically.
     *   CandleAggregatorService.closeStaleCandlesFor() calls stale.volume()
     *   (method call), not stale.volume (field access).
     */
    static class OpenCandle {
        final long      token;
        final String    symbol;
        final TimeFrame tf;
        final Instant   start;

        BigDecimal open, high, low, close;

        final long volumeAtOpen;      // cumulative day volume when candle period opened
        long       currentDayVolume;  // latest cumulative day volume

        OpenCandle(TickReceivedEvent t, Instant start, TimeFrame tf) {
            this.token            = t.getInstrumentToken();
            this.symbol           = t.getTradingSymbol();
            this.tf               = tf;
            this.start            = start;
            this.open             = t.getLastTradedPrice();
            this.high             = t.getLastTradedPrice();
            this.low              = t.getLastTradedPrice();
            this.close            = t.getLastTradedPrice();
            this.volumeAtOpen     = t.getVolumeTradedToday();   // BUG 1 FIX: capture at open
            this.currentDayVolume = t.getVolumeTradedToday();
        }

        OpenCandle update(TickReceivedEvent t) {
            BigDecimal ltp = t.getLastTradedPrice();
            if (ltp.compareTo(high) > 0) high = ltp;
            if (ltp.compareTo(low)  < 0) low  = ltp;
            close           = ltp;
            currentDayVolume = t.getVolumeTradedToday();
            return this;
        }

        /**
         * BUG 1 FIX: Per-candle volume as delta from start of period.
         * Math.max(0, ...) guards against reconnect anomalies.
         */
        long volume() {
            return Math.max(0L, currentDayVolume - volumeAtOpen);
        }

        Candle build() {
            return Candle.builder()
                    .instrumentToken(token)
                    .tradingSymbol(symbol)
                    .timeframe(tf.zerodhaInterval)
                    .open(open)
                    .high(high)
                    .low(low)
                    .close(close)
                    .volume(volume())   // BUG 1 FIX: delta volume
                    .candleTime(start)
                    .complete(true)     // only complete candles published here
                    .build();
        }
    }
}