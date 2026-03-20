package com.trading.marketdata.service;

import com.trading.events.OrderUpdateEvent;
import com.trading.events.TickReceivedEvent;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Tick;
import com.zerodhatech.ticker.KiteTicker;
import com.zerodhatech.ticker.OnConnect;
import com.zerodhatech.ticker.OnDisconnect;
import com.zerodhatech.ticker.OnError;
import com.zerodhatech.ticker.OnOrderUpdate;
import com.zerodhatech.ticker.OnTicks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Slf4j
@RequiredArgsConstructor
public class MarketDataService {

    private final ApplicationEventPublisher publisher;
    private final InstrumentCacheService    instrumentCache;

    @Value("${zerodha.api-key}")
    private String apiKey;

    @Value("${zerodha.websocket.max-reconnects:20}")
    private int maxReconnects;

    // ── State ─────────────────────────────────────────────────────────

    // AtomicReference so connect() and subscribeInBatches() always
    // see the SAME ticker instance with no null races
    private final AtomicReference<KiteTicker> tickerRef =
            new AtomicReference<>(null);

    private volatile String  currentAccessToken;
    private volatile boolean stopping     = false;
    private volatile boolean connected    = false;
    private volatile Instant lastTickTime = Instant.now();

    private final Set<Long> fullModeTokens  = ConcurrentHashMap.newKeySet();
    private final Set<Long> quoteModeTokens = ConcurrentHashMap.newKeySet();

    private final Map<String, Map<String, Object>> lastPrices =
            new ConcurrentHashMap<>();

    // ── Reconnect control ─────────────────────────────────────────────
    private final AtomicInteger reconnectCount = new AtomicInteger(0);

    // Single-thread reconnect scheduler — serialises all reconnect attempts
    private final ScheduledExecutorService reconnectScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ws-reconnect");
                t.setDaemon(true);
                return t;
            });

    // Generation counter — each new connect() increments this.
    // Subscription batches check it and abort if generation changed
    // (i.e. a new connect() fired while they were running)
    private final AtomicLong generation = new AtomicLong(0);

    // ── Public API ────────────────────────────────────────────────────

    public void startStreaming(String accessToken,
                               List<Long> fullTokens,
                               List<Long> quoteTokens) {
        this.currentAccessToken = accessToken;
        this.stopping           = false;
        fullModeTokens.addAll(fullTokens);
        quoteModeTokens.addAll(quoteTokens);
        reconnectCount.set(0);
        connect();
    }

    public void stopStreaming() {
        stopping   = true;
        connected  = false;
        KiteTicker t = tickerRef.getAndSet(null);
        if (t != null) {
            try { t.disconnect(); } catch (Exception ignored) {}
        }
        log.info("Streaming stopped");
    }

    public boolean isConnected()  { return connected; }
    public Instant getLastTickTime() { return lastTickTime; }

    public Map<String, Map<String, Object>> getLastPrices() {
        return Collections.unmodifiableMap(lastPrices);
    }

    public Map<String, BigDecimal> getLastPricesSimple() {
        Map<String, BigDecimal> simple = new HashMap<>();
        lastPrices.forEach((sym, data) -> {
            Object ltp = data.get("ltp");
            if      (ltp instanceof BigDecimal bd) simple.put(sym, bd);
            else if (ltp instanceof Number n)
                simple.put(sym, BigDecimal.valueOf(n.doubleValue()));
        });
        return simple;
    }

    // ── Stale tick watchdog ───────────────────────────────────────────

    @Scheduled(fixedDelay = 300_000)
    public void checkTickHealth() {
        if (stopping || currentAccessToken == null || !connected) return;
        java.time.LocalTime now =
                java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        boolean marketHours =
                now.isAfter(java.time.LocalTime.of(9, 0))
                        && now.isBefore(java.time.LocalTime.of(15, 35));
        if (!marketHours) return;

        long stale = Instant.now().getEpochSecond()
                - lastTickTime.getEpochSecond();
        if (stale > 180) {
            log.warn("No ticks for {}s — forcing reconnect", stale);
            connected = false;
            scheduleReconnect(2);
        }
    }

    // ── Connect ───────────────────────────────────────────────────────

    private synchronized void connect() {
        if (stopping) return;

        // Increment generation — any in-flight batches from old generation
        // will see the mismatch and abort immediately
        long myGeneration = generation.incrementAndGet();
        log.info("connect() gen={}", myGeneration);

        // Disconnect and discard old ticker
        KiteTicker old = tickerRef.getAndSet(null);
        if (old != null) {
            try { old.disconnect(); } catch (Exception ignored) {}
            try { Thread.sleep(500); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt(); return;
            }
        }

        try {
            KiteTicker newTicker = new KiteTicker(currentAccessToken, apiKey);
            newTicker.setTryReconnection(false); // we handle reconnect

            newTicker.setOnConnectedListener(new OnConnect() {
                @Override
                public void onConnected() {
                    connected = true;
                    reconnectCount.set(0);
                    lastTickTime = Instant.now();
                    log.info("WebSocket CONNECTED gen={}", myGeneration);

                    // Only subscribe if we are still on this generation
                    if (generation.get() == myGeneration) {
                        // Small delay then subscribe
                        reconnectScheduler.schedule(
                                () -> subscribeInBatches(myGeneration),
                                500, TimeUnit.MILLISECONDS);
                    } else {
                        log.warn("onConnected: generation changed — skip subscribe");
                    }
                }
            });

            newTicker.setOnDisconnectedListener(new OnDisconnect() {
                @Override
                public void onDisconnected() {
                    connected = false;
                    log.warn("WebSocket DISCONNECTED gen={}", myGeneration);
                    if (!stopping && generation.get() == myGeneration) {
                        scheduleReconnect(5);
                    }
                }
            });

            newTicker.setOnErrorListener(new OnError() {
                @Override public void onError(Exception e) {
                    log.error("WS error: {}", e != null ? e.getMessage() : "null");
                }
                @Override public void onError(KiteException e) {
                    log.error("WS KiteException [{}]: {}", e.code, e.message);
                    if (e.code == 403) {
                        log.error("WS 403 — token expired");
                        stopping = true;
                    }
                }
                @Override public void onError(String s) {
                    log.error("WS error string: {}", s);
                }
            });

            newTicker.setOnOrderUpdateListener(new OnOrderUpdate() {
                @Override
                public void onOrderUpdate(com.zerodhatech.models.Order o) {
                    try {
                        double avg = 0;
                        try { avg = Double.parseDouble(
                                String.valueOf(o.averagePrice)); }
                        catch (Exception ignored) {}
                        publisher.publishEvent(new OrderUpdateEvent(
                                MarketDataService.this,
                                o.orderId, o.tradingSymbol, o.status,
                                parseIntSafe(o.filledQuantity),
                                parseIntSafe(o.pendingQuantity),
                                avg, o.statusMessage));
                    } catch (Exception e) {
                        log.error("Order update error: {}", e.getMessage());
                    }
                }
            });

            newTicker.setOnTickerArrivalListener(new OnTicks() {
                @Override
                public void onTicks(ArrayList<Tick> ticks) {
                    if (ticks == null || ticks.isEmpty()) return;
                    lastTickTime = Instant.now();
                    for (Tick t : ticks) publishTick(t);
                }
            });

            // Set the new ticker BEFORE calling connect()
            tickerRef.set(newTicker);
            newTicker.connect();
            log.info("KiteTicker.connect() called gen={}", myGeneration);

        } catch (Exception e) {
            log.error("connect() failed: {}", e.getMessage());
            tickerRef.set(null);
            if (!stopping) scheduleReconnect(5);
        }
    }

    // ── Subscribe in batches ──────────────────────────────────────────
    // Each batch checks generation before executing.
    // If generation changed (new connect() fired) — batch aborts.

    private void subscribeInBatches(long myGeneration) {
        if (generation.get() != myGeneration) {
            log.warn("subscribeInBatches: generation changed — abort");
            return;
        }
        if (!connected) {
            log.warn("subscribeInBatches: not connected — abort");
            return;
        }

        KiteTicker t = tickerRef.get();
        if (t == null) {
            log.warn("subscribeInBatches: ticker is null — abort");
            return;
        }

        List<Long> allTokens = new ArrayList<>();
        allTokens.addAll(fullModeTokens);
        allTokens.addAll(quoteModeTokens);

        int  batchSize = 30;
        int  total     = allTokens.size();
        int  batches   = (int) Math.ceil((double) total / batchSize);
        long delayMs   = 600;

        log.info("Subscribing {} tokens in {} batches (gen={})",
                total, batches, myGeneration);

        // Use a fresh single-thread executor per subscription cycle
        // This prevents old batches from mixing with new ones
        ScheduledExecutorService batchExec =
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread th = new Thread(r, "ws-sub-" + myGeneration);
                    th.setDaemon(true);
                    return th;
                });

        for (int i = 0; i < total; i += batchSize) {
            final List<Long> batch    = new ArrayList<>(
                    allTokens.subList(i, Math.min(i + batchSize, total)));
            final int        batchNum = (i / batchSize) + 1;
            final long       delay    = (long)(i / batchSize) * delayMs;

            batchExec.schedule(() -> {
                // Check generation before each batch
                if (generation.get() != myGeneration) {
                    log.debug("Batch {} aborted — generation changed", batchNum);
                    return;
                }

                KiteTicker ticker = tickerRef.get();
                if (ticker == null || !connected) {
                    log.debug("Batch {} aborted — ticker null or disconnected",
                            batchNum);
                    return;
                }

                try {
                    ticker.subscribe(new ArrayList<>(batch));

                    ArrayList<Long> fullBatch  = new ArrayList<>();
                    ArrayList<Long> quoteBatch = new ArrayList<>();
                    for (Long token : batch) {
                        if (fullModeTokens.contains(token)) fullBatch.add(token);
                        else quoteBatch.add(token);
                    }
                    if (!fullBatch.isEmpty())
                        ticker.setMode(fullBatch, KiteTicker.modeFull);
                    if (!quoteBatch.isEmpty())
                        ticker.setMode(quoteBatch, KiteTicker.modeQuote);

                    log.info("Batch {}/{} subscribed ({} tokens)",
                            batchNum, batches, batch.size());
                } catch (Exception e) {
                    log.debug("Batch {} failed: {}", batchNum, e.getMessage());
                }
            }, delay, TimeUnit.MILLISECONDS);
        }

        // Shutdown executor after all batches complete
        long shutdownDelay = (long) batches * delayMs + 2000;
        batchExec.schedule(() -> {
            if (generation.get() == myGeneration) {
                log.info("All subscriptions complete gen={} full={} quote={}",
                        myGeneration,
                        fullModeTokens.size(), quoteModeTokens.size());
            }
            batchExec.shutdown();
        }, shutdownDelay, TimeUnit.MILLISECONDS);
    }

    // ── Reconnect with backoff ────────────────────────────────────────

    private void scheduleReconnect(long delaySeconds) {
        if (stopping) return;
        int attempt = reconnectCount.incrementAndGet();
        if (attempt > maxReconnects) {
            log.error("Max reconnects ({}) reached", maxReconnects);
            return;
        }
        long d = delaySeconds > 0 ? delaySeconds
                : Math.min(5L * (1L << (attempt - 1)), 60L);
        log.info("Reconnect {}/{} in {}s", attempt, maxReconnects, d);
        reconnectScheduler.schedule(this::connect, d, TimeUnit.SECONDS);
    }

    // ── Publish tick ──────────────────────────────────────────────────

    private void publishTick(Tick tick) {
        try {
            String symbol = instrumentCache.getSymbol(tick.getInstrumentToken());
            if (symbol == null || symbol.startsWith("UNKNOWN")) return;

            BigDecimal ltp   = BigDecimal.valueOf(tick.getLastTradedPrice());
            BigDecimal close = BigDecimal.valueOf(tick.getClosePrice());
            if (ltp.compareTo(BigDecimal.ZERO) == 0) return;

            BigDecimal changePct = BigDecimal.ZERO;
            if (close.compareTo(BigDecimal.ZERO) != 0) {
                changePct = ltp.subtract(close)
                        .divide(close, MathContext.DECIMAL32)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(2, RoundingMode.HALF_UP);
            }

            Map<String, Object> tickMap = new HashMap<>(8);
            tickMap.put("ltp",           ltp);
            tickMap.put("open",          BigDecimal.valueOf(tick.getOpenPrice()));
            tickMap.put("high",          BigDecimal.valueOf(tick.getHighPrice()));
            tickMap.put("low",           BigDecimal.valueOf(tick.getLowPrice()));
            tickMap.put("close",         close);
            tickMap.put("changePercent", changePct);
            tickMap.put("volume",        (long) tick.getVolumeTradedToday());
            lastPrices.put(symbol, tickMap);

            Instant ts = Instant.now();
            try {
                if (tick.getTickTimestamp() != null)
                    ts = tick.getTickTimestamp().toInstant();
            } catch (Exception ignored) {}

            publisher.publishEvent(new TickReceivedEvent(
                    this,
                    tick.getInstrumentToken(), symbol, ltp,
                    BigDecimal.valueOf(tick.getOpenPrice()),
                    BigDecimal.valueOf(tick.getHighPrice()),
                    BigDecimal.valueOf(tick.getLowPrice()),
                    close,
                    (long) tick.getVolumeTradedToday(),
                    (long) tick.getTotalBuyQuantity(),
                    (long) tick.getTotalSellQuantity(),
                    (long) tick.getOi(),
                    (long) tick.getOpenInterestDayHigh(),
                    (long) tick.getOpenInterestDayLow(),
                    BigDecimal.valueOf(tick.getAverageTradePrice()),
                    BigDecimal.valueOf(tick.getChange()),
                    ts));

        } catch (Exception e) {
            log.error("publishTick error token={}: {}",
                    tick.getInstrumentToken(), e.getMessage());
        }
    }

    private int parseIntSafe(Object val) {
        try { return Integer.parseInt(String.valueOf(val)); }
        catch (Exception e) { return 0; }
    }
}