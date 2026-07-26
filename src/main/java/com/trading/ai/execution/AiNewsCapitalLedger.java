package com.trading.ai.execution;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Slf4j
public class AiNewsCapitalLedger {

    private final JdbcTemplate jdbc;
    private final AtomicReference<BigDecimal> defaultStartingCapital;

    public AiNewsCapitalLedger(JdbcTemplate jdbc,
                               @org.springframework.beans.factory.annotation.Value("${trading.capital:100000}")
                               double configuredCapital) {
        this.jdbc = jdbc;
        this.defaultStartingCapital = new AtomicReference<>(BigDecimal.valueOf(configuredCapital));
        ensureTableExists();
    }

    /**
     * ROOT-CAUSE FIX (capital vanishing on every restart): this check
     * runs in the constructor on EVERY boot, and previously DROPPED the
     * entire ledger table whenever its information_schema column-probe
     * returned 0 - on the new MySQL 9.4 deployment that destroyed all
     * capital data each restart, and with no prior-day history on the
     * fresh DB, ensureRowExists() then fell back to trading.capital
     * (Rs.5000). A destructive DROP re-evaluated on every startup has
     * no place in production code. The migration era is definitively
     * over - this deployment's DB was created with the new schema, so
     * the old schema can no longer exist anywhere. The check now only
     * WARNS (with manual instructions) in that impossible case, and can
     * NEVER destroy data.
     */
    private void migrateOldSchemaIfNeeded() {
        try {
            Integer tableExists = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_news_capital_ledger'
                """, Integer.class);
            if (tableExists == null || tableExists == 0) return;

            Integer hasStrategyColumn = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_news_capital_ledger'
                AND COLUMN_NAME = 'strategy_name'
                """, Integer.class);
            if (hasStrategyColumn != null && hasStrategyColumn > 0) return;

            log.error("[AI-LEDGER] Column-probe could not confirm the strategy_name column. " +
                    "NOT dropping anything (a previous version destroyed the table here on " +
                    "every restart - that destructive path is permanently removed). If this " +
                    "table genuinely has the pre-per-strategy schema, migrate it MANUALLY: " +
                    "ALTER TABLE ai_news_capital_ledger ADD COLUMN strategy_name VARCHAR(50) " +
                    "NOT NULL DEFAULT 'AI_TRADING_V2', then adjust the primary key.");
        } catch (Exception e) {
            log.warn("[AI-LEDGER] Schema migration check failed (non-fatal): {}", e.getMessage());
        }
    }

    private void ensureTableExists() {
        try {
            migrateOldSchemaIfNeeded();
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ai_news_capital_ledger (
                    trade_date        DATE          NOT NULL,
                    strategy_name     VARCHAR(50)   NOT NULL,
                    starting_capital  DECIMAL(14,2) NOT NULL,
                    margin_used       DECIMAL(14,2) NOT NULL DEFAULT 0,
                    realised_pnl      DECIMAL(14,2) NOT NULL DEFAULT 0,
                    trades_count      INT           NOT NULL DEFAULT 0,
                    wins_count        INT           NOT NULL DEFAULT 0,
                    losses_count      INT           NOT NULL DEFAULT 0,
                    updated_at        TIMESTAMP     NOT NULL,
                    PRIMARY KEY (trade_date, strategy_name)
                )
                """);
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ai_news_ledger_entries (
                    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
                    trade_date      DATE         NOT NULL,
                    symbol          VARCHAR(20)  NOT NULL,
                    strategy_name   VARCHAR(50)  NOT NULL,
                    entry_type      VARCHAR(10)  NOT NULL,
                    amount          DECIMAL(14,2) NOT NULL,
                    description     VARCHAR(200),
                    created_at      TIMESTAMP    NOT NULL,
                    INDEX idx_date (trade_date)
                )
                """);
        } catch (Exception e) {
            log.error("[AI-LEDGER] Could not create ledger tables - capital tracking " +
                    "will not persist correctly: {}", e.getMessage());
        }
    }

    private synchronized void ensureRowExists(String strategyName) {
        try {
            Integer exists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ai_news_capital_ledger WHERE trade_date = ? AND strategy_name = ?",
                    Integer.class, LocalDate.now(ZoneId.of("Asia/Kolkata")), strategyName);
            if (exists == null || exists == 0) {
                // FIX (confirmed real bug, per explicit user request):
                // previously called computeYesterdayEndingCapital(),
                // which added yesterday's realised P&L to yesterday's
                // starting capital - meaning a value explicitly set via
                // the UI would silently drift day to day by whatever
                // the strategy made or lost, rather than staying
                // exactly what was set. This directly contradicted this
                // file's own stated principle elsewhere ("the capital
                // entered in the UI should act as the fixed capital per
                // trade, not as a running balance"). Now carries
                // forward ONLY the last explicitly-set starting_capital
                // value, completely ignoring P&L for this specific
                // purpose - today's real-time margin/P&L tracking
                // (debitMargin, recordExit, getAvailableCapital) is
                // entirely unaffected, this only changes what tomorrow
                // inherits as its baseline.
                BigDecimal carryForward = getLastSetStartingCapital(strategyName);
                BigDecimal base = carryForward != null ? carryForward : defaultStartingCapital.get();
                jdbc.update("""
                    INSERT INTO ai_news_capital_ledger
                      (trade_date, strategy_name, starting_capital, margin_used, realised_pnl,
                       trades_count, wins_count, losses_count, updated_at)
                    VALUES (?, ?, ?, 0, 0, 0, 0, 0, ?)
                    """,
                        LocalDate.now(ZoneId.of("Asia/Kolkata")), strategyName, base, java.sql.Timestamp.from(Instant.now()));
                log.info("[AI-LEDGER] Initialised today's ledger for {} with starting capital Rs.{} " +
                        "(carried forward exactly as last set, unaffected by P&L)", strategyName, base);
            }
        } catch (Exception e) {
            log.warn("[AI-LEDGER] ensureRowExists failed for {} (non-fatal, will retry " +
                    "implicitly on next call): {}", strategyName, e.getMessage());
        }
    }

    /**
     * FIX (confirmed real bug, replaces the old computeYesterdayEndingCapital()
     * for the carry-forward purpose - see the detailed reasoning in
     * ensureRowExists() above). Fetches ONLY starting_capital from the
     * most recent prior day - deliberately does NOT add realised_pnl.
     * This is the one, isolated change: the UI-set value now persists
     * exactly as entered, day after day, until explicitly changed again
     * via setStartingCapital() - never silently adjusted by trading
     * results.
     */
    private BigDecimal getLastSetStartingCapital(String strategyName) {
        try {
            Map<String, Object> row = jdbc.queryForMap("""
                SELECT starting_capital FROM ai_news_capital_ledger
                WHERE trade_date < ? AND strategy_name = ? ORDER BY trade_date DESC LIMIT 1
                """, LocalDate.now(ZoneId.of("Asia/Kolkata")), strategyName);
            return (BigDecimal) row.get("starting_capital");
        } catch (Exception e) {
            return null; // no prior day for this strategy - first ever run
        }
    }

    public synchronized boolean setStartingCapital(String strategyName, BigDecimal capital) {
        try {
            ensureRowExists(strategyName);
            jdbc.update("""
                UPDATE ai_news_capital_ledger
                SET starting_capital = ?, updated_at = ?
                WHERE trade_date = ? AND strategy_name = ?
                """,
                    capital, java.sql.Timestamp.from(Instant.now()), LocalDate.now(ZoneId.of("Asia/Kolkata")), strategyName);
            log.info("[AI-LEDGER] {} capital set to Rs.{} via UI", strategyName, capital);
            return true;
        } catch (Exception e) {
            log.error("[AI-LEDGER] setStartingCapital failed for {}: {}", strategyName, e.getMessage());
            return false;
        }
    }

    public synchronized void debitMargin(String symbol, String strategyName,
                                         BigDecimal marginRequired) {
        try {
            ensureRowExists(strategyName);
            jdbc.update("""
                UPDATE ai_news_capital_ledger
                SET margin_used = margin_used + ?, trades_count = trades_count + 1,
                    updated_at = ?
                WHERE trade_date = ? AND strategy_name = ?
                """,
                    marginRequired, java.sql.Timestamp.from(Instant.now()), LocalDate.now(ZoneId.of("Asia/Kolkata")), strategyName);
            recordEntry(symbol, strategyName, "MARGIN_DEBIT", marginRequired.negate(),
                    "Entry margin reserved");
            log.info("[AI-LEDGER] Margin debited: {} Rs.{} for {} (strategy={})",
                    symbol, marginRequired, symbol, strategyName);
        } catch (Exception e) {
            log.error("[AI-LEDGER] debitMargin failed for {} (non-fatal - position " +
                            "tracking continues regardless of ledger state): {}",
                    symbol, e.getMessage());
        }
    }

    public synchronized void recordExit(String symbol, String strategyName,
                                        BigDecimal marginToRelease, BigDecimal realisedPnl,
                                        boolean isWin) {
        try {
            ensureRowExists(strategyName);
            jdbc.update("""
                UPDATE ai_news_capital_ledger
                SET margin_used = margin_used - ?, realised_pnl = realised_pnl + ?,
                    wins_count = wins_count + ?, losses_count = losses_count + ?,
                    updated_at = ?
                WHERE trade_date = ? AND strategy_name = ?
                """,
                    marginToRelease, realisedPnl, isWin ? 1 : 0, isWin ? 0 : 1,
                    java.sql.Timestamp.from(Instant.now()), LocalDate.now(ZoneId.of("Asia/Kolkata")), strategyName);
            recordEntry(symbol, strategyName, "PNL", realisedPnl,
                    "Trade closed - " + (isWin ? "WIN" : "LOSS/BREAKEVEN"));
            log.info("[AI-LEDGER] Exit recorded: {} P&L=Rs.{} ({}) - margin released Rs.{} (strategy={})",
                    symbol, realisedPnl, isWin ? "WIN" : "LOSS", marginToRelease, strategyName);
        } catch (Exception e) {
            log.error("[AI-LEDGER] recordExit failed for {} (non-fatal): {}",
                    symbol, e.getMessage());
        }
    }

    private void recordEntry(String symbol, String strategyName, String type,
                             BigDecimal amount, String description) {
        try {
            jdbc.update("""
                INSERT INTO ai_news_ledger_entries
                  (trade_date, symbol, strategy_name, entry_type, amount, description, created_at)
                VALUES (?,?,?,?,?,?,?)
                """,
                    LocalDate.now(ZoneId.of("Asia/Kolkata")), symbol, strategyName, type,
                    amount.setScale(2, RoundingMode.HALF_UP), description,
                    java.sql.Timestamp.from(Instant.now()));
        } catch (Exception e) {
            log.debug("[AI-LEDGER] recordEntry audit-log failed (non-fatal): {}", e.getMessage());
        }
    }

    public BigDecimal getFixedCapitalPerTrade(String strategyName) {
        try {
            ensureRowExists(strategyName);
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT starting_capital " +
                            "FROM ai_news_capital_ledger WHERE trade_date = ? AND strategy_name = ?",
                    LocalDate.now(ZoneId.of("Asia/Kolkata")), strategyName);
            return (BigDecimal) row.get("starting_capital");
        } catch (Exception e) {
            log.warn("[AI-LEDGER] getFixedCapitalPerTrade failed for {} - returning configured " +
                    "default as a safe fallback: {}", strategyName, e.getMessage());
            return defaultStartingCapital.get();
        }
    }

    public BigDecimal getAvailableCapital(String strategyName) {
        try {
            ensureRowExists(strategyName);
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT starting_capital, margin_used, realised_pnl " +
                            "FROM ai_news_capital_ledger WHERE trade_date = ? AND strategy_name = ?",
                    LocalDate.now(ZoneId.of("Asia/Kolkata")), strategyName);
            BigDecimal start = (BigDecimal) row.get("starting_capital");
            BigDecimal used  = (BigDecimal) row.get("margin_used");
            BigDecimal pnl   = (BigDecimal) row.get("realised_pnl");
            return start.add(pnl).subtract(used);
        } catch (Exception e) {
            log.warn("[AI-LEDGER] getAvailableCapital failed for {} - returning configured " +
                    "default as a safe fallback: {}", strategyName, e.getMessage());
            return defaultStartingCapital.get();
        }
    }

    public BigDecimal getTodayRealisedPnl(String strategyName) {
        try {
            Object pnl = jdbc.queryForObject(
                    "SELECT realised_pnl FROM ai_news_capital_ledger WHERE trade_date = ? AND strategy_name = ?",
                    BigDecimal.class, LocalDate.now(ZoneId.of("Asia/Kolkata")), strategyName);
            return (BigDecimal) pnl;
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    public Map<String, Object> getTodaySummary(String strategyName) {
        try {
            return jdbc.queryForMap(
                    "SELECT * FROM ai_news_capital_ledger WHERE trade_date = ? AND strategy_name = ?",
                    LocalDate.now(ZoneId.of("Asia/Kolkata")), strategyName);
        } catch (Exception e) {
            return Map.of("error", "No ledger data available yet for " + strategyName);
        }
    }

    public List<Map<String, Object>> getTodaySummaryAll() {
        try {
            return jdbc.queryForList(
                    "SELECT * FROM ai_news_capital_ledger WHERE trade_date = ? ORDER BY strategy_name",
                    LocalDate.now(ZoneId.of("Asia/Kolkata")));
        } catch (Exception e) {
            return List.of();
        }
    }

    public List<Map<String, Object>> getTodayEntries() {
        try {
            return jdbc.queryForList(
                    "SELECT * FROM ai_news_ledger_entries WHERE trade_date = ? ORDER BY created_at DESC",
                    LocalDate.now(ZoneId.of("Asia/Kolkata")));
        } catch (Exception e) {
            return List.of();
        }
    }

    @Scheduled(cron = "0 0 7 * * MON", zone = "Asia/Kolkata")
    public void weeklyCleanup() {
        try {
            jdbc.update("DELETE FROM ai_news_ledger_entries WHERE trade_date < ?",
                    LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(90));
        } catch (Exception e) {
            log.debug("[AI-LEDGER] Weekly cleanup failed (non-fatal): {}", e.getMessage());
        }
    }
}