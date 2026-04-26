package com.trading.strategy.smc;

import com.trading.domain.Candle;
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
import com.zerodhatech.models.Instrument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * BestTradeStrategy — SMC-based 1-trade-per-day strategy (BEST_TRADE_V1).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * PHILOSOPHY:
 *   Quality > Quantity. This strategy scans ALL 295 symbols every 15 minutes,
 *   applies 5 hard rules (HTF trend, MTF structure, FVG, ADX, liquidity sweep),
 *   scores qualifying setups, and executes EXACTLY ONE trade per day — the
 *   highest-scoring setup. On days with no qualifying setup it logs NO_TRADE.
 *
 * INTEGRATION:
 *   - Publishes SmartChannelPullbackSignalEvent (same as all other strategies)
 *   - Goes through SmartChannelSignalHandler → PaperTradeExecutionService
 *   - Uses same RiskManagementService, CircuitBreakerService, PositionSizerService
 *   - Zero changes to any existing strategy or service
 *
 * EXECUTION SCHEDULE:
 *   Scans at 9:30, 9:45, 10:00, 10:15... every 15 minutes until 14:45.
 *   Once 1 trade is executed, skips all subsequent cycles today.
 *   No trade is forced — if nothing qualifies, sits out cleanly.
 *
 * LATENCY NOTES:
 *   Parallel scan via stream().parallel() on 295 symbols.
 *   Each symbol analysis: ~2ms (pure Java math, no I/O).
 *   Total scan: ~50ms on a quad-core system (295/4 parallel).
 *   Signal-to-execution path: O(1) via Spring event bus (same JVM).
 * ─────────────────────────────────────────────────────────────────────────────
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BestTradeStrategy {

    private static final String   STRATEGY_NAME = "BEST_TRADE_V1";
    private static final ZoneId   IST           = ZoneId.of("Asia/Kolkata");
    private static final LocalTime TRADE_START  = LocalTime.of(9, 30);
    private static final LocalTime TRADE_END    = LocalTime.of(14, 45);

    // ── Dependencies ──────────────────────────────────────────────────────────
    private final SmcCandleStore          candleStore;
    private final SmcAnalyser             analyser;
    private final InstrumentCacheService  instrumentCache;
    private final ApplicationEventPublisher publisher;
    private final CircuitBreakerService   circuitBreaker;
    private final PositionSizerService    positionSizer;
    private final PaperAccount            paperAccount;
    private final MarketDirectionService  marketDirection;
    private final MarketTimingService     timingService;
    private final RiskManagementService   riskManagement;
    private final SectorClassificationService sectorClassify;

    // ── Config ────────────────────────────────────────────────────────────────
    @Value("${strategy.best-trade.enabled:true}")
    private boolean enabled;

    @Value("${strategy.best-trade.min-score:4}")
    private int minScore;

    @Value("${strategy.best-trade.min-stock-price:100.0}")
    private double minStockPrice;

    @Value("${trading.mode:PAPER}")
    private String tradingMode;

    @Value("${trading.capital:100000}")
    private BigDecimal configuredCapital;

    // ── Per-session state ──────────────────────────────────────────────────────
    private final AtomicBoolean tradeFiredToday  = new AtomicBoolean(false);
    private final AtomicInteger cyclesRun        = new AtomicInteger(0);
    private final AtomicInteger totalScanned     = new AtomicInteger(0);
    private final AtomicInteger totalQualified   = new AtomicInteger(0);

    /** Track average volume per symbol — refreshed daily from 5-day candle volume */
    private final Map<String, Double> avgVolMap  = new ConcurrentHashMap<>();

    /** Last scan result for dashboard */
    private volatile String lastScanSummary      = "Awaiting first scan";
    private volatile SmcSetupScore lastBestSetup = null;
    private volatile String lastNoTradeReason    = "No scan run yet";

    // ══════════════════════════════════════════════════════════════════════════
    // MAIN SCAN CYCLE — every 15 minutes from 9:30 to 14:45
    // ══════════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 30/15 9-14 * * MON-FRI", zone = "Asia/Kolkata")
    public void runScanCycle() {
        if (!enabled) {
            log.trace("[BEST-TRADE] Strategy disabled — skipping cycle");
            return;
        }

        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(TRADE_START) || now.isAfter(TRADE_END)) return;

        if (tradeFiredToday.get()) {
            log.debug("[BEST-TRADE] Trade already executed today — skipping scan");
            return;
        }

        int cycle = cyclesRun.incrementAndGet();
        log.info("[BEST-TRADE] ═══ Scan cycle #{} @ {} ═══", cycle, now);

        // ── Gate: circuit breaker (daily loss / trade count) ──────────────────
        BigDecimal cap = resolveCapital();
        CircuitBreakerService.Permission perm = circuitBreaker.checkPermission(cap);
        if (!perm.isAllowed()) {
            log.warn("[BEST-TRADE] Circuit breaker blocked: {}", perm.reason());
            lastNoTradeReason = "Circuit breaker: " + perm.reason();
            return;
        }

        // ── Gate: market must not be SIDEWAYS ────────────────────────────────
        MarketDirectionService.MarketDirectionResult dir =
                marketDirection.getCurrentDirection();
        if (dir.direction() == MarketDirectionService.Direction.SIDEWAYS) {
            log.info("[BEST-TRADE] Market SIDEWAYS (ATR={}%) — higher-quality conditions needed",
                    String.format("%.2f", dir.niftyAtrPct()));
            lastNoTradeReason = "Market SIDEWAYS — waiting for directional trend";
            return;
        }

        // ── Parallel scan of all symbols ─────────────────────────────────────
        Map<String, Instrument> instruments = instrumentCache.getEquityInstruments();
        Set<String> symbols = instruments.keySet();

        log.info("[BEST-TRADE] Scanning {} symbols for SMC setups...", symbols.size());
        totalScanned.set(symbols.size());

        long scanStart = System.currentTimeMillis();

        // Run in parallel — each analyse() call is stateless and thread-safe
        List<SmcSetupScore> qualified = symbols.parallelStream()
                .map(sym -> analyseSymbol(sym, instruments.get(sym)))
                .filter(Objects::nonNull)
                .filter(SmcSetupScore::passesAllRules)
                .filter(s -> s.totalScore() >= minScore)
                .sorted(Comparator.comparingInt(SmcSetupScore::totalScore).reversed())
                .toList();

        long scanMs = System.currentTimeMillis() - scanStart;
        totalQualified.set(qualified.size());

        log.info("[BEST-TRADE] Scan complete in {}ms — {}/{} symbols qualified (score ≥ {})",
                scanMs, qualified.size(), symbols.size(), minScore);

        // ── Log funnel stats ──────────────────────────────────────────────────
        lastScanSummary = String.format(
                "Cycle#%d @%s | scanned=%d qualified=%d scanMs=%d",
                cycle, now, symbols.size(), qualified.size(), scanMs);

        if (qualified.isEmpty()) {
            log.info("[BEST-TRADE] NO TRADE TODAY (cycle #{}) — no setup passed all 5 rules with score ≥ {}",
                    cycle, minScore);
            lastNoTradeReason = String.format(
                    "Cycle %d: %d symbols scanned — 0 qualified (score ≥ %d)",
                    cycle, symbols.size(), minScore);
            logNoTrade(symbols.size(), 0);
            return;
        }

        // ── Pick TOP 1 ────────────────────────────────────────────────────────
        SmcSetupScore best = qualified.get(0);
        lastBestSetup = best;

        log.info("[BEST-TRADE] ★ BEST SETUP: {}", best.toLogString());
        log.info("[BEST-TRADE] Top 3 candidates:");
        qualified.stream().limit(3).forEach(s ->
                log.info("[BEST-TRADE]   #{}: {}", qualified.indexOf(s) + 1, s.toLogString()));

        // ── Final pre-trade checks ────────────────────────────────────────────
        if (riskManagement.isSymbolAlreadyActive(best.symbol())) {
            log.warn("[BEST-TRADE] Best setup {} is already active in another strategy — skip",
                    best.symbol());
            lastNoTradeReason = best.symbol() + " already held by " +
                    riskManagement.getActiveStrategyForSymbol(best.symbol());
            return;
        }

        // ── Fire signal ───────────────────────────────────────────────────────
        fireSignal(best, instruments.get(best.symbol()), cap);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SYMBOL ANALYSIS
    // ══════════════════════════════════════════════════════════════════════════

    private SmcSetupScore analyseSymbol(String symbol, Instrument inst) {
        try {
            // Price guard — skip penny stocks
            double lastPrice = inst != null ? inst.getLast_price() : 0.0;
            if (lastPrice < minStockPrice) return null;

            // Get multi-timeframe candles
            List<Candle> h4  = candleStore.get4H(symbol);
            List<Candle> h1  = candleStore.get1H(symbol);
            List<Candle> m15 = candleStore.get15M(symbol);

            if (!candleStore.isReady(symbol)) {
                log.trace("[BEST-TRADE] {} — insufficient candle data (4H={} 1H={} 15M={})",
                        symbol, h4.size(), h1.size(), m15.size());
                return null;
            }

            double avgVol = avgVolMap.getOrDefault(symbol, 0.0);
            SmcSetupScore score = analyser.analyse(symbol, h4, h1, m15, avgVol);

            if (score.passesAllRules()) {
                log.debug("[BEST-TRADE] ✓ {} passes all rules — score={}/9",
                        symbol, score.totalScore());
            }
            return score;

        } catch (Exception e) {
            log.trace("[BEST-TRADE] {} analysis exception: {}", symbol, e.getMessage());
            return null;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SIGNAL FIRING
    // ══════════════════════════════════════════════════════════════════════════

    private void fireSignal(SmcSetupScore setup, Instrument inst, BigDecimal cap) {
        String symbol  = setup.symbol();
        TradeDirection direction = setup.direction();

        // Resolve instrument token
        long token = inst != null ? inst.getInstrument_token() : 0L;

        // Position sizing
        PositionSizerService.PositionSize pos = positionSizer.calculate(
                cap, setup.entryPrice(), setup.stopLoss(), symbol, direction.name());

        if (!pos.isValid() || pos.quantity() <= 0) {
            log.warn("[BEST-TRADE] {} position sizing failed: {}", symbol, pos.invalidReason());
            lastNoTradeReason = symbol + " position sizing failed: " + pos.invalidReason();
            return;
        }

        // Sector info
        String sectorName = sectorClassify.getSector(symbol);
        if (sectorName == null) sectorName = "N/A";

        // Risk distance as sector-change proxy
        double riskPct = setup.atr14() > 0
                ? setup.atr14() * 1.5 / setup.currentPrice() * 100
                : 0.5;

        // Score component breakdown
        int scoreStrongFvg  = setup.scoreStrongFvg();
        int scoreVwap       = setup.scoreVwapSide();
        int scoreSr         = setup.scoreSrDistance();
        int scoreVol        = setup.scoreVolume();
        int scoreAdx25      = setup.scoreAdx25();
        int scoreSweepFresh = setup.scoreSweepFresh();
        int scoreFvgFresh   = setup.scoreFvgFresh();
        int totalScore      = setup.totalScore();

        log.info("[BEST-TRADE] 🚀 FIRING SIGNAL: {} | {} | score={}/9 | " +
                        "entry={} sl={} t1={} t2={} | qty={} | reasons: {}",
                symbol, direction, totalScore,
                setup.entryPrice(), setup.stopLoss(), setup.target1(), setup.target2(),
                pos.quantity(), setup.reasons());

        SmartChannelPullbackSignalEvent signal = new SmartChannelPullbackSignalEvent(
                this,
                symbol,
                token,
                direction,
                setup.entryPrice(),
                setup.stopLoss(),
                setup.target1(),
                setup.target2(),
                pos.quantity(),
                pos.actualRisk(),
                STRATEGY_NAME,
                totalScore,
                sectorName,
                riskPct,                             // gapPct proxy
                "SMC_BEST",                          // channelQuality
                "HTF+MTF+FVG+ADX+SWEEP",             // signalType
                (double) totalScore / 9.0,           // pressureRatio → normalized score
                setup.adxValue() / 100.0,            // rvol proxy → ADX normalized
                setup.rule5LiqSweep(),               // strongTrend → sweep confirmed
                "MARKET",                            // entryMode
                direction == TradeDirection.LONG
                        ? "SMC_LONG_FVG_SWEEP" : "SMC_SHORT_FVG_SWEEP",
                0,                                   // candleCloseDelay
                scoreStrongFvg,                      // score component 1
                scoreVwap,                           // score component 2
                scoreSr,                             // score component 3
                scoreVol,                            // score component 4
                scoreAdx25 + scoreSweepFresh + scoreFvgFresh, // score component 5 (trend quality)
                totalScore,                          // total composite score
                0                                    // timeStopMinutes: 0 = 3PM EOD stop
        );

        publisher.publishEvent(signal);

        tradeFiredToday.set(true);
        log.info("[BEST-TRADE] ✅ Signal published — NO MORE TRADES today (1-trade limit)");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // NO TRADE LOGGING
    // ══════════════════════════════════════════════════════════════════════════

    private void logNoTrade(int scanned, int qualified) {
        log.info("[BEST-TRADE] ──────────────────────────────────────");
        log.info("[BEST-TRADE]   STATUS        : NO TRADE TODAY");
        log.info("[BEST-TRADE]   Symbols scanned : {}", scanned);
        log.info("[BEST-TRADE]   Qualified score : {}", qualified);
        log.info("[BEST-TRADE]   Reason          : {}", lastNoTradeReason);
        log.info("[BEST-TRADE]   Capital safe    : ₹{}", resolveCapital());
        log.info("[BEST-TRADE] ──────────────────────────────────────");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // VOLUME AVERAGE MAINTENANCE
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Refresh 20-day average volume map from 5-day 15min candle history.
     * Called at 9:20 AM so the data is ready before the first scan at 9:30.
     */
    @Scheduled(cron = "0 20 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void refreshAvgVolume() {
        log.info("[BEST-TRADE] Refreshing average volume map...");
        Map<String, Instrument> instruments = instrumentCache.getEquityInstruments();
        int updated = 0;

        for (String sym : instruments.keySet()) {
            List<Candle> m15 = candleStore.get15M(sym);
            if (m15.size() >= 10) {
                // Use last 26 candles (approx 1 day of 15min candles) as avg volume
                double totalVol = m15.stream()
                        .limit(Math.min(26, m15.size()))
                        .mapToDouble(c -> (double) c.getVolume())
                        .sum();
                double avgVol = totalVol / Math.min(26, m15.size());
                if (avgVol > 0) {
                    avgVolMap.put(sym, avgVol);
                    updated++;
                }
            }
        }
        log.info("[BEST-TRADE] Average volume refreshed for {} symbols", updated);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DAILY RESET
    // ══════════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyReset() {
        tradeFiredToday.set(false);
        cyclesRun.set(0);
        totalScanned.set(0);
        totalQualified.set(0);
        lastBestSetup    = null;
        lastNoTradeReason = "Day reset — awaiting scan";
        lastScanSummary  = "Day reset";
        log.info("[BEST-TRADE] Daily reset complete");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private BigDecimal resolveCapital() {
        return "PAPER".equalsIgnoreCase(tradingMode)
                ? paperAccount.getCapital()
                : configuredCapital;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DASHBOARD API (read-only)
    // ══════════════════════════════════════════════════════════════════════════

    public boolean  isTradeFiredToday()    { return tradeFiredToday.get(); }
    public int      getCyclesRun()         { return cyclesRun.get(); }
    public int      getTotalScanned()      { return totalScanned.get(); }
    public int      getTotalQualified()    { return totalQualified.get(); }
    public String   getLastScanSummary()   { return lastScanSummary; }
    public String   getLastNoTradeReason() { return lastNoTradeReason; }
    public SmcSetupScore getLastBestSetup(){ return lastBestSetup; }
    public boolean  isEnabled()            { return enabled; }
}