package com.trading.strategy.channel;

import com.trading.domain.Candle;
import com.trading.events.CandleCompleteEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ChannelDetectionService — 5M Channel Structure Engine.
 *
 * CHANNEL RULES (per SmartChannelPullbackStrategy_v2 spec):
 *   Bullish channel → Higher Highs + Higher Lows
 *   Bearish channel → Lower Highs + Lower Lows
 *
 * VALIDATION:
 *   ≥ 2 touches (support + resistance) → VALID
 *   ≥ 3 touches                        → HIGH_QUALITY
 *   < 2 touches                        → INVALID
 *
 * CHANNEL COMPONENTS:
 *   → Support trendline
 *   → Resistance trendline
 *   → Pullback zone
 *
 * BUFFER MANAGEMENT:
 *   Keeps last 40 5M candles per symbol (covers ~3.5 hours of session).
 *   Resets daily at 9:10 IST.
 *
 * LOGGING:
 *   [INFO]  Channel detection started / completed
 *   [DEBUG] Swing point detection, touch counting
 *   [TRACE] Per-candle analysis
 *   [WARN]  Insufficient data, invalid channel
 */
@Service
@Slf4j
public class ChannelDetectionService {

    private static final ZoneId IST         = ZoneId.of("Asia/Kolkata");
    private static final int    MAX_CANDLES = 40;    // last 40 x 5M = ~3.5 hrs
    private static final int    MIN_CANDLES = 6;     // minimum for channel detection
    private static final double TOUCH_TOLERANCE_PCT = 0.003; // 0.3% tolerance for touch

    // ── Per-symbol 5M candle buffer ────────────────────────────────────────
    private final Map<String, Deque<Candle>> buffers5m = new ConcurrentHashMap<>();

    // ── Cached channel results (updated on each new 5M candle) ────────────
    private final Map<String, ChannelResult> channelCache = new ConcurrentHashMap<>();

    // ─────────────────────────────────────────────────────────────────────
    // Channel data structures
    // ─────────────────────────────────────────────────────────────────────

    public enum ChannelType { BULLISH, BEARISH, SIDEWAYS, INSUFFICIENT_DATA }
    public enum ChannelValidity { HIGH_QUALITY, VALID, INVALID }

    public record TrendLine(
            double startPrice,
            double endPrice,
            double slope,         // price change per candle
            int    touches,
            int    startIndex,    // candle index (0 = most recent)
            int    endIndex
    ) {
        public double priceAt(int candleIndex) {
            return endPrice + slope * (endIndex - candleIndex);
        }
    }

    public record ChannelResult(
            String         symbol,
            ChannelType    type,
            ChannelValidity validity,
            TrendLine      supportLine,
            TrendLine      resistanceLine,
            double         channelWidthPct,
            double         supportPrice,       // current support level
            double         resistancePrice,    // current resistance level
            double         pullbackZoneTop,    // top of pullback zone
            double         pullbackZoneBottom, // bottom of pullback zone
            int            candlesAnalyzed,
            String         reason
    ) {
        public boolean isValid() {
            return validity == ChannelValidity.VALID
                    || validity == ChannelValidity.HIGH_QUALITY;
        }

        public boolean isHighQuality() {
            return validity == ChannelValidity.HIGH_QUALITY;
        }

        /** Is current price inside the pullback zone? */
        public boolean isPriceInPullbackZone(double price) {
            return isValid()
                    && price >= pullbackZoneBottom
                    && price <= pullbackZoneTop;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Candle event listener
    // ─────────────────────────────────────────────────────────────────────

    @EventListener
    @Async("tradingExecutor")
    public void onCandle(CandleCompleteEvent event) {
        Candle c = event.getCandle();
        if (!"5minute".equals(c.getTimeframe())) return;
        if (!c.isComplete()) return;

        String sym = c.getTradingSymbol();

        // Update buffer
        Deque<Candle> buf = buffers5m.computeIfAbsent(sym, k -> new ArrayDeque<>());
        buf.addFirst(c);
        if (buf.size() > MAX_CANDLES) ((ArrayDeque<Candle>) buf).removeLast();

        // Re-detect channel
        List<Candle> candles = new ArrayList<>(buf);
        ChannelResult result = detectChannel(sym, candles);
        channelCache.put(sym, result);

        if (result.isValid()) {
            log.debug("[CHANNEL] {} → {} {} | support={} resist={} width={}% touches={}+{}",
                    sym, result.type(), result.validity(),
                    String.format("%.2f", result.supportPrice()),
                    String.format("%.2f", result.resistancePrice()),
                    String.format("%.2f", result.channelWidthPct()),
                    result.supportLine() != null ? result.supportLine().touches() : 0,
                    result.resistanceLine() != null ? result.resistanceLine().touches() : 0);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Core channel detection algorithm
    // ─────────────────────────────────────────────────────────────────────

    private ChannelResult detectChannel(String symbol, List<Candle> candles) {
        if (candles.size() < MIN_CANDLES) {
            return invalidResult(symbol, "Insufficient data: " + candles.size() + " candles");
        }

        log.trace("[CHANNEL] Detecting channel for {} with {} candles", symbol, candles.size());

        // Step 1: Identify swing highs and swing lows
        List<SwingPoint> swingHighs = detectSwingHighs(candles);
        List<SwingPoint> swingLows  = detectSwingLows(candles);

        log.trace("[CHANNEL] {} → swingHighs={} swingLows={}", symbol, swingHighs.size(), swingLows.size());

        if (swingHighs.size() < 2 || swingLows.size() < 2) {
            return invalidResult(symbol, "Insufficient swing points: highs=" + swingHighs.size() + " lows=" + swingLows.size());
        }

        // Step 2: Determine channel direction from swing structure
        ChannelType channelType = determineChannelType(swingHighs, swingLows);
        log.debug("[CHANNEL] {} → type={}", symbol, channelType);

        if (channelType == ChannelType.SIDEWAYS || channelType == ChannelType.INSUFFICIENT_DATA) {
            return invalidResult(symbol, "No directional channel: " + channelType);
        }

        // Step 3: Fit trendlines
        TrendLine supportLine    = fitSupportLine(swingLows, candles);
        TrendLine resistanceLine = fitResistanceLine(swingHighs, candles);

        if (supportLine == null || resistanceLine == null) {
            return invalidResult(symbol, "Could not fit trendlines");
        }

        // Step 4: Count touches on each trendline
        int supportTouches    = countTouches(candles, supportLine, true);
        int resistanceTouches = countTouches(candles, resistanceLine, false);

        // Rebuild with actual touch counts
        supportLine    = new TrendLine(supportLine.startPrice(), supportLine.endPrice(),
                supportLine.slope(), supportTouches, supportLine.startIndex(), supportLine.endIndex());
        resistanceLine = new TrendLine(resistanceLine.startPrice(), resistanceLine.endPrice(),
                resistanceLine.slope(), resistanceTouches, resistanceLine.startIndex(), resistanceLine.endIndex());

        log.debug("[CHANNEL] {} → supportTouches={} resistanceTouches={}", symbol, supportTouches, resistanceTouches);

        // Step 5: Validate (need ≥2 touches on both lines)
        int minTouches = Math.min(supportTouches, resistanceTouches);
        ChannelValidity validity;
        if (minTouches >= 3) {
            validity = ChannelValidity.HIGH_QUALITY;
        } else if (minTouches >= 2) {
            validity = ChannelValidity.VALID;
        } else {
            return invalidResult(symbol, "Insufficient touches: support=" + supportTouches + " resist=" + resistanceTouches);
        }

        // Step 6: Compute current prices and pullback zone
        double currentSupport    = supportLine.priceAt(0);
        double currentResistance = resistanceLine.priceAt(0);
        double channelWidth      = currentResistance - currentSupport;

        if (channelWidth <= 0) {
            return invalidResult(symbol, "Invalid channel width");
        }

        double channelWidthPct = channelWidth / currentSupport * 100;

        // Pullback zone: 15-30% of channel width from support (BUY) / from resistance (SELL)
        double pullbackZoneTop;
        double pullbackZoneBottom;
        if (channelType == ChannelType.BULLISH) {
            pullbackZoneBottom = currentSupport;
            pullbackZoneTop    = currentSupport + channelWidth * 0.30;
        } else {
            pullbackZoneTop    = currentResistance;
            pullbackZoneBottom = currentResistance - channelWidth * 0.30;
        }

        log.debug("[CHANNEL] {} → {} {} support={} resist={} width={}% pullback=[{},{}]",
                symbol, channelType, validity,
                String.format("%.2f", currentSupport),
                String.format("%.2f", currentResistance),
                String.format("%.2f", channelWidthPct),
                String.format("%.2f", pullbackZoneBottom),
                String.format("%.2f", pullbackZoneTop));

        return new ChannelResult(
                symbol, channelType, validity,
                supportLine, resistanceLine,
                channelWidthPct,
                currentSupport, currentResistance,
                pullbackZoneTop, pullbackZoneBottom,
                candles.size(), "OK"
        );
    }

    // ─────────────────────────────────────────────────────────────────────
    // Swing point detection
    // ─────────────────────────────────────────────────────────────────────

    private record SwingPoint(int index, double price, boolean isHigh) {}

    private List<SwingPoint> detectSwingHighs(List<Candle> candles) {
        List<SwingPoint> highs = new ArrayList<>();
        for (int i = 1; i < candles.size() - 1; i++) {
            double prev    = candles.get(i + 1).getHigh().doubleValue();
            double current = candles.get(i).getHigh().doubleValue();
            double next    = candles.get(i - 1).getHigh().doubleValue();
            if (current > prev && current > next) {
                highs.add(new SwingPoint(i, current, true));
            }
        }
        return highs;
    }

    private List<SwingPoint> detectSwingLows(List<Candle> candles) {
        List<SwingPoint> lows = new ArrayList<>();
        for (int i = 1; i < candles.size() - 1; i++) {
            double prev    = candles.get(i + 1).getLow().doubleValue();
            double current = candles.get(i).getLow().doubleValue();
            double next    = candles.get(i - 1).getLow().doubleValue();
            if (current < prev && current < next) {
                lows.add(new SwingPoint(i, current, false));
            }
        }
        return lows;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Channel type determination
    // ─────────────────────────────────────────────────────────────────────

    private ChannelType determineChannelType(List<SwingPoint> highs, List<SwingPoint> lows) {
        // Bullish = Higher Highs + Higher Lows
        // Bearish = Lower Highs + Lower Lows
        // Compare the two most recent vs the two before

        boolean higherHighs = false;
        boolean higherLows  = false;
        boolean lowerHighs  = false;
        boolean lowerLows   = false;

        if (highs.size() >= 2) {
            // Most recent swing high is at index 0 in our list (newest first)
            double recentHigh = highs.get(0).price();
            double olderHigh  = highs.get(highs.size() - 1).price();
            higherHighs = recentHigh > olderHigh;
            lowerHighs  = recentHigh < olderHigh;
        }

        if (lows.size() >= 2) {
            double recentLow = lows.get(0).price();
            double olderLow  = lows.get(lows.size() - 1).price();
            higherLows = recentLow > olderLow;
            lowerLows  = recentLow < olderLow;
        }

        if (higherHighs && higherLows) return ChannelType.BULLISH;
        if (lowerHighs  && lowerLows)  return ChannelType.BEARISH;
        return ChannelType.SIDEWAYS;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Trendline fitting (linear regression on swing points)
    // ─────────────────────────────────────────────────────────────────────

    private TrendLine fitSupportLine(List<SwingPoint> lows, List<Candle> candles) {
        if (lows.size() < 2) return null;
        SwingPoint newest = lows.get(0);
        SwingPoint oldest = lows.get(lows.size() - 1);
        int   indexDiff   = oldest.index() - newest.index();
        if (indexDiff == 0) return null;
        double slope = (newest.price() - oldest.price()) / indexDiff;
        return new TrendLine(oldest.price(), newest.price(), slope, lows.size(),
                oldest.index(), newest.index());
    }

    private TrendLine fitResistanceLine(List<SwingPoint> highs, List<Candle> candles) {
        if (highs.size() < 2) return null;
        SwingPoint newest = highs.get(0);
        SwingPoint oldest = highs.get(highs.size() - 1);
        int   indexDiff   = oldest.index() - newest.index();
        if (indexDiff == 0) return null;
        double slope = (newest.price() - oldest.price()) / indexDiff;
        return new TrendLine(oldest.price(), newest.price(), slope, highs.size(),
                oldest.index(), newest.index());
    }

    // ─────────────────────────────────────────────────────────────────────
    // Touch counting
    // ─────────────────────────────────────────────────────────────────────

    private int countTouches(List<Candle> candles, TrendLine line, boolean isSupport) {
        int touches = 0;
        for (int i = 0; i < candles.size(); i++) {
            Candle c = candles.get(i);
            double linePrice = line.priceAt(i);
            double tolerance = linePrice * TOUCH_TOLERANCE_PCT;
            double candlePrice = isSupport
                    ? c.getLow().doubleValue()
                    : c.getHigh().doubleValue();
            if (Math.abs(candlePrice - linePrice) <= tolerance) {
                touches++;
            }
        }
        return touches;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Get cached channel result for a symbol.
     * Returns empty INVALID result if not yet analyzed.
     */
    public ChannelResult getChannel(String symbol) {
        return channelCache.getOrDefault(symbol,
                invalidResult(symbol, "No channel data yet"));
    }

    /**
     * Returns true if the symbol has a valid channel (VALID or HIGH_QUALITY).
     */
    public boolean hasValidChannel(String symbol) {
        ChannelResult r = channelCache.get(symbol);
        return r != null && r.isValid();
    }

    /**
     * Force-update channel for a symbol using provided candles.
     * Used by strategy during pre-market warmup.
     */
    public void updateChannel(String symbol, List<Candle> candles5m) {
        if (candles5m == null || candles5m.isEmpty()) return;
        Deque<Candle> buf = buffers5m.computeIfAbsent(symbol, k -> new ArrayDeque<>());
        buf.clear();
        candles5m.forEach(c -> {
            buf.addFirst(c);
            if (buf.size() > MAX_CANDLES) ((ArrayDeque<Candle>) buf).removeLast();
        });
        List<Candle> list = new ArrayList<>(buf);
        channelCache.put(symbol, detectChannel(symbol, list));
    }

    /**
     * Get all symbols with valid channels — for dashboard display.
     */
    public Map<String, ChannelResult> getAllValidChannels() {
        Map<String, ChannelResult> valid = new LinkedHashMap<>();
        channelCache.forEach((sym, r) -> {
            if (r.isValid()) valid.put(sym, r);
        });
        return valid;
    }

    public int getTrackedSymbolCount() { return buffers5m.size(); }
    public int getValidChannelCount()  { return (int) channelCache.values().stream().filter(ChannelResult::isValid).count(); }

    // ─────────────────────────────────────────────────────────────────────
    // Scheduled reset
    // ─────────────────────────────────────────────────────────────────────

    @Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyReset() {
        buffers5m.clear();
        channelCache.clear();
        log.info("[CHANNEL] Daily reset complete — channel buffers cleared");
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    private ChannelResult invalidResult(String symbol, String reason) {
        return new ChannelResult(symbol, ChannelType.INSUFFICIENT_DATA,
                ChannelValidity.INVALID, null, null,
                0, 0, 0, 0, 0, 0, reason);
    }
}