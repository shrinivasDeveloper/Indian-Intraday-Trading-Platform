package com.trading.backtest.controller;

import com.trading.backtest.model.BacktestJob;
import com.trading.backtest.service.BacktestJobService;
import com.trading.backtest.service.StrategyBacktestEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * StrategyBacktestController — FINAL MERGED VERSION.
 *
 * FILE LOCATION: src/main/java/com/trading/backtest/controller/StrategyBacktestController.java
 *
 * DO NOT create a copy of this file in com.trading.papertrading.controller —
 * that causes ConflictingBeanDefinitionException on startup.
 *
 * CHANGES vs original in this file:
 *   FIX 1: "ALL" includes all 7 strategies
 *   FIX 2: Frontend display name → backend key normalization
 *   FIX 3: Portfolio result in API response
 *   FIX 4: Added "VAP_PULLBACK" — the exact key PullbackDetectionService.name() returns.
 *          "VWAP_PULLBACK" kept as alias for backward compatibility.
 */
@RestController
@RequestMapping("/api/backtest")
@Slf4j
@RequiredArgsConstructor
public class StrategyBacktestController {

    private final BacktestJobService jobService;

    // ── ALL VALID STRATEGY KEYS ────────────────────────────────────────────────
    // These must exactly match what each strategy's name() method returns.
    private static final List<String> ALL_STRATEGIES = List.of(
            "SIMPLE_ORB",
            "VWAP_MOMENTUM",
            "VAP_PULLBACK",          // FIX 4: PullbackDetectionService.name() returns this
            "VWAP_PULLBACK",         // kept as alias for old backtest jobs
            "AUTO_MODE",
            "RANGE_BREAKOUT_3TOUCH",
            "ORB_VWAP_SECTOR",
            "SCANNER_7GATE"
    );

    // ── DISPLAY NAME → BACKEND KEY ─────────────────────────────────────────────
    private static final Map<String, String> NAME_MAP = new LinkedHashMap<>();
    static {
        NAME_MAP.put("simple orb",            "SIMPLE_ORB");
        NAME_MAP.put("simple_orb",            "SIMPLE_ORB");
        NAME_MAP.put("simpleorb",             "SIMPLE_ORB");
        NAME_MAP.put("orb",                   "SIMPLE_ORB");
        NAME_MAP.put("orb_vwap_sector",       "ORB_VWAP_SECTOR");
        NAME_MAP.put("orb vwap sector",       "ORB_VWAP_SECTOR");
        NAME_MAP.put("orb vwap",              "ORB_VWAP_SECTOR");

        NAME_MAP.put("vwap momentum",         "VWAP_MOMENTUM");
        NAME_MAP.put("vwap_momentum",         "VWAP_MOMENTUM");
        NAME_MAP.put("vwapmomentum",          "VWAP_MOMENTUM");
        NAME_MAP.put("vwap breakout",         "VWAP_MOMENTUM");

        NAME_MAP.put("auto mode",             "AUTO_MODE");
        NAME_MAP.put("auto_mode",             "AUTO_MODE");
        NAME_MAP.put("automode",              "AUTO_MODE");

        // FIX 4: All pullback aliases → VAP_PULLBACK
        NAME_MAP.put("vap pullback",          "VAP_PULLBACK");
        NAME_MAP.put("vap_pullback",          "VAP_PULLBACK");
        NAME_MAP.put("vappullback",           "VAP_PULLBACK");
        NAME_MAP.put("value area pullback",   "VAP_PULLBACK");
        NAME_MAP.put("value_area_pullback",   "VAP_PULLBACK");
        NAME_MAP.put("vwap pullback",         "VAP_PULLBACK");
        NAME_MAP.put("vwap_pullback",         "VAP_PULLBACK");
        NAME_MAP.put("vwappullback",          "VAP_PULLBACK");
        NAME_MAP.put("pullback",              "VAP_PULLBACK");

        NAME_MAP.put("range breakout",        "RANGE_BREAKOUT_3TOUCH");
        NAME_MAP.put("range_breakout",        "RANGE_BREAKOUT_3TOUCH");
        NAME_MAP.put("range_breakout_3touch", "RANGE_BREAKOUT_3TOUCH");
        NAME_MAP.put("rangebreakout",         "RANGE_BREAKOUT_3TOUCH");
        NAME_MAP.put("3 touch",               "RANGE_BREAKOUT_3TOUCH");

        NAME_MAP.put("7-gate scanner",        "SCANNER_7GATE");
        NAME_MAP.put("7 gate scanner",        "SCANNER_7GATE");
        NAME_MAP.put("7gate",                 "SCANNER_7GATE");
        NAME_MAP.put("scanner_7gate",         "SCANNER_7GATE");
        NAME_MAP.put("scanner 7gate",         "SCANNER_7GATE");
        NAME_MAP.put("seven gate",            "SCANNER_7GATE");
        NAME_MAP.put("sevengate",             "SCANNER_7GATE");
        NAME_MAP.put("bb compression",        "SCANNER_7GATE");
    }

    // ── POST /api/backtest/strategy ────────────────────────────────────────────

    @PostMapping("/strategy")
    public ResponseEntity<Map<String, Object>> submitBacktest(
            @RequestBody Map<String, Object> req) {

        LocalDate startDate = LocalDate.parse(
                (String) req.getOrDefault("startDate", LocalDate.now().minusYears(1).toString()));
        LocalDate endDate = LocalDate.parse(
                (String) req.getOrDefault("endDate", LocalDate.now().toString()));
        BigDecimal capital = new BigDecimal(req.getOrDefault("capital", 100000).toString());

        @SuppressWarnings("unchecked")
        List<String> stratReq = (List<String>) req.getOrDefault("strategies", List.of("ALL"));

        List<String> strategies = resolveStrategies(stratReq);

        if (strategies.isEmpty()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "No valid strategies found. Valid keys: " + ALL_STRATEGIES);
            return ResponseEntity.badRequest().body(err);
        }

        if (startDate.isAfter(endDate)) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "startDate must be before endDate");
            return ResponseEntity.badRequest().body(err);
        }

        try {
            BacktestJob job = jobService.submit(startDate, endDate, capital, strategies);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("jobId",   job.getJobId());
            resp.put("status",  job.getStatus().name());
            resp.put("message", "Backtest started. Poll /api/backtest/status/" + job.getJobId());
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("startDate",           startDate.toString());
            params.put("endDate",             endDate.toString());
            params.put("capital",             capital);
            params.put("strategiesRequested", stratReq);
            params.put("strategiesResolved",  strategies);
            resp.put("params", params);
            return ResponseEntity.ok(resp);

        } catch (IllegalStateException e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", e.getMessage());
            return ResponseEntity.status(429).body(err);
        }
    }

    // ── GET /api/backtest/status/{jobId} ──────────────────────────────────────

    @GetMapping("/status/{jobId}")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable String jobId) {
        Optional<BacktestJob> opt = jobService.getJob(jobId);
        if (opt.isEmpty()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "Job not found: " + jobId);
            return ResponseEntity.status(404).body(err);
        }

        BacktestJob job = opt.get();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("jobId",          job.getJobId());
        resp.put("status",         job.getStatus().name());
        resp.put("progressPct",    job.progressPct());
        resp.put("processedCount", job.getProcessedSymbols().get());
        resp.put("totalCount",     job.getTotalSymbols());
        resp.put("currentSymbol",  job.getCurrentSymbol());
        resp.put("elapsedSec",     job.elapsedSeconds());
        long eta = job.etaSeconds();
        resp.put("etaSec",         eta);
        resp.put("etaMinutes",     eta > 0 ? eta / 60 : -1);
        resp.put("strategies",     job.getStrategies());
        if (job.getStatus() == BacktestJob.Status.FAILED)
            resp.put("error", job.getError());
        if (job.getStatus() == BacktestJob.Status.DONE)
            resp.put("message", "Done! Fetch result at /api/backtest/result/" + jobId);
        return ResponseEntity.ok(resp);
    }

    // ── GET /api/backtest/result/{jobId} ──────────────────────────────────────

    @GetMapping("/result/{jobId}")
    public ResponseEntity<Map<String, Object>> getResult(@PathVariable String jobId) {
        Optional<BacktestJob> opt = jobService.getJob(jobId);
        if (opt.isEmpty()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "Job not found: " + jobId);
            return ResponseEntity.status(404).body(err);
        }

        BacktestJob job = opt.get();

        if (job.getStatus() == BacktestJob.Status.RUNNING
                || job.getStatus() == BacktestJob.Status.QUEUED) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("jobId",       job.getJobId());
            resp.put("status",      job.getStatus().name());
            resp.put("progressPct", job.progressPct());
            resp.put("message",     "Still running. Check /api/backtest/status/" + jobId);
            return ResponseEntity.ok(resp);
        }

        if (job.getStatus() == BacktestJob.Status.FAILED) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("jobId",  job.getJobId());
            resp.put("status", "FAILED");
            resp.put("error",  job.getError());
            return ResponseEntity.ok(resp);
        }

        return ResponseEntity.ok(buildResultResponse(job));
    }

    // ── GET /api/backtest/jobs ─────────────────────────────────────────────────

    @GetMapping("/jobs")
    public ResponseEntity<List<Map<String, Object>>> listJobs() {
        List<Map<String, Object>> list = jobService.getAllJobs().stream()
                .sorted(Comparator.comparing(BacktestJob::getCreatedAt))
                .map(job -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("jobId",       job.getJobId());
                    m.put("status",      job.getStatus().name());
                    m.put("progressPct", job.progressPct());
                    m.put("startDate",   job.getStartDate().toString());
                    m.put("endDate",     job.getEndDate().toString());
                    m.put("strategies",  job.getStrategies());
                    m.put("createdAt",   job.getCreatedAt().toString());
                    m.put("elapsedSec",  job.elapsedSeconds());
                    return m;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(list);
    }

    // ── GET /api/backtest/strategies ──────────────────────────────────────────

    @GetMapping("/strategies")
    public ResponseEntity<Map<String, Object>> listStrategies() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("validKeys",   ALL_STRATEGIES);
        resp.put("allKeyword",  "ALL");
        resp.put("nameAliases", NAME_MAP);
        resp.put("description", "Use any key from validKeys, or 'ALL' for all strategies. "
                + "Display names (e.g. 'VAP Pullback', 'Simple ORB') are also accepted.");
        return ResponseEntity.ok(resp);
    }

    // ── Strategy resolution ────────────────────────────────────────────────────

    private List<String> resolveStrategies(List<String> requested) {
        if (requested == null || requested.isEmpty()) return new ArrayList<>(ALL_STRATEGIES);
        if (requested.stream().anyMatch(s -> "ALL".equalsIgnoreCase(s.trim())))
            return new ArrayList<>(ALL_STRATEGIES);

        List<String> resolved = new ArrayList<>();
        for (String raw : requested) {
            String key = normalizeStrategy(raw);
            if (key != null) {
                if (!resolved.contains(key)) resolved.add(key);
            } else {
                log.warn("[BT] Unknown strategy name '{}' — skipped. Valid: {}", raw, ALL_STRATEGIES);
            }
        }
        return resolved;
    }

    private String normalizeStrategy(String raw) {
        if (raw == null || raw.isBlank()) return null;

        String upper = raw.trim().toUpperCase().replace("-", "_");
        for (String valid : ALL_STRATEGIES) {
            if (valid.equals(upper)) return valid;
        }

        String lower           = raw.trim().toLowerCase().replace("-", " ").replace("_", " ");
        String lowerUnderscore = raw.trim().toLowerCase();

        String mapped = NAME_MAP.get(lower);
        if (mapped == null) mapped = NAME_MAP.get(lowerUnderscore);
        if (mapped != null) return mapped;

        for (Map.Entry<String, String> e : NAME_MAP.entrySet()) {
            if (lower.contains(e.getKey()) || e.getKey().contains(lower))
                return e.getValue();
        }
        return null;
    }

    // ── Result builder ─────────────────────────────────────────────────────────

    private Map<String, Object> buildResultResponse(BacktestJob job) {
        StrategyBacktestEngine.StrategyBacktestResult r = job.getResult();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("jobId",         job.getJobId());
        resp.put("status",        "DONE");
        resp.put("elapsedSec",    job.elapsedSeconds());
        resp.put("period",        job.getStartDate() + " to " + job.getEndDate());
        resp.put("capital",       job.getCapital());
        resp.put("strategiesRun", job.getStrategies());

        resp.put("perStockAggregate", buildAggregate(r));

        // FIX 3: Portfolio result
        if (r.portfolio() != null) {
            Map<String, Object> port = new LinkedHashMap<>();
            StrategyBacktestEngine.PortfolioResult p = r.portfolio();
            port.put("note",            "Single ₹1L account, max 2 trades/day, best quality signals, compound");
            port.put("totalTrades",     p.totalTrades());
            port.put("wins",            p.wins());
            port.put("losses",          p.losses());
            port.put("winRate",         String.format("%.1f%%", p.winRate() * 100));
            port.put("profitFactor",    String.format("%.2f",   p.profitFactor()));
            port.put("netPnl",          String.format("%.2f",   p.netPnl()));
            port.put("returnPct",       String.format("%.1f%%", p.returnPct()));
            port.put("annualReturnPct", String.format("%.1f%%", p.annualReturnPct()));
            port.put("maxDrawdownPct",  String.format("%.1f%%", p.maxDrawdownPct()));
            port.put("sharpeRatio",     String.format("%.2f",   p.sharpeRatio()));
            port.put("finalCapital",    String.format("%.2f",   p.finalCapital()));
            resp.put("portfolioResult", port);
        }

        resp.put("perStrategySummary", r.perStrategySummary());

        List<Map<String, Object>> stockRows = r.stockResults().stream().map(sr -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("symbol",       sr.symbol());
            row.put("sector",       sr.sector());
            row.put("totalTrades",  sr.totalTrades());
            row.put("wins",         sr.wins());
            row.put("losses",       sr.losses());
            row.put("winRate",      String.format("%.1f%%", sr.winRate() * 100));
            row.put("totalPnl",     String.format("%.2f",   sr.totalPnl()));
            row.put("avgWin",       String.format("%.2f",   sr.avgWin()));
            row.put("avgLoss",      String.format("%.2f",   sr.avgLoss()));
            row.put("profitFactor", String.format("%.2f",   sr.profitFactor()));
            row.put("bestTrade",    String.format("%.2f",   sr.bestTrade()));
            row.put("worstTrade",   String.format("%.2f",   sr.worstTrade()));
            row.put("byStrategy",   sr.byStrategy());
            return row;
        }).collect(Collectors.toList());

        resp.put("stockResults",   stockRows);
        resp.put("top10Stocks",    r.topStocks(10));
        resp.put("bottom10Stocks", r.bottomStocks(10));
        return resp;
    }

    private Map<String, Object> buildAggregate(StrategyBacktestEngine.StrategyBacktestResult r) {
        Map<String, Object> agg = new LinkedHashMap<>();
        agg.put("note",           "Sum of independent ₹1L experiments — NOT a single portfolio");
        agg.put("totalSymbols",   r.totalSymbols());
        agg.put("totalTrades",    r.totalTrades());
        agg.put("totalWins",      r.totalWins());
        agg.put("totalLosses",    r.totalLosses());
        agg.put("overallWinRate", String.format("%.1f%%", r.overallWinRate() * 100));
        agg.put("overallPnl",     String.format("%.2f",   r.overallPnl()));
        agg.put("overallPnlPct",  String.format("%.2f%%", r.overallPnlPct()));
        agg.put("profitFactor",   String.format("%.2f",   r.profitFactor()));
        return agg;
    }
}