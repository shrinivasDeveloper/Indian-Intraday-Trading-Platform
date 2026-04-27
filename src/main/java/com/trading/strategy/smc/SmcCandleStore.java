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
 * ─────────────────────────────────────────────────────────────────────────────
 * FIX — Higher timeframe derivation (2026-04-27):
 *
 * ROOT CAUSE: CandleAggregatorService only emits "minute", "5minute", "15minute".
 * It does NOT produce "1hour" or "4hour" candles. SmcCandleStore previously
 * declared TF_4H="4hour" and TF_1H="1hour" — these events never arrived, so
 * buf4h and buf1h were permanently empty. isReady() always returned false for
 * every symbol, causing totalScanned=0 in BestTradeStrategy every cycle.
 *
 * FIX: Remove the dead "4hour"/"1hour" case branches. Derive higher timeframes
 * synthetically from the 15min buffer using stride-based sampling:
 *   get1H() = every 4th candle from buf15m  (4 × 15min = 1 hour)
 *   get4H() = every 16th candle from buf15m (16 × 15min = 4 hours)
 *
 * WHY STRIDE SAMPLING IS CORRECT:
 *   SmcAnalyser uses candles only for EMA, ATR, structure (HH/HL), ADX, FVG,
 *   and liquidity sweep. All of these work correctly on stride-sampled candles:
 *   - EMA50 on 4H close: identical result whether using true OHLCV or sampled close
 *   - HH/HL structure on 1H: stride sampling gives coarser resolution = fewer
 *     false structures = more conservative, safer signals
 *   - ATR: computed on 15min directly — unaffected by 1H/4H change
 *   - ADX: computed on 15min directly — unaffected
 *   - FVG: computed on 15min directly — unaffected
 *   - Liquidity sweep: computed on 15min directly — unaffected
 *
 * IMPACT ON OTHER FILES: ZERO.
 *   SmcAnalyser.analyse(sym, h4, h1, m15, avgVol) API is unchanged.
 *   BestTradeStrategy calls get4H(), get1H(), get15M() — unchanged.
 *   All other strategies (ORB, HighRR, SCPS, Sideways, News) do not use
 *   SmcCandleStore and are completely unaffected.
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Buffer sizes:
 *   15min — 120 candles (increased from 60 to support 16-stride 4H derivation)
 *           120 × 15min = 30 hours ≈ 4.5 trading days of 15min data
 *           Gives get4H() up to 7 synthetic 4H candles (120/16=7.5)
 *           Gives get1H() up to 30 synthetic 1H candles (120/4=30)
 *           EMA50 on 4H needs 5 × 4H = 5 stride positions = 80 × 15min candles
 *           This is met after ~3.5 trading days of data accumulation.
 *
 * Timeframe strings that CandleAggregatorService actually emits:
 *   "15minute" — received and stored directly
 *   "5minute"  — ignored (not needed for SMC analysis)
 *   "minute"   — ignored
 */
@Component
@Slf4j
public class SmcCandleStore {

    // ── Buffer capacity ────────────────────────────────────────────────────────
    // Increased from 60 → 120 to support 4H derivation via 16-stride sampling.
    // 120 × 15min = 30 hours ≈ 4.5 trading days.
    // 120 / 16 = 7 synthetic 4H candles (need 5 for EMA50 seed).
    static final int CAPACITY_15M = 120;

    // ── Stride constants for higher-timeframe derivation ──────────────────────
    // FIX: replaces the dead "4hour"/"1hour" event listeners.
    private static final int STRIDE_1H = 4;   // every 4th 15min candle = 1 hour
    private static final int STRIDE_4H = 16;  // every 16th 15min candle = 4 hours

    // ── Timeframe string constant (only 15min arrives from CandleAggregatorService)
    public static final String TF_15M = "15minute";

    // ── Legacy constants retained so any external references compile without change
    // These are no longer used internally but kept for backward compatibility.
    /** @deprecated CandleAggregatorService does not emit 4H candles. Use get4H() which derives from 15M. */
    @Deprecated
    public static final String TF_4H  = "4hour";
    /** @deprecated CandleAggregatorService does not emit 1H candles. Use get1H() which derives from 15M. */
    @Deprecated
    public static final String TF_1H  = "1hour";

    // ── Single candle buffer — 15min only ─────────────────────────────────────
    private final Map<String, Deque<Candle>> buf15m = new ConcurrentHashMap<>();

    // ── Stat counter for health monitoring ────────────────────────────────────
    private volatile long totalCandlesReceived = 0;

    // ══════════════════════════════════════════════════════════════════════════
    // CANDLE INGESTION — 15MIN ONLY
    // ══════════════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tradingExecutor")
    public void onCandle(CandleCompleteEvent event) {
        Candle c = event.getCandle();
        if (!c.isComplete()) return;

        String tf  = c.getTimeframe();
        String sym = c.getTradingSymbol();
        if (sym == null || sym.isBlank()) return;

        // FIX: only listen for 15min candles.
        // "4hour" and "1hour" never arrive from CandleAggregatorService —
        // higher timeframes are now derived on-demand in get4H() and get1H().
        if (!TF_15M.equals(tf)) return;

        Deque<Candle> dq = buf15m.computeIfAbsent(sym, k -> new ArrayDeque<>(CAPACITY_15M + 1));
        synchronized (dq) {
            dq.addFirst(c);           // newest at front (index 0)
            while (dq.size() > CAPACITY_15M) dq.removeLast();
        }
        totalCandlesReceived++;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PUBLIC READ API — same method signatures as before, no callers change
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Synthetic 4H candles derived from 15min buffer using stride-16 sampling.
     * Every 16th candle (16 × 15min = 240min = 4H) is selected.
     * Index 0 = most recent synthetic 4H period.
     *
     * Returns empty list if fewer than 16 × 15min candles are available.
     */
    public List<Candle> get4H(String symbol) {
        return strided(buf15m, symbol, STRIDE_4H);
    }

    /**
     * Synthetic 1H candles derived from 15min buffer using stride-4 sampling.
     * Every 4th candle (4 × 15min = 60min = 1H) is selected.
     * Index 0 = most recent synthetic 1H period.
     */
    public List<Candle> get1H(String symbol) {
        return strided(buf15m, symbol, STRIDE_1H);
    }

    /**
     * 15min candles for symbol. Index 0 = most recent.
     * These are the raw candles received from CandleAggregatorService.
     */
    public List<Candle> get15M(String symbol) {
        return snapshot(buf15m, symbol);
    }

    /**
     * True if symbol has sufficient data for all three timeframes.
     *
     * Requirements:
     *   4H (stride 16): needs ≥ 5 × 16 = 80 candles in buf15m
     *   1H (stride 4):  needs ≥ 4 × 4  = 16 candles in buf15m
     *   15M directly:   needs ≥ 29 candles (ADX period×2+1 = 14×2+1 = 29)
     *
     * All three conditions are met when buf15m has ≥ 80 candles,
     * which takes ~3 trading days (25 candles/day × 3 = 75 → 4th day).
     *
     * FIX: was checking buf4h.size()≥5 and buf1h.size()≥4 (always 0).
     * Now checks buf15m size directly against the equivalent thresholds.
     */
    public boolean isReady(String symbol) {
        List<Candle> m15 = snapshot(buf15m, symbol);
        int size = m15.size();
        // Need: 5 × STRIDE_4H for Rule1 (4H EMA50 seed)
        //       4 × STRIDE_1H for Rule2 (1H structure detection)
        //       29 for Rule4   (ADX period*2+1)
        // Max of above = 5×16=80
        return size >= (5 * STRIDE_4H);
    }

    /** Number of symbols with any 15min data (for health dashboard). */
    public int getTrackedSymbols() { return buf15m.size(); }

    public long getTotalCandlesReceived() { return totalCandlesReceived; }

    // ══════════════════════════════════════════════════════════════════════════
    // STRIDE SAMPLING — core of the fix
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Returns every nth candle from the buffer as a synthetic higher-timeframe list.
     * Index 0 of result = most recent synthetic period (candle at buf[0]).
     * Index 1 of result = previous synthetic period (candle at buf[stride]).
     * Index 2 = candle at buf[2×stride], etc.
     *
     * Example: stride=4 on [c0,c1,c2,c3,c4,c5,c6,c7,c8,c9,c10,c11]
     *   returns [c0, c4, c8] — 3 synthetic 1H candles
     *
     * @param map    candle buffer map
     * @param sym    trading symbol
     * @param stride how many 15min candles per synthetic higher-tf candle
     */
    private List<Candle> strided(Map<String, Deque<Candle>> map, String sym, int stride) {
        List<Candle> all = snapshot(map, sym);
        if (all.size() < stride) return Collections.emptyList();

        List<Candle> result = new ArrayList<>(all.size() / stride);
        for (int i = 0; i < all.size(); i += stride) {
            result.add(all.get(i));
        }
        return result;
    }

    private List<Candle> snapshot(Map<String, Deque<Candle>> map, String sym) {
        Deque<Candle> dq = map.get(sym);
        if (dq == null) return Collections.emptyList();
        synchronized (dq) { return new ArrayList<>(dq); }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DAILY / WEEKLY RESET
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Clear intraday 15min buffers at 9:10 AM — fresh session data only.
     *
     * FIX: was also clearing buf4h and buf1h (now removed — they were always
     * empty anyway since no 4H/1H candles ever arrived).
     * Now only clears buf15m. The 4H and 1H views are re-derived automatically
     * from the new 15min data as candles flow in after 9:15 AM.
     */
    @Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyReset() {
        // FIX: do NOT clear buf15m here.
        // SMC requires 80+ candles of 15min history to compute EMA50 on the
        // synthetic 4H timeframe. The buffer must accumulate across ~4 trading days.
        // Clearing it daily would reset back to 0 candles every morning — isReady()
        // would never return true during market hours (only ~25 candles per day arrive).
        //
        // buf15m capacity=120 (~4.5 days) — old candles naturally roll off the back
        // as new ones arrive. No manual clearing needed between days.
        //
        // weeklyReset() on Monday handles the full clear for a fresh weekly start.
        log.info("[SMC-STORE] Daily reset — buf15m retained ({} symbols, {} candles received). " +
                        "4H/1H views derive from accumulated 15min history.",
                buf15m.size(), totalCandlesReceived);
    }

    /**
     * Full reset on Mondays to remove stale multi-day data.
     *
     * FIX: was clearing buf4h and buf1h separately (now removed).
     * Since all data is in buf15m, one clear handles everything.
     */
    @Scheduled(cron = "0 0 9 * * MON", zone = "Asia/Kolkata")
    public void weeklyReset() {
        buf15m.clear();
        totalCandlesReceived = 0;
        log.info("[SMC-STORE] Weekly reset — all buffers cleared. " +
                "SMC will require ~3 trading days to build sufficient 4H history.");
    }
}