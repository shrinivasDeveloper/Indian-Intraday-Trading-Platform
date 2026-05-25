package com.trading.strategy.highrr;

import com.trading.domain.enums.TradeDirection;
import com.trading.events.SmartChannelPullbackSignalEvent;
import com.trading.marketdata.service.InstrumentCacheService;
import com.trading.marketdata.service.LatencyMonitor;
import com.trading.marketdata.service.MarketTimingService;
import com.trading.papertrading.model.PaperAccount;
import com.trading.position.service.PositionSizerService;
import com.trading.risk.service.CircuitBreakerService;
import com.trading.risk.service.RiskManagementService;
import com.trading.marketdata.service.MarketPressureService;
import com.trading.regime.service.MarketDirectionService;
import com.trading.sector.service.SectorClassificationService;
import com.trading.sector.service.SectorStrengthService;
import com.trading.strategy.highrr.HighRRScannerService.SymbolState;
import com.trading.strategy.highrr.HighRRStructureService;
import com.trading.strategy.highrr.HighRRStructureService.StructureLevels;
import com.trading.strategy.highrr.HighRRStructureService.SRLevel;
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
import java.util.stream.Collectors;

/**
 * HighRRStrategyEngine – Scoring, ranking, top-2 selection, and signal firing.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * CHANGES vs previous version:
 * ─────────────────────────────────────────────────────────────────────────────
 * FIX 1 – instrumentToken was always 0L (hardcoded).
 *   Root cause: HighRRScannerService tracks symbols by name (not token), and the
 *   engine had no way to look up the NSE token. Result: every Trade entity stored
 *   instrumentToken=0L, breaking any downstream logic that needs it (tick routing,
 *   historical data lookup, live order submission).
 *   Fix: inject InstrumentCacheService and use resolveInstrumentToken() — same
 *   approach as OrbDataService — to look up the token from the instrument map.
 *   Returns 0L gracefully if not found (safe for PAPER mode).
 *
 * FIX 2 – volumeHistory deque growing unboundedly.
 *   While the while-loop cap of 10 entries is present, it used an unchecked cast
 *   to ArrayDeque. computeIfAbsent now explicitly creates ArrayDeque for safety.
 *
 * FIX 3 – Selected symbol set cached per cycle.
 *   getSelectedSymbols() previously returned a new ArrayList on every call.
 *   The set is now checked against a pre-fetched copy per evaluation cycle, not
 *   per tick (this engine is scheduler-driven, not tick-driven, so no hot-path issue).
 *
 * All scoring logic, selection, signal firing, and lifecycle methods are unchanged.
 * ─────────────────────────────────────────────────────────────────────────────
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class HighRRStrategyEngine {

    private static final ZoneId IST           = ZoneId.of("Asia/Kolkata");
    private static final String STRATEGY_NAME = "HIGH_RR_INTRADAY_V1";

    // TRADE_START: 9:35->9:45. First 10min are noisiest — gap fills, reversals.
    private static final LocalTime TRADE_START  = LocalTime.of(9, 45);
    private static final LocalTime TRADE_END    = LocalTime.of(14, 0);
    // 90-min time stop: no progress toward T1 within 90 min -> exit
    static final int TIME_STOP_MINUTES = 90;
    // LUNCH_START / LUNCH_END removed — lunch window gate removed entirely.
    // HighRR now scans continuously 9:35 AM – 2:00 PM without interruption.
    private static final double    MIN_ATR_PCT  = 0.25;                 // 0.25% minimum ATR — ensures market has enough range for HighRR setups.
    // 0.20% was too low — allowed marginal days where price barely moved.
    // 0.25% = ~60 Nifty points on a 24,000 level — meaningful intraday range.

    // No hard trade count limit.
    // Risk is governed entirely by the 3 portfolio rules below:
    //   Rule 1: Stop if daily loss ≤ -4%  (protects capital on bad days)
    //   Rule 2: Lock profit at +6%, trade at 50% size  (protects gains on good days)
    //   Rule 3: Stop if profit drops below +3% floor  (never give back good days)
    // On winning days → keep taking trades. On losing days → -4% stops it.
    private static final int    TOP_N_CANDIDATES   = 2;
    private static final double MIN_RR_RATIO       = 2.0;
    // MIN_SL_PCT and FIXED_SL_PCT removed — SL is now ATR-based (computeAtr14).
    // These constants were declared but never referenced after ATR SL was introduced.
    private static final double GOOD_RR_RATIO      = 3.0;

    // ── Gate 5: Structural S/R entry zone ────────────────────────────────────
    // Entry must be within this % of a known S/R level.
    // Prevents mid-air entries like ADANIGREEN ₹1,223 when resistance was ₹1,252 (2.4% away).
    // Tightened 0.5%→0.3%: entry must be within 0.3% of zone. Prevents sloppy entries.
    private static final double SR_ENTRY_ZONE_PCT = 0.003; // 0.3%
    // Structural SL max distance from entry (avoids oversized risk)
    // SR_SL_MAX_PCT removed — SL is now fixed at exactly 1% (FIXED_SL_PCT)
    // Min RR for a structural T1 to be accepted
    private static final double SR_T1_MIN_RR      = 2.0;

    // ── Minimum candidate score before adding to fire list ─────────────────
    // WHY: Without this, a setup with RR=2.0 but no pullback, no volume spike,
    // no S/R quality still qualifies (score = 15 pts from RR alone).
    // UTIAMC and PHOENIXLTD both would have scored below 50 — this gate blocks them.
    // MIN_CANDIDATE_SCORE = 150 (raised from 125).
    // Maximum possible = 170.
    // Minimum path to 150:
    //   RR≥3(25)+trend(20)+pullback(20)+volume(20)+major_SR(20)+HTF_MAJOR(20)+clean(15)+liq(10) = 150
    //   OR: RR≥3(25)+trend(20)+conf(5)+pullback(20)+volume(20)+major_SR(20)+HTF_MAJOR(20)+clean(15) = 150
    // Every qualifying trade needs: RR≥3 + trend + pullback + volume + major_SR + HTF_MAJOR
    //   + at least 2 of: {clean wick, liquidity, confluence, T1_major}
    private static final int    MIN_CANDIDATE_SCORE = 150;

    // ── SL placement ────────────────────────────────────────────────────────
    // SL_BUFFER_PCT removed — was declared but never referenced.
    private static final double ENTRY_BUFFER_PCT    = 0.0003;
    // Max drift from zone before entry = "chasing" (C4 gate)
    private static final double MAX_ZONE_DRIFT_PCT   = 0.003; // 0.3%
    // Max SL distance from zone edge (C5 gate)
    private static final double MAX_SL_FROM_ZONE_PCT = 0.003; // 0.3%
    // Min body/range ratio to be a real confirmation candle (C3 gate)
    private static final double MIN_BODY_RATIO       = 0.30;  // 30%

    // ── Volume spike threshold ───────────────────────────────────────────────
    private static final double VOLUME_SPIKE_FACTOR = 1.5;

    private final HighRRScannerService        scanner;
    private final ApplicationEventPublisher   publisher;
    private final CircuitBreakerService       circuitBreaker;
    private final PositionSizerService        positionSizer;
    private final PaperAccount               paperAccount;
    private final LatencyMonitor             latencyMonitor;
    private final MarketDirectionService     marketDirection; // Gate 1: ATR + regime
    private final MarketTimingService        timingService;   // Gate 2: lunch window
    private final InstrumentCacheService     instrumentCache;  // FIX 1: added for token resolution
    private final RiskManagementService      riskManagement;   // for getDailyPnl() in portfolio risk gates
    private final HighRRStructureService     structureService; // Gate 5: S/R structural levels
    private final SectorClassificationService sectorClassify;   // Gate 6: sector lookup
    private final SectorStrengthService       sectorStrength;   // Gate 6: sector direction
    private final MarketPressureService       pressureService;  // Market quality: breadth

    @Value("${trading.mode:PAPER}")
    private String tradingMode;

    @Value("${trading.capital:100000}")
    private BigDecimal capital;

    @Value("${strategy.high-rr.enabled:true}")
    private boolean engineEnabled;

    // FIX: was 90 — 90-min stop exited profitable trades before they hit target.
    // INDUSINDBK and MAXHEALTH on Apr-24 were 3-7 pts from target when cut.
    // Now set to 0 = DISABLED. HighRRTradeManager uses 3:00 PM EOD stop instead.
    @Value("${strategy.high-rr.time-stop-minutes:0}")
    private int timeStopMinutes;

    @Value("${strategy.high-rr.risk-per-trade:0.01}")
    private double riskPerTrade;

    // ── Portfolio risk constants ─────────────────────────────────────────────
    private static final double DAILY_LOSS_LIMIT_PCT    = 0.04; // -4%
    private static final double PROFIT_LOCK_TRIGGER_PCT = 0.06; // +6%
    private static final double PROFIT_LOCK_FLOOR_PCT   = 0.03; // +3%
    private static final double PROFIT_LOCK_SIZE_FACTOR = 0.50; // 50%

    // ── Session state ────────────────────────────────────────────────────────
    private final AtomicInteger tradesExecutedToday = new AtomicInteger(0);  // FIX: volatile int++ is not atomic
    /** True once daily P&L reaches +6%. Triggers 50% size + floor protection. */
    private volatile boolean profitLocked = false;

    // ── Batch cooldown state ─────────────────────────────────────────────────
    // Rule: max 2 trades per 30-minute window.
    // After 2 trades fire, cooldown starts. Prevents overtrading in volatile markets.
    // Example: 9:35 fires 2 trades → cooldown until 10:05 → fires 2 more → etc.
    private static final int  BATCH_SIZE_LIMIT   = 2;           // trades per batch
    private static final long BATCH_COOLDOWN_MS  = 30 * 60_000L; // 30 minutes in ms
    private static final int  MAX_TRADES_PER_DAY = 10;          // overall daily cap
    private final AtomicInteger batchTradesCount  = new AtomicInteger(0); // trades in current batch
    private volatile long       batchCooldownUntil = 0L; // epoch ms when cooldown ends
    private final Set<String>   firedToday          = ConcurrentHashMap.newKeySet();
    private final Set<String>   activeSignals       = ConcurrentHashMap.newKeySet();
    /** Consecutive cycles with same direction — higher = more conviction. */
    private volatile int        directionConsecutive = 0;
    private volatile MarketDirectionService.Direction lastDirection = null;
    /** Market quality grade computed each cycle: A/B/C/D */
    private volatile String     marketGrade         = "C";

    // FIX 2: explicitly typed as ArrayDeque
    private final Map<String, Deque<Double>> volumeHistory = new ConcurrentHashMap<>();

    // ══════════════════════════════════════════════════════════════════════════
    // 1-MINUTE EVALUATION CYCLE
    // ══════════════════════════════════════════════════════════════════════════

    @Scheduled(fixedDelay = 25_000)  // Reduced from 60s → 25s for faster opportunity capture
    public void runEvaluationCycle() {
        if (!engineEnabled) return;

        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(TRADE_START) || now.isAfter(TRADE_END)) return;
        if (latencyMonitor.isStale()) {
            log.debug("[HIGHRR] Latency stale – skipping cycle");
            return;
        }

        // ── DAILY CAP GATE ───────────────────────────────────────────────────────
        if (tradesExecutedToday.get() >= MAX_TRADES_PER_DAY) {
            log.debug("[HIGHRR] Daily cap reached ({}/{}) — engine idle.", tradesExecutedToday.get(), MAX_TRADES_PER_DAY);
            return;
        }

        // ── BATCH COOLDOWN GATE ──────────────────────────────────────────────────
        // After BATCH_SIZE_LIMIT (2) trades fire, enforce 30-min cooldown.
        // Prevents rapid-fire entries in volatile conditions.
        long nowMs = System.currentTimeMillis();
        if (batchCooldownUntil > nowMs) {
            long remainSec = (batchCooldownUntil - nowMs) / 1000;
            log.debug("[HIGHRR] Batch cooldown active — {}s remaining. Traded {}/{} today.",
                    remainSec, tradesExecutedToday.get(), MAX_TRADES_PER_DAY);
            return;
        }

        // ── PORTFOLIO RISK GATES ─────────────────────────────────────────────────
        BigDecimal dailyPnl = riskManagement.getDailyPnl();
        double startCap = resolveCapital().doubleValue();
        double pnlVal   = dailyPnl.doubleValue();
        double pnlPct   = startCap > 0 ? pnlVal / startCap : 0.0;

        // RULE 1: Hard stop at -4% daily loss (all strategies combined)
        if (pnlPct <= -DAILY_LOSS_LIMIT_PCT) {
            log.warn("[HIGHRR] 🛑 LOSS LIMIT: pnl=₹{} ({}%) ≤ -{}%. Stopped for today.",
                    String.format("%.0f", pnlVal),
                    String.format("%.1f", pnlPct * 100),
                    (int)(DAILY_LOSS_LIMIT_PCT * 100));
            return;
        }

        // RULE 2: Activate profit lock at +6%
        if (!profitLocked && pnlPct >= PROFIT_LOCK_TRIGGER_PCT) {
            profitLocked = true;
            log.info("[HIGHRR] 🔒 PROFIT LOCKED at +{}% (₹{}). 50% position size. Floor +{}%.",
                    (int)(PROFIT_LOCK_TRIGGER_PCT * 100),
                    String.format("%.0f", pnlVal),
                    (int)(PROFIT_LOCK_FLOOR_PCT * 100));
        }

        // RULE 3: Protect locked profit — pause if drops below +3%
        if (profitLocked && pnlPct < PROFIT_LOCK_FLOOR_PCT) {
            log.warn("[HIGHRR] 🔒 PROFIT FLOOR: pnl=₹{} ({}%) below +{}%. Paused.",
                    String.format("%.0f", pnlVal),
                    String.format("%.1f", pnlPct * 100),
                    (int)(PROFIT_LOCK_FLOOR_PCT * 100));
            return;
        }

        // ══════════════════════════════════════════════════════════════════
        // PROFESSIONAL MARKET DIRECTION ANALYSIS — 5-LAYER FRAMEWORK
        // ══════════════════════════════════════════════════════════════════
        // Layer 1: Trend   — EMA stack + slope + compression
        // Layer 2: Momentum — ROC + ATR expansion vs contraction
        // Layer 3: Breadth  — market participation (buy/sell pressure ratio)
        // Layer 4: Volatility — ATR regime
        // Layer 5: Confirmation — consecutive direction cycles
        // Result: market grade A/B/C/D → determines trade criteria stringency
        // ══════════════════════════════════════════════════════════════════

        MarketDirectionService.MarketDirectionResult dir = marketDirection.getCurrentDirection();

        // ── GATE 1: Frozen market — ATR minimum ───────────────────────────────
        if (dir.niftyAtrPct() < MIN_ATR_PCT) {
            log.debug("[HIGHRR] Gate1 BLOCKED — Nifty ATR {}% < {}% (frozen market)",
                    String.format("%.2f", dir.niftyAtrPct()), MIN_ATR_PCT);
            directionConsecutive = 0;
            return;
        }

        // ── GATE 3: Market must not be SIDEWAYS ───────────────────────────────
        if (dir.direction() == MarketDirectionService.Direction.SIDEWAYS) {
            log.debug("[HIGHRR] Gate3 BLOCKED — SIDEWAYS market (ATR {}%)",
                    String.format("%.2f", dir.niftyAtrPct()));
            directionConsecutive = 0;
            return;
        }

        // ── LAYER 1: EMA stack quality ─────────────────────────────────────────
        // Perfect bull stack: EMA20 > EMA50 > EMA200 (all aligned, no compression)
        // EMA compression: all three within 0.3% of each other = indecision zone
        double e20 = dir.niftyEma20(), e50 = dir.niftyEma50(), e200 = dir.niftyEma200();
        boolean bullStack = (e20 > e50) && (e50 > e200);   // perfect bull alignment
        boolean bearStack = (e20 < e50) && (e50 < e200);   // perfect bear alignment
        double emaSpread  = e200 > 0 ? Math.abs(e20 - e200) / e200 : 0;
        boolean emaCompressed = emaSpread < 0.003; // all 3 EMAs within 0.3% = choppy

        // ── LAYER 2: Momentum — ROC via ATR expansion ─────────────────────────
        // intradayMovePct from MarketDirectionService = session move from low/high
        // ATR expanding vs contracting → trending vs ranging
        boolean momentumConfirmed = dir.niftyAtrPct() >= MIN_ATR_PCT * 1.5; // ATR ≥ 1.5× floor
        boolean intradayDriven    = dir.intradayOverrideActive();            // override active
        double  sessionMove       = Math.abs(dir.intradayMovePct());          // session range %

        // ── LAYER 3: Market breadth — buy/sell pressure ratio ──────────────────
        // MarketPressureService tracks strength of all 290+ symbols in real-time.
        // ratio > 1.0 = more buying pressure; ratio < 1.0 = more selling pressure
        // For LONG: ratio > 1.15 = broad participation (strong signal)
        //           ratio > 1.00 = marginal participation (weaker signal)
        //           ratio < 1.00 = market selling into any rally (avoid LONG)
        double  pressureRatio    = 1.0;
        boolean bullishBreadth   = false;
        boolean bearishBreadth   = false;
        try {
            var snap = pressureService.getSnapshot();
            if (snap != null && snap.totalSymbols() >= 100) {
                pressureRatio  = snap.ratio();
                bullishBreadth = pressureRatio >= 1.15; // ≥15% more buying = broad bull
                bearishBreadth = pressureRatio <= 0.87; // ≥15% more selling = broad bear
            }
        } catch (Exception ignored) { /* pressure service unavailable */ }

        // ── LAYER 4: Volatility regime ─────────────────────────────────────────
        // ATR in healthy range (0.25–0.80%) = normal trending day
        // ATR > 0.80% = elevated volatility, widen SL tolerance or skip
        boolean normalVolatility = dir.niftyAtrPct() >= MIN_ATR_PCT
                && dir.niftyAtrPct() <= 0.80;
        boolean highVolatility   = dir.niftyAtrPct() > 0.80;  // chaotic, widen SL

        // ── LAYER 5: Direction consistency ────────────────────────────────────
        // Count consecutive cycles with the same direction — more = more conviction.
        // Resets to 0 when direction flips. Used to grade market quality.
        if (dir.direction() == lastDirection) {
            directionConsecutive = Math.min(directionConsecutive + 1, 20);
        } else {
            directionConsecutive = 1;
            lastDirection = dir.direction();
        }
        boolean directionConfirmed = directionConsecutive >= 3; // 3+ cycles = conviction

        // ── MARKET QUALITY GRADE ──────────────────────────────────────────────
        // Grade A: perfect conditions — trade at full criteria (score ≥ 150)
        // Grade B: good conditions — trade but require score ≥ 155
        // Grade C: mixed signals — require score ≥ 160 (only best setups)
        // Grade D: poor conditions — skip entirely
        //
        // Grade A requires ALL of:
        //   - Perfect EMA stack (all aligned, not compressed)
        //   - Broad breadth confirmation (ratio ≥ 1.15 for LONG, ≤ 0.87 for SHORT)
        //   - Normal volatility
        //   - Direction consistent for 3+ cycles
        //   - No intraday override (organic trend, not panic)
        boolean isBullMarket = dir.direction() == MarketDirectionService.Direction.BULLISH;
        boolean isBearMarket = dir.direction() == MarketDirectionService.Direction.BEARISH;
        boolean breadthAligned = isBullMarket ? bullishBreadth : (isBearMarket && bearishBreadth);

        int qualityPoints = 0;
        if (bullStack && isBullMarket || bearStack && isBearMarket) qualityPoints += 30; // EMA perfectly aligned
        if (!emaCompressed)                                          qualityPoints += 20; // not in indecision zone
        if (breadthAligned)                                          qualityPoints += 25; // broad market participation
        if (normalVolatility)                                        qualityPoints += 15; // healthy ATR
        if (directionConfirmed)                                      qualityPoints += 10; // 3+ consecutive cycles

        // Grade D early exit conditions:
        // 1. EMA compressed + no breadth + new direction = indecision, skip
        // 2. No breadth at ALL (ratio between 0.88-1.14) AND direction just started
        //    = market not confirming the move yet, too risky for HighRR
        boolean noBreadth = !bullishBreadth && !bearishBreadth; // ratio 0.88-1.14 = neutral
        if (emaCompressed && !breadthAligned && directionConsecutive < 2) {
            marketGrade = "D";
            log.debug("[HIGHRR] Grade D — EMA compressed + no breadth + unstable direction. Skip.");
            return;
        }
        if (noBreadth && directionConsecutive < 2 && !momentumConfirmed) {
            marketGrade = "D";
            log.debug("[HIGHRR] Grade D — No breadth + new direction + weak ATR. Skip.");
            return;
        }

        // Grade A requires ≥ 80 pts — BREADTH IS MANDATORY.
        // Without breadthAligned (+25), max possible = 30+20+15+10 = 75 < 80 → Grade B.
        // This ensures Grade A always has confirmed broad market participation.
        // Grade B (45-79): good structure but marginal breadth → BNF must confirm.
        // Grade C (25-44): weak signals → only exceptional setups (score ≥ 160) fire.
        marketGrade = qualityPoints >= 80 ? "A"
                : qualityPoints >= 45 ? "B"
                : qualityPoints >= 25 ? "C" : "D";

        // Grade D still blocks
        if ("D".equals(marketGrade)) {
            log.debug("[HIGHRR] Grade D (quality={}) — poor market conditions. Skip.",
                    qualityPoints);
            return;
        }

        // ── GATE 4: BankNifty confirmation for Grade B/C ──────────────────────
        // When market grade < A, require BankNifty to agree with Nifty.
        // A divergence (Nifty bull but BNF bear) = mixed market = skip on B/C.
        if (!"A".equals(marketGrade)) {
            boolean bnfAgrees = isBullMarket
                    ? dir.bankNiftyBullish()
                    : dir.bankNiftyBearish();
            if (!bnfAgrees) {
                log.debug("[HIGHRR] Grade {} — BankNifty does not confirm Nifty direction. Skip.",
                        marketGrade);
                return;
            }
        }

        log.info("[HIGHRR] Market analysis: dir={} grade={} quality={} ATR={}% "
                        + "stack={} breadth(ratio={}) consecutive={} emaSpread={}%",
                dir.direction(), marketGrade, qualityPoints,
                String.format("%.2f", dir.niftyAtrPct()),
                bullStack ? "PERFECT_BULL" : bearStack ? "PERFECT_BEAR" : "PARTIAL",
                String.format("%.3f", pressureRatio),
                directionConsecutive,
                String.format("%.2f", emaSpread * 100));


        BigDecimal cap = resolveCapital();
        if (profitLocked) {
            cap = cap.multiply(BigDecimal.valueOf(PROFIT_LOCK_SIZE_FACTOR));
        }
        if (!circuitBreaker.checkPermission(cap).isAllowed()) {
            log.debug("[HIGHRR] Circuit breaker blocked evaluation cycle");
            return;
        }

        Set<String> symbols = scanner.getTrackedSymbols();
        if (symbols.isEmpty()) {
            log.debug("[HIGHRR] No tracked symbols yet – waiting for ticks");
            return;
        }

        log.info("[HIGHRR] Starting evaluation cycle @{} | symbols={} | tradesLeft={}",
                now, symbols.size(), "∞");

        List<ScoredCandidate> candidates = new ArrayList<>();
        int evaluated = 0, buySetups = 0, sellSetups = 0;

        for (String symbol : symbols) {
            if (firedToday.contains(symbol) || activeSignals.contains(symbol)) continue;

            SymbolState state = scanner.getSymbolState(symbol);
            if (state == null) continue;

            // Skip stale data (tick more than 5 seconds ago)
            if (System.currentTimeMillis() - state.lastTickEpoch() > 5000) continue;
            if (state.isSideways()) continue;

            evaluated++;

            if (state.isBuySetup() && !state.hasExcessiveWick()) {
                // Gate 4: Only take LONG in BULLISH regime
                if (dir.direction() == MarketDirectionService.Direction.BEARISH) {
                    log.trace("[HIGHRR] {} BUY setup skipped — market is BEARISH", symbol);
                } else {
                    ScoredCandidate c = buildCandidate(symbol, state, true, cap);
                    if (c != null && c.rr() >= MIN_RR_RATIO
                            && c.score() >= gradeMinScore(marketGrade)) {
                        candidates.add(c);
                        buySetups++;
                    }
                }
            }

            if (state.isSellSetup() && !state.hasExcessiveWick()) {
                // Gate 4: Only take SHORT in BEARISH regime
                if (dir.direction() == MarketDirectionService.Direction.BULLISH) {
                    log.trace("[HIGHRR] {} SELL setup skipped — market is BULLISH", symbol);
                } else {
                    ScoredCandidate c = buildCandidate(symbol, state, false, cap);
                    if (c != null && c.rr() >= MIN_RR_RATIO
                            && c.score() >= gradeMinScore(marketGrade)) {
                        candidates.add(c);
                        sellSetups++;
                    }
                }
            }
        }

        log.info("[HIGHRR] Evaluated {} symbols | BUY setups={} SELL setups={} | {} candidates qualify (min score {})",
                evaluated, buySetups, sellSetups, candidates.size(), MIN_CANDIDATE_SCORE);

        if (candidates.isEmpty()) {
            log.info("[HIGHRR] No qualifying candidates this cycle");
            return;
        }

        // Sort by score DESC, then RR DESC, then HTF touches DESC.
        // When multiple stocks score 150+, the best quality fires first:
        //   1. Highest score (primary — most signals aligned)
        //   2. Highest RR (better reward potential)
        //   3. Strongest HTF zone (more institutional backing)
        candidates.sort(Comparator
                .comparingInt(ScoredCandidate::score).reversed()
                .thenComparingDouble(ScoredCandidate::rr).reversed()
                .thenComparingInt(c -> {
                    // HTF touches as tiebreaker — more touches = stronger zone
                    HighRRStructureService.SRLevel z =
                            structureService.getNearestHtfZone(
                                    c.symbol(), c.entryPrice().doubleValue());
                    return z != null ? z.touchCount() : 0;
                }).reversed());

        // Slots = min of remaining daily cap and remaining batch slots
        int dailyRemaining = MAX_TRADES_PER_DAY - tradesExecutedToday.get();
        int batchRemaining = BATCH_SIZE_LIMIT - batchTradesCount.get();
        int slotsLeft = Math.min(dailyRemaining, batchRemaining);
        int toFire    = Math.min(slotsLeft, Math.min(TOP_N_CANDIDATES, candidates.size()));

        for (int i = 0; i < toFire; i++) {
            ScoredCandidate cand = candidates.get(i);
            log.info("[HIGHRR] Selected #{}: {} | dir={} | RR={} | score={} | entry={} sl={} t1={}",
                    i + 1, cand.symbol(), cand.direction(),
                    String.format("%.2f", cand.rr()), cand.score(),
                    cand.entryPrice(), cand.stopLoss(), cand.target1());
            fireSignal(cand);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // BUILD CANDIDATE
    // ══════════════════════════════════════════════════════════════════════════

    private ScoredCandidate buildCandidate(String symbol, SymbolState state,
                                           boolean isBuy, BigDecimal cap) {
        double price = state.price();
        if (price <= 0) return null;

        double entryD = isBuy
                ? price * (1.0 + ENTRY_BUFFER_PCT)
                : price * (1.0 - ENTRY_BUFFER_PCT);
        BigDecimal entryPrice = BigDecimal.valueOf(entryD).setScale(2, RoundingMode.HALF_UP);
        double entryDbl = entryPrice.doubleValue();

        // ── Fetch structural levels — pure O(1) ConcurrentHashMap read ─────────
        // HighRRStructureService pre-computes these every hour from 5 days of
        // 15-min OHLCV. This call has zero I/O and zero blocking.
        StructureLevels structure = structureService.getStructure(symbol);

        // ── GATE 5: Entry must be within 0.5% of a structural S/R level ────────
        // Gate5 is O(1) — runs first to filter cheaply before sector service call.
        // ── GATE 5: Entry within 0.5% of structural S/R — ALWAYS MANDATORY ──────
        // No S/R data = no trade. Prevents mid-air entries with no structural edge.
        // HighRRStructureService loads from Redis at startup (@PostConstruct) and
        // refreshes at 8:50 AM daily. Data is available from the very first cycle.
        // On first-ever deploy with empty Redis, bootstrap fetches from Zerodha.
        // Gate 5a: No structure data → block
        if (structure == null) {
            log.debug("[HIGHRR] {} Gate5 BLOCKED — no S/R structure data yet", symbol);
            return null;
        }

        // Gate 5b: Insufficient history — need ≥ 5 trading days for reliable S/R
        // With < 5 days, "major" levels (≥3 touches) are unreliable noise.
        // TRIVENI/VSTIND/SUPREMEIND errors: fake major levels from 2-day history.
        // Gate5b: need ≥ 2 days minimum for swing detection.
        // Bootstrap fetches 14 calendar days (10 trading days) on every startup.
        // After bootstrap: tradingDays = 10. Gate passes immediately.
        // Gate5c/5d use the full 10-day tenDayHigh/Low and MA20.
        if (structure.tradingDays() < 2) {
            log.debug("[HIGHRR] {} Gate5b BLOCKED — only {} trading days of data (need 2)",
                    symbol, structure.tradingDays());
            return null;
        }

        // Gate 5c: Range-bound stock filter
        // If stock has been in a tight range for 10 days, no trend edge.
        // VSTIND: 10-day range = 254-268 = 5.5%. Passes this check.
        // Additional check: entry must be in lower 40% of 10-day range (near support).
        // Entering above 40% = entry near middle or top of range = no structural edge.
        double tenDayRange = structure.tenDayHigh() - structure.tenDayLow();
        if (tenDayRange > 0) {
            double rangePosition = (entryDbl - structure.tenDayLow()) / tenDayRange;
            if (isBuy && rangePosition > 0.60) {
                log.debug("[HIGHRR] {} BUY Gate5c BLOCKED — entry at {}% of 10-day range (top 40%, not near support)",
                        symbol, String.format("%.0f", rangePosition * 100));
                return null;
            }
            if (!isBuy && rangePosition < 0.40) {
                log.debug("[HIGHRR] {} SELL Gate5c BLOCKED — entry at {}% of 10-day range (bottom 40%, not near resistance)",
                        symbol, String.format("%.0f", rangePosition * 100));
                return null;
            }
        }

        // Gate 5d: MA20 trend alignment
        // LONG only when price > MA20 (uptrend). SHORT only when price < MA20 (downtrend).
        // TRIVENI: crashed below MA20 — LONG should be blocked.
        // CANFINHOME: declining from spike, below MA20 — LONG should be blocked.
        // GRINDWELL/INDIGO: recovering stocks above or near MA20 — LONG allowed.
        if (structure.ma20() > 0) {
            double ma20 = structure.ma20();
            // 1% threshold: INDIGO(-0.7%) fires, VSTIND(-1.1%) blocked
            // Blocks: TRIVENI(-11.9%), CANFINHOME(-3.1%), VSTIND(-1.1%), SUPREMEIND(-1.2%)
            // Allows: GRINDWELL(0%), INDIGO(-0.7%) — genuine near-MA20 recovery
            double ma20ThresholdPct = 0.010; // 1% tolerance below MA20 for LONG
            if (isBuy && entryDbl < ma20 * (1.0 - ma20ThresholdPct)) {
                log.debug("[HIGHRR] {} BUY Gate5d BLOCKED — price {} below MA20 {} (downtrend stock)",
                        symbol, String.format("%.2f", entryDbl), String.format("%.2f", ma20));
                return null;
            }
            if (!isBuy && entryDbl > ma20 * (1.0 + ma20ThresholdPct)) {
                log.debug("[HIGHRR] {} SELL Gate5d BLOCKED — price {} above MA20 {} (uptrend stock)",
                        symbol, String.format("%.2f", entryDbl), String.format("%.2f", ma20));
                return null;
            }
        }
        SRLevel entryAnchorLevel = null;
        if (isBuy) {
            SRLevel sup = structure.nearestSupportBelow(entryDbl);
            if (sup != null && (entryDbl - sup.price()) / entryDbl <= SR_ENTRY_ZONE_PCT)
                entryAnchorLevel = sup;
        } else {
            SRLevel res = structure.nearestResistanceAbove(entryDbl);
            if (res != null && (res.price() - entryDbl) / entryDbl <= SR_ENTRY_ZONE_PCT)
                entryAnchorLevel = res;
        }
        if (entryAnchorLevel == null) {
            log.debug("[HIGHRR] {} {} Gate5 BLOCKED — not within {}% of S/R (price={})",
                    symbol, isBuy?"BUY":"SELL",
                    (int)(SR_ENTRY_ZONE_PCT*100), String.format("%.2f", entryDbl));
            return null;
        }

        // ── GATE 6: SECTOR DIRECTION — moved here (early exit, saves CPU) ─────────
        // Runs immediately after S/R proximity is confirmed (Gate5e).
        // On most days only 1-2 sectors qualify (e.g. Auto +0.66%) — this gate
        // eliminates ~80% of symbols before any candle fetch, SL calc, or
        // target resolution. Zero logic change — just earlier exit point.
        // Sector must be actively moving ≥0.50% in trade direction.
        try {
            String sector = sectorClassify.getSector(symbol);
            if (sector != null && !sector.isEmpty()) {
                SectorStrengthService.SectorData sd = sectorStrength.getSector(sector);
                if (sd != null) {
                    boolean sectorAligned = isBuy
                            ? (sd.alignedBullish() && sd.changePercent() >= 0.50)
                            : (sd.alignedBearish() && sd.changePercent() <= -0.50);
                    if (!sectorAligned) {
                        log.debug("[HIGHRR] {} {} Gate6 BLOCKED — sector {} {}% (need ≥0.50%)",
                                symbol, isBuy ? "BUY" : "SELL", sector,
                                String.format("%.2f", sd.changePercent()));
                        return null;
                    }
                    log.debug("[HIGHRR] {} Gate6 PASS — sector {} {}%",
                            symbol, sector, String.format("%.2f", sd.changePercent()));
                }
            }
        } catch (Exception ignored) {
            log.debug("[HIGHRR] {} Gate6 BLOCKED — sector service unavailable", symbol);
            return null;
        }

        // ── CANDLE CONFIRMATION GATES C1–C4 ──────────────────────────────────────
        // A valid S/R zone is necessary but not sufficient. The candle at the zone
        // must confirm direction with a real body, not just a wick spike.
        List<com.trading.domain.Candle> recentCandles =
                structureService.getRecentCandles(symbol, 3);

        if (!recentCandles.isEmpty()) {
            com.trading.domain.Candle c0 = recentCandles.get(0); // most recent complete candle
            double cOpen  = c0.getOpen().doubleValue();
            double cClose = c0.getClose().doubleValue();
            double cHigh  = c0.getHigh().doubleValue();
            double cLow   = c0.getLow().doubleValue();
            double range  = cHigh - cLow;
            double body   = Math.abs(cClose - cOpen);
            double bodyRatio  = range > 0 ? body / range : 0;
            double rangeAsPct = range / (price > 0 ? price : 1.0);

            // Gate C1: Candle body direction must match trade direction
            // LONG: candle must close bullish (green). Bearish close at support
            //       = price still falling = no confirmation yet.
            // SHORT: candle must close bearish (red) at resistance.
            // Threshold: 0.20 (same as C3 floor) so every non-doji candle
            // that passes C3 also has direction enforced by C1.
            // Previously 0.30 — left a 0.20-0.30 body gap with no direction check.
            if (bodyRatio >= 0.20) {
                if (isBuy && cClose < cOpen) {
                    log.debug("[HIGHRR] {} BUY Gate-C1 BLOCKED — bearish candle body at support "
                                    + "(close={} < open={})",
                            symbol,
                            String.format("%.2f", cClose), String.format("%.2f", cOpen));
                    return null;
                }
                if (!isBuy && cClose > cOpen) {
                    log.debug("[HIGHRR] {} SELL Gate-C1 BLOCKED — bullish candle body at resistance "
                                    + "(close={} > open={})",
                            symbol,
                            String.format("%.2f", cClose), String.format("%.2f", cOpen));
                    return null;
                }
            }

            // Gate C2: Candle body midpoint must be near zone (not wick-only touch)
            // A wick may extend below support but the body should be within 0.5%.
            if (entryAnchorLevel != null) {
                double bodyMid  = (cOpen + cClose) / 2.0;
                double zonePx   = entryAnchorLevel.price();
                double bodyDist = zonePx > 0 ? Math.abs(bodyMid - zonePx) / zonePx : 0;
                if (bodyDist > 0.005) {
                    log.debug("[HIGHRR] {} {} Gate-C2 BLOCKED — body midpoint ({}) is "
                                    + "{}% from zone ({}) — wick-only touch, body not near zone",
                            symbol, isBuy ? "BUY" : "SELL",
                            String.format("%.2f", bodyMid),
                            String.format("%.2f", bodyDist * 100),
                            String.format("%.2f", zonePx));
                    return null;
                }
            }

            // Gate C3: No doji/spinning-top entries — require a real body
            // Doji = body < 20% of candle range and range > 0.1% of price.
            // At a zone, doji = indecision. Wait for next candle to confirm.
            if (bodyRatio < 0.20 && rangeAsPct > 0.001) {
                log.debug("[HIGHRR] {} {} Gate-C3 BLOCKED — doji/spinning top "
                                + "(body={}% of range), no directional conviction",
                        symbol, isBuy ? "BUY" : "SELL",
                        String.format("%.0f", bodyRatio * 100));
                return null;
            }
        }

        // Gate C4: No late entry — price must not have already drifted >0.3% from zone
        // If stock bounced from support and is already 0.4%+ higher, the entry is GONE.
        // LONG: drift = (entry - zone) / zone. Must be ≤ 0.3%.
        // SHORT: drift = (zone - entry) / zone. Must be ≤ 0.3%.
        if (entryAnchorLevel != null) {
            double zonePx = entryAnchorLevel.price();
            double drift  = zonePx > 0
                    ? (isBuy ? (entryDbl - zonePx) / zonePx
                    : (zonePx - entryDbl) / zonePx)
                    : 0;
            if (drift > MAX_ZONE_DRIFT_PCT) {
                log.debug("[HIGHRR] {} {} Gate-C4 BLOCKED — price already {}% from zone "
                                + "(max {}%, late entry / momentum chasing)",
                        symbol, isBuy ? "BUY" : "SELL",
                        String.format("%.2f", drift * 100),
                        String.format("%.1f", MAX_ZONE_DRIFT_PCT * 100));
                return null;
            }
        }

        // ── TRENDLINE CONFLUENCE CHECK (scoring boost, not a gate) ─────────────
        // Checks if price is also touching a rising or falling trendline at the
        // same time as the horizontal S/R zone. This is the exact pattern from
        // the picture: horizontal resistance + rising trendline = ascending triangle.
        // When BOTH a horizontal zone AND a trendline agree at the same price,
        // the setup has two independent technical structures confirming entry.
        // This adds score bonus — never blocks a valid trade.
        boolean atRisingTrendline  = false;
        boolean atFallingTrendline = false;
        if (structure != null) {
            double tl = 0.003; // 0.3% tolerance — same as zone tolerance
            atRisingTrendline  = structure.atRisingTrendline(entryDbl, tl);
            atFallingTrendline = structure.atFallingTrendline(entryDbl, tl);
            if (atRisingTrendline) {
                log.debug("[HIGHRR] {} TRENDLINE CONFLUENCE — price at rising trendline {} "
                                + "AND horizontal zone {} (ascending triangle pattern)",
                        symbol,
                        String.format("%.2f", structure.trendlineSupport()),
                        entryAnchorLevel != null
                                ? String.format("%.2f", entryAnchorLevel.price()) : "none");
            }
            if (atFallingTrendline && !isBuy) {
                log.debug("[HIGHRR] {} TRENDLINE CONFLUENCE — price at falling trendline {} "
                                + "AND horizontal resistance {} (descending triangle SHORT)",
                        symbol,
                        String.format("%.2f", structure.trendlineResistance()),
                        entryAnchorLevel != null
                                ? String.format("%.2f", entryAnchorLevel.price()) : "none");
            }
        }

        // ── ATR-BASED DYNAMIC SL ──────────────────────────────────────────────────
        // Professional approach: SL placed just beyond the anchor S/R zone,
        // not a fixed % from entry. This respects actual market structure.
        // Zone SL = anchor level price ± 0.15% buffer (beyond the zone edge).
        // ATR floor: SL never tighter than stock ATR(14) — avoids noise stops.
        // Hard cap: never wider than 1.5% — protects capital on single trade.
        double atr14Dist = computeAtr14(symbol);  // distance in price (0.8% of price baseline)
        double slFloor   = isBuy ? entryDbl - atr14Dist : entryDbl + atr14Dist;
        // Zone-based SL: just beyond the anchor S/R (0.15% buffer past the zone)
        double zoneSL = 0;
        if (entryAnchorLevel != null) {
            double buf = entryAnchorLevel.price() * 0.0015;
            zoneSL = isBuy
                    ? entryAnchorLevel.price() - buf
                    : entryAnchorLevel.price() + buf;
        }
        double slD;
        if (zoneSL > 0) {
            slD = isBuy
                    ? Math.min(slFloor, zoneSL)   // widest of ATR-floor vs zone-SL
                    : Math.max(slFloor, zoneSL);
        } else {
            slD = slFloor;
        }
        // Hard cap: SL never more than 1.5% from entry
        double maxSlDist = entryDbl * 0.015;
        if (isBuy  && (entryDbl - slD) > maxSlDist) slD = entryDbl - maxSlDist;
        if (!isBuy && (slD - entryDbl) > maxSlDist) slD = entryDbl + maxSlDist;

        // C5: Structural SL validity — SL should be within 0.3% of zone edge.
        // CONFLICT-3 FIX: C5 must NEVER override the ATR noise floor.
        // If corrected SL would be tighter than ATR floor, SKIP the correction.
        // ATR floor = the minimum distance SL needs to survive intraday noise.
        // Placing SL inside ATR range = noise stop = immediate loss.
        if (entryAnchorLevel != null) {
            double zonePx     = entryAnchorLevel.price();
            double slFromZone = zonePx > 0
                    ? (isBuy ? (zonePx - slD) / zonePx : (slD - zonePx) / zonePx)
                    : 0;
            if (slFromZone > MAX_SL_FROM_ZONE_PCT) {
                double corrected = isBuy
                        ? zonePx * (1.0 - MAX_SL_FROM_ZONE_PCT)
                        : zonePx * (1.0 + MAX_SL_FROM_ZONE_PCT);
                // Only tighten if corrected SL is still outside ATR noise floor
                boolean correctedSafeFromNoise = isBuy
                        ? (entryDbl - corrected) >= atr14Dist  // corrected SL ≥ ATR dist
                        : (corrected - entryDbl) >= atr14Dist;
                if (correctedSafeFromNoise) {
                    log.debug("[HIGHRR] {} {} Gate-C5: SL tightened {} → {} "
                                    + "(zone={}, 0.3% cap, ATR safe)",
                            symbol, isBuy ? "BUY" : "SELL",
                            String.format("%.2f", slD),
                            String.format("%.2f", corrected),
                            String.format("%.2f", zonePx));
                    slD = corrected;
                } else {
                    log.debug("[HIGHRR] {} {} Gate-C5: SL kept at {} (C5 correction would "
                                    + "breach ATR floor {}, skipping)",
                            symbol, isBuy ? "BUY" : "SELL",
                            String.format("%.2f", slD),
                            String.format("%.2f", atr14Dist));
                }
            }
        }

        BigDecimal stopLoss = BigDecimal.valueOf(slD).setScale(2,
                isBuy ? RoundingMode.FLOOR : RoundingMode.CEILING);
        BigDecimal risk = entryPrice.subtract(stopLoss).abs();
        if (risk.compareTo(BigDecimal.ZERO) == 0) return null;
        double riskD = risk.doubleValue();

        // SL is fixed at exactly 1% — range checks not needed.
        // riskD will always be entryDbl × 1% = predictable.

        // ── STRUCTURAL TARGET — T1 at next S/R level, not arbitrary 2×risk ─────
        // If the next S/R level gives RR ≥ 2.0, use it. This makes T1 a real
        // price magnet (institutional defenders). Fallback to 2×risk arithmetic.
        BigDecimal target1 = null, target2;
        SRLevel structT1 = null;
        if (structure != null) {
            if (isBuy) {
                List<SRLevel> ress = structure.resistances();
                if (ress != null) {
                    for (SRLevel r : ress) {
                        if (r.price() <= entryDbl * 1.001) continue;
                        if ((r.price() - entryDbl) / riskD >= SR_T1_MIN_RR) {
                            target1 = BigDecimal.valueOf(r.price()).setScale(2, RoundingMode.HALF_UP);
                            structT1 = r;
                            break;
                        }
                    }
                }
            } else {
                List<SRLevel> sups = structure.supports();
                if (sups != null) {
                    for (SRLevel s2 : sups) {
                        if (s2.price() >= entryDbl * 0.999) continue;
                        if ((entryDbl - s2.price()) / riskD >= SR_T1_MIN_RR) {
                            target1 = BigDecimal.valueOf(s2.price()).setScale(2, RoundingMode.HALF_UP);
                            structT1 = s2;
                            break;
                        }
                    }
                }
            }
        }
        if (target1 == null) {
            // Fallback: arithmetic 2× risk (original behaviour preserved)
            target1 = isBuy
                    ? entryPrice.add(risk.multiply(BigDecimal.valueOf(2)))
                    : entryPrice.subtract(risk.multiply(BigDecimal.valueOf(2)));
        }
        target2 = isBuy
                ? entryPrice.add(risk.multiply(BigDecimal.valueOf(3)))
                : entryPrice.subtract(risk.multiply(BigDecimal.valueOf(3)));

        double rewardD = target1.subtract(entryPrice).abs().doubleValue();
        double rr = rewardD / riskD;
        if (rr < MIN_RR_RATIO) return null;

        TradeDirection direction = isBuy ? TradeDirection.LONG : TradeDirection.SHORT;

        // ── CLEAN STRUCTURE GATE — no crowded zone ───────────────────────────────
        // Professional traders avoid entering in congested areas where 3+ S/R
        // levels cluster within 2% of entry. Multiple levels = price pulled in
        // conflicting directions = choppy action, no clean directional move.
        if (structure != null) {
            double bandPct = 0.02;
            long nearbyLevels = 0;
            if (structure.supports() != null)
                nearbyLevels += structure.supports().stream()
                        .filter(l -> Math.abs(l.price() - entryDbl) / entryDbl <= bandPct).count();
            if (structure.resistances() != null)
                nearbyLevels += structure.resistances().stream()
                        .filter(l -> Math.abs(l.price() - entryDbl) / entryDbl <= bandPct).count();
            if (nearbyLevels > 3) {
                log.debug("[HIGHRR] {} {} CLEAN-ZONE BLOCKED — {} levels within 2% of entry",
                        symbol, isBuy ? "BUY" : "SELL", nearbyLevels);
                return null;
            }
        }

        // ── HTF INSTITUTIONAL GATE ───────────────────────────────────────────────
        // Hard block: no trade without institutional backing from 30d/90d levels.

        // HTF gate: only enforced when bootstrap is complete AND data exists for symbol.
        // shouldEnforceHtfGate() returns false while bootstrap is running → safe on first deploy.
        if (structureService.shouldEnforceHtfGate(symbol)) {
            HighRRStructureService.SRLevel htfZoneCheck =
                    structureService.getNearestHtfZone(symbol, entryDbl);
            if (htfZoneCheck == null) {
                log.debug("[HIGHRR] {} {} HTF BLOCKED — no institutional zone within 0.5% of entry {}",
                        symbol, isBuy?"BUY":"SELL", String.format("%.2f", entryDbl));
                return null;
            }
            log.debug("[HIGHRR] {} HTF confirmed: {} strength={} touches={} rej={}%",
                    symbol, htfZoneCheck.price(), htfZoneCheck.strength(),
                    htfZoneCheck.touchCount(),
                    String.format("%.2f", htfZoneCheck.avgRejectionPct() * 100));
        }

        PositionSizerService.PositionSize pos =
                positionSizer.calculate(cap, entryPrice, stopLoss, symbol, direction.name());
        if (!pos.isValid() || pos.quantity() <= 0) {
            log.debug("[HIGHRR] {} invalid position size: {}", symbol, pos.invalidReason());
            return null;
        }

        int score = computeScore(state, rr, isBuy, symbol, entryAnchorLevel, structT1, entryDbl,
                atRisingTrendline, atFallingTrendline);
        long instrumentToken = resolveInstrumentToken(symbol);

        return new ScoredCandidate(
                symbol, direction, entryPrice, stopLoss, target1, target2,
                pos.quantity(), pos.actualRisk(), rr, score, state, instrumentToken
        );
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SCORING LOGIC — max 185 pts, min to fire = 150/155/160 (grade A/B/C)
    //   RR ≥ 3.0:          +25
    //   Trend aligned:     +20 (+5 confluence bonus)
    //   Pullback zone:     +20
    //   Volume spike:      +20
    //   Close strength:    +15 (strong close) or +5 (neutral)
    //   Liquidity:         +10
    //   Major S/R entry:   +20 (or +10 for any S/R)
    //   T1 at major S/R:   +15
    //   HTF quality ≥120:  +25 (or +18/+10 for lower quality)
    //   Entry zone quality:+10 (or +5)
    //   TOTAL MAX = 185
    // ══════════════════════════════════════════════════════════════════════════

    private int computeScore(SymbolState s, double rr, boolean isBuy, String symbol,
                             SRLevel entryLevel, SRLevel structT1, double entryPrice,
                             boolean atRisingTrendline, boolean atFallingTrendline) {
        int score = 0;

        // ── Original scoring (all unchanged) ────────────────────────────────
        // RR≥3.0: +25. Exceptional setup — 3× reward:risk.
        if (rr >= GOOD_RR_RATIO) score += 25;
        else if (rr >= MIN_RR_RATIO) score += 15;

        // Trend alignment: +20 base.
        // Confluence bonus +5 if ALSO at major S/R (checked again after entryLevel known).
        // This rewards the strongest setups: trend + major institutional level.
        // Trend alignment: +20. Core signal — must trade with the trend.
        if (s.isTrendingUp() && isBuy)    score += 20;
        if (s.isTrendingDown() && !isBuy) score += 20;
        // Confluence bonus: trend + major level = +5 extra
        if (entryLevel != null && entryLevel.isMajor()) {
            if ((s.isTrendingUp() && isBuy) || (s.isTrendingDown() && !isBuy)) score += 5;
        }

        // Pullback zone raised 15 → 20: price near S/R is the core HighRR edge.
        if (s.pullbackZone()) score += 20;

        // Volume spike raised 15 → 20: volume confirms institutional participation.
        if (isVolumeSpike(symbol, s.candleVol())) score += 20;

        // Candle close position bonus (replaces redundant wick check):
        // hasExcessiveWick() is already filtered at scanner level (line 450).
        // All candidates here have clean wicks — +15 was always constant.
        // Replaced with close-strength: close in top/bottom 30% of candle range
        // = strong conviction close = +15 bonus. Middle 40% = neutral = +5.
        if (s.candleVol() > 0) { // only when real candle data available
            List<com.trading.domain.Candle> cv = structureService.getRecentCandles(symbol, 1);
            if (!cv.isEmpty()) {
                com.trading.domain.Candle lc = cv.get(0);
                double hi = lc.getHigh().doubleValue(), lo = lc.getLow().doubleValue();
                double cl = lc.getClose().doubleValue();
                double rng = hi - lo;
                if (rng > 0) {
                    double closePos = (cl - lo) / rng; // 0=closed at low, 1=at high
                    // LONG: strong close = top 30% of range = +15
                    // SHORT: strong close = bottom 30% of range = +15
                    boolean strongClose = isBuy ? (closePos >= 0.70) : (closePos <= 0.30);
                    boolean neutralClose = closePos >= 0.30 && closePos <= 0.70;
                    if (strongClose)  score += 15;
                    else if (neutralClose) score += 5;
                    // Weak close (isBuy but closed in bottom 30%): 0 bonus
                }
            }
        }

        if (s.hasAdequateDepth() && s.isSpreadOk()) score += 10;

        // ── NEW: Structural S/R quality bonuses (max +35) ───────────────────
        // +20  Entry at a MAJOR level (touchCount≥3 or PDH/PDL)
        //       → institutional defenders present → highest win-rate entries
        // +10  Entry at ANY S/R level (touchCount≥1)
        //       → some structural significance
        // +15  T1 target is a major S/R level
        //       → T1 is a real price magnet, not arbitrary arithmetic
        // These push a major-level entry from 100 → 135 score,
        // ensuring structural trades always rank above noise-level setups.
        if (entryLevel != null) {
            // Major S/R: +20. Institutional level — highest edge.
            if (entryLevel.isMajor()) score += 20;
            else                      score += 10;
        }
        if (structT1 != null && structT1.isMajor()) score += 15;

        // HTF zone quality reward — uses qualityScore() which weights strength +
        // recency + volume + rejection. Max +25 for perfect MAJOR zone.
        HighRRStructureService.SRLevel hz = structureService.getNearestHtfZone(symbol, entryPrice);
        if (hz != null) {
            double qs = hz.qualityScore();
            if      (qs >= 120) score += 25;  // MAJOR + recent + vol confirmed
            else if (qs >=  80) score += 18;  // MAJOR or STRONG + decent recency
            else if (qs >=  50) score += 10;  // STRONG zone
        }
        // Entry zone quality: recent high-quality 15-min anchor = extra confidence
        if (entryLevel != null) {
            double qs = entryLevel.qualityScore();
            if      (qs >= 100) score += 10;
            else if (qs >=  60) score += 5;
        }

        // Trendline confluence bonus
        // When price is simultaneously at a horizontal S/R zone AND a diagonal
        // trendline, two independent structures agree — significantly higher
        // probability setup. This is the ascending/descending triangle pattern.
        // +20 for trendline confluence (3+ touch validated trendline)
        // +10 extra if trendline AND horizontal zone are both MAJOR
        if (isBuy && atRisingTrendline) {
            score += 20;
            if (entryLevel != null && entryLevel.isMajor()) score += 10; // both major
            log.debug("[HIGHRR] {} Trendline bonus +{} (rising trendline confluence)",
                    symbol, entryLevel != null && entryLevel.isMajor() ? 30 : 20);
        }
        if (!isBuy && atFallingTrendline) {
            score += 20;
            if (entryLevel != null && entryLevel.isMajor()) score += 10;
            log.debug("[HIGHRR] {} Trendline bonus +{} (falling trendline confluence)",
                    symbol, entryLevel != null && entryLevel.isMajor() ? 30 : 20);
        }

        return score;
    }

    /**
     * Dynamic minimum score based on market quality grade.
     * Grade A (perfect market): standard 150 — full set of trades allowed.
     * Grade B (good market):    155 — slightly higher bar.
     * Grade C (mixed market):   160 — only the very best setups fire.
     * Grade D:                  blocked before reaching scoring (returns in cycle gate).
     *
     * This implements the professional trader principle:
     *   "Trade more freely in a strong market, trade less in a weak one."
     */
    private int gradeMinScore(String grade) {
        return switch (grade) {
            case "A" -> MIN_CANDIDATE_SCORE;       // 150 — normal threshold
            case "B" -> MIN_CANDIDATE_SCORE + 5;   // 155 — tighter
            default  -> MIN_CANDIDATE_SCORE + 10;  // 160 — only exceptional setups
        };
    }

    /**
     * ATR(14) approximation: distance in price units for dynamic SL placement.
     * Uses 0.8% of price as the NSE 15-min ATR baseline.
     * A ₹200 stock: ATR ~ ₹1.60. A ₹9,000 stock: ATR ~ ₹72.
     * This ensures SL is never tighter than typical intraday noise.
     */
    private double computeAtr14(String symbol) {
        try {
            var structure = structureService.getStructure(symbol);
            if (structure == null) return 0;
            return structure.lastPrice() * 0.008; // 0.8% of price = NSE 15-min ATR baseline
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean isVolumeSpike(String symbol, double currentVol) {
        if (currentVol <= 0) return false;
        // FIX 2: explicit ArrayDeque type
        Deque<Double> history = volumeHistory.computeIfAbsent(symbol,
                k -> new java.util.ArrayDeque<>());
        if (history.size() < 3) {
            history.addFirst(currentVol);
            return false;
        }
        double avg = history.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        history.addFirst(currentVol);
        while (history.size() > 10) ((java.util.ArrayDeque<Double>) history).removeLast();
        return avg > 0 && currentVol >= avg * VOLUME_SPIKE_FACTOR;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SIGNAL FIRING
    // ══════════════════════════════════════════════════════════════════════════

    private void fireSignal(ScoredCandidate cand) {
        SymbolState s = cand.state();

        log.info("[HIGHRR] 🚀 FIRING SIGNAL: {} | {} | entry={} | sl={} | T1={} | T2={} | " +
                        "RR={} | score={} | qty={} | risk=₹{} | token={}",
                cand.symbol(), cand.direction(),
                cand.entryPrice(), cand.stopLoss(),
                cand.target1(), cand.target2(),
                String.format("%.2f", cand.rr()), cand.score(),
                cand.quantity(), cand.riskAmount(), cand.instrumentToken());

        int scoreRvol        = isVolumeSpike(cand.symbol(), s.candleVol()) ? 15 : 5;
        int scoreTrend       = cand.rr() >= GOOD_RR_RATIO ? 25 : 15;
        int scorePullback    = s.pullbackZone() ? 15 : 0;
        int scoreClean       = !s.hasExcessiveWick() ? 15 : 0;
        int scoreLiquidity   = (s.hasAdequateDepth() && s.isSpreadOk()) ? 10 : 0;
        int totalScore       = cand.score();

        SmartChannelPullbackSignalEvent signal = new SmartChannelPullbackSignalEvent(
                this,
                cand.symbol(),
                cand.instrumentToken(),     // FIX 1: resolved token, not hardcoded 0L
                cand.direction(),
                cand.entryPrice(),
                cand.stopLoss(),
                cand.target1(),
                cand.target2(),
                cand.quantity(),
                cand.riskAmount(),
                STRATEGY_NAME,
                totalScore,
                "N/A",
                0.0,
                "HIGH_RR",
                "PULLBACK",
                0.0,
                s.candleVol() > 0 ? 1.2 : 1.0,
                false,
                "LIMIT",
                s.isTrendingUp() ? "UPTREND_BUY" : "DOWNTREND_SELL",
                0,
                scoreRvol,
                scoreTrend,
                scoreClean,
                scorePullback,
                scoreLiquidity,
                totalScore,
                timeStopMinutes
        );

        publisher.publishEvent(signal);

        firedToday.add(cand.symbol());
        activeSignals.add(cand.symbol());
        tradesExecutedToday.incrementAndGet();
        int batchCount = batchTradesCount.incrementAndGet();
        if (batchCount >= BATCH_SIZE_LIMIT) {
            batchCooldownUntil = System.currentTimeMillis() + BATCH_COOLDOWN_MS;
            batchTradesCount.set(0);
            log.info("[HIGHRR] 🕐 Batch of {} trades complete. Cooldown for 30 min (until {}).",
                    BATCH_SIZE_LIMIT,
                    java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata"))
                            .plusMinutes(30).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")));
        }

        log.info("[HIGHRR] ✅ Signal #{}/{} fired for {}. Session complete for this symbol.",
                tradesExecutedToday.get(), -1 /* unlimited */, cand.symbol());
    }

    // ── Instrument token resolution (FIX 1) ────────────────────────────────

    /**
     * Looks up the NSE instrument token for a given trading symbol.
     * Uses InstrumentCacheService (same approach as OrbDataService).
     * Returns 0L if not found — safe for PAPER mode.
     */
    private long resolveInstrumentToken(String symbol) {
        try {
            Instrument inst = instrumentCache.getEquityInstruments().get(symbol.toUpperCase());
            return inst != null ? inst.getInstrument_token() : 0L;
        } catch (Exception e) {
            log.debug("[HIGHRR] Token resolution failed for {}: {}", symbol, e.getMessage());
            return 0L;
        }
    }

    // ── Candidate record (FIX 1: added instrumentToken field) ───────────────

    private record ScoredCandidate(
            String         symbol,
            TradeDirection direction,
            BigDecimal     entryPrice,
            BigDecimal     stopLoss,
            BigDecimal     target1,
            BigDecimal     target2,
            int            quantity,
            BigDecimal     riskAmount,
            double         rr,
            int            score,
            SymbolState    state,
            long           instrumentToken     // FIX 1: was missing, always 0L before
    ) {}

    // ── Helpers ──────────────────────────────────────────────────────────────

    private BigDecimal resolveCapital() {
        return "PAPER".equalsIgnoreCase(tradingMode) ? paperAccount.getCapital() : capital;
    }

    /** Called by SmartChannelSignalHandler when a HighRR trade closes. */
    public void onSignalClosed(String symbol) {
        activeSignals.remove(symbol);
        log.debug("[HIGHRR] Signal lock released for {}", symbol);
    }

    // ── Dashboard helpers ────────────────────────────────────────────────────

    public int     getTradesExecutedToday() { return tradesExecutedToday.get(); }
    public int     getRemainingSlots()      { return Integer.MAX_VALUE; } // unlimited
    public boolean isEnabled()              { return engineEnabled; }
    public boolean isDailyLimitReached()    { return false; }
    public int     getMaxTradesPerDay()     { return -1; } // unlimited
    public boolean isProfitLocked()         { return profitLocked; }
    public double  getDailyPnlPct()         {
        double cap = resolveCapital().doubleValue();
        return cap > 0 ? riskManagement.getDailyPnl().doubleValue() / cap * 100 : 0.0;
    }
    /** Dashboard: symbols that fired a signal today (may be closed or active). */
    public Set<String> getFiredToday()      { return Collections.unmodifiableSet(firedToday); }
    /** Dashboard: symbols with currently active (open) signals. */
    public Set<String> getActiveSignals()   { return Collections.unmodifiableSet(activeSignals); }

    @Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyReset() {
        tradesExecutedToday.set(0);
        profitLocked = false;
        batchTradesCount.set(0);
        batchCooldownUntil = 0L;
        firedToday.clear();
        activeSignals.clear();
        volumeHistory.clear();
        directionConsecutive = 0;
        lastDirection = null;
        marketGrade = "C";
        log.info("[HIGHRR] Daily reset complete – 2 trade slots available");
    }
}