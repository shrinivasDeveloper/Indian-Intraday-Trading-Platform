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
 * RvolService — Relative Volume by 5-Minute Time Slot
 *
 * WHY THIS MATTERS (The Pro Secret):
 *   At 11:45 AM (lunch), total daily volume has already peaked from the
 *   opening rush. A stock trading 1.5x its own 20-candle average sounds
 *   impressive — but if the HISTORICAL 11:45 AM volume is always half
 *   what it is at 9:30 AM, then 1.5x lunch volume is STILL DEAD VOLUME.
 *
 *   RVOL compares THIS candle's volume to the SAME TIME SLOT across the
 *   last 5 trading days. This eliminates the time-of-day distortion.
 *
 *   Examples:
 *     RVOL = 2.5 at 9:45 AM → massive institutional activity at open
 *     RVOL = 2.5 at 1:30 PM → EVEN MORE significant (low-volume period)
 *     RVOL = 1.2 at 9:45 AM → normal opening volume → NOT a real breakout
 *
 * HOW IT WORKS:
 *   Every 5-min candle that closes updates the history for that symbol+slot.
 *   Slot key = "SYMBOL:HH:mm" (e.g., "RELIANCE:09:45").
 *   We keep a rolling window of last 5 days per slot (at most 5 values).
 *   getRvol() returns currentVol / avg(last5DaysVolumeForSameSlot).
 *
 * RVOL THRESHOLDS (Indian market context, in our strategies):
 *   < 0.8  → Dead volume — avoid all breakouts
 *   0.8–1.2 → Normal range — only enter with other strong confirmation
 *   1.2–2.0 → Elevated — good for entries, increasing confidence
 *   2.0–3.5 → High — strong institutional participation
 *   > 3.5   → Exceptional — high-conviction move, maximum position size
 *
 * DAILY RESET:
 *   Today's candles are NOT included in the 5-day historical average
 *   (would bias the calculation). Only previous days' slots are used.
 *   At 8:45 IST, today's slot data is moved to history and reset.
 */
@Service
@Slf4j
public class RvolService {

    private static final ZoneId IST        = ZoneId.of("Asia/Kolkata");
    private static final int    MAX_DAYS   = 5;   // rolling 5-day window
    private static final int    SLOT_MINS  = 5;   // 5-minute slots

    /**
     * History map: "SYMBOL:HH:mm" → list of up to 5 previous-day volumes for that slot.
     * index 0 = most recent previous day.
     */
    private final Map<String, Deque<Long>> history = new ConcurrentHashMap<>();

    /**
     * Today's slot accumulator: "SYMBOL:HH:mm" → today's volume for that slot.
     * Gets moved to history at EOD reset.
     */
    private final Map<String, Long> todaySlots = new ConcurrentHashMap<>();

    // ── Listen to 5-min candle completions ────────────────────────────────────

    @EventListener
    @Async("tradingExecutor")
    public void onCandle(CandleCompleteEvent event) {
        Candle c = event.getCandle();
        if (!"5minute".equals(c.getTimeframe())) return;
        if (c.getInstrumentToken() == 256265L) return; // skip Nifty index

        ZonedDateTime zdt  = c.getCandleTime().atZone(IST);
        LocalTime     slot = alignToSlot(zdt.toLocalTime());

        // Skip pre-market and post-market
        if (slot.isBefore(LocalTime.of(9, 15))) return;
        if (slot.isAfter(LocalTime.of(15, 25))) return;

        String key = slotKey(c.getTradingSymbol(), slot);
        todaySlots.put(key, c.getVolume());
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Returns the Relative Volume ratio for the given symbol at the given time slot.
     *
     * @param symbol      NSE trading symbol (e.g., "RELIANCE")
     * @param slot        5-minute slot time (e.g., LocalTime.of(10, 30))
     * @param currentVol  current candle volume
     * @return RVOL ratio. Returns 1.0 if insufficient history (neutral — don't penalise).
     */
    public double getRvol(String symbol, LocalTime slot, long currentVol) {
        String key = slotKey(symbol, alignToSlot(slot));
        Deque<Long> hist = history.get(key);

        if (hist == null || hist.isEmpty()) {
            return 1.0; // No history yet — neutral, don't block
        }

        double avg = hist.stream().mapToLong(Long::longValue).average().orElse(0);
        if (avg <= 0) return 1.0;

        return currentVol / avg;
    }

    /**
     * Convenience: get RVOL using current IST time as the slot.
     */
    public double getRvolNow(String symbol, long currentVol) {
        LocalTime now = LocalTime.now(IST);
        return getRvol(symbol, alignToSlot(now), currentVol);
    }

    /**
     * Is this volume significant for this time of day?
     * Returns true if RVOL >= minimumRvol.
     */
    public boolean isSignificantVolume(String symbol, LocalTime slot,
                                       long currentVol, double minimumRvol) {
        return getRvol(symbol, slot, currentVol) >= minimumRvol;
    }

    /**
     * Quick descriptive label for logging.
     */
    public String rvolLabel(double rvol) {
        if (rvol >= 3.5) return "EXCEPTIONAL(" + String.format("%.1fx", rvol) + ")";
        if (rvol >= 2.0) return "HIGH("        + String.format("%.1fx", rvol) + ")";
        if (rvol >= 1.2) return "ELEVATED("    + String.format("%.1fx", rvol) + ")";
        if (rvol >= 0.8) return "NORMAL("      + String.format("%.1fx", rvol) + ")";
        return                   "DEAD("        + String.format("%.1fx", rvol) + ")";
    }

    // ── EOD: move today → history, reset today ─────────────────────────────────

    /**
     * Called at 8:45 IST (before market opens).
     * Moves today's volume into the 5-day history, then clears today's map.
     * This ensures today's live data does NOT bias the RVOL calculation.
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

    // ── Private helpers ────────────────────────────────────────────────────────

    /** Round a LocalTime down to the nearest 5-minute boundary (NSE 5-min slot). */
    private LocalTime alignToSlot(LocalTime t) {
        int minute = (t.getMinute() / SLOT_MINS) * SLOT_MINS;
        return LocalTime.of(t.getHour(), minute, 0);
    }

    /** Build the history map key: "SYMBOL:HH:mm" */
    private String slotKey(String symbol, LocalTime slot) {
        return symbol.toUpperCase() + ":"
                + String.format("%02d:%02d", slot.getHour(), slot.getMinute());
    }

    /** How many symbols have at least 1 day of history? */
    public int getHistorySize() {
        return (int) history.values().stream().filter(d -> !d.isEmpty()).count();
    }

    /** How many days of history does a specific symbol+slot have? */
    public int getDaysOfHistory(String symbol, LocalTime slot) {
        Deque<Long> hist = history.get(slotKey(symbol, alignToSlot(slot)));
        return hist != null ? hist.size() : 0;
    }
}