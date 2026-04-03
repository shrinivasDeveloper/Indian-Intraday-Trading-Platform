package com.trading.analysis.service;

import com.trading.events.CandleCompleteEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * RvolService — Relative Volume calculation for every stock.
 *
 * RVOL = currentCandleVolume / averageVolumeForThisTimeSlot(last 5 days)
 *
 * METHODS EXPOSED (all required by callers):
 *   getRvolNow(symbol, currentVolume)         → used by ProbabilityEngine, MarketModeEngine
 *   getRvol(symbol, slot, currentVolume)      → used by AutoModeStrategy, RangeBreakoutStrategy, PullbackDetectionService
 *   rvolLabel(rvol)                           → used by all 3 strategies for logging
 *   getRvolSimple(symbol, volume, history)    → fallback
 *
 * BUGS FIXED vs original:
 *   1. getRvol() / rvolLabel() were removed in a previous refactor, breaking
 *      AutoModeStrategy, RangeBreakoutStrategy, PullbackDetectionService.
 *      RE-ADDED: getRvol(String, LocalTime, long) and rvolLabel(double).
 *
 *   2. Default 0.0 when no history → blocked all volume gates on day 1.
 *      FIXED: Default = 1.0 (neutral, does not block).
 *
 *   3. ConcurrentModificationException on slotHistory under async load.
 *      FIXED: CopyOnWriteArrayList per slot.
 *
 *   4. No minimum data points check → single outlier day biased RVOL wildly.
 *      FIXED: MIN_DATA_POINTS = 3 required before RVOL is meaningful.
 */
@Service
@Slf4j
public class RvolService {

    private static final ZoneId  IST              = ZoneId.of("Asia/Kolkata");
    private static final int     MAX_HISTORY_DAYS = 5;
    private static final int     MIN_DATA_POINTS  = 3;
    private static final double  DEFAULT_RVOL     = 1.0; // neutral when no history

    /**
     * Per-symbol, per-time-slot volume history.
     * Key: "SYMBOL:HH:MM"  e.g. "RELIANCE:09:15"
     * Value: list of volumes for that slot across recent trading days
     */
    private final Map<String, CopyOnWriteArrayList<Long>> slotHistory = new ConcurrentHashMap<>();

    /** Latest candle volume per symbol (for getRvolNow without slot) */
    private final Map<String, Long> latestVolume = new ConcurrentHashMap<>();

    // ═══════════════════════════════════════════════════════════════════════
    // PRIMARY API — used by strategies
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Time-slot aware RVOL — used by AutoModeStrategy, RangeBreakoutStrategy,
     * PullbackDetectionService.
     *
     * Compares currentVolume to the average volume of the SAME 5-min slot
     * across the last 5 trading days.
     *
     * 1.5× at 9:45 AM (opening rush) = normal = no edge.
     * 1.5× at 1:30 PM (lunch dead zone) = rare = institutional.
     *
     * @param symbol        trading symbol
     * @param slot          LocalTime of the candle (e.g. 09:15, 09:20)
     * @param currentVolume volume of the current candle
     * @return RVOL (1.0 = average; >1 = above average)
     */
    public double getRvol(String symbol, LocalTime slot, long currentVolume) {
        if (currentVolume <= 0) return DEFAULT_RVOL;

        String slotStr = String.format("%02d:%02d", slot.getHour(), (slot.getMinute() / 5) * 5);
        String key     = symbol + ":" + slotStr;

        CopyOnWriteArrayList<Long> history = slotHistory.get(key);
        if (history == null || history.size() < MIN_DATA_POINTS) {
            log.debug("[RVOL] {} slot={} insufficient history ({} pts) → default {}",
                    symbol, slotStr, history == null ? 0 : history.size(), DEFAULT_RVOL);
            return DEFAULT_RVOL;
        }

        double avg = history.stream().mapToLong(Long::longValue).average().orElse(0);
        if (avg <= 0) return DEFAULT_RVOL;

        double rvol = (double) currentVolume / avg;
        log.debug("[RVOL] {} slot={} cur={} avg={:.0f} rvol={:.2f}", symbol, slotStr, currentVolume, avg, rvol);
        return rvol;
    }

    /**
     * Non-slot RVOL — used by ProbabilityEngine, MarketModeEngine, BankNiftyModeEngine,
     * StockRankingEngine.
     *
     * Uses current time to determine slot internally.
     *
     * @param symbol        trading symbol
     * @param currentVolume volume of the current candle
     * @return RVOL (1.0 = average)
     */
    public double getRvolNow(String symbol, long currentVolume) {
        LocalTime now = LocalTime.now(IST);
        return getRvol(symbol, now, currentVolume);
    }

    /**
     * Human-readable RVOL label for log messages.
     * Used by AutoModeStrategy, RangeBreakoutStrategy, PullbackDetectionService.
     *
     * Examples:
     *   2.3 → "2.3× (VERY HIGH)"
     *   1.6 → "1.6× (HIGH)"
     *   1.2 → "1.2× (ELEVATED)"
     *   0.8 → "0.8× (LOW)"
     *
     * @param rvol relative volume value
     * @return formatted string for log output
     */
    public String rvolLabel(double rvol) {
        String tag;
        if      (rvol >= 2.0) tag = "VERY HIGH";
        else if (rvol >= 1.5) tag = "HIGH";
        else if (rvol >= 1.2) tag = "ELEVATED";
        else if (rvol >= 1.0) tag = "AVERAGE";
        else if (rvol >= 0.7) tag = "LOW";
        else                  tag = "VERY LOW";
        return String.format("%.2f× (%s)", rvol, tag);
    }

    /**
     * Simple rolling average RVOL (no time-slot awareness).
     * Used as fallback when slot history is insufficient.
     *
     * @param symbol        trading symbol (for logging)
     * @param currentVolume current candle volume
     * @param recentVolumes list of recent candle volumes (last 20)
     * @return RVOL
     */
    public double getRvolSimple(String symbol, long currentVolume, List<Long> recentVolumes) {
        if (currentVolume <= 0 || recentVolumes == null || recentVolumes.isEmpty())
            return DEFAULT_RVOL;
        double avg = recentVolumes.stream().mapToLong(Long::longValue).average().orElse(0);
        if (avg <= 0) return DEFAULT_RVOL;
        return (double) currentVolume / avg;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // FEED — build slot history from completed candles
    // ═══════════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tradingExecutor")
    public void onCandle(CandleCompleteEvent event) {
        if (!"5minute".equals(event.getCandle().getTimeframe())) return;
        if (!event.getCandle().isComplete()) return; // skip forming candles

        String symbol = event.getCandle().getTradingSymbol();
        long   volume = event.getCandle().getVolume();
        if (volume <= 0) return;

        String slot = getSlotFor(event.getCandle().getCandleTime());
        String key  = symbol + ":" + slot;

        CopyOnWriteArrayList<Long> history =
                slotHistory.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
        history.add(volume);

        // Keep only last MAX_HISTORY_DAYS data points per slot
        while (history.size() > MAX_HISTORY_DAYS) {
            history.remove(0);
        }

        latestVolume.put(symbol, volume);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════

    private String getSlotFor(java.time.Instant candleTime) {
        if (candleTime == null) {
            LocalTime now = LocalTime.now(IST);
            return String.format("%02d:%02d", now.getHour(), (now.getMinute() / 5) * 5);
        }
        LocalTime t    = candleTime.atZone(IST).toLocalTime();
        int       mins = (t.getMinute() / 5) * 5;
        return String.format("%02d:%02d", t.getHour(), mins);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Scheduled maintenance
    // ═══════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyOpenLog() {
        int symbols = (int) slotHistory.keySet().stream()
                .map(k -> k.split(":")[0]).distinct().count();
        log.info("[RVOL] Market open. {} symbols with slot history ({} total slots)",
                symbols, slotHistory.size());
    }

    @Scheduled(cron = "0 35 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void endOfDayLog() {
        int symbols = (int) slotHistory.keySet().stream()
                .map(k -> k.split(":")[0]).distinct().count();
        log.info("[RVOL] End of day. {} symbols tracked across {} time slots",
                symbols, slotHistory.size());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Dashboard
    // ═══════════════════════════════════════════════════════════════════════

    public int  getTrackedSymbolCount() {
        return (int) slotHistory.keySet().stream()
                .map(k -> k.split(":")[0]).distinct().count();
    }

    public long getLatestVolume(String symbol) {
        return latestVolume.getOrDefault(symbol, 0L);
    }
}