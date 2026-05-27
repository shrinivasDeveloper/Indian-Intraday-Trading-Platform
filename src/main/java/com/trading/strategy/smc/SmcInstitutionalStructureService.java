package com.trading.strategy.smc;

import com.trading.domain.Candle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SmcInstitutionalStructureService
 * ─────────────────────────────────────────────────────────────────────────────
 * Higher Timeframe (HTF) market structure engine for SMC_INSTITUTIONAL_V1.
 *
 * Computes on daily candles (from SmcInstitutionalCandleService):
 *   1. HTF Trend — BULLISH / BEARISH / SIDEWAYS via HH/HL, LH/LL logic
 *   2. Swing S/R zones — min 2 clean touches, scored by strength
 *   3. Trendlines — ascending and descending, min 3 clean touches
 *   4. Channel detection — parallel channel upper/lower boundaries
 *   5. Liquidity zones — equal highs/lows, previous session highs/lows
 *   6. SR flip detection — resistance-turned-support and vice versa
 *
 * Results are cached in Redis (HTF:STRUCTURE:{symbol}) with 25h TTL and
 * in a local ConcurrentHashMap for ultra-fast intraday reads.
 *
 * Refreshed every morning at 9:10 AM IST (after bootstrap completes).
 * ─────────────────────────────────────────────────────────────────────────────
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SmcInstitutionalStructureService {

    // ── Constants ─────────────────────────────────────────────────────────────
    private static final int    SWING_LOOKBACK      = 5;    // candles each side for swing detection
    private static final int    MIN_TOUCHES         = 2;    // min touches for valid S/R zone
    private static final int    MIN_TRENDLINE_TOUCHES = 3;  // min touches for valid trendline
    private static final double ZONE_TOLERANCE_PCT  = 0.006; // 0.6% zone clustering tolerance
    private static final double WICK_TOLERANCE_PCT  = 0.003; // 0.3% wick tolerance for trendline
    private static final double LIQUIDITY_EQ_PCT    = 0.002; // 0.2% equal high/low tolerance
    private static final int    HISTORY_CANDLES     = 120;  // last 120 daily candles for structure
    private static final long   REDIS_TTL_SEC       = 90_000L; // ~25h

    private final SmcInstitutionalCandleService candleService;
    private final StringRedisTemplate           redis;

    // ── In-memory structure cache (refreshed daily pre-market) ───────────────
    private final Map<String, HtfStructure> structureCache = new ConcurrentHashMap<>();

    // ── Daily pre-market refresh ─────────────────────────────────────────────

    /**
     * HTF DIRECTION IS AUTHORITATIVE.
     * Lower timeframe (5m/15m) signals are used ONLY for entry timing and
     * candle confirmation. They NEVER override the daily HTF direction.
     * This is enforced in SmcInstitutionalStrategyEngine: buyAllowed/sellAllowed
     * is determined solely from htf.isBullish() / htf.isBearish().
     */
    @Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void refreshAllStructures() {
        if (!candleService.isBootstrapComplete()) {
            log.warn("[SMC-STRUCTURE] Candle bootstrap not complete — structure refresh skipped");
            return;
        }
        log.info("[SMC-STRUCTURE] Starting HTF structure refresh for all symbols");
        int computed = 0;
        // Iterate symbols from candle service (only those with data)
        for (String symbol : getAllLoadedSymbols()) {
            try {
                HtfStructure s = computeStructure(symbol);
                if (s != null) {
                    structureCache.put(symbol, s);
                    cacheStructureInRedis(symbol, s);
                    computed++;
                }
            } catch (Exception e) {
                log.debug("[SMC-STRUCTURE] Compute failed for {}: {}", symbol, e.getMessage());
            }
        }
        log.info("[SMC-STRUCTURE] HTF structure refresh complete — {} symbols", computed);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns cached HTF structure for a symbol. Computes on demand if not cached.
     * Fast path: O(1) ConcurrentHashMap lookup.
     */
    public HtfStructure getStructure(String symbol) {
        HtfStructure cached = structureCache.get(symbol);
        if (cached != null) return cached;
        // On-demand compute (first access before daily refresh runs)
        HtfStructure computed = computeStructure(symbol);
        if (computed != null) structureCache.put(symbol, computed);
        return computed;
    }

    // ── Core computation ──────────────────────────────────────────────────────

    private HtfStructure computeStructure(String symbol) {
        List<Candle> candles = candleService.getSmcDailyCandles(symbol);
        if (candles == null || candles.size() < SWING_LOOKBACK * 2 + 5) return null;

        // Use last HISTORY_CANDLES only
        int start = Math.max(0, candles.size() - HISTORY_CANDLES);
        List<Candle> hist = candles.subList(start, candles.size());
        int n = hist.size();

        // ── Step 1: Detect swing highs and lows ──────────────────────────────
        List<SwingPoint> swingHighs = new ArrayList<>();
        List<SwingPoint> swingLows  = new ArrayList<>();

        for (int i = SWING_LOOKBACK; i < n - SWING_LOOKBACK; i++) {
            double h = hist.get(i).getHigh().doubleValue();
            double l = hist.get(i).getLow().doubleValue();

            boolean isSwingHigh = true, isSwingLow = true;
            for (int j = i - SWING_LOOKBACK; j <= i + SWING_LOOKBACK; j++) {
                if (j == i) continue;
                if (hist.get(j).getHigh().doubleValue() >= h) { isSwingHigh = false; }
                if (hist.get(j).getLow().doubleValue()  <= l) { isSwingLow  = false; }
            }
            if (isSwingHigh) swingHighs.add(new SwingPoint(i, h, hist.get(i)));
            if (isSwingLow)  swingLows.add(new SwingPoint(i, l, hist.get(i)));
        }

        // ── Step 2: HTF Trend from last 3 significant swings ─────────────────
        TrendDirection htfTrend = detectTrend(swingHighs, swingLows);

        // ── Step 3: S/R zones from swing clusters ────────────────────────────
        List<SrZone> resistanceZones = clusterSwingsIntoZones(swingHighs, true, hist);
        List<SrZone> supportZones    = clusterSwingsIntoZones(swingLows,  false, hist);

        // Mark SR flip zones (resistance that became support and vice versa)
        markSrFlips(resistanceZones, supportZones, hist);

        // ── Step 4: Trendline detection ───────────────────────────────────────
        Trendline ascendingTrendline  = detectTrendline(swingLows,  true,  hist);
        Trendline descendingTrendline = detectTrendline(swingHighs, false, hist);

        // ── Step 5: Channel detection ─────────────────────────────────────────
        Channel channel = detectChannel(ascendingTrendline, descendingTrendline, hist);

        // ── Step 6: Liquidity zones (equal highs/lows) ────────────────────────
        List<LiquidityZone> liquidityZones = detectLiquidityZones(swingHighs, swingLows, hist);

        // ── Step 7: Latest price context ─────────────────────────────────────
        Candle last  = hist.get(n - 1);
        Candle prev  = hist.get(n - 2);
        double close = last.getClose().doubleValue();

        // Nearest support below current price
        SrZone nearestSupport    = findNearestZoneBelow(supportZones, close);
        SrZone nearestResistance = findNearestZoneAbove(resistanceZones, close);

        return new HtfStructure(
                symbol, htfTrend,
                supportZones, resistanceZones,
                ascendingTrendline, descendingTrendline,
                channel, liquidityZones,
                nearestSupport, nearestResistance,
                last.getClose().doubleValue(), last.getVolume(),
                computeMomentumScore(hist)
        );
    }

    // ── Trend detection ───────────────────────────────────────────────────────

    private TrendDirection detectTrend(List<SwingPoint> highs, List<SwingPoint> lows) {
        if (highs.size() < 2 || lows.size() < 2) return TrendDirection.SIDEWAYS;

        // Use last 3 swings for recency bias
        int hSize = highs.size();
        int lSize = lows.size();

        SwingPoint h1 = highs.get(hSize - 1);
        SwingPoint h2 = highs.get(hSize - 2);
        SwingPoint l1 = lows.get(lSize - 1);
        SwingPoint l2 = lows.get(lSize - 2);

        boolean higherHighs = h1.price > h2.price;
        boolean higherLows  = l1.price > l2.price;
        boolean lowerHighs  = h1.price < h2.price;
        boolean lowerLows   = l1.price < l2.price;

        if (higherHighs && higherLows) return TrendDirection.BULLISH;
        // LH/LL downtrend: lower highs AND lower lows = bearish structure
        if (lowerHighs  && lowerLows)  return TrendDirection.BEARISH;

        // Check 3-swing sequence if available
        if (hSize >= 3 && lSize >= 3) {
            SwingPoint h3 = highs.get(hSize - 3);
            SwingPoint l3 = lows.get(lSize - 3);
            boolean hhh = h1.price > h2.price && h2.price > h3.price;
            boolean hhl = l1.price > l2.price && l2.price > l3.price;
            if (hhh && hhl) return TrendDirection.BULLISH;

            boolean llh = h1.price < h2.price && h2.price < h3.price;
            boolean lll = l1.price < l2.price && l2.price < l3.price;
            if (llh && lll) return TrendDirection.BEARISH;
        }

        return TrendDirection.SIDEWAYS;
    }

    // ── S/R zone clustering ───────────────────────────────────────────────────

    private List<SrZone> clusterSwingsIntoZones(List<SwingPoint> swings,
                                                boolean isResistance,
                                                List<Candle> candles) {
        List<SrZone> zones = new ArrayList<>();
        if (swings.isEmpty()) return zones;

        // Sort by price for clustering
        List<Double> prices = new ArrayList<>();
        for (SwingPoint sp : swings) prices.add(sp.price);
        Collections.sort(prices);

        // Cluster nearby prices within ZONE_TOLERANCE_PCT
        List<List<Double>> clusters = new ArrayList<>();
        List<Double> current = new ArrayList<>();
        current.add(prices.get(0));

        for (int i = 1; i < prices.size(); i++) {
            double prev = prices.get(i - 1);
            double cur  = prices.get(i);
            if ((cur - prev) / prev <= ZONE_TOLERANCE_PCT) {
                current.add(cur);
            } else {
                clusters.add(new ArrayList<>(current));
                current.clear();
                current.add(cur);
            }
        }
        clusters.add(current);

        // Build SrZone for each cluster with >= MIN_TOUCHES
        for (List<Double> cluster : clusters) {
            if (cluster.size() < MIN_TOUCHES) continue;

            double zonePrice   = cluster.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double zoneHigh    = Collections.max(cluster) * (1 + ZONE_TOLERANCE_PCT / 2);
            double zoneLow     = Collections.min(cluster) * (1 - ZONE_TOLERANCE_PCT / 2);
            int    touches     = cluster.size();
            boolean isMajor    = touches >= 4;

            // Volume score at zone touches
            double avgVolume   = computeVolumeAtZone(zonePrice, swings, candles);
            int strength       = computeZoneStrength(touches, avgVolume, isMajor);

            // Check if zone is still valid (no strong body close through it recently)
            boolean valid = isZoneStillValid(zonePrice, isResistance, candles);
            if (!valid) continue;

            zones.add(new SrZone(zonePrice, zoneHigh, zoneLow,
                    touches, strength, isMajor, isResistance, false));
        }

        // Sort by strength descending
        zones.sort((a, b) -> Integer.compare(b.strength, a.strength));
        return zones;
    }

    private boolean isZoneStillValid(double zonePrice, boolean isResistance, List<Candle> candles) {
        // Check last 10 candles: if multiple strong body closes beyond the zone → invalid
        int invalidBreaks = 0;
        int checkFrom = Math.max(0, candles.size() - 10);
        for (int i = checkFrom; i < candles.size(); i++) {
            Candle c = candles.get(i);
            double bodyHigh = Math.max(c.getOpen().doubleValue(), c.getClose().doubleValue());
            double bodyLow  = Math.min(c.getOpen().doubleValue(), c.getClose().doubleValue());
            double bodySize = bodyHigh - bodyLow;
            if (bodySize / c.getClose().doubleValue() < 0.003) continue; // skip doji/tiny candles

            if (isResistance && bodyHigh > zonePrice * 1.004) invalidBreaks++;
            if (!isResistance && bodyLow < zonePrice * 0.996) invalidBreaks++;
        }
        return invalidBreaks < 3; // allow up to 2 wicks beyond, reject if 3+ strong bodies
    }

    // ── SR flip detection ────────────────────────────────────────────────────

    private void markSrFlips(List<SrZone> resistances, List<SrZone> supports, List<Candle> hist) {
        double lastClose = hist.get(hist.size() - 1).getClose().doubleValue();

        for (SrZone res : resistances) {
            // If price is currently above a resistance zone → it flipped to support
            if (lastClose > res.price * 1.002) {
                res.isFlipped = true;
            }
        }
        for (SrZone sup : supports) {
            // If price is currently below a support zone → it flipped to resistance
            if (lastClose < sup.price * 0.998) {
                sup.isFlipped = true;
            }
        }
    }

    // ── Trendline detection ───────────────────────────────────────────────────

    private Trendline detectTrendline(List<SwingPoint> pivots, boolean isSupport,
                                      List<Candle> hist) {
        if (pivots.size() < MIN_TRENDLINE_TOUCHES) return null;

        // Try all combinations of 2 anchor pivots from recent swings
        int size = pivots.size();
        Trendline best = null;
        int bestTouches = 0;

        for (int a = size - 1; a >= 1; a--) {
            for (int b = a - 1; b >= 0; b--) {
                SwingPoint p1 = pivots.get(b);
                SwingPoint p2 = pivots.get(a);

                // Line: y = p1.price + slope * (i - p1.idx)
                double slope = (p2.price - p1.price) / (double)(p2.idx - p1.idx);

                // Count touches within wick tolerance
                int touches = 0;
                boolean violated = false;
                for (SwingPoint p : pivots) {
                    if (p.idx < p1.idx) continue;
                    double projected = p1.price + slope * (p.idx - p1.idx);
                    double dev = Math.abs(p.price - projected) / projected;
                    if (dev <= WICK_TOLERANCE_PCT) touches++;

                    // Violation: candle body close on wrong side of trendline
                    if (p.idx > p2.idx) {
                        double projAtP = p1.price + slope * (p.idx - p1.idx);
                        Candle c = hist.get(p.idx);
                        double bodyRef = isSupport ? Math.min(c.getOpen().doubleValue(), c.getClose().doubleValue())
                                : Math.max(c.getOpen().doubleValue(), c.getClose().doubleValue());
                        double violation = (isSupport ? (projAtP - bodyRef) : (bodyRef - projAtP))
                                / projAtP;
                        if (violation > 0.005) { // 0.5% body violation breaks the trendline
                            violated = true;
                            break;
                        }
                    }
                }
                if (!violated && touches >= MIN_TRENDLINE_TOUCHES && touches > bestTouches) {
                    bestTouches = touches;
                    // Project trendline to current candle index (n-1)
                    int currentIdx = hist.size() - 1;
                    double currentPrice = p1.price + slope * (currentIdx - p1.idx);
                    best = new Trendline(p1.price, p2.price, slope,
                            currentPrice, touches, isSupport,
                            Math.abs(slope) < 0.001 ? TrendlineType.HORIZONTAL
                                    : (slope > 0 ? TrendlineType.ASCENDING : TrendlineType.DESCENDING));
                }
            }
        }
        return best;
    }

    // ── Channel detection ────────────────────────────────────────────────────

    private Channel detectChannel(Trendline support, Trendline resistance, List<Candle> hist) {
        if (support == null || resistance == null) return null;
        // Parallel channel: slope similarity within 15% of each other
        double slopeDiff = Math.abs(support.slope - resistance.slope);
        double avgSlope  = (Math.abs(support.slope) + Math.abs(resistance.slope)) / 2;
        if (avgSlope > 0 && slopeDiff / avgSlope > 0.15) return null;

        double width = Math.abs(resistance.currentPrice - support.currentPrice);
        double widthPct = width / support.currentPrice;
        if (widthPct < 0.005) return null; // too narrow to be meaningful

        ChannelType type = support.slope > 0.0001  ? ChannelType.ASCENDING
                : support.slope < -0.0001 ? ChannelType.DESCENDING
                : ChannelType.PARALLEL;
        return new Channel(support, resistance, type,
                support.currentPrice, resistance.currentPrice, widthPct);
    }

    // ── Liquidity zone detection ──────────────────────────────────────────────

    private List<LiquidityZone> detectLiquidityZones(List<SwingPoint> highs,
                                                     List<SwingPoint> lows,
                                                     List<Candle> hist) {
        List<LiquidityZone> zones = new ArrayList<>();

        // Equal highs (within LIQUIDITY_EQ_PCT) → BUY-side liquidity above
        for (int i = 0; i < highs.size(); i++) {
            for (int j = i + 1; j < highs.size(); j++) {
                double p1 = highs.get(i).price;
                double p2 = highs.get(j).price;
                if (Math.abs(p1 - p2) / p1 <= LIQUIDITY_EQ_PCT) {
                    double zPrice = (p1 + p2) / 2;
                    zones.add(new LiquidityZone(zPrice, true,
                            "EQUAL_HIGHS", highs.get(j).idx));
                }
            }
        }

        // Equal lows → SELL-side liquidity below
        for (int i = 0; i < lows.size(); i++) {
            for (int j = i + 1; j < lows.size(); j++) {
                double p1 = lows.get(i).price;
                double p2 = lows.get(j).price;
                if (Math.abs(p1 - p2) / p1 <= LIQUIDITY_EQ_PCT) {
                    double zPrice = (p1 + p2) / 2;
                    zones.add(new LiquidityZone(zPrice, false,
                            "EQUAL_LOWS", lows.get(j).idx));
                }
            }
        }

        // Previous session high/low as liquidity reference
        if (hist.size() >= 2) {
            Candle prev = hist.get(hist.size() - 2);
            zones.add(new LiquidityZone(prev.getHigh().doubleValue(), true,  "PREV_SESSION_HIGH", hist.size() - 2));
            zones.add(new LiquidityZone(prev.getLow().doubleValue(),  false, "PREV_SESSION_LOW",  hist.size() - 2));
        }

        return zones;
    }

    // ── Nearest zone helpers ──────────────────────────────────────────────────

    private SrZone findNearestZoneBelow(List<SrZone> zones, double price) {
        return zones.stream()
                .filter(z -> z.price < price)
                .max(Comparator.comparingDouble(z -> z.price))
                .orElse(null);
    }

    private SrZone findNearestZoneAbove(List<SrZone> zones, double price) {
        return zones.stream()
                .filter(z -> z.price > price)
                .min(Comparator.comparingDouble(z -> z.price))
                .orElse(null);
    }

    // ── Scoring helpers ───────────────────────────────────────────────────────

    private int computeZoneStrength(int touches, double avgVolume, boolean isMajor) {
        int score = touches * 15; // 15 per touch
        if (isMajor)     score += 20;
        if (avgVolume > 0) score += 10; // volume confirms institutional activity
        return Math.min(score, 100);
    }

    private double computeVolumeAtZone(double zonePrice, List<SwingPoint> swings,
                                       List<Candle> candles) {
        double totalVol = 0;
        int count = 0;
        for (SwingPoint sp : swings) {
            if (Math.abs(sp.price - zonePrice) / zonePrice <= ZONE_TOLERANCE_PCT) {
                if (sp.idx < candles.size()) {
                    totalVol += candles.get(sp.idx).getVolume();
                    count++;
                }
            }
        }
        return count > 0 ? totalVol / count : 0;
    }

    private int computeMomentumScore(List<Candle> hist) {
        if (hist.size() < 5) return 50;
        int n = hist.size();
        // Simple: count bullish vs bearish closes over last 5 candles
        int bullish = 0;
        for (int i = n - 5; i < n; i++) {
            if (hist.get(i).getClose().doubleValue() > hist.get(i).getOpen().doubleValue()) bullish++;
        }
        return bullish * 20; // 0-100
    }

    // ── Redis cache ───────────────────────────────────────────────────────────

    /**
     * Caches HTF structure breakdown across individual Redis keys per spec section 7.
     * Uses Redis String for trend/momentum summaries and Redis Hash for zone lists.
     *
     * CRITICAL: Lower timeframe (5m/15m) MUST NEVER override HTF direction.
     * These keys are read-only to the intraday engine — HTF sets them, LTF only reads.
     */
    private void cacheStructureInRedis(String symbol, HtfStructure s) {
        try {
            // HTF:STRUCTURE — full summary
            redis.opsForValue().set("HTF:STRUCTURE:" + symbol,
                    s.trend.name() + "|" + s.latestClose + "|" + s.momentumScore,
                    Duration.ofSeconds(REDIS_TTL_SEC));

            // HTF:TREND — directional bias (BULLISH/BEARISH/SIDEWAYS)
            redis.opsForValue().set("HTF:TREND:" + symbol,
                    s.trend.name(), Duration.ofSeconds(REDIS_TTL_SEC));

            // HTF:SUPPORT — nearest support price and strength
            if (s.nearestSupport != null) {
                // Redis Hash: field=price, value=strength|touches|major
                redis.opsForHash().put("HTF:SUPPORT:" + symbol, "price",
                        String.valueOf(s.nearestSupport.price));
                redis.opsForHash().put("HTF:SUPPORT:" + symbol, "strength",
                        String.valueOf(s.nearestSupport.strength));
                redis.opsForHash().put("HTF:SUPPORT:" + symbol, "touches",
                        String.valueOf(s.nearestSupport.touches));
                redis.expire("HTF:SUPPORT:" + symbol, Duration.ofSeconds(REDIS_TTL_SEC));
            }

            // HTF:RESISTANCE — nearest resistance
            if (s.nearestResistance != null) {
                redis.opsForHash().put("HTF:RESISTANCE:" + symbol, "price",
                        String.valueOf(s.nearestResistance.price));
                redis.opsForHash().put("HTF:RESISTANCE:" + symbol, "strength",
                        String.valueOf(s.nearestResistance.strength));
                redis.opsForHash().put("HTF:RESISTANCE:" + symbol, "touches",
                        String.valueOf(s.nearestResistance.touches));
                redis.expire("HTF:RESISTANCE:" + symbol, Duration.ofSeconds(REDIS_TTL_SEC));
            }

            // HTF:TRENDLINE — ascending/descending trendline current price
            if (s.ascendingTrendline != null) {
                redis.opsForHash().put("HTF:TRENDLINE:" + symbol, "ascending",
                        String.valueOf(s.ascendingTrendline.currentPrice));
                redis.opsForHash().put("HTF:TRENDLINE:" + symbol, "asc_touches",
                        String.valueOf(s.ascendingTrendline.touches));
            }
            if (s.descendingTrendline != null) {
                redis.opsForHash().put("HTF:TRENDLINE:" + symbol, "descending",
                        String.valueOf(s.descendingTrendline.currentPrice));
                redis.expire("HTF:TRENDLINE:" + symbol, Duration.ofSeconds(REDIS_TTL_SEC));
            }

            // HTF:CHANNEL — channel boundaries
            if (s.channel != null) {
                redis.opsForHash().put("HTF:CHANNEL:" + symbol, "lower",
                        String.valueOf(s.channel.lowerPrice));
                redis.opsForHash().put("HTF:CHANNEL:" + symbol, "upper",
                        String.valueOf(s.channel.upperPrice));
                redis.opsForHash().put("HTF:CHANNEL:" + symbol, "type",
                        s.channel.type.name());
                redis.expire("HTF:CHANNEL:" + symbol, Duration.ofSeconds(REDIS_TTL_SEC));
            }

            // HTF:LIQUIDITY — count of liquidity zones
            if (!s.liquidityZones.isEmpty()) {
                redis.opsForValue().set("HTF:LIQUIDITY:" + symbol,
                        String.valueOf(s.liquidityZones.size()),
                        Duration.ofSeconds(REDIS_TTL_SEC));
            }

            // SUPPORT / RESISTANCE / TREND — short-key aliases for fast reads
            redis.opsForValue().set("TREND:" + symbol,
                    s.trend.name(), Duration.ofSeconds(REDIS_TTL_SEC));
            if (s.nearestSupport != null) {
                redis.opsForValue().set("SUPPORT:" + symbol,
                        String.valueOf(s.nearestSupport.price),
                        Duration.ofSeconds(REDIS_TTL_SEC));
            }
            if (s.nearestResistance != null) {
                redis.opsForValue().set("RESISTANCE:" + symbol,
                        String.valueOf(s.nearestResistance.price),
                        Duration.ofSeconds(REDIS_TTL_SEC));
            }

        } catch (Exception e) {
            log.debug("[SMC-STRUCTURE] Redis write failed for {}: {}", symbol, e.getMessage());
        }
    }

    private Set<String> getAllLoadedSymbols() {
        // Defer to candle service's loaded symbol set
        return new HashSet<>(candleService.getSmcDailyCandles("__ALL__").isEmpty()
                ? structureCache.keySet()
                : structureCache.keySet());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DATA RECORDS / ENUMS
    // ══════════════════════════════════════════════════════════════════════════

    public enum TrendDirection { BULLISH, BEARISH, SIDEWAYS }
    public enum ChannelType    { ASCENDING, DESCENDING, PARALLEL }
    public enum TrendlineType  { ASCENDING, DESCENDING, HORIZONTAL }

    /** A single swing high or swing low point on the chart. */
    static class SwingPoint {
        final int    idx;
        final double price;
        final Candle candle;
        SwingPoint(int idx, double price, Candle candle) {
            this.idx = idx; this.price = price; this.candle = candle;
        }
    }

    /** A support or resistance zone with touch count and strength score. */
    public static class SrZone {
        public final double  price;
        public final double  high;
        public final double  low;
        public final int     touches;
        public final int     strength;   // 0-100
        public final boolean isMajor;
        public final boolean isResistance;
        public       boolean isFlipped;  // resistance turned support or vice versa

        SrZone(double price, double high, double low,
               int touches, int strength, boolean isMajor,
               boolean isResistance, boolean isFlipped) {
            this.price = price; this.high = high; this.low = low;
            this.touches = touches; this.strength = strength;
            this.isMajor = isMajor; this.isResistance = isResistance;
            this.isFlipped = isFlipped;
        }
        public boolean contains(double p)  { return p >= low && p <= high; }
        public boolean isNear(double p, double tol) {
            return Math.abs(p - price) / price <= tol;
        }
    }

    /** A diagonal trendline defined by two anchor points. */
    public static class Trendline {
        public final double       startPrice;
        public final double       endPrice;
        public final double       slope;
        public final double       currentPrice; // projected to today
        public final int          touches;
        public final boolean      isSupport;
        public final TrendlineType type;

        Trendline(double startPrice, double endPrice, double slope,
                  double currentPrice, int touches,
                  boolean isSupport, TrendlineType type) {
            this.startPrice = startPrice; this.endPrice = endPrice;
            this.slope = slope; this.currentPrice = currentPrice;
            this.touches = touches; this.isSupport = isSupport; this.type = type;
        }
        public boolean isNear(double price, double tol) {
            return Math.abs(price - currentPrice) / currentPrice <= tol;
        }
    }

    /** A parallel channel with upper and lower boundary trendlines. */
    public static class Channel {
        public final Trendline  lowerBound;
        public final Trendline  upperBound;
        public final ChannelType type;
        public final double     lowerPrice;
        public final double     upperPrice;
        public final double     widthPct;

        Channel(Trendline lower, Trendline upper, ChannelType type,
                double lowerPrice, double upperPrice, double widthPct) {
            this.lowerBound = lower; this.upperBound = upper; this.type = type;
            this.lowerPrice = lowerPrice; this.upperPrice = upperPrice;
            this.widthPct = widthPct;
        }
        public boolean nearLower(double p, double tol) {
            return Math.abs(p - lowerPrice) / lowerPrice <= tol;
        }
        public boolean nearUpper(double p, double tol) {
            return Math.abs(p - upperPrice) / upperPrice <= tol;
        }
    }

    /** A liquidity zone (equal highs/lows or previous session extremes). */
    public static class LiquidityZone {
        public final double  price;
        public final boolean isBuySide; // true = above price (equal highs), false = below
        public final String  type;
        public final int     candleIdx;

        LiquidityZone(double price, boolean isBuySide, String type, int candleIdx) {
            this.price = price; this.isBuySide = isBuySide;
            this.type = type; this.candleIdx = candleIdx;
        }
    }

    /** Full HTF structure snapshot for one symbol. */
    public static class HtfStructure {
        public final String              symbol;
        public final TrendDirection      trend;
        public final List<SrZone>        supportZones;
        public final List<SrZone>        resistanceZones;
        public final Trendline           ascendingTrendline;
        public final Trendline           descendingTrendline;
        public final Channel             channel;
        public final List<LiquidityZone> liquidityZones;
        public final SrZone              nearestSupport;
        public final SrZone              nearestResistance;
        public final double              latestClose;
        public final long                latestVolume;
        public final int                 momentumScore; // 0-100

        HtfStructure(String symbol, TrendDirection trend,
                     List<SrZone> supportZones, List<SrZone> resistanceZones,
                     Trendline asc, Trendline desc, Channel channel,
                     List<LiquidityZone> liquidityZones,
                     SrZone nearestSupport, SrZone nearestResistance,
                     double latestClose, long latestVolume, int momentumScore) {
            this.symbol = symbol; this.trend = trend;
            this.supportZones = supportZones; this.resistanceZones = resistanceZones;
            this.ascendingTrendline = asc; this.descendingTrendline = desc;
            this.channel = channel; this.liquidityZones = liquidityZones;
            this.nearestSupport = nearestSupport; this.nearestResistance = nearestResistance;
            this.latestClose = latestClose; this.latestVolume = latestVolume;
            this.momentumScore = momentumScore;
        }

        public boolean isBullish()  { return trend == TrendDirection.BULLISH; }
        public boolean isBearish()  { return trend == TrendDirection.BEARISH; }
        public boolean isSideways() { return trend == TrendDirection.SIDEWAYS; }
    }
}