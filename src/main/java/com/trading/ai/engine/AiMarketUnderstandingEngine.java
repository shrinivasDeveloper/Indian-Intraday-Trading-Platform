package com.trading.ai.engine;

import com.trading.ai.data.AiMarketDataService;
import com.trading.marketdata.service.VixService;
import com.trading.ai.data.AiSymbolUniverse;
import com.trading.domain.Candle;
import com.trading.marketdata.service.MarketDataService;
import com.trading.regime.service.MarketDirectionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AiMarketUnderstandingEngine
 *
 * The AI module's own market intelligence.
 *
 * FIXES IN THIS VERSION:
 *   1. BREADTH_BULL 1.30 -> 1.20  (60% advancing = TRENDING, was 65%)
 *   2. BREADTH_BEAR 0.75 -> 0.80  (60% declining = TRENDING, was 63%)
 *   3. Sector EMA trend intraday - 15m EMA stack per sector
 *   4. Sector breadth - advancing ratio per sector (was only change%)
 *   5. Intraday EMA vs overnight EMA - gap-aware EMA uses today's open
 *      as anchor, not yesterday's close. Gapped-up day now correctly
 *      classified BULLISH from open, not delayed by stale EMA.
 *   6. AiSectorData expanded with intradayEmaDirection and sectorBreadth
 *   7. MarketSnapshot expanded with sectorConfirmationScore
 *
 * NO changes to any other strategy component.
 */
@Service
@ConditionalOnProperty(name = "ai.trading.enabled", havingValue = "true")
@Slf4j
public class AiMarketUnderstandingEngine {

    private final MarketDirectionService marketDir;
    private final MarketDataService      marketData;
    private final AiMarketDataService    aiData;
    private final AiSymbolUniverse       universe;
    private final JdbcTemplate           jdbc;
    private final VixService             vixService;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // -- Regime state ------------------------------------------------------
    public enum Regime { TRENDING, RANGING, VOLATILE, CHOPPY, UNKNOWN }

    private volatile Regime    currentRegime        = Regime.UNKNOWN;
    private volatile int       regimeDuration       = 0;
    private volatile LocalDate regimeStartDate      = LocalDate.now(ZoneId.of("Asia/Kolkata"));
    private volatile double    regimeTradingQuality = 0.5;

    // -- AI's own sector map: sector name -> list of symbols ----------------
    private static final Map<String, List<String>> SECTOR_MAP = new LinkedHashMap<>();
    static {
        SECTOR_MAP.put("Banking",  List.of("HDFCBANK","ICICIBANK","SBIN","AXISBANK","KOTAKBANK",
                "BANKBARODA","FEDERALBNK","IDFCFIRSTB","BANDHANBNK","PNB","CANARABANK","INDIANB"));
        SECTOR_MAP.put("IT",       List.of("TCS","INFY","HCLTECH","WIPRO","TECHM","MPHASIS",
                "PERSISTENT","COFORGE","LTTS","KPITTECH","TATAELXSI","OFSS"));
        SECTOR_MAP.put("Pharma",   List.of("SUNPHARMA","DRREDDY","CIPLA","DIVISLAB","APOLLOHOSP",
                "AUROPHARMA","TORNTPHARM","ALKEM","IPCALAB","NATCOPHARM","LAURUSLABS","GLAND"));
        SECTOR_MAP.put("Auto",     List.of("MARUTI","BAJAJ-AUTO","EICHERMOT","HEROMOTOCO","TATAMOTORS",
                "TVSMOTORS","MOTHERSON","BHARATFORG","ESCORTS","BALKRISIND"));
        SECTOR_MAP.put("Metals",   List.of("TATASTEEL","JSWSTEEL","HINDALCO","VEDL","NMDC",
                "SAIL","NATIONALUM","MOIL","WELCORP","RATNAMANI"));
        SECTOR_MAP.put("Energy",   List.of("RELIANCE","ONGC","OIL","GAIL","PETRONET",
                "TATAPOWER","ADANIGREEN","ADANIPOWER","NTPC","POWERGRID"));
        SECTOR_MAP.put("FMCG",     List.of("HINDUNILVR","ITC","NESTLEIND","BRITANNIA","DABUR",
                "MARICO","GODREJCP","COLPAL","EMAMILTD","TATACONSUM"));
        SECTOR_MAP.put("Infra",    List.of("LT","ADANIPORTS","ADANIENT","GMRINFRA","IRB",
                "NBCC","RVNL","IRFC","CONCOR","BHEL"));
        SECTOR_MAP.put("Realty",   List.of("DLF","GODREJPROP","PRESTIGE","OBEROIRLTY",
                "PHOENIXLTD","BRIGADE","SOBHA","MAHLIFE"));
        SECTOR_MAP.put("Finance",  List.of("BAJFINANCE","BAJAJFINSV","MUTHOOTFIN","CHOLAFIN",
                "PFC","RECLTD","IREDA","IIFL","ISEC","ANGELONE"));
        SECTOR_MAP.put("Consumer", List.of("TITAN","ASIANPAINT","PIDILITIND","HAVELLS",
                "VOLTAS","WHIRLPOOL","BATAINDIA","RELAXO","DMART","TRENT"));
        SECTOR_MAP.put("Telecom",  List.of("BHARTIARTL","IDEA","TATACOMM","HFCL","STLTECH"));
    }

    // -- Sector data cache - refreshed once per classify() cycle -----------
    private volatile Map<String, AiSectorData> sectorCache  = new LinkedHashMap<>();
    private volatile long sectorCacheTs = 0;
    private static final long SECTOR_CACHE_TTL_MS = 60_000;

    // -- Regime outcome tracking --------------------------------------------
    private final Map<String, List<Double>> regimeOutcomes = new ConcurrentHashMap<>();

    // -- ATR thresholds -----------------------------------------------------
    private static final double ATR_CHOPPY   = 0.15;
    private static final double ATR_RANGING  = 0.30;
    private static final double ATR_VOLATILE = 0.50;

    // FIX 1 & 2: Breadth thresholds corrected for Indian market reality
    // Old: BREADTH_BULL=1.30 (65% advancing), BREADTH_BEAR=0.75 (37.5% advancing)
    // Real NSE: 60% advancing = genuine trending day
    // New: BREADTH_BULL=1.20 (60% advancing), BREADTH_BEAR=0.80 (40% advancing)
    private static final double BREADTH_BULL = 1.20;
    private static final double BREADTH_BEAR = 0.80;

    public AiMarketUnderstandingEngine(MarketDirectionService marketDir,
                                       MarketDataService marketData,
                                       AiMarketDataService aiData,
                                       AiSymbolUniverse universe,
                                       JdbcTemplate jdbc,
                                       VixService vixService) {
        this.marketDir   = marketDir;
        this.marketData  = marketData;
        this.aiData      = aiData;
        this.universe    = universe;
        this.jdbc        = jdbc;
        this.vixService  = vixService;
        createTablesIfNeeded();
    }

    // =======================================================================
    // REGIME CLASSIFICATION
    // =======================================================================

    public MarketSnapshot classify() {
        try {
            var dir = marketDir.getCurrentDirection();
            if (dir == null) return MarketSnapshot.unknown();

            double atrPct  = dir.niftyAtrPct();
            double breadth = computeOwnBreadth();
            double realVix = vixService.getCurrentVix();
            double vix     = realVix > 0 ? realVix : computeOwnVixProxy(atrPct);

            // FIX 5: Gap-aware Nifty direction
            // MarketDirectionService uses EMA computed from historical daily candles.
            // On a gap-up day, EMA20 might still be below price but the overnight
            // EMA stack hasn't updated yet. We compute an intraday EMA adjustment
            // using today's 5m candles to detect gap direction immediately at open.
            double intradayNiftyAdjustment = computeIntradayGapAdjustment();
            double niftyDir = dir.isLong() ? 1.0 : dir.isShort() ? -1.0 : 0.0;
            // Blend overnight EMA direction with intraday reality
            // Gap > 0.3% up AND 5m EMA rising = strengthen bullish signal
            // Gap > 0.3% down AND 5m EMA falling = strengthen bearish signal
            double blendedNiftyDir = blendDirection(niftyDir, intradayNiftyAdjustment);

            Regime newRegime;
            if      (atrPct < ATR_CHOPPY)   newRegime = Regime.CHOPPY;
            else if (atrPct > ATR_VOLATILE || vix > 22) newRegime = Regime.VOLATILE;
            else if (atrPct >= ATR_RANGING && (breadth >= BREADTH_BULL || breadth <= BREADTH_BEAR)) {
                // -- GAP-DAY INTRADAY CONFIRMATION (KEY FIX) ------------------
                // On a gap-up/down day, ATR is inflated by the gap itself -
                // not by genuine intraday trend. Breadth is also high because
                // all stocks simply gapped with the index, not because buyers
                // are actively pushing price AFTER the open.
                //
                // Problem: Gap+flat = TRENDING (WRONG) -> fires LONG at gap top
                // Fix:     Gap+flat = RANGING  (RIGHT) -> score 80 required,
                //          no direction lock, doesn't force LONG-only
                //
                // Rule: Only call TRENDING on a gap day if intraday price
                // is actively confirming the gap direction (EMA9 slope > 0).
                // If gap exists but price is flat/reversing -> downgrade to RANGING.
                //
                // Non-gap days: intradayGapConfirmed = true (no penalty)
                boolean intradayGapConfirmed = isIntradayConfirmingGap(intradayNiftyAdjustment);
                newRegime = intradayGapConfirmed ? Regime.TRENDING : Regime.RANGING;
            }
            else                             newRegime = Regime.RANGING;

            if (!newRegime.equals(currentRegime)) {
                persistRegimeChange(currentRegime, newRegime, regimeDuration);
                log.info("[AI-UNDERSTAND] Regime: {} -> {} (ATR={}% VIX={} breadth={})",
                        currentRegime, newRegime,
                        String.format("%.2f", atrPct),
                        String.format("%.1f", vix),
                        String.format("%.2f", breadth));
                regimeDuration  = 0;
                regimeStartDate = LocalDate.now(ZoneId.of("Asia/Kolkata"));
            } else {
                regimeDuration++;
            }
            currentRegime = newRegime;

            double trendStrength         = computeTrendStrength(atrPct, breadth, dir.isTrendTradeable());
            String leadingSector         = computeLeadingSector();
            double sessionQuality        = computeSessionQuality(atrPct, breadth, vix);
            double sectorConfirmation    = computeSectorConfirmationScore(blendedNiftyDir);

            return new MarketSnapshot(
                    newRegime.name(), atrPct, breadth, vix,
                    trendStrength, leadingSector, sessionQuality,
                    regimeDuration, blendedNiftyDir,
                    sectorConfirmation,
                    dir.isTradeable()
            );
        } catch (Exception e) {
            log.debug("[AI-UNDERSTAND] Classify error: {}", e.getMessage());
            return MarketSnapshot.unknown();
        }
    }

    // =======================================================================
    // FIX 5: INTRADAY GAP ADJUSTMENT
    // Detects whether today opened with a significant gap vs yesterday close
    // and whether intraday 5m candles confirm or deny that gap direction.
    // =======================================================================

    private double computeIntradayGapAdjustment() {
        try {
            List<Candle> nifty5m    = aiData.get5mCandles("NIFTY 50");
            List<Candle> niftyDaily = aiData.getDailyCandles("NIFTY 50");

            if (nifty5m.size() < 6 || niftyDaily.size() < 2) return 0.0;

            int n = nifty5m.size();

            // -- 1. Gap: today open vs yesterday close ---------------------
            double prevClose = niftyDaily.get(niftyDaily.size() - 2).getClose().doubleValue();
            double todayOpen = nifty5m.get(0).getOpen().doubleValue();
            double gapPct    = prevClose > 0 ? (todayOpen - prevClose) / prevClose * 100 : 0;

            // -- 2. Intraday EMA9 (fast EMA from 5m candles) --------------
            double ema9         = computeEMAFromCandles(nifty5m, 9);
            double currentPrice = nifty5m.get(n - 1).getClose().doubleValue();
            double intradayBias = currentPrice > ema9 * 1.0005 ? 1.0    // clearly above EMA9
                    : currentPrice < ema9 * 0.9995 ? -1.0   // clearly below EMA9
                    : 0.0;                                   // too close - neutral

            // -- 3. Intraday slope - last 6 candles (30 minutes) ----------
            // Checks whether price is actually MOVING in a direction
            // or just sitting flat after the gap.
            // slope > +0.02% per candle = active uptrend (~5 pts per candle on Nifty)
            // slope < -0.02% per candle = active downtrend
            double firstClose = nifty5m.get(n - 6).getClose().doubleValue();
            double slopePct   = firstClose > 0 ? (currentPrice - firstClose) / firstClose * 100 / 6 : 0;
            boolean slopeUp   = slopePct >  0.02;   // price actively rising last 30 min
            boolean slopeDown = slopePct < -0.02;   // price actively falling last 30 min

            // -- DAILY CONTEXT: is daily trend supporting? -----------------
            // Checks EMA20 vs EMA50 on daily candles for multi-day context.
            // Gap-up into a daily downtrend should NOT be called TRENDING UP.
            double dailyEma20 = computeEMAFromDaily(niftyDaily, 20);
            double dailyEma50 = computeEMAFromDaily(niftyDaily, 50);
            boolean dailyBull = dailyEma20 > dailyEma50 * 1.001; // EMA20 clearly above EMA50
            boolean dailyBear = dailyEma20 < dailyEma50 * 0.999; // EMA20 clearly below EMA50

            // -- COMBINED CONFIRMATION -------------------------------------
            // For BULLISH confirmation all 3 must agree:
            //   a) Gap was upward AND price still above EMA9
            //   b) Last 30 min price slope is positive (actively moving up)
            //   c) Daily EMA stack is bullish (not gap-up into downtrend)
            if (gapPct > 0.3 && intradayBias > 0 && slopeUp && dailyBull)  return 0.5;

            // For BEARISH confirmation:
            if (gapPct < -0.3 && intradayBias < 0 && slopeDown && dailyBear) return -0.5;

            // Gap exists but intraday slope or daily context not confirming
            return 0.0;

        } catch (Exception e) {
            return 0.0;
        }
    }

    /** EMA from daily candles (uses close prices) */
    private double computeEMAFromDaily(List<Candle> daily, int period) {
        if (daily.size() < period) return 0;
        double k   = 2.0 / (period + 1);
        double ema = daily.get(daily.size() - period).getClose().doubleValue();
        for (int i = daily.size() - period + 1; i < daily.size(); i++) {
            ema = daily.get(i).getClose().doubleValue() * k + ema * (1 - k);
        }
        return ema;
    }

    private double blendDirection(double overnightDir, double intradayAdj) {
        // Blend: overnight direction (weight 0.6) + intraday adjustment (weight 0.4)
        double blended = overnightDir * 0.6 + intradayAdj * 0.4;
        // Clamp to [-1, +1]
        return Math.max(-1.0, Math.min(1.0, blended));
    }

    /**
     * Checks if intraday price action CONFIRMS the gap direction.
     *
     * Returns TRUE (allow TRENDING) when:
     *   a) No significant gap today - pure intraday trend, no gap inflation
     *   b) Gap up AND price is holding above EMA9 (buyers present after open)
     *   c) Gap down AND price is holding below EMA9 (sellers present after open)
     *
     * Returns FALSE (downgrade to RANGING) when:
     *   Gap exists BUT price has reversed or gone flat after the open.
     *   This is the gap-up-then-stall pattern that caused false LONG entries.
     *
     * The intradayNiftyAdjustment from computeIntradayGapAdjustment() is:
     *   +0.5 = gap up AND price above EMA9 (confirmed gap)
     *   -0.5 = gap down AND price below EMA9 (confirmed gap)
     *    0.0 = gap exists but price not confirming (or no significant gap)
     *
     * For TRENDING to be valid on a gap day, we need confirmation (+0.5 or -0.5).
     * If adjustment = 0.0 on a gap day -> price flat/reversing -> RANGING.
     */
    private boolean isIntradayConfirmingGap(double intradayAdjustment) {
        try {
            List<Candle> nifty5m    = aiData.get5mCandles("NIFTY 50");
            List<Candle> niftyDaily = aiData.getDailyCandles("NIFTY 50");

            if (nifty5m.size() < 3 || niftyDaily.size() < 2) return true; // no data -> don't penalise

            double prevClose = niftyDaily.get(niftyDaily.size() - 2).getClose().doubleValue();
            double todayOpen = nifty5m.get(0).getOpen().doubleValue();
            double gapPct    = prevClose > 0 ? Math.abs(todayOpen - prevClose) / prevClose * 100 : 0;

            // No significant gap -> pure intraday trend -> allow TRENDING
            // Threshold: 0.4% gap (NIFTY ~24,000 -> 96 points = meaningful gap)
            if (gapPct < 0.4) return true;

            // Significant gap exists -> require intraday confirmation
            // intradayAdjustment = +0.5 or -0.5 means price is confirming gap direction
            // intradayAdjustment = 0.0 means gap-up but price flat/reversing -> NOT trending
            boolean confirmed = Math.abs(intradayAdjustment) >= 0.4;

            if (!confirmed) {
                log.info("[AI-UNDERSTAND] Gap day ({}%) but intraday not confirming (adj={}) -> RANGING not TRENDING",
                        String.format("%.2f", gapPct),
                        String.format("%.1f", intradayAdjustment));
            }
            return confirmed;

        } catch (Exception e) {
            return true; // safe default - don't penalise on error
        }
    }

    private double computeEMAFromCandles(List<Candle> candles, int period) {
        if (candles.size() < period) return 0;
        double k = 2.0 / (period + 1);
        double ema = candles.get(0).getClose().doubleValue();
        for (int i = 1; i < candles.size(); i++) {
            ema = candles.get(i).getClose().doubleValue() * k + ema * (1 - k);
        }
        return ema;
    }

    // =======================================================================
    // AI'S OWN BREADTH
    // =======================================================================

    private double computeOwnBreadth() {
        try {
            Map<String, BigDecimal> prices = marketData.getLastPricesSimple();
            int advancing = 0, declining = 0;

            for (String symbol : universe.getSymbols()) {
                BigDecimal ltpBD = prices.get(symbol);
                if (ltpBD == null) continue;
                double ltp = ltpBD.doubleValue();

                List<Candle> daily = aiData.getDailyCandles(symbol);
                if (daily.size() < 2) continue;

                double prevClose = daily.get(daily.size() - 2).getClose().doubleValue();
                if (prevClose <= 0) continue;

                double change = (ltp - prevClose) / prevClose;
                if (change > 0.001) advancing++;
                else if (change < -0.001) declining++;
            }

            int total = advancing + declining;
            if (total < 50) return 1.0;
            double ratio = (double) advancing / total * 2.0;
            log.debug("[AI-UNDERSTAND] Own breadth: {}/{} advancing = {}",
                    advancing, total, String.format("%.2f", ratio));
            return ratio;
        } catch (Exception e) {
            return 1.0;
        }
    }

    // =======================================================================
    // AI'S OWN VIX PROXY - fallback when VixService unavailable
    // =======================================================================

    private double computeOwnVixProxy(double niftyAtrPct) {
        if (niftyAtrPct < 0.15) return 11.0;
        if (niftyAtrPct < 0.30) return 11.0 + (niftyAtrPct - 0.15) / 0.15 * 6.0;
        if (niftyAtrPct < 0.50) return 17.0 + (niftyAtrPct - 0.30) / 0.20 * 7.0;
        if (niftyAtrPct < 0.80) return 24.0 + (niftyAtrPct - 0.50) / 0.30 * 8.0;
        return 32.0;
    }

    // =======================================================================
    // FIX 3 & 4: SECTOR STRENGTH WITH INTRADAY EMA + SECTOR BREADTH
    // Old: only computed change% vs yesterday close
    // New: adds intraday EMA direction (15m EMA9 vs price) per sector
    //      adds sector breadth (advancing ratio within sector)
    // =======================================================================

    public Map<String, AiSectorData> computeSectorStrength() {
        long now = System.currentTimeMillis();
        if (now - sectorCacheTs < SECTOR_CACHE_TTL_MS && !sectorCache.isEmpty()) {
            return sectorCache;
        }

        Map<String, BigDecimal> prices = marketData.getLastPricesSimple();
        Map<String, AiSectorData> result = new LinkedHashMap<>();

        for (Map.Entry<String, List<String>> e : SECTOR_MAP.entrySet()) {
            String       sector  = e.getKey();
            List<String> symbols = e.getValue();

            double totalChange  = 0;
            int    count        = 0;
            int    advancing    = 0;

            // FIX 3: intraday EMA direction per sector
            int emaUpCount   = 0;
            int emaDownCount = 0;
            int emaCount     = 0;

            for (String sym : symbols) {
                BigDecimal ltpBD = prices.get(sym);
                if (ltpBD == null) continue;
                double ltp = ltpBD.doubleValue();

                // Change vs yesterday close
                List<Candle> daily = aiData.getDailyCandles(sym);
                if (daily.size() < 2) continue;
                double prev = daily.get(daily.size() - 2).getClose().doubleValue();
                if (prev <= 0) continue;

                double chg = (ltp - prev) / prev * 100;
                totalChange += chg;
                count++;

                // FIX 4: sector breadth - is this stock advancing today?
                if (chg > 0.05) advancing++;

                // FIX 3: intraday EMA direction from 15m candles
                List<Candle> c15m = aiData.get15mCandles(sym);
                if (c15m.size() >= 9) {
                    double ema9 = computeEMAFromCandles(c15m, 9);
                    if (ltp > ema9 * 1.001) emaUpCount++;
                    else if (ltp < ema9 * 0.999) emaDownCount++;
                    emaCount++;
                }
            }

            if (count > 0) {
                double avgChange    = totalChange / count;
                double advRatio     = (double) advancing / count;     // FIX 4: sector breadth

                // FIX 3: intraday EMA direction score per sector
                // +1 = majority of sector stocks above intraday EMA (bullish intraday)
                // -1 = majority below (bearish intraday)
                //  0 = mixed
                double intradayEma  = emaCount > 0
                        ? (double)(emaUpCount - emaDownCount) / emaCount
                        : 0.0;

                // Sector aligned bullish: change > 0.25% AND intraday EMA bullish
                // Old: only change > 0.25%
                // New: requires both overnight AND intraday confirmation
                boolean alignedBull = avgChange > 0.25 && intradayEma > 0.3;
                boolean alignedBear = avgChange < -0.25 && intradayEma < -0.3;

                result.put(sector, new AiSectorData(
                        sector, avgChange, advRatio,
                        alignedBull, alignedBear,
                        intradayEma));

                log.debug("[AI-UNDERSTAND] Sector {} chg={}% advRatio={} intradayEma={} bull={} bear={}",
                        sector,
                        String.format("%.2f", avgChange),
                        String.format("%.2f", advRatio),
                        String.format("%.2f", intradayEma),
                        alignedBull, alignedBear);
            }
        }

        sectorCache    = result;
        sectorCacheTs  = System.currentTimeMillis();
        return result;
    }

    // =======================================================================
    // SECTOR CONFIRMATION SCORE
    // Measures how many sectors confirm the overall Nifty direction.
    // Used in MarketSnapshot - flows to AiReasoningEngine environment score.
    // =======================================================================

    private double computeSectorConfirmationScore(double niftyDir) {
        try {
            Map<String, AiSectorData> sectors = computeSectorStrength();
            if (sectors.isEmpty()) return 0.5;

            int confirmed = 0, total = 0;
            for (AiSectorData sd : sectors.values()) {
                total++;
                if (niftyDir > 0.3 && sd.alignedBullish())  confirmed++;
                if (niftyDir < -0.3 && sd.alignedBearish()) confirmed++;
            }
            double score = total > 0 ? (double) confirmed / total : 0.5;
            log.debug("[AI-UNDERSTAND] Sector confirmation: {}/{} sectors aligned = {}",
                    confirmed, total, String.format("%.2f", score));
            return score;
        } catch (Exception e) {
            return 0.5;
        }
    }

    public String getSectorForSymbol(String symbol) {
        for (Map.Entry<String, List<String>> e : SECTOR_MAP.entrySet()) {
            if (e.getValue().contains(symbol)) return e.getKey();
        }
        return "Other";
    }

    public double getSectorChangePercent(String sector) {
        Map<String, AiSectorData> sectors = computeSectorStrength();
        AiSectorData sd = sectors.get(sector);
        return sd != null ? sd.changePercent() : 0.0;
    }

    private String computeLeadingSector() {
        return computeSectorStrength().entrySet().stream()
                .max(Comparator.comparingDouble(e -> Math.abs(e.getValue().changePercent())))
                .map(Map.Entry::getKey)
                .orElse("NONE");
    }

    // =======================================================================
    // SCORING
    // =======================================================================

    private double computeTrendStrength(double atrPct, double breadth, boolean trending) {
        double score = 0;
        score += Math.min(40, (atrPct / ATR_VOLATILE) * 40);
        if (breadth > BREADTH_BULL) score += (breadth - 1.0) * 30;
        else if (breadth < BREADTH_BEAR) score += (1.0 - breadth) * 30;
        if (trending) score += 20;
        score += Math.min(10, regimeDuration * 0.5);
        return Math.min(100, score);
    }

    public double computeSessionQuality(double atrPct, double breadth, double vix) {
        if (currentRegime == Regime.CHOPPY) return 0.0;
        double q = 0.5;
        if (atrPct > ATR_RANGING)                        q += 0.2;
        if (breadth > BREADTH_BULL || breadth < BREADTH_BEAR) q += 0.15;
        if (vix > 12 && vix < 20)                        q += 0.1;
        if (vix > 25)                                    q -= 0.2;
        q += regimeTradingQuality * 0.05;
        return Math.max(0.0, Math.min(1.0, q));
    }

    // =======================================================================
    // LEARNING
    // =======================================================================

    public void recordRegimeOutcome(String regime, double rMultiple) {
        regimeOutcomes.computeIfAbsent(regime, k -> new ArrayList<>()).add(rMultiple);
        List<Double> outcomes = regimeOutcomes.get(regime);
        if (outcomes.size() > 100) outcomes.remove(0);
        if (regime.equals(currentRegime.name())) {
            long wins = outcomes.stream().filter(r -> r >= 1.0).count();
            regimeTradingQuality = (double) wins / outcomes.size();
        }
    }

    // =======================================================================
    // ACCESSORS
    // =======================================================================

    public Regime  getCurrentRegime()  { return currentRegime; }
    public int     getRegimeDuration() { return regimeDuration; }
    public boolean isChoppy()          { return currentRegime == Regime.CHOPPY; }
    public boolean isTradeable()       { return currentRegime != Regime.CHOPPY
            && currentRegime != Regime.UNKNOWN; }

    // =======================================================================
    // PERSISTENCE
    // =======================================================================

    private void persistRegimeChange(Regime from, Regime to, int duration) {
        try {
            jdbc.update("""
                INSERT INTO ai_regime_history
                  (regime_from, regime_to, duration_cycles, change_date)
                VALUES (?, ?, ?, CURDATE())
                """, from.name(), to.name(), duration);
        } catch (Exception ignored) {}
    }

    private void createTablesIfNeeded() {
        try {
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS news_scored_items (
                    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
                    symbol      VARCHAR(20) NOT NULL,
                    score       INT,
                    category    VARCHAR(50),
                    sentiment   VARCHAR(30),
                    age_minutes BIGINT,
                    corroborated BOOLEAN DEFAULT FALSE,
                    headline    TEXT,
                    scored_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    INDEX idx_symbol (symbol),
                    INDEX idx_scored_at (scored_at)
                ) ENGINE=InnoDB
                """);
        } catch (Exception ignored) {}
        try {
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ai_regime_history (
                    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
                    regime_from     VARCHAR(20),
                    regime_to       VARCHAR(20),
                    duration_cycles INT,
                    change_date     DATE,
                    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                ) ENGINE=InnoDB
                """);
        } catch (Exception ignored) {}
    }

    // =======================================================================
    // INNER TYPES
    // =======================================================================

    /**
     * FIX 3 & 4: AiSectorData expanded with intradayEmaDirection and advancingRatio.
     * Old fields unchanged - backward compatible.
     * New fields:
     *   intradayEmaDirection: +1 = majority of stocks above 15m EMA9 (bullish intraday)
     *                         -1 = majority below (bearish intraday)
     *   advancingRatio: fraction of sector stocks advancing today (sector breadth)
     */
    public record AiSectorData(
            String  name,
            double  changePercent,
            double  advancingRatio,          // FIX 4: sector breadth
            boolean alignedBullish,
            boolean alignedBearish,
            double  intradayEmaDirection     // FIX 3: +1 bullish intraday, -1 bearish
    ) {
        // Backward-compatible constructor for existing callers
        public AiSectorData(String name, double changePercent, double advancingRatio,
                            boolean alignedBullish, boolean alignedBearish) {
            this(name, changePercent, advancingRatio, alignedBullish, alignedBearish, 0.0);
        }
    }

    /**
     * FIX: MarketSnapshot expanded with sectorConfirmationScore.
     * All existing fields unchanged - new field added at end.
     * sectorConfirmationScore: 0-1 fraction of sectors confirming Nifty direction.
     * 0.8+ = strong confirmation, 0.5 = mixed, 0.2- = divergence.
     */
    public record MarketSnapshot(
            String  regime,
            double  niftyAtrPct,
            double  breadthRatio,
            double  vix,
            double  trendStrength,
            String  leadingSector,
            double  sessionQuality,
            int     regimeDuration,
            double  niftyDirection,
            double  sectorConfirmationScore, // FIX: new field
            boolean tradeable
    ) {
        public static MarketSnapshot unknown() {
            return new MarketSnapshot("UNKNOWN", 0, 1, 15, 0, "NONE", 0, 0, 0, 0.5, false);
        }
        public boolean isChoppy() { return "CHOPPY".equals(regime); }
    }
}