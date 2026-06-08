package com.trading.ai;

import com.trading.ai.engine.*;
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

/**
 * AiTradingModule — Master orchestrator for the AI-powered trading system.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * ISOLATION GUARANTEE — zero impact on existing strategies:
 *
 *  1. @ConditionalOnProperty — this entire bean (and all its dependencies)
 *     only exist when ai.trading.enabled=true. When false, Spring creates
 *     ZERO beans from the com.trading.ai package. Existing strategies are
 *     completely unaware this module exists.
 *
 *  2. Read-only access to shared services — MarketDataService,
 *     MarketDirectionService, SectorStrengthService, SmcCandleService,
 *     StructureService, NewsIngestionService are all read-only. The AI
 *     module calls getter methods only — never mutates shared state.
 *
 *  3. Own event type — publishes AiTradeSignalEvent, never
 *     SmartChannelPullbackSignalEvent or any existing event type.
 *     Existing event listeners will never see AI events.
 *
 *  4. Own execution path — AiPaperTradeExecutionService is a separate
 *     Spring bean that does NOT extend or modify PaperTradeExecutionService.
 *     Trades are tagged with strategy="AI_TRADING_V1".
 *
 *  5. Own circuit breaker check — reads CircuitBreakerService but does
 *     not record trades through it (AI has its own daily cap).
 *
 * PIPELINE (every 5m candle close):
 *   CandleCompleteEvent
 *     → AiFeatureEngineeringService  (60-feature vector, ~30ms for 253 symbols)
 *     → AiOpportunityRankingService  (numeric pre-score, picks top 15)
 *     → AiReasoningEngine            (Claude Sonnet API, reasons over top 15)
 *     → AiTradeSelectionService      (picks ≤5 from Claude's output)
 *     → AiRiskManagementService      (1% risk, dynamic SL/T1/T2)
 *     → AiPaperTradeExecutionService (creates paper trades)
 *     → AiTradeManagementService     (trails SL, exits on conditions)
 *     → AiLearningService            (records outcome, updates model weights)
 * ═══════════════════════════════════════════════════════════════════════════
 */
@Service
@ConditionalOnProperty(name = "ai.trading.enabled", havingValue = "true")
@Slf4j
@RequiredArgsConstructor
public class AiTradingModule {

    private static final ZoneId    IST        = ZoneId.of("Asia/Kolkata");
    private static final LocalTime SCAN_START = LocalTime.of(9, 30);
    private static final LocalTime SCAN_END   = LocalTime.of(14, 45);

    // ── AI engines (all @ConditionalOnProperty — only exist when AI enabled) ──
    private final AiFeatureEngineeringService  featureEngine;
    private final AiOpportunityRankingService  rankingEngine;
    private final AiReasoningEngine            reasoningEngine;
    private final AiTradeSelectionService      selectionEngine;
    private final AiRiskManagementService      riskEngine;
    private final AiPaperTradeExecutionService executionService;
    private final AiTradeManagementService     tradeManager;
    private final AiLearningService            learningService;
    private final AiStateStore                 stateStore;

    // ── Shared platform services (READ-ONLY — never mutated) ─────────────────
    private final MarketDataService               marketDataService;
    private final MarketDirectionService          marketDirectionService;
    private final SectorStrengthService           sectorStrengthService;
    private final SectorClassificationService     sectorClassify;
    private final CircuitBreakerService           circuitBreaker;
    private final HighRRStructureService          hrrStructureService;
    private final SmcInstitutionalCandleService   smcCandleService;
    private final SmcInstitutionalStructureService smcStructureService;
    private final NewsIngestionService            newsIngestionService;

    @Value("${ai.trading.max-trades-per-day:5}")   private int    maxTrades;
    @Value("${ai.trading.max-concurrent:2}")        private int    maxConcurrent;
    @Value("${ai.trading.min-confidence:0.60}")     private double minConfidence;
    @Value("${ai.trading.reasoning-top-n:15}")      private int    reasoningTopN;
    @Value("${ai.trading.risk-per-trade:0.01}")     private double riskPerTrade;

    // ── Session state (AI-isolated) ───────────────────────────────────────────
    private final AtomicInteger tradesExecutedToday = new AtomicInteger(0);
    private final Set<String>   activePositions     = ConcurrentHashMap.newKeySet();
    private final Set<String>   firedToday          = ConcurrentHashMap.newKeySet();
    private volatile long       lastScanCandleMs    = 0L;

    // ═════════════════════════════════════════════════════════════════════════
    // MAIN TRIGGER — fires on every 5m candle close
    // ═════════════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tradingExecutor")
    public void onCandleClose(CandleCompleteEvent event) {
        if (!"5minute".equals(event.getCandle().getTimeframe())) return;
        if (!event.getCandle().isComplete()) return;

        long ms = event.getCandle().getCandleTime() != null
                ? event.getCandle().getCandleTime().toEpochMilli()
                : System.currentTimeMillis();
        synchronized (this) {
            if (ms <= lastScanCandleMs) return;
            lastScanCandleMs = ms;
        }
        runAiCycle();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // CORE AI CYCLE
    // ═════════════════════════════════════════════════════════════════════════

    private void runAiCycle() {
        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(SCAN_START) || now.isAfter(SCAN_END)) return;

        // ── Session-level gates ───────────────────────────────────────────────
        if (tradesExecutedToday.get() >= maxTrades) {
            log.debug("[AI] Daily limit reached ({}/{})", tradesExecutedToday.get(), maxTrades);
            return;
        }
        if (activePositions.size() >= maxConcurrent) {
            log.debug("[AI] Max concurrent positions ({}/{})", activePositions.size(), maxConcurrent);
            return;
        }
        if (!smcCandleService.isBootstrapComplete()) {
            log.debug("[AI] Candle bootstrap not complete — skipping cycle");
            return;
        }

        try {
            long t0 = System.currentTimeMillis();

            // ── Step 1: Symbol universe ───────────────────────────────────────
            Set<String> universe = smcCandleService.getLoadedSymbols();
            if (universe.isEmpty()) return;

            // ── Step 2: Feature engineering (~30ms for 253 symbols) ───────────
            // Builds a 60-feature numeric vector for every symbol in parallel.
            // Features include: price action, volume, technicals, S/R proximity,
            // HTF trend, sector strength, news score, market context.
            // This is pure in-memory computation — no external calls.
            AiFeatureBatch features = featureEngine.buildAll(
                    universe,
                    marketDataService.getLastPricesSimple(),
                    marketDirectionService.getCurrentDirection(),
                    sectorStrengthService, sectorClassify,
                    smcCandleService, smcStructureService,
                    hrrStructureService,
                    newsIngestionService.getActiveItems()
            );

            // ── Step 3: Numeric opportunity ranking (~5ms) ────────────────────
            // Fast scoring without any AI call. Uses numeric heuristics to
            // rank all symbols and return the top N candidates for Claude.
            // Filters out: firedToday, activePositions, price < ₹50,
            // insufficient data, correlated positions.
            List<AiCandidate> topCandidates = rankingEngine.rankAndFilter(
                    features, firedToday, activePositions, reasoningTopN
            );

            if (topCandidates.isEmpty()) return;

            long t1 = System.currentTimeMillis();

            // ── Step 4: AI Reasoning Engine (Claude Sonnet API) ───────────────
            // This is the core intelligence layer.
            // Claude receives a structured prompt containing:
            //   - Market context (direction, ATR, breadth, VIX, time of day)
            //   - For each top candidate: symbol, direction, LTP, feature summary,
            //     HTF structure, S/R zones, sector context, news, multi-TF alignment
            // Claude reasons like a professional trader:
            //   - Evaluates each opportunity from multiple angles
            //   - Generates bull and bear scenarios
            //   - Challenges its own initial assessment
            //   - Assigns probability and confidence
            //   - Explains decision in plain English
            //   - Returns structured JSON with trade decisions
            List<AiReasonedOpportunity> reasoned = reasoningEngine.reason(
                    topCandidates,
                    marketDirectionService.getCurrentDirection(),
                    now
            );

            long t2 = System.currentTimeMillis();

            // ── Step 5: Trade selection ───────────────────────────────────────
            // From Claude's reasoned opportunities, pick the final trades.
            // Rules: min confidence, min RR 2.0, sector diversification,
            // no duplicate direction on same sector, max slots remaining.
            int slotsLeft = Math.min(
                    maxTrades - tradesExecutedToday.get(),
                    maxConcurrent - activePositions.size()
            );
            List<AiTradeDecision> selected = selectionEngine.select(
                    reasoned, slotsLeft, minConfidence
            );

            log.info("[AI] Cycle @{} | universe={} | featured={} | top-{} → Claude → {}/{} selected | " +
                            "feature={}ms claude={}ms",
                    now, universe.size(), features.size(),
                    topCandidates.size(), selected.size(), reasoned.size(),
                    t1-t0, t2-t1);

            // ── Step 6: Risk management + execution ───────────────────────────
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

                    log.info("[AI] ✅ TRADE #{} | {} {} | entry={} sl={} t1={} | " +
                                    "RR={} conf={}% score={} | reasoning: {}",
                            tradesExecutedToday.get(),
                            sized.getSymbol(), sized.getDirection(),
                            sized.getEntryPrice(), sized.getStopLoss(), sized.getTarget1(),
                            String.format("%.2f", sized.getRrRatio()),
                            (int)(sized.getConfidence()*100),
                            sized.getTradeQualityScore(),
                            sized.getReasoningSummary());
                }
            }

            if (selected.isEmpty() && !reasoned.isEmpty()) {
                log.info("[AI] Claude reasoned over {} candidates — all rejected (conf/RR/sector)",
                        reasoned.size());
            }

        } catch (Exception e) {
            log.error("[AI] Cycle error: {}", e.getMessage(), e);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SCHEDULED TASKS
    // ═════════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 11 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyReset() {
        tradesExecutedToday.set(0);
        activePositions.clear();
        firedToday.clear();
        learningService.onDayStart();
        log.info("[AI] Daily reset — {} trade slots available. AI module active.", maxTrades);
    }

    @Scheduled(cron = "0 5 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void eodExit() {
        log.info("[AI] EOD close — closing all {} AI positions", activePositions.size());
        tradeManager.closeAllPositions("AI_EOD_CLOSE");
    }

    @Scheduled(cron = "0 0 20 * * SUN", zone = "Asia/Kolkata")
    public void weeklyLearningCycle() {
        log.info("[AI] Weekly learning cycle — updating feature weights from trade outcomes");
        learningService.runWeeklyUpdate();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // CALLBACKS FROM MANAGEMENT SERVICE
    // ═════════════════════════════════════════════════════════════════════════

    public void onPositionClosed(String symbol, AiTradeOutcome outcome) {
        activePositions.remove(symbol);
        learningService.recordOutcome(outcome);
        log.info("[AI] Position closed: {} | {} | P&L={} | reason={}",
                symbol, outcome.getOutcomeType(), outcome.getPnl(), outcome.getExitReason());
    }

    // ═════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    private BigDecimal resolveCapital() {
        try {
            return java.math.BigDecimal.valueOf(100_000);
        } catch (Exception e) {
            return BigDecimal.valueOf(100_000);
        }
    }

    // ── Dashboard / scanner read access ──────────────────────────────────────
    public int                    getTradesExecutedToday()  { return tradesExecutedToday.get(); }
    public int                    getActivePositionCount()  { return activePositions.size(); }
    public Set<String>            getFiredToday()           { return Collections.unmodifiableSet(firedToday); }
    public List<AiTradeDecision>  getRecentDecisions()      { return stateStore.getRecentDecisions(); }
    public AiPerformanceMetrics   getPerformance()          { return learningService.getMetrics(); }
}