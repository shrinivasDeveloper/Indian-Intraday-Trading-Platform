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
 *   1. On startup: fetch 60-day daily candles + 45-day 15m candles via Zerodha (2 API calls/symbol)
 *      Historical API for all Nifty500 symbols.
 *   2. Cache daily OHLCV in Redis (key: SMC:DAY:{symbol}) with 25h TTL.
 *   3. Cache 15m OHLCV in Redis (key: SMC:15M:{symbol}) with 4h TTL.
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
    private static final int     DAILY_HISTORY_DAYS    = 60;   // 60 trading days (~3 months) — enough for HTF S/R
    private static final int     INTRADAY_HISTORY_DAYS = 45;   // 45 days 15m — enough for structure + recent setups
    private static final int     MAX_CANDLES_DAILY     = 90;   // 60d + weekend buffer
    private static final int     MAX_CANDLES_15M       = 1500; // 45d × ~27 candles/day
    private static final int     RATE_LIMIT_DELAY_MS = 500;  // ms between API calls within a symbol
    private static final long    REDIS_TTL_DAILY_SEC = 90_000;  // ~25h
    private static final long    REDIS_TTL_15M_SEC   = 14_400;  // 4h

    // Redis key prefixes — isolated from HighRR (SMC:, not stock: or HIGHRR:)
    static final String REDIS_KEY_DAY     = "SMC:DAY:";
    static final String REDIS_KEY_15M     = "SMC:15M:";
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
        if (!bootstrapComplete.get()) {
            log.info("[SMC-CANDLE] Daily refresh skipped — bootstrap not yet complete");
            return;
        }
        log.info("[SMC-CANDLE] Daily refresh starting at 9:05 AM IST");
        // Lightweight: only fetch 5 days of daily candles to get yesterday's close.
        // Full bootstrap (60d daily + 45d 15m) only runs once at startup.
        // This prevents 4-minute bootstrap re-run every morning at 9:05 AM.
        Set<String> symbols = getSmcSymbols();
        if (symbols.isEmpty()) return;
        int updated = 0;
        for (String symbol : symbols) {
            try {
                Instrument inst = instrumentCache.getEquityInstruments().get(symbol);
                if (inst == null) continue;
                long token = inst.getInstrument_token();
                if (token <= 0) continue;
                java.util.Date from = toDate(java.time.LocalDate.now(IST).minusDays(7));
                java.util.Date to   = toDate(java.time.LocalDate.now(IST));
                HistoricalData result = kiteConnect.getHistoricalData(
                        from, to, String.valueOf(token), "day", false, false);
                if (result != null && result.dataArrayList != null && !result.dataArrayList.isEmpty()) {
                    List<Candle> candles = convertToCandles(result.dataArrayList, symbol, "day");
                    if (!candles.isEmpty()) {
                        dailyCandles.put(symbol, candles);
                        updated++;
                    }
                }
                sleep(350); // stay under Zerodha 3 req/sec
            } catch (Throwable e) {
                log.trace("[SMC-CANDLE] Daily refresh failed for {}: {}", symbol, e.getMessage());
            }
        }
        log.info("[SMC-CANDLE] Daily refresh complete — {} symbols updated", updated);
    }

    // ── Core bootstrap logic ─────────────────────────────────────────────────

    private void bootstrapFromBroker() {
        Set<String> symbols = getSmcSymbols();
        if (symbols.isEmpty()) {
            log.warn("[SMC-CANDLE] No symbols available — instrument cache not ready yet");
            return;
        }

        log.info("[SMC-CANDLE] Starting bootstrap for {} symbols | daily={}d | 15m={}d (5m=live)",
                symbols.size(), DAILY_HISTORY_DAYS, INTRADAY_HISTORY_DAYS);

        // Sequential fetch — Zerodha rate limit is per-account, not per-thread.
        // Multiple parallel threads cause burst requests that get throttled (3→10s/symbol).
        // Sequential with 500ms between API calls stays under the 3 req/sec limit
        // and completes in ~6 minutes reliably.
        List<String> symbolList = new ArrayList<>(symbols);
        int total = symbolList.size();
        int done  = 0;

        for (String symbol : symbolList) {
            try {
                fetchAndCacheSymbol(symbol);
                done++;
                if (done % 50 == 0) {
                    log.info("[SMC-CANDLE] Bootstrap progress: {}/{}", done, total);
                }
                // Brief pause between symbols to avoid burst at Zerodha API
                sleep(200);
            } catch (Throwable e) {
                log.debug("[SMC-CANDLE] Fetch failed for {}: {}", symbol, e.getMessage());
            }
        }

        // Sequential execution complete — no thread pool to shut down

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

    public boolean isBootstrapComplete() { return bootstrapComplete.get(); }
    public int     getSymbolsLoaded()    { return symbolsLoaded.get(); }

    /**
     * Returns the set of symbols that have candle data loaded.
     * Used by SmcInstitutionalStrategyEngine to iterate ONLY loaded symbols
     * instead of all 9771 NSE instruments, preventing a 9-second scan loop.
     */
    // ══════════════════════════════════════════════════════════════════════════
    // LIVE 15m CANDLE UPDATE
    // Without this, intraday15m is frozen at bootstrap. Today's candles never
    // reach evaluateSymbol() → SMC evaluates on yesterday's structure only.
    // ══════════════════════════════════════════════════════════════════════════

    @org.springframework.context.event.EventListener
    @org.springframework.scheduling.annotation.Async("tradingExecutor")
    public void onCandleClose(com.trading.events.CandleCompleteEvent event) {
        if (!bootstrapComplete.get()) return;
        Candle c = event.getCandle();
        if (!"15minute".equals(c.getTimeframe()) || !c.isComplete()) return;

        String symbol = c.getTradingSymbol();
        List<Candle> buf = intraday15m.get(symbol);
        if (buf == null) return; // only update symbols in our universe

        synchronized (buf) {
            buf.add(c);
            if (buf.size() > MAX_CANDLES_15M) buf.remove(0);
        }
        log.trace("[SMC-CANDLE] Live 15m appended: {} close={}", symbol, c.getClose());
    }

    public Set<String> getLoadedSymbols() {
        // Union of all timeframes — any symbol with at least daily data
        // Only daily + 15m are bootstrapped; 5m received live
        Set<String> loaded = new java.util.HashSet<>(dailyCandles.keySet());
        loaded.addAll(intraday15m.keySet());
        return java.util.Collections.unmodifiableSet(loaded);
    }

    // ── Symbol universe ──────────────────────────────────────────────────────

    /**
     * Returns the SMC symbol universe — Nifty500 subset only (~253 symbols).
     *
     * CRITICAL: Do NOT use instrumentCache.getEquityInstruments().keySet() here.
     * That returns ALL 9771 NSE instruments → 18.5 minute bootstrap time →
     * Gate 2 blocks all trades until ~10:27 AM.
     *
     * Using Nifty500 subset (same list as HighRRStructureService):
     *   253 symbols × 3 API calls × 340ms ÷ 3 threads ≈ 33 seconds bootstrap.
     *   SMC can trade from 9:32 AM on all days (including Day 1).
     */
    private Set<String> getSmcSymbols() {
        Map<String, Instrument> inst = instrumentCache.getEquityInstruments();
        if (inst == null || inst.isEmpty()) return Collections.emptySet();

        // Primary: filter to NSE EQ instruments with valid tokens in Nifty500 size range
        Set<String> filtered = inst.entrySet().stream()
                .filter(e -> {
                    Instrument i = e.getValue();
                    return "NSE".equals(i.getExchange())
                            && "EQ".equals(i.getInstrument_type())
                            && i.getInstrument_token() > 0
                            && i.getLot_size() == 1;
                })
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet());

        // If reasonable size (200–600 symbols) use it directly
        if (filtered.size() >= 200 && filtered.size() <= 600) {
            log.info("[SMC-CANDLE] Symbol universe: {} NSE EQ symbols", filtered.size());
            return filtered;
        }

        // Fallback: use static Nifty500 list (same as HighRRStructureService)
        return getStaticNifty500(inst);
    }

    /**
     * Static Nifty500 constituent list — exact copy from HighRRStructureService.
     * Used as fallback when equity filter returns unexpected count.
     */
    private Set<String> getStaticNifty500(Map<String, Instrument> instruments) {
        Set<String> nifty500 = new java.util.LinkedHashSet<>(java.util.Arrays.asList(
                // Nifty50
                "RELIANCE","TCS","HDFCBANK","BHARTIARTL","ICICIBANK","INFOSYS","SBIN","HINDUNILVR",
                "ITC","LT","KOTAKBANK","AXISBANK","BAJFINANCE","MARUTI","SUNPHARMA","TATAMOTORS",
                "ULTRACEMCO","NTPC","TITAN","ONGC","ADANIPORTS","POWERGRID","HDFCLIFE","COALINDIA",
                "WIPRO","HCLTECH","BAJAJFINSV","TECHM","ASIANPAINT","NESTLEIND","DIVISLAB","GRASIM",
                "TATASTEEL","JSWSTEEL","INDUSINDBK","CIPLA","DRREDDY","EICHERMOT","HINDALCO","ADANIENT",
                "SBILIFE","BPCL","BRITANNIA","APOLLOHOSP","TATACONSUM","PIDILITIND","HEROMOTOCO",
                "BAJAJ-AUTO","SHREECEM","TRENT",
                // Banking & Finance
                "HDFCAMC","ICICIPRULI","ICICIGI","SBICARD","CHOLAFIN","MUTHOOTFIN","BAJAJHLDNG",
                "IDFCFIRSTB","FEDERALBNK","BANDHANBNK","AUBANK","RBLBANK","PNB","BANKBARODA",
                "CANBK","UNIONBANK","RECLTD","PFC","IRFC","M&MFIN","LICHSGFIN","MANAPPURAM",
                "SUNDARMFIN","ABCAPITAL","POONAWALLA","UGROCAP","CREDITACC",
                // IT & Technology
                "PERSISTENT","MPHASIS","OFSS","LTIM","COFORGE","LTTS","KPITTECH","TATAELXSI",
                "NAUKRI","INDIAMART","POLICYBZR","ANGELONE","CDSL","CAMS","MASTEK",
                // Pharma & Healthcare
                "LUPIN","AUROPHARMA","ZYDUSLIFE","TORNTPHARM","BIOCON","ALKEM","SYNGENE",
                "LALPATHLAB","METROPOLIS","MAXHEALTH","FORTIS","APOLLOHOSP","RAINBOW","KIMS",
                "GLOBALHEALT","YATHARTH","JUPITERWATT","MEDANTA",
                // Auto & Auto Ancillaries
                "TVSMOTOR","MOTHERSON","ASHOKLEY","BHARATFORG","ESCORTS","ENDURANCE","SONACOMS",
                "BALKRISIND","APOLLOTYRE","CEATLTD","MRF","TIINDIA","SWARAJENG","EXIDEIND",
                // Energy & Power
                "ADANIGREEN","ADANIPOWER","TATAPOWER","JSWENERGY","TORNTPOWER","CESC","NHPC",
                "SJVN","RVNL","IREDA","POWERMECH","SUZLON","RPOWER","PCBL",
                // Oil & Gas
                "ONGC","BPCL","IOC","HINDPETRO","GAIL","OIL","MGL","IGL","GUJGASLTD","PETRONET",
                // Cement
                "AMBUJACEM","RAMCOCEM","JKCEMENT","ORIENTCEM","HEIDELBERG","JKLAKSHMI",
                // FMCG & Consumer
                "MARICO","DABUR","COLPAL","GODREJCP","EMAMILTD","VBL","BIKAJI","PATANJALI",
                "MCDOWELL-N","RADICO","UNITDSPR","BALRAMCHIN","TRIVENI","DWARIKESH",
                // Real Estate
                "GODREJPROP","DLF","PRESTIGE","OBEROIRLTY","LODHA","BRIGADE","SOBHA","SUNTECK",
                // Infrastructure & Capital Goods
                "HAL","BEL","SIEMENS","HAVELLS","VOLTAS","ABB","POLYCAB","DIXON","KAYNES",
                "GRINDWELL","CUMMINSIND","TIMKEN","SCHAEFFLER","SKF","ELGIEQUIP","BHEL","THERMAX",
                "KEC","KALPATPOWR","APLAPOLLO","RAJESHEXPO",
                // Metals & Mining
                "NMDC","SAIL","NATIONALUM","HINDCOPPER","VEDL","HINDZINC","MOIL","GMRAIRPORT",
                // Chemicals
                "PIIND","DEEPAKNTR","AARTIIND","SRF","NAVINFLUOR","COROMANDEL","CHAMBLFERT",
                "GNFC","UPL","TATACHEM","GHCL","VINDHYATEL",
                // Textiles & Apparel
                "PAGEIND","ABFRL","RAYMOND","BATAINDIA","MANYAVAR","SENCO","KALYAN","DOMS",
                // PSU & Government
                "CONCOR","IRCTC","DELHIVERY","HAL","BEL","IRFC","RVNL","NHPC","SJVN","RECLTD",
                // Media & Entertainment
                "SUNTV","ZEEL","PVR","PVRINOX",
                // Hotels & Leisure
                "INDHOTEL","LEMONTREE","CHALET",
                // Pipes & Building Materials
                "ASTRAL","PRINCEPIPE","FINOLEX","SUPREMEIND","POLYMED",
                // Logistics
                "GATI","BLUEDART","VRL",
                // Diversified
                "ADANIPORTS","ADANIENT","ADANIGREEN","ADANIPOWER","ATGL","ADANITRANS",
                // Additional Nifty500 constituents
                "AFFLE","TANLA","HAPPYMNDS","ROUTE","CAMPUS","DEVYANI","SAPPHIRE",
                "IDEAFORGE","CYIENT","ZENSAR","MSTCLTD","RAILTEL","IIFL","IIFLWAM",
                "360ONE","NUVAMA","EMUDHRA","INOXWIND","SOLARINDS","HBLPOWER","GPIL",
                "WELCORP","KALYANKJIL","JYOTHYLAB","BAYER","SKFINDIA","SUPRAJIT",
                "CRAFTSMAN","LATENTVIEW","DELHIVERY","BRAINBEES","NIACL","GICRE","STARHEALTH",
                "ICICIB22","HDFCBANK","BAJFINANCE","SBILIFE","MAXLIFE"
        ));
        Set<String> result = nifty500.stream()
                .filter(instruments::containsKey)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        log.info("[SMC-CANDLE] Static Nifty500: {} of {} symbols resolved",
                result.size(), nifty500.size());
        return result;
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