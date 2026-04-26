package com.trading.strategy.news;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
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

    // ── Tier 2: High-impact macro/policy keywords (weight 70–89) ─────────────
    private static final Set<String> TIER2_RBI = Set.of(
            "rbi", "repo rate", "reverse repo", "crr", "slr", "monetary policy",
            "mpc", "rbi governor", "rate cut", "rate hike", "liquidity",
            "inflation target", "rbi announcement", "rbi decision"
    );

    private static final Set<String> TIER2_MACRO = Set.of(
            "gdp", "cpi", "inflation", "iip", "trade deficit", "current account",
            "fiscal deficit", "budget", "gst", "tax", "disinvestment", "fii",
            "dii", "foreign investment", "fpi", "government policy", "pli scheme",
            "production linked", "import duty", "export ban", "msme"
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
            "doubles", "triples", "exceeds", "milestone", "breakthrough", "recovery"
    );

    private static final Set<String> NEGATIVE_WORDS = Set.of(
            "fall", "drop", "decline", "loss", "miss", "weak", "cut", "reduce",
            "warning", "downgrade", "sell", "bearish", "negative", "concern",
            "risk", "problem", "issue", "delay", "penalty", "fine", "fraud",
            "scam", "recall", "ban", "halt", "suspend", "withdraw", "disappoint",
            "widens", "narrows to loss", "slump", "crash", "plunge", "probe"
    );

    // NSE symbol pattern — 2-10 uppercase letters (common NSE symbol format)
    private static final Pattern SYMBOL_PATTERN = Pattern.compile("\\b([A-Z]{2,10})\\b");

    // Common false-positive words that look like symbols but aren't
    private static final Set<String> NON_SYMBOLS = Set.of(
            "THE", "AND", "FOR", "NSE", "BSE", "RBI", "SEBI", "IRDAI", "GST",
            "CEO", "CFO", "MD", "AGM", "EGM", "IPO", "FPO", "QIP", "OFS",
            "EBITDA", "PAT", "PBT", "EPS", "PE", "PB", "ROE", "ROA",
            "Q1", "Q2", "Q3", "Q4", "FY", "FY24", "FY25", "FY26", "H1", "H2",
            "YOY", "QOQ", "MOM", "USD", "INR", "EUR", "GBP", "JPY",
            "GDP", "CPI", "IIP", "PMI", "FII", "DII", "FPI", "NPA",
            "NBFC", "MFI", "RERA", "PLI", "MSP", "MSCI",
            "US", "UK", "EU", "IT", "AI", "ML"
    );

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