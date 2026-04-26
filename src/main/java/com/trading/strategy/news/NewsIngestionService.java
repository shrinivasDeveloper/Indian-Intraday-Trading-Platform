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
    private final Map<String, Integer> sourceFailures = new ConcurrentHashMap<>();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
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
     */
    @Scheduled(fixedRate = 420_000)
    public void fetchFromMoneycontrolBusiness() {
        if (!enabled) return;
        refreshSymbolCache();
        try {
            int added = fetchRss(
                    "https://www.moneycontrol.com/rss/business.xml",
                    "RSS_MC_BIZ");
            if (added > 0) log.info("[NEWS-INGEST] Moneycontrol Business RSS: +{} articles", added);
            sourceFailures.remove("RSS_MC_BIZ");
        } catch (Exception e) {
            int f = sourceFailures.merge("RSS_MC_BIZ", 1, Integer::sum);
            log.warn("[NEWS-INGEST] Moneycontrol Business RSS failed ({}x): {}", f, e.getMessage());
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

    /**
     * Purge articles older than 2 hours every 15 minutes.
     * Exception: BSE/NSE official filings from weekend preserved on Monday
     * so Monday market-open logic in NewsItem.ageMinutes() can process them.
     */
    @Scheduled(fixedRate = 900_000)
    public void purgeStaleItems() {
        Instant cutoff = Instant.now().minusSeconds(7_200);
        int before = activeItems.size();
        activeItems.removeIf(item -> {
            if (item.isActionableMonday()) return false;
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
    private int fetchRss(String url, String sourceTag) throws Exception {
        String body = get(url,
                "User-Agent",      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Accept",          "application/rss+xml, application/xml, text/xml, */*",
                "Accept-Language", "en-US,en;q=0.9",
                "Cache-Control",   "no-cache",
                "Referer",         "https://www.moneycontrol.com/"
        );

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