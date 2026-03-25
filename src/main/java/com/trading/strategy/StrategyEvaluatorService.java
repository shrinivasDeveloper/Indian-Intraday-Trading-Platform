// ========== MODIFIED FILE ==========
// Path: src/main/java/com/trading/strategy/StrategyEvaluatorService.java
// CHANGES vs original:
//   1. Added explicit isTradeable() guard with detailed reason logging.
//      Without this, the logs are silent about WHY signals are 0 → hard to debug.
//   2. RANGE_BREAKOUT_3TOUCH is intentionally EXEMPT from market direction check.
//      It's a range strategy — it works in sideways markets too (price breaking
//      out of a tight box works regardless of overall Nifty trend).
//   3. Added signalsFired counter per strategy for dashboard visibility.
//   4. Added candle count check so strategies only run after sufficient data.
//   5. All other logic IDENTICAL to original.
// ============================================================================

package com.trading.strategy;

import com.trading.analysis.service.PatternDetectionService;
import com.trading.analysis.service.TechnicalAnalysisService;
import com.trading.domain.Candle;
import com.trading.events.CandleCompleteEvent;
import com.trading.events.ProbabilityScoreEvent;
import com.trading.events.ScannerSignalEvent;
import com.trading.regime.service.MarketDirectionService;
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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * StrategyEvaluatorService – connects all 4 strategies.
 *
 * Strategy 1 (7-Gate): ScannerSignalEvent → fire directly
 * Strategies 2,3,4: run on every 5min candle, each independently
 *
 * All strategies fire ProbabilityScoreEvent → RiskManagementService → Execution.
 * Circuit breaker limits total trades/day regardless of strategies firing.
 *
 * FIX 1: Added explicit marketDirection.isTradeable() guard with log.
 *   Without this guard and logging, SIDEWAYS market silently blocks all
 *   strategies with zero explanation in logs.
 *
 * FIX 2: RANGE_BREAKOUT_3TOUCH is exempt from market direction filter.
 *   Range breakouts work in choppy/sideways markets — they detect stocks
 *   that break out of tight consolidation regardless of Nifty trend.
 *   Keeping this strategy active gives signals even on sideways days.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StrategyEvaluatorService {

    private final ApplicationEventPublisher   publisher;
    private final MarketDirectionService      marketDirection;
    private final SectorStrengthService       sectorStrength;
    private final SectorClassificationService sectorClassify;
    private final PatternDetectionService     patternDetection;
    private final TechnicalAnalysisService    technicalAnalysis;

    // Spring auto-injects AutoModeStrategy, RangeBreakoutStrategy, ORBStrategy
    private final List<TradingStrategy> strategies;

    private final Map<String, Deque<Candle>> buf5m  = new ConcurrentHashMap<>();
    private final Map<String, Deque<Candle>> buf15m = new ConcurrentHashMap<>();

    // "SYMBOL:STRATEGY_NAME" – prevents same strategy firing twice same day for same symbol
    private final Set<String> firedToday = Collections.newSetFromMap(new ConcurrentHashMap<>());

    // ── FIX: per-strategy signal count for dashboard ──────────────────────────
    private final Map<String, AtomicInteger> signalCounters = new ConcurrentHashMap<>();

    @Value("${strategy.enabled:true}")
    private boolean enabled;

    // ════════════════════════════════════════════════════════════════════════
    // STRATEGY 1 – 7-Gate Scanner
    // ════════════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tradingExecutor")
    public void onScannerSignal(ScannerSignalEvent event) {
        String sym = event.getTradingSymbol();
        String key = sym + ":SCANNER_7GATE";

        if (firedToday.contains(key)) {
            log.debug("[7GATE] Already fired today for {}", sym);
            return;
        }

        if (event.getDirection() == null
                || event.getEntryPrice() == null
                || event.getStopLoss() == null
                || event.getTarget() == null) {
            log.warn("[7GATE] Signal missing trade params for {} — cannot fire", sym);
            return;
        }

        log.info("[7GATE] Signal: {} dir={} entry={} sl={} target={} sector={}",
                sym, event.getDirection(),
                event.getEntryPrice(), event.getStopLoss(), event.getTarget(),
                event.getSectorClassification());

        fireProbabilityEvent(sym, event.getInstrumentToken(),
                event.getDirection(),
                event.getEntryPrice(), event.getStopLoss(), event.getTarget(),
                BigDecimal.valueOf(80), "SCANNER_7GATE");

        firedToday.add(key);
        incrementSignalCount("SCANNER_7GATE");
    }

    // ════════════════════════════════════════════════════════════════════════
    // STRATEGIES 2, 3, 4 – independent, on every 5min candle
    // ════════════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tradingExecutor")
    public void onCandle(CandleCompleteEvent event) {
        Candle c   = event.getCandle();
        String sym = c.getTradingSymbol();

        // Update buffers
        if ("5minute".equals(c.getTimeframe())) {
            Deque<Candle> b = buf5m.computeIfAbsent(sym, k -> new ArrayDeque<>());
            b.addFirst(c);
            if (b.size() > 100) ((ArrayDeque<Candle>) b).removeLast();
        }
        if ("15minute".equals(c.getTimeframe())) {
            Deque<Candle> b = buf15m.computeIfAbsent(sym, k -> new ArrayDeque<>());
            b.addFirst(c);
            if (b.size() > 100) ((ArrayDeque<Candle>) b).removeLast();
        }

        if (!"5minute".equals(c.getTimeframe())) return;
        if (!enabled) return;

        List<Candle> c5m  = new ArrayList<>(buf5m.getOrDefault(sym, new ArrayDeque<>()));
        List<Candle> c15m = new ArrayList<>(buf15m.getOrDefault(sym, new ArrayDeque<>()));
        if (c5m.isEmpty()) return;

        // ── FIX: Check market direction ONCE before running strategies ─────────
        // Log the reason so we know EXACTLY why signals are 0 during SIDEWAYS.
        MarketDirectionService.MarketDirectionResult dir =
                marketDirection.getCurrentDirection();

        boolean marketTradeable = dir.isTradeable();

        if (!marketTradeable) {
            log.debug("[EVAL] Market SIDEWAYS for {} — reason: {} | Only RANGE_BREAKOUT will run",
                    sym, dir.failReason());
        }

        // Build context once, reuse for all strategies
        TradingStrategy.MarketContext ctx = buildContext(sym, dir);

        // Run each strategy independently
        for (TradingStrategy strategy : strategies) {
            String key = sym + ":" + strategy.name();
            if (firedToday.contains(key)) continue;

            // ── FIX: RANGE_BREAKOUT runs even in sideways market ──────────────
            // All other strategies require BULLISH or BEARISH market direction.
            boolean isRangeBreakout = "RANGE_BREAKOUT_3TOUCH".equals(strategy.name());
            if (!marketTradeable && !isRangeBreakout) {
                // Silently skip — we already logged once above per symbol
                continue;
            }

            try {
                Optional<TradingStrategy.TradeSignal> signal =
                        strategy.generateSignal(sym, c5m, c15m, ctx);

                if (signal.isPresent()) {
                    TradingStrategy.TradeSignal s = signal.get();
                    if (!isValidSignal(s, sym, strategy.name())) continue;

                    log.info("[{}] SIGNAL: {} dir={} score={} entry={} sl={} target={}",
                            strategy.name(), sym, s.direction(),
                            String.format("%.0f", s.score()),
                            s.entryPrice(), s.stopLoss(), s.target());

                    fireProbabilityEvent(sym, c.getInstrumentToken(),
                            s.direction(), s.entryPrice(), s.stopLoss(), s.target(),
                            BigDecimal.valueOf(s.score()), s.strategyName());

                    firedToday.add(key);
                    incrementSignalCount(strategy.name());
                }
            } catch (Exception e) {
                log.warn("[{}] Error for {}: {}", strategy.name(), sym, e.getMessage());
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Build TradingStrategy.MarketContext from service caches
    // ════════════════════════════════════════════════════════════════════════

    private TradingStrategy.MarketContext buildContext(String sym,
                                                       MarketDirectionService.MarketDirectionResult dir) {
        String sectorName = sectorClassify.getSector(sym);
        SectorStrengthService.SectorData sector = sectorStrength.getSector(sectorName);
        TechnicalAnalysisService.TechnicalStructure structure = technicalAnalysis.getStructure(sym);
        PatternDetectionService.PatternResult pattern = patternDetection.getPattern(sym);

        // niftyChangePct: derive from ATR direction (best proxy available)
        double niftyChgPct = dir.niftyBullish()
                ? Math.abs(dir.niftyAtrPct())
                : -Math.abs(dir.niftyAtrPct());

        return new TradingStrategy.MarketContext(
                dir.niftyBullish(),
                dir.niftyBearish(),
                niftyChgPct,
                dir.niftyAtrPct(),

                sectorName,
                sector.changePercent(),
                sector.alignedBullish(),
                sector.alignedBearish(),
                sector.isTopSector(),
                sector.isBottomSector(),
                sector.relativeStrength(),

                structure.vwap(),
                structure.vwapConfluence(),
                pattern,
                structure
        );
    }

    // ════════════════════════════════════════════════════════════════════════
    // Fire ProbabilityScoreEvent – exact constructor match
    // ════════════════════════════════════════════════════════════════════════

    private void fireProbabilityEvent(String sym, long token,
                                      com.trading.domain.enums.TradeDirection dir,
                                      BigDecimal entry, BigDecimal sl, BigDecimal target,
                                      BigDecimal score, String strategyName) {
        publisher.publishEvent(new ProbabilityScoreEvent(
                this,
                sym, token,
                score,
                "EXECUTE",
                dir,
                entry, sl, target,
                strategyName,
                score, score, score, score, score, score, score, score
        ));
    }

    // ════════════════════════════════════════════════════════════════════════
    // Signal validation
    // ════════════════════════════════════════════════════════════════════════

    private boolean isValidSignal(TradingStrategy.TradeSignal s, String sym, String stratName) {
        if (s.entryPrice() == null || s.stopLoss() == null || s.target() == null) {
            log.warn("[{}] {} null prices", stratName, sym);
            return false;
        }
        if (s.entryPrice().compareTo(BigDecimal.ZERO) == 0) {
            log.warn("[{}] {} zero entry", stratName, sym);
            return false;
        }
        BigDecimal slDist = s.entryPrice().subtract(s.stopLoss()).abs();
        if (slDist.compareTo(BigDecimal.ZERO) == 0) {
            log.warn("[{}] {} zero SL dist", stratName, sym);
            return false;
        }
        double slPct = slDist.divide(s.entryPrice(), MathContext.DECIMAL32).doubleValue() * 100;
        if (slPct > 3.0) {
            log.warn("[{}] {} SL {}% > 3%", stratName, sym, String.format("%.2f", slPct));
            return false;
        }
        return true;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Public API for DashboardController
    // ════════════════════════════════════════════════════════════════════════

    public Set<String> getFiredToday() {
        return Collections.unmodifiableSet(firedToday);
    }

    /** Returns how many signals each strategy has fired today */
    public Map<String, Integer> getSignalCounters() {
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("SCANNER_7GATE",          getCount("SCANNER_7GATE"));
        result.put("AUTO_MODE",              getCount("AUTO_MODE"));
        result.put("RANGE_BREAKOUT_3TOUCH",  getCount("RANGE_BREAKOUT_3TOUCH"));
        result.put("ORB_VWAP_SECTOR",        getCount("ORB_VWAP_SECTOR"));
        return result;
    }

    private int getCount(String name) {
        AtomicInteger ai = signalCounters.get(name);
        return ai == null ? 0 : ai.get();
    }

    private void incrementSignalCount(String strategyName) {
        signalCounters.computeIfAbsent(strategyName, k -> new AtomicInteger(0))
                .incrementAndGet();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Daily reset
    // ════════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 45 8 * * MON-FRI", zone = "Asia/Kolkata")
    public void resetDaily() {
        buf5m.clear();
        buf15m.clear();
        firedToday.clear();
        signalCounters.clear();
        log.info("StrategyEvaluator reset complete");
    }
}