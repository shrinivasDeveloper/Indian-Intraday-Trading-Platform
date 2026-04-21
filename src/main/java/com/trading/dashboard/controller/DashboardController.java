package com.trading.dashboard.controller;

import com.trading.execution.service.TradeExecutionService;
import com.trading.execution.service.TradeManagementService;
import com.trading.marketdata.service.LatencyMonitor;
import com.trading.marketdata.service.MarketDataService;
import com.trading.marketdata.service.MarketTimingService;
import com.trading.marketdata.service.VixService;
import com.trading.papertrading.service.PaperTradeExecutionService;
import com.trading.papertrading.service.PaperTradeManagementService;
import com.trading.ranking.service.StockRankingEngine;
import com.trading.regime.service.BankNiftyModeEngine;
import com.trading.regime.service.MarketDirectionService;
import com.trading.regime.service.MarketModeEngine;
import com.trading.regime.service.MarketPhaseEngine;
import com.trading.risk.service.CircuitBreakerService;
import com.trading.risk.service.RiskManagementService;
import com.trading.sector.service.SectorClassificationService;
import com.trading.sector.service.SectorStrengthService;
import com.trading.strategy.channel.ChannelDetectionService;
import com.trading.strategy.channel.SmartChannelPullbackStrategy;
import com.trading.strategy.channel.SmartChannelSignalHandler;
import com.trading.strategy.highrr.HighRRStrategyEngine;
import com.trading.strategy.highrr.HighRRTradeManager;
import com.trading.strategy.orb.OrbDataService;
import com.trading.strategy.orb.OrbStrategyEngine;
import com.trading.validation.StrategyValidationTracker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * DashboardController — v9.1
 *
 * FIXES vs v9.0:
 *   - FIX 1: HighRRStrategyEngine injected → highRR section added to strategyStatus
 *   - FIX 2: OrbDataService + OrbStrategyEngine injected → orbData section added
 *   - FIX 3: todayTrades now reads from paperExecution in PAPER mode
 *             (was reading from tradeExecution = LIVE-only, always empty in PAPER)
 *   - FIX 4: buildStrategyPnl() now reads from paperExecution in PAPER mode
 *             (perStrategyPnl was always {} in PAPER because LIVE service had no trades)
 *   - FIX 5: paperTradesHistory now returns paperExecution trades (correct service)
 *             HighRR trades are stored in HighRRTradeManager which is SELF_MANAGED;
 *             they appear in paperExecution when HighRROrderExecutionService registers
 *             them — if they still show empty, that is a HighRRTradeManager visibility
 *             issue separate from the dashboard.
 *   - FIX 6: strategySummary now includes HIGH_RR_INTRADAY_V1 entry
 */
@RestController
@RequestMapping("/api/dashboard")
@Slf4j
@RequiredArgsConstructor
public class DashboardController {

    // ── Core services ─────────────────────────────────────────────────────
    private final TradeExecutionService       tradeExecution;
    private final TradeManagementService      tradeManagement;
    private final MarketDataService           marketDataService;
    private final VixService                  vixService;
    private final MarketTimingService         timingService;
    private final MarketDirectionService      marketDir;
    private final CircuitBreakerService       circuitBreaker;
    private final SectorStrengthService       sectorStrength;
    private final SectorClassificationService sectorClassify;

    // ── Mode engines ──────────────────────────────────────────────────────
    private final MarketModeEngine            marketModeEngine;
    private final MarketPhaseEngine           marketPhaseEngine;
    private final BankNiftyModeEngine         bankNiftyModeEngine;

    // ── v7+ services ──────────────────────────────────────────────────────
    private final StockRankingEngine          rankingEngine;
    private final LatencyMonitor              latencyMonitor;
    private final PaperTradeExecutionService  paperExecution;
    private final PaperTradeManagementService paperManagement;
    private final RiskManagementService       riskManagement;
    private final StrategyValidationTracker   validationTracker;

    // ── SmartChannelPullback strategy ─────────────────────────────────────
    private final SmartChannelPullbackStrategy smartChannelPullbackStrategy;
    private final SmartChannelSignalHandler    smartChannelSignalHandler;
    private final ChannelDetectionService      channelDetectionService;

    // ── FIX 1: HighRR strategy engine ─────────────────────────────────────
    private final HighRRStrategyEngine         highRRStrategyEngine;

    // ── HighRR trade manager — exposes active/closed trade details ─────────
    // This is the ONLY source of truth for HighRR trade data.
    // HighRR is self-managed: trades bypass PaperTradeExecutionService entirely
    // and live exclusively in HighRRTradeManager. Without this injection,
    // paperTrades, todayTrades, and perStrategyPnl all show empty for HighRR.
    private final HighRRTradeManager           highRRTradeManager;

    // ── FIX 2: ORB strategy services ──────────────────────────────────────
    private final OrbDataService               orbDataService;
    private final OrbStrategyEngine            orbStrategyEngine;

    @Value("${trading.capital:100000}") private BigDecimal capital;
    @Value("${trading.mode:PAPER}")     private String     tradingMode;

    // ══════════════════════════════════════════════════════════════════════
    // GET /api/dashboard/snapshot
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/snapshot")
    public ResponseEntity<Map<String, Object>> snapshot() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("timestamp",   Instant.now().toString());
        data.put("tradingMode", tradingMode);

        MarketDirectionService.MarketDirectionResult dir = marketDir.getCurrentDirection();
        double  vix    = vixService.getCurrentVix();
        String  vixReg = vixService.getRegime().name();
        String  window = timingService.getCurrentWindowName();
        boolean entry  = timingService.isEntryAllowed();
        boolean wsOk   = marketDataService.isConnected();

        // 1. market
        Map<String, Object> market = new LinkedHashMap<>();
        market.put("direction",    dir.direction().name());
        market.put("label",        regimeLabel(dir.direction()));
        market.put("niftyBullish", dir.niftyBullish());
        market.put("niftyBearish", dir.niftyBearish());
        market.put("niftyEma20",   round2(dir.niftyEma20()));
        market.put("niftyEma50",   round2(dir.niftyEma50()));
        market.put("niftyEma200",  round2(dir.niftyEma200()));
        market.put("niftyAtrPct",  round2(dir.niftyAtrPct()));
        market.put("failReason",   nullSafe(dir.failReason()));
        market.put("vix",          vix);
        market.put("vixRegime",    vixReg);
        market.put("window",       window);
        market.put("entryAllowed", entry);
        data.put("market", market);

        // 2. system
        Map<String, Object> system = new LinkedHashMap<>();
        system.put("websocketConnected", wsOk);
        system.put("reconnectCount",     marketDataService.getReconnectCount());
        system.put("vix",                vix);
        system.put("vixRegime",          vixReg);
        system.put("currentWindow",      window);
        system.put("entryAllowed",       entry);
        data.put("system", system);

        // 3. regime
        Map<String, Object> regime = new LinkedHashMap<>();
        regime.put("name",       dir.direction().name());
        regime.put("label",      regimeLabel(dir.direction()));
        regime.put("failReason", nullSafe(dir.failReason()));
        data.put("regime", regime);

        // 4. marketDirection
        Map<String, Object> md = new LinkedHashMap<>();
        md.put("niftyBullish", dir.niftyBullish());
        md.put("niftyBearish", dir.niftyBearish());
        md.put("niftyEma20",   round2(dir.niftyEma20()));
        md.put("niftyEma50",   round2(dir.niftyEma50()));
        md.put("niftyEma200",  round2(dir.niftyEma200()));
        md.put("niftyAtrPct",  round2(dir.niftyAtrPct()));
        md.put("failReason",   nullSafe(dir.failReason()));
        data.put("marketDirection", md);

        // 5. P&L
        BigDecimal dailyPnl   = circuitBreaker.getDailyPnl();
        BigDecimal weeklyPnl  = circuitBreaker.getWeeklyPnl();
        BigDecimal monthlyPnl = circuitBreaker.getMonthlyPnl();
        Map<String, Object> pnl = new LinkedHashMap<>();
        pnl.put("capital",         capital);
        pnl.put("dailyPnl",        dailyPnl);
        pnl.put("weeklyPnl",       weeklyPnl);
        pnl.put("monthlyPnl",      monthlyPnl);
        pnl.put("dailyPct",        pct(dailyPnl, capital));
        pnl.put("tradesToday",     circuitBreaker.getTradesToday());
        pnl.put("maxTradesPerDay", circuitBreaker.getMaxPerDay());
        pnl.put("cbActive",        circuitBreaker.isActive());
        pnl.put("cbReason",        nullSafe(circuitBreaker.getDisableReason()));
        data.put("pnl", pnl);

        // 6. sectors
        List<Map<String, Object>> sectors = new ArrayList<>();
        for (String sn : sectorClassify.getAllSectorNames()) {
            SectorStrengthService.SectorData sd = sectorStrength.getSector(sn);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name",             sn);
            s.put("classification",   sectorLabel(sd));
            s.put("changePercent",    round2(sd.changePercent()));
            s.put("relativeStrength", round2(sd.relativeStrength()));
            s.put("greenPct",         String.format("%.1f", sd.greenPct()));
            s.put("totalStocks",      sd.totalStocks());
            s.put("greenStocks",      sd.greenStocks());
            s.put("redStocks",        sd.redStocks());
            s.put("alignedBullish",   sd.alignedBullish());
            s.put("alignedBearish",   sd.alignedBearish());
            s.put("isTopSector",      sd.isTopSector());
            s.put("isBottomSector",   sd.isBottomSector());
            sectors.add(s);
        }
        data.put("sectors", sectors);

        // 7. active trades
        Map<String, BigDecimal> prices = marketDataService.getLastPricesSimple();
        data.put("activeTrades", buildActiveTrades(prices));

        // 8. today's closed trades — paper execution (SCPS/ORB) + HighRR closed snapshots
        if ("PAPER".equalsIgnoreCase(tradingMode)) {
            // PaperTradeExecutionService returns List<Trade> (domain entity) for SCPS/ORB.
            // HighRRTradeManager returns List<HighRRClosedTrade> (own record type).
            // We keep them separate in a unified list by converting HighRR to plain maps.
            List<Object> merged = new ArrayList<>();
            merged.addAll(paperExecution.getTodayTrades(LocalDate.now()));
            for (HighRRTradeManager.HighRRClosedTrade ct : highRRTradeManager.getClosedTrades()) {
                merged.add(buildClosedTradeMap(ct));
            }
            data.put("todayTrades", merged);
        } else {
            data.put("todayTrades", tradeExecution.getTodayTrades(LocalDate.now()));
        }

        // 9. strategy status — SmartChannelPullback + HighRR + ORB
        Map<String, Object> strategyStatus = new LinkedHashMap<>();
        strategyStatus.put("firedToday",      buildFiredToday());
        strategyStatus.put("perStrategyPnl",  buildStrategyPnl());
        strategyStatus.put("strategySummary", buildStrategySummary());
        strategyStatus.put("smartChannelPullback", buildSmartChannelStatus());
        // FIX 1: HighRR strategy data
        strategyStatus.put("highRR", buildHighRRStatus());
        data.put("strategyStatus", strategyStatus);

        // 10. marketMode (Nifty)
        data.put("marketMode", buildMarketModeMap(marketModeEngine.getCurrentMode()));

        // 11. marketPhase
        data.put("marketPhase", buildPhaseMap());

        // 12. bankNiftyMode
        data.put("bankNiftyMode", buildMarketModeMap(bankNiftyModeEngine.getCurrentMode()));

        // 13. latency
        data.put("latency", buildLatencyMap());

        // 14. stockRankings
        data.put("stockRankings", buildRankingList(10));
        data.put("allRankings",   rankingEngine.getAllRankings());

        // 15. risk slot counters
        Map<String, Object> riskSlots = new LinkedHashMap<>();
        riskSlots.put("phase1Count",      riskManagement.getPhase1Count());
        riskSlots.put("maxPhase1",        riskManagement.getMaxPhase1());
        riskSlots.put("sectorExposure",   riskManagement.getSectorExposure());
        riskSlots.put("strategyExposure", riskManagement.getStrategyExposure());
        riskSlots.put("anyAtBreakeven",   paperManagement.isAnyTradeAtBreakevenOrBeyond());
        data.put("riskSlots", riskSlots);

        // 16. paper trades (active)
        data.put("paperTrades", buildPaperTrades(prices));

        // FIX 5: paper trades history — standard paper trades + HighRR closed snapshots
        if ("PAPER".equalsIgnoreCase(tradingMode)) {
            List<Object> allHistory = new ArrayList<>();
            allHistory.addAll(paperExecution.getAllTrades());
            for (HighRRTradeManager.HighRRClosedTrade ct : highRRTradeManager.getAllClosedTrades()) {
                allHistory.add(buildClosedTradeMap(ct));
            }
            data.put("paperTradesHistory", allHistory);
        } else {
            data.put("paperTradesHistory", Collections.emptyList());
        }

        // 17. validation summary
        data.put("validationFailureFrequency", validationTracker.getFailureFrequency());
        data.put("validationSymbolsTracked",   validationTracker.getTotalSymbolsTracked());

        // 18. channel detection summary
        data.put("channelDetection", buildChannelDetectionSummary());

        // 19. FIX 2: ORB data
        data.put("orbData", buildOrbData());

        // 20. Portfolio daily overview — all trades across all strategies
        //     Combines: ORB (max 2) + HighRR (max 2) + Pullback+Sideways (max 6) = max 10/day
        data.put("portfolioSummary", buildPortfolioSummary(prices));

        return ResponseEntity.ok(data);
    }

    // ══════════════════════════════════════════════════════════════════════
    // GET /api/dashboard/strategy/smart-channel-pullback
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/strategy/smart-channel-pullback")
    public ResponseEntity<Map<String, Object>> smartChannelStatus() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("timestamp",       Instant.now().toString());
        data.putAll(buildSmartChannelStatus());

        Map<String, Object> channels = new LinkedHashMap<>();
        channelDetectionService.getAllValidChannels().forEach((sym, ch) -> {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("type",              ch.type().name());
            c.put("validity",          ch.validity().name());
            c.put("supportPrice",      round2(ch.supportPrice()));
            c.put("resistancePrice",   round2(ch.resistancePrice()));
            c.put("channelWidthPct",   round2(ch.channelWidthPct()));
            c.put("pullbackZoneTop",   round2(ch.pullbackZoneTop()));
            c.put("pullbackZoneBottom",round2(ch.pullbackZoneBottom()));
            c.put("supportTouches",    ch.supportLine() != null ? ch.supportLine().touches() : 0);
            c.put("resistanceTouches", ch.resistanceLine() != null ? ch.resistanceLine().touches() : 0);
            channels.put(sym, c);
        });
        data.put("validChannels", channels);

        return ResponseEntity.ok(data);
    }

    // ══════════════════════════════════════════════════════════════════════
    // GET /api/dashboard/strategy/high-rr  (NEW)
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/strategy/high-rr")
    public ResponseEntity<Map<String, Object>> highRrStatus() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("timestamp", Instant.now().toString());
        data.putAll(buildHighRRStatus());
        return ResponseEntity.ok(data);
    }

    // ══════════════════════════════════════════════════════════════════════
    // GET /api/dashboard/strategy/orb  (NEW)
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/strategy/orb")
    public ResponseEntity<Map<String, Object>> orbStatus() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("timestamp", Instant.now().toString());
        data.putAll(buildOrbData());
        return ResponseEntity.ok(data);
    }

    @GetMapping("/prices")
    public ResponseEntity<Map<String, BigDecimal>> prices() {
        return ResponseEntity.ok(marketDataService.getLastPricesSimple());
    }

    @GetMapping("/phase")
    public ResponseEntity<Map<String, Object>> phase() {
        Map<String, Object> m = buildPhaseMap();
        m.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(m);
    }

    @GetMapping("/ranking")
    public ResponseEntity<Map<String, Object>> ranking() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("candidates",  buildRankingList(20));
        m.put("allRankings", rankingEngine.getAllRankings());
        m.put("timestamp",   Instant.now().toString());
        return ResponseEntity.ok(m);
    }

    @GetMapping("/latency")
    public ResponseEntity<Map<String, Object>> latency() {
        Map<String, Object> m = buildLatencyMap();
        m.put("wsConnected",  marketDataService.isConnected());
        m.put("wsReconnects", marketDataService.getReconnectCount());
        m.put("timestamp",    Instant.now().toString());
        return ResponseEntity.ok(m);
    }

    @GetMapping("/paper-trades")
    public ResponseEntity<Map<String, Object>> paperTrades() {
        Map<String, BigDecimal> prices = marketDataService.getLastPricesSimple();
        List<Object> allHistory = new ArrayList<>();
        allHistory.addAll(paperExecution.getAllTrades());
        for (HighRRTradeManager.HighRRClosedTrade ct : highRRTradeManager.getAllClosedTrades()) {
            allHistory.add(buildClosedTradeMap(ct));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("activeTrades",   buildPaperTrades(prices));
        m.put("closedTrades",   paperExecution.getTodayTrades(LocalDate.now()));
        m.put("highRRTrades",   highRRTradeManager.getClosedTrades().stream()
                .map(this::buildClosedTradeMap).collect(java.util.stream.Collectors.toList()));
        m.put("allTrades",      allHistory);
        m.put("anyAtBreakeven", paperManagement.isAnyTradeAtBreakevenOrBeyond());
        m.put("timestamp",      Instant.now().toString());
        return ResponseEntity.ok(m);
    }

    @GetMapping("/strategy-performance")
    public ResponseEntity<Map<String, Object>> strategyPerformance() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("perStrategyPnl",       buildStrategyPnl());
        resp.put("firedToday",           buildFiredToday());
        resp.put("strategySummary",      buildStrategySummary());
        resp.put("smartChannelPullback", buildSmartChannelStatus());
        resp.put("highRR",               buildHighRRStatus());
        resp.put("timestamp",            Instant.now().toString());
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/validation")
    public ResponseEntity<Map<String, Object>> validation(
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String strategy) {
        Map<String, Object> resp = new LinkedHashMap<>();
        if (symbol != null && !symbol.isBlank()) {
            String sym = symbol.toUpperCase();
            List<Object> symbolLogs = new ArrayList<>();
            validationTracker.getAllLogs().forEach((strat, logs) ->
                    logs.stream()
                            .filter(l -> sym.equals(l.symbol().toUpperCase()))
                            .forEach(symbolLogs::add));
            resp.put("symbol",  symbol);
            resp.put("results", symbolLogs);
        } else if (strategy != null && !strategy.isBlank()) {
            resp.put("strategy", strategy);
            resp.put("results",  validationTracker.getByStrategy(strategy));
        } else {
            Map<String, List<com.trading.validation.SymbolValidationLog>> all =
                    validationTracker.getAllLogs();
            List<Object> recent = new ArrayList<>();
            all.forEach((strat, logs) -> {
                int take = Math.min(10, logs.size());
                for (int i = 0; i < take; i++) recent.add(logs.get(i));
            });
            resp.put("allLogs",          all);
            resp.put("recent",           recent);
            resp.put("failureFrequency", validationTracker.getFailureFrequency());
            resp.put("symbolsTracked",   validationTracker.getTotalSymbolsTracked());
        }
        resp.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/circuit-breaker/reset")
    public ResponseEntity<String> resetCb() {
        log.warn("[DASHBOARD] Circuit breaker manual reset requested");
        return ResponseEntity.ok("Circuit breaker reset acknowledged");
    }

    // ── Private builders ───────────────────────────────────────────────────

    private Map<String, Object> buildSmartChannelStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled",            smartChannelPullbackStrategy.isEnabled());
        m.put("sessionSignalCount", smartChannelPullbackStrategy.getSessionSignalCount());
        m.put("activeSignals",      smartChannelPullbackStrategy.getActiveSignals());
        m.put("activeSignalCount",  smartChannelPullbackStrategy.getActiveSignalCount());
        m.put("openTradesCount",    smartChannelSignalHandler.getOpenTradeCount());
        m.put("validChannels",      channelDetectionService.getValidChannelCount());
        m.put("trackedSymbols",     channelDetectionService.getTrackedSymbolCount());
        return m;
    }

    /**
     * Builds HighRR strategy status for dashboard.
     * Uses HighRRTradeManager.getClosedTrades() → List<HighRRClosedTrade>
     * and getActiveTrades() → Collection<HighRRTrade>.
     * Both are the manager's own record types — no domain Trade entity involved.
     */
    private Map<String, Object> buildHighRRStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled",             highRRStrategyEngine.isEnabled());
        m.put("tradesExecutedToday", highRRStrategyEngine.getTradesExecutedToday());
        m.put("remainingSlots",      highRRStrategyEngine.getRemainingSlots());
        m.put("maxTradesPerDay",     highRRStrategyEngine.getMaxTradesPerDay());
        m.put("dailyLimitReached",   highRRStrategyEngine.isDailyLimitReached());
        m.put("firedToday",          highRRStrategyEngine.getFiredToday());
        m.put("activeSignals",       highRRStrategyEngine.getActiveSignals());
        m.put("activeSignalCount",   highRRStrategyEngine.getActiveSignals().size());
        m.put("strategyName",        "HIGH_RR_INTRADAY_V1");

        // ── P&L from today's closed trades (HighRRClosedTrade) ───────────────
        List<HighRRTradeManager.HighRRClosedTrade> closedToday = highRRTradeManager.getClosedTrades();
        double totalPnl = closedToday.stream()
                .mapToDouble(t -> t.netPnl() != null ? t.netPnl().doubleValue() : 0)
                .sum();
        long wins   = closedToday.stream()
                .filter(t -> t.netPnl() != null && t.netPnl().doubleValue() > 0).count();
        long losses = closedToday.stream()
                .filter(t -> t.netPnl() != null && t.netPnl().doubleValue() <= 0).count();
        int total = closedToday.size();
        m.put("realisedPnl", String.format("%.2f", totalPnl));
        m.put("winsToday",   (int) wins);
        m.put("lossesToday", (int) losses);
        m.put("totalClosed", total);
        m.put("winRate",     total > 0 ? String.format("%.1f%%", (double) wins / total * 100) : "—");

        // ── Active trade snapshots with live unrealised P&L (HighRRTrade) ────
        Map<String, BigDecimal> prices = marketDataService.getLastPricesSimple();
        List<Map<String, Object>> activeDetails = new ArrayList<>();
        for (HighRRTradeManager.HighRRTrade t : highRRTradeManager.getActiveTrades()) {
            BigDecimal ltp = prices.getOrDefault(t.symbol(), t.fillPrice());
            BigDecimal unreal = t.direction().name().equals("LONG")
                    ? ltp.subtract(t.fillPrice()).multiply(BigDecimal.valueOf(t.quantity()))
                    : t.fillPrice().subtract(ltp).multiply(BigDecimal.valueOf(t.quantity()));
            double risk  = t.stopLoss() != null
                    ? t.fillPrice().subtract(t.stopLoss()).abs().doubleValue() : 0;
            double rMult = risk > 0 ? unreal.doubleValue() / risk / t.quantity() : 0;
            Map<String, Object> at = new LinkedHashMap<>();
            at.put("symbol",      t.symbol());
            at.put("direction",   t.direction().name());
            at.put("entryPrice",  t.fillPrice());
            at.put("ltp",         ltp);
            at.put("stopLoss",    t.stopLoss());
            at.put("target",      t.target());
            at.put("quantity",    t.quantity());
            at.put("unrealPnl",   String.format("%.2f", unreal.doubleValue()));
            at.put("rMultiple",   String.format("%.2fR", rMult));
            activeDetails.add(at);
        }
        m.put("activeTradeDetails", activeDetails);

        // ── Closed trade details for session log (HighRRClosedTrade) ─────────
        List<Map<String, Object>> closedDetails = new ArrayList<>();
        for (HighRRTradeManager.HighRRClosedTrade t : closedToday) {
            double pnlVal = t.netPnl() != null ? t.netPnl().doubleValue() : 0;
            double risk   = t.stopLoss() != null && t.entryPrice() != null
                    ? t.entryPrice().subtract(t.stopLoss()).abs().doubleValue() : 0;
            double rMult  = risk > 0 ? pnlVal / risk / t.quantity() : 0;
            Map<String, Object> ct = new LinkedHashMap<>();
            ct.put("symbol",     t.symbol());
            ct.put("direction",  t.direction().name());
            ct.put("entryPrice", t.entryPrice());
            ct.put("exitPrice",  t.exitPrice());
            ct.put("netPnl",     String.format("%.2f", pnlVal));
            ct.put("exitReason", t.exitReason() != null ? t.exitReason() : "—");
            ct.put("quantity",   t.quantity());
            ct.put("rMultiple",  String.format("%.2fR", rMult));
            closedDetails.add(ct);
        }
        m.put("closedTradeDetails", closedDetails);

        return m;
    }

    /**
     * FIX 2: Build ORB strategy data for dashboard.
     * OrbDataService holds the full state: shortlisted stocks, scores, selected symbols,
     * triggered symbols, and per-symbol setup details.
     * OrbStrategyEngine holds execution state: direction lock, executed count, remaining slots.
     */
    private Map<String, Object> buildOrbData() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("prevCloseAvailable", orbDataService.isPrevCloseAvailable());
        m.put("shortlistCount",     orbDataService.getShortlistCount());
        m.put("validOrbCount",      orbDataService.getValidOrbCount());
        m.put("selectedCount",      orbDataService.getSelectedCount());
        m.put("triggeredCount",     orbDataService.getTriggeredCount());
        m.put("orbLocked",          orbDataService.isOrbLocked());
        m.put("selectedSymbols",    orbDataService.getSelectedSymbols());

        // Engine state
        m.put("executedTradesCount", orbStrategyEngine.getExecutedTradesCount());
        m.put("remainingSlots",      orbStrategyEngine.getRemainingSlots());
        String lockedDir = orbStrategyEngine.getLockedDirection() != null
                ? orbStrategyEngine.getLockedDirection().name() : null;
        m.put("lockedDirection", lockedDir);

        // Per-symbol setup details for dashboard cards and table
        List<String> selectedSymbols = orbDataService.getSelectedSymbols();
        Map<String, Object> setups = new LinkedHashMap<>();
        orbDataService.getAllValidOrbData().forEach((symbol, od) -> {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("symbol",           od.symbol);
            s.put("gapPct",           od.gapPct);
            s.put("orbHigh",          od.orbHigh);
            s.put("orbLow",           od.orbLow);
            s.put("orbRange",         od.orbHigh - od.orbLow);
            s.put("rvol",             od.rvol);
            s.put("score",            od.score);
            s.put("cleanCandleCount", od.cleanCandleCount);
            s.put("sectorName",       od.sectorName != null ? od.sectorName : "");
            s.put("sectorAligned",    od.sectorAligned);
            s.put("valid",            od.valid);
            s.put("triggered",        orbDataService.isTriggered(od.symbol));
            s.put("selected",         selectedSymbols.contains(od.symbol));
            setups.put(od.symbol, s);
        });
        m.put("setups", setups);
        return m;
    }

    private Map<String, Object> buildChannelDetectionSummary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("trackedSymbols", channelDetectionService.getTrackedSymbolCount());
        m.put("validChannels",  channelDetectionService.getValidChannelCount());

        List<Map<String, Object>> topChannels = new ArrayList<>();
        channelDetectionService.getAllValidChannels().entrySet().stream()
                .limit(5)
                .forEach(e -> {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("symbol",          e.getKey());
                    c.put("type",            e.getValue().type().name());
                    c.put("validity",        e.getValue().validity().name());
                    c.put("supportPrice",    round2(e.getValue().supportPrice()));
                    c.put("resistancePrice", round2(e.getValue().resistancePrice()));
                    c.put("widthPct",        round2(e.getValue().channelWidthPct()));
                    topChannels.add(c);
                });
        m.put("topChannels", topChannels);
        return m;
    }

    /**
     * FIX 6: strategySummary now includes both SCPS and HIGH_RR entries.
     */
    private List<Map<String, Object>> buildStrategySummary() {
        List<Map<String, Object>> list = new ArrayList<>();

        // SMART_CHANNEL_PULLBACK
        Map<String, Object> scps = new LinkedHashMap<>();
        scps.put("name",    "SMART_CHANNEL_PULLBACK_V3");
        scps.put("enabled", smartChannelPullbackStrategy.isEnabled());
        scps.put("signals", smartChannelPullbackStrategy.getSessionSignalCount());
        scps.put("active",  smartChannelPullbackStrategy.getActiveSignalCount());
        list.add(scps);

        // HIGH_RR_INTRADAY_V1 — FIX 6
        Map<String, Object> hrr = new LinkedHashMap<>();
        hrr.put("name",    "HIGH_RR_INTRADAY_V1");
        hrr.put("enabled", highRRStrategyEngine.isEnabled());
        hrr.put("signals", highRRStrategyEngine.getTradesExecutedToday());
        hrr.put("active",  highRRStrategyEngine.getActiveSignals().size());
        list.add(hrr);

        // ORB_BREAKOUT_V1
        Map<String, Object> orb = new LinkedHashMap<>();
        orb.put("name",    "ORB_BREAKOUT_V1");
        orb.put("enabled", orbStrategyEngine.isEnabled());
        orb.put("signals", orbStrategyEngine.getExecutedTradesCount());
        orb.put("active",  orbStrategyEngine.getActiveSignalCount());
        list.add(orb);

        return list;
    }

    /**
     * FIX 4 + unified firedToday across all strategies.
     * Combines fired symbols from SCPS, HighRR, and ORB.
     */
    private Set<String> buildFiredToday() {
        Set<String> all = new LinkedHashSet<>();
        all.addAll(smartChannelPullbackStrategy.getActiveSignals());
        all.addAll(highRRStrategyEngine.getFiredToday());
        all.addAll(orbDataService.getSelectedSymbols().stream()
                .filter(s -> orbDataService.isTriggered(s))
                .collect(java.util.stream.Collectors.toSet()));
        return all;
    }

    private Map<String, Object> buildMarketModeMap(MarketModeEngine.MarketModeResult mm) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode",             mm.mode().name());
        m.put("ibHigh",           mm.ibHigh());
        m.put("ibLow",            mm.ibLow());
        m.put("ibRangePct",       Math.round(mm.ibRangePct() * 100.0) / 100.0);
        m.put("ibMid",            mm.ibMid());
        m.put("ibComplete",       mm.ibComplete());
        m.put("ibBrokeHigh",      mm.brokeIbHigh());
        m.put("ibBrokeLow",       mm.brokeIbLow());
        m.put("niftyRvol",        Math.round(mm.niftyRvol() * 10.0) / 10.0);
        m.put("minProbability",   mm.minProbability());
        m.put("riskPct",          mm.riskPct());
        m.put("activeStrategies", mm.activeStrategies());
        m.put("rationale",        mm.rationale());
        m.put("isTradeDay",       mm.isTradeDay());
        m.put("trailEma",         mm.trailEma());
        m.put("exitStrategy",     mm.exitStrategyLabel());
        return m;
    }

    private Map<String, Object> buildPhaseMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("phase",          marketPhaseEngine.getCurrentPhase().name());
        m.put("isEarlyPhase",   marketPhaseEngine.isEarlyPhase());
        m.put("isConfirmed",    marketPhaseEngine.isConfirmedPhase());
        m.put("isTradeAllowed", marketPhaseEngine.isTradeAllowed());
        m.put("isOrbAllowed",   marketPhaseEngine.isOrbAllowed());
        m.put("ibForceNeeded",  marketPhaseEngine.isIbForceNeeded());
        m.put("description",    phaseDescription());
        return m;
    }

    private Map<String, Object> buildLatencyMap() {
        LatencyMonitor.LatencySummary s = latencyMonitor.getSummary();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("isStale",          s.isStale());
        m.put("isCritical",       s.isCritical());
        m.put("lagMs",            s.lagMs());
        m.put("lastCandleTime",   s.lastTickTime());
        m.put("status",           s.status());
        m.put("candlesProcessed", s.candlesProcessed());
        return m;
    }

    private List<Map<String, Object>> buildRankingList(int limit) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (StockRankingEngine.RankedStock rs : rankingEngine.getTopCandidates(limit)) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("symbol",     rs.symbol());
            r.put("rank",       rs.rank());
            r.put("finalScore", String.format("%.2f", rs.finalScore()));
            r.put("inTop3",     rs.rank() <= 3);
            r.put("sector",     sectorClassify.getSector(rs.symbol()));
            list.add(r);
        }
        return list;
    }

    private List<Map<String, Object>> buildActiveTrades(Map<String, BigDecimal> prices) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (TradeManagementService.ManagedTrade mt : tradeManagement.getActiveTrades()) {
            var t = mt.trade();
            BigDecimal ltp = prices.getOrDefault(t.getTradingSymbol(), t.getEntryPrice());
            BigDecimal unrealPnl = t.getDirection().name().equals("LONG")
                    ? ltp.subtract(t.getEntryPrice()).multiply(BigDecimal.valueOf(mt.remainingQty()))
                    : t.getEntryPrice().subtract(ltp).multiply(BigDecimal.valueOf(mt.remainingQty()));
            double rDist = mt.rDistance().doubleValue();
            double rMult = rDist > 0 ? unrealPnl.doubleValue() / rDist / mt.remainingQty() : 0;
            String phase = rMult >= 4.0 ? "Trail 0.5ATR (4R+)"
                    : rMult >= 3.0 ? "Trailing (3R+)"
                    : rMult >= 1.0 ? "Breakeven"
                    : "Survival";
            Integer rank = rankingEngine.getRank(t.getTradingSymbol());
            Map<String, Object> tr = new LinkedHashMap<>();
            tr.put("tradingSymbol",  t.getTradingSymbol());
            tr.put("direction",      t.getDirection().name());
            tr.put("strategyName",   t.getStrategyName());
            tr.put("quantity",       t.getQuantity());
            tr.put("remainingQty",   mt.remainingQty());
            tr.put("entryPrice",     t.getEntryPrice());
            tr.put("ltp",            ltp);
            tr.put("stopLoss",       t.getStopLoss());
            tr.put("target",         t.getTarget());
            tr.put("unrealizedPnl",  unrealPnl);
            tr.put("rMultiple",      String.format("%.2fR", rMult));
            tr.put("tradePhase",     phase);
            tr.put("slAtBreakeven",  mt.slAtBreakeven());
            tr.put("trailActive",    mt.trailActive());
            tr.put("rank",           rank != null ? rank : "N/A");
            tr.put("status",         "OPEN");
            list.add(tr);
        }
        return list;
    }

    private List<Map<String, Object>> buildPaperTrades(Map<String, BigDecimal> prices) {
        List<Map<String, Object>> list = new ArrayList<>();

        // Standard strategies (SCPS, ORB, etc.) via PaperTradeManagementService
        for (PaperTradeManagementService.ManagedTrade mt : paperManagement.getActiveTrades()) {
            list.add(buildPaperTradeMap(mt.trade(), prices,
                    mt.remainingQty(), mt.slAtBreakeven(), mt.trailActive(),
                    mt.halfExited(), mt.timeStopMinutes(),
                    mt.slAtBreakeven() ? (mt.halfExited() ? "Phase-4" : "Phase-3/2") : "Phase-1"));
        }

        // HighRR active trades — use HighRRTrade record (symbol(), fillPrice(), etc.)
        // NOT domain Trade entity — HighRRTradeManager uses its own record type.
        for (HighRRTradeManager.HighRRTrade t : highRRTradeManager.getActiveTrades()) {
            BigDecimal ltp      = prices.getOrDefault(t.symbol(), t.fillPrice());
            BigDecimal unrealPnl = t.direction().name().equals("LONG")
                    ? ltp.subtract(t.fillPrice()).multiply(BigDecimal.valueOf(t.quantity()))
                    : t.fillPrice().subtract(ltp).multiply(BigDecimal.valueOf(t.quantity()));
            double risk  = t.stopLoss() != null
                    ? t.fillPrice().subtract(t.stopLoss()).abs().doubleValue() : 0;
            double rMult = risk > 0 ? unrealPnl.doubleValue() / risk / t.quantity() : 0;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tradingSymbol", t.symbol());
            m.put("direction",     t.direction().name());
            m.put("strategyName",  "HIGH_RR_INTRADAY_V1");
            m.put("quantity",      t.quantity());
            m.put("remainingQty",  t.quantity());
            m.put("entryPrice",    t.fillPrice());
            m.put("ltp",           ltp);
            m.put("stopLoss",      t.stopLoss());
            m.put("target",        t.target());
            m.put("unrealPnl",     unrealPnl);
            m.put("rMultiple",     String.format("%.2fR", rMult));
            m.put("slAtBreakeven", false);
            m.put("trailActive",   false);
            m.put("halfExited",    false);
            m.put("timeStop",      t.timeStopMinutes() > 0 ? t.timeStopMinutes() + "min" : "global");
            m.put("phase",         "Phase-1");
            list.add(m);
        }

        return list;
    }

    private Map<String, Object> buildPaperTradeMap(
            com.trading.domain.entity.Trade t,
            Map<String, BigDecimal> prices,
            int remainingQty, boolean slAtBE, boolean trailActive,
            boolean halfExited, int timeStopMins, String phase) {

        BigDecimal ltp = prices.getOrDefault(t.getTradingSymbol(), t.getEntryPrice());
        BigDecimal unrealPnl = t.getDirection().name().equals("LONG")
                ? ltp.subtract(t.getEntryPrice()).multiply(BigDecimal.valueOf(remainingQty))
                : t.getEntryPrice().subtract(ltp).multiply(BigDecimal.valueOf(remainingQty));
        double rDist = t.getStopLoss() != null
                ? t.getEntryPrice().subtract(t.getStopLoss()).abs().doubleValue() : 0;
        double rMult = rDist > 0 ? unrealPnl.doubleValue() / rDist / remainingQty : 0;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tradingSymbol", t.getTradingSymbol());
        m.put("direction",     t.getDirection().name());
        m.put("strategyName",  t.getStrategyName());
        m.put("quantity",      t.getQuantity());
        m.put("remainingQty",  remainingQty);
        m.put("entryPrice",    t.getEntryPrice());
        m.put("ltp",           ltp);
        m.put("stopLoss",      t.getStopLoss());
        m.put("target",        t.getTarget());
        m.put("unrealPnl",     unrealPnl);
        m.put("rMultiple",     String.format("%.2fR", rMult));
        m.put("slAtBreakeven", slAtBE);
        m.put("trailActive",   trailActive);
        m.put("halfExited",    halfExited);
        m.put("timeStop",      timeStopMins > 0 ? timeStopMins + "min" : "global");
        m.put("phase",         phase);
        return m;
    }

    /**
     * Converts a HighRRClosedTrade record into a plain map with the same keys
     * that the Trades tab and dashboard expect for closed trade display.
     * This avoids any domain Trade entity dependency for HighRR.
     */
    private Map<String, Object> buildClosedTradeMap(HighRRTradeManager.HighRRClosedTrade t) {
        double pnlVal = t.netPnl() != null ? t.netPnl().doubleValue() : 0;
        double risk   = t.stopLoss() != null && t.entryPrice() != null
                ? t.entryPrice().subtract(t.stopLoss()).abs().doubleValue() : 0;
        double rMult  = risk > 0 && t.exitPrice() != null
                ? (t.exitPrice().subtract(t.entryPrice()).doubleValue()) / risk : 0;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tradingSymbol", t.symbol());
        m.put("direction",     t.direction().name());
        m.put("strategyName",  "HIGH_RR_INTRADAY_V1");
        m.put("quantity",      t.quantity());
        m.put("entryPrice",    t.entryPrice());
        m.put("exitPrice",     t.exitPrice());
        m.put("netPnl",        t.netPnl());
        m.put("exitReason",    t.exitReason() != null ? t.exitReason() : "—");
        m.put("rMultiple",     String.format("%.2fR", rMult));
        m.put("status",        "CLOSED");
        m.put("entryTime",     t.entryTime() != null ? t.entryTime().toString() : "");
        m.put("exitTime",      t.closedAt()  != null ? t.closedAt().toString()  : "");
        return m;
    }

    /**
     * buildStrategyPnl: reads from correct sources per mode.
     * - PAPER standard strategies: paperExecution.getTodayTrades() → List<Trade>
     * - PAPER HighRR: highRRTradeManager.getClosedTrades() → List<HighRRClosedTrade>
     * - LIVE: tradeExecution.getTodayTrades()
     */
    private Map<String, Object> buildStrategyPnl() {
        Map<String, Object>  result   = new LinkedHashMap<>();
        Map<String, Double>  pnlMap   = new LinkedHashMap<>();
        Map<String, Integer> countMap = new LinkedHashMap<>();
        Map<String, Integer> winsMap  = new LinkedHashMap<>();

        if ("PAPER".equalsIgnoreCase(tradingMode)) {
            // Standard strategies
            for (com.trading.domain.entity.Trade trade : paperExecution.getTodayTrades(LocalDate.now())) {
                String strat = trade.getStrategyName() != null ? trade.getStrategyName() : "UNKNOWN";
                double pnl   = trade.getNetPnl() != null ? trade.getNetPnl().doubleValue() : 0;
                pnlMap.merge(strat, pnl, Double::sum);
                countMap.merge(strat, 1, Integer::sum);
                if (pnl > 0) winsMap.merge(strat, 1, Integer::sum);
            }
            // HighRR — uses HighRRClosedTrade (own record type, not domain Trade)
            for (HighRRTradeManager.HighRRClosedTrade t : highRRTradeManager.getClosedTrades()) {
                double pnl = t.netPnl() != null ? t.netPnl().doubleValue() : 0;
                pnlMap.merge("HIGH_RR_INTRADAY_V1", pnl, Double::sum);
                countMap.merge("HIGH_RR_INTRADAY_V1", 1, Integer::sum);
                if (pnl > 0) winsMap.merge("HIGH_RR_INTRADAY_V1", 1, Integer::sum);
            }
        } else {
            for (com.trading.domain.entity.Trade trade : tradeExecution.getTodayTrades(LocalDate.now())) {
                String strat = trade.getStrategyName() != null ? trade.getStrategyName() : "UNKNOWN";
                double pnl   = trade.getNetPnl() != null ? trade.getNetPnl().doubleValue() : 0;
                pnlMap.merge(strat, pnl, Double::sum);
                countMap.merge(strat, 1, Integer::sum);
                if (pnl > 0) winsMap.merge(strat, 1, Integer::sum);
            }
        }

        pnlMap.forEach((strat, pnl) -> {
            int cnt  = countMap.getOrDefault(strat, 0);
            int wins = winsMap.getOrDefault(strat, 0);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("pnl",     String.format("%.2f", pnl));
            s.put("trades",  cnt);
            s.put("wins",    wins);
            s.put("losses",  cnt - wins);
            s.put("winRate", cnt > 0 ? String.format("%.1f%%", (double) wins / cnt * 100) : "0%");
            result.put(strat, s);
        });
        return result;
    }

    // ══════════════════════════════════════════════════════════════════════
    // GET /api/dashboard/portfolio  (NEW)
    // Full daily trade overview: all strategies, timestamps, P&L, totals
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/portfolio")
    public ResponseEntity<Map<String, Object>> portfolio() {
        Map<String, BigDecimal> prices = marketDataService.getLastPricesSimple();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("timestamp", Instant.now().toString());
        data.putAll(buildPortfolioSummary(prices));
        return ResponseEntity.ok(data);
    }

    /**
     * buildPortfolioSummary — daily trade overview across all strategies.
     *
     * Allocation targets:  ORB = 2  |  HighRR = 2  |  Pullback+Sideways = 6  |  Total = 10
     *
     * Sources:
     *   - Closed standard trades (SCPS/ORB/etc.): paperExecution.getTodayTrades()
     *   - Closed HighRR trades:                   highRRTradeManager.getClosedTrades()
     *   - Active standard trades:                  paperManagement.getActiveTrades()
     *   - Active HighRR trades:                    highRRTradeManager.getActiveTrades()
     *
     * All active trades include live unrealised P&L from current prices.
     */
    private Map<String, Object> buildPortfolioSummary(Map<String, BigDecimal> prices) {
        Map<String, Object> out = new LinkedHashMap<>();

        // ── 1. Collect all trades (closed + active) into a flat list ──────────
        List<Map<String, Object>> allTrades = new ArrayList<>();
        // Track symbols already added as OPEN to prevent duplicates.
        // PRESTIGE and SKFINDIA appeared twice on 2026-04-21 because
        // paperManagement.getActiveTrades() and domain Trade list both contain them.
        Set<String> addedOpenSymbols = new java.util.HashSet<>();

        // Closed standard trades (ORB, SCPS, SIDEWAYS, MarketPressure etc.)
        for (com.trading.domain.entity.Trade t : paperExecution.getTodayTrades(LocalDate.now())) {
            if ("OPEN".equals(t.getStatus())) {
                // Will be captured from paperManagement.getActiveTrades() with live P&L
                continue;
            }
            allTrades.add(portfolioTradeRow(t, prices));
        }

        // Closed HighRR trades
        for (HighRRTradeManager.HighRRClosedTrade t : highRRTradeManager.getClosedTrades()) {
            allTrades.add(portfolioHighRRClosedRow(t));
        }

        // Active standard trades — live unrealised P&L (single source of truth for open)
        for (PaperTradeManagementService.ManagedTrade mt : paperManagement.getActiveTrades()) {
            // FIX: ManagedTrade has no symbol()/strategyName() — use the wrapped Trade entity
            String key = mt.trade().getTradingSymbol() + "|" + mt.trade().getStrategyName();
            if (addedOpenSymbols.add(key)) {
                allTrades.add(portfolioActiveRow(mt, prices));
            }
        }

        // Active HighRR trades — live unrealised P&L
        for (HighRRTradeManager.HighRRTrade t : highRRTradeManager.getActiveTrades()) {
            String key = t.symbol() + "|HIGH_RR_INTRADAY_V1";
            if (addedOpenSymbols.add(key)) {
                allTrades.add(portfolioHighRRActiveRow(t, prices));
            }
        }

        // Sort by entry time ascending so the table reads chronologically
        allTrades.sort(Comparator.comparing(
                m -> m.getOrDefault("entryTime", "").toString()));

        // ── 2. Per-strategy aggregation ───────────────────────────────────────
        Map<String, Object>  stratPnl     = new LinkedHashMap<>();
        Map<String, Integer> stratCount   = new LinkedHashMap<>();
        Map<String, Integer> stratWins    = new LinkedHashMap<>();
        Map<String, Integer> stratActive  = new LinkedHashMap<>();

        for (Map<String, Object> row : allTrades) {
            String strat  = row.getOrDefault("strategyName", "UNKNOWN").toString();
            String status = row.getOrDefault("status", "").toString();
            double pnl    = parseDouble(row.getOrDefault("pnl", 0));

            if ("CLOSED".equals(status)) {
                stratPnl.merge(strat, pnl, (a, b) -> (double) a + (double) b);
                stratCount.merge(strat, 1, Integer::sum);
                if (pnl > 0) stratWins.merge(strat, 1, Integer::sum);
            } else {
                stratActive.merge(strat, 1, Integer::sum);
            }
        }

        List<Map<String, Object>> stratBreakdown = new ArrayList<>();
        // Strategy allocation targets
        Map<String, int[]> allocationTargets = new LinkedHashMap<>();
        allocationTargets.put("ORB_BREAKOUT_V1",            new int[]{2});
        allocationTargets.put("HIGH_RR_INTRADAY_V1",        new int[]{2});
        allocationTargets.put("SMART_CHANNEL_PULLBACK_V3",  new int[]{3});
        allocationTargets.put("SIDEWAYS_SCALP_V1",          new int[]{3});
        allocationTargets.put("SCALP_PRESSURE_V2",          new int[]{3}); // actual STRATEGY_NAME in SidewaysScalpStrategy
        allocationTargets.put("MARKET_PRESSURE_V1",         new int[]{4}); // FIX: was missing → showed allocationMax=0

        // Build from all seen strategies, plus ensure targets are shown even with 0 trades
        Set<String> allStrats = new LinkedHashSet<>(allocationTargets.keySet());
        allStrats.addAll(stratCount.keySet());
        allStrats.addAll(stratActive.keySet());

        double totalRealisedPnl = 0;
        double totalUnrealPnl   = 0;

        for (String strat : allStrats) {
            double closed = (double) stratPnl.getOrDefault(strat, 0.0);
            int    cnt    = stratCount.getOrDefault(strat, 0);
            int    wins   = stratWins.getOrDefault(strat, 0);
            int    active = stratActive.getOrDefault(strat, 0);
            int    target = allocationTargets.containsKey(strat)
                    ? allocationTargets.get(strat)[0] : 0;
            totalRealisedPnl += closed;

            // Unrealised from active rows for this strategy
            double unreal = allTrades.stream()
                    .filter(r -> strat.equals(r.getOrDefault("strategyName", ""))
                            && "OPEN".equals(r.getOrDefault("status", "")))
                    .mapToDouble(r -> parseDouble(r.getOrDefault("pnl", 0)))
                    .sum();
            totalUnrealPnl += unreal;

            Map<String, Object> sb = new LinkedHashMap<>();
            sb.put("strategyName",   strat);
            sb.put("allocationMax",  target);
            sb.put("closedTrades",   cnt);
            sb.put("activeTrades",   active);
            sb.put("totalTrades",    cnt + active);
            sb.put("slotsUsed",      active);  // FIX: slots = currently open, not total traded today
            sb.put("slotsRemaining", Math.max(0, target - active));
            sb.put("wins",           wins);
            sb.put("losses",         cnt - wins);
            sb.put("winRate",        cnt > 0 ? String.format("%.1f%%", (double) wins / cnt * 100) : "—");
            sb.put("realisedPnl",    String.format("%.2f", closed));
            sb.put("unrealisedPnl",  String.format("%.2f", unreal));
            sb.put("totalPnl",       String.format("%.2f", closed + unreal));
            stratBreakdown.add(sb);
        }

        // ── 3. Portfolio totals ───────────────────────────────────────────────
        long totalClosed = allTrades.stream().filter(r -> "CLOSED".equals(r.get("status"))).count();
        long totalActive = allTrades.stream().filter(r -> "OPEN".equals(r.get("status"))).count();
        int  maxDaily    = 10;

        out.put("tradeLog",         allTrades);
        out.put("strategyBreakdown", stratBreakdown);
        out.put("totalClosedTrades", (int) totalClosed);
        out.put("totalActiveTrades", (int) totalActive);
        out.put("totalTrades",       (int)(totalClosed + totalActive));
        out.put("maxTradesPerDay",   maxDaily);
        out.put("slotsRemaining",    Math.max(0, maxDaily - (int)(totalClosed + totalActive)));
        out.put("totalRealisedPnl",  String.format("%.2f", totalRealisedPnl));
        out.put("totalUnrealisedPnl",String.format("%.2f", totalUnrealPnl));
        out.put("totalCombinedPnl",  String.format("%.2f", totalRealisedPnl + totalUnrealPnl));
        out.put("allocationSummary", buildAllocationSummary(allTrades));
        return out;
    }

    /** Allocation progress for each strategy group vs target. */
    private Map<String, Object> buildAllocationSummary(List<Map<String, Object>> allTrades) {
        Map<String, Object> a = new LinkedHashMap<>();
        for (Map<String, Object> row : allTrades) {
            String strat = row.getOrDefault("strategyName", "UNKNOWN").toString();
            a.merge(strat, 1, (x, y) -> (int) x + (int) y);
        }
        return a;
    }

    /** Build a portfolio row for a domain Trade entity (SCPS, ORB, etc.). */
    private Map<String, Object> portfolioTradeRow(com.trading.domain.entity.Trade t,
                                                  Map<String, BigDecimal> prices) {
        boolean isClosed = "CLOSED".equals(t.getStatus());
        double  pnl;
        String  ltpStr = "—";
        if (isClosed) {
            pnl    = t.getNetPnl() != null ? t.getNetPnl().doubleValue() : 0;
        } else {
            BigDecimal ltp = prices.getOrDefault(t.getTradingSymbol(), t.getEntryPrice());
            ltpStr = String.format("%.2f", ltp.doubleValue());
            pnl    = t.getDirection().name().equals("LONG")
                    ? ltp.subtract(t.getEntryPrice()).multiply(BigDecimal.valueOf(t.getQuantity())).doubleValue()
                    : t.getEntryPrice().subtract(ltp).multiply(BigDecimal.valueOf(t.getQuantity())).doubleValue();
        }
        double risk = t.getStopLoss() != null && t.getEntryPrice() != null
                ? t.getEntryPrice().subtract(t.getStopLoss()).abs().doubleValue() : 0;
        double rMult = risk > 0 ? pnl / risk / t.getQuantity() : 0;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tradingSymbol", t.getTradingSymbol());
        m.put("strategyName",  t.getStrategyName() != null ? t.getStrategyName() : "UNKNOWN");
        m.put("direction",     t.getDirection().name());
        m.put("quantity",      t.getQuantity());
        m.put("entryPrice",    fmt(t.getEntryPrice()));
        m.put("entryTime",     t.getEntryTime() != null ? istTime(t.getEntryTime()) : "—");
        m.put("exitPrice",     isClosed ? fmt(t.getExitPrice()) : "—");
        m.put("exitTime",      isClosed && t.getExitTime() != null ? istTime(t.getExitTime()) : "—");
        m.put("stopLoss",      fmt(t.getStopLoss()));
        m.put("target",        fmt(t.getTarget()));
        m.put("ltp",           isClosed ? "—" : ltpStr);
        m.put("pnl",           pnl);
        m.put("pnlFormatted",  String.format("%.2f", pnl));
        m.put("rMultiple",     String.format("%.2fR", rMult));
        m.put("exitReason",    isClosed ? (t.getExitReason() != null ? t.getExitReason() : "—") : "—");
        m.put("status",        t.getStatus() != null ? t.getStatus() : "OPEN");
        return m;
    }

    /** Build a portfolio row for a HighRRClosedTrade record. */
    private Map<String, Object> portfolioHighRRClosedRow(HighRRTradeManager.HighRRClosedTrade t) {
        double pnl   = t.netPnl() != null ? t.netPnl().doubleValue() : 0;
        double risk  = t.stopLoss() != null && t.entryPrice() != null
                ? t.entryPrice().subtract(t.stopLoss()).abs().doubleValue() : 0;
        double rMult = risk > 0 ? pnl / risk / t.quantity() : 0;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tradingSymbol", t.symbol());
        m.put("strategyName",  "HIGH_RR_INTRADAY_V1");
        m.put("direction",     t.direction().name());
        m.put("quantity",      t.quantity());
        m.put("entryPrice",    fmt(t.entryPrice()));
        m.put("entryTime",     t.entryTime() != null ? istTime(t.entryTime()) : "—");
        m.put("exitPrice",     fmt(t.exitPrice()));
        m.put("exitTime",      t.closedAt() != null ? istTime(t.closedAt()) : "—");
        m.put("stopLoss",      fmt(t.stopLoss()));
        m.put("target",        fmt(t.target1()));
        m.put("ltp",           "—");
        m.put("pnl",           pnl);
        m.put("pnlFormatted",  String.format("%.2f", pnl));
        m.put("rMultiple",     String.format("%.2fR", rMult));
        m.put("exitReason",    t.exitReason() != null ? t.exitReason() : "—");
        m.put("status",        "CLOSED");
        return m;
    }

    /** Build a portfolio row for an active standard trade (SCPS/ORB) with live P&L. */
    private Map<String, Object> portfolioActiveRow(PaperTradeManagementService.ManagedTrade mt,
                                                   Map<String, BigDecimal> prices) {
        com.trading.domain.entity.Trade t = mt.trade();
        BigDecimal ltp = prices.getOrDefault(t.getTradingSymbol(), t.getEntryPrice());
        double pnl = t.getDirection().name().equals("LONG")
                ? ltp.subtract(t.getEntryPrice()).multiply(BigDecimal.valueOf(mt.remainingQty())).doubleValue()
                : t.getEntryPrice().subtract(ltp).multiply(BigDecimal.valueOf(mt.remainingQty())).doubleValue();
        double risk  = t.getStopLoss() != null
                ? t.getEntryPrice().subtract(t.getStopLoss()).abs().doubleValue() : 0;
        double rMult = risk > 0 ? pnl / risk / mt.remainingQty() : 0;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tradingSymbol", t.getTradingSymbol());
        m.put("strategyName",  t.getStrategyName() != null ? t.getStrategyName() : "UNKNOWN");
        m.put("direction",     t.getDirection().name());
        m.put("quantity",      t.getQuantity());
        m.put("entryPrice",    fmt(t.getEntryPrice()));
        m.put("entryTime",     t.getEntryTime() != null ? istTime(t.getEntryTime()) : "—");
        m.put("exitPrice",     "—");
        m.put("exitTime",      "—");
        m.put("stopLoss",      fmt(t.getStopLoss()));
        m.put("target",        fmt(t.getTarget()));
        m.put("ltp",           String.format("%.2f", ltp.doubleValue()));
        m.put("pnl",           pnl);
        m.put("pnlFormatted",  String.format("%.2f", pnl));
        m.put("rMultiple",     String.format("%.2fR", rMult));
        m.put("exitReason",    "—");
        m.put("status",        "OPEN");
        return m;
    }

    /** Build a portfolio row for an active HighRR trade with live P&L. */
    private Map<String, Object> portfolioHighRRActiveRow(HighRRTradeManager.HighRRTrade t,
                                                         Map<String, BigDecimal> prices) {
        BigDecimal ltp = prices.getOrDefault(t.symbol(), t.fillPrice());
        double pnl = t.direction().name().equals("LONG")
                ? ltp.subtract(t.fillPrice()).multiply(BigDecimal.valueOf(t.quantity())).doubleValue()
                : t.fillPrice().subtract(ltp).multiply(BigDecimal.valueOf(t.quantity())).doubleValue();
        double risk  = t.stopLoss() != null
                ? t.fillPrice().subtract(t.stopLoss()).abs().doubleValue() : 0;
        double rMult = risk > 0 ? pnl / risk / t.quantity() : 0;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tradingSymbol", t.symbol());
        m.put("strategyName",  "HIGH_RR_INTRADAY_V1");
        m.put("direction",     t.direction().name());
        m.put("quantity",      t.quantity());
        m.put("entryPrice",    String.format("%.2f", t.fillPrice().doubleValue()));
        m.put("entryTime",     t.entryTime() != null ? istTime(t.entryTime()) : "—");
        m.put("exitPrice",     "—");
        m.put("exitTime",      "—");
        m.put("stopLoss",      fmt(t.stopLoss()));
        m.put("target",        fmt(t.target()));
        m.put("ltp",           String.format("%.2f", ltp.doubleValue()));
        m.put("pnl",           pnl);
        m.put("pnlFormatted",  String.format("%.2f", pnl));
        m.put("rMultiple",     String.format("%.2fR", rMult));
        m.put("exitReason",    "—");
        m.put("status",        "OPEN");
        return m;
    }

    /** Format BigDecimal to 2dp string, returns "—" for null. */
    private String fmt(BigDecimal v) {
        return v != null ? String.format("%.2f", v.doubleValue()) : "—";
    }

    /** Convert Instant to IST HH:mm:ss string. */
    private String istTime(Instant instant) {
        return instant.atZone(java.time.ZoneId.of("Asia/Kolkata"))
                .toLocalTime().toString();
    }

    /** Safe double parse from map value. */
    private double parseDouble(Object v) {
        if (v == null) return 0;
        try { return Double.parseDouble(v.toString()); } catch (Exception e) { return 0; }
    }

    private String phaseDescription() {
        return switch (marketPhaseEngine.getCurrentPhase()) {
            case PRE_OPEN  -> "Pre-open. No trades.";
            case EARLY     -> "Early (9:15-10:15). Threshold=58.";
            case CONFIRMED -> "Confirmed (10:15+). IB locked.";
            case CLOSED    -> "Market closed.";
        };
    }

    private double pct(BigDecimal pnl, BigDecimal cap) {
        if (cap == null || cap.compareTo(BigDecimal.ZERO) == 0) return 0;
        return pnl.divide(cap, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).doubleValue();
    }

    private BigDecimal round2(double val) {
        return BigDecimal.valueOf(val).setScale(2, RoundingMode.HALF_UP);
    }

    private String nullSafe(String v) { return v != null ? v : ""; }

    private String regimeLabel(MarketDirectionService.Direction d) {
        return switch (d) {
            case BULLISH  -> "Strong Bull";
            case BEARISH  -> "Strong Bear";
            case SIDEWAYS -> "Sideways";
        };
    }

    private String sectorLabel(SectorStrengthService.SectorData sd) {
        if (sd.alignedBullish()) return "STRONG";
        if (sd.alignedBearish()) return "WEAK";
        return "NEUTRAL";
    }
}