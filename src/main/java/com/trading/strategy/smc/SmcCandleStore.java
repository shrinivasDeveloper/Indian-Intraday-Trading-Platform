package com.trading.strategy.smc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.domain.Candle;
import com.trading.events.CandleCompleteEvent;
import com.trading.marketdata.service.InstrumentCacheService;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.models.HistoricalData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SmcCandleStore — rolling 15-min candle buffer with full Redis persistence
 * and Zerodha historical bootstrap.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * ARCHITECTURE — Two-layer startup strategy:
 *
 * LAYER 1 — Redis restore (@PostConstruct, ~200ms):
 *   On every startup, loads previously persisted candles from Redis.
 *   Key: "smc:candles:15m:{symbol}"  TTL: 7 days
 *   After the first full accumulation cycle (~4 days), every subsequent
 *   deployment restores all 295 symbols instantly and SMC is ready.
 *
 * LAYER 2 — Zerodha historical bootstrap (only when Redis is empty):
 *   On first deployment (or after a Redis flush), Redis has no candle keys.
 *   The bootstrap fetches the last 5 trading days of 15-min OHLCV data
 *   from Zerodha's historical API for all 295 subscribed symbols.
 *   5 days × 25 candles = ~125 candles per symbol → exceeds the 80-candle
 *   isReady() threshold → SMC can trade from the very first day of deployment.
 *
 *   Bootstrap timing:
 *     ~30 seconds total (295 symbols, batches of 50, 250ms rate-limit pause)
 *     Runs only when Redis has zero smc:candles:15m:* keys
 *     After the first successful bootstrap, Redis has data and this path
 *     never runs again (unless Redis is explicitly flushed)
 *
 * LAYER 3 — Live candle ingestion (continuous):
 *   Every completed 15-min candle is pushed to buf15m and persisted to Redis.
 *   Redis write is fire-and-forget on the existing tradingExecutor thread.
 *   ~0.3 writes/sec — negligible Redis load.
 *
 * RESULT:
 *   Day 1 (fresh deployment): Bootstrap runs → 125 candles loaded → SMC trades
 *   Day 2+: Redis restore → instant → SMC trades from startup
 *   Deploy any time: Redis has latest candles → SMC never misses a day
 *
 * ZERO IMPACT ON OTHER STRATEGIES:
 *   Uses isolated "smc:candles:15m:*" key namespace.
 *   ORB (orb:*), warmup (candles:NIFTY*), instruments — untouched.
 * ─────────────────────────────────────────────────────────────────────────────
 */
@Component
@Slf4j
public class SmcCandleStore {

    // ── Constants ──────────────────────────────────────────────────────────────
    static final int          CAPACITY_15M = 120;          // ~4.5 trading days
    private static final int  STRIDE_1H    = 4;            // every 4th 15min = 1H
    private static final int  STRIDE_4H    = 16;           // every 16th 15min = 4H
    private static final int  READY_THRESHOLD = 5 * STRIDE_4H; // 80 candles
    private static final int  BOOTSTRAP_DAYS  = 7;         // calendar days to fetch
    private static final int  BATCH_SIZE      = 50;        // Zerodha rate limit
    private static final long BATCH_PAUSE_MS  = 250;       // 250ms between batches
    static final ZoneId       IST = ZoneId.of("Asia/Kolkata");

    public static final  String TF_15M = "15minute";
    private static final String KEY_PREFIX = "smc:candles:15m:";
    private static final Duration TTL     = Duration.ofDays(7);

    // ── Deprecated legacy constants (kept for compile compat) ─────────────────
    @Deprecated public static final String TF_4H = "4hour";
    @Deprecated public static final String TF_1H = "1hour";

    // ── In-memory buffer ───────────────────────────────────────────────────────
    private final Map<String, Deque<Candle>> buf15m = new ConcurrentHashMap<>();
    private volatile long totalCandlesReceived = 0;

    // ── Dependencies (@Autowired(required=false) = graceful degradation) ───────
    @Autowired(required = false) private StringRedisTemplate  redis;
    @Autowired(required = false) private KiteConnect          kiteConnect;
    @Autowired(required = false) private InstrumentCacheService instrumentCache;
    @Autowired                   private ObjectMapper          objectMapper;

    // ══════════════════════════════════════════════════════════════════════════
    // STARTUP — Layer 1 + Layer 2
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Startup sequence:
     *   1. Try to restore candle history from Redis (fast path, ~200ms)
     *   2. If Redis was empty (first deployment or flush), bootstrap from
     *      Zerodha historical API (~30s) — only runs once in the system lifetime
     */
    /**
     * Startup sequence — runs at ANY startup time regardless of clock.
     *
     * Phase 1: Redis restore (~200ms) — synchronous, fast.
     * Phase 2: If buf15m still empty (Redis had no data), launch Zerodha
     *          historical bootstrap in a BACKGROUND THREAD so @PostConstruct
     *          returns immediately and does NOT block scheduling-1.
     *
     * This fixes the late-login problem:
     *   If app starts at 8:47 AM, the @Scheduled(8:45) cron is missed forever.
     *   By moving bootstrap into a background thread launched from @PostConstruct,
     *   bootstrap runs at whatever time the app starts — 8:40, 8:47, 9:05, any time.
     *   ORB and all other @Scheduled crons are completely unaffected.
     */
    @PostConstruct
    public void initialise() {
        int restored = loadFromRedis();

        if (restored == 0) {
            // Redis empty — launch bootstrap in background thread.
            // @PostConstruct returns immediately so scheduling-1 is never blocked.
            // Bootstrap completes ~3.5 min later, well before 9:30 AM first scan.
            Thread bootstrapThread = new Thread(() -> {
                log.info("[SMC-STORE] Starting background bootstrap " +
                                "(startup time: {} IST — @Scheduled 8:45 cron may have been missed)",
                        java.time.LocalTime.now(IST));
                bootstrapFromBroker();
            }, "smc-bootstrap");
            bootstrapThread.setDaemon(true);
            bootstrapThread.start();
            log.info("[SMC-STORE] Bootstrap launched in background thread — " +
                    "@PostConstruct returning immediately. " +
                    "ORB and all other strategies unaffected.");
        } else {
            log.info("[SMC-STORE] Redis restore complete: {} symbols, {} ready. " +
                            "SMC ready for 9:30 AM scan.",
                    buf15m.size(), getReadySymbolCount());
        }
    }

    // ── Layer 1: Redis restore ─────────────────────────────────────────────────

    private int loadFromRedis() {
        if (redis == null) {
            log.warn("[SMC-STORE] Redis not available — skipping restore.");
            return 0;
        }
        try {
            Set<String> keys = redis.keys(KEY_PREFIX + "*");
            if (keys == null || keys.isEmpty()) return 0;

            int loaded = 0, totalC = 0, readyNow = 0;
            for (String key : keys) {
                String symbol = key.substring(KEY_PREFIX.length());
                try {
                    String json = redis.opsForValue().get(key);
                    if (json == null || json.isBlank()) continue;

                    List<Candle> candles = objectMapper.readValue(json,
                            new TypeReference<List<Candle>>() {});
                    if (candles.isEmpty()) continue;

                    Deque<Candle> dq = new ArrayDeque<>(CAPACITY_15M + 1);
                    for (Candle c : candles) dq.addLast(c);
                    buf15m.put(symbol, dq);

                    loaded++;
                    totalC += candles.size();
                    if (candles.size() >= READY_THRESHOLD) readyNow++;

                } catch (Exception e) {
                    log.debug("[SMC-STORE] Restore failed for {}: {}", symbol, e.getMessage());
                }
            }
            if (loaded > 0) {
                log.info("[SMC-STORE] ✅ Redis restore: {} symbols ({} candles). " +
                                "{} symbols isReady() — SMC {} trade immediately.",
                        loaded, totalC, readyNow,
                        readyNow > 0 ? "CAN" : "cannot yet");
            }
            return loaded;

        } catch (Exception e) {
            log.warn("[SMC-STORE] Redis restore error: {}", e.getMessage());
            return 0;
        }
    }

    // ── Layer 2: Zerodha historical bootstrap ─────────────────────────────────

    /**
     * Phase 2 — Zerodha historical bootstrap, runs at 8:45 AM every trading day.
     *
     * Checks if Redis already has candle data. If yes → skips (already fast).
     * If Redis is empty (first deployment or weekly reset) → fetches the last
     * BOOTSTRAP_DAYS calendar days of 15-min OHLCV data for all equity instruments
     * from Zerodha's historical API, populates buf15m, and persists to Redis.
     *
     * Why 8:45 AM (not @PostConstruct)?
     *   @PostConstruct runs synchronously and blocks ALL @Scheduled methods
     *   (ORB 9:00, HighRR 9:10, SCPS 9:10...) until it finishes.
     *   A 25-minute bootstrap at @PostConstruct would freeze the entire system.
     *   Running at 8:45 AM gives a 45-minute window before the first SMC scan
     *   at 9:30 AM — more than enough for all 9751 instruments.
     *
     * Timing estimate:
     *   9751 instruments / 50 per batch = 196 batches
     *   Pause: 195 × 250ms = 49 seconds
     *   API calls: liquid (~295) × ~200ms + illiquid (~9456) × ~10ms = ~155 seconds
     *   TOTAL: ~3.5 minutes → done by 8:49 AM, well before 9:30 AM first scan
     *
     * Runs ONLY when buf15m is empty (Redis was empty at startup).
     * Skipped on all subsequent days because Redis has data and PostConstruct
     * restores it, making buf15m non-empty before this cron fires.
     */
    /**
     * Zerodha historical bootstrap — called from background thread in @PostConstruct.
     * No longer @Scheduled — runs at startup time regardless of clock.
     * Guard: if buf15m already populated (Redis restored), returns immediately.
     */
    public void bootstrapFromBroker() {
        // Skip if already populated from Redis restore at startup
        if (!buf15m.isEmpty()) {
            log.debug("[SMC-STORE] Bootstrap skipped — {} symbols already loaded from Redis.",
                    buf15m.size());
            return;
        }
        if (kiteConnect == null || instrumentCache == null) {
            log.warn("[SMC-STORE] KiteConnect or InstrumentCacheService unavailable — " +
                    "bootstrap skipped. SMC will accumulate data over ~4 trading days.");
            return;
        }

        // Date range: last BOOTSTRAP_DAYS calendar days → covers 5 trading days
        LocalDate today = LocalDate.now(IST);
        LocalDate from  = today.minusDays(BOOTSTRAP_DAYS);

        java.util.Date fromDate = java.util.Date.from(
                from.atStartOfDay(IST).toInstant());
        java.util.Date toDate = java.util.Date.from(
                today.atStartOfDay(IST).toInstant());

        log.info("[SMC-STORE] Bootstrap: fetching 15min candles from {} to {} for {} symbols...",
                from, today,
                instrumentCache.getEquityInstruments().size());

        List<Map.Entry<String, com.zerodhatech.models.Instrument>> entries =
                new ArrayList<>(instrumentCache.getEquityInstruments().entrySet());

        int loaded = 0, skipped = 0, errors = 0;

        for (int batchStart = 0; batchStart < entries.size(); batchStart += BATCH_SIZE) {
            List<Map.Entry<String, com.zerodhatech.models.Instrument>> batch =
                    entries.subList(batchStart,
                            Math.min(batchStart + BATCH_SIZE, entries.size()));

            for (Map.Entry<String, com.zerodhatech.models.Instrument> entry : batch) {
                String symbol = entry.getKey();
                long   token  = entry.getValue().getInstrument_token();

                try {
                    HistoricalData result = kiteConnect.getHistoricalData(
                            fromDate, toDate,
                            String.valueOf(token),
                            "15minute",
                            false, false);

                    if (result == null || result.dataArrayList == null
                            || result.dataArrayList.isEmpty()) {
                        skipped++;
                        continue;
                    }

                    // Convert HistoricalData items → Candle objects
                    // Reverse order: Zerodha returns oldest-first, we want newest-first
                    List<HistoricalData> raw = result.dataArrayList;
                    Deque<Candle> dq = new ArrayDeque<>(CAPACITY_15M + 1);

                    for (int i = raw.size() - 1; i >= 0 && dq.size() < CAPACITY_15M; i--) {
                        HistoricalData h = raw.get(i);
                        if (h.close <= 0) continue;
                        Candle c = buildCandle(symbol, h);
                        dq.addLast(c);   // oldest at back, newest at front after reverse
                    }

                    if (dq.isEmpty()) { skipped++; continue; }

                    // Reverse so index 0 = newest (same as live ingestion)
                    Deque<Candle> ordered = new ArrayDeque<>(dq.size());
                    List<Candle> list = new ArrayList<>(dq);
                    for (int i = 0; i < list.size(); i++) ordered.addFirst(list.get(i));

                    buf15m.put(symbol, ordered);
                    persistToRedis(symbol, ordered);
                    loaded++;

                } catch (Throwable e) {
                    // KiteException extends Throwable (not Exception)
                    errors++;
                    if (errors <= 5) {
                        log.debug("[SMC-STORE] Bootstrap error {}: {}", symbol, e.getMessage());
                    }
                }
            }

            // Rate-limit pause between batches
            if (batchStart + BATCH_SIZE < entries.size()) {
                try { Thread.sleep(BATCH_PAUSE_MS); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        int readyNow = getReadySymbolCount();
        log.info("[SMC-STORE] ✅ Bootstrap complete: loaded={} skipped={} errors={} | " +
                        "{} symbols isReady() (≥{} candles) — SMC {} trade today.",
                loaded, skipped, errors, readyNow, READY_THRESHOLD,
                readyNow > 0 ? "CAN" : "cannot yet (need more data)");
    }

    /**
     * Convert a Zerodha HistoricalData candle to our domain Candle object.
     * All price fields are stored as BigDecimal (2dp) to match SmcAnalyser's
     * .getClose().doubleValue() / .getHigh().doubleValue() calls.
     */
    private Candle buildCandle(String symbol, HistoricalData h) {
        return Candle.builder()
                .tradingSymbol(symbol)
                .timeframe(TF_15M)
                .open(  BigDecimal.valueOf(h.open) .setScale(2, java.math.RoundingMode.HALF_UP))
                .high(  BigDecimal.valueOf(h.high) .setScale(2, java.math.RoundingMode.HALF_UP))
                .low(   BigDecimal.valueOf(h.low)  .setScale(2, java.math.RoundingMode.HALF_UP))
                .close( BigDecimal.valueOf(h.close).setScale(2, java.math.RoundingMode.HALF_UP))
                .volume((long) h.volume)
                .complete(true)
                .build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // LIVE CANDLE INGESTION — Layer 3
    // ══════════════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tradingExecutor")
    public void onCandle(CandleCompleteEvent event) {
        Candle c = event.getCandle();
        if (!c.isComplete()) return;
        if (!TF_15M.equals(c.getTimeframe())) return;

        String sym = c.getTradingSymbol();
        if (sym == null || sym.isBlank()) return;

        Deque<Candle> dq = buf15m.computeIfAbsent(sym,
                k -> new ArrayDeque<>(CAPACITY_15M + 1));
        synchronized (dq) {
            dq.addFirst(c);
            while (dq.size() > CAPACITY_15M) dq.removeLast();
        }
        totalCandlesReceived++;
        persistToRedis(sym, dq);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // REDIS WRITE HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private void persistToRedis(String symbol, Deque<Candle> dq) {
        if (redis == null) return;
        try {
            List<Candle> snap;
            synchronized (dq) { snap = new ArrayList<>(dq); }
            String json = objectMapper.writeValueAsString(snap);
            redis.opsForValue().set(KEY_PREFIX + symbol, json, TTL);
        } catch (JsonProcessingException e) {
            log.debug("[SMC-STORE] Serialize error {}: {}", symbol, e.getMessage());
        } catch (Exception e) {
            log.debug("[SMC-STORE] Redis write error {}: {}", symbol, e.getMessage());
        }
    }

    private void clearRedisKeys() {
        if (redis == null) return;
        try {
            Set<String> keys = redis.keys(KEY_PREFIX + "*");
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
                log.info("[SMC-STORE] Cleared {} Redis candle keys.", keys.size());
            }
        } catch (Exception e) {
            log.debug("[SMC-STORE] Redis clear error: {}", e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PUBLIC READ API — all signatures unchanged
    // ══════════════════════════════════════════════════════════════════════════

    /** Synthetic 4H via stride-16. Index 0 = most recent. */
    public List<Candle> get4H(String symbol) { return strided(symbol, STRIDE_4H); }

    /** Synthetic 1H via stride-4. Index 0 = most recent. */
    public List<Candle> get1H(String symbol) { return strided(symbol, STRIDE_1H); }

    /** Raw 15min. Index 0 = most recent. */
    public List<Candle> get15M(String symbol) { return snapshot(symbol); }

    /**
     * True when buf15m has ≥ 80 candles for this symbol.
     * After bootstrap: true for all 295 symbols from startup.
     * Without bootstrap: true after ~4 trading days of live accumulation.
     */
    public boolean isReady(String symbol) {
        return snapshot(symbol).size() >= READY_THRESHOLD;
    }

    public int  getTrackedSymbols()        { return buf15m.size(); }
    public long getTotalCandlesReceived()  { return totalCandlesReceived; }

    public int getReadySymbolCount() {
        int n = 0;
        for (Deque<Candle> dq : buf15m.values())
            if (dq.size() >= READY_THRESHOLD) n++;
        return n;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // INTERNAL HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private List<Candle> strided(String sym, int stride) {
        List<Candle> all = snapshot(sym);
        if (all.size() < stride) return Collections.emptyList();
        List<Candle> r = new ArrayList<>(all.size() / stride);
        for (int i = 0; i < all.size(); i += stride) r.add(all.get(i));
        return r;
    }

    private List<Candle> snapshot(String sym) {
        Deque<Candle> dq = buf15m.get(sym);
        if (dq == null) return Collections.emptyList();
        synchronized (dq) { return new ArrayList<>(dq); }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SCHEDULED RESETS
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Daily 9:10 AM — does NOT clear buf15m.
     * Cross-day accumulation is required. Old candles roll off naturally at CAPACITY_15M.
     * Redis keys also retained — survive any intraday deployment.
     */
    @Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyReset() {
        log.info("[SMC-STORE] Session start — {} symbols, {} ready, {} candles received.",
                buf15m.size(), getReadySymbolCount(), totalCandlesReceived);
    }

    /**
     * Monday 9:00 AM — clears in-memory AND Redis for a fresh weekly start.
     * Next startup re-bootstraps from Zerodha (fast path if Redis is populated
     * from prior week, bootstrap path if cleared).
     */
    @Scheduled(cron = "0 0 9 * * MON", zone = "Asia/Kolkata")
    public void weeklyReset() {
        buf15m.clear();
        totalCandlesReceived = 0;
        clearRedisKeys();
        log.info("[SMC-STORE] Weekly reset — buffers and Redis cleared. " +
                "Bootstrap will run on next startup.");
    }
}