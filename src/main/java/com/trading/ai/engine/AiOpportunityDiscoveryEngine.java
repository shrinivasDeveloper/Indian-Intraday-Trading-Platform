package com.trading.ai.engine;

import com.trading.ai.data.AiMarketDataService;
import java.math.BigDecimal;
import com.trading.ai.data.AiMarketDataService.AiSRLevel;
import com.trading.ai.data.AiMarketDataService.AiStructureLevels;
import com.trading.ai.data.AiSymbolUniverse;
import com.trading.ai.engine.AiMarketUnderstandingEngine.MarketSnapshot;
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
 *   1. f[58] trendline — now checks BOTH rising and falling trendlines
 *                        was only checking rising, always returning false in bearish markets
 *   2. f[59] pattern confidence — now includes f[55] (sweep high) for SHORT candidates
 *                        was (f[54]+f[56]+f[58])/3 → SHORT always got 0 confidence
 *   3. f[48–53] news — reads from news_scored_items MySQL table written by NewsTradingStrategy
 *                        was hardcoded 0 — ML model would never learn news patterns
 *   4. numericScore — includes news signal and both sweep directions
 *   5. buildCandidate — sets newsSummary from actual news if available
 *
 * 60 FEATURES (9 groups):
 *   A (0–7):   Price structure — MA distances, zone proximity, S/R
 *   B (8–15):  Momentum — returns, RSI, MACD, acceleration
 *   C (16–21): Volume — RVOL, spike, trend, buy pressure
 *   D (22–27): HTF trend — EMA alignment, daily bias, weekly
 *   E (28–33): Sector — change%, RS, alignment
 *   F (34–41): Market context — Nifty/BNF, VIX, breadth, time
 *   G (42–47): Symbol history — win rate, avg R, recency
 *   H (48–53): News — reads from shared MySQL table (zero coupling to NewsStrategy)
 *   I (54–59): AI patterns — liquidity sweeps, SR flip, trendline, channel
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
    private final JdbcTemplate                  jdbc;

    private final Map<String, AiSymbolHistory> symbolHistory = new ConcurrentHashMap<>();

    private static final double MIN_NUMERIC_SCORE = 35.0;
    private static final int    TOP_CANDIDATES    = 20;
    private static final LocalTime MARKET_OPEN    = LocalTime.of(9, 30);
    private static final LocalTime MARKET_CLOSE   = LocalTime.of(15, 30);

    public AiOpportunityDiscoveryEngine(AiMarketDataService aiData,
                                        AiSymbolUniverse universe,
                                        MarketDataService marketData,
                                        AiMarketUnderstandingEngine marketEngine,
                                        JdbcTemplate jdbc) {
        this.aiData       = aiData;
        this.universe     = universe;
        this.marketData   = marketData;
        this.marketEngine = marketEngine;
        this.jdbc         = jdbc;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MAIN SCAN — called every 5-minute candle close
    // ═══════════════════════════════════════════════════════════════════════

    public List<AiCandidate> discover(MarketSnapshot snapshot,
                                      Set<String> exclude,
                                      Set<String> firedToday) {
        long t0 = System.currentTimeMillis();
        Map<String, BigDecimal> pricesMap = marketData.getLastPricesSimple();
        AiMarketContext ctx = buildMarketContext(snapshot);

        List<AiCandidate> candidates = new ArrayList<>();
        int featuredCount = 0;

        for (String symbol : universe.getSymbols()) {
            if (exclude.contains(symbol)) continue;
            if (firedToday.contains(symbol)) continue;

            BigDecimal ltpBD = pricesMap.get(symbol);
            if (ltpBD == null) continue;
            double ltp = ltpBD.doubleValue();
            if (ltp < 50) continue;

            List<Candle> c5m   = aiData.get5mCandles(symbol);
            List<Candle> c15m  = aiData.get15mCandles(symbol);
            List<Candle> daily = aiData.getDailyCandles(symbol);
            if (c5m.size() < 10 || daily.size() < 5) continue;

            try {
                double[] features = buildFeatures(symbol, ltp, c5m, c15m, daily, ctx);
                featuredCount++;

                double numericScore = numericScore(features);
                if (numericScore < MIN_NUMERIC_SCORE) continue;

                String direction = suggestDirection(features);
                if (direction == null) continue;

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

        log.debug("[AI-DISCOVER] Scanned {} symbols | featured={} | top-{} selected | {}ms",
                universe.size(), featuredCount, top.size(),
                System.currentTimeMillis() - t0);
        return top;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 60-FEATURE VECTOR
    // ═══════════════════════════════════════════════════════════════════════

    private double[] buildFeatures(String symbol, double ltp,
                                   List<Candle> c5m, List<Candle> c15m,
                                   List<Candle> daily,
                                   AiMarketContext ctx) {
        double[] f = new double[60];
        AiStructureLevels structure = aiData.getStructure(symbol);

        // ── Group A: Price Structure (0–7) ──────────────────────────────
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

        // ── Group B: Momentum (8–15) ─────────────────────────────────────
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

        // ── Group C: Volume (16–21) ──────────────────────────────────────
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
        f[21] = 0;

        // ── Group D: HTF Trend (22–27) ───────────────────────────────────
        double dailyReturn = 0;
        if (daily.size() >= 2) {
            double prevClose = daily.get(daily.size()-2).getClose().doubleValue();
            double currClose = daily.get(daily.size()-1).getClose().doubleValue();
            dailyReturn = prevClose > 0 ? (currClose - prevClose) / prevClose : 0;
        }

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

        // ── Group E: Sector (28–33) ──────────────────────────────────────
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
                // New: actual intraday EMA bias — ML learns intraday sector momentum
                f[32] = norm(sd.intradayEmaDirection(), -1.0, 1.0);
                f[33] = norm(sd.changePercent(), -1.0, 1.0);
            }
        } catch (Exception ignored) {}

        // ── Group F: Market Context (34–41) ─────────────────────────────
        f[34] = ctx.niftyDirection;
        f[35] = ctx.bnfDirection;
        f[36] = norm(ctx.niftyAtrPct, 0, 0.8);
        // FIX: invert VIX normalisation — low VIX is GOOD (positive feature)
        // Old: norm(vix, 8, 30) gave +1.0 for VIX 30 — ML learned high VIX = good (WRONG)
        // New: -norm gives +0.8 for VIX 10 (calm) and -1.0 for VIX 30 (fearful)
        f[37] = -norm(ctx.vix, 8, 30);
        f[38] = norm(ctx.breadthRatio, 0.5, 1.5);
        f[39] = ctx.sessionTimeFraction;
        f[40] = norm(ctx.marketRegimeScore, 0, 100);
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        f[41] = now.isBefore(LocalTime.of(11, 30)) ? 1.0
                : now.isBefore(LocalTime.of(13, 30)) ? 0.6
                : now.isBefore(LocalTime.of(14, 30)) ? 0.3 : 0.0;

        // ── Group G: Symbol History (42–47) ─────────────────────────────
        AiSymbolHistory history = symbolHistory.getOrDefault(symbol,
                AiSymbolHistory.empty(symbol));
        f[42] = norm(history.getWinRate(), 0.3, 0.8);
        f[43] = norm(history.getAvgRMultiple(), 0.5, 3.0);
        f[44] = Math.min(1.0, history.getTimesThisWeek() / 5.0);
        f[45] = history.getLastOutcome();
        f[46] = history.getTotalTrades() > 0 ? 1.0 : 0.0;
        f[47] = 0;

        // ── Group H: News (48–53) ────────────────────────────────────────
        // FIX: reads from news_scored_items written by NewsTradingStrategy.
        // Zero coupling — reads via JdbcTemplate only, no News class imported.
        // If table not available → all zeros (safe fallback, trade still evaluates).
        NewsContext news = readNewsContext(symbol);
        if (news != null) {
            f[48] = 1.0;                                           // news exists
            f[49] = Math.min(1.0, news.score() / 100.0);          // news sentiment score
            f[50] = Math.max(0.0, 1.0 - news.ageMinutes() / 90.0); // freshness (decays at 90min)
            f[51] = news.corroborated() ? 1.0 : 0.5;              // source credibility
            f[52] = Math.min(1.0, news.score() / 80.0);           // conviction strength
            f[53] = news.corroborated() ? 1.0 : 0.0;              // corroboration flag
        }
        // else: f[48–53] remain 0.0 — no news found

        // ── Group I: AI Patterns (54–59) ────────────────────────────────
        f[54] = aiData.detectLiquiditySweepLow(c5m)  ? 1.0 : 0.0;
        f[55] = aiData.detectLiquiditySweepHigh(c5m) ? 1.0 : 0.0;
        f[56] = (structure != null)
                ? (aiData.detectSRFlip(c5m, structure, ltp) ? 1.0 : 0.0) : 0.0;

        // Channel position — where is price in last 20 candles range
        if (n5 >= 20) {
            double hi20 = c5m.subList(n5-20, n5).stream()
                    .mapToDouble(c -> c.getHigh().doubleValue()).max().orElse(ltp);
            double lo20 = c5m.subList(n5-20, n5).stream()
                    .mapToDouble(c -> c.getLow().doubleValue()).min().orElse(ltp);
            double range20 = hi20 - lo20;
            f[57] = range20 > 0 ? norm((ltp - lo20) / range20, 0, 1) : 0.5;
        } else {
            f[57] = 0.5; // middle if not enough candles
        }

        // FIX: trendline touch — checks BOTH rising (LONG setups) and falling (SHORT setups)
        // Old: only checked rising trendline → always false in bearish markets
        f[58] = (detectRisingTrendlineTouch(c15m, ltp)
                || detectFallingTrendlineTouch(c15m, ltp)) ? 1.0 : 0.0;

        // FIX: pattern confidence — include sweep_high for SHORT candidates
        // Old: (f[54] + f[56] + f[58]) / 3 — SHORT candidates always got 0 confidence
        // New: max(sweep_low, sweep_high) so direction-appropriate sweep scores correctly
        f[59] = (Math.max(f[54], f[55]) + f[56] + f[58]) / 3.0;

        return f;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // NUMERIC PRE-SCORE
    // FIX: includes news signal and both sweep directions
    // ═══════════════════════════════════════════════════════════════════════

    private double numericScore(double[] f) {
        double s = 0;
        s += Math.abs(f[3])  * 20;                          // EMA stack
        s += Math.abs(f[22]) * 20;                          // HTF trend
        s += Math.max(0, f[16]) * 15;                       // RVOL
        s += f[17] > 0 ? 10 : 0;                            // volume spike
        s += Math.max(f[54], f[55]) > 0 ? 15 : 0;          // FIX: either sweep pattern
        s += (f[30] > 0 || f[31] > 0) ? 10 : 0;            // sector aligned
        s += Math.max(0, Math.abs(f[8])) * 5;               // recent momentum
        s += Math.max(0, Math.abs(f[38] - 0.5)) * 10;      // breadth extreme
        s += f[48] > 0 ? Math.min(10, f[49] * 10) : 0;     // FIX: news boost (max 10pts)
        return Math.min(100, s);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // DIRECTION DETECTION
    // ═══════════════════════════════════════════════════════════════════════

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
        bearScore += f[55] > 0 ? 3 : 0;   // sweep high (sell signal)
        bearScore += f[8] < 0  ? 1 : 0;
        bearScore += f[34] < 0 ? 2 : 0;
        if (f[48] > 0 && f[49] < 0.4) bearScore += 2; // negative news

        if (bullScore <= 3 && bearScore <= 3) return null;
        return bullScore > bearScore ? "LONG" : "SHORT";
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CANDIDATE BUILDER
    // ═══════════════════════════════════════════════════════════════════════

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
                .liquiditySweep(f[54] > 0 || f[55] > 0)
                .srFlip(f[56] > 0)
                .trendlineTouch(f[58] > 0)
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

    // ═══════════════════════════════════════════════════════════════════════
    // MARKET CONTEXT BUILDER
    // ═══════════════════════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════════════════════
    // TRENDLINE DETECTION
    // FIX: separate rising and falling trendline checks
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Rising trendline — used for LONG setups.
     * Checks if LTP is touching a rising trendline from last 10 15m candle lows.
     */
    private boolean detectRisingTrendlineTouch(List<Candle> c15m, double ltp) {
        if (c15m.size() < 10) return false;
        int n = c15m.size();
        double low1 = c15m.get(n-10).getLow().doubleValue();
        double low2 = c15m.get(n-5).getLow().doubleValue();
        if (low2 <= low1) return false; // not rising
        double slope = (low2 - low1) / 5.0;
        double tl    = low2 + slope * 5;
        return Math.abs(ltp - tl) / tl < 0.008; // within 0.8% tolerance (was 0.5% — too tight)
    }

    /**
     * FIX: Falling trendline — used for SHORT setups.
     * Was missing entirely — caused f[58] to always be 0 for SHORT candidates.
     * Checks if LTP is touching a falling trendline from last 10 15m candle highs.
     */
    private boolean detectFallingTrendlineTouch(List<Candle> c15m, double ltp) {
        if (c15m.size() < 10) return false;
        int n = c15m.size();
        double high1 = c15m.get(n-10).getHigh().doubleValue();
        double high2 = c15m.get(n-5).getHigh().doubleValue();
        if (high2 >= high1) return false; // not falling
        double slope = (high2 - high1) / 5.0;
        double tl    = high2 + slope * 5;
        return Math.abs(ltp - tl) / tl < 0.008; // within 0.8% tolerance
    }

    // ═══════════════════════════════════════════════════════════════════════
    // NEWS CONTEXT — reads from shared MySQL table
    // Zero coupling to NewsStrategy — JdbcTemplate only
    // ═══════════════════════════════════════════════════════════════════════

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
            // Table not yet created or no data — safe to return null
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // NORMALISATION
    // ═══════════════════════════════════════════════════════════════════════

    private double norm(double value, double min, double max) {
        if (max == min) return 0;
        return Math.max(-1, Math.min(1, 2.0 * (value - min) / (max - min) - 1.0));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ACCESSORS
    // ═══════════════════════════════════════════════════════════════════════

    public void updateSymbolHistory(String symbol, AiSymbolHistory history) {
        symbolHistory.put(symbol, history);
    }

    public AiSymbolHistory getSymbolHistory(String symbol) {
        return symbolHistory.getOrDefault(symbol, AiSymbolHistory.empty(symbol));
    }

    private String getSector(String symbol) {
        return marketEngine.getSectorForSymbol(symbol);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // INNER TYPE — news context from MySQL
    // ═══════════════════════════════════════════════════════════════════════

    private record NewsContext(
            int     score,
            String  category,
            String  sentiment,
            long    ageMinutes,
            boolean corroborated,
            String  headline
    ) {}
}