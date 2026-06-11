package com.trading.ai.engine;

import com.trading.ai.data.AiMarketDataService;
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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AiMarketUnderstandingEngine
 *
 * The AI module's own market intelligence.
 * Computes regime, breadth, VIX proxy, and sector rotation
 * entirely from within the AI package.
 *
 * ZERO dependency on:
 *   - VixService              → AI computes own VIX proxy from Nifty ATR
 *   - MarketPressureService   → AI computes own breadth from 253 symbols
 *   - SectorStrengthService   → AI computes own sector strength from prices
 *   - SectorClassificationService → AI uses own static sector map
 *
 * ONLY uses (shared read-only infrastructure):
 *   - MarketDirectionService  → Nifty ATR, direction
 *   - MarketDataService       → live prices for breadth computation
 *   - AiMarketDataService     → AI's own candle store
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

    // ── Regime state ──────────────────────────────────────────────────────
    public enum Regime { TRENDING, RANGING, VOLATILE, CHOPPY, UNKNOWN }

    private volatile Regime    currentRegime       = Regime.UNKNOWN;
    private volatile int       regimeDuration      = 0;
    private volatile LocalDate regimeStartDate     = LocalDate.now();
    private volatile double    regimeTradingQuality = 0.5;

    // ── AI's own sector map: sector name → list of symbols ────────────────
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

    // ── Regime outcome tracking ────────────────────────────────────────────
    private final Map<String, List<Double>> regimeOutcomes = new ConcurrentHashMap<>();

    // ── ATR thresholds ─────────────────────────────────────────────────────
    private static final double ATR_CHOPPY   = 0.15;
    private static final double ATR_RANGING  = 0.30;
    private static final double ATR_VOLATILE = 0.50;
    private static final double BREADTH_BULL = 1.30;
    private static final double BREADTH_BEAR = 0.75;

    public AiMarketUnderstandingEngine(MarketDirectionService marketDir,
                                       MarketDataService marketData,
                                       AiMarketDataService aiData,
                                       AiSymbolUniverse universe,
                                       JdbcTemplate jdbc) {
        this.marketDir = marketDir;
        this.marketData = marketData;
        this.aiData    = aiData;
        this.universe  = universe;
        this.jdbc      = jdbc;
        createTablesIfNeeded();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // REGIME CLASSIFICATION
    // ═══════════════════════════════════════════════════════════════════════

    public MarketSnapshot classify() {
        try {
            var dir = marketDir.getCurrentDirection();
            if (dir == null) return MarketSnapshot.unknown();

            double atrPct  = dir.niftyAtrPct();
            double breadth = computeOwnBreadth();     // AI's own computation
            double vix     = computeOwnVixProxy(atrPct); // AI's own computation

            Regime newRegime;
            if      (atrPct < ATR_CHOPPY)   newRegime = Regime.CHOPPY;
            else if (atrPct > ATR_VOLATILE || vix > 22) newRegime = Regime.VOLATILE;
            else if (atrPct >= ATR_RANGING && (breadth > BREADTH_BULL || breadth < BREADTH_BEAR))
                newRegime = Regime.TRENDING;
            else                             newRegime = Regime.RANGING;

            if (!newRegime.equals(currentRegime)) {
                persistRegimeChange(currentRegime, newRegime, regimeDuration);
                log.info("[AI-UNDERSTAND] Regime: {} → {} (ATR={}% VIX={} breadth={})",
                        currentRegime, newRegime,
                        String.format("%.2f", atrPct),
                        String.format("%.1f", vix),
                        String.format("%.2f", breadth));
                regimeDuration  = 0;
                regimeStartDate = LocalDate.now();
            } else {
                regimeDuration++;
            }
            currentRegime = newRegime;

            double trendStrength  = computeTrendStrength(atrPct, breadth, dir.isTrendTradeable());
            String leadingSector  = computeLeadingSector();
            double sessionQuality = computeSessionQuality(atrPct, breadth, vix);

            return new MarketSnapshot(
                    newRegime.name(), atrPct, breadth, vix,
                    trendStrength, leadingSector, sessionQuality,
                    regimeDuration,
                    dir.isLong() ? 1.0 : dir.isShort() ? -1.0 : 0.0,
                    dir.isTradeable()
            );
        } catch (Exception e) {
            log.debug("[AI-UNDERSTAND] Classify error: {}", e.getMessage());
            return MarketSnapshot.unknown();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AI'S OWN BREADTH — counts advancing/declining from 253 symbols
    // NO dependency on MarketPressureService
    // ═══════════════════════════════════════════════════════════════════════

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
            if (total < 50) return 1.0; // not enough data yet
            double ratio = (double) advancing / total * 2.0; // normalised: 1.0 = neutral
            log.debug("[AI-UNDERSTAND] Own breadth: {}/{} advancing = {}", advancing, total, String.format("%.2f", ratio));
            return ratio;
        } catch (Exception e) {
            return 1.0;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AI'S OWN VIX PROXY — derived from Nifty ATR
    // NO dependency on VixService
    // ═══════════════════════════════════════════════════════════════════════

    private double computeOwnVixProxy(double niftyAtrPct) {
        // Map ATR% to approximate VIX equivalent
        // Historical calibration: ATR 0.15% ≈ VIX 10, ATR 0.50% ≈ VIX 22, ATR 0.80% ≈ VIX 30
        if (niftyAtrPct < 0.15) return 10.0;
        if (niftyAtrPct < 0.30) return 10.0 + (niftyAtrPct - 0.15) / 0.15 * 8.0;  // 10–18
        if (niftyAtrPct < 0.50) return 18.0 + (niftyAtrPct - 0.30) / 0.20 * 7.0;  // 18–25
        return 25.0 + (niftyAtrPct - 0.50) / 0.30 * 10.0;                           // 25–35
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AI'S OWN SECTOR STRENGTH — from live prices
    // NO dependency on SectorStrengthService
    // ═══════════════════════════════════════════════════════════════════════

    public Map<String, AiSectorData> computeSectorStrength() {
        Map<String, BigDecimal> prices = marketData.getLastPricesSimple();
        Map<String, AiSectorData> result = new LinkedHashMap<>();

        for (Map.Entry<String, List<String>> e : SECTOR_MAP.entrySet()) {
            String sector = e.getKey();
            List<String> symbols = e.getValue();
            double totalChange = 0;
            int count = 0, advancing = 0;

            for (String sym : symbols) {
                BigDecimal ltpBD = prices.get(sym);
                if (ltpBD == null) continue;
                double ltp = ltpBD.doubleValue();

                List<Candle> daily = aiData.getDailyCandles(sym);
                if (daily.size() < 2) continue;

                double prev = daily.get(daily.size() - 2).getClose().doubleValue();
                if (prev <= 0) continue;

                double chg = (ltp - prev) / prev * 100;
                totalChange += chg;
                count++;
                if (chg > 0) advancing++;
            }

            if (count > 0) {
                double avgChange = totalChange / count;
                double advRatio  = (double) advancing / count;
                result.put(sector, new AiSectorData(
                        sector, avgChange, advRatio,
                        avgChange > 0.25, avgChange < -0.25));
            }
        }
        return result;
    }

    /** Get AI's own sector classification for a symbol */
    public String getSectorForSymbol(String symbol) {
        for (Map.Entry<String, List<String>> e : SECTOR_MAP.entrySet()) {
            if (e.getValue().contains(symbol)) return e.getKey();
        }
        return "Other";
    }

    /** Get change% for a specific sector */
    public double getSectorChangePercent(String sector) {
        Map<String, AiSectorData> sectors = computeSectorStrength();
        AiSectorData sd = sectors.get(sector);
        return sd != null ? sd.changePercent() : 0.0;
    }

    private String computeLeadingSector() {
        Map<String, AiSectorData> sectors = computeSectorStrength();
        return sectors.entrySet().stream()
                .max(Comparator.comparingDouble(e -> Math.abs(e.getValue().changePercent())))
                .map(Map.Entry::getKey)
                .orElse("NONE");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SCORING
    // ═══════════════════════════════════════════════════════════════════════

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
        if (atrPct > ATR_RANGING) q += 0.2;
        if (breadth > BREADTH_BULL || breadth < BREADTH_BEAR) q += 0.15;
        if (vix > 12 && vix < 20) q += 0.1;
        if (vix > 25) q -= 0.2;
        q += regimeTradingQuality * 0.05;
        return Math.max(0.0, Math.min(1.0, q));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LEARNING
    // ═══════════════════════════════════════════════════════════════════════

    public void recordRegimeOutcome(String regime, double rMultiple) {
        regimeOutcomes.computeIfAbsent(regime, k -> new ArrayList<>()).add(rMultiple);
        List<Double> outcomes = regimeOutcomes.get(regime);
        if (outcomes.size() > 100) outcomes.remove(0);
        if (regime.equals(currentRegime.name())) {
            long wins = outcomes.stream().filter(r -> r >= 1.0).count();
            regimeTradingQuality = (double) wins / outcomes.size();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ACCESSORS
    // ═══════════════════════════════════════════════════════════════════════

    public Regime  getCurrentRegime()  { return currentRegime; }
    public int     getRegimeDuration() { return regimeDuration; }
    public boolean isChoppy()          { return currentRegime == Regime.CHOPPY; }
    public boolean isTradeable()       { return currentRegime != Regime.CHOPPY && currentRegime != Regime.UNKNOWN; }

    // ═══════════════════════════════════════════════════════════════════════
    // PERSISTENCE
    // ═══════════════════════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════════════════════
    // INNER TYPES
    // ═══════════════════════════════════════════════════════════════════════

    public record AiSectorData(
            String  name,
            double  changePercent,
            double  advancingRatio,
            boolean alignedBullish,
            boolean alignedBearish
    ) {}

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
            boolean tradeable
    ) {
        public static MarketSnapshot unknown() {
            return new MarketSnapshot("UNKNOWN", 0, 1, 15, 0, "NONE", 0, 0, 0, false);
        }
        public boolean isChoppy() { return "CHOPPY".equals(regime); }
    }
}