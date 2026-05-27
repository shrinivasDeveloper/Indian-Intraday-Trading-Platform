package com.trading.strategy.smc;

import com.trading.domain.Candle;
import com.trading.marketdata.service.InstrumentCacheService;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.models.HistoricalData;
import com.zerodhatech.models.Instrument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SmcInstitutionalCandleService
 * ─────────────────────────────────────────────────────────────────────────────
 * Bootstrap service for the SMC_INSTITUTIONAL_V1 strategy.
 *
 * Responsibilities:
 *   1. On startup: fetch 1-year daily candles + 90-day 15m candles via Zerodha
 *      Historical API for all Nifty500 symbols.
 *   2. Cache daily OHLCV in Redis (key: SMC:DAY:{symbol}) with 25h TTL.
 *   3. Cache 15m OHLCV in Redis (key: SMC:15M:{symbol}) with 4h TTR.
 *   4. Refresh daily candles every morning at 9:05 AM IST (pre-market).
 *   5. Expose getSmcDailyCandles(symbol) and getSmc15mCandles(symbol) to
 *      SmcInstitutionalStructureService for HTF analysis.
 *
 * Architecture Notes:
 *   - Mirrors HighRRStructureService bootstrap pattern (parallel fetch with
 *     rate-limit guard, cold Redis fallback).
 *   - Does NOT modify any existing service or event flow.
 *   - SMC strategy reads from this service only; no shared state with HighRR.
 * ─────────────────────────────────────────────────────────────────────────────
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SmcInstitutionalCandleService {

    private static final ZoneId  IST              = ZoneId.of("Asia/Kolkata");
    private static final int     DAILY_HISTORY_DAYS = 365;   // 1 year daily candles
    private static final int     INTRADAY_HISTORY_DAYS = 90; // 90 days 15m candles
    private static final int     INTRADAY_5M_DAYS      = 30;  // 30 days 5m candles (spec s5)
    private static final int     MAX_CANDLES_5M        = 2400; // 30d × ~80 candles/day
    private static final int     MAX_CANDLES_DAILY = 270;    // ~252 trading days/yr + buffer
    private static final int     MAX_CANDLES_15M   = 2400;   // 90d × ~27 candles/day
    private static final int     RATE_LIMIT_DELAY_MS = 340;  // ~3 req/sec Zerodha limit
    private static final int     FETCH_PARALLELISM   = 3;    // parallel fetch threads
    private static final long    REDIS_TTL_DAILY_SEC = 90_000;  // ~25h
    private static final long    REDIS_TTL_15M_SEC   = 14_400;  // 4h

    // Redis key prefixes — isolated from HighRR (SMC:, not stock: or HIGHRR:)
    static final String REDIS_KEY_DAY     = "SMC:DAY:";
    static final String REDIS_KEY_15M     = "SMC:15M:";
    static final String REDIS_KEY_5M      = "SMC:5M:";
    // HTF structure sub-keys (set by SmcInstitutionalStructureService)
    static final String REDIS_HTF_TREND   = "HTF:TREND:";
    static final String REDIS_HTF_SUPPORT = "HTF:SUPPORT:";
    static final String REDIS_HTF_RESIST  = "HTF:RESISTANCE:";
    static final String REDIS_HTF_CHANNEL = "HTF:CHANNEL:";
    static final String REDIS_HTF_TRENDLN = "HTF:TRENDLINE:";
    static final String REDIS_HTF_LIQUID  = "HTF:LIQUIDITY:";
    static final String REDIS_TREND       = "TREND:";
    static final String REDIS_SUPPORT     = "SUPPORT:";
    static final String REDIS_RESISTANCE  = "RESISTANCE:";

    private final InstrumentCacheService  instrumentCache;
    private final StringRedisTemplate     redis;
    private final KiteConnect             kiteConnect;

    @Value("${strategy.smc.enabled:true}")
    private boolean smcEnabled;

    @Value("${strategy.smc.bootstrap-delay-seconds:120}")
    private int bootstrapDelaySeconds;

    // ── In-memory cache for hot path reads ───────────────────────────────────
    private final Map<String, List<Candle>> dailyCandles = new ConcurrentHashMap<>();
    private final Map<String, List<Candle>> intraday15m  = new ConcurrentHashMap<>();
    private final Map<String, List<Candle>> intraday5m   = new ConcurrentHashMap<>();

    private final AtomicBoolean  bootstrapComplete = new AtomicBoolean(false);
    private final AtomicInteger  symbolsLoaded     = new AtomicInteger(0);

    // ── Bootstrap on startup ─────────────────────────────────────────────────

    @PostConstruct
    public void scheduleBootstrap() {
        if (!smcEnabled) {
            log.info("[SMC-CANDLE] Strategy disabled — skipping bootstrap");
            return;
        }
        // Delay so InstrumentCacheService finishes loading instruments first
        Thread t = new Thread(() -> {
            try {
                log.info("[SMC-CANDLE] Bootstrap scheduled — waiting {}s for instrument cache",
                        bootstrapDelaySeconds);
                Thread.sleep(bootstrapDelaySeconds * 1000L);
                bootstrapFromBroker();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "smc-bootstrap");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Daily refresh at 9:05 AM IST — fetch yesterday's closing candle
     * and refresh daily structure before market open.
     */
    @Scheduled(cron = "0 5 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyRefresh() {
        if (!smcEnabled) return;
        log.info("[SMC-CANDLE] Daily refresh starting at 9:05 AM IST");
        bootstrapFromBroker();
    }

    // ── Core bootstrap logic ─────────────────────────────────────────────────

    private void bootstrapFromBroker() {
        Set<String> symbols = getSmcSymbols();
        if (symbols.isEmpty()) {
            log.warn("[SMC-CANDLE] No symbols available — instrument cache not ready yet");
            return;
        }

        log.info("[SMC-CANDLE] Starting bootstrap for {} symbols | daily={}d | 15m={}d",
                symbols.size(), DAILY_HISTORY_DAYS, INTRADAY_HISTORY_DAYS);

        ExecutorService pool = Executors.newFixedThreadPool(FETCH_PARALLELISM,
                r -> { Thread t = new Thread(r, "smc-fetch"); t.setDaemon(true); return t; });

        List<String> symbolList = new ArrayList<>(symbols);
        AtomicInteger fetched = new AtomicInteger(0);
        int total = symbolList.size();

        // Submit parallel fetch tasks with rate limiting
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < symbolList.size(); i++) {
            final String symbol = symbolList.get(i);
            final int idx = i;
            futures.add(pool.submit(() -> {
                try {
                    // Stagger start to spread load (idx / PARALLELISM × delay)
                    Thread.sleep((long)(idx / FETCH_PARALLELISM) * RATE_LIMIT_DELAY_MS);
                    fetchAndCacheSymbol(symbol);
                    int done = fetched.incrementAndGet();
                    if (done % 50 == 0) {
                        log.info("[SMC-CANDLE] Bootstrap progress: {}/{}", done, total);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable e) {
                    // KiteException extends Throwable directly — wraps fetchAndCacheSymbol Zerodha calls
                    log.debug("[SMC-CANDLE] Fetch failed for {}: {}", symbol, e.getMessage());
                }
            }));
        }

        pool.shutdown();
        try {
            pool.awaitTermination(20, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        bootstrapComplete.set(true);
        symbolsLoaded.set(dailyCandles.size());
        log.info("[SMC-CANDLE] Bootstrap complete — {} symbols loaded (daily:{} 15m:{})",
                symbolsLoaded.get(), dailyCandles.size(), intraday15m.size());
    }

    private void fetchAndCacheSymbol(String symbol) {
        Instrument inst = instrumentCache.getEquityInstruments().get(symbol);
        if (inst == null) return;
        long token = inst.getInstrument_token();

        Date fromDaily = toDate(LocalDate.now(IST).minusDays(DAILY_HISTORY_DAYS));
        Date toDate    = toDate(LocalDate.now(IST));

        // ── Daily candles ────────────────────────────────────────────────────
        try {
            HistoricalData _resultDay = kiteConnect.getHistoricalData(
                    fromDaily, toDate, String.valueOf(token), "day", false, false);
            List<HistoricalData> raw = (_resultDay != null) ? _resultDay.dataArrayList : null;

            if (raw != null && !raw.isEmpty()) {
                List<Candle> candles = convertToCandles(raw, symbol, "day");
                // Cap at MAX_CANDLES_DAILY (most recent)
                if (candles.size() > MAX_CANDLES_DAILY) {
                    candles = candles.subList(candles.size() - MAX_CANDLES_DAILY, candles.size());
                }
                dailyCandles.put(symbol, candles);
                cacheInRedis(REDIS_KEY_DAY + symbol, candles, REDIS_TTL_DAILY_SEC);
            }
        } catch (Throwable e) {
            // KiteException extends Throwable directly — catch(Exception) would miss it
            log.trace("[SMC-CANDLE] Daily fetch error {}: {}", symbol, e.getMessage());
            List<Candle> cached = loadFromRedis(REDIS_KEY_DAY + symbol);
            if (!cached.isEmpty()) dailyCandles.put(symbol, cached);
        }

        // Rate limit between daily and 15m calls
        sleep(RATE_LIMIT_DELAY_MS);

        // ── 5m candles (last 30 days for intraday confirmation) ───────────
        Date from5m = toDate(LocalDate.now(IST).minusDays(INTRADAY_5M_DAYS));
        try {
            HistoricalData _result5m = kiteConnect.getHistoricalData(
                    from5m, toDate, String.valueOf(token), "5minute", false, false);
            List<HistoricalData> raw5m = (_result5m != null) ? _result5m.dataArrayList : null;

            if (raw5m != null && !raw5m.isEmpty()) {
                List<Candle> c5m = convertToCandles(raw5m, symbol, "5minute");
                if (c5m.size() > MAX_CANDLES_5M) {
                    c5m = c5m.subList(c5m.size() - MAX_CANDLES_5M, c5m.size());
                }
                intraday5m.put(symbol, c5m);
                cacheInRedis(REDIS_KEY_5M + symbol, c5m, REDIS_TTL_15M_SEC);
            }
        } catch (Throwable e) {
            // KiteException extends Throwable directly
            log.trace("[SMC-CANDLE] 5m fetch error {}: {}", symbol, e.getMessage());
            List<Candle> cached5m = loadFromRedis(REDIS_KEY_5M + symbol);
            if (!cached5m.isEmpty()) intraday5m.put(symbol, cached5m);
        }

        sleep(RATE_LIMIT_DELAY_MS);

        // ── 15m candles ──────────────────────────────────────────────────────
        Date from15m = toDate(LocalDate.now(IST).minusDays(INTRADAY_HISTORY_DAYS));
        try {
            HistoricalData _result15m = kiteConnect.getHistoricalData(
                    from15m, toDate, String.valueOf(token), "15minute", false, false);
            List<HistoricalData> raw = (_result15m != null) ? _result15m.dataArrayList : null;

            if (raw != null && !raw.isEmpty()) {
                List<Candle> candles = convertToCandles(raw, symbol, "15minute");
                if (candles.size() > MAX_CANDLES_15M) {
                    candles = candles.subList(candles.size() - MAX_CANDLES_15M, candles.size());
                }
                intraday15m.put(symbol, candles);
                cacheInRedis(REDIS_KEY_15M + symbol, candles, REDIS_TTL_15M_SEC);
            }
        } catch (Throwable e) {
            // KiteException extends Throwable directly
            log.trace("[SMC-CANDLE] 15m fetch error {}: {}", symbol, e.getMessage());
            List<Candle> cached = loadFromRedis(REDIS_KEY_15M + symbol);
            if (!cached.isEmpty()) intraday15m.put(symbol, cached);
        }
    }

    // ── Public accessors for structure service ────────────────────────────────

    /**
     * Returns daily candles for HTF structure analysis.
     * Returns empty list if bootstrap not complete or symbol not found.
     */
    public List<Candle> getSmcDailyCandles(String symbol) {
        List<Candle> c = dailyCandles.get(symbol);
        if (c != null) return Collections.unmodifiableList(c);
        // Fallback: try Redis on cache miss
        List<Candle> cached = loadFromRedis(REDIS_KEY_DAY + symbol);
        if (!cached.isEmpty()) dailyCandles.put(symbol, cached);
        return Collections.unmodifiableList(cached);
    }

    /**
     * Returns 15m candles for intraday confirmation.
     */
    public List<Candle> getSmcIntraday15m(String symbol) {
        List<Candle> c = intraday15m.get(symbol);
        if (c != null) return Collections.unmodifiableList(c);
        List<Candle> cached = loadFromRedis(REDIS_KEY_15M + symbol);
        if (!cached.isEmpty()) intraday15m.put(symbol, cached);
        return Collections.unmodifiableList(cached);
    }

    /**
     * Returns 5m candles for intraday setup confirmation (short-term momentum).
     * Lower timeframe — never overrides HTF direction.
     */
    public List<Candle> getSmc5mCandles(String symbol) {
        List<Candle> c = intraday5m.get(symbol);
        if (c != null) return Collections.unmodifiableList(c);
        List<Candle> cached = loadFromRedis(REDIS_KEY_5M + symbol);
        if (!cached.isEmpty()) intraday5m.put(symbol, cached);
        return Collections.unmodifiableList(cached);
    }

    public boolean isBootstrapComplete() { return bootstrapComplete.get(); }
    public int     getSymbolsLoaded()    { return symbolsLoaded.get(); }

    // ── Symbol universe ──────────────────────────────────────────────────────

    private Set<String> getSmcSymbols() {
        Map<String, Instrument> inst = instrumentCache.getEquityInstruments();
        if (inst == null || inst.isEmpty()) return Collections.emptySet();
        return inst.keySet();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private List<Candle> convertToCandles(List<HistoricalData> raw, String symbol) {
        return convertToCandles(raw, symbol, "day");
    }

    private List<Candle> convertToCandles(List<HistoricalData> raw, String symbol, String timeframe) {
        List<Candle> result = new ArrayList<>(raw.size());
        for (HistoricalData h : raw) {
            if (h == null) continue;
            try {
                result.add(Candle.builder()
                        .tradingSymbol(symbol)
                        .timeframe(timeframe)
                        .open(  java.math.BigDecimal.valueOf(h.open) .setScale(2, java.math.RoundingMode.HALF_UP))
                        .high(  java.math.BigDecimal.valueOf(h.high) .setScale(2, java.math.RoundingMode.HALF_UP))
                        .low(   java.math.BigDecimal.valueOf(h.low)  .setScale(2, java.math.RoundingMode.HALF_UP))
                        .close( java.math.BigDecimal.valueOf(h.close).setScale(2, java.math.RoundingMode.HALF_UP))
                        .volume((long) h.volume)
                        .complete(true)
                        .build());
            } catch (Exception ignored) {}
        }
        return result;
    }

    private void cacheInRedis(String key, List<Candle> candles, long ttlSeconds) {
        try {
            // Serialize as compact CSV: open|high|low|close|vol|epochSec per line
            // Avoids large JSON blobs as per spec requirement
            StringBuilder sb = new StringBuilder(candles.size() * 48);
            for (Candle c : candles) {
                sb.append(c.getOpen().doubleValue()).append('|')
                        .append(c.getHigh().doubleValue()).append('|')
                        .append(c.getLow().doubleValue()).append('|')
                        .append(c.getClose().doubleValue()).append('|')
                        .append(c.getVolume()).append('|')
                        .append(c.getCandleTime() != null
                                ? c.getCandleTime().atZone(IST).toEpochSecond()
                                : 0L)
                        .append('\n');
            }
            redis.opsForValue().set(key, sb.toString(), Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            log.debug("[SMC-CANDLE] Redis write failed for {}: {}", key, e.getMessage());
        }
    }

    private List<Candle> loadFromRedis(String key) {
        try {
            String raw = redis.opsForValue().get(key);
            if (raw == null || raw.isEmpty()) return new ArrayList<>();
            List<Candle> result = new ArrayList<>();
            for (String line : raw.split("\n")) {
                if (line.isEmpty()) continue;
                String[] p = line.split("\\|");
                if (p.length < 5) continue;
                result.add(Candle.builder()
                        .tradingSymbol(key)
                        .timeframe("day")
                        .open(  java.math.BigDecimal.valueOf(Double.parseDouble(p[0])).setScale(2, java.math.RoundingMode.HALF_UP))
                        .high(  java.math.BigDecimal.valueOf(Double.parseDouble(p[1])).setScale(2, java.math.RoundingMode.HALF_UP))
                        .low(   java.math.BigDecimal.valueOf(Double.parseDouble(p[2])).setScale(2, java.math.RoundingMode.HALF_UP))
                        .close( java.math.BigDecimal.valueOf(Double.parseDouble(p[3])).setScale(2, java.math.RoundingMode.HALF_UP))
                        .volume(Long.parseLong(p[4]))
                        .complete(true)
                        .build());
            }
            return result;
        } catch (Exception e) {
            log.debug("[SMC-CANDLE] Redis read failed for {}: {}", key, e.getMessage());
            return new ArrayList<>();
        }
    }

    private Date toDate(LocalDate d) {
        return Date.from(d.atStartOfDay(IST).toInstant());
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}