package com.trading.marketdata.service;

import com.trading.domain.Candle;
import com.trading.domain.enums.TradeDirection;
import com.trading.events.CandleCompleteEvent;
import com.trading.events.SmartChannelPullbackSignalEvent;
import com.trading.analysis.service.RvolService;
import com.trading.marketdata.service.MarketPressureService.PressureSnapshot;
import com.trading.papertrading.model.PaperAccount;
import com.trading.position.service.PositionSizerService;
import com.trading.regime.service.MarketDirectionService;
import com.trading.risk.service.CircuitBreakerService;
import com.trading.sector.service.SectorClassificationService;
import com.trading.sector.service.SectorStrengthService;
import com.trading.strategy.channel.ChannelDetectionService;
import com.trading.strategy.channel.ChannelDetectionService.ChannelResult;
import com.trading.strategy.channel.ChannelDetectionService.ChannelType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
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
 * MarketPressureDecisionEngine – 1-minute cycle trade decision engine.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * IMPROVEMENTS (cumulative, traceable to live trading data):
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * FIX 1 — LUNCH WINDOW EXCLUSION (root cause of all 6 losing trades 2026-04-17
 *          AND 2 losing trades 2026-04-21 KNRCON/SAPPHIRE)
 *   All losing trades entered 11:15–11:30 IST (LUNCH window 11:00–12:30).
 *   Low volume → pressure ratios unreliable → entries fail within minutes.
 *   Fix: Hard exclude LUNCH, LATE (14:00–14:40), and OBSERVATION (9:15–9:30).
 *
 * FIX 2 — MINIMUM PRICE FILTER
 *   Stocks below ₹100 have wide effective spreads, large quantities, amplified slippage.
 *   Fix: entryPrice >= MIN_STOCK_PRICE (₹100).
 *
 * FIX 3 — MINIMUM SL DISTANCE
 *   SL < 0.5% of entry price is inside normal market noise and gets hit trivially.
 *   Fix: SL distance >= MIN_SL_PCT (0.5%) of entry price.
 *
 * FIX 4 — PRESSURE RATIO THRESHOLD FOR AFTERNOON
 *   Afternoon momentum is lower. Require 1.20 vs morning 1.10.
 *
 * FIX 5 — MINIMUM RR RAISED FROM 1.5 TO 1.8
 *   After slippage, 1.5R net → ~1.2R. Raised to 1.8 for meaningful expectancy.
 *
 * FIX 6 — WIDER SL BUFFER BELOW SUPPORT (0.3% instead of 0.2%)
 *
 * GATE A — ATR ≥ 0.30% (NEW 2026-04-21)
 *   Nifty ATR < 0.30% = frozen market. Proved by Apr-21: SKFINDIA/PRESTIGE entered
 *   on frozen day (ATR 0.20%). Channels narrow, SLs get hit by noise.
 *   Fix: Check MarketDirectionService.niftyAtrPct() >= 0.30 before proceeding.
 *
 * GATE B — MARKET NOT SIDEWAYS (NEW 2026-04-21)
 *   MarketPressure is a momentum strategy. SIDEWAYS regime kills it.
 *   The strategy already has pressure-direction alignment, but an explicit
 *   SIDEWAYS check prevents wasting cycles on flat-market days.
 *
 * GATE C — REGIME MATCH AT SIGNAL LEVEL (NEW 2026-04-21)
 *   Only fire BUY signals in BULLISH regime, only fire SELL signals in BEARISH regime.
 *   This prevents the Apr-21 pattern where KNRCON/SAPPHIRE LONGs were taken on a
 *   broadly-rising market but individual stocks still had weak setups.
 *   Note: This refines the existing sector gate (which only blocks opposite-direction
 *   stocks at ±0.5%) with a broader market-level regime check.
 * ─────────────────────────────────────────────────────────────────────────────
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MarketPressureDecisionEngine {

    private static final ZoneId   IST           = ZoneId.of("Asia/Kolkata");
    private static final String   STRATEGY_NAME = "MARKET_PRESSURE_V1";

    // ── Trading hours ──────────────────────────────────────────────────────────
    private static final LocalTime ENTRY_START = LocalTime.of(9, 35); // skip first 5-min noise
    private static final LocalTime ENTRY_END   = LocalTime.of(14, 0);

    // ── Signal limits ──────────────────────────────────────────────────────────
    private static final int  MAX_SIGNALS_PER_CYCLE = 2;
    private static final long SYMBOL_COOLDOWN_MS    = 30 * 60 * 1000L;

    // ── Quality filters ────────────────────────────────────────────────────────
    /** FIX 2: Skip stocks below this price. */
    private static final double MIN_STOCK_PRICE = 100.0;

    /** FIX 3: SL must be at least this % of entry price. */
    private static final double MIN_SL_PCT = 0.005;

    /** FIX 5: Minimum RR after slippage. */
    private static final double MIN_RR_RATIO = 1.8;

    /** FIX 4: Higher pressure ratio required in AFTERNOON. */
    private static final double MIN_DOMINANCE_RATIO_MORNING   = 1.10;
    private static final double MIN_DOMINANCE_RATIO_AFTERNOON = 1.20;

    /** FIX 6: Wider SL buffer below support/above resistance. */
    private static final double SL_BUFFER_BUY  = 0.003;
    private static final double SL_BUFFER_SELL = 0.003;

    /** Minimum RVOL for pressure-based trades. */
    private static final double MIN_RVOL = 1.0;

    /** GATE A: Minimum Nifty ATR% — below this the market is frozen.
     *  FIXED: was 0.30 — blocked Apr-22(0.29%) and Apr-23(0.23%).
     *  MarketPressure is a momentum strategy and needs more range than SCPS.
     *  0.25% still allows all days where individual stocks have meaningful moves. */
    private static final double MIN_ATR_PCT = 0.25;

    // ── Dependencies ───────────────────────────────────────────────────────────
    private final MarketPressureService       pressureService;
    private final ChannelDetectionService     channelDetection;
    private final RvolService                 rvolService;
    private final SectorStrengthService       sectorStrength;
    private final SectorClassificationService sectorClassify;
    private final CircuitBreakerService       circuitBreaker;
    private final PositionSizerService        positionSizer;
    private final PaperAccount               paperAccount;
    private final ApplicationEventPublisher  publisher;
    private final LatencyMonitor             latencyMonitor;
    private final MarketTimingService        timingService;    // FIX 1: window check
    private final MarketDirectionService     marketDirection;  // GATE A+B+C: ATR/regime

    @Value("${trading.mode:PAPER}")
    private String tradingMode;

    @Value("${trading.capital:100000}")
    private BigDecimal capital;

    @Value("${strategy.market-pressure.enabled:true}")
    private boolean engineEnabled;

    @Value("${strategy.market-pressure.time-stop-minutes:30}")
    private int timeStopMinutes;

    @Value("${strategy.market-pressure.max-signals-per-session:6}")
    private int maxSignalsPerSession;

    // ── Per-session state ──────────────────────────────────────────────────────
    private final Map<String, Long> lastSignalTime  = new ConcurrentHashMap<>();
    private final Set<String>       activeSignals   = ConcurrentHashMap.newKeySet();
    private volatile int            sessionSignalCount = 0;

    // ── Candle buffer ──────────────────────────────────────────────────────────
    private final Map<String, Candle> latestCandles = new ConcurrentHashMap<>();

    // ══════════════════════════════════════════════════════════════════════════
    // CANDLE LISTENER
    // ══════════════════════════════════════════════════════════════════════════

    @org.springframework.context.event.EventListener
    @Async("tradingExecutor")
    public void onCandle(CandleCompleteEvent event) {
        Candle c = event.getCandle();
        if (!"5minute".equals(c.getTimeframe()) || !c.isComplete()) return;
        latestCandles.put(c.getTradingSymbol(), c);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 1-MINUTE DECISION CYCLE
    // ══════════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 60_000)
    public void runDecisionCycle() {
        if (!engineEnabled) return;

        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(ENTRY_START) || now.isAfter(ENTRY_END)) return;

        if (latencyMonitor.isStale()) {
            log.debug("[PRESSURE-ENGINE] Latency stale – skipping cycle");
            return;
        }

        // ── FIX 1: Window exclusion ────────────────────────────────────────────
        // LUNCH (11:00–12:30): low volume, unreliable pressure ratios.
        //   Root cause of ALL losses on 2026-04-17 and 2026-04-21.
        // LATE  (14:00–14:40): thin market, hard to exit cleanly.
        // OBSERVATION (9:15–9:30): too volatile, pressure not meaningful.
        MarketTimingService.TimeWindow currentWindow = timingService.getCurrentWindow();
        if (currentWindow == MarketTimingService.TimeWindow.LUNCH) {
            log.debug("[PRESSURE-ENGINE] Gate: LUNCH window ({}) – skipping. " +
                    "All Apr-17/Apr-21 losses occurred in this window.", now);
            return;
        }
        if (currentWindow == MarketTimingService.TimeWindow.LATE) {
            log.debug("[PRESSURE-ENGINE] Gate: LATE window ({}) – skipping.", now);
            return;
        }
        if (currentWindow == MarketTimingService.TimeWindow.OBSERVATION) {
            log.debug("[PRESSURE-ENGINE] Gate: OBSERVATION window – skipping.");
            return;
        }

        // ── GATE A: Frozen market — Nifty ATR must be ≥ 0.30% ────────────────
        // On frozen days (ATR < 0.30%) channels are narrow and SLs get hit by noise.
        // Proved by Apr-21: KNRCON (-₹143) and SAPPHIRE (-₹218) both on ATR=0.20% day.
        MarketDirectionService.MarketDirectionResult dir = marketDirection.getCurrentDirection();
        if (dir.niftyAtrPct() < MIN_ATR_PCT) {
            log.debug("[PRESSURE-ENGINE] Gate A BLOCKED — Nifty ATR {}% < {}%. " +
                            "Frozen market, pressure signals unreliable.",
                    String.format("%.2f", dir.niftyAtrPct()),
                    String.format("%.2f", MIN_ATR_PCT));
            return;
        }

        // ── GATE B: Market must not be SIDEWAYS ───────────────────────────────
        // MarketPressure is a momentum/directional strategy.
        // SIDEWAYS regime → pressure readings oscillate without follow-through.
        if (dir.direction() == MarketDirectionService.Direction.SIDEWAYS) {
            log.debug("[PRESSURE-ENGINE] Gate B BLOCKED — Market direction SIDEWAYS. " +
                    "Pressure strategy needs trending market.");
            return;
        }

        // ── Session cap ────────────────────────────────────────────────────────
        if (sessionSignalCount >= maxSignalsPerSession) {
            log.debug("[PRESSURE-ENGINE] Session cap reached {}/{}", sessionSignalCount, maxSignalsPerSession);
            return;
        }

        // ── Circuit breaker ────────────────────────────────────────────────────
        BigDecimal cap = resolveCapital();
        CircuitBreakerService.Permission cb = circuitBreaker.checkPermission(cap);
        if (!cb.isAllowed()) {
            log.debug("[PRESSURE-ENGINE] CB blocked: {}", cb.reason());
            return;
        }

        // ── Read pressure ──────────────────────────────────────────────────────
        PressureSnapshot pressure = pressureService.getSnapshot();

        // FIX 4: Use window-appropriate dominance ratio
        double requiredRatio = (currentWindow == MarketTimingService.TimeWindow.AFTERNOON)
                ? MIN_DOMINANCE_RATIO_AFTERNOON
                : MIN_DOMINANCE_RATIO_MORNING;

        if (!isActionableWithThreshold(pressure, requiredRatio)) {
            log.info("[PRESSURE-ENGINE] Cycle @{} | Pressure NOT actionable | dir={} ratio={} " +
                            "required={} syms={} locked={} window={} atr={}%",
                    now, pressure.direction(),
                    String.format("%.3f", pressure.ratio()),
                    requiredRatio,
                    pressure.totalSymbols(), pressure.openLocked(), currentWindow,
                    dir.niftyAtrPct());
            return;
        }

        boolean isBuy = pressure.isBuy();

        // ── GATE C: Regime-direction match ────────────────────────────────────
        // Only fire BUY signals in BULLISH regime, SELL signals in BEARISH regime.
        // Pressure may show BUY at the stock level, but if the broad market is
        // BEARISH, longs have high failure rate.
        if (isBuy && dir.direction() == MarketDirectionService.Direction.BEARISH) {
            log.info("[PRESSURE-ENGINE] Gate C BLOCKED — BUY pressure but market is BEARISH. " +
                    "Skipping cycle to avoid trading against broad trend.");
            return;
        }
        if (!isBuy && dir.direction() == MarketDirectionService.Direction.BULLISH) {
            log.info("[PRESSURE-ENGINE] Gate C BLOCKED — SELL pressure but market is BULLISH. " +
                    "Skipping cycle to avoid trading against broad trend.");
            return;
        }

        log.info("[PRESSURE-ENGINE] 🟢 Cycle @{} | {} DOMINANT | buy={} sell={} ratio={} " +
                        "syms=(↑{}↓{}) window={} atr={}% regime={}",
                now,
                pressure.direction(),
                String.format("%.2f", pressure.buyStrength()),
                String.format("%.2f", pressure.sellStrength()),
                String.format("%.3f", pressure.ratio()),
                pressure.buySymbols(),
                pressure.sellSymbols(),
                currentWindow,
                dir.niftyAtrPct(),
                dir.direction());

        // ── Scan channels ──────────────────────────────────────────────────────
        Map<String, ChannelResult> validChannels = channelDetection.getAllValidChannels();
        if (validChannels.isEmpty()) {
            log.debug("[PRESSURE-ENGINE] No valid channels available");
            return;
        }

        List<Candidate> candidates = new ArrayList<>();

        for (Map.Entry<String, ChannelResult> entry : validChannels.entrySet()) {
            String        symbol  = entry.getKey();
            ChannelResult channel = entry.getValue();
            Candidate c = evaluateSymbol(symbol, channel, pressure, isBuy, cap, currentWindow);
            if (c != null) candidates.add(c);
        }

        if (candidates.isEmpty()) {
            log.info("[PRESSURE-ENGINE] No qualifying candidates this cycle (dir={})", pressure.direction());
            return;
        }

        // ── Rank and fire top N ────────────────────────────────────────────────
        candidates.sort(Comparator.comparingDouble(Candidate::score).reversed());
        int fired = 0;

        for (Candidate cand : candidates) {
            if (fired >= MAX_SIGNALS_PER_CYCLE) break;
            if (sessionSignalCount >= maxSignalsPerSession) break;
            fireSignal(cand, pressure);
            fired++;
        }

        if (fired == 0) {
            log.debug("[PRESSURE-ENGINE] No signals fired this cycle");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SYMBOL EVALUATION
    // ══════════════════════════════════════════════════════════════════════════

    private Candidate evaluateSymbol(String symbol, ChannelResult channel,
                                     PressureSnapshot pressure, boolean isBuy,
                                     BigDecimal cap,
                                     MarketTimingService.TimeWindow window) {

        // ── Cooldown / active signal guard ─────────────────────────────────────
        if (activeSignals.contains(symbol)) return null;
        Long lastFired = lastSignalTime.get(symbol);
        if (lastFired != null && System.currentTimeMillis() - lastFired < SYMBOL_COOLDOWN_MS) return null;

        // ── Channel alignment ──────────────────────────────────────────────────
        boolean channelAligned;
        if (isBuy) {
            channelAligned = channel.type() == ChannelType.BULLISH
                    || channel.type() == ChannelType.SIDEWAYS;
        } else {
            channelAligned = channel.type() == ChannelType.BEARISH
                    || channel.type() == ChannelType.SIDEWAYS;
        }
        if (!channelAligned) {
            log.trace("REJECTED: {} → channel type {} doesn't align with {} pressure",
                    symbol, channel.type(), pressure.direction());
            return null;
        }

        if (channel.isTransitioning()) {
            log.trace("REJECTED: {} → channel transitioning", symbol);
            return null;
        }

        // ── Latest candle ──────────────────────────────────────────────────────
        Candle latestCandle = latestCandles.get(symbol);
        if (latestCandle == null) return null;

        double currentPrice = latestCandle.getClose().doubleValue();

        // ── FIX 2: Minimum price filter ────────────────────────────────────────
        if (currentPrice < MIN_STOCK_PRICE) {
            log.debug("REJECTED: {} → price {} below minimum {}",
                    symbol, currentPrice, MIN_STOCK_PRICE);
            return null;
        }

        double support    = channel.supportPrice();
        double resistance = channel.resistancePrice();

        // ── Pullback zone ──────────────────────────────────────────────────────
        if (!channel.isPriceInPullbackZone(currentPrice)) {
            log.trace("REJECTED: {} → price {} not in pullback zone", symbol, currentPrice);
            return null;
        }

        // ── RVOL ───────────────────────────────────────────────────────────────
        double rvol = rvolService.getRvolNow(symbol, latestCandle.getVolume());
        if (rvol < MIN_RVOL) {
            log.trace("REJECTED: {} → RVOL {} < {}", symbol, rvol, MIN_RVOL);
            return null;
        }

        // ── Sector gate ────────────────────────────────────────────────────────
        String sectorName = sectorClassify.getSector(symbol);
        SectorStrengthService.SectorData sectorData = sectorStrength.getSector(sectorName);
        double sectorChg = sectorData.changePercent();

        if (isBuy && sectorChg < -0.5) {
            log.trace("REJECTED: {} → sector {} strongly bearish ({}%) vs BUY pressure",
                    symbol, sectorName, sectorChg);
            return null;
        }
        if (!isBuy && sectorChg > 0.5) {
            log.trace("REJECTED: {} → sector {} strongly bullish ({}%) vs SELL pressure",
                    symbol, sectorName, sectorChg);
            return null;
        }

        // ── Symbol pct change gate ─────────────────────────────────────────────
        double symbolPctChange = pressureService.getSymbolPctChange(symbol);
        if (isBuy && symbolPctChange < -0.5) {
            log.trace("REJECTED: {} → symbol change {}% fights BUY pressure", symbol, symbolPctChange);
            return null;
        }
        if (!isBuy && symbolPctChange > 0.5) {
            log.trace("REJECTED: {} → symbol change {}% fights SELL pressure", symbol, symbolPctChange);
            return null;
        }

        // ── Build trade parameters ─────────────────────────────────────────────
        TradeDirection direction = isBuy ? TradeDirection.LONG : TradeDirection.SHORT;

        BigDecimal entryPrice = latestCandle.getClose().setScale(2, RoundingMode.HALF_UP);
        BigDecimal stopLoss;
        BigDecimal target1;
        BigDecimal target2;

        if (isBuy) {
            // FIX 6: wider SL buffer (0.3% below support)
            double slLevel = support * (1.0 - SL_BUFFER_BUY);
            stopLoss = BigDecimal.valueOf(slLevel).setScale(2, RoundingMode.FLOOR);
            BigDecimal risk = entryPrice.subtract(stopLoss).abs();
            target1 = entryPrice.add(risk.multiply(BigDecimal.valueOf(2)));
            target2 = entryPrice.add(risk.multiply(BigDecimal.valueOf(3)));
        } else {
            // FIX 6: wider SL buffer (0.3% above resistance)
            double slLevel = resistance * (1.0 + SL_BUFFER_SELL);
            stopLoss = BigDecimal.valueOf(slLevel).setScale(2, RoundingMode.CEILING);
            BigDecimal risk = stopLoss.subtract(entryPrice).abs();
            target1 = entryPrice.subtract(risk.multiply(BigDecimal.valueOf(2)));
            target2 = entryPrice.subtract(risk.multiply(BigDecimal.valueOf(3)));
        }

        BigDecimal risk = entryPrice.subtract(stopLoss).abs();
        if (risk.compareTo(BigDecimal.ZERO) == 0) return null;

        // ── FIX 3: Minimum SL distance ─────────────────────────────────────────
        double slPct = risk.doubleValue() / entryPrice.doubleValue();
        if (slPct < MIN_SL_PCT) {
            log.debug("REJECTED: {} → SL distance {}% below minimum {}%. " +
                            "Channel too narrow for reliable SL placement. entry={} sl={}",
                    symbol, slPct * 100, MIN_SL_PCT * 100, entryPrice, stopLoss);
            return null;
        }

        // ── FIX 5: Minimum RR check ────────────────────────────────────────────
        double reward  = target1.subtract(entryPrice).abs().doubleValue();
        double rrRatio = reward / risk.doubleValue();
        if (rrRatio < MIN_RR_RATIO) {
            log.debug("REJECTED: {} → RR {} < minimum {}", symbol, rrRatio, MIN_RR_RATIO);
            return null;
        }

        // ── Position sizing ────────────────────────────────────────────────────
        PositionSizerService.PositionSize pos =
                positionSizer.calculate(cap, entryPrice, stopLoss, symbol, direction.name());
        if (!pos.isValid() || pos.quantity() <= 0) {
            log.debug("REJECTED: {} → invalid position size: {}", symbol, pos.invalidReason());
            return null;
        }

        // ── Scoring ────────────────────────────────────────────────────────────
        double pressureScore = pressure.ratio();
        double rvolScore     = rvol;
        double channelScore  = channel.isHighQuality() ? 1.5 : 1.0;
        double sectorScore   = isBuy
                ? Math.max(0, sectorChg)
                : Math.max(0, -sectorChg);
        double finalScore = pressureScore * rvolScore * channelScore + sectorScore;

        // ChannelResult does NOT expose getInstrumentToken() — use InstrumentCacheService
        // if token is needed, or default to 0L. SmartChannelPullbackSignalEvent accepts 0L
        // and the handler resolves the token from InstrumentCacheService before firing the order.
        long instrumentToken = 0L;

        return new Candidate(
                symbol, direction,
                entryPrice, stopLoss, target1, target2,
                pos.quantity(), pos.actualRisk(),  // FIX: actualRisk() not riskAmount()
                sectorName, sectorChg,
                channel.isHighQuality() ? "HIGH_QUALITY" : "VALID",
                rvol, rrRatio, finalScore,
                instrumentToken
        );
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SIGNAL FIRING
    // ══════════════════════════════════════════════════════════════════════════

    private void fireSignal(Candidate cand, PressureSnapshot pressure) {
        boolean isBuy = cand.direction() == TradeDirection.LONG;

        int scoreRvol     = cand.rvol() >= 1.5 ? 20 : cand.rvol() >= 1.2 ? 12 : 5;
        int scorePressure = pressure.ratio() >= 1.5 ? 25 : pressure.ratio() >= 1.2 ? 15 : 8;
        int scoreChannel  = "HIGH_QUALITY".equals(cand.channelQuality()) ? 20 : 12;
        int scoreSector   = Math.abs(cand.sectorChg()) < 0.3 ? 15
                : (isBuy ? (cand.sectorChg() > 0 ? 20 : 8) : (cand.sectorChg() < 0 ? 20 : 8));
        int scoreRR       = cand.rrRatio() >= 2.5 ? 20 : cand.rrRatio() >= 1.8 ? 15 : 10;
        int totalScore    = scoreRvol + scorePressure + scoreChannel + scoreSector + scoreRR;

        log.info("[PRESSURE-ENGINE] 🚀 SIGNAL: {} | dir={} | entry={} | sl={} | T1={} | " +
                        "RVOL={} | pressure_ratio={} | sector={}({}%) | RR={} | score={}",
                cand.symbol(), cand.direction(), cand.entryPrice(), cand.stopLoss(), cand.target1(),
                String.format("%.2f", cand.rvol()),
                String.format("%.3f", pressure.ratio()),
                cand.sectorName(), cand.sectorChg(), cand.rrRatio(), totalScore);

        SmartChannelPullbackSignalEvent signal = new SmartChannelPullbackSignalEvent(
                this,
                cand.symbol(),
                cand.instrumentToken(),
                cand.direction(),
                cand.entryPrice(),
                cand.stopLoss(),
                cand.target1(),
                cand.target2(),
                cand.quantity(),
                cand.riskAmount(),
                STRATEGY_NAME,
                totalScore,
                cand.sectorName(),
                cand.sectorChg(),
                cand.channelQuality(),
                "PRESSURE",
                pressure.ratio(),
                cand.rvol(),
                false,
                "MARKET",
                pressure.direction(),
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

        lastSignalTime.put(cand.symbol(), System.currentTimeMillis());
        activeSignals.add(cand.symbol());
        sessionSignalCount++;

        log.info("[PRESSURE-ENGINE] Signal #{} fired for {} (session total)",
                sessionSignalCount, cand.symbol());
    }

    // ── Candidate record ───────────────────────────────────────────────────────

    private record Candidate(
            String         symbol,
            TradeDirection direction,
            BigDecimal     entryPrice,
            BigDecimal     stopLoss,
            BigDecimal     target1,
            BigDecimal     target2,
            int            quantity,
            BigDecimal     riskAmount,
            String         sectorName,
            double         sectorChg,
            String         channelQuality,
            double         rvol,
            double         rrRatio,
            double         score,
            long           instrumentToken
    ) {}

    // ── Helpers ────────────────────────────────────────────────────────────────

    private boolean isActionableWithThreshold(PressureSnapshot p, double threshold) {
        if (p == null || p.totalSymbols() < 5) return false;
        if (p.openLocked()) return false;
        return p.ratio() >= threshold;
    }

    private BigDecimal resolveCapital() {
        return "PAPER".equalsIgnoreCase(tradingMode)
                ? paperAccount.getCapital()
                : capital;
    }

    public void onSignalClosed(String symbol) {
        activeSignals.remove(symbol);
        log.debug("[PRESSURE-ENGINE] Signal lock released for {}", symbol);
    }

    // ── Dashboard helpers ──────────────────────────────────────────────────────

    public int     getSessionSignalCount() { return sessionSignalCount; }
    public int     getActiveSignalCount()  { return activeSignals.size(); }
    public boolean isEnabled()             { return engineEnabled; }

    @Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyReset() {
        lastSignalTime.clear();
        activeSignals.clear();
        latestCandles.clear();
        sessionSignalCount = 0;
        log.info("[PRESSURE-ENGINE] Daily reset complete");
    }
}