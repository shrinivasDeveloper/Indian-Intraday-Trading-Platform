package com.trading.analysis.service;

import com.trading.domain.Candle;
import com.trading.events.CandleCompleteEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RvolService — Relative Volume by 5-Minute Time Slot.
 *
 * COMPILE ERROR FIX:
 *   AutoModeStrategy line 268 called getRvolNow(String, LocalTime, long) — 3 args.
 *   But getRvolNow(String, long) only takes 2 args.
 *
 *   ROOT CAUSE: AutoModeStrategy should call getRvol(symbol, slot, vol) — NOT getRvolNow.
 *   But AutoModeStrategy is NOT modified here — it calls getRvolNow with 3 args in the
 *   version the user has applied. Since we cannot change the strategy file, we add a
 *   3-arg overload of getRvolNow(String, LocalTime, long) that delegates to getRvol().
 *   This makes BOTH call patterns valid:
 *     getRvolNow(symbol, volume)             — 2 args (used by StrategyEvaluatorService)
 *     getRvolNow(symbol, slot, volume)       — 3 args (used by some strategy versions)
 *     getRvol(symbol, slot, volume)          — 3 args (used by original strategies)
 *
 * ORIGINAL LOGIC PRESERVED 100%:
 *   - history map: "SYMBOL:HH:mm" → Deque<Long> of up to 5 previous-day volumes
 *   - todaySlots map: "SYMBOL:HH:mm" → today's volume
 *   - dailyRoll() at 8:45 IST moves today → history
 *   - Returns 1.0 (neutral) when no history — never blocks day-1 trades
 *
 * WHY TIME-SLOT RVOL MATTERS:
 *   1.5× at 9:45 AM (opening rush) = normal, no edge.
 *   1.5× at 1:30 PM (lunch)        = rare = institutional activity.
 */
@Service
@Slf4j
public class RvolService {

    private static final ZoneId IST       = ZoneId.of("Asia/Kolkata");
    private static final int    MAX_DAYS  = 5;
    private static final int    SLOT_MINS = 5;

    /**
     * History: "SYMBOL:HH:mm" → up to 5 previous-day volumes for that slot.
     * index 0 = most recent previous day.
     */
    private final Map<String, Deque<Long>> history    = new ConcurrentHashMap<>();

    /**
     * Today's accumulator: "SYMBOL:HH:mm" → today's volume.
     * Moved to history at EOD reset (8:45 IST).
     */
    private final Map<String, Long>        todaySlots = new ConcurrentHashMap<>();

    // ── Candle listener ───────────────────────────────────────────────────

    @EventListener
    @Async("tradingExecutor")
    public void onCandle(CandleCompleteEvent event) {
        Candle c = event.getCandle();
        if (!"5minute".equals(c.getTimeframe())) return;
        if (!c.isComplete()) return;
        if (c.getInstrumentToken() == 256265L) return; // skip Nifty index

        ZonedDateTime zdt  = c.getCandleTime().atZone(IST);
        LocalTime     slot = alignToSlot(zdt.toLocalTime());

        if (slot.isBefore(LocalTime.of(9, 15))) return;
        if (slot.isAfter(LocalTime.of(15, 25))) return;

        String key = slotKey(c.getTradingSymbol(), slot);
        todaySlots.put(key, c.getVolume());
    }

    // ── Public API — ALL method signatures preserved ──────────────────────

    /**
     * PRIMARY: Time-slot aware RVOL.
     * Used by: AutoModeStrategy, RangeBreakoutStrategy, PullbackDetectionService.
     *
     * @param symbol     NSE trading symbol
     * @param slot       5-minute slot time (e.g. LocalTime.of(10, 30))
     * @param currentVol current candle volume
     * @return RVOL ratio. Returns 1.0 if insufficient history (neutral).
     */
    public double getRvol(String symbol, LocalTime slot, long currentVol) {
        String key = slotKey(symbol, alignToSlot(slot));
        Deque<Long> hist = history.get(key);

        if (hist == null || hist.isEmpty()) {
            return 1.0; // no history → neutral, don't block
        }

        double avg = hist.stream().mapToLong(Long::longValue).average().orElse(0);
        if (avg <= 0) return 1.0;

        return (double) currentVol / avg;
    }

    /**
     * CONVENIENCE 2-arg: uses current IST time as slot.
     * Used by: ProbabilityEngine, MarketModeEngine, BankNiftyModeEngine,
     *          StockRankingEngine, StrategyEvaluatorService.
     *
     * @param symbol     NSE trading symbol
     * @param currentVol current candle volume
     * @return RVOL ratio
     */
    public double getRvolNow(String symbol, long currentVol) {
        LocalTime now = LocalTime.now(IST);
        return getRvol(symbol, alignToSlot(now), currentVol);
    }

    /**
     * COMPATIBILITY 3-arg overload of getRvolNow.
     * Fixes compile error: some strategy versions call getRvolNow(symbol, slot, vol).
     * Delegates to getRvol(symbol, slot, vol).
     *
     * Used by: any caller that passes slot to getRvolNow (treated same as getRvol).
     *
     * @param symbol     NSE trading symbol
     * @param slot       5-minute slot time
     * @param currentVol current candle volume
     * @return RVOL ratio
     */
    public double getRvolNow(String symbol, LocalTime slot, long currentVol) {
        return getRvol(symbol, slot, currentVol);
    }

    /**
     * Volume significance check.
     */
    public boolean isSignificantVolume(String symbol, LocalTime slot,
                                       long currentVol, double minimumRvol) {
        return getRvol(symbol, slot, currentVol) >= minimumRvol;
    }

    /**
     * Human-readable RVOL label for log messages.
     * Used by: AutoModeStrategy, RangeBreakoutStrategy, PullbackDetectionService.
     *
     * Examples: "EXCEPTIONAL(3.8x)", "HIGH(2.1x)", "ELEVATED(1.4x)", "NORMAL(0.9x)", "DEAD(0.5x)"
     */
    public String rvolLabel(double rvol) {
        if (rvol >= 3.5) return "EXCEPTIONAL(" + String.format("%.1fx", rvol) + ")";
        if (rvol >= 2.0) return "HIGH("        + String.format("%.1fx", rvol) + ")";
        if (rvol >= 1.2) return "ELEVATED("    + String.format("%.1fx", rvol) + ")";
        if (rvol >= 0.8) return "NORMAL("      + String.format("%.1fx", rvol) + ")";
        return                   "DEAD("        + String.format("%.1fx", rvol) + ")";
    }

    // ── EOD roll ─────────────────────────────────────────────────────────

    /**
     * Called at 8:45 IST (before market opens).
     * Moves today's slot volumes into the 5-day rolling history.
     * Clears today's map — today's data must NOT bias RVOL calculation.
     */
    @Scheduled(cron = "0 45 8 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyRoll() {
        int moved = 0;
        for (Map.Entry<String, Long> e : todaySlots.entrySet()) {
            Deque<Long> hist = history.computeIfAbsent(e.getKey(), k -> new ArrayDeque<>());
            hist.addFirst(e.getValue());
            if (hist.size() > MAX_DAYS) ((ArrayDeque<Long>) hist).removeLast();
            moved++;
        }
        todaySlots.clear();
        log.info("[RVOL] Daily roll: {} slot entries moved to history", moved);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private LocalTime alignToSlot(LocalTime t) {
        int minute = (t.getMinute() / SLOT_MINS) * SLOT_MINS;
        return LocalTime.of(t.getHour(), minute, 0);
    }

    private String slotKey(String symbol, LocalTime slot) {
        return symbol.toUpperCase() + ":"
                + String.format("%02d:%02d", slot.getHour(), slot.getMinute());
    }

    // ── Dashboard helpers ─────────────────────────────────────────────────

    public int getHistorySize() {
        return (int) history.values().stream().filter(d -> !d.isEmpty()).count();
    }

    public int getDaysOfHistory(String symbol, LocalTime slot) {
        Deque<Long> hist = history.get(slotKey(symbol, alignToSlot(slot)));
        return hist != null ? hist.size() : 0;
    }

    public int getTrackedSymbolCount() {
        return (int) history.keySet().stream()
                .map(k -> k.split(":")[0]).distinct().count();
    }
}