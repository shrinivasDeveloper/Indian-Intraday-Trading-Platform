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
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service @Slf4j @RequiredArgsConstructor
public class CandleAggregatorService {

    private final ApplicationEventPublisher publisher;
    private final Map<String, OpenCandle>   openCandles = new ConcurrentHashMap<>();

    @EventListener
    @Async("tradingExecutor")
    public void onTick(TickReceivedEvent tick) {
        for (TimeFrame tf : TimeFrame.values()) {
            if (tf == TimeFrame.DAY) continue;
            Instant start = align(tick.getTickTimestamp(), tf.minutes);
            String  key   = tick.getInstrumentToken() + ":" + tf.name() + ":" + start.getEpochSecond();

            openCandles.compute(key, (k, existing) ->
                existing == null
                    ? new OpenCandle(tick, start, tf)
                    : existing.update(tick));

            closeStaleCandlesFor(tick.getInstrumentToken(), tf, start);
        }
    }

    private void closeStaleCandlesFor(long token, TimeFrame tf, Instant currentStart) {
        String prefix = token + ":" + tf.name() + ":";
        // Collect stale keys first — never mutate while iterating
        List<String> staleKeys = new ArrayList<>();
        for (Map.Entry<String, OpenCandle> entry : openCandles.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) continue;
            long epoch = Long.parseLong(entry.getKey().substring(prefix.length()));
            if (epoch < currentStart.getEpochSecond()) staleKeys.add(entry.getKey());
        }
        for (String k : staleKeys) {
            OpenCandle stale = openCandles.remove(k);
            if (stale != null)
                publisher.publishEvent(new CandleCompleteEvent(this, stale.build()));
        }
    }

    private Instant align(Instant t, int mins) {
        long sec = t.getEpochSecond();
        long p   = (long) mins * 60;
        return Instant.ofEpochSecond((sec / p) * p);
    }

    static class OpenCandle {
        final long token; final String symbol;
        final TimeFrame tf; final Instant start;
        BigDecimal open, high, low, close;
        long volume;

        OpenCandle(TickReceivedEvent t, Instant s, TimeFrame tf) {
            token  = t.getInstrumentToken();
            symbol = t.getTradingSymbol();
            this.tf = tf; start = s;
            open = high = low = close = t.getLastTradedPrice();
            volume = t.getVolumeTradedToday();  // already long from event
        }

        OpenCandle update(TickReceivedEvent t) {
            BigDecimal ltp = t.getLastTradedPrice();
            if (ltp.compareTo(high) > 0) high = ltp;
            if (ltp.compareTo(low)  < 0) low  = ltp;
            close  = ltp;
            volume = t.getVolumeTradedToday();
            return this;
        }

        Candle build() {
            return Candle.builder()
                .instrumentToken(token).tradingSymbol(symbol)
                .timeframe(tf.zerodhaInterval)
                .open(open).high(high).low(low).close(close)
                .volume(volume).candleTime(start).complete(true)
                .build();
        }
    }
}
