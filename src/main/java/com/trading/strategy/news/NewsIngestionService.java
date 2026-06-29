package com.trading.strategy.news;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.marketdata.service.InstrumentCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * NewsIngestionService — fetches real-time Indian market news from CONFIRMED WORKING sources.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * ALL SOURCES ARE FREE — NO API KEY REQUIRED — NO BOT DETECTION ISSUES.
 *
 * All 5 sources below are confirmed working from your Windows machine logs.
 * Sources that were removed and why:
 *   NSE API          → HTTP 404 (URL never worked, BSE covers same data)
 *   Zee Business RSS → HTTP 403 (permanent bot detection, not fixable with headers)
 *   Moneycontrol Markets RSS → HTTP 503 (rate-limited/banned, persistent failure)
 *   GNews API        → HTTP 403 (requires API key — 403 without key, always)
 *
 * ── ACTIVE SOURCES ────────────────────────────────────────────────────────────
 *
 * SOURCE 1 — BSE Corporate Announcements    [CONFIRMED ✅ +6 in logs]
 *   URL:  https://api.bseindia.com/BseIndiaAPI/api/AnnSubCategoryGetData/w
 *   What: ALL corporate filings — earnings, dividends, board meetings, M&A.
 *   Poll: Every 3 minutes.
 *
 * SOURCE 2 — BSE Results Filter             [CONFIRMED ✅ same API, results-only]
 *   URL:  https://api.bseindia.com/BseIndiaAPI/api/AnnSubCategoryGetData/w?strCat=Result
 *   What: ONLY quarterly results / earnings announcements. Highest signal quality.
 *   Poll: Every 3 minutes (offset 90s from SOURCE 1).
 *
 * SOURCE 3 — ET Markets RSS                 [CONFIRMED ✅ no errors in logs]
 *   URL:  https://economictimes.indiatimes.com/markets/rss.cms
 *   What: Indian market news, sector trends, macro events.
 *   Poll: Every 5 minutes.
 *
 * SOURCE 4 — Moneycontrol Business RSS      [CONFIRMED ✅ +6 in logs]
 *   URL:  https://www.moneycontrol.com/rss/business.xml
 *   What: Corporate news, M&A, government policy, macro economy.
 *   Poll: Every 7 minutes.
 *
 * SOURCE 5 — Hindu Business Line RSS        [CONFIRMED ✅ no errors in logs]
 *   URL:  https://www.thehindubusinessline.com/feeder/default.rss
 *   What: Indian business, economy, government policy.
 *   Poll: Every 10 minutes.
 * ─────────────────────────────────────────────────────────────────────────────
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class NewsIngestionService {

    private final NewsKeywordFilter        filter;
    private final InstrumentCacheService   instrumentCache;
    private final ObjectMapper             objectMapper;

    // ── Config ────────────────────────────────────────────────────────────────
    @Value("${strategy.news.enabled:true}")
    private boolean enabled;

    // ── State ─────────────────────────────────────────────────────────────────
    private final List<NewsItem>       activeItems = new CopyOnWriteArrayList<>();
    private final Map<String, Boolean> seenIds     = new ConcurrentHashMap<>();
    private volatile Set<String>       knownSymbols = Collections.emptySet();

    /** Consecutive failure counter per source — for health monitoring */
    private final Map<String, Integer> sourceFailures    = new ConcurrentHashMap<>();
    /** Per-source backoff epoch (ms). After 3 consecutive failures, skip until this time. */
    private final Map<String, Long>    sourceBackoffUntil = new ConcurrentHashMap<>();

    // HTTP/1.1 forced: Cloudflare on Railway datacenter IPs sometimes rejects HTTP/2.
    // HTTP/1.1 is what a browser uses for simple RSS navigation.
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    // ══════════════════════════════════════════════════════════════════════════
    // SCHEDULED FETCHERS — ONLY CONFIRMED WORKING SOURCES
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * SOURCE 1: BSE Corporate Announcements — every 3 minutes.
     * All categories: earnings, dividends, board meetings, M&A, buybacks.
     */
    @Scheduled(fixedRate = 180_000)
    public void fetchFromBse() {
        if (!enabled) return;
        refreshSymbolCache();
        try {
            int added = fetchBseAnnouncements(
                    "https://api.bseindia.com/BseIndiaAPI/api/AnnSubCategoryGetData/w"
                            + "?pageno=1&strCat=-1&strPrevDate=&strScrip=&strSearch=P"
                            + "&strToDate=&strType=C&subcategory=-1",
                    "BSE_ALL"
            );
            if (added > 0) log.info("[NEWS-INGEST] BSE: +{} announcements", added);
            sourceFailures.remove("BSE_ALL");
        } catch (Exception e) {
            int f = sourceFailures.merge("BSE_ALL", 1, Integer::sum);
            log.warn("[NEWS-INGEST] BSE fetch failed ({}x): {}", f, e.getMessage());
        }
    }

    /**
     * SOURCE 2: BSE Results filter — every 3 minutes, offset 90s.
     * ONLY quarterly/annual results. Highest signal quality for news trading.
     */
    @Scheduled(fixedRate = 180_000, initialDelay = 90_000)
    public void fetchFromBseResults() {
        if (!enabled) return;
        refreshSymbolCache();
        try {
            int added = fetchBseAnnouncements(
                    "https://api.bseindia.com/BseIndiaAPI/api/AnnSubCategoryGetData/w"
                            + "?pageno=1&strCat=Result&strPrevDate=&strScrip=&strSearch=P"
                            + "&strToDate=&strType=C&subcategory=-1",
                    "BSE_RESULTS"
            );
            if (added > 0) log.info("[NEWS-INGEST] BSE Results: +{} earnings announcements", added);
            sourceFailures.remove("BSE_RESULTS");
        } catch (Exception e) {
            int f = sourceFailures.merge("BSE_RESULTS", 1, Integer::sum);
            log.warn("[NEWS-INGEST] BSE Results fetch failed ({}x): {}", f, e.getMessage());
        }
    }

    /**
     * SOURCE 3: ET Markets RSS — every 5 minutes.
     */
    @Scheduled(fixedRate = 300_000)
    public void fetchFromEtMarkets() {
        if (!enabled) return;
        refreshSymbolCache();
        try {
            int added = fetchRss(
                    "https://economictimes.indiatimes.com/markets/rss.cms",
                    "RSS_ET");
            if (added > 0) log.info("[NEWS-INGEST] ET Markets RSS: +{} articles", added);
            sourceFailures.remove("RSS_ET");
        } catch (Exception e) {
            int f = sourceFailures.merge("RSS_ET", 1, Integer::sum);
            log.warn("[NEWS-INGEST] ET Markets RSS failed ({}x): {}", f, e.getMessage());
        }
    }

    /**
     * SOURCE 4: Moneycontrol Business RSS — every 7 minutes.
     *
     * 403 HANDLING:
     *   Moneycontrol uses Cloudflare. 403 is a bot-challenge, not a permanent ban.
     *   It is most common at off-hours (midnight) when Cloudflare is stricter.
     *   Strategy:
     *     - 1st failure: log WARN, retry normally next cycle (7 min)
     *     - 2nd failure: log WARN, still retry
     *     - 3rd+ consecutive failure: log DEBUG only (silence spam), backoff 30 min
     *   sourceFailures counter is cleared on any success.
     *   sourceBackoffUntil tracks when to resume after 3+ failures.
     */
    @Scheduled(fixedRate = 420_000)
    public void fetchFromMoneycontrolBusiness() {
        if (!enabled) return;
        refreshSymbolCache();

        // Backoff: after 3 consecutive 403s, skip until backoff window expires
        Long backoffUntil = sourceBackoffUntil.get("RSS_MC_BIZ");
        if (backoffUntil != null && System.currentTimeMillis() < backoffUntil) {
            log.debug("[NEWS-INGEST] Moneycontrol Business RSS skipped (backoff until {})", backoffUntil);
            return;
        }

        try {
            int added = fetchRss(
                    "https://www.moneycontrol.com/rss/business.xml",
                    "RSS_MC_BIZ");
            if (added > 0) log.info("[NEWS-INGEST] Moneycontrol Business RSS: +{} articles", added);
            sourceFailures.remove("RSS_MC_BIZ");
            sourceBackoffUntil.remove("RSS_MC_BIZ");
        } catch (Exception e) {
            int f = sourceFailures.merge("RSS_MC_BIZ", 1, Integer::sum);
            if (f <= 2) {
                log.warn("[NEWS-INGEST] Moneycontrol Business RSS failed ({}x): {}", f, e.getMessage());
            } else {
                // 3+ failures: activate 30-min backoff, reduce log noise
                long backoff = System.currentTimeMillis() + 30 * 60 * 1000L;
                sourceBackoffUntil.put("RSS_MC_BIZ", backoff);
                log.warn("[NEWS-INGEST] Moneycontrol Business RSS failed ({}x) — backing off 30min: {}",
                        f, e.getMessage());
            }
        }
    }

    /**
     * SOURCE 5: Hindu Business Line RSS — every 10 minutes.
     */
    @Scheduled(fixedRate = 600_000)
    public void fetchFromHinduBL() {
        if (!enabled) return;
        refreshSymbolCache();
        try {
            int added = fetchRss(
                    "https://www.thehindubusinessline.com/feeder/default.rss",
                    "RSS_HBL");
            if (added > 0) log.info("[NEWS-INGEST] Hindu Business Line RSS: +{} articles", added);
            sourceFailures.remove("RSS_HBL");
        } catch (Exception e) {
            int f = sourceFailures.merge("RSS_HBL", 1, Integer::sum);
            log.warn("[NEWS-INGEST] Hindu Business Line RSS failed ({}x): {}", f, e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // GLOBAL NEWS SOURCES (6, 7, 8)
    // Added without touching any existing logic.
    // These feed the GLOBAL_EVENT category in NewsScoreEngine.
    // Scoring, trading, dashboard — all unchanged.
    // Source weight: RSS_REUTERS = 11 (between NewsAPI=12 and ET=10)
    //
    // Why these matter for NSE:
    //   Reuters Business → FED decisions, crude oil, US CPI, geopolitical events
    //   Reuters Top News → Major global events that move FII flows into India
    //   RBI Official RSS → Direct RBI policy announcements from source
    //                       (currently only picked up 30-60 min late via ET/MC)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * SOURCE 6: ET Economy Policy RSS — every 10 minutes.
     * Replaces feeds.reuters.com/reuters/businessNews (domain dead since 2020).
     * Covers: FED impact on India, crude oil, RBI policy context,
     *         US inflation, global macro affecting Indian markets.
     * Same GLOBAL_EVENT and ECONOMIC_DATA category mapping.
     */
    @Scheduled(fixedRate = 600_000, initialDelay = 45_000)
    public void fetchFromReutersBusiness() {
        if (!enabled) return;
        refreshSymbolCache();
        try {
            int added = fetchRss(
                    "https://economictimes.indiatimes.com/news/economy/policy/rss.cms",
                    "RSS_ET_ECO");
            if (added > 0) log.info("[NEWS-INGEST] ET Economy Policy RSS: +{} articles", added);
            sourceFailures.remove("RSS_REUTERS_BIZ");
        } catch (Exception e) {
            int f = sourceFailures.merge("RSS_REUTERS_BIZ", 1, Integer::sum);
            log.warn("[NEWS-INGEST] ET Economy Policy RSS failed ({}x): {}", f, e.getMessage());
        }
    }

    /**
     * SOURCE 7: ET Economy Indicators RSS — every 15 minutes.
     * Replaces feeds.reuters.com/reuters/topNews (domain dead since 2020).
     * Covers: GDP, inflation, IIP, trade data, global commodity prices,
     *         FII flows, rupee movement — all global macro affecting NSE.
     */
    @Scheduled(fixedRate = 900_000, initialDelay = 75_000)
    public void fetchFromReutersTopNews() {
        if (!enabled) return;
        refreshSymbolCache();
        try {
            int added = fetchRss(
                    "https://economictimes.indiatimes.com/news/economy/indicators/rss.cms",
                    "RSS_ET_IND");
            if (added > 0) log.info("[NEWS-INGEST] ET Economy Indicators RSS: +{} articles", added);
            sourceFailures.remove("RSS_REUTERS_TOP");
        } catch (Exception e) {
            int f = sourceFailures.merge("RSS_REUTERS_TOP", 1, Integer::sum);
            log.warn("[NEWS-INGEST] ET Economy Indicators RSS failed ({}x): {}", f, e.getMessage());
        }
    }

    /**
     * SOURCE 8: RBI Official RSS — every 15 minutes.
     * Covers: Rate decisions, policy announcements, liquidity measures.
     * Currently RBI news reaches the strategy 30-60 min late via ET/MC.
     * This fetches directly from the source → faster signal.
     * Maps to RBI_POLICY category (score 85 × 0.30 = 26/30 category pts).
     */
    @Scheduled(fixedRate = 900_000, initialDelay = 105_000)
    public void fetchFromRbiOfficial() {
        if (!enabled) return;
        refreshSymbolCache();
        try {
            // RBI RSS returns HTTP 302 redirect — use getWithRedirect() not fetchRss()
            // fetchRss() uses get() which throws on non-200 — this is RBI-specific only
            String body = getWithRedirect(
                    "https://www.rbi.org.in/RSS/RBIRSSContent.aspx?Id=316",
                    "User-Agent",  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Accept",      "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Referer",     "https://www.rbi.org.in/"
            );
            if (body.isBlank()) return;

            List<String[]> items = parseRssItems(body);
            int added = 0;
            for (String[] item : items) {
                String headline    = item[0];
                String description = item[1];
                String link        = item[2];
                String pubDate     = item[3];
                if (headline.isBlank()) continue;
                if (!filter.isRelevant(headline, description)) continue;
                String id = "RSS_RBI:" + (headline + pubDate).hashCode();
                if (seenIds.putIfAbsent(id, true) != null) continue;
                Instant published = parseRssDate(pubDate);
                NewsItem newsItem = buildItem(id, headline, description, link, "RSS_RBI", published);
                if (newsItem != null) { activeItems.add(newsItem); added++; }
            }
            if (added > 0) log.info("[NEWS-INGEST] RBI Official RSS: +{} announcements", added);
            sourceFailures.remove("RSS_RBI");
        } catch (Exception e) {
            int f = sourceFailures.merge("RSS_RBI", 1, Integer::sum);
            log.warn("[NEWS-INGEST] RBI Official RSS failed ({}x): {}", f, e.getMessage());
        }
    }

    /**
     * Purge articles older than 2 hours every 15 minutes.
     * Exception: BSE/NSE official filings from weekend preserved on Monday
     * so Monday market-open logic in NewsItem.ageMinutes() can process them.
     */
    @Scheduled(fixedRate = 900_000)
    public void purgeStaleItems() {
        Instant cutoff = Instant.now().minusSeconds(7_200);
        int before = activeItems.size();
        // FIX: was item.isActionableMonday() — that method now falls back to
        // isActionable() which always returns true (60-min cutoff removed for
        // scoring purposes), which would have made this purge exempt EVERY
        // item EVERY day, leaking memory. isMondayWeekendException() is a
        // narrow, dedicated check that only protects genuine Monday weekend
        // BSE/NSE filings — everything else still purges normally after 2h.
        activeItems.removeIf(item -> {
            if (item.isMondayWeekendException()) return false;
            return item.publishedAt().isBefore(cutoff);
        });
        int purged = before - activeItems.size();
        if (purged > 0) log.debug("[NEWS-INGEST] Purged {} stale items", purged);
    }

    /** Daily reset at 9:00 AM — clean slate for the new trading day */
    @Scheduled(cron = "0 0 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyReset() {
        activeItems.clear();
        seenIds.clear();
        sourceFailures.clear();
        log.info("[NEWS-INGEST] Daily reset complete — all sources cleared");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ══════════════════════════════════════════════════════════════════════════

    /** All active actionable news items — market-aware check for Monday weekend news */
    public List<NewsItem> getActiveItems() {
        return activeItems.stream()
                .filter(item -> item.isActionable() || item.isActionableMonday())
                .toList();
    }

    /** News items for a specific symbol */
    public List<NewsItem> getItemsForSymbol(String symbol) {
        return activeItems.stream()
                .filter(i -> i.mentionedSymbols().contains(symbol))
                .filter(item -> item.isActionable() || item.isActionableMonday())
                .toList();
    }

    public int getTotalIngested()                  { return activeItems.size(); }
    public Map<String, Integer> getSourceFailures(){ return Collections.unmodifiableMap(sourceFailures); }

    // ══════════════════════════════════════════════════════════════════════════
    // SOURCE IMPLEMENTATIONS
    // ══════════════════════════════════════════════════════════════════════════

    // ── BSE Corporate Announcements (both SOURCE 1 and SOURCE 2 use this) ────
    private int fetchBseAnnouncements(String url, String sourceTag) throws Exception {
        String body = get(url,
                "Referer",    "https://www.bseindia.com/",
                "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Accept",     "application/json, text/plain, */*",
                "Origin",     "https://www.bseindia.com"
        );

        JsonNode table = objectMapper.readTree(body).path("Table");
        if (!table.isArray()) return 0;

        int added = 0;
        for (JsonNode row : table) {
            String headline = row.path("HEADLINE").asText("").trim();
            String category = row.path("CATEGORYNAME").asText("").trim();
            String dtStr    = row.path("DT_TM").asText("");
            String newsId   = row.path("NEWSID").asText(
                    String.valueOf((headline + dtStr).hashCode()));

            if (headline.isBlank()) continue;

            String id = sourceTag + ":" + newsId;
            if (seenIds.putIfAbsent(id, true) != null) continue;

            String description = category.isBlank() ? headline : category + ": " + headline;
            if (!filter.isRelevant(headline, description)) continue;

            Instant published = parseIstDateTime(dtStr);
            NewsItem item = buildItem(id, headline, description,
                    "https://www.bseindia.com/", "BSE", published);
            if (item != null) { activeItems.add(item); added++; }
        }
        return added;
    }

    // ── RSS feeds (ET Markets, Moneycontrol Business, Hindu Business Line) ────
    //
    // 403 FIX: Moneycontrol (and ET) use Cloudflare bot detection.
    // They check for a complete browser header fingerprint.
    // Java HttpClient sends minimal headers by default — Cloudflare blocks it.
    // Adding sec-fetch-* and upgrade-insecure-requests makes the request look like a real
    // Chrome browser. NOTE: "Connection" and "Accept-Encoding" are NOT set here — they are
    // restricted headers in Java HttpClient and are managed by the HTTP stack automatically.
    // makes the request look like a real Chrome browser.
    //
    // Referer is set per-domain: MC gets mc.com, ET gets economictimes.com.
    // This prevents Cloudflare from flagging cross-origin requests.
    //
    // HTTP/1.1 forced (version(HttpClient.Version.HTTP_1_1)) because:
    //   - Some Cloudflare configs block HTTP/2 from datacenter IPs (Railway)
    //   - HTTP/1.1 is what a simple browser tab uses for RSS fetch
    private int fetchRss(String url, String sourceTag) throws Exception {
        // Choose referer based on domain
        String referer = url.contains("moneycontrol") ? "https://www.moneycontrol.com/"
                : url.contains("economictimes") ? "https://economictimes.indiatimes.com/"
                : url.contains("thehindubusinessline") ? "https://www.thehindubusinessline.com/"
                : url.contains("reuters") ? "https://www.reuters.com/"
                : url.contains("rbi.org") ? "https://www.rbi.org.in/"
                : "https://www.google.com/";

        // RESTRICTED HEADER FIX:
        // Java HttpClient throws IllegalArgumentException for "Connection" and "Accept-Encoding".
        // These are managed internally by the HTTP/1.1 stack — the JDK controls them directly:
        //   Connection:      managed by keep-alive logic inside HttpClient
        //   Accept-Encoding: managed by HttpClient's built-in gzip decompression
        // Setting them via builder.header() throws:
        //   "restricted header name: Connection"
        //   "restricted header name: Accept-Encoding"
        // Both must be REMOVED from application code. The stack sends them correctly anyway.
        String body = get(url,
                "User-Agent",                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                "Accept",                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language",           "en-IN,en-US;q=0.9,en;q=0.8",
                "Upgrade-Insecure-Requests", "1",
                "Sec-Fetch-Dest",            "document",
                "Sec-Fetch-Mode",            "navigate",
                "Sec-Fetch-Site",            "none",
                "Sec-Fetch-User",            "?1",
                "Cache-Control",             "max-age=0",
                "Referer",                   referer
        );

        // Null/empty body guard — Reuters sometimes returns empty body instead of error
        if (body == null || body.isBlank()) return 0;

        List<String[]> items = parseRssItems(body);
        int added = 0;
        for (String[] item : items) {
            String headline    = item[0];
            String description = item[1];
            String link        = item[2];
            String pubDate     = item[3];

            if (headline.isBlank()) continue;
            if (!filter.isRelevant(headline, description)) continue;

            String id = sourceTag + ":" + (headline + pubDate).hashCode();
            if (seenIds.putIfAbsent(id, true) != null) continue;

            Instant published = parseRssDate(pubDate);
            NewsItem newsItem = buildItem(id, headline, description, link, sourceTag, published);
            if (newsItem != null) { activeItems.add(newsItem); added++; }
        }
        return added;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SHARED HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    /** Build HTTP GET request with alternating header key/value pairs */
    private String get(String url, String... headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET();
        for (int i = 0; i < headers.length - 1; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        HttpResponse<String> resp = httpClient.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 429)
            throw new RuntimeException("Rate limited (429)");
        if (resp.statusCode() == 403)
            throw new RuntimeException("Forbidden (403)");
        if (resp.statusCode() != 200)
            throw new RuntimeException("HTTP " + resp.statusCode());
        return resp.body();
    }

    /**
     * Redirect-following GET — used only for RBI RSS which returns HTTP 302.
     * Uses a separate HttpClient with NORMAL redirect policy.
     * Existing sources use get() with NEVER (default) — completely untouched.
     */
    private String getWithRedirect(String url, String... headers) throws Exception {
        HttpClient redirectClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET();
        for (int i = 0; i < headers.length - 1; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        HttpResponse<String> resp = redirectClient.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 429)
            throw new RuntimeException("Rate limited (429)");
        if (resp.statusCode() == 403)
            throw new RuntimeException("Forbidden (403)");
        if (resp.statusCode() != 200)
            throw new RuntimeException("HTTP " + resp.statusCode());
        String body = resp.body();
        if (body == null || body.isBlank()) return "";
        return body;
    }

    private NewsItem buildItem(String id, String headline, String description,
                               String url, String source, Instant published) {
        NewsItem.NewsCategory category = filter.classify(headline, description);
        if (category == NewsItem.NewsCategory.OTHER) return null;

        NewsItem.Sentiment sentiment = filter.detectSentiment(headline, description);
        int weight   = filter.computeKeywordWeight(headline, description, category);
        List<String> symbols = filter.extractSymbols(headline, description, knownSymbols);
        List<String> sectors = filter.extractSectors(headline, description);

        return new NewsItem(id, headline, description, url, source,
                published, Instant.now(), category, sentiment, symbols, sectors, weight);
    }

    private void refreshSymbolCache() {
        if (knownSymbols.isEmpty()) {
            knownSymbols = Collections.unmodifiableSet(
                    new HashSet<>(instrumentCache.getEquityInstruments().keySet()));
        }
    }

    // ── XML Parsing ───────────────────────────────────────────────────────────

    private List<String[]> parseRssItems(String xml) {
        List<String[]> items = new ArrayList<>();
        int start = 0;
        while ((start = xml.indexOf("<item>", start)) >= 0) {
            int end = xml.indexOf("</item>", start);
            if (end < 0) break;
            String block = xml.substring(start, end + 7);
            items.add(new String[]{
                    extractTag(block, "title"),
                    extractTag(block, "description"),
                    extractTag(block, "link"),
                    extractTag(block, "pubDate")
            });
            start = end + 7;
        }
        return items;
    }

    private String extractTag(String xml, String tag) {
        String open  = "<" + tag + ">";
        String close = "</" + tag + ">";
        int s = xml.indexOf(open);
        if (s < 0) {
            open  = "<" + tag + "><![CDATA[";
            close = "]]></" + tag + ">";
            s = xml.indexOf(open);
        }
        if (s < 0) return "";
        int e = xml.indexOf(close, s);
        if (e < 0) return "";
        return xml.substring(s + open.length(), e)
                .replaceAll("<[^>]+>", "")
                .replaceAll("&amp;", "&").replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">").replaceAll("&quot;", "\"")
                .replaceAll("&apos;", "'").trim();
    }

    // ── Date Parsing ──────────────────────────────────────────────────────────

    /** Parse RFC 822 RSS date: "Mon, 21 Apr 2026 09:30:00 +0530" */
    private Instant parseRssDate(String pubDate) {
        try {
            return java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                    .parse(pubDate, ZonedDateTime::from).toInstant();
        } catch (Exception e) {
            return Instant.now();
        }
    }

    /** Parse BSE datetime: "2026-04-24 09:35:22" → IST */
    private Instant parseIstDateTime(String dtStr) {
        try {
            return ZonedDateTime.parse(
                    dtStr.replace(" ", "T") + "+05:30").toInstant();
        } catch (Exception e) {
            return Instant.now();
        }
    }
}