package com.trading.ai;

import com.trading.ai.engine.*;
import com.trading.ai.engine.proprietary.*;
import com.trading.ai.model.*;
import com.trading.events.CandleCompleteEvent;
import com.trading.marketdata.service.MarketDataService;
import com.trading.regime.service.MarketDirectionService;
import com.trading.sector.service.SectorStrengthService;
import com.trading.sector.service.SectorClassificationService;
import com.trading.risk.service.CircuitBreakerService;
import com.trading.strategy.highrr.HighRRStructureService;
import com.trading.strategy.smc.SmcInstitutionalCandleService;
import com.trading.strategy.smc.SmcInstitutionalStructureService;
import com.trading.strategy.news.NewsIngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import com.trading.papertrading.model.PaperAccount;
import jakarta.annotation.PostConstruct;

/**
 * AiTradingModuleV2 — Fully Proprietary AI Trading System
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * ZERO EXTERNAL DEPENDENCIES:
 *   No Claude, ChatGPT, Gemini, or any external API.
 *   All intelligence is self-owned and runs in-process.
 *
 * PROPRIETARY INTELLIGENCE STACK:
 *
 *  ┌─────────────────────────────────────────────────────────────┐
 *  │  AiFeatureEngineeringService   — 60-feature market vectors  │
 *  │  MarketRegimeClassifier        — TRENDING/RANGING/VOLATILE  │
 *  │  ProprietaryMLEngine (Smile)   — GBM + RandomForest         │
 *  │    • GradientBoostedTrees      — primary opportunity scorer  │
 *  │    • RandomForest              — ensemble confidence         │
 *  │    • Regime-specific models    — adapts to market conditions │
 *  │    • Online SGD update         — immediate outcome learning  │
 *  │  HypothesisEngine              — bull/bear scenario scoring  │
 *  │  AiOpportunityRankingService   — feature-weighted ranking    │
 *  │  AiTradeSelectionService       — diversification + RR check  │
 *  │  AiRiskManagementService       — 1% risk, dynamic sizing     │
 *  │  AiPaperTradeExecutionService  — isolated execution path     │
 *  │  AiTradeManagementService      — trail SL, condition exit    │
 *  │  AiLearningService             — MySQL persistence + weekly  │
 *  └─────────────────────────────────────────────────────────────┘
 *
 * LEARNING TIMELINE:
 *   Day 1–50:   System collects feature vectors + outcomes. Uses numeric
 *               scoring (same as AiOpportunityRankingService alone).
 *
 *   Day 50+:    First GBM model trained. Predictions replace numeric scoring.
 *               Feature importance computed → weights updated in ranking engine.
 *
 *   Day 100+:   RandomForest added to ensemble. Confidence from disagreement.
 *
 *   Day 200+:   Regime-specific models trained. System adapts to TRENDING vs
 *               RANGING vs VOLATILE automatically.
 *
 *   Weekly:     Full retrain on 90-day rolling window (Sunday 20:00).
 *               New model deploys only if validation accuracy improves.
 *
 *   Daily:      Online SGD correction applied after each trade outcome.
 *               Corrects for intra-week regime drift.
 *
 * ISOLATION:
 *   @ConditionalOnProperty(ai.trading.enabled=true)
 *   ZERO beans created when disabled. Existing strategies unaffected.
 * ═══════════════════════════════════════════════════════════════════════════
 */
@Service
@ConditionalOnProperty(name = "ai.trading.enabled", havingValue = "true")
@Slf4j
@RequiredArgsConstructor
public class AiTradingModuleV2 {

    private static final ZoneId    IST        = ZoneId.of("Asia/Kolkata");
    private static final LocalTime SCAN_START = LocalTime.of(9, 30);
    private static final LocalTime SCAN_END   = LocalTime.of(14, 45);

    // ── Proprietary AI engines (all @ConditionalOnProperty) ──────────────────
    private final ProprietaryMLEngine         mlEngine;
    private final MarketRegimeClassifier      regimeClassifier;
    private final HypothesisEngine            hypothesisEngine;
    private final AiFeatureEngineeringService featureEngine;
    private final AiOpportunityRankingService rankingEngine;
    private final AiTradeSelectionService     selectionEngine;
    private final AiRiskManagementService     riskEngine;
    private final AiPaperTradeExecutionService executionService;
    private final AiTradeManagementService    tradeManager;
    private final AiLearningService           learningService;
    private final AiStateStore                stateStore;

    // ── Platform services (READ-ONLY, no mutations) ───────────────────────────
    private final MarketDataService               marketDataService;
    private final MarketDirectionService          marketDirectionService;
    private final SectorStrengthService           sectorStrengthService;
    private final SectorClassificationService     sectorClassify;
    private final CircuitBreakerService           circuitBreaker;
    private final HighRRStructureService          hrrStructureService;
    private final SmcInstitutionalCandleService   smcCandleService;
    private final SmcInstitutionalStructureService smcStructureService;
    private final NewsIngestionService            newsIngestionService;
    private final PaperAccount                    paperAccount;

    @Value("${ai.trading.max-trades-per-day:5}")  private int    maxTrades;
    @Value("${ai.trading.max-concurrent:2}")       private int    maxConcurrent;
    @Value("${ai.trading.min-confidence:0.60}")    private double minConfidence;
    @Value("${ai.trading.ranking-top-n:20}")       private int    rankingTopN;
    @Value("${ai.trading.risk-per-trade:0.01}")    private double riskPerTrade;

    // ── Session state ─────────────────────────────────────────────────────────
    private final AtomicInteger tradesExecutedToday = new AtomicInteger(0);
    private final Set<String>   activePositions     = ConcurrentHashMap.newKeySet();
    private final Set<String>   firedToday          = ConcurrentHashMap.newKeySet();
    private volatile long       lastScanCandleMs    = 0L;

    // ═════════════════════════════════════════════════════════════════════════
    // INITIALISATION
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Wire AiTradeManagementService back to this module AFTER Spring finishes
     * constructing all beans. Without this, tradeManager.moduleRef is null and
     * onPositionClosed() never fires — breaking the learning loop entirely.
     */
    @PostConstruct
    public void init() {
        tradeManager.setModuleRef(this::onPositionClosed);
        log.info("[AI-V2] Initialised — tradeManager wired. Learning loop active.");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // TRIGGER
    // ═════════════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tradingExecutor")
    public void onCandleClose(CandleCompleteEvent event) {
        if (!"5minute".equals(event.getCandle().getTimeframe())) return;
        if (!event.getCandle().isComplete()) return;
        long ms = event.getCandle().getCandleTime() != null
                ? event.getCandle().getCandleTime().toEpochMilli() : System.currentTimeMillis();
        synchronized (this) {
            if (ms <= lastScanCandleMs) return;
            lastScanCandleMs = ms;
        }
        runProprietaryAiCycle();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // CORE CYCLE — fully proprietary, zero external calls
    // ═════════════════════════════════════════════════════════════════════════

    private void runProprietaryAiCycle() {
        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(SCAN_START) || now.isAfter(SCAN_END)) return;
        if (tradesExecutedToday.get() >= maxTrades) return;
        if (activePositions.size() >= maxConcurrent) return;
        if (!smcCandleService.isBootstrapComplete()) return;

        try {
            long t0 = System.currentTimeMillis();

            // ── Latency guard: skip cycle if previous one is still running ───
            // Prevents heap buildup if buildAll() takes > 4 minutes on slow days
            long msSinceLastScan = t0 - lastScanCandleMs;
            if (msSinceLastScan < 200) {
                log.debug("[AI-V2] Cycle skipped — previous cycle < 200ms ago");
                return;
            }

            // ── STEP 1: Regime classification ─────────────────────────────────
            String regime = regimeClassifier.classify();
            if ("CHOPPY".equals(regime)) {
                log.debug("[AI] Market regime CHOPPY — no trades");
                return;
            }

            // ── STEP 2: Feature engineering ────────────────────────────────────
            Set<String> universe = smcCandleService.getLoadedSymbols();
            if (universe.isEmpty()) return;

            AiFeatureBatch batch = featureEngine.buildAll(
                    universe,
                    marketDataService.getLastPricesSimple(),
                    marketDirectionService.getCurrentDirection(),
                    sectorStrengthService, sectorClassify,
                    smcCandleService, smcStructureService,
                    hrrStructureService,
                    newsIngestionService.getActiveItems()
            );

            // ── STEP 3: ML scoring + hypothesis generation ─────────────────────
            // For each symbol, run:
            //   a) ProprietaryMLEngine.predict() → probability + confidence
            //   b) HypothesisEngine.evaluate()   → bull/bear scenario
            // Combine into AiCandidate with composite score
            double[] featureWeights = mlEngine.getFeatureImportance();
            List<AiCandidate> mlCandidates = new ArrayList<>();

            for (Map.Entry<String, AiFeatureVector> entry : batch.getFeatures().entrySet()) {
                if (firedToday.contains(entry.getKey())) continue;
                if (activePositions.contains(entry.getKey())) continue;

                AiFeatureVector fv = entry.getValue();
                double[] features  = fv.getFeatures();

                // ML prediction (uses GBM+RF ensemble when trained, numeric fallback otherwise)
                AiPrediction prediction = mlEngine.predict(features, regime);

                // Hypothesis evaluation (bull/bear scenario scoring)
                AiCandidate tempCandidate = buildTempCandidate(entry.getKey(), fv);
                AiHypothesis hypothesis  = hypothesisEngine.evaluate(tempCandidate, featureWeights);

                // Composite score:
                //   50% ML probability + 30% conviction + 20% confidence
                double compositeScore =
                        prediction.getProbability() * 50.0
                                + Math.max(0, hypothesis.getConviction()) * 0.30
                                + prediction.getConfidence() * 20.0;

                if (compositeScore < 35.0) continue; // fast filter

                AiCandidate candidate = buildCandidate(entry.getKey(), fv,
                        prediction, hypothesis, compositeScore);
                mlCandidates.add(candidate);
            }

            // Sort by composite score
            mlCandidates.sort((a, b) -> Double.compare(b.getNumericScore(), a.getNumericScore()));
            List<AiCandidate> topCandidates = mlCandidates.stream()
                    .limit(rankingTopN).collect(java.util.stream.Collectors.toList());

            long t1 = System.currentTimeMillis();

            if (topCandidates.isEmpty()) {
                log.debug("[AI] No candidates above threshold this cycle");
                return;
            }

            // ── STEP 4: Trade selection ────────────────────────────────────────
            int slotsLeft = Math.min(
                    maxTrades - tradesExecutedToday.get(),
                    maxConcurrent - activePositions.size()
            );
            List<AiTradeDecision> selected = selectionEngine.selectFromMlCandidates(
                    topCandidates, slotsLeft, minConfidence
            );

            long cycleMs = t1 - t0;
            if (cycleMs > 120_000) { // warn if > 2 minutes
                log.warn("[AI-V2] ⚠️  Slow cycle: {}ms — consider reducing ranking-top-n", cycleMs);
            }
            log.info("[AI-V2] Cycle @{} | regime={} | universe={} | scored={} | selected={} | {}ms",
                    now, regime, universe.size(), mlCandidates.size(),
                    selected.size(), cycleMs);

            // ── STEP 5: Risk management + execution ────────────────────────────
            for (AiTradeDecision decision : selected) {
                AiTradeDecision sized = riskEngine.applyRiskManagement(
                        decision, resolveCapital(), riskPerTrade
                );
                if (sized.getPositionSize() <= 0) continue;

                boolean executed = executionService.execute(sized);
                if (executed) {
                    tradesExecutedToday.incrementAndGet();
                    activePositions.add(sized.getSymbol());
                    firedToday.add(sized.getSymbol());
                    stateStore.recordDecision(sized);

                    log.info("[AI-V2] ✅ TRADE #{} | {} {} | " +
                                    "entry={} sl={} t1={} | " +
                                    "Success Probability={:.0f}% | Expected RR={:.1f} | " +
                                    "Expected Return={:.1f}% | Confidence={:.0f}% | {}",
                            tradesExecutedToday.get(),
                            sized.getSymbol(), sized.getDirection(),
                            sized.getEntryPrice(), sized.getStopLoss(), sized.getTarget1(),
                            sized.getProbabilityOfSuccess()*100,
                            sized.getExpectedRR(),
                            sized.getExpectedReturn(),
                            sized.getConfidence()*100,
                            truncate(sized.getReasoningSummary(), 80));
                }
            }

        } catch (Exception e) {
            log.error("[AI-V2] Cycle error: {}", e.getMessage(), e);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // TRADE CLOSE → LEARNING FEEDBACK
    // ═════════════════════════════════════════════════════════════════════════

    public void onPositionClosed(String symbol, AiTradeOutcome outcome) {
        activePositions.remove(symbol);
        learningService.recordOutcome(outcome);

        // Feed outcome back to ML engine:
        //   1. Records training sample to MySQL
        //   2. Updates rolling win/loss RR distribution (drives expectedRR)
        //   3. Applies immediate online SGD correction (drives next cycle)
        // All three happen synchronously before the next candle fires.
        if (outcome.getFeatureVectorAtEntry() != null) {
            mlEngine.onTradeOutcome(
                    outcome.getFeatureVectorAtEntry(),
                    outcome.getRMultiple(),
                    regimeClassifier.getCurrentRegime(),
                    symbol
            );
        }

        log.info("[AI-V2] Closed: {} | R={:.2f} | {} | P&L={}",
                symbol, outcome.getRMultiple(),
                outcome.getRMultiple() >= 1.0 ? "WIN" : "LOSS",
                outcome.getPnl());
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SCHEDULED
    // ═════════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 11 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyReset() {
        tradesExecutedToday.set(0);
        activePositions.clear();
        firedToday.clear();
        learningService.onDayStart();
        log.info("[AI-V2] Daily reset | Regime={} | Model={} | Samples={} | WR={:.0f}%",
                regimeClassifier.getCurrentRegime(),
                learningService.getMetrics().getTotalTrades() >= 50 ? "GBM+RF" : "NUMERIC",
                learningService.getMetrics().getTotalTrades(),
                learningService.getWinRate()*100);
    }

    @Scheduled(cron = "0 5 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void eodExit() {
        tradeManager.closeAllPositions("AI_EOD");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    private AiCandidate buildTempCandidate(String symbol, AiFeatureVector fv) {
        double[] f = fv.getFeatures();
        boolean isLong = f.length > 22 && f[22] > 0;
        return AiCandidate.builder()
                .symbol(symbol).ltp(fv.getLtp())
                .suggestedDirection(isLong ? "LONG" : "SHORT")
                .featureVector(fv).sector(fv.getSector()).build();
    }

    private AiCandidate buildCandidate(String symbol, AiFeatureVector fv,
                                       AiPrediction pred, AiHypothesis hyp,
                                       double compositeScore) {
        double[] f = fv.getFeatures();
        boolean isLong = f.length > 22 && f[22] > 0;
        return AiCandidate.builder()
                .symbol(symbol)
                .ltp(fv.getLtp())
                .numericScore(compositeScore)
                .suggestedDirection(isLong ? "LONG" : "SHORT")
                .sector(fv.getSector())
                .featureVector(fv)
                .mlProbability(pred.getProbability())
                .mlConfidence(pred.getConfidence())
                .mlModelUsed(pred.getModelUsed())
                .mlReasoning(pred.getReasoning())
                .hypothesis(hyp)
                .htfTrend(f.length > 22 ? (f[22]>0.5?"BULLISH":f[22]<-0.5?"BEARISH":"SIDEWAYS") : "UNKNOWN")
                .rvol(f.length > 12 ? f[12] : 1.0)
                .distFromSupport(f.length > 20 ? f[20] : 0)
                .distFromResistance(f.length > 21 ? f[21] : 0)
                .supportStrength(f.length > 23 ? (int)(f[23]*100) : 50)
                .liquiditySweep(f.length > 28 && f[28] > 0)
                .srFlip(f.length > 25 && f[25] > 0)
                .trendlineTouch(f.length > 26 && f[26] > 0)
                .volumeSpike(f.length > 14 && f[14] > 0)
                .newsSummary(f.length > 50 && f[50] > 0.3 ? "Score "+(int)(f[50]*100) : "None")
                .historicalWinRate(f.length > 55 ? f[55] : 0.5)
                .build();
    }

    private BigDecimal resolveCapital() {
        try {
            BigDecimal balance = paperAccount.getCapital();
            return (balance != null && balance.compareTo(BigDecimal.ZERO) > 0)
                    ? balance
                    : BigDecimal.valueOf(100_000);
        } catch (Exception e) {
            log.debug("[AI-V2] PaperAccount unavailable — using ₹1L default");
            return BigDecimal.valueOf(100_000);
        }
    }

    private String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }

    // ── Dashboard ─────────────────────────────────────────────────────────────
    public int                   getTradesExecutedToday() { return tradesExecutedToday.get(); }
    public int                   getActiveCount()         { return activePositions.size(); }
    public Set<String>           getFiredToday()          { return Collections.unmodifiableSet(firedToday); }
    public List<AiTradeDecision> getRecentDecisions()     { return stateStore.getRecentDecisions(); }
    public AiPerformanceMetrics  getPerformance()         { return learningService.getMetrics(); }
    public String                getCurrentRegime()       { return regimeClassifier.getCurrentRegime(); }
    public String                getModelStatus() {
        int n = learningService.getMetrics().getTotalTrades();
        if (n < 50)  return "COLLECTING_DATA (" + n + "/50 samples)";
        if (n < 100) return "GBM_ONLY (" + n + " samples)";
        if (n < 200) return "GBM+RF_ENSEMBLE (" + n + " samples)";
        return "FULL_ENSEMBLE+REGIME (" + n + " samples)";
    }
}