package com.trading.strategy.news;

import com.trading.domain.enums.TradeDirection;

import java.time.Instant;
import java.util.List;

/**
 * Scored stock candidate produced by NewsScoreEngine.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * SCORING FORMULA (max 100):
 *
 *   categoryScore  (0-30)  — based on NewsCategory.basePriority, scaled to 30
 *   sentimentScore (0-25)  — based on Sentiment.score, scaled to 25
 *   recencyScore   (0-20)  — exponential decay: 20 * e^(-age/30min)
 *   sourceScore    (0-15)  — BSE=15, NEWSAPI=12, RSS=10, UNKNOWN=5
 *   keywordScore   (0-10)  — keyword impact weight / 10
 *
 *   TOTAL = sum of above, capped at 100
 *
 * A score >= 65 triggers trade evaluation.
 * A score >= 80 is HIGH CONVICTION — direction bias is strong.
 * ─────────────────────────────────────────────────────────────────────────────
 */
public record NewsScore(

        /** NSE trading symbol (e.g. "RELIANCE", "HDFCBANK") */
        String symbol,

        /** Sector this stock belongs to */
        String sectorName,

        /** Composite news score: 0–100 */
        int totalScore,

        /** Component breakdown for logging and dashboard */
        int categoryScore,
        int sentimentScore,
        int recencyScore,
        int sourceScore,
        int keywordScore,

        /** Trade direction implied by news sentiment and category */
        TradeDirection direction,

        /**
         * News category that drove this score.
         * EARNINGS/M&A = highest quality signal.
         */
        NewsItem.NewsCategory primaryCategory,

        /** Dominant sentiment across all articles for this symbol */
        NewsItem.Sentiment dominantSentiment,

        /** All articles that contributed to this score */
        List<NewsItem> sourceArticles,

        /** Headline of the highest-scoring article (for logging/dashboard) */
        String primaryHeadline,

        /** When the highest-priority article was published */
        Instant eventTime,

        /** Age of the primary article in minutes (used for freshness display) */
        long ageMinutes,

        /**
         * True if multiple independent sources reported the same event.
         * Corroborated news gets a +5 bonus in scoring and higher confidence.
         */
        boolean corroborated

) {

    /** Returns true if this score qualifies for trade evaluation */
    public boolean isTradeWorthy(int minScore) {
        return totalScore >= minScore && !sourceArticles.isEmpty();
    }

    /** Summary string for logging */
    public String toLogString() {
        return String.format(
                "%s [%s] score=%d dir=%s cat=%s sentiment=%s age=%dmin corroborated=%s | \"%s\"",
                symbol, sectorName, totalScore, direction,
                primaryCategory, dominantSentiment, ageMinutes,
                corroborated ? "YES" : "no",
                primaryHeadline.length() > 80 ? primaryHeadline.substring(0, 80) + "..." : primaryHeadline
        );
    }
}