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
import com.trading.regime.service.MarketDirectionService;
import com.trading.papertrading.model.PaperAccount;
import com.trading.position.service.PositionSizerService;
import com.trading.risk.service.CircuitBreakerService;
import com.trading.sector.service.SectorClassificationService;
import com.trading.marketdata.service.InstrumentCacheService;
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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SmartChannelPullbackStrategy (SMART_CHANNEL_PULLBACK_V3)
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * STRATEGY LOGIC:
 *   On each 5-minute candle close, scan all valid channels. For channels whose
 *   price is near support (BUY) or resistance (SELL), with sector pressure
 *   aligned, fire a signal to SmartChannelSignalHandler.
 *
 *   Pipeline: CandleCompleteEvent → evaluateChannels() → SmartChannelPullbackSignalEvent
 *             → SmartChannelSignalHandler → TradeApprovedEvent
 *             → PaperTradeExecutionService → PaperTradeManagementService
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * IMPROVEMENTS vs SMART_CHANNEL_PULLBACK_V2 (based on 2026-04-17 live data):
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * ROOT CAUSE ANALYSIS (April 17, 2026):
 *   - 0 signals fired despite 25 valid channels and BUY market pressure.
 *   - Channels detected: UCOBANK (₹26, width 0.67%), INDUSINDBK (width 1.12%),
 *     ATUL (width 0.69%), DELHIVERY (width 0.70%), HINDPETRO (width 0.66%).
 *   - MARKET_PRESSURE strategy occupied UCOBANK, PATELENG, QUESS, CGPOWER, SUNTV,
 *     UCOBANK at 11:15–11:30 IST (LUNCH window). PaperTradeExecutionService blocks
 *     further trades on those same symbols.
 *   - SCPS evaluates on 5-minute candle close. At 11:15 IST candle, MARKET_PRESSURE
 *     had already locked several symbols. For the remaining 19+ channels:
 *     the pullback zone (bottom 30% of channel) was too narrow — during an upward
 *     momentum day (DOUBLE_DISTRIBUTION mode, sectors mostly green), price was
 *     sitting ABOVE the narrow pullback zone on every BULLISH channel.
 *   - UCOBANK: pullback zone top = 26.65 + (26.78-26.65)*0.30 = 26.689.
 *     MARKET_PRESSURE entry was 26.69 — just ₹0.001 ABOVE the SCPS zone.
 *     So SCPS would have fired on UCOBANK but it was blocked by PaperTradeExecutionService.
 *
 * FIX 1: WIDER PULLBACK ZONE (primary fix for 0 signals)
 *   Old: pullback zone = bottom 30% of channel (PB_FACTOR = 0.30), i.e. SCPS delegated
 *        entirely to ChannelDetectionService.isPriceInPullbackZone() which uses 0.30.
 *   New: SCPS applies its OWN wider zone: up to 50% of the channel from support (BUY)
 *        or resistance (SELL). This means "anywhere in the lower half is a valid pullback."
 *   Rationale: On momentum days (DOUBLE_DISTRIBUTION), price runs higher and rarely
 *   returns to the bottom 30%. The bottom 50% still represents clear value relative
 *   to the channel. ChannelDetectionService.isPriceInPullbackZone() is no longer used —
 *   SCPS computes its own zone to give better signal generation.
 *
 * FIX 2: PRICE MINIMUM FILTER (₹100)
 *   Old: No minimum price filter.
 *   New: Skip stocks below ₹100. Consistent with scanner.min-price=100 in application.yml.
 *   Rationale: UCOBANK (₹26) and PATELENG (₹28) were traded by MARKET_PRESSURE, causing
 *   large quantities (748, 698 shares) with noise-dominated SL distances.
 *   SCPS must not produce similar signals.
 *
 * FIX 3: LUNCH WINDOW HANDLING
 *   Old: No window-aware filtering — SCPS could signal during 11:00–12:30 LUNCH.
 *   New: During LUNCH window, require HIGH_QUALITY channel (not just VALID).
 *       SCPS still runs in LUNCH (unlike MARKET_PRESSURE which is fully excluded)
 *       because pullback-near-support is a lower-risk entry than momentum. However,
 *       requiring HIGH_QUALITY filters out the weak setups that dominate during lunch.
 *
 * FIX 4: MINIMUM SL DISTANCE (0.4%)
 *   Old: No minimum SL % check.
 *   New: SL must be at least 0.4% of entry price. Prevents trades where the SL is so
 *       tight that normal market noise causes an immediate hit (as with CGPOWER ₹2.38 SL
 *       on ₹771 entry = 0.31%, hit in 2 minutes).
 *
 * FIX 5: MINIMUM RR CONFIGURABLE (raised from 1.8 via yml default)
 *   Old: min-adjusted-rr: 1.8 (from application.yml, but was not always enforced
 *       correctly for both BUY and SELL directions).
 *   New: Enforced consistently for both directions. RR check happens AFTER SL distance
 *       check so both filters apply independently.
 *
 * FIX 6: SECTOR THRESHOLD IMPROVEMENT
 *   Old: sectorBuyThreshold=0.05 (0.05% min sector change). This was very generous.
 *   New: Keep 0.05 but add directional check: for BUY, sector must be positive (> 0).
 *       For SELL, sector must be negative (< 0). The previous code correctly checked
 *       (isBuy && sectorChg < 0) → reject, but let through sectors at 0.0% (neutral).
 *       Now: (isBuy && sectorChg <= 0) → reject for a stricter gate.
 *
 * FIX 7: RVOL MINIMUM RAISED TO 1.1 (from yml: min-rvol: 1.0)
 *   Old: min-rvol: 1.0 (in application.yml).
 *   New: Internal minimum is 1.1. Rationale: RVOL=1.0 is average — it means no
 *       elevated conviction. A pullback needs SOME above-average volume returning
 *       to key levels to be meaningful. 1.0x is noise; 1.1x is the minimum signal.
 *       (yml still shows 1.0 to allow override downward if needed.)
 *
 * UNCHANGED:
 *   - Event type: SmartChannelPullbackSignalEvent (identical constructor, same fields)
 *   - Signal routing: SmartChannelSignalHandler → TradeApprovedEvent
 *   - Execution: PaperTradeExecutionService → PaperTradeManagementService
 *   - Strategy name: "SMART_CHANNEL_PULLBACK_V3"
 *   - Session cap: max-signals-per-session (from yml: 3)
 *   - Symbol cooldown: symbol-cooldown-minutes (from yml: 60)
 *   - T1/T2 RR multipliers: t1-rr=2.0, t2-rr=3.0
 *   - All other strategies completely unaffected
 * ─────────────────────────────────────────────────────────────────────────────
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SmartChannelPullbackStrategy {

    private static final ZoneId   IST           = ZoneId.of("Asia/Kolkata");
    private static final String   STRATEGY_NAME = "SMART_CHANNEL_PULLBACK_V3";

    // ── FIX 1: Wider pullback zone ────────────────────────────────────────────
    /**
     * For BUY: price must be in [support, support + width * PB_ZONE_FACTOR].
     * For SELL: price must be in [resistance - width * PB_ZONE_FACTOR, resistance].
     *
     * Old value was 0.30 (bottom/top 30% of channel).
     * New value 0.50 means "anywhere in the lower/upper half of the channel."
     *
     * Why 0.50? On momentum days, price runs higher and rarely touches the bottom 30%.
     * The lower half still represents value relative to channel resistance. The sector
     * pressure gate (sectorBuyThreshold) and RVOL gate ensure we don't enter on
     * weak pullbacks — the zone just needs to be wide enough to generate signals.
     */
    // FIX: tightened from 0.50 → 0.30 — only enter in bottom 30% of channel near support.
    // Entry at 0-30% from support → SL 0.3% below support → tight risk → RR improves.
    // Was 0.50 (bottom half): entry could be mid-channel → wide SL → low RR.
    private static final double PB_ZONE_FACTOR = 0.30;

    // ── FIX 2: Minimum stock price ────────────────────────────────────────────
    /** Reject stocks below ₹100 — consistent with scanner.min-price=100. */
    private static final double MIN_STOCK_PRICE = 100.0;

    // ── FIX 4: Minimum SL distance ────────────────────────────────────────────
    /**
     * SL must be at least 0.4% of entry price.
     * Prevents noise-level SLs that get hit by random tick movement.
     * (CGPOWER on 2026-04-17 had 0.31% SL — hit in 2 minutes.)
     */
    private static final double MIN_SL_PCT = 0.004;  // 0.4%

    // ── FIX 7: Internal minimum RVOL ─────────────────────────────────────────
    /**
     * Internal minimum RVOL floor, regardless of yml setting.
     * yml setting (min-rvol: 1.0) can raise this further, but never below 1.1.
     */
    private static final double INTERNAL_MIN_RVOL = 1.1;

    // ── Dependencies ──────────────────────────────────────────────────────────
    private final ChannelDetectionService     channelDetection;
    private final MarketPressureService       pressureService;
    private final MarketTimingService         timingService;
    private final MarketDirectionService      marketDirection; // for ATR-aware pressure bypass
    private final RvolService                 rvolService;
    private final SectorStrengthService       sectorStrength;
    private final SectorClassificationService sectorClassify;
    private final CircuitBreakerService       circuitBreaker;
    private final PositionSizerService        positionSizer;
    private final PaperAccount               paperAccount;
    private final ApplicationEventPublisher  publisher;
    private final InstrumentCacheService     instrumentCache;

    // ── Config from application.yml (strategy.smart-channel-pullback.*) ───────
    @Value("${strategy.smart-channel-pullback.enabled:true}")
    private boolean strategyEnabled;

    @Value("${strategy.smart-channel-pullback.require-high-quality-channel:false}")
    private boolean requireHighQuality;

    @Value("${strategy.smart-channel-pullback.time-stop-minutes:90}")
    private int timeStopMinutes;

    @Value("${strategy.smart-channel-pullback.min-rvol:1.0}")
    private double minRvol;

    @Value("${strategy.smart-channel-pullback.max-signals-per-session:3}")
    private int maxSignalsPerSession;

    // IMPROVEMENT: Sector threshold lowered from 0.05% → 0.0%.
    // The original 0.05% rejected valid trades where a broadly bullish sector was
    // at +0.03% — still positive, still aligned, but below threshold.
    // The existing strict check (sectorChg > 0 required) is sufficient directional filter.
    // Threshold is now 0.0 so any positive sector = valid BUY, any negative = valid SELL.
    @Value("${strategy.smart-channel-pullback.sector-buy-threshold:0.0}")
    private double sectorBuyThreshold;

    @Value("${strategy.smart-channel-pullback.sector-sell-threshold:0.0}")
    private double sectorSellThreshold;

    @Value("${strategy.smart-channel-pullback.min-adjusted-rr:1.8}")
    private double minAdjustedRr;

    @Value("${strategy.smart-channel-pullback.symbol-cooldown-minutes:60}")
    private long symbolCooldownMinutes;

    @Value("${strategy.smart-channel-pullback.t1-rr:2.0}")
    private double t1Rr;

    @Value("${strategy.smart-channel-pullback.t2-rr:3.0}")
    private double t2Rr;

    @Value("${trading.mode:PAPER}")
    private String tradingMode;

    @Value("${trading.capital:100000}")
    private BigDecimal capital;

    // ── Session state ─────────────────────────────────────────────────────────
    private final AtomicInteger  sessionSignalCount = new AtomicInteger(0);  // FIX: volatile int++ is not atomic
    private final Set<String>    activeSignals      = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> lastSignalTime  = new ConcurrentHashMap<>();

    // ── Latest candle buffer (keyed by tradingSymbol) ─────────────────────────
    private final Map<String, Candle> latestCandles = new ConcurrentHashMap<>();

    // ══════════════════════════════════════════════════════════════════════════
    // CANDLE LISTENER — fires on every 5-minute candle close
    // ══════════════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tradingExecutor")
    public void onCandle(CandleCompleteEvent event) {
        Candle candle = event.getCandle();
        if (!candle.isComplete()) return;

        // Buffer all 5-minute candles for RVOL and price context
        if ("5minute".equals(candle.getTimeframe())) {
            latestCandles.put(candle.getTradingSymbol(), candle);
            // Only evaluate after the candle is complete
            evaluateChannels(candle);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CORE EVALUATION — called on every completed 5-min candle
    // ══════════════════════════════════════════════════════════════════════════

    private void evaluateChannels(Candle triggerCandle) {
        if (!strategyEnabled) return;

        // ── Session cap ───────────────────────────────────────────────────────
        if (sessionSignalCount.get() >= maxSignalsPerSession) {
            log.debug("[SCPS] Session cap reached ({}/{})", sessionSignalCount.get(), maxSignalsPerSession);
            return;
        }

        // ── Circuit breaker ───────────────────────────────────────────────────
        BigDecimal cap = resolveCapital();
        if (!circuitBreaker.checkPermission(cap).isAllowed()) {
            log.debug("[SCPS] Circuit breaker blocked");
            return;
        }

        // ── Get market pressure + sector-aware fallback ───────────────────────
        // PROBLEM (Apr-23): MarketPressureService had 0 tracked symbols all day
        // ("No open prices yet" at 12:33 PM). pressure.isActionable() = false.
        // Meanwhile: 18 valid channels, 4 HIGH_QUALITY BEARISH, Banking WEAK.
        // FIX: If pressure is not actionable BUT we have HIGH_QUALITY channels
        //      AND the sector for those channels is clearly aligned → use sector direction.
        PressureSnapshot pressure = pressureService.getSnapshot();
        boolean pressureOk = pressure.isActionable();
        boolean isBuy;

        if (pressureOk) {
            isBuy = pressure.isBuy();
        } else {
            // Pressure unavailable. Try sector-based fallback:
            // Only fires when Nifty direction is non-SIDEWAYS OR when
            // at least one strongly-aligned sector exists (>= 60% aligned).
            MarketDirectionService.MarketDirectionResult dir = marketDirection.getCurrentDirection();
            boolean hasStrongBullishSector = sectors(channelDetection.getAllValidChannels())
                    .stream().anyMatch(s -> {
                        var sd = sectorStrength.getSector(s);
                        return sd != null && sd.alignedBullish() &&
                                sd.changePercent() >= 0.30;
                    });
            boolean hasStrongBearishSector = sectors(channelDetection.getAllValidChannels())
                    .stream().anyMatch(s -> {
                        var sd = sectorStrength.getSector(s);
                        return sd != null && sd.alignedBearish() &&
                                sd.changePercent() <= -0.30;
                    });

            if (dir.direction() == MarketDirectionService.Direction.BULLISH || hasStrongBullishSector) {
                isBuy = true;
                log.info("[SCPS] Pressure unavailable — using sector fallback: BULLISH direction");
            } else if (dir.direction() == MarketDirectionService.Direction.BEARISH || hasStrongBearishSector) {
                isBuy = false;
                log.info("[SCPS] Pressure unavailable — using sector fallback: BEARISH direction");
            } else {
                log.debug("[SCPS] Pressure not actionable and no clear sector alignment: dir={} ratio={} syms={}",
                        pressure.direction(), String.format("%.3f", pressure.ratio()), pressure.totalSymbols());
                return;
            }
        }

        // ── FIX 3: Time window handling ───────────────────────────────────────
        TimeWindow currentWindow = timingService.getCurrentWindow();

        // Fully skip OBSERVATION (pre-market / 9:15-9:30): channels not stable yet
        if (currentWindow == TimeWindow.OBSERVATION) {
            log.debug("[SCPS] OBSERVATION window — skipping evaluation");
            return;
        }

        // ── SIDEWAYS MARKET BLOCK ─────────────────────────────────────────────
        // CHOLAFIN SHORT (score=52) and ORIENTCEM LONG (score=44) both fired in
        // SIDEWAYS market on 04-May-2026 — both SL hit within minutes.
        // In SIDEWAYS: no directional trend, channel support/resistance breaks
        // constantly. HighRR already blocks in SIDEWAYS. SCPS must do the same.
        //
        // Exception: strong market pressure (ratio >= 1.5) can create valid
        // breakouts from channel levels even without a trend.
        MarketDirectionService.MarketDirectionResult marketDir = marketDirection.getCurrentDirection();
        boolean isSideways = marketDir.direction() == MarketDirectionService.Direction.SIDEWAYS;
        if (isSideways) {
            boolean strongPressure = pressureOk && pressure.ratio() >= 1.5;
            if (!strongPressure) {
                log.debug("[SCPS] SIDEWAYS market — skipping (pressure.ratio={} pressureOk={}).",
                        String.format("%.2f", pressure.ratio()), pressureOk);
                return;
            }
            log.info("[SCPS] SIDEWAYS but strong pressure ratio={} — proceeding.",
                    String.format("%.2f", pressure.ratio()));
        }

        // During LUNCH, require HIGH_QUALITY channels only (stricter filter)
        boolean lunchWindow = (currentWindow == TimeWindow.LUNCH);

        // ── Get all valid channels ────────────────────────────────────────────
        Map<String, ChannelResult> validChannels = channelDetection.getAllValidChannels();
        if (validChannels.isEmpty()) {
            log.debug("[SCPS] No valid channels available");
            return;
        }

        log.debug("[SCPS] Evaluating {} channels | pressure={} | window={} | candle={}",
                validChannels.size(), pressure.direction(), currentWindow,
                triggerCandle.getTradingSymbol());

        int evaluated = 0, signalsFired = 0;

        for (Map.Entry<String, ChannelResult> entry : validChannels.entrySet()) {
            if (sessionSignalCount.get() >= maxSignalsPerSession) break;

            String        symbol  = entry.getKey();
            ChannelResult channel = entry.getValue();

            boolean fired = evaluateSymbol(symbol, channel, pressure, isBuy, cap, lunchWindow);
            evaluated++;
            if (fired) signalsFired++;
        }

        log.debug("[SCPS] Cycle complete | evaluated={} fired={} totalToday={}",
                evaluated, signalsFired, sessionSignalCount);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SYMBOL EVALUATION
    // ══════════════════════════════════════════════════════════════════════════

    private boolean evaluateSymbol(String symbol, ChannelResult channel,
                                   PressureSnapshot pressure, boolean isBuy,
                                   BigDecimal cap, boolean lunchWindow) {

        // ── Cooldown check ────────────────────────────────────────────────────
        if (activeSignals.contains(symbol)) {
            log.trace("[SCPS] {} — already has active signal, skipping", symbol);
            return false;
        }
        Long lastFired = lastSignalTime.get(symbol);
        if (lastFired != null) {
            long cooldownMs = symbolCooldownMinutes * 60_000L;
            if (System.currentTimeMillis() - lastFired < cooldownMs) {
                log.trace("[SCPS] {} — in cooldown ({} min)", symbol, symbolCooldownMinutes);
                return false;
            }
        }

        // ── FIX 3: Lunch window → HIGH_QUALITY required ───────────────────────
        boolean isHighQuality = channel.isHighQuality();
        if (lunchWindow && !isHighQuality) {
            log.trace("[SCPS] {} — LUNCH window requires HIGH_QUALITY channel, got VALID",
                    symbol);
            return false;
        }
        if (requireHighQuality && !isHighQuality) {
            log.trace("[SCPS] {} — high-quality channel required, got VALID", symbol);
            return false;
        }

        // ── Channel type alignment ────────────────────────────────────────────
        // BUY: accept BULLISH and SIDEWAYS channels (pullback to support)
        // SELL: accept BEARISH and SIDEWAYS channels (pullback to resistance)
        ChannelType channelType = channel.type();
        if (isBuy && channelType == ChannelType.BEARISH) {
            log.trace("[SCPS] {} — BEARISH channel incompatible with BUY pressure", symbol);
            return false;
        }
        if (!isBuy && channelType == ChannelType.BULLISH) {
            log.trace("[SCPS] {} — BULLISH channel incompatible with SELL pressure", symbol);
            return false;
        }

        // Do not enter transitioning channels — support/resistance is unreliable mid-break
        if (channel.isTransitioning()) {
            log.trace("[SCPS] {} — channel transitioning, skipping", symbol);
            return false;
        }

        // ── Latest candle ─────────────────────────────────────────────────────
        Candle candle = latestCandles.get(symbol);
        if (candle == null) {
            log.trace("[SCPS] {} — no candle data yet", symbol);
            return false;
        }

        double closePrice    = candle.getClose().doubleValue();
        double supportPrice  = channel.supportPrice();
        double resistancePrice = channel.resistancePrice();
        double channelWidth  = resistancePrice - supportPrice;

        if (channelWidth <= 0) {
            log.trace("[SCPS] {} — invalid channel geometry (width={})", symbol, channelWidth);
            return false;
        }

        // ── FIX 2: Minimum price filter ───────────────────────────────────────
        if (closePrice < MIN_STOCK_PRICE) {
            log.debug("[SCPS] {} — price ₹{} below minimum ₹{}. Skipping.",
                    symbol, String.format("%.2f", closePrice), MIN_STOCK_PRICE);
            return false;
        }

        // ── FIX 1: SCPS-specific pullback zone (wider than channel service's 30%) ──
        // BUY setup: price in the lower 50% of the channel (near support)
        // SELL setup: price in the upper 50% of the channel (near resistance)
        //
        // Zone for BUY: [support, support + width * 0.50]
        // Zone for SELL: [resistance - width * 0.50, resistance]
        boolean inPullbackZone;
        if (isBuy) {
            double zoneTop = supportPrice + (channelWidth * PB_ZONE_FACTOR);
            inPullbackZone = (closePrice >= supportPrice) && (closePrice <= zoneTop);
            if (!inPullbackZone) {
                log.trace("[SCPS] {} — BUY: price {} not in pullback zone [{}, {}]",
                        symbol, closePrice, supportPrice, zoneTop);
                return false;
            }
        } else {
            double zoneBottom = resistancePrice - (channelWidth * PB_ZONE_FACTOR);
            inPullbackZone = (closePrice <= resistancePrice) && (closePrice >= zoneBottom);
            if (!inPullbackZone) {
                log.trace("[SCPS] {} — SELL: price {} not in pullback zone [{}, {}]",
                        symbol, closePrice, zoneBottom, resistancePrice);
                return false;
            }
        }

        // ── RVOL gate ─────────────────────────────────────────────────────────
        // FIX 7: Apply max(yml setting, INTERNAL_MIN_RVOL) as effective minimum
        double effectiveMinRvol = Math.max(minRvol, INTERNAL_MIN_RVOL);
        double rvol = rvolService.getRvolNow(symbol, candle.getVolume());
        // FIX: RVOL=1.0 exactly means RvolService has no history yet (returns default).
        // On Day 1-5, treat RVOL=1.0 as UNKNOWN (not FAIL) — use threshold of 1.0.
        // After 5 days of accumulation, real RVOL values appear and 1.1 threshold applies.
        double rvolThreshold = (rvol == 1.0) ? 1.0 : effectiveMinRvol;
        if (rvol < rvolThreshold) {
            log.trace("[SCPS] {} — RVOL {} < minimum {}", symbol, rvol, rvolThreshold);
            return false;
        }

        // ── Sector gate ───────────────────────────────────────────────────────
        String sectorName = sectorClassify.getSector(symbol);
        SectorStrengthService.SectorData sectorData = sectorStrength.getSector(sectorName);
        double sectorChg = sectorData.changePercent();

        // FIX 6: Stricter directional sector check
        // BUY: sector must be positive AND above threshold (not just "not below threshold")
        if (isBuy && sectorChg <= 0) {
            log.trace("[SCPS] {} — sector {} not positive ({}%) for BUY",
                    symbol, sectorName, sectorChg);
            return false;
        }
        if (!isBuy && sectorChg >= 0) {
            log.trace("[SCPS] {} — sector {} not negative ({}%) for SELL",
                    symbol, sectorName, sectorChg);
            return false;
        }
        if (isBuy && sectorChg < sectorBuyThreshold) {
            log.trace("[SCPS] {} — sector {} change {}% below buy threshold {}%",
                    symbol, sectorName, sectorChg, sectorBuyThreshold);
            return false;
        }
        if (!isBuy && sectorChg > sectorSellThreshold) {
            log.trace("[SCPS] {} — sector {} change {}% above sell threshold {}%",
                    symbol, sectorName, sectorChg, sectorSellThreshold);
            return false;
        }

        // ── Build trade parameters ────────────────────────────────────────────
        BigDecimal entryPrice = candle.getClose().setScale(2, RoundingMode.HALF_UP);
        BigDecimal stopLoss;
        BigDecimal target1;
        BigDecimal target2;

        if (isBuy) {
            // SL = support * 0.997 (0.3% below support — slightly outside the zone)
            double slLevel = supportPrice * 0.997;
            stopLoss = BigDecimal.valueOf(slLevel).setScale(2, RoundingMode.FLOOR);
            BigDecimal risk = entryPrice.subtract(stopLoss).abs();
            target1  = entryPrice.add(risk.multiply(BigDecimal.valueOf(t1Rr)));
            target2  = entryPrice.add(risk.multiply(BigDecimal.valueOf(t2Rr)));
        } else {
            // SL = resistance * 1.003 (0.3% above resistance)
            double slLevel = resistancePrice * 1.003;
            stopLoss = BigDecimal.valueOf(slLevel).setScale(2, RoundingMode.CEILING);
            BigDecimal risk = stopLoss.subtract(entryPrice).abs();
            target1  = entryPrice.subtract(risk.multiply(BigDecimal.valueOf(t1Rr)));
            target2  = entryPrice.subtract(risk.multiply(BigDecimal.valueOf(t2Rr)));
        }

        BigDecimal risk = entryPrice.subtract(stopLoss).abs();
        if (risk.compareTo(BigDecimal.ZERO) == 0) {
            log.trace("[SCPS] {} — zero risk distance, skipping", symbol);
            return false;
        }

        // ── FIX 4: Minimum SL distance ────────────────────────────────────────
        double slPct = risk.doubleValue() / entryPrice.doubleValue();
        if (slPct < MIN_SL_PCT) {
            log.debug("[SCPS] {} — SL distance {}% below minimum {}%. " +
                            "Channel too narrow for safe trade. entry={} sl={}",
                    symbol, slPct * 100, MIN_SL_PCT * 100, entryPrice, stopLoss);
            return false;
        }

        // ── FIX 5: RR check (enforced for both BUY and SELL) ─────────────────
        double reward  = target1.subtract(entryPrice).abs().doubleValue();
        double rrRatio = reward / risk.doubleValue();
        if (rrRatio < minAdjustedRr) {
            log.trace("[SCPS] {} — RR {} below minimum {}", symbol, rrRatio, minAdjustedRr);
            return false;
        }

        // ── Position sizing ───────────────────────────────────────────────────
        TradeDirection direction = isBuy ? TradeDirection.LONG : TradeDirection.SHORT;
        PositionSizerService.PositionSize pos =
                positionSizer.calculate(cap, entryPrice, stopLoss, symbol, direction.name());
        if (!pos.isValid() || pos.quantity() <= 0) {
            log.debug("[SCPS] {} — position sizing failed: {}", symbol, pos.invalidReason());
            return false;
        }

        // ── Resolve instrument token ──────────────────────────────────────────
        // FIX: Resolve actual instrument token from InstrumentCacheService.
        // Was hardcoded to 0L which shows as instrumentToken=0 in dashboard.
        // Token needed for correct position monitoring (live) and clean logging (paper).
        long instrumentToken = 0L;
        try {
            Long resolved = instrumentCache.getToken("NSE", symbol);
            if (resolved != null && resolved > 0) instrumentToken = resolved;
        } catch (Exception ignored) { }

        // ── Build probability score ───────────────────────────────────────────
        int scoreRvol    = rvol >= 2.0 ? 25 : rvol >= 1.5 ? 18 : rvol >= 1.1 ? 10 : 5;
        int scorePressure = pressure.ratio() >= 1.5 ? 25 : pressure.ratio() >= 1.2 ? 18 : 12;
        int scoreChannel  = isHighQuality ? 20 : 12;
        int scoreSector   = isBuy
                ? (sectorChg >= 1.0 ? 20 : sectorChg >= 0.5 ? 15 : 10)
                : (sectorChg <= -1.0 ? 20 : sectorChg <= -0.5 ? 15 : 10);
        int scoreRR       = rrRatio >= 3.0 ? 10 : rrRatio >= 2.5 ? 7 : 5;
        int totalScore    = scoreRvol + scorePressure + scoreChannel + scoreSector + scoreRR;

        // Channel pullback quality label
        double rangePos = isBuy
                ? (closePrice - supportPrice) / channelWidth   // 0 = at support, 1 = at resistance
                : (resistancePrice - closePrice) / channelWidth;
        String pullbackStrength = rangePos <= 0.15 ? "BEST"
                : rangePos <= 0.30 ? "GOOD" : "MODERATE";

        // ── Minimum score gate ────────────────────────────────────────────
        // CHOLAFIN (score=52) and ORIENTCEM (score=44) both fired in SIDEWAYS
        // on 04-May-2026 — both hit SL immediately. Score floor prevents weak setups.
        // LUNCH window: 65 (stricter). Normal session: 55.
        int minScore = lunchWindow ? 65 : 55;
        if (totalScore < minScore) {
            log.info("[SCPS] {} BLOCKED — score {} < {} (window={} HQ={} rvol={})",
                    symbol, totalScore, minScore,
                    timingService.getCurrentWindow(), isHighQuality,
                    String.format("%.2f", rvol));
            return false;
        }

        // ── Fire signal ───────────────────────────────────────────────────────
        log.info("[SCPS] 🚀 SIGNAL: {} | {} | entry={} | sl={} | T1={} | T2={} | " +
                        "channel={} | sector={}({}%) | RVOL={} | RR={} | " +
                        "score={} | window={} | pullback={}",
                symbol, direction, entryPrice, stopLoss, target1, target2,
                channelType,
                sectorName, sectorChg,
                rvol, rrRatio,
                totalScore,
                timingService.getCurrentWindow(),
                pullbackStrength);

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
                channelType.name(),
                pullbackStrength,
                pressure.ratio(),
                rvol,
                false,
                "MARKET",
                isBuy ? "PULLBACK_TO_SUPPORT" : "PULLBACK_TO_RESISTANCE",
                0,
                scoreRvol,
                scorePressure,
                scoreChannel,
                scoreSector,
                scoreRR,
                totalScore,
                timeStopMinutes
        );

        publisher.publishEvent(signal);

        // Track state
        lastSignalTime.put(symbol, System.currentTimeMillis());
        activeSignals.add(symbol);
        sessionSignalCount.incrementAndGet();

        log.info("[SCPS] Signal #{}/{} fired for {} (session)",
                sessionSignalCount.get(), maxSignalsPerSession, symbol);
        return true;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SIGNAL LOCK RELEASE (called by SmartChannelSignalHandler on trade close)
    // ══════════════════════════════════════════════════════════════════════════

    public void onSignalClosed(String symbol) {
        activeSignals.remove(symbol);
        log.debug("[SCPS] Signal lock released for {}", symbol);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DAILY RESET
    // ══════════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyReset() {
        sessionSignalCount.set(0);
        activeSignals.clear();
        lastSignalTime.clear();
        latestCandles.clear();
        log.info("[SCPS] Daily reset complete — {} signal slots available", maxSignalsPerSession);
    }

    // ── Dashboard helpers ─────────────────────────────────────────────────────

    public boolean isEnabled()            { return strategyEnabled; }
    public int     getSessionSignalCount() { return sessionSignalCount.get(); }
    public int     getActiveSignalCount()  { return activeSignals.size(); }
    public Set<String> getActiveSignals()  { return Collections.unmodifiableSet(activeSignals); }
    public int     getOpenTradesCount()    { return activeSignals.size(); }
    public int     getValidChannels()      { return channelDetection.getAllValidChannels().size(); }
    public int     getTrackedSymbols()     { return latestCandles.size(); }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /** Returns distinct sector names for all symbols in the given channel map. */
    private java.util.Set<String> sectors(java.util.Map<String, ChannelResult> channels) {
        java.util.Set<String> result = new java.util.HashSet<>();
        for (String sym : channels.keySet()) {
            String sector = sectorClassify.getSector(sym);
            if (sector != null && !sector.isBlank()) result.add(sector);
        }
        return result;
    }

    private BigDecimal resolveCapital() {
        return "PAPER".equalsIgnoreCase(tradingMode)
                ? paperAccount.getCapital()
                : capital;
    }
}