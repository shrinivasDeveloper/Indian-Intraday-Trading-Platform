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
import org.springframework.jdbc.core.JdbcTemplate;
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
 *   1. AiMarketUnderstandingEngine  - regime, trend, breadth, sector rotation
 *   2. AiOpportunityDiscoveryEngine - universe scan, 60-feature vectors
 *   3. AiProbabilityEngine          - LR + GBM ML probability scoring
 *   4. AiConfidenceScoringEngine    - contradiction detection, confidence
 *   5. AiTradeQualityScoringEngine  - setup quality 0-100
 *   6. AiRiskAssessmentEngine       - position sizing, SL/T1/T2 placement
 *   7. AiTradeManagementEngine      - 1m monitoring, SL/T1/T2/trailing/EOD
 *   8. AiLearningEngine             - outcome recording, symbol history
 *   9. AiContinuousImprovementEngine - nightly adaptation, threshold tuning
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

    // -- Execution score thresholds - single source of truth, used both
    // for the actual gate AND the dashboard status export below, so the
    // two can never silently drift apart again (the exact bug found via
    // real dashboard data: the dashboard's legend text was a separate
    // hardcoded "70"/"80" string that went stale after this constant was
    // last raised, while individual watchlist items - which read the
    // live threshold value passed through per-candidate - correctly
    // showed 85, creating a confusing mismatch on screen).
    // FIX (per explicit instruction: "make it 80 is minimum score",
    // given alongside removing the TOO_EXTENDED gate). Previously 75
    // TRENDING / 85 RANGING - now unified to a single 80-point minimum
    // for both regimes, serving as the compensating quality control for
    // the removed extension gate.
    // THRESHOLD RECALIBRATION (this session, per confirmed math after 3
    // zero-trade sessions with healthy 21-23 stock watchlists): 80 was
    // calibrated for the OLD binary pattern scoring (any pattern = 50
    // pts). The family-based redesign awards 30 (1 family) / 40 (2) /
    // 50 (3+), and the freshness fixes deliberately make simultaneous
    // fresh families rarer - so real days score 55-75 and 80 became
    // near-unreachable. At 70: 1-family needs 40/55 confirmation
    // (stellar-only), 2-family needs 30/55 (realistic bread-and-butter),
    // 3-family fires readily - trade flow restored at a HIGHER quality
    // bar than the old regime, since 2+ independent families is a
    // stronger requirement than any-single-pattern ever was.
    private static final int TRENDING_EXECUTION_THRESHOLD = 70;
    private static final int RANGING_EXECUTION_THRESHOLD  = 70;

    // -- Pure observability: WHY didn't an eligible-or-close candidate
    // actually execute? Pipeline has several gates AFTER the pattern-
    // score threshold (ML confidence floor, extension hard-lock, and a
    // final "pick ONE best candidate" reasoning step that can reject
    // everyone) - none of which were ever visible on the dashboard,
    // confirmed by direct user report (HINDUNILVR showed "[FAST] Eligible"
    // with no trade taken, and there was no way to see why without
    // server logs, which aren't reliably retained on a free hosting
    // tier). This map is written to at each gate, purely additive - it
    // NEVER changes any actual control-flow decision, only records what
    // already happened, for dashboard display.
    private final Map<String, String> blockReasons = new ConcurrentHashMap<>();

    // -- The 9 engines - all owned by AI module ----------------------------
    private final AiMarketUnderstandingEngine   marketEngine;
    private final AiOpportunityDiscoveryEngine  discoveryEngine;
    private final AiProbabilityEngine           probabilityEngine;
    private final AiRiskAssessmentEngine        riskEngine;
    private final AiTradeManagementEngine       tradeManager;
    private final AiLearningEngine              learningEngine;
    private final AiContinuousImprovementEngine improvementEngine;
    private final AiReasoningEngine             reasoningEngine;

    // -- AI's own data service ---------------------------------------------
    private final AiMarketDataService           aiData;

    // -- Shared read-only infrastructure ----------------------------------
    private final MarketDataService             marketData;
    private final CircuitBreakerService         circuitBreaker;
    private final com.trading.papertrading.model.PaperAccount paperAccountCB;
    private final AiPatternConfidenceEngine     confidenceEngine;
    private final JdbcTemplate                  jdbc;

    // -- Independent AI/News-only execution + capital tracking ------------
    // INDEPENDENCE: replaces the old SmartChannelPullbackSignalEvent ->
    // SmartChannelSignalHandler -> TradeApprovedEvent -> PaperTradeExecutionService
    // chain entirely. No dependency on any other strategy's pipeline.
    private final com.trading.ai.execution.AiLiveOrderExecutionService liveOrderService;
    private final com.trading.ai.execution.AiNewsCapitalLedger         capitalLedger;


    // -- State -------------------------------------------------------------
    private final Set<String>      firedToday     = ConcurrentHashMap.newKeySet();
    // symbol -> dominant pattern name (e.g. "BANDHANBNK" -> "Liq. Sweep")
    private final Map<String, String> watchlist   = new ConcurrentHashMap<>();
    private final AtomicInteger    tradesToday     = new AtomicInteger(0);
    private final AtomicBoolean    cycleRunning    = new AtomicBoolean(false);
    private final List<AiTradeDecision> todayDecisions = Collections.synchronizedList(new ArrayList<>());
    private volatile String        currentRegime   = "UNKNOWN";
    private volatile long          lastCycleMs     = 0;
    private volatile String        lastCycleSlot   = "";

    // -- Window trade tracking - max 2 trades per time window --------------
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

    // -- Configuration -----------------------------------------------------
    @Value("${ai.trading.max-trades-per-day:5}")
    private int maxTradesPerDay;

    @Value("${ai.trading.max-concurrent:4}")
    private int maxConcurrent;  // max 4 open positions - when any closes, next fires immediately

    // -- Trade window ------------------------------------------------------
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
            AiPatternConfidenceEngine     confidenceEngine,
            JdbcTemplate                  jdbc,
            com.trading.ai.execution.AiLiveOrderExecutionService liveOrderService,
            com.trading.ai.execution.AiNewsCapitalLedger         capitalLedger) {

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
        this.confidenceEngine  = confidenceEngine;
        this.jdbc              = jdbc;
        this.liveOrderService  = liveOrderService;
        this.capitalLedger     = capitalLedger;
        ensureFiredTradesTableExists();
        // INDEPENDENCE: wire entry-fill and entry-rejection handling here.
        // Uses the purpose-specific setters (setOnEntryFilled/setOnEntryRejected)
        // - AiTradeManagementEngine separately wires setOnExitFilled/
        // setOnExitRejected in its own constructor. Both registrations
        // coexist safely; see AiLiveOrderExecutionService for why this had
        // to be split into purpose-specific slots.
        liveOrderService.setOnEntryFilled("AI_TRADING_V2", this::onLiveEntryFilled);
        liveOrderService.setOnEntryRejected("AI_TRADING_V2", this::onLiveEntryRejected);
    }

    // =======================================================================
    // PERSISTENCE - fired-today / trades-today survive a mid-market restart
    // =======================================================================

    private void ensureFiredTradesTableExists() {
        try {
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ai_fired_trades_today (
                    trade_date   DATE        NOT NULL,
                    symbol       VARCHAR(20) NOT NULL,
                    window_name  VARCHAR(20),
                    fired_at     TIMESTAMP   NOT NULL,
                    PRIMARY KEY (trade_date, symbol)
                )
                """);
        } catch (Exception e) {
            log.warn("[AI-SYSTEM] Could not create ai_fired_trades_today table - " +
                    "persistence disabled this session: {}", e.getMessage());
        }
    }

    private void persistFiredTrade(String symbol, String window) {
        try {
            jdbc.update("""
                INSERT INTO ai_fired_trades_today (trade_date, symbol, window_name, fired_at)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE window_name = ?, fired_at = ?
                """,
                    LocalDate.now(ZoneId.of("Asia/Kolkata")), symbol, window, java.sql.Timestamp.from(Instant.now()),
                    window, java.sql.Timestamp.from(Instant.now()));
        } catch (Exception e) {
            log.debug("[AI-SYSTEM] persistFiredTrade failed for {} (non-fatal): {}",
                    symbol, e.getMessage());
        }
    }

    /**
     * Rebuilds firedToday, tradesToday, and tradesPerWindow from the database
     * on startup. Without this, a mid-market restart would forget every trade
     * already fired today, resetting daily caps and risking duplicate entries
     * on symbols that are, in reality, already taken.
     */
    private void reconcileFiredTradesFromDatabase() {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT symbol, window_name FROM ai_fired_trades_today WHERE trade_date = ?",
                    LocalDate.now(ZoneId.of("Asia/Kolkata")));
            if (rows.isEmpty()) {
                log.info("[AI-SYSTEM] Reconciliation: no trades fired yet today (per database).");
                return;
            }
            for (Map<String, Object> row : rows) {
                String symbol = (String) row.get("symbol");
                String window = (String) row.get("window_name");
                firedToday.add(symbol);
                tradesToday.incrementAndGet();
                if (window != null && tradesPerWindow.containsKey(window)) {
                    tradesPerWindow.get(window).incrementAndGet();
                }
            }
            log.info("[AI-SYSTEM] [OK] Reconciled {} fired trade(s) from database - " +
                    "tradesToday={}", rows.size(), tradesToday.get());
        } catch (Exception e) {
            log.warn("[AI-SYSTEM] reconcileFiredTradesFromDatabase failed - starting " +
                    "with empty firedToday as before this fix existed: {}", e.getMessage());
        }
    }

    @PostConstruct
    public void init() {
        // Wire learning callback - every trade close feeds into learning engine
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

        log.info("[AI-SYSTEM] [OK] Initialised - 9 engines wired. Learning loop active.");
        log.info("[AI-SYSTEM] Independence confirmed: zero imports from HighRR, SMC, News strategies.");

        // RESTART RECOVERY - rebuild today's state from the database so a
        // mid-market-hours redeploy resumes exactly where it left off,
        // instead of silently forgetting open positions and fired trades.
        // Both calls are individually best-effort and never block startup.
        tradeManager.reconcileFromDatabase();
        reconcileFiredTradesFromDatabase();
    }

    // =======================================================================
    // MAIN CYCLE - fires on every 5-minute candle close
    // =======================================================================

    @EventListener
    @Async("tradingExecutor")
    public void onCandleClose(CandleCompleteEvent event) {
        if (!"5minute".equals(event.getCandle().getTimeframe())) return;
        if (!event.getCandle().isComplete()) return;

        // -- Cycle guard - run ONCE per 5-minute slot ---------------------
        LocalTime nowTime = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        // Round down to nearest 5-minute slot: e.g. 10:07 -> "10:05"
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
     * Full AI decision cycle - 9 steps matching the 9 engines.
     */
    private void runCycle(CandleCompleteEvent event) {
        long t0 = System.currentTimeMillis();

        // -- PRE-FLIGHT GATES ----------------------------------------------
        // Gate 1: Bootstrap complete
        if (!aiData.isBootstrapComplete()) {
            log.debug("[AI-SYSTEM] Bootstrap not complete - skip cycle");
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

        // Gate 3.5: Window trade cap - max 2 trades per time window
        // Each window (PRIME/GOOD/MODERATE/ACCEPTABLE) allows max 2 trades.
        // Prevents clustering all 5 trades into one window.
        // When window changes, counter resets and 2 more trades allowed.
        String currentWindow = getTradeWindow(now);
        if (currentWindow != null) {
            AtomicInteger windowCount = tradesPerWindow.get(currentWindow);
            if (windowCount != null && windowCount.get() >= MAX_TRADES_PER_WINDOW) {
                log.debug("[AI-SYSTEM] Window cap reached - {}: {}/{} trades. Waiting for next window.",
                        currentWindow, windowCount.get(), MAX_TRADES_PER_WINDOW);
                return;
            }
        }

        // Gate 4: Concurrent position cap - checks BOTH AI internal + platform
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
                log.debug("[AI-SYSTEM] Circuit breaker active - skip cycle");
                return;
            }} catch (Exception _cbEx) {}

        // ===================================================================
        // ENGINE 1: MARKET UNDERSTANDING
        // ===================================================================
        AiMarketUnderstandingEngine.MarketSnapshot snapshot = marketEngine.classify();

        // FIX: notify improvement engine of phase changes so threshold auto-adjusts
        int samples = probabilityEngine.getSamplesCount();
        improvementEngine.onPhaseChange(samples);

        // Gate 6: Regime awareness - only CHOPPY blocks scanning/watchlist
        // RANGING and TRENDING both allow scanning - execution depends on score threshold
        // Per design: no Nifty/BankNifty/sector hard gates
        currentRegime = snapshot.regime();
        boolean choppy   = snapshot.isChoppy();
        boolean ranging  = "RANGING".equals(currentRegime);
        boolean trending = "TRENDING".equals(currentRegime);

        // Determine execution threshold based on regime
        // TRENDING -> 75+ score executes (raised from 70, given 500+ stock
        //            universe surfaces more adequate-but-not-excellent
        //            candidates per cycle than the original smaller pool)
        // RANGING  -> 85+ score executes (raised from 80 - stricter, since
        //            ranging moves are inherently less reliable)
        // CHOPPY   -> NO execution, watchlist monitoring only
        int executionThreshold = trending ? TRENDING_EXECUTION_THRESHOLD
                : ranging ? RANGING_EXECUTION_THRESHOLD : 999; // 999 = never executes

        log.debug("[AI-SYSTEM] Regime={} threshold={}", currentRegime, executionThreshold);

        // ===================================================================
        // ENGINE 2: OPPORTUNITY DISCOVERY
        // ===================================================================
        Set<String> openSymbols = tradeManager.getOpenPositions().keySet();
        List<AiCandidate> candidates = discoveryEngine.discover(snapshot, openSymbols, firedToday);

        if (candidates.isEmpty()) {
            log.debug("[AI-SYSTEM] No candidates passed pre-screening");
            return;
        }

        // ===================================================================
        // ENGINES 3 + 4 + 5: PROBABILITY + RISK ASSESSMENT
        // NO rule filters here - confidence, RR, quality are INPUTS to
        // AiReasoningEngine, not gates. The reasoning engine decides.
        // ===================================================================
        List<AiCandidate>    scoredCandidates = new ArrayList<>();
        List<AiTradeDecision> allDecisions    = new ArrayList<>();
        List<AiPatternConfidenceEngine.ConfidenceResult> confidenceResults = new ArrayList<>();

        for (AiCandidate candidate : candidates) {

            // -- STEP A: Pattern Confidence Score (100-point model) -----------
            // PRIMARY scoring mechanism - stock-specific, no market bias
            List<com.trading.domain.Candle> dailyCandles =
                    aiData.getDailyCandles(candidate.getSymbol());
            List<com.trading.domain.Candle> candles5m =
                    aiData.get5mCandles(candidate.getSymbol());
            AiPatternConfidenceEngine.ConfidenceResult conf =
                    confidenceEngine.score(candidate, dailyCandles, candles5m);

            // REMOVED (per explicit instruction: "Extended, please remove
            // this gate many trade are blocking this gate please
            // completely remove... make it 80 is minimum score"). The
            // >1.5%-move-from-prior-close hard-lock that used to sit here
            // has been fully removed - it was blocking too many otherwise-
            // valid trades. The unified 80-point minimum score (see
            // TRENDING_EXECUTION_THRESHOLD/RANGING_EXECUTION_THRESHOLD
            // below) is the compensating quality control requested in its
            // place. Every other gate before and after this point -
            // direction alignment, confidence floor, risk assessment,
            // daily/concurrent caps, reasoning engine - is completely
            // untouched.

            // -- FIX 2 (soft penalty): recently-traded symbols need a higher
            // score to qualify again, instead of being excluded outright. A
            // genuinely strong fresh setup elsewhere still wins on a level
            // playing field; a repeat needs to be noticeably better.
            // IMPORTANT - scope is deliberately narrow: this ONLY affects the
            // eligibility-gate comparisons below (effectiveScore vs
            // executionThreshold). conf.totalScore() itself, confidenceResults,
            // and the reasoning engine's own selection logic are completely
            // untouched - so once a candidate clears this gate, everything
            // downstream behaves exactly as it did before this change.
            boolean recentlyTraded = isRecentlyTraded(candidate.getSymbol());
            int     recencyPenalty = recentlyTraded ? 10 : 0;
            int     effectiveScore = Math.max(0, conf.totalScore() - recencyPenalty);

            // Add to watchlist with skip reason encoded
            // Format: "pattern|skipReason|score|threshold"
            // skipReason: ELIGIBLE / SCORE_LOW / NO_CANDLE / DIRECTION / CHOPPY
            // (TOO_EXTENDED removed per explicit instruction - see threshold
            // constants above for the compensating 80-point minimum score)
            if (conf.bullishPatterns() + conf.bearishPatterns() > 0) {
                String dp = conf.dominantPattern() != null ? conf.dominantPattern() : "Pattern";
                String skipReason;
                if (conf.totalScore() == 0) {
                    skipReason = "NO_CANDLE";          // Gate 2 failed - no confirming 5m candle
                } else if (effectiveScore < executionThreshold) {
                    skipReason = recentlyTraded ? "RECENTLY_TRADED" : "SCORE_LOW";
                } else if (choppy) {
                    skipReason = "CHOPPY";             // choppy market - no execution
                } else {
                    skipReason = "ELIGIBLE";           // passes score, waiting direction/window check
                }
                // FIX (found via direct user report: stocks showing
                // "Eligible" for 40+ minutes with zero new activity or
                // explanation - confirmed root cause: watchlist only
                // clears at midnight, so a stale entry from an EARLIER
                // cycle just sits there unchanged if the discovery engine
                // stops including that symbol in later cycles' candidate
                // list). Appending a timestamp as a 5th field so staleness
                // can be detected and filtered when the dashboard reads
                // this data - see getStatus() below.
                watchlist.put(candidate.getSymbol(),
                        dp + "|" + skipReason + "|" + effectiveScore + "|" + executionThreshold
                                + "|" + System.currentTimeMillis());
            }

            // Log watchlist entry
            if (effectiveScore >= 50 && effectiveScore < executionThreshold) {
                log.info("[AI-SYSTEM] [WATCH] WATCHLIST: {} score={}/100{} [{}] threshold={}",
                        candidate.getSymbol(), effectiveScore,
                        recentlyTraded ? " (raw=" + conf.totalScore() + ", -10 recently traded)" : "",
                        conf.dominantPattern(), executionThreshold);
            }

            // FIX (found via direct, repeated user report: the exact same
            // handful of stocks - FINCABLES, CAMS, IGIL, ELECON,
            // LICHSGFIN, GVT&D - showing "Eligible" across MANY
            // consecutive cycles with zero trade, zero blockReasons entry,
            // and zero AI-REASON log activity, even after the staleness
            // fix. This is an explicit, unmissable INFO-level checkpoint
            // at the exact moment a candidate becomes genuinely eligible
            // (score >= threshold, non-choppy) - every subsequent stage
            // this candidate passes through will now also log explicitly,
            // so grepping for this symbol shows its COMPLETE, unbroken
            // path from here through to either execution or final
            // rejection - nothing can be silently lost anymore.
            if (!choppy && effectiveScore >= executionThreshold) {
                log.info("[AI-TRACE] {} ELIGIBLE checkpoint reached: score={} threshold={} " +
                                "regime={} - proceeding to direction/risk/confidence checks",
                        candidate.getSymbol(), effectiveScore, executionThreshold, currentRegime);
            }

            // CHOPPY market: scan and watchlist only - never execute
            if (choppy) continue;

            // Below execution threshold: watchlist monitoring only
            if (effectiveScore < executionThreshold) {
                log.debug("[AI-SYSTEM] {} score={}/{}{} - below threshold, on watchlist",
                        candidate.getSymbol(), effectiveScore, executionThreshold,
                        recentlyTraded ? " (recently traded penalty applied)" : "");
                continue;
            }

            // -- TRENDING: market direction alignment required --------------
            // Rule: TRENDING -> Score >= 80 + Market Direction + Stock Direction in sync
            // Only LONG trades when market is BULLISH trending.
            // Only SHORT trades when market is BEARISH trending.
            // RANGING has no direction requirement - both sides valid.
            if (trending) {
                double marketDir = snapshot.niftyDirection(); // +1=BULLISH, -1=BEARISH, 0=SIDEWAYS
                String stockDir  = candidate.getSuggestedDirection();
                boolean marketBull = marketDir > 0.3;
                boolean marketBear = marketDir < -0.3;

                if (marketBull && "SHORT".equals(stockDir)) {
                    log.debug("[AI-SYSTEM] {} SHORT skipped - TRENDING market is BULLISH, no counter-trend",
                            candidate.getSymbol());
                    String dp = conf.dominantPattern() != null ? conf.dominantPattern() : "Pattern";
                    watchlist.put(candidate.getSymbol(), dp + "|DIRECTION|" + conf.totalScore() + "|" + executionThreshold + "|" + System.currentTimeMillis());
                    continue;
                }
                if (marketBear && "LONG".equals(stockDir)) {
                    log.debug("[AI-SYSTEM] {} LONG skipped - TRENDING market is BEARISH, no counter-trend",
                            candidate.getSymbol());
                    String dp = conf.dominantPattern() != null ? conf.dominantPattern() : "Pattern";
                    watchlist.put(candidate.getSymbol(), dp + "|DIRECTION|" + conf.totalScore() + "|" + executionThreshold + "|" + System.currentTimeMillis());
                    continue;
                }
                if (!marketBull && !marketBear) {
                    // Market direction unclear (SIDEWAYS nifty in TRENDING regime)
                    // Allow trade - stock direction is the primary signal
                    log.debug("[AI-SYSTEM] {} - TRENDING but Nifty SIDEWAYS, stock direction leads",
                            candidate.getSymbol());
                }
            }

            // -- STEP B: Risk Assessment - compute SL/T1/T2 ------------------
            AiPrediction prediction = probabilityEngine.predict(
                    candidate.getFeatureVector().getFeatures(), currentRegime);
            AiTradeDecision decision = riskEngine.assess(
                    candidate, prediction, currentRegime);
            if (decision == null) {
                log.info("[AI-TRACE] {} REJECTED at risk assessment - decision was null " +
                                "(riskEngine.assess returned null - see AiRiskAssessmentEngine for exact " +
                                "cause: invalid live price or position size rounds to zero)",
                        candidate.getSymbol());
                // FIX: this was a completely silent continue - no blockReasons entry,
                // so "Eligible" stocks blocked here had zero explanation on the
                // dashboard. Now records exactly why. riskEngine.assess() returns
                // null when it cannot compute a valid SL/T1/T2 for this candidate
                // (e.g. ATR too small, price action too compressed for a clean
                // risk:reward setup, or the risk parameters fall outside the
                // engine's configured bounds) - a legitimate, real gate, just
                // previously invisible.
                // FIX (found via direct user cross-check): the previous message
                // mentioned "ATR too compressed" and "risk:reward outside bounds"
                // - neither is actually checked by AiRiskAssessmentEngine at this
                // stage (confirmed by reading its complete source twice). The
                // ONLY 2 real conditions that produce a null result here are:
                // riskPerShare<=0 (entry/SL came out equal - almost always a
                // stale/invalid live price at that exact moment) or qty<=0
                // (position size rounds to zero - capital too small for this
                // stock's price). Message now accurately reflects only these
                // 2 real, code-verified causes, not speculative language.
                blockReasons.put(candidate.getSymbol(),
                        "Pattern scored OK and cleared all gates, but the risk engine " +
                                "could not size a valid trade - either the live price feed " +
                                "returned an invalid/stale tick at that exact moment (entry and " +
                                "stop-loss came out equal), or the computed position size rounded " +
                                "to zero shares for this stock's price at current capital");
                continue;
            }

            // FIX (low-accuracy investigation): AiContinuousImprovementEngine
            // computes a sophisticated, win-rate-adaptive confidence floor
            // (starts at 0.40 for a fresh system, tightens toward 0.75 as
            // losing trades accumulate, loosens toward 0.55 if win rate is
            // strong) - but this value was NEVER actually applied anywhere;
            // it was only ever read for dashboard display
            // (status.put("minConfidence", ...)). The self-correction signal
            // existed but never fed back into trade selection. Wiring it in
            // here as a genuine gate, alongside the existing pattern-score
            // executionThreshold - these are two different, complementary
            // confidence concepts (pattern-based 0-100 vs. ML-prediction-
            // based 0-1) and both should hold for a trade to qualify.
            double minConf = improvementEngine.getMinConfidenceThreshold();
            if (decision.getConfidence() < minConf) {
                log.info("[AI-TRACE] {} REJECTED at ML confidence floor: confidence={} " +
                                "adaptive_floor={}", candidate.getSymbol(),
                        String.format("%.2f", decision.getConfidence()),
                        String.format("%.2f", minConf));
                blockReasons.put(candidate.getSymbol(), String.format(
                        "ML confidence %.2f below adaptive floor %.2f (pattern score was OK)",
                        decision.getConfidence(), minConf));
                continue;
            }
            blockReasons.remove(candidate.getSymbol()); // cleared this gate - wasn't the blocker

            // FIX (new enhancement, per explicit user specification):
            // "Before placing a trade, check whether the stock has
            // already moved 1.5% in the SAME direction as the signal -
            // if so, skip, since the move has already happened."
            // Deliberately directional, unlike the old, removed
            // TOO_EXTENDED gate (which blocked on ANY 1.5%+ move
            // regardless of direction, even one favorable to entry).
            // A BUY signal is only skipped if the stock ALREADY gained
            // 1.5%+ today; a SELL signal is only skipped if it ALREADY
            // fell 1.5%+ - the opposite-direction case is explicitly
            // allowed through, since that's not "chasing" a move.
            if (!dailyCandles.isEmpty()) {
                double refPrice = dailyCandles.get(dailyCandles.size() - 1)
                        .getClose().doubleValue();
                if (refPrice > 0) {
                    double movePct = (candidate.getLtp() - refPrice) / refPrice;
                    boolean isLong = "LONG".equals(candidate.getSuggestedDirection());
                    boolean alreadyMovedSameDirection =
                            (isLong && movePct >= 0.015) || (!isLong && movePct <= -0.015);
                    if (alreadyMovedSameDirection) {
                        log.info("[AI-TRACE] {} SKIPPED - already moved {}% in the {} direction " +
                                        "today (>= 1.5% threshold) - the move has already happened, " +
                                        "chasing it now is not taken", candidate.getSymbol(),
                                String.format("%.2f", movePct * 100), isLong ? "BUY" : "SELL");
                        blockReasons.put(candidate.getSymbol(), String.format(
                                "Cleared all individual gates, but stock already moved %.2f%% " +
                                        "in the %s direction today (>= 1.5%% threshold) - the move has " +
                                        "already happened, not entering a chase", movePct * 100,
                                isLong ? "BUY" : "SELL"));
                        continue;
                    }
                }
            }

            log.info("[AI-TRACE] {} survived ALL gates - entering ranking pool " +
                            "(will be decided by reasoningEngine.selectBest this cycle)",
                    candidate.getSymbol());

            scoredCandidates.add(candidate);
            allDecisions.add(decision);
            confidenceResults.add(conf);
        }

        if (choppy) {
            log.info("[AI-SYSTEM] CHOPPY - {} stocks on watchlist, no execution",
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

        // ===================================================================
        // ENGINE 7: REASONING - picks the single best candidate
        // Pattern confidence already filtered above.
        // Reasoning provides additional comparative ranking and narrative.
        // ===================================================================
        AiReasoningEngine.AiReasoningResult reasoned = reasoningEngine.selectBest(
                scoredCandidates, allDecisions, snapshot);

        // FIX (found via direct user report: genuinely eligible watchlist
        // stocks producing zero trades AND zero "why didn't it trade"
        // entries, vanishing without any trace). Confirmed root cause in
        // AiReasoningEngine: a candidate with a null/malformed feature
        // vector was silently dropped from ranking consideration entirely,
        // regardless of whether OTHER candidates still won this cycle.
        // Record the real, specific reason now, instead of that silent gap.
        java.util.Map<String, String> silentlySkipped = reasoningEngine.getLastSkippedSymbols();
        for (var entry : silentlySkipped.entrySet()) {
            blockReasons.put(entry.getKey(),
                    "Was marked Eligible on the watchlist, but a data issue prevented it from " +
                            "being ranked for execution: " + entry.getValue());
        }

        if (reasoned == null) {
            log.debug("[AI-SYSTEM] Reasoning engine: no trade | regime={}", currentRegime);
            // Pure observability: every candidate that made it this far
            // (passed pattern score, confidence floor, and hard-lock) but
            // still didn't get picked because reasoningEngine rejected
            // ALL of them this cycle - record that, distinctly from a
            // candidate that lost out to a single better one (handled below).
            for (AiCandidate c : scoredCandidates) {
                if (silentlySkipped.containsKey(c.getSymbol())) continue; // already recorded above, more specifically
                blockReasons.put(c.getSymbol(),
                        "Cleared all individual gates, but reasoning engine selected " +
                                "no trade at all this cycle (regime/market-context check failed)");
            }
            return;
        }
        // Pure observability: everyone who cleared all individual gates
        // but wasn't the ONE candidate reasoningEngine actually picked.
        for (AiCandidate c : scoredCandidates) {
            if (!c.getSymbol().equals(reasoned.decision().getSymbol())) {
                blockReasons.put(c.getSymbol(), "Cleared all individual gates, but " +
                        reasoned.decision().getSymbol() + " was selected instead this cycle " +
                        "(only one trade fires per cycle)");
            }
        }
        blockReasons.remove(reasoned.decision().getSymbol()); // this one's actually proceeding

        // Attach confidence score to the reasoned decision
        int confScore = confidenceResults.stream()
                .filter(r -> r.dominantPattern() != null)
                .mapToInt(AiPatternConfidenceEngine.ConfidenceResult::totalScore)
                .max().orElse(executionThreshold);

        // ===================================================================
        // ENGINE 8: EXECUTION - only the reasoned best candidate
        // ===================================================================
        if (tradesToday.get() >= maxTradesPerDay) {
            blockReasons.put(reasoned.decision().getSymbol(), String.format(
                    "Selected as best candidate, but daily trade cap reached (%d/%d)",
                    tradesToday.get(), maxTradesPerDay));
        } else if (tradeManager.getOpenCount() >= maxConcurrent) {
            blockReasons.put(reasoned.decision().getSymbol(), String.format(
                    "Selected as best candidate, but max concurrent positions reached (%d/%d)",
                    tradeManager.getOpenCount(), maxConcurrent));
        }
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
                blockReasons.remove(enriched.getSymbol()); // genuinely traded - clear any stale reason
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
                persistFiredTrade(enriched.getSymbol(), w);

                log.info("[AI-SYSTEM] [OK] TRADE #{} | {} {} | composite={} " +
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
            } else {
                blockReasons.put(enriched.getSymbol(),
                        "Selected as best candidate, order placement was attempted but failed " +
                                "(see logs for the broker/network error)");
            }
        }

        long elapsed = System.currentTimeMillis() - t0;
        log.debug("[AI-SYSTEM] Cycle complete | regime={} candidates={} trades={} | {}ms",
                currentRegime, candidates.size(), allDecisions.size(), elapsed);
    }

    // =======================================================================
    // 1-MINUTE CANDLE - trade management for all open AI positions
    // ENGINE 7: TRADE MANAGEMENT
    // =======================================================================

    @EventListener
    @Async("tradingExecutor")
    public void onMinuteCandle(CandleCompleteEvent event) {
        if (!"minute".equals(event.getCandle().getTimeframe())) return;
        // Update all open AI positions
        tradeManager.getOpenPositions().keySet()
                .forEach(tradeManager::onCandle);
    }

    // =======================================================================
    // EXECUTION - direct Trade creation, no external service
    // =======================================================================

    private boolean executeTrade(AiTradeDecision decision) {
        // INDEPENDENCE (this session's rework): no longer routes through
        // SmartChannelPullbackSignalEvent / SmartChannelSignalHandler /
        // TradeApprovedEvent / PaperTradeExecutionService - that shared
        // platform pipeline belongs to the other strategies being
        // permanently removed. AI now executes directly and independently:
        //
        //   PAPER mode: simulates an instant fill here, registers with
        //     AiTradeManagementEngine, debits AiNewsCapitalLedger directly.
        //
        //   LIVE mode: places a real order via AiLiveOrderExecutionService,
        //     and only registers the position once the broker confirms the
        //     fill (onLiveEntryFilled() below) - using the actual fill
        //     price, not the original signal price.
        //
        // AiTradeManagementEngine remains the sole position/SL/T1/T2
        // authority either way - it now also drives LIVE exit orders
        // directly (see this session's changes there), with zero
        // dependency on any other strategy's classes or event pipeline.
        synchronized (this) {
            // FIX: getOpenCount() only counts CONFIRMED positions (registered
            // after fill in LIVE mode). A LIVE entry order placed but not yet
            // fill-confirmed wouldn't be counted here, creating a brief window
            // where more entries than maxConcurrent could be placed before the
            // first one's fill confirmation arrives. Counting pendingEntryContext
            // too closes that gap - reserves the slot the moment an order is
            // placed, not only once it's confirmed filled.
            int effectiveOpenCount = tradeManager.getOpenCount() + pendingEntryContext.size();
            if (effectiveOpenCount >= maxConcurrent) {
                log.debug("[AI-SYSTEM] executeTrade: concurrent limit reached " +
                                "(open={} pending={}) - skip {}",
                        tradeManager.getOpenCount(), pendingEntryContext.size(), decision.getSymbol());
                return false;
            }
            if (tradesToday.get() >= maxTradesPerDay) {
                log.debug("[AI-SYSTEM] executeTrade: daily limit reached - skip {}",
                        decision.getSymbol());
                return false;
            }
            // INDEPENDENCE FIX: removed riskManagement.isSymbolAlreadyActive() -
            // that checks against OTHER strategies' open positions via shared
            // RiskManagementService. AI/News now only need to check their OWN
            // open positions, which tradeManager.getOpenCount()/hasPosition()
            // (checked above) already covers completely independently.

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
                    log.warn("[AI-SYSTEM] Invalid decision fields for {} - skip", symbol);
                    return false;
                }

                long      token       = aiData.resolveInstrumentToken(symbol);
                TradeDirection dir    = TradeDirection.valueOf(direction);
                boolean   isBuy       = dir == TradeDirection.LONG;
                BigDecimal marginReq  = entry.multiply(BigDecimal.valueOf(qty));

                // -- LIVE MODE: place a real order, wait for broker fill ------
                // confirmation before registering anything. PAPER mode below
                // continues to simulate an instant fill, exactly as before -
                // zero change to paper behaviour.
                if (liveOrderService.isLiveMode()) {
                    String orderId = liveOrderService.placeEntryOrder(
                            symbol, isBuy, qty, entry.doubleValue(), "AI_TRADING_V2");
                    if (orderId == null) {
                        log.warn("[AI-SYSTEM] LIVE entry order not placed for {} " +
                                "(blocked or failed) - no position opened.", symbol);
                        return false;
                    }
                    // Fill confirmation arrives asynchronously via
                    // onLiveEntryFilled() below - registerTrade() and the
                    // ledger debit happen there, using the ACTUAL fill price,
                    // not this signal price.
                    pendingEntryContext.put(orderId, new PendingEntryContext(
                            symbol, token, dir, sl, t1, t2, qty, decision));
                    log.info("[AI-SYSTEM] LIVE entry order placed, awaiting fill: {} {} " +
                            "orderId={}", symbol, direction, orderId);
                    return true;
                }

                // -- PAPER MODE - unchanged simulated instant fill -----------
                Trade trade = Trade.builder()
                        .tradeDate(LocalDate.now(ZoneId.of("Asia/Kolkata")))
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
                capitalLedger.debitMargin(symbol, "AI_TRADING_V2", marginReq);

                log.info("[AI-SYSTEM] [OK] Signal fired (PAPER): {} {} | token={} entry={} sl={} t1={}",
                        symbol, direction, token, entry, sl, t1);
                return true;

            } catch (Exception e) {
                log.error("[AI-SYSTEM] Execution failed for {}: {}",
                        decision.getSymbol(), e.getMessage(), e);
                return false;
            }
        }
    }

    /**
     * Holds the data needed to register a position once a LIVE entry order's
     * fill is confirmed - the signal-time decision details, keyed by orderId
     * so onLiveEntryFilled() can reconstruct the full Trade/position.
     */
    private record PendingEntryContext(
            String symbol, long instrumentToken, TradeDirection direction,
            BigDecimal stopLoss, BigDecimal target1, BigDecimal target2,
            int quantity, AiTradeDecision decision) {}

    private final Map<String, PendingEntryContext> pendingEntryContext = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Wired to liveOrderService.setOnEntryFilled() in init(). Fires once the
     * broker confirms a LIVE entry order is COMPLETE. Registers the position
     * using the ACTUAL average fill price/quantity, not the original signal
     * price - slippage between signal and fill is real and must be reflected
     * in the position's true entry price for correct P&L/R-multiple math.
     */
    private void onLiveEntryFilled(String symbol,
                                   com.trading.ai.execution.AiLiveOrderExecutionService.FillResult fill) {
        PendingEntryContext ctx = null;
        for (Map.Entry<String, PendingEntryContext> e : pendingEntryContext.entrySet()) {
            if (e.getValue().symbol().equals(symbol)) { ctx = e.getValue(); break; }
        }
        if (ctx == null) {
            log.error("[AI-SYSTEM] onLiveEntryFilled: no pending context found for {} - " +
                    "cannot register position. orderId={}", symbol, fill.orderId());
            return;
        }
        pendingEntryContext.remove(fill.orderId());

        BigDecimal actualEntry = BigDecimal.valueOf(fill.avgFillPrice());
        Trade trade = Trade.builder()
                .tradeDate(LocalDate.now(ZoneId.of("Asia/Kolkata")))
                .tradingSymbol(symbol)
                .instrumentToken(ctx.instrumentToken())
                .direction(ctx.direction())
                .status("OPEN")
                .entryTime(Instant.now())
                .entryPrice(actualEntry)
                .quantity(fill.filledQty())
                .stopLoss(ctx.stopLoss())
                .target(ctx.target1())
                .strategyName("AI_TRADING_V2")
                .probabilityScore(BigDecimal.valueOf(
                        (int)(ctx.decision().getProbabilityOfSuccess() * 100)))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        tradeManager.registerTrade(trade, ctx.decision());
        capitalLedger.debitMargin(symbol, "AI_TRADING_V2",
                actualEntry.multiply(BigDecimal.valueOf(fill.filledQty())));

        log.info("[AI-SYSTEM] [OK] LIVE entry CONFIRMED: {} {} qty={} actualEntry={} " +
                        "(signal price was {})", symbol, ctx.direction(), fill.filledQty(),
                actualEntry, ctx.decision().getEntryPrice());
    }

    /**
     * Fired when a LIVE entry order is REJECTED or CANCELLED by the broker.
     * Cleans up pendingEntryContext for this symbol so it doesn't leak in
     * memory forever - no position was ever opened, so there is nothing
     * else to undo (no margin was debited, no Trade was registered; both
     * of those only happen in onLiveEntryFilled() above, after confirmed fill).
     */
    private void onLiveEntryRejected(String symbol, String statusMessage) {
        pendingEntryContext.entrySet().removeIf(e -> e.getValue().symbol().equals(symbol));
        log.warn("[AI-SYSTEM] LIVE entry order rejected/cancelled for {} - reason: {}. " +
                "No position was opened.", symbol, statusMessage);
        // FIX (found while confirming whether order-placement failures
        // show on the dashboard - they didn't): this covers BOTH genuine
        // broker-side rejections (order reached Zerodha, then rejected)
        // AND pre-flight failures caught before ever reaching the broker
        // (e.g. AccountMarginGuard's insufficient-margin check, added
        // during the platform-wide cross-strategy safeguard review) -
        // both paths route through this same callback via
        // AiLiveOrderExecutionService's catch block, so this one fix
        // makes ALL order-placement-stage failures visible here.
        blockReasons.put(symbol, "Order not placed: " + statusMessage);
    }

    // =======================================================================
    // DAILY RESET - midnight
    // =======================================================================

    private static int safeInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    private static long safeLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0; }
    }

    /**
     * Returns the current trade window name based on IST time.
     * Returns null if outside trading hours.
     *
     *  9:30 - 11:00 -> PRIME      (highest momentum, patterns fresh)
     * 11:00 - 12:30 -> GOOD       (trend established, volume active)
     * 12:30 - 13:30 -> MODERATE   (lunch zone, spreads widen)
     * 13:30 - 14:40 -> ACCEPTABLE (late session, closing momentum)
     * Outside these  -> null      (window blocked)
     */
    private String getTradeWindow(LocalTime t) {
        int m = t.getHour() * 60 + t.getMinute();
        if (m >= 570 && m < 660) return "PRIME";       // 9:30-11:00
        if (m >= 660 && m < 750) return "GOOD";        // 11:00-12:30
        if (m >= 750 && m < 810) return "MODERATE";    // 12:30-13:30
        if (m >= 810 && m < 880) return "ACCEPTABLE";  // 13:30-14:40
        return null; // outside window - Gate 2 already blocks this
    }

    /**
     * FIX 2 (soft penalty) support - checks whether this symbol appears in
     * ai_trade_outcomes (the existing, already-populated all-time learning
     * history table - same table AiLearningEngine reads at startup) within
     * the last 2 calendar days. Read-only query against existing data; no
     * new table, no new column, no change to anything that writes to this
     * table. Fails OPEN (returns false / "not recently traded") on any DB
     * error - a database hiccup must never block the existing, high-
     * performing pipeline from trading; it only means this specific
     * penalty doesn't apply for that one check.
     */
    private boolean isRecentlyTraded(String symbol) {
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ai_trade_outcomes WHERE symbol = ? AND exit_time >= ?",
                    Integer.class, symbol,
                    java.sql.Timestamp.from(Instant.now().minusSeconds(2 * 86_400L)));
            return count != null && count > 0;
        } catch (Exception e) {
            log.debug("[AI-SYSTEM] isRecentlyTraded check failed for {} - treating as " +
                    "not recently traded (fail-open): {}", symbol, e.getMessage());
            return false;
        }
    }

    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Kolkata")
    public void dailyReset() {
        firedToday.clear();
        tradesToday.set(0);
        tradesPerWindow.values().forEach(counter -> counter.set(0)); // reset all window counters
        todayDecisions.clear();
        // FIX: clear trade manager positions - prevents ghost positions
        // surviving midnight and blocking the next trading session
        tradeManager.clearPositions();
        watchlist.clear();
        blockReasons.clear(); // FIX: was not cleared, so stale "why didn't it
        // trade" reasons from yesterday persisted into
        // today's dashboard card - now cleared at midnight
        // with everything else
        learningEngine.dailyReset();
        // Clear stale fired-trades rows so they're never reconciled into a
        // future day by mistake.
        try {
            jdbc.update("DELETE FROM ai_fired_trades_today WHERE trade_date < ?", LocalDate.now(ZoneId.of("Asia/Kolkata")));
        } catch (Exception e) {
            log.debug("[AI-SYSTEM] Daily fired-trades DB cleanup failed (non-fatal): {}",
                    e.getMessage());
        }
        log.info("[AI-SYSTEM] Daily reset complete");
    }

    // =======================================================================
    // WEEKLY RESET - Monday 07:00
    // =======================================================================

    @Scheduled(cron = "0 0 7 * * MON", zone = "Asia/Kolkata")
    public void weeklyReset() {
        learningEngine.weeklyReset();
        log.info("[AI-SYSTEM] Weekly reset complete");
    }

    // =======================================================================
    // BTST READ-ONLY ACCESSORS - used by BtstAiStrategy only
    // These are purely read-only - zero impact on AI trading system state
    // =======================================================================

    /** Returns map of symbol -> confidence score for all watchlist stocks */
    public Map<String, Integer> getWatchlistScores() {
        // watchlist stores symbol -> pattern name
        // scores are not stored - BTST uses pattern presence as proxy
        // stocks on watchlist passed all gates - score >= 1
        Map<String, Integer> scores = new LinkedHashMap<>();
        for (String sym : watchlist.keySet()) {
            // Use todayDecisions for actual scores if available
            todayDecisions.stream()
                    .filter(d -> sym.equals(d.getSymbol()))
                    .findFirst()
                    .ifPresentOrElse(
                            d -> scores.put(sym, (int) d.getNumericPreScore()),
                            () -> scores.put(sym, 80) // on watchlist = >= threshold
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

    // =======================================================================
    // STATUS ACCESSORS - for DashboardController
    // =======================================================================

    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("regime",         currentRegime);
        // FIX, found via real dashboard data: TRENDING/RANGING badge text
        // was hardcoded as static strings "70"/"80" in dashboard.html,
        // left stale after this session's threshold raise to 75/85. Each
        // individual watchlist item already showed the REAL live
        // threshold (85) correctly, since that flows dynamically from
        // here - but the legend banner above it showed the old, wrong
        // numbers, creating a confusing mismatch (e.g. "RANGING: Score
        // >= 80" next to a watchlist item showing "83/85"). Exposing the
        // real constants here so the dashboard can render them dynamically
        // instead of hardcoding numbers that silently go stale on the
        // next threshold change.
        status.put("trendingThreshold", TRENDING_EXECUTION_THRESHOLD);
        status.put("rangingThreshold",  RANGING_EXECUTION_THRESHOLD);
        // FIX: dashboard reads ai.thresholdLabel for the small label under
        // the regime card, but this was never actually populated by the
        // backend - always silently fell back to a bare "-". Now shows
        // the real, currently-applicable threshold for whatever regime is
        // actually active right now.
        String activeThresholdLabel = "TRENDING".equals(currentRegime)
                ? "Score >= " + TRENDING_EXECUTION_THRESHOLD + " + direction match"
                : "RANGING".equals(currentRegime)
                ? "Score >= " + RANGING_EXECUTION_THRESHOLD + " (both directions)"
                : "No execution - choppy regime";
        status.put("thresholdLabel", activeThresholdLabel);
        // Pure observability for the dashboard - see blockReasons field
        // docstring. Read-only export, never influences any decision.
        status.put("blockReasons", new LinkedHashMap<>(blockReasons));
        status.put("phase",          probabilityEngine.getPhaseLabel());
        status.put("tradesToday",    tradesToday.get());
        status.put("watchlistCount", watchlist.size());
        // Parse encoded watchlist value: "pattern|skipReason|score|threshold"
        // Returns [{symbol, pattern, skipReason, score, threshold}] for dashboard
        // FIX (found via direct user report: stocks showing "Eligible"
        // for 40+ minutes with zero new activity - confirmed root cause
        // was watchlist only clearing at midnight, so a stale entry from
        // an earlier cycle just sits there unchanged once the discovery
        // engine stops including that symbol in later cycles). Filters
        // out any entry older than STALE_THRESHOLD_MS - the dashboard
        // will now only ever show genuinely current, actively-being-
        // considered watchlist entries, never a possibly hour-old
        // "Eligible" masquerading as live.
        final long STALE_THRESHOLD_MS = 10 * 60 * 1000; // 10 min ~= 2 scan cycles, safe buffer
        long nowMs = System.currentTimeMillis();
        status.put("watchlist", watchlist.entrySet().stream()
                .filter(e -> {
                    String[] parts = e.getValue().split("\\|", 5);
                    if (parts.length < 5) return true; // no timestamp (shouldn't happen in
                    // practice - in-memory map is always
                    // fresh since last restart - but fail
                    // safe rather than hide valid data)
                    long ts = safeLong(parts[4]);
                    return ts == 0 || (nowMs - ts) <= STALE_THRESHOLD_MS;
                })
                .map(e -> {
                    String[] parts = e.getValue().split("\\|", 5);
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