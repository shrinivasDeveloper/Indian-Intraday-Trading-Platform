package com.trading.marketdata.service;

import com.trading.domain.Candle;
import com.trading.domain.enums.TradeDirection;
import com.trading.events.CandleCompleteEvent;
import com.trading.events.SmartChannelPullbackSignalEvent;
import com.trading.analysis.service.RvolService;
import com.trading.marketdata.service.MarketPressureService.PressureSnapshot;
import com.trading.papertrading.model.PaperAccount;
import com.trading.position.service.PositionSizerService;
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
 * MarketPressureDecisionEngine — 1-minute cycle trade decision engine.
 *
 * EVERY 60 SECONDS:
 *   1. Read current PressureSnapshot from MarketPressureService
 *   2. Check pressure is actionable (clear BUY or SELL dominance)
 *   3. For each symbol with a valid channel:
 *      a. Channel direction must ALIGN with pressure direction
 *      b. Price must be in pullback zone (best entry point)
 *      c. RVOL must be ≥ minimum (volume confirming the move)
 *      d. Sector must not be strongly against the trade
 *   4. Rank candidates by pressure + channel quality + RVOL
 *   5. Fire signals for top N candidates (max 2 per cycle)
 *
 * SIGNAL TYPE:
 *   Fires SmartChannelPullbackSignalEvent (same event as other strategies)
 *   Strategy name: "MARKET_PRESSURE_V1"
 *   Picked up by SmartChannelSignalHandler → TradeApprovedEvent → execution
 *
 * NOISE FILTERS:
 *   - |pressure_ratio - 1.0| > 0.10  (10% dominance required)
 *   - Minimum 20 symbols contributing to pressure
 *   - Open prices must be locked (after 9:20 AM)
 *   - Circuit breaker must allow trading
 *   - Per-symbol cooldown: 30 minutes
 *
 * RISK:
 *   - SL: just below channel support (BUY) or above resistance (SELL)
 *   - T1: 2R, T2: 3R
 *   - Time stop: configurable (default 30 minutes for pressure trades)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MarketPressureDecisionEngine {

    private static final ZoneId IST           = ZoneId.of("Asia/Kolkata");
    private static final String STRATEGY_NAME = "MARKET_PRESSURE_V1";

    // ── Trading hours ──────────────────────────────────────────────────────────
    private static final LocalTime ENTRY_START = LocalTime.of(9, 25);  // after open lock
    private static final LocalTime ENTRY_END   = LocalTime.of(14, 40);

    // ── Signal limits ──────────────────────────────────────────────────────────
    private static final int MAX_SIGNALS_PER_CYCLE   = 2;   // max trades per 60s cycle
    private static final long SYMBOL_COOLDOWN_MS     = 30 * 60 * 1000L; // 30 minutes

    // ── Minimum RVOL for pressure-based trades ─────────────────────────────────
    private static final double MIN_RVOL = 1.0;

    // ── Dependencies ──────────────────────────────────────────────────────────
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

    // ── Candle buffer for latest 5m candles (needed for RVOL) ─────────────────
    // symbol → latest completed 5m candle
    private final Map<String, Candle> latestCandles = new ConcurrentHashMap<>();

    // ══════════════════════════════════════════════════════════════════════════
    // CANDLE LISTENER — keep latest 5m candle per symbol
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
            log.debug("[PRESSURE-ENGINE] Latency stale — skipping cycle");
            return;
        }

        // ── GATE: Session signal cap ───────────────────────────────────────────
        if (sessionSignalCount >= maxSignalsPerSession) {
            log.debug("[PRESSURE-ENGINE] Session cap reached {}/{}", sessionSignalCount, maxSignalsPerSession);
            return;
        }

        // ── GATE: Circuit breaker ──────────────────────────────────────────────
        BigDecimal cap = resolveCapital();
        CircuitBreakerService.Permission cb = circuitBreaker.checkPermission(cap);
        if (!cb.isAllowed()) {
            log.debug("[PRESSURE-ENGINE] CB blocked: {}", cb.reason());
            return;
        }

        // ── READ PRESSURE ──────────────────────────────────────────────────────
        PressureSnapshot pressure = pressureService.getSnapshot();

        if (!pressure.isActionable()) {
            log.info("[PRESSURE-ENGINE] Cycle @{} | Pressure NOT actionable | dir={} ratio={} syms={} locked={}",
                    now,
                    pressure.direction(),
                    String.format("%.3f", pressure.ratio()),
                    pressure.totalSymbols(),
                    pressure.openLocked());
            return;
        }

        boolean isBuy = pressure.isBuy();
        log.info("[PRESSURE-ENGINE] 🟢 Cycle @{} | {} DOMINANT | buy={} sell={} ratio={} syms={}(↑{}↓{})",
                now,
                pressure.direction(),
                String.format("%.2f", pressure.buyStrength()),
                String.format("%.2f", pressure.sellStrength()),
                String.format("%.3f", pressure.ratio()),
                pressure.totalSymbols(),
                pressure.buySymbols(),
                pressure.sellSymbols());

        // ── SCAN CHANNELS FOR BEST ENTRIES ────────────────────────────────────
        Map<String, ChannelResult> validChannels = channelDetection.getAllValidChannels();
        if (validChannels.isEmpty()) {
            log.debug("[PRESSURE-ENGINE] No valid channels available");
            return;
        }

        List<Candidate> candidates = new ArrayList<>();

        for (Map.Entry<String, ChannelResult> entry : validChannels.entrySet()) {
            String        symbol  = entry.getKey();
            ChannelResult channel = entry.getValue();

            Candidate c = evaluateSymbol(symbol, channel, pressure, isBuy, cap);
            if (c != null) candidates.add(c);
        }

        if (candidates.isEmpty()) {
            log.info("[PRESSURE-ENGINE] No qualifying candidates this cycle (dir={})", pressure.direction());
            return;
        }

        // ── RANK AND FIRE TOP N SIGNALS ───────────────────────────────────────
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
                                     BigDecimal cap) {

        // ── GATE: Not in active signal / cooldown ─────────────────────────────
        if (activeSignals.contains(symbol)) return null;
        Long lastFired = lastSignalTime.get(symbol);
        if (lastFired != null && System.currentTimeMillis() - lastFired < SYMBOL_COOLDOWN_MS) return null;

        // ── GATE: Channel must align with pressure direction ───────────────────
        // BUY pressure → need BULLISH or SIDEWAYS channel (entering from support)
        // SELL pressure → need BEARISH or SIDEWAYS channel (entering from resistance)
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

        // ── GATE: Channel not transitioning ───────────────────────────────────
        if (channel.isTransitioning()) {
            log.trace("REJECTED: {} → channel transitioning", symbol);
            return null;
        }

        // ── GATE: Latest candle available ──────────────────────────────────────
        Candle latestCandle = latestCandles.get(symbol);
        if (latestCandle == null) return null;

        double currentPrice = latestCandle.getClose().doubleValue();
        double support      = channel.supportPrice();
        double resistance   = channel.resistancePrice();

        // ── GATE: Price in pullback zone ───────────────────────────────────────
        if (!channel.isPriceInPullbackZone(currentPrice)) {
            log.trace("REJECTED: {} → price {:.2f} not in pullback zone", symbol, currentPrice);
            return null;
        }

        // ── GATE: RVOL ─────────────────────────────────────────────────────────
        double rvol = rvolService.getRvolNow(symbol, latestCandle.getVolume());
        if (rvol < MIN_RVOL) {
            log.trace("REJECTED: {} → RVOL {:.2f} < {}", symbol, rvol, MIN_RVOL);
            return null;
        }

        // ── GATE: Sector not strongly against trade ────────────────────────────
        String sectorName = sectorClassify.getSector(symbol);
        SectorStrengthService.SectorData sectorData = sectorStrength.getSector(sectorName);
        double sectorChg = sectorData.changePercent();

        if (isBuy  && sectorChg < -0.5) {
            log.trace("REJECTED: {} → sector {} strongly bearish ({:.2f}%) vs BUY pressure",
                    symbol, sectorName, sectorChg);
            return null;
        }
        if (!isBuy && sectorChg > 0.5) {
            log.trace("REJECTED: {} → sector {} strongly bullish ({:.2f}%) vs SELL pressure",
                    symbol, sectorName, sectorChg);
            return null;
        }

        // ── Symbol's own % change must confirm pressure direction ──────────────
        double symbolPctChange = pressureService.getSymbolPctChange(symbol);
        if (isBuy  && symbolPctChange < -0.5) {
            log.trace("REJECTED: {} → symbol change {:.2f}% fights BUY pressure", symbol, symbolPctChange);
            return null;
        }
        if (!isBuy && symbolPctChange > 0.5) {
            log.trace("REJECTED: {} → symbol change {:.2f}% fights SELL pressure", symbol, symbolPctChange);
            return null;
        }

        // ── BUILD TRADE PARAMETERS ─────────────────────────────────────────────
        TradeDirection direction = isBuy ? TradeDirection.LONG : TradeDirection.SHORT;

        BigDecimal entryPrice = latestCandle.getClose().setScale(2, RoundingMode.HALF_UP);
        BigDecimal stopLoss;
        BigDecimal target1;
        BigDecimal target2;

        if (isBuy) {
            double slLevel = support * 0.998;   // 0.2% below support
            stopLoss = BigDecimal.valueOf(slLevel).setScale(2, RoundingMode.FLOOR);
            BigDecimal risk = entryPrice.subtract(stopLoss).abs();
            target1  = entryPrice.add(risk.multiply(BigDecimal.valueOf(2)));
            target2  = entryPrice.add(risk.multiply(BigDecimal.valueOf(3)));
        } else {
            double slLevel = resistance * 1.002;  // 0.2% above resistance
            stopLoss = BigDecimal.valueOf(slLevel).setScale(2, RoundingMode.CEILING);
            BigDecimal risk = stopLoss.subtract(entryPrice).abs();
            target1  = entryPrice.subtract(risk.multiply(BigDecimal.valueOf(2)));
            target2  = entryPrice.subtract(risk.multiply(BigDecimal.valueOf(3)));
        }

        BigDecimal risk = entryPrice.subtract(stopLoss).abs();
        if (risk.compareTo(BigDecimal.ZERO) == 0) return null;

        // ── RR check ───────────────────────────────────────────────────────────
        double reward  = target1.subtract(entryPrice).abs().doubleValue();
        double rrRatio = reward / risk.doubleValue();
        if (rrRatio < 1.5) {
            log.debug("REJECTED: {} → RR {:.2f} < 1.5", symbol, rrRatio);
            return null;
        }

        // ── Position sizing ────────────────────────────────────────────────────
        PositionSizerService.PositionSize pos =
                positionSizer.calculate(cap, entryPrice, stopLoss, symbol, direction.name());
        if (!pos.isValid() || pos.quantity() <= 0) {
            log.debug("REJECTED: {} → invalid position size: {}", symbol, pos.invalidReason());
            return null;
        }

        // ── Score this candidate ───────────────────────────────────────────────
        // Higher = better candidate for this cycle
        double pressureScore = pressure.ratio();                        // ratio dominance
        double rvolScore     = rvol;                                    // volume confirmation
        double channelScore  = channel.isHighQuality() ? 1.5 : 1.0;   // channel quality
        double sectorScore   = isBuy ? Math.max(0, sectorChg + 1.0)    // sector alignment bonus
                : Math.max(0, -sectorChg + 1.0);
        double rrScore       = rrRatio;                                 // risk/reward

        double finalScore = pressureScore * rvolScore * channelScore * sectorScore * rrScore;

        return new Candidate(
                symbol, direction, entryPrice, stopLoss, target1, target2,
                pos.quantity(), pos.actualRisk(),
                sectorName, sectorChg,
                channel.isHighQuality() ? "HIGH_QUALITY" : "VALID",
                rvol, rrRatio, finalScore,
                latestCandle.getInstrumentToken()
        );
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SIGNAL FIRING
    // ══════════════════════════════════════════════════════════════════════════

    private void fireSignal(Candidate cand, PressureSnapshot pressure) {
        boolean isBuy = cand.direction() == TradeDirection.LONG;

        // Post-entry score (0–100 scale for SmartChannelPullbackSignalEvent)
        int scoreRvol    = cand.rvol() >= 1.5 ? 20 : cand.rvol() >= 1.2 ? 12 : 5;
        int scorePressure = pressure.ratio() >= 1.5 ? 25 : pressure.ratio() >= 1.2 ? 15 : 8;
        int scoreChannel  = "HIGH_QUALITY".equals(cand.channelQuality()) ? 20 : 12;
        int scoreSector   = Math.abs(cand.sectorChg()) < 0.3 ? 15    // neutral sector
                : (isBuy ? (cand.sectorChg() > 0 ? 20 : 8)           // positive sector for BUY
                : (cand.sectorChg() < 0 ? 20 : 8));                   // negative sector for SELL
        int scoreRR       = cand.rrRatio() >= 2.5 ? 20 : cand.rrRatio() >= 2.0 ? 15 : 10;
        int totalScore    = scoreRvol + scorePressure + scoreChannel + scoreSector + scoreRR;

        log.info("[PRESSURE-ENGINE] 🚀 SIGNAL: {} | dir={} | entry={} | sl={} | T1={} | " +
                        "RVOL={} | pressure_ratio={} | sector={}({:.2f}%) | score={}",
                cand.symbol(), cand.direction(), cand.entryPrice(), cand.stopLoss(), cand.target1(),
                String.format("%.2f", cand.rvol()),
                String.format("%.3f", pressure.ratio()),
                cand.sectorName(), cand.sectorChg(), totalScore);

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
                "PRESSURE",          // pullbackStrength label
                pressure.ratio(),    // pullbackPercent field reused for ratio
                cand.rvol(),
                false,               // vwapAligned
                "MARKET",            // entryType
                pressure.direction(), // marketBias
                0,                   // scoreVwap
                scoreRvol,
                scorePressure,       // scoreContinuation field reused for pressure
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

    private BigDecimal resolveCapital() {
        return "PAPER".equalsIgnoreCase(tradingMode)
                ? paperAccount.getCapital()
                : capital;
    }

    /** Called by SmartChannelSignalHandler when a pressure trade closes. */
    public void onSignalClosed(String symbol) {
        activeSignals.remove(symbol);
        log.debug("[PRESSURE-ENGINE] Signal lock released for {}", symbol);
    }

    // ── Dashboard helpers ──────────────────────────────────────────────────────

    public int     getSessionSignalCount()  { return sessionSignalCount; }
    public int     getActiveSignalCount()   { return activeSignals.size(); }
    public boolean isEnabled()              { return engineEnabled; }

    @org.springframework.scheduling.annotation.Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyReset() {
        lastSignalTime.clear();
        activeSignals.clear();
        latestCandles.clear();
        sessionSignalCount = 0;
        log.info("[PRESSURE-ENGINE] Daily reset complete");
    }
}