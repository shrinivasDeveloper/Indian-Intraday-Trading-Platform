package com.trading.sectorheatmap.domain;

import java.util.Map;
import java.util.Set;

/**
 * SectorTaxonomy - the official NSE Indices Ltd. Industry-to-Sector
 * mapping, fetched and verified directly from NSE Indices Limited's own
 * published "Industry Classification Guideline, July 2023"
 * (niftyindices.com/docs/.../nse-indices_industry-classification-
 * guideline-2023-07.pdf).
 *
 * INDEPENDENCE: this entire module (sectorheatmap.*) is a completely
 * separate, standalone package - zero imports from AI, News, Swing, or
 * Hero-or-Zero. It uses only genuinely shared, neutral infrastructure
 * (the KiteConnect bean, JdbcTemplate) - never any strategy-specific
 * class. Fully removable without affecting any existing trading
 * pipeline.
 *
 * This is NSE's real 4-tier classification, narrowed to the 2 tiers
 * this module actually needs: Industry -> Sector (the middle two of
 * NSE's Macro-Economic Sector -> Sector -> Industry -> Basic Industry
 * hierarchy). There are exactly 22 official Sectors - this map's
 * values are guaranteed to only ever be one of those 22 names.
 *
 * STABLE BY DESIGN: this is NSE's own taxonomy DEFINITION (which
 * Industry belongs to which Sector) - not per-stock data. This
 * genuinely is fixed, official reference data that only changes when
 * NSE itself revises its classification structure (confirmed: last
 * revision July 2023, reviewed annually per NSE's own stated policy) -
 * appropriate to hardcode, unlike per-stock sector ASSIGNMENTS (which
 * this module fetches dynamically and persists - see
 * SectorHeatmapDataService).
 */
public final class SectorTaxonomy {

    private SectorTaxonomy() {}

    /** The exact 22 official NSE Sectors - nothing else is ever valid. */
    public static final Set<String> ALL_22_SECTORS = Set.of(
            "Chemicals", "Construction Materials", "Metals & Mining",
            "Forest Materials", "Automobile and Auto Components",
            "Consumer Durables", "Textiles", "Media, Entertainment & Publication",
            "Realty", "Consumer Services", "Retailing",
            "Oil, Gas & Consumable Fuels", "Fast Moving Consumer Goods",
            "Financial Services", "Healthcare", "Construction",
            "Capital Goods", "Information Technology", "Services",
            "Telecommunication", "Power", "Diversified"
    );

    /** Industry name (as it appears in NSE's own stock-level data feed,
     *  lowercased for case-insensitive lookup) -> parent Sector. */
    public static final Map<String, String> INDUSTRY_TO_SECTOR = Map.ofEntries(
            // Chemicals
            Map.entry("chemicals & petrochemicals", "Chemicals"),
            Map.entry("fertilizers & agrochemicals", "Chemicals"),
            // Construction Materials
            Map.entry("cement & cement products", "Construction Materials"),
            Map.entry("other construction materials", "Construction Materials"),
            // Metals & Mining
            Map.entry("ferrous metals", "Metals & Mining"),
            Map.entry("non - ferrous metals", "Metals & Mining"),
            Map.entry("non-ferrous metals", "Metals & Mining"),
            Map.entry("diversified metals", "Metals & Mining"),
            Map.entry("minerals & mining", "Metals & Mining"),
            Map.entry("metals & minerals trading", "Metals & Mining"),
            // Forest Materials
            Map.entry("paper, forest & jute products", "Forest Materials"),
            // Automobile and Auto Components
            Map.entry("automobiles", "Automobile and Auto Components"),
            Map.entry("auto components", "Automobile and Auto Components"),
            // Consumer Durables
            Map.entry("consumer durables", "Consumer Durables"),
            // Textiles
            Map.entry("textiles & apparels", "Textiles"),
            // Media, Entertainment & Publication
            Map.entry("media", "Media, Entertainment & Publication"),
            Map.entry("entertainment", "Media, Entertainment & Publication"),
            Map.entry("printing & publication", "Media, Entertainment & Publication"),
            // Realty
            Map.entry("realty", "Realty"),
            // Consumer Services
            Map.entry("leisure services", "Consumer Services"),
            Map.entry("other consumer services", "Consumer Services"),
            // Retailing
            Map.entry("retailing", "Retailing"),
            // Oil, Gas & Consumable Fuels
            Map.entry("gas", "Oil, Gas & Consumable Fuels"),
            Map.entry("oil", "Oil, Gas & Consumable Fuels"),
            Map.entry("petroleum products", "Oil, Gas & Consumable Fuels"),
            Map.entry("consumable fuels", "Oil, Gas & Consumable Fuels"),
            // Fast Moving Consumer Goods
            Map.entry("fast moving consumer goods", "Fast Moving Consumer Goods"),
            Map.entry("agricultural food & other products", "Fast Moving Consumer Goods"),
            Map.entry("beverages", "Fast Moving Consumer Goods"),
            Map.entry("cigarettes & tobacco products", "Fast Moving Consumer Goods"),
            Map.entry("food products", "Fast Moving Consumer Goods"),
            Map.entry("personal products", "Fast Moving Consumer Goods"),
            Map.entry("household products", "Fast Moving Consumer Goods"),
            Map.entry("diversified fmcg", "Fast Moving Consumer Goods"),
            // Financial Services
            Map.entry("finance", "Financial Services"),
            Map.entry("banks", "Financial Services"),
            Map.entry("capital markets", "Financial Services"),
            Map.entry("insurance", "Financial Services"),
            Map.entry("financial technology (fintech)", "Financial Services"),
            // Healthcare
            Map.entry("healthcare", "Healthcare"),
            Map.entry("pharmaceuticals & biotechnology", "Healthcare"),
            Map.entry("healthcare equipment & supplies", "Healthcare"),
            Map.entry("healthcare services", "Healthcare"),
            // Construction
            Map.entry("construction", "Construction"),
            // Capital Goods
            Map.entry("aerospace & defense", "Capital Goods"),
            Map.entry("agricultural, commercial & construction vehicles", "Capital Goods"),
            Map.entry("electrical equipment", "Capital Goods"),
            Map.entry("industrial manufacturing", "Capital Goods"),
            Map.entry("industrial products", "Capital Goods"),
            // Information Technology
            Map.entry("information technology", "Information Technology"),
            Map.entry("it - software", "Information Technology"),
            Map.entry("it - services", "Information Technology"),
            Map.entry("it - hardware", "Information Technology"),
            // Services
            Map.entry("services", "Services"),
            Map.entry("engineering services", "Services"),
            Map.entry("transport services", "Services"),
            Map.entry("transport infrastructure", "Services"),
            Map.entry("commercial services & supplies", "Services"),
            Map.entry("public services", "Services"),
            // Telecommunication
            Map.entry("telecommunication", "Telecommunication"),
            Map.entry("telecom - services", "Telecommunication"),
            Map.entry("telecom - equipment & accessories", "Telecommunication"),
            // Power
            Map.entry("power", "Power"),
            // Utilities (NSE's own tier - "Other Utilities" maps to Power
            // per its actual placement in the guideline's Utilities block)
            Map.entry("utilities", "Power"),
            Map.entry("other utilities", "Power"),
            // Diversified
            Map.entry("diversified", "Diversified")
    );

    // FIX (found via direct user report: 448 of 743 live stocks - 60%!
    // - were landing in "Diversified", far more than a genuine
    // catch-all sector should ever hold). Tracks every unrecognized
    // raw industry string and how many stocks hit it, so the actual,
    // real values from NSE's live data can be seen and mapped
    // correctly - rather than guessing at fixes without real evidence.
    private static final java.util.Map<String, Integer> unmatchedIndustryCounts =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Case-insensitive, whitespace-tolerant lookup. Returns "Diversified"
     *  (NSE's own genuine catch-all sector) if the industry name isn't
     *  recognized - never returns null, never silently drops a stock. */
    public static String sectorFor(String rawIndustry) {
        if (rawIndustry == null || rawIndustry.isBlank()) return "Diversified";
        String key = rawIndustry.trim().toLowerCase();
        String result = INDUSTRY_TO_SECTOR.get(key);
        if (result == null) {
            // Track the exact, real, unrecognized value - trimmed but
            // NOT lowercased in the log, so the real casing/spacing from
            // NSE's actual data is visible for fixing the taxonomy.
            unmatchedIndustryCounts.merge(rawIndustry.trim(), 1, Integer::sum);
            return "Diversified";
        }
        return result;
    }

    /** Called once per refresh cycle (see SectorHeatmapDataService) to
     *  log a real, evidence-based summary of exactly which industry
     *  strings are falling through, and how often - the actual data
     *  needed to fix the taxonomy correctly instead of guessing. */
    public static java.util.Map<String, Integer> getAndClearUnmatchedIndustries() {
        java.util.Map<String, Integer> snapshot = new java.util.LinkedHashMap<>(unmatchedIndustryCounts);
        unmatchedIndustryCounts.clear();
        return snapshot;
    }
}