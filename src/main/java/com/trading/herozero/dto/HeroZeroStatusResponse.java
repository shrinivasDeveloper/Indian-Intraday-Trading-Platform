package com.trading.herozero.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * HeroZeroStatusResponse - read-only status DTO for the dashboard,
 * matching the spec's exact "Output Format" field list.
 */
public record HeroZeroStatusResponse(
        LocalDate date,
        LocalTime time,
        String index,
        boolean isMonthlyExpiryToday,
        boolean wasHolidayShifted,
        LocalDate originalExpiryDate,
        LocalDate holidayDate,
        LocalDate shiftedExpiryDate,
        BigDecimal indiaVix,
        Integer positionSize,
        String ceStrike,
        String peStrike,
        BigDecimal cePremium,
        BigDecimal pePremium,
        BigDecimal totalPremium,
        BigDecimal upperBreakeven,
        BigDecimal lowerBreakeven,
        BigDecimal spotPrice,
        String tradeStatus,
        String skipReason
) {}