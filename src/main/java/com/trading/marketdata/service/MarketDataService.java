package com.trading.marketdata.service;

import com.trading.events.TickReceivedEvent;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Tick;
import com.zerodhatech.ticker.KiteTicker;
import com.zerodhatech.ticker.OnConnect;
import com.zerodhatech.ticker.OnDisconnect;
import com.zerodhatech.ticker.OnError;
import com.zerodhatech.ticker.OnTicks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MarketDataService — Zerodha KiteTicker WebSocket connection manager.
 *
 * COMPILE ERRORS FIXED:
 *
 *   ERROR 1: setMaximumRetries(int) throws KiteException
 *            setMaximumRetryInterval(int) throws KiteException
 *            → Both calls now wrapped in try-catch(KiteException)
 *
 *   ERROR 2: subscribe(ArrayList<Long>) — SDK requires ArrayList, not List
 *            setMode(ArrayList<Long>, String) — same
 *            → All calls now use new ArrayList<>() wrappers.
 *              The `batch` variable is explicitly declared as ArrayList<Long>.
 *
 *   ERROR 3: tick.getOpenInterest() does not exist
 *            → Correct method is tick.getOi() (confirmed from jar bytecode)
 *
 * ADDITIONAL FIXES:
 *   - OnError interface requires 3 overloads: onError(Exception), onError(KiteException), onError(String)
 *   - getLastPricesSimple() added for DashboardController
 *   - Exponential backoff on reconnect: 1s → 2s → 4s → 8s → 16s → 30s max
 *   - TickReceivedEvent now carries all fields (totalBuyQty, totalSellQty, etc.)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MarketDataService {

    private final ApplicationEventPublisher publisher;
    private final KiteConnect               kiteConnect;
    private final InstrumentCacheService    instrumentCache;

    private KiteTicker        ticker;
    private ArrayList<Long>   subscribedFullTokens  = new ArrayList<>();
    private ArrayList<Long>   subscribedQuoteTokens = new ArrayList<>();
    private String            accessToken;

    /** Last tick price per symbol — for DashboardController /prices endpoint */
    private final Map<String, BigDecimal> lastPrices = new ConcurrentHashMap<>();

    private final AtomicBoolean  connected      = new AtomicBoolean(false);
    private final AtomicInteger  reconnectCount = new AtomicInteger(0);

    // SDK limits
    private static final int MAX_BACKOFF_SEC = 30;
    private static final int BATCH_SIZE      = 200;   // Zerodha max per subscribe call
    private static final int BATCH_DELAY_MS  = 100;   // prevent 429 rate limit

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Start WebSocket streaming.
     * Called by MarketDataStartupService after WarmupService completes.
     */
    public void startStreaming(String accessToken, List<Long> fullTokens,
                               List<Long> quoteTokens) {
        this.accessToken           = accessToken;
        this.subscribedFullTokens  = new ArrayList<>(fullTokens);
        this.subscribedQuoteTokens = new ArrayList<>(quoteTokens);

        log.info("[WS] Starting: {} FULL tokens, {} QUOTE tokens",
                fullTokens.size(), quoteTokens.size());

        kiteConnect.setAccessToken(accessToken);
        initTicker();
    }

    /** Reconnect with a new access token (called by DailyLoginScheduler). */
    public void reconnect(String newAccessToken) {
        log.info("[WS] Reconnecting with new token...");
        if (ticker != null) {
            try { ticker.disconnect(); } catch (Exception ignored) {}
        }
        accessToken = newAccessToken;
        kiteConnect.setAccessToken(newAccessToken);
        initTicker();
    }

    public boolean isConnected()       { return connected.get(); }
    public int     getReconnectCount() { return reconnectCount.get(); }

    /**
     * Returns last traded price for all tracked symbols.
     * Used by DashboardController (/api/dashboard/snapshot and /api/dashboard/prices).
     */
    public Map<String, BigDecimal> getLastPricesSimple() {
        return Collections.unmodifiableMap(lastPrices);
    }

    // ── KiteTicker initialization ─────────────────────────────────────────

    private void initTicker() {
        try {
            ticker = new KiteTicker(accessToken, kiteConnect.getApiKey());

            ticker.setOnConnectedListener(new OnConnect() {
                @Override
                public void onConnected() {
                    connected.set(true);
                    reconnectCount.set(0);
                    log.info("[WS] Connected. Subscribing {} FULL + {} QUOTE tokens in batches...",
                            subscribedFullTokens.size(), subscribedQuoteTokens.size());
                    subscribeInBatches();
                }
            });

            ticker.setOnDisconnectedListener(new OnDisconnect() {
                @Override
                public void onDisconnected() {
                    connected.set(false);
                    log.warn("[WS] Disconnected. Scheduling reconnect...");
                    scheduleReconnect();
                }
            });

            // COMPILE FIX 1: OnError interface requires all 3 overloads
            ticker.setOnErrorListener(new OnError() {
                @Override
                public void onError(Exception e) {
                    log.error("[WS] Exception: {}", e.getMessage());
                }

                @Override
                public void onError(KiteException e) {
                    // COMPILE FIX 1: KiteException overload was missing
                    log.error("[WS] KiteException: code={} message={}", e.code, e.message);
                    if (e.code == 429) {
                        log.warn("[WS] Rate limited (429). Reconnect will back off.");
                    }
                }

                @Override
                public void onError(String error) {
                    log.error("[WS] Error string: {}", error);
                }
            });

            ticker.setOnTickerArrivalListener(new OnTicks() {
                @Override
                public void onTicks(ArrayList<Tick> ticks) {
                    for (Tick tick : ticks) {
                        publishTick(tick);
                    }
                }
            });

            // COMPILE FIX 1: setMaximumRetries and setMaximumRetryInterval throw KiteException
            try {
                ticker.setMaximumRetries(20);
                ticker.setMaximumRetryInterval(30);
            } catch (KiteException e) {
                log.warn("[WS] Could not set retry params: {}", e.message);
            }

            ticker.connect();

        } catch (Exception e) {
            log.error("[WS] Failed to initialize KiteTicker: {}", e.getMessage(), e);
            scheduleReconnect();
        }
    }

    // ── Tick publishing ───────────────────────────────────────────────────

    /**
     * Build and publish TickReceivedEvent from Zerodha Tick.
     * COMPILE FIX 3: tick.getOi() — not tick.getOpenInterest() (doesn't exist in SDK).
     */
    private void publishTick(Tick tick) {
        try {
            String symbol = instrumentCache.getSymbol(tick.getInstrumentToken());
            double ltpDouble = tick.getLastTradedPrice();
            if (ltpDouble <= 0) return;

            BigDecimal ltp = BigDecimal.valueOf(ltpDouble);
            lastPrices.put(symbol, ltp);

            Instant tickTime = tick.getLastTradedTime() != null
                    ? tick.getLastTradedTime().toInstant()
                    : Instant.now();

            TickReceivedEvent event = new TickReceivedEvent(
                    this,
                    tick.getInstrumentToken(),
                    symbol,
                    ltp,
                    (long) tick.getLastTradedQuantity(),
                    (long) tick.getVolumeTradedToday(),
                    (long) tick.getTotalBuyQuantity(),
                    (long) tick.getTotalSellQuantity(),
                    (long) tick.getOi(),              // COMPILE FIX 3: getOi() not getOpenInterest()
                    tickTime
            );

            publisher.publishEvent(event);

        } catch (Exception e) {
            log.debug("[WS] Tick publish error token {}: {}", tick.getInstrumentToken(), e.getMessage());
        }
    }

    // ── Batched subscription ──────────────────────────────────────────────

    /**
     * COMPILE FIX 2:
     *   ticker.subscribe(ArrayList<Long>) — SDK requires ArrayList, not List.
     *   ticker.setMode(ArrayList<Long>, String) — same.
     *   → batch declared as ArrayList<Long> explicitly.
     *   → quote tokens wrapped with new ArrayList<>().
     */
    private void subscribeInBatches() {
        int total   = subscribedFullTokens.size();
        int batches = (total + BATCH_SIZE - 1) / BATCH_SIZE;

        for (int b = 0; b < batches; b++) {
            int from = b * BATCH_SIZE;
            int to   = Math.min(from + BATCH_SIZE, total);

            // COMPILE FIX 2: must be ArrayList<Long>, not List<Long>
            ArrayList<Long> batch = new ArrayList<>(subscribedFullTokens.subList(from, to));

            try {
                ticker.subscribe(batch);                          // ArrayList<Long> ✓
                ticker.setMode(batch, KiteTicker.modeFull);       // ArrayList<Long> ✓
                log.info("[WS] Subscribed batch {}/{}: {} tokens (FULL)",
                        b + 1, batches, batch.size());

                if (b < batches - 1) {
                    Thread.sleep(BATCH_DELAY_MS); // prevent 429
                }
            } catch (Exception e) {
                log.error("[WS] Batch {} subscription failed: {}", b + 1, e.getMessage());
            }
        }

        if (!subscribedQuoteTokens.isEmpty()) {
            try {
                // COMPILE FIX 2: new ArrayList<>() wrapper required
                ArrayList<Long> quoteList = new ArrayList<>(subscribedQuoteTokens);
                ticker.subscribe(quoteList);
                ticker.setMode(quoteList, KiteTicker.modeQuote);
                log.info("[WS] Subscribed {} QUOTE tokens", quoteList.size());
            } catch (Exception e) {
                log.error("[WS] QUOTE subscription failed: {}", e.getMessage());
            }
        }

        log.info("[WS] All subscriptions complete: {} FULL + {} QUOTE",
                subscribedFullTokens.size(), subscribedQuoteTokens.size());
    }

    // ── Reconnect with exponential backoff ────────────────────────────────

    private void scheduleReconnect() {
        int attempt  = reconnectCount.incrementAndGet();
        int delaySec = (int) Math.min(Math.pow(2, attempt - 1), MAX_BACKOFF_SEC);

        log.info("[WS] Reconnect attempt {} in {}s...", attempt, delaySec);

        Thread t = new Thread(() -> {
            try {
                Thread.sleep((long) delaySec * 1000);
                if (!connected.get() && accessToken != null) {
                    log.info("[WS] Attempting reconnect #{}", attempt);
                    initTicker();
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }, "ws-reconnect-" + attempt);
        t.setDaemon(true);
        t.start();
    }
}