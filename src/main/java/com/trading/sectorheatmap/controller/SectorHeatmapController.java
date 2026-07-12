package com.trading.sectorheatmap.controller;

import com.trading.sectorheatmap.domain.SectorTaxonomy;
import com.trading.sectorheatmap.service.SectorHeatmapDataService;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * SectorHeatmapController - a completely separate API surface
 * (/api/sector-heatmap/**), independent of every existing strategy's
 * own controller. No shared routes, no shared request/response types.
 */
@RestController
@RequestMapping("/api/sector-heatmap")
public class SectorHeatmapController {

    private final SectorHeatmapDataService dataService;

    public SectorHeatmapController(SectorHeatmapDataService dataService) {
        this.dataService = dataService;
    }

    /** All 22 sectors with their current average % change - the main
     *  heatmap grid view. */
    @GetMapping
    public Map<String, Object> getHeatmap() {
        Map<String, SectorHeatmapDataService.SectorChange> sectorChanges = dataService.getSectorAverageChange();
        List<Map<String, Object>> sectors = new ArrayList<>();
        for (String sector : SectorTaxonomy.ALL_22_SECTORS) {
            var change = sectorChanges.get(sector);
            sectors.add(Map.of(
                    "sector", sector,
                    "changePct", change != null ? change.changePct() : 0.0,
                    "stockCount", change != null ? change.stockCount() : 0,
                    "hasData", change != null && change.stockCount() > 0
            ));
        }
        return Map.of(
                "sectors", sectors,
                "totalMappedStocks", dataService.getMappedStockCount(),
                "totalLivePrices", dataService.getLivePriceCount()
        );
    }

    /**
     * Per explicit requirement: "When a user clicks on any sector,
     * display all the stocks belonging to that sector... sorted by
     * percentage change in descending order... users should also be
     * able to switch to ascending order."
     */
    @GetMapping("/{sector}")
    public Map<String, Object> getSectorStocks(
            @PathVariable String sector,
            @RequestParam(defaultValue = "desc") String order) {
        boolean ascending = "asc".equalsIgnoreCase(order);
        var stocks = dataService.getStocksInSector(sector, ascending);
        return Map.of(
                "sector", sector,
                "order", ascending ? "asc" : "desc",
                "count", stocks.size(),
                "stocks", stocks
        );
    }
}