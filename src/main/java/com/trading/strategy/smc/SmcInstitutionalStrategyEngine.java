package com.trading.strategy.smc;

import com.trading.domain.Candle;
import com.trading.domain.enums.TradeDirection;
import com.trading.events.CandleCompleteEvent;
import com.trading.events.TickReceivedEvent;
import com.trading.marketdata.service.InstrumentCacheService;
import com.trading.marketdata.service.LatencyMonitor;
import com.trading.papertrading.model.PaperAccount;
import com.trading.position.service.PositionSizerService;
import com.trading.regime.service.MarketDirectionService;
import com.trading.risk.service.CircuitBreakerService;
import com.trading.sector.service.SectorClassificationService;
import com.trading.sector.service.SectorStrengthService;
import com.trading.strategy.smc.SmcInstitutionalStructureService.*;
import com.zerodhatech.models.Instrument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
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
 * SmcInstitutionalStrategyEngine
 * ─────────────────────────────────────────────────────────────────────────────
 * Core evaluation engine for the SMC_INSTITUTIONAL_V1 strategy.
 *
 * Execution flow (standard pipeline — NOT self-managed):
 *   SmcInstitutionalStrategyEngine (this service)
 *     └─ publishes SmartChannelPullbackSignalEvent(strategy="SMC_INSTITUTIONAL_V1")
 *          └─ SmartChannelSignalHandler.onSignal()  [existing, unchanged]
 *               └─ publishes TradeApprovedEvent
 *                    └─ PaperTradeExecutionService.onTradeApproved()  [existing]
 *                         └─ PaperTradeManagementService  [existing]
 *
 * ZERO modifications to existing files.
 * PaperTradeExecutionService does NOT contain "SMC_INSTITUTIONAL_V1" in
 * SELF_MANAGED_STRATEGIES → standard execution pipeline handles it.
 *
 * Gate pipeline (all must pass to fire signal):
 *   Gate 1 : Market regime — ATR not frozen, not SIDEWAYS
 *   Gate 2 : Bootstrap ready — candle service has data
 *   Gate 3 : HTF structure valid — clear trend or strong reversal setup
 *   Gate 4 : Intraday setup detection — one of 6 setup types detected
 *   Gate 5 : Confirmation candle on 15m — engulfing/pin-bar/marubozu
 *   Gate 6 : RR ≥ 3.0 (min 1:3 per spec)
 *   Gate 7 : No opposite strong zone blocking target
 *   Gate 8 : Confidence score ≥ threshold (60 by default)
 *   Gate 9 : Removed — platform circuit breaker handles daily cap globally
 *   Gate 10: Sector alignment
 * ─────────────────────────────────────────────────────────────────────────────
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SmcInstitutionalStrategyEngine {

    // ── Strategy identity ─────────────────────────────────────────────────────
    private static final String STRATEGY_NAME = "SMC_INSTITUTIONAL_V1";

    // ── Session window: 9:30 AM – 2:00 PM IST ────────────────────────────────
    private static final ZoneId    IST         = ZoneId.of("Asia/Kolkata");
    private static final LocalTime TRADE_START = LocalTime.of(9, 30);

    // ── Trade limits ──────────────────────────────────────────────────────────
    private static final double PREFERRED_RR       = 5.0;

    // ── Zone proximity tolerances ─────────────────────────────────────────────
    private static final double ZONE_ENTRY_TOL   = 0.006; // 0.6% — price near zone
    private static final double ZONE_BLOCK_TOL   = 0.015; // 1.5% — blocking zone check
    private static final double SL_BUFFER_PCT    = 0.002; // 0.2% SL buffer beyond zone
    private static final double MAX_SL_PCT       = 0.025; // 2.5% max SL distance

    // ── Confidence threshold ──────────────────────────────────────────────────
    // MIN_CONFIDENCE removed — use @Value minConfidence field only

    // ── Dependencies (all existing platform services) ─────────────────────────
    private final SmcInstitutionalCandleService    candleService;
    private final SmcInstitutionalStructureService structureService;
    private final MarketDirectionService           marketDirection;
    private final SectorStrengthService            sectorStrength;
    private final SectorClassificationService      sectorClassify;
    private final PositionSizerService             positionSizer;
    private final CircuitBreakerService            circuitBreaker;
    private final InstrumentCacheService           instrumentCache;
    private final PaperAccount                     paperAccount;
    private final LatencyMonitor                   latencyMonitor;
    private final ApplicationEventPublisher        publisher;

    // ── Config (application.yml: strategy.smc.*) ──────────────────────────────
    @Value("${strategy.smc.enabled:true}")
    private boolean enabled;

    @Value("${trading.mode:PAPER}")
    private String tradingMode;

    @Value("${trading.capital:100000}")
    private BigDecimal configCapital;

    @Value("${strategy.smc.time-stop-minutes:90}")
    private int timeStopMinutes;

    @Value("${strategy.smc.risk-per-trade:0.01}")
    private double riskPerTrade;

    @Value("${strategy.smc.min-confidence:60}")
    private int minConfidence;

    @Value("${strategy.smc.min-rr:3.0}")
    private double minRr;                // yml: strategy.smc.min-rr

    @Value("${strategy.smc.trade-end:14:40}")
    private String tradeEndConfig;       // yml: strategy.smc.trade-end (HH:mm)

    // ── Session state ─────────────────────────────────────────────────────────
    private final AtomicInteger       tradesExecutedToday = new AtomicInteger(0);
    private final Set<String>         firedToday          = ConcurrentHashMap.newKeySet();
    private final Set<String>         activeSignals        = ConcurrentHashMap.newKeySet();

    // ══════════════════════════════════════════════════════════════════════════
    // MAIN EVALUATION CYCLE — runs every 5 minutes
    // (Lower cadence than HighRR's 1-min because this is HTF-driven, not tick-driven)
    // ══════════════════════════════════════════════════════════════════════════
    // CANDLE-DRIVEN EVALUATION — fires on every 5m candle close
    //
    // WHY NOT @Scheduled(fixedRate=300_000):
    //   A fixed 5-min timer fires at arbitrary offsets from candle boundaries.
    //   Example: timer fires at 09:32:17 but the 09:30 candle closed at 09:35:00.
    //   That means evaluation runs on stale data (the 09:25 candle), missing the
    //   setup that formed in the 09:30 candle entirely.
    //
    //   CandleCompleteEvent on "5minute" fires EXACTLY when a 5m candle closes —
    //   immediately after CandleAggregatorService completes the candle. Evaluation
    //   always runs on the freshest possible data, aligned to candle boundaries.
    //
    // CADENCE: every 5 minutes (same as before) but candle-aligned.
    //   ~12 evaluations/hour × 4.5 trading hours = ~54 evaluations/day.
    //
    // THREAD SAFETY: CandleCompleteEvent fires from trade-N threads.
    //   Multiple 5m candles may close in the same second (different symbols).
    //   We guard with a per-candle-close dedup: only evaluate once per candle close
    //   time, regardless of how many symbols close at the same timestamp.
    // ══════════════════════════════════════════════════════════════════════════

    /** Tracks the last 5m candle close time we evaluated, to deduplicate. */
    private volatile long lastEvalCandleMs = 0L;

    @org.springframework.context.event.EventListener
    @org.springframework.scheduling.annotation.Async("tradingExecutor")
    public void onCandleClose(CandleCompleteEvent event) {
        if (!enabled) return;

        // Only react to 5m candle closes — the SMC confirmation timeframe
        if (!"5minute".equals(event.getCandle().getTimeframe())) return;
        if (!event.getCandle().isComplete()) return;

        // Deduplicate: multiple 5m candles close at the same timestamp (one per symbol).
        // We only want to run ONE full evaluation scan per candle close time.
        // Use the candle's close time (rounded to minute) as the dedup key.
        long candleCloseMs = event.getCandle().getCandleTime() != null
                ? event.getCandle().getCandleTime().toEpochMilli()
                : System.currentTimeMillis();

        // Atomic check-and-set — only the first candle close at this timestamp proceeds
        synchronized (this) {
            if (candleCloseMs <= lastEvalCandleMs) return;
            lastEvalCandleMs = candleCloseMs;
        }

        runEvaluationCycle();
    }

    /**
     * Core evaluation logic — called on every 5m candle close.
     * Also kept package-visible for testing.
     */
    void runEvaluationCycle() {
        LocalTime now = LocalTime.now(IST);
        LocalTime tradeEnd = parseTradeEnd();
        if (now.isBefore(TRADE_START) || now.isAfter(tradeEnd)) return;

        // Gate 1: Market regime
        MarketDirectionService.MarketDirectionResult dir = marketDirection.getCurrentDirection();
        if (dir == null) {
            log.debug("[SMC] Gate1 BLOCKED — market direction unavailable");
            return;
        }
        if (dir.niftyAtrPct() < 0.15) {
            log.debug("[SMC] Gate1 BLOCKED — market frozen (ATR {}%)",
                    String.format("%.3f", dir.niftyAtrPct()));
            return;
        }

        // Gate 2: Bootstrap
        if (!candleService.isBootstrapComplete()) {
            log.debug("[SMC] Gate2 BLOCKED — candle bootstrap not complete");
            return;
        }

        // NOTE: No SMC-specific daily trade limit.
        // The platform circuit breaker (max-trades-per-day: 10) handles the global cap
        // across all strategies. RiskManagementService enforces slot limits.

        // Gate CB
        BigDecimal cap = resolveCapital();
        if (!circuitBreaker.checkPermission(cap).isAllowed()) {
            log.debug("[SMC] Circuit breaker blocked evaluation");
            return;
        }

        // Latency guard
        if (latencyMonitor.isStale()) {
            log.debug("[SMC] Latency stale — skipping cycle");
            return;
        }

        // Only symbols with loaded candle data (~253 Nifty500)
        Set<String> symbols = candleService.getLoadedSymbols();
        if (symbols.isEmpty()) return;

        log.debug("[SMC] Evaluation @{} | symbols={} | firedToday={}",
                now, symbols.size(), tradesExecutedToday.get());

        List<SmcCandidate> candidates = new ArrayList<>();

        for (String symbol : symbols) {
            if (firedToday.contains(symbol) || activeSignals.contains(symbol)) continue;
            try {
                SmcCandidate c = evaluateSymbol(symbol, dir, cap);
                if (c != null) candidates.add(c);
            } catch (Exception e) {
                log.trace("[SMC] Evaluation error for {}: {}", symbol, e.getMessage());
            }
        }

        if (candidates.isEmpty()) {
            log.debug("[SMC] No qualifying candidates this cycle");
            return;
        }

        // Best setup first — fire top candidate(s)
        // Platform RiskManagementService enforces the global 10-trade cap.
        candidates.sort(Comparator.comparingInt(SmcCandidate::confidence).reversed());

        // Fire only the top 1 candidate per cycle to avoid flooding slots
        // (SMC is a high-conviction strategy — one best setup is sufficient)
        fireSignal(candidates.get(0));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SYMBOL EVALUATION
    // ══════════════════════════════════════════════════════════════════════════

    private SmcCandidate evaluateSymbol(String symbol,
                                        MarketDirectionService.MarketDirectionResult dir,
                                        BigDecimal cap) {
        // Gate 3: HTF structure
        HtfStructure htf = structureService.getStructure(symbol);
        if (htf == null) return null;

        // Reject pure sideways unless strong reversal setup exists
        boolean isSideways = htf.isSideways();

        // Determine allowed trade directions from HTF
        boolean buyAllowed  = htf.isBullish()
                || (isSideways && htf.momentumScore >= 60);
        boolean sellAllowed = htf.isBearish()
                || (isSideways && htf.momentumScore <= 40);

        if (!buyAllowed && !sellAllowed) {
            log.trace("[SMC] {} Gate3 BLOCKED — HTF sideways with weak momentum ({})",
                    symbol, htf.momentumScore);
            return null;
        }

        // ── CRITICAL: LTF NEVER overrides HTF direction ────────────────────
        // 5m and 15m are used ONLY for entry timing and candle confirmation.
        // buyAllowed/sellAllowed is determined from HTF (daily) only — see above.
        // If the 5m shows a sell signal but HTF is bullish, we take BUY only.
        // ─────────────────────────────────────────────────────────────────────

        // Get 15m candles for intraday confirmation
        List<Candle> candles15m = candleService.getSmcIntraday15m(symbol);
        if (candles15m == null || candles15m.size() < 2) return null; // need only last 2 candles

        // 5m candles: SmcCandleStore only buffers 15m data.
        // 5m momentum check is supplementary — evaluation proceeds without it.
        List<Candle> candles5m = null;
        boolean has5mData = false;

        // Current price = last 15m close (HTF-aligned price reference)
        Candle lastCandle = candles15m.get(candles15m.size() - 1);
        Candle prevCandle = candles15m.get(candles15m.size() - 2);
        double currentPrice = lastCandle.getClose().doubleValue();
        if (currentPrice < 50.0) return null; // minimum price filter

        // 5m momentum confirmation (supplementary — does not override HTF)
        // If we have 5m data, use last 3 candles to assess short-term momentum
        boolean shortTermBull = true, shortTermBear = true;
        if (has5mData) {
            int sz = candles5m.size();
            int bullCount = 0, bearCount = 0;
            for (int i = sz - 3; i < sz; i++) {
                Candle c5 = candles5m.get(i);
                if (c5.getClose().doubleValue() > c5.getOpen().doubleValue()) bullCount++;
                else bearCount++;
            }
            // 5m momentum must not strongly contradict HTF direction
            // (e.g. 3 consecutive bearish 5m candles during an HTF BUY setup reduces confidence)
            shortTermBull = bullCount >= 2; // ≥2 of 3 bullish
            shortTermBear = bearCount >= 2; // ≥2 of 3 bearish
        }

        // Gate 4: Setup detection — try all 6 setup types
        SmcSetup setup = detectSetup(symbol, currentPrice, lastCandle, prevCandle,
                htf, buyAllowed, sellAllowed);
        if (setup == null) return null;

        // Gate 5: Confirmation candle validation
        CandlePattern pattern = detectCandlePattern(lastCandle, prevCandle, setup.isBuy);
        if (pattern == CandlePattern.NONE) {
            log.trace("[SMC] {} Gate5 BLOCKED — no valid confirmation candle ({})",
                    symbol, setup.setupType);
            return null;
        }

        // Compute SL and T1
        double entry = setup.isBuy
                ? currentPrice * 1.0003   // tiny entry buffer
                : currentPrice * 0.9997;
        double sl = setup.isBuy
                ? setup.slPrice * (1.0 - SL_BUFFER_PCT)
                : setup.slPrice * (1.0 + SL_BUFFER_PCT);
        double risk = Math.abs(entry - sl);

        // Reject oversized SL
        if (risk / entry > MAX_SL_PCT) {
            log.trace("[SMC] {} Gate5 BLOCKED — SL too wide {:.2f}%", symbol, risk/entry*100);
            return null;
        }

        // Gate 6: RR ≥ 3.0
        double target = setup.isBuy ? entry + risk * minRr : entry - risk * minRr;
        double rr     = risk > 0 ? Math.abs(target - entry) / risk : 0;
        if (rr < minRr) {
            log.trace("[SMC] {} Gate6 BLOCKED — RR {:.2f} < {}", symbol, rr, minRr);
            return null;
        }
        double t2 = setup.isBuy ? entry + risk * PREFERRED_RR : entry - risk * PREFERRED_RR;

        // Gate 7: No blocking opposite zone between entry and target
        if (hasBlockingZone(htf, entry, target, setup.isBuy)) {
            log.trace("[SMC] {} Gate7 BLOCKED — opposing zone blocks target path", symbol);
            return null;
        }

        // Gate 8: Confidence score
        int confidence = computeConfidence(htf, setup, pattern, rr, dir, shortTermBull, shortTermBear);
        if (confidence < minConfidence) {
            log.trace("[SMC] {} Gate8 BLOCKED — confidence {} < threshold {}",
                    symbol, confidence, minConfidence);
            return null;
        }

        // Gate 10: Sector alignment
        String sector = sectorClassify.getSector(symbol);
        if (sector != null && !sector.isEmpty()) {
            SectorStrengthService.SectorData sd = sectorStrength.getSector(sector);
            if (sd != null) {
                boolean sectorOk = setup.isBuy
                        ? (sd.alignedBullish() && sd.changePercent() >= 0.25)
                        : (sd.alignedBearish() && sd.changePercent() <= -0.25);
                if (!sectorOk) {
                    log.trace("[SMC] {} Gate10 BLOCKED — sector {} not aligned ({}%)",
                            symbol, sector, sd.changePercent());
                    return null;
                }
            }
        }

        // Position sizing (1% risk rule via PositionSizerService)
        TradeDirection direction = setup.isBuy ? TradeDirection.LONG : TradeDirection.SHORT;
        BigDecimal entryBd = BigDecimal.valueOf(entry).setScale(2, RoundingMode.HALF_UP);
        BigDecimal slBd    = BigDecimal.valueOf(sl).setScale(2,
                setup.isBuy ? RoundingMode.FLOOR : RoundingMode.CEILING);

        PositionSizerService.PositionSize pos =
                positionSizer.calculate(cap, entryBd, slBd, symbol, direction.name());
        if (!pos.isValid() || pos.quantity() <= 0) {
            log.trace("[SMC] {} invalid position size: {}", symbol, pos.invalidReason());
            return null;
        }

        long token = resolveToken(symbol);

        log.info("[SMC] ✅ CANDIDATE: {} | {} | setup={} | entry={} sl={} T1={} T2={} | RR={} conf={}",
                symbol, direction, setup.setupType,
                String.format("%.2f", entry), String.format("%.2f", sl),
                String.format("%.2f", target), String.format("%.2f", t2),
                String.format("%.2f", rr), confidence);

        return new SmcCandidate(
                symbol, direction, token,
                entryBd, slBd,
                BigDecimal.valueOf(target).setScale(2, RoundingMode.HALF_UP),
                BigDecimal.valueOf(t2).setScale(2, RoundingMode.HALF_UP),
                pos.quantity(), pos.actualRisk(),
                confidence, rr, setup.setupType, pattern,
                htf.trend, sector != null ? sector : "N/A",
                setup.isBuy ? htf.nearestSupport : htf.nearestResistance,
                setup.liquiditySweepDetected, lastCandle.getVolume()
        );
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SETUP DETECTION — 6 setup types per spec section 22
    // ══════════════════════════════════════════════════════════════════════════

    private SmcSetup detectSetup(String symbol, double price, Candle last, Candle prev,
                                 HtfStructure htf, boolean buyAllowed, boolean sellAllowed) {

        // ── Setup 1: Strong Support Bounce ────────────────────────────────────
        if (buyAllowed && htf.nearestSupport != null
                && htf.nearestSupport.isNear(price, ZONE_ENTRY_TOL)) {
            if (htf.nearestSupport.strength >= 30) {
                // Valid only if candle shows lower wick rejection
                double wickRatio = (last.getLow().doubleValue() < last.getOpen().doubleValue())
                        ? (last.getOpen().doubleValue() - last.getLow().doubleValue()) / (last.getHigh().doubleValue() - last.getLow().doubleValue() + 0.001)
                        : 0;
                if (wickRatio >= 0.35) { // meaningful lower wick
                    return SmcSetup.buy("SUPPORT_BOUNCE", htf.nearestSupport.low, false);
                }
            }
        }

        // ── Setup 2: Strong Resistance Rejection ─────────────────────────────
        if (sellAllowed && htf.nearestResistance != null
                && htf.nearestResistance.isNear(price, ZONE_ENTRY_TOL)) {
            if (htf.nearestResistance.strength >= 30) {
                double wickRatio = (last.getHigh().doubleValue() > last.getOpen().doubleValue())
                        ? (last.getHigh().doubleValue() - last.getOpen().doubleValue()) / (last.getHigh().doubleValue() - last.getLow().doubleValue() + 0.001)
                        : 0;
                if (wickRatio >= 0.35) {
                    return SmcSetup.sell("RESISTANCE_REJECTION", htf.nearestResistance.high, false);
                }
            }
        }

        // ── Setup 3: Trendline Bounce ─────────────────────────────────────────
        if (buyAllowed && htf.ascendingTrendline != null
                && htf.ascendingTrendline.isNear(price, ZONE_ENTRY_TOL)) {
            return SmcSetup.buy("TRENDLINE_BOUNCE", htf.ascendingTrendline.currentPrice * 0.997, false);
        }
        if (sellAllowed && htf.descendingTrendline != null
                && htf.descendingTrendline.isNear(price, ZONE_ENTRY_TOL)) {
            return SmcSetup.sell("TRENDLINE_REJECTION", htf.descendingTrendline.currentPrice * 1.003, false);
        }

        // ── Setup 4: Liquidity Sweep Reversal ────────────────────────────────
        SmcSetup sweepSetup = detectLiquiditySweep(price, last, prev, htf, buyAllowed, sellAllowed);
        if (sweepSetup != null) return sweepSetup;

        // ── Setup 5: Breakout Retest ─────────────────────────────────────────
        SmcSetup retestSetup = detectBreakoutRetest(price, last, htf, buyAllowed, sellAllowed);
        if (retestSetup != null) return retestSetup;

        // ── Setup 6: Channel S/R ─────────────────────────────────────────────
        if (htf.channel != null) {
            if (buyAllowed && htf.channel.nearLower(price, ZONE_ENTRY_TOL)) {
                return SmcSetup.buy("CHANNEL_SUPPORT", htf.channel.lowerPrice * 0.997, false);
            }
            if (sellAllowed && htf.channel.nearUpper(price, ZONE_ENTRY_TOL)) {
                return SmcSetup.sell("CHANNEL_RESISTANCE", htf.channel.upperPrice * 1.003, false);
            }
        }

        return null; // no valid setup
    }

    private SmcSetup detectLiquiditySweep(double price, Candle last, Candle prev,
                                          HtfStructure htf,
                                          boolean buyAllowed, boolean sellAllowed) {
        for (LiquidityZone lz : htf.liquidityZones) {
            double zp = lz.price;
            // Sweep detection: wick went beyond zone but candle CLOSED back inside
            boolean wickBeyond = lz.isBuySide
                    ? (last.getHigh().doubleValue() > zp * 1.001)   // swept above equal highs
                    : (last.getLow().doubleValue() < zp * 0.999);    // swept below equal lows

            boolean closedBack = lz.isBuySide
                    ? (last.getClose().doubleValue() < zp)            // rejected back below
                    : (last.getClose().doubleValue() > zp);           // rejected back above

            if (wickBeyond && closedBack) {
                // Sweep above equal highs → bearish reversal (SELL)
                if (lz.isBuySide && sellAllowed) {
                    return SmcSetup.sell("LIQUIDITY_SWEEP_HIGH", last.getHigh().doubleValue() * 1.002, true);
                }
                // Sweep below equal lows → bullish reversal (BUY)
                if (!lz.isBuySide && buyAllowed) {
                    return SmcSetup.buy("LIQUIDITY_SWEEP_LOW", last.getLow().doubleValue() * 0.998, true);
                }
            }
        }
        return null;
    }

    private SmcSetup detectBreakoutRetest(double price, Candle last,
                                          HtfStructure htf,
                                          boolean buyAllowed, boolean sellAllowed) {
        // BUY retest: price broke above resistance, now retesting former resistance as support
        if (buyAllowed) {
            for (SrZone res : htf.resistanceZones) {
                if (res.isFlipped && res.isNear(price, ZONE_ENTRY_TOL) && price > res.price * 0.994) {
                    return SmcSetup.buy("BREAKOUT_RETEST_BUY", res.low * 0.998, false);
                }
            }
        }
        // SELL retest: broke below support, retesting former support as resistance
        if (sellAllowed) {
            for (SrZone sup : htf.supportZones) {
                if (sup.isFlipped && sup.isNear(price, ZONE_ENTRY_TOL) && price < sup.price * 1.006) {
                    return SmcSetup.sell("BREAKDOWN_RETEST_SELL", sup.high * 1.002, false);
                }
            }
        }
        return null;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CANDLE PATTERN RECOGNITION (15m confirmation)
    // ══════════════════════════════════════════════════════════════════════════

    private CandlePattern detectCandlePattern(Candle last, Candle prev, boolean isBuy) {
        double open  = last.getOpen().doubleValue();
        double close = last.getClose().doubleValue();
        double high  = last.getHigh().doubleValue();
        double low   = last.getLow().doubleValue();
        double range = high - low;
        if (range < 0.001) return CandlePattern.NONE;

        double body     = Math.abs(close - open);
        double bodyRatio = body / range;
        boolean bullish  = close > open;
        boolean bearish  = close < open;

        // Marubozu: body ≥ 80% of range
        if (isBuy && bullish && bodyRatio >= 0.80)   return CandlePattern.MARUBOZU;
        if (!isBuy && bearish && bodyRatio >= 0.80)  return CandlePattern.MARUBOZU;

        // Engulfing: current candle body engulfs previous body
        double prevBody  = Math.abs(prev.getClose().doubleValue() - prev.getOpen().doubleValue());
        boolean prevBull = prev.getClose().doubleValue() > prev.getOpen().doubleValue();
        if (isBuy && bullish && !prevBull && body > prevBody * 1.05
                && close > prev.getOpen().doubleValue() && open < prev.getClose().doubleValue()) {
            return CandlePattern.BULLISH_ENGULFING;
        }
        if (!isBuy && bearish && prevBull && body > prevBody * 1.05
                && close < prev.getOpen().doubleValue() && open > prev.getClose().doubleValue()) {
            return CandlePattern.BEARISH_ENGULFING;
        }

        // Pin bar / hammer / shooting star: wick ≥ 2x body, body ≤ 40%
        double upperWick = high - Math.max(open, close);
        double lowerWick = Math.min(open, close) - low;
        if (isBuy && lowerWick >= body * 2.0 && bodyRatio <= 0.40
                && lowerWick >= range * 0.50) {
            return CandlePattern.PIN_BAR;
        }
        if (!isBuy && upperWick >= body * 2.0 && bodyRatio <= 0.40
                && upperWick >= range * 0.50) {
            return CandlePattern.PIN_BAR;
        }

        // Strong momentum candle: body ≥ 60%, correct direction
        if (isBuy && bullish && bodyRatio >= 0.60)   return CandlePattern.MOMENTUM;
        if (!isBuy && bearish && bodyRatio >= 0.60)  return CandlePattern.MOMENTUM;

        // Reclaim candle: candle closed beyond a level it had previously wicked below/above
        // For BUY: current candle closes above prev candle's open (reclaims lost ground)
        // For SELL: current candle closes below prev candle's open
        boolean bullReclaim = bullish && close > prev.getOpen().doubleValue() && prev.getClose().doubleValue() < prev.getOpen().doubleValue();
        boolean bearReclaim = bearish && close < prev.getOpen().doubleValue() && prev.getClose().doubleValue() > prev.getOpen().doubleValue();
        if (isBuy  && bullReclaim) return CandlePattern.RECLAIM;
        if (!isBuy && bearReclaim) return CandlePattern.RECLAIM;

        return CandlePattern.NONE;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CONFIDENCE SCORING (0-100)
    // ══════════════════════════════════════════════════════════════════════════

    private int computeConfidence(HtfStructure htf, SmcSetup setup,
                                  CandlePattern pattern, double rr,
                                  MarketDirectionService.MarketDirectionResult dir,
                                  boolean shortTermBull, boolean shortTermBear) {
        int score = 0;

        // HTF trend clarity (+20 for clear trend, +10 for sideways with sweep)
        if (!htf.isSideways())                   score += 20;
        else if (setup.liquiditySweepDetected)   score += 10;

        // HTF momentum (+10)
        if (htf.momentumScore >= 60)             score += 10;

        // Zone quality — nearest S/R strength (up to +15)
        SrZone zone = setup.isBuy ? htf.nearestSupport : htf.nearestResistance;
        if (zone != null) score += Math.min(15, zone.strength / 7);

        // Trendline confluence (+10 if at trendline AND zone)
        boolean atTrendline = (setup.isBuy && htf.ascendingTrendline != null
                && htf.ascendingTrendline.isNear(setup.slPrice, 0.01))
                || (!setup.isBuy && htf.descendingTrendline != null
                && htf.descendingTrendline.isNear(setup.slPrice, 0.01));
        if (atTrendline)                         score += 10;

        // Channel confluence (+8)
        if (htf.channel != null
                && (htf.channel.nearLower(setup.slPrice, 0.01)
                || htf.channel.nearUpper(setup.slPrice, 0.01))) score += 8;

        // Liquidity sweep (+12 — high probability reversal)
        if (setup.liquiditySweepDetected)        score += 12;

        // Confirmation candle quality
        score += switch (pattern) {
            case BULLISH_ENGULFING, BEARISH_ENGULFING -> 15;
            case MARUBOZU                             -> 12;
            case PIN_BAR                              -> 10;
            case RECLAIM                             -> 10; // reclaim candle: strong reversal signal
            case MOMENTUM                             ->  8;
            default                                  ->  0;
        };

        // RR quality (+10 if ≥1:5, +5 if ≥1:3)
        if (rr >= PREFERRED_RR)                  score += 10;
        else if (rr >= minRr)                   score +=  5;

        // Market regime confirmation (+5)
        boolean mktBull = dir.direction() == MarketDirectionService.Direction.BULLISH;
        boolean mktBear = dir.direction() == MarketDirectionService.Direction.BEARISH;
        if ((setup.isBuy && mktBull) || (!setup.isBuy && mktBear)) score += 5;

        // 5m short-term momentum confirmation (+5 if LTF agrees with HTF direction)
        // Note: LTF agreement boosts score but LTF disagreement does NOT block the trade
        // (HTF direction is authoritative — spec section 11: lower TF must NEVER override HTF)
        if (setup.isBuy  && shortTermBull) score += 5;
        if (!setup.isBuy && shortTermBear) score += 5;

        return Math.min(score, 100);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // BLOCKING ZONE CHECK
    // ══════════════════════════════════════════════════════════════════════════

    private boolean hasBlockingZone(HtfStructure htf, double entry,
                                    double target, boolean isBuy) {
        // For BUY: check if a strong resistance zone sits between entry and target
        if (isBuy) {
            for (SrZone res : htf.resistanceZones) {
                if (res.strength < 40) continue; // only major zones block
                if (res.price > entry && res.price < target * 0.97) {
                    // Allow if it's a flipped zone (former resistance now support)
                    if (!res.isFlipped) return true;
                }
            }
        } else {
            for (SrZone sup : htf.supportZones) {
                if (sup.strength < 40) continue;
                if (sup.price < entry && sup.price > target * 1.03) {
                    if (!sup.isFlipped) return true;
                }
            }
        }
        return false;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SIGNAL FIRING — publishes SmcSignalEvent (SMC-owned event)
    // SmcSignalHandler converts SmcSignalEvent → SmartChannelPullbackSignalEvent
    // so the standard pipeline handles it without any coupling to SCPS strategy.
    // ══════════════════════════════════════════════════════════════════════════

    private void fireSignal(SmcCandidate c) {
        int totalScore = c.confidence; // composite confidence as total score

        log.info("[SMC] 🚀 FIRING SIGNAL: {} | {} | setup={} | conf={} | entry={} sl={} T1={} T2={} | RR={} | pattern={}",
                c.symbol, c.direction, c.setupType, c.confidence,
                c.entryPrice, c.stopLoss, c.target1, c.target2,
                String.format("%.2f", c.rr), c.pattern);

        SmcSignalEvent signal = new SmcSignalEvent(
                this,                       // source
                c.symbol,                   // tradingSymbol
                c.instrumentToken,          // instrumentToken
                c.direction,                // direction
                c.entryPrice,               // entryPrice
                c.stopLoss,                 // stopLoss
                c.target1,                  // target1
                c.target2,                  // target2
                c.quantity,                 // quantity
                c.riskAmount,               // riskAmount
                STRATEGY_NAME,              // strategyName = "SMC_INSTITUTIONAL_V1"
                (double) totalScore,        // probabilityScore
                c.sector,                   // sectorName
                c.rr,                       // rrRatio
                c.setupType,                // setupType
                c.confidence,               // confidenceScore
                totalScore,                 // totalScore
                timeStopMinutes,            // timeStopMinutes
                c.liquiditySweepDetected    // liquiditySweepDetected
        );

        publisher.publishEvent(signal);

        firedToday.add(c.symbol);
        activeSignals.add(c.symbol);
        tradesExecutedToday.incrementAndGet();

        log.info("[SMC] ✅ Signal #{}/{} fired for {} | HTF={} | setup={} | conf={}",
                tradesExecutedToday.get(), "(platform cap)",  // no SMC-specific limit
                c.symbol, c.htfTrend, c.setupType, c.confidence);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DAILY RESET
    // ══════════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyReset() {
        tradesExecutedToday.set(0);
        firedToday.clear();
        activeSignals.clear();
        log.info("[SMC] Daily reset — 2 trade slots available");
    }

    /** Called when a signal lock should be released (trade closed). */
    public void onSignalClosed(String symbol) {
        activeSignals.remove(symbol);
        log.debug("[SMC] Signal lock released for {}", symbol);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // WEBSOCKET TICK LISTENER — live price monitoring
    // Spec section 6: WebSocket Tick Feed → Redis Pub/Sub → Strategy Engine
    //
    // Purpose: monitor active SMC signal proximity in real time.
    // Does NOT generate new signals (scheduled cycle handles that).
    // Used to detect if price is drifting away from entry zone post-signal.
    // ══════════════════════════════════════════════════════════════════════════

    @org.springframework.context.event.EventListener
    @org.springframework.scheduling.annotation.Async("tickExecutor")
    public void onTick(TickReceivedEvent tick) {
        // Only active during trading window
        LocalTime now = LocalTime.now(IST);
        LocalTime tradeEnd = parseTradeEnd();
        if (now.isBefore(TRADE_START) || now.isAfter(tradeEnd)) return;

        // Only process symbols with active signals (prevents hot-path overhead)
        String symbol = tick.getTradingSymbol();
        if (!activeSignals.contains(symbol)) return;

        // Stale-price guard: if LTP has moved >1% away from HTF zone, release lock
        // (prevents holding a signal lock on a symbol that has moved away from entry)
        double ltp = tick.getLastTradedPrice().doubleValue();
        HtfStructure htf = structureService.getStructure(symbol);
        if (htf == null) return;

        double nearestZonePrice = 0;
        if (htf.nearestSupport != null)    nearestZonePrice = htf.nearestSupport.price;
        if (htf.nearestResistance != null) nearestZonePrice = htf.nearestResistance.price;

        if (nearestZonePrice > 0) {
            double drift = Math.abs(ltp - nearestZonePrice) / nearestZonePrice;
            if (drift > 0.015) { // 1.5% drift from zone → release signal lock
                log.debug("[SMC] {} price drifted {:.2f}% from zone — releasing signal lock",
                        symbol, drift * 100);
                onSignalClosed(symbol);
            }
        }
    }

    // ── Dashboard helpers ────────────────────────────────────────────────────
    public int     getTradesExecutedToday() { return tradesExecutedToday.get(); }
    public int     getRemainingSlots()      { return 10 - tradesExecutedToday.get(); } // platform cap
    public boolean isEnabled()              { return enabled; }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Parses strategy.smc.trade-end (HH:mm) into LocalTime. Defaults to 14:40. */
    private LocalTime parseTradeEnd() {
        try {
            return LocalTime.parse(tradeEndConfig);
        } catch (Exception e) {
            return LocalTime.of(14, 40); // safe default
        }
    }

    private BigDecimal resolveCapital() {
        return "PAPER".equalsIgnoreCase(tradingMode) ? paperAccount.getCapital() : configCapital;
    }

    private long resolveToken(String symbol) {
        try {
            Instrument inst = instrumentCache.getEquityInstruments().get(symbol.toUpperCase());
            return inst != null ? inst.getInstrument_token() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // INTERNAL RECORDS / ENUMS
    // ══════════════════════════════════════════════════════════════════════════

    public enum CandlePattern {
        BULLISH_ENGULFING, BEARISH_ENGULFING, PIN_BAR, MOMENTUM, MARUBOZU, RECLAIM, NONE
    }

    private static class SmcSetup {
        final String  setupType;
        final boolean isBuy;
        final double  slPrice;
        final boolean liquiditySweepDetected;

        SmcSetup(String setupType, boolean isBuy, double slPrice, boolean sweep) {
            this.setupType = setupType; this.isBuy = isBuy;
            this.slPrice = slPrice; this.liquiditySweepDetected = sweep;
        }
        static SmcSetup buy(String type, double sl, boolean sweep)  {
            return new SmcSetup(type, true,  sl, sweep);
        }
        static SmcSetup sell(String type, double sl, boolean sweep) {
            return new SmcSetup(type, false, sl, sweep);
        }
    }

    private record SmcCandidate(
            String          symbol,
            TradeDirection  direction,
            long            instrumentToken,
            BigDecimal      entryPrice,
            BigDecimal      stopLoss,
            BigDecimal      target1,
            BigDecimal      target2,
            int             quantity,
            BigDecimal      riskAmount,
            int             confidence,
            double          rr,
            String          setupType,
            CandlePattern   pattern,
            TrendDirection  htfTrend,
            String          sector,
            SrZone          anchorZone,
            boolean         liquiditySweepDetected,
            long            volume
    ) {}
}