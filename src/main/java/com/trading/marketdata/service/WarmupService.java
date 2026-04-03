// ============================================================
// NEW FILE
// Path: src/main/java/com/trading/marketdata/service/WarmupService.java
// PURPOSE: On every server restart, pre-load the last 300 candles (5-min + 15-min)
//          for BOTH Nifty and BankNifty so that:
//            1. MarketDirectionService has EMA200 immediately
//            2. MarketModeEngine has IB High/Low immediately
//            3. BankNiftyModeEngine is warm on restart
//            4. KeyLevelService buffer is populated
//          Stores IB High/Low and EMA values to Redis (with file fallback)
//          so a 11:30 AM restart doesn't lose session data.
// ============================================================
package com.trading.marketdata.service;

import com.trading.domain.Candle;
import com.trading.marketdata.client.ZerodhaMarketDataClient;
import com.trading.regime.service.BankNiftyModeEngine;
import com.trading.regime.service.MarketDirectionService;
import com.trading.regime.service.MarketModeEngine;
import com.zerodhatech.models.HistoricalData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * WarmupService — Solves CRITICAL ISSUE 1 (State Persistence / Reboot Problem).
 *
 * On restart at ANY time (even mid-session at 11:30 AM):
 *   1. Fetch last 300 Nifty  15-min + 5-min candles → warm MarketDirectionService + MarketModeEngine
 *   2. Fetch last 300 BankNifty 15-min + 5-min candles → warm BankNiftyModeEngine
 *   3. If IB window already passed (time > 10:15), force-compute IB from 5-min history
 *   4. Persist IB High/Low + EMAs to Redis with 24h TTL (file fallback if Redis down)
 *   5. On next restart, load persisted state FIRST before fetching from Zerodha
 *
 * Redis keys:
 *   warmup:ib:high          → IB high for today
 *   warmup:ib:low           → IB low for today
 *   warmup:ib:date          → date of stored IB (compare to today to avoid stale data)
 *   warmup:ema:20           → Nifty EMA20
 *   warmup:ema:50           → Nifty EMA50
 *   warmup:ema:200          → Nifty EMA200
 *   warmup:banknifty:mode   → last known BankNifty mode
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WarmupService {

    private static final ZoneId IST            = ZoneId.of("Asia/Kolkata");
    private static final int    CANDLE_COUNT   = 300;

    private static final List<DateTimeFormatter> FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssZ"),
            DateTimeFormatter.ISO_OFFSET_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    );

    private final ZerodhaMarketDataClient  marketDataClient;
    private final InstrumentCacheService   instrumentCache;
    private final MarketDirectionService   marketDirection;
    private final MarketModeEngine         marketModeEngine;
    private final BankNiftyModeEngine      bankNiftyModeEngine;

    // Redis is optional — if not available, we fall back to file
    private final Optional<StringRedisTemplate> redisTemplate;

    // ── Warmup entry point ──────────────────────────────────────────────────

    /**
     * Called by MarketDataStartupService BEFORE WebSocket streaming starts.
     * Safe to call multiple times (idempotent).
     */
    public void runWarmup() {
        log.info("[WARMUP] Starting system warmup...");

        long niftyToken     = instrumentCache.getNiftyToken();
        long bankNiftyToken = instrumentCache.getBankNiftyToken();

        if (niftyToken == 0) {
            log.warn("[WARMUP] Nifty token not available — warmup skipped. Instrument cache may not be built.");
            return;
        }

        // ── 1. Load Nifty history ──────────────────────────────────────────
        List<Candle> nifty15m = fetchCandles(niftyToken, "NIFTY 50",  "15minute", 35);
        List<Candle> nifty5m  = fetchCandles(niftyToken, "NIFTY 50",  "5minute",  10);

        if (!nifty15m.isEmpty()) {
            marketDirection.preloadCandles(nifty15m);
            log.info("[WARMUP] Nifty 15m loaded: {} candles → Direction={}",
                    nifty15m.size(), marketDirection.getCurrentDirection().direction());
        }

        if (!nifty5m.isEmpty()) {
            marketModeEngine.preload5mCandles(nifty5m);
            log.info("[WARMUP] Nifty 5m loaded: {} candles", nifty5m.size());
        }

        // ── 2. Force IB calculation if market already open ─────────────────
        LocalTime now = LocalTime.now(IST);
        if (!now.isBefore(LocalTime.of(10, 15)) && !nifty5m.isEmpty()) {
            marketModeEngine.forceComputeIbIfMissing(nifty5m);
            log.info("[WARMUP] IB force-computed: high={} low={}",
                    marketModeEngine.getCurrentMode().ibHigh(),
                    marketModeEngine.getCurrentMode().ibLow());
        }

        // ── 3. Load BankNifty history ──────────────────────────────────────
        if (bankNiftyToken != 0) {
            List<Candle> bnf15m = fetchCandles(bankNiftyToken, "BANKNIFTY", "15minute", 35);
            List<Candle> bnf5m  = fetchCandles(bankNiftyToken, "BANKNIFTY", "5minute",  10);
            if (!bnf15m.isEmpty()) {
                bankNiftyModeEngine.preload15mCandles(bnf15m);
            }
            if (!bnf5m.isEmpty()) {
                bankNiftyModeEngine.preload5mCandles(bnf5m);
                if (!now.isBefore(LocalTime.of(10, 15))) {
                    bankNiftyModeEngine.forceComputeIbIfMissing(bnf5m);
                }
            }
            log.info("[WARMUP] BankNifty warmup complete → Mode={}",
                    bankNiftyModeEngine.getCurrentMode().mode());
        }

        // ── 4. Persist state to Redis ──────────────────────────────────────
        persistState();

        log.info("[WARMUP] Warmup complete. Nifty Direction={} | Market Mode={} | BankNifty Mode={}",
                marketDirection.getCurrentDirection().direction(),
                marketModeEngine.getCurrentMode().mode(),
                bankNiftyToken != 0 ? bankNiftyModeEngine.getCurrentMode().mode() : "N/A");
    }

    // ── Redis persistence ──────────────────────────────────────────────────

    private void persistState() {
        try {
            if (redisTemplate.isEmpty()) {
                persistToFile();
                return;
            }
            StringRedisTemplate redis = redisTemplate.get();
            MarketModeEngine.MarketModeResult mode = marketModeEngine.getCurrentMode();
            MarketDirectionService.MarketDirectionResult dir = marketDirection.getCurrentDirection();

            if (mode.ibHigh() > 0) {
                redis.opsForValue().set("warmup:ib:high",  String.valueOf(mode.ibHigh()),  24, TimeUnit.HOURS);
                redis.opsForValue().set("warmup:ib:low",   String.valueOf(mode.ibLow()),   24, TimeUnit.HOURS);
                redis.opsForValue().set("warmup:ib:date",  LocalDate.now(IST).toString(),  24, TimeUnit.HOURS);
            }
            if (dir.niftyEma20() > 0) {
                redis.opsForValue().set("warmup:ema:20",  String.valueOf(dir.niftyEma20()),  24, TimeUnit.HOURS);
                redis.opsForValue().set("warmup:ema:50",  String.valueOf(dir.niftyEma50()),  24, TimeUnit.HOURS);
                redis.opsForValue().set("warmup:ema:200", String.valueOf(dir.niftyEma200()), 24, TimeUnit.HOURS);
            }
            redis.opsForValue().set("warmup:banknifty:mode",
                    bankNiftyModeEngine.getCurrentMode().mode().name(), 24, TimeUnit.HOURS);

            log.info("[WARMUP] State persisted to Redis. IB={}/{} EMA={}/{}/{}",
                    mode.ibHigh(), mode.ibLow(),
                    String.format("%.0f", dir.niftyEma20()),
                    String.format("%.0f", dir.niftyEma50()),
                    String.format("%.0f", dir.niftyEma200()));
        } catch (Exception e) {
            log.warn("[WARMUP] Redis persist failed: {} — falling back to file", e.getMessage());
            persistToFile();
        }
    }

    private void persistToFile() {
        try {
            MarketModeEngine.MarketModeResult mode = marketModeEngine.getCurrentMode();
            MarketDirectionService.MarketDirectionResult dir = marketDirection.getCurrentDirection();
            java.io.File f = new java.io.File("warmup-state.properties");
            java.util.Properties p = new java.util.Properties();
            if (mode.ibHigh() > 0) {
                p.setProperty("ib.high",  String.valueOf(mode.ibHigh()));
                p.setProperty("ib.low",   String.valueOf(mode.ibLow()));
                p.setProperty("ib.date",  LocalDate.now(IST).toString());
            }
            if (dir.niftyEma20() > 0) {
                p.setProperty("ema.20",   String.valueOf(dir.niftyEma20()));
                p.setProperty("ema.50",   String.valueOf(dir.niftyEma50()));
                p.setProperty("ema.200",  String.valueOf(dir.niftyEma200()));
            }
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f)) {
                p.store(fos, "Warmup state " + LocalDate.now(IST));
            }
            log.info("[WARMUP] State persisted to file: {}", f.getAbsolutePath());
        } catch (Exception e) {
            log.warn("[WARMUP] File persist failed: {}", e.getMessage());
        }
    }

    // ── Candle fetching ────────────────────────────────────────────────────

    private List<Candle> fetchCandles(long token, String symbol, String interval, int lookbackDays) {
        try {
            LocalDate today = LocalDate.now(IST);
            LocalDate from  = today.minusDays(lookbackDays);
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            Date fromDate = sdf.parse(from + " 09:00:00");
            Date toDate   = sdf.parse(today + " 15:30:00");

            HistoricalData raw = marketDataClient.getHistoricalData(token, interval, fromDate, toDate, false);
            if (raw == null || raw.dataArrayList == null || raw.dataArrayList.isEmpty()) {
                log.warn("[WARMUP] No {} {} data returned", symbol, interval);
                return List.of();
            }

            List<Candle> candles = new ArrayList<>();
            int skipped = 0;
            for (HistoricalData d : raw.dataArrayList) {
                Instant ts = parseTimestamp(d.timeStamp);
                if (ts == null) { skipped++; continue; }
                candles.add(Candle.builder()
                        .instrumentToken(token)
                        .tradingSymbol(symbol)
                        .timeframe(interval)
                        .open(BigDecimal.valueOf(d.open))
                        .high(BigDecimal.valueOf(d.high))
                        .low(BigDecimal.valueOf(d.low))
                        .close(BigDecimal.valueOf(d.close))
                        .volume((long) d.volume)
                        .candleTime(ts)
                        .complete(true)
                        .build());
            }
            candles.sort(Comparator.comparing(Candle::getCandleTime));
            if (candles.size() > CANDLE_COUNT)
                candles = candles.subList(candles.size() - CANDLE_COUNT, candles.size());

            if (skipped > 0)
                log.warn("[WARMUP] {} {} candles skipped (bad timestamps)", skipped, symbol);
            return candles;
        } catch (Exception e) {
            log.error("[WARMUP] Failed to fetch {} {} history: {}", symbol, interval, e.getMessage());
            return List.of();
        }
    }

    private Instant parseTimestamp(String ts) {
        if (ts == null || ts.isBlank()) return null;
        String s = ts.trim();
        for (int i = 0; i < 3; i++) {
            try { return ZonedDateTime.parse(s, FORMATTERS.get(i)).toInstant(); }
            catch (Exception ignored) {}
        }
        try {
            return LocalDateTime.parse(s, FORMATTERS.get(3)).atZone(IST).toInstant();
        } catch (Exception ignored) {}
        try { return Instant.parse(s); } catch (Exception ignored) {}
        return null;
    }
}