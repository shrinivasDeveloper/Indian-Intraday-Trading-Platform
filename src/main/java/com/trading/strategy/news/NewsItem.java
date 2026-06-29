package com.trading.strategy.news;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Immutable record representing a single parsed news article from any source.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * SOURCES:
 *   BSE        — Official BSE corporate announcements (earnings, dividends, M&A)
 *   NSE        — Official NSE corporate announcements
 *   RSS_ET     — Economic Times Markets RSS feed
 *   RSS_MC_MKT — Moneycontrol Markets RSS
 *   RSS_MC_BIZ — Moneycontrol Business RSS
 *   RSS_CNBC   — CNBC TV18 RSS
 *   RSS_HBL    — Hindu Business Line RSS
 *   GNEWS      — GNews.io API (Google News aggregator)
 * ─────────────────────────────────────────────────────────────────────────────
 */
public record NewsItem(
        String id,
        String headline,
        String description,
        String url,
        String source,
        Instant publishedAt,
        Instant ingestedAt,
        NewsCategory category,
        Sentiment sentiment,
        List<String> mentionedSymbols,
        List<String> mentionedSectors,
        int keywordWeight
) {

    private static final ZoneId   IST                      = ZoneId.of("Asia/Kolkata");
    // No longer used as a hard cutoff (see isActionable() below) — kept for
    // reference only in case the 60-minute gate needs to be restored later.
    private static final int      NORMAL_ACTIONABLE_MINUTES = 60;
    private static final int      MONDAY_WALL_CLOCK_MINUTES = 4320; // 72h covers Fri-Mon
    private static final LocalTime MARKET_OPEN             = LocalTime.of(9, 0);

    /** News categories in priority order for scoring */
    public enum NewsCategory {
        EARNINGS(100),
        MERGER_ACQUISITION(90),
        RBI_POLICY(85),
        ECONOMIC_DATA(75),
        GOVT_POLICY(70),
        SECTOR_NEWS(60),
        GLOBAL_EVENT(55),
        BREAKING_NEWS(50),
        OTHER(20);

        public final int basePriority;
        NewsCategory(int p) { this.basePriority = p; }
    }

    public enum Sentiment {
        STRONGLY_POSITIVE(100),
        POSITIVE(70),
        NEUTRAL(50),
        NEGATIVE(30),
        STRONGLY_NEGATIVE(0);

        public final int score;
        Sentiment(int s) { this.score = s; }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // AGE CALCULATION — market-aware for Monday weekend news
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Age in minutes using MARKET-AWARE reference time.
     *
     * Problem without this fix:
     *   A BSE earnings filing published Saturday 4 PM has wall-clock age of
     *   2490 minutes at Monday 9:35 AM → isActionable() returns false → IGNORED.
     *   But the stock has NOT priced this news yet — it is market-fresh.
     *
     * Fix:
     *   For BSE/NSE filings published on Saturday or Sunday, when today is Monday,
     *   age is calculated from Monday 9:00 AM (market open) instead of wall clock.
     *   A Saturday 4 PM filing becomes "35 minutes old" at Monday 9:35 AM.
     *
     * For all other sources and all weekday news: standard wall-clock age.
     */
    public long ageMinutes() {
        if (isOfficialSource() && isWeekendPublished() && isMondayNow()) {
            LocalDate monday    = LocalDate.now(IST);
            Instant mondayOpen  = monday.atTime(MARKET_OPEN).atZone(IST).toInstant();
            Instant now         = Instant.now();
            if (now.isBefore(mondayOpen)) return 0L;
            return Math.max(0, (now.toEpochMilli() - mondayOpen.toEpochMilli()) / 60_000L);
        }
        return rawAgeMinutes();
    }

    /**
     * True if within the 60-minute actionable trading window.
     * For BSE/NSE weekend filings on Monday: age is measured from market open,
     * so these correctly appear "fresh" at 9:35 AM Monday.
     */
    public boolean isActionable() {
        // FIX: removed the 60-minute hard cutoff (NORMAL_ACTIONABLE_MINUTES).
        // Previously, any news older than 60 minutes was excluded from scoring
        // entirely, regardless of category/sentiment strength. Now all news
        // remains eligible for scoring; the exponential recency decay in
        // NewsScoreEngine.computeRecencyScore() still naturally reduces older
        // news' score contribution toward zero — this is a soft de-prioritization,
        // not a hard exclusion. minScore was raised to 75 (application.yml) to
        // compensate, so only strong category+sentiment combinations can still
        // cross the threshold once recency contributes little or nothing.
        return true;
    }

    /**
     * Monday-extended check: allows official weekend filings to be scored
     * even via the NewsScoreEngine's recencyScore calculation.
     * Fallback for cases where isActionable() would be false but the news
     * is genuinely market-relevant on Monday.
     */
    public boolean isActionableMonday() {
        if (!isMondayNow()) return isActionable();
        if (isOfficialSource() && isWeekendPublished()) {
            return rawAgeMinutes() < MONDAY_WALL_CLOCK_MINUTES;
        }
        return isActionable();
    }

    /**
     * NEW — dedicated check used ONLY by NewsIngestionService.purgeStaleItems()
     * to decide whether to exempt a Monday weekend filing from the 2-hour
     * memory purge. Deliberately does NOT fall back to isActionable() (which
     * now always returns true after the 60-min cutoff removal) — that fallback
     * would make purgeStaleItems() never purge anything, leaking memory all day.
     * This method only ever returns true for the narrow, genuine weekend-filing
     * case; everything else returns false so the 2-hour purge still applies.
     */
    public boolean isMondayWeekendException() {
        if (!isMondayNow()) return false;
        if (isOfficialSource() && isWeekendPublished()) {
            return rawAgeMinutes() < MONDAY_WALL_CLOCK_MINUTES;
        }
        return false;
    }

    /** Raw wall-clock age in minutes from publishedAt. Used by NewsScoreEngine recency decay. */
    public long rawAgeMinutes() {
        return Math.max(0, (Instant.now().toEpochMilli() - publishedAt.toEpochMilli()) / 60_000L);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private boolean isWeekendPublished() {
        DayOfWeek dow = publishedAt.atZone(IST).getDayOfWeek();
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
    }

    private boolean isMondayNow() {
        return LocalDate.now(IST).getDayOfWeek() == DayOfWeek.MONDAY;
    }

    /** BSE and NSE have precise timestamps and are not republished on Monday. */
    private boolean isOfficialSource() {
        return "BSE".equals(source) || "NSE".equals(source);
    }
}