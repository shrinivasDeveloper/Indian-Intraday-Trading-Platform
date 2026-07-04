package com.trading.swing.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

/**
 * MarketHolidayChecker - centralized, single source of truth for
 * "is the market open today" - covers BOTH weekends AND genuine NSE/BSE
 * trading holidays.
 *
 * HONEST LIMITATION, stated plainly: NSE's own official holiday page
 * (nseindia.com/resources/exchange-communication-holidays) renders its
 * actual date table via JavaScript dropdowns (select Product + Year) -
 * a static fetch of that page returns only the page shell, not the
 * final table. There is no free, scrapable, machine-readable NSE/BSE
 * holiday calendar API. This list was compiled by cross-referencing
 * multiple independent public sources (Groww, ClearTax, Zerodha
 * Marketintel, Anand Rathi, Sahi, Kotak Neo) that consistently agreed
 * on these dates as of this writing - but this has NOT been verified
 * against NSE's own definitive table directly, since that table isn't
 * accessible via a static fetch.
 *
 * ACTION REQUIRED: cross-check this list against
 * https://www.nseindia.com/resources/exchange-communication-holidays
 * yourself (select "Equities" + the relevant year) before fully
 * trusting it for real trades. Update MARKET_HOLIDAYS_2026 below if
 * any date is wrong, and add a new set for each subsequent year - this
 * class deliberately keeps every year's list separate and explicit
 * rather than trying to calculate festival dates algorithmically
 * (lunar/regional festival dates shift every year and cannot be
 * reliably computed).
 *
 * Only WEEKDAY holidays are listed - a holiday that falls on a
 * Saturday/Sunday is correctly omitted here, since the existing
 * weekend check already covers that day; listing it here too would be
 * redundant, not wrong, but is intentionally left out for clarity.
 */
public final class MarketHolidayChecker {

    private MarketHolidayChecker() {}

    // FIX (per explicit instruction: "all the holiday also handled in
    // this strategy please check"). Cross-referenced 2026 NSE/BSE
    // equity trading holidays that fall on a WEEKDAY (weekend-
    // overlapping holidays correctly excluded - already covered by the
    // separate weekend check). See class-level docstring for the
    // honest caveat on how this list was compiled.
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

    /**
     * True if the market is genuinely CLOSED today - either a weekend
     * OR a known trading holiday. False means the market should be
     * open (assuming this list is accurate and complete for the
     * current year - see class docstring's honest caveat).
     */
    public static boolean isMarketClosedToday() {
        return isMarketClosed(LocalDate.now());
    }

    public static boolean isMarketClosed(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return true;
        if (date.getYear() == 2026 && MARKET_HOLIDAYS_2026.contains(date)) return true;
        // NOTE: years other than 2026 have no holiday list defined yet -
        // only the weekend check applies for those years until a new
        // MARKET_HOLIDAYS_<year> set is added and wired in here.
        return false;
    }
}