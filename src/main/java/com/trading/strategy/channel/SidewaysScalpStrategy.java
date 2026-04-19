package com.trading.strategy.channel;

import com.trading.analysis.service.RvolService;
import com.trading.domain.Candle;
import com.trading.domain.enums.TradeDirection;
import com.trading.events.CandleCompleteEvent;
import com.trading.events.SmartChannelPullbackSignalEvent;
import com.trading.marketdata.service.MarketPressureService;
import com.trading.marketdata.service.MarketPressureService.PressureSnapshot;
import com.trading.marketdata.service.MarketTimingService;
import com.trading.marketdata.service.MarketTimingService.TimeWindow;
import com.trading.papertrading.model.PaperAccount;
import com.trading.position.service.PositionSizerService;
import com.trading.risk.service.CircuitBreakerService;
import com.trading.sector.service.SectorClassificationService;
import com.trading.sector.service.SectorStrengthService;
import com.trading.strategy.channel.ChannelDetectionService.ChannelResult;
import com.trading.strategy.channel.ChannelDetectionService.ChannelType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SidewaysScalpStrategy (SCALP_PRESSURE_V2)
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * STRATEGY LOGIC:
 *   Scalp trades on SIDEWAYS channels where price bounces between narrow
 *   support/resistance levels. Fires when:
 *   1. Channel is SIDEWAYS (width 0.6% – 1.8%)
 *   2. Price is near support (BUY) or near resistance (SELL)
 *   3. A reversal candle pattern is present (hammer/shooting star)
 *      OR market pressure is strong (ratio >= 1.5) — high conviction bypass
 *   4. RVOL is elevated (confirming activity at the level)
 *   5. Market pressure direction aligns with the trade direction
 *
 *   Pipeline: CandleCompleteEvent → evaluateForScalp() → SmartChannelPullbackSignalEvent
 *             → SmartChannelSignalHandler → TradeApprovedEvent
 *             → PaperTradeExecutionService → PaperTradeManagementService
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * IMPROVEMENTS vs SCALP_PRESSURE_V2 (based on 2026-04-17 live data):
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * ROOT CAUSE ANALYSIS (April 17, 2026):
 *   - 0 signals fired despite multiple SIDEWAYS channels (UCOBANK 0.67%,
 *     ATUL 0.69%, DELHIVERY 0.70%) and BUY market pressure.
 *   - MARKET_PRESSURE occupied UCOBANK at 11:15 IST.
 *   - For the remaining SIDEWAYS channels (ATUL, DELHIVERY):
 *     The original isBullishReversal() check requires a strict hammer candle:
 *     close > open AND lowerWick >= body AND (close-low)/range >= 0.5.
 *     During the 11:00–12:30 LUNCH window, candles are typically doji-like
 *     (very small body, nearly equal open/close) — they don't meet the hammer
 *     criteria because the body (|close - open|) is near-zero, making
 *     "lowerWick >= body" always true but "(close-low)/range >= 0.5" fails
 *     for doji candles where close ≈ (high + low) / 2.
 *   - Result: the reversal candle gate eliminated all lunch-window signals,
 *     which is actually CORRECT behaviour for most cases — lunch dojis are
 *     not genuine reversals. However, when market pressure is very strong
 *     (ratio >= 1.5), the market pressure itself provides directional
 *     conviction and the candle pattern requirement can be relaxed.
 *
 * FIX 1: STRONG-PRESSURE BYPASS FOR REVERSAL CANDLE REQUIREMENT
 *   Old: isBullishReversal() / isBearishReversal() ALWAYS required a strict
 *        hammer/shooting-star pattern. On lunch-window doji candles, this
 *        always returned false → zero signals.
 *   New: When pressure.ratio() >= STRONG_PRESSURE_BYPASS_RATIO (1.50):
 *        - Accept any bullish candle (close > open) for BUY
 *        - Accept any bearish candle (close < open) for SELL
 *        - The strong market pressure itself is the directional signal.
 *        When pressure is moderate (< 1.50), the full reversal pattern is still required.
 *   Rationale: This is not about lowering standards — a 1.5x pressure dominance
 *   ratio means 60% of tracked stocks are moving in one direction. That is a
 *   strong enough signal on its own. The candle pattern is an additional filter
 *   that becomes redundant at high conviction levels.
 *
 * FIX 2: MINIMUM STOCK PRICE FILTER (₹100)
 *   Old: No minimum price filter.
 *   New: Reject stocks below ₹100. UCOBANK at ₹26 should never be scalped —
 *        the channel width is 0.67% = ₹0.13 absolute, which is consumed entirely
 *        by spread. Consistent with scanner.min-price=100 in application.yml.
 *
 * FIX 3: MINIMUM SL DISTANCE (0.35%)
 *   Old: No minimum SL percentage check.
 *   New: SL must be >= 0.35% of entry price. Scalp trades have tighter SL
 *        targets than SCPS (they're near the channel boundary), so the minimum
 *        is set slightly lower than SCPS (0.35% vs 0.40%) to preserve signals
 *        on valid narrow channels while filtering out pure-noise setups.
 *
 * FIX 4: PROXIMITY THRESHOLD TIGHTENED FOR NARROW CHANNELS
 *   Old: PROXIMITY = 0.004 (price must be within 0.4% of support/resistance).
 *   New: PROXIMITY = 0.005 (0.5%). Wider proximity for narrow channels (0.6-1.8%)
 *        means more signals when price is approaching key levels, not just
 *        sitting exactly at the level. This combines with FIX 1 to generate more
 *        valid signals on low-volatility days.
 *
 * FIX 5: RANGE POSITION GATE WIDENED
 *   Old: For BUY: rangePos > 0.35 → skip (price too far into channel).
 *        For SELL: rangePos < 0.65 → skip (price too far into channel).
 *   New: For BUY: rangePos > 0.45 → skip (allow wider entry zone near support).
 *        For SELL: rangePos < 0.55 → skip.
 *   Rationale: On low-ATR days (the April 17 frozen-market scenario),
 *   price may park at 40% of the channel range — this is still "near support"
 *   in a low-volatility environment and a valid scalp entry point.
 *
 * FIX 6: LUNCH WINDOW → FULL SKIP (unlike SCPS which applies stricter filters)
 *   Old: No time window filtering.
 *   New: Skip LUNCH entirely (11:00–12:30). Scalp trades rely on mean-reversion
 *        within narrow channels. During lunch, spread widens and volume thins —
 *        the mean-reversion assumption breaks down as market makers widen quotes.
 *        Unlike SCPS (pullback-to-support), scalp trades have a much tighter
 *        margin for error and should not be taken during the low-volume period.
 *   Also skip LATE window (14:00–14:40): thin market, hard to exit at target.
 *
 * UNCHANGED:
 *   - Channel width filters: MIN_WIDTH=0.6%, MAX_WIDTH=1.8% (SIDEWAYS channel only)
 *   - Event type: SmartChannelPullbackSignalEvent (identical constructor)
 *   - Strategy name: "SCALP_PRESSURE_V2"
 *   - Signal routing: SmartChannelSignalHandler → TradeApprovedEvent
 *   - Execution: PaperTradeExecutionService → PaperTradeManagementService
 *   - Time gates: ENTRY_END = 14:00 IST (still hard coded, now enforced via window check)
 *   - All other strategies completely unaffected
 * ─────────────────────────────────────────────────────────────────────────────
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SidewaysScalpStrategy {

    private static final ZoneId   IST           = ZoneId.of("Asia/Kolkata");
    private static final String   STRATEGY_NAME = "SCALP_PRESSURE_V2";

    // ── Channel width bounds for scalp (SIDEWAYS channels only) ──────────────
    // Only trade narrow channels suitable for scalp targets.
    private static final double MIN_WIDTH_PCT = 0.6;   // skip if channel < 0.6% wide
    private static final double MAX_WIDTH_PCT = 1.8;   // skip if channel > 1.8% wide

    // ── FIX 2: Minimum stock price ────────────────────────────────────────────
    private static final double MIN_STOCK_PRICE = 100.0;

    // ── FIX 4: Proximity threshold ────────────────────────────────────────────
    // FIX: was 0.004 (0.4%), now 0.005 (0.5%) for slightly wider entry zone.
    private static final double PROXIMITY = 0.005;

    // ── FIX 5: Range position gates ───────────────────────────────────────────
    // FIX: was 0.35/0.65, now 0.45/0.55 to widen valid entry zone near levels.
    private static final double RANGE_POS_BUY_MAX  = 0.45; // BUY: price in bottom 45% of channel
    private static final double RANGE_POS_SELL_MIN = 0.55; // SELL: price in top 45% of channel

    // ── FIX 1: Strong-pressure bypass threshold ───────────────────────────────
    /**
     * When pressure.ratio() >= this value, any bullish/bearish candle is accepted
     * without requiring the full hammer/shooting-star pattern.
     * 1.5 means 60% of tracked stocks are moving in the same direction.
     */
    private static final double STRONG_PRESSURE_BYPASS_RATIO = 1.50;

    // ── FIX 3: Minimum SL distance ────────────────────────────────────────────
    private static final double MIN_SL_PCT = 0.0035;  // 0.35%

    // ── Dependencies ──────────────────────────────────────────────────────────
    private final ChannelDetectionService     channelDetection;
    private final MarketPressureService       pressureService;
    private final MarketTimingService         timingService;
    private final RvolService                 rvolService;
    private final SectorStrengthService       sectorStrength;
    private final SectorClassificationService sectorClassify;
    private final CircuitBreakerService       circuitBreaker;
    private final PositionSizerService        positionSizer;
    private final PaperAccount               paperAccount;
    private final ApplicationEventPublisher  publisher;

    // ── Config from application.yml (strategy.sideways-scalp.*) ─────────────
    @Value("${strategy.sideways-scalp.enabled:true}")
    private boolean strategyEnabled;

    @Value("${strategy.sideways-scalp.min-rvol:1.1}")
    private double minRvol;

    @Value("${strategy.sideways-scalp.max-signals-per-session:5}")
    private int maxSignalsPerSession;

    @Value("${strategy.sideways-scalp.time-stop-minutes:20}")
    private int timeStopMinutes;

    @Value("${trading.mode:PAPER}")
    private String tradingMode;

    @Value("${trading.capital:100000}")
    private BigDecimal capital;

    // ── Session state ─────────────────────────────────────────────────────────
    private volatile int         sessionSignalCount = 0;
    private final Set<String>    activeSignals      = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> lastSignalTime  = new ConcurrentHashMap<>();

    // ── Latest candle buffer ──────────────────────────────────────────────────
    private final Map<String, Candle> latestCandles = new ConcurrentHashMap<>();

    // ══════════════════════════════════════════════════════════════════════════
    // CANDLE LISTENER
    // ══════════════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tradingExecutor")
    public void onCandle(CandleCompleteEvent event) {
        Candle candle = event.getCandle();
        if (!candle.isComplete()) return;

        if ("5minute".equals(candle.getTimeframe())) {
            latestCandles.put(candle.getTradingSymbol(), candle);
            evaluateForScalp(candle);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CORE EVALUATION
    // ══════════════════════════════════════════════════════════════════════════

    private void evaluateForScalp(Candle triggerCandle) {
        if (!strategyEnabled) return;

        // ── Session cap ───────────────────────────────────────────────────────
        if (sessionSignalCount >= maxSignalsPerSession) {
            log.debug("[SCALP] Session cap reached ({}/{})", sessionSignalCount, maxSignalsPerSession);
            return;
        }

        // ── Circuit breaker ───────────────────────────────────────────────────
        BigDecimal cap = resolveCapital();
        if (!circuitBreaker.checkPermission(cap).isAllowed()) {
            log.debug("[SCALP] Circuit breaker blocked");
            return;
        }

        // ── FIX 6: Time window gate ───────────────────────────────────────────
        // Scalp trades need tight spreads and active market makers.
        // Skip LUNCH (11:00–12:30): spreads widen, volume thins, mean-reversion fails.
        // Skip LATE (14:00–14:40): thin market, can't exit cleanly at target.
        // Skip OBSERVATION (pre-9:30): channels not yet stable.
        TimeWindow currentWindow = timingService.getCurrentWindow();
        if (currentWindow == TimeWindow.LUNCH) {
            log.debug("[SCALP] LUNCH window — skipping scalp evaluation (spread risk)");
            return;
        }
        if (currentWindow == TimeWindow.LATE) {
            log.debug("[SCALP] LATE window — skipping scalp evaluation");
            return;
        }
        if (currentWindow == TimeWindow.OBSERVATION) {
            log.debug("[SCALP] OBSERVATION window — skipping scalp evaluation");
            return;
        }

        // ── Market pressure ───────────────────────────────────────────────────
        PressureSnapshot pressure = pressureService.getSnapshot();
        if (!pressure.isActionable()) {
            log.debug("[SCALP] Pressure not actionable: dir={} ratio={}",
                    pressure.direction(), String.format("%.3f", pressure.ratio()));
            return;
        }

        boolean isBuy          = pressure.isBuy();
        boolean isStrongPressure = pressure.ratio() >= STRONG_PRESSURE_BYPASS_RATIO;

        // ── Get SIDEWAYS channels ─────────────────────────────────────────────
        Map<String, ChannelResult> validChannels = channelDetection.getAllValidChannels();
        if (validChannels.isEmpty()) return;

        log.debug("[SCALP] Evaluating {} channels | pressure={} ratio={:.3f} strong={} window={}",
                validChannels.size(), pressure.direction(),
                pressure.ratio(), isStrongPressure, currentWindow);

        for (Map.Entry<String, ChannelResult> entry : validChannels.entrySet()) {
            if (sessionSignalCount >= maxSignalsPerSession) break;

            String        symbol  = entry.getKey();
            ChannelResult channel = entry.getValue();

            evaluateSymbol(symbol, channel, pressure, isBuy, isStrongPressure, cap);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SYMBOL EVALUATION
    // ══════════════════════════════════════════════════════════════════════════

    private void evaluateSymbol(String symbol, ChannelResult channel,
                                PressureSnapshot pressure, boolean isBuy,
                                boolean isStrongPressure, BigDecimal cap) {

        // ── Cooldown / active signal guard ────────────────────────────────────
        if (activeSignals.contains(symbol)) return;
        Long lastFired = lastSignalTime.get(symbol);
        if (lastFired != null && System.currentTimeMillis() - lastFired < 30 * 60_000L) {
            log.trace("[SCALP] {} — in 30-min cooldown", symbol);
            return;
        }

        // ── Scalp requires SIDEWAYS channel only ──────────────────────────────
        // Scalp logic is for mean-reversion within range, not directional trend trades.
        // BULLISH/BEARISH channels are not appropriate for scalp — price may keep trending.
        if (channel.type() != ChannelType.SIDEWAYS) {
            log.trace("[SCALP] {} — channel type {} is not SIDEWAYS", symbol, channel.type());
            return;
        }

        if (channel.isTransitioning()) {
            log.trace("[SCALP] {} — channel transitioning", symbol);
            return;
        }

        // ── Channel width bounds ──────────────────────────────────────────────
        // ChannelResult does not expose widthPct() directly — compute from prices.
        double support    = channel.supportPrice();
        double resistance = channel.resistancePrice();
        double channelWidth = resistance - support;
        // widthPct = (resistance - support) / support * 100
        double widthPct = support > 0 ? (channelWidth / support) * 100.0 : 0.0;
        if (widthPct < MIN_WIDTH_PCT || widthPct > MAX_WIDTH_PCT) {
            log.trace("[SCALP] {} — channel width {:.2f}% outside [{},{}]%",
                    symbol, widthPct, MIN_WIDTH_PCT, MAX_WIDTH_PCT);
            return;
        }

        // ── Latest candle ─────────────────────────────────────────────────────
        Candle candle = latestCandles.get(symbol);
        if (candle == null) return;

        double closePrice    = candle.getClose().doubleValue();
        double openPrice     = candle.getOpen().doubleValue();
        double highPrice     = candle.getHigh().doubleValue();
        double lowPrice      = candle.getLow().doubleValue();
        // support, resistance, channelWidth already declared above in width check block

        if (channelWidth <= 0) return;

        // ── FIX 2: Minimum stock price ────────────────────────────────────────
        if (closePrice < MIN_STOCK_PRICE) {
            log.debug("[SCALP] {} — price ₹{:.2f} below minimum ₹{}. Skipping.",
                    symbol, closePrice, MIN_STOCK_PRICE);
            return;
        }

        // ── Proximity check: is price near support (BUY) or resistance (SELL)? ──
        // FIX 4: Uses PROXIMITY = 0.005 (was 0.004)
        boolean nearSupport    = (closePrice - support) / support <= PROXIMITY;
        boolean nearResistance = (resistance - closePrice) / resistance <= PROXIMITY;

        if (isBuy && !nearSupport) {
            log.trace("[SCALP] {} — not near support for BUY. price={:.2f} support={:.2f}",
                    symbol, closePrice, support);
            return;
        }
        if (!isBuy && !nearResistance) {
            log.trace("[SCALP] {} — not near resistance for SELL. price={:.2f} resistance={:.2f}",
                    symbol, closePrice, resistance);
            return;
        }

        // ── FIX 5: Range position gate (widened) ─────────────────────────────
        double rangePos = (closePrice - support) / channelWidth;
        if (isBuy && rangePos > RANGE_POS_BUY_MAX) {
            log.trace("[SCALP] {} — rangePos {:.2f} > {:.2f} for BUY (too far from support)",
                    symbol, rangePos, RANGE_POS_BUY_MAX);
            return;
        }
        if (!isBuy && rangePos < RANGE_POS_SELL_MIN) {
            log.trace("[SCALP] {} — rangePos {:.2f} < {:.2f} for SELL (too far from resistance)",
                    symbol, rangePos, RANGE_POS_SELL_MIN);
            return;
        }

        // ── RVOL gate ─────────────────────────────────────────────────────────
        double rvol = rvolService.getRvolNow(symbol, candle.getVolume());
        if (rvol < minRvol) {
            log.trace("[SCALP] {} — RVOL {:.2f} < minimum {:.2f}", symbol, rvol, minRvol);
            return;
        }

        // ── Candle quality: body is meaningful (not a doji with zero body) ────
        // This is a prerequisite for pattern recognition.
        double candleRange = highPrice - lowPrice;
        if (candleRange <= 0) return;

        double body     = Math.abs(closePrice - openPrice);
        double bodyPct  = candleRange > 0 ? (body / candleRange) : 0;

        // Reject extremely thin candles (body < 5% of range = near-zero body)
        // These are pure dojis where open ≈ close and no pattern is meaningful.
        if (bodyPct < 0.05) {
            log.trace("[SCALP] {} — candle body {:.1f}% of range — pure doji, skipping",
                    symbol, bodyPct * 100);
            return;
        }

        // ── FIX 1: Reversal candle check with strong-pressure bypass ─────────
        boolean validCandle;
        if (isBuy) {
            if (isStrongPressure) {
                // Strong pressure bypass: accept any bullish candle (close > open)
                validCandle = closePrice > openPrice;
                if (!validCandle) {
                    log.trace("[SCALP] {} — strong pressure bypass active but candle not bullish", symbol);
                    return;
                }
                log.debug("[SCALP] {} — strong pressure bypass ({:.2f}x ≥ {:.2f}x): " +
                                "accepting bullish candle without hammer pattern",
                        symbol, pressure.ratio(), STRONG_PRESSURE_BYPASS_RATIO);
            } else {
                // Standard: require hammer pattern (bullish reversal near support)
                validCandle = isBullishReversal(closePrice, openPrice, highPrice, lowPrice, candleRange, body);
                if (!validCandle) {
                    log.trace("[SCALP] {} — no bullish reversal candle pattern", symbol);
                    return;
                }
            }
        } else {
            if (isStrongPressure) {
                // Strong pressure bypass: accept any bearish candle (close < open)
                validCandle = closePrice < openPrice;
                if (!validCandle) {
                    log.trace("[SCALP] {} — strong pressure bypass active but candle not bearish", symbol);
                    return;
                }
                log.debug("[SCALP] {} — strong pressure bypass ({:.2f}x ≥ {:.2f}x): " +
                                "accepting bearish candle without shooting-star pattern",
                        symbol, pressure.ratio(), STRONG_PRESSURE_BYPASS_RATIO);
            } else {
                // Standard: require shooting-star pattern (bearish reversal near resistance)
                validCandle = isBearishReversal(closePrice, openPrice, highPrice, lowPrice, candleRange, body);
                if (!validCandle) {
                    log.trace("[SCALP] {} — no bearish reversal candle pattern", symbol);
                    return;
                }
            }
        }

        // ── Sector gate (light) ───────────────────────────────────────────────
        // For scalp, we use a lighter sector filter — just ensure sector isn't
        // strongly opposing. Scalp trades rely on channel mean-reversion more than
        // sector momentum, so we're more permissive.
        String sectorName = sectorClassify.getSector(symbol);
        SectorStrengthService.SectorData sectorData = sectorStrength.getSector(sectorName);
        double sectorChg = sectorData.changePercent();

        // Reject if sector is STRONGLY opposing (e.g. -1% sector for a BUY trade)
        if (isBuy && sectorChg < -0.5) {
            log.trace("[SCALP] {} — sector {} strongly bearish ({:.2f}%) for BUY scalp",
                    symbol, sectorName, sectorChg);
            return;
        }
        if (!isBuy && sectorChg > 0.5) {
            log.trace("[SCALP] {} — sector {} strongly bullish ({:.2f}%) for SELL scalp",
                    symbol, sectorName, sectorChg);
            return;
        }

        // ── Build trade parameters ────────────────────────────────────────────
        BigDecimal entryPrice = candle.getClose().setScale(2, RoundingMode.HALF_UP);
        BigDecimal stopLoss;
        BigDecimal target1;
        BigDecimal target2;

        if (isBuy) {
            // SL: just below support (0.2% below)
            double slLevel = support * 0.998;
            stopLoss = BigDecimal.valueOf(slLevel).setScale(2, RoundingMode.FLOOR);
            // T1: resistance level (full channel width)
            target1  = BigDecimal.valueOf(resistance).setScale(2, RoundingMode.HALF_UP);
            // T2: resistance + half channel width extension
            double t2Level = resistance + (channelWidth * 0.5);
            target2  = BigDecimal.valueOf(t2Level).setScale(2, RoundingMode.HALF_UP);
        } else {
            // SL: just above resistance (0.2% above)
            double slLevel = resistance * 1.002;
            stopLoss = BigDecimal.valueOf(slLevel).setScale(2, RoundingMode.CEILING);
            // T1: support level (full channel width)
            target1  = BigDecimal.valueOf(support).setScale(2, RoundingMode.HALF_UP);
            // T2: support - half channel width extension
            double t2Level = support - (channelWidth * 0.5);
            target2  = BigDecimal.valueOf(t2Level).setScale(2, RoundingMode.HALF_UP);
        }

        BigDecimal risk = entryPrice.subtract(stopLoss).abs();
        if (risk.compareTo(BigDecimal.ZERO) == 0) {
            log.trace("[SCALP] {} — zero risk distance", symbol);
            return;
        }

        // ── FIX 3: Minimum SL distance ────────────────────────────────────────
        double slPct = risk.doubleValue() / entryPrice.doubleValue();
        if (slPct < MIN_SL_PCT) {
            log.debug("[SCALP] {} — SL {:.3f}% below minimum {:.2f}%. " +
                            "Channel too narrow for safe scalp. entry={} sl={}",
                    symbol, slPct * 100, MIN_SL_PCT * 100, entryPrice, stopLoss);
            return;
        }

        // ── Position sizing ───────────────────────────────────────────────────
        TradeDirection direction = isBuy ? TradeDirection.LONG : TradeDirection.SHORT;
        PositionSizerService.PositionSize pos =
                positionSizer.calculate(cap, entryPrice, stopLoss, symbol, direction.name());
        if (!pos.isValid() || pos.quantity() <= 0) {
            log.debug("[SCALP] {} — position sizing failed: {}", symbol, pos.invalidReason());
            return;
        }

        // Verify RR after sizing (T1 at channel boundary)
        double reward  = target1.subtract(entryPrice).abs().doubleValue();
        double rrRatio = reward / risk.doubleValue();

        // Scalp minimum RR is lower (1.2) since target is the full channel width
        if (rrRatio < 1.2) {
            log.trace("[SCALP] {} — RR {:.2f} below scalp minimum 1.2", symbol, rrRatio);
            return;
        }

        // ── Resolve instrument token ──────────────────────────────────────────
        // ChannelResult does not expose instrumentToken — use 0L (safe for PAPER mode).
        long instrumentToken = 0L;

        // ── Build score ───────────────────────────────────────────────────────
        int scoreRvol     = rvol >= 2.0 ? 25 : rvol >= 1.5 ? 18 : rvol >= 1.1 ? 12 : 5;
        int scorePressure = isStrongPressure ? 25 : 15;
        int scoreChannel  = channel.isHighQuality() ? 20 : 12;
        int scoreCandle   = isStrongPressure ? 15 : 20;  // full pattern = higher score
        int scoreRR       = rrRatio >= 2.0 ? 20 : rrRatio >= 1.5 ? 12 : 8;
        int totalScore    = scoreRvol + scorePressure + scoreChannel + scoreCandle + scoreRR;

        // ── Decide entry type label ───────────────────────────────────────────
        String entryType = isStrongPressure ? "PRESSURE_SCALP" : "REVERSAL_SCALP";
        String channelLabel = String.format("SIDEWAYS %.2f%%", widthPct);

        // ── Fire signal ───────────────────────────────────────────────────────
        log.info("[SCALP] 🚀 SIGNAL: {} | {} | entry={} | sl={} | T1={} | T2={} | " +
                        "channel={} | RVOL={:.2f} | RR={:.2f} | score={} | " +
                        "strongPressure={} | bypass={}",
                symbol, direction, entryPrice, stopLoss, target1, target2,
                channelLabel, rvol, rrRatio, totalScore,
                isStrongPressure, isStrongPressure && !(isBullishReversal(
                        closePrice, openPrice, highPrice, lowPrice, candleRange, body)));

        SmartChannelPullbackSignalEvent signal = new SmartChannelPullbackSignalEvent(
                this,
                symbol,
                instrumentToken,
                direction,
                entryPrice,
                stopLoss,
                target1,
                target2,
                pos.quantity(),
                pos.actualRisk(),
                STRATEGY_NAME,
                totalScore,
                sectorName,
                sectorChg,
                channelLabel,
                entryType,
                pressure.ratio(),
                rvol,
                false,
                "MARKET",
                isBuy ? "NEAR_SUPPORT_SCALP" : "NEAR_RESISTANCE_SCALP",
                0,
                scoreRvol,
                scorePressure,
                scoreChannel,
                scoreCandle,
                scoreRR,
                totalScore,
                timeStopMinutes
        );

        publisher.publishEvent(signal);

        lastSignalTime.put(symbol, System.currentTimeMillis());
        activeSignals.add(symbol);
        sessionSignalCount++;

        log.info("[SCALP] Signal #{}/{} fired for {} (session)",
                sessionSignalCount, maxSignalsPerSession, symbol);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CANDLE PATTERN HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Hammer / bullish engulfing near support (BUY reversal signal).
     *
     * Requirements:
     *   1. Bullish candle: close > open
     *   2. Lower wick >= body (long lower shadow shows rejection of lower prices)
     *   3. Close in upper half of candle range (close - low) / range >= 0.50
     *   4. Lower wick >= 15% of full candle range (non-trivial wick)
     *
     * This is unchanged from V1 — only the bypass condition is new (FIX 1).
     */
    private boolean isBullishReversal(double close, double open,
                                      double high, double low,
                                      double range, double body) {
        if (close <= open) return false;                        // must be bullish
        double lowerWick = open - low;                         // body bottom to low
        if (lowerWick < body) return false;                    // wick must be >= body
        if ((close - low) / range < 0.50) return false;        // close in upper half
        if (lowerWick / range < 0.15) return false;            // wick must be meaningful
        return true;
    }

    /**
     * Shooting-star / bearish engulfing near resistance (SELL reversal signal).
     *
     * Requirements:
     *   1. Bearish candle: close < open
     *   2. Upper wick >= body (long upper shadow shows rejection of higher prices)
     *   3. Close in lower half of candle range (high - close) / range >= 0.50
     *   4. Upper wick >= 15% of full candle range (non-trivial wick)
     */
    private boolean isBearishReversal(double close, double open,
                                      double high, double low,
                                      double range, double body) {
        if (close >= open) return false;                        // must be bearish
        double upperWick = high - open;                         // body top to high
        if (upperWick < body) return false;                     // wick must be >= body
        if ((high - close) / range < 0.50) return false;       // close in lower half
        if (upperWick / range < 0.15) return false;             // wick must be meaningful
        return true;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SIGNAL LOCK RELEASE (called by SmartChannelSignalHandler on trade close)
    // ══════════════════════════════════════════════════════════════════════════

    public void onSignalClosed(String symbol) {
        activeSignals.remove(symbol);
        log.debug("[SCALP] Signal lock released for {}", symbol);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DAILY RESET
    // ══════════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyReset() {
        sessionSignalCount = 0;
        activeSignals.clear();
        lastSignalTime.clear();
        latestCandles.clear();
        log.info("[SCALP] Daily reset complete — {} signal slots available", maxSignalsPerSession);
    }

    // ── Dashboard helpers ─────────────────────────────────────────────────────

    public boolean isEnabled()             { return strategyEnabled; }
    public int     getSessionSignalCount() { return sessionSignalCount; }
    public int     getActiveSignalCount()  { return activeSignals.size(); }
    public Set<String> getActiveSignals()  { return Collections.unmodifiableSet(activeSignals); }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private BigDecimal resolveCapital() {
        return "PAPER".equalsIgnoreCase(tradingMode)
                ? paperAccount.getCapital()
                : capital;
    }
}