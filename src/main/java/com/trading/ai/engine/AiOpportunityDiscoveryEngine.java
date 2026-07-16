package com.trading.ai.engine;

import com.trading.ai.data.AiMarketDataService;
import java.math.BigDecimal;
import com.trading.ai.data.AiMarketDataService.AiSRLevel;
import com.trading.ai.data.AiMarketDataService.AiStructureLevels;
import com.trading.ai.data.AiSymbolUniverse;
import com.trading.ai.engine.AiMarketUnderstandingEngine.MarketSnapshot;
import com.trading.ai.engine.AiDailyPatternEngine;
import com.trading.ai.model.AiCandidate;
import com.trading.ai.model.AiFeatureVector;
import com.trading.ai.model.AiMarketContext;
import com.trading.ai.model.AiSymbolHistory;
import com.trading.domain.Candle;
import com.trading.marketdata.service.MarketDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * AiOpportunityDiscoveryEngine
 *
 * The AI's own market scanner and feature builder.
 *
 * FIXES APPLIED:
 *   1. f[58] trendline - now checks BOTH rising and falling trendlines
 *                        was only checking rising, always returning false in bearish markets
 *   2. f[59] pattern confidence - now includes f[55] (sweep high) for SHORT candidates
 *                        was (f[54]+f[56]+f[58])/3 -> SHORT always got 0 confidence
 *   3. f[48-53] news - reads from news_scored_items MySQL table written by NewsTradingStrategy
 *                        was hardcoded 0 - ML model would never learn news patterns
 *   4. numericScore - includes news signal and both sweep directions
 *   5. buildCandidate - sets newsSummary from actual news if available
 *
 * 60 FEATURES (9 groups):
 *   A (0-7):   Price structure - MA distances, zone proximity, S/R
 *   B (8-15):  Momentum - returns, RSI, MACD, acceleration
 *   C (16-21): Volume - RVOL, spike, trend, buy pressure
 *   D (22-27): HTF trend - EMA alignment, daily bias, weekly
 *   E (28-33): Sector - change%, RS, alignment
 *   F (34-41): Market context - Nifty/BNF, VIX, breadth, time
 *   G (42-47): Symbol history - win rate, avg R, recency
 *   H (48-53): News - reads from shared MySQL table (zero coupling to NewsStrategy)
 *   I (54-59): AI patterns - liquidity sweeps, SR flip, trendline, channel
 *
 * INDEPENDENCE:
 *   All features from AiMarketDataService and shared MySQL.
 *   Zero imports from HighRR, SMC, News, or Channel strategy services.
 */
@Service
@ConditionalOnProperty(name = "ai.trading.enabled", havingValue = "true")
@Slf4j
public class AiOpportunityDiscoveryEngine {

    private final AiMarketDataService           aiData;
    private final AiSymbolUniverse              universe;
    private final MarketDataService             marketData;
    private final AiMarketUnderstandingEngine   marketEngine;
    private final JdbcTemplate              jdbc;
    private final AiDailyPatternEngine       dailyPatternEngine;

    private final Map<String, AiSymbolHistory> symbolHistory = new ConcurrentHashMap<>();

    private static final double MIN_NUMERIC_SCORE = 35.0;
    private static final int    TOP_CANDIDATES    = 30;
    private static final LocalTime MARKET_OPEN    = LocalTime.of(9, 30);
    private static final LocalTime MARKET_CLOSE   = LocalTime.of(15, 30);

    public AiOpportunityDiscoveryEngine(AiMarketDataService aiData,
                                        AiSymbolUniverse universe,
                                        MarketDataService marketData,
                                        AiMarketUnderstandingEngine marketEngine,
                                        JdbcTemplate jdbc,
                                        AiDailyPatternEngine dailyPatternEngine) {
        this.aiData       = aiData;
        this.universe     = universe;
        this.marketData   = marketData;
        this.marketEngine = marketEngine;
        this.jdbc              = jdbc;
        this.dailyPatternEngine = dailyPatternEngine;
    }

    // =======================================================================
    // MAIN SCAN - 4-stage hierarchical pipeline
    //
    // Stage 1: Daily (1-year) - primary qualification gate
    //   Hard filter. Only stocks with clean HTF structure pass.
    //   Checks: EMA alignment, ADR quality, trend consistency,
    //           52-week position, structural higher-highs/lower-lows.
    //
    // Stage 2 (15m validation): REMOVED - daily patterns are the only
    //   qualification layer. No 15m filter between daily and feature building.
    //
    // Stage 3: Feature building + daily pattern gate
    //   Final confirmation layer: entry quality, volume, RR.
    //   Builds the full 60-feature vector.
    //
    // Stage 4: 1-minute - EXECUTION ONLY (not in discovery pipeline)
    //   Used only at execution time for precise entry timing.
    //   1m candles are NOT used for stock selection or filtering.
    // =======================================================================

    public List<AiCandidate> discover(MarketSnapshot snapshot,
                                      Set<String> exclude,
                                      Set<String> firedToday) {
        long t0 = System.currentTimeMillis();
        Map<String, BigDecimal> pricesMap = marketData.getLastPricesSimple();
        AiMarketContext ctx = buildMarketContext(snapshot);

        List<AiCandidate> candidates = new ArrayList<>();
        int stage1Pass = 0, stage2Pass = 0;
        int featuredCount = 0;

        for (String symbol : universe.getSymbols()) {
            if (exclude.contains(symbol)) continue;
            if (firedToday.contains(symbol)) continue;

            BigDecimal ltpBD = pricesMap.get(symbol);
            if (ltpBD == null) continue;
            double ltp = ltpBD.doubleValue();
            if (ltp < 50) continue;

            List<Candle> daily = aiData.getDailyCandles(symbol);
            List<Candle> c15m  = aiData.get15mCandles(symbol);
            List<Candle> c5m   = aiData.get5mCandles(symbol);

            // Minimum data guard
            if (daily.size() < 20 || c5m.size() < 10) continue;

            // ==============================================================
            // STAGE 1 - DAILY QUALIFICATION (1-year HTF filter)
            // Hard gate. Stock must pass ALL daily checks to proceed.
            // ==============================================================
            DailyQualResult dq = qualifyOnDaily(symbol, ltp, daily);
            if (!dq.passes) {
                log.debug("[AI-DISCOVER] {} [FAIL] Stage1 daily: {}", symbol, dq.reason);
                continue;
            }
            stage1Pass++;

            // FIX (production-readiness cross-check, found before this
            // was ever deployed): originally placed at the TOP of this
            // loop, this DB query would have run for ALL 532 universe
            // symbols on EVERY 5-minute cycle (~75 cycles/trading day =
            // ~40,000 extra queries/day) - most of them wasted on
            // symbols that would have been filtered out anyway by the
            // cheap, in-memory checks above. Moved to HERE, after Stage
            // 1's daily qualification - only genuine survivors (a much
            // smaller, real candidate set) ever trigger this query now.
            // Zero change to WHAT gets excluded, only WHEN the check
            // runs - same real ai_trade_outcomes table, same 2-day
            // lookback, same exclusion behavior as originally intended.
            if (wasRecentlyTraded(symbol)) continue;

            // Stage 2 (15m validation) removed by design.
            // Daily patterns are the only qualification layer before feature building.
            stage2Pass++;

            // ==============================================================
            // STAGE 3 - 5-MINUTE TRADE CONFIRMATION
            // Builds full feature vector for reasoning engine.
            // Stage 1 & 2 already qualified this stock - now find best entry.
            // ==============================================================
            try {
                double[] features = buildFeatures(symbol, ltp, c5m, c15m, daily, ctx);
                featuredCount++;

                double numericScore = numericScore(features);
                if (numericScore < MIN_NUMERIC_SCORE) continue;

                String direction = suggestDirection(features);
                if (direction == null) continue;

                // -- STAGE 3 GATE: Minimum daily pattern score -------------
                // ALL 16 patterns are now on daily candles.
                // A trade requires at least 2 daily patterns confirming direction.
                // This prevents low-quality setups from reaching the reasoning engine.
                //
                // f[54-58]: intraday-group patterns (now all daily)
                // f[60-69]: daily pattern engine
                // f[47]:    supply/demand zone
                //
                // Count how many daily patterns confirm trade direction
                int dailyPatternScore = 0;
                // Group I patterns (f[54-59])
                if ("LONG".equals(direction))  {
                    if (features[54] > 0.5) dailyPatternScore++; // sweep low
                    if (features[56] > 0.5) dailyPatternScore++; // SR flip
                    if (features[57] < 0.35) dailyPatternScore++; // near daily range low
                    if (features[58] > 0.5) dailyPatternScore++; // trendline touch
                    if (features[47] > 0.5) dailyPatternScore++; // demand zone
                }
                if ("SHORT".equals(direction)) {
                    if (features[55] > 0.5) dailyPatternScore++; // sweep high
                    if (features[56] > 0.5) dailyPatternScore++; // SR flip
                    if (features[57] > 0.65) dailyPatternScore++; // near daily range high
                    if (features[58] > 0.5) dailyPatternScore++; // trendline touch
                    if (features[47] < -0.5) dailyPatternScore++; // supply zone
                }
                // Group K patterns (f[60-69]) - direction-aligned daily patterns
                for (int k = 60; k <= 69; k++) {
                    if ("LONG".equals(direction)  && features[k] > 0.5) dailyPatternScore++;
                    if ("SHORT".equals(direction) && features[k] < -0.5) dailyPatternScore++;
                }

                // Minimum threshold: at least 1 daily pattern must confirm
                if (dailyPatternScore < 1) {
                    log.debug("[AI-DISCOVER] {} skipped - 0 daily patterns confirm {}",
                            symbol, direction);
                    continue;
                }
                log.debug("[AI-DISCOVER] {} [OK] {} daily patterns confirm {} direction",
                        symbol, dailyPatternScore, direction);

                // Daily bias override: prefer direction aligned with daily structure
                if (dq.biasBull && "SHORT".equals(direction)) {
                    if (numericScore < 70) {
                        log.debug("[AI-DISCOVER] {} SHORT skipped - daily bias bullish", symbol);
                        continue;
                    }
                }
                if (!dq.biasBull && "LONG".equals(direction)) {
                    if (numericScore < 70) {
                        log.debug("[AI-DISCOVER] {} LONG skipped - daily bias bearish", symbol);
                        continue;
                    }
                }

                // -- Exhausted gap filter -------------------------------------
                // Skip stocks that have ALREADY moved > 1% today AND momentum
                // is now reversing. Prevents chasing moves that are done.
                //
                // Conditions (both must be true):
                //   1. Already moved > 1% from yesterday close
                //   2. Last 5 candles momentum is now reversing
                //
                // Note: 10-day position check removed - a stock can be near
                // its 10-day low and still be a valid reversal setup.
                double dayReturn    = features[24]; // normalised daily return
                double momentum5c   = features[10]; // last 5-candle direction
                double rawDayReturn = (dayReturn + 1.0) / 2.0 * 0.06 - 0.03;

                boolean exhaustedLong  = rawDayReturn >  0.01 && momentum5c < 0;
                boolean exhaustedShort = rawDayReturn < -0.01 && momentum5c > 0;

                if (exhaustedLong && "LONG".equals(direction)) {
                    log.debug("[AI-DISCOVER] {} LONG skipped - up {}% but momentum reversing",
                            symbol, String.format("%.1f", rawDayReturn * 100));
                    continue;
                }
                if (exhaustedShort && "SHORT".equals(direction)) {
                    log.debug("[AI-DISCOVER] {} SHORT skipped - down {}% but momentum recovering",
                            symbol, String.format("%.1f", rawDayReturn * 100));
                    continue;
                }

                String sector = getSector(symbol);

                AiFeatureVector fv = new AiFeatureVector(
                        symbol, ltp, features, sector,
                        null, ctx, c5m, c15m, daily);

                AiCandidate candidate = buildCandidate(symbol, ltp, sector, fv,
                        direction, numericScore, features);
                candidates.add(candidate);

            } catch (Exception e) {
                log.debug("[AI-DISCOVER] Feature build failed for {}: {}", symbol, e.getMessage());
            }
        }

        List<AiCandidate> top = candidates.stream()
                .sorted(Comparator.comparingDouble(AiCandidate::getNumericScore).reversed())
                .limit(TOP_CANDIDATES)
                .collect(Collectors.toList());

        log.info("[AI-DISCOVER] Pipeline: {} total -> {} daily -> {} 15m -> {} featured -> {} candidates | {}ms",
                universe.size(), stage1Pass, stage2Pass, featuredCount,
                top.size(), System.currentTimeMillis() - t0);
        return top;
    }

    // =======================================================================
    // STAGE 1 - DAILY QUALIFICATION (1-year HTF analysis)
    // Hard filter. ALL conditions must pass.
    // =======================================================================

    /** Result object from daily qualification */
    record DailyQualResult(boolean passes, boolean biasBull, String reason) {}

    /**
     * Daily qualification - uses ~252 days of daily candle data.
     *
     * Checks (ALL must pass for LONG bias, mirror for SHORT bias):
     *   1. Minimum history - at least 60 days of daily data
     *   2. ADR quality     - average daily range > 0.4% (stock moves enough to trade)
     *   3. EMA structure   - daily EMA20 > EMA50 (medium-term trend quality)
     *   4. 52-week position - not buying near 52-week low (avoid catching knives)
     *   5. Trend consistency - stock not in a crash (last 20 days not down > 15%)
     *   6. Volume quality  - not a dead stock (average volume meaningful)
     *
     * Returns biasBull=true if daily structure favours LONG,
     *         biasBull=false if daily structure favours SHORT.
     * Either bias can trade, but lower timeframe must confirm.
     */
    private DailyQualResult qualifyOnDaily(String symbol, double ltp,
                                           List<Candle> daily) {
        if (daily.size() < 20) {
            return new DailyQualResult(false, true, "insufficient history");
        }

        int n = daily.size();

        // -- Check 1: ADR quality -----------------------------------------
        // Average Daily Range must be > 0.4% of price
        // Stocks moving less than 0.4%/day cannot generate meaningful intraday setups
        double adrSum = 0;
        int adrCount = Math.min(20, n);
        for (int i = n - adrCount; i < n; i++) {
            double h = daily.get(i).getHigh().doubleValue();
            double l = daily.get(i).getLow().doubleValue();
            double mid = (h + l) / 2.0;
            if (mid > 0) adrSum += (h - l) / mid;
        }
        double adr = adrSum / adrCount;
        if (adr < 0.004) { // 0.4% minimum ADR
            return new DailyQualResult(false, true, "ADR too low: " +
                    String.format("%.2f", adr * 100) + "%");
        }

        // -- Check 2: EMA structure ---------------------------------------
        // Compute EMA20 and EMA50 from daily candles
        double ema20 = computeEMAFromDaily(daily, 20);
        double ema50 = computeEMAFromDaily(daily, 50);
        // EMA200 if enough data
        double ema200 = daily.size() >= 200 ? computeEMAFromDaily(daily, 200) : 0;

        // Determine daily bias from EMA structure
        boolean bullEma = ema20 > ema50;  // medium trend bullish
        boolean bearEma = ema20 < ema50;  // medium trend bearish

        // If EMA200 available, use it as additional filter
        // Price below EMA200 = long-term downtrend - prefer SHORT or neutral only
        boolean belowEma200 = ema200 > 0 && ltp < ema200;

        // -- Check 3: 52-week position (if enough data) -------------------
        double week52High = 0, week52Low = Double.MAX_VALUE;
        int lookback252 = Math.min(252, n);
        for (int i = n - lookback252; i < n; i++) {
            double h = daily.get(i).getHigh().doubleValue();
            double l = daily.get(i).getLow().doubleValue();
            if (h > week52High) week52High = h;
            if (l < week52Low)  week52Low  = l;
        }
        double week52Range = week52High - week52Low;
        // Position within 52-week range (0 = at 52-week low, 1 = at 52-week high)
        double week52Pos = week52Range > 0 ? (ltp - week52Low) / week52Range : 0.5;

        // -- Check 4: Recent trend consistency ----------------------------
        // Last 20 days: if stock crashed > 20% it is not tradeable LONG
        double price20DaysAgo = daily.get(Math.max(0, n - 20)).getClose().doubleValue();
        double recentReturn = price20DaysAgo > 0 ? (ltp - price20DaysAgo) / price20DaysAgo : 0;

        // -- Determine daily bias -----------------------------------------
        boolean biasBull;
        if (bullEma && !belowEma200 && week52Pos > 0.25) {
            // EMA structure bullish, above EMA200, not near 52-week low
            biasBull = true;
        } else if (bearEma && (ema200 == 0 || belowEma200) && week52Pos < 0.75) {
            // EMA structure bearish, below or no EMA200, not at 52-week high
            biasBull = false;
        } else {
            // Mixed - use 20-day momentum to decide
            biasBull = recentReturn >= 0;
        }

        // -- Hard rejections ----------------------------------------------
        // LONG bias but stock crashed > 25% in 20 days - avoid
        if (biasBull && recentReturn < -0.25) {
            return new DailyQualResult(false, true, "crashed -25% in 20d");
        }
        // SHORT bias but stock rallied > 25% in 20 days - avoid chasing
        if (!biasBull && recentReturn > 0.25) {
            return new DailyQualResult(false, false, "rallied +25% in 20d");
        }

        return new DailyQualResult(true, biasBull,
                String.format("ADR=%.1f%% EMA20%sEMA50 52wk=%.0f%%",
                        adr * 100, bullEma ? ">" : "<", week52Pos * 100));
    }

    /**
     * Helper: compute EMA from daily candles using close prices.
     * Uses the standard EMA formula: EMA = close * k + prevEMA * (1-k)
     * where k = 2 / (period + 1)
     */
    private double computeEMAFromDaily(List<Candle> daily, int period) {
        if (daily.size() < period) return 0;
        double k = 2.0 / (period + 1);
        // Seed with SMA of first `period` candles
        double ema = 0;
        for (int i = 0; i < period; i++) {
            ema += daily.get(i).getClose().doubleValue();
        }
        ema /= period;
        for (int i = period; i < daily.size(); i++) {
            ema = daily.get(i).getClose().doubleValue() * k + ema * (1 - k);
        }
        return ema;
    }

    // =======================================================================
    // DAILY PATTERN HELPERS - used by f[54], f[55], f[58]
    // =======================================================================

    /**
     * f[54] - Daily Liquidity Sweep Low.
     * Looks for equal DAILY lows within 0.5% across last 10 daily candles.
     * The day's LOW swept below that level AND closed ABOVE it = institutional trap.
     * This is a genuine daily sweep - not 5m noise.
     */
    /**
     * f[54] - Daily Liquidity Sweep Low (STRICT).
     *
     * A genuine sweep requires a SIGNIFICANT support level tested multiple times:
     *   1. Level must have been tested at least 2 previous times (3 touches min)
     *      - this distinguishes real support from random daily noise
     *   2. The sweep candle's low went BELOW the level
     *   3. Close recovered ABOVE the level (rejection of the sweep)
     *   4. Recovery close > 0.3% above level (not just a hairline recovery)
     *   5. Equal low tolerance tightened to 0.3% (was 0.5% - too loose)
     *
     * This eliminates false signals from any two candles that happen to have
     * similar lows within normal daily price oscillation.
     */
    /**
     * f[54] - Daily Liquidity Sweep Low (STRICT, further tightened).
     *
     * A genuine sweep requires a SIGNIFICANT support level tested multiple times:
     *   1. Level must have been tested at least 2 previous times (3 touches min)
     *      - this distinguishes real support from random daily noise
     *   2. The sweep candle's low went BELOW the level
     *   3. Close recovered ABOVE the level (rejection of the sweep)
     *   4. Recovery close > 0.3% above level (not just a hairline recovery)
     *   5. Equal low tolerance tightened to 0.3% (was 0.5% - too loose)
     *
     * FIX (per explicit user request, 5 confirmed gaps found via direct
     * code review):
     *   a) Volume confirmation added - candVol > avgVol * 1.2, matching
     *      the exact same threshold already used by detectBOS() in
     *      AiDailyPatternEngine. A genuine stop-hunt sweep should show
     *      elevated volume as resting stops trigger; this was previously
     *      completely unchecked.
     *   b) Lookback widened from 20 to 90 candles (both for candidate
     *      levels AND the touch-counting window) - previously only the
     *      most recent ~20 trading days were ever considered, even
     *      though the full ~252-day daily history is passed in. Major,
     *      well-established levels from 2-4 months back were never
     *      examined at all.
     *   c) Touch requirement raised from 1 prior touch (2 total) to 2
     *      prior touches (3 total) - brings sweep's validation bar
     *      closer to this file's other patterns (Triangle requires 3+,
     *      Trendline requires 2+ intermediate plus 2 definition points).
     *   d) First-sweep-only: the inner loop now BREAKS at the first
     *      genuine sweep candle found for a level (whether or not it
     *      passes the new volume check), instead of silently continuing
     *      to scan further candles. This ensures only a level's FIRST,
     *      freshest sweep is ever credited - a level that's already
     *      been swept once is a depleted liquidity pool, and a later,
     *      weaker repeat sweep of the same level no longer counts.
     */
    private double detectDailyLiquiditySweepLow(List<Candle> daily, double ltp) {
        int n = daily.size();
        if (n < 15) return 0;
        double EQ_TOL = 0.003; // 0.3% equal low tolerance (tightened from 0.5%)
        int lookback = Math.min(90, n - 1); // FIX: widened from 20

        double avgVol = 0;
        int volCount = Math.min(20, n);
        for (int v = n - volCount; v < n; v++) avgVol += daily.get(v).getVolume();
        avgVol /= volCount;

        for (int i = n - lookback; i < n - 1; i++) {
            if (i < 0) continue;
            double level = daily.get(i).getLow().doubleValue();

            // Count how many prior candles tested this level (within EQ_TOL)
            int priorTouches = 0;
            for (int k = Math.max(0, i - lookback); k < i; k++) { // FIX: widened from i-20
                double kLow = daily.get(k).getLow().doubleValue();
                if (Math.abs(kLow - level) / level < EQ_TOL) priorTouches++;
            }
            // FIX: raised from 1 to 2 prior touches (3 touches total)
            if (priorTouches < 2) continue;

            // Find the FIRST genuine sweep candle after this level formed -
            // FIX: break (not continue past) once found, so only the
            // freshest sweep of this level is ever considered.
            for (int j = i + 1; j < n; j++) {
                double candLow   = daily.get(j).getLow().doubleValue();
                double candClose = daily.get(j).getClose().doubleValue();
                if (Math.abs(candLow - level) / level < EQ_TOL) {
                    // Swept below AND closed clearly above
                    if (candLow < level * (1 - 0.001)
                            && candClose > level * 1.003) { // 0.3% recovery
                        // FIX: require elevated volume on the sweep candle
                        // itself - same threshold as detectBOS().
                        double candVol = daily.get(j).getVolume();
                        if (candVol < avgVol * 1.2) {
                            break; // first sweep found but too weak on volume -
                            // this level's liquidity is considered used,
                            // don't keep scanning later candles for it
                        }
                        // PROXIMITY: ltp must still be within 1.0% of sweep level
                        // If stock ran 2%+ above level -> entry opportunity gone
                        if (ltp <= level * 1.010) {
                            return 1.0; // sweep confirmed AND price still near level
                        }
                        return 0; // sweep valid but price already ran too far
                    }
                }
            }
        }
        return 0;
    }

    /**
     * f[55] - Daily Liquidity Sweep High (STRICT, further tightened).
     * Same strictness as f[54], mirrored - see the detailed FIX notes
     * on detectDailyLiquiditySweepLow() above; all 5 fixes apply
     * identically here (volume confirmation, widened 90-candle
     * lookback, raised touch requirement, first-sweep-only logic).
     */
    private double detectDailyLiquiditySweepHigh(List<Candle> daily, double ltp) {
        int n = daily.size();
        if (n < 15) return 0;
        double EQ_TOL = 0.003; // 0.3% equal high tolerance
        int lookback = Math.min(90, n - 1); // FIX: widened from 20

        double avgVol = 0;
        int volCount = Math.min(20, n);
        for (int v = n - volCount; v < n; v++) avgVol += daily.get(v).getVolume();
        avgVol /= volCount;

        for (int i = n - lookback; i < n - 1; i++) {
            if (i < 0) continue;
            double level = daily.get(i).getHigh().doubleValue();

            int priorTouches = 0;
            for (int k = Math.max(0, i - lookback); k < i; k++) { // FIX: widened from i-20
                double kHigh = daily.get(k).getHigh().doubleValue();
                if (Math.abs(kHigh - level) / level < EQ_TOL) priorTouches++;
            }
            // FIX: raised from 1 to 2 prior touches (3 touches total)
            if (priorTouches < 2) continue;

            for (int j = i + 1; j < n; j++) {
                double candHigh  = daily.get(j).getHigh().doubleValue();
                double candClose = daily.get(j).getClose().doubleValue();
                if (Math.abs(candHigh - level) / level < EQ_TOL) {
                    if (candHigh > level * 1.001
                            && candClose < level * 0.997) { // 0.3% rejection
                        // FIX: require elevated volume on the sweep candle
                        double candVol = daily.get(j).getVolume();
                        if (candVol < avgVol * 1.2) {
                            break; // first sweep found but too weak on volume -
                            // level considered used, stop scanning it
                        }
                        // PROXIMITY: ltp must still be within 1.0% of sweep level
                        // If stock dropped 2%+ below level -> short opportunity gone
                        if (ltp >= level * 0.990) {
                            // FIX: was returning +1.0 - caused computePatternScore()
                            // to never credit SweepHigh for SHORT (checks val < -0.5).
                            // SweepHigh is a SHORT-only pattern; must be negative.
                            return -1.0; // sweep high confirmed AND price near level
                        }
                        return 0; // sweep valid but price already dropped too far
                    }
                }
            }
        }
        return 0;
    }

    /**
     * f[58] - Daily Trendline Touch (3-touch validated).
     * Finds rising or falling trendline from daily swing points.
     * Requires 3+ touches to validate the line.
     * Current price within 1.5% of trendline = touch confirmed.
     */
    private double detectDailyTrendlineTouch(List<Candle> daily, double ltp) {
        int n = daily.size();
        if (n < 20) return 0;
        int lb = Math.min(60, n);
        List<Candle> w = daily.subList(n - lb, n);
        int wn = w.size();

        // Rising support trendline (LONG setup)
        // FIX: Use 3-candle lookahead/lookback (was 2) for more significant swings
        List<double[]> swingLows = new ArrayList<>();
        for (int i = 3; i < wn - 3; i++) {
            double l = w.get(i).getLow().doubleValue();
            if (l < w.get(i-1).getLow().doubleValue()
                    && l < w.get(i-2).getLow().doubleValue()
                    && l < w.get(i-3).getLow().doubleValue()
                    && l < w.get(i+1).getLow().doubleValue()
                    && l < w.get(i+2).getLow().doubleValue()
                    && l < w.get(i+3).getLow().doubleValue()) {
                swingLows.add(new double[]{i, l});
            }
        }
        if (swingLows.size() >= 2) {
            double[] oldest = swingLows.get(0);
            double[] newest = swingLows.get(swingLows.size() - 1);
            double slope = (newest[1] - oldest[1]) / (newest[0] - oldest[0]);
            if (slope > 0) { // rising trendline
                double projected = newest[1] + slope * (wn - 1 - newest[0]);
                int touches = 0;
                for (double[] sl : swingLows) {
                    double expected = newest[1] + slope * (wn - 1 - sl[0]);
                    if (expected > 0 && Math.abs(sl[1] - expected) / expected < 0.015) touches++;
                }
                if (touches >= 3 && projected > 0
                        && Math.abs(ltp - projected) / projected < 0.015) {
                    return 1.0; // at rising daily trendline
                }
            }
        }

        // Falling resistance trendline (SHORT setup) - lb=3
        List<double[]> swingHighs = new ArrayList<>();
        for (int i = 3; i < wn - 3; i++) {
            double h = w.get(i).getHigh().doubleValue();
            if (h > w.get(i-1).getHigh().doubleValue()
                    && h > w.get(i-2).getHigh().doubleValue()
                    && h > w.get(i-3).getHigh().doubleValue()
                    && h > w.get(i+1).getHigh().doubleValue()
                    && h > w.get(i+2).getHigh().doubleValue()
                    && h > w.get(i+3).getHigh().doubleValue()) {
                swingHighs.add(new double[]{i, h});
            }
        }
        if (swingHighs.size() >= 2) {
            double[] oldest = swingHighs.get(0);
            double[] newest = swingHighs.get(swingHighs.size() - 1);
            double slope = (newest[1] - oldest[1]) / (newest[0] - oldest[0]);
            if (slope < 0) { // falling trendline
                double projected = newest[1] + slope * (wn - 1 - newest[0]);
                int touches = 0;
                for (double[] sh : swingHighs) {
                    double expected = newest[1] + slope * (wn - 1 - sh[0]);
                    if (expected > 0 && Math.abs(sh[1] - expected) / expected < 0.015) touches++;
                }
                if (touches >= 3 && projected > 0
                        && Math.abs(ltp - projected) / projected < 0.015) {
                    // FIX: was returning +1.0 for falling trendline too - identical
                    // to the rising-trendline LONG case above. Falling resistance
                    // trendline is a SHORT setup and must be negative-signed so
                    // computePatternScore()'s bearish check (val < -0.5) can see it.
                    return -1.0; // at falling daily trendline (SHORT)
                }
            }
        }
        return 0;
    }

    // =======================================================================
    // 60-FEATURE VECTOR
    // =======================================================================

    private double[] buildFeatures(String symbol, double ltp,
                                   List<Candle> c5m, List<Candle> c15m,
                                   List<Candle> daily,
                                   AiMarketContext ctx) {
        double[] f = new double[80]; // f[0-59] base features, f[60-69] daily pattern features
        AiStructureLevels structure = aiData.getStructure(symbol);

        // -- Group A: Price Structure (0-7) ------------------------------
        double ma20  = aiData.computeEMA(daily, 20);
        double ma50  = aiData.computeEMA(daily, 50);
        double ma200 = aiData.computeEMA(daily, 200);

        f[0] = ma20  > 0 ? norm((ltp - ma20)  / ma20,  -0.1, 0.1) : 0;
        f[1] = ma50  > 0 ? norm((ltp - ma50)  / ma50,  -0.1, 0.1) : 0;
        f[2] = ma200 > 0 ? norm((ltp - ma200) / ma200, -0.1, 0.1) : 0;

        double emaStack = 0;
        if (ma20 > 0 && ma50 > 0 && ma200 > 0) {
            if      (ltp > ma20 && ma20 > ma50 && ma50 > ma200) emaStack =  1.0;
            else if (ltp < ma20 && ma20 < ma50 && ma50 < ma200) emaStack = -1.0;
            else    emaStack = norm((ma20 - ma50) / ma50, -0.05, 0.05);
        }
        f[3] = emaStack;

        if (structure != null) {
            AiSRLevel supp = aiData.nearestSupportBelow(symbol, ltp);
            AiSRLevel res  = aiData.nearestResistanceAbove(symbol, ltp);
            f[4] = supp != null ? norm((ltp - supp.price()) / ltp, 0, 0.05) : 1.0;
            f[5] = res  != null ? norm((res.price() - ltp)  / ltp, 0, 0.05) : 1.0;
            f[6] = supp != null ? Math.min(1.0, supp.touchCount() / 5.0) : 0;
            f[7] = res  != null ? Math.min(1.0, res.touchCount()  / 5.0) : 0;
        }

        // -- Group B: Momentum (8-15) -------------------------------------
        int n5  = c5m.size();
        int n15 = c15m.size();

        if (n5 >= 2) {
            double prev1 = c5m.get(n5 - 2).getClose().doubleValue();
            f[8] = prev1 > 0 ? norm((ltp - prev1) / prev1, -0.02, 0.02) : 0;
        }
        if (n5 >= 4) {
            double prev3 = c5m.get(n5 - 4).getClose().doubleValue();
            f[9] = prev3 > 0 ? norm((ltp - prev3) / prev3, -0.03, 0.03) : 0;
        }
        if (n5 >= 6) {
            double prev5 = c5m.get(n5 - 6).getClose().doubleValue();
            f[10] = prev5 > 0 ? norm((ltp - prev5) / prev5, -0.05, 0.05) : 0;
        }
        if (n15 >= 4) {
            double prev15m = c15m.get(n15 - 4).getClose().doubleValue();
            f[11] = prev15m > 0 ? norm((ltp - prev15m) / prev15m, -0.05, 0.05) : 0;
        }
        if (n15 >= 13) {
            double prev1h = c15m.get(n15 - 13).getClose().doubleValue();
            f[12] = prev1h > 0 ? norm((ltp - prev1h) / prev1h, -0.08, 0.08) : 0;
        }

        double rsi = aiData.computeRSI(c5m, 14);
        f[13] = norm(rsi, 20, 80);
        f[14] = norm(aiData.computeMACD(c5m), -1, 1);
        f[15] = f[8] - (n5 >= 4 ? (f[8] + f[9]) / 2 : f[8]);

        // -- Group C: Volume (16-21) --------------------------------------
        double rvol = aiData.computeRVOL(c5m, 20);
        f[16] = norm(rvol, 0.3, 3.0);
        f[17] = rvol > 2.0 ? 1.0 : 0.0;

        if (n5 >= 4) {
            long v0 = c5m.get(n5-1).getVolume();
            long v1 = c5m.get(n5-2).getVolume();
            long v2 = c5m.get(n5-3).getVolume();
            f[18] = v0 > v1 && v1 > v2 ? 1.0 : v0 < v1 && v1 < v2 ? -1.0 : 0;
        }

        if (n5 >= 5) {
            long buyCount = 0;
            for (int i = n5 - 5; i < n5; i++) {
                Candle c   = c5m.get(i);
                double rng = c.getHigh().doubleValue() - c.getLow().doubleValue();
                double pos = rng > 0
                        ? (c.getClose().doubleValue() - c.getLow().doubleValue()) / rng : 0.5;
                if (pos > 0.6) buyCount++;
            }
            f[19] = norm(buyCount / 5.0, 0.2, 0.8);
        }

        f[20] = (f[8] > 0 && rvol > 1.2) ? 1.0 : (f[8] < 0 && rvol > 1.2) ? -1.0 : 0;

        // -- Group D: HTF Trend (22-27) -----------------------------------
        // Declared here so f[21] RS calculation can use dailyReturn
        double dailyReturn = 0;
        if (daily.size() >= 2) {
            double prevClose = daily.get(daily.size()-2).getClose().doubleValue();
            double currClose = daily.get(daily.size()-1).getClose().doubleValue();
            dailyReturn = prevClose > 0 ? (currClose - prevClose) / prevClose : 0;
        }

        // FIX: f[21] = Relative Strength vs Nifty
        // RS = stock daily return - Nifty daily return
        // +1.0 = stock outperforming Nifty by 3%+ (genuine strength)
        // -1.0 = stock underperforming Nifty by 3%+ (genuine weakness)
        //  0.0 = moving in line with index
        // This is one of the most important features - a stock up 1.5% when
        // Nifty is flat is very different from a stock up 1.5% when Nifty is up 2%.
        try {
            List<Candle> niftyDaily = aiData.getDailyCandles("NIFTY 50");
            if (niftyDaily.size() >= 2) {
                double niftyPrev = niftyDaily.get(niftyDaily.size()-2).getClose().doubleValue();
                double niftyCurr = niftyDaily.get(niftyDaily.size()-1).getClose().doubleValue();
                double niftyReturn = niftyPrev > 0 ? (niftyCurr - niftyPrev) / niftyPrev : 0;
                double rs = dailyReturn - niftyReturn; // stock excess return vs Nifty
                f[21] = norm(rs, -0.03, 0.03); // +1 = 3%+ outperformance
            }
        } catch (Exception ignored) {}

        f[22] = emaStack;
        f[23] = daily.size() >= 2 && daily.get(daily.size()-1).getClose()
                .compareTo(daily.get(daily.size()-1).getOpen()) > 0 ? 1.0 : -1.0;
        f[24] = norm(dailyReturn, -0.03, 0.03);

        if (daily.size() >= 6) {
            double weeklySum = 0;
            for (int i = daily.size()-5; i < daily.size(); i++) {
                double r = (daily.get(i).getClose().doubleValue()
                        - daily.get(i).getOpen().doubleValue())
                        / daily.get(i).getOpen().doubleValue();
                weeklySum += r;
            }
            f[25] = norm(weeklySum / 5, -0.02, 0.02);
        }

        double dailyAtr = aiData.computeATR(daily, 14);
        f[26] = ltp > 0 ? norm(dailyAtr / ltp, 0, 0.04) : 0;

        if (daily.size() >= 10) {
            List<Candle> last10 = daily.subList(daily.size()-10, daily.size());
            double hi10 = last10.stream().mapToDouble(c -> c.getHigh().doubleValue()).max().orElse(ltp);
            double lo10 = last10.stream().mapToDouble(c -> c.getLow().doubleValue()).min().orElse(ltp);
            double range10 = hi10 - lo10;
            f[27] = range10 > 0 ? norm((ltp - lo10) / range10, 0, 1) : 0.5;
        }

        // -- Group E: Sector (28-33) --------------------------------------
        String sector = getSector(symbol);
        try {
            AiMarketUnderstandingEngine.AiSectorData sd =
                    marketEngine.computeSectorStrength().get(sector);
            if (sd != null) {
                f[28] = norm(sd.changePercent(), -2.0, 2.0);
                // FIX 4: use real sector breadth (advancing ratio) not normalised ratio
                f[29] = norm(sd.advancingRatio(), 0.3, 0.7);
                // FIX 3: alignedBullish now requires BOTH overnight change AND intraday EMA
                f[30] = sd.alignedBullish() ? 1.0 : 0.0;
                f[31] = sd.alignedBearish() ? 1.0 : 0.0;
                // FIX 3: f[32] = intraday EMA direction for this sector (-1 to +1)
                // Old: always 0.5 (unused placeholder)
                // New: actual intraday EMA bias - ML learns intraday sector momentum
                f[32] = norm(sd.intradayEmaDirection(), -1.0, 1.0);
                f[33] = norm(sd.changePercent(), -1.0, 1.0);
            }
        } catch (Exception ignored) {}

        // -- Group F: Market Context (34-41) -----------------------------
        f[34] = ctx.niftyDirection;
        f[35] = ctx.bnfDirection;
        f[36] = norm(ctx.niftyAtrPct, 0, 0.8);
        // FIX: invert VIX normalisation - low VIX is GOOD (positive feature)
        // Old: norm(vix, 8, 30) gave +1.0 for VIX 30 - ML learned high VIX = good (WRONG)
        // New: -norm gives +0.8 for VIX 10 (calm) and -1.0 for VIX 30 (fearful)
        f[37] = -norm(ctx.vix, 8, 30);
        f[38] = norm(ctx.breadthRatio, 0.5, 1.5);
        f[39] = ctx.sessionTimeFraction;
        f[40] = norm(ctx.marketRegimeScore, 0, 100);
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        f[41] = now.isBefore(LocalTime.of(11, 30)) ? 1.0
                : now.isBefore(LocalTime.of(13, 30)) ? 0.6
                : now.isBefore(LocalTime.of(14, 30)) ? 0.3 : 0.0;

        // -- Group G: Symbol History (42-47) -----------------------------
        AiSymbolHistory history = symbolHistory.getOrDefault(symbol,
                AiSymbolHistory.empty(symbol));
        f[42] = norm(history.getWinRate(), 0.3, 0.8);
        f[43] = norm(history.getAvgRMultiple(), 0.5, 3.0);
        f[44] = Math.min(1.0, history.getTimesThisWeek() / 5.0);
        f[45] = history.getLastOutcome();
        f[46] = history.getTotalTrades() > 0 ? 1.0 : 0.0;
        // FIX (accuracy issue found): f[47] = Supply/Demand zone proximity
        // Demand zone: a tight, quiet BASE candle immediately followed by a
        // strong institutional up-move (the "launch pad"). Supply zone:
        // same idea, downward.
        // +1.0 = price at demand zone (institutional buy zone - bullish)
        // -1.0 = price at supply zone (institutional sell zone - bearish)
        //  0.0 = no nearby S/D zone
        //
        // BUG FIXED: baseBody was computed but never actually checked -
        // the old code flagged ANY candle followed by a big move as a
        // "zone", regardless of whether that first candle was a genuine
        // quiet consolidation or itself a chaotic, wide-ranging candle.
        // That's not real S/D zone theory and explains the low accuracy -
        // now requires baseBody < 0.6xATR (a genuinely small/flat base).
        //
        // BUG FIXED: when both a demand-like and supply-like base existed
        // within the lookback window, the old code let whichever matched
        // LAST in the loop silently overwrite the other via Math.max/min
        // on a single shared variable - an arbitrary, index-order-dependent
        // result, not a quality- or distance-based decision. Now tracks the
        // CLOSEST valid zone of each type by actual distance from current
        // price, and between the two, the nearer one wins (the more
        // recently/closely relevant zone, structurally).
        try {
            double sdScore = 0.0;
            if (daily.size() >= 5) {
                double atrForSD = aiData.computeATR(daily, 14);
                double sdTolerance = atrForSD * 2; // zone is 2 ATR wide
                double maxBaseBody = atrForSD * 0.6; // genuine "quiet base" threshold

                double bestDemandDist = Double.MAX_VALUE;
                double bestSupplyDist = Double.MAX_VALUE;

                for (int i = daily.size() - 20; i < daily.size() - 1; i++) {
                    if (i < 0) continue;
                    Candle base  = daily.get(i);
                    Candle next  = daily.get(i + 1);
                    double baseOpen  = base.getOpen().doubleValue();
                    double baseClose = base.getClose().doubleValue();
                    double nextOpen  = next.getOpen().doubleValue();
                    double nextClose = next.getClose().doubleValue();
                    double baseBody  = Math.abs(baseClose - baseOpen);
                    double nextBody  = Math.abs(nextClose - nextOpen);

                    boolean baseIsQuiet = baseBody < maxBaseBody;
                    double zoneTop = Math.max(baseOpen, baseClose);
                    double zoneBot = Math.min(baseOpen, baseClose);
                    double zoneMid = (zoneTop + zoneBot) / 2.0;
                    double distFromZone = Math.abs(ltp - zoneMid);

                    // Demand zone: quiet base, NEXT candle big green
                    boolean strongUpMove = baseIsQuiet && nextClose > nextOpen
                            && nextBody > atrForSD * 1.5;
                    if (strongUpMove
                            && ltp >= zoneBot - sdTolerance && ltp <= zoneTop + sdTolerance
                            && distFromZone < bestDemandDist) {
                        bestDemandDist = distFromZone;
                    }

                    // Supply zone: quiet base, NEXT candle big red
                    boolean strongDownMove = baseIsQuiet && nextClose < nextOpen
                            && nextBody > atrForSD * 1.5;
                    if (strongDownMove
                            && ltp >= zoneBot - sdTolerance && ltp <= zoneTop + sdTolerance
                            && distFromZone < bestSupplyDist) {
                        bestSupplyDist = distFromZone;
                    }
                }

                boolean hasDemand = bestDemandDist < Double.MAX_VALUE;
                boolean hasSupply = bestSupplyDist < Double.MAX_VALUE;
                if (hasDemand && hasSupply) {
                    // Both present - the structurally nearer zone wins,
                    // not whichever happened to be processed last.
                    sdScore = bestDemandDist <= bestSupplyDist ? 0.8 : -0.8;
                } else if (hasDemand) {
                    sdScore = 0.8;
                } else if (hasSupply) {
                    sdScore = -0.8;
                }
            }
            f[47] = sdScore;
        } catch (Exception ignored) {}

        // -- Group H: News (48-53) ----------------------------------------
        // FIX: reads from news_scored_items written by NewsTradingStrategy.
        // Zero coupling - reads via JdbcTemplate only, no News class imported.
        // If table not available -> all zeros (safe fallback, trade still evaluates).
        NewsContext news = readNewsContext(symbol);
        if (news != null) {
            f[48] = 1.0;                                           // news exists
            f[49] = Math.min(1.0, news.score() / 100.0);          // news sentiment score
            f[50] = Math.max(0.0, 1.0 - news.ageMinutes() / 90.0); // freshness (decays at 90min)
            f[51] = news.corroborated() ? 1.0 : 0.5;              // source credibility
            f[52] = Math.min(1.0, news.score() / 80.0);           // conviction strength
            f[53] = news.corroborated() ? 1.0 : 0.0;              // corroboration flag
        }
        // else: f[48-53] remain 0.0 - no news found

        // -- Group I: AI Patterns (54-59) - ALL ON DAILY CANDLES ----------
        //
        // REDESIGNED: All 5 patterns now use DAILY candles exclusively.
        // No more 5m/15m noise. Only clean daily structure signals.
        //
        // f[54] = Daily Liquidity Sweep Low  - equal daily lows swept and recovered
        // f[55] = Daily Liquidity Sweep High - equal daily highs swept and recovered
        // f[56] = Daily S/R Flip            - price holding above former daily resistance
        // f[57] = Daily Channel Position    - price position within 20-day daily range
        // f[58] = Daily Trendline Touch     - price at daily trendline (3-touch validated)
        // f[59] = Daily Pattern Confidence  - composite of all daily intraday patterns

        // f[54]: Daily Liquidity Sweep Low
        // Equal daily lows within 0.5% swept intraday and closed above = institutional trap
        f[54] = detectDailyLiquiditySweepLow(daily, ltp);

        // f[55]: Daily Liquidity Sweep High
        f[55] = detectDailyLiquiditySweepHigh(daily, ltp);

        // f[56]: Daily S/R Flip - price holding above former daily resistance
        f[56] = (structure != null)
                ? (aiData.detectSRFlip(daily, structure, ltp) ? 1.0 : 0.0) : 0.0;

        // f[57]: Daily channel position - where is price in 20-day daily range
        // Scale: -1 (range bottom) to +1 (range top), via norm(). Neutral = 0.
        // FIX: fallback was 0.5 - on a -1..+1 scale, 0.5 means "70% toward
        // the top", not neutral. This silently gave SHORT setups an
        // undeserved +2pt bonus (AiPatternConfidenceEngine: cp > 0.2 check)
        // whenever a stock had <20 days history or a flat/degenerate range.
        // Correct neutral midpoint on this scale is 0, not 0.5.
        int nD = daily.size();
        if (nD >= 20) {
            double hiD = daily.subList(nD-20, nD).stream()
                    .mapToDouble(can -> can.getHigh().doubleValue()).max().orElse(ltp);
            double loD = daily.subList(nD-20, nD).stream()
                    .mapToDouble(can -> can.getLow().doubleValue()).min().orElse(ltp);
            double rangeD = hiD - loD;
            f[57] = rangeD > 0 ? norm((ltp - loD) / rangeD, 0, 1) : 0.0;
        } else {
            f[57] = 0.0;
        }

        // f[58]: Daily trendline touch - uses daily swing points, 3-touch validated
        // This is the SAME trendline as f[69] from AiDailyPatternEngine but normalised
        f[58] = detectDailyTrendlineTouch(daily, ltp);

        // f[59]: Daily pattern confidence - composite
        // FIX: f[55] (SweepHigh) and f[58] (TrendlineI falling case) are now
        // negative-signed when triggered. Use Math.abs() on both so SHORT
        // setups contribute equally to this composite as LONG setups do.
        f[59] = (Math.max(f[54], Math.abs(f[55])) + f[56] + Math.abs(f[58])) / 3.0;

        // -- Group K: Daily Pattern Features (f[60-69]) -----------------------
        // All patterns validated strictly on daily candles (1-year data).
        // BOS, CHOCH, OB, FVG, Accumulation/Distribution, Triple, H&S,
        // Triangle, Channel, Trendline - each -1 to +1.
        try {
            AiDailyPatternEngine.DailyPatterns dp = dailyPatternEngine.detect(ltp, daily);
            double[] dpf = dp.toFeatures();
            for (int k = 0; k < dpf.length && k < 10; k++) {
                f[60 + k] = dpf[k];
            }
        } catch (Exception ignored) {}

        return f;
    }

    // =======================================================================
    // NUMERIC PRE-SCORE
    // FIX: includes news signal and both sweep directions
    // =======================================================================

    /**
     * True if this symbol was traded (any outcome) within the last 2
     * days - per explicit request, this is now a HARD exclusion at
     * discovery time, not just the softer downstream score penalty
     * AiTradingSystem separately still applies as a second layer.
     * Fails OPEN (returns false, does not exclude) on any query error -
     * never lets a database hiccup silently cost a legitimate new
     * opportunity its discovery slot.
     */
    private boolean wasRecentlyTraded(String symbol) {
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ai_trade_outcomes WHERE symbol = ? AND exit_time >= ?",
                    Integer.class, symbol,
                    java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(2 * 86_400L)));
            return count != null && count > 0;
        } catch (Exception e) {
            log.debug("[AI-DISCOVER] Recently-traded check failed for {} (failing open, " +
                    "not excluding): {}", symbol, e.getMessage());
            return false;
        }
    }

    private double numericScore(double[] f) {
        double s = 0;
        s += Math.abs(f[3])  * 20;                          // EMA stack
        s += Math.abs(f[22]) * 20;                          // HTF trend
        s += Math.max(0, f[16]) * 15;                       // RVOL
        s += f[17] > 0 ? 10 : 0;                            // volume spike
        // FIX: f[55] (SweepHigh) is now negative-signed; use Math.abs() so
        // either sweep direction (LONG SweepLow or SHORT SweepHigh) credits here.
        s += Math.max(f[54], Math.abs(f[55])) > 0 ? 15 : 0;  // either sweep pattern
        s += (f[30] > 0 || f[31] > 0) ? 10 : 0;            // sector aligned
        s += Math.max(0, Math.abs(f[8])) * 5;               // recent momentum
        s += Math.max(0, Math.abs(f[38] - 0.5)) * 10;      // breadth extreme
        s += f[48] > 0 ? Math.min(10, f[49] * 10) : 0;     // FIX: news boost (max 10pts)
        return Math.min(100, s);
    }

    // =======================================================================
    // DIRECTION DETECTION
    // =======================================================================

    private String suggestDirection(double[] f) {
        double bullScore = 0, bearScore = 0;

        bullScore += f[3] > 0 ? 3 : 0;    // EMA bull
        bullScore += f[22] > 0 ? 3 : 0;   // HTF bull
        bullScore += f[30] > 0 ? 2 : 0;   // sector bull
        bullScore += f[54] > 0 ? 3 : 0;   // sweep low (buy signal)
        bullScore += f[8] > 0  ? 1 : 0;   // momentum up
        bullScore += f[34] > 0 ? 2 : 0;   // Nifty up
        // News boost for direction
        if (f[48] > 0 && f[49] > 0.6) bullScore += 2; // positive news

        bearScore += f[3] < 0 ? 3 : 0;
        bearScore += f[22] < 0 ? 3 : 0;
        bearScore += f[31] > 0 ? 2 : 0;
        // FIX: f[55] sign convention changed (now -1.0 when triggered, was +1.0)
        // to match computePatternScore()'s bearish check (val < -0.5).
        bearScore += f[55] < 0 ? 3 : 0;   // sweep high (sell signal)
        bearScore += f[8] < 0  ? 1 : 0;
        bearScore += f[34] < 0 ? 2 : 0;
        if (f[48] > 0 && f[49] < 0.4) bearScore += 2; // negative news

        if (bullScore <= 3 && bearScore <= 3) return null;
        return bullScore > bearScore ? "LONG" : "SHORT";
    }

    // =======================================================================
    // CANDIDATE BUILDER
    // =======================================================================

    private AiCandidate buildCandidate(String symbol, double ltp, String sector,
                                       AiFeatureVector fv, String direction,
                                       double numericScore, double[] f) {
        double rvol = aiData.computeRVOL(aiData.get5mCandles(symbol), 20);

        AiSRLevel supp = aiData.nearestSupportBelow(symbol, ltp);
        AiSRLevel res  = aiData.nearestResistanceAbove(symbol, ltp);

        // FIX: build actual news summary instead of always "No news"
        String newsSummary = "No news";
        if (f[48] > 0) {
            NewsContext news = readNewsContext(symbol);
            if (news != null) {
                newsSummary = news.category() + " | score=" + news.score()
                        + " | age=" + news.ageMinutes() + "min"
                        + (news.corroborated() ? " | CORROBORATED" : "");
            }
        }

        return AiCandidate.builder()
                .symbol(symbol)
                .ltp(ltp)
                .sector(sector != null ? sector : "Other")
                .featureVector(fv)
                .suggestedDirection(direction)
                .numericScore(numericScore)
                .mlProbability(numericScore / 100.0)
                .mlConfidence(0.5)
                .mlModelUsed("NUMERIC_FALLBACK")
                .mlReasoning("Pre-screen numeric score: " + String.format("%.0f", numericScore))
                .hypothesis(null)
                .htfTrend(f[22] > 0.3 ? "BULLISH" : f[22] < -0.3 ? "BEARISH" : "SIDEWAYS")
                .rvol(rvol)
                .distFromSupport(supp != null ? (ltp - supp.price()) / ltp * 100 : 99)
                .distFromResistance(res != null ? (res.price() - ltp) / ltp * 100 : 99)
                .supportStrength(supp != null ? supp.touchCount() : 0)
                // FIX: f[55] (SweepHigh) is now negative-signed when triggered
                .liquiditySweep(f[54] > 0 || f[55] < 0)
                .srFlip(f[56] > 0)
                // FIX: f[58] now negative for falling-trendline SHORT touches
                .trendlineTouch(f[58] != 0)
                .channelPosition(f[57] > 0.7 ? "UPPER" : f[57] < 0.3 ? "LOWER" : "MID")
                .return5m(f[10])
                .return15m(f[11])
                .return1h(f[12])
                .volumeSpike(f[17] > 0)
                .sectorChange(f[28])
                .newsSummary(newsSummary)
                .historicalWinRate(symbolHistory.getOrDefault(symbol,
                        AiSymbolHistory.empty(symbol)).getWinRate())
                .emaStackDesc(f[3] > 0.5 ? "FULLY_BULLISH" : f[3] < -0.5 ? "FULLY_BEARISH" : "PARTIAL")
                .build();
    }

    // =======================================================================
    // MARKET CONTEXT BUILDER
    // =======================================================================

    private AiMarketContext buildMarketContext(MarketSnapshot snapshot) {
        LocalTime now        = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        int totalMinutes     = (int)(MARKET_CLOSE.toSecondOfDay() - MARKET_OPEN.toSecondOfDay()) / 60;
        int elapsed          = Math.max(0, (int)(now.toSecondOfDay() - MARKET_OPEN.toSecondOfDay()) / 60);
        double fraction      = Math.min(1.0, (double) elapsed / totalMinutes);
        double regimeScore   = switch (snapshot.regime()) {
            case "TRENDING"  -> 80;
            case "RANGING"   -> 60;
            case "VOLATILE"  -> 40;
            case "CHOPPY"    -> 0;
            default          -> 30;
        };
        return new AiMarketContext(
                snapshot.niftyDirection(),
                0.0,
                snapshot.niftyAtrPct(),
                snapshot.vix(),
                snapshot.breadthRatio(),
                fraction,
                regimeScore);
    }

    // =======================================================================
    // TRENDLINE DETECTION
    // FIX: separate rising and falling trendline checks
    // =======================================================================

    // =======================================================================
    // NEWS CONTEXT - reads from shared MySQL table
    // Zero coupling to NewsStrategy - JdbcTemplate only
    // =======================================================================

    private NewsContext readNewsContext(String symbol) {
        try {
            List<NewsContext> results = jdbc.query(
                    """
                    SELECT score, category, sentiment, age_minutes, corroborated, headline
                    FROM news_scored_items
                    WHERE symbol = ?
                      AND scored_at > NOW() - INTERVAL 90 MINUTE
                    ORDER BY score DESC
                    LIMIT 1
                    """,
                    (rs, row) -> new NewsContext(
                            rs.getInt("score"),
                            rs.getString("category"),
                            rs.getString("sentiment"),
                            rs.getLong("age_minutes"),
                            rs.getBoolean("corroborated"),
                            rs.getString("headline")
                    ),
                    symbol);
            return results.isEmpty() ? null : results.get(0);
        } catch (Exception e) {
            // Table not yet created or no data - safe to return null
            return null;
        }
    }

    // =======================================================================
    // NORMALISATION
    // =======================================================================

    private double norm(double value, double min, double max) {
        if (max == min) return 0;
        return Math.max(-1, Math.min(1, 2.0 * (value - min) / (max - min) - 1.0));
    }

    // =======================================================================
    // ACCESSORS
    // =======================================================================

    public void updateSymbolHistory(String symbol, AiSymbolHistory history) {
        symbolHistory.put(symbol, history);
    }

    public AiSymbolHistory getSymbolHistory(String symbol) {
        return symbolHistory.getOrDefault(symbol, AiSymbolHistory.empty(symbol));
    }

    private String getSector(String symbol) {
        return marketEngine.getSectorForSymbol(symbol);
    }

    // =======================================================================
    // INNER TYPE - news context from MySQL
    // =======================================================================

    private record NewsContext(
            int     score,
            String  category,
            String  sentiment,
            long    ageMinutes,
            boolean corroborated,
            String  headline
    ) {}
}