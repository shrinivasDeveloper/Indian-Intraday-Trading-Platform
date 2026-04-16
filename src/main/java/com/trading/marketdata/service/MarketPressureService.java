package com.trading.marketdata.service;

import com.trading.events.TickReceivedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MarketPressureService — Real-time market-wide pressure aggregation.
 *
 * CORE CONCEPT:
 *   Base reference = 9:15 AM open price (fixed, never changes intraday).
 *   Every tick updates: % change = (live - open) / open * 100
 *   Every 60 seconds: sum all positives → BUY strength, all negatives → SELL strength
 *   Dominant side = current market pressure direction.
 *
 * REDIS KEYS:
 *   mkt:open:{symbol}      → 9:15 AM open price (string double)
 *   mkt:live:{symbol}      → latest tick price  (string double)
 *   mkt:pct:{symbol}       → % change from open (string double, can be negative)
 *   mkt:buy_strength       → aggregated BUY  strength (sum of positive %)
 *   mkt:sell_strength      → aggregated SELL strength (sum of abs negative %)
 *   mkt:pressure_dir       → "BUY" | "SELL" | "NEUTRAL"
 *   mkt:pressure_ratio     → buy/sell ratio (string double)
 *   mkt:symbol_count       → total symbols contributing
 *   mkt:buy_count          → symbols with positive change
 *   mkt:sell_count         → symbols with negative change
 *
 * NOISE FILTER:
 *   Symbols with |% change| < threshold (default 0.1%) are treated as neutral.
 *   This avoids counting micro-movements from illiquid stocks.
 *
 * THREAD SAFETY:
 *   - openPrices ConcurrentHashMap (locked at 9:15 AM — never written after)
 *   - latestPrices ConcurrentHashMap (always updated from tick thread)
 *   - Redis is the source of truth for aggregated state
 *   - In-memory maps are the hot-path cache (Redis is written async)
 */
@Service
@Slf4j
public class MarketPressureService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // ── Market open time ────────────────────────────────────────────────────────
    private static final LocalTime MARKET_OPEN  = LocalTime.of(9, 15);
    private static final LocalTime OPEN_LOCK_BY = LocalTime.of(9, 20); // capture window
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);

    // ── Noise filter: ignore symbols with |change| below this % ───────────────
    private static final double NOISE_THRESHOLD_PCT = 0.10;

    // ── Minimum symbols for a reliable pressure reading ────────────────────────
    private static final int MIN_SYMBOLS_FOR_SIGNAL = 20;

    // ── Pressure dominance required to act (BUY_strength must be X% more than SELL) ──
    private static final double MIN_DOMINANCE_RATIO = 1.10; // 10% stronger to qualify

    // ── Redis key prefixes ─────────────────────────────────────────────────────
    private static final String KEY_OPEN     = "mkt:open:";
    private static final String KEY_LIVE     = "mkt:live:";
    private static final String KEY_PCT      = "mkt:pct:";
    private static final String KEY_BUY_STR  = "mkt:buy_strength";
    private static final String KEY_SELL_STR = "mkt:sell_strength";
    private static final String KEY_DIR      = "mkt:pressure_dir";
    private static final String KEY_RATIO    = "mkt:pressure_ratio";
    private static final String KEY_SYM_CNT  = "mkt:symbol_count";
    private static final String KEY_BUY_CNT  = "mkt:buy_count";
    private static final String KEY_SELL_CNT = "mkt:sell_count";

    @Autowired(required = false)
    private StringRedisTemplate redis;

    // ── In-memory hot-path state (Redis is async backup) ──────────────────────
    // symbol → 9:15 AM open price (immutable after OPEN_LOCK_BY)
    private final Map<String, Double> openPrices   = new ConcurrentHashMap<>();
    // symbol → latest tick price
    private final Map<String, Double> latestPrices = new ConcurrentHashMap<>();

    // ── Computed pressure state (updated every 60s by scheduler) ──────────────
    private volatile double  buyStrength    = 0.0;
    private volatile double  sellStrength   = 0.0;
    private volatile String  pressureDir    = "NEUTRAL";
    private volatile double  pressureRatio  = 1.0;
    private volatile int     symbolCount    = 0;
    private volatile int     buyCount       = 0;
    private volatile int     sellCount      = 0;
    private volatile boolean openLocked     = false;
    private volatile boolean marketActive   = false;

    // ── Stats ──────────────────────────────────────────────────────────────────
    private final AtomicInteger ticksProcessed = new AtomicInteger(0);

    // ══════════════════════════════════════════════════════════════════════════
    // TICK LISTENER — called for every live WebSocket tick
    // ══════════════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tickExecutor")
    public void onTick(TickReceivedEvent tick) {
        // Skip index tokens (Nifty=256265, BankNifty=260105, VIX=264969)
        long token = tick.getInstrumentToken();
        if (token == 256265L || token == 260105L || token == 264969L) return;

        String symbol = tick.getTradingSymbol();
        if (symbol == null || symbol.isBlank()) return;

        double ltp = tick.getLastTradedPrice().doubleValue();
        if (ltp <= 0) return;

        LocalTime now = LocalTime.now(IST);

        // ── Capture 9:15 AM open prices ────────────────────────────────────────
        // Window: 9:15 to 9:20 — first tick of each symbol sets the open
        if (!now.isBefore(MARKET_OPEN) && !now.isAfter(OPEN_LOCK_BY) && !openLocked) {
            openPrices.putIfAbsent(symbol, ltp);
            // Persist to Redis so restart preserves open prices
            persistOpenPriceAsync(symbol, ltp);
        }

        // ── Track market active state ───────────────────────────────────────────
        marketActive = !now.isBefore(MARKET_OPEN) && !now.isAfter(MARKET_CLOSE);

        // ── Update latest price (always) ───────────────────────────────────────
        latestPrices.put(symbol, ltp);
        ticksProcessed.incrementAndGet();

        // ── Compute % change and write to Redis (async for performance) ────────
        Double openPrice = openPrices.get(symbol);
        if (openPrice != null && openPrice > 0) {
            double pctChange = (ltp - openPrice) / openPrice * 100.0;
            persistLivePriceAsync(symbol, ltp, pctChange);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 1-MINUTE AGGREGATION SCHEDULER
    // Runs every 60 seconds during market hours
    // ══════════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 60_000)
    public void aggregatePressure() {
        LocalTime now = LocalTime.now(IST);

        // Lock open prices at 9:20 AM
        if (!openLocked && !now.isBefore(OPEN_LOCK_BY)) {
            openLocked = true;
            log.info("[PRESSURE] 🔒 Open prices locked at {} — {} symbols captured",
                    now, openPrices.size());
        }

        // Only aggregate during market hours
        if (now.isBefore(MARKET_OPEN) || now.isAfter(MARKET_CLOSE)) {
            pressureDir = "NEUTRAL";
            return;
        }

        if (openPrices.isEmpty()) {
            log.debug("[PRESSURE] No open prices yet — waiting for 9:15 AM ticks");
            return;
        }

        // ── Compute pressure from in-memory state ──────────────────────────────
        double totalBuy  = 0.0;
        double totalSell = 0.0;
        int    buyCnt    = 0;
        int    sellCnt   = 0;
        int    symCnt    = 0;

        for (Map.Entry<String, Double> entry : openPrices.entrySet()) {
            String symbol    = entry.getKey();
            Double openPrice = entry.getValue();
            Double livePrice = latestPrices.get(symbol);

            if (openPrice == null || openPrice <= 0 || livePrice == null || livePrice <= 0) continue;

            double pctChange = (livePrice - openPrice) / openPrice * 100.0;
            symCnt++;

            // Apply noise filter — ignore micro-movements
            if (Math.abs(pctChange) < NOISE_THRESHOLD_PCT) continue;

            if (pctChange > 0) {
                totalBuy += pctChange;
                buyCnt++;
            } else {
                totalSell += Math.abs(pctChange);
                sellCnt++;
            }
        }

        // ── Update state ───────────────────────────────────────────────────────
        buyStrength  = totalBuy;
        sellStrength = totalSell;
        symbolCount  = symCnt;
        buyCount     = buyCnt;
        sellCount    = sellCnt;

        // Compute ratio (avoid divide by zero)
        double ratio;
        if (totalSell == 0 && totalBuy == 0) {
            ratio = 1.0;
            pressureDir = "NEUTRAL";
        } else if (totalSell == 0) {
            ratio = Double.MAX_VALUE;
            pressureDir = "BUY";
        } else if (totalBuy == 0) {
            ratio = 0.0;
            pressureDir = "SELL";
        } else {
            ratio = totalBuy / totalSell;
            if (symCnt < MIN_SYMBOLS_FOR_SIGNAL) {
                pressureDir = "NEUTRAL"; // not enough data
            } else if (ratio >= MIN_DOMINANCE_RATIO) {
                pressureDir = "BUY";
            } else if (ratio <= (1.0 / MIN_DOMINANCE_RATIO)) {
                pressureDir = "SELL";
            } else {
                pressureDir = "NEUTRAL"; // too balanced — no strong side
            }
        }
        pressureRatio = ratio;

        log.info("[PRESSURE] {} | BUY_str={} ({} syms) | SELL_str={} ({} syms) | " +
                        "ratio={} | total_syms={} | ticks={}",
                pressureDir,
                String.format("%.2f", totalBuy),  buyCnt,
                String.format("%.2f", totalSell), sellCnt,
                String.format("%.3f", ratio),
                symCnt,
                ticksProcessed.get());

        // ── Persist aggregated state to Redis ──────────────────────────────────
        persistAggregatedStateAsync(totalBuy, totalSell, pressureDir, ratio, symCnt, buyCnt, sellCnt);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PUBLIC API — called by strategies and decision engine
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Current market pressure direction: "BUY", "SELL", or "NEUTRAL"
     */
    public String getPressureDirection() { return pressureDir; }

    /**
     * True if market pressure clearly favours BUY (buyStrength > sellStrength * ratio threshold)
     */
    public boolean isBuyPressure() { return "BUY".equals(pressureDir); }

    /**
     * True if market pressure clearly favours SELL
     */
    public boolean isSellPressure() { return "SELL".equals(pressureDir); }

    /**
     * True if pressure is strong enough to act (not neutral)
     */
    public boolean hasClearPressure() { return !"NEUTRAL".equals(pressureDir); }

    /** Raw BUY strength (sum of all positive % changes from open) */
    public double getBuyStrength()  { return buyStrength; }

    /** Raw SELL strength (sum of absolute negative % changes from open) */
    public double getSellStrength() { return sellStrength; }

    /** Ratio: buyStrength / sellStrength. >1.0 = buy dominates, <1.0 = sell dominates */
    public double getPressureRatio() { return pressureRatio; }

    /** How many symbols have open prices captured */
    public int getSymbolCount() { return symbolCount; }

    /** Symbols moving up */
    public int getBuyCount()    { return buyCount; }

    /** Symbols moving down */
    public int getSellCount()   { return sellCount; }

    /** True once 9:20 AM open prices are locked */
    public boolean isOpenLocked() { return openLocked; }

    /** Ticks processed since startup */
    public int getTicksProcessed() { return ticksProcessed.get(); }

    /**
     * % change for a specific symbol from 9:15 AM open.
     * Returns 0.0 if no open price captured yet.
     */
    public double getSymbolPctChange(String symbol) {
        Double open = openPrices.get(symbol);
        Double live = latestPrices.get(symbol);
        if (open == null || open <= 0 || live == null || live <= 0) return 0.0;
        return (live - open) / open * 100.0;
    }

    /**
     * Returns pressure state as a snapshot record for strategies to consume.
     */
    public PressureSnapshot getSnapshot() {
        return new PressureSnapshot(
                pressureDir,
                buyStrength,
                sellStrength,
                pressureRatio,
                symbolCount,
                buyCount,
                sellCount,
                openLocked,
                marketActive
        );
    }

    /**
     * PressureSnapshot — immutable value object consumed by strategies.
     */
    public record PressureSnapshot(
            String  direction,      // "BUY" | "SELL" | "NEUTRAL"
            double  buyStrength,
            double  sellStrength,
            double  ratio,          // buy/sell ratio
            int     totalSymbols,
            int     buySymbols,
            int     sellSymbols,
            boolean openLocked,     // true once 9:20 AM prices are locked
            boolean marketActive
    ) {
        public boolean isBuy()    { return "BUY".equals(direction); }
        public boolean isSell()   { return "SELL".equals(direction); }
        public boolean isNeutral(){ return "NEUTRAL".equals(direction); }

        /** True if pressure is actionable: open locked + enough symbols + clear direction */
        public boolean isActionable() {
            return openLocked && totalSymbols >= 20 && !isNeutral();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DAILY RESET
    // ══════════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyReset() {
        openPrices.clear();
        latestPrices.clear();
        buyStrength   = 0.0;
        sellStrength  = 0.0;
        pressureDir   = "NEUTRAL";
        pressureRatio = 1.0;
        symbolCount   = 0;
        buyCount      = 0;
        sellCount      = 0;
        openLocked    = false;
        marketActive  = false;
        ticksProcessed.set(0);

        // Clear Redis market pressure keys
        clearRedisStateAsync();

        log.info("[PRESSURE] Daily reset complete — ready for new session");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // REDIS HELPERS — all async, never block the tick thread
    // ══════════════════════════════════════════════════════════════════════════

    private void persistOpenPriceAsync(String symbol, double price) {
        if (redis == null) return;
        try {
            redis.opsForValue().set(KEY_OPEN + symbol,
                    String.valueOf(price), 26, TimeUnit.HOURS);
        } catch (Exception ignored) {}
    }

    private void persistLivePriceAsync(String symbol, double ltp, double pctChange) {
        if (redis == null) return;
        try {
            redis.opsForValue().set(KEY_LIVE + symbol, String.valueOf(ltp), 1, TimeUnit.HOURS);
            redis.opsForValue().set(KEY_PCT  + symbol, String.format("%.4f", pctChange), 1, TimeUnit.HOURS);
        } catch (Exception ignored) {}
    }

    private void persistAggregatedStateAsync(double buy, double sell, String dir,
                                             double ratio, int symCnt, int buyCnt, int sellCnt) {
        if (redis == null) return;
        try {
            redis.opsForValue().set(KEY_BUY_STR,  String.format("%.4f", buy),   5, TimeUnit.MINUTES);
            redis.opsForValue().set(KEY_SELL_STR, String.format("%.4f", sell),  5, TimeUnit.MINUTES);
            redis.opsForValue().set(KEY_DIR,       dir,                         5, TimeUnit.MINUTES);
            redis.opsForValue().set(KEY_RATIO,    String.format("%.4f", ratio), 5, TimeUnit.MINUTES);
            redis.opsForValue().set(KEY_SYM_CNT,  String.valueOf(symCnt),       5, TimeUnit.MINUTES);
            redis.opsForValue().set(KEY_BUY_CNT,  String.valueOf(buyCnt),       5, TimeUnit.MINUTES);
            redis.opsForValue().set(KEY_SELL_CNT, String.valueOf(sellCnt),      5, TimeUnit.MINUTES);
        } catch (Exception ignored) {}
    }

    private void clearRedisStateAsync() {
        if (redis == null) return;
        try {
            redis.delete(List.of(KEY_BUY_STR, KEY_SELL_STR, KEY_DIR,
                    KEY_RATIO, KEY_SYM_CNT, KEY_BUY_CNT, KEY_SELL_CNT));
        } catch (Exception ignored) {}
    }

    /**
     * Attempt to restore open prices from Redis on restart
     * (called by ParallelWarmupService if needed).
     */
    public void restoreOpenPricesFromRedis(Set<String> symbols) {
        if (redis == null || symbols == null) return;
        int restored = 0;
        for (String symbol : symbols) {
            try {
                String val = redis.opsForValue().get(KEY_OPEN + symbol);
                if (val != null && !val.isBlank()) {
                    openPrices.putIfAbsent(symbol, Double.parseDouble(val));
                    restored++;
                }
            } catch (Exception ignored) {}
        }
        if (restored > 0) {
            log.info("[PRESSURE] Restored {} open prices from Redis on restart", restored);
        }
    }
}