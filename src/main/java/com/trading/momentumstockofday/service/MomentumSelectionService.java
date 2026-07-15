package com.trading.momentumstockofday.service;

import com.trading.momentumstockofday.config.MomentumConfig;
import com.trading.momentumstockofday.domain.MomentumCandidate;
import com.trading.sectorheatmap.service.SectorHeatmapDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * MomentumSelectionService - implements the exact selection sequence
 * from the spec:
 *   "At 9:25 AM, identify and rank all sectors based on their
 *   percentage change... Select the top three performing sectors...
 *   From each of these sectors, select the top three stocks based on
 *   percentage gain."
 *
 * INDEPENDENCE (per explicit requirement): the ONLY external data
 * dependency here is SectorHeatmapDataService - itself a separate,
 * independent module, not a strategy. Zero imports from AI, News,
 * Swing, or Hero-or-Zero.
 */
@Service
@Slf4j
public class MomentumSelectionService {

    private final SectorHeatmapDataService heatmapService;
    private final MomentumConfig config;

    public MomentumSelectionService(SectorHeatmapDataService heatmapService, MomentumConfig config) {
        this.heatmapService = heatmapService;
        this.config = config;
    }

    /**
     * Runs once, at 9:25 AM. Returns the ranked list of (at most)
     * topSectorsCount x topStocksPerSector candidates, in STRICT
     * priority order - sector-1's stocks first, then sector-2's, then
     * sector-3's - per spec: "The strategy should always follow this
     * priority order and never skip ahead."
     */
    public List<MomentumCandidate> selectCandidates() {
        // Step 1: rank all 22 sectors by % change, per spec exactly.
        // FIX (compile error found by user): getSectorAverageChange()
        // now returns SectorChange (changePct + stockCount) instead of
        // a bare Double, from an earlier fix to the Sector Heatmap
        // module that added "no data vs genuinely flat" tracking - this
        // consumer needed updating to match. Zero change to the actual
        // ranking/selection logic itself, purely adapting to the new
        // return type.
        Map<String, SectorHeatmapDataService.SectorChange> sectorChanges =
                heatmapService.getSectorAverageChange();
        List<Map.Entry<String, SectorHeatmapDataService.SectorChange>> rankedSectors =
                sectorChanges.entrySet().stream()
                        // FIX: exclude sectors with zero live stocks entirely -
                        // their 0.0% is a fallback default, not a genuine
                        // reading, and could otherwise falsely outrank a
                        // genuinely negative sector with real data.
                        .filter(e -> e.getValue().stockCount() > 0)
                        // FIX (per explicit user request: "add a validation
                        // gate to check the number of stocks in each
                        // sector. Only sectors that have more than 10
                        // stocks should be considered for scanning.
                        // Sectors with 10 or fewer stocks should be
                        // skipped"). Added as a SEPARATE, additional
                        // filter - the existing stockCount() > 0 filter
                        // above is completely untouched, per explicit
                        // instruction not to modify existing logic.
                        .filter(e -> e.getValue().stockCount() > 10)
                        // FIX (found via direct user report, confirmed
                        // precisely against real live data): ranking by
                        // RAW value always favored any positive sector
                        // over negative ones, regardless of magnitude -
                        // e.g. Healthcare at +0.31% ranked above Realty
                        // at -1.11%, even though Realty's move was more
                        // than 3x stronger. Per the original spec
                        // ("select top 3 sectors if green or red... look
                        // for short and long trade opportunities"), the
                        // top 3 must be the STRONGEST movers in EITHER
                        // direction. Now ranks by absolute magnitude -
                        // direction determination (LONG for positive,
                        // SHORT for negative, done later via
                        // sectorChangePct >= 0) is completely unchanged.
                        .sorted((a, b) -> Double.compare(
                                Math.abs(b.getValue().changePct()), Math.abs(a.getValue().changePct())))
                        .toList();

        if (rankedSectors.isEmpty()) {
            log.warn("[MOMENTUM-SELECT] No sector data available from heatmap - cannot select " +
                    "candidates today");
            return List.of();
        }

        // Step 2: top 3 performing sectors.
        List<Map.Entry<String, SectorHeatmapDataService.SectorChange>> topSectors = rankedSectors.stream()
                .limit(config.getTopSectorsCount())
                .toList();

        log.info("[MOMENTUM-SELECT] Top {} sectors today: {}", topSectors.size(),
                topSectors.stream()
                        .map(e -> String.format("%s (%.2f%%, %d stocks)", e.getKey(),
                                e.getValue().changePct(), e.getValue().stockCount()))
                        .toList());

        List<MomentumCandidate> candidates = new ArrayList<>();
        int sectorRank = 1;
        for (var sectorEntry : topSectors) {
            String sector = sectorEntry.getKey();
            double sectorChangePct = sectorEntry.getValue().changePct();

            // Per spec: "Trade only in the direction of the sector's
            // trend. Never take a trade against the sector direction."
            String direction = sectorChangePct >= 0 ? "LONG" : "SHORT";

            // Step 3: top 3 stocks in this sector by % gain. Note:
            // "gain" per spec means strongest move IN the sector's own
            // trend direction - for a LONG sector, that's highest %
            // change; for a SHORT sector, that's the most NEGATIVE %
            // change (the strongest movers in the sector's actual
            // direction, not just "positive movers").
            boolean sectorIsLong = direction.equals("LONG");
            var stocksInSector = heatmapService.getStocksInSector(sector, !sectorIsLong);
            // getStocksInSector(sector, ascending) - descending (ascending=false)
            // gives highest-change-first, which is correct for a LONG
            // sector's "top gainers." For a SHORT sector, ascending=true
            // gives most-negative-first, correctly representing the
            // strongest movers in THAT sector's own (downward) direction.

            List<MomentumCandidate> sectorPicks = new ArrayList<>();
            var limited = stocksInSector.stream().limit(config.getTopStocksPerSector()).toList();
            for (int stockRank = 1; stockRank <= limited.size(); stockRank++) {
                var s = limited.get(stockRank - 1);
                sectorPicks.add(new MomentumCandidate(s.symbol(), s.companyName(), sector,
                        sectorRank, stockRank, direction, s.ltp()));
            }

            candidates.addAll(sectorPicks);
            log.info("[MOMENTUM-SELECT] Sector #{} '{}' ({}%, direction={}) - top {} stocks: {}",
                    sectorRank, sector, String.format("%.2f", sectorChangePct), direction,
                    sectorPicks.size(),
                    sectorPicks.stream().map(MomentumCandidate::getSymbol).toList());

            sectorRank++;
        }

        log.info("[MOMENTUM-SELECT] Total candidates selected: {} (max possible: {})",
                candidates.size(), config.getTopSectorsCount() * config.getTopStocksPerSector());
        return candidates;
    }
}