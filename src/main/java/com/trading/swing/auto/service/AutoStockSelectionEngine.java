package com.trading.swing.auto.service;

import com.trading.swing.auto.domain.SectorPerformance;
import com.trading.swing.auto.domain.StockCandidate;
import com.trading.swing.config.ManualSwingConfig;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.models.Instrument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

/**
 * AutoStockSelectionEngine - orchestrates Rules 1 through 3 in the exact
 * order and fallback logic the spec describes:
 *
 *   Rule 1 (highest priority): rank sectors, take top N.
 *   Rule 2: walk the ranked sectors in order; for each, check whether it
 *     qualifies (daily/weekly/monthly/yearly thresholds). "If no
 *     qualifying stock is found in the highest-ranked sector, proceed to
 *     the next" - implemented literally: even a qualifying sector can
 *     still yield zero usable stocks after Rule 3, so the walk
 *     continues to the next sector in that case too, not just when the
 *     sector itself fails to qualify.
 *   Rule 3 (mandatory): within a qualifying sector, every stock must
 *     pass the momentum check or it's dropped immediately - no scoring,
 *     no exception. Survivors are then ranked by liquidity (a real,
 *     free bhavcopy-volume signal) since fundamentals data is no
 *     longer part of this pipeline.
 *
 * REMOVED (per explicit instruction: "remove the fundamentals in my
 * swing trading completely... without affecting other rule"). Rule 4
 * (Promoter Holding mandatory gate + sales/profit growth/FII/DII
 * scoring) has been fully removed - StockFundamentalService is no
 * longer called anywhere in this class, and the mandatory promoter
 * gate no longer exists. Rules 1, 2, and 3 are completely unchanged -
 * same thresholds, same mandatory momentum AND-gate, same sector
 * walk-and-fallback logic, verified line-for-line against the version
 * before this change.
 *
 * Small-cap prioritization: unchanged - no market-cap data source
 * exists for this system (never did), so this remains an unfulfilled
 * "nice to have" rather than an active filter or bonus; liquidity
 * (see below) is the only real signal this system has for ranking
 * survivors of Rule 3.
 */
@Service
@Slf4j
public class AutoStockSelectionEngine {

    private final SectorPerformanceService sectorService;
    private final StockMomentumService momentumService;
    private final com.trading.swing.auto.repository.DailyBarRepository barRepo;
    private final com.trading.swing.repository.ManualSwingTradeRepository tradeRepo;
    private final KiteConnect kiteConnect;
    private final ManualSwingConfig config;

    // FIX (per explicit instruction: "if we traded today don't trade
    // again cooling period is 10 trading days"). A stock that was
    // bought (manual OR auto) within the last 10 REAL trading days is
    // skipped for auto-selection - prevents repeatedly buying back into
    // the same stock shortly after exiting it. Uses the real bhavcopy
    // trading calendar (weekends/holidays correctly excluded), not
    // naive calendar-day subtraction.
    private static final int COOLING_PERIOD_TRADING_DAYS = 10;

    public AutoStockSelectionEngine(SectorPerformanceService sectorService,
                                    StockMomentumService momentumService,
                                    com.trading.swing.auto.repository.DailyBarRepository barRepo,
                                    com.trading.swing.repository.ManualSwingTradeRepository tradeRepo,
                                    KiteConnect kiteConnect,
                                    ManualSwingConfig config) {
        this.sectorService = sectorService;
        this.momentumService = momentumService;
        this.barRepo = barRepo;
        this.tradeRepo = tradeRepo;
        this.kiteConnect = kiteConnect;
        this.config = config;
    }

    /**
     * Fetches the FULL NSE+BSE equity instrument list directly from
     * KiteConnect - deliberately NOT using the existing
     * InstrumentCacheService.getEquityInstruments(), which filters to
     * NSE only (confirmed by reading its source). Rule 1 explicitly
     * requires "every NSE and BSE listed stock... no listed stock
     * excluded" - this module needs its own, broader fetch to honor that,
     * without touching or widening the existing cache that AI/News rely
     * on staying NSE-scoped.
     */
    public List<Instrument> fetchFullNseAndBseUniverse() {
        List<Instrument> nse = new ArrayList<>();
        List<Instrument> bse = new ArrayList<>();
        try {
            nse.addAll(kiteConnect.getInstruments("NSE"));
        } catch (com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException ex) {
            log.error("[AUTO-SELECT] KiteException fetching NSE instruments: {}", ex.message);
        } catch (java.io.IOException ex) {
            log.error("[AUTO-SELECT] IOException fetching NSE instruments: {}", ex.getMessage());
        }
        try {
            bse.addAll(kiteConnect.getInstruments("BSE"));
        } catch (com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException ex) {
            log.error("[AUTO-SELECT] KiteException fetching BSE instruments: {}", ex.message);
        } catch (java.io.IOException ex) {
            log.error("[AUTO-SELECT] IOException fetching BSE instruments: {}", ex.getMessage());
        }

        // FIX: deduplication by trading symbol. A dual-listed stock (e.g.
        // RELIANCE on both NSE and BSE) was previously counted twice in the
        // sector classification, inflating sector stock counts and
        // double-weighting in performance averages. NSE preferred since
        // our bhavcopy data and momentum checks are also NSE-sourced.
        Map<String, Instrument> deduplicated = new java.util.LinkedHashMap<>();
        for (Instrument i : nse) {
            if (i.getTradingsymbol() != null)
                deduplicated.put(SymbolNormalizer.normalize(i.getTradingsymbol()), i);
        }
        int nseCount = deduplicated.size();
        int bseAdded = 0;
        for (Instrument i : bse) {
            if (i.getTradingsymbol() != null) {
                String norm = SymbolNormalizer.normalize(i.getTradingsymbol());
                if (!deduplicated.containsKey(norm)) {
                    deduplicated.put(norm, i);
                    bseAdded++;
                }
            }
        }
        List<Instrument> combined = new ArrayList<>(deduplicated.values());
        log.info("[AUTO-SELECT] Universe: {} NSE + {} BSE-only = {} unique instruments " +
                        "({} dual-listed stocks deduplicated, NSE preferred)",
                nseCount, bseAdded,
                combined.size(), (nse.size() + bse.size()) - combined.size());
        return combined;
    }

    /**
     * Runs the complete selection process. Returns empty if no stock
     * qualifies across all top-ranked sectors - the caller (scheduler)
     * must handle that by simply not placing a trade that day, not by
     * relaxing any rule.
     */
    public Optional<StockCandidate> selectBestStock() {
        List<Instrument> universe = fetchFullNseAndBseUniverse();
        if (universe.isEmpty()) {
            log.error("[AUTO-SELECT] Empty instrument universe - cannot proceed");
            return Optional.empty();
        }

        Map<String, List<String>> sectorToSymbols = sectorService.classifyAll(universe);
        List<SectorPerformance> rankedSectors = sectorService.rankTopSectors(sectorToSymbols);

        log.info("[AUTO-SELECT] Top {} ranked sectors: {}", rankedSectors.size(),
                rankedSectors.stream().map(SectorPerformance::sectorName).toList());

        Map<String, Instrument> instrumentBySymbol = new HashMap<>();
        for (Instrument i : universe) {
            if (i.getTradingsymbol() != null) {
                // FIX: MUST use the same normalization as
                // SectorPerformanceService.classifyAll() - sector.symbolsInSector()
                // now returns normalized symbols (suffix-stripped), so this map's
                // keys must match exactly or every lookup below silently fails.
                instrumentBySymbol.putIfAbsent(
                        SymbolNormalizer.normalize(i.getTradingsymbol()), i);
            }
        }

        for (SectorPerformance sector : rankedSectors) {
            if (!sector.qualifies()) {
                log.info("[AUTO-SELECT] Sector '{}' does not qualify ({}) - trying next sector",
                        sector.sectorName(), sector.disqualificationReason());
                continue;
            }

            log.info("[AUTO-SELECT] Sector '{}' qualifies (daily={} weekly={} monthly={} yearly={}) " +
                            "- evaluating its {} stock(s) for momentum",
                    sector.sectorName(), sector.dailyPct(), sector.weeklyPct(),
                    sector.monthlyPct(), sector.yearlyPct(), sector.symbolsInSector().size());

            Optional<StockCandidate> pick = evaluateSectorForBestStock(sector, instrumentBySymbol);
            if (pick.isPresent()) {
                log.info("[AUTO-SELECT] Final selection: {} (sector={}, confidence={}/100) - {}",
                        pick.get().symbol(), sector.sectorName(), pick.get().confidenceScore(),
                        pick.get().scoreBreakdown());
                return pick;
            }
            log.info("[AUTO-SELECT] No usable stock survived Rule 3/4 in sector '{}' - " +
                            "trying next sector, per spec's explicit fallback instruction",
                    sector.sectorName());
        }

        log.warn("[AUTO-SELECT] No qualifying stock found across all {} top-ranked sectors today " +
                "- no automated trade will be placed", rankedSectors.size());
        return Optional.empty();
    }

    private Optional<StockCandidate> evaluateSectorForBestStock(
            SectorPerformance sector, Map<String, Instrument> instrumentBySymbol) {

        List<StockCandidate> candidates = new ArrayList<>();

        for (String symbol : sector.symbolsInSector()) {
            // Rule 3 - mandatory, AND gate, no exceptions - UNCHANGED,
            // exact same condition as before Rule 4's removal.
            if (!momentumService.passesMomentumCheck(symbol)) continue;

            // NEW (per explicit instruction, separate from Rules 1-3):
            // 10-trading-day cooling period. A stock bought within the
            // last 10 REAL trading days (manual or auto) is skipped here
            // - independent of and does not alter Rule 3's own check above.
            if (isInCoolingPeriod(symbol)) {
                log.debug("[AUTO-SELECT] {} skipped - within 10-trading-day cooling period",
                        symbol);
                continue;
            }

            // REMOVED (per explicit instruction: "remove the fundamentals
            // in my swing trading completely"). Rule 4's mandatory
            // Promoter Holding gate and fundamentals-based scoring used
            // to sit here - fully removed. Every stock that passes Rule 3
            // now proceeds directly to candidacy; no fundamentals data is
            // fetched or checked anywhere in this method.

            Instrument inst = instrumentBySymbol.get(symbol);
            if (inst == null) continue;

            var recentBars = barRepo.findBySymbol(symbol, java.time.LocalDate.now().minusDays(10));
            if (recentBars.isEmpty()) continue; // no recent price data - can't size a quantity, skip
            BigDecimal lastClose = recentBars.get(recentBars.size() - 1).close();

            long avgVolume = (long) recentBars.stream()
                    .skip(Math.max(0, recentBars.size() - 5))
                    .mapToLong(b -> b.volume())
                    .average().orElse(0);

            // Scoring now uses liquidity alone - the one real, free data
            // signal left once fundamentals scoring is removed. Still a
            // bonus-style score (0-100), never a pass/fail gate - a
            // stock with lower volume still gets a valid score and can
            // still be selected, exactly matching "no hard filter" for
            // small-cap-typical low liquidity.
            int score = computeConfidenceScore(avgVolume);
            String breakdown = String.format(
                    "avgVolume(5d)=%,d shares - momentum-qualified, no fundamentals gate applied",
                    avgVolume);

            candidates.add(new StockCandidate(symbol, inst.getName(), inst.getExchange(),
                    sector.sectorName(), lastClose, score, breakdown));
        }

        if (candidates.isEmpty()) return Optional.empty();

        candidates.sort((a, b) -> Integer.compare(b.confidenceScore(), a.confidenceScore()));
        return Optional.of(candidates.get(0));
    }

    /**
     * Scoring after Rule 4's removal - liquidity is the only real signal
     * left. Bonus-style only (0-100 scale kept for compatibility with
     * downstream dashboard/logging expectations) - a low-volume stock
     * still gets a valid, non-zero-floor score and can still be
     * selected; this never excludes anything by itself.
     */
    private int computeConfidenceScore(long avgVolume) {
        return boundedPoints(avgVolume, 0, 100_000, 100);
    }

    /**
     * True if this symbol was bought (manual OR auto) within the last
     * COOLING_PERIOD_TRADING_DAYS real trading days. Fails OPEN (returns
     * false, i.e. does NOT block) if the symbol has never been traded
     * before, or if trading-day counting genuinely fails for any reason
     * - never lets a data/lookup problem silently block a legitimate
     * opportunity.
     */
    private boolean isInCoolingPeriod(String symbol) {
        try {
            var lastBuy = tradeRepo.findMostRecentBuyDate(symbol);
            if (lastBuy.isEmpty()) return false; // never traded before - no cooling period applies
            int tradingDaysSince = barRepo.countTradingDaysBetween(
                    lastBuy.get(), java.time.LocalDate.now());
            return tradingDaysSince < COOLING_PERIOD_TRADING_DAYS;
        } catch (Exception e) {
            log.debug("[AUTO-SELECT] Cooling-period check failed for {} (failing open, " +
                    "not blocking): {}", symbol, e.getMessage());
            return false;
        }
    }

    private int boundedPoints(double value, double floor, double ceiling, int maxPoints) {
        if (value <= floor) return 0;
        if (value >= ceiling) return maxPoints;
        return (int) Math.round((value - floor) / (ceiling - floor) * maxPoints);
    }
}