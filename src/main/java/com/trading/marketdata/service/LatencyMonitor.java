package com.trading.marketdata.service;

import com.trading.events.CandleCompleteEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * LatencyMonitor — System health guardian for live candle processing.
 *
 * BUG FIXED (WARNING → now zero-warning):
 *   Lombok @Getter on a class generates isStale() for boolean field "stale".
 *   But we also have an explicit public method isStale() which conflicts.
 *   COMPILER WARNING: "Not generating isStale(): A method with that name already exists"
 *
 *   FIX: Remove @Getter from class level. Add explicit @Getter only on non-boolean
 *   fields that need getters. For the boolean "stale" field, our explicit
 *   isStale() method is the getter — no duplicate generated.
 *
 * HOW IT WORKS:
 *   Every completed 5m candle → recordCandleReceived() → update lastCandleTime.
 *   Every 5 seconds → checkLatency() compares now vs lastCandleTime.
 *   If gap > STALE_THRESHOLD_MS (60s) → stale = true → trades blocked.
 *   Auto-recovers when data resumes.
 *
 * INTEGRATION:
 *   StrategyEvaluatorService: if (latencyMonitor.isStale()) return;
 *   PaperTradeManagementService: passes through (SL monitoring continues even when stale)
 */
@Service
@Slf4j
public class LatencyMonitor {

    private static final ZoneId IST                    = ZoneId.of("Asia/Kolkata");
    private static final long   STALE_THRESHOLD_MS     = 60_000L;
    private static final long   CRITICAL_THRESHOLD_MS  = 120_000L;

    private final AtomicReference<Instant> lastCandleTime   = new AtomicReference<>(null);
    private final AtomicLong               candlesProcessed = new AtomicLong(0);

    // BUG FIX: No class-level @Getter. Explicit methods below prevent
    // Lombok duplicate-method warning ("Not generating isStale()")
    private volatile boolean stale    = false;
    private volatile boolean critical = false;
    private volatile long    lagMs    = 0;
    private volatile String  status   = "WAITING";

    // ── Event listener — update on every complete 5m candle ──────────────

    @EventListener
    @Async("tradingExecutor")
    public void onCandle(CandleCompleteEvent event) {
        if (!"5minute".equals(event.getCandle().getTimeframe())) return;
        if (!event.getCandle().isComplete()) return;
        lastCandleTime.set(Instant.now());
        candlesProcessed.incrementAndGet();
    }

    // ── Health check — runs every 5 seconds ──────────────────────────────

    @Scheduled(fixedDelay = 5000)
    public void checkLatency() {
        LocalTime now = LocalTime.now(IST);

        // Outside market hours → not stale
        if (now.isBefore(LocalTime.of(9, 15)) || now.isAfter(LocalTime.of(15, 35))) {
            stale    = false;
            critical = false;
            lagMs    = 0;
            status   = "MARKET_CLOSED";
            return;
        }

        Instant last = lastCandleTime.get();
        if (last == null) {
            if (now.isAfter(LocalTime.of(9, 40))) {
                stale  = true;
                lagMs  = -1;
                status = "NO_DATA_RECEIVED";
                log.warn("[LATENCY] No candle received after 9:40 AM — marking STALE");
            }
            return;
        }

        long    gapMs    = Instant.now().toEpochMilli() - last.toEpochMilli();
        boolean wasStale = stale;
        lagMs = gapMs;

        if (gapMs > CRITICAL_THRESHOLD_MS) {
            stale    = true;
            critical = true;
            status   = String.format("CRITICAL (%.0fs lag)", gapMs / 1000.0);
            log.error("[LATENCY] CRITICAL LAG {}s — ALL TRADES BLOCKED. Check WebSocket.", gapMs / 1000);
        } else if (gapMs > STALE_THRESHOLD_MS) {
            stale    = true;
            critical = false;
            status   = String.format("STALE (%.0fs lag)", gapMs / 1000.0);
            if (!wasStale) {
                log.warn("[LATENCY] System STALE: {}s since last candle — trades blocked", gapMs / 1000);
            }
        } else {
            stale    = false;
            critical = false;
            status   = String.format("LIVE (%.1fs)", gapMs / 1000.0);
            if (wasStale) {
                log.info("[LATENCY] System recovered from STALE — trades re-enabled (lag={}ms)", gapMs);
            }
        }
    }

    // ── Explicit public getters (NO Lombok @Getter — avoids duplicate warning) ──

    /**
     * Returns true when candle data is delayed > 60 seconds.
     * StrategyEvaluatorService checks this before executing any trade.
     */
    public boolean isStale()    { return stale; }

    public boolean isCritical() { return critical; }
    public long    getLagMs()   { return lagMs; }
    public String  getStatus()  { return status; }
    public long    getCandlesProcessed() { return candlesProcessed.get(); }

    /** Summary record for dashboard display */
    public LatencySummary getSummary() {
        Instant last = lastCandleTime.get();
        return new LatencySummary(
                stale, critical, lagMs,
                last != null ? last.atZone(IST).toLocalTime().toString() : "None",
                status,
                candlesProcessed.get()
        );
    }

    public record LatencySummary(
            boolean isStale,
            boolean isCritical,
            long    lagMs,
            String  lastCandleTime,
            String  status,
            long    candlesProcessed
    ) {}
}