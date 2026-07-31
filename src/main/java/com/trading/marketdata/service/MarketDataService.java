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

    /**
     * ADDITIVE (per explicit user request - order-book hard gate for
     * Momentum): live market depth per symbol, captured on every
     * FULL-mode tick. Verified against Zerodha's official javadoc
     * (kite.trade/docs/javakiteconnect/v3/com/zerodhatech/models/
     * Tick.html and .../Depth.html):
     *   Tick.getMarketDepth() -> Map<String, ArrayList<Depth>>
     *     keys are "buy" and "sell", each an ArrayList of depth
     *     entries, best price first.
     *   Depth.getPrice()    -> double
     *   Depth.getQuantity() -> int
     *   Depth.getOrders()   -> int
     * Zerodha's depth feed provides exactly 5 levels per side - NOT
     * 10 - a real platform constraint, not a limitation of this code.
     * QUOTE-mode symbols never populate this (depth is FULL-mode
     * only) - callers must treat an absent/empty snapshot as "no
     * depth data available" and fail safe accordingly.
     */
    /**
     * ADDITIVE (per explicit user request - Order Book Confirmation
     * Module, full spec): a ROLLING history of depth snapshots per
     * symbol, sampled at ~1-second cadence, is the actual foundation
     * this spec requires - deltas, 3-consecutive-update persistence,
     * and spoofing detection are all impossible from a single instant
     * snapshot (my first attempt's mistake, corrected here).
     * Sampling piggybacks on tick arrival: a new entry is appended
     * only if >=900ms has passed since the last stored sample for
     * that symbol - achieves ~1s cadence with zero extra timer thread.
     * Capped at 10 samples (10s window) - enough for 3-consecutive
     * persistence + spoofing detection, without unbounded memory growth.
     */
    public record DepthLevel(double price, int quantity, int orders) {}
    public record DepthSnapshot(List<DepthLevel> bids, List<DepthLevel> asks, Instant capturedAt) {}
    private static final long DEPTH_SAMPLE_MIN_INTERVAL_MS = 900;
    private static final int DEPTH_HISTORY_MAX_SAMPLES = 10;
    private final Map<String, java.util.Deque<DepthSnapshot>> depthHistory = new ConcurrentHashMap<>();

    /**
     * ADDITIVE (Institutional Confirmation Engine - Volume Profile
     * module, per explicit user request): intraday volume-at-price
     * histogram per symbol, built from incremental per-tick volume
     * attributed to price buckets. Bucket size is CONFIGURABLE (spec
     * requirement) - default 0.05% of the price at first capture each
     * day (a percentage keeps bucket granularity sane across a Rs.50
     * stock and a Rs.11,000 stock alike; a fixed rupee amount would
     * not). Reset once per day by the caller (Momentum's own daily
     * reset already exists - this hooks into nothing new, see
     * resetVolumeProfile() below, called the same way lastPrices would
     * be reset if it ever needed to be).
     */
    private final Map<String, Long> lastCumulativeVolume = new ConcurrentHashMap<>();
    private final Map<String, java.util.concurrent.ConcurrentSkipListMap<Double, Long>> volumeProfiles =
            new ConcurrentHashMap<>();
    private final Map<String, Double> profileBucketSize = new ConcurrentHashMap<>();
    private static final double DEFAULT_BUCKET_SIZE_PCT = 0.0005; // 0.05% of first-seen price

    /** Developing POC history: one snapshot per symbol per ~60s, so
     *  "developing POC shifting upward/downward" can be measured. */
    public record PocSnapshot(double poc, Instant at) {}
    private final Map<String, java.util.Deque<PocSnapshot>> developingPocHistory = new ConcurrentHashMap<>();
    private static final long POC_SNAPSHOT_MIN_INTERVAL_MS = 55_000; // ~1 minute, spec's own cadence
    private static final int POC_HISTORY_MAX_SAMPLES = 30; // 30 minutes of developing-POC history

    /**
     * ADDITIVE (Institutional Confirmation Engine - Order Flow module,
     * per explicit user request): classifies each tick's incremental
     * volume (already computed above for Volume Profile) as an
     * aggressive BUY or SELL using the standard tick rule:
     *   price >= current best ask -> BUY (lifted the offer)
     *   price <= current best bid -> SELL (hit the bid)
     *   otherwise -> fallback to price-direction vs the previous tick
     *   (up-tick = buy pressure, down-tick = sell pressure, unchanged
     *   = carry forward the last classification) - the standard
     *   fallback when depth doesn't clearly show the aggressor.
     * Reuses the live depth already captured this same tick (see the
     * depth-capture block below) - zero new market-data subscription.
     * Sampled at ~1s cadence, per the spec's own "Update every second".
     */
    public record OrderFlowSnapshot(long buyVolume, long sellVolume, long cumulativeDelta,
                                    double price, Instant capturedAt) {}
    private final Map<String, Double> lastPriceForTickRule = new ConcurrentHashMap<>();
    private final Map<String, Boolean> lastClassificationWasBuy = new ConcurrentHashMap<>();
    private final Map<String, long[]> cumulativeBuySell = new ConcurrentHashMap<>(); // [0]=buy,[1]=sell
    private final Map<String, Long> cumulativeDeltaBySymbol = new ConcurrentHashMap<>();
    private final Map<String, java.util.Deque<OrderFlowSnapshot>> orderFlowHistory = new ConcurrentHashMap<>();
    private static final long ORDERFLOW_SAMPLE_MIN_INTERVAL_MS = 950; // ~1s, spec's own cadence
    private static final int ORDERFLOW_HISTORY_MAX_SAMPLES = 60; // ~1 minute of 1s samples

    private void recordOrderFlow(String symbol, double price, long incrementalVol) {
        DepthSnapshot depth = getDepth(symbol); // most recent depth, already captured this tick or a prior one
        Boolean isBuy;
        if (depth != null && !depth.bids().isEmpty() && !depth.asks().isEmpty()) {
            double bestAsk = depth.asks().get(0).price();
            double bestBid = depth.bids().get(0).price();
            if (price >= bestAsk) isBuy = true;
            else if (price <= bestBid) isBuy = false;
            else isBuy = null; // ambiguous - fall through to price-direction rule
        } else {
            isBuy = null;
        }
        if (isBuy == null) {
            Double lastPrice = lastPriceForTickRule.get(symbol);
            if (lastPrice != null && price > lastPrice) isBuy = true;
            else if (lastPrice != null && price < lastPrice) isBuy = false;
            else isBuy = lastClassificationWasBuy.getOrDefault(symbol, true); // carry forward, fail-open true
        }
        lastPriceForTickRule.put(symbol, price);
        lastClassificationWasBuy.put(symbol, isBuy);

        long[] cumBS = cumulativeBuySell.computeIfAbsent(symbol, k -> new long[2]);
        synchronized (cumBS) {
            if (isBuy) cumBS[0] += incrementalVol; else cumBS[1] += incrementalVol;
        }
        long cumDelta = cumBS[0] - cumBS[1];
        cumulativeDeltaBySymbol.put(symbol, cumDelta);

        var hist = orderFlowHistory.computeIfAbsent(symbol, k -> new java.util.concurrent.ConcurrentLinkedDeque<>());
        OrderFlowSnapshot last = hist.peekLast();
        Instant now = Instant.now();
        long buyThisSample = isBuy ? incrementalVol : 0;
        long sellThisSample = isBuy ? 0 : incrementalVol;
        if (last == null || now.toEpochMilli() - last.capturedAt().toEpochMilli()
                >= ORDERFLOW_SAMPLE_MIN_INTERVAL_MS) {
            hist.addLast(new OrderFlowSnapshot(buyThisSample, sellThisSample, cumDelta, price, now));
            while (hist.size() > ORDERFLOW_HISTORY_MAX_SAMPLES) hist.pollFirst();
        } else {
            // Still within the same ~1s sample window - accumulate
            // into the CURRENT (not-yet-finalized) sample rather than
            // creating a new one, so each returned sample genuinely
            // represents ~1 second of activity, not a sub-second sliver.
            hist.pollLast();
            hist.addLast(new OrderFlowSnapshot(last.buyVolume() + buyThisSample,
                    last.sellVolume() + sellThisSample, cumDelta, price, last.capturedAt()));
        }
    }

    public List<OrderFlowSnapshot> getOrderFlowHistory(String symbol) {
        var h = orderFlowHistory.get(symbol);
        return h == null ? List.of() : new ArrayList<>(h);
    }

    public void resetOrderFlow(String symbol) {
        cumulativeBuySell.remove(symbol);
        cumulativeDeltaBySymbol.remove(symbol);
        orderFlowHistory.remove(symbol);
        lastPriceForTickRule.remove(symbol);
        lastClassificationWasBuy.remove(symbol);
    }

    private void recordVolumeAtPrice(String symbol, double price, long incrementalVolume) {
        double bucketSize = profileBucketSize.computeIfAbsent(symbol,
                k -> Math.max(0.01, price * DEFAULT_BUCKET_SIZE_PCT));
        double bucket = Math.round(price / bucketSize) * bucketSize;
        var profile = volumeProfiles.computeIfAbsent(symbol,
                k -> new java.util.concurrent.ConcurrentSkipListMap<>());
        profile.merge(bucket, incrementalVolume, Long::sum);

        // Update the developing-POC history at ~1-minute cadence.
        var pocHist = developingPocHistory.computeIfAbsent(symbol,
                k -> new java.util.concurrent.ConcurrentLinkedDeque<>());
        PocSnapshot lastSnap = pocHist.peekLast();
        Instant now = Instant.now();
        if (lastSnap == null || now.toEpochMilli() - lastSnap.at().toEpochMilli()
                >= POC_SNAPSHOT_MIN_INTERVAL_MS) {
            double currentPoc = profile.entrySet().stream()
                    .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(bucket);
            pocHist.addLast(new PocSnapshot(currentPoc, now));
            while (pocHist.size() > POC_HISTORY_MAX_SAMPLES) pocHist.pollFirst();
        }
    }

    /** Read-only view of the full session volume-at-price histogram,
     *  bucket price -> cumulative volume, for VolumeProfileConfirmationService. */
    public Map<Double, Long> getVolumeProfile(String symbol) {
        var p = volumeProfiles.get(symbol);
        return p == null ? Map.of() : new java.util.TreeMap<>(p);
    }

    public List<PocSnapshot> getDevelopingPocHistory(String symbol) {
        var h = developingPocHistory.get(symbol);
        return h == null ? List.of() : new ArrayList<>(h);
    }

    public double getVolumeProfileBucketSize(String symbol) {
        return profileBucketSize.getOrDefault(symbol, 0.0);
    }

    /** Daily reset hook - clears volume profile state for a new
     *  trading day. Callers (e.g. Momentum's own daily reset) invoke
     *  this once at market open; harmless/no-op if never called
     *  (state simply keeps accumulating, same risk any other
     *  session-scoped cache in this file already carries). */
    public void resetVolumeProfile(String symbol) {
        volumeProfiles.remove(symbol);
        lastCumulativeVolume.remove(symbol);
        profileBucketSize.remove(symbol);
        developingPocHistory.remove(symbol);
    }

    /** Most recent single snapshot - kept for any caller that only
     *  needs "right now" (e.g. dashboard display). */
    public DepthSnapshot getDepth(String symbol) {
        var hist = depthHistory.get(symbol);
        return (hist == null || hist.isEmpty()) ? null : hist.peekLast();
    }

    /** Full rolling history, oldest-first, for the Order Book
     *  Confirmation Module's delta/persistence/spoofing checks. */
    public List<DepthSnapshot> getDepthHistory(String symbol) {
        var hist = depthHistory.get(symbol);
        return hist == null ? List.of() : new ArrayList<>(hist);
    }

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

            // ADDITIVE (Institutional Confirmation Engine - Volume
            // Profile module, per explicit user request): attribute
            // each tick's INCREMENTAL volume (today's cumulative volume
            // minus the previous reading) to the price bucket the
            // trade occurred at. Uses only fields already JAR-verified
            // in this file (getLastTradedPrice, getVolumeTradedToday).
            // First tick of the day for a symbol has no prior baseline
            // to diff against - skipped rather than wrongly attributing
            // the whole pre-market/opening cumulative volume to one tick.
            long cumVol = tick.getVolumeTradedToday();
            Long prevCumVol = lastCumulativeVolume.get(symbol);
            if (prevCumVol != null && cumVol > prevCumVol) {
                long incrementalVol = cumVol - prevCumVol;
                recordVolumeAtPrice(symbol, ltpDbl, incrementalVol);
                // ADDITIVE (Institutional Confirmation Engine - Order
                // Flow module, per explicit user request): classify
                // this SAME incremental volume as aggressive buy or
                // sell using the standard tick rule.
                recordOrderFlow(symbol, ltpDbl, incrementalVol);
            }
            lastCumulativeVolume.put(symbol, cumVol);

            // ADDITIVE (order-book hard gate): capture depth if this
            // tick carries it (FULL mode only - null/absent for QUOTE
            // mode, handled safely rather than assumed present).
            Map<String, ArrayList<com.zerodhatech.models.Depth>> md = tick.getMarketDepth();
            if (md != null) {
                List<com.zerodhatech.models.Depth> buyRaw = md.get("buy");
                List<com.zerodhatech.models.Depth> sellRaw = md.get("sell");
                if (buyRaw != null && sellRaw != null && !buyRaw.isEmpty() && !sellRaw.isEmpty()) {
                    List<DepthLevel> bids = new ArrayList<>();
                    for (com.zerodhatech.models.Depth d : buyRaw) {
                        bids.add(new DepthLevel(d.getPrice(), d.getQuantity(), d.getOrders()));
                    }
                    List<DepthLevel> asks = new ArrayList<>();
                    for (com.zerodhatech.models.Depth d : sellRaw) {
                        asks.add(new DepthLevel(d.getPrice(), d.getQuantity(), d.getOrders()));
                    }
                    java.util.Deque<DepthSnapshot> hist = depthHistory.computeIfAbsent(
                            symbol, k -> new java.util.concurrent.ConcurrentLinkedDeque<>());
                    DepthSnapshot last = hist.peekLast();
                    Instant now = Instant.now();
                    if (last == null || now.toEpochMilli() - last.capturedAt().toEpochMilli()
                            >= DEPTH_SAMPLE_MIN_INTERVAL_MS) {
                        hist.addLast(new DepthSnapshot(bids, asks, now));
                        while (hist.size() > DEPTH_HISTORY_MAX_SAMPLES) hist.pollFirst();
                    }
                }
            }

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