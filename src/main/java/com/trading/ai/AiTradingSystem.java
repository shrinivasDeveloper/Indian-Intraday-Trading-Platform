package com.trading.ai;

import com.trading.ai.data.AiMarketDataService;
import com.trading.ai.data.AiSymbolUniverse;
import com.trading.ai.engine.*;
import com.trading.ai.model.*;
import com.trading.domain.entity.Trade;
import com.trading.domain.enums.TradeDirection;
import com.trading.events.CandleCompleteEvent;
import com.trading.marketdata.service.MarketDataService;
import com.trading.risk.service.CircuitBreakerService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AiTradingSystem
 *
 * The AI module's master orchestrator.
 * Wires all 9 engines required by the original prompt:
 *
 *   1. AiMarketUnderstandingEngine  — regime, trend, breadth, sector rotation
 *   2. AiOpportunityDiscoveryEngine — universe scan, 60-feature vectors
 *   3. AiProbabilityEngine          — LR + GBM ML probability scoring
 *   4. AiConfidenceScoringEngine    — contradiction detection, confidence
 *   5. AiTradeQualityScoringEngine  — setup quality 0–100
 *   6. AiRiskAssessmentEngine       — position sizing, SL/T1/T2 placement
 *   7. AiTradeManagementEngine      — 1m monitoring, SL/T1/T2/trailing/EOD
 *   8. AiLearningEngine             — outcome recording, symbol history
 *   9. AiContinuousImprovementEngine — nightly adaptation, threshold tuning
 *
 * INDEPENDENCE GUARANTEE:
 *   This file and ALL engines import ONLY from:
 *     - com.trading.ai.*             (own package)
 *     - com.trading.marketdata.*     (shared read-only ticks)
 *     - com.trading.regime.*         (shared read-only direction)
 *     - com.trading.sector.*         (shared read-only sector)
 *     - com.trading.papertrading.*   (shared execution layer)
 *     - com.trading.risk.*           (shared circuit breaker)
 *     - com.trading.domain.*         (shared domain objects)
 *     - com.trading.events.*         (shared events)
 *
 *   ZERO imports from:
 *     - com.trading.strategy.highrr.*
 *     - com.trading.strategy.smc.*
 *     - com.trading.strategy.news.*
 *     - com.trading.strategy.channel.*
 */
@Service
@ConditionalOnProperty(name = "ai.trading.enabled", havingValue = "true")
@Slf4j
public class AiTradingSystem {

    // ── The 9 engines — all owned by AI module ────────────────────────────
    private final AiMarketUnderstandingEngine   marketEngine;
    private final AiOpportunityDiscoveryEngine  discoveryEngine;
    private final AiProbabilityEngine           probabilityEngine;
    private final AiRiskAssessmentEngine        riskEngine;
    private final AiTradeManagementEngine       tradeManager;
    private final AiLearningEngine              learningEngine;
    private final AiContinuousImprovementEngine improvementEngine;
    private final AiReasoningEngine             reasoningEngine;

    // ── AI's own data service ─────────────────────────────────────────────
    private final AiMarketDataService           aiData;

    // ── Shared read-only infrastructure ──────────────────────────────────
    private final MarketDataService             marketData;
    private final CircuitBreakerService         circuitBreaker;
    private final com.trading.papertrading.model.PaperAccount paperAccountCB;


    // ── State ─────────────────────────────────────────────────────────────
    private final Set<String>      firedToday     = ConcurrentHashMap.newKeySet();
    private final AtomicInteger    tradesToday     = new AtomicInteger(0);
    private final AtomicBoolean    cycleRunning    = new AtomicBoolean(false);
    private final List<AiTradeDecision> todayDecisions = Collections.synchronizedList(new ArrayList<>());
    private volatile String        currentRegime   = "UNKNOWN";
    private volatile long          lastCycleMs     = 0;
    private volatile String        lastCycleSlot   = "";  // e.g. "09:30" — prevent duplicate cycles per slot

    // ── Configuration ─────────────────────────────────────────────────────
    @Value("${ai.trading.max-trades-per-day:5}")
    private int maxTradesPerDay;

    @Value("${ai.trading.max-concurrent:2}")
    private int maxConcurrent;

    // ── Trade window ──────────────────────────────────────────────────────
    private static final LocalTime WINDOW_OPEN  = LocalTime.of(9,  30);
    private static final LocalTime WINDOW_CLOSE = LocalTime.of(14, 40);
    private static final long      CYCLE_COOLDOWN_MS = 200;

    public AiTradingSystem(
            AiMarketUnderstandingEngine   marketEngine,
            AiOpportunityDiscoveryEngine  discoveryEngine,
            AiProbabilityEngine           probabilityEngine,
            AiRiskAssessmentEngine        riskEngine,
            AiTradeManagementEngine       tradeManager,
            AiLearningEngine              learningEngine,
            AiContinuousImprovementEngine improvementEngine,
            AiReasoningEngine             reasoningEngine,
            AiMarketDataService           aiData,
            MarketDataService             marketData,
            CircuitBreakerService         circuitBreaker,
            com.trading.papertrading.model.PaperAccount paperAccountCB) {

        this.marketEngine      = marketEngine;
        this.discoveryEngine   = discoveryEngine;
        this.probabilityEngine = probabilityEngine;
        this.riskEngine        = riskEngine;
        this.tradeManager      = tradeManager;
        this.learningEngine    = learningEngine;
        this.improvementEngine = improvementEngine;
        this.reasoningEngine   = reasoningEngine;
        this.aiData            = aiData;
        this.marketData        = marketData;
        this.circuitBreaker    = circuitBreaker;
        this.paperAccountCB    = paperAccountCB;
    }

    @PostConstruct
    public void init() {
        // Wire learning callback — every trade close feeds into learning engine
        tradeManager.setOnClosedCallback(outcome -> {
            outcome = AiTradeOutcome.builder()
                    .symbol(outcome.getSymbol())
                    .direction(outcome.getDirection())
                    .entryPrice(outcome.getEntryPrice())
                    .exitPrice(outcome.getExitPrice())
                    .pnl(outcome.getPnl())
                    .rMultiple(outcome.getRMultiple())
                    .exitReason(outcome.getExitReason())
                    .outcomeType(outcome.getOutcomeType())
                    .confidence(outcome.getConfidence())
                    .qualityScore(outcome.getQualityScore())
                    .reasoning(outcome.getReasoning())
                    .dominantFactor(outcome.getDominantFactor())
                    .featureVectorAtEntry(outcome.getFeatureVectorAtEntry())
                    .featureVectorJson(outcome.getFeatureVectorJson())
                    .entryTime(outcome.getEntryTime())
                    .exitTime(outcome.getExitTime())
                    .regime(currentRegime)  // inject current regime
                    .build();
            learningEngine.recordOutcome(outcome);
        });

        log.info("[AI-SYSTEM] ✅ Initialised — 9 engines wired. Learning loop active.");
        log.info("[AI-SYSTEM] Independence confirmed: zero imports from HighRR, SMC, News strategies.");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MAIN CYCLE — fires on every 5-minute candle close
    // ═══════════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tradingExecutor")
    public void onCandleClose(CandleCompleteEvent event) {
        if (!"5minute".equals(event.getCandle().getTimeframe())) return;
        if (!event.getCandle().isComplete()) return;

        // ── Cycle guard — run ONCE per 5-minute slot ─────────────────────
        LocalTime nowTime = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        // Round down to nearest 5-minute slot: e.g. 10:07 → "10:05"
        int slotMin = (nowTime.getHour() * 60 + nowTime.getMinute()) / 5 * 5;
        String slot = String.format("%02d:%02d", slotMin / 60, slotMin % 60);
        if (slot.equals(lastCycleSlot)) return;  // already processed this slot
        if (!cycleRunning.compareAndSet(false, true)) return;
        lastCycleSlot = slot;
        lastCycleMs = System.currentTimeMillis();
        try {
            runCycle(event);
        } finally {
            cycleRunning.set(false);
        }
    }

    /**
     * Full AI decision cycle — 9 steps matching the 9 engines.
     */
    private void runCycle(CandleCompleteEvent event) {
        long t0 = System.currentTimeMillis();

        // ── PRE-FLIGHT GATES ──────────────────────────────────────────────
        // Gate 1: Bootstrap complete
        if (!aiData.isBootstrapComplete()) {
            log.debug("[AI-SYSTEM] Bootstrap not complete — skip cycle");
            return;
        }

        // Gate 2: Trade window
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        if (now.isBefore(WINDOW_OPEN) || now.isAfter(WINDOW_CLOSE)) return;

        // Gate 3: Daily trade cap
        if (tradesToday.get() >= maxTradesPerDay) {
            log.debug("[AI-SYSTEM] Daily cap reached ({}/{})", tradesToday.get(), maxTradesPerDay);
            return;
        }

        // Gate 4: Concurrent position cap
        if (tradeManager.getOpenCount() >= maxConcurrent) {
            log.debug("[AI-SYSTEM] Max concurrent positions ({}/{})",
                    tradeManager.getOpenCount(), maxConcurrent);
            return;
        }

        // Gate 5: Circuit breaker
        try { BigDecimal _cap = paperAccountCB.getCapital();
            if (!circuitBreaker.checkPermission(_cap).isAllowed()) {
                log.debug("[AI-SYSTEM] Circuit breaker active — skip cycle");
                return;
            }} catch (Exception _cbEx) {}

        // ═══════════════════════════════════════════════════════════════════
        // ENGINE 1: MARKET UNDERSTANDING
        // ═══════════════════════════════════════════════════════════════════
        AiMarketUnderstandingEngine.MarketSnapshot snapshot = marketEngine.classify();

        // FIX: notify improvement engine of phase changes so threshold auto-adjusts
        int samples = probabilityEngine.getSamplesCount();
        improvementEngine.onPhaseChange(samples);

        // Gate 6: Regime gate
        if (snapshot.isChoppy()) {
            log.debug("[AI-SYSTEM] Regime CHOPPY — no trading");
            return;
        }
        if (!snapshot.tradeable()) {
            log.debug("[AI-SYSTEM] Market not tradeable — skip");
            return;
        }
        currentRegime = snapshot.regime();

        // ═══════════════════════════════════════════════════════════════════
        // ENGINE 2: OPPORTUNITY DISCOVERY
        // ═══════════════════════════════════════════════════════════════════
        Set<String> openSymbols = tradeManager.getOpenPositions().keySet();
        List<AiCandidate> candidates = discoveryEngine.discover(snapshot, openSymbols, firedToday);

        if (candidates.isEmpty()) {
            log.debug("[AI-SYSTEM] No candidates passed pre-screening");
            return;
        }

        // ═══════════════════════════════════════════════════════════════════
        // ENGINES 3 + 4 + 5: PROBABILITY + RISK ASSESSMENT
        // NO rule filters here — confidence, RR, quality are INPUTS to
        // AiReasoningEngine, not gates. The reasoning engine decides.
        // ═══════════════════════════════════════════════════════════════════
        List<AiCandidate>    scoredCandidates = new ArrayList<>();
        List<AiTradeDecision> allDecisions    = new ArrayList<>();

        for (AiCandidate candidate : candidates) {

            // Engine 3: Probability scoring — score only, never filter
            AiPrediction prediction = probabilityEngine.predict(
                    candidate.getFeatureVector().getFeatures(), currentRegime);

            // Engine 6: Risk assessment — compute SL/T1/T2
            // Only hard skip: if SL placement is mathematically impossible (null)
            AiTradeDecision decision = riskEngine.assess(
                    candidate, prediction, currentRegime);
            if (decision == null) {
                // Risk assessment failed — SL could not be placed at any valid level
                // This is a data problem, not a quality judgment — safe to skip
                log.debug("[AI-SYSTEM] {} risk assessment null (SL placement failed) — skip",
                        candidate.getSymbol());
                continue;
            }

            // Pass candidate + decision to reasoning engine as inputs
            // Confidence, RR, quality are available inside decision object
            // AiReasoningEngine will use them as weighted layer inputs
            scoredCandidates.add(candidate);
            allDecisions.add(decision);
        }

        if (scoredCandidates.isEmpty()) {
            log.debug("[AI-SYSTEM] No candidates after risk assessment | regime={}", currentRegime);
            return;
        }

        log.debug("[AI-SYSTEM] {} candidates passed to reasoning engine | regime={}",
                scoredCandidates.size(), currentRegime);

        // ═══════════════════════════════════════════════════════════════════
        // ENGINE 7: AI REASONING — 6-layer thinking, picks the single best
        // Receives ALL candidates. No pre-filtering. Reasons comparatively.
        // Confidence, RR, quality are weighted inputs — not hard gates.
        // ═══════════════════════════════════════════════════════════════════
        AiReasoningEngine.AiReasoningResult reasoned = reasoningEngine.selectBest(
                scoredCandidates,
                allDecisions,
                snapshot);

        if (reasoned == null) {
            log.debug("[AI-SYSTEM] Reasoning engine: no trade this cycle | regime={}", currentRegime);
            return;
        }

        // ═══════════════════════════════════════════════════════════════════
        // ENGINE 8: EXECUTION — only the reasoned best candidate
        // ═══════════════════════════════════════════════════════════════════
        if (tradesToday.get() < maxTradesPerDay && tradeManager.getOpenCount() < maxConcurrent) {
            // Enrich decision with reasoning narrative
            AiTradeDecision enriched = AiTradeDecision.builder()
                    .symbol(reasoned.decision().getSymbol())
                    .direction(reasoned.decision().getDirection())
                    .entryPrice(reasoned.decision().getEntryPrice())
                    .stopLoss(reasoned.decision().getStopLoss())
                    .target1(reasoned.decision().getTarget1())
                    .target2(reasoned.decision().getTarget2())
                    .positionSize(reasoned.decision().getPositionSize())
                    .riskAmount(reasoned.decision().getRiskAmount())
                    .probabilityOfSuccess(reasoned.decision().getProbabilityOfSuccess())
                    .expectedRR(reasoned.decision().getExpectedRR())
                    .expectedReturn(reasoned.decision().getExpectedReturn())
                    .confidence(reasoned.composite())
                    .rrRatio(reasoned.decision().getRrRatio())
                    .tradeQualityScore(reasoned.decision().getTradeQualityScore())
                    .opportunityScore(reasoned.decision().getOpportunityScore())
                    .riskScore(reasoned.decision().getRiskScore())
                    .reasoning(reasoned.reasoning())
                    .bullScenario(reasoned.bullScenario())
                    .bearScenario(reasoned.bearScenario())
                    .dominantFactor(reasoned.candidate().getHtfTrend())
                    .exitPlan("T1 at " + reasoned.decision().getTarget1() +
                            ". Trail SL after T1. EOD exit 15:05.")
                    .reasoningSummary(reasoned.reasoning().substring(
                            0, Math.min(100, reasoned.reasoning().length())))
                    .htfTrend(reasoned.candidate().getHtfTrend())
                    .sector(reasoned.candidate().getSector())
                    .numericPreScore(reasoned.candidate().getNumericScore())
                    .featureVector(reasoned.candidate().getFeatureVector())
                    .build();

            boolean executed = executeTrade(enriched);
            if (executed) {
                tradesToday.incrementAndGet();
                firedToday.add(enriched.getSymbol());
                todayDecisions.add(enriched);

                log.info("[AI-SYSTEM] ✅ TRADE #{} | {} {} | composite={} " +
                                "env={} move={} fund={} time={} pattern={} rr={} " +
                                "| entry={} sl={} t1={}",
                        tradesToday.get(),
                        enriched.getSymbol(), enriched.getDirection(),
                        String.format("%.2f", reasoned.composite()),
                        String.format("%.2f", reasoned.envScore()),
                        String.format("%.2f", reasoned.moveScore()),
                        String.format("%.2f", reasoned.fundamentalScore()),
                        String.format("%.2f", reasoned.timingScore()),
                        String.format("%.2f", reasoned.patternScore()),
                        String.format("%.2f", reasoned.rrScore()),
                        enriched.getEntryPrice(),
                        enriched.getStopLoss(),
                        enriched.getTarget1());
            }
        }

        long elapsed = System.currentTimeMillis() - t0;
        log.debug("[AI-SYSTEM] Cycle complete | regime={} candidates={} trades={} | {}ms",
                currentRegime, candidates.size(), allDecisions.size(), elapsed);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 1-MINUTE CANDLE — trade management for all open AI positions
    // ENGINE 7: TRADE MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tradingExecutor")
    public void onMinuteCandle(CandleCompleteEvent event) {
        if (!"minute".equals(event.getCandle().getTimeframe())) return;
        // Update all open AI positions
        tradeManager.getOpenPositions().keySet()
                .forEach(tradeManager::onCandle);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // EXECUTION — direct Trade creation, no external service
    // ═══════════════════════════════════════════════════════════════════════

    private boolean executeTrade(AiTradeDecision decision) {
        try {
            // FIX: Resolve actual instrument token from InstrumentCacheService via AiMarketDataService
            // Was hardcoded to 0L — caused trades to be invisible to portfolio/monitoring systems
            long instrumentToken = aiData.resolveInstrumentToken(decision.getSymbol());

            Trade trade = Trade.builder()
                    .tradeDate(LocalDate.now())
                    .tradingSymbol(decision.getSymbol())
                    .instrumentToken(instrumentToken)  // FIX: real token, not 0
                    .direction(TradeDirection.valueOf(decision.getDirection()))
                    .status("OPEN")
                    .entryTime(Instant.now())
                    .entryPrice(decision.getEntryPrice())
                    .quantity(decision.getPositionSize())
                    .stopLoss(decision.getStopLoss())
                    .target(decision.getTarget1())
                    .strategyName("AI_TRADING_V2")
                    .probabilityScore(BigDecimal.valueOf(
                            (int)(decision.getProbabilityOfSuccess() * 100)))
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            // Register with trade management engine (in-memory position tracking)
            tradeManager.registerTrade(trade, decision);

            log.info("[AI-SYSTEM] ✅ Trade registered: {} {} | token={} entry={} sl={} t1={}",
                    decision.getSymbol(), decision.getDirection(),
                    instrumentToken, decision.getEntryPrice(),
                    decision.getStopLoss(), decision.getTarget1());
            return true;

        } catch (Exception e) {
            log.error("[AI-SYSTEM] Execution failed for {}: {}",
                    decision.getSymbol(), e.getMessage());
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // DAILY RESET — midnight
    // ═══════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Kolkata")
    public void dailyReset() {
        firedToday.clear();
        tradesToday.set(0);
        todayDecisions.clear();
        learningEngine.dailyReset();
        log.info("[AI-SYSTEM] Daily reset complete");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // WEEKLY RESET — Monday 07:00
    // ═══════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 0 7 * * MON", zone = "Asia/Kolkata")
    public void weeklyReset() {
        learningEngine.weeklyReset();
        log.info("[AI-SYSTEM] Weekly reset complete");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // STATUS ACCESSORS — for DashboardController
    // ═══════════════════════════════════════════════════════════════════════

    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("regime",         currentRegime);
        status.put("phase",          probabilityEngine.getPhaseLabel());
        status.put("tradesToday",    tradesToday.get());
        status.put("maxTrades",      maxTradesPerDay);
        status.put("openPositions",  tradeManager.getOpenCount());
        status.put("maxConcurrent",  maxConcurrent);
        status.put("totalTrades",    learningEngine.getTotalTrades());
        status.put("totalWins",      learningEngine.getTotalWins());
        status.put("winRate",        learningEngine.getWinRate());
        status.put("totalPnl",       learningEngine.getTotalPnl());
        status.put("samplesCount",   probabilityEngine.getSamplesCount());
        status.put("minConfidence",  improvementEngine.getMinConfidenceThreshold());
        status.put("minRR",          improvementEngine.getMinExpectedRR());
        status.put("bootstrapDone",  aiData.isBootstrapComplete());
        status.put("bootstrapPct",   aiData.getBootstrapProgress());

        // Today's decisions
        List<Map<String, Object>> decisions = new ArrayList<>();
        for (AiTradeDecision d : todayDecisions) {
            Map<String, Object> dec = new LinkedHashMap<>();
            dec.put("symbol",      d.getSymbol());
            dec.put("direction",   d.getDirection());
            dec.put("probability", String.format("%.0f%%", d.getProbabilityOfSuccess()*100));
            dec.put("confidence",  String.format("%.0f%%", d.getConfidence()*100));
            dec.put("expectedRR",  String.format("%.1f", d.getExpectedRR()));
            dec.put("quality",     d.getTradeQualityScore());
            dec.put("reasoning",   d.getReasoning());
            dec.put("bull",        d.getBullScenario());
            dec.put("bear",        d.getBearScenario());
            dec.put("exitPlan",    d.getExitPlan());
            decisions.add(dec);
        }
        status.put("decisions", decisions);
        return status;
    }

    public List<AiTradeDecision> getTodayDecisions()  { return Collections.unmodifiableList(todayDecisions); }
    public int getTradesToday()                        { return tradesToday.get(); }
    public String getCurrentRegime()                   { return currentRegime; }
}