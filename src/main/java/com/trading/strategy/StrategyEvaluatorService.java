// ============================================================
// REPLACE FILE (full replacement) — v7.0 FINAL
// Path: src/main/java/com/trading/strategy/StrategyEvaluatorService.java
// v7.0 CHANGES vs previous version:
//   1. MarketPhaseEngine integration — EARLY vs CONFIRMED phase handling
//   2. StockRankingEngine — only TOP 3 ranked stocks execute (v7.0 req 6)
//   3. Dynamic thresholds — phase-aware (58 early, 62/60/68 confirmed)
//   4. Early boost (+5 if time < 10:15 && rvol > 1.2)
//   5. Partial candle support — strategies evaluated on forming candles too
//   6. ORB strategy prioritized in EARLY phase regardless of market mode
//   7. All previous v3.1 fixes preserved (LatencyMonitor, BankNiftyModeEngine,
//      stale signal guard, decay factor)
// ============================================================
package com.trading.strategy;

import com.trading.analysis.service.PatternDetectionService;
import com.trading.analysis.service.RvolService;
import com.trading.analysis.service.TechnicalAnalysisService;
import com.trading.domain.Candle;
import com.trading.domain.enums.TradeDirection;
import com.trading.events.CandleCompleteEvent;
import com.trading.events.ProbabilityScoreEvent;
import com.trading.events.ScannerSignalEvent;
import com.trading.marketdata.service.LatencyMonitor;
import com.trading.marketdata.service.VixService;
import com.trading.ranking.service.StockRankingEngine;
import com.trading.regime.service.BankNiftyModeEngine;
import com.trading.regime.service.MarketDirectionService;
import com.trading.regime.service.MarketModeEngine;
import com.trading.regime.service.MarketPhaseEngine;
import com.trading.regime.service.ProbabilityEngine;
import com.trading.sector.service.SectorClassificationService;
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
import java.math.MathContext;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * StrategyEvaluatorService v7.0 — Full adaptive system.
 *
 * EXECUTION FLOW:
 *   Phase check → Latency check → Market tradeable? → VIX check
 *   → Strategy allowed in mode? → generateSignal()
 *   → ProbabilityEngine (with phase boost + decay)
 *   → Threshold check (phase-aware dynamic)
 *   → StockRankingEngine (top 3 only)
 *   → executeTrade()
 *
 * v7.0 ADDITIONS:
 *   - MarketPhaseEngine: EARLY (ORB only, threshold=58) vs CONFIRMED (all strategies)
 *   - StockRankingEngine: submits candidates, only rank≤3 execute
 *   - Dynamic threshold: 58 early / 62 TREND / 60 NORMAL / 68 NEUTRAL
 *   - Early boost: +5 probability if time<10:15 && rvol>1.2
 *   - Partial candle: strategies triggered on forming candles (complete=false)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StrategyEvaluatorService {

    private final ApplicationEventPublisher   publisher;
    private final MarketDirectionService      marketDirection;
    private final MarketModeEngine            marketModeEngine;
    private final BankNiftyModeEngine         bankNiftyModeEngine;
    private final MarketPhaseEngine           marketPhaseEngine;    // v7.0
    private final ProbabilityEngine           probabilityEngine;
    private final LatencyMonitor              latencyMonitor;
    private final StockRankingEngine          rankingEngine;        // v7.0
    private final VixService                  vixService;
    private final RvolService                 rvolService;
    private final SectorStrengthService       sectorStrength;
    private final SectorClassificationService sectorClassify;
    private final PatternDetectionService     patternDetection;
    private final TechnicalAnalysisService    technicalAnalysis;

    private final List<TradingStrategy> strategies;

    private final Map<String, Deque<Candle>> buf5m  = new ConcurrentHashMap<>();
    private final Map<String, Deque<Candle>> buf15m = new ConcurrentHashMap<>();

    private final Set<String>                firedToday     = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<String, AtomicInteger> signalCounters = new ConcurrentHashMap<>();

    @Value("${strategy.enabled:true}")
    private boolean enabled;

    @Value("${trading.max-signal-age-seconds:30}")
    private int maxSignalAgeSeconds;

    private static final double       VIX_TREND_BLOCK    = 28.0;
    private static final Set<String>  VIX_BLOCKED_STRATS = Set.of("AUTO_MODE", "ORB_VWAP_SECTOR", "SCANNER_7GATE");
    private static final String       ORB_STRATEGY       = "ORB_VWAP_SECTOR";

    // ═══════════════════════════════════════════════════════════════════════════
    // 7-Gate Scanner signal handler
    // ═══════════════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tradingExecutor")
    public void onScannerSignal(ScannerSignalEvent event) {
        String sym = event.getTradingSymbol();
        String key = sym + ":SCANNER_7GATE";
        if (firedToday.contains(key) || !enabled) return;

        if (latencyMonitor.isStale()) {
            log.warn("[7GATE] {} BLOCKED: STALE ({})", sym, latencyMonitor.getStatus());
            return;
        }
        if (!marketPhaseEngine.isTradeAllowed()) {
            log.debug("[7GATE] {} skip — market phase {}", sym, marketPhaseEngine.getCurrentPhase());
            return;
        }

        MarketModeEngine.MarketModeResult niftyMode = marketModeEngine.getCurrentMode();
        MarketModeEngine.MarketModeResult mode = bankNiftyModeEngine.getModeForSymbol(sym, niftyMode);

        if (!mode.isTradeDay()) { log.debug("[7GATE] {} skip NON_TREND_DAY", sym); return; }
        if (!isStrategyAllowedInMode("SCANNER_7GATE", mode) && !marketPhaseEngine.isEarlyPhase()) {
            log.debug("[7GATE] {} skip mode={}", sym, mode.mode()); return;
        }

        double vix = vixService.getCurrentVix();
        if (vix > VIX_TREND_BLOCK) { log.info("[7GATE] {} blocked VIX={:.1f}", sym, vix); return; }

        if (event.getDirection() == null || event.getEntryPrice() == null
                || event.getStopLoss() == null || event.getTarget() == null) {
            log.warn("[7GATE] {} missing signal params", sym); return;
        }
        if (isSignalStale(event.getScanTime())) {
            log.warn("[7GATE] {} SKIP: signal age > {}s", sym, maxSignalAgeSeconds); return;
        }

        // v7.0 — early boost for 7-gate signals before 10:15
        double rvolVal = 1.0;
        double vixAdj  = vix < 20 ? 5 : vix > 20 ? -5 : 0;
        double earlyBoost = marketPhaseEngine.getEarlyBoost(rvolVal);
        double prob = Math.min(95, Math.max(0, 75.0 + vixAdj + earlyBoost));

        // v7.0 — dynamic threshold
        double threshold = marketPhaseEngine.getAdjustedThreshold(mode.minProbability());
        MarketModeEngine.TradeTier tier = prob >= 75 ? MarketModeEngine.TradeTier.GOLD
                : prob >= threshold ? MarketModeEngine.TradeTier.NORMAL : MarketModeEngine.TradeTier.SKIP;
        if (tier == MarketModeEngine.TradeTier.SKIP) {
            log.debug("[7GATE] {} prob={:.0f} below threshold {:.0f}", sym, prob, threshold); return;
        }

        // v7.0 — ranking check
        if (!rankingEngine.isTopRanked(sym)) {
            log.info("[7GATE] {} SKIP: not in top-3 ranked", sym); return;
        }

        log.info("[7GATE] {} FIRE prob={:.0f} tier={} phase={} threshold={:.0f}",
                sym, prob, tier, marketPhaseEngine.getCurrentPhase(), threshold);

        fireProbabilityEvent(sym, event.getInstrumentToken(), event.getDirection(),
                event.getEntryPrice(), event.getStopLoss(), event.getTarget(),
                BigDecimal.valueOf(prob), "SCANNER_7GATE", 0);
        firedToday.add(key);
        incrementSignalCount("SCANNER_7GATE");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Candle-based strategies (complete=true AND forming candle complete=false)
    // ═══════════════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tradingExecutor")
    public void onCandle(CandleCompleteEvent event) {
        Candle c   = event.getCandle();
        String sym = c.getTradingSymbol();

        // Accumulate buffer (both complete and forming candles)
        if ("5minute".equals(c.getTimeframe())) {
            Deque<Candle> b = buf5m.computeIfAbsent(sym, k -> new ArrayDeque<>());
            // For forming candles: replace head. For complete: add as new head.
            if (c.isComplete()) {
                b.addFirst(c);
            } else {
                // Replace the forming head if it exists, else add
                if (!b.isEmpty() && !b.peekFirst().isComplete()) {
                    ((ArrayDeque<Candle>) b).pollFirst();
                }
                b.addFirst(c);
            }
            if (b.size() > 100) ((ArrayDeque<Candle>) b).removeLast();
        }
        if ("15minute".equals(c.getTimeframe()) && c.isComplete()) {
            Deque<Candle> b = buf15m.computeIfAbsent(sym, k -> new ArrayDeque<>());
            b.addFirst(c); if (b.size() > 100) ((ArrayDeque<Candle>) b).removeLast();
        }

        // Only evaluate on 5m candles (complete OR forming during EARLY phase)
        if (!"5minute".equals(c.getTimeframe()) || !enabled) return;

        // For forming (partial) candles: only evaluate in EARLY phase for ORB
        if (!c.isComplete() && !marketPhaseEngine.isEarlyPhase()) return;

        List<Candle> c5m  = new ArrayList<>(buf5m.getOrDefault(sym, new ArrayDeque<>()));
        List<Candle> c15m = new ArrayList<>(buf15m.getOrDefault(sym, new ArrayDeque<>()));
        if (c5m.isEmpty()) return;

        // ── LATENCY GUARD ─────────────────────────────────────────────────────
        if (latencyMonitor.isStale()) {
            log.debug("[EVAL] {} BLOCKED: STALE", sym); return;
        }

        // ── PHASE CHECK ───────────────────────────────────────────────────────
        if (!marketPhaseEngine.isTradeAllowed()) return;

        // ── IB FORCE FAILSAFE (v7.0 FIX 4) ───────────────────────────────────
        if (marketPhaseEngine.isIbForceNeeded()) {
            marketModeEngine.forceComputeIbIfMissing(c5m);
            bankNiftyModeEngine.forceComputeIbIfMissing(c5m);
        }

        // ── MODE (per index) ──────────────────────────────────────────────────
        MarketModeEngine.MarketModeResult niftyMode = marketModeEngine.getCurrentMode();
        MarketModeEngine.MarketModeResult mode = bankNiftyModeEngine.getModeForSymbol(sym, niftyMode);
        if (!mode.isTradeDay() && !marketPhaseEngine.isEarlyPhase()) return;

        // ── DIRECTION CHECK ───────────────────────────────────────────────────
        MarketDirectionService.MarketDirectionResult dir = marketDirection.getCurrentDirection();
        if (!dir.isTradeable()) {
            log.debug("[EVAL] {} BLOCKED: market not tradeable — {}", sym, dir.failReason()); return;
        }

        double  vix         = vixService.getCurrentVix();
        boolean vixHighFear = vix > VIX_TREND_BLOCK;

        // ── RVOL for early boost ───────────────────────────────────────────────
        double rvol = c5m.isEmpty() ? 1.0 : rvolService.getRvolNow(sym, c5m.get(0).getVolume());
        double earlyBoost = marketPhaseEngine.getEarlyBoost(rvol);

        TradingStrategy.MarketContext ctx = buildContext(sym, dir);

        for (TradingStrategy strategy : strategies) {
            String strat = strategy.name();
            String key   = sym + ":" + strat;
            if (firedToday.contains(key)) continue;

            // ── STRATEGY GATE ─────────────────────────────────────────────────
            boolean earlyOrbAllowed = marketPhaseEngine.isEarlyPhase()
                    && ORB_STRATEGY.equals(strat)
                    && marketPhaseEngine.isOrbAllowed();

            if (!earlyOrbAllowed) {
                // In confirmed phase OR not ORB: use mode gate
                if (!isStrategyAllowedInMode(strat, mode)) continue;
            }

            // ── VIX GATE ─────────────────────────────────────────────────────
            if (vixHighFear && VIX_BLOCKED_STRATS.contains(strat)) continue;

            try {
                Optional<TradingStrategy.TradeSignal> signalOpt = strategy.generateSignal(sym, c5m, c15m, ctx);
                if (signalOpt.isEmpty()) continue;

                TradingStrategy.TradeSignal signal = signalOpt.get();
                if (!isValidSignal(signal, sym, strat)) continue;

                // ── PROBABILITY ENGINE ────────────────────────────────────────
                ProbabilityEngine.ScoringContext scoreCtx =
                        new ProbabilityEngine.ScoringContext(sym, strat, signal, ctx, c5m, c15m);
                ProbabilityEngine.ScoreBreakdown score = probabilityEngine.calculate(scoreCtx);

                // ── v7.0 EARLY BOOST ─────────────────────────────────────────
                double adjustedTotal = Math.min(100, score.total() + earlyBoost);
                if (earlyBoost > 0) {
                    log.debug("[EVAL] {} {} early boost +{:.0f} → prob={:.0f}",
                            strat, sym, earlyBoost, adjustedTotal);
                }

                // ── v7.0 DYNAMIC THRESHOLD ────────────────────────────────────
                double threshold = marketPhaseEngine.getAdjustedThreshold(mode.minProbability());
                MarketModeEngine.TradeTier tier = adjustedTotal >= 75
                        ? MarketModeEngine.TradeTier.GOLD
                        : adjustedTotal >= threshold
                        ? MarketModeEngine.TradeTier.NORMAL
                        : MarketModeEngine.TradeTier.SKIP;

                if (tier == MarketModeEngine.TradeTier.SKIP) {
                    log.debug("[PROB] {} {} SKIP prob={:.0f}<threshold={:.0f} | {}",
                            strat, sym, adjustedTotal, threshold, score.detail());
                    continue;
                }

                // ── v7.0 RANKING ENGINE ───────────────────────────────────────
                String sectorName = sectorClassify.getSector(sym);
                rankingEngine.submitCandidate(sym, adjustedTotal, c5m, sectorName);
                if (!rankingEngine.isTopRanked(sym)) {
                    log.info("[RANK] {} {} SKIP: not in top-3. rank={}",
                            strat, sym, rankingEngine.getRank(sym));
                    continue;
                }

                double posMultiplier = adjustedTotal >= 75 ? 1.2 : 1.0;

                log.info("[{}] {} FIRE prob={:.0f} tier={} size={:.1f}x mode={} phase={} index={} threshold={:.0f}",
                        strat, sym, adjustedTotal, tier, posMultiplier,
                        mode.mode(), marketPhaseEngine.getCurrentPhase(),
                        bankNiftyModeEngine.isBankNiftyStock(sym) ? "BANKNIFTY" : "NIFTY",
                        threshold);
                log.debug("[{}] {} breakdown: {}", strat, sym, score.detail());

                fireProbabilityEvent(sym, c.getInstrumentToken(), signal.direction(),
                        signal.entryPrice(), signal.stopLoss(), signal.target(),
                        BigDecimal.valueOf(adjustedTotal), strat, signal.timeStopMinutes());
                firedToday.add(key);
                incrementSignalCount(strat);

            } catch (Exception e) {
                log.warn("[{}] Error {}: {}", strat, sym, e.getMessage());
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private boolean isStrategyAllowedInMode(String strat, MarketModeEngine.MarketModeResult mode) {
        String active = mode.activeStrategies();
        if (active == null || active.isBlank() || "NONE".equals(active)) return false;
        for (String s : active.split(",")) {
            if (s.trim().equalsIgnoreCase(strat)) return true;
        }
        return false;
    }

    private boolean isSignalStale(Instant signalTime) {
        if (signalTime == null) return false;
        long ageMs = Instant.now().toEpochMilli() - signalTime.toEpochMilli();
        return ageMs > (long) maxSignalAgeSeconds * 1000;
    }

    private TradingStrategy.MarketContext buildContext(String sym,
                                                       MarketDirectionService.MarketDirectionResult dir) {
        String sectorName = sectorClassify.getSector(sym);
        SectorStrengthService.SectorData   sector    = sectorStrength.getSector(sectorName);
        TechnicalAnalysisService.TechnicalStructure  structure = technicalAnalysis.getStructure(sym);
        PatternDetectionService.PatternResult        pattern   = patternDetection.getPattern(sym);
        double niftyChgPct = dir.niftyBullish() ? Math.abs(dir.niftyAtrPct()) : -Math.abs(dir.niftyAtrPct());
        return new TradingStrategy.MarketContext(
                dir.niftyBullish(), dir.niftyBearish(), niftyChgPct, dir.niftyAtrPct(),
                sectorName, sector.changePercent(), sector.alignedBullish(), sector.alignedBearish(),
                sector.isTopSector(), sector.isBottomSector(), sector.relativeStrength(),
                structure.vwap(), structure.vwapConfluence(), pattern, structure
        );
    }

    private boolean isValidSignal(TradingStrategy.TradeSignal s, String sym, String strat) {
        if (s.entryPrice() == null || s.stopLoss() == null || s.target() == null) {
            log.warn("[{}] {} null prices", strat, sym); return false;
        }
        if (s.entryPrice().compareTo(BigDecimal.ZERO) == 0) {
            log.warn("[{}] {} zero entry", strat, sym); return false;
        }
        BigDecimal slDist = s.entryPrice().subtract(s.stopLoss()).abs();
        if (slDist.compareTo(BigDecimal.ZERO) == 0) {
            log.warn("[{}] {} zero SL", strat, sym); return false;
        }
        double slPct = slDist.divide(s.entryPrice(), MathContext.DECIMAL32).doubleValue() * 100;
        if (slPct > 3.0) { log.warn("[{}] {} SL {:.2f}%>3%", strat, sym, slPct); return false; }
        return true;
    }

    private void fireProbabilityEvent(String sym, long token, TradeDirection dir,
                                      BigDecimal entry, BigDecimal sl, BigDecimal tgt,
                                      BigDecimal score, String stratName, int timeStop) {
        publisher.publishEvent(new ProbabilityScoreEvent(
                this, sym, token, score, "EXECUTE", dir, entry, sl, tgt, stratName,
                score, score, score, score, score, score, score, score,
                timeStop, Instant.now(), 0.0
        ));
    }

    // ── Dashboard ──────────────────────────────────────────────────────────────

    private void incrementSignalCount(String s) {
        signalCounters.computeIfAbsent(s, k -> new AtomicInteger()).incrementAndGet();
    }

    public Set<String>         getFiredToday()     { return Collections.unmodifiableSet(firedToday); }
    public int                 getCount(String s)  { AtomicInteger a = signalCounters.get(s); return a != null ? a.get() : 0; }
    public Map<String,Integer> getSignalCounters() {
        Map<String,Integer> r = new LinkedHashMap<>();
        List.of("SCANNER_7GATE","AUTO_MODE","ORB_VWAP_SECTOR","VAP_PULLBACK","RANGE_BREAKOUT_3TOUCH")
                .forEach(s -> r.put(s, getCount(s)));
        return r;
    }

    @Scheduled(cron = "0 15 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyReset() {
        firedToday.clear();
        signalCounters.clear();
        log.info("[EVAL] Daily reset");
    }
}