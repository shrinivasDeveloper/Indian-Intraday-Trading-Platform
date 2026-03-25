package com.trading.marketdata.service;

import com.trading.domain.Candle;
import com.trading.marketdata.client.ZerodhaMarketDataClient;
import com.trading.regime.service.MarketDirectionService;
import com.zerodhatech.models.HistoricalData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

/**
 * NiftyHistoricalLoaderService
 *
 * Called once on startup (from MarketDataStartupService.onTokenReady) BEFORE
 * WebSocket streaming starts. Fetches the last 300 Nifty 15-min candles from
 * the Zerodha historical API and pre-loads them into MarketDirectionService so
 * EMA200 is available immediately instead of waiting ~8 trading days.
 *
 * BUGS FIXED vs previous version:
 *   1. Timestamp format '2026-03-24T14:30:00+0530' was not parsed.
 *      Zerodha uses T-separator with NO colon in the UTC offset (+0530, not +05:30).
 *      Added Pattern A: "yyyy-MM-dd'T'HH:mm:ssZ" which matches this exactly.
 *
 *   2. Old fallback on parse failure returned Instant.now(), which corrupted
 *      buffer sort order (candle appeared at current time instead of market time).
 *      New behaviour: return null and SKIP the candle entirely.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class NiftyHistoricalLoaderService {

    private static final ZoneId IST           = ZoneId.of("Asia/Kolkata");
    private static final int    CANDLES_NEEDED = 300;

    // Zerodha timestamp formats — ordered by how common they are:
    //   A) "2026-03-24T14:30:00+0530"  (T-sep, no colon in offset)  ← most common
    //   B) "2026-03-24 14:30:00+0530"  (space-sep, no colon offset)
    //   C) "2026-03-24T14:30:00+05:30" (ISO-8601 standard)
    //   D) "2026-03-24 14:30:00"       (no offset → assume IST)
    private static final List<DateTimeFormatter> FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ"),  // A
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssZ"),    // B
            DateTimeFormatter.ISO_OFFSET_DATE_TIME,                  // C
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")      // D (local, no offset)
    );

    private final ZerodhaMarketDataClient marketDataClient;
    private final InstrumentCacheService  instrumentCache;
    private final MarketDirectionService  marketDirection;

    /**
     * Fetches historical Nifty 15-min candles and pre-loads the
     * MarketDirectionService buffer. Call this before WebSocket streaming.
     */
    public void loadNiftyHistory() {
        try {
            long niftyToken = instrumentCache.getNiftyToken();
            if (niftyToken == 0) {
                log.warn("[LOADER] Nifty token is 0 — instrument cache may not be built yet. Skipping pre-load.");
                return;
            }

            // Go back 35 calendar days to guarantee 300 fifteen-min candles
            // (accounts for weekends, holidays, half-trading days).
            LocalDate today = LocalDate.now(IST);
            LocalDate from  = today.minusDays(35);

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            Date fromDate = sdf.parse(from.toString()  + " 09:00:00");
            Date toDate   = sdf.parse(today.toString() + " 15:30:00");

            log.info("[LOADER] Fetching Nifty 15-min history: {} → {}", from, today);

            HistoricalData raw = marketDataClient.getHistoricalData(
                    niftyToken, "15minute", fromDate, toDate, false);

            if (raw == null || raw.dataArrayList == null || raw.dataArrayList.isEmpty()) {
                log.warn("[LOADER] Zerodha returned no historical data. " +
                        "Verify token is valid and market has been open recently. " +
                        "Market direction will remain SIDEWAYS until 200 live 15-min candles accumulate.");
                return;
            }

            List<Candle> candles = new ArrayList<>();
            int skipped = 0;

            for (HistoricalData d : raw.dataArrayList) {
                Instant ts = parseTimestamp(d.timeStamp);
                if (ts == null) {
                    skipped++;
                    continue; // skip — do NOT use Instant.now() as that corrupts sort order
                }
                candles.add(Candle.builder()
                        .instrumentToken(niftyToken)
                        .tradingSymbol("NIFTY 50")
                        .timeframe("15minute")
                        .open(BigDecimal.valueOf(d.open))
                        .high(BigDecimal.valueOf(d.high))
                        .low(BigDecimal.valueOf(d.low))
                        .close(BigDecimal.valueOf(d.close))
                        .volume((long) d.volume)
                        .candleTime(ts)
                        .complete(true)
                        .build());
            }

            if (skipped > 0) {
                log.warn("[LOADER] {} candle(s) skipped — unrecognised timestamp format. " +
                        "Expected: '2026-03-24T14:30:00+0530' or '2026-03-24 14:30:00+0530'", skipped);
            }

            if (candles.isEmpty()) {
                log.warn("[LOADER] All {} candles had unparseable timestamps. " +
                        "Market direction will remain SIDEWAYS.", raw.dataArrayList.size());
                return;
            }

            // Sort OLDEST → NEWEST before handing to preloadCandles()
            candles.sort(Comparator.comparing(Candle::getCandleTime));

            // Keep only the most recent CANDLES_NEEDED
            if (candles.size() > CANDLES_NEEDED) {
                candles = candles.subList(candles.size() - CANDLES_NEEDED, candles.size());
            }

            log.info("[LOADER] Loaded {} Nifty 15-min candles (skipped {} with bad timestamps) — pre-loading buffer",
                    candles.size(), skipped);

            marketDirection.preloadCandles(candles);

            MarketDirectionService.MarketDirectionResult result = marketDirection.getCurrentDirection();
            log.info("[LOADER] Warm-up complete. Direction={} | EMA20={} EMA50={} EMA200={} ATR={}% | Reason={}",
                    result.direction(),
                    String.format("%.0f", result.niftyEma20()),
                    String.format("%.0f", result.niftyEma50()),
                    String.format("%.0f", result.niftyEma200()),
                    String.format("%.2f", result.niftyAtrPct()),
                    result.failReason() != null ? result.failReason() : "OK");

        } catch (Exception e) {
            log.error("[LOADER] Failed to pre-load Nifty history: {}. " +
                    "Market direction will remain SIDEWAYS until live candles accumulate.", e.getMessage(), e);
        }
    }

    // ── Timestamp parsing ──────────────────────────────────────────────────────
    // Returns null on failure — callers MUST skip null candles.
    // NEVER return Instant.now() as a fallback — it corrupts the sort order.

    private Instant parseTimestamp(String ts) {
        if (ts == null || ts.isBlank()) return null;
        String s = ts.trim();

        // Try offset-aware patterns A, B, C
        for (int i = 0; i < 3; i++) {
            try {
                return ZonedDateTime.parse(s, FORMATTERS.get(i)).toInstant();
            } catch (Exception ignored) {}
        }

        // Pattern D: plain local datetime — assume IST
        try {
            LocalDateTime ldt = LocalDateTime.parse(s, FORMATTERS.get(3));
            return ldt.atZone(IST).toInstant();
        } catch (Exception ignored) {}

        // Last resort: Java built-in ISO instant parser
        try { return Instant.parse(s); } catch (Exception ignored) {}

        log.warn("[LOADER] Cannot parse timestamp '{}' — skipping candle", ts);
        return null;
    }
}