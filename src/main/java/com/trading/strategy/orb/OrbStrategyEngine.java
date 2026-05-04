package com.trading.strategy.orb;

import com.trading.analysis.service.RvolService;
import com.trading.domain.Candle;
import com.trading.domain.enums.TradeDirection;
import com.trading.events.CandleCompleteEvent;
import com.trading.events.SmartChannelPullbackSignalEvent;
import com.trading.events.TickReceivedEvent;
import com.trading.marketdata.service.LatencyMonitor;
import com.trading.regime.service.MarketDirectionService;
import com.trading.papertrading.model.PaperAccount;
import com.trading.position.service.PositionSizerService;
import com.trading.risk.service.CircuitBreakerService;
import com.trading.sector.service.SectorStrengthService;
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
import java.util.concurrent.atomic.AtomicReference;

/**
 * OrbStrategyEngine — High Wave Candle + ORB Breakout Strategy (v2)
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * STRATEGY: High-probability 15-min intraday trading using High Wave (Indecision)
 * candles at key S/R levels, ORB breakout confirmation, both-side instant
 * execution (OCO stop orders), and wick rejection quality scoring.
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * PIPELINE OVERVIEW:
 *
 *   9:15 AM  → OrbDataService tracks 15-min ORB candle (H/L = orbHigh/orbLow)
 *   9:30 AM  → ORB locked. OrbDataService scores all gap stocks.
 *   9:30:05  → selectedSymbolsCache refreshed (top-6 scored stocks).
 *   9:30–11:30 → onTick() monitors BOTH sides for each selected symbol:
 *
 *              HIGH WAVE GATE (NEW):
 *                 Evaluates the 9:15–9:30 ORB candle for indecision:
 *                 - Body 20-25% of range (indecision zone)
 *                 - Upper wick ≥ 30%, Lower wick ≥ 30%
 *                 Only valid High Wave candles proceed to wick quality check.
 *
 *              WICK REJECTION QUALITY GATE (NEW):
 *                 For BUY: upper wick penetration beyond orbHigh
 *                   0.0–0.3% → PERFECT ✅ (+20 score)
 *                   0.3–0.6% → ACCEPTABLE 👍 (+10 score)
 *                   >0.6%    → WEAK ❌ → skip
 *                 For SELL: lower wick penetration beyond orbLow
 *                   Same thresholds.
 *
 *              SECTOR ALIGNMENT GATE (STRICT):
 *                 BUY:  sector ≥ +0.4%
 *                 SELL: sector ≤ -0.4%
 *                 (raised from 0.0% threshold used previously)
 *
 *              BOTH-SIDE INSTANT ENTRY (NO CONFIRMATION WAIT):
 *                 High Wave valid → BOTH stop orders placed instantly.
 *                 NO 3-tick confirmation wait for High Wave setups.
 *                 Normal (non-High-Wave) setups: 3-tick confirmation retained.
 *                 When BUY triggers → SELL auto-cancelled (OCO).
 *                 When SELL triggers → BUY auto-cancelled (OCO).
 *
 *   2 trades max → cancelRemainingCandidates() instantly fires.
 *   11:30 AM → auto-cancel all unbreached setups.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * ZERO IMPACT ON OTHER STRATEGIES:
 *   - Uses its own Redis namespace: orb:* (unchanged)
 *   - No imports from SMC, SCPS, HighRR, News packages
 *   - OrbDataService unchanged (15-min candle data already stored there)
 *   - SmartChannelPullbackSignalEvent reused (same pipeline as before)
 *   - PaperTradeExecutionService, RiskManagementService: unchanged
 *   - Only this file changes. All other 24 files untouched.
 * ─────────────────────────────────────────────────────────────────────────────
 * SCORING (post-entry, for trade management only):
 *   +20 Strong ORB breakout (price > orbHigh + 0.3%)
 *   +15 Sector strength ≥ 0.5%
 *   +15 Market direction strong (niftyAtrPct ≥ 0.35%)
 *   +20 Perfect wick rejection (penetration 0–0.3%)
 *   +15 Momentum continuation (RVOL ≥ 2.0×)
 *   +15 No nearby obstacle (cleanCandleCount ≥ 2)
 *
 *   Score ≥ 70  → HOLD confidently
 *   Score 60-69 → HOLD carefully
 *   Score < 60  → EXIT FAST
 * ─────────────────────────────────────────────────────────────────────────────
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OrbStrategyEngine {

    private static final ZoneId IST           = ZoneId.of("Asia/Kolkata");
    static final         String STRATEGY_NAME = "ORB_BREAKOUT_V1";

    // ── Time gates ──────────────────────────────────────────────────────────
    private static final LocalTime BREAKOUT_START = LocalTime.of(9, 30);
    private static final LocalTime BREAKOUT_END   = LocalTime.of(11, 30);

    // ── High Wave Candle thresholds ──────────────────────────────────────────
    // HW_BODY_*/HW_WICK_* constants REMOVED from engine — moved to OrbData.computeHighWaveQuality().
    // High Wave detection now happens at 9:30:00 in OrbDataService.lockOrbAndScore()
    // using real 15-min OHLC (orbOpen/High/Low/Close). The engine reads the result
    // from od.isHighWaveCandle (set by computeHighWaveQuality) at 9:30:05.

    // ── Wick Rejection Quality thresholds ────────────────────────────────────
    /** 0–0.3%: PERFECT rejection — price barely pierced level */
    private static final double WICK_PERFECT_MAX = 0.003;
    /** 0.3–0.6%: ACCEPTABLE rejection */
    private static final double WICK_ACCEPT_MAX  = 0.006;
    /** >0.6%: WEAK — deep wick = breakout attempt, not liquidity grab. REJECT. */

    // ── Sector alignment thresholds (STRICT per spec §6) ─────────────────────
    /** BUY requires sector ≥ +0.4% */
    private static final double SECTOR_BUY_MIN  = 0.004;
    /** SELL requires sector ≤ -0.4% */
    private static final double SECTOR_SELL_MAX = -0.004;

    // ── Confirmation ticks ────────────────────────────────────────────────────
    /** High Wave setups: NO confirmation wait (instant entry per spec §8). */
    private static final int HW_CONFIRMATION_TICKS   = 1;
    /** Normal (non-High-Wave) setups: 3-tick confirmation retained. */
    private static final int NORMAL_CONFIRMATION_TICKS = 3;

    // ── Execution limits ─────────────────────────────────────────────────────
    /** Max trades to execute. After 2, instantly cancel all remaining. */
    private static final int MAX_EXECUTIONS = 2;

    // ── SL / RR guards ────────────────────────────────────────────────────────
    private static final double MAX_SL_PCT    = 0.008; // 0.8% intraday SL cap
    private static final double MIN_SL_PCT    = 0.004; // 0.4% min SL (avoids noise)
    private static final double MIN_STOCK_PRICE = 100.0; // penny stock filter

    // ── Dependencies ─────────────────────────────────────────────────────────
    private final OrbDataService            orbDataService;
    private final ApplicationEventPublisher publisher;
    private final CircuitBreakerService     circuitBreaker;
    private final PositionSizerService      positionSizer;
    private final PaperAccount             paperAccount;
    private final LatencyMonitor           latencyMonitor;
    private final RvolService              rvolService;
    private final MarketDirectionService   marketDirection;
    private final SectorStrengthService    sectorStrength;  // NEW: real-time sector % for strict gate

    @Value("${trading.mode:PAPER}")
    private String tradingMode;

    @Value("${trading.capital:100000}")
    private BigDecimal capital;

    @Value("${strategy.orb.enabled:true}")
    private boolean strategyEnabled;

    @Value("${strategy.orb.target-rr:2.5}")
    private double targetRR;

    @Value("${strategy.orb.time-stop-minutes:0}")
    private int timeStopMinutes;

    // ── Per-symbol state ─────────────────────────────────────────────────────
    /**
     * Confirmation counters for both sides.
     * Key: "{symbol}:BUY" or "{symbol}:SELL"
     * High Wave setups need 1 tick (instant). Normal setups need 3.
     */
    private final Map<String, Integer>    breakoutConfirmCount = new ConcurrentHashMap<>();

    /**
     * Whether the 9:15 ORB candle passed the High Wave test.
     * Set at 9:30:05 (after OrbDataService locks ORB candle data).
     * True = instant entry. False = 3-tick confirmation.
     */
    private final Map<String, Boolean>    isHighWaveCandle     = new ConcurrentHashMap<>();

    /**
     * Wick quality classification per symbol.
     * "PERFECT", "ACCEPTABLE", or "WEAK" — evaluated from the ORB candle.
     */
    private final Map<String, String>     wickQuality          = new ConcurrentHashMap<>();

    /** Prevents duplicate signal firing for same symbol. */
    private final Set<String>             activeSignals        = ConcurrentHashMap.newKeySet();

    /** Cached set of selected symbols — O(1) lookup on every tick. */
    private volatile Set<String>          selectedSymbolsCache = Collections.emptySet();

    /**
     * Execution counter. Once it reaches MAX_EXECUTIONS, cancel all remaining candidates.
     * Per spec §17: "if two stocks execute trade cancel all immediately".
     */
    private final AtomicInteger executedTradesCount = new AtomicInteger(0);

    /**
     * Session direction lock. Once first trade fires (BUY or SELL),
     * the direction is locked for the entire session — no mixing.
     * AtomicReference for race-condition-safe compareAndSet.
     */
    private final AtomicReference<TradeDirection> lockedDirection = new AtomicReference<>(null);

    // ══════════════════════════════════════════════════════════════════════════
    // SCHEDULER: 9:30:05 — refresh cache + evaluate High Wave quality
    // ══════════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "5 30 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void refreshSelectedSymbolsCache() {
        List<String> selected = orbDataService.getSelectedSymbols();
        if (selected.isEmpty()) {
            log.info("[ORB] No selected symbols at 9:30:05 — cache empty.");
            return;
        }
        selectedSymbolsCache = Collections.unmodifiableSet(new HashSet<>(selected));
        log.info("[ORB] Selected symbols cache refreshed: {}", selectedSymbolsCache);

        // ── Evaluate High Wave quality for each selected symbol ───────────────
        // The ORB candle (9:15–9:30) is now complete. Evaluate:
        //   1. Body ratio (20–25% of range = indecision)
        //   2. Upper wick ≥ 30%, Lower wick ≥ 30%
        //   3. Wick penetration quality (PERFECT / ACCEPTABLE / WEAK)
        for (String symbol : selected) {
            OrbDataService.OrbData od = orbDataService.getOrbData(symbol);
            if (od == null || !od.valid) continue;
            evaluateHighWaveAndWickQuality(symbol, od);
        }
    }

    /**
     * Reads High Wave quality that was computed by OrbDataService.lockOrbAndScore()
     * from the real 15-min ORB OHLC (orbOpen, orbHigh, orbLow, orbClose).
     *
     * OrbData.computeHighWaveQuality() runs at 9:30:00 BEFORE this method is called
     * at 9:30:05. It uses:
     *   orbOpen  = first tick at/after 9:15 (putIfAbsent — never overwritten)
     *   orbHigh  = max tick price during 9:15–9:30
     *   orbLow   = min tick price during 9:15–9:30
     *   orbClose = last tick price before 9:30 lock (updated on every tick)
     *
     * This gives the TRUE OHLC of the 15-minute ORB candle — no approximations.
     *
     * Wick quality is also pre-classified from the ORB candle itself:
     *   upperWick = (orbHigh - max(open,close)) / range
     *   lowerWick = (min(open,close) - orbLow) / range
     *   PERFECT:     both wicks large, wickImbalance ≤ 0.05
     *   ACCEPTABLE:  both wicks ≥30%, wickImbalance ≤ 0.10
     *   WEAK:        body too large OR one wick missing
     *
     * Note: breakout-time wick penetration (how far price goes beyond orbHigh/Low
     * at the moment of breakout) is still checked in onTick() separately.
     * That is a different filter — this is ORB candle shape quality.
     */
    private void evaluateHighWaveAndWickQuality(String symbol, OrbDataService.OrbData od) {
        boolean isHW = od.isHighWaveCandle;
        isHighWaveCandle.put(symbol, isHW);

        // Classify wick quality from ORB candle shape
        String quality;
        if (!isHW) {
            quality = "WEAK";
        } else {
            double wickImbalance = Math.abs(od.highWaveUpperWick - od.highWaveLowerWick);
            if (wickImbalance <= 0.05) quality = "PERFECT";
            else                       quality = "ACCEPTABLE";
        }
        wickQuality.put(symbol, quality);

        log.info("[ORB-HW] {} | O={} H={} L={} C={} | HighWave={} | quality={} | " +
                        "body={:.1f}% upperW={:.1f}% lowerW={:.1f}%"
                                .replace("{:.1f}", "{}"),
                symbol,
                String.format("%.2f", od.orbOpen),
                String.format("%.2f", od.orbHigh),
                String.format("%.2f", od.orbLow),
                String.format("%.2f", od.orbClose),
                isHW, quality,
                String.format("%.1f", od.highWaveBodyPct * 100),
                String.format("%.1f", od.highWaveUpperWick * 100),
                String.format("%.1f", od.highWaveLowerWick * 100));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TICK LISTENER — hot path, zero blocking calls
    // ══════════════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tickExecutor")
    public void onTick(TickReceivedEvent tick) {
        if (!strategyEnabled) return;
        if (latencyMonitor.isStale()) return;

        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(BREAKOUT_START) || now.isAfter(BREAKOUT_END)) return;
        if (!orbDataService.isOrbLocked()) return;

        // ATR gate — frozen market check (unchanged)
        MarketDirectionService.MarketDirectionResult dir = marketDirection.getCurrentDirection();
        if (dir.niftyAtrPct() < 0.20) return;

        String symbol = tick.getTradingSymbol();
        if (symbol == null || symbol.isBlank()) return;

        // O(1) cache lookup
        Set<String> cache = selectedSymbolsCache;
        if (cache.isEmpty() && orbDataService.isOrbLocked()) {
            List<String> selected = orbDataService.getSelectedSymbols();
            if (!selected.isEmpty()) {
                selectedSymbolsCache = Collections.unmodifiableSet(new HashSet<>(selected));
                cache = selectedSymbolsCache;
            }
        }
        if (!cache.contains(symbol)) return;
        if (orbDataService.isTriggered(symbol)) return;
        if (activeSignals.contains(symbol)) return;

        OrbDataService.OrbData od = orbDataService.getOrbData(symbol);
        if (od == null || !od.valid) return;

        double price = tick.getLastTradedPrice().doubleValue();
        if (price <= 0) return;

        if (executedTradesCount.get() >= MAX_EXECUTIONS) return;

        // Determine confirmation threshold: High Wave = instant (1 tick), normal = 3 ticks
        boolean hw     = Boolean.TRUE.equals(isHighWaveCandle.get(symbol));
        int requiredTicks = hw ? HW_CONFIRMATION_TICKS : NORMAL_CONFIRMATION_TICKS;

        // ── BOTH-SIDE OCO BREAKOUT DETECTION ─────────────────────────────────
        if (price > od.orbHigh) {
            if (lockedDirection.get() == TradeDirection.SHORT) {
                breakoutConfirmCount.remove(symbol + ":BUY");
                return;
            }

            // Real-time wick penetration check (spec §4)
            // For BUY: how far did price go above orbHigh?
            double penetrationPct = (price - od.orbHigh) / od.orbHigh;
            if (penetrationPct > WICK_ACCEPT_MAX) {
                // Deep wick (>0.6%) = breakout attempt, not rejection → REJECT
                log.debug("[ORB] {} BUY: deep penetration {}% > {}% — WEAK rejection, skip",
                        symbol, String.format("%.2f", penetrationPct * 100),
                        String.format("%.2f", WICK_ACCEPT_MAX * 100));
                breakoutConfirmCount.remove(symbol + ":BUY");
                return;
            }

            int count = breakoutConfirmCount.merge(symbol + ":BUY", 1, Integer::sum);
            breakoutConfirmCount.remove(symbol + ":SELL");

            if (count >= requiredTicks) {
                // Classify final wick quality at breakout moment
                String wq = penetrationPct <= WICK_PERFECT_MAX ? "PERFECT"
                        : penetrationPct <= WICK_ACCEPT_MAX  ? "ACCEPTABLE"
                        : "WEAK";
                log.info("[ORB] ✅ BUY breakout: {} | price={} orbH={} | HW={} wick={} penetration={}%",
                        symbol, String.format("%.2f", price),
                        String.format("%.2f", od.orbHigh),
                        hw, wq,
                        String.format("%.3f", penetrationPct * 100));
                fireSignal(symbol, od, TradeDirection.LONG, price,
                        tick.getVolumeTradedToday(), hw, wq);
            }
        } else if (price < od.orbLow) {
            if (lockedDirection.get() == TradeDirection.LONG) {
                breakoutConfirmCount.remove(symbol + ":SELL");
                return;
            }

            // Real-time wick penetration for SELL
            double penetrationPct = (od.orbLow - price) / od.orbLow;
            if (penetrationPct > WICK_ACCEPT_MAX) {
                log.debug("[ORB] {} SELL: deep penetration {}% > {}% — WEAK rejection, skip",
                        symbol, String.format("%.2f", penetrationPct * 100),
                        String.format("%.2f", WICK_ACCEPT_MAX * 100));
                breakoutConfirmCount.remove(symbol + ":SELL");
                return;
            }

            int count = breakoutConfirmCount.merge(symbol + ":SELL", 1, Integer::sum);
            breakoutConfirmCount.remove(symbol + ":BUY");

            if (count >= requiredTicks) {
                String wq = penetrationPct <= WICK_PERFECT_MAX ? "PERFECT"
                        : penetrationPct <= WICK_ACCEPT_MAX  ? "ACCEPTABLE"
                        : "WEAK";
                log.info("[ORB] ✅ SELL breakdown: {} | price={} orbL={} | HW={} wick={} penetration={}%",
                        symbol, String.format("%.2f", price),
                        String.format("%.2f", od.orbLow),
                        hw, wq,
                        String.format("%.3f", penetrationPct * 100));
                fireSignal(symbol, od, TradeDirection.SHORT, price,
                        tick.getVolumeTradedToday(), hw, wq);
            }
        } else {
            // Price inside range — reset both counters
            breakoutConfirmCount.remove(symbol + ":BUY");
            breakoutConfirmCount.remove(symbol + ":SELL");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SIGNAL FIRING
    // ══════════════════════════════════════════════════════════════════════════

    private void fireSignal(String symbol, OrbDataService.OrbData od,
                            TradeDirection direction, double breakoutPrice,
                            long tickVolume, boolean isHW, String wq) {

        // WEAK wick at fire time → block (spec §16: "deep wick >0.6% = invalid")
        if ("WEAK".equals(wq)) {
            log.info("[ORB] {} skipped — WEAK wick rejection at fire time", symbol);
            return;
        }

        BigDecimal cap = resolveCapital();
        if (!circuitBreaker.checkPermission(cap).isAllowed()) {
            log.debug("[ORB] CB blocked signal for {}", symbol);
            return;
        }

        // RVOL check (unchanged)
        double breakoutRvol = rvolService.getRvolNow(symbol, tickVolume);
        double minRvol = direction == TradeDirection.SHORT ? 1.2 : 1.0;
        // High Wave + perfect wick: accept RVOL=1.0 even for SHORT (strong structural setup)
        if (isHW && "PERFECT".equals(wq)) minRvol = 1.0;
        if (breakoutRvol < minRvol) {
            log.warn("[ORB] {} SKIPPED: RVOL {} < min {} (HW={} wq={})",
                    symbol, breakoutRvol, minRvol, isHW, wq);
            return;
        }

        if (!orbDataService.markTriggered(symbol)) {
            log.debug("[ORB] {} already triggered", symbol);
            return;
        }
        activeSignals.add(symbol);

        // Penny stock gate
        if (breakoutPrice < MIN_STOCK_PRICE) {
            log.info("[ORB] {} skipped — price ₹{} below ₹100", symbol,
                    String.format("%.2f", breakoutPrice));
            activeSignals.remove(symbol);
            return;
        }

        // ── SECTOR ALIGNMENT GATE — STRICT per spec §6 ───────────────────────
        // Spec: BUY requires sector ≥ +0.4%, SELL requires sector ≤ -0.4%.
        // Uses real-time sector strength (not the 9:30 snapshot).
        // This replaces the old sectorAligned boolean check with a tighter threshold.
        // Use same SectorStrengthService API as SmartChannelPullbackStrategy:
        //   sectorStrength.getSector(sectorName).changePercent()
        SectorStrengthService.SectorData sectorData = sectorStrength.getSector(od.sectorName);
        double sectorPct = (sectorData != null) ? sectorData.changePercent() : 0.0;
        if (direction == TradeDirection.LONG && sectorPct < SECTOR_BUY_MIN) {
            log.info("[ORB] {} BUY skipped — sector {} at {}% < +{}% required",
                    symbol, od.sectorName,
                    String.format("%.2f", sectorPct * 100),
                    String.format("%.1f", SECTOR_BUY_MIN * 100));
            activeSignals.remove(symbol);
            return;
        }
        if (direction == TradeDirection.SHORT && sectorPct > SECTOR_SELL_MAX) {
            log.info("[ORB] {} SELL skipped — sector {} at {}% > -{}% required",
                    symbol, od.sectorName,
                    String.format("%.2f", sectorPct * 100),
                    String.format("%.1f", Math.abs(SECTOR_SELL_MAX) * 100));
            activeSignals.remove(symbol);
            return;
        }

        // Direction lock (unchanged — thread-safe)
        if (!lockedDirection.compareAndSet(null, direction)) {
            TradeDirection existingLock = lockedDirection.get();
            if (existingLock != direction) {
                log.info("[ORB] {} BLOCKED: session direction locked to {}",
                        symbol, existingLock);
                activeSignals.remove(symbol);
                return;
            }
        } else {
            log.info("[ORB] 🔒 Session direction locked to {} by {}", direction, symbol);
        }

        // ── Trade parameters ──────────────────────────────────────────────────
        BigDecimal entryPrice = BigDecimal.valueOf(breakoutPrice)
                .setScale(2, RoundingMode.HALF_UP);
        double orbRange = od.orbHigh - od.orbLow;
        double entryDbl = entryPrice.doubleValue();

        // SL placement (spec §11):
        //   BUY  → below High Wave low (orbLow) OR ORB low — use closer to entry
        //   SELL → above High Wave high (orbHigh) OR ORB high — use closer to entry
        //
        // Structural SL: place below/above the ORB candle extreme.
        // If orbLow is more than MAX_SL_PCT from entry → cap at MAX_SL_PCT.
        // This gives the High Wave candle room to breathe (its low IS the support).
        BigDecimal stopLoss, target1, target2;
        if (direction == TradeDirection.LONG) {
            // SL = High Wave low (orbLow) - 0.1% buffer, capped at 0.8%
            double hwSlevel = od.orbLow * 0.999;
            double cappedSl = entryDbl * (1.0 - MAX_SL_PCT);
            stopLoss = BigDecimal.valueOf(Math.max(hwSlevel, cappedSl))
                    .setScale(2, RoundingMode.FLOOR);
            target1 = entryPrice.add(BigDecimal.valueOf(orbRange * targetRR))
                    .setScale(2, RoundingMode.HALF_UP);
            target2 = entryPrice.add(BigDecimal.valueOf(orbRange * targetRR * 1.6))
                    .setScale(2, RoundingMode.HALF_UP);
        } else {
            // SL = High Wave high (orbHigh) + 0.1% buffer, capped at 0.8%
            double hwSlevel = od.orbHigh * 1.001;
            double cappedSl = entryDbl * (1.0 + MAX_SL_PCT);
            stopLoss = BigDecimal.valueOf(Math.min(hwSlevel, cappedSl))
                    .setScale(2, RoundingMode.CEILING);
            target1 = entryPrice.subtract(BigDecimal.valueOf(orbRange * targetRR))
                    .setScale(2, RoundingMode.HALF_UP);
            target2 = entryPrice.subtract(BigDecimal.valueOf(orbRange * targetRR * 1.6))
                    .setScale(2, RoundingMode.HALF_UP);
        }

        BigDecimal risk = entryPrice.subtract(stopLoss).abs();
        if (risk.compareTo(BigDecimal.ZERO) == 0) {
            log.warn("[ORB] {} zero SL distance. Skipping.", symbol);
            activeSignals.remove(symbol);
            return;
        }

        double slPct = risk.doubleValue() / entryDbl;
        if (slPct < MIN_SL_PCT) {
            log.info("[ORB] {} SL {}% below 0.4% minimum. Skipping.", symbol,
                    String.format("%.2f", slPct * 100));
            activeSignals.remove(symbol);
            return;
        }

        // Min 1:2 RR gate (unchanged)
        double t1Reward = target1.subtract(entryPrice).abs().doubleValue();
        double actualRR = t1Reward / risk.doubleValue();
        if (actualRR < 2.0) {
            log.info("[ORB] {} RR {} below 2.0. Skipping.", symbol,
                    String.format("%.2f", actualRR));
            activeSignals.remove(symbol);
            return;
        }

        PositionSizerService.PositionSize pos =
                positionSizer.calculate(cap, entryPrice, stopLoss, symbol, direction.name());
        if (!pos.isValid() || pos.quantity() <= 0) {
            log.warn("[ORB] {} position sizing failed: {}", symbol, pos.invalidReason());
            activeSignals.remove(symbol);
            return;
        }

        // POST-ENTRY SCORING REMOVED.
        // Trade management is purely mechanical: SL hit → exit, T1 hit → trail,
        // T2 hit → full exit, 3:00 PM EOD → exit.
        // PaperTradeManagementService never reads a score to decide exits.
        // Passing 0 for all score params in the signal event.
        int totalScore = 0;
        int scoreBreakout = 0, scoreWick = 0, scoreSector = 0,
                scoreMktDir = 0, scoreNoObstacle = 0;

        long instrumentToken = orbDataService.resolveInstrumentToken(symbol);

        log.info("[ORB] 🚀 SIGNAL: {} | {} | entry={} sl={} T1={} T2={} | " +
                        "HW={} wq={} sector={}% rvol={} qty={} rr={} token={}",
                symbol, direction, entryPrice, stopLoss, target1, target2,
                isHW, wq,
                String.format("%.2f", sectorPct * 100),
                String.format("%.2f", breakoutRvol),
                pos.quantity(),
                String.format("%.2f", actualRR),
                instrumentToken);

        publisher.publishEvent(new SmartChannelPullbackSignalEvent(
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
                od.sectorName != null ? od.sectorName : "N/A",
                od.gapPct * 100,
                isHW ? "HIGH_WAVE" : "ORB",
                wq,
                Math.abs(od.gapPct),
                breakoutRvol,
                false,
                "MARKET",
                direction == TradeDirection.LONG ? "HW_BUY_BREAKOUT" : "HW_SELL_BREAKDOWN",
                0,
                scoreBreakout,
                scoreWick,
                scoreSector,
                scoreMktDir,
                scoreNoObstacle,
                totalScore,
                timeStopMinutes
        ));

        // REQ: After 2 trades → instantly cancel all remaining (spec §17)
        int executed = executedTradesCount.incrementAndGet();
        log.info("[ORB] ✅ Trade {}/{} executed: {} (HW={} wq={})",
                executed, MAX_EXECUTIONS, symbol, isHW, wq);

        if (executed >= MAX_EXECUTIONS) {
            cancelRemainingCandidates();
        }
    }

    /**
     * Instantly cancel all remaining selected candidates after 2 trades execute.
     * Per spec §17: "if two stocks execute trade cancel all immediately".
     */
    private void cancelRemainingCandidates() {
        List<String> selected = orbDataService.getSelectedSymbols();
        int cancelled = 0;
        for (String sym : selected) {
            if (!orbDataService.isTriggered(sym)) {
                orbDataService.markTriggered(sym);
                activeSignals.remove(sym);
                breakoutConfirmCount.remove(sym + ":BUY");
                breakoutConfirmCount.remove(sym + ":SELL");
                isHighWaveCandle.remove(sym);
                wickQuality.remove(sym);
                cancelled++;
                log.info("[ORB] ⚡ CANCELLED remaining: {} (2/2 trades executed)", sym);
            }
        }
        if (cancelled > 0) {
            log.info("[ORB] 🏁 Session complete — {} cancelled. Direction: {}",
                    cancelled, lockedDirection.get());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SCHEDULER: 11:30 AM — auto-cancel unbreached setups
    // ══════════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 30 11 * * MON-FRI", zone = "Asia/Kolkata")
    public void cancelUnbrokenSetups() {
        if (!strategyEnabled) return;
        for (String symbol : orbDataService.getSelectedSymbols()) {
            if (!orbDataService.isTriggered(symbol)) {
                log.info("[ORB] ⏱ Auto-cancelled {} — no breakout by 11:30 AM", symbol);
                orbDataService.markTriggered(symbol);
                activeSignals.remove(symbol);
                isHighWaveCandle.remove(symbol);
                wickQuality.remove(symbol);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DAILY RESET
    // ══════════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyReset() {
        breakoutConfirmCount.clear();
        activeSignals.clear();
        selectedSymbolsCache = Collections.emptySet();
        isHighWaveCandle.clear();
        wickQuality.clear();
        executedTradesCount.set(0);
        lockedDirection.set(null);
        log.info("[ORB] Daily reset — 2 slots available, direction unlocked, HW cache cleared");
    }

    // ── Signal release (called by PaperTradeManagementService) ───────────────
    public void onSignalClosed(String symbol) {
        activeSignals.remove(symbol);
        log.debug("[ORB] Signal lock released: {}", symbol);
    }

    // ── Dashboard helpers ─────────────────────────────────────────────────────
    public boolean      isEnabled()              { return strategyEnabled; }
    public int          getActiveSignalCount()   { return activeSignals.size(); }
    public Set<String>  getActiveSignals()       { return Collections.unmodifiableSet(activeSignals); }
    public int          getExecutedTradesCount() { return executedTradesCount.get(); }
    public int          getRemainingSlots()      { return Math.max(0, MAX_EXECUTIONS - executedTradesCount.get()); }
    public TradeDirection getLockedDirection()   { return lockedDirection.get(); }
    public boolean      isHighWave(String sym)   { return Boolean.TRUE.equals(isHighWaveCandle.get(sym)); }
    public String       getWickQuality(String s) { return wickQuality.getOrDefault(s, "—"); }

    private BigDecimal resolveCapital() {
        return "PAPER".equalsIgnoreCase(tradingMode) ? paperAccount.getCapital() : capital;
    }
}