package com.trading.strategy.news;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.Collections;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * NewsKeywordFilter — stateless keyword-based article classifier.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * RESPONSIBILITIES:
 *   1. Determine if an article is relevant to Indian equity markets
 *   2. Classify into NewsCategory based on keyword presence
 *   3. Assign preliminary Sentiment (POSITIVE/NEGATIVE/NEUTRAL)
 *   4. Extract potential NSE symbols from headline text
 *   5. Compute keywordWeight (0–100) based on keyword impact tier
 * ─────────────────────────────────────────────────────────────────────────────
 */
@Component
@Slf4j
public class NewsKeywordFilter {

    // ── Tier 1: Highest-impact keywords (weight 90–100) ───────────────────────
    private static final Set<String> TIER1_EARNINGS = Set.of(
            "quarterly results", "q1 results", "q2 results", "q3 results", "q4 results",
            "annual results", "earnings", "profit surge", "net profit", "revenue beat",
            "revenue miss", "results beat", "results disappoint", "board approves dividend",
            "dividend declared", "bonus shares", "rights issue", "buy back", "buyback",
            "revenue grows", "profit falls", "loss widens", "profit doubles"
    );

    private static final Set<String> TIER1_MA = Set.of(
            "merger", "acquisition", "takeover", "buyout", "stake sale", "stake acquisition",
            "demerger", "open offer", "delisting", "amalgamation", "strategic investment",
            "acquires", "acquired by", "merger approved", "board approves merger"
    );

    // ── NEW: Breaking news — sudden company-specific events needing urgent ────
    // attention. Previously these fell through to OTHER (basePriority 20) since
    // BREAKING_NEWS (basePriority 50) was defined in the enum but never assigned
    // by classify(). This restores the category's intended use.
    // Checked AFTER EARNINGS/M&A (more specific, higher priority) and BEFORE
    // RBI/MACRO/GLOBAL/sector (which are existing, unmodified paths below).
    private static final Set<String> TIER1_BREAKING = Set.of(
            "fire breaks out", "plant fire", "factory fire", "explosion at",
            "ceo resigns", "md resigns", "cfo resigns", "resigns with immediate effect",
            "steps down", "sudden resignation",
            "raided by", "raid at", "ed raid", "cbi raid", "income tax raid",
            "search and seizure at", "premises searched",
            "plant shutdown", "factory shutdown", "production halted",
            "major client cancels", "contract cancelled", "order cancelled",
            "strike at", "workers strike", "labour unrest", "plant closure",
            "accident at", "explosion", "fire incident",
            "arrested", "chairman arrested", "promoter arrested",
            "trading halted", "circuit breaker hit", "stock halted"
    );

    // ── Tier 2: High-impact macro/policy keywords (weight 70–89) ─────────────
    private static final Set<String> TIER2_RBI = Set.of(
            "rbi", "repo rate", "reverse repo", "crr", "slr", "monetary policy",
            "mpc", "rbi governor", "rate cut", "rate hike", "liquidity",
            "inflation target", "rbi announcement", "rbi decision"
    );

    private static final Set<String> TIER2_MACRO = Set.of(
            "gdp", "cpi", "inflation", "iip", "trade deficit", "current account",
            "fiscal deficit", "budget", "gst", "disinvestment", "fii",
            "dii", "foreign investment", "fpi", "government policy", "pli scheme",
            "production linked", "import duty", "export ban", "msme",
            // FIX: replaced generic "tax" (was wrongly catching company-specific
            // tax notices/orders like "Income Tax Order received by XYZ" and
            // routing them through the strict macro direction filter).
            // These specific phrases only match genuine macro tax POLICY news.
            "income tax slab", "income tax rate", "corporate tax rate",
            "tax policy", "tax reform", "wealth tax", "tax relief for taxpayers"
    );

    private static final Set<String> TIER2_GLOBAL = Set.of(
            "federal reserve", "fed rate", "us market", "dow jones", "s&p 500",
            "nasdaq", "crude oil", "brent crude", "gold price", "dollar index",
            "china economy", "global recession", "geopolitical", "ukraine",
            "opec", "us jobs", "us inflation", "usd inr"
    );

    // ── Tier 3: Sector-specific keywords (weight 50–69) ───────────────────────
    private static final Map<String, Set<String>> SECTOR_KEYWORDS = new LinkedHashMap<>();
    static {
        SECTOR_KEYWORDS.put("Banking & Finance", Set.of(
                "npa", "bad loans", "credit growth", "casa ratio", "net interest margin",
                "nim", "loan growth", "deposit growth", "banking sector", "nbfc",
                "microfinance", "insurance premium", "aum grows", "assets under management"
        ));
        SECTOR_KEYWORDS.put("IT", Set.of(
                "it sector", "software exports", "digital transformation", "cloud contract",
                "deal win", "large deal", "total contract value", "attrition",
                "headcount", "hiring freeze", "visa", "h1b", "us tech spending"
        ));
        SECTOR_KEYWORDS.put("Pharma", Set.of(
                "fda approval", "usfda", "drug approval", "clinical trial", "patent",
                "api shortage", "drug recall", "nda", "anda", "biosimilar",
                "cancer drug", "generic drug", "export to us"
        ));
        SECTOR_KEYWORDS.put("Energy", Set.of(
                "oil price", "gas price", "refinery", "capacity expansion", "renewable energy",
                "solar capacity", "wind energy", "power tariff", "electricity demand",
                "coal shortage", "fuel price", "ongc", "reliance industries energy"
        ));
        SECTOR_KEYWORDS.put("Auto", Set.of(
                "vehicle sales", "auto sales", "ev launch", "electric vehicle",
                "auto sector", "semiconductor shortage", "ev policy", "production cut",
                "monthly sales data", "two wheeler", "passenger vehicle"
        ));
        SECTOR_KEYWORDS.put("Real Estate", Set.of(
                "housing sales", "property market", "real estate", "home loan",
                "rera", "affordable housing", "luxury homes", "office leasing",
                "commercial real estate", "residential demand"
        ));
        SECTOR_KEYWORDS.put("Metals", Set.of(
                "steel prices", "aluminium", "copper", "zinc", "iron ore",
                "metal sector", "anti-dumping", "chinese steel", "infrastructure demand",
                "commodity prices", "base metal"
        ));
        SECTOR_KEYWORDS.put("FMCG", Set.of(
                "rural demand", "urban consumption", "fmcg sector", "volume growth",
                "price hike", "raw material cost", "distribution expansion",
                "premium segment", "consumer staples"
        ));
    }

    // ── Positive / Negative sentiment keywords ────────────────────────────────
    private static final Set<String> POSITIVE_WORDS = Set.of(
            "surge", "soar", "jump", "rally", "gain", "profit", "beat", "record",
            "high", "strong", "growth", "rise", "positive", "upgrade", "bullish",
            "outperform", "buy", "dividend", "bonus", "win", "award", "approval",
            "contract win", "new order", "expansion", "launch", "partnership",
            "doubles", "triples", "exceeds", "milestone", "breakthrough", "recovery",
            // Legal / regulatory outcome words — ADDED (covers tax/court/SEBI orders)
            "favourable", "favorable", "favour of", "favor of", "relief",
            "exonerated", "acquitted", "quashed", "cleared of", "sanctioned",
            "approved by", "resolution plan approved", "no penalty", "withdrawn case",
            "settled in", "ruled in favour", "ruled in favor",
            // Corporate events — ADDED (orders, stake, FDA, ratings, capacity)
            "wins order", "receives order", "order worth",
            "promoter increases stake", "promoter increases holding",
            "buyback announced", "special dividend", "bonus issue", "stock split",
            "fda approval", "usfda approval", "credit rating upgraded",
            "capacity expansion", "plant commissioned"
    );

    private static final Set<String> NEGATIVE_WORDS = Set.of(
            "fall", "drop", "decline", "loss", "miss", "weak", "cut", "reduce",
            "warning", "downgrade", "sell", "bearish", "negative", "concern",
            "risk", "problem", "issue", "delay", "penalty", "fine", "fraud",
            "scam", "recall", "ban", "halt", "suspend", "withdraw", "disappoint",
            "widens", "narrows to loss", "slump", "crash", "plunge", "probe",
            // Legal / regulatory outcome words — ADDED (covers tax/court/SEBI orders)
            "unfavourable", "unfavorable", "against the company", "rejected",
            "raid", "raided", "seized", "search and seizure", "insolvency",
            "bankruptcy", "npa rises", "pledge shares", "promoter pledge",
            "qualified opinion", "demand notice", "show cause notice",
            "litigation", "default on", "defaulted", "irregularities found",
            // Corporate events — ADDED (stake reduction, audits, NCLT, ratings)
            "promoter reduces stake", "promoter reduces holding",
            "forensic audit", "fraud detected", "nclt admits",
            "insolvency proceedings", "default on payment",
            "credit rating downgraded"
    );

    // NSE symbol pattern — 2-10 uppercase letters (common NSE symbol format)
    private static final Pattern SYMBOL_PATTERN = Pattern.compile("\\b([A-Z]{2,10})\\b");

    // Common false-positive words that look like symbols but aren't
    private static final Set<String> NON_SYMBOLS = Set.of(
            // Exchange / Regulatory
            "THE", "AND", "FOR", "NSE", "BSE", "RBI", "SEBI", "IRDAI", "GST",
            "NCLT", "NCLAT", "SAT", "NSDL", "CDSL", "MCX", "NCDEX",
            // Corporate events — these match NSE symbols like CLEAN, BONUS
            "DIVIDEND", "BONUS", "RIGHTS", "BUYBACK", "MERGER", "ACQUISITION",
            "SCHEME", "DEMERGER", "SPLIT", "RECORD", "CLOSURE", "NOTICE",
            "HEARING", "ORDER", "APPEAL", "PETITION", "WRIT", "SUIT",
            "CLEAN", "PURE", "LONG", "SHORT", "OPEN", "CLOSE", "HIGH", "LOW",
            "ANNUAL", "GENERAL", "MEETING", "BOARD", "DIRECTOR", "COMMITTEE",
            "AUDIT", "ACCOUNTS", "BALANCE", "SHEET", "REPORT", "RESULTS",
            "QUARTER", "HALF", "YEAR", "DATE", "TIME", "LIMIT", "PERIOD",
            // Corporate actions
            "CEO", "CFO", "MD", "AGM", "EGM", "IPO", "FPO", "QIP", "OFS",
            "MOU", "MOA", "LOI", "LOA", "NDA", "MCA", "ROC",
            // Financial metrics
            "EBITDA", "PAT", "PBT", "EPS", "PE", "PB", "ROE", "ROA", "NAV",
            "NII", "NIM", "GNPA", "NNPA", "CAR", "TIER",
            // Periods
            "Q1", "Q2", "Q3", "Q4", "FY", "FY24", "FY25", "FY26", "H1", "H2",
            "YOY", "QOQ", "MOM", "YTD", "MTD",
            // Currencies / Macro
            "USD", "INR", "EUR", "GBP", "JPY", "CNY", "SGD", "AED",
            "GDP", "CPI", "WPI", "IIP", "PMI", "FII", "DII", "FPI", "NPA",
            // Sector / Entity types
            "NBFC", "MFI", "RERA", "PLI", "MSP", "MSCI", "NIFTY", "SENSEX",
            "BANK", "FUND", "TRUST", "GROUP", "CORP", "LTD", "PVT", "LLP",
            "INDIA", "GLOBAL", "WORLD", "MARKET", "SECTOR", "INDUSTRY",
            // Technology / Generic
            "US", "UK", "EU", "IT", "AI", "ML", "IOT", "EV", "CNG", "LNG",
            "MW", "GW", "KW", "KV", "OPEC", "WTO", "IMF", "ADB", "AIIB",
            // News words that look like tickers
            "NEWS", "PRESS", "RELEASE", "MEDIA", "ALERT", "UPDATE", "INFO",
            "DATA", "RATE", "PRICE", "COST", "FEE", "TAX", "DUTY", "LEVY",
            "LOSS", "GAIN", "PROFIT", "REVENUE", "INCOME", "EXPENSE",
            "GROWTH", "RISE", "FALL", "DROP", "SURGE", "JUMP", "SLIP",
            "NEW", "OLD", "BIG", "TOP", "KEY", "MAIN", "CORE", "UNIT",
            "WIN", "BUY", "SELL", "HOLD", "ADD", "EXIT", "ENTRY", "STOP",
            // NSE symbols that are also common English/financial words
            // These are valid NSE tickers BUT appear too often in generic text
            // Strategy 1 (direct ticker scan) still catches them in headlines
            // Strategy 3 (company name map) handles them via full name
            "ACC",   // "acc to sources", "acc to reports" — too common
            "CUB",   // "cub" as in bear cub — rare but risky
            "FACT",  // "fact of the matter", "in fact" — very common
            "IOB",   // "iob" rarely appears standalone in news text
            "NLC",   // generic abbreviation risk
            "PEL",   // rarely standalone in news
            "SAIL",  // "set sail", "sail through" — common in news
            "UBL"    // rarely standalone
            // NOTE: ITC, DLF, MRF, SRF, PFC, MTAR kept — they appear
            // as exact tickers in BSE headlines and are worth matching
    );


    // ── Company name → NSE symbol lookup table ────────────────────────────────
    // Covers all major Nifty500 stocks with their common news name variations.
    // Keys are LOWERCASE for case-insensitive matching.
    // Built from: BSE full names, common abbreviations, brand names used in press.
    private static final Map<String, String> COMPANY_NAME_MAP;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        // Large caps — most likely to appear in news
        m.put("reliance industries",    "RELIANCE");
        m.put("reliance industry",      "RELIANCE");
        m.put("tata consultancy",       "TCS");
        m.put("tcs",                    "TCS");
        m.put("hdfc bank",              "HDFCBANK");
        m.put("hdfcbank",               "HDFCBANK");
        m.put("infosys",                "INFY");
        m.put("icici bank",             "ICICIBANK");
        m.put("hindustan unilever",     "HINDUNILVR");
        m.put("hul",                    "HINDUNILVR");
        m.put("state bank of india",    "SBIN");
        m.put("state bank",             "SBIN");
        m.put("sbi",                    "SBIN");
        m.put("bharti airtel",          "BHARTIARTL");
        m.put("airtel",                 "BHARTIARTL");
        m.put("bajaj finance",          "BAJFINANCE");
        m.put("kotak mahindra bank",    "KOTAKBANK");
        m.put("kotak bank",             "KOTAKBANK");
        m.put("kotak mahindra",         "KOTAKBANK");
        m.put("larsen",                 "LT");
        m.put("l&t",                    "LT");
        m.put("larsen & toubro",        "LT");
        m.put("larsen and toubro",      "LT");
        m.put("itc",                    "ITC");
        m.put("axis bank",              "AXISBANK");
        m.put("asian paints",           "ASIANPAINT");
        m.put("maruti suzuki",          "MARUTI");
        m.put("maruti",                 "MARUTI");
        m.put("sun pharmaceutical",     "SUNPHARMA");
        m.put("sun pharma",             "SUNPHARMA");
        m.put("ultratech cement",       "ULTRACEMCO");
        m.put("wipro",                  "WIPRO");
        m.put("hcl technologies",       "HCLTECH");
        m.put("hcl tech",               "HCLTECH");
        m.put("tech mahindra",          "TECHM");
        m.put("tech mah",               "TECHM");
        m.put("power grid",             "POWERGRID");
        m.put("ntpc",                   "NTPC");
        m.put("ongc",                   "ONGC");
        m.put("oil and natural gas",    "ONGC");
        m.put("tata steel",             "TATASTEEL");
        m.put("tata motors",            "TATAMOTORS");
        m.put("tata power",             "TATAPOWER");
        m.put("tata consumer",          "TATACONSUM");
        m.put("dr reddy",               "DRREDDY");
        m.put("dr. reddy",              "DRREDDY");
        m.put("cipla",                  "CIPLA");
        m.put("bajaj auto",             "BAJAJ-AUTO");
        m.put("hero motocorp",          "HEROMOTOCO");
        m.put("hero moto",              "HEROMOTOCO");
        m.put("eicher motors",          "EICHERMOT");
        m.put("mahindra",               "M&M");
        m.put("m&m",                    "M&M");
        m.put("upl",                    "UPL");
        m.put("adani enterprises",      "ADANIENT");
        m.put("adani ports",            "ADANIPORTS");
        m.put("adani green",            "ADANIGREEN");
        m.put("adani power",            "ADANIPOWER");
        m.put("adani transmission",     "ADANITRANS");
        m.put("grasim",                 "GRASIM");
        m.put("hindalco",               "HINDALCO");
        m.put("nestle india",           "NESTLEIND");
        m.put("nestle",                 "NESTLEIND");
        m.put("titan",                  "TITAN");
        m.put("bajaj finserv",          "BAJAJFINSV");
        m.put("bank of baroda",         "BANKBARODA");
        m.put("canara bank",            "CANBK");
        m.put("punjab national bank",   "PNB");
        m.put("pnb",                    "PNB");
        m.put("union bank",             "UNIONBANK");
        m.put("indusind bank",          "INDUSINDBK");
        m.put("yes bank",               "YESBANK");
        m.put("federal bank",           "FEDERALBNK");
        m.put("bandhan bank",           "BANDHANBNK");
        m.put("coal india",             "COALINDIA");
        m.put("bhel",                   "BHEL");
        m.put("gail",                   "GAIL");
        m.put("ioc",                    "IOC");
        m.put("indian oil",             "IOC");
        m.put("bpcl",                   "BPCL");
        m.put("bharat petroleum",       "BPCL");
        m.put("hpcl",                   "HINDPETRO");
        m.put("hindustan petroleum",    "HINDPETRO");
        m.put("vedanta",                "VEDL");
        m.put("jsw steel",              "JSWSTEEL");
        m.put("steel authority",        "SAIL");
        m.put("sail",                   "SAIL");
        m.put("nmdc",                   "NMDC");
        m.put("sbi life",               "SBILIFE");
        m.put("hdfc life",              "HDFCLIFE");
        m.put("icici prudential",       "ICICIPRULI");
        m.put("icici lombard",          "ICICIGI");
        m.put("sbi cards",              "SBICARD");
        m.put("dmart",                  "DMART");
        m.put("avenue supermarts",      "DMART");
        m.put("havells",                "HAVELLS");
        m.put("godrej consumer",        "GODREJCP");
        m.put("dabur",                  "DABUR");
        m.put("marico",                 "MARICO");
        m.put("britannia",              "BRITANNIA");
        m.put("pidilite",               "PIDILITIND");
        m.put("berger paints",          "BERGEPAINT");
        m.put("ambuja cements",         "AMBUJACEM");
        m.put("acc",                    "ACC");
        m.put("shree cement",           "SHREECEM");
        m.put("divi's",                 "DIVISLAB");
        m.put("divis laboratories",     "DIVISLAB");
        m.put("lupin",                  "LUPIN");
        m.put("aurobindo",              "AUROPHARMA");
        m.put("torrent pharma",         "TORNTPHARM");
        m.put("abbott india",           "ABBOTINDIA");
        m.put("interglobe",             "INDIGO");
        m.put("indigo",                 "INDIGO");
        m.put("apollo hospitals",       "APOLLOHOSP");
        m.put("max healthcare",         "MAXHEALTH");
        m.put("fortis",                 "FORTIS");
        m.put("muthoot",                "MUTHOOTFIN");
        m.put("bajaj holdings",         "BAJAJHLDNG");
        m.put("irctc",                  "IRCTC");
        m.put("container corporation",  "CONCOR");
        m.put("concor",                 "CONCOR");
        m.put("intertek",               "INDUSTOWER");
        m.put("indus towers",           "INDUSTOWER");
        m.put("zomato",                 "ZOMATO");
        m.put("nykaa",                  "NYKAA");
        m.put("paytm",                  "PAYTM");
        m.put("policy bazaar",          "POLICYBZR");
        m.put("policybazaar",           "POLICYBZR");
        m.put("swiggy",                 "SWIGGY");
        m.put("eternal",                "ETERNAL");
        m.put("irfc",                   "IRFC");
        m.put("rvnl",                   "RVNL");
        m.put("recltd",                 "RECLTD");
        m.put("rec limited",            "RECLTD");
        m.put("power finance",          "PFC");
        m.put("pfc",                    "PFC");
        m.put("ireda",                  "IREDA");
        // ── Missing mappings — identified by systematic audit ─────────────────
        // Automobiles
        m.put("ashok leyland",          "ASHOKLEY");
        m.put("ashokleyland",           "ASHOKLEY");
        m.put("tvs motor",              "TVSMOTOR");
        m.put("tvs motors",             "TVSMOTOR");
        m.put("motherson sumi",         "MOTHERSON");
        m.put("samvardhana motherson",  "MOTHERSON");
        m.put("balkrishna industries",  "BALKRISIND");
        m.put("bkt tyres",              "BALKRISIND");
        // Consumer durables / electricals
        m.put("voltas",                 "VOLTAS");
        m.put("crompton greaves",       "CROMPTON");
        m.put("crompton consumer",      "CROMPTON");
        m.put("polycab",                "POLYCAB");
        m.put("cg power",               "CGPOWER");
        m.put("whirlpool india",        "WHIRLPOOL");
        // Industrial / engineering
        m.put("siemens india",          "SIEMENS");
        m.put("cummins india",          "CUMMINSIND");
        m.put("thermax",                "THERMAX");
        m.put("bharat electronics",     "BEL");
        m.put("bel",                    "BEL");
        // Metals / mining
        m.put("hindustan zinc",         "HINDZINC");
        m.put("hinduzinc",              "HINDZINC");
        m.put("national aluminium",     "NATIONALUM");
        m.put("nalco",                  "NATIONALUM");
        m.put("jindal steel",           "JINDALSTEL");
        m.put("jspl",                   "JINDALSTEL");
        m.put("jindal stainless",       "JSL");
        // Chemicals
        m.put("gujarat fluorochemicals","FLUOROCHEM");
        m.put("gfl",                    "FLUOROCHEM");
        // Finance / NBFC
        m.put("muthoot finance",        "MUTHOOTFIN");
        m.put("manappuram finance",     "MANAPPURAM");
        m.put("cholamandalam",          "CHOLAFIN");
        m.put("chola finance",          "CHOLAFIN");
        m.put("shriram finance",        "SHRIRAMFIN");
        m.put("sundaram finance",       "SUNDARMFIN");
        m.put("lic housing finance",    "LICHSGFIN");
        m.put("lic housing",            "LICHSGFIN");
        m.put("poonawalla fincorp",     "POONAWALLA");
        m.put("canara bank",            "CANBK");
        m.put("union bank of india",    "UNIONBANK");
        m.put("union bank",             "UNIONBANK");
        // Real estate
        m.put("oberoi realty",          "OBEROIRLTY");
        m.put("godrej properties",      "GODREJPROP");
        m.put("sobha",                  "SOBHA");
        m.put("brigade enterprises",    "BRIGADE");
        // Consumer
        m.put("page industries",        "PAGEIND");
        m.put("jockey",                 "PAGEIND");
        m.put("united breweries",       "UBL");
        m.put("kingfisher",             "UBL");
        // Renewables / energy
        m.put("inox wind",              "INOXWIND");
        m.put("inoxwind",               "INOXWIND");
        m.put("suzlon energy",          "SUZLON");
        m.put("cesc",                   "CESC");
        m.put("torrent power",          "TORNTPOWER");
        m.put("jsw energy",             "JSWENERGY");
        m.put("adani green",            "ADANIGREEN");
        m.put("adani power",            "ADANIPOWER");
        m.put("adani enterprises",      "ADANIENT");
        m.put("adani ports",            "ADANIPORTS");
        // Infra
        m.put("ircon international",    "IRCON");
        m.put("rail vikas nigam",       "RVNL");
        m.put("hg infra",               "HGINFRA");
        m.put("pnc infratech",          "PNCINFRA");
        COMPANY_NAME_MAP = Collections.unmodifiableMap(m);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns true if the article is relevant to Indian equity markets.
     * Fast pre-filter applied before full classification.
     */
    public boolean isRelevant(String headline, String description) {
        String combined = (headline + " " + description).toLowerCase();

        // Must contain at least one finance/market keyword
        boolean hasFinanceKeyword =
                combined.contains("india") || combined.contains("nse") ||
                        combined.contains("bse") || combined.contains("sensex") ||
                        combined.contains("nifty") || combined.contains("stock") ||
                        combined.contains("share") || combined.contains("market") ||
                        combined.contains("equity") || combined.contains("rupee") ||
                        combined.contains("rbi") || combined.contains("sebi") ||
                        // Global keywords that directly move Indian markets
                        // Added for Reuters/RBI sources — FED, crude oil, dollar
                        // always impact NSE through FII flows and commodity prices
                        combined.contains("federal reserve") || combined.contains("fed rate") ||
                        combined.contains("crude oil") || combined.contains("brent crude") ||
                        combined.contains("usd inr") || combined.contains("usd/inr") ||
                        combined.contains("dollar index") || combined.contains("dollar surges") ||
                        combined.contains("dollar falls") || combined.contains("dollar weakens") ||
                        combined.contains("us inflation") || combined.contains("us jobs") ||
                        isEarningsRelated(combined) || isMacroRelated(combined);

        return hasFinanceKeyword;
    }

    /**
     * Classify the article into a NewsCategory based on keyword matching.
     * Returns the highest-priority matching category.
     */
    public NewsItem.NewsCategory classify(String headline, String description) {
        String combined = (headline + " " + description).toLowerCase();

        if (matchesAny(combined, TIER1_EARNINGS))    return NewsItem.NewsCategory.EARNINGS;
        if (matchesAny(combined, TIER1_MA))          return NewsItem.NewsCategory.MERGER_ACQUISITION;
        if (matchesAny(combined, TIER1_BREAKING))    return NewsItem.NewsCategory.BREAKING_NEWS;
        if (matchesAny(combined, TIER2_RBI))         return NewsItem.NewsCategory.RBI_POLICY;
        if (matchesAny(combined, TIER2_MACRO))       return NewsItem.NewsCategory.ECONOMIC_DATA;
        if (matchesAny(combined, TIER2_GLOBAL))      return NewsItem.NewsCategory.GLOBAL_EVENT;

        // Check for government policy keywords
        if (combined.contains("government") || combined.contains("ministry") ||
                combined.contains("policy") || combined.contains("regulation") ||
                combined.contains("parliament") || combined.contains("cabinet")) {
            return NewsItem.NewsCategory.GOVT_POLICY;
        }

        // Check sector keywords
        for (Map.Entry<String, Set<String>> entry : SECTOR_KEYWORDS.entrySet()) {
            if (matchesAny(combined, entry.getValue())) {
                return NewsItem.NewsCategory.SECTOR_NEWS;
            }
        }

        return NewsItem.NewsCategory.OTHER;
    }

    /**
     * Determine preliminary sentiment from keyword analysis.
     */
    public NewsItem.Sentiment detectSentiment(String headline, String description) {
        String combined = (headline + " " + description).toLowerCase();
        int positiveCount = 0, negativeCount = 0;

        for (String word : POSITIVE_WORDS) {
            if (combined.contains(word)) positiveCount++;
        }
        for (String word : NEGATIVE_WORDS) {
            if (combined.contains(word)) negativeCount++;
        }

        int net = positiveCount - negativeCount;
        if      (net >= 3)  return NewsItem.Sentiment.STRONGLY_POSITIVE;
        else if (net >= 1)  return NewsItem.Sentiment.POSITIVE;
        else if (net <= -3) return NewsItem.Sentiment.STRONGLY_NEGATIVE;
        else if (net <= -1) return NewsItem.Sentiment.NEGATIVE;
        else                return NewsItem.Sentiment.NEUTRAL;
    }

    /**
     * Compute keyword impact weight (0–100) based on tier and count.
     */
    public int computeKeywordWeight(String headline, String description,
                                    NewsItem.NewsCategory category) {
        String combined = (headline + " " + description).toLowerCase();
        int weight = category.basePriority;

        // Bonus for multiple keyword hits
        int hits = countMatches(combined, TIER1_EARNINGS) +
                countMatches(combined, TIER1_MA) +
                countMatches(combined, TIER1_BREAKING) +
                countMatches(combined, TIER2_RBI) +
                countMatches(combined, TIER2_MACRO);
        weight += Math.min(15, hits * 3);

        return Math.min(100, weight);
    }

    /**
     * Extract potential NSE symbols from headline text.
     * Conservative: only return symbols that look like real NSE symbols.
     */
    public List<String> extractSymbols(String headline, String description,
                                       Set<String> knownSymbols) {
        String combined = headline + " " + description;
        Set<String> found = new LinkedHashSet<>();

        // Strategy 1: Look for known symbols directly
        String upper = combined.toUpperCase();
        for (String sym : knownSymbols) {
            // Check as word boundary
            if (upper.contains(" " + sym + " ") ||
                    upper.contains(" " + sym + ",") ||
                    upper.contains(" " + sym + ".") ||
                    upper.contains("(" + sym + ")") ||
                    upper.startsWith(sym + " ")) {
                found.add(sym);
            }
        }

        // Strategy 2: Regex for uppercase words not in exclusion list
        var matcher = SYMBOL_PATTERN.matcher(combined);
        while (matcher.find()) {
            String candidate = matcher.group(1);
            if (!NON_SYMBOLS.contains(candidate) &&
                    candidate.length() >= 3 &&
                    knownSymbols.contains(candidate)) {
                found.add(candidate);
            }
        }

        // Strategy 3: Company name → NSE symbol mapping table
        // Handles "HDFC Bank" → "HDFCBANK", "Infosys" → "INFY" etc.
        // BSE/RSS headlines use company names, not NSE tickers.
        String lowerCombined = combined.toLowerCase();
        for (Map.Entry<String, String> entry : COMPANY_NAME_MAP.entrySet()) {
            if (lowerCombined.contains(entry.getKey())) {
                String mappedSymbol = entry.getValue();
                if (knownSymbols.contains(mappedSymbol)) {
                    found.add(mappedSymbol);
                }
            }
        }

        return new ArrayList<>(found);
    }

    /**
     * Identify sectors mentioned in the article.
     */
    public List<String> extractSectors(String headline, String description) {
        String combined = (headline + " " + description).toLowerCase();
        List<String> sectors = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : SECTOR_KEYWORDS.entrySet()) {
            if (matchesAny(combined, entry.getValue())) {
                sectors.add(entry.getKey());
            }
        }
        return sectors;
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private boolean matchesAny(String text, Set<String> keywords) {
        return keywords.stream().anyMatch(text::contains);
    }

    private int countMatches(String text, Set<String> keywords) {
        return (int) keywords.stream().filter(text::contains).count();
    }

    private boolean isEarningsRelated(String text) {
        return TIER1_EARNINGS.stream().anyMatch(text::contains);
    }

    private boolean isMacroRelated(String text) {
        return TIER2_RBI.stream().anyMatch(text::contains) ||
                TIER2_MACRO.stream().anyMatch(text::contains);
    }
}