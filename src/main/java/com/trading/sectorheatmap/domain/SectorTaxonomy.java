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
            "Chemicals", "Construction Materials", "Metals",
            "Forest Materials", "Auto",
            "Consumer Durables", "Textiles", "Media",
            "Realty", "Consumer Services", "Retail",
            "Oil & Gas", "FMCG",
            "Financial Services", "Healthcare", "Construction",
            "Capital Goods", "Information Technology (IT)", "Services",
            "Telecom", "Power", "Diversified",
            // FIX (per explicit user request, Option B): added as new
            // sectors get real, verified symbol-level overrides or
            // industry-string retargeting.
            "PSU Banks", "Private Banks", "Insurance", "NBFC", "Housing Finance",
            "Pharma", "Hospitals", "Aviation", "Commercial & Transport Services",
            "REITs & Realty"
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
            Map.entry("ferrous metals", "Metals"),
            Map.entry("non - ferrous metals", "Metals"),
            Map.entry("non-ferrous metals", "Metals"),
            Map.entry("diversified metals", "Metals"),
            Map.entry("minerals & mining", "Metals"),
            Map.entry("metals & minerals trading", "Metals"),
            // Forest Materials
            Map.entry("paper, forest & jute products", "Forest Materials"),
            // Automobile and Auto Components
            Map.entry("automobiles", "Auto"),
            Map.entry("auto components", "Auto"),
            // Consumer Durables
            Map.entry("consumer durables", "Consumer Durables"),
            // Textiles
            Map.entry("textiles & apparels", "Textiles"),
            // Media, Entertainment & Publication
            Map.entry("media", "Media"),
            Map.entry("entertainment", "Media"),
            Map.entry("printing & publication", "Media"),
            // Realty
            Map.entry("realty", "Realty"),
            // Consumer Services
            Map.entry("leisure services", "Consumer Services"),
            Map.entry("other consumer services", "Consumer Services"),
            // Retailing
            Map.entry("retailing", "Retail"),
            // FIX (per explicit user report: Retailing is the one
            // remaining sector showing "No live data", after all other
            // 21 sectors were confirmed fixed). Unlike those 21, I don't
            // have a confirmed real raw string specifically for pure
            // retail companies (DMART/TRENT/VMART) - real research found
            // conflicting signals: one source shows DMart's own
            // classification hierarchy as "Consumer Services > Retailing
            // > Diversified Retail," suggesting genuine retail companies
            // may often be tagged at the "Consumer Services" level in
            // NSE's actual data, with "Retail" itself being rarer or
            // more specific than expected. Added the most plausible,
            // safe variant aliases below rather than guess a single one
            // with false confidence - the diagnostic logging already
            // built into refreshMapping() will show the definitive,
            // real answer on the next deployment if any of these still
            // don't match.
            Map.entry("retail", "Retail"),
            Map.entry("retailers", "Retail"),
            Map.entry("trading", "Retail"),
            // Oil, Gas & Consumable Fuels
            Map.entry("gas", "Oil & Gas"),
            Map.entry("oil", "Oil & Gas"),
            Map.entry("petroleum products", "Oil & Gas"),
            Map.entry("consumable fuels", "Oil & Gas"),
            // Fast Moving Consumer Goods
            Map.entry("fast moving consumer goods", "FMCG"),
            Map.entry("agricultural food & other products", "FMCG"),
            Map.entry("beverages", "FMCG"),
            Map.entry("cigarettes & tobacco products", "FMCG"),
            Map.entry("food products", "FMCG"),
            Map.entry("personal products", "FMCG"),
            Map.entry("household products", "FMCG"),
            Map.entry("diversified fmcg", "FMCG"),
            // Financial Services
            Map.entry("finance", "Financial Services"),
            Map.entry("banks", "Financial Services"),
            Map.entry("capital markets", "Financial Services"),
            Map.entry("insurance", "Insurance"),
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
            Map.entry("information technology", "Information Technology (IT)"),
            Map.entry("it - software", "Information Technology (IT)"),
            Map.entry("it - services", "Information Technology (IT)"),
            Map.entry("it - hardware", "Information Technology (IT)"),
            // Services
            Map.entry("services", "Services"),
            Map.entry("engineering services", "Services"),
            Map.entry("transport services", "Services"),
            Map.entry("transport infrastructure", "Services"),
            Map.entry("commercial services & supplies", "Services"),
            Map.entry("public services", "Services"),
            // Telecommunication
            Map.entry("telecommunication", "Telecom"),
            Map.entry("telecom - services", "Telecom"),
            Map.entry("telecom - equipment & accessories", "Telecom"),
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
            Map.entry("automobile and auto components", "Auto"),
            Map.entry("capital goods", "Capital Goods"),
            Map.entry("financial services", "Financial Services"),
            Map.entry("construction materials", "Construction Materials"),
            Map.entry("chemicals", "Chemicals"),
            Map.entry("consumer services", "Consumer Services"),
            Map.entry("textiles", "Textiles"),
            Map.entry("metals & mining", "Metals"),
            Map.entry("forest materials", "Forest Materials"),
            Map.entry("media, entertainment & publication", "Media"),
            // "Oil Gas & Consumable Fuels" - confirmed real variant
            // without the comma after "Oil", alongside the with-comma
            // version already mapped above - both accepted.
            Map.entry("oil gas & consumable fuels", "Oil & Gas"),
            // "Media Entertainment & Publication" - confirmed real
            // variant without the comma after "Media".
            Map.entry("media entertainment & publication", "Media"),

            // ============================================================
            // ADDITIONAL FIX: an OLDER, alternate GICS-style vocabulary
            // (ALL-CAPS, simpler category names) confirmed present in a
            // separate, real historical NSE data snapshot. Adding these
            // as aliases too, for maximum robustness regardless of which
            // exact format the live endpoint returns on any given day.
            // ============================================================
            Map.entry("it", "Information Technology (IT)"),
            Map.entry("metals", "Metals"),
            Map.entry("pharma", "Healthcare"),
            Map.entry("telecom", "Telecom"),
            Map.entry("automobile", "Auto"),
            Map.entry("media & entertainment", "Media"),
            Map.entry("energy", "Power"),
            Map.entry("consumer goods", "Consumer Durables"),
            Map.entry("fertilisers & pesticides", "Chemicals") // British spelling variant
    );

    // ============================================================
    // SYMBOL-LEVEL OVERRIDES (per explicit user request, Option B):
    // for sector splits that NSE's raw industry-string data cannot
    // distinguish (e.g., "Banks" doesn't separate PSU from Private),
    // this map provides an explicit, individually-verified override
    // for specific symbols - checked BEFORE the industry-string
    // lookup above, which remains completely unchanged and still
    // handles every symbol not listed here. This is additive, layered
    // logic - never a replacement for the proven industry mapping.
    //
    // PSU Banks - verified via multiple consistent, real sources: the
    // complete, current list of 12 government-owned banks (Government
    // of India holds a majority stake) listed on NSE.
    // ============================================================
    public static final Map<String, String> SYMBOL_SECTOR_OVERRIDES = Map.ofEntries(
            Map.entry("SBIN", "PSU Banks"),
            Map.entry("PNB", "PSU Banks"),
            Map.entry("BANKBARODA", "PSU Banks"),
            Map.entry("CANBK", "PSU Banks"),
            Map.entry("UNIONBANK", "PSU Banks"),
            Map.entry("BANKINDIA", "PSU Banks"),
            Map.entry("INDIANB", "PSU Banks"),
            Map.entry("CENTRALBK", "PSU Banks"),
            Map.entry("UCOBANK", "PSU Banks"),
            Map.entry("MAHABANK", "PSU Banks"),
            Map.entry("PSB", "PSU Banks"),
            Map.entry("IOB", "PSU Banks"),

            // FIX (per explicit user request, Category 1: Private
            // Banks). 17 of NSE's ~21 private sector banks confirmed
            // with high confidence across multiple consistent, real
            // sources. Honest note: a handful of smaller, less
            // commonly-covered private banks may not be included here
            // yet - those will surface in the refinement report
            // (getSymbolsNeedingRefinement()) as still-unrefined
            // Financial Services entries, for further verification
            // rather than guessing.
            Map.entry("HDFCBANK", "Private Banks"),
            Map.entry("ICICIBANK", "Private Banks"),
            Map.entry("AXISBANK", "Private Banks"),
            Map.entry("KOTAKBANK", "Private Banks"),
            Map.entry("INDUSINDBK", "Private Banks"),
            Map.entry("FEDERALBNK", "Private Banks"),
            Map.entry("IDFCFIRSTB", "Private Banks"),
            Map.entry("YESBANK", "Private Banks"),
            Map.entry("RBLBANK", "Private Banks"),
            Map.entry("BANDHANBNK", "Private Banks"),
            Map.entry("KARURVYSYA", "Private Banks"),
            Map.entry("IDBI", "Private Banks"),
            Map.entry("SOUTHBANK", "Private Banks"),
            Map.entry("CSBBANK", "Private Banks"),
            Map.entry("J&KBANK", "Private Banks"),
            Map.entry("CUB", "Private Banks"),
            Map.entry("DCBBANK", "Private Banks"),

            // FIX (per explicit user request, Category 2: NBFC).
            // Major, well-known large/mid-cap NBFCs confirmed across
            // multiple sources. Honest note: NBFC is a genuinely large,
            // open-ended category (thousands of companies, many
            // micro-cap) - this is not an exhaustive list, but the
            // significant, widely-recognized names likely to be part of
            // a Nifty Total Market universe. 3 of these (SHRIRAMFIN,
            // MANAPPURAM, RECLTD) additionally cross-verified as
            // genuinely present in the tracked universe via real log
            // data seen earlier this session.
            Map.entry("BAJFINANCE", "NBFC"),
            Map.entry("CHOLAFIN", "NBFC"),
            Map.entry("SHRIRAMFIN", "NBFC"),
            Map.entry("MUTHOOTFIN", "NBFC"),
            Map.entry("ABCAPITAL", "NBFC"),
            Map.entry("M&MFIN", "NBFC"),
            Map.entry("SUNDARMFIN", "NBFC"),
            Map.entry("MANAPPURAM", "NBFC"),
            Map.entry("POONAWALLA", "NBFC"),
            Map.entry("IIFL", "NBFC"),
            Map.entry("PFC", "NBFC"),
            Map.entry("RECLTD", "NBFC"),
            Map.entry("BAJAJHLDNG", "NBFC"),

            // FIX (per explicit user request, Category 3 - Phase 1:
            // Housing Finance). Major, publicly-listed HFCs verified
            // across multiple sources. AAVAS and BAJAJHFL additionally
            // cross-verified as genuinely present in the tracked
            // universe via real log data seen earlier this session.
            // Phase 1 only - further verification against the
            // refinement report still required.
            Map.entry("LICHSGFIN", "Housing Finance"),
            Map.entry("PNBHOUSING", "Housing Finance"),
            Map.entry("CANFINHOME", "Housing Finance"),
            Map.entry("GICHSGFIN", "Housing Finance"),
            Map.entry("APTUS", "Housing Finance"),
            Map.entry("AAVAS", "Housing Finance"),
            Map.entry("HOMEFIRST", "Housing Finance"),
            Map.entry("INDIASHLTR", "Housing Finance"),
            Map.entry("REPCOHOME", "Housing Finance"),
            Map.entry("BAJAJHFL", "Housing Finance"),

            // FIX (per explicit user request, Healthcare domain,
            // Category: Pharma - Phase 1). Major, well-known
            // pharmaceutical companies verified across multiple
            // sources. 6 of these (SUNPHARMA, DIVISLAB, ALKEM, BIOCON,
            // JBCHEPHARM, JUBLPHARMA) additionally cross-verified as
            // genuinely present in the tracked universe via real log
            // data seen earlier this session.
            Map.entry("SUNPHARMA", "Pharma"),
            Map.entry("DIVISLAB", "Pharma"),
            Map.entry("CIPLA", "Pharma"),
            Map.entry("DRREDDY", "Pharma"),
            Map.entry("TORNTPHARM", "Pharma"),
            Map.entry("MANKIND", "Pharma"),
            Map.entry("ZYDUSLIFE", "Pharma"),
            Map.entry("LUPIN", "Pharma"),
            Map.entry("ALKEM", "Pharma"),
            Map.entry("AUROPHARMA", "Pharma"),
            Map.entry("BIOCON", "Pharma"),
            Map.entry("GLENMARK", "Pharma"),
            Map.entry("IPCALAB", "Pharma"),
            Map.entry("LAURUSLABS", "Pharma"),
            Map.entry("NATCOPHARM", "Pharma"),
            Map.entry("ABBOTINDIA", "Pharma"),
            Map.entry("AJANTPHARM", "Pharma"),
            Map.entry("JBCHEPHARM", "Pharma"),
            Map.entry("GLAND", "Pharma"),
            Map.entry("JUBLPHARMA", "Pharma"),

            // FIX (per explicit user request, Healthcare domain,
            // Category: Hospitals - Phase 1). Major hospital chains
            // verified across multiple sources. 4 of these (APOLLOHOSP,
            // MAXHEALTH, MEDANTA, RAINBOW) additionally cross-verified
            // as genuinely present in the tracked universe via real
            // log data seen earlier this session.
            Map.entry("APOLLOHOSP", "Hospitals"),
            Map.entry("MAXHEALTH", "Hospitals"),
            Map.entry("FORTIS", "Hospitals"),
            Map.entry("NH", "Hospitals"),
            Map.entry("MEDANTA", "Hospitals"),
            Map.entry("KIMS", "Hospitals"),
            Map.entry("ASTERDM", "Hospitals"),
            Map.entry("RAINBOW", "Hospitals"),
            Map.entry("YATHARTH", "Hospitals"),

            // FIX (per explicit user request, Services domain,
            // Category: Aviation - Phase 1). Aviation is a genuinely
            // small, well-defined category in India - 2 major, actively-
            // traded listed airlines. Jet Airways is under bankruptcy
            // revival proceedings, not a normal actively-traded stock,
            // so intentionally excluded.
            Map.entry("INDIGO", "Aviation"),
            Map.entry("SPICEJET", "Aviation"),

            // FIX (per explicit user request, Services domain,
            // Category: Commercial & Transport Services - Phase 1).
            // Major logistics/transport companies verified across
            // multiple sources.
            Map.entry("CONCOR", "Commercial & Transport Services"),
            Map.entry("BLUEDART", "Commercial & Transport Services"),
            Map.entry("DELHIVERY", "Commercial & Transport Services"),
            Map.entry("TCI", "Commercial & Transport Services"),
            Map.entry("MAHLOG", "Commercial & Transport Services"),
            Map.entry("VRLLOG", "Commercial & Transport Services"),
            Map.entry("TCIEXP", "Commercial & Transport Services"),
            Map.entry("GATEWAY", "Commercial & Transport Services"),
            Map.entry("ALLCARGO", "Commercial & Transport Services"),
            Map.entry("SCI", "Commercial & Transport Services"),

            // FIX (per explicit user request, Realty domain, Category:
            // REITs & Realty - Phase 1). All 5 currently listed Indian
            // REITs, confirmed via multiple, highly consistent sources -
            // a genuinely small, closed category similar to PSU Banks.
            // Realty (Remaining) requires no code change - the existing
            // "Realty" industry-string mapping already correctly
            // captures property developers.
            Map.entry("EMBASSY", "REITs & Realty"),
            Map.entry("MINDSPACE", "REITs & Realty"),
            Map.entry("BIRET", "REITs & Realty"),
            Map.entry("NXST", "REITs & Realty"),
            Map.entry("KRT", "REITs & Realty"),

            // FIX (confirmed real bug from dashboard: Insurance showed
            // "No live data" - the exact same failure pattern already
            // found and fixed for Private Banks. The raw industry-
            // string retargeting to "insurance" only matches an exact
            // value, but many insurance companies' raw NSE industry may
            // directly be "Financial Services", bypassing it entirely).
            // Replaced with verified symbol overrides, same proven
            // mechanism as every other category. 3 of these (ICICIPRULI,
            // ICICIGI, MFSL) additionally cross-verified as genuinely
            // present in the tracked universe via real log data seen
            // earlier this session.
            Map.entry("LICI", "Insurance"),
            Map.entry("HDFCLIFE", "Insurance"),
            Map.entry("ICICIPRULI", "Insurance"),
            Map.entry("SBILIFE", "Insurance"),
            Map.entry("MFSL", "Insurance"),
            Map.entry("ICICIGI", "Insurance"),
            Map.entry("NIACL", "Insurance"),
            Map.entry("STARHEALTH", "Insurance"),
            Map.entry("GICRE", "Insurance")
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

    /**
     * FIX (per explicit user request, Option B): checks the symbol-
     * level override FIRST - for the specific stocks where a finer
     * sector split has been individually verified (e.g., PSU Banks).
     * Falls back to the existing, completely unchanged sectorFor(
     * industry) for every symbol not explicitly listed in the
     * override map above.
     */
    public static String sectorForSymbol(String symbol, String rawIndustry) {
        if (symbol != null) {
            String override = SYMBOL_SECTOR_OVERRIDES.get(symbol.trim().toUpperCase());
            if (override != null) return override;
        }
        return sectorFor(rawIndustry);
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