// ============================================================
// NEW FILE — v7.0 FIX 2
// Path: src/main/java/com/trading/marketdata/service/PartialCandleProcessor.java
// PURPOSE: v7.0 says "DO NOT wait for candle close — if (candle.isForming()) evaluateSignal()"
//          This service processes live tick data into a forming (partial) candle
//          and publishes a CandleCompleteEvent with complete=false so strategies
//          can evaluate signals in real time without waiting for 5-min close.
//
//   v7.0 FIX 1: Use @Scheduled(fixedRate = 5000) for fast data fetch
//               OR WebSocket live ticks (already done via onTick)
//   v7.0 FIX 2: Evaluate on partial candle (this file)
// ============================================================
package com.trading.marketdata.service;

import com.trading.domain.Candle;
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
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PartialCandleProcessor — v7.0 FIX 2.
 *
 * ROOT PROBLEM (v7.0):
 *   The system only evaluated strategies on CLOSED candles (CandleCompleteEvent
 *   with complete=true). On a 5-minute candle, this means the earliest possible
 *   signal is at 9:20 (first complete 5m candle closes). ORB setups forming at
 *   9:16 are completely missed.
 *
 * FIX:
 *   Every 5 seconds (fixedRate = 5000), this service:
 *   1. Takes the latest tick for each symbol
 *   2. Builds a forming (partial) candle with current OHLCV
 *   3. Publishes CandleCompleteEvent with complete=false
 *   Strategies receive this and can evaluate the forming candle.
 *
 * STRATEGY INTEGRATION:
 *   Strategies that support partial candles check candle.complete() == false
 *   and apply looser conditions (e.g., ignore body quality for partial candles).
 *   The 7-Gate Scanner's Gate 4 (tick-based) already handles this via onTick().
 *   ORBStrategy and AutoModeStrategy get forming candles via this service.
 *
 * GUARD:
 *   Partial candle events are throttled to 1 per symbol per 5 seconds.
 *   StrategyEvaluatorService still uses the full candle (complete=true) for
 *   most scoring — partial is only for early signal detection.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PartialCandleProcessor {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final ApplicationEventPublisher publisher;
    private final LatencyMonitor            latencyMonitor;

    // Latest tick data per symbol: symbol → latest forming candle state
    private final Map<String, FormingCandle> formingCandles = new ConcurrentHashMap<>();

    private record FormingCandle(
            long       token,
            String     symbol,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            long       volume,
            Instant    candleStart
    ) {}

    // ── Tick listener — update forming candle on each tick ───────────────────

    @EventListener
    @Async("tradingExecutor")
    public void onTick(TickReceivedEvent tick) {
        String sym = tick.getTradingSymbol();
        BigDecimal ltp = tick.getLastTradedPrice();
        if (ltp == null || ltp.compareTo(BigDecimal.ZERO) == 0) return;

        formingCandles.compute(sym, (k, existing) -> {
            if (existing == null) {
                // New candle — first tick of the 5-min window
                return new FormingCandle(
                        tick.getInstrumentToken(), sym,
                        ltp, ltp, ltp, ltp,
                        tick.getLastTradedQuantity(),
                        Instant.now()
                );
            }
            // Update forming candle
            return new FormingCandle(
                    existing.token(), sym,
                    existing.open(),
                    ltp.compareTo(existing.high()) > 0 ? ltp : existing.high(),
                    ltp.compareTo(existing.low())  < 0 ? ltp : existing.low(),
                    ltp,
                    existing.volume() + tick.getLastTradedQuantity(),
                    existing.candleStart()
            );
        });
    }

    // ── Publish partial candle events every 5 seconds ────────────────────────

    @Scheduled(fixedRate = 5000)
    public void publishPartialCandles() {
        // Don't publish during market closed, pre-open or if system is stale
        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(LocalTime.of(9, 15)) || now.isAfter(LocalTime.of(15, 35))) return;
        if (latencyMonitor.isStale()) return;
        if (formingCandles.isEmpty()) return;

        int published = 0;
        for (Map.Entry<String, FormingCandle> entry : formingCandles.entrySet()) {
            FormingCandle fc = entry.getValue();
            if (fc.close().compareTo(BigDecimal.ZERO) == 0) continue;

            // Build partial candle — complete=false signals "forming"
            Candle partial = Candle.builder()
                    .instrumentToken(fc.token())
                    .tradingSymbol(fc.symbol())
                    .timeframe("5minute")
                    .open(fc.open())
                    .high(fc.high())
                    .low(fc.low())
                    .close(fc.close())
                    .volume(fc.volume())
                    .candleTime(fc.candleStart())
                    .complete(false)  // ← KEY: marks this as a forming candle
                    .build();

            publisher.publishEvent(new CandleCompleteEvent(this, partial));
            published++;
        }

        if (published > 0) {
            log.debug("[PARTIAL] Published {} forming candle events", published);
        }
    }

    // ── Reset forming candles at each 5-min boundary ─────────────────────────

    @Scheduled(cron = "0 */5 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void resetOnCandleClose() {
        // Clear forming candles at each 5-min boundary so OHLCV resets correctly
        formingCandles.clear();
        log.debug("[PARTIAL] Forming candles reset at candle boundary");
    }

    @Scheduled(cron = "0 14 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyReset() {
        formingCandles.clear();
        log.info("[PARTIAL] Daily reset complete");
    }

    public int getFormingCandleCount() { return formingCandles.size(); }
}