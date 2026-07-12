package com.trading.strategy.news;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.Collections;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * NewsKeywordFilter - stateless keyword-based article classifier.
 *
 * -----------------------------------------------------------------------------
 * RESPONSIBILITIES:
 *   1. Determine if an article is relevant to Indian equity markets
 *   2. Classify into NewsCategory based on keyword presence
 *   3. Assign preliminary Sentiment (POSITIVE/NEGATIVE/NEUTRAL)
 *   4. Extract potential NSE symbols from headline text
 *   5. Compute keywordWeight (0-100) based on keyword impact tier
 * -----------------------------------------------------------------------------
 */
@Component
@Slf4j
public class NewsKeywordFilter {

    // -- Tier 1: Highest-impact keywords (weight 90-100) -----------------------
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

    // -- NEW: Breaking news - sudden company-specific events needing urgent ----
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

    // -- Tier 2: High-impact macro/policy keywords (weight 70-89) -------------
    private static final Set<String> TIER2_RBI = Set.of(
            "rbi", "repo rate", "reverse repo", "crr", "slr", "monetary policy",
            "rbi governor", "rate cut", "rate hike", "liquidity",
            // FIX (found via direct user report): removed standalone "mpc" -
            // it's a literal substring of "MPCB" (Maharashtra Pollution
            // Control Board, a completely unrelated state environmental
            // regulator), causing a company-specific pollution/closure
            // notice to be misclassified as RBI monetary policy news.
            // "monetary policy" above already safely covers genuine RBI
            // Monetary Policy Committee announcements without this
            // collision risk - real MPC news headlines virtually always
            // also say "monetary policy" or "RBI" directly.
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

    // -- Tier 3: Sector-specific keywords (weight 50-69) -----------------------
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

    // -- Positive / Negative sentiment keywords --------------------------------
    private static final Set<String> POSITIVE_WORDS = Set.of(
            "surge", "soar", "jump", "rally", "gain", "profit", "beat", "record",
            "high", "strong", "growth", "rise", "positive", "upgrade", "bullish",
            "outperform", "buy", "dividend", "bonus", "win", "award", "approval",
            "contract win", "new order", "expansion", "launch", "partnership",
            "doubles", "triples", "exceeds", "milestone", "breakthrough", "recovery",
            // Legal / regulatory outcome words - ADDED (covers tax/court/SEBI orders)
            "favourable", "favorable", "favour of", "favor of", "relief",
            "exonerated", "acquitted", "quashed", "cleared of", "sanctioned",
            "approved by", "resolution plan approved", "no penalty", "withdrawn case",
            "settled in", "ruled in favour", "ruled in favor",
            // Corporate events - ADDED (orders, stake, FDA, ratings, capacity)
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
            // Legal / regulatory outcome words - ADDED (covers tax/court/SEBI orders)
            "unfavourable", "unfavorable", "against the company", "rejected",
            "raid", "raided", "seized", "search and seizure", "insolvency",
            "bankruptcy", "npa rises", "pledge shares", "promoter pledge",
            "qualified opinion", "demand notice", "show cause notice",
            "litigation", "default on", "defaulted", "irregularities found",
            // Corporate events - ADDED (stake reduction, audits, NCLT, ratings)
            "promoter reduces stake", "promoter reduces holding",
            "forensic audit", "fraud detected", "nclt admits",
            "insolvency proceedings", "default on payment",
            "credit rating downgraded"
    );

    // NSE symbol pattern - 2-10 uppercase letters (common NSE symbol format)
    private static final Pattern SYMBOL_PATTERN = Pattern.compile("\\b([A-Z]{2,10})\\b");

    // Common false-positive words that look like symbols but aren't
    private static final Set<String> NON_SYMBOLS = Set.of(
            // Exchange / Regulatory
            "THE", "AND", "FOR", "NSE", "BSE", "RBI", "SEBI", "IRDAI", "GST",
            "NCLT", "NCLAT", "SAT", "NSDL", "CDSL", "MCX", "NCDEX",
            // Corporate events - these match NSE symbols like CLEAN, BONUS
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
            "ACC",   // "acc to sources", "acc to reports" - too common
            "CUB",   // "cub" as in bear cub - rare but risky
            "FACT",  // "fact of the matter", "in fact" - very common
            "IOB",   // "iob" rarely appears standalone in news text
            "NLC",   // generic abbreviation risk
            "PEL",   // rarely standalone in news
            "SAIL",  // "set sail", "sail through" - common in news
            "UBL"    // rarely standalone
            // NOTE: ITC, DLF, MRF, SRF, PFC, MTAR kept - they appear
            // as exact tickers in BSE headlines and are worth matching
    );


    // -- Company name -> NSE symbol lookup table --------------------------------
    // Covers all major Nifty500 stocks with their common news name variations.
    // Keys are LOWERCASE for case-insensitive matching.
    // Built from: BSE full names, common abbreviations, brand names used in press.
    private static final Map<String, String> COMPANY_NAME_MAP;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        // Large caps - most likely to appear in news
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
        m.put("adani transmission",     "ADANIENSOL");
        m.put("adani energy solutions", "ADANIENSOL");
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
        // REMOVED: m.put("sail", "SAIL") - matching is substring-based, not
        // word-boundary. "sail" falsely matches inside "sailing", "assail",
        // "unassailable" - real words that can appear in unrelated news.
        // "steel authority" (above) is the safe, unambiguous alternative;
        // Strategy 1 also independently catches a literal "SAIL" ticker
        // mention regardless of this removal.
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
        // REMOVED: m.put("acc", "ACC") - substring match falsely matches
        // inside "according", "accept", "account", "access", "accurate" -
        // all extremely common in business prose. Strategy 1 (direct ticker
        // scan) independently catches a literal "ACC" mention in headlines,
        // so no real coverage is lost by removing this risky key.
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
        m.put("zomato",                 "ETERNAL");
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
        // -- Missing mappings - identified by systematic audit -----------------
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
        // -- High-liquidity coverage expansion - added to bring all 532
        // HIGH_LIQUIDITY_SYMBOLS up to name-recognition coverage, not just
        // ticker-direct matching. Verified against NSE listings; a small
        // number of genuinely uncertain/very-recent-listing symbols were
        // deliberately left unmapped rather than guessed.
        m.put("hindustan aeronautics", "HAL");
        m.put("hal", "HAL");
        m.put("beml", "BEML");
        m.put("mrf tyres", "MRF");
        m.put("mrf", "MRF");
        m.put("dlf", "DLF");
        m.put("trent", "TRENT");
        m.put("westside", "TRENT");
        m.put("glenmark pharmaceuticals", "GLENMARK");
        m.put("glenmark", "GLENMARK");
        m.put("bata india", "BATAINDIA");
        m.put("bata", "BATAINDIA");
        m.put("bosch limited", "BOSCHLTD");
        m.put("bosch india", "BOSCHLTD");
        m.put("colgate palmolive", "COLPAL");
        m.put("colgate", "COLPAL");
        m.put("gillette india", "GILLETTE");
        m.put("gillette", "GILLETTE");
        m.put("pfizer india", "PFIZER");
        m.put("pfizer", "PFIZER");
        m.put("sanofi india", "SANOFI");
        m.put("sanofi", "SANOFI");
        m.put("naukri", "NAUKRI");
        m.put("info edge", "NAUKRI");
        m.put("indiamart", "INDIAMART");
        m.put("zee entertainment", "ZEEL");
        m.put("zee", "ZEEL");
        m.put("sun tv network", "SUNTV");
        m.put("sun tv", "SUNTV");
        m.put("raymond", "RAYMOND");
        m.put("jubilant foodworks", "JUBLFOOD");
        m.put("dominos india", "JUBLFOOD");
        m.put("vodafone idea", "IDEA");
        m.put("vi", "IDEA");
        m.put("indian overseas bank", "IOB");
        m.put("central bank of india", "CENTRALBK");
        m.put("multi commodity exchange", "MCX");
        m.put("bse limited", "BSE");
        m.put("central depository services", "CDSL");
        m.put("cdsl", "CDSL");
        m.put("nhpc", "NHPC");
        m.put("sjvn", "SJVN");
        m.put("nbcc", "NBCC");
        m.put("hudco", "HUDCO");
        m.put("railtel", "RAILTEL");
        m.put("mazagon dock", "MAZDOCK");
        m.put("gland pharma", "GLAND");
        m.put("alkem laboratories", "ALKEM");
        m.put("alkem", "ALKEM");
        m.put("zydus lifesciences", "ZYDUSLIFE");
        m.put("zydus", "ZYDUSLIFE");
        m.put("zydus wellness", "ZYDUSWELL");
        m.put("granules india", "GRANULES");
        m.put("laurus labs", "LAURUSLABS");
        m.put("natco pharma", "NATCOPHARM");
        m.put("ipca laboratories", "IPCALAB");
        m.put("ipca", "IPCALAB");
        m.put("jb chemicals", "JBCHEPHARM");
        m.put("ajanta pharma", "AJANTPHARM");
        m.put("wockhardt", "WOCKPHARMA");
        m.put("suven pharma", "SUVENPHARM");
        m.put("jubilant pharmova", "JUBLPHARMA");
        m.put("syngene international", "SYNGENE");
        m.put("syngene", "SYNGENE");
        m.put("apollo tyres", "APOLLOTYRE");
        m.put("ceat", "CEATLTD");
        m.put("jk tyre", "JKTYRE");
        m.put("exide industries", "EXIDEIND");
        m.put("exide", "EXIDEIND");
        m.put("bharat forge", "BHARATFORG");
        m.put("endurance technologies", "ENDURANCE");
        m.put("uno minda", "UNOMINDA");
        m.put("escorts kubota", "ESCORTS");
        m.put("escorts", "ESCORTS");
        m.put("force motors", "FORCEMOT");
        m.put("schaeffler india", "SCHAEFFLER");
        m.put("timken india", "TIMKEN");
        m.put("titagarh rail", "TITAGARH");
        m.put("samsonite", "VIPIND");
        m.put("hyundai motor india", "HYUNDAI");
        m.put("hyundai", "HYUNDAI");
        m.put("trident group", "TRIDENT");
        m.put("welspun living", "WELSPUNLIV");
        m.put("vardhman textiles", "VTL");
        m.put("kpr mill", "KPRMILL");
        m.put("century textiles", "CENTURYTEX");
        m.put("relaxo footwears", "RELAXO");
        m.put("metro brands", "METROBRAND");
        m.put("campus activewear", "CAMPUS");
        m.put("vmart retail", "VMART");
        m.put("v-mart", "VMART");
        m.put("shoppers stop", "SHOPERSTOP");
        m.put("aditya birla fashion", "ABFRL");
        m.put("vedant fashions", "VEDANTFASN");
        m.put("westlife foodworld", "WESTLIFE");
        m.put("mcdonalds india", "WESTLIFE");
        m.put("devyani international", "DEVYANI");
        m.put("kfc india", "DEVYANI");
        m.put("sapphire foods", "SAPPHIRE");
        m.put("travel food services", "TRAVELFOOD");
        m.put("vbl", "VBL");
        m.put("varun beverages", "VBL");
        m.put("united spirits", "UNITDSPR");
        m.put("radico khaitan", "RADICO");
        m.put("indian hotels", "INDHOTEL");
        m.put("taj hotels", "INDHOTEL");
        m.put("eih limited", "EIHOTEL");
        m.put("oberoi hotels", "EIHOTEL");
        m.put("chalet hotels", "CHALET");
        m.put("lemon tree hotels", "LEMONTREE");
        m.put("itc hotels", "ITCHOTELS");
        m.put("delhivery", "DELHIVERY");
        m.put("blue dart", "BLUEDART");
        m.put("container corporation of india", "CONCOR");
        m.put("indian railway finance", "IRFC");
        m.put("rail vikas nigam limited", "RVNL");
        m.put("rites", "RITES");
        m.put("ircon", "IRCON");
        m.put("gmr airports", "GMRAIRPORT");
        m.put("gmr infra", "GMRAIRPORT");
        m.put("adani total gas", "ATGL");
        m.put("petronet lng", "PETRONET");
        m.put("mahanagar gas", "MGL");
        m.put("indraprastha gas", "IGL");
        m.put("gujarat gas", "GUJGASLTD");
        m.put("gujarat state petronet", "GSPL");
        m.put("oil india", "OIL");
        m.put("mangalore refinery", "MRPL");
        m.put("chennai petroleum", "CHENNPETRO");
        m.put("indian railway catering", "IRCTC");
        m.put("crisil", "CRISIL");
        m.put("cams", "CAMS");
        m.put("computer age management", "CAMS");
        m.put("k-fin technologies", "KFINTECH");
        m.put("kfin technologies", "KFINTECH");
        m.put("multi commodity", "MCX");
        m.put("angel one", "ANGELONE");
        m.put("motilal oswal", "MOTILALOFS");
        m.put("iifl finance", "IIFL");
        m.put("5paisa capital", "5PAISA");
        m.put("geojit financial", "GEOJITFSL");
        m.put("nuvama wealth", "NUVAMA");
        m.put("anand rathi", "ANANDRATHI");
        m.put("jm financial", "JMFINANCIL");
        m.put("360 one wam", "360ONE");
        m.put("hdfc amc", "HDFCAMC");
        m.put("hdfc asset management", "HDFCAMC");
        m.put("uti amc", "UTIAMC");
        m.put("aditya birla sun life amc", "ABSLAMC");
        m.put("icici prudential amc", "ICICIAMC");
        m.put("nippon life india amc", "NAM-INDIA");
        m.put("can fin homes", "CANFINHOME");
        m.put("home first finance", "HOMEFIRST");
        m.put("aptus value housing", "APTUS");
        m.put("aavas financiers", "AAVAS");
        m.put("lic housing finance limited", "LICHSGFIN");
        m.put("pnb housing finance", "PNBHOUSING");
        m.put("aadhar housing finance", "AADHARHFC");
        m.put("sbfc finance", "SBFC");
        m.put("sammaan capital", "SAMMAANCAP");
        m.put("indiabulls housing", "SAMMAANCAP");
        m.put("five star business finance", "FIVESTAR");
        m.put("credit access grameen", "CREDITACC");
        m.put("muthoot capital", "MUTHOOTCAP");
        m.put("ifci", "IFCI");
        m.put("idbi bank", "IDBI");
        m.put("mahindra finance", "M&MFIN");
        m.put("tata capital", "TATACAP");
        m.put("tata investment", "TATAINVEST");
        m.put("bajaj housing finance", "BAJAJHFL");
        m.put("star health insurance", "STARHEALTH");
        m.put("star health", "STARHEALTH");
        m.put("niva bupa", "NIVABUPA");
        m.put("go digit insurance", "GODIGIT");
        m.put("go digit", "GODIGIT");
        m.put("general insurance corporation", "GICRE");
        m.put("new india assurance", "NIACL");
        m.put("bank of india", "BANKINDIA");
        m.put("indian bank", "INDIANB");
        m.put("karur vysya bank", "KARURVYSYA");
        m.put("rbl bank", "RBLBANK");
        m.put("idfc first bank", "IDFCFIRSTB");
        m.put("au small finance bank", "AUBANK");
        m.put("equitas small finance bank", "EQUITASBNK");
        m.put("ujjivan small finance bank", "UJJIVANSFB");
        m.put("esaf small finance bank", "ESAFSFB");
        m.put("suryoday small finance bank", "SURYODAY");
        m.put("bandhan bank", "BANDHANBNK");
        m.put("uco bank", "UCOBANK");
        m.put("punjab and sind bank", "PSB");
        m.put("jammu and kashmir bank", "J&KBANK");
        m.put("dhanlaxmi bank", "DHANBANK");
        m.put("south indian bank", "SOUTHBANK");
        m.put("city union bank", "CUB");
        m.put("maharashtra bank", "MAHABANK");
        m.put("bank of maharashtra", "MAHABANK");
        m.put("apar industries", "APARINDS");
        m.put("polycab india", "POLYCAB");
        m.put("havells india", "HAVELLS");
        m.put("kei industries", "KEI");
        m.put("rr kabel", "RRKABEL");
        m.put("finolex cables", "FINCABLES");
        m.put("dixon technologies", "DIXON");
        m.put("amber enterprises", "AMBER");
        m.put("syrma sgs", "SYRMA");
        m.put("kaynes technology", "KAYNES");
        m.put("cyient", "CYIENT");
        m.put("cyient dlm", "CYIENTDLM");
        m.put("zen technologies", "ZENTEC");
        m.put("data patterns", "DATAPATTNS");
        m.put("hbl power systems", "HBLENGINE");
        m.put("centum electronics", "CENTUM");
        m.put("avalon technologies", "AVALON");
        m.put("crompton greaves consumer", "CROMPTON");
        m.put("voltas limited", "VOLTAS");
        m.put("blue star", "BLUESTARCO");
        m.put("whirlpool of india", "WHIRLPOOL");
        m.put("kajaria ceramics", "KAJARIACER");
        m.put("cera sanitaryware", "CERA");
        m.put("greenpanel industries", "GREENPANEL");
        m.put("century plyboards", "CENTURYPLY");
        m.put("astral limited", "ASTRAL");
        m.put("astral pipes", "ASTRAL");
        m.put("supreme industries", "SUPREMEIND");
        m.put("prince pipes", "PRINCEPIPE");
        m.put("ambuja cement", "AMBUJACEM");
        m.put("acc limited", "ACC");
        m.put("jk cement", "JKCEMENT");
        m.put("ramco cements", "RAMCOCEM");
        m.put("dalmia bharat", "DALBHARAT");
        m.put("birla corporation", "BIRLACORP");
        m.put("jk lakshmi cement", "JKLAKSHMI");
        m.put("nuvoco vistas", "NUVOCO");
        m.put("heidelberg cement", "HEIDELBERG");
        m.put("jsw cement", "JSWCEMENT");
        m.put("national aluminium company", "NATIONALUM");
        m.put("hindustan copper", "HINDCOPPER");
        m.put("gravita india", "GRAVITA");
        m.put("graphite india", "GRAPHITE");
        m.put("hindustan zinc limited", "HINDZINC");
        m.put("shyam metalics", "SHYAMMETL");
        m.put("welspun corp", "WELCORP");
        m.put("jindal saw", "JINDALSAW");
        m.put("apl apollo tubes", "APLAPOLLO");
        m.put("jindal stainless", "JSL");
        m.put("ratnamani metals", "RATNAMANI");
        m.put("sarda energy", "SARDAEN");
        m.put("kirloskar oil engines", "KIRLOSENG");
        m.put("kirloskar pneumatic", "KPIL");
        m.put("thermax limited", "THERMAX");
        m.put("abb india", "ABB");
        m.put("siemens limited", "SIEMENS");
        m.put("schneider electric infrastructure", "SCHNEIDER");
        m.put("ge t&d india", "GVT&D");
        m.put("cummins generator", "CUMMINSIND");
        m.put("triveni turbine", "TRITURBINE");
        m.put("elgi equipments", "ELGIEQUIP");
        m.put("greaves cotton", "GREAVESCOT");
        m.put("praj industries", "PRAJIND");
        m.put("ksb pumps", "KSB");
        m.put("kirloskar brothers", "KIRLOSBROS");
        m.put("jyoti cnc automation", "JYOTICNC");
        m.put("titan company", "TITAN");
        m.put("rajesh exports", "RAJESHEXPO");
        m.put("kalyan jewellers", "KALYANKJIL");
        m.put("senco gold", "SENCO");
        m.put("pc jeweller", "PCJEWELLER");
        m.put("tbz", "TBZ");
        m.put("tribhovandas bhimji zaveri", "TBZ");
        m.put("vaibhav global", "VAIBHAVGBL");
        m.put("page industries limited", "PAGEIND");
        m.put("kewal kiran clothing", "KKCL");
        m.put("birla precision", "BIRLAPREC");
        m.put("emami limited", "EMAMILTD");
        m.put("godrej industries", "GODREJIND");
        m.put("marico limited", "MARICO");
        m.put("dabur india", "DABUR");
        m.put("jyothy labs", "JYOTHYLAB");
        m.put("honasa consumer", "HONASA");
        m.put("mamaearth", "HONASA");
        m.put("nykaa", "NYKAA");
        m.put("fsn e-commerce", "NYKAA");
        m.put("bikaji foods", "BIKAJI");
        m.put("dom's industries", "DOMS");
        m.put("doms industries", "DOMS");
        m.put("eveready industries", "EVEREADY");
        m.put("vst industries", "VSTIND");
        m.put("nestle limited", "NESTLEIND");
        m.put("britannia industries", "BRITANNIA");
        m.put("hatsun agro", "HATSUN");
        m.put("heritage foods", "HERITGFOOD");
        m.put("kwality limited", "KWALITY");
        m.put("avenue supermarts limited", "DMART");
        m.put("trent hypermarket", "TRENT");
        m.put("v2 retail", "V2RETAIL");
        m.put("aditya birla retail", "ABFRL");
        m.put("saregama india", "SAREGAMA");
        m.put("pvr inox", "PVRINOX");
        m.put("network18", "NETWORK18");
        m.put("tv18 broadcast", "TV18BRDCST");
        m.put("dish tv", "DISHTV");
        m.put("hathway cable", "HATHWAY");
        m.put("den networks", "DEN");
        m.put("info edge india", "NAUKRI");
        m.put("just dial", "JUSTDIAL");
        m.put("affle india", "AFFLE");
        m.put("affle", "AFFLE");
        m.put("indiamart intermesh", "INDIAMART");
        m.put("matrimony.com", "MATRIMONY");
        m.put("rategain travel technologies", "RATEGAIN");
        m.put("map my india", "MAPMYINDIA");
        m.put("c.e. info systems", "MAPMYINDIA");
        m.put("newgen software", "NEWGEN");
        m.put("intellect design arena", "INTELLECT");
        m.put("birlasoft", "BSOFT");
        m.put("zensar technologies", "ZENSARTECH");
        m.put("persistent systems", "PERSISTENT");
        m.put("coforge", "COFORGE");
        m.put("mphasis", "MPHASIS");
        m.put("l&t technology services", "LTTS");
        m.put("l&t mindtree", "LTM");
        m.put("ltimindtree", "LTIM");
        m.put("mindtree", "LTIM");
        m.put("tata elxsi", "TATAELXSI");
        m.put("kpit technologies", "KPITTECH");
        m.put("tata technologies", "TATATECH");
        m.put("happiest minds", "HAPPSTMNDS");
        m.put("latentview analytics", "LATENTVIEW");
        m.put("cms info systems", "CMSINFO");
        m.put("oracle financial services", "OFSS");
        m.put("sonata software", "SONATSOFTW");
        m.put("netweb technologies", "NETWEB");
        m.put("ksolves india", "KSOLVES");
        m.put("cartrade tech", "CARTRADE");
        m.put("policybazaar", "POLICYBZR");
        m.put("pb fintech", "POLICYBZR");
        m.put("one97 communications", "PAYTM");
        m.put("info edge ventures", "NAUKRI");
        m.put("indegene", "INDGN");
        m.put("medplus health", "MEDPLUS");
        m.put("rainbow childrens hospital", "RAINBOW");
        m.put("apollo hospitals enterprise", "APOLLOHOSP");
        m.put("fortis healthcare", "FORTIS");
        m.put("max healthcare institute", "MAXHEALTH");
        m.put("krishna institute of medical sciences", "KIMS");
        m.put("kims hospitals", "KIMS");
        m.put("aster dm healthcare", "ASTERDM");
        m.put("global health", "MEDANTA");
        m.put("medanta", "MEDANTA");
        m.put("narayana hrudayalaya", "NH");
        m.put("yatharth hospital", "YATHARTH");
        m.put("metropolis healthcare", "METROPOLIS");
        m.put("dr lal pathlabs", "LALPATHLAB");
        m.put("vijaya diagnostic", "VIJAYA");
        m.put("thyrocare", "THYROCARE");
        m.put("poly medicure", "POLYMED");
        m.put("caplin point laboratories", "CAPLIPOINT");
        m.put("mankind pharma", "MANKIND");
        m.put("torrent power", "TORNTPOWER");
        m.put("cesc limited", "CESC");
        m.put("jsw energy", "JSWENERGY");
        m.put("jp power", "JPPOWER");
        m.put("reliance power", "RPOWER");
        m.put("nlc india", "NLCINDIA");
        m.put("ntpc green energy", "NTPCGREEN");
        m.put("acme solar", "ACMESOLAR");
        m.put("acme solar holdings", "ACMESOLAR");
        m.put("waaree energies", "WAAREEENER");
        m.put("inox wind energy", "INOXWIND");
        m.put("ola electric", "OLAELEC");
        m.put("ather energy", "ATHERENERG");
        m.put("olectra greentech", "OLECTRA");
        m.put("emmvee photovoltaic", "EMMVEE");
        m.put("premier energies", "PREMIERENE");
        m.put("urja global", "URJA");
        m.put("websol energy", "WEBELSOLAR");
        m.put("rattan india power", "RTNPOWER");
        m.put("torrent gas", "TORNTPOWER");
        m.put("indian energy exchange", "IEX");
        m.put("iex", "IEX");
        m.put("clean science", "CLEAN");
        m.put("clean science and technology", "CLEAN");
        m.put("deepak nitrite", "DEEPAKNTR");
        m.put("deepak fertilisers", "DEEPAKFERT");
        m.put("aarti industries", "AARTIIND");
        m.put("srf limited", "SRF");
        m.put("pidilite industries", "PIDILITIND");
        m.put("navin fluorine", "NAVINFLUOR");
        m.put("atul limited", "ATUL");
        m.put("sumitomo chemical india", "SUMICHEM");
        m.put("solar industries", "SOLARINDS");
        m.put("chambal fertilisers", "CHAMBLFERT");
        m.put("coromandel international", "COROMANDEL");
        m.put("paradeep phosphates", "PARADEEP");
        m.put("rashtriya chemicals", "RCF");
        // REMOVED: m.put("fact", "FACT") - substring match falsely matches
        // inside "manufacture", "manufacturing", "manufacturer",
        // "satisfaction" - all extremely common in Indian business/
        // industrial news. The full name below is the safe alternative.
        m.put("fertilisers and chemicals travancore", "FACT");
        m.put("gail india", "GAIL");
        m.put("oil and natural gas corporation", "ONGC");
        m.put("vedanta limited", "VEDL");
        m.put("adani wilmar", "AWL");
        m.put("aw l agri", "AWL");
        m.put("patanjali foods", "PATANJALI");
        m.put("ruchi soya", "PATANJALI");
        m.put("gujarat ambuja exports", "GAEL");
        m.put("godfrey phillips", "GODFRYPHLP");
        m.put("vst tillers", "VSTTILLERS");
        m.put("escorts limited", "ESCORTS");
        m.put("balrampur chini mills", "BALRAMCHIN");
        m.put("eid parry", "EIDPARRY");
        m.put("dcm shriram", "DCMSHRIRAM");
        m.put("godavari biorefineries", "GODAVARIB");
        m.put("triveni engineering", "TRIVENI");
        m.put("dwarikesh sugar", "DWARKESH");
        m.put("kaveri seed", "KAVERISEED");
        m.put("rallis india", "RALLIS");
        m.put("upl limited", "UPL");
        m.put("bayer cropscience", "BAYERCROP");
        m.put("pi industries", "PIIND");
        m.put("sumitomo chemical", "SUMICHEM");
        m.put("dhanuka agritech", "DHANUKA");
        m.put("bharat petroleum corporation", "BPCL");
        m.put("hindustan petroleum corporation", "HINDPETRO");
        m.put("indian oil corporation", "IOC");
        m.put("castrol india", "CASTROLIND");
        m.put("gulf oil lubricants", "GULFOILLUB");
        m.put("savita oil technologies", "SOTL");
        m.put("pinelabs", "PINELABS");
        m.put("pine labs", "PINELABS");
        m.put("groww", "GROWW");
        m.put("billionbrains garage", "GROWW");
        m.put("meesho", "MEESHO");
        m.put("swiggy limited", "SWIGGY");
        m.put("eternal limited", "ETERNAL");
        m.put("zomato eternal", "ETERNAL");
        m.put("firstcry", "FIRSTCRY");
        m.put("brainbees solutions", "FIRSTCRY");
        m.put("urban company", "URBANCO");
        m.put("vishal mega mart", "VMM");
        m.put("lenskart", "LENSKART");
        m.put("niva bupa health insurance", "NIVABUPA");
        m.put("sagility india", "SAGILITY");
        m.put("concord biotech", "CONCORDBIO");
        m.put("emcure pharmaceuticals", "EMCURE");
        m.put("sai life sciences", "SAILIFE");
        m.put("belrise industries", "BELRISE");
        m.put("jwl", "JWL");
        m.put("jupiter wagons", "JWL");
        m.put("titagarh rail systems", "TITAGARH");
        m.put("texmaco rail", "TEXRAIL");
        m.put("railway products", "RVNL");
        m.put("garden reach shipbuilders", "GRSE");
        m.put("cochin shipyard", "COCHINSHIP");
        m.put("mazagon dock shipbuilders", "MAZDOCK");
        m.put("bharat dynamics", "BDL");
        m.put("bharat electronics limited", "BEL");
        m.put("hindustan aeronautics limited", "HAL");
        m.put("data security council", "DSCI");
        m.put("inox india", "INOXINDIA");
        m.put("inox wind limited", "INOXWIND");
        m.put("borosil renewables", "BORORENEW");
        m.put("apollo micro systems", "APOLLOMICR");
        m.put("paras defence", "PARAS");
        m.put("zen technologies limited", "ZENTEC");
        m.put("data patterns india", "DATAPATTNS");
        m.put("astra microwave", "ASTRAMICRO");
        m.put("mtar technologies", "MTARTECH");
        m.put("hindustan shipyard", "HSL");
        m.put("garden silk mills", "GARDENSILK");
        m.put("genesys international", "GENESYS");
        m.put("centrum capital", "CENTRUM");
        m.put("ircon international limited", "IRCON");
        m.put("hg infra engineering", "HGINFRA");
        m.put("pnc infratech limited", "PNCINFRA");
        m.put("kec international", "KEC");
        m.put("kalpataru projects", "KPIL");
        m.put("nbcc india limited", "NBCC");
        m.put("ahluwalia contracts", "AHLUCONT");
        m.put("ncc limited", "NCC");
        m.put("nagarjuna construction", "NCC");
        m.put("irb infrastructure", "IRB");
        m.put("gmr infrastructure", "GMRAIRPORT");
        m.put("dlf limited", "DLF");
        m.put("godrej properties limited", "GODREJPROP");
        m.put("oberoi realty limited", "OBEROIRLTY");
        m.put("macrotech developers", "LODHA");
        m.put("lodha group", "LODHA");
        m.put("sobha limited", "SOBHA");
        m.put("prestige estates", "PRESTIGE");
        m.put("brigade enterprises limited", "BRIGADE");
        m.put("phoenix mills", "PHOENIXLTD");
        m.put("anant raj", "ANANTRAJ");
        m.put("signature global", "SIGNATURE");
        m.put("puravankara", "PURVANKARA");
        m.put("sunteck realty", "SUNTECK");
        m.put("mahindra lifespace", "MAHLIFE");
        m.put("godrej industries limited", "GODREJIND");
        m.put("ttk prestige", "TTKPRESTIG");
        m.put("symphony limited", "SYMPHONY");
        m.put("orient electric", "ORIENTELEC");
        m.put("v guard industries", "VGUARD");
        m.put("usha martin", "USHAMART");
        m.put("apar industries limited", "APARINDS");
        m.put("kec international limited", "KEC");
        m.put("schaeffler india limited", "SCHAEFFLER");
        m.put("timken india limited", "TIMKEN");
        m.put("skf india", "SKFINDIA");
        m.put("nrb bearings", "NRBBEARING");
        m.put("craftsman automation", "CRAFTSMAN");
        m.put("sona blw precision", "SONACOMS");
        m.put("sona comstar", "SONACOMS");
        m.put("rajratan global wire", "RATNAMANI");
        m.put("gabriel india", "GABRIEL");
        m.put("jbm auto", "JBMA");
        m.put("motherson sumi wiring", "MSUMI");
        m.put("samvardhana motherson international", "MOTHERSON");
        m.put("varroc engineering", "VARROC");
        m.put("happy forgings", "HAPPYFORG");
        m.put("rk forgings", "RKFORGE");
        m.put("ramkrishna forgings", "RKFORGE");
        m.put("setco automotive", "SETCO");
        m.put("sundram fasteners", "SUNDRMFAST");
        m.put("sundaram clayton", "SUNCLAYLTD");
        m.put("balkrishna paper", "BALKRISIND");
        m.put("ceat limited", "CEATLTD");
        m.put("indag rubber", "INDAGRUBBR");
        m.put("force motors limited", "FORCEMOT");
        m.put("vrl logistics", "VRLLOG");
        m.put("transport corporation of india", "TCI");
        m.put("allcargo logistics", "ALLCARGO");
        m.put("delhivery limited", "DELHIVERY");
        m.put("mahindra logistics", "MAHLOG");
        m.put("snowman logistics", "SNOWMAN");
        m.put("tci express", "TCIEXP");
        m.put("gateway distriparks", "GATEWAY");
        m.put("great eastern shipping", "GESHIP");
        m.put("shipping corporation of india", "SCI");
        m.put("essar shipping", "ESSARSHPNG");
        m.put("seamec limited", "SEAMECLTD");
        m.put("adani ports and special economic zone", "ADANIPORTS");
        m.put("jsw infrastructure", "JSWINFRA");
        m.put("gujarat pipavav port", "GPPL");
        m.put("v.r.l logistics", "VRLLOG");
        m.put("redington india", "REDINGTON");
        m.put("redington limited", "REDINGTON");
        m.put("aegis logistics", "AEGISLOG");
        m.put("aegis vopak terminals", "AEGISVOPAK");
        m.put("gulf petrochem", "AEGISVOPAK");
        m.put("apar industries ltd", "APARINDS");
        m.put("hindustan oil exploration", "HINDOILEXP");
        m.put("oil and gas commission", "ONGC");
        m.put("spandana sphoorty", "SPANDANA");
        m.put("manappuram finance limited", "MANAPPURAM");
        m.put("muthoot finance limited", "MUTHOOTFIN");
        m.put("l&t finance", "LTF");
        m.put("l&t finance holdings", "LTF");
        m.put("poonawalla fincorp limited", "POONAWALLA");
        m.put("piramal finance", "PIRAMALFIN");
        m.put("indostar capital", "INDOSTAR");
        m.put("manappuram home finance", "MANAPPURAM");
        m.put("repco home finance", "REPCO");
        m.put("jio financial services", "JIOFIN");
        m.put("jio financial", "JIOFIN");
        m.put("bajaj finance limited", "BAJFINANCE");
        m.put("bajaj finserv limited", "BAJAJFINSV");
        m.put("cholamandalam investment", "CHOLAFIN");
        m.put("cholamandalam financial holdings", "CHOLAHLDNG");
        m.put("shriram finance limited", "SHRIRAMFIN");
        m.put("sundaram finance limited", "SUNDARMFIN");
        m.put("can fin homes limited", "CANFINHOME");
        m.put("home first finance company", "HOMEFIRST");
        m.put("aadhar housing finance limited", "AADHARHFC");
        m.put("aptus value housing finance", "APTUS");
        m.put("muthoot microfin", "MUTHOOTMF");
        m.put("creditaccess grameen", "CREDITACC");
        m.put("spandana sphoorty financial", "SPANDANA");
        m.put("ujjivan financial services", "UJJIVANSFB");
        m.put("indostar capital finance", "INDOSTAR");
        m.put("cholamandalam ms general insurance", "CHOLAHLDNG");
        m.put("icici lombard general insurance", "ICICIGI");
        m.put("icici prudential life insurance", "ICICIPRULI");
        m.put("hdfc life insurance", "HDFCLIFE");
        m.put("sbi life insurance", "SBILIFE");
        m.put("life insurance corporation", "LICI");
        m.put("lic of india", "LICI");
        m.put("max financial services", "MFSL");
        m.put("max life insurance", "MFSL");
        m.put("general insurance corporation of india", "GICRE");
        m.put("the new india assurance", "NIACL");
        m.put("genus power infrastructures", "GENUSPOWER");
        m.put("kp energy", "KPEL");
        m.put("websol energy system", "WEBELSOLAR");
        m.put("kirloskar oil engines limited", "KIRLOSENG");
        m.put("apco infratech", "APCOTEXIND");
        m.put("apcotex industries", "APCOTEXIND");
        m.put("jain irrigation", "JAINREC");
        m.put("jain resource recycling", "JAINREC");
        m.put("praj industries limited", "PRAJIND");
        m.put("graphite india limited", "GRAPHITE");
        m.put("hindustan engineering", "HEG");
        m.put("hindustan engineering and graphite", "HEG");
        m.put("century enka", "CENTENKA");
        m.put("filatex india", "FILATEX");
        m.put("indo count industries", "ICIL");
        m.put("kpr mill limited", "KPRMILL");
        m.put("alok industries", "ALOKINDS");
        m.put("welspun india", "WELSPUNLIV");
        m.put("garware technical fibres", "GARFIBRES");
        m.put("himatsingka seide", "HIMATSEIDE");
        m.put("sutlej textiles", "SUTLEJTEX");
        m.put("rsl limited", "RSWM");
        m.put("nahar industrial", "NAHARINDUS");
        m.put("page industries ltd", "PAGEIND");
        m.put("arvind limited", "ARVIND");
        m.put("arvind fashions", "ARVINDFASN");
        m.put("usha martin limited", "USHAMART");
        m.put("man industries", "MANINDS");
        m.put("jain steel & power", "JAINSP");
        m.put("welspun specialty solutions", "WELSPECIA");
        m.put("rajesh exports limited", "RAJESHEXPO");
        m.put("titan eyeplus", "TITAN");
        m.put("kalyan jewellers india", "KALYANKJIL");
        m.put("senco gold limited", "SENCO");
        m.put("thangamayil jewellery", "THANGAMAYL");
        m.put("rajratan global", "RAJRATAN");
        m.put("vaibhav global limited", "VAIBHAVGBL");
        m.put("orient bell", "ORIENTBELL");
        m.put("nitco tiles", "NITCO");
        m.put("somany ceramics", "SOMANYCERA");
        m.put("kajaria ceramics limited", "KAJARIACER");
        m.put("hsil limited", "HSIL");
        m.put("cera sanitaryware limited", "CERA");
        m.put("allied blenders and distillers", "ABDL");
        m.put("allied blenders", "ABDL");
        m.put("aditya birla lifestyle brands", "ABLBL");
        m.put("nmdc steel", "NSLNISP");
        m.put("swan corp", "SWANCORP");
        m.put("swan energy", "SWANENERGY");
        m.put("aia engineering", "AIAENG");
        m.put("hitachi energy india", "POWERINDIA");
        m.put("hitachi energy", "POWERINDIA");
        m.put("amara raja energy", "ARE&M");
        m.put("amara raja batteries", "ARE&M");
        m.put("asahi india glass", "ASAHIINDIA");
        m.put("bombay burmah trading", "BBTC");
        m.put("bharti hexacom", "BHARTIHEXA");
        m.put("bls international", "BLS");
        m.put("blue jet healthcare", "BLUEJET");
        m.put("canara hsbc life insurance", "CANHLIFE");
        m.put("carborundum universal", "CARBORUNIV");
        m.put("capri global capital", "CGCL");
        m.put("choice international", "CHOICEIN");
        m.put("cie automotive india", "CIEINDIA");
        m.put("eclerx services", "ECLERX");
        m.put("eclerx", "ECLERX");
        m.put("elecon engineering", "ELECON");
        m.put("engineers india", "ENGINERSIN");
        m.put("eris lifesciences", "ERIS");
        m.put("firstsource solutions", "FSL");
        m.put("gallantt ispat", "GALLANTT");
        m.put("gallantt metal", "GALLANTT");
        m.put("glaxosmithkline pharmaceuticals", "GLAXO");
        m.put("gsk pharma", "GLAXO");
        m.put("gujarat mineral development", "GMDCLTD");
        m.put("godawari power and ispat", "GPIL");
        m.put("hfcl limited", "HFCL");
        m.put("international gemmological institute", "IGIL");
        m.put("india cements", "INDIACEM");
        m.put("iol chemicals", "IOLCP");
        m.put("icici securities", "ISEC");
        m.put("iti limited", "ITI");
        m.put("indian telephone industries", "ITI");
        m.put("jubilant ingrevia", "JUBLINGREA");
        m.put("linde india", "LINDEINDIA");
        m.put("lloyds metals and energy", "LLOYDSME");
        m.put("lt foods", "LTFOODS");
        m.put("daawat rice", "LTFOODS");
        m.put("minda corporation", "MINDACORP");
        m.put("mmtc limited", "MMTC");
        m.put("moil limited", "MOIL");
        m.put("manganese ore india", "MOIL");
        m.put("neuland laboratories", "NEULANDLAB");
        m.put("onesource specialty pharma", "ONESOURCE");
        m.put("pcbl limited", "PCBL");
        m.put("phillips carbon black", "PCBL");
        m.put("pg electroplast", "PGEL");
        m.put("piramal pharma", "PPLPHARMA");
        m.put("rhi magnesita india", "RHIM");
        m.put("sequent scientific", "SEQUENT");
        m.put("supreme petrochem", "SPLPETRO");
        m.put("transformers and rectifiers", "TARIL");
        m.put("tata chemicals", "TATACHEM");
        m.put("tata communications", "TATACOMM");
        m.put("tbo tek", "TBOTEK");
        m.put("techno electric", "TECHNOE");
        m.put("tega industries", "TEGA");
        m.put("tejas networks", "TEJASNET");
        m.put("tube investments of india", "TIINDIA");
        m.put("tata teleservices maharashtra", "TTML");
        m.put("zf commercial vehicle control systems", "ZFCVINDIA");
        m.put("piramal enterprises", "PIRAMALFIN");
        // -- Cross-checked against updated Zerodha-verified symbol list --------
        m.put("biocon", "BIOCON");
        m.put("biocon limited", "BIOCON");
        m.put("ccl products", "CCL");
        m.put("siemens energy india", "ENRIN");
        m.put("siemens energy", "ENRIN");
        m.put("ptc industries", "PTCIL");
        m.put("leela palaces", "THELEELA");
        m.put("the leela", "THELEELA");
        m.put("leela hotels", "THELEELA");
        m.put("aditya birla capital", "ABCAPITAL");
        m.put("aditya birla capital limited", "ABCAPITAL");
        m.put("aditya birla real estate", "ABREL");
        m.put("action construction equipment", "ACE");
        m.put("acutaas chemicals", "ACUTAAS");
        m.put("afcons infrastructure", "AFCONS");
        m.put("hdb financial services", "HDBFS");
        m.put("honeywell automation india", "HONAUT");
        m.put("honeywell automation", "HONAUT");
        m.put("nava limited", "NAVA");
        m.put("nava bharat ventures", "NAVA");
        m.put("anthem biosciences", "ANTHEM");
        COMPANY_NAME_MAP = Collections.unmodifiableMap(m);
    }

    /**
     * High-liquidity symbol universe - 532 Zerodha-verified, actively
     * traded Nifty500-class stocks. Used as an ADDITIONAL gate inside
     * extractSymbols() below - on top of the existing knownSymbols
     * parameter (the live NSE instrument cache, passed in by
     * NewsIngestionService and unchanged), a symbol must ALSO be in this
     * curated list to be accepted. This narrows matching to genuinely
     * worth-trading names without weakening the existing knownSymbols
     * check - a delisted/suspended symbol is still correctly excluded
     * even if present in this list.
     *
     * Built via LinkedHashSet + Collections.unmodifiableSet rather than
     * Set.of(...) - Set.of() throws IllegalArgumentException at startup
     * if the literal list contains even one duplicate; this construction
     * is duplicate-tolerant by design, so a future edit to this list
     * can never crash the application.
     */
    private static final Set<String> HIGH_LIQUIDITY_SYMBOLS;
    static {
        Set<String> s = new LinkedHashSet<>(Arrays.asList(
                "RELIANCE", "TCS", "HDFCBANK", "INFY", "ICICIBANK", "HINDUNILVR", "ITC", "SBIN",
                "BAJFINANCE", "BHARTIARTL", "KOTAKBANK", "LT", "AXISBANK", "ASIANPAINT", "MARUTI", "NESTLEIND",
                "TITAN", "SUNPHARMA", "ULTRACEMCO", "WIPRO", "HCLTECH", "TECHM", "POWERGRID", "NTPC",
                "ONGC", "COALINDIA", "TATASTEEL", "JSWSTEEL", "HINDALCO", "GRASIM", "BAJAJ-AUTO", "EICHERMOT",
                "HEROMOTOCO", "DRREDDY", "CIPLA", "DIVISLAB", "APOLLOHOSP", "ADANIPORTS", "ADANIGREEN", "ADANIENT",
                "ATGL", "BAJAJFINSV", "HDFCLIFE", "SBILIFE", "ICICIPRULI", "MUTHOOTFIN", "CHOLAFIN", "PFC",
                "RECLTD", "IRCTC", "INDIGO", "TATACONSUM", "TMCV", "DABUR", "GODREJCP", "PIDILITIND",
                "BERGEPAINT", "MARICO", "COLPAL", "EMAMILTD", "BRITANNIA", "TATAPOWER", "TRENT", "VEDL",
                "NATIONALUM", "NMDC", "SAIL", "BHEL", "SIEMENS", "ABB", "HAVELLS", "VOLTAS",
                "BLUESTARCO", "WHIRLPOOL", "BOSCHLTD", "CUMMINSIND", "THERMAX", "IRB", "PRESTIGE", "GODREJPROP",
                "OBEROIRLTY", "DLF", "PHOENIXLTD", "LTM", "MPHASIS", "PERSISTENT", "COFORGE", "LTTS",
                "KPITTECH", "TATAELXSI", "OFSS", "ETERNAL", "PAYTM", "POLICYBZR", "NYKAA", "DELHIVERY",
                "CARTRADE", "SAPPHIRE", "BANKINDIA", "CANBK", "INDIANB", "UNIONBANK", "PNB", "FEDERALBNK",
                "IDFCFIRSTB", "BANDHANBNK", "RBLBANK", "YESBANK", "KARURVYSYA", "CREDITACC", "MRF", "APOLLOTYRE",
                "CEATLTD", "BALKRISIND", "TVSMOTOR", "MOTHERSON", "BHARATFORG", "ENDURANCE", "UNOMINDA", "ESCORTS",
                "PAGEIND", "DMART", "JUBLFOOD", "DEVYANI", "ZYDUSLIFE", "ALKEM", "IPCALAB", "NATCOPHARM",
                "GRANULES", "LAURUSLABS", "AUROPHARMA", "TORNTPHARM", "AJANTPHARM", "GLAND", "GLAXO", "PFIZER",
                "ABBOTINDIA", "NEULANDLAB", "TATACHEM", "DEEPAKNTR", "AAVAS", "APTUS", "HOMEFIRST", "ICICIGI",
                "STARHEALTH", "NIACL", "GICRE", "NEWGEN", "MFSL", "IIFL", "ANGELONE", "MOTILALOFS",
                "MCX", "BSE", "CDSL", "CAMS", "KFINTECH", "IRFC", "RVNL", "HUDCO",
                "NBCC", "NHPC", "SJVN", "OIL", "MGL", "IGL", "GAIL", "PETRONET",
                "SUPREMEIND", "ASTRAL", "FINCABLES", "ACC", "AMBUJACEM", "JKCEMENT", "RAMCOCEM", "JINDALSAW",
                "WELCORP", "KALYANKJIL", "ZEEL", "PVRINOX", "INOXWIND", "IREDA", "TORNTPOWER", "CESC",
                "JPPOWER", "ADANIPOWER", "POLYCAB", "KEI", "BATAINDIA", "ASTERDM", "MAXHEALTH", "FORTIS",
                "NH", "KIMS", "RAINBOW", "MAZDOCK", "GRSE", "BEL", "HAL", "BEML",
                "CYIENT", "ZENSARTECH", "HEXT", "BSOFT", "INTELLECT", "CONCOR", "BLUEDART", "360ONE",
                "ABCAPITAL", "AMBER", "ANANTRAJ", "APLAPOLLO", "ARE&M", "ASHOKLEY", "AUBANK", "BAJAJHFL",
                "BALRAMCHIN", "BANKBARODA", "CANFINHOME", "CGPOWER", "CHAMBLFERT", "CHOLAHLDNG", "CLEAN", "COROMANDEL",
                "CROMPTON", "DALBHARAT", "DIXON", "EIDPARRY", "EXIDEIND", "FACT", "FIVESTAR", "FLUOROCHEM",
                "FORCEMOT", "GMRAIRPORT", "GODREJIND", "HDFCAMC", "HINDCOPPER", "HINDPETRO", "HINDZINC", "HYUNDAI",
                "IDBI", "IDEA", "INDHOTEL", "INDUSINDBK", "IOB", "IOC", "JBCHEPHARM", "JSWENERGY",
                "JSWINFRA", "JUBLINGREA", "KAJARIACER", "KAYNES", "KEC", "KPIL", "LALPATHLAB", "LICI",
                "LINDEINDIA", "LLOYDSME", "LODHA", "LTF", "LUPIN", "M&M", "M&MFIN", "MAHABANK",
                "MANKIND", "MRPL", "NAM-INDIA", "NAUKRI", "NLCINDIA", "NSLNISP", "NTPCGREEN", "NUVAMA",
                "OLECTRA", "PATANJALI", "PNBHOUSING", "POLYMED", "PPLPHARMA", "PREMIERENE", "RADICO", "REDINGTON",
                "RKFORGE", "RPOWER", "SBFC", "SBICARD", "SCHAEFFLER", "SHREECEM", "SHYAMMETL", "SOLARINDS",
                "SONACOMS", "SUMICHEM", "SUNDARMFIN", "SUZLON", "SYNGENE", "TATACOMM", "TATATECH", "TIINDIA",
                "TITAGARH", "TRIDENT", "UBL", "UCOBANK", "UNITDSPR", "UPL", "USHAMART", "UTIAMC",
                "VBL", "VIJAYA", "WAAREEENER", "WELSPUNLIV", "WOCKPHARMA", "ZENTEC", "ZFCVINDIA", "5PAISA",
                "CAMPUS", "CENTURYTEX", "DISHTV", "EQUITASBNK", "ESAFSFB", "GATEWAY", "GEOJITFSL", "GPPL",
                "GREENPANEL", "GSPL", "HATHWAY", "HEIDELBERG", "IOLCP", "ISEC", "KSOLVES", "METROBRAND",
                "MOIL", "NETWORK18", "PARAS", "PCJEWELLER", "PRAJIND", "PRINCEPIPE", "RAJESHEXPO", "RATEGAIN",
                "RATNAMANI", "RAYMOND", "RELAXO", "SANOFI", "SENCO", "SEQUENT", "SHOPERSTOP", "SOUTHBANK",
                "SUNDRMFAST", "SURYODAY", "SUVENPHARM", "TRIVENI", "UJJIVANSFB", "VMART", "WESTLIFE", "AEGISLOG",
                "BIRLACORP", "VMM", "MTARTECH", "TBZ", "3MINDIA", "AADHARHFC", "AARTIIND", "ABDL",
                "ABFRL", "ABLBL", "ABREL", "ABSLAMC", "ACE", "ACMESOLAR", "ACUTAAS", "AEGISVOPAK",
                "AFCONS", "AFFLE", "AIAENG", "AIIL", "ANANDRATHI", "ANTHEM", "ANURAS", "APARINDS",
                "ASAHIINDIA", "ATHERENERG", "ATUL", "AWL", "BAJAJHLDNG", "BAYERCROP", "BBTC", "BDL",
                "BELRISE", "BHARTIHEXA", "BIKAJI", "BLS", "BLUEJET", "BRIGADE", "CANHLIFE", "CAPLIPOINT",
                "CARBORUNIV", "CASTROLIND", "CEMPRO", "CENTRALBK", "CGCL", "CHALET", "CHENNPETRO", "CHOICEIN",
                "CIEINDIA", "COHANCE", "CONCORDBIO", "CPPLUS", "CRAFTSMAN", "CRISIL", "CUB", "DATAPATTNS",
                "DCMSHRIRAM", "DEEPAKFERT", "DOMS", "ECLERX", "EIHOTEL", "ELECON", "ELGIEQUIP", "EMCURE",
                "EMMVEE", "ENGINERSIN", "ERIS", "FIRSTCRY", "FSL", "GABRIEL", "GALLANTT", "GESHIP",
                "GILLETTE", "GLENMARK", "GMDCLTD", "GODFRYPHLP", "GODIGIT", "GPIL", "GRAPHITE", "GRAVITA",
                "GROWW", "GVT&D", "HBLENGINE", "HDBFS", "HEG", "HFCL", "HONASA", "HONAUT",
                "HSCL", "ICICIAMC", "IEX", "IFCI", "IGIL", "IKS", "INDGN", "INDIACEM",
                "INDIAMART", "IRCON", "ITCHOTELS", "ITI", "J&KBANK", "JAINREC", "JBMA", "JINDALSTEL",
                "JIOFIN", "JKTYRE", "JMFINANCIL", "JSL", "JSWCEMENT", "JSWDULUX", "JUBLPHARMA", "JWL",
                "JYOTICNC", "KIRLOSENG", "KPRMILL", "LATENTVIEW", "LGEINDIA", "LICHSGFIN", "LTFOODS", "MANAPPURAM",
                "MAPMYINDIA", "MEDANTA", "MEESHO", "MINDACORP", "MMTC", "MSUMI", "NAVA", "NAVINFLUOR",
                "NCC", "NETWEB", "NIVABUPA", "NUVOCO", "OLAELEC", "ONESOURCE", "PARADEEP", "PCBL",
                "PGEL", "PIIND", "PINELABS", "PIRAMALFIN", "POONAWALLA", "POWERINDIA", "PWL", "RAILTEL",
                "RHIM", "RITES", "RRKABEL", "SAGILITY", "SAILIFE", "SAMMAANCAP", "SARDAEN", "SAREGAMA",
                "SCHNEIDER", "SCI", "SHRIRAMFIN", "SIGNATURE", "SOBHA", "SONATSOFTW", "SPLPETRO", "SRF",
                "SUNTV", "SWANCORP", "SWIGGY", "SYRMA", "TARIL", "TATACAP", "TATAINVEST", "TBOTEK",
                "TECHNOE", "TEGA", "TEJASNET", "TENNIND", "TIMKEN", "TMPV", "TRAVELFOOD", "TRITURBINE",
                "TTML", "URBANCO", "VTL", "ZYDUSWELL",
                "ADANIENSOL", "BIOCON", "BPCL", "CCL", "COCHINSHIP", "ENRIN", "INDUSTOWER",
                "LEMONTREE", "LENSKART", "PTCIL", "THELEELA", "TATAMOTORS"
        ));
        HIGH_LIQUIDITY_SYMBOLS = Collections.unmodifiableSet(s);
    }

    // -- Public API ------------------------------------------------------------

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
                        // Added for Reuters/RBI sources - FED, crude oil, dollar
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
     * Compute keyword impact weight (0-100) based on tier and count.
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
     *
     * NOTE: every match in all 3 strategies below now also requires
     * HIGH_LIQUIDITY_SYMBOLS.contains(...) - an additional gate on top of
     * the existing knownSymbols check, narrowing matches to genuinely
     * worth-trading names. knownSymbols itself (passed in by
     * NewsIngestionService, sourced from the live NSE instrument cache)
     * is completely unchanged.
     */
    public List<String> extractSymbols(String headline, String description,
                                       Set<String> knownSymbols) {
        String combined = headline + " " + description;

        // FIX (found via dashboard report: MRPL's routine board-meeting
        // filing notice was showing up as a "BSE" trade signal instead of
        // "MRPL"). Confirmed real, not a data problem: BSE Limited (the
        // exchange operator) is ITSELF a genuine, valid NSE-listed stock
        // trading under ticker "BSE" - so when a completely unrelated
        // company's routine filing says "...has informed BSE that the
        // meeting of the Board...", the word "BSE" there refers to which
        // EXCHANGE was notified, not to BSE Limited's own stock, but the
        // extractor was matching the literal word regardless of context.
        // Covers the common real boilerplate variants seen in actual NSE/
        // BSE corporate filings (informed/notified/reported to, past and
        // present tense, "the Company" prefix optional) so this closes
        // the actual bug rather than one specific wording. Genuine BSE
        // Limited news (e.g. "BSE Limited reported record trading
        // volumes") is unaffected - only these specific filing-notice
        // phrasings are stripped, not every mention of the word.
        combined = combined.replaceAll(
                "(?i)\\b(has|have)\\s+(informed|notified)\\s+(BSE|NSE)\\s+that\\b",
                "$1 $2 the exchange that");
        combined = combined.replaceAll(
                "(?i)\\b(reported|filed)\\s+(to|with)\\s+(BSE|NSE)\\b",
                "$1 $2 the exchange");
        // FIX (found via dashboard report, second confirmed variant):
        // "BSE Intimation is hereby given under the regulation 30..." -
        // a genuinely different boilerplate OPENING than the two above
        // (those match BSE/NSE appearing mid-sentence; this one has
        // BSE/NSE as the very first word of a standard regulatory
        // filing preamble). Real example: a conductor/cable
        // manufacturer's "commencement of production" announcement was
        // being attributed to BSE Limited's own stock, purely because
        // the filing's standard opening happens to start with the
        // exchange's name.
        combined = combined.replaceAll(
                "(?i)\\b(BSE|NSE)\\s+Intimation\\s+is\\s+hereby\\s+given\\b",
                "The exchange intimation is hereby given");

        Set<String> found = new LinkedHashSet<>();

        // Strategy 1: Look for known symbols directly
        String upper = combined.toUpperCase();
        for (String sym : knownSymbols) {
            if (!HIGH_LIQUIDITY_SYMBOLS.contains(sym)) continue;
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
                    knownSymbols.contains(candidate) &&
                    HIGH_LIQUIDITY_SYMBOLS.contains(candidate)) {
                found.add(candidate);
            }
        }

        // Strategy 3: Company name -> NSE symbol mapping table
        // Handles "HDFC Bank" -> "HDFCBANK", "Infosys" -> "INFY" etc.
        // BSE/RSS headlines use company names, not NSE tickers.
        String lowerCombined = combined.toLowerCase();
        for (Map.Entry<String, String> entry : COMPANY_NAME_MAP.entrySet()) {
            if (lowerCombined.contains(entry.getKey())) {
                String mappedSymbol = entry.getValue();
                if (knownSymbols.contains(mappedSymbol) &&
                        HIGH_LIQUIDITY_SYMBOLS.contains(mappedSymbol)) {
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

    // -- Private helpers --------------------------------------------------------

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