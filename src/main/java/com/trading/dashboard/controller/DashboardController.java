package com.trading.dashboard.controller;

import com.trading.execution.service.TradeExecutionService;
import com.trading.execution.service.TradeManagementService;
import com.trading.marketdata.service.MarketDataService;
import com.trading.marketdata.service.MarketTimingService;
import com.trading.marketdata.service.VixService;
import com.trading.regime.service.MarketDirectionService;
import com.trading.risk.service.CircuitBreakerService;
import com.trading.scanner.service.SevenGateScannerService;
import com.trading.sector.service.SectorClassificationService;
import com.trading.sector.service.SectorStrengthService;
import com.trading.strategy.StrategyEvaluatorService;
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
 * DashboardController
 *
 * FIX: Added 'system', 'regime', and 'marketDirection' sections to /snapshot response.
 *
 * ROOT CAUSE of WS RED / VIX 0 / EMA 0/0/0 on dashboard:
 *   The frontend JS reads:
 *     d.system.websocketConnected  → for WS pill colour
 *     d.system.vix                 → for VIX display
 *     d.system.vixRegime           → for VIX regime
 *     d.system.currentWindow       → for Window
 *     d.system.entryAllowed        → for Entry status
 *     d.regime.name                → for direction pill
 *     d.regime.failReason          → for failReason text
 *     d.marketDirection.niftyEma*  → for EMA rows in Risk tab
 *
 *   But the old API returned only a 'market' object.
 *   None of the above keys existed → all defaulted to undefined → false/0/—.
 *
 * FIX: We now emit BOTH the old 'market' section (for backward compat)
 *   AND the new 'system', 'regime', 'marketDirection' sections the JS expects.
 */
@RestController
@RequestMapping("/api/dashboard")
@Slf4j
@RequiredArgsConstructor
public class DashboardController {

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

    @Value("${trading.capital:100000}") private BigDecimal capital;

    // ══════════════════════════════════════════════════════════════════════
    // GET /api/dashboard/snapshot
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/snapshot")
    public ResponseEntity<Map<String, Object>> snapshot() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("timestamp", Instant.now().toString());

        MarketDirectionService.MarketDirectionResult dir = marketDir.getCurrentDirection();
        double  vix     = vixService.getCurrentVix();
        String  vixReg  = vixService.getRegime().name();
        String  window  = timingService.getCurrentWindowName();
        boolean entry   = timingService.isEntryAllowed();
        boolean wsOk    = marketDataService.isConnected(); // isConnected() exists at MarketDataService line 7017

        // ── 1. 'market' — backward-compat block (keep for any legacy consumers) ──
        Map<String, Object> market = new LinkedHashMap<>();
        market.put("direction",     dir.direction().name());
        market.put("label",         regimeLabel(dir.direction()));
        market.put("niftyBullish",  dir.niftyBullish());
        market.put("niftyBearish",  dir.niftyBearish());
        market.put("niftyEma20",    round2(dir.niftyEma20()));
        market.put("niftyEma50",    round2(dir.niftyEma50()));
        market.put("niftyEma200",   round2(dir.niftyEma200()));
        market.put("niftyAtrPct",   round2(dir.niftyAtrPct()));
        market.put("failReason",    nullSafe(dir.failReason()));
        market.put("vix",           vix);
        market.put("vixRegime",     vixReg);
        market.put("window",        window);
        market.put("entryAllowed",  entry);
        data.put("market", market);

        // ── 2. 'system' — what the WS pill, VIX pill, Window stat read from ──────
        // FIX: these keys were missing → WS was always red, VIX showed —
        Map<String, Object> system = new LinkedHashMap<>();
        system.put("websocketConnected", wsOk);      // WS pill: green if true
        system.put("vix",                vix);        // VIX pill display
        system.put("vixRegime",          vixReg);     // VIX regime label
        system.put("currentWindow",      window);     // Window stat card
        system.put("entryAllowed",       entry);      // "✓ Entries open" / "✗"
        data.put("system", system);

        // ── 3. 'regime' — what the direction pill and failReason read from ────────
        // FIX: JS reads d.regime.name but old API only had d.market.direction
        Map<String, Object> regime = new LinkedHashMap<>();
        regime.put("name",       dir.direction().name());   // "BULLISH" / "BEARISH" / "SIDEWAYS"
        regime.put("label",      regimeLabel(dir.direction()));
        regime.put("failReason", nullSafe(dir.failReason()));
        data.put("regime", regime);

        // ── 4. 'marketDirection' — what the EMA rows in Risk tab read from ────────
        // FIX: JS reads d.marketDirection.niftyEma20 but old API had d.market.niftyEma20
        Map<String, Object> marketDirection = new LinkedHashMap<>();
        marketDirection.put("niftyBullish",  dir.niftyBullish());
        marketDirection.put("niftyBearish",  dir.niftyBearish());
        marketDirection.put("niftyEma20",    round2(dir.niftyEma20()));
        marketDirection.put("niftyEma50",    round2(dir.niftyEma50()));
        marketDirection.put("niftyEma200",   round2(dir.niftyEma200()));
        marketDirection.put("niftyAtrPct",   round2(dir.niftyAtrPct()));
        marketDirection.put("failReason",    nullSafe(dir.failReason()));
        data.put("marketDirection", marketDirection);

        // ── 5. P&L ────────────────────────────────────────────────────────────────
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

        // ── 6. Sectors ────────────────────────────────────────────────────────────
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

        // ── 7. Active trades ──────────────────────────────────────────────────────
        Map<String, BigDecimal> prices = marketDataService.getLastPricesSimple();
        List<Map<String, Object>> activeTrades = new ArrayList<>();
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
            tr.put("status",         "OPEN");
            activeTrades.add(tr);
        }
        data.put("activeTrades", activeTrades);

        // ── 8. Today's closed trades ──────────────────────────────────────────────
        data.put("todayTrades", tradeExecution.getTodayTrades(LocalDate.now()));

        // ── 9. Strategy status panel ──────────────────────────────────────────────
        Map<String, Object> strategyStatus = new LinkedHashMap<>();
        strategyStatus.put("firedToday",      strategyEvaluator.getFiredToday());
        strategyStatus.put("perStrategyPnl",  buildStrategyPnl());
        strategyStatus.put("strategySummary", buildStrategySummary());
        data.put("strategyStatus", strategyStatus);

        // ── 10. Armed stocks ──────────────────────────────────────────────────────
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
        data.put("armedStocks",     armedMap);
        data.put("gateRejections",  scanner.getGateRejections());

        // ── 11. Gate status ───────────────────────────────────────────────────────
        Map<Integer, String> gateStatus = new LinkedHashMap<>();
        gateStatus.put(1, dir.isTradeable() ? "PASS" : "FAIL");
        for (int i = 2; i <= 7; i++) gateStatus.put(i, "WAIT");
        data.put("gateStatus", gateStatus);

        return ResponseEntity.ok(data);
    }

    // ══════════════════════════════════════════════════════════════════════
    // GET /api/dashboard/prices
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/prices")
    public ResponseEntity<Map<String, Object>> prices() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("prices",    marketDataService.getLastPrices());
        resp.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(resp);
    }

    // ══════════════════════════════════════════════════════════════════════
    // POST /api/dashboard/circuit-breaker/reset
    // ══════════════════════════════════════════════════════════════════════

    @PostMapping("/circuit-breaker/reset")
    public ResponseEntity<String> resetCb() {
        circuitBreaker.manualReset();
        log.warn("Circuit breaker manually reset");
        return ResponseEntity.ok("Circuit breaker reset successfully");
    }

    // ══════════════════════════════════════════════════════════════════════
    // GET /api/dashboard/strategy-performance
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/strategy-performance")
    public ResponseEntity<Map<String, Object>> strategyPerformance() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("perStrategyPnl",  buildStrategyPnl());
        resp.put("firedToday",      strategyEvaluator.getFiredToday());
        resp.put("strategySummary", buildStrategySummary());
        resp.put("timestamp",       Instant.now().toString());
        return ResponseEntity.ok(resp);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════

    private Map<String, Object> buildStrategyPnl() {
        Map<String, Object> result  = new LinkedHashMap<>();
        Map<String, Double>  pnlMap  = new LinkedHashMap<>();
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
        for (String name : List.of("SCANNER_7GATE", "AUTO_MODE",
                "RANGE_BREAKOUT_3TOUCH", "ORB_VWAP_SECTOR")) {
            long count = fired.stream().filter(k -> k.endsWith(":" + name)).count();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("strategy",     name);
            m.put("signalsFired", count);
            list.add(m);
        }
        return list;
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