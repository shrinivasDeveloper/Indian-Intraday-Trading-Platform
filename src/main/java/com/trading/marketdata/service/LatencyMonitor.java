package com.trading.marketdata.service;

import com.trading.events.CandleCompleteEvent;
import com.trading.events.TickReceivedEvent;
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
 * LatencyMonitor — Measures time since last TICK (not last candle close).
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * ROOT CAUSE FIX — Design bug causing false STALE every 4-5 minutes:
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * BROKEN DESIGN (previous version):
 *   lastDataTime updated ONLY on CandleCompleteEvent (every 5 minutes).
 *   STALE_THRESHOLD_MS = 60 seconds.
 *   Result: 60 seconds after every candle closes, lag exceeds threshold.
 *   The monitor trips STALE for the remaining ~4 minutes of every bar.
 *   → "DATA STALE — All trades BLOCKED" fires correctly but for wrong trigger.
 *   → Screenshot at 9:45 showing STALE (61s lag) = this bug, not dead WebSocket.
 *
 * CORRECT DESIGN (this version):
 *   lastDataTime updated on every TICK via onTick().
 *   During market hours, Zerodha sends 1-3 ticks per second per subscribed token.
 *   With 405 tokens subscribed, ticks arrive every millisecond.
 *   Lag will be < 2 seconds during a live session.
 *   STALE only fires when WebSocket actually disconnects or Zerodha goes down.
 *
 * THRESHOLD VALUES (corrected):
 *   STALE_THRESHOLD_MS   = 10,000ms (10s)  — no tick for 10s = real outage
 *   CRITICAL_THRESHOLD_MS = 30,000ms (30s)  — no tick for 30s = full disconnect
 *
 *   Previous values (60s/120s) were calibrated for candle-based monitoring.
 *   With tick-based monitoring, 10s of silence in live market = confirmed outage.
 *
 * CANDLE LISTENER RETAINED:
 *   onCandle() still counts completed candles for the "candlesProcessed" dashboard
 *   metric. It does NOT update lastDataTime (that's tick-only now).
 *
 * EXECUTOR ASSIGNMENT:
 *   onTick()   → @Async("tickExecutor")    — latency-critical, must not queue
 *   onCandle() → @Async("tradingExecutor") — counter only, low priority
 *
 * LOMBOK WARNING FIX (retained from previous version):
 *   No class-level @Getter. Explicit isStale(), isCritical() etc.
 *   Prevents: "Not generating isStale(): A method with that name already exists"
 *
 * INTEGRATION:
 *   StrategyEvaluatorService: if (latencyMonitor.isStale()) return; // skip signal
 *   DashboardController:      latencyMonitor.getSummary()
 */
@Service
@Slf4j
public class LatencyMonitor {

    private static final ZoneId IST                   = ZoneId.of("Asia/Kolkata");

    // ── CORRECTED thresholds — calibrated for tick-based (not candle-based) monitoring ──
    // 10 seconds of tick silence during market hours = real WebSocket outage
    private static final long STALE_THRESHOLD_MS    = 10_000L;   // 10s  (was 60s)
    private static final long CRITICAL_THRESHOLD_MS = 30_000L;   // 30s  (was 120s)

    /**
     * Updated on every incoming TICK — not just candle close.
     * Null until first tick arrives after market open.
     */
    private final AtomicReference<Instant> lastDataTime     = new AtomicReference<>(null);
    private final AtomicLong               ticksReceived    = new AtomicLong(0);
    private final AtomicLong               candlesProcessed = new AtomicLong(0);

    // No Lombok @Getter on class — avoids isStale() duplicate warning
    private volatile boolean stale    = false;
    private volatile boolean critical = false;
    private volatile long    lagMs    = 0;
    private volatile String  status   = "WAITING";

    // ════════════════════════════════════════════════════════════════════════
    // ROOT CAUSE FIX: Update on every TICK, not every candle close
    // Uses tickExecutor so this check is never delayed by strategy tasks
    // ════════════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tickExecutor")
    public void onTick(TickReceivedEvent tick) {
        lastDataTime.set(Instant.now());
        ticksReceived.incrementAndGet();
    }

    // ── Candle counter (for dashboard display only — does NOT affect staleness) ──

    @EventListener
    @Async("tradingExecutor")
    public void onCandle(CandleCompleteEvent event) {
        if (!"5minute".equals(event.getCandle().getTimeframe())) return;
        if (!event.getCandle().isComplete()) return;
        candlesProcessed.incrementAndGet();
        // NOTE: do NOT update lastDataTime here — that's tick-only now
    }

    // ════════════════════════════════════════════════════════════════════════
    // Health check — runs every 2 seconds
    // (was 5s — faster polling needed for 10s threshold)
    // ════════════════════════════════════════════════════════════════════════

    @Scheduled(fixedDelay = 2000)
    public void checkLatency() {
        LocalTime now = LocalTime.now(IST);

        // Outside market hours → always healthy
        if (now.isBefore(LocalTime.of(9, 15)) || now.isAfter(LocalTime.of(15, 35))) {
            stale    = false;
            critical = false;
            lagMs    = 0;
            status   = "MARKET_CLOSED";
            return;
        }

        Instant last = lastDataTime.get();

        // No tick yet — only flag as problem after 9:30 (first 15min = normal warm-up)
        if (last == null) {
            if (now.isAfter(LocalTime.of(9, 30))) {
                stale  = true;
                lagMs  = -1;
                status = "NO_TICK_RECEIVED";
                log.warn("[LATENCY] No tick received by 9:30 AM — WebSocket may not be connected");
            } else {
                status = "WARMING_UP";
            }
            return;
        }

        long    gapMs    = Instant.now().toEpochMilli() - last.toEpochMilli();
        boolean wasStale = stale;
        lagMs = gapMs;

        if (gapMs > CRITICAL_THRESHOLD_MS) {
            stale    = true;
            critical = true;
            status   = String.format("CRITICAL — no tick for %.0fs", gapMs / 1000.0);
            log.error("[LATENCY] CRITICAL: {}s since last tick — WebSocket disconnected. ALL TRADES BLOCKED.",
                    gapMs / 1000);

        } else if (gapMs > STALE_THRESHOLD_MS) {
            stale    = true;
            critical = false;
            status   = String.format("STALE — no tick for %.1fs", gapMs / 1000.0);
            if (!wasStale) {
                log.warn("[LATENCY] STALE: {}s since last tick — trades blocked", gapMs / 1000.0);
            }

        } else {
            stale    = false;
            critical = false;
            status   = String.format("LIVE (%.0fms lag)", (double) gapMs);
            if (wasStale) {
                log.info("[LATENCY] Recovered — tick lag now {}ms. Trades re-enabled.", gapMs);
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Public API — no Lombok @Getter to avoid isStale() duplicate warning
    // ════════════════════════════════════════════════════════════════════════

    /** Returns true when no tick has been received for > 10 seconds during market hours. */
    public boolean isStale()    { return stale; }

    public boolean isCritical() { return critical; }
    public long    getLagMs()   { return lagMs; }
    public String  getStatus()  { return status; }

    public long getTicksReceived()    { return ticksReceived.get(); }
    public long getCandlesProcessed() { return candlesProcessed.get(); }

    public LatencySummary getSummary() {
        Instant last = lastDataTime.get();
        return new LatencySummary(
                stale,
                critical,
                lagMs,
                last != null ? last.atZone(IST).toLocalTime().toString() : "None",
                status,
                candlesProcessed.get(),
                ticksReceived.get()
        );
    }

    public record LatencySummary(
            boolean isStale,
            boolean isCritical,
            long    lagMs,
            String  lastTickTime,
            String  status,
            long    candlesProcessed,
            long    ticksReceived
    ) {}
}