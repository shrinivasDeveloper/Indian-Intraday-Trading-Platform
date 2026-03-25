package com.trading.papertrading.controller;

import com.trading.domain.entity.Trade;
import com.trading.domain.enums.TradeDirection;
import com.trading.execution.service.TradeManagementService;
import com.trading.papertrading.model.PaperAccount;
import com.trading.papertrading.service.PaperTradeExecutionService;
import com.trading.papertrading.service.PaperTradeManagementService;
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
import java.util.stream.Collectors;

/**
 * Paper trading REST API — mirrors DashboardController structure.
 *
 * GET  /api/paper/status          — current mode (LIVE or PAPER)
 * GET  /api/paper/snapshot        — full dashboard data (positions, account, trades)
 * GET  /api/paper/trades/today    — today's closed trades
 * GET  /api/paper/trades/all      — all paper trades
 * GET  /api/paper/account         — account summary
 * POST /api/paper/reset           — reset virtual account
 */
@RestController
@RequestMapping("/api/paper")
@Slf4j
@RequiredArgsConstructor
public class PaperTradingController {

    private final PaperTradeExecutionService   execution;
    private final PaperTradeManagementService  management;
    private final PaperAccount                 account;

    @Value("${trading.mode:LIVE}")
    private String tradingMode;

    // ── Mode status ───────────────────────────────────────────────────

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("mode",       tradingMode.toUpperCase());
        r.put("isPaper",    "PAPER".equalsIgnoreCase(tradingMode));
        r.put("isLive",     "LIVE".equalsIgnoreCase(tradingMode));
        r.put("timestamp",  Instant.now().toString());
        return ResponseEntity.ok(r);
    }

    // ── Snapshot — main dashboard polling endpoint ───────────────────

    @GetMapping("/snapshot")
    public ResponseEntity<Map<String, Object>> snapshot() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode",      tradingMode.toUpperCase());
        data.put("isPaper",   "PAPER".equalsIgnoreCase(tradingMode));
        data.put("timestamp", Instant.now().toString());

        // ── Account summary ───────────────────────────────────────────
        Map<String, Object> acct = new LinkedHashMap<>();
        acct.put("initialCapital",    account.getInitialCapital());
        acct.put("currentCapital",    account.getCapital());
        acct.put("totalPnl",          account.getTotalPnl());
        acct.put("totalReturnPct",    String.format("%.2f%%", account.getTotalReturnPct()));
        acct.put("dailyPnl",          account.getDailyPnl());
        acct.put("dailyReturnPct",    String.format("%.2f%%", account.getDailyReturnPct()));
        acct.put("dailyTrades",       account.getDailyTrades());
        acct.put("dailyWins",         account.getDailyWins());
        acct.put("dailyLosses",       account.getDailyLosses());
        acct.put("dailyWinRate",      String.format("%.1f%%", account.getDailyWinRate() * 100));
        acct.put("dailyProfitFactor", String.format("%.2f",   account.getDailyProfitFactor()));
        acct.put("totalTrades",       account.getTotalTrades());
        acct.put("totalWins",         account.getTotalWins());
        acct.put("totalLosses",       account.getTotalLosses());
        acct.put("totalWinRate",      String.format("%.1f%%", account.getTotalWinRate() * 100));
        acct.put("totalProfitFactor", String.format("%.2f",   account.getTotalProfitFactor()));
        acct.put("maxDrawdownPct",    String.format("%.2f%%", account.getMaxDrawdownPct()));
        acct.put("maxDrawdownRs",     account.getMaxDrawdown());
        data.put("account", acct);

        // ── Active positions — mirrors DashboardController activeTrades ─
        Map<String, BigDecimal> prices = management.getLastPrices();
        List<Map<String, Object>> openList = new ArrayList<>();

        for (PaperTradeManagementService.ManagedTrade mt : management.getActiveTrades()) {
            Trade t    = mt.trade();
            String sym = t.getTradingSymbol();
            BigDecimal ltp = prices.getOrDefault(sym, t.getEntryPrice());

            BigDecimal unrealPnl = t.getDirection() == TradeDirection.LONG
                    ? ltp.subtract(t.getEntryPrice())
                    .multiply(BigDecimal.valueOf(mt.remainingQty()))
                    : t.getEntryPrice().subtract(ltp)
                    .multiply(BigDecimal.valueOf(mt.remainingQty()));

            double rDist   = mt.rDistance().doubleValue();
            double rMult   = rDist > 0
                    ? unrealPnl.doubleValue() / rDist / mt.remainingQty() : 0;

            // Same phase logic as DashboardController
            String phase = rMult >= 4.0 ? "Trail 0.5ATR (4R+)"
                    : rMult >= 3.0 ? "Trailing (3R+)"
                    : rMult >= 1.0 ? "Breakeven"
                    : "Survival";

            Map<String, Object> pos = new LinkedHashMap<>();
            pos.put("tradingSymbol",  sym);
            pos.put("direction",      t.getDirection().name());
            pos.put("strategyName",   t.getStrategyName());
            pos.put("quantity",       t.getQuantity());
            pos.put("remainingQty",   mt.remainingQty());
            pos.put("entryPrice",     t.getEntryPrice());   // fill price (with slippage)
            pos.put("ltp",            ltp);
            pos.put("stopLoss",       t.getStopLoss());     // live SL (may have moved)
            pos.put("target",         t.getTarget());
            pos.put("unrealizedPnl",  unrealPnl.setScale(2, RoundingMode.HALF_UP));
            pos.put("rMultiple",      String.format("%.2fR", rMult));
            pos.put("tradePhase",     phase);
            pos.put("slAtBreakeven",  mt.slAtBreakeven());
            pos.put("trailActive",    mt.trailActive());
            pos.put("halfExited",     mt.halfExited());
            pos.put("status",         "OPEN");
            openList.add(pos);
        }
        data.put("activeTrades", openList);

        // ── Today's trades — same structure as DashboardController ────
        data.put("todayTrades", buildTradeRows(execution.getTodayTrades(LocalDate.now())));

        // ── Strategy breakdown ────────────────────────────────────────
        data.put("strategyBreakdown",
                buildStrategyBreakdown(execution.getTodayTrades(LocalDate.now())));

        return ResponseEntity.ok(data);
    }

    // ── Trade history ─────────────────────────────────────────────────

    @GetMapping("/trades/today")
    public ResponseEntity<List<Map<String, Object>>> todayTrades() {
        return ResponseEntity.ok(
                buildTradeRows(execution.getTodayTrades(LocalDate.now())));
    }

    @GetMapping("/trades/all")
    public ResponseEntity<Map<String, Object>> allTrades(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size) {
        List<Trade> all   = execution.getAllTrades();
        int total = all.size();
        int from  = Math.min(page * size, total);
        int to    = Math.min(from + size, total);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("total",  total);
        r.put("page",   page);
        r.put("size",   size);
        r.put("trades", buildTradeRows(all.subList(from, to)));
        return ResponseEntity.ok(r);
    }

    // ── Account ───────────────────────────────────────────────────────

    @GetMapping("/account")
    public ResponseEntity<Map<String, Object>> accountSummary() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("initialCapital",    account.getInitialCapital());
        r.put("currentCapital",    account.getCapital());
        r.put("totalPnl",          account.getTotalPnl());
        r.put("totalReturnPct",    String.format("%.2f%%", account.getTotalReturnPct()));
        r.put("totalTrades",       account.getTotalTrades());
        r.put("totalWins",         account.getTotalWins());
        r.put("totalLosses",       account.getTotalLosses());
        r.put("totalWinRate",      String.format("%.1f%%", account.getTotalWinRate() * 100));
        r.put("totalProfitFactor", String.format("%.2f",   account.getTotalProfitFactor()));
        r.put("maxDrawdownPct",    String.format("%.2f%%", account.getMaxDrawdownPct()));
        r.put("maxDrawdownRs",     account.getMaxDrawdown());
        r.put("dailyPnl",          account.getDailyPnl());
        r.put("dailyTrades",       account.getDailyTrades());
        r.put("dailyWinRate",      String.format("%.1f%%", account.getDailyWinRate() * 100));
        return ResponseEntity.ok(r);
    }

    // ── Reset ─────────────────────────────────────────────────────────

    @PostMapping("/reset")
    public ResponseEntity<String> resetAccount() {
        account.hardReset();
        log.warn("[PAPER] Account reset via API");
        return ResponseEntity.ok(
                "Paper trading account reset. Capital restored to ₹"
                        + account.getInitialCapital());
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private List<Map<String, Object>> buildTradeRows(List<Trade> trades) {
        return trades.stream().map(t -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tradeDate",       t.getTradeDate() != null ? t.getTradeDate().toString() : null);
            row.put("tradingSymbol",   t.getTradingSymbol());
            row.put("direction",       t.getDirection() != null ? t.getDirection().name() : null);
            row.put("strategyName",    t.getStrategyName());
            row.put("quantity",        t.getQuantity());
            row.put("entryPrice",      t.getEntryPrice());
            row.put("exitPrice",       t.getExitPrice());
            row.put("stopLoss",        t.getStopLoss());
            row.put("target",          t.getTarget());
            row.put("status",          t.getStatus());
            row.put("exitReason",      t.getExitReason());
            row.put("netPnl",          t.getNetPnl() != null
                    ? String.format("%.2f", t.getNetPnl().doubleValue()) : null);
            row.put("probabilityScore", t.getProbabilityScore());
            row.put("entryTime",       t.getEntryTime() != null
                    ? t.getEntryTime().toString() : null);
            row.put("exitTime",        t.getExitTime() != null
                    ? t.getExitTime().toString() : null);
            return row;
        }).collect(Collectors.toList());
    }

    private Map<String, Object> buildStrategyBreakdown(List<Trade> trades) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, List<Trade>> byStrat = trades.stream()
                .filter(t -> "CLOSED".equals(t.getStatus()))
                .collect(Collectors.groupingBy(t ->
                        t.getStrategyName() != null ? t.getStrategyName() : "UNKNOWN"));
        byStrat.forEach((strat, list) -> {
            long wins  = list.stream()
                    .filter(t -> t.getNetPnl() != null
                            && t.getNetPnl().doubleValue() > 0).count();
            double pnl = list.stream()
                    .filter(t -> t.getNetPnl() != null)
                    .mapToDouble(t -> t.getNetPnl().doubleValue()).sum();
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("trades",  list.size());
            s.put("wins",    wins);
            s.put("losses",  list.size() - wins);
            s.put("winRate", list.size() > 0
                    ? String.format("%.1f%%", (double) wins / list.size() * 100) : "0%");
            s.put("pnl",     String.format("%.2f", pnl));
            result.put(strat, s);
        });
        return result;
    }
}