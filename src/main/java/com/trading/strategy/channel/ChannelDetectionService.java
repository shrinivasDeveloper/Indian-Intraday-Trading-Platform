package com.trading.strategy.channel;

import com.trading.domain.Candle;
import com.trading.events.CandleCompleteEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ChannelDetectionService — 5M Channel Structure Engine.
 *
 * FIXES vs previous version:
 *   FIX 1: Buffer 40 → 75 candles (6.25 hour full session coverage)
 *   FIX 2: Removed synchronized blocks (ConcurrentHashMap.compute is atomic)
 *   FIX 3: Channel age validation via sessionDate field
 *   FIX 4: 2-candle lookback swing detection (cleaner pivots, less noise)
 *   FIX 5: String.format guarded behind isDebugEnabled() (no alloc in hot path)
 */
@Service
@Slf4j
public class ChannelDetectionService {

    private static final ZoneId IST                 = ZoneId.of("Asia/Kolkata");
    private static final int    MAX_CANDLES         = 75;   // FIX 1: was 40
    private static final int    MIN_CANDLES         = 8;
    private static final double TOUCH_TOLERANCE_PCT = 0.003;

    // FIX 2: ConcurrentHashMap — no synchronized needed anywhere
    private final Map<String, Deque<Candle>>  buffers5m    = new ConcurrentHashMap<>();
    private final Map<String, ChannelResult>  channelCache = new ConcurrentHashMap<>();

    // ── Data structures ───────────────────────────────────────────────────

    public enum ChannelType    { BULLISH, BEARISH, SIDEWAYS, INSUFFICIENT_DATA }
    public enum ChannelValidity { HIGH_QUALITY, VALID, INVALID }

    public record TrendLine(
            double startPrice, double endPrice, double slope,
            int touches, int startIndex, int endIndex
    ) {
        public double priceAt(int idx) { return endPrice + slope * (endIndex - idx); }
    }

    /** FIX 3: sessionDate added so getChannel() rejects stale previous-day data */
    public record ChannelResult(
            String symbol, ChannelType type, ChannelValidity validity,
            TrendLine supportLine, TrendLine resistanceLine,
            double channelWidthPct, double supportPrice, double resistancePrice,
            double pullbackZoneTop, double pullbackZoneBottom,
            int candlesAnalyzed, String reason,
            LocalDate sessionDate, long lastUpdateEpoch
    ) {
        public boolean isValid() {
            return (validity == ChannelValidity.VALID || validity == ChannelValidity.HIGH_QUALITY)
                    && sessionDate != null
                    && sessionDate.equals(LocalDate.now(ZoneId.of("Asia/Kolkata")));
        }
        public boolean isHighQuality() { return validity == ChannelValidity.HIGH_QUALITY && isValid(); }
        public boolean isPriceInPullbackZone(double price) {
            return isValid() && price >= pullbackZoneBottom && price <= pullbackZoneTop;
        }
        public long ageInMinutes() {
            return (System.currentTimeMillis() - lastUpdateEpoch) / 60_000;
        }
    }

    // ── Event listener ────────────────────────────────────────────────────

    @EventListener
    @Async("tradingExecutor")
    public void onCandle(CandleCompleteEvent event) {
        Candle c = event.getCandle();
        if (!"5minute".equals(c.getTimeframe()) || !c.isComplete()) return;

        String sym = c.getTradingSymbol();

        // FIX 2: compute() is atomic on ConcurrentHashMap — no synchronized block needed
        buffers5m.compute(sym, (k, buf) -> {
            if (buf == null) buf = new ArrayDeque<>();
            buf.addFirst(c);
            while (buf.size() > MAX_CANDLES) ((ArrayDeque<Candle>) buf).removeLast();
            return buf;
        });

        Deque<Candle> buf = buffers5m.get(sym);
        if (buf == null) return;

        ChannelResult result = detectChannel(sym, new ArrayList<>(buf));
        channelCache.put(sym, result); // ConcurrentHashMap.put is thread-safe

        // FIX 5: guard String.format() behind log level check
        if (log.isDebugEnabled() && result.isValid()) {
            log.debug("[CHANNEL] {} → {} {} | sup={} res={} w={}% t={}+{}",
                    sym, result.type(), result.validity(),
                    String.format("%.2f", result.supportPrice()),
                    String.format("%.2f", result.resistancePrice()),
                    String.format("%.2f", result.channelWidthPct()),
                    result.supportLine() != null ? result.supportLine().touches() : 0,
                    result.resistanceLine() != null ? result.resistanceLine().touches() : 0);
        }
    }

    // ── Core detection ────────────────────────────────────────────────────

    private ChannelResult detectChannel(String symbol, List<Candle> candles) {
        if (candles.size() < MIN_CANDLES)
            return invalid(symbol, "Insufficient data: " + candles.size());

        List<SwingPoint> highs = detectSwingHighs(candles);
        List<SwingPoint> lows  = detectSwingLows(candles);

        if (highs.size() < 2 || lows.size() < 2)
            return invalid(symbol, "Not enough swings: H=" + highs.size() + " L=" + lows.size());

        ChannelType type = channelType(highs, lows);
        if (type == ChannelType.SIDEWAYS || type == ChannelType.INSUFFICIENT_DATA)
            return invalid(symbol, "No directional channel: " + type);

        TrendLine sup = fitSupport(lows);
        TrendLine res = fitResistance(highs);
        if (sup == null || res == null) return invalid(symbol, "Trendline fit failed");

        int st = countTouches(candles, sup, true);
        int rt = countTouches(candles, res, false);
        sup = new TrendLine(sup.startPrice(), sup.endPrice(), sup.slope(), st, sup.startIndex(), sup.endIndex());
        res = new TrendLine(res.startPrice(), res.endPrice(), res.slope(), rt, res.startIndex(), res.endIndex());

        int min = Math.min(st, rt);
        ChannelValidity validity = min >= 3 ? ChannelValidity.HIGH_QUALITY
                : min >= 2 ? ChannelValidity.VALID
                : null;
        if (validity == null) return invalid(symbol, "Touches too few: sup=" + st + " res=" + rt);

        double curSup = sup.priceAt(0);
        double curRes = res.priceAt(0);
        double width  = curRes - curSup;
        if (width <= 0) return invalid(symbol, "Negative channel width");

        double widthPct = width / curSup * 100;
        double pzTop, pzBot;
        if (type == ChannelType.BULLISH) { pzBot = curSup; pzTop = curSup + width * 0.30; }
        else                             { pzTop = curRes; pzBot = curRes - width * 0.30; }

        if (log.isDebugEnabled()) {
            log.debug("[CHANNEL] {} {} {} sup={} res={} w={}% pz=[{},{}]",
                    symbol, type, validity,
                    String.format("%.2f", curSup), String.format("%.2f", curRes),
                    String.format("%.2f", widthPct),
                    String.format("%.2f", pzBot), String.format("%.2f", pzTop));
        }

        return new ChannelResult(symbol, type, validity, sup, res, widthPct,
                curSup, curRes, pzTop, pzBot, candles.size(), "OK",
                LocalDate.now(IST), System.currentTimeMillis());
    }

    // ── Swing detection — FIX 4: 2-candle lookback each side ─────────────

    private record SwingPoint(int index, double price, boolean isHigh) {}

    private List<SwingPoint> detectSwingHighs(List<Candle> c) {
        List<SwingPoint> r = new ArrayList<>();
        for (int i = 2; i < c.size() - 2; i++) {
            double v = c.get(i).getHigh().doubleValue();
            if (v > c.get(i+1).getHigh().doubleValue() && v > c.get(i+2).getHigh().doubleValue()
                    && v > c.get(i-1).getHigh().doubleValue() && v > c.get(i-2).getHigh().doubleValue())
                r.add(new SwingPoint(i, v, true));
        }
        return r;
    }

    private List<SwingPoint> detectSwingLows(List<Candle> c) {
        List<SwingPoint> r = new ArrayList<>();
        for (int i = 2; i < c.size() - 2; i++) {
            double v = c.get(i).getLow().doubleValue();
            if (v < c.get(i+1).getLow().doubleValue() && v < c.get(i+2).getLow().doubleValue()
                    && v < c.get(i-1).getLow().doubleValue() && v < c.get(i-2).getLow().doubleValue())
                r.add(new SwingPoint(i, v, false));
        }
        return r;
    }

    private ChannelType channelType(List<SwingPoint> highs, List<SwingPoint> lows) {
        if (highs.size() < 2 || lows.size() < 2) return ChannelType.INSUFFICIENT_DATA;
        double rH = highs.get(0).price(), oH = highs.get(highs.size()-1).price();
        double rL = lows.get(0).price(),  oL = lows.get(lows.size()-1).price();
        if (rH > oH && rL > oL) return ChannelType.BULLISH;
        if (rH < oH && rL < oL) return ChannelType.BEARISH;
        return ChannelType.SIDEWAYS;
    }

    // ── Trendline fitting ─────────────────────────────────────────────────

    private TrendLine fitSupport(List<SwingPoint> lows) {
        if (lows.size() < 2) return null;
        SwingPoint n = lows.get(0), o = lows.get(lows.size()-1);
        int d = o.index() - n.index();
        if (d == 0) return null;
        return new TrendLine(o.price(), n.price(), (n.price()-o.price())/d, lows.size(), o.index(), n.index());
    }

    private TrendLine fitResistance(List<SwingPoint> highs) {
        if (highs.size() < 2) return null;
        SwingPoint n = highs.get(0), o = highs.get(highs.size()-1);
        int d = o.index() - n.index();
        if (d == 0) return null;
        return new TrendLine(o.price(), n.price(), (n.price()-o.price())/d, highs.size(), o.index(), n.index());
    }

    private int countTouches(List<Candle> candles, TrendLine line, boolean isSupport) {
        int t = 0;
        for (int i = 0; i < candles.size(); i++) {
            double lp  = line.priceAt(i);
            double tol = lp * TOUCH_TOLERANCE_PCT;
            double cp  = isSupport ? candles.get(i).getLow().doubleValue()
                    : candles.get(i).getHigh().doubleValue();
            if (Math.abs(cp - lp) <= tol) t++;
        }
        return t;
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * FIX 3: Rejects stale previous-session channels.
     */
    public ChannelResult getChannel(String symbol) {
        ChannelResult r = channelCache.get(symbol);
        if (r == null) return invalid(symbol, "No data yet");
        if (r.sessionDate() != null && !r.sessionDate().equals(LocalDate.now(IST)))
            return invalid(symbol, "Stale channel from previous session");
        return r;
    }

    public boolean hasValidChannel(String symbol) {
        ChannelResult r = channelCache.get(symbol);
        return r != null && r.isValid();
    }

    public void updateChannel(String symbol, List<Candle> candles5m) {
        if (candles5m == null || candles5m.isEmpty()) return;
        buffers5m.compute(symbol, (k, existing) -> {
            Deque<Candle> newBuf = new ArrayDeque<>();
            for (Candle c : candles5m) {
                newBuf.addFirst(c);
                while (newBuf.size() > MAX_CANDLES) ((ArrayDeque<Candle>) newBuf).removeLast();
            }
            return newBuf;
        });
        Deque<Candle> buf = buffers5m.get(symbol);
        if (buf != null) channelCache.put(symbol, detectChannel(symbol, new ArrayList<>(buf)));
    }

    public Map<String, ChannelResult> getAllValidChannels() {
        Map<String, ChannelResult> valid = new LinkedHashMap<>();
        channelCache.forEach((k, v) -> { if (v.isValid()) valid.put(k, v); });
        return valid;
    }

    public int getTrackedSymbolCount() { return buffers5m.size(); }
    public int getValidChannelCount()  {
        return (int) channelCache.values().stream().filter(ChannelResult::isValid).count();
    }

    @Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyReset() {
        buffers5m.clear();
        channelCache.clear();
        log.info("[CHANNEL] Daily reset — 75-candle buffers cleared");
    }

    private ChannelResult invalid(String symbol, String reason) {
        return new ChannelResult(symbol, ChannelType.INSUFFICIENT_DATA,
                ChannelValidity.INVALID, null, null,
                0, 0, 0, 0, 0, 0, reason, LocalDate.now(IST), System.currentTimeMillis());
    }
}