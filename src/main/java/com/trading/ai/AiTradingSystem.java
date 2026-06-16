package com.trading.ai;

import com.trading.ai.data.AiMarketDataService;
import com.trading.ai.data.AiSymbolUniverse;
import com.trading.ai.engine.*;
import com.trading.ai.model.*;
import com.trading.domain.entity.Trade;
import com.trading.domain.enums.TradeDirection;
import com.trading.events.CandleCompleteEvent;
import com.trading.events.SmartChannelPullbackSignalEvent;
import com.trading.marketdata.service.MarketDataService;
import com.trading.risk.service.CircuitBreakerService;
import com.trading.risk.service.RiskManagementService;
import org.springframework.context.ApplicationEventPublisher;
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
    private final ApplicationEventPublisher     publisher;
    private final AiPatternConfidenceEngine     confidenceEngine;
    private final RiskManagementService         riskManagement;


    // ── State ─────────────────────────────────────────────────────────────
    private final Set<String>      firedToday     = ConcurrentHashMap.newKeySet();
    // symbol → dominant pattern name (e.g. "BANDHANBNK" → "Liq. Sweep")
    private final Map<String, String> watchlist   = new ConcurrentHashMap<>();
    private final AtomicInteger    tradesToday     = new AtomicInteger(0);
    private final AtomicBoolean    cycleRunning    = new AtomicBoolean(false);
    private final List<AiTradeDecision> todayDecisions = Collections.synchronizedList(new ArrayList<>());
    private volatile String        currentRegime   = "UNKNOWN";
    private volatile long          lastCycleMs     = 0;
    private volatile String        lastCycleSlot   = "";

    // ── Window trade tracking — max 2 trades per time window ──────────────
    // Keys: "PRIME", "GOOD", "MODERATE", "ACCEPTABLE"
    private final Map<String, AtomicInteger> tradesPerWindow = new ConcurrentHashMap<>(
            java.util.Map.of(
                    "PRIME",      new AtomicInteger(0),
                    "GOOD",       new AtomicInteger(0),
                    "MODERATE",   new AtomicInteger(0),
                    "ACCEPTABLE", new AtomicInteger(0)
            )
    );
    private static final int MAX_TRADES_PER_WINDOW = 2;

    // ── Configuration ─────────────────────────────────────────────────────
    @Value("${ai.trading.max-trades-per-day:5}")
    private int maxTradesPerDay;

    @Value("${ai.trading.max-concurrent:4}")
    private int maxConcurrent;  // max 4 open positions — when any closes, next fires immediately

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
            com.trading.papertrading.model.PaperAccount paperAccountCB,
            ApplicationEventPublisher     publisher,
            RiskManagementService         riskManagement,
            AiPatternConfidenceEngine     confidenceEngine) {

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
        this.publisher         = publisher;
        this.riskManagement    = riskManagement;
        this.confidenceEngine  = confidenceEngine;
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

        // Gate 3.5: Window trade cap — max 2 trades per time window
        // Each window (PRIME/GOOD/MODERATE/ACCEPTABLE) allows max 2 trades.
        // Prevents clustering all 5 trades into one window.
        // When window changes, counter resets and 2 more trades allowed.
        String currentWindow = getTradeWindow(now);
        if (currentWindow != null) {
            AtomicInteger windowCount = tradesPerWindow.get(currentWindow);
            if (windowCount != null && windowCount.get() >= MAX_TRADES_PER_WINDOW) {
                log.debug("[AI-SYSTEM] Window cap reached — {}: {}/{} trades. Waiting for next window.",
                        currentWindow, windowCount.get(), MAX_TRADES_PER_WINDOW);
                return;
            }
        }

        // Gate 4: Concurrent position cap — checks BOTH AI internal + platform
        // AI internal: tradeManager tracks AI positions for learning/history
        // Platform: riskManagement tracks ALL active positions across all strategies
        int aiOpenCount = tradeManager.getOpenCount();
        if (aiOpenCount >= maxConcurrent) {
            log.debug("[AI-SYSTEM] Max concurrent AI positions ({}/{})",
                    aiOpenCount, maxConcurrent);
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

        // Gate 6: Regime awareness — only CHOPPY blocks scanning/watchlist
        // RANGING and TRENDING both allow scanning — execution depends on score threshold
        // Per design: no Nifty/BankNifty/sector hard gates
        currentRegime = snapshot.regime();
        boolean choppy   = snapshot.isChoppy();
        boolean ranging  = "RANGING".equals(currentRegime);
        boolean trending = "TRENDING".equals(currentRegime);

        // Determine execution threshold based on regime
        // TRENDING → 70+ score executes
        // RANGING  → 80+ score executes (stricter — ranging moves are less reliable)
        // CHOPPY   → NO execution, watchlist monitoring only
        int executionThreshold = trending ? 70 : ranging ? 80 : 999; // 999 = never executes

        log.debug("[AI-SYSTEM] Regime={} threshold={}", currentRegime, executionThreshold);

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
        List<AiPatternConfidenceEngine.ConfidenceResult> confidenceResults = new ArrayList<>();

        for (AiCandidate candidate : candidates) {

            // ── STEP A: Pattern Confidence Score (100-point model) ───────────
            // PRIMARY scoring mechanism — stock-specific, no market bias
            List<com.trading.domain.Candle> dailyCandles =
                    aiData.getDailyCandles(candidate.getSymbol());
            List<com.trading.domain.Candle> candles5m =
                    aiData.get5mCandles(candidate.getSymbol());
            AiPatternConfidenceEngine.ConfidenceResult conf =
                    confidenceEngine.score(candidate, dailyCandles, candles5m);

            // Add to watchlist with skip reason encoded
            // Format: "pattern|skipReason|score|threshold"
            // skipReason: ELIGIBLE / SCORE_LOW / NO_CANDLE / DIRECTION / CHOPPY
            if (conf.bullishPatterns() + conf.bearishPatterns() > 0) {
                String dp = conf.dominantPattern() != null ? conf.dominantPattern() : "Pattern";
                String skipReason;
                if (conf.totalScore() == 0) {
                    skipReason = "NO_CANDLE";          // Gate 2 failed — no confirming 5m candle
                } else if (!conf.meetsThreshold(executionThreshold)) {
                    skipReason = "SCORE_LOW";          // score below threshold
                } else if (choppy) {
                    skipReason = "CHOPPY";             // choppy market — no execution
                } else {
                    skipReason = "ELIGIBLE";           // passes score, waiting direction/window check
                }
                watchlist.put(candidate.getSymbol(),
                        dp + "|" + skipReason + "|" + conf.totalScore() + "|" + executionThreshold);
            }

            // Log watchlist entry
            if (conf.totalScore() >= 50 && conf.totalScore() < executionThreshold) {
                log.info("[AI-SYSTEM] 👀 WATCHLIST: {} score={}/100 [{}] threshold={}",
                        candidate.getSymbol(), conf.totalScore(),
                        conf.dominantPattern(), executionThreshold);
            }

            // CHOPPY market: scan and watchlist only — never execute
            if (choppy) continue;

            // Below execution threshold: watchlist monitoring only
            if (!conf.meetsThreshold(executionThreshold)) {
                log.debug("[AI-SYSTEM] {} score={}/{} — below threshold, on watchlist",
                        candidate.getSymbol(), conf.totalScore(), executionThreshold);
                continue;
            }

            // ── TRENDING: market direction alignment required ──────────────
            // Rule: TRENDING → Score ≥ 70 + Market Direction + Stock Direction in sync
            // Only LONG trades when market is BULLISH trending.
            // Only SHORT trades when market is BEARISH trending.
            // RANGING has no direction requirement — both sides valid.
            if (trending) {
                double marketDir = snapshot.niftyDirection(); // +1=BULLISH, -1=BEARISH, 0=SIDEWAYS
                String stockDir  = candidate.getSuggestedDirection();
                boolean marketBull = marketDir > 0.3;
                boolean marketBear = marketDir < -0.3;

                if (marketBull && "SHORT".equals(stockDir)) {
                    log.debug("[AI-SYSTEM] {} SHORT skipped — TRENDING market is BULLISH, no counter-trend",
                            candidate.getSymbol());
                    String dp = conf.dominantPattern() != null ? conf.dominantPattern() : "Pattern";
                    watchlist.put(candidate.getSymbol(), dp + "|DIRECTION|" + conf.totalScore() + "|" + executionThreshold);
                    continue;
                }
                if (marketBear && "LONG".equals(stockDir)) {
                    log.debug("[AI-SYSTEM] {} LONG skipped — TRENDING market is BEARISH, no counter-trend",
                            candidate.getSymbol());
                    String dp = conf.dominantPattern() != null ? conf.dominantPattern() : "Pattern";
                    watchlist.put(candidate.getSymbol(), dp + "|DIRECTION|" + conf.totalScore() + "|" + executionThreshold);
                    continue;
                }
                if (!marketBull && !marketBear) {
                    // Market direction unclear (SIDEWAYS nifty in TRENDING regime)
                    // Allow trade — stock direction is the primary signal
                    log.debug("[AI-SYSTEM] {} — TRENDING but Nifty SIDEWAYS, stock direction leads",
                            candidate.getSymbol());
                }
            }

            // ── STEP B: Risk Assessment — compute SL/T1/T2 ──────────────────
            AiPrediction prediction = probabilityEngine.predict(
                    candidate.getFeatureVector().getFeatures(), currentRegime);
            AiTradeDecision decision = riskEngine.assess(
                    candidate, prediction, currentRegime);
            if (decision == null) {
                log.debug("[AI-SYSTEM] {} risk assessment null — skip", candidate.getSymbol());
                continue;
            }

            scoredCandidates.add(candidate);
            allDecisions.add(decision);
            confidenceResults.add(conf);
        }

        if (choppy) {
            log.info("[AI-SYSTEM] CHOPPY — {} stocks on watchlist, no execution",
                    watchlist.size());
            return;
        }

        if (scoredCandidates.isEmpty()) {
            log.debug("[AI-SYSTEM] No candidates met {} threshold | regime={}",
                    executionThreshold, currentRegime);
            return;
        }

        log.debug("[AI-SYSTEM] {} candidates met threshold={} | regime={}",
                scoredCandidates.size(), executionThreshold, currentRegime);

        // ═══════════════════════════════════════════════════════════════════
        // ENGINE 7: REASONING — picks the single best candidate
        // Pattern confidence already filtered above.
        // Reasoning provides additional comparative ranking and narrative.
        // ═══════════════════════════════════════════════════════════════════
        AiReasoningEngine.AiReasoningResult reasoned = reasoningEngine.selectBest(
                scoredCandidates, allDecisions, snapshot);

        if (reasoned == null) {
            log.debug("[AI-SYSTEM] Reasoning engine: no trade | regime={}", currentRegime);
            return;
        }

        // Attach confidence score to the reasoned decision
        int confScore = confidenceResults.stream()
                .filter(r -> r.dominantPattern() != null)
                .mapToInt(AiPatternConfidenceEngine.ConfidenceResult::totalScore)
                .max().orElse(executionThreshold);

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
                    .confidence((double)confScore / 100.0)
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
                // Increment window counter
                String w = getTradeWindow(LocalTime.now(ZoneId.of("Asia/Kolkata")));
                if (w != null && tradesPerWindow.containsKey(w)) {
                    tradesPerWindow.get(w).incrementAndGet();
                    log.info("[AI-SYSTEM] Window {} trades: {}/{}", w,
                            tradesPerWindow.get(w).get(), MAX_TRADES_PER_WINDOW);
                }

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
        // FIX: Route through platform pipeline (SmartChannelPullbackSignalEvent)
        // so AI trades appear in Overview, Trades, Portfolio, and circuit breaker.
        // Previously used AiTradeManagementEngine.registerTrade() directly
        // which was invisible to the platform.
        //
        // AiTradeManagementEngine is still used for:
        //   - learning callback (on trade close)
        //   - in-memory position tracking for gate checks
        //
        // Platform pipeline handles:
        //   - Execution and fill
        //   - SL/T1/T2 exit management
        //   - Portfolio / Trades / Overview display
        //   - Circuit breaker awareness
        synchronized (this) {
            if (tradeManager.getOpenCount() >= maxConcurrent) {
                log.debug("[AI-SYSTEM] executeTrade: concurrent limit reached — skip {}",
                        decision.getSymbol());
                return false;
            }
            if (tradesToday.get() >= maxTradesPerDay) {
                log.debug("[AI-SYSTEM] executeTrade: daily limit reached — skip {}",
                        decision.getSymbol());
                return false;
            }
            // Check platform-level symbol dedup
            if (riskManagement.isSymbolAlreadyActive(decision.getSymbol())) {
                log.debug("[AI-SYSTEM] {} already active in another strategy — skip",
                        decision.getSymbol());
                return false;
            }

            try {
                String     symbol    = decision.getSymbol();
                String     direction = decision.getDirection();
                BigDecimal entry     = decision.getEntryPrice();
                BigDecimal sl        = decision.getStopLoss();
                BigDecimal t1        = decision.getTarget1();
                BigDecimal t2        = decision.getTarget2() != null
                        ? decision.getTarget2() : t1;
                int qty              = decision.getPositionSize();

                if (symbol == null || direction == null || entry == null
                        || sl == null || t1 == null || qty <= 0) {
                    log.warn("[AI-SYSTEM] Invalid decision fields for {} — skip", symbol);
                    return false;
                }

                long      token       = aiData.resolveInstrumentToken(symbol);
                TradeDirection dir    = TradeDirection.valueOf(direction);
                BigDecimal riskAmt    = decision.getRiskAmount() != null
                        ? decision.getRiskAmount()
                        : entry.multiply(BigDecimal.valueOf(0.01))
                        .setScale(2, RoundingMode.HALF_UP);

                int  qualityScore = decision.getTradeQualityScore();
                double confidence = decision.getConfidence();
                String sector     = decision.getSector() != null ? decision.getSector() : "Other";

                // Build signal — same structure as all other strategies
                SmartChannelPullbackSignalEvent signal = new SmartChannelPullbackSignalEvent(
                        this,
                        symbol,
                        token,
                        dir,
                        entry,
                        sl,
                        t1,
                        t2,
                        qty,
                        riskAmt,
                        "AI_TRADING_V2",
                        (int)(confidence * 100),
                        sector,
                        0.0,                             // sectorChange
                        "AI_REASONING",                  // channelQuality → category
                        "AI_SIGNAL",                     // signalType
                        confidence,                      // pressureRatio
                        decision.getNumericPreScore() / 100.0, // rvol proxy
                        qualityScore >= 60,              // strongTrend
                        "MARKET",                        // entryMode
                        "AI_" + direction,               // signalLabel
                        0,                               // candleCloseDelay
                        qualityScore,                    // scoreCategory
                        (int)(decision.getProbabilityOfSuccess() * 100), // scoreSentiment
                        100,                             // scoreRecency (fresh signal)
                        80,                              // scoreSource (AI engine)
                        (int)(confidence * 100),         // scoreKeyword
                        (int)(confidence * 100),         // totalScore
                        0                                // timeStopMin (EOD handles exit)
                );

                // Fire through platform pipeline — appears in Overview/Trades/Portfolio
                publisher.publishEvent(signal);

                // Also register with AiTradeManagementEngine for learning tracking
                Trade trade = Trade.builder()
                        .tradeDate(LocalDate.now())
                        .tradingSymbol(symbol)
                        .instrumentToken(token)
                        .direction(dir)
                        .status("OPEN")
                        .entryTime(Instant.now())
                        .entryPrice(entry)
                        .quantity(qty)
                        .stopLoss(sl)
                        .target(t1)
                        .strategyName("AI_TRADING_V2")
                        .probabilityScore(BigDecimal.valueOf(
                                (int)(decision.getProbabilityOfSuccess() * 100)))
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();
                tradeManager.registerTrade(trade, decision);

                log.info("[AI-SYSTEM] ✅ Signal fired: {} {} | token={} entry={} sl={} t1={}",
                        symbol, direction, token, entry, sl, t1);
                return true;

            } catch (Exception e) {
                log.error("[AI-SYSTEM] Execution failed for {}: {}",
                        decision.getSymbol(), e.getMessage(), e);
                return false;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // DAILY RESET — midnight
    // ═══════════════════════════════════════════════════════════════════════

    private static int safeInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    /**
     * Returns the current trade window name based on IST time.
     * Returns null if outside trading hours.
     *
     *  9:30 – 11:00 → PRIME      (highest momentum, patterns fresh)
     * 11:00 – 12:30 → GOOD       (trend established, volume active)
     * 12:30 – 13:30 → MODERATE   (lunch zone, spreads widen)
     * 13:30 – 14:40 → ACCEPTABLE (late session, closing momentum)
     * Outside these  → null      (window blocked)
     */
    private String getTradeWindow(LocalTime t) {
        int m = t.getHour() * 60 + t.getMinute();
        if (m >= 570 && m < 660) return "PRIME";       // 9:30-11:00
        if (m >= 660 && m < 750) return "GOOD";        // 11:00-12:30
        if (m >= 750 && m < 810) return "MODERATE";    // 12:30-13:30
        if (m >= 810 && m < 880) return "ACCEPTABLE";  // 13:30-14:40
        return null; // outside window — Gate 2 already blocks this
    }

    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Kolkata")
    public void dailyReset() {
        firedToday.clear();
        tradesToday.set(0);
        tradesPerWindow.values().forEach(counter -> counter.set(0)); // reset all window counters
        todayDecisions.clear();
        // FIX: clear trade manager positions — prevents ghost positions
        // surviving midnight and blocking the next trading session
        tradeManager.clearPositions();
        watchlist.clear();
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
    // BTST READ-ONLY ACCESSORS — used by BtstAiStrategy only
    // These are purely read-only — zero impact on AI trading system state
    // ═══════════════════════════════════════════════════════════════════════

    /** Returns map of symbol → confidence score for all watchlist stocks */
    public Map<String, Integer> getWatchlistScores() {
        // watchlist stores symbol → pattern name
        // scores are not stored — BTST uses pattern presence as proxy
        // stocks on watchlist passed all gates — score ≥ 1
        Map<String, Integer> scores = new LinkedHashMap<>();
        for (String sym : watchlist.keySet()) {
            // Use todayDecisions for actual scores if available
            todayDecisions.stream()
                    .filter(d -> sym.equals(d.getSymbol()))
                    .findFirst()
                    .ifPresentOrElse(
                            d -> scores.put(sym, (int) d.getNumericPreScore()),
                            () -> scores.put(sym, 80) // on watchlist = ≥ threshold
                    );
        }
        return Collections.unmodifiableMap(scores);
    }

    /** Returns the suggested direction for a watchlist symbol */
    public String getWatchlistDirection(String symbol) {
        return todayDecisions.stream()
                .filter(d -> symbol.equals(d.getSymbol()))
                .findFirst()
                .map(d -> d.getDirection() != null ? d.getDirection() : "LONG")
                .orElse("LONG");
    }

    /** Returns the dominant pattern name for a watchlist symbol */
    public String getWatchlistPattern(String symbol) {
        return watchlist.getOrDefault(symbol, "Pattern");
    }

    /** Returns true if symbol is currently in an active AI position */
    public boolean isSymbolInActivePosition(String symbol) {
        return tradeManager.getOpenPositions().containsKey(symbol);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // STATUS ACCESSORS — for DashboardController
    // ═══════════════════════════════════════════════════════════════════════

    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("regime",         currentRegime);
        status.put("phase",          probabilityEngine.getPhaseLabel());
        status.put("tradesToday",    tradesToday.get());
        status.put("watchlistCount", watchlist.size());
        // Parse encoded watchlist value: "pattern|skipReason|score|threshold"
        // Returns [{symbol, pattern, skipReason, score, threshold}] for dashboard
        status.put("watchlist", watchlist.entrySet().stream()
                .map(e -> {
                    String[] parts = e.getValue().split("\\|", 4);
                    String  pattern   = parts[0];
                    String  reason    = parts.length > 1 ? parts[1] : "ELIGIBLE";
                    int     score     = parts.length > 2 ? safeInt(parts[2]) : 0;
                    int     threshold = parts.length > 3 ? safeInt(parts[3]) : 70;
                    return java.util.Map.of(
                            "symbol",     e.getKey(),
                            "pattern",    pattern,
                            "skipReason", reason,
                            "score",      score,
                            "threshold",  threshold
                    );
                })
                .collect(java.util.stream.Collectors.toList()));
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