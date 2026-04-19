package com.trading.strategy.highrr;

import com.trading.events.TickReceivedEvent;
import com.trading.events.CandleCompleteEvent;
import com.trading.domain.Candle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * HighRRScannerService — Real-time tick consumer for HighRR strategy.
 *
 * FIXES vs previous version:
 *
 *   FIX 1 — computeSpreadEstimate() was broken:
 *     The formula `imbalance * 0.001` always produced values far below
 *     MAX_SPREAD_PCT (0.0015), making the spread filter permanently disabled.
 *     The thin-book guard (MIN_DEPTH_QTY) was the only working safety net.
 *     New logic: if either side of book depth is thin → flag wide spread.
 *     If both sides are adequate → spread is considered acceptable.
 *     This correctly reflects the available data from Zerodha ticks
 *     (which do NOT provide actual bid/ask prices, only quantities).
 *
 * RESPONSIBILITIES:
 *   1. Consume every WebSocket tick (via TickReceivedEvent)
 *   2. Compute incremental VWAP per symbol
 *   3. Detect trend: UPTREND / DOWNTREND / SIDEWAYS
 *   4. Detect pullback zone (price retraced to VWAP/support/resistance)
 *   5. Track bid/ask depth for slippage avoidance
 *   6. Write full symbol state to Redis key: stock:{symbol}
 *   7. Detect 1-second momentum spikes
 *
 * RUNS COMPLETELY INDEPENDENTLY — no shared state with other strategies.
 *
 * REDIS KEY FORMAT:
 *   stock:{symbol} → hash with fields:
 *     price, volume, vwap, trend, pullbackZone,
 *     bidQty, askQty, spread, spreadPct,
 *     candleHigh, candleLow, candleOpen, candleClose,
 *     prevHigh, prevLow, prevClose,
 *     momentumSpike, lastTickEpoch, score
 */
@Service
@Slf4j
public class HighRRScannerService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // ── Time gates ────────────────────────────────────────────────────────────
    private static final LocalTime SCAN_START  = LocalTime.of(9, 20);
    private static final LocalTime SCAN_END    = LocalTime.of(13, 0);
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);

    // ── VWAP proximity: price within this % of VWAP = "near VWAP" ────────────
    private static final double VWAP_PROXIMITY_PCT = 0.005;  // 0.5%

    // ── Spread / depth filter ─────────────────────────────────────────────────
    // FIX 1: Zerodha ticks do NOT carry direct bid/ask prices — only quantities.
    // We cannot compute a real spread %. Instead we gate purely on depth:
    //   - If bidQty < MIN_DEPTH_QTY OR askQty < MIN_DEPTH_QTY → thin book → skip.
    //   - If both sides adequate → spread considered acceptable.
    // MAX_SPREAD_PCT is kept for SymbolState.isSpreadOk() to use with the
    // thin-book sentinel value (MAX_SPREAD_PCT * 2) that signals "skip this stock".
    private static final long   MIN_DEPTH_QTY  = 5_000L;
    private static final double MAX_SPREAD_PCT = 0.0015;     // used as threshold only

    // ── Momentum spike: skip if 1-second move > 0.2% ─────────────────────────
    private static final double MOMENTUM_SPIKE_PCT = 0.002;

    // ── Candle buffer: keep last 5 completed 5m candles per symbol ────────────
    private static final int CANDLE_BUFFER_SIZE = 5;

    // ── Skip index tokens ─────────────────────────────────────────────────────
    private static final Set<Long> INDEX_TOKENS = Set.of(256265L, 260105L, 264969L);

    @Autowired(required = false)
    private StringRedisTemplate redis;

    // ── In-memory state (hot path — Redis is the persistent store) ────────────

    // VWAP accumulators: symbol → [cumPV, cumVol]
    private final Map<String, double[]> vwapAccumulators = new ConcurrentHashMap<>();

    // Latest tick per symbol: symbol → [price, epochMs]
    private final Map<String, double[]> latestTick = new ConcurrentHashMap<>();

    // Previous tick price for momentum spike detection: symbol → price
    private final Map<String, Double> prevTickPrice = new ConcurrentHashMap<>();

    // Previous tick timestamp for 1-second spike window: symbol → epochMs
    private final Map<String, Long> prevTickTime = new ConcurrentHashMap<>();

    // Candle buffers: symbol → deque of completed 5m candles (newest first)
    private final Map<String, Deque<Candle>> candleBuffers = new ConcurrentHashMap<>();

    // Current forming candle state: symbol → [open, high, low, close, volume]
    private final Map<String, double[]> formingCandle = new ConcurrentHashMap<>();

    // ══════════════════════════════════════════════════════════════════════════
    // TICK LISTENER — called for every WebSocket tick
    // Runs on tickExecutor (fast, dedicated thread pool)
    // ══════════════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tickExecutor")
    public void onTick(TickReceivedEvent tick) {
        if (INDEX_TOKENS.contains(tick.getInstrumentToken())) return;

        String symbol = tick.getTradingSymbol();
        if (symbol == null || symbol.isBlank()) return;

        double price = tick.getLastTradedPrice().doubleValue();
        if (price <= 0) return;

        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(MARKET_OPEN) || now.isAfter(SCAN_END)) return;

        long tickVol = tick.getLastTradedQuantity();
        long dayVol  = tick.getVolumeTradedToday();
        long bidQty  = tick.getTotalBuyQuantity();
        long askQty  = tick.getTotalSellQuantity();
        long nowMs   = System.currentTimeMillis();

        // ── 1. Incremental VWAP update ────────────────────────────────────────
        double vwap = updateVwap(symbol, price, tickVol, dayVol);

        // ── 2. Momentum spike detection (1-second window) ─────────────────────
        boolean momentumSpike = detectMomentumSpike(symbol, price, nowMs);

        // ── 3. Spread / depth check ────────────────────────────────────────────
        // FIX 1: Use depth-based guard only (Zerodha has no real bid/ask price).
        // spreadPct is set to sentinel value > MAX_SPREAD_PCT when book is thin.
        double spreadPct = computeSpreadEstimate(bidQty, askQty);

        // ── 4. Update forming candle ───────────────────────────────────────────
        updateFormingCandle(symbol, price, tickVol);

        // ── 5. Compute trend from candle buffer ───────────────────────────────
        String trend = computeTrend(symbol, price, vwap);

        // ── 6. Pullback zone detection ────────────────────────────────────────
        boolean pullbackZone = detectPullbackZone(symbol, price, vwap);

        // ── 7. Update latest tick state ───────────────────────────────────────
        latestTick.put(symbol, new double[]{price, nowMs});

        // ── 8. Write full state to Redis ──────────────────────────────────────
        if (now.isAfter(SCAN_START)) {
            writeSymbolStateToRedis(symbol, price, vwap, trend, pullbackZone,
                    bidQty, askQty, spreadPct, momentumSpike, dayVol, nowMs);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CANDLE COMPLETE LISTENER — update candle buffer for trend detection
    // ══════════════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tradingExecutor")
    public void onCandle(CandleCompleteEvent event) {
        Candle c = event.getCandle();
        if (!"5minute".equals(c.getTimeframe()) || !c.isComplete()) return;
        if (INDEX_TOKENS.contains(c.getInstrumentToken())) return;

        String symbol = c.getTradingSymbol();
        Deque<Candle> buf = candleBuffers.computeIfAbsent(symbol, k -> new ArrayDeque<>());
        synchronized (buf) {
            buf.addFirst(c);
            while (buf.size() > CANDLE_BUFFER_SIZE) {
                ((ArrayDeque<Candle>) buf).removeLast();
            }
        }

        // Clear forming candle after close
        formingCandle.remove(symbol);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DAILY RESET
    // ══════════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyReset() {
        vwapAccumulators.clear();
        latestTick.clear();
        prevTickPrice.clear();
        prevTickTime.clear();
        candleBuffers.clear();
        formingCandle.clear();
        log.info("[HIGHRR-SCANNER] Daily reset complete");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // VWAP — Incremental calculation
    // ══════════════════════════════════════════════════════════════════════════

    private double updateVwap(String symbol, double price, long tickVol, long dayVol) {
        double[] acc = vwapAccumulators.computeIfAbsent(symbol, k -> new double[]{0.0, 0.0});
        if (tickVol > 0) {
            acc[0] += price * tickVol;
            acc[1] += tickVol;
        }
        return (acc[1] > 0) ? acc[0] / acc[1] : price;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TREND DETECTION
    // ══════════════════════════════════════════════════════════════════════════

    private String computeTrend(String symbol, double price, double vwap) {
        Deque<Candle> buf = candleBuffers.get(symbol);
        if (buf == null || buf.size() < 3) {
            if (vwap <= 0) return "SIDEWAYS";
            return price > vwap * 1.002 ? "UPTREND" : price < vwap * 0.998 ? "DOWNTREND" : "SIDEWAYS";
        }

        List<Candle> candles;
        synchronized (buf) {
            candles = new ArrayList<>(buf);
        }

        double high0 = candles.get(0).getHigh().doubleValue();
        double low0  = candles.get(0).getLow().doubleValue();
        double high1 = candles.get(1).getHigh().doubleValue();
        double low1  = candles.get(1).getLow().doubleValue();
        double high2 = candles.size() > 2 ? candles.get(2).getHigh().doubleValue() : high1;
        double low2  = candles.size() > 2 ? candles.get(2).getLow().doubleValue() : low1;

        boolean hhhl = (high0 > high1 && high1 > high2) && (low0 > low1 && low1 > low2);
        boolean lhll = (high0 < high1 && high1 < high2) && (low0 < low1 && low1 < low2);

        boolean aboveVwap = vwap > 0 && price > vwap;
        boolean belowVwap = vwap > 0 && price < vwap;

        if (hhhl && aboveVwap) return "UPTREND";
        if (lhll && belowVwap) return "DOWNTREND";
        if (hhhl || aboveVwap) return "UPTREND";
        if (lhll || belowVwap) return "DOWNTREND";
        return "SIDEWAYS";
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PULLBACK ZONE DETECTION
    // ══════════════════════════════════════════════════════════════════════════

    private boolean detectPullbackZone(String symbol, double price, double vwap) {
        if (vwap <= 0) return false;

        double vwapDist = Math.abs(price - vwap) / vwap;
        if (vwapDist <= VWAP_PROXIMITY_PCT) return true;

        Deque<Candle> buf = candleBuffers.get(symbol);
        if (buf == null || buf.isEmpty()) return false;

        List<Candle> candles;
        synchronized (buf) {
            candles = new ArrayList<>(buf);
        }

        double swingLow  = candles.stream().mapToDouble(c -> c.getLow().doubleValue()).min().orElse(0);
        double swingHigh = candles.stream().mapToDouble(c -> c.getHigh().doubleValue()).max().orElse(Double.MAX_VALUE);

        if (swingLow > 0 && Math.abs(price - swingLow) / swingLow <= 0.003) return true;
        if (swingHigh < Double.MAX_VALUE && Math.abs(price - swingHigh) / swingHigh <= 0.003) return true;

        return false;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // MOMENTUM SPIKE DETECTION (1-second window)
    // ══════════════════════════════════════════════════════════════════════════

    private boolean detectMomentumSpike(String symbol, double price, long nowMs) {
        Double prev     = prevTickPrice.get(symbol);
        Long   prevTime = prevTickTime.get(symbol);

        prevTickPrice.put(symbol, price);
        prevTickTime.put(symbol, nowMs);

        if (prev == null || prevTime == null || prev <= 0) return false;

        long elapsedMs = nowMs - prevTime;
        if (elapsedMs > 1000) return false;

        double movePct = Math.abs(price - prev) / prev;
        return movePct > MOMENTUM_SPIKE_PCT;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SPREAD ESTIMATE
    //
    // FIX 1: Previous formula `imbalance * 0.001` always produced values
    // well below the MAX_SPREAD_PCT threshold (0.0015), making the spread
    // filter permanently disabled.
    //
    // Zerodha ticks carry totalBuyQuantity and totalSellQuantity but NOT
    // actual bid/ask prices. We cannot compute a real spread percentage.
    //
    // Correct approach: gate purely on depth adequacy.
    //   - thin book (either side < MIN_DEPTH_QTY) → return sentinel > threshold
    //   - adequate depth on both sides → return 0.0 (passes isSpreadOk())
    //
    // SymbolState.isSpreadOk() checks spreadPct <= MAX_SPREAD_PCT (0.0015).
    // Sentinel value MAX_SPREAD_PCT * 2 = 0.003 → correctly fails isSpreadOk().
    // ══════════════════════════════════════════════════════════════════════════

    private double computeSpreadEstimate(long bidQty, long askQty) {
        // Thin book on either side → treat as wide spread (skip this entry)
        if (bidQty < MIN_DEPTH_QTY || askQty < MIN_DEPTH_QTY) {
            return MAX_SPREAD_PCT * 2.0; // sentinel: fails isSpreadOk()
        }
        // Both sides adequate → spread acceptable
        return 0.0; // passes isSpreadOk() (0.0 <= 0.0015)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // FORMING CANDLE TRACKER
    // ══════════════════════════════════════════════════════════════════════════

    private void updateFormingCandle(String symbol, double price, long tickVol) {
        formingCandle.compute(symbol, (k, existing) -> {
            if (existing == null) {
                return new double[]{price, price, price, price, tickVol};
            }
            existing[1] = Math.max(existing[1], price);
            existing[2] = Math.min(existing[2], price);
            existing[3] = price;
            existing[4] += tickVol;
            return existing;
        });
    }

    // ══════════════════════════════════════════════════════════════════════════
    // REDIS WRITE — full symbol state as Redis Hash
    // ══════════════════════════════════════════════════════════════════════════

    private void writeSymbolStateToRedis(String symbol, double price, double vwap,
                                         String trend, boolean pullbackZone,
                                         long bidQty, long askQty, double spreadPct,
                                         boolean momentumSpike, long dayVol, long nowMs) {
        if (redis == null) return;
        try {
            String key = "stock:" + symbol;
            Map<String, String> fields = new HashMap<>(16);
            fields.put("price",         String.format("%.4f", price));
            fields.put("vwap",          String.format("%.4f", vwap));
            fields.put("volume",        String.valueOf(dayVol));
            fields.put("trend",         trend);
            fields.put("pullbackZone",  String.valueOf(pullbackZone));
            fields.put("bidQty",        String.valueOf(bidQty));
            fields.put("askQty",        String.valueOf(askQty));
            fields.put("spreadPct",     String.format("%.6f", spreadPct));
            fields.put("momentumSpike", String.valueOf(momentumSpike));
            fields.put("lastTickEpoch", String.valueOf(nowMs));

            double[] fc = formingCandle.get(symbol);
            if (fc != null) {
                fields.put("candleOpen",  String.format("%.4f", fc[0]));
                fields.put("candleHigh",  String.format("%.4f", fc[1]));
                fields.put("candleLow",   String.format("%.4f", fc[2]));
                fields.put("candleClose", String.format("%.4f", fc[3]));
                fields.put("candleVol",   String.format("%.0f", fc[4]));
            }

            Deque<Candle> buf = candleBuffers.get(symbol);
            if (buf != null && !buf.isEmpty()) {
                Candle latest;
                synchronized (buf) { latest = buf.peekFirst(); }
                if (latest != null) {
                    fields.put("prevHigh",  latest.getHigh().toPlainString());
                    fields.put("prevLow",   latest.getLow().toPlainString());
                    fields.put("prevClose", latest.getClose().toPlainString());
                }
            }

            redis.opsForHash().putAll(key, fields);
            redis.expire(key, 2, TimeUnit.HOURS);
        } catch (Exception e) {
            log.debug("[HIGHRR-SCANNER] Redis write error for {}: {}", symbol, e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PUBLIC API — used by HighRRStrategyEngine
    // ══════════════════════════════════════════════════════════════════════════

    public SymbolState getSymbolState(String symbol) {
        if (redis == null) return null;
        try {
            String key = "stock:" + symbol;
            Map<Object, Object> raw = redis.opsForHash().entries(key);
            if (raw == null || raw.isEmpty()) return null;
            return SymbolState.fromRedisHash(symbol, raw);
        } catch (Exception e) {
            log.debug("[HIGHRR-SCANNER] Redis read error for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    public double getLatestPrice(String symbol) {
        double[] tick = latestTick.get(symbol);
        return tick != null ? tick[0] : 0.0;
    }

    public boolean hasExcessiveWick(String symbol) {
        double[] fc = formingCandle.get(symbol);
        if (fc == null) return false;
        double body  = Math.abs(fc[3] - fc[0]);
        double range = fc[1] - fc[2];
        if (range <= 0) return false;
        double wickTotal = range - body;
        return wickTotal > body;
    }

    public Set<String> getTrackedSymbols() {
        return Collections.unmodifiableSet(latestTick.keySet());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SYMBOL STATE RECORD
    // ══════════════════════════════════════════════════════════════════════════

    public record SymbolState(
            String  symbol,
            double  price,
            double  vwap,
            long    volume,
            String  trend,
            boolean pullbackZone,
            long    bidQty,
            long    askQty,
            double  spreadPct,
            boolean momentumSpike,
            double  candleOpen,
            double  candleHigh,
            double  candleLow,
            double  candleClose,
            double  candleVol,
            double  prevHigh,
            double  prevLow,
            double  prevClose,
            long    lastTickEpoch
    ) {
        public boolean isTrendingUp()   { return "UPTREND".equals(trend); }
        public boolean isTrendingDown() { return "DOWNTREND".equals(trend); }
        public boolean isSideways()     { return "SIDEWAYS".equals(trend); }

        public boolean hasAdequateDepth() { return bidQty >= 5000 && askQty >= 5000; }

        /**
         * FIX 1: spreadPct is now 0.0 when depth is adequate, or MAX_SPREAD_PCT*2
         * when depth is thin. This correctly gates isSpreadOk().
         */
        public boolean isSpreadOk() { return spreadPct <= 0.0015; }

        public boolean isBuySetup() {
            return isTrendingUp()
                    && pullbackZone
                    && !momentumSpike
                    && isSpreadOk()
                    && hasAdequateDepth()
                    && isBullishCandle();
        }

        public boolean isSellSetup() {
            return isTrendingDown()
                    && pullbackZone
                    && !momentumSpike
                    && isSpreadOk()
                    && hasAdequateDepth()
                    && isBearishCandle();
        }

        public boolean isBullishCandle() {
            return candleClose > candleOpen && candleClose > 0;
        }

        public boolean isBearishCandle() {
            return candleClose < candleOpen && candleClose > 0;
        }

        public boolean hasExcessiveWick() {
            double body  = Math.abs(candleClose - candleOpen);
            double range = candleHigh - candleLow;
            if (range <= 0) return false;
            return (range - body) > body;
        }

        public boolean hasVolumeSpike() {
            return candleVol > 0 && prevClose > 0;
        }

        static SymbolState fromRedisHash(String symbol, Map<Object, Object> raw) {
            try {
                return new SymbolState(
                        symbol,
                        parseDouble(raw, "price"),
                        parseDouble(raw, "vwap"),
                        parseLong(raw, "volume"),
                        parseStr(raw, "trend", "SIDEWAYS"),
                        parseBool(raw, "pullbackZone"),
                        parseLong(raw, "bidQty"),
                        parseLong(raw, "askQty"),
                        parseDouble(raw, "spreadPct"),
                        parseBool(raw, "momentumSpike"),
                        parseDouble(raw, "candleOpen"),
                        parseDouble(raw, "candleHigh"),
                        parseDouble(raw, "candleLow"),
                        parseDouble(raw, "candleClose"),
                        parseDouble(raw, "candleVol"),
                        parseDouble(raw, "prevHigh"),
                        parseDouble(raw, "prevLow"),
                        parseDouble(raw, "prevClose"),
                        parseLong(raw, "lastTickEpoch")
                );
            } catch (Exception e) {
                return null;
            }
        }

        private static double  parseDouble(Map<Object,Object> m, String k) { try { String v = (String)m.get(k); return v != null ? Double.parseDouble(v) : 0.0; } catch(Exception e){ return 0.0; } }
        private static long    parseLong(Map<Object,Object> m, String k)   { try { String v = (String)m.get(k); return v != null ? Long.parseLong(v) : 0L; }    catch(Exception e){ return 0L;  } }
        private static boolean parseBool(Map<Object,Object> m, String k)   { try { String v = (String)m.get(k); return "true".equalsIgnoreCase(v); }              catch(Exception e){ return false;} }
        private static String  parseStr(Map<Object,Object> m, String k, String def) { String v = (String)m.get(k); return v != null ? v : def; }
    }
}