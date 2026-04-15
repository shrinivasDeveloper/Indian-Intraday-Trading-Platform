package com.trading.strategy.channel;

import com.trading.analysis.service.RvolService;
import com.trading.analysis.service.TechnicalAnalysisService;
import com.trading.config.StrategyConfig;
import com.trading.domain.Candle;
import com.trading.domain.enums.TradeDirection;
import com.trading.events.CandleCompleteEvent;
import com.trading.events.SmartChannelPullbackSignalEvent;
import com.trading.marketdata.service.MarketTimingService;
import com.trading.marketdata.service.VixService;
import com.trading.regime.service.MarketDirectionService;
import com.trading.risk.service.CircuitBreakerService;
import com.trading.sector.service.SectorClassificationService;
import com.trading.sector.service.SectorStrengthService;
import com.trading.position.service.PositionSizerService;
import com.trading.papertrading.model.PaperAccount;
import com.trading.marketdata.service.LatencyMonitor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SmartChannelPullbackStrategy — v2 implementation.
 *
 * MODULE: SmartChannelPullbackStrategy_v2
 *
 * TIMEFRAME LOGIC:
 *   15M → Market trend direction (STRONG_BULLISH / STRONG_BEARISH / SIDEWAYS)
 *   5M  → Channel structure engine (support/resistance/pullback zone)
 *   1M  → Optional confirmation (rejection/momentum candle)
 *
 * COMPLETE PIPELINE (per spec):
 *   1. Sector filter → rank sectors, select top/bottom
 *   2. Stock selection → 15M trend + 5M channel + pullback validation
 *   3. Entry engine → rejection candle at support/resistance
 *   4. Overextension filter → skip if price already extended
 *   5. Post-entry scoring → VWAP, RVOL, structure, entry quality
 *   6. Time filter → 09:40–14:40
 *   7. Signal → SmartChannelPullbackSignalEvent → RiskManagementService → Trade
 *
 * LOGGING:
 *   Follows Java-style logging from spec:
 *   [INFO]  Market data received, sector/stock selected, order placed
 *   [DEBUG] 15M trend, channel validation, pullback %, score
 *   [TRACE] Sector checking, stock checking
 *   [WARN]  Weak momentum → EXIT
 *
 * INTEGRATION:
 *   Fires SmartChannelPullbackSignalEvent → caught by SmartChannelSignalHandler
 *   which converts it to TradeApprovedEvent after RiskManagementService gates pass.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SmartChannelPullbackStrategy {

    private static final ZoneId IST          = ZoneId.of("Asia/Kolkata");
    private static final String STRATEGY_NAME = "SMART_CHANNEL_PULLBACK_V2";

    // ── Time filter: 09:40 – 14:40 ────────────────────────────────────────
    private static final LocalTime ENTRY_START = LocalTime.of(9, 40);
    private static final LocalTime ENTRY_END   = LocalTime.of(14, 40);

    // ── Sector threshold ──────────────────────────────────────────────────
    private static final double SECTOR_BUY_THRESHOLD  =  0.15;  // ≥ +0.3%
    private static final double SECTOR_SELL_THRESHOLD = -0.15;  // ≤ -0.3%

    // ── Pullback precision thresholds ────────────────────────────────────
    private static final double PULLBACK_BEST_MIN = 0.003;   // 0.3%
    private static final double PULLBACK_BEST_MAX = 0.005;   // 0.5%
    private static final double PULLBACK_GOOD_MAX = 0.008;   // 0.8%
    private static final double PULLBACK_LATE_MAX = 0.010;   // 1.0%
    // > 1.0% → INVALID

    // ── Overextension filter ─────────────────────────────────────────────
    private static final double LARGE_CAP_AVOID = 0.02;  // ≥2% avoid
    private static final double LARGE_CAP_SKIP  = 0.03;  // ≥3% skip
    private static final double MID_CAP_AVOID   = 0.03;
    private static final double MID_CAP_SKIP    = 0.05;
    private static final double SMALL_CAP_AVOID = 0.04;
    private static final double SMALL_CAP_SKIP  = 0.06;

    // ── Dependencies ─────────────────────────────────────────────────────
    private final ApplicationEventPublisher   publisher;
    private final MarketDirectionService      marketDirection;
    private final SectorStrengthService       sectorStrength;
    private final SectorClassificationService sectorClassify;
    private final ChannelDetectionService     channelDetection;
    private final RvolService                 rvolService;
    private final TechnicalAnalysisService    technicalAnalysis;
    private final MarketTimingService         timingService;
    private final VixService                  vixService;
    private final CircuitBreakerService       circuitBreaker;
    private final PositionSizerService        positionSizer;
    private final PaperAccount               paperAccount;
    private final LatencyMonitor              latencyMonitor;

    @Value("${trading.mode:PAPER}")
    private String tradingMode;

    @Value("${trading.capital:100000}")
    private BigDecimal capital;

    @Value("${strategy.smart-channel-pullback.enabled:true}")
    private boolean strategyEnabled;

    @Value("${strategy.smart-channel-pullback.time-stop-minutes:60}")
    private int timeStopMinutes;

    @Value("${strategy.smart-channel-pullback.min-rvol:1.0}")
    private double minRvol;

    @Value("${strategy.smart-channel-pullback.require-high-quality-channel:false}")
    private boolean requireHighQualityChannel;

    @Value("${strategy.smart-channel-pullback.max-signals-per-session:3}")
    private int maxSignalsPerSession;

    // ── Per-session state ─────────────────────────────────────────────────
    // symbol → last signal time (prevent duplicate signals)
    private final Map<String, Long> lastSignalTime = new ConcurrentHashMap<>();
    // How many signals fired this session
    private int sessionSignalCount = 0;
    // Active signals to prevent re-fire while trade is open
    private final Set<String> activeSignals = ConcurrentHashMap.newKeySet();
    // Cooldown per symbol (60 min between signals for same symbol)
    private static final long SYMBOL_COOLDOWN_MS = 60 * 60 * 1000L;

    // ─────────────────────────────────────────────────────────────────────
    // Main trigger: every completed 5M candle
    // ─────────────────────────────────────────────────────────────────────

    @EventListener
    @Async("tradingExecutor")
    public void onCandle(CandleCompleteEvent event) {
        Candle c = event.getCandle();
        if (!"5minute".equals(c.getTimeframe())) return;
        if (!c.isComplete()) return;

        // ── System health checks ──────────────────────────────────────────
        if (!strategyEnabled) return;
        if (latencyMonitor.isStale()) {
            log.debug("[SCPS] Latency stale — skipping evaluation");
            return;
        }

        LocalTime now = LocalTime.now(IST);

        // ── Time filter 09:40 – 14:40 ─────────────────────────────────────
        if (now.isBefore(ENTRY_START) || now.isAfter(ENTRY_END)) return;

        // ── Circuit breaker check ─────────────────────────────────────────
        BigDecimal currentCapital = resolveCapital();
        CircuitBreakerService.Permission cb = circuitBreaker.checkPermission(currentCapital);
        if (!cb.isAllowed()) {
            log.debug("[SCPS] Circuit breaker active: {}", cb.reason());
            return;
        }

        // ── Session signal cap ────────────────────────────────────────────
        if (sessionSignalCount >= maxSignalsPerSession) {
            log.debug("[SCPS] Session signal cap reached: {}/{}", sessionSignalCount, maxSignalsPerSession);
            return;
        }

        log.info("[INFO] Market data received — evaluating {} at {}", c.getTradingSymbol(), now);

        evaluateStock(c.getTradingSymbol(), c);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Full evaluation pipeline for one stock
    // ─────────────────────────────────────────────────────────────────────

    private void evaluateStock(String symbol, Candle latestCandle) {

        // ── GATE 0: Not already in an active signal ───────────────────────
        if (activeSignals.contains(symbol)) {
            log.trace("[SCPS] {} already has active signal — skip", symbol);
            return;
        }

        // ── GATE 0b: Symbol cooldown ──────────────────────────────────────
        Long lastFired = lastSignalTime.get(symbol);
        if (lastFired != null
                && System.currentTimeMillis() - lastFired < SYMBOL_COOLDOWN_MS) {
            log.trace("[SCPS] {} in cooldown — skip", symbol);
            return;
        }

        // ═════════════════════════════════════════════════════════════════
        // GATE 1: 15M Market Trend Direction
        // ═════════════════════════════════════════════════════════════════
        MarketDirectionService.MarketDirectionResult marketDir =
                marketDirection.getCurrentDirection();

        log.debug("[DEBUG] 15M trend: {}", marketDir.direction());

        if (!marketDir.isTrendTradeable()) {
            log.trace("[SCPS] {} market is SIDEWAYS — no trade", symbol);
            return;
        }

        boolean isBullMarket = marketDir.direction() == MarketDirectionService.Direction.BULLISH;
        String  marketBias   = isBullMarket ? "STRONG_BULLISH" : "STRONG_BEARISH";
        TradeDirection direction = isBullMarket ? TradeDirection.LONG : TradeDirection.SHORT;

        if (!marketDir.isTradeable()) return;

        // ═════════════════════════════════════════════════════════════════
        // GATE 2: Sector Filter
        // ═════════════════════════════════════════════════════════════════
        String  sectorName   = sectorClassify.getSector(symbol);
        SectorStrengthService.SectorData sectorData = sectorStrength.getSector(sectorName);

        log.debug("[DEBUG] Sector ranking complete");
        log.trace("[TRACE] Checking sector: {} → {}%", sectorName, String.format("%.2f", sectorData.changePercent()));

        boolean sectorDirectionOk;
        boolean sectorThresholdOk;

        if (isBullMarket) {
            sectorDirectionOk = sectorData.changePercent() >= 0;
            sectorThresholdOk = sectorData.changePercent() >= SECTOR_BUY_THRESHOLD;
        } else {
            sectorDirectionOk = sectorData.changePercent() <= 0;
            sectorThresholdOk = sectorData.changePercent() <= SECTOR_SELL_THRESHOLD;
        }

        log.debug("[DEBUG] Sector validation: Direction match: {} | Threshold pass: {}",
                sectorDirectionOk ? "YES" : "NO",
                sectorThresholdOk ? "YES" : "NO");

        // Skip neutral zone or opposite direction
        if (!sectorDirectionOk || !sectorThresholdOk) {
            log.trace("[SCPS] {} sector {} does not pass — skip", symbol, sectorName);
            return;
        }

        log.info("[INFO] Sector selected: {}", sectorName);

        // ═════════════════════════════════════════════════════════════════
        // GATE 3: Stock 15M Trend Alignment
        // ═════════════════════════════════════════════════════════════════
        TechnicalAnalysisService.TechnicalStructure structure =
                technicalAnalysis.getStructure(symbol);

        log.debug("[DEBUG] Stock scan started");
        log.trace("[TRACE] Checking stock: {}", symbol);

        // Verify stock trend aligns with sector direction
        // (We use VWAP position as a proxy for 15M trend alignment)
        boolean stockTrendAligned;
        if (isBullMarket) {
            stockTrendAligned = structure.vwap() != null
                    && latestCandle.getClose().compareTo(structure.vwap()) >= 0;
        } else {
            stockTrendAligned = structure.vwap() != null
                    && latestCandle.getClose().compareTo(structure.vwap()) <= 0;
        }

        // If no VWAP yet, allow (startup)
        if (structure.vwap().compareTo(BigDecimal.ZERO) == 0) stockTrendAligned = true;

        if (!stockTrendAligned) {
            log.trace("[SCPS] {} stock trend not aligned with market bias", symbol);
            return;
        }

        // ═════════════════════════════════════════════════════════════════
        // GATE 4: 5M Channel Validation
        // ═════════════════════════════════════════════════════════════════
        ChannelDetectionService.ChannelResult channel = channelDetection.getChannel(symbol);

        log.debug("[DEBUG] Channel: Support touches: {} | Resistance touches: {} | Status: {}",
                channel.supportLine() != null ? channel.supportLine().touches() : 0,
                channel.resistanceLine() != null ? channel.resistanceLine().touches() : 0,
                channel.isValid() ? "VALID" : "INVALID");

        if (!channel.isValid()) {
            log.trace("[SCPS] {} no valid channel: {}", symbol, channel.reason());
            return;
        }
// GATE 4b: No new entries during channel-type switch (v5)
        if (channel.isTransitioning()) {
            log.trace("[SCPS] {} channel transitioning — skip", symbol);
            return;
        }

        // Must require high-quality (3+ touches) if configured
        if (requireHighQualityChannel && !channel.isHighQuality()) {
            log.trace("[SCPS] {} channel not HIGH_QUALITY (touches < 3)", symbol);
            return;
        }

        // Channel direction must match market bias
        boolean channelDirectionOk = (isBullMarket && channel.type() == ChannelDetectionService.ChannelType.BULLISH)
                || (!isBullMarket && channel.type() == ChannelDetectionService.ChannelType.BEARISH);
        if (!channelDirectionOk) {
            log.trace("[SCPS] {} channel type {} doesn't match market bias {}",
                    symbol, channel.type(), marketBias);
            return;
        }

        // ═════════════════════════════════════════════════════════════════
        // GATE 5: Pullback Rule
        // ═════════════════════════════════════════════════════════════════
        double currentPrice = latestCandle.getClose().doubleValue();

        // Check if price is in pullback zone
        if (!channel.isPriceInPullbackZone(currentPrice)) {
            log.trace("[SCPS] {} price {} not in pullback zone [{},{}]",
                    symbol,
                    String.format("%.2f", currentPrice),
                    String.format("%.2f", channel.pullbackZoneBottom()),
                    String.format("%.2f", channel.pullbackZoneTop()));
            return;
        }

        // Compute pullback % from support (BUY) or resistance (SELL)
        double pullbackPct;
        if (isBullMarket) {
            pullbackPct = (currentPrice - channel.supportPrice()) / channel.supportPrice();
        } else {
            pullbackPct = (channel.resistancePrice() - currentPrice) / channel.resistancePrice();
        }

        log.debug("[DEBUG] Pullback: {}%", String.format("%.2f", pullbackPct * 100));

        // Reject if pullback > 1%
        if (pullbackPct > PULLBACK_LATE_MAX) {
            log.trace("[SCPS] {} pullback {}% too deep (>1%) — INVALID", symbol, String.format("%.2f", pullbackPct * 100));
            return;
        }

        String pullbackStrength;
        if (pullbackPct >= PULLBACK_BEST_MIN && pullbackPct <= PULLBACK_BEST_MAX) {
            pullbackStrength = "BEST";
        } else if (pullbackPct > PULLBACK_BEST_MAX && pullbackPct <= PULLBACK_GOOD_MAX) {
            pullbackStrength = "GOOD";
        } else if (pullbackPct > PULLBACK_GOOD_MAX && pullbackPct <= PULLBACK_LATE_MAX) {
            pullbackStrength = "LATE";
        } else {
            pullbackStrength = "TOO_EARLY"; // < 0.3%, barely moved from support
        }

        if ("TOO_EARLY".equals(pullbackStrength)) {
            log.trace("[SCPS] {} pullback {}% too shallow — wait for deeper pull", symbol, String.format("%.2f", pullbackPct * 100));
            return;
        }

        log.info("[INFO] Stock selected: {}", symbol);

        // ═════════════════════════════════════════════════════════════════
        // GATE 6: Entry Engine — Rejection Candle Check
        // ═════════════════════════════════════════════════════════════════
//        boolean isRejectionCandle = isRejectionCandle(latestCandle, isBullMarket);
//        if (!isRejectionCandle) {
//            log.trace("[SCPS] {} no rejection candle at {}",
//                    symbol, isBullMarket ? "support" : "resistance");
//            return;
//        }

        // ═════════════════════════════════════════════════════════════════
        // GATE 7: Overextension Filter
        // ═════════════════════════════════════════════════════════════════
        double dayChangePct = computeDayChangePct(latestCandle, channel);
        String capType = getCapType(symbol);
        if (isOverextended(dayChangePct, isBullMarket, capType)) {
            log.trace("[SCPS] {} overextended: {}% ({} cap)", symbol, String.format("%.2f", dayChangePct * 100), capType);
            return;
        }

        // ═════════════════════════════════════════════════════════════════
        // BUILD SIGNAL
        // ═════════════════════════════════════════════════════════════════

        // Entry price: current price (limit order at rejection candle close)
        BigDecimal entryPrice = latestCandle.getClose()
                .setScale(2, RoundingMode.HALF_UP);

        // Stop loss: below support (BUY) or above resistance (SELL)
        BigDecimal stopLoss;
        if (isBullMarket) {
            double slLevel = channel.supportPrice() * 0.998; // 0.2% below support
            stopLoss = BigDecimal.valueOf(slLevel).setScale(2, RoundingMode.FLOOR);
        } else {
            double slLevel = channel.resistancePrice() * 1.002;
            stopLoss = BigDecimal.valueOf(slLevel).setScale(2, RoundingMode.CEILING);
        }

        BigDecimal risk = entryPrice.subtract(stopLoss).abs();
        if (risk.compareTo(BigDecimal.ZERO) == 0) return;

        // Targets: T1 = 1:2, T2 = 1:3
        BigDecimal target1;
        BigDecimal target2;
        if (isBullMarket) {
            target1 = entryPrice.add(risk.multiply(BigDecimal.valueOf(2)));
            target2 = entryPrice.add(risk.multiply(BigDecimal.valueOf(3)));
        } else {
            target1 = entryPrice.subtract(risk.multiply(BigDecimal.valueOf(2)));
            target2 = entryPrice.subtract(risk.multiply(BigDecimal.valueOf(3)));
        }

        // Slippage-adjusted RR pre-flight check (Gate 7.5)
        double slippageAdj = entryPrice.doubleValue() * 0.0005; // 0.05% entry slip
        double adjustedRisk = risk.doubleValue() + slippageAdj;
        double adjustedReward = target1.subtract(entryPrice).abs().doubleValue() - slippageAdj;
        double rrRatio = adjustedReward / adjustedRisk;
        if (rrRatio < 1.8) { // minimum 1.8:1 after slippage
            log.trace("[SCPS] {} slippage-adjusted RR {} < 1.8 — reject", symbol, String.format("%.2f", rrRatio));
            return;
        }

        // Position sizing — resolve capital here (cap is not in scope from onCandle)
        BigDecimal cap = resolveCapital();
        PositionSizerService.PositionSize pos = positionSizer.calculate(
                cap, entryPrice, stopLoss, symbol, direction.name());
        if (!pos.isValid() || pos.quantity() <= 0) {
            log.trace("[SCPS] {} invalid position size: {}", symbol, pos.invalidReason());
            return;
        }

        // ═════════════════════════════════════════════════════════════════
        // POST-ENTRY SCORING
        // ═════════════════════════════════════════════════════════════════
        double rvol = rvolService.getRvolNow(symbol, latestCandle.getVolume());

        // VWAP aligned → +15
        boolean vwapAligned = isVwapAligned(latestCandle, structure, isBullMarket);
        int scoreVwap = vwapAligned ? 15 : 0;

        // RVOL ≥ 1.5 → +20, RVOL 1.2–1.5 → +10
        int scoreRvol;
        if (rvol >= 1.5)       scoreRvol = 20;
        else if (rvol >= 1.2)  scoreRvol = 10;
        else                   scoreRvol = 0;

        // Strong continuation → +15 (consecutive aligned candles)
        boolean strongContinuation = isStrongContinuation(latestCandle, isBullMarket);
        int scoreContinuation = strongContinuation ? 15 : 0;

        // Clean entry → +20 (price exactly at trendline, not inside channel)
        boolean cleanEntry = isCleanEntry(currentPrice, channel, isBullMarket);
        int scoreCleanEntry = cleanEntry ? 20 : 0;

        // Early entry → +15 (pullback is BEST quality)
        int scoreEarlyEntry = "BEST".equals(pullbackStrength) ? 15 : 0;

        // No nearby S/R → +15
        boolean noNearbySR = !hasNearbyStructure(entryPrice, structure, isBullMarket);
        int scoreNoNearbySR = noNearbySR ? 15 : 0;

        int totalScore = scoreVwap + scoreRvol + scoreContinuation
                + scoreCleanEntry + scoreEarlyEntry + scoreNoNearbySR;

        log.debug("[DEBUG] Score update: {} " +
                        "(vwap={} rvol={} cont={} clean={} early={} noSR={} TOTAL={})",
                symbol, scoreVwap, scoreRvol, scoreContinuation,
                scoreCleanEntry, scoreEarlyEntry, scoreNoNearbySR, totalScore);

        String decision = totalScore >= 60 ? "HOLD_STRONG"
                : totalScore >= 40 ? "HOLD_CAREFULLY"
                : "EXIT_FAST";

        // Check minimum RVOL
        if (rvol < minRvol) {
            log.trace("[SCPS] {} RVOL {} below minimum {}", symbol, String.format("%.2f", rvol), minRvol);
        }

        // ═════════════════════════════════════════════════════════════════
        // FIRE SIGNAL
        // ═════════════════════════════════════════════════════════════════
        log.info("[INFO] Entry signal generated for {} | dir={} entry={} sl={} T1={} T2={} score={} decision={}",
                symbol, direction, entryPrice, stopLoss, target1, target2, totalScore, decision);

        log.info("[INFO] Order placed: LIMIT @ {} | qty={} | risk=₹{} | RR={}",
                entryPrice, pos.quantity(),
                String.format("%.2f", pos.actualRisk().doubleValue()),
                String.format("%.2f", rrRatio));

        SmartChannelPullbackSignalEvent signal = new SmartChannelPullbackSignalEvent(
                this,
                symbol,
                latestCandle.getInstrumentToken(),
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
                sectorData.changePercent(),
                channel.isHighQuality() ? "HIGH_QUALITY" : "VALID",
                pullbackStrength,
                pullbackPct,
                rvol,
                vwapAligned,
                "LIMIT",
                marketBias,
                scoreVwap, scoreRvol, scoreContinuation,
                scoreCleanEntry, scoreEarlyEntry, scoreNoNearbySR,
                totalScore,
                timeStopMinutes
        );

        publisher.publishEvent(signal);

        // Track signal
        lastSignalTime.put(symbol, System.currentTimeMillis());
        activeSignals.add(symbol);
        sessionSignalCount++;

        log.info("[INFO] Execution confirmed — signal #{} this session for {}",
                sessionSignalCount, symbol);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Entry quality checks
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Rejection candle: wick into the zone with close away from it.
     * BUY: lower wick >= body, close in upper 40% of candle range.
     * SELL: upper wick >= body, close in lower 40% of candle range.
     */
//    private boolean isRejectionCandle(Candle c, boolean isBullish) {
//        double open  = c.getOpen().doubleValue();
//        double high  = c.getHigh().doubleValue();
//        double low   = c.getLow().doubleValue();
//        double close = c.getClose().doubleValue();
//        double range = high - low;
//        if (range == 0) return false;
//
//        double body     = Math.abs(close - open);
//        double bodyPct  = body / range;
//
//        if (isBullish) {
//            // Bullish rejection: lower wick prominent, close in upper half
//            double lowerWick = Math.min(open, close) - low;
//            double closePos  = (close - low) / range;
//            return lowerWick >= body && closePos >= 0.5 && bodyPct <= 0.6;
//        } else {
//            // Bearish rejection: upper wick prominent, close in lower half
//            double upperWick = high - Math.max(open, close);
//            double closePos  = (close - low) / range;
//            return upperWick >= body && closePos <= 0.5 && bodyPct <= 0.6;
//        }
//    }

    private boolean isStrongContinuation(Candle c, boolean isBullish) {
        return isBullish ? c.isBullish() : c.isBearish();
    }

    private boolean isCleanEntry(double price, ChannelDetectionService.ChannelResult channel,
                                 boolean isBullish) {
        double target = isBullish ? channel.supportPrice() : channel.resistancePrice();
        double tolerance = target * 0.002; // 0.2% tolerance
        return Math.abs(price - target) <= tolerance;
    }

    private boolean isVwapAligned(Candle c, TechnicalAnalysisService.TechnicalStructure structure,
                                  boolean isBullish) {
        if (structure.vwap() == null || structure.vwap().compareTo(BigDecimal.ZERO) == 0) return false;
        double vwap = structure.vwap().doubleValue();
        double price = c.getClose().doubleValue();
        return isBullish ? price >= vwap : price <= vwap;
    }

    private boolean hasNearbyStructure(BigDecimal entryPrice,
                                       TechnicalAnalysisService.TechnicalStructure structure,
                                       boolean isBullish) {
        double price    = entryPrice.doubleValue();
        double tolerance = price * 0.005; // 0.5%

        if (isBullish) {
            return structure.resistanceZones().stream()
                    .anyMatch(r -> Math.abs(r.doubleValue() - price) < tolerance);
        } else {
            return structure.supportZones().stream()
                    .anyMatch(s -> Math.abs(s.doubleValue() - price) < tolerance);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Overextension filter
    // ─────────────────────────────────────────────────────────────────────

    private double computeDayChangePct(Candle c, ChannelDetectionService.ChannelResult channel) {
        // Use channel support as proxy for day open
        double open = channel.supportPrice();
        if (open == 0) return 0;
        return Math.abs(c.getClose().doubleValue() - open) / open;
    }

    private String getCapType(String symbol) {
        // Simple heuristic: well-known large caps
        Set<String> largeCaps = Set.of(
                "RELIANCE","TCS","HDFCBANK","INFY","ICICIBANK","HINDUNILVR",
                "ITC","SBIN","BHARTIARTL","KOTAKBANK","LT","BAJFINANCE",
                "HCLTECH","ASIANPAINT","AXISBANK","MARUTI","SUNPHARMA",
                "TITAN","BAJAJFINSV","ULTRACEMCO","ONGC","WIPRO","TECHM",
                "NTPC","POWERGRID","JSWSTEEL","TATAMOTORS","TATASTEEL"
        );
        if (largeCaps.contains(symbol)) return "LARGE";
        // Conservative default
        return "MID";
    }

    private boolean isOverextended(double changePct, boolean isBullish, String capType) {
        double avoid, skip;
        switch (capType) {
            case "LARGE" -> { avoid = LARGE_CAP_AVOID; skip = LARGE_CAP_SKIP; }
            case "SMALL" -> { avoid = SMALL_CAP_AVOID; skip = SMALL_CAP_SKIP; }
            default      -> { avoid = MID_CAP_AVOID;   skip = MID_CAP_SKIP;  }
        }
        // Only consider extension in the direction of trade
        if (isBullish  && changePct < 0) return false;
        if (!isBullish && changePct < 0) return false;
        return changePct >= skip; // hard skip; avoid is logged but not blocked
    }

    // ─────────────────────────────────────────────────────────────────────
    // Trade lifecycle callbacks
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Called by SmartChannelSignalHandler when a trade closes.
     * Releases the active signal lock so next pullback can re-enter.
     */
    public void onSignalClosed(String symbol) {
        activeSignals.remove(symbol);
        log.debug("[SCPS] Signal lock released for {} — ready for next setup", symbol);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Capital resolution helper
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Returns the effective capital to use for position sizing and CB checks.
     * PAPER mode: uses PaperAccount.getCapital() (tracks virtual P&L correctly).
     * LIVE mode:  uses the @Value-injected capital from application.yml.
     */
    private BigDecimal resolveCapital() {
        return "PAPER".equalsIgnoreCase(tradingMode)
                ? paperAccount.getCapital()
                : capital;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Scheduled resets
    // ─────────────────────────────────────────────────────────────────────

    @Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyReset() {
        lastSignalTime.clear();
        activeSignals.clear();
        sessionSignalCount = 0;
        log.info("[SCPS] Daily reset — session state cleared");
    }

    // ─────────────────────────────────────────────────────────────────────
    // Dashboard helpers
    // ─────────────────────────────────────────────────────────────────────

    public int   getSessionSignalCount()  { return sessionSignalCount; }
    public int   getActiveSignalCount()   { return activeSignals.size(); }
    public Set<String> getActiveSignals() { return Collections.unmodifiableSet(activeSignals); }
    public boolean isEnabled()            { return strategyEnabled; }
}