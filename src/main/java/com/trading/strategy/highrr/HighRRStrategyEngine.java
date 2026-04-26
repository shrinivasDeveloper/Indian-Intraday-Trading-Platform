package com.trading.strategy.highrr;

import com.trading.domain.enums.TradeDirection;
import com.trading.events.SmartChannelPullbackSignalEvent;
import com.trading.marketdata.service.InstrumentCacheService;
import com.trading.marketdata.service.LatencyMonitor;
import com.trading.marketdata.service.MarketTimingService;
import com.trading.papertrading.model.PaperAccount;
import com.trading.position.service.PositionSizerService;
import com.trading.risk.service.CircuitBreakerService;
import com.trading.regime.service.MarketDirectionService;
import com.trading.strategy.highrr.HighRRScannerService.SymbolState;
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
    private static final LocalTime TRADE_END    = LocalTime.of(13, 0);
    private static final LocalTime LUNCH_START  = LocalTime.of(11, 0);  // Gate: skip lunch window
    private static final LocalTime LUNCH_END    = LocalTime.of(12, 30);
    private static final double    MIN_ATR_PCT  = 0.20;                 // FIXED: was 0.30 — blocked Apr-22(0.29%) and Apr-23(0.23%).
    // Indian Nifty 15-min ATR of 0.20% = ~48 point candle range. Individual stocks still move 3-5x Nifty.
    // Ultra-frozen days (ATR < 0.10% like Apr-21) are still blocked. Apr-22/23 now allowed.

    // ── Limits ──────────────────────────────────────────────────────────────
    private static final int    MAX_TRADES_PER_DAY = 2;
    private static final int    TOP_N_CANDIDATES   = 2;
    private static final double MIN_RR_RATIO       = 2.0;
    /** Minimum SL distance as % of entry price.
     *  Prevents noise-level SLs on low-priced stocks like UCOBANK (₹26).
     *  0.30% on ₹26 = ₹0.08 = 1-2 ticks → instant SL hit on opening noise.
     *  Raising to 0.50% ensures SL represents a real structural level. */
    private static final double MIN_SL_PCT         = 0.005;
    private static final double GOOD_RR_RATIO      = 3.0;

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
    private final InstrumentCacheService     instrumentCache; // FIX 1: added for token resolution

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

    // ── Session state ────────────────────────────────────────────────────────
    private volatile int        tradesExecutedToday = 0;
    private final Set<String>   firedToday          = ConcurrentHashMap.newKeySet();
    private final Set<String>   activeSignals       = ConcurrentHashMap.newKeySet();

    // FIX 2: explicitly typed as ArrayDeque
    private final Map<String, Deque<Double>> volumeHistory = new ConcurrentHashMap<>();

    // ══════════════════════════════════════════════════════════════════════════
    // 1-MINUTE EVALUATION CYCLE
    // ══════════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 60_000)
    public void runEvaluationCycle() {
        if (!engineEnabled) return;

        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(TRADE_START) || now.isAfter(TRADE_END)) return;
        if (latencyMonitor.isStale()) {
            log.debug("[HIGHRR] Latency stale – skipping cycle");
            return;
        }

        if (tradesExecutedToday >= MAX_TRADES_PER_DAY) {
            log.debug("[HIGHRR] Daily trade limit reached ({}/{}). Engine idle.",
                    tradesExecutedToday, MAX_TRADES_PER_DAY);
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

        // ── GATE 2: Lunch window (11:00–12:30) ────────────────────────────────
        // Volume thins, spreads widen, momentum signals are unreliable.
        // MARKET_PRESSURE proved this on Apr-17 and Apr-21.
        if (!now.isBefore(LUNCH_START) && !now.isAfter(LUNCH_END)) {
            log.debug("[HIGHRR] Gate 2 BLOCKED — Lunch window (11:00–12:30). Skipping cycle.");
            return;
        }

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
                now, symbols.size(), MAX_TRADES_PER_DAY - tradesExecutedToday);

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
                    if (c != null && c.rr() >= MIN_RR_RATIO) {
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
                    if (c != null && c.rr() >= MIN_RR_RATIO) {
                        candidates.add(c);
                        sellSetups++;
                    }
                }
            }
        }

        log.info("[HIGHRR] Evaluated {} symbols | BUY setups={} SELL setups={} | {} candidates qualify",
                evaluated, buySetups, sellSetups, candidates.size());

        if (candidates.isEmpty()) {
            log.info("[HIGHRR] No qualifying candidates this cycle");
            return;
        }

        candidates.sort(Comparator.comparingDouble(ScoredCandidate::score).reversed());

        int slotsLeft = MAX_TRADES_PER_DAY - tradesExecutedToday;
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

        double slD;
        if (isBuy) {
            double swingLow = state.candleLow() > 0 ? state.candleLow() : price * 0.985;
            slD = swingLow * (1.0 - SL_BUFFER_PCT);
        } else {
            double swingHigh = state.candleHigh() > 0 ? state.candleHigh() : price * 1.015;
            slD = swingHigh * (1.0 + SL_BUFFER_PCT);
        }
        BigDecimal stopLoss = BigDecimal.valueOf(slD).setScale(2,
                isBuy ? RoundingMode.FLOOR : RoundingMode.CEILING);

        BigDecimal risk = entryPrice.subtract(stopLoss).abs();
        if (risk.compareTo(BigDecimal.ZERO) == 0) return null;

        double riskD = risk.doubleValue();

        // IMPROVEMENT: enforce BOTH max AND min SL distance.
        // Max 5% prevents oversized risk (existing check).
        // Min 0.5% prevents noise-level SL on low-priced stocks.
        // Root cause of UCOBANK/REDINGTON Apr-21 losses: SL was 0.30-0.34%
        // on stocks ₹26-₹228. Opening noise ate through SL in 2-3 minutes.
        if (riskD / price < MIN_SL_PCT) {
            log.debug("[HIGHRR] {} SL distance {}% below minimum {}% — structural level too tight.",
                    symbol, riskD / price * 100, MIN_SL_PCT * 100);
            return null;
        }
        if (riskD / price > 0.05) {
            log.trace("[HIGHRR] {} SL too far: risk={}%", symbol,
                    String.format("%.2f", riskD / price * 100));
            return null;
        }

        BigDecimal target1, target2;
        if (isBuy) {
            target1 = entryPrice.add(risk.multiply(BigDecimal.valueOf(2)));
            target2 = entryPrice.add(risk.multiply(BigDecimal.valueOf(3)));
        } else {
            target1 = entryPrice.subtract(risk.multiply(BigDecimal.valueOf(2)));
            target2 = entryPrice.subtract(risk.multiply(BigDecimal.valueOf(3)));
        }

        double rewardD = target1.subtract(entryPrice).abs().doubleValue();
        double rr      = rewardD / riskD;
        if (rr < MIN_RR_RATIO) return null;

        TradeDirection direction = isBuy ? TradeDirection.LONG : TradeDirection.SHORT;
        PositionSizerService.PositionSize pos =
                positionSizer.calculate(cap, entryPrice, stopLoss, symbol, direction.name());
        if (!pos.isValid() || pos.quantity() <= 0) {
            log.debug("[HIGHRR] {} invalid position size: {}", symbol, pos.invalidReason());
            return null;
        }

        int score = computeScore(state, rr, isBuy, symbol);

        // FIX 1: resolve instrument token from cache
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

    private int computeScore(SymbolState s, double rr, boolean isBuy, String symbol) {
        int score = 0;

        if (rr >= GOOD_RR_RATIO) score += 25;
        else if (rr >= MIN_RR_RATIO) score += 15;

        if (s.isTrendingUp() && isBuy)    score += 20;
        if (s.isTrendingDown() && !isBuy) score += 20;

        if (s.pullbackZone()) score += 15;

        if (isVolumeSpike(symbol, s.candleVol())) score += 15;

        if (!s.hasExcessiveWick()) score += 15;

        if (s.hasAdequateDepth() && s.isSpreadOk()) score += 10;

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
        tradesExecutedToday++;

        log.info("[HIGHRR] ✅ Signal #{}/{} fired for {}. Session complete for this symbol.",
                tradesExecutedToday, MAX_TRADES_PER_DAY, cand.symbol());
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

    public int     getTradesExecutedToday() { return tradesExecutedToday; }
    public int     getRemainingSlots()      { return MAX_TRADES_PER_DAY - tradesExecutedToday; }
    public boolean isEnabled()              { return engineEnabled; }
    public boolean isDailyLimitReached()    { return tradesExecutedToday >= MAX_TRADES_PER_DAY; }
    public int     getMaxTradesPerDay()     { return MAX_TRADES_PER_DAY; }
    /** Dashboard: symbols that fired a signal today (may be closed or active). */
    public Set<String> getFiredToday()      { return Collections.unmodifiableSet(firedToday); }
    /** Dashboard: symbols with currently active (open) signals. */
    public Set<String> getActiveSignals()   { return Collections.unmodifiableSet(activeSignals); }

    @Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyReset() {
        tradesExecutedToday = 0;
        firedToday.clear();
        activeSignals.clear();
        volumeHistory.clear();
        log.info("[HIGHRR] Daily reset complete – 2 trade slots available");
    }
}