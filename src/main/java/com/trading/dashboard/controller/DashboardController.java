package com.trading.dashboard.controller;

import com.trading.marketdata.service.LatencyMonitor;
import com.trading.marketdata.service.MarketDataService;
import com.trading.marketdata.service.MarketTimingService;
import com.trading.marketdata.service.VixService;
import com.trading.ranking.service.StockRankingEngine;
import com.trading.regime.service.BankNiftyModeEngine;
import com.trading.regime.service.MarketDirectionService;
import com.trading.regime.service.MarketModeEngine;
import com.trading.regime.service.MarketPhaseEngine;
import com.trading.risk.service.CircuitBreakerService;
import com.trading.sector.service.SectorClassificationService;
import com.trading.sector.service.SectorStrengthService;
import com.trading.strategy.orb.OrbDataService;
import com.trading.strategy.news.NewsScore;
import com.trading.strategy.news.NewsTradingStrategy;
import com.trading.strategy.news.NewsIngestionService;
import com.trading.validation.StrategyValidationTracker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * DashboardController - v9.2 (cleanup)
 *
 * CLEANUP (this revision): removed all SmartChannelPullback, HighRR, ORB-
 * strategy-engine, SMC, and BestTrade dependencies, plus the shared
 * PaperTradeExecutionService/PaperTradeManagementService/RiskManagementService
 * - those strategies have been permanently removed from the application.
 * OrbDataService is kept: NewsTradingStrategy depends on it directly for
 * live tick prices (getLivePrice()), independent of the ORB strategy engine
 * that was removed. AI and News sections (newsCatalyst, ai/status, news/all,
 * and the news block inside scanner()) are completely unchanged - every
 * edit in this revision only removes code that was exclusively serving the
 * now-deleted strategies.
 *
 * Historical fix notes from earlier revisions (kept for context):
 *   - FIX 3: todayTrades reads from paperExecution in PAPER mode (removed)
 *   - FIX 4: buildStrategyPnl() reads from paperExecution in PAPER mode (removed)
 *   - FIX 5: paperTradesHistory returns paperExecution trades (removed)
 *   - FIX 6: strategySummary included HIGH_RR_INTRADAY_V1 entry (removed)
 */
@RestController
@RequestMapping("/api/dashboard")
@Slf4j
@RequiredArgsConstructor
public class DashboardController {

    // -- Core services -----------------------------------------------------
    private final MarketDataService           marketDataService;
    private final VixService                  vixService;
    private final MarketTimingService         timingService;
    private final MarketDirectionService      marketDir;
    private final CircuitBreakerService       circuitBreaker;
    private final com.trading.herozero.repository.HeroZeroTradeRepository heroZeroRepo;
    private final com.trading.momentumstockofday.repository.MomentumTradeRepository momentumRepo;
    // ADDITIVE (dashboard gate-visibility feature, per explicit user
    // request): read-only access to real-time gate pass/fail state.
    private final com.trading.momentumstockofday.service.MomentumGateStatusService momentumGateStatusService;
    private final SectorStrengthService       sectorStrength;
    private final SectorClassificationService sectorClassify;

    // -- Mode engines ------------------------------------------------------
    private final MarketModeEngine            marketModeEngine;
    private final MarketPhaseEngine           marketPhaseEngine;
    private final BankNiftyModeEngine         bankNiftyModeEngine;

    // -- v7+ services ------------------------------------------------------
    private final StockRankingEngine          rankingEngine;
    private final LatencyMonitor              latencyMonitor;
    private final StrategyValidationTracker   validationTracker;

    // -- News strategy ------------------------------------------------------
    private final NewsTradingStrategy          newsTradingStrategy; // NEWS_CATALYST_V1
    private final NewsIngestionService         newsIngestionService; // raw ingested articles

    // -- OrbDataService - KEPT: NewsTradingStrategy depends on it directly
    // for live tick prices (getLivePrice()). The ORB strategy engine that
    // used to consume the rest of this service's data has been removed.
    private final OrbDataService               orbDataService;

    // -- Per-strategy capital ledger - for the UI-editable capital control
    private final com.trading.ai.execution.AiNewsCapitalLedger capitalLedger;

    @Value("${trading.capital:100000}") private BigDecimal capital;
    @Value("${trading.mode:PAPER}")     private String     tradingMode;

    // ======================================================================
    // GET /api/dashboard/snapshot
    // ======================================================================

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
        // FIX (per explicit user request: "Hero-zero and momentum, all
        // the strategies will show, not only AI and News"). AI+News
        // P&L/trade count come from the circuit breaker (now correctly
        // fed real data - see AiTradeManagementEngine/
        // NewsTradeManagementEngine fixes). Hero-Zero and Momentum are
        // deliberately NOT wired into CircuitBreakerService itself -
        // that would break the "fully independent" requirement both
        // were explicitly built under. Instead, their own, separately-
        // tracked P&L/trade counts are added here, at the dashboard
        // layer only - the correct place for cross-strategy visibility
        // without creating any real dependency between strategies.
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
        BigDecimal aiNewsDailyPnl = circuitBreaker.getDailyPnl();
        BigDecimal heroZeroPnl    = heroZeroRepo.getTodaysRealisedPnl(today);
        BigDecimal momentumPnl    = momentumRepo.getTodaysRealisedPnl();
        BigDecimal dailyPnl       = aiNewsDailyPnl.add(heroZeroPnl).add(momentumPnl);

        int aiNewsTradesToday  = circuitBreaker.getTradesToday();
        int heroZeroTrades     = heroZeroRepo.getTodaysTradeCount(today);
        int momentumTrades     = momentumRepo.getTodaysTradeCount();
        int totalTradesToday   = aiNewsTradesToday + heroZeroTrades + momentumTrades;

        BigDecimal weeklyPnl  = circuitBreaker.getWeeklyPnl();
        BigDecimal monthlyPnl = circuitBreaker.getMonthlyPnl();
        Map<String, Object> pnl = new LinkedHashMap<>();
        pnl.put("capital",         capital);
        pnl.put("dailyPnl",        dailyPnl);
        pnl.put("weeklyPnl",       weeklyPnl);
        pnl.put("monthlyPnl",      monthlyPnl);
        pnl.put("dailyPct",        pct(dailyPnl, capital));
        pnl.put("tradesToday",     totalTradesToday);
        pnl.put("maxTradesPerDay", circuitBreaker.getMaxPerDay());
        pnl.put("cbActive",        circuitBreaker.isActive());
        pnl.put("cbReason",        nullSafe(circuitBreaker.getDisableReason()));
        // Breakdown, so the dashboard can show per-strategy contribution
        // if desired, without losing the combined total above.
        pnl.put("breakdown", Map.of(
                "aiNews",    Map.of("pnl", aiNewsDailyPnl, "trades", aiNewsTradesToday),
                "heroZero",  Map.of("pnl", heroZeroPnl,    "trades", heroZeroTrades),
                "momentum",  Map.of("pnl", momentumPnl,    "trades", momentumTrades)
        ));
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

        // 7. marketMode (Nifty)
        data.put("marketMode", buildMarketModeMap(marketModeEngine.getCurrentMode()));

        // 8. marketPhase
        data.put("marketPhase", buildPhaseMap());

        // 11. bankNiftyMode
        data.put("bankNiftyMode", buildMarketModeMap(bankNiftyModeEngine.getCurrentMode()));

        // 12. latency
        data.put("latency", buildLatencyMap());

        // 13. stockRankings
        data.put("stockRankings", buildRankingList(10));
        data.put("allRankings",   rankingEngine.getAllRankings());

        // 14. validation summary
        data.put("validationFailureFrequency", validationTracker.getFailureFrequency());
        data.put("validationSymbolsTracked",   validationTracker.getTotalSymbolsTracked());

        // 15. AI and News sections - completely independent of anything above
        data.put("newsCatalyst",     buildNewsCatalystSummary());

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

    // ======================================================================
    // PER-STRATEGY CAPITAL - UI-editable, AI and News independently
    // ======================================================================

    // ======================================================================
    // HERO-ZERO MANUAL EXPIRY (per explicit user request): calendar-picked
    // expiry per index, persisted in MySQL (survives restart/crash),
    // visible until manually changed. required=false so this controller
    // keeps working even if the new service bean is ever absent.
    // ======================================================================
    @Autowired(required = false)
    private com.trading.herozero.service.HeroZeroExpiryOverrideService heroZeroExpiryService;

    // ======================================================================
    // MOMENTUM GATE STATUS (per explicit user request): real-time,
    // per-symbol Pass/Fail/Pending status for every validation gate in
    // the watchlist pipeline. Read-only - this endpoint only exposes
    // what MomentumGateStatusService already recorded; it triggers no
    // computation and makes no trading decision.
    // ======================================================================
    @GetMapping("/momentum/gate-status")
    public ResponseEntity<Map<String, Object>> getMomentumGateStatus() {
        Map<String, Object> out = new LinkedHashMap<>();
        var snapshot = momentumGateStatusService.getAllStatus();
        Map<String, Object> bySymbol = new LinkedHashMap<>();
        for (var entry : snapshot.entrySet()) {
            Map<String, Object> gates = new LinkedHashMap<>();
            for (var g : entry.getValue().entrySet()) {
                Map<String, Object> gateInfo = new LinkedHashMap<>();
                gateInfo.put("state", g.getValue().state().name());
                gateInfo.put("reason", g.getValue().reason());
                gateInfo.put("updatedAt", g.getValue().updatedAt().toString());
                gates.put(g.getKey(), gateInfo);
            }
            bySymbol.put(entry.getKey(), gates);
        }
        out.put("symbols", bySymbol);
        out.put("scanningGates", com.trading.momentumstockofday.service.MomentumGateStatusService.SCANNING_GATE_NAMES);
        out.put("entryGates", com.trading.momentumstockofday.service.MomentumGateStatusService.ENTRY_GATE_NAMES);
        out.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(out);
    }

    // ======================================================================
    // DUAL ENTRY STRATEGY DASHBOARD (per explicit user request): own,
    // fully isolated endpoints - zero shared state or code path with
    // Momentum's own dashboard endpoints above.
    // ======================================================================
    @Autowired(required = false)
    private com.trading.dualentry.config.DualEntryConfig dualEntryConfig;
    @Autowired(required = false)
    private com.trading.dualentry.scheduler.DualEntryScheduler dualEntryScheduler;
    @Autowired(required = false)
    private com.trading.dualentry.repository.DualEntryTradeRepository dualEntryTradeRepo;
    @Autowired(required = false)
    private com.trading.dualentry.service.DualEntryGateStatusService dualEntryGateStatusService;

    @GetMapping("/dual-entry/status")
    public ResponseEntity<Map<String, Object>> getDualEntryStatus() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (dualEntryConfig == null || dualEntryScheduler == null) {
            out.put("available", false);
            return ResponseEntity.ok(out);
        }
        out.put("available", true);
        out.put("enabled", dualEntryConfig.isEnabled());
        out.put("capital", dualEntryConfig.getCapital());
        out.put("maxTradesPerDay", dualEntryConfig.getMaxTradesPerDay());
        out.put("tradesToday", dualEntryScheduler.getTradesTodayCount());
        out.put("hasActiveTrade", dualEntryScheduler.hasActiveTrade());
        var candidates = dualEntryScheduler.getTodaysCandidates();
        out.put("candidateCount", candidates.size());
        List<Map<String, Object>> candidateList = new ArrayList<>();
        for (var c : candidates) {
            Map<String, Object> ci = new LinkedHashMap<>();
            ci.put("symbol", c.getSymbol());
            ci.put("sector", c.getSector());
            ci.put("sectorRank", c.getSectorRank());
            ci.put("direction", c.getDirection());
            candidateList.add(ci);
        }
        out.put("candidates", candidateList);
        out.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(out);
    }

    @GetMapping("/dual-entry/trades/today")
    public ResponseEntity<Map<String, Object>> getDualEntryTradesToday() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (dualEntryTradeRepo == null) {
            out.put("available", false);
            return ResponseEntity.ok(out);
        }
        out.put("available", true);
        var trades = dualEntryTradeRepo.findToday();
        List<Map<String, Object>> tradeList = new ArrayList<>();
        for (var t : trades) {
            Map<String, Object> ti = new LinkedHashMap<>();
            ti.put("symbol", t.getSymbol());
            ti.put("sector", t.getSector());
            ti.put("sectorRank", t.getSectorRank());
            ti.put("direction", t.getDirection());
            ti.put("entryMode", t.getEntryMode());
            ti.put("entryPrice", t.getEntryPrice());
            ti.put("stopLoss", t.getStopLoss());
            ti.put("target", t.getTarget());
            ti.put("currentTrailStop", t.getCurrentTrailStop());
            ti.put("trailingActive", t.isTrailingActive());
            ti.put("quantity", t.getQuantity());
            ti.put("status", t.getStatus());
            ti.put("exitPrice", t.getExitPrice());
            ti.put("exitReason", t.getExitReason());
            tradeList.add(ti);
        }
        out.put("trades", tradeList);
        return ResponseEntity.ok(out);
    }

    @GetMapping("/dual-entry/gate-status")
    public ResponseEntity<Map<String, Object>> getDualEntryGateStatus() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (dualEntryGateStatusService == null) {
            out.put("available", false);
            return ResponseEntity.ok(out);
        }
        out.put("available", true);
        var snapshot = dualEntryGateStatusService.getAllStatus();
        Map<String, Object> bySymbol = new LinkedHashMap<>();
        for (var entry : snapshot.entrySet()) {
            Map<String, Object> gates = new LinkedHashMap<>();
            for (var g : entry.getValue().entrySet()) {
                Map<String, Object> gateInfo = new LinkedHashMap<>();
                gateInfo.put("state", g.getValue().state().name());
                gateInfo.put("reason", g.getValue().reason());
                gateInfo.put("updatedAt", g.getValue().updatedAt().toString());
                gates.put(g.getKey(), gateInfo);
            }
            bySymbol.put(entry.getKey(), gates);
        }
        out.put("symbols", bySymbol);
        out.put("gateNames", com.trading.dualentry.service.DualEntryGateStatusService.GATE_NAMES);
        out.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(out);
    }

    @GetMapping("/hero-zero/expiry")
    public ResponseEntity<Map<String, Object>> getHeroZeroExpiry() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (heroZeroExpiryService == null) {
            out.put("available", false);
            return ResponseEntity.ok(out);
        }
        out.put("available", true);
        out.put("overrides", heroZeroExpiryService.getAllOverrides());
        out.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(out);
    }

    /** Body: {"indexName": "NIFTY", "expiryDate": "2026-07-30"} */
    @PostMapping("/hero-zero/expiry")
    public ResponseEntity<Map<String, Object>> setHeroZeroExpiry(@RequestBody Map<String, Object> body) {
        Map<String, Object> resp = new LinkedHashMap<>();
        if (heroZeroExpiryService == null) {
            resp.put("success", false);
            resp.put("error", "Hero-Zero expiry service not available");
            return ResponseEntity.badRequest().body(resp);
        }
        try {
            String indexName = String.valueOf(body.get("indexName")).toUpperCase().trim();
            List<String> allowed = List.of("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX");
            if (!allowed.contains(indexName)) {
                resp.put("success", false);
                resp.put("error", "indexName must be one of " + allowed + ", got: " + indexName);
                return ResponseEntity.badRequest().body(resp);
            }
            java.time.LocalDate expiry = java.time.LocalDate.parse(String.valueOf(body.get("expiryDate")));
            boolean ok = heroZeroExpiryService.setOverride(indexName, expiry);
            resp.put("success", ok);
            if (!ok) resp.put("error", "rejected (past date or persistence failure - see logs)");
            resp.put("indexName", indexName);
            resp.put("expiryDate", expiry.toString());
            return ok ? ResponseEntity.ok(resp) : ResponseEntity.badRequest().body(resp);
        } catch (Exception e) {
            resp.put("success", false);
            resp.put("error", "invalid request: " + e.getMessage() +
                    " (expected {\"indexName\":\"NIFTY\",\"expiryDate\":\"YYYY-MM-DD\"})");
            return ResponseEntity.badRequest().body(resp);
        }
    }

    @DeleteMapping("/hero-zero/expiry/{indexName}")
    public ResponseEntity<Map<String, Object>> clearHeroZeroExpiry(@PathVariable String indexName) {
        Map<String, Object> resp = new LinkedHashMap<>();
        if (heroZeroExpiryService == null) {
            resp.put("success", false);
            return ResponseEntity.badRequest().body(resp);
        }
        boolean ok = heroZeroExpiryService.clearOverride(indexName.toUpperCase().trim());
        resp.put("success", ok);
        resp.put("indexName", indexName.toUpperCase().trim());
        return ResponseEntity.ok(resp);
    }


    /**
     * Returns today's capital summary for AI and News, for the UI to
     * display current values before editing.
     */
    @GetMapping("/capital")
    public ResponseEntity<Map<String, Object>> getCapital() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            out.put("AI_TRADING_V2", capitalLedger.getTodaySummary("AI_TRADING_V2"));
        } catch (Exception e) {
            out.put("AI_TRADING_V2", Map.of("error", e.getMessage()));
        }
        try {
            out.put("NEWS_CATALYST_V1", capitalLedger.getTodaySummary("NEWS_CATALYST_V1"));
        } catch (Exception e) {
            out.put("NEWS_CATALYST_V1", Map.of("error", e.getMessage()));
        }
        out.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(out);
    }

    /**
     * Sets a strategy's capital allocation. Body: {"strategyName": "...",
     * "capital": 6000}. strategyName must be exactly "AI_TRADING_V2" or
     * "NEWS_CATALYST_V1" - rejects anything else rather than silently
     * creating a ledger row for a typo'd strategy name.
     */
    @PostMapping("/capital")
    public ResponseEntity<Map<String, Object>> setCapital(@RequestBody Map<String, Object> body) {
        Map<String, Object> resp = new LinkedHashMap<>();
        try {
            String strategyName = String.valueOf(body.get("strategyName"));
            if (!"AI_TRADING_V2".equals(strategyName) && !"NEWS_CATALYST_V1".equals(strategyName)) {
                resp.put("success", false);
                resp.put("error", "strategyName must be AI_TRADING_V2 or NEWS_CATALYST_V1, got: " + strategyName);
                return ResponseEntity.badRequest().body(resp);
            }
            BigDecimal capitalAmt = new BigDecimal(String.valueOf(body.get("capital")));
            if (capitalAmt.compareTo(BigDecimal.ZERO) <= 0) {
                resp.put("success", false);
                resp.put("error", "capital must be a positive number");
                return ResponseEntity.badRequest().body(resp);
            }
            boolean ok = capitalLedger.setStartingCapital(strategyName, capitalAmt);
            resp.put("success", ok);
            resp.put("strategyName", strategyName);
            resp.put("capital", capitalAmt);
            log.warn("[DASHBOARD] Capital changed via UI: {} -> Rs.{}", strategyName, capitalAmt);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            resp.put("success", false);
            resp.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        }
    }

    // -- Private builders ---------------------------------------------------

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
    // ==========================================================================
    // NEWS CATALYST SUMMARY - read-only snapshot for dashboard
    // ==========================================================================

    private Map<String, Object> buildNewsCatalystSummary() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            out.put("enabled",             newsTradingStrategy.isEnabled());
            out.put("sessionSignals",      newsTradingStrategy.getSessionSignalCount());
            out.put("maxSignals",          newsTradingStrategy.getMaxSignalsPerSession());
            out.put("activeNewsItems",     newsTradingStrategy.getActiveItemCount());
            out.put("totalIngested",       newsTradingStrategy.getTotalIngested());
            out.put("firedToday",          newsTradingStrategy.getFiredToday());
            // Pure observability, mirrors the same fix already applied to
            // AI - see NewsTradingStrategy.blockReasons docstring.
            out.put("blockReasons",        newsTradingStrategy.getBlockReasons());
            out.put("sessionCapReached",   newsTradingStrategy.isSessionCapReachedThisCycle());

            // Recent news events that triggered signals
            List<Map<String, Object>> events = new ArrayList<>();
            for (var e : newsTradingStrategy.getRecentEvents()) {
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("symbol",    e.symbol());
                ev.put("direction", e.direction().name());
                ev.put("category",  e.category());
                ev.put("sentiment", e.sentiment());
                ev.put("score",     e.score());
                ev.put("headline",  e.headline());
                ev.put("ageMin",    e.ageMinutes());
                ev.put("firedAt",   e.firedAt());
                events.add(ev);
            }
            out.put("recentEvents", events);

            // -- scoredItems: ALL scored stocks from last cycle ------------------
            // This powers the "All Scored News Items" table in the dashboard News tab.
            // Includes every stock above threshold - traded, eligible, skipped, and below.
            // The dashboard renders TRADED (green), ELIGIBLE (gold), SKIPPED, BELOW 65.
            List<Map<String, Object>> scoredList = new ArrayList<>();
            for (NewsScore ns : newsTradingStrategy.getLastCycleScores()) {
                Map<String, Object> si = new LinkedHashMap<>();
                si.put("symbol",     ns.symbol());
                si.put("score",      ns.totalScore());
                si.put("category",   ns.primaryCategory() != null ? ns.primaryCategory().name() : "-");
                si.put("sentiment",  ns.dominantSentiment() != null ? ns.dominantSentiment().name() : "-");
                // direction is null when unclear - dashboard shows SKIPPED row
                si.put("direction",  ns.direction() != null ? ns.direction().name() : "-");
                si.put("ageMinutes", ns.ageMinutes());
                si.put("source",     ns.sourceArticles() != null && !ns.sourceArticles().isEmpty()
                        ? ns.sourceArticles().get(0).source() : "-");
                si.put("headline",   ns.primaryHeadline() != null
                        ? (ns.primaryHeadline().length() > 120
                        ? ns.primaryHeadline().substring(0, 120) + "..."
                        : ns.primaryHeadline())
                        : "-");
                // skipReason: shown as tooltip on SKIPPED pill in dashboard
                String skipReason = "";
                if (ns.direction() == null) skipReason = "direction unclear";
                else if (ns.totalScore() < 65) skipReason = "score " + ns.totalScore() + " below 65";
                si.put("skipReason", skipReason);
                scoredList.add(si);
            }
            out.put("scoredItems", scoredList);

            // -- ingestedItems: ALL raw articles regardless of symbol match ------
            // scoredItems only contains symbol-matched scores.
            // General market news (BSE filings, Moneycontrol) that don't mention
            // a specific NSE stock never appear in scoredItems.
            // ingestedItems shows EVERYTHING that was ingested - for full visibility.
            //
            // FIX: Suppress pure regulatory boilerplate from DISPLAY only.
            // Most BSE ingestion is routine compliance filings (Reg 29(2)/30/44(3),
            // Postal Ballot results, Analyst Meeting intimations) that carry zero
            // tradeable signal and clutter the dashboard. This filter only affects
            // what's RENDERED here - newsIngestionService.getActiveItems() itself,
            // scoring, and trading logic are completely untouched.
            List<Map<String, Object>> ingestedList = new ArrayList<>();
            int suppressedBoilerplate = 0;
            for (com.trading.strategy.news.NewsItem item :
                    newsIngestionService.getActiveItems()) {
                if (isRegulatoryBoilerplate(item.headline(), item.description())) {
                    suppressedBoilerplate++;
                    continue;
                }
                Map<String, Object> ai = new LinkedHashMap<>();
                ai.put("headline",   item.headline() != null
                        ? (item.headline().length() > 120
                        ? item.headline().substring(0, 120) + "..."
                        : item.headline())
                        : "-");
                ai.put("category",   item.category() != null ? item.category().name() : "-");
                ai.put("sentiment",  item.sentiment() != null ? item.sentiment().name() : "-");
                ai.put("source",     item.source() != null ? item.source() : "-");
                ai.put("ageMinutes", java.time.Duration.between(
                        item.publishedAt(),
                        java.time.Instant.now()).toMinutes());
                // Rough article score: category(30) + sentiment(25) + recency(20)
                int catScore  = item.category() != null
                        ? Math.round(item.category().basePriority / 100f * 30) : 0;
                int sentScore = item.sentiment() != null
                        ? Math.round(item.sentiment().score / 100f * 25) : 0;
                long ageMin   = java.time.Duration.between(
                        item.publishedAt(),
                        java.time.Instant.now()).toMinutes();
                int recScore  = (int) Math.round(20 * Math.exp(-ageMin / 30.0));
                ai.put("articleScore", catScore + sentScore + recScore);
                ingestedList.add(ai);
            }
            // Sort by articleScore descending
            ingestedList.sort((a, b) ->
                    Integer.compare((int) b.getOrDefault("articleScore", 0),
                            (int) a.getOrDefault("articleScore", 0)));
            out.put("ingestedItems", ingestedList);
            out.put("suppressedBoilerplateCount", suppressedBoilerplate);

            // -- globalNewsItems: macro/global articles always shown ------------
            // Filtered to GLOBAL_EVENT, RBI_POLICY, ECONOMIC_DATA categories
            // from ET Economy / RBI sources - shown regardless of symbol match
            // These never appear in scoredItems (no NSE stock name in headline)
            // but are important macro context for the trader
            List<Map<String, Object>> globalList = new ArrayList<>();
            for (com.trading.strategy.news.NewsItem item :
                    newsIngestionService.getActiveItems()) {
                String category = item.category() != null ? item.category().name() : "";
                if (!category.equals("GLOBAL_EVENT")
                        && !category.equals("RBI_POLICY")
                        && !category.equals("ECONOMIC_DATA")) continue;

                Map<String, Object> gi = new LinkedHashMap<>();
                gi.put("headline",  item.headline() != null
                        ? (item.headline().length() > 100
                        ? item.headline().substring(0, 100) + "..."
                        : item.headline())
                        : "-");
                gi.put("category",  category.replace("_", " "));
                gi.put("sentiment", item.sentiment() != null ? item.sentiment().name() : "NEUTRAL");
                gi.put("source",    item.source() != null ? item.source() : "-");
                gi.put("ageMinutes", java.time.Duration.between(
                        item.publishedAt(),
                        java.time.Instant.now()).toMinutes());
                globalList.add(gi);
            }
            // Sort by age ascending (freshest first)
            globalList.sort((a, b) ->
                    Long.compare((long) a.getOrDefault("ageMinutes", 0L),
                            (long) b.getOrDefault("ageMinutes", 0L)));
            out.put("globalNewsItems", globalList);
        } catch (Exception e) {
            log.warn("[DASHBOARD] buildNewsCatalystSummary failed: {}", e.getMessage());
            out.put("error", e.getMessage());
        }
        return out;
    }

    /**
     * Identifies routine SEBI/BSE regulatory boilerplate that carries zero
     * tradeable signal - used ONLY to declutter the dashboard's ingestedItems
     * display. Does NOT affect NewsScoreEngine, NewsKeywordFilter, category/
     * sentiment classification, or any trading decision. A purely cosmetic
     * display filter applied after scoring/categorization already happened.
     */
    private boolean isRegulatoryBoilerplate(String headline, String description) {
        String combined = ((headline != null ? headline : "") + " "
                + (description != null ? description : "")).toLowerCase();
        // Each phrase below is routine compliance text seen verbatim across
        // hundreds of BSE filings daily - never carries a trading signal.
        return combined.contains("regulation 29(2)")
                || combined.contains("regulation 29 (2)")
                || combined.contains("reg 29(2)")
                || combined.contains("regulation 30 of the sebi")
                || combined.contains("reg 30 of the sebi")
                || combined.contains("regulation 44(3)")
                || combined.contains("regulation 44 (3)")
                || combined.contains("postal ballot")
                || combined.contains("scrutinizer's report")
                || combined.contains("analyst/institutional investor meeting")
                || combined.contains("analyst / institutional investor meeting")
                || combined.contains("regulation 10(6)")
                || combined.contains("regulation 10 (6)")
                || combined.contains("disclosure under sebi (pit)")
                || combined.contains("prohibition of insider trading")
                || combined.contains("intimation of analyst");
    }

    // ==========================================================================
    // MANUAL SCANNER - exposes top News candidates plus generic market context
    // Used by the scanner UI (/scanner.html) for manual trade decisions
    // ==========================================================================

    @GetMapping("/scanner")
    public ResponseEntity<Map<String, Object>> scanner() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        Map<String, java.math.BigDecimal> prices = marketDataService.getLastPricesSimple();

        // -- News candidates --------------------------------------------------
        try {
            List<com.trading.strategy.news.NewsScore> scores = newsTradingStrategy.getLastCycleScores();
            List<Map<String, Object>> newsItems = new java.util.ArrayList<>();
            for (com.trading.strategy.news.NewsScore s : scores) {
                java.math.BigDecimal ltp = prices.get(s.symbol());
                Map<String, Object> item = new java.util.LinkedHashMap<>();
                item.put("symbol",          s.symbol());
                item.put("sector",          s.sectorName());
                item.put("score",           s.totalScore());
                item.put("categoryScore",   s.categoryScore());
                item.put("sentimentScore",  s.sentimentScore());
                item.put("recencyScore",    s.recencyScore());
                item.put("sourceScore",     s.sourceScore());
                item.put("keywordScore",    s.keywordScore());
                item.put("direction",       s.direction() != null ? s.direction().name() : "-");
                item.put("category",        s.primaryCategory() != null ? s.primaryCategory().name() : "-");
                item.put("sentiment",       s.dominantSentiment() != null ? s.dominantSentiment().name() : "-");
                item.put("headline",        s.primaryHeadline());
                item.put("ageMinutes",      s.ageMinutes());
                item.put("corroborated",    s.corroborated());
                item.put("ltp",             ltp != null ? ltp : java.math.BigDecimal.ZERO);
                item.put("fired",           newsTradingStrategy.getFiredToday().contains(s.symbol()));
                newsItems.add(item);
            }
            newsItems.sort((a, b) -> Integer.compare(
                    (int) b.getOrDefault("score", 0), (int) a.getOrDefault("score", 0)));
            out.put("news", Map.of(
                    "items",          newsItems,
                    "firedToday",     newsTradingStrategy.getFiredToday(),
                    "sessionSignals", newsTradingStrategy.getSessionSignalCount(),
                    "activeCount",    newsTradingStrategy.getActiveItemCount()
            ));
        } catch (Exception e) {
            out.put("news", Map.of("error", e.getMessage()));
        }

        // -- Market context ---------------------------------------------------
        try {
            var dir = marketDir.getCurrentDirection();
            out.put("market", Map.of(
                    "direction",   dir != null ? dir.direction().name() : "-",
                    "niftyAtrPct", dir != null ? dir.niftyAtrPct() : 0.0,
                    "label",       dir != null ? dir.direction().name() : "-"
            ));
        } catch (Exception e) {
            out.put("market", Map.of("direction", "-"));
        }

        out.put("timestamp", java.time.Instant.now().toString());
        return ResponseEntity.ok(out);
    }

    @GetMapping("/news/all")
    public ResponseEntity<Map<String, Object>> getAllNewsItems() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        try {
            // getLastCycleScores() uses scoreAllForDashboard() - NO threshold, NO direction filter
            // Returns every symbol mentioned in any active news article, regardless of score
            List<com.trading.strategy.news.NewsScore> all = newsTradingStrategy.getLastCycleScores();
            Map<String, java.math.BigDecimal> prices = marketDataService.getLastPricesSimple();

            List<Map<String, Object>> items = new java.util.ArrayList<>();
            for (com.trading.strategy.news.NewsScore s : all) {
                java.math.BigDecimal ltp = prices.get(s.symbol());
                Map<String, Object> item = new java.util.LinkedHashMap<>();
                item.put("symbol",          s.symbol());
                item.put("sector",          s.sectorName());
                item.put("score",           s.totalScore());
                item.put("categoryScore",   s.categoryScore());
                item.put("sentimentScore",  s.sentimentScore());
                item.put("recencyScore",    s.recencyScore());
                item.put("sourceScore",     s.sourceScore());
                item.put("keywordScore",    s.keywordScore());
                item.put("direction",       s.direction() != null ? s.direction().name() : "UNCLEAR");
                item.put("category",        s.primaryCategory() != null ? s.primaryCategory().name() : "-");
                item.put("sentiment",       s.dominantSentiment() != null ? s.dominantSentiment().name() : "-");
                item.put("headline",        s.primaryHeadline());
                item.put("ageMinutes",      s.ageMinutes());
                item.put("corroborated",    s.corroborated());
                item.put("ltp",             ltp != null ? ltp : java.math.BigDecimal.ZERO);
                item.put("fired",           newsTradingStrategy.getFiredToday().contains(s.symbol()));
                // Status tag for scanner display
                String status;
                int score = s.totalScore();
                if (newsTradingStrategy.getFiredToday().contains(s.symbol())) status = "FIRED";
                else if ("UNCLEAR".equals(item.get("direction")))             status = "NO_DIRECTION";
                else if (score >= 65)                                          status = "TRADEABLE";
                else if (score >= 40)                                          status = "WATCHLIST";
                else                                                           status = "LOW_SCORE";
                item.put("status", status);
                items.add(item);
            }

            // Sort: FIRED first, then TRADEABLE, then WATCHLIST, then by score desc
            items.sort((a, b) -> {
                String sa = String.valueOf(a.get("status")),
                        sb = String.valueOf(b.get("status"));
                if ("FIRED".equals(sa) && !"FIRED".equals(sb)) return -1;
                if ("FIRED".equals(sb) && !"FIRED".equals(sa)) return 1;
                return Integer.compare(
                        (int) b.getOrDefault("score", 0),
                        (int) a.getOrDefault("score", 0));
            });

            out.put("items",          items);
            out.put("total",          items.size());
            out.put("firedToday",     newsTradingStrategy.getFiredToday());
            out.put("sessionSignals", newsTradingStrategy.getSessionSignalCount());
            out.put("activeCount",    newsTradingStrategy.getActiveItemCount());
            out.put("timestamp",      java.time.Instant.now().toString());
        } catch (Exception e) {
            out.put("error", e.getMessage());
            out.put("items", Collections.emptyList());
        }
        return ResponseEntity.ok(out);
    }

    // ==========================================================================
    // AI TRADING MODULE - read-only status endpoint
    // Returns null-safe data when AI module is disabled
    // ==========================================================================

    @Autowired(required = false)
    private com.trading.ai.AiTradingSystem aiModule;

    @GetMapping("/ai/status")
    public ResponseEntity<Map<String, Object>> getAiStatus() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        if (aiModule == null) {
            out.put("enabled", false);
            out.put("message", "AI module disabled. Set AI_TRADING_ENABLED=true to enable.");
            return ResponseEntity.ok(out);
        }
        try {
            out.put("enabled", true);

            // -- Core status from AiTradingSystem -----------------------------
            // Contains: tradesToday, maxTradesPerDay, regime, phase, positions,
            //           watchlistCount, watchlist, bootstrapComplete, samplesCount
            Map<String, Object> status = aiModule.getStatus();
            out.putAll(status);

            // -- Regime and execution threshold --------------------------------
            String regime = String.valueOf(status.getOrDefault("regime", "UNKNOWN"));
            int threshold = "TRENDING".equals(regime) ? 70
                    : "RANGING".equals(regime)  ? 80
                    : 999;
            out.put("executionThreshold", threshold);
            out.put("thresholdLabel",
                    "TRENDING".equals(regime) ? "Score >= 70 + direction match"
                            : "RANGING".equals(regime)  ? "Score >= 80 (both directions)"
                            : "CHOPPY - watchlist only, no execution");

            // -- Watchlist (stocks with confirmed daily patterns) --------------
            // -- Watchlist already included via out.putAll(status) above ----------
            // watchlist is now List<Map<symbol,pattern>> - no cast needed
            // Just ensure watchlistCount is correct
            @SuppressWarnings("unchecked")
            java.util.List<?> watchlist =
                    (java.util.List<?>) status.getOrDefault("watchlist", java.util.List.of());
            out.put("watchlist",      watchlist);
            out.put("watchlistCount", watchlist.size());

            // -- Recent AI decisions with full confidence breakdown ------------
            out.put("recentDecisions", aiModule.getTodayDecisions().stream().limit(10).map(d -> {
                Map<String, Object> m = new java.util.LinkedHashMap<>();

                // Trade levels
                m.put("symbol",         d.getSymbol());
                m.put("direction",      d.getDirection());
                m.put("entryPrice",     d.getEntryPrice());
                m.put("stopLoss",       d.getStopLoss());
                m.put("target1",        d.getTarget1());
                m.put("target2",        d.getTarget2());

                // 100-point confidence model breakdown
                int confTotal = (int)(d.getConfidence() * 100);
                m.put("confidenceScore",       confTotal + "/100");
                m.put("confidenceTotal",       confTotal);

                // Pattern info
                m.put("dominantPattern",  d.getDominantFactor());
                m.put("patternScore",     "50/50");  // binary - if here, pattern passed
                m.put("bullishPatterns",  d.getNumericPreScore()); // reused field

                // Probability and RR from risk engine
                m.put("pSuccess",       String.format("%.0f%%", d.getProbabilityOfSuccess()*100));
                m.put("expectedRR",     String.format("%.1f",  d.getExpectedRR()));
                m.put("expectedReturn", String.format("%.1f%%", d.getExpectedReturn()));

                // Reasoning narrative
                m.put("reasoning",     d.getReasoningSummary());
                m.put("bullScenario",  d.getBullScenario());
                m.put("bearScenario",  d.getBearScenario());
                m.put("exitPlan",      d.getExitPlan());

                // Quality
                m.put("qualityScore",  d.getTradeQualityScore() + "/100");

                return m;
            }).collect(java.util.stream.Collectors.toList()));

            // -- Pipeline stage summary ----------------------------------------
            Map<String, Object> pipeline = new java.util.LinkedHashMap<>();
            pipeline.put("stage1", "Daily qualification (252-day data, EMA/ADR/52wk)");
            pipeline.put("stage2", "REMOVED - daily patterns are the only qualification layer");
            pipeline.put("stage3", "Feature build + 16 daily pattern gate (min 1 required)");
            pipeline.put("gate1",  "Daily pattern confirmed (mandatory)");
            pipeline.put("gate2",  "5m candle confirms direction (mandatory, >25% body)");
            pipeline.put("scoring","Pattern=50pts + Candle=20 + Volume=10 + Trend=10 + PA=10");
            out.put("pipeline", pipeline);

            // -- Daily patterns reference --------------------------------------
            out.put("patterns", java.util.List.of(
                    "BOS (f60)", "CHOCH (f61)", "OrderBlock (f62)", "FVG (f63)",
                    "AccumDist (f64)", "TriplePattern (f65)", "H&S (f66)",
                    "Triangle (f67)", "Channel (f68)", "TrendlineD (f69)",
                    "SweepLow (f54)", "SweepHigh (f55)", "SRFlip (f56)",
                    "ChannelPos (f57)", "TrendlineI (f58)", "SupplyDemand (f47)"
            ));

        } catch (Exception e) {
            out.put("error", e.getMessage());
        }
        return ResponseEntity.ok(out);
    }

}