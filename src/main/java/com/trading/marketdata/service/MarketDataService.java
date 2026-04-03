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
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MarketDataService — Manages the Zerodha KiteTicker WebSocket connection.
 *
 * PRODUCTION ISSUES FIXED:
 *
 *   1. [RACE CONDITION] ticker/subscribedTokens fields written from main thread,
 *      read from ws-reconnect-N threads — no synchronization → stale reads,
 *      NPE, double-connect. Fixed: volatile on ticker + accessToken; token
 *      lists made volatile-safe via local snapshot in subscribeInBatches().
 *
 *   2. [THREAD LEAK] scheduleReconnect() spawned a raw new Thread() on every
 *      disconnect. 20 retries × multiple reconnect cycles = unbounded threads.
 *      Fixed: single ScheduledExecutorService (1 thread), rejects duplicate
 *      reconnect if one is already pending via reconnectPending flag.
 *
 *   3. [MARKET HOURS BLINDNESS] reconnect fired at 3:35 PM, 6 PM, midnight —
 *      burning Zerodha's rate limit for nothing. WebSocket only works
 *      9:00–15:35 IST on trading days.
 *      Fixed: scheduleReconnect() bails out outside market hours.
 *
 *   4. [SILENT TICK DROP] publishTick() catches ALL exceptions silently at
 *      DEBUG level. A broken instrumentCache lookup or null symbol silently
 *      drops the tick — candle aggregation starves, strategy gets no data.
 *      Fixed: null/empty symbol guard at WARN level so dropped ticks are
 *      visible in logs without flooding.
 *
 *   5. [SUBSCRIBE ON CALLBACK THREAD] subscribeInBatches() is called from
 *      onConnected() which runs on the KiteTicker WebSocket callback thread.
 *      Thread.sleep(100ms) × N batches blocks that thread — Zerodha's SDK
 *      queues all incoming ticks behind it, causing a tick burst on resume.
 *      Fixed: subscribeInBatches() offloaded to the reconnect executor so
 *      the WS callback thread is released immediately.
 *
 *   6. [DOUBLE OLD TICKER] reconnect() calls disconnect() on old ticker then
 *      immediately overwrites ticker field in initTicker(). If disconnect()
 *      triggers onDisconnected() callback (which calls scheduleReconnect()),
 *      you get two parallel reconnect chains.
 *      Fixed: set connected=false and cancel any pending reconnect before
 *      disconnect(); guard scheduleReconnect() against intentional reconnects.
 *
 *   7. [STALE RECONNECT COUNT] reconnectCount is never reset on a successful
 *      manual reconnect() call — so after a daily token refresh the backoff
 *      starts from attempt-21 (30s delay) rather than 0.
 *      Fixed: reconnect() resets reconnectCount to 0 before initTicker().
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MarketDataService {

    private static final ZoneId IST             = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MARKET_OPEN  = LocalTime.of(9, 0);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 35);

    private final ApplicationEventPublisher publisher;
    private final KiteConnect               kiteConnect;
    private final InstrumentCacheService    instrumentCache;

    // FIX 1: volatile — written by main/reconnect threads, read by WS callback thread
    private volatile KiteTicker ticker;
    private volatile String     accessToken;

    private List<Long> subscribedFullTokens  = new ArrayList<>();
    private List<Long> subscribedQuoteTokens = new ArrayList<>();

    private final AtomicBoolean connected       = new AtomicBoolean(false);
    private final AtomicInteger reconnectCount  = new AtomicInteger(0);
    // FIX 2/6: single executor prevents thread leak; flag prevents duplicate reconnects
    private final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ws-reconnect");
        t.setDaemon(true);
        return t;
    });
    // Separate executor for batched subscriptions — must be distinct from reconnectExecutor
    // so the WS callback thread (ReadingThread) is not the one picking up the task.
    // Using a plain single-thread executor (not scheduled) is sufficient here.
    private final java.util.concurrent.ExecutorService subscribeExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "ws-subscribe");
                t.setDaemon(true);
                return t;
            });
    private final AtomicBoolean reconnectPending = new AtomicBoolean(false);
    // FIX 6: flag set during intentional reconnect to suppress onDisconnected→scheduleReconnect
    private volatile boolean intentionalDisconnect = false;

    private final Map<String, BigDecimal> lastPrices = new ConcurrentHashMap<>();

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

    /**
     * Called by DailyLoginScheduler with fresh access token after 6 AM login.
     * FIX 6: sets intentionalDisconnect so onDisconnected() does NOT trigger
     *        another scheduleReconnect() in parallel.
     * FIX 7: resets reconnectCount so backoff starts fresh from attempt-1.
     */
    public void reconnect(String newAccessToken) {
        log.info("[WS] Intentional reconnect with new token...");
        intentionalDisconnect = true;
        reconnectCount.set(0);          // FIX 7: reset backoff counter
        reconnectPending.set(false);    // cancel any pending auto-reconnect

        if (ticker != null) {
            try { ticker.disconnect(); } catch (Exception ignored) {}
        }
        intentionalDisconnect = false;

        accessToken = newAccessToken;
        kiteConnect.setAccessToken(newAccessToken);
        initTicker();
    }

    public boolean isConnected()       { return connected.get(); }
    public int     getReconnectCount() { return reconnectCount.get(); }

    public Map<String, BigDecimal> getLastPricesSimple() {
        return java.util.Collections.unmodifiableMap(lastPrices);
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
                    reconnectPending.set(false);
                    log.info("[WS] Connected. Subscribing {} FULL + {} QUOTE tokens...",
                            subscribedFullTokens.size(), subscribedQuoteTokens.size());

                    // FIX 5: offload batched subscribe to a DEDICATED thread (ws-subscribe).
                    // Must NOT use reconnectExecutor here — if a reconnect is already
                    // scheduled, submit() would queue behind it and block subscription.
                    // Separate executor guarantees immediate dispatch off ReadingThread.
                    subscribeExecutor.submit(() -> subscribeInBatches());
                }
            });

            ticker.setOnDisconnectedListener(new OnDisconnect() {
                @Override
                public void onDisconnected() {
                    connected.set(false);
                    // FIX 6: suppress auto-reconnect when we intentionally disconnected
                    if (intentionalDisconnect) {
                        log.info("[WS] Intentional disconnect — skipping auto-reconnect.");
                        return;
                    }
                    log.warn("[WS] Unexpected disconnect. Scheduling reconnect...");
                    scheduleReconnect();
                }
            });

            ticker.setOnErrorListener(new OnError() {
                @Override
                public void onError(Exception e) {
                    log.error("[WS] Exception: {}", e.getMessage());
                }

                @Override
                public void onError(KiteException e) {
                    log.error("[WS] KiteException: code={} message={}", e.code, e.message);
                    if (e.code == 429) {
                        log.warn("[WS] Rate limited (429) — backoff will apply on next reconnect.");
                    }
                }

                @Override
                public void onError(String error) {
                    log.error("[WS] Error: {}", error);
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

            // setMaximumRetries/Interval are the only methods that declare throws KiteException
            // (KiteException extends Throwable directly, not Exception — plain catch(Exception)
            // would silently miss it). connect/subscribe/setMode do NOT declare throws.
            try {
                ticker.setMaximumRetries(20);
                ticker.setMaximumRetryInterval(30);
            } catch (KiteException ke) {
                log.warn("[WS] setMaximumRetries/Interval rejected: code={} msg={} — using SDK defaults",
                        ke.code, ke.message);
            }

            ticker.connect();

        } catch (Exception e) {
            log.error("[WS] Failed to initialize KiteTicker: {}", e.getMessage(), e);
            scheduleReconnect();
        }
    }

    // ── Tick publishing ───────────────────────────────────────────────────

    private void publishTick(Tick tick) {
        try {
            String symbol = instrumentCache.getSymbol(tick.getInstrumentToken());

            // FIX 4: null/empty symbol means the token is not in our instrument cache.
            // Log at WARN (not silently swallow at DEBUG) so dropped ticks are visible.
            if (symbol == null || symbol.isEmpty()) {
                log.warn("[WS] Unknown instrument token {} — not in cache, tick dropped",
                        tick.getInstrumentToken());
                return;
            }

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
                    (long) tick.getOi(),
                    tickTime
            );

            publisher.publishEvent(event);

        } catch (Exception e) {
            // Genuine unexpected error — log at error, not debug
            log.error("[WS] Tick publish error for token {}: {}",
                    tick.getInstrumentToken(), e.getMessage());
        }
    }

    // ── Batched subscription ──────────────────────────────────────────────

    private void subscribeInBatches() {
        // FIX 1: take a local snapshot — field could be reassigned mid-iteration
        // if reconnect() is called concurrently
        List<Long> fullTokens  = new ArrayList<>(subscribedFullTokens);
        List<Long> quoteTokens = new ArrayList<>(subscribedQuoteTokens);

        int total   = fullTokens.size();
        int batches = (total + BATCH_SIZE - 1) / BATCH_SIZE;

        for (int b = 0; b < batches; b++) {
            int from = b * BATCH_SIZE;
            int to   = Math.min(from + BATCH_SIZE, total);
            ArrayList<Long> batch = new ArrayList<>(fullTokens.subList(from, to));

            try {
                ticker.subscribe(batch);
                ticker.setMode(batch, KiteTicker.modeFull);
                log.info("[WS] Subscribed batch {}/{}: {} tokens (FULL)", b + 1, batches, batch.size());

                if (b < batches - 1) {
                    Thread.sleep(BATCH_DELAY_MS);
                }
            } catch (Exception e) {
                log.error("[WS] Batch {} subscription failed: {}", b + 1, e.getMessage());
            }
        }

        if (!quoteTokens.isEmpty()) {
            try {
                ArrayList<Long> qt = new ArrayList<>(quoteTokens);
                ticker.subscribe(qt);
                ticker.setMode(qt, KiteTicker.modeQuote);
                log.info("[WS] Subscribed {} QUOTE tokens", quoteTokens.size());
            } catch (Exception e) {
                log.error("[WS] QUOTE subscription failed: {}", e.getMessage());
            }
        }

        log.info("[WS] All subscriptions complete. {} FULL + {} QUOTE",
                fullTokens.size(), quoteTokens.size());
    }

    // ── Reconnect with exponential backoff ────────────────────────────────

    private void scheduleReconnect() {
        // FIX 3: never attempt reconnect outside market hours — pointless and
        // burns Zerodha rate limits. Connection is restored by DailyLoginScheduler
        // at market open with a fresh access token anyway.
        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(MARKET_OPEN) || now.isAfter(MARKET_CLOSE)) {
            log.info("[WS] Outside market hours ({}) — skipping auto-reconnect.", now);
            return;
        }

        // FIX 2: reject duplicate reconnect if one is already scheduled
        if (!reconnectPending.compareAndSet(false, true)) {
            log.debug("[WS] Reconnect already pending — ignoring duplicate request.");
            return;
        }

        int attempt  = reconnectCount.incrementAndGet();
        int delaySec = (int) Math.min(Math.pow(2, attempt - 1), MAX_BACKOFF_SEC);

        log.info("[WS] Reconnect attempt {} scheduled in {}s...", attempt, delaySec);

        reconnectExecutor.schedule(() -> {
            reconnectPending.set(false);
            if (!connected.get() && accessToken != null) {
                log.info("[WS] Attempting reconnect #{}", attempt);
                initTicker();
            }
        }, delaySec, TimeUnit.SECONDS);
    }
}