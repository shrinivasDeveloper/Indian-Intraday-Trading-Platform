package com.trading.herozero.util;

import com.trading.herozero.config.HeroZeroConfig;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/**
 * MonthlyExpiryCalculator - determines the real, holiday-adjusted
 * Monthly Expiry date for a given index and month, per the spec's
 * exact "Holiday Expiry Rules" section.
 *
 * Algorithm:
 *   1. Find the LAST occurrence of the index's configured expiry
 *      weekday in the given month (the "natural" monthly expiry).
 *   2. If that date is a market holiday, walk BACKWARD one trading
 *      day at a time until landing on a genuine trading day (per
 *      spec's explicit examples: "Thursday Holiday -> shifts to
 *      Wednesday", etc. - always shifts to the PREVIOUS day, never
 *      forward).
 */
@Component
public class MonthlyExpiryCalculator {

    private final HeroZeroConfig config;

    public MonthlyExpiryCalculator(HeroZeroConfig config) {
        this.config = config;
    }

    public record ExpiryResult(
            LocalDate naturalExpiry,
            LocalDate actualExpiry,
            boolean wasShifted,
            LocalDate holidayDate // null if not shifted
    ) {}

    public ExpiryResult calculate(String index, LocalDate anyDateInMonth) {
        Integer weekdayIso = config.getExpiryWeekday().get(index.toUpperCase());
        if (weekdayIso == null) {
            throw new IllegalArgumentException(
                    "No expiry weekday configured for index: " + index +
                            " - check hero-zero.expiry-weekday in application.yml");
        }
        DayOfWeek expiryDow = DayOfWeek.of(weekdayIso);

        LocalDate naturalExpiry = anyDateInMonth
                .with(TemporalAdjusters.lastDayOfMonth())
                .with(TemporalAdjusters.previousOrSame(expiryDow));

        if (!HeroZeroHolidayChecker.isMarketClosed(naturalExpiry)) {
            return new ExpiryResult(naturalExpiry, naturalExpiry, false, null);
        }

        LocalDate shifted = HeroZeroHolidayChecker.previousTradingDay(naturalExpiry);
        return new ExpiryResult(naturalExpiry, shifted, true, naturalExpiry);
    }

    /** True if `today` is genuinely this index's (possibly shifted)
     *  monthly expiry trading day. */
    public boolean isTodayMonthlyExpiry(String index) {
        LocalDate today = LocalDate.now();
        ExpiryResult result = calculate(index, today);
        return result.actualExpiry().isEqual(today);
    }
}