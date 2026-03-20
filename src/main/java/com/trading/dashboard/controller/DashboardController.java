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

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
@Slf4j
public class DashboardController {

    private final MarketDirectionService      marketDirection;
    private final CircuitBreakerService       circuitBreaker;
    private final MarketDataService           marketDataService;
    private final SectorStrengthService       sectorStrength;
    private final SectorClassificationService sectorClassify;
    private final TradeExecutionService       tradeExecution;
    private final TradeManagementService      tradeManagement;
    private final VixService                  vixService;
    private final MarketTimingService         timingService;
    private final SevenGateScannerService     scanner;

    @Value("${trading.capital:100000}")
    private String capitalStr;

    @Value("${zerodha.account-id:}")
    private String accountId;

    // ── Full snapshot ─────────────────────────────────────────────────

    @GetMapping("/snapshot")
    public ResponseEntity<Map<String, Object>> snapshot() {
        Map<String, Object> data = new LinkedHashMap<>();
        BigDecimal capital = new BigDecimal(capitalStr);

        // ── System ────────────────────────────────────────────────────
        Map<String, Object> system = new LinkedHashMap<>();
        system.put("websocketConnected",   marketDataService.isConnected());
        system.put("circuitBreakerActive", circuitBreaker.isActive());
        system.put("circuitBreakerReason", nullSafe(circuitBreaker.getDisableReason()));
        system.put("currentWindow",        timingService.getCurrentWindowName());
        system.put("entryAllowed",         timingService.isEntryAllowed());
        system.put("vix",                  vixService.getCurrentVix());
        system.put("vixRegime",            vixService.getRegime().name());
        system.put("accountId",            accountId);
        system.put("timestamp",            Instant.now().toString());
        data.put("system", system);

        // ── Regime ────────────────────────────────────────────────────
        MarketDirectionService.MarketDirectionResult dir =
                marketDirection.getCurrentDirection();
        Map<String, Object> regime = new LinkedHashMap<>();
        regime.put("name",            dir.direction().name());
        regime.put("label",           regimeLabel(dir.direction()));
        regime.put("tradeable",       dir.isTradeable());
        regime.put("longFavourable",  dir.isLong());
        regime.put("shortFavourable", dir.isShort());
        regime.put("failReason",      nullSafe(dir.failReason()));
        regime.put("niftyAtrPct",     dir.niftyAtrPct());
//        regime.put("bankNiftyAtrPct", dir.bankNiftyAtrPct());
        regime.put("niftyEma20",      dir.niftyEma20());
        regime.put("niftyEma50",      dir.niftyEma50());
        regime.put("niftyEma200",     dir.niftyEma200());
        data.put("regime", regime);

        // ── Market direction detail ───────────────────────────────────
        Map<String, Object> mktDir = new LinkedHashMap<>();
        mktDir.put("niftyEma20",      dir.niftyEma20());
        mktDir.put("niftyEma50",      dir.niftyEma50());
        mktDir.put("niftyEma200",     dir.niftyEma200());
        mktDir.put("niftyAtrPct",     dir.niftyAtrPct());
//        mktDir.put("bankNiftyAtrPct", dir.bankNiftyAtrPct());
        data.put("marketDirection", mktDir);

        // ── P&L ───────────────────────────────────────────────────────
        BigDecimal dailyPnl   = circuitBreaker.getDailyPnl();
        BigDecimal weeklyPnl  = circuitBreaker.getWeeklyPnl();
        BigDecimal monthlyPnl = circuitBreaker.getMonthlyPnl();
        double     dailyPct   = pct(dailyPnl, capital);

        Map<String, Object> pnl = new LinkedHashMap<>();
        pnl.put("capital",         capital);
        pnl.put("dailyPnl",        dailyPnl);
        pnl.put("weeklyPnl",       weeklyPnl);
        pnl.put("monthlyPnl",      monthlyPnl);
        pnl.put("dailyPct",        dailyPct);
        pnl.put("tradesToday",     circuitBreaker.getTradesToday());
        pnl.put("maxTradesPerDay", circuitBreaker.getMaxPerDay());
        pnl.put("cbActive",        circuitBreaker.isActive());
        pnl.put("cbReason",        nullSafe(circuitBreaker.getDisableReason()));
        pnl.put("vix",             vixService.getCurrentVix());
        pnl.put("currentWindow",   timingService.getCurrentWindowName());
        data.put("pnl", pnl);

        // ── Sectors ───────────────────────────────────────────────────
        List<Map<String, Object>> sectors = new ArrayList<>();
        for (String sectorName : sectorClassify.getAllSectorNames()) {
            SectorStrengthService.SectorData sd = sectorStrength.getSector(sectorName);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name",             sectorName);
            // Use alignedBullish/Bearish — no .classification() method
            s.put("classification",   sectorClassification(sd));
            s.put("changePercent",    round2(sd.changePercent()));
            s.put("relativeStrength", round2(sd.relativeStrength()));
            s.put("greenPct",         String.format("%.1f", sd.greenPct()));
            s.put("totalStocks",      sd.totalStocks());
            s.put("greenStocks",      sd.greenStocks());
            s.put("redStocks",        sd.redStocks());
            s.put("alignedBullish",   sd.alignedBullish());
            s.put("alignedBearish",   sd.alignedBearish());
            sectors.add(s);
        }
        data.put("sectors", sectors);

        // ── Active trades with live unrealized P&L ────────────────────
        Map<String, BigDecimal> prices = marketDataService.getLastPricesSimple();

        List<Map<String, Object>> activeTrades = new ArrayList<>();
        for (TradeManagementService.ManagedTrade mt : tradeManagement.getActiveTrades()) {
            var t = mt.trade();
            BigDecimal ltp = prices.getOrDefault(
                    t.getTradingSymbol(), t.getEntryPrice());

            BigDecimal unrealizedPnl = t.getDirection().name().equals("LONG")
                    ? ltp.subtract(t.getEntryPrice())
                    .multiply(BigDecimal.valueOf(mt.remainingQty()))
                    : t.getEntryPrice().subtract(ltp)
                    .multiply(BigDecimal.valueOf(mt.remainingQty()));

            double rDist = mt.rDistance().doubleValue();
            double rMult = rDist > 0
                    ? unrealizedPnl.doubleValue() / rDist / mt.remainingQty()
                    : 0;

            String phase = rMult >= 2.0 ? "Trail 0.5ATR"
                    : rMult >= 1.5          ? "Trail 1ATR"
                    : rMult >= 1.0          ? "Breakeven"
                    :                         "Survival";

            Map<String, Object> tr = new LinkedHashMap<>();
            tr.put("tradingSymbol",  t.getTradingSymbol());
            tr.put("direction",      t.getDirection().name());
            tr.put("quantity",       t.getQuantity());
            tr.put("remainingQty",   mt.remainingQty());
            tr.put("entryPrice",     t.getEntryPrice());
            tr.put("ltp",            ltp);
            tr.put("stopLoss",       t.getStopLoss());
            tr.put("target",         t.getTarget());
            tr.put("unrealizedPnl",  unrealizedPnl);
            tr.put("rMultiple",      String.format("%.2f", rMult));
            tr.put("tradePhase",     phase);
            tr.put("status",         "OPEN");
            activeTrades.add(tr);
        }
        data.put("activeTrades", activeTrades);

        // ── Today's trades ────────────────────────────────────────────
        data.put("todayTrades", tradeExecution.getTodayTrades(LocalDate.now()));

        // ── Armed stocks ──────────────────────────────────────────────
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
        data.put("armedStocks", armedMap);

        // ── Gate rejections ───────────────────────────────────────────
        data.put("gateRejections", scanner.getGateRejections());

        // ── Gate status (overview tab) ────────────────────────────────
        Map<Integer, String> gateStatus = new LinkedHashMap<>();
        gateStatus.put(1, dir.isTradeable() ? "PASS" : "FAIL");
        gateStatus.put(2, "WAIT");
        gateStatus.put(3, "WAIT");
        gateStatus.put(4, "WAIT");
        gateStatus.put(5, "WAIT");
        gateStatus.put(6, "WAIT");
        gateStatus.put(7, "WAIT");
        data.put("gateStatus", gateStatus);

        return ResponseEntity.ok(data);
    }

    // ── Live prices ───────────────────────────────────────────────────

    @GetMapping("/prices")
    public ResponseEntity<Map<String, Object>> prices() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("prices",    marketDataService.getLastPrices());
        resp.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(resp);
    }

    // ── Circuit breaker reset ─────────────────────────────────────────

    @PostMapping("/circuit-breaker/reset")
    public ResponseEntity<String> resetCb() {
        circuitBreaker.manualReset();
        log.warn("Circuit breaker manually reset");
        return ResponseEntity.ok("Circuit breaker reset successfully");
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private double pct(BigDecimal pnl, BigDecimal capital) {
        if (capital == null || capital.compareTo(BigDecimal.ZERO) == 0) return 0;
        return pnl.divide(capital, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).doubleValue();
    }

    private BigDecimal round2(double val) {
        return BigDecimal.valueOf(val).setScale(2, RoundingMode.HALF_UP);
    }

    private String nullSafe(String val) {
        return val != null ? val : "";
    }

    private String regimeLabel(MarketDirectionService.Direction d) {
        return switch (d) {
            case BULLISH  -> "Strong Bull";
            case BEARISH  -> "Strong Bear";
            case SIDEWAYS -> "Sideways";
        };
    }

    /** Derive a classification string from SectorData fields */
    private String sectorClassification(SectorStrengthService.SectorData sd) {
        if (sd.alignedBullish()) return "STRONG";
        if (sd.alignedBearish()) return "WEAK";
        return "NEUTRAL";
    }
}