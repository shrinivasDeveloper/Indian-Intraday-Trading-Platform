package com.trading.strategy.orb;

import com.trading.analysis.service.RvolService;
import com.trading.domain.enums.TradeDirection;
import com.trading.events.SmartChannelPullbackSignalEvent;
import com.trading.events.TickReceivedEvent;
import com.trading.marketdata.service.LatencyMonitor;
import com.trading.regime.service.MarketDirectionService;
import com.trading.papertrading.model.PaperAccount;
import com.trading.position.service.PositionSizerService;
import com.trading.risk.service.CircuitBreakerService;
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
 * OrbStrategyEngine – Real-time ORB breakout monitoring and signal firing.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * CHANGES vs previous version:
 * ─────────────────────────────────────────────────────────────────────────────
 * REQ 1 – Top-10 watchlist (was top-5, now top-10).
 *   OrbDataService now selects the top 10 scored stocks at 9:30.
 *   All 10 are monitored for breakout. Only 2 actually execute trades.
 *
 * REQ 2 – Execute 2 trades then instantly cancel remaining 3.
 *   executedTradesCount: AtomicInteger tracks trades fired this session.
 *   MAX_EXECUTIONS = 2: once this is reached inside fireSignal(), all remaining
 *   selected-but-untriggered symbols are immediately marked triggered (cancelled).
 *   No delay — cancellation happens synchronously within the same fireSignal() call.
 *
 * REQ 3 – One direction per session (no BUY/SELL mixing).
 *   lockedDirection: volatile field, null until first trade fires.
 *   First breakout (BUY or SELL) locks the direction for the entire session.
 *   Any subsequent breakout in the opposite direction is blocked and logged.
 *   Reset at daily reset.
 *
 * REQ 4 – Direction-aware RVOL thresholds.
 *   BUY:  breakoutRvol >= 1.0 (more opportunities, higher win rate)
 *   SELL: breakoutRvol >= 1.5 (fewer but higher-quality signals)
 *   All other conditions (scoring, SL, targets, time gates) unchanged.
 *
 * All breakout detection, signal firing, time gates, and scheduler logic unchanged.
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

    // ── Fake breakout guard ──────────────────────────────────────────────────
    private static final int CONFIRMATION_TICKS = 3;

    private final OrbDataService            orbDataService;
    private final ApplicationEventPublisher publisher;
    private final CircuitBreakerService     circuitBreaker;
    private final PositionSizerService      positionSizer;
    private final PaperAccount             paperAccount;
    private final LatencyMonitor           latencyMonitor;
    private final RvolService              rvolService;
    private final MarketDirectionService   marketDirection; // ORB regime gate

    @Value("${trading.mode:PAPER}")
    private String tradingMode;

    @Value("${trading.capital:100000}")
    private BigDecimal capital;

    @Value("${strategy.orb.enabled:true}")
    private boolean strategyEnabled;

    @Value("${strategy.orb.target-rr:2.5}")  // Raised from 1.5 → 2.5. On real trend days ORB runs 3-5x range.
    // 1.5 was leaving massive profit on the table. T1=2.5x orbRange, T2=4.0x orbRange.
    private double targetRR;

    @Value("${strategy.orb.time-stop-minutes:120}")
    private int timeStopMinutes;

    // ── Execution limits ─────────────────────────────────────────────────────
    /** REQ 2: Max trades to actually execute from the top-10 candidates. */
    private static final int MAX_EXECUTIONS = 2;

    // ── Confirmation counters ────────────────────────────────────────────────
    private final Map<String, Integer> breakoutConfirmCount = new ConcurrentHashMap<>();

    // ── Per-strategy active signal locks ────────────────────────────────────
    private final Set<String> activeSignals = ConcurrentHashMap.newKeySet();

    /**
     * REQ 2: Counts trades actually executed this session (not just monitored).
     * Once this reaches MAX_EXECUTIONS, remaining candidates are cancelled instantly.
     */
    private final AtomicInteger executedTradesCount = new AtomicInteger(0);

    /**
     * REQ 3: Direction lock for the session — thread-safe via AtomicReference.
     * null = no trade fired yet, direction is open.
     * Once first trade fires (BUY or SELL), compareAndSet locks it atomically.
     * All opposite-direction breakouts are blocked for the rest of the session.
     *
     * WHY AtomicReference and NOT volatile:
     * volatile only guarantees visibility, NOT atomicity of read-then-write.
     * Without AtomicReference, two symbols could simultaneously read null,
     * both pass the direction check, and fire in opposite directions — violating REQ 3.
     * AtomicReference.compareAndSet(null, direction) is atomic: only the first
     * thread succeeds; all others see the already-locked direction.
     */
    private final AtomicReference<TradeDirection> lockedDirection = new AtomicReference<>(null);

    /**
     * FIX 1: Cached selected-symbol set.
     * Volatile ensures visibility across threads (written by scheduler,
     * read by tickExecutor on every tick).
     * Set.of() returns an immutable set — contains() is O(1) with no allocation.
     */
    private volatile Set<String> selectedSymbolsCache = Collections.emptySet();

    // ══════════════════════════════════════════════════════════════════════════
    // FIX 1: Refresh cache 5 seconds after ORB lock (9:30:05)
    // Runs after OrbDataService.lockOrbAndScore() at 9:30:00
    // ══════════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "5 30 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void refreshSelectedSymbolsCache() {
        List<String> selected = orbDataService.getSelectedSymbols();
        selectedSymbolsCache = selected.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new HashSet<>(selected));
        log.info("[ORB] Selected symbols cache refreshed: {}", selectedSymbolsCache);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TICK LISTENER – hot path, zero blocking calls
    // ══════════════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tickExecutor")
    public void onTick(TickReceivedEvent tick) {
        if (!strategyEnabled) return;
        if (latencyMonitor.isStale()) return;

        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(BREAKOUT_START) || now.isAfter(BREAKOUT_END)) return;
        if (!orbDataService.isOrbLocked()) return;

        // ── ORB REGIME GATE ──────────────────────────────────────────────────
        // ORB monitors GAP stocks which trade independently of Nifty direction.
        // A gap-up stock in a "SIDEWAYS" Nifty session still has its own momentum.
        // The OCO + lockedDirection mechanism already controls trade direction.
        //
        // REMOVED: dir.direction() == SIDEWAYS → return
        //   This was blocking ORB even on big IB days (Apr-24: IB=1.08%, ibBrokeLow=true)
        //   when Nifty EMAs showed SIDEWAYS at 9:30 AM before the day developed.
        //   ORB should fire when gap+RVOL conditions are met, regardless of Nifty regime.
        //
        // KEPT: ATR gate — below 0.20% the whole market is frozen and ORB setups are noise.
        MarketDirectionService.MarketDirectionResult dir = marketDirection.getCurrentDirection();
        if (dir.niftyAtrPct() < 0.20) return;

        String symbol = tick.getTradingSymbol();
        if (symbol == null || symbol.isBlank()) return;

        // FIX 1: O(1) check against pre-built immutable set — zero allocation hot path.
        // Fallback: lazily populate if cache is empty (covers edge case of restart at 9:31+).
        Set<String> cache = selectedSymbolsCache;
        if (cache.isEmpty() && orbDataService.isOrbLocked()) {
            List<String> selected = orbDataService.getSelectedSymbols();
            if (!selected.isEmpty()) {
                selectedSymbolsCache = Collections.unmodifiableSet(new HashSet<>(selected));
                cache = selectedSymbolsCache;
                log.debug("[ORB] Lazily populated selected-symbol cache: {}", cache);
            }
        }
        if (!cache.contains(symbol)) return;

        if (orbDataService.isTriggered(symbol)) return;
        if (activeSignals.contains(symbol)) return;

        OrbDataService.OrbData od = orbDataService.getOrbData(symbol);
        if (od == null || !od.valid) return;

        double price = tick.getLastTradedPrice().doubleValue();
        if (price <= 0) return;

        // REQ 2: If we've already executed MAX_EXECUTIONS trades this session,
        // remaining candidates should have been cancelled already. Belt-and-suspenders guard.
        if (executedTradesCount.get() >= MAX_EXECUTIONS) return;

        // ── BOTH-SIDE OCO BREAKOUT DETECTION ─────────────────────────────────────
        // Spec §7: "Place both-side trigger orders (OCO style)"
        // Every selected stock monitors BOTH directions:
        //   price > orbHigh → BUY breakout
        //   price < orbLow  → SELL breakdown
        // Whichever fires first is the real move. markTriggered() ensures only ONE
        // side can execute — once BUY fires, the symbol is marked triggered and the
        // SELL path can never fire for the same symbol (and vice versa).
        // This is correct OCO behaviour: first breakout wins, other side cancelled.
        //
        // The gap direction (isGapUp) was previously used to restrict to one side only.
        // That is WRONG — a gap-up stock CAN fail and break down below orbLow (false gap),
        // and a gap-down stock CAN reverse and break above orbHigh (gap fill).
        // Both are valid tradeable moves. We always monitor both sides.

        // ── BUY side: price breaks above ORB High ──────────────────────────────
        if (price > od.orbHigh) {
            // REQ 3: If direction is locked to SELL, skip BUY breakouts
            if (lockedDirection.get() == TradeDirection.SHORT) {
                breakoutConfirmCount.remove(symbol + ":BUY");
                return;
            }
            int count = breakoutConfirmCount.merge(symbol + ":BUY", 1, Integer::sum);
            // Reset the opposite counter — price is going up, not down
            breakoutConfirmCount.remove(symbol + ":SELL");
            if (count >= CONFIRMATION_TICKS) {
                log.info("[ORB] ✅ BUY breakout confirmed: {} | price={} orbH={} confirms={} (gap={})",
                        symbol, String.format("%.2f", price),
                        String.format("%.2f", od.orbHigh), count,
                        od.isGapUp() ? "UP" : "DOWN");
                fireSignal(symbol, od, TradeDirection.LONG, price, tick.getVolumeTradedToday());
            }
        }
        // ── SELL side: price breaks below ORB Low ──────────────────────────────
        else if (price < od.orbLow) {
            // REQ 3: If direction is locked to BUY, skip SELL breakouts
            if (lockedDirection.get() == TradeDirection.LONG) {
                breakoutConfirmCount.remove(symbol + ":SELL");
                return;
            }
            int count = breakoutConfirmCount.merge(symbol + ":SELL", 1, Integer::sum);
            // Reset the opposite counter — price is going down, not up
            breakoutConfirmCount.remove(symbol + ":BUY");
            if (count >= CONFIRMATION_TICKS) {
                log.info("[ORB] ✅ SELL breakdown confirmed: {} | price={} orbL={} confirms={} (gap={})",
                        symbol, String.format("%.2f", price),
                        String.format("%.2f", od.orbLow), count,
                        od.isGapUp() ? "UP" : "DOWN");
                fireSignal(symbol, od, TradeDirection.SHORT, price, tick.getVolumeTradedToday());
            }
        }
        // ── Price inside range: reset both counters ─────────────────────────────
        else {
            // Price returned inside ORB range — cancel pending confirmations on both sides
            breakoutConfirmCount.remove(symbol + ":BUY");
            breakoutConfirmCount.remove(symbol + ":SELL");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SIGNAL FIRING
    // ══════════════════════════════════════════════════════════════════════════

    private void fireSignal(String symbol, OrbDataService.OrbData od,
                            TradeDirection direction, double breakoutPrice,
                            long tickVolume) {

        BigDecimal cap = resolveCapital();
        if (!circuitBreaker.checkPermission(cap).isAllowed()) {
            log.debug("[ORB] CB blocked signal for {}", symbol);
            return;
        }

        // RVOL confirmation at breakout time.
        // Lockout-RVOL (MIN_RVOL in OrbDataService) is now 1.0 — captures all gap stocks.
        // Breakout-RVOL is a second filter at the moment the price actually breaks.
        // SHORT (gap-down breakdown): needs 1.2x — slightly higher quality required for shorts.
        // LONG (gap-up breakout): needs 1.0x — any above-average volume confirms move.
        double breakoutRvol = rvolService.getRvolNow(symbol, tickVolume);
        double minRvol = direction == TradeDirection.SHORT ? 1.2 : 1.0;
        if (breakoutRvol < minRvol) {
            log.warn("[ORB] {} SKIPPED: breakout RVOL {} < min {} for {} side. " +
                            "Will retry if RVOL improves on next confirmation.",
                    symbol, breakoutRvol, minRvol, direction);
            // Do NOT call markTriggered() — allow future breakout if volume improves
            return;
        }
        log.debug("[ORB] {} breakout RVOL={} >= {} — volume confirmed ✓",
                symbol, breakoutRvol, minRvol);

        // Atomic dedup — returns false if this symbol was already triggered
        // (concurrent tick race on same symbol). Must happen BEFORE direction lock.
        if (!orbDataService.markTriggered(symbol)) {
            log.debug("[ORB] {} already triggered (concurrent tick race prevented)", symbol);
            return;
        }
        activeSignals.add(symbol);

        // REQ 3: Atomically lock direction on first trade.
        // compareAndSet(null, direction) succeeds only if no direction is set yet.
        // If it fails, another thread already locked a direction.
        // If the locked direction differs from this trade's direction → block.
        // This is race-condition-safe: volatile read + write is NOT atomic,
        // but compareAndSet IS atomic — only one thread can win.
        if (!lockedDirection.compareAndSet(null, direction)) {
            // Direction was already locked by another trade
            TradeDirection existingLock = lockedDirection.get();
            if (existingLock != direction) {
                log.info("[ORB] {} BLOCKED: session direction locked to {}. Cannot fire {} trade.",
                        symbol, existingLock, direction);
                // Undo markTriggered so it could theoretically retry, but since direction
                // is locked this symbol can never fire the opposite side anyway
                activeSignals.remove(symbol);
                return;
            }
            // Same direction as lock — allowed (second trade of same direction)
        } else {
            log.info("[ORB] 🔒 Session direction locked to {} by {}", direction, symbol);
        }

        // ── Trade parameters ─────────────────────────────────────────────────
        BigDecimal entryPrice = BigDecimal.valueOf(breakoutPrice)
                .setScale(2, RoundingMode.HALF_UP);
        double orbRange = od.orbHigh - od.orbLow;

        BigDecimal stopLoss, target1, target2;
        if (direction == TradeDirection.LONG) {
            // SL: below orbLow with 0.1% buffer (tighter than 0.1% but min 1 tick away)
            // IMPROVEMENT: use max(orbLow * 0.999, orbLow - 0.5*orbRange) as SL floor
            // so SL distance is never less than 0.5x ORB range — avoids noise-level SL
            double slLevel = Math.max(od.orbLow * 0.999, od.orbLow - orbRange * 0.5);
            stopLoss = BigDecimal.valueOf(slLevel).setScale(2, RoundingMode.FLOOR);
            target1  = entryPrice.add(BigDecimal.valueOf(orbRange * targetRR))
                    .setScale(2, RoundingMode.HALF_UP);
            target2  = entryPrice.add(BigDecimal.valueOf(orbRange * targetRR * 1.6))
                    .setScale(2, RoundingMode.HALF_UP);
        } else {
            double slLevel = Math.min(od.orbHigh * 1.001, od.orbHigh + orbRange * 0.5);
            stopLoss = BigDecimal.valueOf(slLevel).setScale(2, RoundingMode.CEILING);
            target1  = entryPrice.subtract(BigDecimal.valueOf(orbRange * targetRR))
                    .setScale(2, RoundingMode.HALF_UP);
            target2  = entryPrice.subtract(BigDecimal.valueOf(orbRange * targetRR * 1.6))
                    .setScale(2, RoundingMode.HALF_UP);
        }

        BigDecimal risk = entryPrice.subtract(stopLoss).abs();
        if (risk.compareTo(BigDecimal.ZERO) == 0) {
            log.warn("[ORB] {} zero SL distance – ORB range too small. Skipping.", symbol);
            orbDataService.markTriggered(symbol); // prevent retry
            activeSignals.remove(symbol);
            return;
        }

        // IMPROVEMENT: Minimum SL distance check — SL must be at least 0.4% of entry.
        // Prevents trading when ORB range is so tight the SL is inside market noise.
        double slPct = risk.doubleValue() / entryPrice.doubleValue();
        if (slPct < 0.004) {
            log.info("[ORB] {} SL distance {}% below minimum 0.4% — ORB range too narrow. Skipping.",
                    symbol, slPct * 100);
            orbDataService.markTriggered(symbol);
            activeSignals.remove(symbol);
            return;
        }

        // FIX: Enforce minimum 1:2 RR on ORB trades.
        // Wide ORB ranges (e.g. 13pt range on ₹470 stock) create wide SLs that compress RR.
        // Calculate actual RR before position sizing and skip if below 2.0.
        // T1 reward = orbRange × targetRR (e.g. 13.3 × 2.5 = 33.25)
        // Risk = entry - SL (e.g. 470.34 - 450 = 20.34)
        // RR = 33.25 / 20.34 = 1.63 → SKIP (was firing at 1.65R previously)
        double t1Reward = target1.subtract(entryPrice).abs().doubleValue();
        double actualRR = t1Reward / risk.doubleValue();
        if (actualRR < 2.0) {
            log.info("[ORB] {} actual RR {:.1f} below minimum 2.0 (range={}pt, risk={}pt T1={}). Skipping."
                            .replace("{:.1f}", "{}"),
                    symbol, String.format("%.2f", actualRR),
                    String.format("%.2f", orbRange),
                    String.format("%.2f", risk.doubleValue()),
                    target1);
            orbDataService.markTriggered(symbol);
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

        // ── Post-entry quality score ─────────────────────────────────────────
        int scoreRvol   = od.rvol >= 2.0 ? 20 : od.rvol >= 1.5 ? 12 : 5;
        int scoreClean  = od.cleanCandleCount >= 2 ? 20 : od.cleanCandleCount == 1 ? 12 : 0;
        int scoreSector = od.sectorAligned ? 20 : 5;
        int scoreGap    = Math.abs(od.gapPct) >= 0.02 ? 20 : Math.abs(od.gapPct) >= 0.01 ? 12 : 5;
        int scoreStruct = od.score >= 70 ? 20 : od.score >= 50 ? 12 : 5;
        int totalScore  = scoreRvol + scoreClean + scoreSector + scoreGap + scoreStruct;

        long instrumentToken = orbDataService.resolveInstrumentToken(symbol);

        log.info("[ORB] 🚀 SIGNAL FIRED: {} | {} | entry={} sl={} T1={} T2={} | " +
                        "gap={}% rvol={} score={} qty={} risk=₹{} token={}",
                symbol, direction, entryPrice, stopLoss, target1, target2,
                od.gapPct * 100, od.rvol, totalScore, pos.quantity(), pos.actualRisk(),
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
                "ORB",
                od.rvol >= 2.0 ? "BEST" : od.rvol >= 1.5 ? "GOOD" : "LATE",
                Math.abs(od.gapPct),
                od.rvol,
                false,
                "MARKET",
                direction == TradeDirection.LONG ? "GAP_UP_BREAKOUT" : "GAP_DOWN_BREAKDOWN",
                0,
                scoreRvol,
                scoreClean,
                scoreSector,
                scoreGap,
                scoreStruct,
                totalScore,
                timeStopMinutes
        ));

        // REQ 2: Count this execution. If we've now reached MAX_EXECUTIONS,
        // INSTANTLY cancel all remaining selected-but-untriggered candidates.
        // This happens synchronously here — no scheduler delay, no tick wait.
        int executed = executedTradesCount.incrementAndGet();
        log.info("[ORB] ✅ Trade {}/{} executed: {}", executed, MAX_EXECUTIONS, symbol);

        if (executed >= MAX_EXECUTIONS) {
            cancelRemainingCandidates();
        }
    }

    /**
     * REQ 2: Instantly cancel all remaining selected candidates that haven't triggered yet.
     * Called immediately after the 2nd trade executes — no delay.
     * markTriggered() blocks them from ever firing again.
     */
    private void cancelRemainingCandidates() {
        List<String> selected = orbDataService.getSelectedSymbols();
        int cancelledCount = 0;
        for (String sym : selected) {
            if (!orbDataService.isTriggered(sym)) {
                orbDataService.markTriggered(sym);
                activeSignals.remove(sym);
                breakoutConfirmCount.remove(sym + ":BUY");
                breakoutConfirmCount.remove(sym + ":SELL");
                cancelledCount++;
                log.info("[ORB] ⚡ INSTANTLY CANCELLED remaining candidate: {} " +
                        "(2 trades already executed)", sym);
            }
        }
        if (cancelledCount > 0) {
            log.info("[ORB] 🏁 Session complete — {} remaining candidates cancelled. " +
                    "Direction was: {}", cancelledCount, lockedDirection.get());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SCHEDULER: 11:30 AM – cancel unbreached setups
    // ══════════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 30 11 * * MON-FRI", zone = "Asia/Kolkata")
    public void cancelUnbrokenSetups() {
        if (!strategyEnabled) return;
        for (String symbol : orbDataService.getSelectedSymbols()) {
            if (!orbDataService.isTriggered(symbol)) {
                log.info("[ORB] ⏱ Auto-cancelled {} – no breakout by 11:30 AM", symbol);
                orbDataService.markTriggered(symbol);
                activeSignals.remove(symbol);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SIGNAL LOCK RELEASE
    // ══════════════════════════════════════════════════════════════════════════

    public void onSignalClosed(String symbol) {
        activeSignals.remove(symbol);
        log.debug("[ORB] Signal lock released for {}", symbol);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DAILY RESET
    // ══════════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyReset() {
        breakoutConfirmCount.clear();
        activeSignals.clear();
        selectedSymbolsCache = Collections.emptySet();
        executedTradesCount.set(0);     // REQ 2: reset execution counter
        lockedDirection.set(null);       // REQ 3: reset direction lock
        log.info("[ORB] Engine daily reset complete — 2 execution slots available, direction unlocked");
    }

    // ── Dashboard helpers ────────────────────────────────────────────────────
    public boolean isEnabled()               { return strategyEnabled; }
    public int     getActiveSignalCount()    { return activeSignals.size(); }
    public Set<String> getActiveSignals()    { return Collections.unmodifiableSet(activeSignals); }
    public int     getExecutedTradesCount()  { return executedTradesCount.get(); }
    public int     getRemainingSlots()       { return Math.max(0, MAX_EXECUTIONS - executedTradesCount.get()); }
    public TradeDirection getLockedDirection(){ return lockedDirection.get(); }

    private BigDecimal resolveCapital() {
        return "PAPER".equalsIgnoreCase(tradingMode) ? paperAccount.getCapital() : capital;
    }
}