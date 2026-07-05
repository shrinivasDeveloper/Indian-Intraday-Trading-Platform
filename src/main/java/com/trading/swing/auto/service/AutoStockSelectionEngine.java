package com.trading.swing.auto.service;

import com.trading.swing.auto.domain.StockCandidate;
import com.trading.swing.config.ManualSwingConfig;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.models.Instrument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

/**
 * AutoStockSelectionEngine - REMOVED sector-based filtering entirely,
 * per explicit instruction: "remove sector field put only stock filter
 * for all nse bse stock." Sector classification for the ~4,000+ stocks
 * outside NSE's real 750-stock official list was an honest KEYWORD
 * APPROXIMATION, not verified fact (confirmed earlier this session) -
 * removing it as a gate/grouping mechanism means every stock is now
 * judged purely on its OWN real price performance, with zero risk of
 * a wrong sector guess ever affecting which stocks get considered.
 *
 * NEW, SIMPLIFIED FLOW:
 *   1. Fetch the full NSE+BSE universe (unchanged).
 *   2. Filter to genuine tradable equity (EQ/BE) - this filter
 *      previously lived inside SectorPerformanceService.classifyAll(),
 *      now applied directly here since that class is no longer used
 *      for selection at all.
 *   3. Check EVERY stock directly against StockQualificationService
 *      (daily 4-6%, weekly >=15%, monthly >=weekly+5%, yearly >=70%)
 *      - no sector grouping, no sector ranking, no sector walk.
 *   4. Every qualifying stock also passes mandatory momentum
 *      (StockMomentumService, unchanged) and the 15-trading-day
 *      cooling period (unchanged).
 *   5. Among ALL qualifying stocks across the ENTIRE universe, the
 *      single best one (by liquidity) is selected.
 *
 * SectorPerformanceService, StockSectorClassifier, and
 * OfficialSectorMappingCache/Parser are no longer called anywhere in
 * this class - left in place as orphaned-but-harmless files (same
 * precedent as StockFundamentalService/FundamentalSnapshot from an
 * earlier fix this session), in case sector data is ever wanted again
 * for a different purpose.
 */
@Service
@Slf4j
public class AutoStockSelectionEngine {

    private final StockQualificationService qualificationService;
    private final StockMomentumService momentumService;
    private final com.trading.swing.auto.repository.DailyBarRepository barRepo;
    private final com.trading.swing.repository.ManualSwingTradeRepository tradeRepo;
    private final KiteConnect kiteConnect;
    private final ManualSwingConfig config;

    private static final int COOLING_PERIOD_TRADING_DAYS = 15;

    public AutoStockSelectionEngine(StockQualificationService qualificationService,
                                    StockMomentumService momentumService,
                                    com.trading.swing.auto.repository.DailyBarRepository barRepo,
                                    com.trading.swing.repository.ManualSwingTradeRepository tradeRepo,
                                    KiteConnect kiteConnect,
                                    ManualSwingConfig config) {
        this.qualificationService = qualificationService;
        this.momentumService = momentumService;
        this.barRepo = barRepo;
        this.tradeRepo = tradeRepo;
        this.kiteConnect = kiteConnect;
        this.config = config;
    }

    /**
     * Fetches the FULL NSE+BSE instrument list directly from
     * KiteConnect - deliberately NOT using the existing
     * InstrumentCacheService.getEquityInstruments(), which filters to
     * NSE only (confirmed by reading its source). Spec explicitly
     * requires "every NSE and BSE listed stock... no listed stock
     * excluded."
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

        Map<String, Instrument> deduplicated = new LinkedHashMap<>();
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
     * Runs the complete selection process directly across every NSE+BSE
     * stock - no sector grouping, no sector ranking. Returns empty if
     * no stock in the ENTIRE universe qualifies.
     */
    public Optional<StockCandidate> selectBestStock() {
        List<Instrument> universe = fetchFullNseAndBseUniverse();
        if (universe.isEmpty()) {
            log.error("[AUTO-SELECT] Empty instrument universe - cannot proceed");
            return Optional.empty();
        }

        // FIX: EQ/BE filter previously lived inside
        // SectorPerformanceService.classifyAll() - now applied directly
        // here, since that class is no longer used for selection at all.
        // Without this, futures/options contracts (which the raw
        // universe fetch above also returns) would incorrectly be
        // treated as "stocks" by the qualification checks below.
        List<StockCandidate> candidates = new ArrayList<>();
        int totalEquity = 0;

        for (Instrument inst : universe) {
            String type = inst.getInstrument_type();
            if (!"EQ".equals(type) && !"BE".equals(type)) continue;
            if (inst.getTradingsymbol() == null) continue;
            totalEquity++;

            String symbol = SymbolNormalizer.normalize(inst.getTradingsymbol());

            StockQualificationService.QualificationResult qual = qualificationService.check(symbol);
            if (!qual.qualifies()) {
                log.debug("[AUTO-SELECT] {} does not qualify: {}", symbol, qual.reason());
                continue;
            }

            if (!momentumService.passesMomentumCheck(symbol)) {
                log.debug("[AUTO-SELECT] {} qualified on performance but failed mandatory " +
                        "momentum check", symbol);
                continue;
            }

            if (isInCoolingPeriod(symbol)) {
                log.debug("[AUTO-SELECT] {} skipped - within {}-trading-day cooling period",
                        symbol, COOLING_PERIOD_TRADING_DAYS);
                continue;
            }

            var recentBars = barRepo.findBySymbol(symbol, java.time.LocalDate.now().minusDays(10));
            if (recentBars.isEmpty()) continue;
            BigDecimal lastClose = recentBars.get(recentBars.size() - 1).close();

            long avgVolume = (long) recentBars.stream()
                    .skip(Math.max(0, recentBars.size() - 5))
                    .mapToLong(b -> b.volume())
                    .average().orElse(0);

            int score = computeConfidenceScore(avgVolume);
            String breakdown = String.format(
                    "daily=%.2f%% weekly=%.2f%% monthly=%.2f%% yearly=%.2f%% avgVolume(5d)=%,d shares - " +
                            "qualified + momentum-confirmed (no sector filter applied)",
                    qual.dailyPct(), qual.weeklyPct(), qual.monthlyPct(), qual.yearlyPct(), avgVolume);

            candidates.add(new StockCandidate(symbol, inst.getName(), inst.getExchange(),
                    "N/A", lastClose, score, breakdown));
        }

        log.info("[AUTO-SELECT] Evaluated {} equity instruments across full NSE+BSE universe - " +
                "{} qualifying candidate(s) found", totalEquity, candidates.size());

        if (candidates.isEmpty()) {
            log.warn("[AUTO-SELECT] No qualifying stock found across the entire NSE+BSE universe " +
                    "today - no automated trade will be placed");
            return Optional.empty();
        }

        candidates.sort((a, b) -> Integer.compare(b.confidenceScore(), a.confidenceScore()));
        StockCandidate best = candidates.get(0);
        log.info("[AUTO-SELECT] Final selection: {} (score={}/100) - {}",
                best.symbol(), best.confidenceScore(), best.scoreBreakdown());
        return Optional.of(best);
    }

    private int computeConfidenceScore(long avgVolume) {
        return boundedPoints(avgVolume, 0, 100_000, 100);
    }

    private boolean isInCoolingPeriod(String symbol) {
        try {
            var lastBuy = tradeRepo.findMostRecentBuyDate(symbol);
            if (lastBuy.isEmpty()) return false;
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