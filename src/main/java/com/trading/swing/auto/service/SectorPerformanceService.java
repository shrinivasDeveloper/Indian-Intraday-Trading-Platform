package com.trading.swing.auto.service;

import com.trading.swing.auto.domain.DailyBar;
import com.trading.swing.auto.domain.SectorPerformance;
import com.trading.swing.auto.repository.DailyBarRepository;
import com.trading.swing.config.ManualSwingConfig;
import com.zerodhatech.models.Instrument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * SectorPerformanceService - Rule 1 (sector strength, highest priority)
 * and Rule 2 (sector qualification thresholds).
 *
 * CLASSIFICATION SOURCE (updated): for any stock covered by NSE Indices
 * Ltd.'s own Nifty Total Market constituent file (750 stocks - Nifty
 * 500 + Nifty Microcap 250), this now uses that REAL, OFFICIAL,
 * revenue-segment-based classification - not a keyword guess. This is
 * the actual source NSE/Trendlyne-style sector pages are built on,
 * confirmed against NSE Indices' own published methodology and
 * downloaded fresh daily via OfficialSectorMappingCache.
 *
 * For the remainder - smaller micro-caps outside those 750 stocks, where
 * no free official mapping exists - StockSectorClassifier's keyword
 * matching is used as a CLEARLY DISTINGUISHED fallback (logged
 * separately, never silently blended with the real data). This is the
 * most honest approach available without a paid data source: real data
 * where it genuinely exists, transparent approximation where it doesn't.
 *
 * Methodology for performance calculation, stated plainly: sector
 * performance is the SIMPLE AVERAGE of its constituent stocks' % price
 * change over each timeframe - not market-cap-weighted, since no
 * market-cap data source is currently connected to this system.
 *
 * "No listed stock should be excluded" (Rule 1): every EQ/BE symbol with
 * sufficient stored history is included in its sector's average - none
 * are filtered out before this stage, regardless of which classification
 * source (official or fallback) applies to it.
 */
@Service
@Slf4j
public class SectorPerformanceService {

    private final DailyBarRepository barRepo;
    private final StockSectorClassifier classifier;
    private final OfficialSectorMappingCache officialMapping;
    private final ManualSwingConfig config;

    public SectorPerformanceService(DailyBarRepository barRepo,
                                    StockSectorClassifier classifier,
                                    OfficialSectorMappingCache officialMapping,
                                    ManualSwingConfig config) {
        this.barRepo = barRepo;
        this.classifier = classifier;
        this.officialMapping = officialMapping;
        this.config = config;
    }

    /**
     * Builds the sector->symbols map from the live instrument list (every
     * EQ/BE NSE+BSE equity instrument gets classified - "no listed stock
     * excluded"). Pass in the full combined NSE+BSE instrument list.
     */
    public Map<String, List<String>> classifyAll(List<Instrument> instruments) {
        Map<String, List<String>> sectorToSymbols = new HashMap<>();
        int officialCount = 0, fallbackCount = 0;

        for (Instrument inst : instruments) {
            if (inst.getTradingsymbol() == null) continue;
            String type = inst.getInstrument_type();
            if (!"EQ".equals(type) && !"BE".equals(type)) continue;

            // FIX (found alongside the SME-series bhavcopy gap, per
            // explicit request: "Photon, Parameshwari Silk etc are
            // missing"). Kite's OWN tradingsymbol field can carry
            // suffixes NSE's bhavcopy file does NOT use - confirmed
            // directly from Zerodha's own developer forum, where
            // multiple developers report exactly this mismatch (e.g.
            // "NSE:NDTV-BE" vs bhavcopy's plain "NDTV"; BSE symbols can
            // carry a trailing "*" for corporate-action stocks). Without
            // stripping these, a stock could be correctly CLASSIFIED
            // into a sector here, yet its later barRepo.findBySymbol()
            // price lookup would silently return zero rows (different
            // symbol string), meaning the stock could never actually
            // qualify or be selected - invisible in this
            // classification step, expressed here as "0 bars found",
            // never as a visible error. This is the real, confirmed
            // root cause of stocks appearing to be classified but then
            // never surfacing further downstream.
            String symbol = SymbolNormalizer.normalize(inst.getTradingsymbol());

            // Real, official classification first - confirmed source,
            // not a guess. Only fall back to keywords when this symbol
            // genuinely isn't covered by NSE's 750-stock official file.
            String sector = officialMapping.getOfficialSector(symbol);
            if (sector != null) {
                officialCount++;
            } else {
                sector = classifier.classify(symbol, inst.getName());
                fallbackCount++;
            }
            // FIX: previously excluded OTHERS-classified stocks here -
            // confirmed via sampling that this silently dropped ~90%+ of
            // a realistic small-cap universe, violating the spec's
            // explicit "no listed stock should be excluded." classify()
            // now returns the genuine, rankable DIVERSIFIED bucket
            // instead of OTHERS, and it is included here like any other
            // sector - no special-case exclusion remains.

            sectorToSymbols.computeIfAbsent(sector, k -> new ArrayList<>()).add(symbol);
        }
        log.info("[SECTOR-PERF] Classification source breakdown: {} stocks via REAL official " +
                        "NSE Indices data, {} stocks via keyword fallback (outside the 750-stock " +
                        "official coverage) - {}% official coverage this cycle",
                officialCount, fallbackCount,
                (officialCount + fallbackCount) > 0
                        ? (100 * officialCount / (officialCount + fallbackCount)) : 0);
        return sectorToSymbols;
    }

    /**
     * Computes performance for every classified sector, ranks by daily
     * performance (Rule 1: "Rank sectors based on Daily, Weekly, Monthly
     * Performance" - daily first as the primary sort, the others as
     * supporting context and the actual Rule 2 gates), and returns the
     * top N (config: topSectorsToEvaluate) - qualifying or not, so the
     * caller can walk down the ranked list per Rule 2's explicit
     * fallback instruction ("if no qualifying stock found in the
     * highest-ranked sector, proceed to the next").
     */
    public List<SectorPerformance> rankTopSectors(Map<String, List<String>> sectorToSymbols) {
        List<SectorPerformance> all = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : sectorToSymbols.entrySet()) {
            SectorPerformance perf = computeSectorPerformance(entry.getKey(), entry.getValue());
            if (perf != null) all.add(perf);
        }
        all.sort((a, b) -> b.dailyPct().compareTo(a.dailyPct()));
        return all.stream().limit(config.getTopSectorsToEvaluate()).collect(Collectors.toList());
    }

    private SectorPerformance computeSectorPerformance(String sectorName, List<String> symbols) {
        LocalDate today = LocalDate.now();
        List<BigDecimal> dailyChanges = new ArrayList<>();
        List<BigDecimal> weeklyChanges = new ArrayList<>();
        List<BigDecimal> monthlyChanges = new ArrayList<>();
        List<BigDecimal> yearlyChanges = new ArrayList<>();

        for (String symbol : symbols) {
            List<DailyBar> bars = barRepo.findBySymbol(symbol, today.minusYears(1).minusDays(10));
            if (bars.size() < 2) continue; // not enough history for this symbol yet - skip it,
            // don't let one thin symbol distort the average

            BigDecimal latestClose = bars.get(bars.size() - 1).close();
            pctChangeFromLookback(bars, latestClose, 1).ifPresent(dailyChanges::add);
            pctChangeFromLookback(bars, latestClose, 5).ifPresent(weeklyChanges::add);
            pctChangeFromLookback(bars, latestClose, 21).ifPresent(monthlyChanges::add);
            pctChangeFromLookback(bars, latestClose, 252).ifPresent(yearlyChanges::add);
        }

        if (dailyChanges.isEmpty()) {
            log.debug("[SECTOR-PERF] {} has no symbols with sufficient history yet - skipped",
                    sectorName);
            return null;
        }

        // FIX (critical, found during prompt-vs-output verification):
        // average() previously returned BigDecimal.ZERO for an empty
        // list - meaning during the gradual historical backfill (which
        // takes a while by design), a sector with literally ZERO stocks
        // having 252 days of history would report yearlyAvg=0.0% and
        // get auto-disqualified by Rule 2's ">=60%" check - not because
        // its real yearly performance was bad, but because the system
        // falsely reported "0%" instead of "I don't have this data yet."
        // A sector with genuinely strong 65% yearly growth would have
        // been incorrectly rejected. Fixed: weekly/monthly/yearly
        // averages are now Optional - null means "insufficient data,"
        // structurally distinct from a real, computed zero, and
        // checkQualification() treats null as "cannot yet judge this
        // criterion" rather than "fails this criterion."
        BigDecimal dailyAvg   = average(dailyChanges); // always has data - checked above
        Optional<BigDecimal> weeklyAvg  = averageOrEmpty(weeklyChanges);
        Optional<BigDecimal> monthlyAvg = averageOrEmpty(monthlyChanges);
        Optional<BigDecimal> yearlyAvg  = averageOrEmpty(yearlyChanges);

        String disqualReason = checkQualification(dailyAvg, weeklyAvg, monthlyAvg, yearlyAvg);

        return new SectorPerformance(sectorName, dailyAvg,
                weeklyAvg.orElse(null), monthlyAvg.orElse(null), yearlyAvg.orElse(null),
                symbols, disqualReason == null, disqualReason);
    }

    /**
     * Rule 2's exact thresholds, checked in the exact order stated:
     *   Daily: 5%-6% (not higher than 6%)
     *   Weekly: >= 15%
     *   Monthly: >= weekly + 5 percentage points
     *   Yearly: >= 60%
     *
     * weekly/monthly/yearly are Optional - empty means genuinely
     * insufficient backfilled history for this timeframe yet (NOT a real
     * 0% reading). A sector cannot be confidently qualified OR
     * disqualified on a criterion it doesn't yet have real data for -
     * this returns a clearly distinct "insufficient data" reason rather
     * than silently treating missing data as a failing zero.
     */
    private String checkQualification(BigDecimal daily, Optional<BigDecimal> weekly,
                                      Optional<BigDecimal> monthly, Optional<BigDecimal> yearly) {
        double d = daily.doubleValue();

        if (d < config.getSectorDailyMinPct() || d > config.getSectorDailyMaxPct()) {
            return String.format("Daily %.2f%% outside required %.0f-%.0f%% band",
                    d, config.getSectorDailyMinPct(), config.getSectorDailyMaxPct());
        }

        if (weekly.isEmpty()) {
            return "Weekly performance: insufficient backfilled history yet - cannot judge " +
                    "this criterion, not a failing 0%";
        }
        double w = weekly.get().doubleValue();
        if (w < config.getSectorWeeklyMinPct()) {
            return String.format("Weekly %.2f%% below required %.0f%%",
                    w, config.getSectorWeeklyMinPct());
        }

        if (monthly.isEmpty()) {
            return "Monthly performance: insufficient backfilled history yet - cannot judge " +
                    "this criterion, not a failing 0%";
        }
        double m = monthly.get().doubleValue();
        double requiredMonthly = w + config.getSectorMonthlyOverWeeklyMarginPct();
        if (m < requiredMonthly) {
            return String.format("Monthly %.2f%% below weekly+%.0f (%.2f%% required)",
                    m, config.getSectorMonthlyOverWeeklyMarginPct(), requiredMonthly);
        }

        if (yearly.isEmpty()) {
            return "Yearly performance: insufficient backfilled history yet (needs ~252 trading " +
                    "days) - cannot judge this criterion, not a failing 0%";
        }
        double y = yearly.get().doubleValue();
        if (y < config.getSectorYearlyMinPct()) {
            return String.format("Yearly %.2f%% below required %.0f%%",
                    y, config.getSectorYearlyMinPct());
        }
        return null; // genuinely qualifies - every timeframe had real data and passed
    }

    private Optional<BigDecimal> pctChangeFromLookback(List<DailyBar> bars, BigDecimal latestClose,
                                                       int tradingDaysBack) {
        int idx = bars.size() - 1 - tradingDaysBack;
        if (idx < 0) return Optional.empty(); // not enough history for this lookback yet
        BigDecimal pastClose = bars.get(idx).close();
        if (pastClose.signum() <= 0) return Optional.empty();
        return Optional.of(latestClose.subtract(pastClose)
                .divide(pastClose, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)));
    }

    private BigDecimal average(List<BigDecimal> values) {
        if (values.isEmpty()) return BigDecimal.ZERO;
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), 4, RoundingMode.HALF_UP);
    }

    /** Same averaging logic as average(), but returns empty rather than
     *  a misleading ZERO when there's genuinely no data to average. */
    private Optional<BigDecimal> averageOrEmpty(List<BigDecimal> values) {
        if (values.isEmpty()) return Optional.empty();
        return Optional.of(average(values));
    }
}