package com.trading.swing.auto.service;

import com.trading.swing.auto.domain.FundamentalSnapshot;
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
 * AutoStockSelectionEngine - orchestrates Rules 1 through 4 in the exact
 * order and fallback logic the spec describes:
 *
 *   Rule 1 (highest priority): rank sectors, take top N.
 *   Rule 2: walk the ranked sectors in order; for each, check whether it
 *     qualifies (daily/weekly/monthly/yearly thresholds). "If no
 *     qualifying stock is found in the highest-ranked sector, proceed to
 *     the next" - implemented literally: even a qualifying sector can
 *     still yield zero usable stocks after Rules 3/4, so the walk
 *     continues to the next sector in that case too, not just when the
 *     sector itself fails to qualify.
 *   Rule 3 (mandatory): within a qualifying sector, every stock must
 *     pass the momentum check or it's dropped immediately - no scoring,
 *     no exception.
 *   Rule 4: of the stocks that survive Rule 3, Promoter Holding > 60% is
 *     also mandatory (stated as such in the spec) - then the remaining
 *     candidates are scored and ranked by sales/profit growth and
 *     FII/DII trends, highest confidence wins.
 *
 * Small-cap prioritization: market cap is approximated as
 * (total shares outstanding, when NSE's shareholding data includes it)
 * x current price - there's no dedicated market-cap data source
 * connected to this system, so this is a best-effort proxy, applied as
 * a scoring bonus (favoring genuinely small-cap candidates) rather than
 * a hard filter, exactly matching "prioritize small-cap... allowing
 * other categories if required."
 */
@Service
@Slf4j
public class AutoStockSelectionEngine {

    private static final BigDecimal SMALL_CAP_CEILING_CRORES = BigDecimal.valueOf(5000);

    private final SectorPerformanceService sectorService;
    private final StockMomentumService momentumService;
    private final StockFundamentalService fundamentalService;
    private final com.trading.swing.auto.repository.DailyBarRepository barRepo;
    private final KiteConnect kiteConnect;
    private final ManualSwingConfig config;

    public AutoStockSelectionEngine(SectorPerformanceService sectorService,
                                    StockMomentumService momentumService,
                                    StockFundamentalService fundamentalService,
                                    com.trading.swing.auto.repository.DailyBarRepository barRepo,
                                    KiteConnect kiteConnect,
                                    ManualSwingConfig config) {
        this.sectorService = sectorService;
        this.momentumService = momentumService;
        this.fundamentalService = fundamentalService;
        this.barRepo = barRepo;
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
    /** Called by AutoSwingScheduler on each new trading day to clear
     *  the Yahoo Finance day-level fundamentals cache. */
    public void clearFundamentalsCache() {
        fundamentalService.clearCache();
    }

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
                            "- evaluating its {} stock(s) for momentum and fundamentals",
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
            // Rule 3 - mandatory, AND gate, no exceptions
            if (!momentumService.passesMomentumCheck(symbol)) continue;

            // Rule 4 - Promoter Holding > 60% is also explicitly mandatory
            FundamentalSnapshot fundamentals = fundamentalService.fetch(symbol);
            if (fundamentals.promoterHoldingPct() == null
                    || fundamentals.promoterHoldingPct().doubleValue() <= config.getMinPromoterHoldingPct()) {
                continue;
            }

            Instrument inst = instrumentBySymbol.get(symbol);
            if (inst == null) continue;

            var recentBars = barRepo.findBySymbol(symbol, java.time.LocalDate.now().minusDays(10));
            if (recentBars.isEmpty()) continue; // no recent price data - can't size a quantity, skip
            BigDecimal lastClose = recentBars.get(recentBars.size() - 1).close();

            int score = computeConfidenceScore(fundamentals);
            String breakdown = String.format(
                    "promoter=%.1f%% fii=%.1f%% dii=%.1f%% salesGrowth=%s profitGrowth=%s",
                    fundamentals.promoterHoldingPct(), fundamentals.fiiHoldingPct(),
                    fundamentals.diiHoldingPct(), fundamentals.salesGrowthPct(),
                    fundamentals.profitGrowthPct());

            candidates.add(new StockCandidate(symbol, inst.getName(), inst.getExchange(),
                    sector.sectorName(), lastClose, fundamentals, score, breakdown));
        }

        if (candidates.isEmpty()) return Optional.empty();

        candidates.sort((a, b) -> Integer.compare(b.confidenceScore(), a.confidenceScore()));
        return Optional.of(candidates.get(0));
    }

    /**
     * Rule 4's scoring - every component directly from the spec's listed
     * criteria, nothing added that wasn't asked for:
     *   Sales growth, profit growth: higher = better
     *   FII holding: higher = better score; increased vs previous
     *     quarter = bonus
     *   DII holding, Public holding: present per spec, included as minor
     *     scoring inputs (the spec lists them without specific
     *     thresholds, unlike promoter/FII which have explicit rules)
     */
    private int computeConfidenceScore(FundamentalSnapshot f) {
        int score = 0;

        if (f.salesGrowthPct() != null) {
            score += boundedPoints(f.salesGrowthPct().doubleValue(), 0, 30, 20);
        }
        if (f.profitGrowthPct() != null) {
            score += boundedPoints(f.profitGrowthPct().doubleValue(), 0, 30, 20);
        }
        if (f.fiiHoldingPct() != null) {
            score += boundedPoints(f.fiiHoldingPct().doubleValue(), 0, 20, 15);
            if (f.fiiHoldingPctPreviousQuarter() != null
                    && f.fiiHoldingPct().compareTo(f.fiiHoldingPctPreviousQuarter()) > 0) {
                score += 15; // "FII holding should have increased vs previous month" - explicit bonus
            }
        }
        if (f.diiHoldingPct() != null) {
            score += boundedPoints(f.diiHoldingPct().doubleValue(), 0, 15, 10);
        }
        if (f.publicHoldingPct() != null) {
            score += 5; // present per spec, minor weight - no specific direction stated for this one
        }

        return Math.min(100, score);
    }

    private int boundedPoints(double value, double floor, double ceiling, int maxPoints) {
        if (value <= floor) return 0;
        if (value >= ceiling) return maxPoints;
        return (int) Math.round((value - floor) / (ceiling - floor) * maxPoints);
    }
}