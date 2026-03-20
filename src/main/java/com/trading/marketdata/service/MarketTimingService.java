package com.trading.marketdata.service;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.*;

/**
 * Market timing — defines all time windows and trading rules.
 *
 * 09:15 - 09:40 → Observation only (collect gap/open data, no trades)
 * 09:40 - 11:00 → Prime morning window (RR 2.5, full size)
 * 11:00 - 12:30 → Lunch window (RR 3.0, half size)
 * 12:30 - 14:00 → Afternoon window (RR 2.5, full size)
 * 14:00 - 14:40 → Late window (RR 3.0, half size)
 * 14:40+        → No new entries
 * 15:00         → Force close all + EOD sequence
 */
@Service
@Slf4j
public class MarketTimingService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public enum TimeWindow {
        PRE_OPEN,         // Before 09:15
        OBSERVATION,      // 09:15 - 09:40
        PRIME_MORNING,    // 09:40 - 11:00 — RR 2.5
        LUNCH,            // 11:00 - 12:30 — RR 3.0, half size
        AFTERNOON,        // 12:30 - 14:00 — RR 2.5
        LATE,             // 14:00 - 14:40 — RR 3.0, half size
        ENTRY_CLOSED,     // 14:40 - 15:00 — no new entries
        EOD,              // After 15:00
        MARKET_CLOSED     // Weekends/holidays
    }

    public TimeWindow getCurrentWindow() {
        LocalTime now = LocalTime.now(IST);
        DayOfWeek day = LocalDate.now(IST).getDayOfWeek();

        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY)
            return TimeWindow.MARKET_CLOSED;

        if (now.isBefore(LocalTime.of(9, 15)))   return TimeWindow.PRE_OPEN;
        if (now.isBefore(LocalTime.of(9, 40)))   return TimeWindow.OBSERVATION;
        if (now.isBefore(LocalTime.of(11, 0)))   return TimeWindow.PRIME_MORNING;
        if (now.isBefore(LocalTime.of(12, 30)))  return TimeWindow.LUNCH;
        if (now.isBefore(LocalTime.of(14, 0)))   return TimeWindow.AFTERNOON;
        if (now.isBefore(LocalTime.of(14, 40)))  return TimeWindow.LATE;
        if (now.isBefore(LocalTime.of(15, 0)))   return TimeWindow.ENTRY_CLOSED;
        return TimeWindow.EOD;
    }

    public boolean isObservationPeriod() {
        return getCurrentWindow() == TimeWindow.OBSERVATION;
    }

    public boolean isEntryAllowed() {
        TimeWindow w = getCurrentWindow();
        return w == TimeWindow.PRIME_MORNING
                || w == TimeWindow.LUNCH
                || w == TimeWindow.AFTERNOON
                || w == TimeWindow.LATE;
    }

    public boolean isMarketOpen() {
        TimeWindow w = getCurrentWindow();
        return w != TimeWindow.PRE_OPEN
                && w != TimeWindow.MARKET_CLOSED
                && w != TimeWindow.EOD;
    }

    public boolean isForceCloseTime() {
        LocalTime now = LocalTime.now(IST);
        return now.isAfter(LocalTime.of(15, 0))
                || now.equals(LocalTime.of(15, 0));
    }

    /** Minimum reward:risk ratio for current window */
    public double getMinRR(double vixExtra) {
        return switch (getCurrentWindow()) {
            case PRIME_MORNING -> 2.5 + vixExtra;
            case LUNCH         -> 3.0 + vixExtra;
            case AFTERNOON     -> 2.5 + vixExtra;
            case LATE          -> 3.0 + vixExtra;
            default            -> 99.0; // effectively blocks entry
        };
    }

    /** Position size multiplier for current window */
    public double getWindowSizeMultiplier() {
        return switch (getCurrentWindow()) {
            case LUNCH -> 0.5;
            case LATE  -> 0.5;
            default    -> 1.0;
        };
    }

    /** After 14:00, trades entered are half size */
    public boolean isAfterTwoPM() {
        LocalTime now = LocalTime.now(IST);
        return now.isAfter(LocalTime.of(14, 0));
    }

    public String getCurrentWindowName() {
        return switch (getCurrentWindow()) {
            case OBSERVATION   -> "Observation (9:15-9:40)";
            case PRIME_MORNING -> "Prime Morning (9:40-11:00)";
            case LUNCH         -> "Lunch (11:00-12:30)";
            case AFTERNOON     -> "Afternoon (12:30-14:00)";
            case LATE          -> "Late (14:00-14:40)";
            case ENTRY_CLOSED  -> "Entry Closed (14:40-15:00)";
            case EOD           -> "End of Day";
            case MARKET_CLOSED -> "Market Closed";
            default            -> "Pre-Open";
        };
    }
}