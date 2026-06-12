package com.trading.ai.data;

import com.trading.domain.Candle;
import com.trading.marketdata.service.InstrumentCacheService;
import com.trading.marketdata.service.MarketDataService;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.HistoricalData;
import com.zerodhatech.models.Instrument;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AiMarketDataService
 *
 * The AI module's own independent data layer.
 * Fetches and manages candle data entirely within the AI package.
 *
 * INDEPENDENCE GUARANTEE:
 *   - No imports from com.trading.strategy.highrr.*
 *   - No imports from com.trading.strategy.smc.*
 *   - No imports from com.trading.strategy.news.*
 *   - Owns its own candle store, own S/R computation, own pattern detection
 *
 * DATA FETCHED ON BOOTSTRAP:
 *   - Daily candles: 60 days  (for HTF trend, S/R, regime context)
 *   - 15-minute candles: 30 days  (for intraday structure)
 *   - 5-minute candles: 7 days   (for entry timing)
 *   - All 253 Nifty500 symbols
 *
 * LIVE UPDATES:
 *   - New 5-minute candles added via addLiveCandle() called from AiTradingSystem
 *   - S/R levels recomputed after each daily candle
 */
@Service
@ConditionalOnProperty(name = "ai.trading.enabled", havingValue = "true")
@Slf4j
public class AiMarketDataService {

    // ── Dependencies — ONLY shared infrastructure, never strategy services ──
    private final MarketDataService    marketDataService;
    private final AiSymbolUniverse     symbolUniverse;
    private final KiteConnect          kiteConnect;
    private final InstrumentCacheService instrumentCache;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Value("${zerodha.api-key:}")
    private String apiKey;

    // ── Candle stores (keyed by symbol) ─────────────────────────────────────
    // These are entirely owned by the AI module
    private final Map<String, List<Candle>> dailyCandles  = new ConcurrentHashMap<>();
    private final Map<String, List<Candle>> candles15m    = new ConcurrentHashMap<>();
    private final Map<String, List<Candle>> candles5m     = new ConcurrentHashMap<>();

    // ── Computed S/R levels (keyed by symbol) ────────────────────────────────
    private final Map<String, AiStructureLevels> structureMap = new ConcurrentHashMap<>();

    // ── Bootstrap state ───────────────────────────────────────────────────────
    private final AtomicBoolean bootstrapComplete = new AtomicBoolean(false);
    private volatile int bootstrapProgress = 0;

    // ── Constants ─────────────────────────────────────────────────────────────
    private static final int DAILY_DAYS  = 60;
    private static final int M15_DAYS    = 30;
    private static final int M5_DAYS     = 7;
    private static final int MAX_DAILY   = 60;
    private static final int MAX_15M     = 500;
    private static final int MAX_5M      = 300;

    // S/R computation parameters
    private static final int    SWING_LOOKBACK   = 3;    // candles each side for swing detection
    private static final double CLUSTER_PCT      = 0.005; // 0.5% — merge levels within this range
    private static final int    MIN_TOUCH_COUNT  = 2;    // minimum touches for valid level

    public AiMarketDataService(MarketDataService marketDataService,
                               AiSymbolUniverse symbolUniverse,
                               KiteConnect kiteConnect,
                               InstrumentCacheService instrumentCache) {
        this.marketDataService = marketDataService;
        this.symbolUniverse    = symbolUniverse;
        this.kiteConnect       = kiteConnect;
        this.instrumentCache   = instrumentCache;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // BOOTSTRAP — runs once on startup
    // ═══════════════════════════════════════════════════════════════════════

    @EventListener(ApplicationReadyEvent.class)
    @Async("tradingExecutor")
    public void bootstrap() {
        // Delay 90 seconds so SMC bootstrap finishes first (SMC runs ~50s)
        // This prevents both services hitting Zerodha API simultaneously
        try { Thread.sleep(90_000); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return;
        }
        List<String> symbols = symbolUniverse.getSymbols();
        log.info("[AI-DATA] Bootstrap starting: {} symbols | daily={}d 15m={}d 5m={}d",
                symbols.size(), DAILY_DAYS, M15_DAYS, M5_DAYS);

        bootstrapProgress = 0;
        int loaded = 0;

        for (String symbol : symbols) {
            try {
                loadHistoricalData(symbol);
                computeStructure(symbol);
                loaded++;
                bootstrapProgress = loaded;

                if (loaded % 50 == 0) {
                    log.info("[AI-DATA] Bootstrap progress: {}/{}", loaded, symbols.size());
                }
                // Throttle to avoid Zerodha rate limits
                Thread.sleep(300);

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.debug("[AI-DATA] Bootstrap failed for {}: {}", symbol, e.getMessage());
            }
        }

        bootstrapComplete.set(true);
        log.info("[AI-DATA] Bootstrap complete — {}/{} symbols loaded", loaded, symbols.size());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HISTORICAL DATA LOADING — AI's own fetcher
    // ═══════════════════════════════════════════════════════════════════════

    private void loadHistoricalData(String symbol) {
        Instrument inst = instrumentCache.getEquityInstruments().get(symbol);
        if (inst == null) return;
        long token = inst.getInstrument_token();
        if (token <= 0) return;

        java.util.Date now  = toDate(LocalDate.now(IST));

        // Daily candles
        java.util.Date fromDay = toDate(LocalDate.now(IST).minusDays(DAILY_DAYS));
        List<Candle> daily = fetchCandles(token, fromDay, now, "day", symbol);
        if (!daily.isEmpty()) dailyCandles.put(symbol, capList(daily, MAX_DAILY));

        // 15-minute candles
        java.util.Date from15m = toDate(LocalDate.now(IST).minusDays(M15_DAYS));
        List<Candle> m15 = fetchCandles(token, from15m, now, "15minute", symbol);
        if (!m15.isEmpty()) candles15m.put(symbol, capList(m15, MAX_15M));

        // 5-minute candles
        java.util.Date from5m = toDate(LocalDate.now(IST).minusDays(M5_DAYS));
        List<Candle> m5 = fetchCandles(token, from5m, now, "5minute", symbol);
        if (!m5.isEmpty()) candles5m.put(symbol, capList(m5, MAX_5M));
    }

    private List<Candle> fetchCandles(long token, java.util.Date from,
                                      java.util.Date to, String interval, String symbol) {
        try {
            HistoricalData result = kiteConnect.getHistoricalData(
                    from, to, String.valueOf(token), interval, false, false);
            if (result == null || result.dataArrayList == null) return Collections.emptyList();
            return toCandles(result.dataArrayList, symbol, interval);
        } catch (KiteException | java.io.IOException e) {
            log.debug("[AI-DATA] Fetch failed symbol={} interval={}: {}", symbol, interval, e.getMessage());
            return Collections.emptyList();
        } catch (Exception e) {
            log.debug("[AI-DATA] Fetch unexpected error symbol={}: {}", symbol, e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<Candle> toCandles(List<HistoricalData> raw, String symbol, String timeframe) {
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        List<Candle> result = new ArrayList<>(raw.size());
        for (HistoricalData h : raw) {
            try {
                result.add(Candle.builder()
                        .tradingSymbol(symbol)
                        .timeframe(timeframe)
                        .open( BigDecimal.valueOf(h.open) .setScale(2, RoundingMode.HALF_UP))
                        .high( BigDecimal.valueOf(h.high) .setScale(2, RoundingMode.HALF_UP))
                        .low(  BigDecimal.valueOf(h.low)  .setScale(2, RoundingMode.HALF_UP))
                        .close(BigDecimal.valueOf(h.close).setScale(2, RoundingMode.HALF_UP))
                        .volume((long) h.volume)
                        .complete(true)
                        .build());
            } catch (Exception ignored) {}
        }
        return result;
    }

    private java.util.Date toDate(LocalDate date) {
        return java.util.Date.from(date.atStartOfDay(IST).toInstant());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LIVE CANDLE UPDATES — called from AiTradingSystem on each candle close
    // ═══════════════════════════════════════════════════════════════════════

    public void addLiveCandle(String symbol, Candle candle, String timeframe) {
        switch (timeframe) {
            case "5minute" -> {
                candles5m.computeIfAbsent(symbol, k -> new ArrayList<>()).add(candle);
                capInPlace(candles5m.get(symbol), MAX_5M);
            }
            case "15minute" -> {
                candles15m.computeIfAbsent(symbol, k -> new ArrayList<>()).add(candle);
                capInPlace(candles15m.get(symbol), MAX_15M);
            }
            case "day" -> {
                dailyCandles.computeIfAbsent(symbol, k -> new ArrayList<>()).add(candle);
                capInPlace(dailyCandles.get(symbol), MAX_DAILY);
                computeStructure(symbol); // recompute S/R on new daily candle
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // S/R STRUCTURE COMPUTATION — AI's own algorithm, no borrowed code
    // ═══════════════════════════════════════════════════════════════════════

    public void computeStructure(String symbol) {
        List<Candle> daily = dailyCandles.get(symbol);
        if (daily == null || daily.size() < 5) return;

        List<Double> swingHighs = new ArrayList<>();
        List<Double> swingLows  = new ArrayList<>();

        // Step 1 — detect swing highs and lows
        for (int i = SWING_LOOKBACK; i < daily.size() - SWING_LOOKBACK; i++) {
            double high = daily.get(i).getHigh().doubleValue();
            double low  = daily.get(i).getLow().doubleValue();
            boolean isSwingHigh = true;
            boolean isSwingLow  = true;

            for (int j = i - SWING_LOOKBACK; j <= i + SWING_LOOKBACK; j++) {
                if (j == i) continue;
                if (daily.get(j).getHigh().doubleValue() >= high) isSwingHigh = false;
                if (daily.get(j).getLow().doubleValue()  <= low)  isSwingLow  = false;
            }
            if (isSwingHigh) swingHighs.add(high);
            if (isSwingLow)  swingLows.add(low);
        }

        // Step 2 — add recent high/low of last 10 days as additional levels
        int recent = Math.min(10, daily.size());
        List<Candle> recentCandles = daily.subList(daily.size() - recent, daily.size());
        double recentHigh = recentCandles.stream()
                .mapToDouble(c -> c.getHigh().doubleValue()).max().orElse(0);
        double recentLow  = recentCandles.stream()
                .mapToDouble(c -> c.getLow().doubleValue()).min().orElse(Double.MAX_VALUE);
        if (recentHigh > 0) swingHighs.add(recentHigh);
        if (recentLow < Double.MAX_VALUE) swingLows.add(recentLow);

        // Step 3 — cluster nearby levels
        List<AiSRLevel> supports    = clusterLevels(swingLows,  false);
        List<AiSRLevel> resistances = clusterLevels(swingHighs, true);

        // Step 4 — compute MA20, MA50, MA200 from daily
        double ma20  = computeSMA(daily, 20);
        double ma50  = computeSMA(daily, 50);
        double ma200 = computeSMA(daily, 200);

        // Step 5 — ATR(14)
        double atr14 = computeATR(daily, 14);

        structureMap.put(symbol, new AiStructureLevels(
                supports, resistances, ma20, ma50, ma200, atr14));
    }

    private List<AiSRLevel> clusterLevels(List<Double> prices, boolean isResistance) {
        if (prices.isEmpty()) return Collections.emptyList();
        Collections.sort(prices);

        List<AiSRLevel> result = new ArrayList<>();
        List<Double> cluster = new ArrayList<>();

        for (double p : prices) {
            if (cluster.isEmpty() || Math.abs(p - cluster.get(0)) / cluster.get(0) <= CLUSTER_PCT) {
                cluster.add(p);
            } else {
                if (cluster.size() >= MIN_TOUCH_COUNT) {
                    double avg = cluster.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                    result.add(new AiSRLevel(avg, cluster.size(), isResistance));
                }
                cluster.clear();
                cluster.add(p);
            }
        }
        if (cluster.size() >= MIN_TOUCH_COUNT) {
            double avg = cluster.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            result.add(new AiSRLevel(avg, cluster.size(), isResistance));
        }
        // Sort: supports descending (nearest first), resistances ascending
        result.sort(isResistance
                ? Comparator.comparingDouble(AiSRLevel::price)
                : Comparator.comparingDouble(AiSRLevel::price).reversed());
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TECHNICAL INDICATORS — own implementations
    // ═══════════════════════════════════════════════════════════════════════

    private double computeSMA(List<Candle> candles, int period) {
        if (candles.size() < period) return 0;
        int start = candles.size() - period;
        double sum = 0;
        for (int i = start; i < candles.size(); i++) {
            sum += candles.get(i).getClose().doubleValue();
        }
        return sum / period;
    }

    public double computeEMA(List<Candle> candles, int period) {
        if (candles.size() < period) return 0;
        double k = 2.0 / (period + 1);
        double ema = computeSMA(candles.subList(0, period), period);
        for (int i = period; i < candles.size(); i++) {
            ema = candles.get(i).getClose().doubleValue() * k + ema * (1 - k);
        }
        return ema;
    }

    public double computeATR(List<Candle> candles, int period) {
        if (candles.size() < 2) return 0;
        int start = Math.max(1, candles.size() - period);
        double sumTR = 0;
        int count = 0;
        for (int i = start; i < candles.size(); i++) {
            double high  = candles.get(i).getHigh().doubleValue();
            double low   = candles.get(i).getLow().doubleValue();
            double prev  = candles.get(i - 1).getClose().doubleValue();
            double tr    = Math.max(high - low,
                    Math.max(Math.abs(high - prev), Math.abs(low - prev)));
            sumTR += tr;
            count++;
        }
        return count > 0 ? sumTR / count : 0;
    }

    public double computeRSI(List<Candle> candles, int period) {
        if (candles.size() < period + 1) return 50.0;
        double avgGain = 0, avgLoss = 0;
        int start = candles.size() - period - 1;
        for (int i = start + 1; i <= start + period; i++) {
            double change = candles.get(i).getClose().doubleValue()
                    - candles.get(i - 1).getClose().doubleValue();
            if (change > 0) avgGain += change;
            else            avgLoss += Math.abs(change);
        }
        avgGain /= period;
        avgLoss /= period;
        if (avgLoss == 0) return 100;
        double rs = avgGain / avgLoss;
        return 100 - (100 / (1 + rs));
    }

    public double computeRVOL(List<Candle> candles, int lookback) {
        if (candles.size() < 2) return 1.0;
        double currentVol = candles.get(candles.size() - 1).getVolume();
        int start = Math.max(0, candles.size() - lookback - 1);
        double sum = 0;
        int cnt = 0;
        for (int i = start; i < candles.size() - 1; i++) {
            sum += candles.get(i).getVolume();
            cnt++;
        }
        if (cnt == 0 || sum == 0) return 1.0;
        return currentVol / (sum / cnt);
    }

    public double computeMACD(List<Candle> candles) {
        if (candles.size() < 26) return 0;
        double ema12 = computeEMA(candles, 12);
        double ema26 = computeEMA(candles, 26);
        return ema12 - ema26;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PATTERN DETECTION — AI's own, independent
    // ═══════════════════════════════════════════════════════════════════════

    /** Detects liquidity sweep low — equal lows swept then recovered */
    public boolean detectLiquiditySweepLow(List<Candle> candles5m) {
        if (candles5m.size() < 5) return false;
        int n = candles5m.size();
        Candle last = candles5m.get(n - 1);
        double lastLow   = last.getLow().doubleValue();
        double lastClose = last.getClose().doubleValue();

        // Find equal lows in last 10 candles
        for (int i = n - 10; i < n - 1; i++) {
            if (i < 0) continue;
            double prevLow = candles5m.get(i).getLow().doubleValue();
            boolean equalLows = Math.abs(lastLow - prevLow) / prevLow < 0.003;
            if (equalLows && lastClose > prevLow * 1.001) {
                return true; // swept and recovered
            }
        }
        return false;
    }

    /** Detects liquidity sweep high — equal highs swept then rejected */
    public boolean detectLiquiditySweepHigh(List<Candle> candles5m) {
        if (candles5m.size() < 5) return false;
        int n = candles5m.size();
        Candle last = candles5m.get(n - 1);
        double lastHigh  = last.getHigh().doubleValue();
        double lastClose = last.getClose().doubleValue();

        for (int i = n - 10; i < n - 1; i++) {
            if (i < 0) continue;
            double prevHigh = candles5m.get(i).getHigh().doubleValue();
            boolean equalHighs = Math.abs(lastHigh - prevHigh) / prevHigh < 0.003;
            if (equalHighs && lastClose < prevHigh * 0.999) {
                return true;
            }
        }
        return false;
    }

    /** Detects S/R flip — former resistance now acting as support */
    public boolean detectSRFlip(List<Candle> candles5m, AiStructureLevels structure, double ltp) {
        if (structure == null || candles5m.size() < 3) return false;
        for (AiSRLevel res : structure.resistances()) {
            double level = res.price();
            if (ltp > level * 0.998 && ltp < level * 1.005) {
                // Price is just above a former resistance — potential SR flip
                return true;
            }
        }
        return false;
    }

    /** Returns the nearest support level below price */
    public AiSRLevel nearestSupportBelow(String symbol, double price) {
        AiStructureLevels s = structureMap.get(symbol);
        if (s == null) return null;
        return s.supports().stream()
                .filter(l -> l.price() < price)
                .min(Comparator.comparingDouble(l -> price - l.price()))
                .orElse(null);
    }

    /** Returns the nearest resistance level above price */
    public AiSRLevel nearestResistanceAbove(String symbol, double price) {
        AiStructureLevels s = structureMap.get(symbol);
        if (s == null) return null;
        return s.resistances().stream()
                .filter(l -> l.price() > price)
                .min(Comparator.comparingDouble(l -> l.price() - price))
                .orElse(null);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ACCESSORS
    // ═══════════════════════════════════════════════════════════════════════

    public List<Candle>        getDailyCandles(String symbol) {
        return dailyCandles.getOrDefault(symbol, Collections.emptyList());
    }
    public List<Candle>        get15mCandles(String symbol) {
        return candles15m.getOrDefault(symbol, Collections.emptyList());
    }
    public List<Candle>        get5mCandles(String symbol) {
        return candles5m.getOrDefault(symbol, Collections.emptyList());
    }
    public AiStructureLevels   getStructure(String symbol) {
        return structureMap.get(symbol);
    }
    public boolean             isBootstrapComplete() { return bootstrapComplete.get(); }
    public int                 getBootstrapProgress(){ return bootstrapProgress; }

    /**
     * FIX: Resolve real instrument token for a symbol.
     * Was hardcoded to 0L in AiTradingSystem — caused trades to be invisible
     * to portfolio tracking and WebSocket monitoring systems.
     */
    public long resolveInstrumentToken(String symbol) {
        try {
            var inst = instrumentCache.getEquityInstruments().get(symbol.toUpperCase());
            if (inst != null) return inst.getInstrument_token();
            log.debug("[AI-DATA] Instrument token not found for {}", symbol);
            return 0L;
        } catch (Exception e) {
            return 0L;
        }
    }
    public double              getLtp(String symbol) {
        BigDecimal v = marketDataService.getLastPricesSimple().get(symbol);
        return v != null ? v.doubleValue() : 0.0;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    private long resolveToken(String symbol) {
        try {
            Instrument inst = instrumentCache.getEquityInstruments().get(symbol);
            return inst != null ? inst.getInstrument_token() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private <T> List<T> capList(List<T> list, int max) {
        if (list.size() <= max) return list;
        return new ArrayList<>(list.subList(list.size() - max, list.size()));
    }

    private <T> void capInPlace(List<T> list, int max) {
        while (list != null && list.size() > max) list.remove(0);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // INNER TYPES — owned by AI module
    // ═══════════════════════════════════════════════════════════════════════

    public record AiSRLevel(double price, int touchCount, boolean isResistance) {
        public boolean isMajor() { return touchCount >= 3; }
    }

    public record AiStructureLevels(
            List<AiSRLevel> supports,
            List<AiSRLevel> resistances,
            double ma20,
            double ma50,
            double ma200,
            double atr14
    ) {
        public boolean hasData() {
            return !supports.isEmpty() || !resistances.isEmpty();
        }
    }
}