package com.trading.strategy.news;

import com.trading.domain.enums.TradeDirection;
import com.trading.sector.service.SectorClassificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * NewsScoreEngine — converts raw NewsItems into ranked NewsScore objects.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * SCORING FORMULA (max 100 points):
 *
 *   categoryScore  (0–30):  NewsCategory.basePriority / 100 × 30
 *   sentimentScore (0–25):  Sentiment.score / 100 × 25
 *   recencyScore   (0–20):  20 × e^(-ageMin/30)   — halves every 30 minutes
 *   sourceScore    (0–15):  BSE=15, NEWSAPI=12, RSS_ET=10, RSS_MC=9, OTHER=5
 *   keywordScore   (0–10):  item.keywordWeight() / 10
 *
 * BONUSES:
 *   +5  if corroborated (same story from 2+ independent sources)
 *   +3  if EARNINGS category (already high base, reinforces conviction)
 *   +2  if STRONGLY_POSITIVE or STRONGLY_NEGATIVE (clear signal)
 *
 * TOTAL capped at 100.
 *
 * DIRECTION LOGIC:
 *   POSITIVE / STRONGLY_POSITIVE sentiment → LONG
 *   NEGATIVE / STRONGLY_NEGATIVE sentiment → SHORT
 *   NEUTRAL → determined by category (EARNINGS beat=LONG, miss=SHORT)
 *             Macro/RBI/Global: direction depends on article context
 *             Default for NEUTRAL + MACRO: skip (no clear directional bias)
 * ─────────────────────────────────────────────────────────────────────────────
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class NewsScoreEngine {

    private final SectorClassificationService sectorClassify;

    // Source credibility weights
    private static final Map<String, Integer> SOURCE_WEIGHTS = Map.of(
            "BSE",      15,   // official corporate filings — highest credibility
            "NEWSAPI",  12,   // curated news aggregator
            "RSS_ET",   10,   // Economic Times — reputable
            "RSS_MC",    9,   // Moneycontrol — reputable
            "UNKNOWN",   5
    );

    /**
     * Scores a single stock symbol based on all active news items mentioning it.
     * Returns empty Optional if score is below threshold or direction is unclear.
     */
    public Optional<NewsScore> scoreSymbol(String symbol,
                                           List<NewsItem> allItems,
                                           int minScore) {
        List<NewsItem> symbolItems = allItems.stream()
                .filter(item -> item.mentionedSymbols().contains(symbol))
                // Use market-aware actionable check:
                // On Monday, BSE/NSE weekend filings use market-open as age reference
                // so they appear "fresh" rather than 40+ hours old.
                .filter(item -> item.isActionable() || item.isActionableMonday())
                .sorted(Comparator.comparing(
                        (NewsItem i) -> i.category().basePriority).reversed())
                .toList();

        if (symbolItems.isEmpty()) return Optional.empty();

        // Use the highest-priority article as primary
        NewsItem primary = symbolItems.get(0);

        // Compute each score component
        int categoryScore  = computeCategoryScore(primary.category());
        int sentimentScore = computeSentimentScore(primary.sentiment());
        int recencyScore   = computeRecencyScore(primary.ageMinutes());
        int sourceScore    = SOURCE_WEIGHTS.getOrDefault(primary.source(), 5);
        int keywordScore   = primary.keywordWeight() / 10;

        int total = categoryScore + sentimentScore + recencyScore
                + sourceScore + keywordScore;

        // Corroboration bonus: same event from 2+ independent sources
        boolean corroborated = isCorroborated(symbolItems);
        if (corroborated) total += 5;

        // EARNINGS conviction bonus
        if (primary.category() == NewsItem.NewsCategory.EARNINGS) total += 3;

        // Strong sentiment bonus
        if (primary.sentiment() == NewsItem.Sentiment.STRONGLY_POSITIVE ||
                primary.sentiment() == NewsItem.Sentiment.STRONGLY_NEGATIVE)  total += 2;

        total = Math.min(100, total);

        // Determine direction — skip NEUTRAL macro items (no clear trade bias)
        Optional<TradeDirection> dir = determineDirection(primary, symbolItems);
        if (dir.isEmpty()) {
            log.debug("[NEWS-SCORE] {} score={} but direction unclear — skip", symbol, total);
            return Optional.empty();
        }

        if (total < minScore) {
            log.trace("[NEWS-SCORE] {} score={} below threshold {}", symbol, total, minScore);
            return Optional.empty();
        }

        String sectorName = sectorClassify.getSector(symbol);

        NewsScore score = new NewsScore(
                symbol, sectorName, total,
                categoryScore, sentimentScore, recencyScore, sourceScore, keywordScore,
                dir.get(),
                primary.category(),
                primary.sentiment(),
                symbolItems,
                primary.headline(),
                primary.publishedAt(),
                primary.ageMinutes(),
                corroborated
        );

        log.info("[NEWS-SCORE] {}", score.toLogString());
        return Optional.of(score);
    }

    /**
     * Score ALL symbols mentioned in active news and return sorted by total score.
     * Only returns symbols above minScore threshold.
     */
    public List<NewsScore> scoreAll(List<NewsItem> allItems,
                                    Set<String> tradableSymbols,
                                    int minScore) {
        // Collect all mentioned symbols
        Set<String> mentionedSymbols = allItems.stream()
                .flatMap(item -> item.mentionedSymbols().stream())
                .filter(tradableSymbols::contains)
                .collect(Collectors.toSet());

        // Also collect sector-level signals and map to top stocks in that sector
        // (handled separately in NewsTradingStrategy for sector-wide news)

        List<NewsScore> scores = mentionedSymbols.stream()
                .map(sym -> scoreSymbol(sym, allItems, minScore))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .sorted(Comparator.comparingInt(NewsScore::totalScore).reversed())
                .toList();

        log.debug("[NEWS-SCORE] Scored {}/{} symbols above threshold {}",
                scores.size(), mentionedSymbols.size(), minScore);
        return scores;
    }

    /**
     * Score ALL symbols for dashboard visibility — no direction filter, no threshold.
     * Returns EVERY scored stock including:
     *   - Direction unclear (ASIANPAINT, BSE — shown as SKIPPED in dashboard)
     *   - Below 65 threshold (TREJHARA — shown as BELOW 65)
     *   - Above threshold and eligible
     *
     * CRITICAL DIFFERENCE from scoreAll():
     *   scoreAll() drops items where direction is unclear (returns Optional.empty).
     *   scoreAllForDashboard() keeps ALL items and sets direction=null for unclear.
     *   This is what populates the "All Scored News Items" table in the News tab.
     *
     * Trading logic NEVER calls this method. Only DashboardController (via
     * NewsTradingStrategy.getLastCycleScores()) reads these results.
     */
    public List<NewsScore> scoreAllForDashboard(List<NewsItem> allItems,
                                                Set<String> tradableSymbols) {
        Set<String> mentionedSymbols = allItems.stream()
                .flatMap(item -> item.mentionedSymbols().stream())
                .filter(tradableSymbols::contains)
                .collect(Collectors.toSet());

        List<NewsScore> results = new java.util.ArrayList<>();

        for (String symbol : mentionedSymbols) {
            List<NewsItem> symbolItems = allItems.stream()
                    .filter(item -> item.mentionedSymbols().contains(symbol))
                    .filter(item -> item.isActionable() || item.isActionableMonday())
                    .sorted(Comparator.comparing(
                            (NewsItem i) -> i.category().basePriority).reversed())
                    .toList();

            if (symbolItems.isEmpty()) continue;

            NewsItem primary = symbolItems.get(0);

            int categoryScore  = computeCategoryScore(primary.category());
            int sentimentScore = computeSentimentScore(primary.sentiment());
            int recencyScore   = computeRecencyScore(primary.ageMinutes());
            int sourceScore    = SOURCE_WEIGHTS.getOrDefault(primary.source(), 5);
            int keywordScore   = primary.keywordWeight() / 10;
            int total = categoryScore + sentimentScore + recencyScore
                    + sourceScore + keywordScore;

            boolean corroborated = isCorroborated(symbolItems);
            if (corroborated) total += 5;
            if (primary.category() == NewsItem.NewsCategory.EARNINGS) total += 3;
            if (primary.sentiment() == NewsItem.Sentiment.STRONGLY_POSITIVE ||
                    primary.sentiment() == NewsItem.Sentiment.STRONGLY_NEGATIVE) total += 2;
            total = Math.min(100, total);

            // Direction: null if unclear — dashboard shows "SKIPPED (direction unclear)"
            Optional<TradeDirection> dir = determineDirection(primary, symbolItems);

            String sectorName = sectorClassify.getSector(symbol);

            results.add(new NewsScore(
                    symbol, sectorName, total,
                    categoryScore, sentimentScore, recencyScore, sourceScore, keywordScore,
                    dir.orElse(null),          // null = direction unclear
                    primary.category(),
                    primary.sentiment(),
                    symbolItems,
                    primary.headline(),
                    primary.publishedAt(),
                    primary.ageMinutes(),
                    corroborated
            ));
        }

        results.sort(Comparator.comparingInt(NewsScore::totalScore).reversed());
        log.debug("[NEWS-SCORE] Dashboard: {} items scored (all directions, no threshold)",
                results.size());
        return results;
    }

    // ── Score components ──────────────────────────────────────────────────────

    private int computeCategoryScore(NewsItem.NewsCategory category) {
        // basePriority is 0-100, scale to 0-30
        return (int) Math.round(category.basePriority / 100.0 * 30);
    }

    private int computeSentimentScore(NewsItem.Sentiment sentiment) {
        // sentiment.score is 0-100, scale to 0-25
        return (int) Math.round(sentiment.score / 100.0 * 25);
    }

    private int computeRecencyScore(long ageMinutes) {
        // Exponential decay: 20 * e^(-ageMin/30)
        // At 0 min: 20 points   At 30 min: 10 points   At 60 min: ~5 points
        double decay = 20.0 * Math.exp(-ageMinutes / 30.0);
        return (int) Math.round(Math.max(0, decay));
    }

    // ── Direction logic ───────────────────────────────────────────────────────

    private Optional<TradeDirection> determineDirection(NewsItem primary,
                                                        List<NewsItem> allItems) {
        // Calculate weighted sentiment across all items
        int positiveWeight = 0, negativeWeight = 0;
        for (NewsItem item : allItems) {
            int w = item.keywordWeight();
            switch (item.sentiment()) {
                case STRONGLY_POSITIVE -> positiveWeight += w * 2;
                case POSITIVE          -> positiveWeight += w;
                case STRONGLY_NEGATIVE -> negativeWeight += w * 2;
                case NEGATIVE          -> negativeWeight += w;
                default -> {} // NEUTRAL items don't contribute
            }
        }

        // Strong directional bias
        if (positiveWeight > negativeWeight * 1.5) return Optional.of(TradeDirection.LONG);
        if (negativeWeight > positiveWeight * 1.5) return Optional.of(TradeDirection.SHORT);

        // For EARNINGS category, use primary article sentiment
        if (primary.category() == NewsItem.NewsCategory.EARNINGS ||
                primary.category() == NewsItem.NewsCategory.MERGER_ACQUISITION) {
            return switch (primary.sentiment()) {
                case STRONGLY_POSITIVE, POSITIVE -> Optional.of(TradeDirection.LONG);
                case STRONGLY_NEGATIVE, NEGATIVE -> Optional.of(TradeDirection.SHORT);
                default -> Optional.empty(); // neutral earnings = skip
            };
        }

        // For macro/RBI/global: require strong sentiment to trade
        if (primary.category() == NewsItem.NewsCategory.RBI_POLICY ||
                primary.category() == NewsItem.NewsCategory.ECONOMIC_DATA ||
                primary.category() == NewsItem.NewsCategory.GLOBAL_EVENT) {
            if (primary.sentiment() == NewsItem.Sentiment.STRONGLY_POSITIVE)
                return Optional.of(TradeDirection.LONG);
            if (primary.sentiment() == NewsItem.Sentiment.STRONGLY_NEGATIVE)
                return Optional.of(TradeDirection.SHORT);
            return Optional.empty(); // weak macro signal = skip
        }

        // For sector/govt/breaking news: use primary sentiment
        return switch (primary.sentiment()) {
            case STRONGLY_POSITIVE, POSITIVE -> Optional.of(TradeDirection.LONG);
            case STRONGLY_NEGATIVE, NEGATIVE -> Optional.of(TradeDirection.SHORT);
            default -> Optional.empty();
        };
    }

    // ── Corroboration check ───────────────────────────────────────────────────

    private boolean isCorroborated(List<NewsItem> items) {
        if (items.size() < 2) return false;
        // Check if at least 2 DIFFERENT sources reported the same story
        long uniqueSources = items.stream()
                .map(NewsItem::source)
                .distinct()
                .count();
        return uniqueSources >= 2;
    }
}