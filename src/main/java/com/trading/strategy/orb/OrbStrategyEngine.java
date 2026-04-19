package com.trading.strategy.orb;

import com.trading.analysis.service.RvolService;
import com.trading.domain.enums.TradeDirection;
import com.trading.events.SmartChannelPullbackSignalEvent;
import com.trading.events.TickReceivedEvent;
import com.trading.marketdata.service.LatencyMonitor;
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

/**
 * OrbStrategyEngine – Real-time ORB breakout monitoring and signal firing.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * CHANGES vs previous version:
 * ─────────────────────────────────────────────────────────────────────────────
 * FIX 1 – Per-tick selected-symbol set allocation eliminated.
 *   Root cause: onTick() called orbDataService.getSelectedSymbols() which
 *   returns a new ArrayList copy on every invocation. With 400+ symbols ×
 *   multiple ticks per second, this produced massive short-lived heap allocation
 *   pressure → frequent young-gen GC pauses → latency spikes at exactly the
 *   moment tick processing must be fastest (breakout detection).
 *   Fix: cache the selected-symbol lookup in a volatile Set<String> field.
 *   The set is refreshed in two places:
 *     (a) After OrbDataService.lockOrbAndScore() runs at 9:30 AM (via a new
 *         @Scheduled method at 9:30:05 that runs 5 seconds after lock)
 *     (b) As a fallback, refreshed lazily inside onTick() only when the cache
 *         is empty AND orbLocked is true (covers edge cases like restarts).
 *   The hot path (tick processing after 9:30) performs a single Set.contains()
 *   against an already-populated in-memory set — zero allocation.
 *
 * FIX 2 – Breakout confirmation map key collision guard.
 *   The breakoutConfirmCount map uses "symbol:BUY" and "symbol:SELL" keys.
 *   When price re-enters the range after a failed confirmation (e.g. 2 of 3
 *   ticks confirmed then retracted), the counter reset logic was correct.
 *   However, if a symbol simultaneously satisfies BOTH isBuySetup() AND
 *   isSellSetup() (only possible if OrbData is misconfigured, since isGapUp()
 *   is mutually exclusive), both paths could fire. Added explicit mutual
 *   exclusion: BUY setup is evaluated first; SELL is skipped if od.isGapUp().
 *   This reinforces the semantic guarantee already present in OrbData.
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
    // GAP FIX 3: needed for real-time RVOL check at breakout moment
    private final RvolService              rvolService;

    @Value("${trading.mode:PAPER}")
    private String tradingMode;

    @Value("${trading.capital:100000}")
    private BigDecimal capital;

    @Value("${strategy.orb.enabled:true}")
    private boolean strategyEnabled;

    @Value("${strategy.orb.target-rr:1.5}")
    private double targetRR;

    @Value("${strategy.orb.time-stop-minutes:120}")
    private int timeStopMinutes;

    // ── Confirmation counters ────────────────────────────────────────────────
    private final Map<String, Integer> breakoutConfirmCount = new ConcurrentHashMap<>();

    // ── Per-strategy active signal locks ────────────────────────────────────
    private final Set<String> activeSignals = ConcurrentHashMap.newKeySet();

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

        // FIX 2: isBuySetup() and isSellSetup() are mutually exclusive by design
        // (isGapUp() determines the direction). Explicit branching ensures only one
        // path fires, even if OrbData state is ever unexpected.
        if (od.isGapUp()) {
            // Gap-up stock: only look for upside BUY breakout
            if (price > od.orbHigh) {
                int count = breakoutConfirmCount.merge(symbol + ":BUY", 1, Integer::sum);
                if (count >= CONFIRMATION_TICKS) {
                    log.info("[ORB] ✅ BUY confirmed: {} | price={} orbH={} confirms={}",
                            symbol, String.format("%.2f", price),
                            String.format("%.2f", od.orbHigh), count);
                    fireSignal(symbol, od, TradeDirection.LONG, price, tick.getVolumeTradedToday());
                }
            } else if (price <= od.orbHigh) {
                // Price back inside range — reset BUY counter
                breakoutConfirmCount.remove(symbol + ":BUY");
            }
        } else {
            // Gap-down stock: only look for downside SELL breakdown
            if (price < od.orbLow) {
                int count = breakoutConfirmCount.merge(symbol + ":SELL", 1, Integer::sum);
                if (count >= CONFIRMATION_TICKS) {
                    log.info("[ORB] ✅ SELL confirmed: {} | price={} orbL={} confirms={}",
                            symbol, String.format("%.2f", price),
                            String.format("%.2f", od.orbLow), count);
                    fireSignal(symbol, od, TradeDirection.SHORT, price, tick.getVolumeTradedToday());
                }
            } else if (price >= od.orbLow) {
                // Price back inside range — reset SELL counter
                breakoutConfirmCount.remove(symbol + ":SELL");
            }
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

        // GAP FIX 3 (CORRECTED): Volume confirmation at breakout moment.
        // Spec §10: "If breakout is weak (low volume / fake move) → Skip entry"
        //
        // IMPORTANT: tickVolume from getVolumeTradedToday() is CUMULATIVE for the whole day.
        // Comparing it against od.latestVolume (also cumulative at 9:30) would always pass
        // because cumulative volume only grows. Instead we recompute RVOL at breakout time.
        //
        // RVOL = currentVolume / expectedAverageVolumeByNow
        // rvolService.getRvolNow() computes this correctly using time-slot weighting.
        // A breakout on RVOL < 1.0 means below-average participation — likely a fake move.
        // Minimum breakout RVOL threshold: 1.0 (at least average volume for the time of day).
        double breakoutRvol = rvolService.getRvolNow(symbol, tickVolume);
        if (breakoutRvol < 1.0) {
            log.warn("[ORB] {} SKIPPED: weak breakout RVOL {:.2f} < 1.0. " +
                            "Below-average volume at breakout — likely fake move. " +
                            "Will retry if RVOL improves on next confirmation.",
                    symbol, breakoutRvol);
            // Do NOT call markTriggered() — allow future breakout if volume improves
            return;
        }
        log.debug("[ORB] {} breakout RVOL={:.2f} — volume confirmed ✓", symbol, breakoutRvol);

        // Atomic dedup — returns false if already triggered
        if (!orbDataService.markTriggered(symbol)) {
            log.debug("[ORB] {} already triggered (concurrent tick race prevented)", symbol);
            return;
        }
        activeSignals.add(symbol);

        // ── Trade parameters ─────────────────────────────────────────────────
        BigDecimal entryPrice = BigDecimal.valueOf(breakoutPrice)
                .setScale(2, RoundingMode.HALF_UP);
        double orbRange = od.orbHigh - od.orbLow;

        BigDecimal stopLoss, target1, target2;
        if (direction == TradeDirection.LONG) {
            stopLoss = BigDecimal.valueOf(od.orbLow * 0.999).setScale(2, RoundingMode.FLOOR);
            target1  = entryPrice.add(BigDecimal.valueOf(orbRange * targetRR))
                    .setScale(2, RoundingMode.HALF_UP);
            target2  = entryPrice.add(BigDecimal.valueOf(orbRange * targetRR * 1.5))
                    .setScale(2, RoundingMode.HALF_UP);
        } else {
            stopLoss = BigDecimal.valueOf(od.orbHigh * 1.001).setScale(2, RoundingMode.CEILING);
            target1  = entryPrice.subtract(BigDecimal.valueOf(orbRange * targetRR))
                    .setScale(2, RoundingMode.HALF_UP);
            target2  = entryPrice.subtract(BigDecimal.valueOf(orbRange * targetRR * 1.5))
                    .setScale(2, RoundingMode.HALF_UP);
        }

        BigDecimal risk = entryPrice.subtract(stopLoss).abs();
        if (risk.compareTo(BigDecimal.ZERO) == 0) {
            log.warn("[ORB] {} zero SL distance – ORB range too small. Skipping.", symbol);
            orbDataService.markTriggered(symbol); // prevent retry
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
                        "gap={:.2f}% rvol={:.2f} score={} qty={} risk=₹{} token={}",
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
        selectedSymbolsCache = Collections.emptySet(); // FIX 1: reset cache
        log.info("[ORB] Engine daily reset complete");
    }

    // ── Dashboard helpers ────────────────────────────────────────────────────
    public boolean isEnabled()            { return strategyEnabled; }
    public int     getActiveSignalCount() { return activeSignals.size(); }
    public Set<String> getActiveSignals() { return Collections.unmodifiableSet(activeSignals); }

    private BigDecimal resolveCapital() {
        return "PAPER".equalsIgnoreCase(tradingMode) ? paperAccount.getCapital() : capital;
    }
}