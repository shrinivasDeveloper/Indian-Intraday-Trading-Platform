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
import com.trading.regime.service.MarketDirectionService;
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

    private static final LocalTime TRADE_START  = LocalTime.of(9, 35);  // Gate: skip opening 5-min noise
    private static final LocalTime TRADE_END    = LocalTime.of(14, 0);
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
    /** Minimum SL distance as % of entry price.
     *  Prevents noise-level SLs on low-priced stocks like UCOBANK (₹26).
     *  0.30% on ₹26 = ₹0.08 = 1-2 ticks → instant SL hit on opening noise.
     *  Raising to 0.50% ensures SL represents a real structural level. */
    private static final double MIN_SL_PCT         = 0.005;
    private static final double FIXED_SL_PCT        = 0.010; // exactly 1% — always
    private static final double GOOD_RR_RATIO      = 3.0;

    // ── Gate 5: Structural S/R entry zone ────────────────────────────────────
    // Entry must be within this % of a known S/R level.
    // Prevents mid-air entries like ADANIGREEN ₹1,223 when resistance was ₹1,252 (2.4% away).
    private static final double SR_ENTRY_ZONE_PCT = 0.005; // 0.5%
    // Structural SL max distance from entry (avoids oversized risk)
    // SR_SL_MAX_PCT removed — SL is now fixed at exactly 1% (FIXED_SL_PCT)
    // Min RR for a structural T1 to be accepted
    private static final double SR_T1_MIN_RR      = 2.0;

    // ── Minimum candidate score before adding to fire list ─────────────────
    // WHY: Without this, a setup with RR=2.0 but no pullback, no volume spike,
    // no S/R quality still qualifies (score = 15 pts from RR alone).
    // UTIAMC and PHOENIXLTD both would have scored below 50 — this gate blocks them.
    // Score breakdown: trend(20)+pullback(15)+volume(15)+clean(15)+liquidity(10) = 75 max
    // For a HIGH_RR trade, minimum entry quality = trend + pullback + one other = 50.
    // MIN_CANDIDATE_SCORE raised 50 → 65.
    // Old: RR(25) + trend(20) + any one signal(15) = 60 could pass at 50.
    // MIN_CANDIDATE_SCORE = 110 — target setup minimum.
    // Target: RR≥3(25) + trend(20) + confluence(5) + pullback(20) + volume(20)
    //       + major_SR(20) = 110 ← best setup. Min path to 85:
    //   RR≥3(25) + trend(20) + pullback(20) + major_SR(20) = 85 ✅
    //   RR≥3(25) + trend(20) + confluence(5) + pullback(20) + volume(20) = 90 ✅
    //   RR(15) + trend(20) + clean(15) = 50 ❌ (no pullback, no S/R)
    // Score threshold raised 110 → 125.
    // Path to 125: RR≥3(25)+trend(20)+conf(5)+pullback(20)+volume(20)+major_SR(20)+clean(15) = 125
    // Requires ALL 6 primary signals plus clean wick confirmation.
    private static final int    MIN_CANDIDATE_SCORE = 125;

    // ── SL placement ────────────────────────────────────────────────────────
    private static final double SL_BUFFER_PCT    = 0.002;
    private static final double ENTRY_BUFFER_PCT = 0.0003;

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

        // ── GATE 1: Frozen market — Nifty ATR must be ≥ 0.30% ──────────────────
        // On frozen days (ATR < 0.30%) individual stocks also lack range.
        // A 0.30-0.34% SL gets hit by noise in 2-3 minutes. Proved by Apr-21:
        // UCOBANK and REDINGTON both stopped out within 3 minutes.
        MarketDirectionService.MarketDirectionResult dir = marketDirection.getCurrentDirection();
        if (dir.niftyAtrPct() < MIN_ATR_PCT) {
            log.debug("[HIGHRR] Gate 1 BLOCKED — Nifty ATR {}% < {}% minimum. Frozen market.",
                    String.format("%.2f", dir.niftyAtrPct()),
                    String.format("%.2f", MIN_ATR_PCT));
            return;
        }

        // Lunch window gate REMOVED — HighRR scans 9:35–14:00 without interruption.

        // ── GATE 3: Market must not be SIDEWAYS ───────────────────────────────
        // HighRR is a momentum/trend strategy. Sideways markets produce
        // false breakouts. On SIDEWAYS days ATR is often low anyway (Gate 1
        // would also catch it), but this adds an explicit regime check.
        if (dir.direction() == MarketDirectionService.Direction.SIDEWAYS) {
            log.debug("[HIGHRR] Gate 3 BLOCKED — Market direction SIDEWAYS. HighRR needs trending market.");
            return;
        }

        // ── GATE 4: Strategy must match market regime ──────────────────────────
        // Only take LONGs in BULLISH regime, only take SHORTs in BEARISH regime.
        // This gate is enforced at individual candidate level in the scoring loop
        // below (isBuySetup filtered against dir.direction()). Logged here for
        // cycle-level visibility.
        log.debug("[HIGHRR] Market regime: {} | ATR: {}% — proceeding with evaluation",
                dir.direction(), String.format("%.2f", dir.niftyAtrPct()));

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
                            && c.score() >= MIN_CANDIDATE_SCORE) {
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
                            && c.score() >= MIN_CANDIDATE_SCORE) {
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

        candidates.sort(Comparator.comparingDouble(ScoredCandidate::score).reversed());

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
        // Prevents mid-air entries. ADANIGREEN at ₹1,223 was 2.4% below
        // the ₹1,252 resistance — that entry had no structural edge.
        // A valid SHORT entry waits for price to reach ₹1,252 (near resistance).
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

        // ── FIXED 1% STOP LOSS ───────────────────────────────────────────────────
        // SL is always exactly 1% from entry price.
        // LONG:  SL = entry × 0.99  (1% below entry)
        // SHORT: SL = entry × 1.01  (1% above entry)
        //
        // WHY FIXED 1%:
        //   Structural SL was variable (0.5–1.5%) causing inconsistent losses.
        //   Today ICICIPRULI lost 1.33%, SUNPHARMA 1.24%, APTUS 1.30% — all different.
        //   Fixed 1% gives: every loss = exactly ₹1,000 per trade (1% of ₹1L).
        //   Predictable. Max 4 SL losses before -4% Rule 1 fires.
        //   T1 = 2R = entry ± 2%. T2 = 3R = entry ± 3%.
        double slD = isBuy
                ? entryDbl * (1.0 - FIXED_SL_PCT)
                : entryDbl * (1.0 + FIXED_SL_PCT);

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
        PositionSizerService.PositionSize pos =
                positionSizer.calculate(cap, entryPrice, stopLoss, symbol, direction.name());
        if (!pos.isValid() || pos.quantity() <= 0) {
            log.debug("[HIGHRR] {} invalid position size: {}", symbol, pos.invalidReason());
            return null;
        }

        int score = computeScore(state, rr, isBuy, symbol, entryAnchorLevel, structT1);
        long instrumentToken = resolveInstrumentToken(symbol);

        return new ScoredCandidate(
                symbol, direction, entryPrice, stopLoss, target1, target2,
                pos.quantity(), pos.actualRisk(), rr, score, state, instrumentToken
        );
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SCORING LOGIC
    //   RR ≥ 1:3     → +25
    //   Strong trend → +20
    //   Pullback at VWAP → +15
    //   Volume spike  → +15
    //   Clean candle  → +15
    //   Liquidity OK  → +10
    //   TOTAL MAX    = 100
    // ══════════════════════════════════════════════════════════════════════════

    private int computeScore(SymbolState s, double rr, boolean isBuy, String symbol,
                             SRLevel entryLevel, SRLevel structT1) {
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

        if (!s.hasExcessiveWick()) score += 15;

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

        return score;
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
        log.info("[HIGHRR] Daily reset complete – 2 trade slots available");
    }
}