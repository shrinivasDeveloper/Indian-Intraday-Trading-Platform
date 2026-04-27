package com.trading.strategy.news;

import com.trading.domain.enums.TradeDirection;
import com.trading.events.SmartChannelPullbackSignalEvent;
import com.trading.marketdata.service.InstrumentCacheService;
import com.trading.marketdata.service.MarketTimingService;
import com.trading.papertrading.model.PaperAccount;
import com.trading.position.service.PositionSizerService;
import com.trading.regime.service.MarketDirectionService;
import com.trading.risk.service.CircuitBreakerService;
import com.trading.risk.service.RiskManagementService;
import com.trading.sector.service.SectorClassificationService;
import com.trading.sector.service.SectorStrengthService;
import com.zerodhatech.models.Instrument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * NewsTradingStrategy (NEWS_CATALYST_V1) — independent news-driven strategy.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * DESIGN PRINCIPLES:
 *   1. Completely independent — zero modifications to any existing strategy
 *   2. Uses the IDENTICAL signal pipeline as all other strategies
 *      (SmartChannelPullbackSignalEvent → SmartChannelSignalHandler →
 *       TradeApprovedEvent → PaperTradeExecutionService)
 *   3. All existing risk gates still apply (symbol dedup, circuit breaker,
 *      daily trade limit, slot allocation)
 *   4. Max 2 signals per session — same constraint as ORB and HighRR
 *
 * EXECUTION CYCLE (every 3 minutes):
 *   1. Fetch scored news items from NewsScoreEngine
 *   2. Apply market regime gates (ATR, window, direction)
 *   3. For top-ranked stocks: compute entry/SL/target from current price
 *   4. Fire SmartChannelPullbackSignalEvent into existing pipeline
 *
 * SL / TARGET METHODOLOGY:
 *   News catalyst trades are SHORT-DURATION — the price move happens fast
 *   after news breaks. Strategy uses:
 *     Entry:  current LTP (market order simulation)
 *     SL:     entry ± 0.8% (news trades need room — volatile after news)
 *     T1:     entry ± 1.6% (2:1 RR)
 *     T2:     entry ± 2.4% (3:1 RR)
 *   Time stop: 20 minutes (news impact fades quickly)
 *
 * SLOT BUDGET:
 *   NEWS_CATALYST_V1 gets max 2 slots per session.
 *   Total system budget: ORB(2) + HighRR(2) + MarketPressure(4) + SCPS(2) + NEWS(2) = 12
 *   Circuit breaker max-trades-per-day governs the hard ceiling.
 * ─────────────────────────────────────────────────────────────────────────────
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class NewsTradingStrategy {

    private static final ZoneId   IST           = ZoneId.of("Asia/Kolkata");
    private static final String   STRATEGY_NAME = "NEWS_CATALYST_V1";

    // ── Time gates ─────────────────────────────────────────────────────────────
    private static final LocalTime TRADE_START  = LocalTime.of(9, 35);
    // FIXED: was 14:00 — extended to 14:30 to capture afternoon RBI/policy announcements
    private static final LocalTime TRADE_END    = LocalTime.of(14, 30);
    private static final LocalTime LUNCH_START  = LocalTime.of(11, 0);
    private static final LocalTime LUNCH_END    = LocalTime.of(12, 30);

    // ── Trade sizing for news catalyst ────────────────────────────────────────
    /** SL distance as % of entry price */
    private static final double SL_PCT          = 0.008;  // 0.8%
    /** T1 as multiple of risk (2R) */
    private static final double T1_RR           = 2.0;
    /** T2 as multiple of risk (3R) */
    private static final double T2_RR           = 3.0;
    /** Minimum ATR for market to be tradeable */
    // FIXED: was 0.30 → now 0.20 (news is event-driven, not range-dependent)
    private static final double MIN_ATR_PCT     = 0.20;
    /** Time stop — news impact fades in ~20 minutes */
    private static final int    TIME_STOP_MIN   = 20;

    // ── Dependencies ──────────────────────────────────────────────────────────
    private final NewsIngestionService      ingestionService;
    private final NewsScoreEngine           scoreEngine;
    private final InstrumentCacheService    instrumentCache;
    private final ApplicationEventPublisher publisher;
    private final CircuitBreakerService     circuitBreaker;
    private final PositionSizerService      positionSizer;
    private final PaperAccount             paperAccount;
    private final MarketDirectionService   marketDirection;
    private final MarketTimingService      timingService;
    private final RiskManagementService    riskManagement;
    private final SectorClassificationService sectorClassify;
    private final SectorStrengthService    sectorStrength;

    // ── Config ────────────────────────────────────────────────────────────────
    @Value("${strategy.news.enabled:true}")
    private boolean engineEnabled;

    @Value("${strategy.news.min-score:65}")
    private int minScore;

    @Value("${strategy.news.max-signals-per-session:2}")
    private int maxSignalsPerSession;

    @Value("${trading.mode:PAPER}")
    private String tradingMode;

    @Value("${trading.capital:100000}")
    private BigDecimal configuredCapital;

    // ── Per-session state ──────────────────────────────────────────────────────
    private final AtomicInteger     sessionSignalCount = new AtomicInteger(0);  // FIX: volatile int++ is not atomic
    private final Set<String>       firedToday         = ConcurrentHashMap.newKeySet();
    private final Set<String>       activeSignals      = ConcurrentHashMap.newKeySet();

    /** Snapshot of recent news events for dashboard display */
    private final List<NewsEventSnapshot> recentEvents = new CopyOnWriteArrayList<>();

    // ══════════════════════════════════════════════════════════════════════════
    // MAIN EXECUTION CYCLE — every 3 minutes
    // ══════════════════════════════════════════════════════════════════════════

    @Scheduled(fixedRate = 180_000)
    public void runCycle() {
        if (!engineEnabled) return;

        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(TRADE_START) || now.isAfter(TRADE_END)) return;

        // ── Gate 1: Frozen market (ATR < 0.20%) ───────────────────────────────
        // EXCEPTION: EARNINGS and M&A news bypass this gate entirely.
        // A company announcing +80% profit moves 5-8% regardless of Nifty ATR.
        // The ATR gate protects against trading macro/sector news in a dead market,
        // NOT against trading company-specific events that move independently.
        // High-conviction bypass: category is EARNINGS or M&A AND score >= 75.
        MarketDirectionService.MarketDirectionResult dir = marketDirection.getCurrentDirection();
        final boolean marketFrozen = dir.niftyAtrPct() < MIN_ATR_PCT;
        // NOTE: marketFrozen is applied PER-SIGNAL in the scoring loop below,
        // not as a blanket early return — so company events can still pass.

        // ── Gate 2: Lunch window — NOT applied to news strategy ─────────────
        // News events (RBI policy, earnings, M&A) happen at any time including lunch.
        // Blocking 11:00-12:30 would miss RBI policy announcements (typically 10-12 AM)
        // and corporate results declared during market hours.
        // Lunch spread-risk affects technical channel strategies (SCPS/Sideways), not news.

        // Skip LATE window (14:00–14:40)
        MarketTimingService.TimeWindow window = timingService.getCurrentWindow();
        if (window == MarketTimingService.TimeWindow.LATE ||
                window == MarketTimingService.TimeWindow.OBSERVATION) return;

        // ── Gate 3: SIDEWAYS market — relaxed for high-conviction news ─────────
        // Company-specific events (EARNINGS, M&A) move the stock independently of Nifty.
        // A merger announcement or 50% profit beat trades regardless of Nifty direction.
        // MACRO news (RBI, GDP, global events) remains gated on SIDEWAYS — those ARE market events.
        // High-conviction threshold: score >= 80 (EARNINGS + strong sentiment + fresh)
        final int SIDEWAYS_BYPASS_SCORE = 80;
        boolean isSideways = dir.direction() == MarketDirectionService.Direction.SIDEWAYS;
        // For now, continue scoring — SIDEWAYS check applied per-signal in fireSignal()
        // based on category and score. Do not block here — let scoring decide.

        // ── Session cap ────────────────────────────────────────────────────────
        if (sessionSignalCount.get() >= maxSignalsPerSession) {
            log.debug("[NEWS] Session cap reached {}/{}", sessionSignalCount.get(), maxSignalsPerSession);
            return;
        }

        // ── Circuit breaker ────────────────────────────────────────────────────
        BigDecimal cap = resolveCapital();
        if (!circuitBreaker.checkPermission(cap).isAllowed()) {
            log.debug("[NEWS] Circuit breaker blocked");
            return;
        }

        // ── Score all active news ──────────────────────────────────────────────
        List<NewsItem> activeItems = ingestionService.getActiveItems();
        if (activeItems.isEmpty()) {
            log.debug("[NEWS] No active news items — cycle idle");
            return;
        }

        Set<String> tradableSymbols = instrumentCache.getEquityInstruments().keySet();
        List<NewsScore> scores = scoreEngine.scoreAll(activeItems, tradableSymbols, minScore);

        if (scores.isEmpty()) {
            log.debug("[NEWS] No news scores above threshold {} — cycle idle", minScore);
            return;
        }

        log.info("[NEWS] Cycle @{} | ATR={}% regime={} | {} qualifying scores | top: {}",
                now, dir.niftyAtrPct(), dir.direction(),
                scores.size(), scores.get(0).symbol());

        // ── Gate 4: Regime match — smart bypass for company-specific events ────
        // RULE: Company events (EARNINGS, M&A) bypass regime alignment AND ATR gate.
        //       The individual stock moves on its own news — Nifty ATR is irrelevant.
        // RULE: Macro events (RBI, GDP, GLOBAL) respect regime alignment + ATR gate.
        // RULE: SIDEWAYS market + score >= SIDEWAYS_BYPASS_SCORE → allow company-specific news.
        final int ATR_BYPASS_MIN_SCORE = 75; // minimum score to bypass frozen ATR gate
        List<NewsScore> aligned = scores.stream()
                .filter(s -> {
                    boolean isCompanyEvent = s.primaryCategory() == NewsItem.NewsCategory.EARNINGS
                            || s.primaryCategory() == NewsItem.NewsCategory.MERGER_ACQUISITION;

                    // Company events bypass BOTH regime check AND ATR gate
                    if (isCompanyEvent && s.totalScore() >= ATR_BYPASS_MIN_SCORE) {
                        log.info("[NEWS] {} bypassing ATR+regime gate — company event ({}) score={}",
                                s.symbol(), s.primaryCategory(), s.totalScore());
                        return true;
                    }
                    // Company event but score < 75 — still bypass regime, but needs normal ATR
                    if (isCompanyEvent) {
                        if (marketFrozen) {
                            log.debug("[NEWS] {} company event blocked — ATR frozen and score {} < {}",
                                    s.symbol(), s.totalScore(), ATR_BYPASS_MIN_SCORE);
                            return false;
                        }
                        log.debug("[NEWS] {} bypassing regime gate — company event ({})",
                                s.symbol(), s.primaryCategory());
                        return true;
                    }
                    // Non-company event: ATR gate applies
                    if (marketFrozen) return false;
                    // SIDEWAYS bypass: only for very high conviction non-company news
                    if (isSideways && s.totalScore() >= SIDEWAYS_BYPASS_SCORE) {
                        log.info("[NEWS] {} bypassing SIDEWAYS gate — score={} >= {}",
                                s.symbol(), s.totalScore(), SIDEWAYS_BYPASS_SCORE);
                        return true;
                    }
                    // SIDEWAYS with normal score → block macro/sector news
                    if (isSideways) return false;
                    // Bullish/Bearish: check direction alignment
                    return isDirectionAligned(s.direction(), dir.direction());
                })
                .toList();

        if (aligned.isEmpty()) {
            log.debug("[NEWS] Gate 4 BLOCKED — No regime-aligned signals (regime={} sideways={})",
                    dir.direction(), isSideways);
            return;
        }

        // ── Fire top-N signals ─────────────────────────────────────────────────
        int slotsLeft = maxSignalsPerSession - sessionSignalCount.get();
        int toFire    = Math.min(slotsLeft, aligned.size());

        for (int i = 0; i < toFire; i++) {
            NewsScore candidate = aligned.get(i);
            if (!fireSignal(candidate, cap, dir)) {
                log.debug("[NEWS] Signal not fired for {} — gate check failed", candidate.symbol());
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SIGNAL FIRING
    // ══════════════════════════════════════════════════════════════════════════

    private boolean fireSignal(NewsScore score, BigDecimal cap,
                               MarketDirectionService.MarketDirectionResult dir) {
        String symbol = score.symbol();

        // Skip if already traded today or currently active
        if (firedToday.contains(symbol) || activeSignals.contains(symbol)) {
            log.debug("[NEWS] {} already fired/active today — skip", symbol);
            return false;
        }

        // Skip if another strategy holds this symbol
        if (riskManagement.isSymbolAlreadyActive(symbol)) {
            log.debug("[NEWS] {} held by {} — skip",
                    symbol, riskManagement.getActiveStrategyForSymbol(symbol));
            return false;
        }

        // Resolve instrument token
        Instrument inst = instrumentCache.getEquityInstruments().get(symbol.toUpperCase());
        if (inst == null) {
            log.debug("[NEWS] {} not in instrument cache — skip", symbol);
            return false;
        }
        long instrumentToken = inst.getInstrument_token();

        // Estimate current price from instrument or use last known
        // In paper mode we use a reasonable estimate — PaperTradeExecutionService
        // will apply actual slippage on fill. We derive entry from the last tick
        // price cached in InstrumentCacheService if available, else skip.
        BigDecimal entryPrice = resolveCurrentPrice(symbol, inst);
        if (entryPrice == null || entryPrice.compareTo(BigDecimal.valueOf(100)) < 0) {
            log.debug("[NEWS] {} price unavailable or below ₹100 — skip", symbol);
            return false;
        }

        boolean isBuy = score.direction() == TradeDirection.LONG;

        // Compute SL and targets
        BigDecimal risk   = entryPrice.multiply(BigDecimal.valueOf(SL_PCT))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal stopLoss = isBuy
                ? entryPrice.subtract(risk).setScale(2, RoundingMode.FLOOR)
                : entryPrice.add(risk).setScale(2, RoundingMode.CEILING);
        BigDecimal target1 = isBuy
                ? entryPrice.add(risk.multiply(BigDecimal.valueOf(T1_RR)))
                .setScale(2, RoundingMode.HALF_UP)
                : entryPrice.subtract(risk.multiply(BigDecimal.valueOf(T1_RR)))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal target2 = isBuy
                ? entryPrice.add(risk.multiply(BigDecimal.valueOf(T2_RR)))
                .setScale(2, RoundingMode.HALF_UP)
                : entryPrice.subtract(risk.multiply(BigDecimal.valueOf(T2_RR)))
                .setScale(2, RoundingMode.HALF_UP);

        // Position sizing
        PositionSizerService.PositionSize pos =
                positionSizer.calculate(cap, entryPrice, stopLoss, symbol,
                        score.direction().name());
        if (!pos.isValid() || pos.quantity() <= 0) {
            log.debug("[NEWS] {} position sizing failed: {}", symbol, pos.invalidReason());
            return false;
        }

        // Sector data for signal enrichment
        String sectorName  = score.sectorName();
        double sectorChg   = 0.0;
        try {
            sectorChg = sectorStrength.getSector(sectorName).changePercent();
        } catch (Exception ignored) {}

        // Score components for signal
        int scoreCategory  = score.categoryScore();
        int scoreSentiment = score.sentimentScore();
        int scoreRecency   = score.recencyScore();
        int scoreSource    = score.sourceScore();
        int scoreKeyword   = score.keywordScore();
        int totalScore     = score.totalScore();

        log.info("[NEWS] 🚀 SIGNAL: {} | dir={} | entry={} | sl={} | T1={} | T2={} | " +
                        "score={} | category={} | sentiment={} | age={}min | headline: \"{}\"",
                symbol, score.direction(), entryPrice, stopLoss, target1, target2,
                totalScore, score.primaryCategory(), score.dominantSentiment(),
                score.ageMinutes(), truncate(score.primaryHeadline(), 60));

        // Build and publish the signal event (identical structure to all other strategies)
        SmartChannelPullbackSignalEvent signal = new SmartChannelPullbackSignalEvent(
                this,
                symbol,
                instrumentToken,
                score.direction(),
                entryPrice,
                stopLoss,
                target1,
                target2,
                pos.quantity(),
                pos.actualRisk(),
                STRATEGY_NAME,
                totalScore,
                sectorName,
                sectorChg,
                score.primaryCategory().name(),       // channelQuality → category
                "NEWS_CATALYST",                      // signalType
                (double) totalScore / 100.0,          // pressureRatio → normalized score
                (double) score.recencyScore() / 20.0, // rvol → recency proxy
                score.corroborated(),                  // strongTrend → corroborated flag
                "MARKET",                              // entryMode
                score.direction() == TradeDirection.LONG
                        ? "NEWS_" + score.primaryCategory().name() + "_LONG"
                        : "NEWS_" + score.primaryCategory().name() + "_SHORT",
                0,                                     // candleCloseDelay
                scoreCategory,
                scoreSentiment,
                scoreRecency,
                scoreSource,
                scoreKeyword,
                totalScore,
                TIME_STOP_MIN
        );

        publisher.publishEvent(signal);

        // Track state
        firedToday.add(symbol);
        activeSignals.add(symbol);
        sessionSignalCount.incrementAndGet();

        // Record for dashboard
        recentEvents.add(0, new NewsEventSnapshot(
                symbol, score.direction(), score.primaryCategory().name(),
                score.dominantSentiment().name(), totalScore,
                score.primaryHeadline(), score.ageMinutes(),
                java.time.LocalTime.now(IST).toString()
        ));
        if (recentEvents.size() > 20) recentEvents.subList(20, recentEvents.size()).clear();

        log.info("[NEWS] Signal #{}/{} fired for {} (session total)",
                sessionSignalCount.get(), maxSignalsPerSession, symbol);
        return true;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private boolean isDirectionAligned(TradeDirection signalDir,
                                       MarketDirectionService.Direction marketDir) {
        if (marketDir == MarketDirectionService.Direction.BULLISH &&
                signalDir == TradeDirection.SHORT) return false;
        if (marketDir == MarketDirectionService.Direction.BEARISH &&
                signalDir == TradeDirection.LONG)  return false;
        return true;
    }

    private BigDecimal resolveCapital() {
        return "PAPER".equalsIgnoreCase(tradingMode)
                ? paperAccount.getCapital()
                : configuredCapital;
    }

    /**
     * Attempts to resolve the current price for a symbol.
     * Uses instrument's last price if available, else returns null.
     * PaperTradeExecutionService will use LTP from the tick stream at fill time.
     */
    private BigDecimal resolveCurrentPrice(String symbol, Instrument inst) {
        try {
            // Zerodha Instrument field is last_price — getter is getLast_price()
            double ltp = inst.getLast_price();
            if (ltp > 0) {
                return BigDecimal.valueOf(ltp).setScale(2, RoundingMode.HALF_UP);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String truncate(String s, int len) {
        return s == null ? "" : (s.length() > len ? s.substring(0, len) + "…" : s);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SIGNAL LIFECYCLE — called back from SmartChannelSignalHandler
    // ══════════════════════════════════════════════════════════════════════════

    public void onSignalClosed(String symbol) {
        activeSignals.remove(symbol);
        log.debug("[NEWS] Signal lock released for {}", symbol);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DAILY RESET
    // ══════════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyReset() {
        firedToday.clear();
        activeSignals.clear();
        sessionSignalCount.set(0);
        recentEvents.clear();
        log.info("[NEWS] Daily reset complete");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DASHBOARD API (read-only)
    // ══════════════════════════════════════════════════════════════════════════

    public int     getSessionSignalCount()    { return sessionSignalCount.get(); }
    public int     getMaxSignalsPerSession()  { return maxSignalsPerSession; }
    public boolean isEnabled()               { return engineEnabled; }
    public int     getActiveItemCount()       { return ingestionService.getActiveItems().size(); }
    public int     getTotalIngested()         { return ingestionService.getTotalIngested(); }
    public List<NewsEventSnapshot> getRecentEvents() {
        return Collections.unmodifiableList(recentEvents);
    }
    public Set<String> getFiredToday()       { return Collections.unmodifiableSet(firedToday); }

    // ══════════════════════════════════════════════════════════════════════════
    // DASHBOARD SNAPSHOT RECORD
    // ══════════════════════════════════════════════════════════════════════════

    /** Read-only snapshot of a fired news signal — for dashboard display only */
    public record NewsEventSnapshot(
            String symbol,
            TradeDirection direction,
            String category,
            String sentiment,
            int score,
            String headline,
            long ageMinutes,
            String firedAt
    ) {}
}