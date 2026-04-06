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
import com.trading.scanner.service.SevenGateScannerService;
import com.trading.sector.service.SectorClassificationService;
import com.trading.sector.service.SectorStrengthService;
import com.trading.strategy.StrategyEvaluatorService;
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
 * DashboardController — v7.0 Complete Dashboard API.
 *
 * ALL 4 COMPILE ERRORS FIXED:
 *
 *   ERROR 1: getCurrentBankNiftyMode() — DOES NOT EXIST on BankNiftyModeEngine.
 *            FIX: BankNiftyModeEngine has @Getter on field `currentMode`, so
 *                 Lombok generates getCurrentMode() — that is what to call.
 *
 *   ERROR 2: getForSymbol(String) — DOES NOT EXIST on StrategyValidationTracker.
 *            FIX: Use getAllLogs() which returns Map<strategy, List<SymbolValidationLog>>,
 *                 then filter by symbol manually.
 *
 *   ERROR 3: getForStrategy(String) — DOES NOT EXIST.
 *            FIX: Use getByStrategy(String strategy) — this method DOES exist.
 *
 *   ERROR 4: getRecent(int) — DOES NOT EXIST.
 *            FIX: Use getAllLogs() and flatten/limit in code.
 *
 * VERIFIED API against source:
 *   BankNiftyModeEngine:         getCurrentMode(), getModeForSymbol(), isBankNiftyStock()
 *   StrategyValidationTracker:   record(), getAllLogs(), getByStrategy(), getFailureFrequency(), getTotalSymbolsTracked()
 *   MarketPhaseEngine:           getCurrentPhase(), isEarlyPhase(), isConfirmedPhase(), isTradeAllowed(), isOrbAllowed(), isIbForceNeeded()
 *   StockRankingEngine:          getTopCandidates(int), getAllRankings(), getRank(String)
 *   LatencyMonitor:              getSummary(), isStale(), isCritical(), getLagMs(), getStatus()
 *   RiskManagementService:       getPhase1Count(), getMaxPhase1(), getSectorExposure(), getStrategyExposure()
 *   PaperTradeManagementService: getActiveTrades(), isAnyTradeAtBreakevenOrBeyond()
 *   PaperTradeExecutionService:  getAllTrades(), getTodayTrades(LocalDate)
 *   StrategyEvaluatorService:    getFiredToday(), getSignalCounters()
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
    private final SevenGateScannerService     scanner;
    private final SectorStrengthService       sectorStrength;
    private final SectorClassificationService sectorClassify;
    private final StrategyEvaluatorService    strategyEvaluator;

    // ── Mode engines ──────────────────────────────────────────────────────
    private final MarketModeEngine            marketModeEngine;
    private final MarketPhaseEngine           marketPhaseEngine;
    private final BankNiftyModeEngine         bankNiftyModeEngine;

    // ── v7.0 services ─────────────────────────────────────────────────────
    private final StockRankingEngine          rankingEngine;
    private final LatencyMonitor              latencyMonitor;
    private final PaperTradeExecutionService  paperExecution;
    private final PaperTradeManagementService paperManagement;
    private final RiskManagementService       riskManagement;
    private final StrategyValidationTracker   validationTracker;

    @Value("${trading.capital:100000}") private BigDecimal capital;
    @Value("${trading.mode:PAPER}")     private String     tradingMode;

    // ═══════════════════════════════════════════════════════════════════════
    // GET /api/dashboard/snapshot — full system state
    // ═══════════════════════════════════════════════════════════════════════

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

        // 9. strategy status
        Map<String, Object> strategyStatus = new LinkedHashMap<>();
        strategyStatus.put("firedToday",      strategyEvaluator.getFiredToday());
        strategyStatus.put("perStrategyPnl",  buildStrategyPnl());
        strategyStatus.put("strategySummary", buildStrategySummary());
        strategyStatus.put("signalCounters",  strategyEvaluator.getSignalCounters());
        data.put("strategyStatus", strategyStatus);

        // 10. armed stocks
        Map<String, Object> armedMap = new LinkedHashMap<>();
        scanner.getArmedStocks().forEach((sym, armed) -> {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("symbol",          sym);
            a.put("sector",          sectorClassify.getSector(sym));
            a.put("direction",       armed.direction().name());
            a.put("compressionHigh", armed.compressionHigh());
            a.put("compressionLow",  armed.compressionLow());
            a.put("stopLoss",        armed.stopLoss());
            a.put("target",          armed.target());
            a.put("gapType",         armed.gapType().name());
            a.put("armedAt",         armed.armedAt().toString());
            a.put("minRR",           armed.minRR());
            a.put("isReentry",       armed.isReentry());
            armedMap.put(sym, a);
        });
        data.put("armedStocks",    armedMap);
        data.put("armedCount",     scanner.getArmedCount());
        data.put("gateRejections", scanner.getGateRejections());

        // 11. gate status
        Map<Integer, String> gateStatus = new LinkedHashMap<>();
        String gate1 = dir.direction() == MarketDirectionService.Direction.SIDEWAYS
                ? "SIDE" : dir.isTradeable() ? "PASS" : "FAIL";
        gateStatus.put(1, gate1);
        for (int i = 2; i <= 7; i++) gateStatus.put(i, "WAIT");
        data.put("gateStatus", gateStatus);

        // 12. marketMode (Nifty)
        data.put("marketMode", buildMarketModeMap(marketModeEngine.getCurrentMode()));

        // 13. v7.0: marketPhase (EARLY / CONFIRMED)
        data.put("marketPhase", buildPhaseMap());

        // 14. v7.0: bankNiftyMode
        // FIX: BankNiftyModeEngine has @Getter on `currentMode` field.
        // Lombok generates getCurrentMode() — NOT getCurrentBankNiftyMode().
        data.put("bankNiftyMode", buildMarketModeMap(bankNiftyModeEngine.getCurrentMode()));

        // 15. v7.0: latency
        data.put("latency", buildLatencyMap());

        // 16. v7.0: stockRankings
        data.put("stockRankings", buildRankingList(10));
        data.put("allRankings",   rankingEngine.getAllRankings());

        // 17. v7.0: risk slot counters
        Map<String, Object> riskSlots = new LinkedHashMap<>();
        riskSlots.put("phase1Count",      riskManagement.getPhase1Count());
        riskSlots.put("maxPhase1",        riskManagement.getMaxPhase1());
        riskSlots.put("sectorExposure",   riskManagement.getSectorExposure());
        riskSlots.put("strategyExposure", riskManagement.getStrategyExposure());
        riskSlots.put("anyAtBreakeven",   paperManagement.isAnyTradeAtBreakevenOrBeyond());
        data.put("riskSlots", riskSlots);

        // 18. paper trades
        data.put("paperTrades",        buildPaperTrades(prices));
        data.put("paperTradesHistory", paperExecution.getAllTrades());

        // 19. validation summary
        data.put("validationFailureFrequency", validationTracker.getFailureFrequency());
        data.put("validationSymbolsTracked",   validationTracker.getTotalSymbolsTracked());

        return ResponseEntity.ok(data);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GET /api/dashboard/prices
    // ═══════════════════════════════════════════════════════════════════════

    @GetMapping("/prices")
    public ResponseEntity<Map<String, BigDecimal>> prices() {
        return ResponseEntity.ok(marketDataService.getLastPricesSimple());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GET /api/dashboard/phase
    // ═══════════════════════════════════════════════════════════════════════

    @GetMapping("/phase")
    public ResponseEntity<Map<String, Object>> phase() {
        Map<String, Object> m = buildPhaseMap();
        m.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(m);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GET /api/dashboard/ranking
    // ═══════════════════════════════════════════════════════════════════════

    @GetMapping("/ranking")
    public ResponseEntity<Map<String, Object>> ranking() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("candidates",  buildRankingList(20));
        m.put("allRankings", rankingEngine.getAllRankings());
        m.put("timestamp",   Instant.now().toString());
        return ResponseEntity.ok(m);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GET /api/dashboard/latency
    // ═══════════════════════════════════════════════════════════════════════

    @GetMapping("/latency")
    public ResponseEntity<Map<String, Object>> latency() {
        Map<String, Object> m = buildLatencyMap();
        m.put("wsConnected",  marketDataService.isConnected());
        m.put("wsReconnects", marketDataService.getReconnectCount());
        m.put("timestamp",    Instant.now().toString());
        return ResponseEntity.ok(m);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GET /api/dashboard/paper-trades
    // ═══════════════════════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════════════════════
    // GET /api/dashboard/strategy-performance
    // ═══════════════════════════════════════════════════════════════════════

    @GetMapping("/strategy-performance")
    public ResponseEntity<Map<String, Object>> strategyPerformance() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("perStrategyPnl",  buildStrategyPnl());
        resp.put("firedToday",      strategyEvaluator.getFiredToday());
        resp.put("strategySummary", buildStrategySummary());
        resp.put("signalCounters",  strategyEvaluator.getSignalCounters());
        resp.put("timestamp",       Instant.now().toString());
        return ResponseEntity.ok(resp);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GET /api/dashboard/validation
    //   ?symbol=RELIANCE      → all logs for that symbol (filtered from getAllLogs)
    //   ?strategy=SCANNER_7GATE → all logs for that strategy (getByStrategy)
    //   (no params)           → full log map + failure frequency
    //
    // FIX: Only methods that ACTUALLY EXIST on StrategyValidationTracker:
    //   - getAllLogs()              → Map<String, List<SymbolValidationLog>>
    //   - getByStrategy(String)    → List<SymbolValidationLog>
    //   - getFailureFrequency()    → Map<String, Integer>
    //   - getTotalSymbolsTracked() → int
    // ═══════════════════════════════════════════════════════════════════════

    @GetMapping("/validation")
    public ResponseEntity<Map<String, Object>> validation(
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String strategy) {

        Map<String, Object> resp = new LinkedHashMap<>();

        if (symbol != null && !symbol.isBlank()) {
            // FIX ERROR 2: getForSymbol() doesn't exist.
            // Use getAllLogs() and filter by symbol across all strategies.
            String sym = symbol.toUpperCase();
            List<Object> symbolLogs = new ArrayList<>();
            validationTracker.getAllLogs().forEach((strat, logs) ->
                    logs.stream()
                            .filter(l -> sym.equals(l.symbol().toUpperCase()))
                            .forEach(symbolLogs::add)
            );
            resp.put("symbol",  symbol);
            resp.put("results", symbolLogs);

        } else if (strategy != null && !strategy.isBlank()) {
            // FIX ERROR 3: getForStrategy() doesn't exist.
            // Use getByStrategy(String) — this method DOES exist.
            resp.put("strategy", strategy);
            resp.put("results",  validationTracker.getByStrategy(strategy));

        } else {
            // FIX ERROR 4: getRecent(int) doesn't exist.
            // Use getAllLogs() and flatten manually.
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

    // ═══════════════════════════════════════════════════════════════════════
    // POST /api/dashboard/circuit-breaker/reset
    // ═══════════════════════════════════════════════════════════════════════

    @PostMapping("/circuit-breaker/reset")
    public ResponseEntity<String> resetCb() {
        log.warn("[DASHBOARD] Circuit breaker manual reset requested");
        return ResponseEntity.ok("Circuit breaker reset acknowledged");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Private builders — all use only verified existing methods
    // ═══════════════════════════════════════════════════════════════════════

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
                    : rMult >= 1.0 ? "Breakeven" : "Survival";
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
                    ? ltp.subtract(t.getEntryPrice()).multiply(BigDecimal.valueOf(mt.remainingQty()))
                    : t.getEntryPrice().subtract(ltp).multiply(BigDecimal.valueOf(mt.remainingQty()));
            double rDist = mt.rDistance().doubleValue();
            double rMult = rDist > 0 ? unrealPnl.doubleValue() / rDist / mt.remainingQty() : 0;

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
            m.put("timeStop",      mt.timeStopMinutes() > 0 ? mt.timeStopMinutes() + "min" : "global");
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
            String strat = trade.getStrategyName() != null ? trade.getStrategyName() : "UNKNOWN";
            double pnl   = trade.getNetPnl() != null ? trade.getNetPnl().doubleValue() : 0;
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
            s.put("winRate", cnt > 0 ? String.format("%.1f%%", (double) wins / cnt * 100) : "0%");
            result.put(strat, s);
        });
        return result;
    }

    private List<Map<String, Object>> buildStrategySummary() {
        Set<String> fired = strategyEvaluator.getFiredToday();
        List<Map<String, Object>> list = new ArrayList<>();
        for (String name : List.of("SCANNER_7GATE", "AUTO_MODE", "ORB_VWAP_SECTOR",
                "VAP_PULLBACK", "RANGE_BREAKOUT_3TOUCH")) {
            long count = fired.stream().filter(k -> k.endsWith(":" + name)).count();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("strategy",     name);
            m.put("signalsFired", count);
            list.add(m);
        }
        return list;
    }

    private String phaseDescription() {
        return switch (marketPhaseEngine.getCurrentPhase()) {
            case PRE_OPEN  -> "Pre-open. No trades.";
            case EARLY     -> "Early (9:15-10:15). ORB active. Threshold=58. Early boost if RVOL>1.2.";
            case CONFIRMED -> "Confirmed (10:15+). IB locked. Full strategies active.";
            case CLOSED    -> "Market closed.";
        };
    }

    // ── Helpers ───────────────────────────────────────────────────────────

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