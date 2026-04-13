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
 * JAR-VERIFIED FIXES (from javap on kiteconnect.jar):
 *
 *   FIX 1 — Tick field types (all verified via javap):
 *     getLastTradedQuantity()  → double  (cast to long safely)
 *     getTotalBuyQuantity()    → double  (cast to long safely)
 *     getTotalSellQuantity()   → double  (cast to long safely)
 *     getVolumeTradedToday()   → long    (no cast needed)
 *     getOi()                  → double  (not getOpenInterest — doesn't exist)
 *     getLastTradedTime()      → java.util.Date (not Instant)
 *     getTickTimestamp()       → java.util.Date (not Instant)
 *
 *   FIX 2 — KiteTicker method signatures (verified):
 *     subscribe(ArrayList<Long>)              must be ArrayList, not List
 *     setMode(ArrayList<Long>, String)        must be ArrayList, not List
 *     setMaximumRetries(int)   throws KiteException
 *     setMaximumRetryInterval(int) throws KiteException
 *
 *   FIX 3 — OnError interface (verified):
 *     Must implement all 3 overloads:
 *       onError(Exception)
 *       onError(KiteException)
 *       onError(String)
 *
 *   FIX 4 — Date → Instant conversion:
 *     tick.getLastTradedTime() returns java.util.Date
 *     Must call .toInstant() to get Instant for TickReceivedEvent
 *
 *   FIX 5 — ENABLE_LOGGING:
 *     KiteConnect has: public static boolean ENABLE_LOGGING;
 *     Can be set as KiteConnect.ENABLE_LOGGING = false (no method call needed)
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

    private static final int MAX_BACKOFF_SEC = 30;
    private static final int BATCH_SIZE      = 200;
    private static final int BATCH_DELAY_MS  = 100;

    // ── Public API ────────────────────────────────────────────────────────

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

            // JAR-VERIFIED: OnError requires exactly these 3 overloads
            ticker.setOnErrorListener(new OnError() {
                @Override
                public void onError(Exception e) {
                    log.error("[WS] Exception: {}", e.getMessage());
                }

                @Override
                public void onError(KiteException e) {
                    // KiteException.message and .code are public fields (verified)
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
                    // Real-time tick processing — minimum overhead here
                    for (Tick tick : ticks) {
                        publishTick(tick);
                    }
                }
            });

            // JAR-VERIFIED: both throw KiteException — must be wrapped
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
     *
     * JAR-VERIFIED field types:
     *   getLastTradedQuantity() → double  → cast to long (always whole number from exchange)
     *   getVolumeTradedToday()  → long    → use directly
     *   getTotalBuyQuantity()   → double  → cast to long
     *   getTotalSellQuantity()  → double  → cast to long
     *   getOi()                 → double  → cast to long
     *   getLastTradedTime()     → java.util.Date → .toInstant() for Instant
     *   getTickTimestamp()      → java.util.Date → .toInstant() for Instant
     */
    private void publishTick(Tick tick) {
        try {
            String symbol  = instrumentCache.getSymbol(tick.getInstrumentToken());
            double ltpDbl  = tick.getLastTradedPrice();
            if (ltpDbl <= 0) return;

            BigDecimal ltp = BigDecimal.valueOf(ltpDbl);
            lastPrices.put(symbol, ltp);

            // JAR-VERIFIED: getLastTradedTime() returns java.util.Date, not Instant
            // Must call .toInstant() to convert
            Instant tickTime;
            if (tick.getLastTradedTime() != null) {
                tickTime = tick.getLastTradedTime().toInstant();
            } else if (tick.getTickTimestamp() != null) {
                tickTime = tick.getTickTimestamp().toInstant();
            } else {
                tickTime = Instant.now();
            }

            TickReceivedEvent event = new TickReceivedEvent(
                    this,
                    tick.getInstrumentToken(),                      // long   ✓
                    symbol,                                          // String ✓
                    ltp,                                             // BigDecimal ✓
                    (long) tick.getLastTradedQuantity(),             // double → long cast ✓
                    tick.getVolumeTradedToday(),                     // long directly ✓
                    (long) tick.getTotalBuyQuantity(),               // double → long cast ✓
                    (long) tick.getTotalSellQuantity(),              // double → long cast ✓
                    (long) tick.getOi(),                             // double → long cast ✓ (NOT getOpenInterest)
                    tickTime                                         // Instant from Date.toInstant() ✓
            );

            publisher.publishEvent(event);

        } catch (Exception e) {
            log.debug("[WS] Tick publish error token {}: {}",
                    tick.getInstrumentToken(), e.getMessage());
        }
    }

    // ── Batched subscription ──────────────────────────────────────────────

    /**
     * JAR-VERIFIED: subscribe and setMode require ArrayList<Long>, not List<Long>.
     * Method signatures:
     *   subscribe(ArrayList<Long>)
     *   setMode(ArrayList<Long>, String)
     */
    private void subscribeInBatches() {
        int total   = subscribedFullTokens.size();
        int batches = (total + BATCH_SIZE - 1) / BATCH_SIZE;

        for (int b = 0; b < batches; b++) {
            int from = b * BATCH_SIZE;
            int to   = Math.min(from + BATCH_SIZE, total);

            // Must be ArrayList<Long> — JAR signature verified
            ArrayList<Long> batch = new ArrayList<>(subscribedFullTokens.subList(from, to));

            try {
                ticker.subscribe(batch);
                ticker.setMode(batch, KiteTicker.modeFull);
                log.info("[WS] Subscribed batch {}/{}: {} tokens (FULL)",
                        b + 1, batches, batch.size());

                if (b < batches - 1) {
                    Thread.sleep(BATCH_DELAY_MS);
                }
            } catch (Exception e) {
                log.error("[WS] Batch {} subscription failed: {}", b + 1, e.getMessage());
            }
        }

        if (!subscribedQuoteTokens.isEmpty()) {
            try {
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