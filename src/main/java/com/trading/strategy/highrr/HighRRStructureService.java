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
import java.util.Collections;
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
    private static final String   KEY_PREFIX        = "hrr:structure:";
    private static final String   KEY_CANDLE_PREFIX = "hrr:candles:";
    private static final Duration TTL_CANDLE        = Duration.ofDays(2);
    private static final Duration TTL        = Duration.ofDays(5);

    // ── Algorithm tuning ──────────────────────────────────────────────────────
    /** Candles on each side required to confirm a swing point on 15-min. */
    // SWING_LOOKBACK = 5: intraday swings — 5 candles each side = 75-min window.
    // Captures intraday pivots and recent support/resistance.
    private static final int    SWING_LOOKBACK       = 5;
    // SWING_LOOKBACK_WEEKLY = 26: weekly swings — 26 candles each side = 1 full trading day.
    // Captures the type of horizontal zones visible in multi-week charts:
    // a level that dominates an entire trading day on each side = truly significant.
    // These are the thick horizontal bands professional traders draw on their charts.
    // 26 × 15-min = 390 min = 1 full NSE trading day (9:15–15:30) on each side.
    private static final int    SWING_LOOKBACK_WEEKLY = 26;
    /** Two levels within this % are merged into one cluster. */
    // 0.6%: wider merge collapses weak nearby levels into one strong zone.
    private static final double CLUSTER_PCT          = 0.006;  // 0.6%
    /** Price must be within this % of a level to count as a "touch". */
    // 0.4%: touch tolerance must match cluster width
    private static final double TOUCH_TOLERANCE_PCT  = 0.004;  // 0.4%
    /** Minimum touches for a level to be considered significant. */
    // MIN_TOUCHES_MAJOR = 5: only zones tested 5+ times on 15-min are major.
    // 5 touches = repeated institutional defence at exactly this zone.
    private static final int    MIN_TOUCHES_MAJOR    = 5;
    // 90 trading days of 15-min history — required to detect genuine institutional
    // zones that recur across multiple weeks. 10 days was too short: a level tested
    // in weeks 2, 5, and 9 = institutional. With only 10 days we see 1 touch = noise.
    private static final int    HISTORY_DAYS         = 90;
    /** Max candles kept in rolling buffer (90 days × 26 candles = 2,340). */
    private static final int    MAX_CANDLES          = 2400;
    /** Re-compute S/R after this many new candles arrive. */
    private static final int    RECOMPUTE_EVERY      = 4;
    /** Zerodha API rate limit — batch size and pause. */
    private static final int    BATCH_SIZE           = 50;
    private static final long   BATCH_PAUSE_MS       = 300;
    // 130 calendar days covers ~90 trading days of 15-min data.
    // This ensures all S/R zones across 3 months are properly identified.
    private static final int    BOOTSTRAP_CALENDAR_DAYS = 130;
    // Higher timeframe fetch windows
    private static final int    HTF_30D_CALENDAR_DAYS   = 42;  // ~30 trading days
    private static final int    HTF_90D_CALENDAR_DAYS   = 130; // ~90 trading days
    // HTF algorithm tuning
    private static final double HTF_CLUSTER_PCT         = 0.010; // 1.0% — daily zones need width
    // 3 daily touches = the zone has been tested on 3 separate trading days.
    // This is the clearest sign of institutional interest at that price.
    private static final int    MIN_TOUCHES_HTF         = 3;     // daily touches
    private static final double MIN_SWING_SIZE_PCT      = 0.005; // filter tiny swings
    // HTF Redis keys
    private static final String   KEY_30D_PREFIX = "hrr:htf30:";
    private static final String   KEY_90D_PREFIX = "hrr:htf90:";
    private static final Duration TTL_HTF        = Duration.ofDays(7);

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // ── Dependencies ──────────────────────────────────────────────────────────
    @Autowired(required = false) private StringRedisTemplate     redis;
    @Autowired(required = false) private KiteConnect             kiteConnect;
    @Autowired(required = false) private InstrumentCacheService  instrumentCache;
    @Autowired(required = false) private HighRRScannerService    scanner;
    @Autowired                   private ObjectMapper            objectMapper;

    // ── State ─────────────────────────────────────────────────────────────────
    /** Rolling 15-min candle buffer per symbol (newest first). */
    private final Map<String, Deque<Candle>>   candleBuffer  = new ConcurrentHashMap<>();
    /** Precomputed S/R levels per symbol — the hot read path. */
    private final Map<String, StructureLevels> levelCache    = new ConcurrentHashMap<>();
    /** HTF 30-day daily S/R levels — institutional zones. */
    private final Map<String, List<SRLevel>>   htf30Cache    = new ConcurrentHashMap<>();
    /** HTF 90-day daily S/R levels — major institutional zones. */
    private final Map<String, List<SRLevel>>   htf90Cache    = new ConcurrentHashMap<>();
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

    /** All HTF major levels (30d + 90d merged). Empty if not loaded. */
    public List<SRLevel> getHtfLevels(String symbol) {
        List<SRLevel> r = new ArrayList<>();
        List<SRLevel> d30 = htf30Cache.get(symbol);
        List<SRLevel> d90 = htf90Cache.get(symbol);
        if (d30 != null) r.addAll(d30);
        if (d90 != null) r.addAll(d90);
        return r;
    }

    /**
     * Returns the nearest entry-grade HTF zone (STRONG or MAJOR) within 0.5% of price.
     * Returns null if no qualifying HTF zone exists near entry.
     * Called by engine as a HARD GATE — null means no institutional backing.
     */
    public SRLevel getNearestHtfZone(String symbol, double price) {
        return getHtfLevels(symbol).stream()
                .filter(SRLevel::isEntryGrade)
                .filter(l -> Math.abs(l.price() - price) / price <= 0.005)
                .min(Comparator.comparingDouble(l -> Math.abs(l.price() - price)))
                .orElse(null);
    }

    /** Quick boolean check — true if getNearestHtfZone() would return non-null. */
    public boolean isNearHtfLevel(String symbol, double price) {
        return getNearestHtfZone(symbol, price) != null;
    }

    /**
     * Returns true when HTF data should be enforced as a hard gate.
     * Logic:
     *   - Bootstrap not complete: always bypass (data still loading)
     *   - Bootstrap complete + htfLevels present: enforce hard gate
     *   - Bootstrap complete + htfLevels empty: symbol had no usable HTF data → bypass
     *     (rare: very new listings or illiquid stocks with no daily swings)
     */
    public boolean shouldEnforceHtfGate(String symbol) {
        if (!bootstrapComplete) return false;   // still loading
        List<SRLevel> htf = getHtfLevels(symbol);
        return !htf.isEmpty();                  // enforce only when data exists
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
        log.info("[HRR-STRUCT] Startup: restored {} symbol levels from Redis.", restored);
        int candlesRestored = restoreCandlesFromRedis();
        log.info("[HRR-STRUCT] Candle restore: {} symbols loaded from Redis.", candlesRestored);

        // KEY: If Redis has data, mark bootstrapComplete immediately.
        // The HTF gate and S/R detection work with cached data right away.
        // API refresh runs in background to update stale cache — does NOT block trading.
        if (restored > 0 && candlesRestored > 0) {
            bootstrapComplete = true;
            log.info("[HRR-STRUCT] ✅ Full restore from Redis ({} levels, {} candle buffers). "
                    + "API refresh in background.", restored, candlesRestored);
        } else if (restored > 0) {
            bootstrapComplete = true;
            log.info("[HRR-STRUCT] Levels restored ({}), candle API bootstrap starting.", restored);
        }

        if (kiteConnect != null && instrumentCache != null) {
            Thread boot = new Thread(() -> {
                log.info("[HRR-STRUCT] Background API bootstrap starting...");
                bootstrapFromBroker();
            }, "hrr-structure-bootstrap");
            boot.setDaemon(true);
            boot.start();
        } else {
            log.warn("[HRR-STRUCT] KiteConnect/InstrumentCache unavailable.");
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
                        restoreHtfFromRedis(symbol);
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

        // ── Wait for InstrumentCacheService to load (auth completes after @PostConstruct) ──
        // Problem: @PostConstruct fires before Zerodha auto-login completes (~10s delay).
        // InstrumentCacheService is empty at that point → bootstrap aborts.
        // Fix: poll every 2s for up to 90s until instruments are available.
        Map<String, Instrument> instruments = instrumentCache.getEquityInstruments();
        if (instruments.isEmpty()) {
            log.info("[HRR-STRUCT] Instruments not ready yet — waiting for auth to complete (max 90s)...");
            int waited = 0;
            while (instruments.isEmpty() && waited < 90) {
                try { Thread.sleep(2000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); break;
                }
                waited += 2;
                instruments = instrumentCache.getEquityInstruments();
            }
            if (instruments.isEmpty()) {
                log.warn("[HRR-STRUCT] Instruments still empty after {}s — bootstrap aborted. 8:50 AM refresh will retry.", waited);
                bootstrapComplete = true;
                return;
            }
            log.info("[HRR-STRUCT] Instruments available after {}s — proceeding with bootstrap.", waited);
        }

        LocalDate today = LocalDate.now(IST);
        java.util.Date fromDate = java.util.Date.from(
                today.minusDays(BOOTSTRAP_CALENDAR_DAYS).atStartOfDay(IST).toInstant());
        java.util.Date toDate = java.util.Date.from(
                today.atTime(15, 31).atZone(IST).toInstant());

        // CRITICAL FIX: Only bootstrap the 295 tracked Nifty500 symbols.
        // instruments.entrySet() contains ALL 9,759 NSE instruments.
        // Bootstrapping all 9,759 takes 2.7 hours and exhausts the Zerodha API quota.
        // Strategy: if candleBuffer is populated (WebSocket ticks arrived), use those symbols.
        //           If candleBuffer is empty (very first boot), use all instruments
        //           but the WebSocket subscription will populate candleBuffer with
        //           only the 295 subscribed symbols within seconds.
        //           So we wait up to 15s for candleBuffer to populate, then filter.
        // Determine which symbols to bootstrap:
        // Priority 1: levelCache already has Redis-restored symbols (from prior run)
        // Priority 2: htf30Cache has prior HTF data — use those symbols
        // Priority 3: filter instruments to Nifty500-sized list (≤500 symbols)
        //             by taking the most liquid stocks (market cap proxy = token order)
        // This guarantees we never bootstrap 9,759 symbols regardless of market hours.
        // Build candidate symbols from Redis caches — but FILTER strictly:
        // 1. Symbol must exist in instruments map (valid NSE symbol)
        // 2. Instrument must be NSE EQ type (not SGB, ETF, BE series, SM series)
        // 3. Instrument must have lot_size == 1 (equities only)
        // This prevents stale/garbage Redis keys from old bootstrap runs
        // (SGBs, bonds, ETFs) from being bootstrapped.
        Set<String> rawCached = new java.util.LinkedHashSet<>();
        rawCached.addAll(levelCache.keySet());
        rawCached.addAll(htf30Cache.keySet());
        rawCached.addAll(candleBuffer.keySet());
        // Copy to effectively final variable for use in lambda
        // (instruments is reassigned in the wait loop above, so not effectively final)
        final Map<String, Instrument> instrumentsFinal = instruments;
        // Step 1: Filter Redis keys to valid NSE EQ equities (removes bonds/ETFs/BE)
        Set<String> equitySymbols = rawCached.stream()
                .filter(sym -> {
                    Instrument inst = instrumentsFinal.get(sym);
                    if (inst == null) return false;
                    return "NSE".equals(inst.getExchange())
                            && "EQ".equals(inst.getInstrument_type())
                            && inst.getLot_size() == 1
                            && inst.getInstrument_token() > 0;
                })
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        // Step 2: Use FULL Nifty500 static list as bootstrap scope.
        // CRITICAL FIX: Previously used Redis∩Nifty500 = only symbols already in Redis.
        // This caused circular dependency — new symbols never entered bootstrap.
        // Fix: scope = full Nifty500 list (~253 resolved symbols).
        // Inside bootstrap task, candleBuffer.containsKey() skips API for cached symbols.
        // New symbols (not in Redis) get fetched from API — they now enter correctly.
        Set<String> nifty500 = getNifty500StaticList(instrumentsFinal);
        log.info("[HRR-STRUCT] Redis cache: {} raw → {} NSE EQ | Nifty500 scope: {} symbols",
                rawCached.size(), equitySymbols.size(), nifty500.size());
        // Determine final scope:
        // Priority 1: Full Nifty500 list (all symbols, cached or not)
        // Priority 2: First deploy path
        final Set<String> finalTracked;
        if (!nifty500.isEmpty()) {
            finalTracked = nifty500;
            long cached = nifty500.stream().filter(candleBuffer::containsKey).count();
            log.info("[HRR-STRUCT] Bootstrap scope: {} symbols ({} already cached, {} need API fetch)",
                    finalTracked.size(), cached, finalTracked.size() - cached);
        } else {
            // First-ever deploy: no Redis data. Use all instruments but
            // scanner will have tracked symbols registered already.
            // Limit to 500 to avoid 2-hour bootstrap on 9759 instruments.
            // First deploy: use scanner symbols if available, otherwise
            // filter instruments to known Nifty500 constituents.
            // This ensures we never bootstrap random 500 of 9,759 on first deploy.
            Set<String> scannerSyms = scanner != null ? scanner.getTrackedSymbols() : java.util.Collections.emptySet();
            if (!scannerSyms.isEmpty()) {
                finalTracked = scannerSyms;
            } else {
                // Filter by Nifty500 using InstrumentCacheService subscription tokens.
                // Wait up to 30s for subscription list to be built.
                Set<String> subscribed = getSubscribedSymbols(instruments);
                finalTracked = !subscribed.isEmpty() ? subscribed
                        : getNifty500StaticList(instruments);
            }
            log.info("[HRR-STRUCT] Bootstrap scope: {} symbols (first deploy)", finalTracked.size());
        }
        List<Map.Entry<String, Instrument>> entries = instruments.entrySet().stream()
                .filter(e -> finalTracked.contains(e.getKey()))
                .collect(java.util.stream.Collectors.toList());
        int total = entries.size(), loaded = 0, failed = 0;
        log.info("[HRR-STRUCT] Bootstrapping {} symbols ({} calendar days)...", total, BOOTSTRAP_CALENDAR_DAYS);

        // Parallel bootstrap: 4 threads, each handling a quarter of symbols.
        // Reduces first-deploy time from ~5 minutes to ~90 seconds.
        // Zerodha rate limit: ~3 calls/sec per token — 4 threads stay safe.
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.atomic.AtomicInteger loadedAtomic = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger failedAtomic = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();

        for (Map.Entry<String, Instrument> entry : entries) {
            String symbol  = entry.getKey();
            Instrument inst = entry.getValue();
            final java.util.Date fd = fromDate, td2 = toDate;
            final LocalDate tod = today;
            futures.add(pool.submit(() -> {
                try {
                    // Skip if candles already in buffer from Redis restore
                    if (candleBuffer.containsKey(symbol)) {
                        recomputeLevels(symbol);
                        fetchAndCacheHtf(symbol, inst, tod);
                        loadedAtomic.incrementAndGet();
                        return null;
                    }
                    // Guard: skip instruments with no valid token
                    long token = (long) inst.getInstrument_token();
                    if (token <= 0) {
                        log.debug("[HRR-STRUCT] Skipping {} — invalid token {}", symbol, token);
                        return null;
                    }
                    // Guard: skip non-equity instruments (should be filtered already,
                    // but double-check here to prevent API calls for SGBs/ETFs)
                    if (!"EQ".equals(inst.getInstrument_type())) {
                        log.debug("[HRR-STRUCT] Skipping {} — not EQ type ({})",
                                symbol, inst.getInstrument_type());
                        return null;
                    }
                    HistoricalData result = kiteConnect.getHistoricalData(
                            fd, td2,
                            String.valueOf(token),
                            "15minute", false, false);
                    if (result == null || result.dataArrayList == null
                            || result.dataArrayList.isEmpty()) return null;
                    Deque<Candle> dq = new ArrayDeque<>(MAX_CANDLES + 1);
                    List<HistoricalData> raw = result.dataArrayList;
                    for (int j = raw.size() - 1; j >= 0; j--) {
                        Candle c = buildCandle(symbol, raw.get(j));
                        dq.addLast(c);
                        if (dq.size() >= MAX_CANDLES) break;
                    }
                    candleBuffer.put(symbol, dq);
                    recomputeLevels(symbol);
                    fetchAndCacheHtf(symbol, inst, tod);
                    persistCandlesToRedis(symbol, dq);
                    loadedAtomic.incrementAndGet();
                } catch (Throwable e) {
                    failedAtomic.incrementAndGet();
                    // Log class name when message is null (e.g. NullPointerException)
                    String errMsg = e.getMessage() != null ? e.getMessage()
                            : e.getClass().getSimpleName();
                    log.debug("[HRR-STRUCT] Bootstrap failed {}: {}", symbol, errMsg);
                }
                return null;
            }));
            // Rate limiting: pause after every 2 submissions (one per thread)
            // 2 threads × pause 500ms = ~4 calls/second — within Zerodha limits
            // Previously: 4 threads × 200ms = 20 calls/second → NetworkException
            if (futures.size() % 2 == 0) {
                try { Thread.sleep(500); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); break;
                }
            }
        }

        // Wait for all parallel tasks to complete
        pool.shutdown();
        try {
            pool.awaitTermination(10, java.util.concurrent.TimeUnit.MINUTES);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        loaded  = loadedAtomic.get();
        failed  = failedAtomic.get();

        bootstrapComplete = true;

        // ── VALIDATION: check only OUR tracked symbols, not all 9792 instruments ─
        // Previous bug: iterated instruments.entrySet() = 9792 NSE instruments.
        // 9792 - 1382 cached = 8410 "missing" — false alarm, we only track ~295.
        // Now validates against finalTracked (the actual bootstrap scope).
        int withLevels      = 0;
        int withMajorLevel  = 0;
        int with5DayHistory = 0;
        int missingData     = 0;
        for (String sym : finalTracked) {
            StructureLevels lvl = levelCache.get(sym);
            if (lvl == null) { missingData++; continue; }
            withLevels++;
            if (lvl.majorLevelCount() > 0) withMajorLevel++;
            if (lvl.tradingDays() >= 2)    with5DayHistory++;
        }
        log.info("[HRR-STRUCT] ✅ Bootstrap complete in {}s: {}/{} loaded ({} failed).",
                String.format("%.1f", (System.currentTimeMillis() - bootstrapStartMs) / 1000.0),
                loaded, total, failed);
        log.info("[HRR-STRUCT] Validation: {}/{} tracked symbols have S/R | {} have major level "
                        + "| {} have ≥2 days history | {} missing",
                withLevels, finalTracked.size(), withMajorLevel, with5DayHistory, missingData);
        if (missingData > 10) {
            log.warn("[HRR-STRUCT] ⚠️ {} of {} tracked symbols missing S/R data.",
                    missingData, finalTracked.size());
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
                // strict > captures equal-high consolidation zones as resistance
                if (candles.get(i - k).getHigh().doubleValue() > h ||
                        candles.get(i + k).getHigh().doubleValue() > h) {
                    isSwingHigh = false;
                    break;
                }
            }
            if (isSwingHigh) rawResistances.add(h);

            // Swing low: lower than all candles within SWING_LOOKBACK on each side
            boolean isSwingLow = true;
            for (int k = 1; k <= SWING_LOOKBACK; k++) {
                // strict < captures equal-low consolidation zones as support
                if (candles.get(i - k).getLow().doubleValue() < l ||
                        candles.get(i + k).getLow().doubleValue() < l) {
                    isSwingLow = false;
                    break;
                }
            }
            if (isSwingLow) rawSupports.add(l);
        }

        // ── Step 1b: WEEKLY swing detection (SWING_LOOKBACK_WEEKLY = 26) ────────
        // Second pass with 1-day lookback on each side to find MULTI-WEEK significant
        // highs and lows — the thick horizontal zones in your picture.
        // A swing high dominating a full day on each side = major institutional level.
        // These are added to rawResistances/rawSupports WITH a weight marker.
        // The cluster step merges them with intraday swings into one strong zone.
        if (n >= SWING_LOOKBACK_WEEKLY * 2 + 3) {
            for (int i = SWING_LOOKBACK_WEEKLY; i < n - SWING_LOOKBACK_WEEKLY; i++) {
                double h = candles.get(i).getHigh().doubleValue();
                double l = candles.get(i).getLow().doubleValue();
                boolean isWeeklyHigh = true, isWeeklyLow = true;
                for (int k = 1; k <= SWING_LOOKBACK_WEEKLY; k++) {
                    if (candles.get(i - k).getHigh().doubleValue() > h ||
                            candles.get(i + k).getHigh().doubleValue() > h) {
                        isWeeklyHigh = false;
                    }
                    if (candles.get(i - k).getLow().doubleValue() < l ||
                            candles.get(i + k).getLow().doubleValue() < l) {
                        isWeeklyLow = false;
                    }
                }
                // Add weekly swings — add TWICE to give them extra weight in clustering
                // (more occurrences in the raw list = more touches counted = higher strength)
                if (isWeeklyHigh) { rawResistances.add(h); rawResistances.add(h); }
                if (isWeeklyLow)  { rawSupports.add(l);    rawSupports.add(l); }
            }
        }

        // ── Step 2: Previous Day High / Low / Close ────────────────────────────
        // Find the last completed trading day boundary in the buffer.
        // candles are newest-first, so we scan for day transitions.
        double pdh = 0, pdl = Double.MAX_VALUE, pdc = 0;
        boolean foundYesterdayCandle = false;

        // Identify yesterday's candles (first group after today's candles)
        LocalDate today = LocalDate.now(IST);
        LocalDate yesterday = today.minusDays(1);

        // Approximate day boundary at index 26 (26 × 15-min = 1 trading day)
        int dayBoundary = 26;
        if (n > dayBoundary + 5) {
            for (int i = dayBoundary; i < Math.min(dayBoundary * 2, n); i++) {
                double h = candles.get(i).getHigh().doubleValue();
                double l = candles.get(i).getLow().doubleValue();
                double cc = candles.get(i).getClose().doubleValue();
                if (h > pdh) pdh = h;
                if (l < pdl) pdl = l;
                pdc = cc; foundYesterdayCandle = true;
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

        // ── Mark 90-day high/low as MAJOR ──────────────────────────────────
        // The 90-day high IS the strongest resistance on the chart.
        // Every breakout trader watches it. Every option writer defends it.
        // Same for 90-day low = strongest support.
        // Only add if not already present within CLUSTER_PCT of an existing level.
        double ninetyDayHigh = candles.stream()
                .mapToDouble(c -> c.getHigh().doubleValue()).max().orElse(0);
        double ninetyDayLow  = candles.stream()
                .mapToDouble(c -> c.getLow().doubleValue()).min().orElse(0);
        if (ninetyDayHigh > 0) {
            boolean alreadyCovered = resistances.stream().anyMatch(
                    r -> Math.abs(r.price() - ninetyDayHigh) / ninetyDayHigh <= CLUSTER_PCT);
            if (!alreadyCovered) rawResistances.add(ninetyDayHigh);
            resistances = markAsMajor(resistances, ninetyDayHigh); // force MAJOR
        }
        if (ninetyDayLow > 0) {
            boolean alreadyCovered = supports.stream().anyMatch(
                    s -> Math.abs(s.price() - ninetyDayLow) / ninetyDayLow <= CLUSTER_PCT);
            if (!alreadyCovered) rawSupports.add(ninetyDayLow);
            supports = markAsMajor(supports, ninetyDayLow); // force MAJOR
        }

        // Sort: supports descending (highest support first), resistances ascending (lowest first)
        resistances.sort(Comparator.comparingDouble(SRLevel::price));
        supports.sort(Comparator.comparingDouble(SRLevel::price).reversed());

        // ── Step 4: Compute multi-day context, build and cache result ─────────
        double currentPrice = candles.get(0).getClose().doubleValue();

        // tradingDays: estimate from buffer size (26 candles per trading day on NSE)
        // With MAX_CANDLES=260: max tradingDays = 260/26 = 10
        int tradingDays = Math.max(1, n / 26);

        // 90-day high/low: full buffer up to 2400 candles = 90 trading days.
        // Previously capped at 260 (10 days) — this was a bug left from the old system.
        // Now uses the full buffer so Gate5c range position reflects the true 90-day range.
        int ninetyDayLen = n; // use all available candles (up to MAX_CANDLES=2400)
        double tenDayHigh = candles.subList(0, ninetyDayLen).stream()
                .mapToDouble(c -> c.getHigh().doubleValue()).max().orElse(currentPrice);
        double tenDayLow  = candles.subList(0, ninetyDayLen).stream()
                .mapToDouble(c -> c.getLow().doubleValue()).min().orElse(currentPrice);

        // MA20: simple moving average of last 20 closing prices
        int ma20Len = Math.min(n, 20);
        double ma20 = candles.subList(0, ma20Len).stream()
                .mapToDouble(c -> c.getClose().doubleValue()).average().orElse(currentPrice);

        // Trendline detection — connect swing lows (rising) and swing highs (falling)
        double trendlineSup = computeTrendlinePrice(candles, true);
        double trendlineRes = computeTrendlinePrice(candles, false);

        StructureLevels levels = new StructureLevels(
                symbol, supports, resistances, currentPrice,
                System.currentTimeMillis(),
                tradingDays, tenDayHigh, tenDayLow, ma20,
                trendlineSup, trendlineRes);

        // levelCache.put() first — engine reads from memory, never from Redis.
        // persistToRedis() is fire-and-forget: Redis is only for restart recovery.
        // A Redis timeout (rare) must NEVER block the tradingExecutor thread.
        levelCache.put(symbol, levels);
        final StructureLevels toSave = levels;
        new Thread(() -> persistToRedis(symbol, toSave), "hrr-redis-persist").start();

        log.trace("[HRR-STRUCT] {} computed: {} supports, {} resistances (from {} candles)",
                symbol, supports.size(), resistances.size(), n);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HTF DATA FETCH — institutional-grade S/R from 30d and 90d daily data
    // ══════════════════════════════════════════════════════════════════════════

    void fetchAndCacheHtf(String symbol, Instrument inst, LocalDate today) {
        if (kiteConnect == null || inst == null) return;
        try {
            java.util.Date to = java.util.Date.from(
                    today.atTime(15, 31).atZone(IST).toInstant());
            String token = String.valueOf((long) inst.getInstrument_token());

            // 30-day daily fetch
            java.util.Date from30 = java.util.Date.from(
                    today.minusDays(HTF_30D_CALENDAR_DAYS).atStartOfDay(IST).toInstant());
            HistoricalData res30 = kiteConnect.getHistoricalData(
                    from30, to, token, "day", false, false);
            if (res30 != null && res30.dataArrayList != null && !res30.dataArrayList.isEmpty()) {
                List<SRLevel> lvl30 = computeHtfLevels(res30.dataArrayList);
                htf30Cache.put(symbol, lvl30);
                persistHtfToRedis(KEY_30D_PREFIX + symbol, lvl30);
            }

            // 90-day daily fetch
            java.util.Date from90 = java.util.Date.from(
                    today.minusDays(HTF_90D_CALENDAR_DAYS).atStartOfDay(IST).toInstant());
            HistoricalData res90 = kiteConnect.getHistoricalData(
                    from90, to, token, "day", false, false);
            if (res90 != null && res90.dataArrayList != null && !res90.dataArrayList.isEmpty()) {
                List<SRLevel> lvl90 = computeHtfLevels(res90.dataArrayList);
                htf90Cache.put(symbol, lvl90);
                persistHtfToRedis(KEY_90D_PREFIX + symbol, lvl90);
            }
            log.trace("[HRR-STRUCT] {} HTF fetch done.", symbol);
        } catch (Throwable e) {
            // KiteException extends Throwable directly — must use Throwable, not Exception
            log.trace("[HRR-STRUCT] HTF failed {}: {}", symbol, e.getMessage());
        }
    }

    private List<SRLevel> computeHtfLevels(List<HistoricalData> data) {
        if (data == null || data.size() < 6) return Collections.emptyList();
        int n = data.size();
        List<Double> rawR = new ArrayList<>(), rawS = new ArrayList<>();
        for (int i = 2; i < n - 2; i++) {
            double h = data.get(i).high, l = data.get(i).low;
            if (data.get(i-1).high < h && data.get(i-2).high < h
                    && data.get(i+1).high < h && data.get(i+2).high < h) rawR.add(h);
            if (data.get(i-1).low > l && data.get(i-2).low > l
                    && data.get(i+1).low > l && data.get(i+2).low > l) rawS.add(l);
        }
        double absH = data.stream().mapToDouble(d -> d.high).max().orElse(0);
        double absL = data.stream().mapToDouble(d -> d.low).min().orElse(0);
        // Only add absolute extremes if tested multiple times — untested extremes are not S/R
        if (absH > 0 && data.stream().filter(d -> Math.abs(d.high - absH) / absH
                <= HTF_CLUSTER_PCT).count() >= 2) rawR.add(absH);
        if (absL > 0 && data.stream().filter(d -> Math.abs(d.low - absL) / absL
                <= HTF_CLUSTER_PCT).count() >= 2) rawS.add(absL);
        List<SRLevel> all = new ArrayList<>();
        all.addAll(clusterHtfLevels(rawR, data, false));
        all.addAll(clusterHtfLevels(rawS, data, true));
        all.sort(Comparator.comparingInt(SRLevel::touchCount).reversed());
        return all;
    }

    private List<SRLevel> clusterHtfLevels(List<Double> raw,
                                           List<HistoricalData> data, boolean isSup) {
        if (raw.isEmpty()) return Collections.emptyList();
        List<Double> sorted = new ArrayList<>(raw);
        Collections.sort(sorted);
        // Compare each new price to LAST member of current group, not first anchor.
        // Prevents drift: [5100,5120,5140] — 5140 vs 5100 = 0.78% (borderline)
        // but 5140 vs 5120 = 0.39% (correctly same cluster).
        List<Double> cls = new ArrayList<>();
        List<Double> grp = new ArrayList<>();
        grp.add(sorted.get(0));
        for (int i = 1; i < sorted.size(); i++) {
            double p = sorted.get(i);
            double last = grp.get(grp.size() - 1);
            if ((p - last) / last <= HTF_CLUSTER_PCT) { grp.add(p); }
            else {
                cls.add(grp.stream().mapToDouble(d->d).average().orElse(last));
                grp.clear(); grp.add(p);
            }
        }
        if (!grp.isEmpty()) { double last = grp.get(grp.size()-1);
            cls.add(grp.stream().mapToDouble(d->d).average().orElse(last)); }
        List<SRLevel> res = new ArrayList<>();
        for (double level : cls) {
            int t = (int) data.stream().filter(d -> {
                double pr = isSup ? d.low : d.high;
                return Math.abs(pr - level) / level <= HTF_CLUSTER_PCT;
            }).count();
            if (t >= MIN_TOUCHES_HTF) {
                double rej = computeHtfRejection(level, data, isSup);
                boolean vc = hasHtfVolumeConfirmation(level, data, isSup);
                String strength = classifyStrength(t, rej, vc, true);
                // HTF: only STRONG and MAJOR zones qualify
                if ("STRONG".equals(strength) || "MAJOR".equals(strength)) {
                    double peakVR = data.isEmpty() ? 1.0
                            : data.stream().filter(d -> {
                        double pr = isSup ? d.low : d.high;
                        return Math.abs(pr - level) / level <= HTF_CLUSTER_PCT;
                    }).mapToDouble(d -> {
                        double avgV = data.stream().mapToDouble(x -> x.volume).average().orElse(1);
                        return avgV > 0 ? d.volume / avgV : 1.0;
                    }).max().orElse(1.0);
                    res.add(new SRLevel(
                            BigDecimal.valueOf(level).setScale(2, RoundingMode.HALF_UP).doubleValue(),
                            t, true, rej, vc, strength, "htf", 999, peakVR));
                }
            }
        }
        return res;
    }

    private void persistHtfToRedis(String key, List<SRLevel> levels) {
        if (redis == null) return;
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(levels), TTL_HTF);
        } catch (Exception e) {
            log.trace("[HRR-STRUCT] HTF persist failed {}: {}", key, e.getMessage());
        }
    }

    private void restoreHtfFromRedis(String symbol) {
        if (redis == null) return;
        try {
            String j30 = redis.opsForValue().get(KEY_30D_PREFIX + symbol);
            if (j30 != null) htf30Cache.put(symbol, objectMapper.readValue(j30,
                    new TypeReference<List<SRLevel>>() {}));
            String j90 = redis.opsForValue().get(KEY_90D_PREFIX + symbol);
            if (j90 != null) htf90Cache.put(symbol, objectMapper.readValue(j90,
                    new TypeReference<List<SRLevel>>() {}));
        } catch (Exception e) {
            log.trace("[HRR-STRUCT] HTF restore failed {}: {}", symbol, e.getMessage());
        }
    }

    /**
     * Cluster raw price levels within CLUSTER_PCT of each other.
     * Returns SRLevel list with touch counts computed against candle history.
     */
    private List<SRLevel> clusterLevels(List<Double> raw, List<Candle> candles, boolean isSupport) {
        if (raw.isEmpty()) return Collections.emptyList();

        // Clustering (CLUSTER_PCT=0.4%) merges nearby levels — no pre-filter needed.
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

        // Compute full institutional quality metrics per cluster
        List<SRLevel> result = new ArrayList<>();
        double avgVol = candles.stream().mapToLong(Candle::getVolume)
                .average().orElse(1);
        for (double level : clustered) {
            int     touches   = countTouches(level, candles, isSupport);
            double  rejection = computeAvgRejection(level, candles, isSupport);
            boolean volConf   = hasVolumeConfirmation(level, candles, isSupport, avgVol);
            String  strength  = classifyStrength(touches, rejection, volConf, false);
            if ("WEAK".equals(strength)) continue;
            // Recency: find the most recently touched candle index
            int lastIdx = findLastTouchIndex(level, candles, isSupport);
            // Peak volume ratio at any touch
            double maxVR = findPeakVolRatio(level, candles, isSupport, avgVol);
            result.add(new SRLevel(
                    BigDecimal.valueOf(level).setScale(2, RoundingMode.HALF_UP).doubleValue(),
                    touches,
                    "STRONG".equals(strength) || "MAJOR".equals(strength),
                    rejection, volConf, strength, "15min", lastIdx, maxVR
            ));
        }
        return result;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // INSTITUTIONAL QUALITY HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Classify zone strength based on real market evidence.
     *
     * MAJOR:    touches ≥ 3 AND (rejection ≥ 1.0% OR volume confirmed)
     *            OR HTF touches ≥ 2 AND rejection ≥ 0.8%
     * STRONG:   touches ≥ 3 AND rejection ≥ 0.5%
     *            OR HTF touches ≥ 2
     * MODERATE: touches ≥ 2 AND rejection ≥ 0.3%
     * WEAK:     everything else → filtered out
     */
    private String classifyStrength(int touches, double rejectionPct,
                                    boolean volConf, boolean isHtf) {
        if (isHtf) {
            // HTF daily: MAJOR = 4+ touches OR (3 touches + strong rejection ≥ 1%)
            // STRONG = 3 touches confirmed. Below that = WEAK.
            if (touches >= 4 || (touches >= 3 && rejectionPct >= 0.010)) return "MAJOR";
            if (touches >= 3) return "STRONG";
            return "WEAK";
        }
        // 15-min levels — very strict to filter small zones
        // MAJOR: 5+ touches AND (rejection ≥ 1.5% OR volume spike)
        // STRONG: 4+ touches AND rejection ≥ 0.8%
        // MODERATE: 3+ touches AND rejection ≥ 0.5%
        // WEAK: everything else — never stored, never used
        if (touches >= 5 && (rejectionPct >= 0.015 || volConf)) return "MAJOR";
        if (touches >= 4 && rejectionPct >= 0.008) return "STRONG";
        if (touches >= 3 && rejectionPct >= 0.005) return "MODERATE";
        return "WEAK";
    }

    /**
     * Average % price moved AWAY from the level after each touch.
     * Measures how strong the rejection was — the core evidence of institutional activity.
     * Support: price touched the low, then closed higher → rejection measured as (close-low)/level.
     * Resistance: price touched the high, then closed lower → rejection = (high-close)/level.
     */
    private double computeAvgRejection(double level, List<Candle> candles,
                                       boolean isSupport) {
        List<Double> rejections = new ArrayList<>();
        for (Candle c : candles) {
            double touch = isSupport ? c.getLow().doubleValue() : c.getHigh().doubleValue();
            if (Math.abs(touch - level) / level <= TOUCH_TOLERANCE_PCT) {
                double close = c.getClose().doubleValue();
                double rejection = isSupport
                        ? (close - touch) / level   // price bounced up
                        : (touch - close) / level;  // price rejected down
                if (rejection > 0) rejections.add(rejection);
            }
        }
        return rejections.isEmpty() ? 0
                : rejections.stream().mapToDouble(d -> d).average().orElse(0);
    }

    /**
     * True if at least one touch at this level had volume above average.
     * Volume above average at a S/R level = institutions defending the zone.
     */
    private boolean hasVolumeConfirmation(double level, List<Candle> candles,
                                          boolean isSupport, double avgVolume) {
        for (Candle c : candles) {
            double touch = isSupport ? c.getLow().doubleValue() : c.getHigh().doubleValue();
            if (Math.abs(touch - level) / level <= TOUCH_TOLERANCE_PCT) {
                if (c.getVolume() > avgVolume * 1.3) return true; // 30% above avg
            }
        }
        return false;
    }

    /** HTF rejection: avg % price moved away from level on daily candles. */
    private double computeHtfRejection(double level,
                                       List<HistoricalData> data, boolean isSup) {
        List<Double> rejections = new ArrayList<>();
        for (HistoricalData d : data) {
            double touch = isSup ? d.low : d.high;
            if (Math.abs(touch - level) / level <= HTF_CLUSTER_PCT) {
                double rej = isSup ? (d.close - touch) / level : (touch - d.close) / level;
                if (rej > 0) rejections.add(rej);
            }
        }
        return rejections.isEmpty() ? 0
                : rejections.stream().mapToDouble(v -> v).average().orElse(0);
    }

    /** HTF volume confirmation: any touch day had volume above 30d avg. */
    private boolean hasHtfVolumeConfirmation(double level,
                                             List<HistoricalData> data, boolean isSup) {
        double avgVol = data.stream().mapToDouble(d -> d.volume).average().orElse(1);
        for (HistoricalData d : data) {
            double touch = isSup ? d.low : d.high;
            if (Math.abs(touch - level) / level <= HTF_CLUSTER_PCT) {
                if (d.volume > avgVol * 1.3) return true;
            }
        }
        return false;
    }

    /** Index (0=newest) of the most recently touched candle. 999 if no touch found. */
    private int findLastTouchIndex(double level, List<Candle> candles, boolean isSupport) {
        for (int i = 0; i < candles.size(); i++) {
            double price = isSupport
                    ? candles.get(i).getLow().doubleValue()
                    : candles.get(i).getHigh().doubleValue();
            if (Math.abs(price - level) / level <= TOUCH_TOLERANCE_PCT) return i;
        }
        return 999;
    }

    /** Peak volume/avgVol ratio at any touch of the level. */
    private double findPeakVolRatio(double level, List<Candle> candles,
                                    boolean isSupport, double avgVol) {
        if (avgVol <= 0) return 1.0;
        double peak = 1.0;
        for (Candle c : candles) {
            double price = isSupport ? c.getLow().doubleValue() : c.getHigh().doubleValue();
            if (Math.abs(price - level) / level <= TOUCH_TOLERANCE_PCT) {
                double ratio = c.getVolume() / avgVol;
                if (ratio > peak) peak = ratio;
            }
        }
        return peak;
    }

    /**
     * Detect a rising (isSupport=true) or falling (isSupport=false) trendline
     * from the candle buffer and return its CURRENT price (at candle index 0).
     *
     * Algorithm:
     *   1. Collect swing lows (for rising) or swing highs (for falling)
     *      These are the same swings used for horizontal S/R detection.
     *   2. Find the two most recent swing points that form a valid trendline:
     *      Rising: second low > first low (higher lows = uptrend)
     *      Falling: second high < first high (lower highs = downtrend)
     *   3. Compute the line through these two points using linear regression.
     *   4. Project the line forward to candle index 0 (current price).
     *   5. Validate: at least 3 candles must be within TOUCH_TOLERANCE_PCT of
     *      the trendline — confirms it is a genuine multi-touch trendline.
     *
     * Returns the trendline price at current candle, or 0.0 if no valid
     * trendline is found (not enough touches or no valid slope).
     */
    private double computeTrendlinePrice(List<Candle> candles, boolean isSupport) {
        if (candles.size() < SWING_LOOKBACK * 4) return 0.0;
        int n = candles.size();

        // Collect swing points (index, price) — candles are newest-first
        // We reverse to get oldest-first for chronological slope computation
        List<double[]> swings = new ArrayList<>(); // {index_from_oldest, price}
        for (int i = SWING_LOOKBACK; i < n - SWING_LOOKBACK; i++) {
            double price = isSupport
                    ? candles.get(i).getLow().doubleValue()
                    : candles.get(i).getHigh().doubleValue();
            boolean isSwing = true;
            for (int k = 1; k <= SWING_LOOKBACK; k++) {
                double prev = isSupport
                        ? candles.get(i - k).getLow().doubleValue()
                        : candles.get(i - k).getHigh().doubleValue();
                double next = isSupport
                        ? candles.get(i + k).getLow().doubleValue()
                        : candles.get(i + k).getHigh().doubleValue();
                if (isSupport ? (prev < price || next < price)
                        : (prev > price || next > price)) {
                    isSwing = false; break;
                }
            }
            if (isSwing) {
                // Convert to chronological index (oldest=0, newest=n-1)
                swings.add(new double[]{n - 1 - i, price});
            }
        }
        if (swings.size() < 2) return 0.0;

        // Sort by chronological index (oldest first)
        swings.sort(Comparator.comparingDouble(a -> a[0]));

        // Find best two anchor points forming a valid trendline:
        // Rising (support): look for two swing lows where second > first (higher lows)
        // Falling (resistance): look for two swing highs where second < first (lower highs)
        double[] p1 = null, p2 = null;
        for (int i = 0; i < swings.size() - 1; i++) {
            for (int j = i + 1; j < swings.size(); j++) {
                double[] a = swings.get(i), b = swings.get(j);
                boolean validSlope = isSupport ? (b[1] > a[1]) : (b[1] < a[1]);
                // Minimum separation: at least 10 candles apart (avoid noise)
                boolean separated = (b[0] - a[0]) >= 10;
                if (validSlope && separated) {
                    // Prefer the two most recent swing points
                    p1 = a; p2 = b;
                }
            }
        }
        if (p1 == null || p2 == null) return 0.0;

        // Compute slope: rise / run between the two anchor points
        double slope = (p2[1] - p1[1]) / (p2[0] - p1[0]);

        // Project to current candle (chronological index = n-1)
        double currentIdx = n - 1;
        double trendlineAtCurrent = p1[1] + slope * (currentIdx - p1[0]);

        // Validate: count candles where price was within TOUCH_TOLERANCE_PCT of trendline
        // A real trendline has 3+ touches — fake ones have only 2 (just the anchors)
        int touches = 0;
        for (int i = 0; i < n; i++) {
            double chrono  = n - 1 - i; // convert newest-first index to chronological
            double tlPrice = p1[1] + slope * (chrono - p1[0]);
            double actual  = isSupport
                    ? candles.get(i).getLow().doubleValue()
                    : candles.get(i).getHigh().doubleValue();
            if (tlPrice > 0 && Math.abs(actual - tlPrice) / tlPrice <= TOUCH_TOLERANCE_PCT) {
                touches++;
            }
        }
        // Need at least 3 touches to be a valid tradeable trendline
        if (touches < 3) return 0.0;

        // Sanity: trendline price must be near current price (within 5%)
        double cur = candles.get(0).getClose().doubleValue();
        if (cur > 0 && Math.abs(trendlineAtCurrent - cur) / cur > 0.05) return 0.0;

        return BigDecimal.valueOf(trendlineAtCurrent)
                .setScale(2, RoundingMode.HALF_UP).doubleValue();
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
                        ? new SRLevel(l.price(), 99, true, l.avgRejectionPct(),
                        l.hasVolumeConfirm(), "MAJOR", l.source(),
                        l.lastTestedIdx(), l.maxVolRatio())
                        : l)
                .collect(Collectors.toList());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // REDIS PERSISTENCE — fire-and-forget on tradingExecutor
    // ══════════════════════════════════════════════════════════════════════════

    private void persistCandlesToRedis(String symbol, Deque<Candle> candles) {
        if (redis == null || candles == null || candles.isEmpty()) return;
        try {
            String json = objectMapper.writeValueAsString(new ArrayList<>(candles));
            redis.opsForValue().set(KEY_CANDLE_PREFIX + symbol, json, TTL_CANDLE);
        } catch (Exception e) {
            log.debug("[HRR-STRUCT] Candle persist failed {}: {}", symbol, e.getMessage());
        }
    }

    private int restoreCandlesFromRedis() {
        if (redis == null) return 0;
        try {
            Set<String> keys = redis.keys(KEY_CANDLE_PREFIX + "*");
            if (keys == null || keys.isEmpty()) return 0;
            int count = 0;
            for (String key : keys) {
                String symbol = key.substring(KEY_CANDLE_PREFIX.length());
                try {
                    String json = redis.opsForValue().get(key);
                    if (json == null || json.isBlank()) continue;
                    List<Candle> list = objectMapper.readValue(json,
                            objectMapper.getTypeFactory()
                                    .constructCollectionType(List.class, Candle.class));
                    if (list != null && !list.isEmpty()) {
                        candleBuffer.put(symbol, new ArrayDeque<>(list));
                        count++;
                    }
                } catch (Exception e) {
                    log.debug("[HRR-STRUCT] Candle restore failed {}: {}", symbol, e.getMessage());
                }
            }
            log.info("[HRR-STRUCT] Candle restore complete: {}/{} symbols", count, keys.size());
            return count;
        } catch (Exception e) {
            log.warn("[HRR-STRUCT] Candle restore error: {}", e.getMessage());
            return 0;
        }
    }

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
     * A validated Support or Resistance zone.
     *
     * Strength tiers:
     *   WEAK     — 1-2 touches, no volume confirmation, reaction < 0.3%
     *              → IGNORED: never used for entry gating
     *   MODERATE — 2-3 touches, reaction ≥ 0.3%, no volume spike
     *              → allowed for 15-min pullback entries only
     *   STRONG   — 3+ touches, avg reaction ≥ 0.5%, at least 1 session gap between tests
     *              → primary entry zone
     *   MAJOR    — 3+ touches OR from HTF daily data with 2+ reactions AND volume spike
     *              → highest priority — used for bonus score
     *
     * @param price              Exact level price (2dp).
     * @param touchCount         Times price came within tolerance of this level.
     * @param isMajor            true for STRONG or MAJOR tiers.
     * @param avgRejectionPct    Average % price moved away after touching the level.
     * @param hasVolumeConfirm   true if any touch had above-average volume.
     * @param strength           Zone strength: WEAK/MODERATE/STRONG/MAJOR.
     * @param source             "15min", "30d", or "90d".
     */
    /**
     * A validated S/R zone with full institutional quality metadata.
     *
     * @param price            Exact level price (2dp)
     * @param touchCount       Times price came within tolerance
     * @param isMajor          STRONG or MAJOR tier
     * @param avgRejectionPct  Avg % price moved away after touching
     * @param hasVolumeConfirm Any touch had above-average volume
     * @param strength         WEAK/MODERATE/STRONG/MAJOR
     * @param source           "15min", "htf"
     * @param lastTestedIdx    Candle index (newest=0) of most recent touch
     *                         Lower = more recent = higher priority
     * @param maxVolRatio      Highest volume/avgVol ratio seen at any touch
     *                         > 2.0 = heavy institutional footprint
     */
    public record SRLevel(
            double  price,
            int     touchCount,
            boolean isMajor,
            double  avgRejectionPct,
            boolean hasVolumeConfirm,
            String  strength,
            String  source,
            int     lastTestedIdx,    // candle index of most recent touch (0=most recent)
            double  maxVolRatio       // peak volume/avgVol at any touch
    ) {
        public static SRLevel basic(double price, int touches, boolean major) {
            String str = major ? "STRONG" : (touches >= 2 ? "MODERATE" : "WEAK");
            return new SRLevel(price, touches, major, 0.0, false, str, "15min", 999, 1.0);
        }
        /** True when zone is strong enough to gate entry (STRONG or MAJOR). */
        public boolean isEntryGrade() { return "STRONG".equals(strength) || "MAJOR".equals(strength); }
        /** Zone tested within last N candles — higher priority. */
        public boolean isRecent(int nCandles) { return lastTestedIdx <= nCandles; }
        /** Score combining strength, recency, and volume. Used for zone ranking. */
        public double qualityScore() {
            double base = "MAJOR".equals(strength) ? 100
                    : "STRONG".equals(strength) ? 70
                    : "MODERATE".equals(strength) ? 40 : 0;
            // Recency bonus: tested in last 50 candles (≈2 weeks) = +20
            double recency = lastTestedIdx <= 50 ? 20 : (lastTestedIdx <= 130 ? 10 : 0);
            // Volume bonus: peak vol > 2× avg = +15, > 1.5× = +8
            double volBonus = maxVolRatio >= 2.0 ? 15 : (maxVolRatio >= 1.5 ? 8 : 0);
            // Rejection depth: avg rejection > 1% = +10
            double rejBonus = avgRejectionPct >= 0.010 ? 10 : (avgRejectionPct >= 0.005 ? 5 : 0);
            return base + recency + volBonus + rejBonus;
        }
    }

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
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    /**
     * trendlineSupport:    price of rising trendline at current candle (0 = none detected).
     * trendlineResistance: price of falling trendline at current candle (0 = none detected).
     * These are DYNAMIC levels — they change each candle as the trendline advances.
     * When price is within 0.3% of trendlineSupport = price touching rising trendline.
     * When price is within 0.3% of trendlineResistance = price touching falling trendline.
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
            double        ma20,
            double        trendlineSupport,
            double        trendlineResistance
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

        /** True when price is within tolerance% of the rising trendline. */
        public boolean atRisingTrendline(double price, double tolerancePct) {
            return trendlineSupport > 0
                    && Math.abs(price - trendlineSupport) / trendlineSupport <= tolerancePct;
        }

        /** True when price is within tolerance% of the falling trendline. */
        public boolean atFallingTrendline(double price, double tolerancePct) {
            return trendlineResistance > 0
                    && Math.abs(price - trendlineResistance) / trendlineResistance <= tolerancePct;
        }
    }

    // ── First-deploy symbol resolution helpers ───────────────────────────────

    /**
     * Try to get the Nifty500 subscription list from InstrumentCacheService.
     * Waits up to 30s for the subscription to be built after instrument load.
     * Returns subset of instruments that are in the subscription.
     */
    private Set<String> getSubscribedSymbols(Map<String, Instrument> instruments) {
        // The subscription is built by InstrumentCacheService after loading.
        // We detect it by checking if the instrument has a valid token AND
        // is in the NSE EQ segment (equity) — this filters out F&O instruments.
        // A more reliable approach: wait for the token set in instrumentCache.
        try {
            for (int attempt = 0; attempt < 15; attempt++) {
                // getNiftySubscribedSymbols if available (returns Set<String>)
                // Fallback: filter equity instruments to those with valid tokens
                Set<String> result = instruments.entrySet().stream()
                        .filter(e -> {
                            Instrument inst = e.getValue();
                            // Only NSE EQ equity instruments — excludes F&O, currency etc
                            return "NSE".equals(inst.getExchange())
                                    && "EQ".equals(inst.getInstrument_type())
                                    && inst.getInstrument_token() > 0
                                    && inst.getLot_size() == 1; // equity lot = 1
                        })
                        .map(Map.Entry::getKey)
                        .collect(java.util.stream.Collectors.toSet());
                // Nifty500 should have ~500 symbols. If we get close, use it.
                if (result.size() >= 200 && result.size() <= 600) {
                    log.info("[HRR-STRUCT] Subscription filter: {} NSE EQ instruments found", result.size());
                    return result;
                }
                Thread.sleep(2000);
            }
        } catch (Exception e) {
            log.debug("[HRR-STRUCT] getSubscribedSymbols error: {}", e.getMessage());
        }
        return java.util.Collections.emptySet();
    }

    /**
     * Static Nifty500 constituent list — used as absolute last resort on first deploy.
     * Contains the most liquid 295 NSE equity symbols.
     * Returns only those that exist in the instrument map.
     */
    /**
     * Complete Nifty500 symbol list with exact Zerodha instrument names.
     * These names match what InstrumentCacheService resolves (292 of 295).
     * 6 tokens not found: SEQUENTSCIEN, LTIM, TATAMOTORS, PIRAMALEE, HBLPOWER, ZOMATO.
     * Updated to 295 symbols — previous list had only 198 with wrong names.
     */
    private Set<String> getNifty500StaticList(Map<String, Instrument> instruments) {
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
        log.info("[HRR-STRUCT] Static Nifty500 list: {} of {} symbols resolved in instruments",
                result.size(), nifty500.size());
        return result;
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

    /**
     * Returns the most recent N 15-min candles for a symbol (newest first).
     * Used by the engine for candle confirmation validation.
     * Returns empty list if symbol not in buffer or fewer than N candles available.
     */
    public List<Candle> getRecentCandles(String symbol, int n) {
        Deque<Candle> buf = candleBuffer.get(symbol);
        if (buf == null || buf.isEmpty()) return Collections.emptyList();
        return buf.stream().limit(n).collect(Collectors.toList());
    }
}