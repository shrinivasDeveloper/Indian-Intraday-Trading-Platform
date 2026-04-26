package com.trading.strategy.smc;

import com.trading.domain.Candle;
import com.trading.events.CandleCompleteEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SmcCandleStore — rolling candle buffer for three timeframes per symbol.
 *
 * Listens to CandleCompleteEvent (fired by CandleAggregatorService) and
 * routes each completed candle into the correct per-symbol deque.
 *
 * Buffer sizes:
 *   4hour  — 20 candles  (5 trading days × 2 candles/day ≈ full week lookback)
 *   1hour  — 24 candles  (3 trading days × 8 candles/day)
 *   15min  — 60 candles  (2 trading days × 26 candles/day)
 *
 * All reads are snapshot-safe: getCandlesFor() returns a new ArrayList copy.
 * Writes are ConcurrentHashMap + synchronized-deque to prevent torn reads.
 *
 * Timeframe strings (from CandleAggregatorService):
 *   "4hour"    — 4-hour candles
 *   "1hour"    — 1-hour candles (or "60minute" depending on aggregator config)
 *   "15minute" — 15-minute candles
 */
@Component
@Slf4j
public class SmcCandleStore {

    // ── Buffer capacities ─────────────────────────────────────────────────────
    static final int CAPACITY_4H  = 20;
    static final int CAPACITY_1H  = 24;
    static final int CAPACITY_15M = 60;

    // ── Timeframe string constants (must match CandleAggregatorService output) ─
    public static final String TF_4H  = "4hour";
    public static final String TF_1H  = "1hour";
    public static final String TF_15M = "15minute";

    // ── Candle buffers — newest candle is at index 0 (front of deque) ─────────
    private final Map<String, Deque<Candle>> buf4h  = new ConcurrentHashMap<>();
    private final Map<String, Deque<Candle>> buf1h  = new ConcurrentHashMap<>();
    private final Map<String, Deque<Candle>> buf15m = new ConcurrentHashMap<>();

    // ── Stat counters for health monitoring ───────────────────────────────────
    private volatile long totalCandlesReceived = 0;

    // ══════════════════════════════════════════════════════════════════════════
    // CANDLE INGESTION
    // ══════════════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tradingExecutor")
    public void onCandle(CandleCompleteEvent event) {
        Candle c = event.getCandle();
        if (!c.isComplete()) return;

        String tf  = c.getTimeframe();
        String sym = c.getTradingSymbol();
        if (sym == null || sym.isBlank()) return;

        switch (tf) {
            case TF_4H  -> push(buf4h,  sym, c, CAPACITY_4H,  "4H");
            case TF_1H  -> push(buf1h,  sym, c, CAPACITY_1H,  "1H");
            case TF_15M -> push(buf15m, sym, c, CAPACITY_15M, "15M");
            default     -> { /* other timeframes (5min, 1min) — ignore */ }
        }
        totalCandlesReceived++;
    }

    private void push(Map<String, Deque<Candle>> map, String sym, Candle c,
                      int cap, String label) {
        Deque<Candle> dq = map.computeIfAbsent(sym, k -> new ArrayDeque<>(cap + 1));
        synchronized (dq) {
            dq.addFirst(c);           // newest at front
            while (dq.size() > cap) dq.removeLast();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PUBLIC READ API
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Returns snapshot list of 4H candles for symbol.
     * Index 0 = most recent (closed) candle.
     * Returns empty list if no data yet.
     */
    public List<Candle> get4H(String symbol) {
        return snapshot(buf4h, symbol);
    }

    /** 1H candles for symbol. Index 0 = most recent. */
    public List<Candle> get1H(String symbol) {
        return snapshot(buf1h, symbol);
    }

    /** 15min candles for symbol. Index 0 = most recent. */
    public List<Candle> get15M(String symbol) {
        return snapshot(buf15m, symbol);
    }

    /** True if symbol has sufficient data for all three timeframes. */
    public boolean isReady(String symbol) {
        return get4H(symbol).size()  >= 5  &&   // need at least 5 for EMA50 seed
                get1H(symbol).size()  >= 4  &&   // need 4 for structure detection
                get15M(symbol).size() >= 15;     // need 15 for ADX + FVG
    }

    /** Number of symbols with any 15min data (for health dashboard). */
    public int getTrackedSymbols() { return buf15m.size(); }

    public long getTotalCandlesReceived() { return totalCandlesReceived; }

    private List<Candle> snapshot(Map<String, Deque<Candle>> map, String sym) {
        Deque<Candle> dq = map.get(sym);
        if (dq == null) return Collections.emptyList();
        synchronized (dq) { return new ArrayList<>(dq); }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DAILY RESET
    // ══════════════════════════════════════════════════════════════════════════

    /** Clear intraday 15min buffers at 9:10 AM — fresh session data only. */
    @Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyReset() {
        buf15m.clear();
        totalCandlesReceived = 0;
        log.info("[SMC-STORE] Daily reset — 15min buffers cleared. 4H/1H buffers retained.");
    }

    /** Full clear on Mondays to remove stale weekly data from 4H/1H. */
    @Scheduled(cron = "0 0 9 * * MON", zone = "Asia/Kolkata")
    public void weeklyReset() {
        buf4h.clear();
        buf1h.clear();
        log.info("[SMC-STORE] Weekly reset — all buffers cleared.");
    }
}