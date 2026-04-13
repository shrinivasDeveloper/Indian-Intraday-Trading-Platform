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
 * DashboardController — v9.0
 *
 * ADDED vs v8.0:
 *   - smartChannelPullback section in strategyStatus
 *   - channelDetection section (valid channels count, tracked symbols)
 *   - GET /api/dashboard/strategy/smart-channel-pullback (dedicated endpoint)
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

    // ── SmartChannelPullback strategy (NEW) ───────────────────────────────
    private final SmartChannelPullbackStrategy smartChannelPullbackStrategy;
    private final SmartChannelSignalHandler    smartChannelSignalHandler;
    private final ChannelDetectionService      channelDetectionService;

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

        // 7. active trades (LIVE)
        Map<String, BigDecimal> prices = marketDataService.getLastPricesSimple();
        data.put("activeTrades", buildActiveTrades(prices));

        // 8. today's closed trades
        data.put("todayTrades", tradeExecution.getTodayTrades(LocalDate.now()));

        // 9. strategy status — includes SmartChannelPullback
        Map<String, Object> strategyStatus = new LinkedHashMap<>();
        strategyStatus.put("firedToday",      smartChannelPullbackStrategy.getActiveSignals());
        strategyStatus.put("perStrategyPnl",  buildStrategyPnl());
        strategyStatus.put("strategySummary", buildStrategySummary());
        // NEW: SmartChannelPullback specific data
        strategyStatus.put("smartChannelPullback", buildSmartChannelStatus());
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

        // 16. paper trades
        data.put("paperTrades",        buildPaperTrades(prices));
        data.put("paperTradesHistory", paperExecution.getAllTrades());

        // 17. validation summary
        data.put("validationFailureFrequency", validationTracker.getFailureFrequency());
        data.put("validationSymbolsTracked",   validationTracker.getTotalSymbolsTracked());

        // 18. channel detection summary (NEW)
        data.put("channelDetection", buildChannelDetectionSummary());

        return ResponseEntity.ok(data);
    }

    // ══════════════════════════════════════════════════════════════════════
    // NEW: GET /api/dashboard/strategy/smart-channel-pullback
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/strategy/smart-channel-pullback")
    public ResponseEntity<Map<String, Object>> smartChannelStatus() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("timestamp",       Instant.now().toString());
        data.putAll(buildSmartChannelStatus());

        // Valid channels
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
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("activeTrades",   buildPaperTrades(prices));
        m.put("closedTrades",   paperExecution.getTodayTrades(LocalDate.now()));
        m.put("allTrades",      paperExecution.getAllTrades());
        m.put("anyAtBreakeven", paperManagement.isAnyTradeAtBreakevenOrBeyond());
        m.put("timestamp",      Instant.now().toString());
        return ResponseEntity.ok(m);
    }

    @GetMapping("/strategy-performance")
    public ResponseEntity<Map<String, Object>> strategyPerformance() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("perStrategyPnl",       buildStrategyPnl());
        resp.put("firedToday",           smartChannelPullbackStrategy.getActiveSignals());
        resp.put("strategySummary",      buildStrategySummary());
        resp.put("smartChannelPullback", buildSmartChannelStatus());
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

    private List<Map<String, Object>> buildStrategySummary() {
        List<Map<String, Object>> list = new ArrayList<>();
        Map<String, Object> scps = new LinkedHashMap<>();
        scps.put("name",    "SMART_CHANNEL_PULLBACK_V2");
        scps.put("enabled", smartChannelPullbackStrategy.isEnabled());
        scps.put("signals", smartChannelPullbackStrategy.getSessionSignalCount());
        scps.put("active",  smartChannelPullbackStrategy.getActiveSignalCount());
        list.add(scps);
        return list;
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
                    ? ltp.subtract(t.getEntryPrice())
                    .multiply(BigDecimal.valueOf(mt.remainingQty()))
                    : t.getEntryPrice().subtract(ltp)
                    .multiply(BigDecimal.valueOf(mt.remainingQty()));
            double rDist = mt.rDistance().doubleValue();
            double rMult = rDist > 0
                    ? unrealPnl.doubleValue() / rDist / mt.remainingQty() : 0;
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
        for (PaperTradeManagementService.ManagedTrade mt : paperManagement.getActiveTrades()) {
            var t = mt.trade();
            BigDecimal ltp = prices.getOrDefault(t.getTradingSymbol(), t.getEntryPrice());
            BigDecimal unrealPnl = t.getDirection().name().equals("LONG")
                    ? ltp.subtract(t.getEntryPrice())
                    .multiply(BigDecimal.valueOf(mt.remainingQty()))
                    : t.getEntryPrice().subtract(ltp)
                    .multiply(BigDecimal.valueOf(mt.remainingQty()));
            double rDist = mt.rDistance().doubleValue();
            double rMult = rDist > 0
                    ? unrealPnl.doubleValue() / rDist / mt.remainingQty() : 0;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tradingSymbol", t.getTradingSymbol());
            m.put("direction",     t.getDirection().name());
            m.put("strategyName",  t.getStrategyName());
            m.put("quantity",      t.getQuantity());
            m.put("remainingQty",  mt.remainingQty());
            m.put("entryPrice",    t.getEntryPrice());
            m.put("ltp",           ltp);
            m.put("stopLoss",      t.getStopLoss());
            m.put("target",        t.getTarget());
            m.put("unrealPnl",     unrealPnl);
            m.put("rMultiple",     String.format("%.2fR", rMult));
            m.put("slAtBreakeven", mt.slAtBreakeven());
            m.put("trailActive",   mt.trailActive());
            m.put("halfExited",    mt.halfExited());
            m.put("timeStop",      mt.timeStopMinutes() > 0
                    ? mt.timeStopMinutes() + "min" : "global");
            m.put("phase",         mt.slAtBreakeven()
                    ? (mt.halfExited() ? "Phase-4" : "Phase-3/2") : "Phase-1");
            list.add(m);
        }
        return list;
    }

    private Map<String, Object> buildStrategyPnl() {
        Map<String, Object>  result   = new LinkedHashMap<>();
        Map<String, Double>  pnlMap   = new LinkedHashMap<>();
        Map<String, Integer> countMap = new LinkedHashMap<>();
        Map<String, Integer> winsMap  = new LinkedHashMap<>();
        for (var trade : tradeExecution.getTodayTrades(LocalDate.now())) {
            String strat = trade.getStrategyName() != null
                    ? trade.getStrategyName() : "UNKNOWN";
            double pnl = trade.getNetPnl() != null
                    ? trade.getNetPnl().doubleValue() : 0;
            pnlMap.merge(strat, pnl, Double::sum);
            countMap.merge(strat, 1, Integer::sum);
            if (pnl > 0) winsMap.merge(strat, 1, Integer::sum);
        }
        pnlMap.forEach((strat, pnl) -> {
            int cnt  = countMap.getOrDefault(strat, 0);
            int wins = winsMap.getOrDefault(strat, 0);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("pnl",     String.format("%.2f", pnl));
            s.put("trades",  cnt);
            s.put("wins",    wins);
            s.put("losses",  cnt - wins);
            s.put("winRate", cnt > 0
                    ? String.format("%.1f%%", (double) wins / cnt * 100) : "0%");
            result.put(strat, s);
        });
        return result;
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