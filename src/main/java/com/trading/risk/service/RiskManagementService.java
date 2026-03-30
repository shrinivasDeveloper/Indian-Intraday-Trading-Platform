package com.trading.risk.service;

import com.trading.events.ProbabilityScoreEvent;
import com.trading.events.TradeApprovedEvent;
import com.trading.marketdata.service.MarketTimingService;
import com.trading.marketdata.service.VixService;
import com.trading.papertrading.service.PaperTradeManagementService;
import com.trading.position.service.PositionSizerService;
import com.trading.sector.service.SectorClassificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * RiskManagementService — 10-2-3 Slot Manager.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * GATE STRUCTURE (applied in order, outside lock unless noted):
 *
 *   Gate 0   — Stale Signal Guard      (age > 30s → reject)
 *   Gate 1   — Circuit Breaker          (daily count, P&L caps)
 *   Gate 5   — Price Validation         (null / zero entry or SL)
 *   Gate 7.5 — Slippage-Adjusted RR    ← NEW (see below)
 *   Gate 6   — Position Sizing          (1% risk rule, uses virtualEntry)
 *   ── ReentrantLock acquired ──────────────────────────────────────────
 *   Gate 2   — Sector Limit             (max 2 per sector)
 *   Gate 3   — Strategy Diversity       (max 2 per strategy/day)
 *   Gate 4   — Phase-1 Concurrency      (max 3, 4th needs Phase-2)
 *   Commit   — increment all counters
 *   ── ReentrantLock released ──────────────────────────────────────────
 *
 * ═══════════════════════════════════════════════════════════════════════
 * GATE 7.5 — SLIPPAGE-ADJUSTED RR ("Pre-Flight" Check)  [NEW]
 *
 *   THE BUG THIS FIXES:
 *     Strategy signals are scored and approved based on signal_price → target / SL.
 *     But paper (and live) execution fills at virtual_entry = signal_price + slippage.
 *     This makes the ACTUAL RR materially worse than the scored RR.
 *
 *     Example from the trade walkthrough in the documentation:
 *       Signal:        entry=₹891.55  SL=₹887.00  target=₹904.00
 *       Signal RR:     (904.00-891.55) / (891.55-887.00) = 12.45/4.55 = 2.74R
 *       Impact slip:   0.084% → virtualEntry = ₹892.30
 *       Adjusted SL:   892.30 - 887.00 = ₹5.30
 *       Adjusted tgt:  904.00 - 892.30 = ₹11.70
 *       Real RR:       11.70 / 5.30 = 2.21R  ← FAILS the 2.5R minimum!
 *
 *     Without Gate 7.5, the trade is APPROVED even though the filled position
 *     violates the minimum RR rule. This is a systematic P&L leak.
 *
 *   SLIPPAGE ESTIMATION IN GATE 7.5:
 *     Priority 1: event.getImpactSlipPct() > 0
 *       Used directly. Set by the strategy/scanner when ATR and volume data
 *       are available at signal-fire time (full-constructor path).
 *
 *     Priority 2: event.getImpactSlipPct() == 0.0 (old-style 18-param events)
 *       Conservative proxy computed from signal prices only:
 *         atrProxy  = slDist × 2        (2× SL distance ≈ daily ATR)
 *         normAtr   = atrProxy / entry
 *         proxySlip = BASE_SLIP + ATR_WEIGHT × normAtr
 *         (no volume stress term — no volume data here → underestimate → safe)
 *       This is always conservative (never overstates slippage) so it may
 *       pass some trades that would fail with true slippage, but never rejects
 *       a trade that would genuinely pass. The hard RR check is the backstop.
 *
 *   VIRTUAL ENTRY PROPAGATION:
 *     Once Gate 7.5 computes virtualEntry, it is passed to:
 *       1. Position sizing (Gate 6) — uses wider SL dist → fewer shares → less risk
 *       2. TradeApprovedEvent — PaperTradeExecutionService uses it as the
 *          "expected fill" price for ATR estimation and logging
 *
 *   MINIMUM RR SOURCE:
 *     MarketTimingService.getMinRR(vixService.extraRrRequirement())
 *     This is the same RR check the Scanner uses in Gate 7, ensuring perfect
 *     consistency: a trade that passed the scanner's RR check still passes
 *     the slippage-adjusted check only if the fill doesn't destroy the edge.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * RACE CONDITION FIX (ReentrantLock):
 *   Gates 2, 3, 4 + counter commit are a single atomic transaction under
 *   ReentrantLock(fair=true). See previous session analysis for full detail.
 *
 * STALE SIGNAL FIX (Gate 0):
 *   Signals older than max-signal-age-seconds (default 30s) are rejected
 *   before touching any state. Prevents entering a breakout that already moved.
 * ═══════════════════════════════════════════════════════════════════════
 */
@Service
@Slf4j
public class RiskManagementService {

    private final ApplicationEventPublisher   publisher;
    private final CircuitBreakerService       circuitBreaker;
    private final PositionSizerService        positionSizer;
    private final SectorClassificationService sectorClassify;
    private final PaperTradeManagementService paperManagement;
    private final MarketTimingService         timingService;
    private final VixService                  vixService;

    public RiskManagementService(
            ApplicationEventPublisher publisher,
            CircuitBreakerService circuitBreaker,
            PositionSizerService positionSizer,
            SectorClassificationService sectorClassify,
            @Lazy PaperTradeManagementService paperManagement,
            MarketTimingService timingService,
            VixService vixService) {
        this.publisher       = publisher;
        this.circuitBreaker  = circuitBreaker;
        this.positionSizer   = positionSizer;
        this.sectorClassify  = sectorClassify;
        this.paperManagement = paperManagement;
        this.timingService   = timingService;
        this.vixService      = vixService;
    }

    // ── Config ─────────────────────────────────────────────────────────────────

    @Value("${trading.capital:100000}")
    private String capitalStr;

    @Value("${trading.max-trades-per-strategy:2}")
    private int maxTradesPerStrategy;

    @Value("${trading.max-phase1-concurrent:3}")
    private int maxPhase1Concurrent;

    @Value("${trading.max-sector-trades:2}")
    private int maxSectorTrades;

    @Value("${trading.max-signal-age-seconds:30}")
    private int maxSignalAgeSeconds;

    // Gate 7.5 impact cost constants — match PositionSizerService.ImpactCostCalculator defaults
    // Configurable via yml so they stay in sync with the execution-side formula.
    @Value("${paper-trading.impact-cost.base:0.0003}")
    private double impactBase;

    @Value("${paper-trading.impact-cost.atr-weight:0.15}")
    private double impactAtrWeight;

    @Value("${paper-trading.impact-cost.min-slip:0.0003}")
    private double impactMinSlip;

    @Value("${paper-trading.impact-cost.max-slip:0.005}")
    private double impactMaxSlip;

    // ── Counters ───────────────────────────────────────────────────────────────

    private final Map<String, Integer> sectorExposure   = new ConcurrentHashMap<>();
    private final Map<String, Integer> strategyExposure = new ConcurrentHashMap<>();
    private final AtomicInteger        phase1Count      = new AtomicInteger(0);

    /** Fair ReentrantLock — gates 2/3/4 check-and-commit are one atomic transaction */
    private final ReentrantLock slotLock = new ReentrantLock(true);

    private BigDecimal capital() { return new BigDecimal(capitalStr); }

    // ══════════════════════════════════════════════════════════════════════════
    // MAIN EVENT HANDLER
    // ══════════════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tradingExecutor")
    public void onProbabilityScore(ProbabilityScoreEvent event) {
        if (!"EXECUTE".equals(event.getDecision())) return;

        String     sym      = event.getTradingSymbol();
        String     strategy = event.getStrategyName() != null ? event.getStrategyName() : "UNKNOWN";
        BigDecimal cap      = capital();

        // ── Gate 0: Stale Signal Guard ─────────────────────────────────────────
        Instant signalTime = event.getSignalTimestamp();
        if (signalTime != null) {
            long ageSeconds = Duration.between(signalTime, Instant.now()).getSeconds();
            if (ageSeconds > maxSignalAgeSeconds) {
                log.warn("[RISK] REJECTED {} [STALE_SIGNAL]: {}s old (max={}s) — dropping stale breakout",
                        sym, ageSeconds, maxSignalAgeSeconds);
                return;
            }
        }

        // ── Gate 1: Circuit Breaker ────────────────────────────────────────────
        CircuitBreakerService.Permission perm = circuitBreaker.checkPermission(cap);
        if (!perm.isAllowed()) {
            log.warn("[RISK] REJECTED {} [CB]: {}", sym, perm.reason());
            return;
        }

        // ── Gate 5: Price Validation ───────────────────────────────────────────
        BigDecimal signalEntry = event.getEntryPrice();
        BigDecimal sl          = event.getStopLoss();
        BigDecimal target      = event.getTarget();

        if (signalEntry == null || signalEntry.compareTo(BigDecimal.ZERO) == 0
                || sl == null || sl.compareTo(BigDecimal.ZERO) == 0) {
            log.warn("[RISK] REJECTED {} [NULL_PRICES]: entry or SL is zero/null", sym);
            return;
        }

        // ── Gate 7.5: Slippage-Adjusted RR Pre-Flight Check  ──────────────────
        //
        // Compute the VIRTUAL ENTRY (expected fill price after impact cost).
        // Then verify the actual RR from virtual entry still clears the minimum.
        // If slippage consumes the edge, reject BEFORE wasting a position slot.
        //
        // This gate runs OUTSIDE the lock — pure arithmetic, no state mutation.
        BigDecimal virtualEntry = computeVirtualEntry(sym, signalEntry, sl, event);
        if (virtualEntry == null) return; // should never be null, guard against NPE

        // Only check RR if target is provided (some strategies may omit it)
        if (target != null && target.compareTo(BigDecimal.ZERO) > 0) {
            boolean isLong = event.getDirection() != null
                    && event.getDirection().name().equals("LONG");

            BigDecimal adjSlDist  = virtualEntry.subtract(sl).abs();
            BigDecimal adjTgtDist = isLong
                    ? target.subtract(virtualEntry)
                    : virtualEntry.subtract(target);

            if (adjSlDist.compareTo(BigDecimal.ZERO) > 0
                    && adjTgtDist.compareTo(BigDecimal.ZERO) > 0) {

                double adjustedRR = adjTgtDist.divide(adjSlDist, MathContext.DECIMAL64).doubleValue();
                double minRR      = timingService.getMinRR(vixService.extraRrRequirement());

                if (adjustedRR < minRR) {
                    // Compute the original signal RR for the log so it's clear how much slippage ate
                    BigDecimal sigSlDist  = signalEntry.subtract(sl).abs();
                    BigDecimal sigTgtDist = isLong
                            ? target.subtract(signalEntry)
                            : signalEntry.subtract(target);
                    double signalRR = sigSlDist.compareTo(BigDecimal.ZERO) > 0
                            ? sigTgtDist.divide(sigSlDist, MathContext.DECIMAL64).doubleValue()
                            : 0;

                    log.warn("[RISK] REJECTED {} [SLIPPAGE_KILLS_RR]: " +
                                    "signalEntry={} virtualEntry={} SL={} target={} | " +
                                    "signalRR={:.2f}R → adjustedRR={:.2f}R < minRR={:.1f}R | " +
                                    "Slippage reduced RR by {:.2f}R. Trade rejected.",
                            sym, signalEntry, virtualEntry, sl, target,
                            signalRR, adjustedRR, minRR, signalRR - adjustedRR);
                    return;
                }

                log.debug("[RISK] Gate-7.5 PASS {}: signalEntry={} virtualEntry={} " +
                                "adjustedRR={:.2f}R >= minRR={:.1f}R",
                        sym, signalEntry, virtualEntry, adjustedRR, minRR);
            }
        }

        // ── Gate 6: Position Sizing ────────────────────────────────────────────
        // CRITICAL: use virtualEntry (not signalEntry) so the sizing correctly
        // accounts for the wider SL distance after slippage.
        // Fewer shares → actual risk stays within the 1% ceiling.
        PositionSizerService.PositionSize size = positionSizer.calculate(
                cap, virtualEntry, sl, sym, event.getDirection().name());
        if (!size.isValid()) {
            log.warn("[RISK] REJECTED {} [SIZING]: {}", sym, size.invalidReason());
            return;
        }

        // ── ATOMIC SECTION: Gates 2, 3, 4 + counter commit ────────────────────
        slotLock.lock();
        try {
            String sector = sectorClassify.getSector(sym);

            // Gate 2: Sector Limit
            int sectorCount = sectorExposure.getOrDefault(sector, 0);
            if (sectorCount >= maxSectorTrades) {
                log.warn("[RISK] REJECTED {} [SECTOR]: '{}' has {}/{} trades",
                        sym, sector, sectorCount, maxSectorTrades);
                return;
            }

            // Gate 3: Strategy Diversity
            int stratCount = strategyExposure.getOrDefault(strategy, 0);
            if (stratCount >= maxTradesPerStrategy) {
                log.warn("[RISK] REJECTED {} [STRATEGY_DIVERSITY]: '{}' has {}/{} trades today",
                        sym, strategy, stratCount, maxTradesPerStrategy);
                return;
            }

            // Gate 4: Phase-1 Concurrency
            int p1 = phase1Count.get();
            if (p1 >= maxPhase1Concurrent) {
                boolean hasBreakeven = paperManagement.isAnyTradeAtBreakevenOrBeyond();
                if (!hasBreakeven) {
                    log.warn("[RISK] REJECTED {} [PHASE1_FULL]: phase1={}/{}, no breakeven trade yet",
                            sym, p1, maxPhase1Concurrent);
                    return;
                }
                log.info("[RISK] Gate-4 OVERRIDE {}: phase1={}/{} — a trade at breakeven → allow",
                        sym, p1, maxPhase1Concurrent);
            }

            // All gates passed — commit atomically
            sectorExposure.merge(sector, 1, Integer::sum);
            strategyExposure.merge(strategy, 1, Integer::sum);
            phase1Count.incrementAndGet();
            circuitBreaker.recordTradeEntered();

            log.info("[RISK] APPROVED: {} strategy={} sector={} dir={} qty={} " +
                            "signalEntry={} virtualEntry={} sl={} target={} " +
                            "sector={}/{} strat={}/{} phase1={}/{}",
                    sym, strategy, sector, event.getDirection(), size.quantity(),
                    signalEntry, virtualEntry, sl, target,
                    sectorExposure.getOrDefault(sector, 0), maxSectorTrades,
                    strategyExposure.getOrDefault(strategy, 0), maxTradesPerStrategy,
                    phase1Count.get(), maxPhase1Concurrent);

        } finally {
            slotLock.unlock();
        }

        // ── Publish OUTSIDE the lock ───────────────────────────────────────────
        // Pass virtualEntry as the entry price so PaperTradeExecutionService
        // uses it for ATR estimation and slippage logging.
        // The actual fill will be computed again at execution time — this is
        // consistent because the impact cost formula is deterministic given
        // the same ATR/volume inputs.
        publisher.publishEvent(new TradeApprovedEvent(this,
                sym, event.getInstrumentToken(),
                event.getDirection(), virtualEntry,   // ← virtualEntry, not signalEntry
                sl, target,
                size.quantity(), size.actualRisk(),
                event.getTotalScore(), event.getStrategyName(),
                event.getTimeStopMinutes()));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // GATE 7.5 HELPER — Virtual Entry Computation
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Computes the expected fill price after impact cost slippage.
     *
     * <h3>Slippage source priority</h3>
     * <ol>
     *   <li>If {@code event.getImpactSlipPct() > 0}: use directly. This value was
     *       computed by the strategy/scanner using real ATR14 and current volume
     *       data at signal-fire time (full precision).</li>
     *   <li>If {@code event.getImpactSlipPct() == 0.0}: compute a conservative proxy
     *       using the SL distance as an ATR approximation:
     *       <pre>
     *         atrProxy  = slDist × 2        (2× SL distance ≈ 1-day ATR)
     *         normAtr   = atrProxy / entry
     *         proxySlip = BASE_SLIP + ATR_WEIGHT × normAtr
     *         slip      = clamp(proxySlip, MIN_SLIP, MAX_SLIP)
     *       </pre>
     *       No volume-stress term is added (no volume data available here), so
     *       this proxy always UNDERESTIMATES slippage. It may let borderline trades
     *       pass Gate 7.5 when true slippage would fail them — but it never
     *       rejects a genuinely-good trade. The hard RR minimum is the backstop.
     *   </li>
     * </ol>
     *
     * <h3>Direction</h3>
     * LONG fills ABOVE signal price (market impact pushes price up at entry).
     * SHORT fills BELOW signal price (market impact pushes price down at entry).
     * Result is tick-aligned to the NSE 5-paise grid (conservative direction).
     */
    private BigDecimal computeVirtualEntry(String sym,
                                           BigDecimal signalEntry,
                                           BigDecimal sl,
                                           ProbabilityScoreEvent event) {
        // Determine slippage %
        double slipPct;
        if (event.getImpactSlipPct() > 0.0) {
            // Full-precision value from signal-fire time
            slipPct = Math.min(event.getImpactSlipPct(), impactMaxSlip);
            log.debug("[RISK] Gate-7.5 {}: using event impactSlipPct={:.4f}%",
                    sym, slipPct * 100);
        } else {
            // Conservative proxy: ATR ≈ 2 × SL distance, no volume stress
            BigDecimal slDist   = signalEntry.subtract(sl).abs();
            double     atrProxy = slDist.multiply(BigDecimal.valueOf(2),
                    MathContext.DECIMAL64).doubleValue();
            double normAtr      = signalEntry.doubleValue() > 0
                    ? atrProxy / signalEntry.doubleValue() : 0;
            double rawSlip      = impactBase + impactAtrWeight * normAtr;
            slipPct             = Math.max(impactMinSlip, Math.min(impactMaxSlip, rawSlip));
            log.debug("[RISK] Gate-7.5 {}: computed proxySlip={:.4f}% (atrProxy={:.2f})",
                    sym, slipPct * 100, atrProxy);
        }

        // Apply slippage to get raw virtual entry
        boolean isLong = event.getDirection() != null
                && event.getDirection().name().equals("LONG");

        BigDecimal rawVirtual = isLong
                ? signalEntry.multiply(BigDecimal.valueOf(1.0 + slipPct), MathContext.DECIMAL64)
                : signalEntry.multiply(BigDecimal.valueOf(1.0 - slipPct), MathContext.DECIMAL64);

        // Align to 5-paise NSE grid — conservative: LONG rounds UP (worse fill = more cost)
        BigDecimal ticks = rawVirtual.multiply(BigDecimal.valueOf(20), MathContext.DECIMAL64)
                .setScale(0, isLong ? RoundingMode.CEILING : RoundingMode.FLOOR);
        return ticks.divide(BigDecimal.valueOf(20), 2, RoundingMode.UNNECESSARY);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Phase-2 Migration callback
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Called when a trade migrates Phase-1 → Phase-2 (SL to breakeven).
     * Runs under slotLock to prevent race with concurrent Gate-4 check.
     */
    public void notifyPhase2Migration(String symbol) {
        slotLock.lock();
        try {
            int prev = phase1Count.getAndUpdate(v -> Math.max(0, v - 1));
            log.info("[RISK] {} → Phase-2. phase1Count: {} → {}", symbol, prev, phase1Count.get());
        } finally {
            slotLock.unlock();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Trade close callback
    // ══════════════════════════════════════════════════════════════════════════

    public void onTradeClosed(String symbol, BigDecimal pnl,
                              String strategy, boolean reachedPhase2) {
        circuitBreaker.recordPnl(pnl);
        slotLock.lock();
        try {
            String sector = sectorClassify.getSector(symbol);
            sectorExposure.merge(sector,   -1, (a, b) -> Math.max(0, a + b));
            if (strategy != null) {
                strategyExposure.merge(strategy, -1, (a, b) -> Math.max(0, a + b));
            }
            if (!reachedPhase2) {
                phase1Count.updateAndGet(v -> Math.max(0, v - 1));
            }
            log.debug("[RISK] Closed {}: sector/strat released, reachedPhase2={}, phase1={}",
                    symbol, reachedPhase2, phase1Count.get());
        } finally {
            slotLock.unlock();
        }
    }

    /** Backward-compatible overload — assumes Phase-1 close. */
    public void onTradeClosed(String symbol, BigDecimal pnl) {
        onTradeClosed(symbol, pnl, null, false);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Dashboard helpers
    // ══════════════════════════════════════════════════════════════════════════

    public Map<String, Integer> getSectorExposure()   { return Collections.unmodifiableMap(sectorExposure); }
    public Map<String, Integer> getStrategyExposure() { return Collections.unmodifiableMap(strategyExposure); }
    public int                  getPhase1Count()      { return phase1Count.get(); }
    public int                  getMaxPhase1()        { return maxPhase1Concurrent; }

    // ══════════════════════════════════════════════════════════════════════════
    // Daily reset
    // ══════════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 45 8 * * MON-FRI", zone = "Asia/Kolkata")
    public void resetDaily() {
        slotLock.lock();
        try {
            sectorExposure.clear();
            strategyExposure.clear();
            phase1Count.set(0);
            log.info("[RISK] Daily reset — all slot counters cleared");
        } finally {
            slotLock.unlock();
        }
    }
}