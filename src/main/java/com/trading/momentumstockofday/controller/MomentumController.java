package com.trading.momentumstockofday.controller;

import com.trading.momentumstockofday.domain.MomentumCandidate;
import com.trading.momentumstockofday.repository.MomentumCapitalRepository;
import com.trading.momentumstockofday.repository.MomentumTradeRepository;
import com.trading.momentumstockofday.scheduler.MomentumScheduler;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * MomentumController - a completely separate API surface
 * (/api/momentum-stock-of-day/**), independent of every existing
 * strategy's own controller.
 */
@RestController
@RequestMapping("/api/momentum-stock-of-day")
public class MomentumController {

    private final MomentumScheduler scheduler;
    private final MomentumTradeRepository repository;
    private final MomentumCapitalRepository capitalRepository;

    public MomentumController(MomentumScheduler scheduler, MomentumTradeRepository repository,
                              MomentumCapitalRepository capitalRepository) {
        this.scheduler = scheduler;
        this.repository = repository;
        this.capitalRepository = capitalRepository;
    }

    /** Per explicit user request: capital allocated from the UI. */
    @GetMapping("/capital")
    public Map<String, Object> getCapital() {
        return Map.of("capital", capitalRepository.getCapital());
    }

    @PostMapping("/capital")
    public Map<String, Object> setCapital(@RequestBody Map<String, Object> body) {
        BigDecimal newCapital = new BigDecimal(String.valueOf(body.get("capital")));
        if (newCapital.compareTo(BigDecimal.ZERO) <= 0) {
            return Map.of("error", "Capital must be greater than zero");
        }
        capitalRepository.setCapital(newCapital);
        return Map.of("capital", newCapital, "message", "Capital updated successfully");
    }

    @GetMapping("/candidates")
    public Map<String, Object> getTodaysCandidates() {
        List<MomentumCandidate> candidates = scheduler.getTodaysCandidates();
        return Map.of(
                "count", candidates.size(),
                "candidates", candidates.stream().map(c -> Map.ofEntries(
                        Map.entry("symbol", c.getSymbol()),
                        Map.entry("companyName", c.getCompanyName()),
                        Map.entry("sector", c.getSector()),
                        Map.entry("sectorRank", c.getSectorRank()),
                        Map.entry("stockRank", c.getStockRank()),
                        Map.entry("direction", c.getDirection()),
                        Map.entry("selectionPrice", c.getSelectionPrice()),
                        Map.entry("validConsolidation", c.isValidConsolidation()),
                        Map.entry("consolidationHigh", c.getConsolidationHigh()),
                        Map.entry("consolidationLow", c.getConsolidationLow()),
                        Map.entry("lastEvaluationNote", c.getLastEvaluationNote())
                )).toList()
        );
    }

    @GetMapping("/trades/today")
    public List<Map<String, Object>> getTodaysTrades() {
        return repository.findToday().stream().map(t -> Map.<String, Object>ofEntries(
                Map.entry("symbol", t.getSymbol()),
                Map.entry("sector", t.getSector()),
                Map.entry("sectorRank", t.getSectorRank()),
                Map.entry("direction", t.getDirection()),
                Map.entry("entryPrice", t.getEntryPrice()),
                Map.entry("stopLoss", t.getStopLoss()),
                Map.entry("target", t.getTarget()),
                Map.entry("quantity", t.getQuantity()),
                Map.entry("status", t.getStatus()),
                Map.entry("exitPrice", t.getExitPrice() != null ? t.getExitPrice() : ""),
                Map.entry("exitReason", t.getExitReason() != null ? t.getExitReason() : ""),
                Map.entry("trailingActive", t.isTrailingActive()),
                Map.entry("currentTrailStop", t.getCurrentTrailStop() != null ? t.getCurrentTrailStop() : "")
        )).toList();
    }
}