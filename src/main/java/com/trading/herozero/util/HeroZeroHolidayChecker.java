package com.trading.herozero.util;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

/**
 * HeroZeroHolidayChecker - completely independent holiday/weekend
 * checker for this module.
 *
 * INDEPENDENCE (explicit spec requirement: "No shared business logic
 * that could impact existing strategies"): this class is DELIBERATELY
 * a separate, standalone copy - it does NOT import or depend on
 * com.trading.swing.service.MarketHolidayChecker (a similar utility
 * already built for the Swing module this session). Duplicating this
 * small amount of data is the correct tradeoff here: a future change
 * to Swing's holiday list must never accidentally affect this
 * completely independent options strategy, and vice versa.
 *
 * HONEST LIMITATION (same as the Swing module's equivalent): NSE's own
 * official holiday page renders its date table via JavaScript
 * dropdowns - a static fetch cannot retrieve it. This list was compiled
 * by cross-referencing multiple independent public sources and has NOT
 * been verified against NSE's own definitive table directly. VERIFY
 * this list against https://www.nseindia.com/resources/exchange-
 * communication-holidays (select Equities/Derivatives + year) before
 * relying on it for real trades with real capital.
 */
public final class HeroZeroHolidayChecker {

    private HeroZeroHolidayChecker() {}

    private static final Set<LocalDate> MARKET_HOLIDAYS_2026 = Set.of(
            LocalDate.of(2026, 1, 26),   // Republic Day (Monday)
            LocalDate.of(2026, 3, 4),    // Holi (Wednesday) - VERIFY exact date
            LocalDate.of(2026, 4, 3),    // Good Friday (Friday)
            LocalDate.of(2026, 5, 1),    // Maharashtra Day (Friday)
            LocalDate.of(2026, 5, 28),   // Bakri Id (Thursday)
            LocalDate.of(2026, 6, 26),   // Muharram (Friday)
            LocalDate.of(2026, 9, 14),   // Ganesh Chaturthi (Monday)
            LocalDate.of(2026, 10, 2),   // Gandhi Jayanti (Friday)
            LocalDate.of(2026, 12, 25)   // Christmas (Friday)
    );

    public static boolean isMarketClosed(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return true;
        if (date.getYear() == 2026 && MARKET_HOLIDAYS_2026.contains(date)) return true;
        return false;
    }

    // FIX (same confirmed critical bug found in Swing's MarketHolidayChecker
    // - a real production log showed AutoSwingScheduler firing at 1:03 AM
    // IST instead of 3 PM, root-caused to bare LocalDate.now()/LocalTime.now()
    // using the JVM's default timezone, UTC on Railway, not India time).
    private static final java.time.ZoneId IST = java.time.ZoneId.of("Asia/Kolkata");

    public static boolean isMarketClosedToday() {
        return isMarketClosed(LocalDate.now(IST));
    }

    /** Walks backward from the given date until it lands on a genuine
     *  trading day - used for the holiday-expiry-shift rule. */
    public static LocalDate previousTradingDay(LocalDate from) {
        LocalDate d = from.minusDays(1);
        while (isMarketClosed(d)) {
            d = d.minusDays(1);
        }
        return d;
    }
}