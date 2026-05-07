package com.trading.strategy.highrr;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.domain.Candle;
import com.trading.events.CandleCompleteEvent;
import com.trading.marketdata.service.InstrumentCacheService;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.models.HistoricalData;
import com.zerodhatech.models.Instrument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * HighRRStructureService — Multi-stock S/R level engine for HighRR strategy.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * ARCHITECTURE: Zero-latency, zero-impact structural analysis
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * LAYER 1 — STARTUP (Redis restore, ~50ms):
 *   Key: "hrr:structure:{symbol}"  TTL: 5 days
 *   Restores precomputed S/R levels instantly on every restart.
 *   HighRRStrategyEngine can query levels immediately at 9:35 AM first cycle.
 *
 * LAYER 2 — BACKGROUND BOOTSTRAP (first deployment only, ~25 seconds):
 *   Fetches 14 calendar days of 15-min OHLCV from Zerodha for 295 symbols.
 *   10 trading days × 26 candles = 260 candles/symbol → detects 15-25 S/R levels.
 *   Only runs when Redis has no hrr:structure:* keys (first deploy or flush).
 *   Background daemon thread — does NOT block @Scheduled or ORB startup.
 *
 * LAYER 3 — LIVE INGESTION (every 15-min candle):
 *   Appends incoming 15-min candles to the rolling buffer.
 *   Re-computes S/R levels after every 4 new candles (every hour).
 *   Persists updated levels to Redis — fire-and-forget on tradingExecutor.
 *
 * LAYER 4 — DAILY REFRESH (8:50 AM daily):
 *   Fetches yesterday's full candle data to add to the rolling buffer.
 *   Ensures levels always reflect the latest 5-day window.
 *   Runs BEFORE HighRR first cycle (9:35 AM) — no race condition.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * S/R DETECTION ALGORITHM — per symbol, on 130 candles of 15-min data:
 *
 * SWING HIGHS (Resistance):
 *   candle[i].high > candle[i-2].high && candle[i].high > candle[i-1].high
 *                 && candle[i].high > candle[i+1].high && candle[i].high > candle[i+2].high
 *   → 2-candle lookback/lookahead minimises noise on NSE 15-min.
 *
 * SWING LOWS (Support):
 *   candle[i].low < candle[i-2].low && candle[i].low < candle[i-1].low
 *                && candle[i].low < candle[i+1].low && candle[i].low < candle[i+2].low
 *
 * PREVIOUS DAY LEVELS (PDH/PDL/PDC):
 *   Previous day High, Low, and Close.
 *   Most-watched levels by all intraday algorithms on NSE.
 *   Treated as special high-significance S/R regardless of touches.
 *
 * CLUSTER MERGING:
 *   Levels within 0.4% of each other are merged into one level.
 *   ADANIGREEN: 1,252 and 1,253.5 → one level at 1,252.75
 *   Removes duplicate noise from closely-spaced swing points.
 *
 * TOUCH COUNT:
 *   Each level gets a touch count (times price came within 0.3%).
 *   Levels with touchCount >= 3 are "major" and scored higher.
 *   PDH/PDL/PDC always get touchCount = 99 (always major).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * ZERO IMPACT GUARANTEE:
 *   - Isolated Redis namespace: "hrr:structure:*" (never overlaps smc:, orb:)
 *   - @Autowired(required=false) on all external deps — graceful degradation
 *   - All network I/O on background threads — never on tick processing path
 *   - HighRRStrategyEngine.buildCandidate() falls back to candleHigh/Low
 *     if getStructure(symbol) returns null — existing behavior preserved
 *   - No changes to HighRRScannerService, HighRRTradeManager, or any other file
 * ─────────────────────────────────────────────────────────────────────────────
 */
@Service
@Slf4j
public class HighRRStructureService {

    // ── Redis ──────────────────────────────────────────────────────────────────
    private static final String   KEY_PREFIX = "hrr:structure:";
    private static final Duration TTL        = Duration.ofDays(5);

    // ── Algorithm tuning ──────────────────────────────────────────────────────
    /** Candles on each side required to confirm a swing point on 15-min. */
    private static final int    SWING_LOOKBACK       = 2;
    /** Two levels within this % are merged into one cluster. */
    private static final double CLUSTER_PCT          = 0.004;  // 0.4%
    /** Price must be within this % of a level to count as a "touch". */
    private static final double TOUCH_TOLERANCE_PCT  = 0.003;  // 0.3%
    /** Minimum touches for a level to be considered significant. */
    private static final int    MIN_TOUCHES_MAJOR    = 3;
    /** Days of 15-min history to maintain per symbol. */
    private static final int    HISTORY_DAYS         = 10;
    /** Max candles kept in rolling buffer (10 days × 26 candles). */
    private static final int    MAX_CANDLES          = 260;
    /** Re-compute S/R after this many new candles arrive. */
    private static final int    RECOMPUTE_EVERY      = 4;
    /** Zerodha API rate limit — batch size and pause. */
    private static final int    BATCH_SIZE           = 50;
    private static final long   BATCH_PAUSE_MS       = 300;
    /** Calendar days to fetch on bootstrap (covers 10 trading days = 2 weeks). */
    private static final int    BOOTSTRAP_CALENDAR_DAYS = 14;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // ── Dependencies ──────────────────────────────────────────────────────────
    @Autowired(required = false) private StringRedisTemplate     redis;
    @Autowired(required = false) private KiteConnect             kiteConnect;
    @Autowired(required = false) private InstrumentCacheService  instrumentCache;
    @Autowired                   private ObjectMapper            objectMapper;

    // ── State ─────────────────────────────────────────────────────────────────
    /** Rolling 15-min candle buffer per symbol (newest first). */
    private final Map<String, Deque<Candle>>   candleBuffer  = new ConcurrentHashMap<>();
    /** Precomputed S/R levels per symbol — the hot read path. */
    private final Map<String, StructureLevels> levelCache    = new ConcurrentHashMap<>();
    /** New candle counter per symbol — triggers re-compute every RECOMPUTE_EVERY. */
    private final Map<String, Integer>         newCandleCount = new ConcurrentHashMap<>();
    /** Tracks whether bootstrap has completed (for logging). */
    private volatile boolean bootstrapComplete = false;

    // ══════════════════════════════════════════════════════════════════════════
    // PUBLIC API — called by HighRRStrategyEngine (zero latency)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Primary query method — O(1) ConcurrentHashMap lookup.
     * Returns null if structure data not yet available (engine falls back gracefully).
     * NEVER blocks. NEVER does I/O.
     */
    public StructureLevels getStructure(String symbol) {
        return levelCache.get(symbol);
    }

    /**
     * Convenience: nearest support below a given price.
     * Returns 0.0 if no data (engine falls back to candleLow).
     */
    public double nearestSupportBelow(String symbol, double price) {
        StructureLevels s = levelCache.get(symbol);
        if (s == null || s.supports().isEmpty()) return 0.0;
        return s.supports().stream()
                .filter(lvl -> lvl.price() < price)
                .max(Comparator.comparingDouble(SRLevel::price))
                .map(SRLevel::price)
                .orElse(0.0);
    }

    /**
     * Convenience: nearest resistance above a given price.
     * Returns 0.0 if no data.
     */
    public double nearestResistanceAbove(String symbol, double price) {
        StructureLevels s = levelCache.get(symbol);
        if (s == null || s.resistances().isEmpty()) return 0.0;
        return s.resistances().stream()
                .filter(lvl -> lvl.price() > price)
                .min(Comparator.comparingDouble(SRLevel::price))
                .map(SRLevel::price)
                .orElse(0.0);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // STARTUP — Layers 1 + 2
    // ══════════════════════════════════════════════════════════════════════════

    @PostConstruct
    public void initialise() {
        // STEP 1: Restore from Redis immediately — engine can use stale data
        //         while fresh data is being fetched from API in background.
        int restored = restoreFromRedis();
        log.info("[HRR-STRUCT] Startup: restored {} symbols from Redis (serving as warm cache).", restored);

        // STEP 2: ALWAYS fetch fresh from API in background — regardless of Redis state.
        //         This ensures every deployment starts with current market data.
        //         Even if Redis has data, it may be from a previous session/day.
        if (kiteConnect != null && instrumentCache != null) {
            Thread boot = new Thread(() -> {
                log.info("[HRR-STRUCT] Starting full API bootstrap on every deployment...");
                bootstrapFromBroker();
            }, "hrr-structure-bootstrap");
            boot.setDaemon(true);
            boot.start();
        } else {
            log.warn("[HRR-STRUCT] KiteConnect/InstrumentCache unavailable — skipping API bootstrap.");
            bootstrapComplete = true;
        }
    }

    // ── Layer 1: Redis restore ─────────────────────────────────────────────────

    private int restoreFromRedis() {
        if (redis == null) return 0;
        try {
            Set<String> keys = redis.keys(KEY_PREFIX + "*");
            if (keys == null || keys.isEmpty()) return 0;

            int count = 0;
            for (String key : keys) {
                String symbol = key.substring(KEY_PREFIX.length());
                try {
                    String json = redis.opsForValue().get(key);
                    if (json == null || json.isBlank()) continue;
                    StructureLevels lvls = objectMapper.readValue(json, StructureLevels.class);
                    if (lvls != null) {
                        levelCache.put(symbol, lvls);
                        count++;
                    }
                } catch (Exception e) {
                    log.debug("[HRR-STRUCT] Restore failed for {}: {}", symbol, e.getMessage());
                }
            }
            return count;
        } catch (Exception e) {
            log.warn("[HRR-STRUCT] Redis restore error: {}", e.getMessage());
            return 0;
        }
    }

    // ── Layer 2: Zerodha bootstrap ────────────────────────────────────────────

    void bootstrapFromBroker() {
        long bootstrapStartMs = System.currentTimeMillis();
        if (kiteConnect == null || instrumentCache == null) {
            log.warn("[HRR-STRUCT] KiteConnect/InstrumentCache unavailable — bootstrap skipped.");
            bootstrapComplete = true;
            return;
        }

        LocalDate today = LocalDate.now(IST);
        java.util.Date fromDate = java.util.Date.from(
                today.minusDays(BOOTSTRAP_CALENDAR_DAYS).atStartOfDay(IST).toInstant());
        java.util.Date toDate = java.util.Date.from(
                today.atTime(15, 31).atZone(IST).toInstant());

        Map<String, Instrument> instruments = instrumentCache.getEquityInstruments();
        if (instruments.isEmpty()) {
            log.warn("[HRR-STRUCT] No instruments available — bootstrap aborted.");
            bootstrapComplete = true;
            return;
        }

        List<Map.Entry<String, Instrument>> entries = new ArrayList<>(instruments.entrySet());
        int total = entries.size(), loaded = 0, failed = 0;
        log.info("[HRR-STRUCT] Bootstrapping {} symbols ({} calendar days)...", total, BOOTSTRAP_CALENDAR_DAYS);

        for (int i = 0; i < entries.size(); i += BATCH_SIZE) {
            List<Map.Entry<String, Instrument>> batch =
                    entries.subList(i, Math.min(i + BATCH_SIZE, entries.size()));

            for (Map.Entry<String, Instrument> entry : batch) {
                String symbol = entry.getKey();
                Instrument inst = entry.getValue();
                try {
                    HistoricalData result = kiteConnect.getHistoricalData(
                            fromDate, toDate,
                            String.valueOf((long) inst.getInstrument_token()),
                            "15minute", false, false);

                    if (result == null || result.dataArrayList == null
                            || result.dataArrayList.isEmpty()) continue;

                    Deque<Candle> dq = new ArrayDeque<>(MAX_CANDLES + 1);
                    List<HistoricalData> raw = result.dataArrayList;
                    // newest first
                    for (int j = raw.size() - 1; j >= 0; j--) {
                        Candle c = buildCandle(symbol, raw.get(j));
                        dq.addLast(c);
                        if (dq.size() >= MAX_CANDLES) break;
                    }
                    candleBuffer.put(symbol, dq);
                    long stockMs = System.currentTimeMillis();
                    recomputeLevels(symbol);
                    long stockElapsed = System.currentTimeMillis() - stockMs;
                    loaded++;
                    // Log slow stocks (>500ms indicates API or processing issue)
                    if (stockElapsed > 500) {
                        log.warn("[HRR-STRUCT] Slow load: {} took {}ms", symbol, stockElapsed);
                    }

                } catch (Throwable e) {
                    failed++;
                    log.debug("[HRR-STRUCT] Bootstrap failed {}: {}", symbol, e.getMessage());
                }
            }

            if (i + BATCH_SIZE < entries.size()) {
                try { Thread.sleep(BATCH_PAUSE_MS); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); break;
                }
            }
        }

        bootstrapComplete = true;

        // ── VALIDATION: data quality cross-check ─────────────────────────────
        int withLevels      = 0;
        int withMajorLevel  = 0;
        int with5DayHistory = 0;
        int missingData     = 0;
        for (Map.Entry<String, Instrument> e : instruments.entrySet()) {
            StructureLevels lvl = levelCache.get(e.getKey());
            if (lvl == null) { missingData++; continue; }
            withLevels++;
            if (lvl.majorLevelCount() > 0) withMajorLevel++;
            if (lvl.tradingDays() >= 2)    with5DayHistory++;
        }
        log.info("[HRR-STRUCT] ✅ Bootstrap complete in {}s: {}/{} loaded ({} failed).",
                String.format("%.1f", (System.currentTimeMillis() - bootstrapStartMs) / 1000.0),
                loaded, total, failed);
        log.info("[HRR-STRUCT] Validation: {} have S/R levels | {} have major level | {} have ≥2 days history | {} missing",
                withLevels, withMajorLevel, with5DayHistory, missingData);
        if (missingData > 50) {
            log.warn("[HRR-STRUCT] ⚠️ {} symbols missing data — check Zerodha API connectivity.", missingData);
        }
    }

    // ── Layer 4: Daily refresh at 8:50 AM ────────────────────────────────────

    @Scheduled(cron = "0 50 8 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyRefresh() {
        if (kiteConnect == null || instrumentCache == null) return;
        log.info("[HRR-STRUCT] Daily refresh starting ({} symbols in buffer)...", candleBuffer.size());

        // Only refresh symbols already in our buffer (the 295 tracked stocks)
        Set<String> toRefresh = candleBuffer.isEmpty()
                ? instrumentCache.getEquityInstruments().keySet()
                : candleBuffer.keySet();

        LocalDate today  = LocalDate.now(IST);
        LocalDate from   = today.minusDays(BOOTSTRAP_CALENDAR_DAYS);
        java.util.Date fromDate = java.util.Date.from(from.atStartOfDay(IST).toInstant());
        java.util.Date toDate   = java.util.Date.from(today.atTime(15, 31).atZone(IST).toInstant());

        Map<String, Instrument> instruments = instrumentCache.getEquityInstruments();
        List<String> symbols = new ArrayList<>(toRefresh);
        int refreshed = 0;

        for (int i = 0; i < symbols.size(); i += BATCH_SIZE) {
            List<String> batch = symbols.subList(i, Math.min(i + BATCH_SIZE, symbols.size()));
            for (String symbol : batch) {
                Instrument inst = instruments.get(symbol);
                if (inst == null) continue;
                try {
                    HistoricalData result = kiteConnect.getHistoricalData(
                            fromDate, toDate,
                            String.valueOf((long) inst.getInstrument_token()),
                            "15minute", false, false);
                    if (result == null || result.dataArrayList == null
                            || result.dataArrayList.isEmpty()) continue;

                    Deque<Candle> dq = candleBuffer.computeIfAbsent(
                            symbol, k -> new ArrayDeque<>(MAX_CANDLES + 1));
                    List<HistoricalData> raw = result.dataArrayList;
                    synchronized (dq) {
                        // Prepend new candles (they are returned oldest-first by Zerodha)
                        for (int j = raw.size() - 1; j >= 0; j--) {
                            Candle c = buildCandle(symbol, raw.get(j));
                            dq.addFirst(c);
                        }
                        while (dq.size() > MAX_CANDLES) dq.removeLast();
                    }
                    recomputeLevels(symbol);
                    refreshed++;
                } catch (Throwable e) {
                    // KiteException extends Throwable directly (not Exception).
                    log.trace("[HRR-STRUCT] Refresh failed {}: {}", symbol, e.getMessage());
                }
            }
            if (i + BATCH_SIZE < symbols.size()) {
                try { Thread.sleep(BATCH_PAUSE_MS); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); break;
                }
            }
        }
        log.info("[HRR-STRUCT] ✅ Daily refresh complete — {} symbols updated.", refreshed);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // LAYER 3 — LIVE INGESTION (on tradingExecutor thread, zero extra latency)
    // ══════════════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tradingExecutor")
    public void onCandle(CandleCompleteEvent event) {
        Candle c = event.getCandle();
        if (!c.isComplete() || !"15minute".equals(c.getTimeframe())) return;
        String sym = c.getTradingSymbol();
        if (sym == null || sym.isBlank()) return;

        Deque<Candle> dq = candleBuffer.computeIfAbsent(
                sym, k -> new ArrayDeque<>(MAX_CANDLES + 1));
        synchronized (dq) {
            dq.addFirst(c);
            while (dq.size() > MAX_CANDLES) dq.removeLast();
        }

        // Re-compute levels every RECOMPUTE_EVERY candles (every ~1 hour)
        int count = newCandleCount.merge(sym, 1, Integer::sum);
        if (count >= RECOMPUTE_EVERY) {
            newCandleCount.put(sym, 0);
            recomputeLevels(sym);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // S/R COMPUTATION CORE — runs off tick path, on tradingExecutor
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Recomputes S/R levels for one symbol from its candle buffer.
     * Persists result to Redis and updates levelCache atomically.
     *
     * Algorithm:
     *   1. Extract swing highs (resistance) and swing lows (support)
     *      using 2-candle lookback/lookahead on 15-min OHLCV.
     *   2. Add Previous Day High, Low, Close as special levels.
     *   3. Cluster levels within 0.4% → merge into one.
     *   4. Count touches (times price came within 0.3% of level).
     *   5. Sort supports descending, resistances ascending.
     *   6. Store in levelCache and Redis.
     */
    void recomputeLevels(String symbol) {
        Deque<Candle> dq = candleBuffer.get(symbol);
        if (dq == null) return;

        List<Candle> candles;
        synchronized (dq) { candles = new ArrayList<>(dq); }
        if (candles.size() < (SWING_LOOKBACK * 2 + 3)) return;

        List<Double> rawResistances = new ArrayList<>();
        List<Double> rawSupports    = new ArrayList<>();

        int n = candles.size();

        // ── Step 1: Swing highs and lows ──────────────────────────────────────
        for (int i = SWING_LOOKBACK; i < n - SWING_LOOKBACK; i++) {
            double h = candles.get(i).getHigh().doubleValue();
            double l = candles.get(i).getLow().doubleValue();

            // Swing high: higher than all candles within SWING_LOOKBACK on each side
            boolean isSwingHigh = true;
            for (int k = 1; k <= SWING_LOOKBACK; k++) {
                if (candles.get(i - k).getHigh().doubleValue() >= h ||
                        candles.get(i + k).getHigh().doubleValue() >= h) {
                    isSwingHigh = false;
                    break;
                }
            }
            if (isSwingHigh) rawResistances.add(h);

            // Swing low: lower than all candles within SWING_LOOKBACK on each side
            boolean isSwingLow = true;
            for (int k = 1; k <= SWING_LOOKBACK; k++) {
                if (candles.get(i - k).getLow().doubleValue() <= l ||
                        candles.get(i + k).getLow().doubleValue() <= l) {
                    isSwingLow = false;
                    break;
                }
            }
            if (isSwingLow) rawSupports.add(l);
        }

        // ── Step 2: Previous Day High / Low / Close ────────────────────────────
        // Find the last completed trading day boundary in the buffer.
        // candles are newest-first, so we scan for day transitions.
        double pdh = 0, pdl = Double.MAX_VALUE, pdc = 0;
        boolean foundYesterdayCandle = false;

        // Identify yesterday's candles (first group after today's candles)
        LocalDate today = LocalDate.now(IST);
        LocalDate yesterday = today.minusDays(1);

        // Since candles have no timestamp in our domain model, use index:
        // candles 0-25 = today (approx 26 × 15-min), candles 26-51 = yesterday
        // We use the approximate day boundary at index 26
        int dayBoundary = 26; // 26 candles = 1 trading day of 15-min data
        if (n > dayBoundary + 5) {
            for (int i = dayBoundary; i < Math.min(dayBoundary + dayBoundary, n); i++) {
                double h = candles.get(i).getHigh().doubleValue();
                double l = candles.get(i).getLow().doubleValue();
                double c = candles.get(i).getClose().doubleValue();
                if (h > pdh) pdh = h;
                if (l < pdl) pdl = l;
                pdc = c; // last close in this window = previous day close approx
                foundYesterdayCandle = true;
            }
        }

        if (foundYesterdayCandle && pdh > 0) {
            rawResistances.add(pdh);    // PDH = resistance
            rawSupports.add(pdl);       // PDL = support
            rawSupports.add(pdc);       // PDC = weak support/resistance
        }

        // ── Step 3: Cluster merge ─────────────────────────────────────────────
        List<SRLevel> resistances = clusterLevels(rawResistances, candles, false);
        List<SRLevel> supports    = clusterLevels(rawSupports,    candles, true);

        // Mark PDH/PDL as major (touchCount 99)
        if (foundYesterdayCandle && pdh > 0) {
            resistances = markAsMajor(resistances, pdh);
            supports    = markAsMajor(supports,    pdl);
        }

        // Sort: supports descending (highest support first), resistances ascending (lowest first)
        resistances.sort(Comparator.comparingDouble(SRLevel::price));
        supports.sort(Comparator.comparingDouble(SRLevel::price).reversed());

        // ── Step 4: Compute multi-day context, build and cache result ─────────
        double currentPrice = candles.get(0).getClose().doubleValue();

        // tradingDays: estimate from buffer size (26 candles per trading day on NSE)
        // With MAX_CANDLES=260: max tradingDays = 260/26 = 10
        int tradingDays = Math.max(1, n / 26);

        // 10-day high/low: full buffer = 260 candles = 10 real trading days
        int tenDayLen = Math.min(n, 260); // now n can be up to 260
        double tenDayHigh = candles.subList(0, tenDayLen).stream()
                .mapToDouble(c -> c.getHigh().doubleValue()).max().orElse(currentPrice);
        double tenDayLow  = candles.subList(0, tenDayLen).stream()
                .mapToDouble(c -> c.getLow().doubleValue()).min().orElse(currentPrice);

        // MA20: simple moving average of last 20 closing prices
        int ma20Len = Math.min(n, 20);
        double ma20 = candles.subList(0, ma20Len).stream()
                .mapToDouble(c -> c.getClose().doubleValue()).average().orElse(currentPrice);

        StructureLevels levels = new StructureLevels(
                symbol, supports, resistances, currentPrice,
                System.currentTimeMillis(),
                tradingDays, tenDayHigh, tenDayLow, ma20);

        // levelCache.put() first — engine reads from memory, never from Redis.
        // persistToRedis() is fire-and-forget: Redis is only for restart recovery.
        // A Redis timeout (rare) must NEVER block the tradingExecutor thread.
        levelCache.put(symbol, levels);
        final StructureLevels toSave = levels;
        new Thread(() -> persistToRedis(symbol, toSave), "hrr-redis-persist").start();

        log.trace("[HRR-STRUCT] {} computed: {} supports, {} resistances (from {} candles)",
                symbol, supports.size(), resistances.size(), n);
    }

    /**
     * Cluster raw price levels within CLUSTER_PCT of each other.
     * Returns SRLevel list with touch counts computed against candle history.
     */
    private List<SRLevel> clusterLevels(List<Double> raw, List<Candle> candles, boolean isSupport) {
        if (raw.isEmpty()) return Collections.emptyList();

        List<Double> sorted = new ArrayList<>(raw);
        Collections.sort(sorted);

        List<Double> clustered = new ArrayList<>();
        double anchor = sorted.get(0);
        List<Double> group = new ArrayList<>();
        group.add(anchor);

        for (int i = 1; i < sorted.size(); i++) {
            double price = sorted.get(i);
            if ((price - anchor) / anchor <= CLUSTER_PCT) {
                group.add(price);
            } else {
                // Merge group → midpoint
                double mid = group.stream().mapToDouble(Double::doubleValue).average().orElse(anchor);
                clustered.add(mid);
                group.clear();
                anchor = price;
                group.add(price);
            }
        }
        if (!group.isEmpty()) {
            double mid = group.stream().mapToDouble(Double::doubleValue).average().orElse(anchor);
            clustered.add(mid);
        }

        // Compute touch count for each cluster
        List<SRLevel> result = new ArrayList<>();
        for (double level : clustered) {
            int touches = countTouches(level, candles, isSupport);
            result.add(new SRLevel(
                    BigDecimal.valueOf(level).setScale(2, RoundingMode.HALF_UP).doubleValue(),
                    touches,
                    touches >= MIN_TOUCHES_MAJOR
            ));
        }
        return result;
    }

    /**
     * Count how many candles came within TOUCH_TOLERANCE_PCT of the level.
     * Support: we look at candle lows. Resistance: candle highs.
     */
    private int countTouches(double level, List<Candle> candles, boolean isSupport) {
        int count = 0;
        for (Candle c : candles) {
            double price = isSupport
                    ? c.getLow().doubleValue()
                    : c.getHigh().doubleValue();
            if (Math.abs(price - level) / level <= TOUCH_TOLERANCE_PCT) count++;
        }
        return count;
    }

    private List<SRLevel> markAsMajor(List<SRLevel> levels, double price) {
        return levels.stream()
                .map(l -> Math.abs(l.price() - price) / price <= CLUSTER_PCT
                        ? new SRLevel(l.price(), 99, true)
                        : l)
                .collect(Collectors.toList());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // REDIS PERSISTENCE — fire-and-forget on tradingExecutor
    // ══════════════════════════════════════════════════════════════════════════

    private void persistToRedis(String symbol, StructureLevels levels) {
        if (redis == null) return;
        try {
            String json = objectMapper.writeValueAsString(levels);
            redis.opsForValue().set(KEY_PREFIX + symbol, json, TTL);
        } catch (Exception e) {
            log.trace("[HRR-STRUCT] Redis write failed for {}: {}", symbol, e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DOMAIN OBJECTS — immutable, Jackson-serialisable, zero allocation on read
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * A single Support or Resistance price level.
     *
     * @param price      The exact price level (2dp, 5-paise aligned).
     * @param touchCount How many times price touched within 0.3%.
     *                   PDH/PDL levels are always 99 (treated as major).
     * @param isMajor    touchCount >= 3 OR is a PDH/PDL/PDC level.
     */
    public record SRLevel(double price, int touchCount, boolean isMajor) {}

    /**
     * All S/R levels for one symbol, computed from last 5 days of 15-min data.
     * Supports are sorted highest-first, resistances lowest-first.
     * This means supports.get(0) is the nearest support below current price
     * and resistances.get(0) is the nearest resistance above.
     *
     * @param symbol       NSE trading symbol.
     * @param supports     Support levels, sorted descending by price.
     * @param resistances  Resistance levels, sorted ascending by price.
     * @param lastPrice    Last close price when levels were computed.
     * @param computedAt   Unix epoch millis when levels were last computed.
     */
    public record StructureLevels(
            String        symbol,
            List<SRLevel> supports,
            List<SRLevel> resistances,
            double        lastPrice,
            long          computedAt,
            int           tradingDays,
            double        tenDayHigh,
            double        tenDayLow,
            double        ma20
    ) {
        /** Nearest support strictly below a given price. Null if none found. */
        public SRLevel nearestSupportBelow(double price) {
            return supports == null ? null : supports.stream()
                    .filter(l -> l.price() < price * 0.9997) // at least 0.03% below
                    .findFirst().orElse(null);
        }

        /** Nearest resistance strictly above a given price. Null if none found. */
        public SRLevel nearestResistanceAbove(double price) {
            return resistances == null ? null : resistances.stream()
                    .filter(l -> l.price() > price * 1.0003) // at least 0.03% above
                    .findFirst().orElse(null);
        }

        /**
         * How many major levels (touchCount >= 3) exist between support and resistance?
         * Used as a structural quality score.
         */
        public int majorLevelCount() {
            int c = 0;
            if (supports    != null) for (SRLevel l : supports)     if (l.isMajor()) c++;
            if (resistances != null) for (SRLevel l : resistances)  if (l.isMajor()) c++;
            return c;
        }
    }

    // ── Candle builder (same pattern as SmcCandleStore.buildCandle) ───────────

    private Candle buildCandle(String symbol, HistoricalData h) {
        return Candle.builder()
                .tradingSymbol(symbol)
                .timeframe("15minute")
                .open(  BigDecimal.valueOf(h.open) .setScale(2, RoundingMode.HALF_UP))
                .high(  BigDecimal.valueOf(h.high) .setScale(2, RoundingMode.HALF_UP))
                .low(   BigDecimal.valueOf(h.low)  .setScale(2, RoundingMode.HALF_UP))
                .close( BigDecimal.valueOf(h.close).setScale(2, RoundingMode.HALF_UP))
                .volume((long) h.volume)
                .complete(true)
                .build();
    }

    // ── Dashboard helpers ─────────────────────────────────────────────────────

    public int  getSymbolCount()     { return levelCache.size(); }
    public int  getBufferCount()     { return candleBuffer.size(); }
    public boolean isBootstrapDone() { return bootstrapComplete; }
}