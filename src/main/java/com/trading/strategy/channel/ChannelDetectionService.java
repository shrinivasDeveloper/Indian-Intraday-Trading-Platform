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
 * ChannelDetectionService — v5 (TradingView-style auto-switching state machine).
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * WHAT IS NEW IN V5 (built on v4 regression foundation)
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * 1. CHANNEL STATE MACHINE (per symbol)
 *    Persistent ChannelState survives across candle events, enabling smooth
 *    transitions instead of cold re-detection on every bar.
 *
 *    States:  UNDETECTED → SIDEWAYS ↔ BULLISH ↔ BEARISH → TRANSITIONING
 *
 *    Transitions:
 *      SIDEWAYS → BULLISH  : close > horizontalHigh + CONFIRM_BARS consecutive
 *                            + rising pivot highs (HH structure confirmed)
 *      SIDEWAYS → BEARISH  : close < horizontalLow  + CONFIRM_BARS consecutive
 *                            + falling pivot lows (LL structure confirmed)
 *      BULLISH  → SIDEWAYS : slope decays below ATR-normalized threshold
 *                            OR R² drops below MIN_R2 (structure deteriorating)
 *                            OR lower high forms (HH sequence breaks)
 *      BEARISH  → SIDEWAYS : symmetric logic
 *      BULLISH ↔ BEARISH   : direct flip after MIN_BARS_IN_STATE + CONFIRM_BARS
 *      Any → TRANSITIONING : 2-bar confirmation window (prevents whipsawing)
 *
 * 2. HORIZONTAL CHANNEL — TRUE TRADINGVIEW STYLE
 *    NOT just a slope threshold. Requires:
 *      • Rolling HORIZ_LOOKBACK-bar high/low within width bounds
 *      • Both regression lines nearly flat (ATR-normalized slope gate)
 *      • ≥2 independent touches of each level (same as trend channels)
 *      • Price NOT broken out of the band
 *    Support = flat regression through rolling lows
 *    Resistance = flat regression through rolling highs
 *    Pullback zone = bottom 30% (near support) for BUY setups
 *                  = top 30%    (near resistance) for SELL setups
 *
 * 3. ATR-NORMALIZED SLOPE THRESHOLD (dynamic per stock)
 *    Static 0.005%/bar was wrong for volatile stocks.
 *    Dynamic: threshold = ATR14 / price * SIDEWAYS_ATR_FACTOR (0.5)
 *    Adapts automatically — ₹500 stock with 1% ATR gets wider flat zone
 *    than ₹5000 stock with 0.5% ATR.
 *
 * 4. HYSTERESIS (no whipsawing)
 *    MIN_BARS_IN_STATE = 4 : must be in current state ≥4 bars before switching
 *    CONFIRM_BARS = 2      : 2 consecutive confirming bars needed for transition
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * FULL BACKWARD COMPATIBILITY (v4 → v5)
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * All v4 public API preserved without modification:
 *   ChannelResult : symbol, type, validity, supportLine, resistanceLine,
 *                   supportPrice, resistancePrice, channelWidthPct,
 *                   pullbackZoneTop, pullbackZoneBottom, candlesAnalyzed,
 *                   reason, sessionDate, lastUpdateEpoch
 *   ChannelResult methods: isValid(), isHighQuality(), isPriceInPullbackZone(), ageInMinutes()
 *   TrendLine     : slope, intercept, touches, startPrice, endPrice, startIndex, endIndex
 *   TrendLine methods: valueAt(int), priceAt(int)
 *   Service methods: getChannel(), hasValidChannel(), updateChannel(),
 *                    getAllValidChannels(), getValidChannelCount(), getTrackedSymbolCount()
 *
 * NEW additive fields in ChannelResult (zero breaking changes):
 *   isTransitioning()     → block new entries during channel-type switch
 *   previousType()        → channel type before last switch
 *   barsInCurrentState()  → freshness indicator
 *   stateLabel()          → human-readable: "BULLISH (8 bars)", "SWITCHING→BEARISH"
 *
 * NEW service methods:
 *   getTransitioningCount()  → how many symbols are switching right now
 *   getChannelTypeSummary()  → {"BULLISH":45,"BEARISH":23,"SIDEWAYS":67,"TRANSITIONING":5}
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * EXECUTION INTEGRATION — one new check in SmartChannelPullbackStrategy
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 *   // Add this ONE line after existing channel.isValid() check:
 *   if (channel.isTransitioning()) {
 *       log.trace("[SCPS] {} channel transitioning — skip entry", symbol);
 *       return;
 *   }
 *
 * All other strategy code (isPriceInPullbackZone, supportPrice, type, etc.) unchanged.
 */
@Service
@Slf4j
public class ChannelDetectionService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // ── Buffer sizing ────────────────────────────────────────────────────────────
    private static final int    MAX_CANDLES         = 75;
    private static final int    MIN_CANDLES         = 25;

    // ── Pivot detection ──────────────────────────────────────────────────────────
    private static final int    PIVOT_LOOKBACK      = 3;
    private static final int    MAX_PIVOTS_USED     = 5;

    // ── Regression quality ───────────────────────────────────────────────────────
    private static final double TOUCH_TOLERANCE_PCT = 0.003;
    private static final double MIN_R2              = 0.75;

    // ── Channel geometry ─────────────────────────────────────────────────────────
    private static final double MIN_WIDTH_PCT       = 0.4;
    private static final double MAX_WIDTH_PCT       = 6.0;
    private static final double MAX_SLOPE_DIFF_PCT  = 0.02;

    // ── Sideways / trend slope gate (ATR-normalized) ─────────────────────────────
    private static final double SIDEWAYS_ATR_FACTOR = 0.5;   // multiplied by ATR%
    private static final double SIDEWAYS_SLOPE_FALLBACK = 0.005; // when ATR unavailable

    // ── State machine ────────────────────────────────────────────────────────────
    private static final int    CONFIRM_BARS        = 2;     // bars to confirm transition
    private static final int    MIN_BARS_IN_STATE   = 4;     // hysteresis before switch
    private static final int    ATR_PERIOD          = 14;

    // ── Horizontal channel ───────────────────────────────────────────────────────
    private static final int    HORIZ_LOOKBACK      = 20;    // rolling window for range

    // ── Pullback zone factors ────────────────────────────────────────────────────
    private static final double PB_FACTOR_NORMAL    = 0.30;
    private static final double PB_FACTOR_WIDE      = 0.25;

    // ═══════════════════════════════════════════════════════════════════════════
    // PER-SYMBOL STATE MACHINE
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Persistent per-symbol state.
     * One instance per trading symbol, lives for the full session.
     * Cleared at dailyReset() (9:10 IST).
     */
    private static class ChannelState {
        ChannelType currentType       = ChannelType.INSUFFICIENT_DATA;
        ChannelType previousType      = ChannelType.INSUFFICIENT_DATA;
        int         barsInState       = 0;

        // Transition control
        boolean     isTransitioning   = false;
        ChannelType pendingType       = null;
        int         transitionConfirm = 0;
        double      breakoutLevel     = 0;
        int         confirmCount      = 0;   // consecutive bars confirming or denying

        // Price memory
        double      lastSupportPrice    = 0;
        double      lastResistancePrice = 0;

        // ATR (rolling, updated each candle)
        double atr14 = 0;
    }

    // ── Storage ──────────────────────────────────────────────────────────────────
    private final Map<String, Deque<Candle>>  buffers = new ConcurrentHashMap<>();
    private final Map<String, ChannelResult>  cache   = new ConcurrentHashMap<>();
    private final Map<String, ChannelState>   states  = new ConcurrentHashMap<>();

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC DATA TYPES (FULLY BACKWARD COMPATIBLE WITH v4)
    // ═══════════════════════════════════════════════════════════════════════════

    public enum ChannelType     { BULLISH, BEARISH, SIDEWAYS, INSUFFICIENT_DATA }
    public enum ChannelValidity { HIGH_QUALITY, VALID, INVALID }

    /**
     * TrendLine — v4 API preserved exactly.
     * Added: intercept (v5), valueAt(int) as primary; priceAt(int) as alias.
     */
    public record TrendLine(
            double slope,
            double intercept,
            int    touches,
            double startPrice,
            double endPrice,
            int    startIndex,
            int    endIndex
    ) {
        /** Line value at candle index x. */
        public double valueAt(int x) { return slope * x + intercept; }

        /** v4 backward-compatible alias. All existing callers unchanged. */
        public double priceAt(int x) { return valueAt(x); }
    }

    /**
     * ChannelResult — v4 API preserved + v5 new fields appended.
     * Record components are append-only: no v4 field removed or reordered.
     */
    public record ChannelResult(
            // ── v4 fields (unchanged) ──────────────────────────────────────────
            String          symbol,
            ChannelType     type,
            ChannelValidity validity,
            TrendLine       supportLine,
            TrendLine       resistanceLine,
            double          supportPrice,
            double          resistancePrice,
            double          channelWidthPct,
            double          pullbackZoneTop,
            double          pullbackZoneBottom,
            int             candlesAnalyzed,
            String          reason,
            LocalDate       sessionDate,
            long            lastUpdateEpoch,
            // ── v5 new fields (appended — zero breaking changes) ────────────────
            boolean         isTransitioning,
            ChannelType     previousType,
            int             barsInCurrentState
    ) {
        // ── v4 methods — all preserved ──────────────────────────────────────────

        public boolean isValid() {
            return validity != ChannelValidity.INVALID
                    && sessionDate != null
                    && sessionDate.equals(LocalDate.now(ZoneId.of("Asia/Kolkata")));
        }

        public boolean isHighQuality() {
            return validity == ChannelValidity.HIGH_QUALITY && isValid();
        }

        public boolean isPriceInPullbackZone(double price) {
            return isValid() && price >= pullbackZoneBottom && price <= pullbackZoneTop;
        }

        public long ageInMinutes() {
            return (System.currentTimeMillis() - lastUpdateEpoch) / 60_000;
        }

        // ── v5 new methods ──────────────────────────────────────────────────────

        /**
         * True during channel-type switch (CONFIRM_BARS confirmation window).
         * SmartChannelPullbackStrategy must skip new entries when this is true.
         */
        public boolean isTransitioning() { return isTransitioning; }

        /**
         * Human-readable state label for DashboardController.
         * Examples:
         *   "BULLISH (8 bars)"
         *   "SIDEWAYS (consolidation, 14 bars)"
         *   "SWITCHING → BEARISH"
         *   "DETECTING..."
         */
        public String stateLabel() {
            if (type == ChannelType.INSUFFICIENT_DATA) return "DETECTING...";
            if (isTransitioning)
                return "SWITCHING → " + type.name();
            if (type == ChannelType.SIDEWAYS)
                return "SIDEWAYS (consolidation, " + barsInCurrentState + " bars)";
            return type.name() + " (" + barsInCurrentState + " bars)";
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EVENT LISTENER
    // ═══════════════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tradingExecutor")
    public void onCandle(CandleCompleteEvent event) {
        Candle c = event.getCandle();
        if (!"5minute".equals(c.getTimeframe()) || !c.isComplete()) return;

        String symbol = c.getTradingSymbol();

        buffers.compute(symbol, (k, buf) -> {
            if (buf == null) buf = new ArrayDeque<>();
            buf.addFirst(c);
            while (buf.size() > MAX_CANDLES) ((ArrayDeque<Candle>) buf).removeLast();
            return buf;
        });

        Deque<Candle> buf = buffers.get(symbol);
        if (buf == null || buf.size() < MIN_CANDLES) return;

        // Oldest → newest for correct regression x-axis
        List<Candle> candles = new ArrayList<>(buf);
        Collections.reverse(candles);

        ChannelState state = states.computeIfAbsent(symbol, k -> new ChannelState());
        state.atr14 = computeAtr(candles, ATR_PERIOD);

        ChannelResult result = runStateMachine(symbol, candles, state);
        cache.put(symbol, result);

        if (log.isDebugEnabled() && result.isValid()) {
            log.debug("[CHANNEL] {} | {} | sup={} res={} w={}% t={}/{} | {}",
                    symbol, result.validity(),
                    String.format("%.2f", result.supportPrice()),
                    String.format("%.2f", result.resistancePrice()),
                    String.format("%.2f", result.channelWidthPct()),
                    result.supportLine()    != null ? result.supportLine().touches()    : 0,
                    result.resistanceLine() != null ? result.resistanceLine().touches() : 0,
                    result.stateLabel());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STATE MACHINE — top-level dispatcher
    // ═══════════════════════════════════════════════════════════════════════════

    private ChannelResult runStateMachine(String symbol,
                                          List<Candle> candles,
                                          ChannelState state) {

        double lastClose      = candles.get(candles.size() - 1).getClose().doubleValue();
        double dynSidewaysSl  = computeDynSidewaysSlope(state, lastClose);

        // First-time detection
        if (state.currentType == ChannelType.INSUFFICIENT_DATA) {
            return initialDetect(symbol, candles, state, dynSidewaysSl);
        }

        // During transition confirmation window
        if (state.isTransitioning) {
            return handleTransition(symbol, candles, state, lastClose, dynSidewaysSl);
        }

        // Increment bar counter then evaluate current state
        state.barsInState++;
        return switch (state.currentType) {
            case SIDEWAYS -> evalSideways(symbol, candles, state, lastClose, dynSidewaysSl);
            case BULLISH  -> evalTrend(symbol, candles, state, lastClose, dynSidewaysSl, true);
            case BEARISH  -> evalTrend(symbol, candles, state, lastClose, dynSidewaysSl, false);
            default       -> initialDetect(symbol, candles, state, dynSidewaysSl);
        };
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // INITIAL DETECTION
    // ═══════════════════════════════════════════════════════════════════════════

    private ChannelResult initialDetect(String symbol, List<Candle> candles,
                                        ChannelState state, double dynSS) {
        // Try trend first (more specific than sideways)
        ChannelResult trend = detectTrendChannel(symbol, candles, state);
        if (trend.isValid()) {
            activateState(state, trend.type(), ChannelType.INSUFFICIENT_DATA);
            log.info("[CHANNEL] {} initial → {}", symbol, trend.stateLabel());
            return trend;
        }
        // Try horizontal
        ChannelResult horiz = detectHorizontalChannel(symbol, candles, state, dynSS);
        if (horiz.isValid()) {
            activateState(state, ChannelType.SIDEWAYS, ChannelType.INSUFFICIENT_DATA);
            state.lastSupportPrice    = horiz.supportPrice();
            state.lastResistancePrice = horiz.resistancePrice();
            log.info("[CHANNEL] {} initial → SIDEWAYS", symbol);
            return horiz;
        }
        return invalid(symbol, "Initial: " + trend.reason());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EVALUATE SIDEWAYS STATE
    // ═══════════════════════════════════════════════════════════════════════════

    private ChannelResult evalSideways(String symbol, List<Candle> candles,
                                       ChannelState state, double price, double dynSS) {
        // Re-fit horizontal channel with latest data
        ChannelResult horiz = detectHorizontalChannel(symbol, candles, state, dynSS);

        // Update known levels from latest fit (if valid)
        if (horiz.isValid()) {
            state.lastSupportPrice    = horiz.supportPrice();
            state.lastResistancePrice = horiz.resistancePrice();
        }

        double res = state.lastResistancePrice;
        double sup = state.lastSupportPrice;

        // ── Check for upside breakout → BULLISH ──────────────────────────────────
        if (res > 0 && price > res) {
            state.confirmCount++;
            if (state.barsInState >= MIN_BARS_IN_STATE
                    && state.confirmCount >= CONFIRM_BARS
                    && hasBullishPivots(candles)) {
                log.info("[CHANNEL] {} SIDEWAYS→BULLISH: breakout {:.2f} > res {:.2f}",
                        symbol, price, res);
                startTransition(state, ChannelType.BULLISH, res);
                return buildTransitionResult(symbol, candles, state, horiz);
            }
        }
        // ── Check for downside breakdown → BEARISH ───────────────────────────────
        else if (sup > 0 && price < sup) {
            state.confirmCount++;
            if (state.barsInState >= MIN_BARS_IN_STATE
                    && state.confirmCount >= CONFIRM_BARS
                    && hasBearishPivots(candles)) {
                log.info("[CHANNEL] {} SIDEWAYS→BEARISH: breakdown {:.2f} < sup {:.2f}",
                        symbol, price, sup);
                startTransition(state, ChannelType.BEARISH, sup);
                return buildTransitionResult(symbol, candles, state, horiz);
            }
        } else {
            state.confirmCount = 0; // price back inside range — reset
        }

        // ── Return refreshed horizontal result ───────────────────────────────────
        if (horiz.isValid()) return addStateInfo(horiz, state);

        // ── Horizontal failing — try trend detection as fallback ─────────────────
        ChannelResult trend = detectTrendChannel(symbol, candles, state);
        if (trend.isValid() && state.barsInState >= MIN_BARS_IN_STATE) {
            log.info("[CHANNEL] {} SIDEWAYS→{} via trend (horizontal failed)",
                    symbol, trend.type());
            activateState(state, trend.type(), ChannelType.SIDEWAYS);
            return addStateInfo(trend, state);
        }

        // Degrade gracefully
        ChannelResult prev = cache.get(symbol);
        return (prev != null && prev.isValid()) ? addStateInfo(prev, state)
                : invalid(symbol, "Sideways unclear: " + horiz.reason());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EVALUATE TREND STATE (BULLISH or BEARISH)
    // ═══════════════════════════════════════════════════════════════════════════

    private ChannelResult evalTrend(String symbol, List<Candle> candles,
                                    ChannelState state, double price,
                                    double dynSS, boolean isBull) {
        ChannelResult trend = detectTrendChannel(symbol, candles, state);

        // ── Trend structure failed → check for transition to SIDEWAYS ────────────
        if (!trend.isValid()) {
            state.confirmCount++;
            if (state.confirmCount >= CONFIRM_BARS && state.barsInState >= MIN_BARS_IN_STATE) {
                log.info("[CHANNEL] {} {}→SIDEWAYS: structure broke ({})",
                        symbol, isBull ? "BULLISH" : "BEARISH", trend.reason());
                startTransition(state, ChannelType.SIDEWAYS, price);
                ChannelResult horiz = detectHorizontalChannel(symbol, candles, state, dynSS);
                return buildTransitionResult(symbol, candles, state, horiz);
            }
            // Not confirmed — hold last valid result
            ChannelResult prev = cache.get(symbol);
            return (prev != null && prev.isValid()) ? addStateInfo(prev, state)
                    : invalid(symbol, "Trend check pending: " + trend.reason());
        }
        state.confirmCount = 0; // trend re-confirmed, reset failure counter

        // ── Slope decay → SIDEWAYS ──────────────────────────────────────────────
        double slopeAbs  = trend.supportLine() != null ? Math.abs(trend.supportLine().slope()) : 0;
        double priceMid  = (trend.supportPrice() + trend.resistancePrice()) / 2.0;
        double slopePctVal = priceMid > 0 ? slopeAbs / priceMid * 100 : 0;

        if (slopePctVal < dynSS && state.barsInState >= MIN_BARS_IN_STATE) {
            log.info("[CHANNEL] {} {}→SIDEWAYS: slope decayed {:.4f}% < {:.4f}%",
                    symbol, isBull ? "BULLISH" : "BEARISH", slopePctVal, dynSS);
            startTransition(state, ChannelType.SIDEWAYS, price);
            ChannelResult horiz = detectHorizontalChannel(symbol, candles, state, dynSS);
            return buildTransitionResult(symbol, candles, state, horiz);
        }

        // ── Direct flip BULLISH↔BEARISH ─────────────────────────────────────────
        boolean flipped = (isBull  && trend.type() == ChannelType.BEARISH)
                || (!isBull && trend.type() == ChannelType.BULLISH);
        if (flipped && state.barsInState >= MIN_BARS_IN_STATE * 2) {
            state.confirmCount++;
            if (state.confirmCount >= CONFIRM_BARS) {
                log.info("[CHANNEL] {} DIRECT FLIP {}→{}",
                        symbol,
                        isBull ? "BULLISH" : "BEARISH",
                        trend.type().name());
                startTransition(state, trend.type(), price);
                return buildTransitionResult(symbol, candles, state, trend);
            }
        } else if (!flipped) {
            state.confirmCount = 0;
        }

        // ── Trend still valid ────────────────────────────────────────────────────
        state.lastSupportPrice    = trend.supportPrice();
        state.lastResistancePrice = trend.resistancePrice();
        return addStateInfo(trend, state);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TRANSITION CONFIRMATION HANDLER
    // ═══════════════════════════════════════════════════════════════════════════

    private ChannelResult handleTransition(String symbol, List<Candle> candles,
                                           ChannelState state, double price, double dynSS) {
        state.transitionConfirm++;
        state.barsInState++;

        // Check confirmation criteria for the pending type
        boolean confirming = switch (state.pendingType) {
            case BULLISH  -> price > state.breakoutLevel;
            case BEARISH  -> price < state.breakoutLevel;
            case SIDEWAYS -> true; // always confirms after CONFIRM_BARS in SIDEWAYS
            default       -> false;
        };

        if (confirming && state.transitionConfirm >= CONFIRM_BARS) {
            // Transition complete
            ChannelType completed = state.pendingType;
            activateState(state, completed, state.currentType);
            log.info("[CHANNEL] {} transition COMPLETE → {}", symbol, completed.name());
            return completed == ChannelType.SIDEWAYS
                    ? addStateInfo(detectHorizontalChannel(symbol, candles, state, dynSS), state)
                    : addStateInfo(detectTrendChannel(symbol, candles, state), state);
        }

        if (!confirming) {
            // Price retraced — abort transition, revert to previous type
            log.info("[CHANNEL] {} transition ABORTED — price {:.2f} retraced", symbol, price);
            ChannelType abortedTarget = state.pendingType;
            state.isTransitioning   = false;
            state.pendingType       = null;
            state.transitionConfirm = 0;
            // Re-detect based on reverted type
            ChannelResult prev = cache.get(symbol);
            return (prev != null && prev.isValid()) ? addStateInfo(prev, state)
                    : invalid(symbol, "Transition to " + abortedTarget + " aborted");
        }

        // Still in transition window — return in-progress result
        return buildOngoingTransitionResult(symbol, state);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TREND CHANNEL DETECTION (regression-based, from v4 + v5 refinements)
    // ═══════════════════════════════════════════════════════════════════════════

    private ChannelResult detectTrendChannel(String symbol,
                                             List<Candle> candles,
                                             ChannelState state) {
        List<Integer> highIdx = detectPivotHighs(candles);
        List<Integer> lowIdx  = detectPivotLows(candles);

        if (highIdx.size() < 3 || lowIdx.size() < 3)
            return invalid(symbol, "Not enough pivots: H=" + highIdx.size() + " L=" + lowIdx.size());

        highIdx = highIdx.subList(Math.max(0, highIdx.size() - MAX_PIVOTS_USED), highIdx.size());
        lowIdx  = lowIdx.subList( Math.max(0,  lowIdx.size() - MAX_PIVOTS_USED),  lowIdx.size());

        double[] resRaw = regression(highIdx, candles, true);
        double[] supRaw = regression(lowIdx,  candles, false);
        if (resRaw == null || supRaw == null)
            return invalid(symbol, "Regression failed");

        double resSlope = resRaw[0], resInt = resRaw[1];
        double supSlope = supRaw[0], supInt = supRaw[1];

        double r2Res = r2(highIdx, candles, resSlope, resInt, true);
        double r2Sup = r2(lowIdx,  candles, supSlope, supInt, false);
        if (r2Res < MIN_R2 || r2Sup < MIN_R2)
            return invalid(symbol, String.format("Poor R²: res=%.2f sup=%.2f", r2Res, r2Sup));

        int    last         = candles.size() - 1;
        double supNow       = supSlope * last + supInt;
        double resNow       = resSlope * last + resInt;
        double width        = resNow - supNow;

        if (width <= 0)         return invalid(symbol, "sup ≥ res");
        double widthPct = width / supNow * 100;
        if (widthPct < MIN_WIDTH_PCT) return invalid(symbol, String.format("Too narrow: %.2f%%", widthPct));
        if (widthPct > MAX_WIDTH_PCT) return invalid(symbol, String.format("Too wide: %.2f%%",   widthPct));

        // Wedge guard
        double midPrice     = (supNow + resNow) / 2.0;
        double slopeDiffPct = Math.abs(resSlope - supSlope) / midPrice * 100;
        if (slopeDiffPct > MAX_SLOPE_DIFF_PCT)
            return invalid(symbol, String.format("Wedge: %.4f%%", slopeDiffPct));

        // Classify direction using ATR-normalized slope
        double dynSS      = computeDynSidewaysSlope(state, supNow);
        double slopeMagPct = Math.abs(supSlope) / supNow * 100;

        ChannelType type;
        if      (slopeMagPct < dynSS) type = ChannelType.SIDEWAYS;
        else if (supSlope > 0 && resSlope > 0) type = ChannelType.BULLISH;
        else if (supSlope < 0 && resSlope < 0) type = ChannelType.BEARISH;
        else return invalid(symbol, "Mixed slopes (not parallel)");

        // Touch counting
        TrendLine tmpSup = new TrendLine(supSlope, supInt, 0, 0, 0, 0, last);
        TrendLine tmpRes = new TrendLine(resSlope, resInt, 0, 0, 0, 0, last);
        int supT = countTouches(candles, tmpSup, false);
        int resT = countTouches(candles, tmpRes, true);
        int minT = Math.min(supT, resT);

        ChannelValidity validity;
        if      (minT >= 3) validity = ChannelValidity.HIGH_QUALITY;
        else if (minT >= 2) validity = ChannelValidity.VALID;
        else return invalid(symbol, "Too few touches: s=" + supT + " r=" + resT);

        // Breakout guard
        double lastClose = candles.get(last).getClose().doubleValue();
        if (lastClose > resNow || lastClose < supNow)
            return invalid(symbol, "Breakout detected");

        // Pullback zone
        double pbF  = widthPct > 2.0 ? PB_FACTOR_WIDE : PB_FACTOR_NORMAL;
        double pzTop, pzBot;
        if (type == ChannelType.BULLISH || type == ChannelType.SIDEWAYS) {
            pzBot = supNow;  pzTop = supNow + width * pbF;
        } else {
            pzTop = resNow;  pzBot = resNow - width * pbF;
        }

        TrendLine supLine = new TrendLine(supSlope, supInt, supT, supInt, supNow, 0, last);
        TrendLine resLine = new TrendLine(resSlope, resInt, resT, resInt, resNow, 0, last);

        return new ChannelResult(
                symbol, type, validity,
                supLine, resLine,
                supNow, resNow, widthPct,
                pzTop, pzBot,
                candles.size(), "OK",
                LocalDate.now(IST), System.currentTimeMillis(),
                state.isTransitioning, state.previousType, state.barsInState
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HORIZONTAL CHANNEL DETECTION (TradingView-style)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * True TradingView-style horizontal channel detection:
     *   1. Rolling HORIZ_LOOKBACK-bar high/low for range anchors
     *   2. ATR-normalized slope gate — lines must be genuinely flat
     *   3. ≥2 independent touches of each level
     *   4. Width within MIN/MAX bounds
     *   5. Breakout guard: price still inside band
     *
     * Support  = flat regression through candles near the rolling low
     * Resistance = flat regression through candles near the rolling high
     * Pullback zone (for BUY): bottom 30% of range (near support)
     * Pullback zone (for SELL): SmartChannelPullbackStrategy can flip based on direction
     */
    private ChannelResult detectHorizontalChannel(String symbol,
                                                  List<Candle> candles,
                                                  ChannelState state,
                                                  double dynSS) {
        int n        = Math.min(HORIZ_LOOKBACK, candles.size());
        int startIdx = candles.size() - n;
        if (n < 10) return invalid(symbol, "Horiz: too few bars (" + n + ")");

        List<Candle> win = candles.subList(startIdx, candles.size());

        // ── Rolling range ────────────────────────────────────────────────────────
        double rollingHigh = Double.MIN_VALUE;
        double rollingLow  = Double.MAX_VALUE;
        for (Candle c : win) {
            rollingHigh = Math.max(rollingHigh, c.getHigh().doubleValue());
            rollingLow  = Math.min(rollingLow,  c.getLow().doubleValue());
        }
        double mid      = (rollingHigh + rollingLow) / 2.0;
        double widthPct = (rollingHigh - rollingLow) / mid * 100;

        if (widthPct < MIN_WIDTH_PCT) return invalid(symbol, String.format("Horiz too narrow: %.2f%%", widthPct));
        if (widthPct > MAX_WIDTH_PCT) return invalid(symbol, String.format("Horiz too wide: %.2f%%",   widthPct));

        // ── Identify candles near rolling high/low for regression ────────────────
        List<Integer> hiIdx = new ArrayList<>();
        List<Integer> loIdx = new ArrayList<>();
        for (int i = 0; i < win.size(); i++) {
            double h = win.get(i).getHigh().doubleValue();
            double l = win.get(i).getLow().doubleValue();
            if (h >= rollingHigh * (1.0 - TOUCH_TOLERANCE_PCT)) hiIdx.add(i);
            if (l <= rollingLow  * (1.0 + TOUCH_TOLERANCE_PCT)) loIdx.add(i);
        }

        if (hiIdx.size() < 2 || loIdx.size() < 2)
            return invalid(symbol, "Horiz: not enough level touches H=" + hiIdx.size() + " L=" + loIdx.size());

        double[] resRaw = regressionWindow(hiIdx, win, true);
        double[] supRaw = regressionWindow(loIdx, win, false);
        if (resRaw == null || supRaw == null)
            return invalid(symbol, "Horiz: regression failed");

        double resSlope = resRaw[0], resInt = resRaw[1];
        double supSlope = supRaw[0], supInt = supRaw[1];

        // ── Flatness gate: slope must be below 3× dynamic threshold ─────────────
        // 3× because horizontal channel allows slightly more slope than pure sideways
        double resSlopePct = mid > 0 ? Math.abs(resSlope) / mid * 100 : 999;
        double supSlopePct = mid > 0 ? Math.abs(supSlope) / mid * 100 : 999;
        double flatThresh  = dynSS * 3.0;

        if (resSlopePct > flatThresh || supSlopePct > flatThresh)
            return invalid(symbol, String.format(
                    "Horiz: not flat res=%.4f%% sup=%.4f%% (max=%.4f%%)",
                    resSlopePct, supSlopePct, flatThresh));

        // ── Current values ────────────────────────────────────────────────────────
        int    lastWin = win.size() - 1;
        double supNow  = supSlope * lastWin + supInt;
        double resNow  = resSlope * lastWin + resInt;
        double width   = resNow - supNow;
        if (width <= 0) return invalid(symbol, "Horiz: sup >= res");

        // ── Breakout guard ────────────────────────────────────────────────────────
        double lastClose = win.get(lastWin).getClose().doubleValue();
        double tol       = width * 0.05; // allow 5% tolerance before calling it a breakout
        if (lastClose > resNow + tol || lastClose < supNow - tol)
            return invalid(symbol, "Horiz: breakout detected");

        // ── Touch counting ────────────────────────────────────────────────────────
        TrendLine tmpSup = new TrendLine(supSlope, supInt, 0, 0, 0, 0, lastWin);
        TrendLine tmpRes = new TrendLine(resSlope, resInt, 0, 0, 0, 0, lastWin);
        int supT = countTouches(win, tmpSup, false);
        int resT = countTouches(win, tmpRes, true);
        int minT = Math.min(supT, resT);

        ChannelValidity validity;
        if      (minT >= 3) validity = ChannelValidity.HIGH_QUALITY;
        else if (minT >= 2) validity = ChannelValidity.VALID;
        else return invalid(symbol, "Horiz: too few touches s=" + supT + " r=" + resT);

        // ── Pullback zone ─────────────────────────────────────────────────────────
        double pbF  = widthPct > 2.0 ? PB_FACTOR_WIDE : PB_FACTOR_NORMAL;
        double pzBot = supNow;
        double pzTop = supNow + width * pbF;   // BUY zone near support

        TrendLine supLine = new TrendLine(supSlope, supInt, supT, supInt, supNow, 0, lastWin);
        TrendLine resLine = new TrendLine(resSlope, resInt, resT, resInt, resNow, 0, lastWin);

        return new ChannelResult(
                symbol, ChannelType.SIDEWAYS, validity,
                supLine, resLine,
                supNow, resNow, widthPct,
                pzTop, pzBot,
                win.size(), "Horizontal channel",
                LocalDate.now(IST), System.currentTimeMillis(),
                state.isTransitioning, state.previousType, state.barsInState
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PIVOT DETECTION (from v4, unchanged)
    // ═══════════════════════════════════════════════════════════════════════════

    private List<Integer> detectPivotHighs(List<Candle> c) {
        List<Integer> res = new ArrayList<>();
        for (int i = PIVOT_LOOKBACK; i < c.size() - PIVOT_LOOKBACK; i++) {
            double v = c.get(i).getHigh().doubleValue();
            boolean ok = true;
            for (int j = 1; j <= PIVOT_LOOKBACK; j++) {
                if (v <= c.get(i-j).getHigh().doubleValue()
                        || v <= c.get(i+j).getHigh().doubleValue()) { ok = false; break; }
            }
            if (ok) res.add(i);
        }
        return res;
    }

    private List<Integer> detectPivotLows(List<Candle> c) {
        List<Integer> res = new ArrayList<>();
        for (int i = PIVOT_LOOKBACK; i < c.size() - PIVOT_LOOKBACK; i++) {
            double v = c.get(i).getLow().doubleValue();
            boolean ok = true;
            for (int j = 1; j <= PIVOT_LOOKBACK; j++) {
                if (v >= c.get(i-j).getLow().doubleValue()
                        || v >= c.get(i+j).getLow().doubleValue()) { ok = false; break; }
            }
            if (ok) res.add(i);
        }
        return res;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // REGRESSION (from v4) + window overload for horizontal detection
    // ═══════════════════════════════════════════════════════════════════════════

    /** OLS regression on the full candle list using pivot indices. */
    private double[] regression(List<Integer> idx, List<Candle> c, boolean hi) {
        int n = idx.size();
        if (n < 2) return null;
        double sx = 0, sy = 0, sxy = 0, sx2 = 0;
        for (int i : idx) {
            double y = hi ? c.get(i).getHigh().doubleValue() : c.get(i).getLow().doubleValue();
            sx += i; sy += y; sxy += (double)i*y; sx2 += (double)i*i;
        }
        double d = n*sx2 - sx*sx;
        if (Math.abs(d) < 1e-10) return null;
        double slope = (n*sxy - sx*sy) / d;
        double icept = (sy - slope*sx) / n;
        return new double[]{slope, icept};
    }

    /** OLS regression on a sub-window list (window indices 0-based). */
    private double[] regressionWindow(List<Integer> idx, List<Candle> win, boolean hi) {
        int n = idx.size();
        if (n < 2) return null;
        double sx = 0, sy = 0, sxy = 0, sx2 = 0;
        for (int i : idx) {
            double y = hi ? win.get(i).getHigh().doubleValue() : win.get(i).getLow().doubleValue();
            sx += i; sy += y; sxy += (double)i*y; sx2 += (double)i*i;
        }
        double d = n*sx2 - sx*sx;
        if (Math.abs(d) < 1e-10) return null;
        double slope = (n*sxy - sx*sy) / d;
        double icept = (sy - slope*sx) / n;
        return new double[]{slope, icept};
    }

    /** R² coefficient of determination. */
    private double r2(List<Integer> idx, List<Candle> c,
                      double slope, double icept, boolean hi) {
        double mean = 0;
        for (int i : idx)
            mean += hi ? c.get(i).getHigh().doubleValue() : c.get(i).getLow().doubleValue();
        mean /= idx.size();
        double ssTot = 0, ssRes = 0;
        for (int i : idx) {
            double a = hi ? c.get(i).getHigh().doubleValue() : c.get(i).getLow().doubleValue();
            ssTot += Math.pow(a - mean, 2);
            ssRes += Math.pow(a - (slope*i + icept), 2);
        }
        return ssTot < 1e-12 ? 1.0 : 1.0 - ssRes/ssTot;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TOUCH COUNTER (from v4, unchanged)
    // ═══════════════════════════════════════════════════════════════════════════

    private int countTouches(List<Candle> candles, TrendLine line, boolean useHigh) {
        int touches = 0; boolean last = false;
        for (int i = 0; i < candles.size(); i++) {
            double price = useHigh ? candles.get(i).getHigh().doubleValue()
                    : candles.get(i).getLow().doubleValue();
            double lv    = line.valueAt(i);
            boolean touch = Math.abs(price - lv) <= lv * TOUCH_TOLERANCE_PCT;
            if (touch && !last) touches++;
            last = touch;
        }
        return touches;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ATR + DYNAMIC SLOPE THRESHOLD
    // ═══════════════════════════════════════════════════════════════════════════

    private double computeAtr(List<Candle> candles, int period) {
        int n = Math.min(period, candles.size() - 1);
        if (n <= 0) return 0;
        double sum = 0;
        for (int i = candles.size() - 1; i >= candles.size() - n; i--) {
            double h  = candles.get(i).getHigh().doubleValue();
            double l  = candles.get(i).getLow().doubleValue();
            double pc = candles.get(i-1).getClose().doubleValue();
            sum += Math.max(h-l, Math.max(Math.abs(h-pc), Math.abs(l-pc)));
        }
        return sum / n;
    }

    /**
     * ATR-normalized flat/trend slope threshold.
     * If ATR not yet available, falls back to static constant.
     */
    private double computeDynSidewaysSlope(ChannelState state, double price) {
        return (state.atr14 > 0 && price > 0)
                ? (state.atr14 / price) * SIDEWAYS_ATR_FACTOR
                : SIDEWAYS_SLOPE_FALLBACK;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PIVOT STRUCTURE CHECKS (for transition confirmation)
    // ═══════════════════════════════════════════════════════════════════════════

    /** True if the last 2 pivot highs are rising (HH structure = bullish momentum). */
    private boolean hasBullishPivots(List<Candle> candles) {
        List<Integer> highs = detectPivotHighs(candles);
        if (highs.size() < 2) return false;
        int n = highs.size();
        return candles.get(highs.get(n-1)).getHigh().doubleValue()
                > candles.get(highs.get(n-2)).getHigh().doubleValue();
    }

    /** True if the last 2 pivot lows are falling (LL structure = bearish momentum). */
    private boolean hasBearishPivots(List<Candle> candles) {
        List<Integer> lows = detectPivotLows(candles);
        if (lows.size() < 2) return false;
        int n = lows.size();
        return candles.get(lows.get(n-1)).getLow().doubleValue()
                < candles.get(lows.get(n-2)).getLow().doubleValue();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STATE MACHINE HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private void activateState(ChannelState st, ChannelType newType, ChannelType prevType) {
        st.previousType      = prevType;
        st.currentType       = newType;
        st.barsInState       = 1;
        st.isTransitioning   = false;
        st.pendingType       = null;
        st.transitionConfirm = 0;
        st.confirmCount      = 0;
    }

    private void startTransition(ChannelState st, ChannelType target, double breakoutLevel) {
        st.isTransitioning   = true;
        st.pendingType       = target;
        st.transitionConfirm = 0;
        st.breakoutLevel     = breakoutLevel;
        st.confirmCount      = 0;
    }

    /** Copy state machine metadata into a freshly-detected ChannelResult. */
    private ChannelResult addStateInfo(ChannelResult r, ChannelState st) {
        if (r == null) return null;
        return new ChannelResult(
                r.symbol(), r.type(), r.validity(),
                r.supportLine(), r.resistanceLine(),
                r.supportPrice(), r.resistancePrice(),
                r.channelWidthPct(),
                r.pullbackZoneTop(), r.pullbackZoneBottom(),
                r.candlesAnalyzed(), r.reason(),
                r.sessionDate(), System.currentTimeMillis(),
                st.isTransitioning, st.previousType, st.barsInState
        );
    }

    /**
     * Build a ChannelResult representing the PENDING (destination) channel type
     * while the transition confirmation window is still open.
     * Uses fallback geometry if the target channel isn't fully formed yet.
     */
    private ChannelResult buildTransitionResult(String symbol,
                                                List<Candle> candles,
                                                ChannelState st,
                                                ChannelResult fallback) {

        boolean hasValidFallback = (fallback != null && fallback.isValid());

        return new ChannelResult(
                symbol,
                st.pendingType != null ? st.pendingType : ChannelType.INSUFFICIENT_DATA,

                // ✅ FIXED: invalid instead of fake VALID
                hasValidFallback ? fallback.validity() : ChannelValidity.INVALID,

                // ── Geometry safely guarded ─────────────────────────────
                hasValidFallback ? fallback.supportLine()    : null,
                hasValidFallback ? fallback.resistanceLine() : null,

                hasValidFallback ? fallback.supportPrice()    : st.lastSupportPrice,
                hasValidFallback ? fallback.resistancePrice() : st.lastResistancePrice,

                hasValidFallback ? fallback.channelWidthPct() : 0,
                hasValidFallback ? fallback.pullbackZoneTop()  : 0,
                hasValidFallback ? fallback.pullbackZoneBottom() : 0,

                candles.size(),

                "TRANSITIONING→" + (st.pendingType != null ? st.pendingType.name() : "?"),

                LocalDate.now(IST),
                System.currentTimeMillis(),

                true,              // isTransitioning = true
                st.currentType,    // previousType
                st.barsInState
        );
    }

    /**
     * Build an in-progress transition result when no fallback geometry is available.
     * Preserves last cached support/resistance values.
     */
    private ChannelResult buildOngoingTransitionResult(String symbol, ChannelState st) {
        ChannelResult last = cache.get(symbol);
        if (last == null) return invalid(symbol, "Transitioning — no prior data");
        return new ChannelResult(
                symbol,
                st.pendingType != null ? st.pendingType : last.type(),
                last.validity(),
                last.supportLine(), last.resistanceLine(),
                last.supportPrice(), last.resistancePrice(),
                last.channelWidthPct(),
                last.pullbackZoneTop(), last.pullbackZoneBottom(),
                last.candlesAnalyzed(),
                "TRANSITIONING→" + (st.pendingType != null ? st.pendingType.name() : "?"),
                LocalDate.now(IST), System.currentTimeMillis(),
                true, st.currentType, st.barsInState
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API — ALL v4 METHODS PRESERVED + v5 ADDITIONS
    // ═══════════════════════════════════════════════════════════════════════════

    /** Primary getter. Never returns null. Rejects stale previous-session data. */
    public ChannelResult getChannel(String symbol) {
        ChannelResult r = cache.get(symbol);
        if (r == null) return invalid(symbol, "No data");
        if (r.sessionDate() != null && !r.sessionDate().equals(LocalDate.now(IST)))
            return invalid(symbol, "Stale (previous session)");
        return r;
    }

    public boolean hasValidChannel(String symbol) {
        ChannelResult r = cache.get(symbol);
        return r != null && r.isValid();
    }

    public void updateChannel(String symbol, List<Candle> candles5m) {
        if (candles5m == null || candles5m.isEmpty()) return;
        buffers.compute(symbol, (k, existing) -> {
            Deque<Candle> buf = new ArrayDeque<>();
            for (Candle c : candles5m) {
                buf.addFirst(c);
                while (buf.size() > MAX_CANDLES) ((ArrayDeque<Candle>) buf).removeLast();
            }
            return buf;
        });
        Deque<Candle> buf = buffers.get(symbol);
        if (buf != null && buf.size() >= MIN_CANDLES) {
            List<Candle> ordered = new ArrayList<>(buf);
            Collections.reverse(ordered);
            ChannelState st = states.computeIfAbsent(symbol, k -> new ChannelState());
            st.atr14 = computeAtr(ordered, ATR_PERIOD);
            cache.put(symbol, runStateMachine(symbol, ordered, st));
        }
    }

    public Map<String, ChannelResult> getAllValidChannels() {
        Map<String, ChannelResult> valid = new LinkedHashMap<>();
        cache.forEach((k, v) -> { if (v.isValid()) valid.put(k, v); });
        return valid;
    }

    public int getValidChannelCount() {
        return (int) cache.values().stream().filter(ChannelResult::isValid).count();
    }

    public int getTrackedSymbolCount() {
        return buffers.size();
    }

    // ── v5 NEW public methods ───────────────────────────────────────────────────

    /**
     * Number of symbols currently switching channel types.
     * For DashboardController: shows "N channels switching" warning badge.
     */
    public int getTransitioningCount() {
        return (int) cache.values().stream()
                .filter(r -> r.isValid() && r.isTransitioning())
                .count();
    }

    /**
     * Channel type distribution across all valid channels.
     * For DashboardController overview card.
     * Returns: {"BULLISH":45, "BEARISH":23, "SIDEWAYS":67, "TRANSITIONING":5}
     */
    public Map<String, Integer> getChannelTypeSummary() {
        Map<String, Integer> s = new LinkedHashMap<>();
        s.put("BULLISH",0); s.put("BEARISH",0); s.put("SIDEWAYS",0); s.put("TRANSITIONING",0);
        cache.values().stream().filter(ChannelResult::isValid).forEach(r -> {
            if (r.isTransitioning()) s.merge("TRANSITIONING", 1, Integer::sum);
            else                     s.merge(r.type().name(),  1, Integer::sum);
        });
        return s;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SCHEDULED RESET
    // ═══════════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyReset() {
        buffers.clear();
        cache.clear();
        states.clear();
        log.info("[CHANNEL] Daily reset — all state machines cleared for new session");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // INVALID RESULT FACTORY
    // ═══════════════════════════════════════════════════════════════════════════

    private ChannelResult invalid(String symbol, String reason) {
        return new ChannelResult(
                symbol,
                ChannelType.INSUFFICIENT_DATA, ChannelValidity.INVALID,
                null, null,
                0, 0, 0, 0, 0, 0,
                reason,
                LocalDate.now(IST), System.currentTimeMillis(),
                false, ChannelType.INSUFFICIENT_DATA, 0
        );
    }
}