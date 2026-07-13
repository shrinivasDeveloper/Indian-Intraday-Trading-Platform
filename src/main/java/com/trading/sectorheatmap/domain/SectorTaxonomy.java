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
            // FIX (per explicit user report: Retailing is the one
            // remaining sector showing "No live data", after all other
            // 21 sectors were confirmed fixed). Unlike those 21, I don't
            // have a confirmed real raw string specifically for pure
            // retail companies (DMART/TRENT/VMART) - real research found
            // conflicting signals: one source shows DMart's own
            // classification hierarchy as "Consumer Services > Retailing
            // > Diversified Retail," suggesting genuine retail companies
            // may often be tagged at the "Consumer Services" level in
            // NSE's actual data, with "Retailing" itself being rarer or
            // more specific than expected. Added the most plausible,
            // safe variant aliases below rather than guess a single one
            // with false confidence - the diagnostic logging already
            // built into refreshMapping() will show the definitive,
            // real answer on the next deployment if any of these still
            // don't match.
            Map.entry("retail", "Retailing"),
            Map.entry("retailers", "Retailing"),
            Map.entry("trading", "Retailing"),
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
            Map.entry("diversified", "Diversified"),

            // ============================================================
            // MAJOR FIX (per explicit user request for a complete,
            // permanent fix, backed by real evidence): NSE's live CSV
            // frequently uses the SECTOR NAME ITSELF directly as the
            // Industry value for many stocks - confirmed from a real,
            // current data source (containing 2025/2026-era companies
            // like "PG Electroplast", "Alivus Life Sciences"). The
            // taxonomy above only ever had granular SUB-industry names
            // mapped (e.g. "banks" -> Financial Services) - it never had
            // a SELF-MAPPING for when the raw value already equals the
            // sector name directly. This was the dominant, systematic
            // cause of the 60% "Diversified" miscategorization - not a
            // few missing entries, but this entire class of case.
            // (Note: several sector self-mappings - power, services,
            // telecommunication, construction, healthcare, realty,
            // information technology, consumer durables, fast moving
            // consumer goods, retailing - were ALREADY present in the
            // original taxonomy above and are correctly NOT repeated
            // here, to avoid a duplicate-key error.)
            // ============================================================
            Map.entry("automobile and auto components", "Automobile and Auto Components"),
            Map.entry("capital goods", "Capital Goods"),
            Map.entry("financial services", "Financial Services"),
            Map.entry("construction materials", "Construction Materials"),
            Map.entry("chemicals", "Chemicals"),
            Map.entry("consumer services", "Consumer Services"),
            Map.entry("textiles", "Textiles"),
            Map.entry("metals & mining", "Metals & Mining"),
            Map.entry("forest materials", "Forest Materials"),
            Map.entry("media, entertainment & publication", "Media, Entertainment & Publication"),
            // "Oil Gas & Consumable Fuels" - confirmed real variant
            // without the comma after "Oil", alongside the with-comma
            // version already mapped above - both accepted.
            Map.entry("oil gas & consumable fuels", "Oil, Gas & Consumable Fuels"),
            // "Media Entertainment & Publication" - confirmed real
            // variant without the comma after "Media".
            Map.entry("media entertainment & publication", "Media, Entertainment & Publication"),

            // ============================================================
            // ADDITIONAL FIX: an OLDER, alternate GICS-style vocabulary
            // (ALL-CAPS, simpler category names) confirmed present in a
            // separate, real historical NSE data snapshot. Adding these
            // as aliases too, for maximum robustness regardless of which
            // exact format the live endpoint returns on any given day.
            // ============================================================
            Map.entry("it", "Information Technology"),
            Map.entry("metals", "Metals & Mining"),
            Map.entry("pharma", "Healthcare"),
            Map.entry("telecom", "Telecommunication"),
            Map.entry("automobile", "Automobile and Auto Components"),
            Map.entry("media & entertainment", "Media, Entertainment & Publication"),
            Map.entry("energy", "Power"),
            Map.entry("consumer goods", "Consumer Durables"),
            Map.entry("fertilisers & pesticides", "Chemicals") // British spelling variant
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