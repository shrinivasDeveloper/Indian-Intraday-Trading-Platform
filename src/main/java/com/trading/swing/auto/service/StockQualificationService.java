package com.trading.swing.auto.service;

import com.trading.swing.auto.domain.DailyBar;
import com.trading.swing.auto.repository.DailyBarRepository;
import com.trading.swing.config.ManualSwingConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * StockQualificationService - "Stock Qualification", the CORRECTED
 * spec section. This threshold check applies to an INDIVIDUAL STOCK's
 * own performance, never to a sector-wide average (this system's
 * earlier, incorrect implementation - fixed, see SectorPerformanceService's
 * docstring for the corrected sector-ranking-only role).
 *
 * A stock qualifies only if ALL FOUR hold, using its OWN price data:
 *   Daily Performance:   4% <= x <= 6%   ("only not more than this")
 *   Weekly Performance:  >= 15%           ("15 or more")
 *   Monthly Performance: >= Weekly + 5 percentage points
 *   Yearly Performance:  >= 70%           (ADDED per explicit instruction:
 *                                          "add for stock yearly gate -
 *                                          yearly Performance >= 70
 *                                          percentage")
 *
 * Missing data (insufficient backfilled history for a given timeframe)
 * is treated as NOT qualifying, but distinctly logged as such - never
 * silently treated as a passing or failing zero. The yearly check in
 * particular needs ~252 trading days of history - during an ongoing
 * bhavcopy backfill (this system's real, gradual, by-design backfill
 * process), many stocks will genuinely lack this yet; they correctly
 * fail to qualify with a clear "insufficient history" reason rather
 * than a misleading pass or fail on fabricated data.
 */
@Service
@Slf4j
public class StockQualificationService {

    private final DailyBarRepository barRepo;
    private final ManualSwingConfig config;

    public StockQualificationService(DailyBarRepository barRepo, ManualSwingConfig config) {
        this.barRepo = barRepo;
        this.config = config;
    }

    public record QualificationResult(boolean qualifies, String reason,
                                      BigDecimal dailyPct, BigDecimal weeklyPct,
                                      BigDecimal monthlyPct, BigDecimal yearlyPct) {}

    public QualificationResult check(String symbol) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        // FIX: widened from 60 to 370 days - the new yearly gate needs
        // ~252 trading days of history (roughly 365 calendar days plus
        // buffer for weekends/holidays) to compute at all. The previous
        // 60-day window was correctly sized for daily/weekly/monthly
        // only, before this yearly gate existed.
        List<DailyBar> bars = barRepo.findBySymbol(symbol, today.minusDays(370));
        if (bars.size() < 2) {
            return new QualificationResult(false, "Insufficient price history",
                    null, null, null, null);
        }

        BigDecimal latestClose = bars.get(bars.size() - 1).close();
        Optional<BigDecimal> dailyOpt   = pctChangeFromLookback(bars, latestClose, 1);
        Optional<BigDecimal> weeklyOpt  = pctChangeFromLookback(bars, latestClose, 5);
        Optional<BigDecimal> monthlyOpt = pctChangeFromLookback(bars, latestClose, 21);
        Optional<BigDecimal> yearlyOpt  = pctChangeFromLookback(bars, latestClose, 252);

        if (dailyOpt.isEmpty()) {
            return new QualificationResult(false, "Insufficient history for daily performance",
                    null, null, null, null);
        }
        double daily = dailyOpt.get().doubleValue();
        if (daily < config.getStockDailyMinPct() || daily > config.getStockDailyMaxPct()) {
            return new QualificationResult(false, String.format(
                    "Daily %.2f%% outside required %.0f-%.0f%% band",
                    daily, config.getStockDailyMinPct(), config.getStockDailyMaxPct()),
                    dailyOpt.get(), weeklyOpt.orElse(null), monthlyOpt.orElse(null), yearlyOpt.orElse(null));
        }

        if (weeklyOpt.isEmpty()) {
            return new QualificationResult(false, "Insufficient history for weekly performance",
                    dailyOpt.get(), null, null, null);
        }
        double weekly = weeklyOpt.get().doubleValue();
        if (weekly < config.getStockWeeklyMinPct()) {
            return new QualificationResult(false, String.format(
                    "Weekly %.2f%% below required %.0f%%", weekly, config.getStockWeeklyMinPct()),
                    dailyOpt.get(), weeklyOpt.get(), monthlyOpt.orElse(null), yearlyOpt.orElse(null));
        }

        if (monthlyOpt.isEmpty()) {
            return new QualificationResult(false, "Insufficient history for monthly performance",
                    dailyOpt.get(), weeklyOpt.get(), null, yearlyOpt.orElse(null));
        }
        double monthly = monthlyOpt.get().doubleValue();
        double requiredMonthly = weekly + config.getStockMonthlyOverWeeklyMarginPct();
        if (monthly < requiredMonthly) {
            return new QualificationResult(false, String.format(
                    "Monthly %.2f%% below weekly+%.0f (%.2f%% required)",
                    monthly, config.getStockMonthlyOverWeeklyMarginPct(), requiredMonthly),
                    dailyOpt.get(), weeklyOpt.get(), monthlyOpt.get(), yearlyOpt.orElse(null));
        }

        // NEW GATE (per explicit instruction): yearly performance >= 70%.
        if (yearlyOpt.isEmpty()) {
            return new QualificationResult(false,
                    "Insufficient history for yearly performance (needs ~252 trading days - " +
                            "likely still mid-backfill)",
                    dailyOpt.get(), weeklyOpt.get(), monthlyOpt.get(), null);
        }
        double yearly = yearlyOpt.get().doubleValue();
        if (yearly < config.getStockYearlyMinPct()) {
            return new QualificationResult(false, String.format(
                    "Yearly %.2f%% below required %.0f%%", yearly, config.getStockYearlyMinPct()),
                    dailyOpt.get(), weeklyOpt.get(), monthlyOpt.get(), yearlyOpt.get());
        }

        return new QualificationResult(true, null,
                dailyOpt.get(), weeklyOpt.get(), monthlyOpt.get(), yearlyOpt.get());
    }

    private Optional<BigDecimal> pctChangeFromLookback(List<DailyBar> bars, BigDecimal latestClose,
                                                       int tradingDaysBack) {
        int idx = bars.size() - 1 - tradingDaysBack;
        if (idx < 0) return Optional.empty();
        BigDecimal pastClose = bars.get(idx).close();
        if (pastClose.signum() <= 0) return Optional.empty();
        return Optional.of(latestClose.subtract(pastClose)
                .divide(pastClose, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)));
    }
}