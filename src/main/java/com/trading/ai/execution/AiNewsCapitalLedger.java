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

/**
 * AiNewsCapitalLedger
 *
 * Self-contained capital and P&L tracking for the AI and News strategies
 * ONLY. Deliberately does NOT depend on or touch PaperAccount, RiskManagementService,
 * or any other shared/strategy-specific class - those belong to the other
 * strategies being permanently removed.
 *
 * FIX (per-strategy capital, requested after going live with Rs.10,000 split
 * across AI and News): previously this ledger pooled ONE shared capital
 * figure across every strategy that called debitMargin()/recordExit() -
 * even though those methods already took a strategyName parameter, it was
 * only ever used for the audit trail (ai_news_ledger_entries), never to
 * actually segment the balance itself. Schema changed from
 * PRIMARY KEY (trade_date) to PRIMARY KEY (trade_date, strategy_name) -
 * AI, News, and BTST now each carry their own independent starting
 * capital, margin used, and realised P&L. Every existing call site
 * (debitMargin/recordExit) already passed strategyName correctly and
 * needed ZERO changes - only the 2 no-arg getAvailableCapital() callers
 * (AiRiskAssessmentEngine, NewsTradingStrategy) needed updating to pass
 * their own strategy name.
 *
 * PAPER mode: this ledger IS the simulation - debits margin on entry,
 *   credits P&L on exit, all purely in this table. No other class needs
 *   to be involved for paper accounting to work correctly.
 *
 * LIVE mode: this ledger mirrors the broker's actual fund movements for
 *   reporting/dashboard purposes. It does not gate order placement - your
 *   broker margin is the real constraint - but it gives a clean, per-
 *   strategy P&L view.
 *
 * Capital configuration: trading.capital is the FALLBACK default, used
 * only the very first time a strategy is seen with no prior day's data
 * and no UI-set value yet. The real, persistent per-strategy value is set
 * via setStartingCapital(strategyName, capital) - intended to be called
 * from a dashboard UI action - and carries forward automatically day to
 * day after that (today's ending capital = tomorrow's starting point).
 */
@Service
@Slf4j
public class AiNewsCapitalLedger {

    private final JdbcTemplate jdbc;
    private final AtomicReference<BigDecimal> defaultStartingCapital;

    /**
     * Constructor injection (not field-level @Value) - ensureTableExists()
     * runs during construction, before Spring would populate a field-level
     * @Value, so the fallback default must arrive via the constructor.
     */
    public AiNewsCapitalLedger(JdbcTemplate jdbc,
                               @org.springframework.beans.factory.annotation.Value("${trading.capital:100000}")
                               double configuredCapital) {
        this.jdbc = jdbc;
        this.defaultStartingCapital = new AtomicReference<>(BigDecimal.valueOf(configuredCapital));
        ensureTableExists();
    }

    /**
     * Self-healing schema migration. The table may already exist from
     * before this per-strategy rework - with the OLD schema (one row per
     * trade_date only, no strategy_name column). CREATE TABLE IF NOT
     * EXISTS silently does nothing in that case, since the table already
     * exists - leaving every query referencing strategy_name failing with
     * "bad SQL grammar" (column doesn't exist).
     *
     * Detects this precisely (checks information_schema for the
     * strategy_name column, not just table existence) and drops the old
     * table so the CREATE TABLE right after this runs cleanly creates the
     * correct new schema. This is intentionally a drop, not an ALTER/
     * migrate-in-place: the old schema's single pooled number per day
     * has no way to know how to split into per-strategy capital, and at
     * this point (going live with fresh capital numbers anyway) starting
     * the ledger clean is the correct, safe behavior - not a workaround.
     */
    private void migrateOldSchemaIfNeeded() {
        try {
            Integer tableExists = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_news_capital_ledger'
                """, Integer.class);
            if (tableExists == null || tableExists == 0) return; // doesn't exist yet - nothing to migrate

            Integer hasStrategyColumn = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_news_capital_ledger'
                AND COLUMN_NAME = 'strategy_name'
                """, Integer.class);
            if (hasStrategyColumn != null && hasStrategyColumn > 0) return; // already correct schema

            log.warn("[AI-LEDGER] Detected OLD schema (pre-per-strategy, no strategy_name " +
                    "column) - dropping and recreating with the new schema. This is expected " +
                    "exactly once, on the first startup after this change; any pre-existing " +
                    "PAPER-mode test data in this table is intentionally discarded.");
            jdbc.execute("DROP TABLE ai_news_capital_ledger");
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

    /**
     * Ensures a row exists for (today, strategyName) - lazily, on first
     * touch, rather than pre-creating rows for a hardcoded list of known
     * strategies. Keeps this ledger fully generic about which strategies
     * actually exist.
     */
    private synchronized void ensureRowExists(String strategyName) {
        try {
            Integer exists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ai_news_capital_ledger WHERE trade_date = ? AND strategy_name = ?",
                    Integer.class, LocalDate.now(ZoneId.of("Asia/Kolkata")), strategyName);
            if (exists == null || exists == 0) {
                BigDecimal carryForward = computeYesterdayEndingCapital(strategyName);
                BigDecimal base = carryForward != null ? carryForward : defaultStartingCapital.get();
                jdbc.update("""
                    INSERT INTO ai_news_capital_ledger
                      (trade_date, strategy_name, starting_capital, margin_used, realised_pnl,
                       trades_count, wins_count, losses_count, updated_at)
                    VALUES (?, ?, ?, 0, 0, 0, 0, 0, ?)
                    """,
                        LocalDate.now(ZoneId.of("Asia/Kolkata")), strategyName, base, java.sql.Timestamp.from(Instant.now()));
                log.info("[AI-LEDGER] Initialised today's ledger for {} with starting capital Rs.{}",
                        strategyName, base);
            }
        } catch (Exception e) {
            log.warn("[AI-LEDGER] ensureRowExists failed for {} (non-fatal, will retry " +
                    "implicitly on next call): {}", strategyName, e.getMessage());
        }
    }

    private BigDecimal computeYesterdayEndingCapital(String strategyName) {
        try {
            Map<String, Object> row = jdbc.queryForMap("""
                SELECT starting_capital, realised_pnl FROM ai_news_capital_ledger
                WHERE trade_date < ? AND strategy_name = ? ORDER BY trade_date DESC LIMIT 1
                """, LocalDate.now(ZoneId.of("Asia/Kolkata")), strategyName);
            BigDecimal start = (BigDecimal) row.get("starting_capital");
            BigDecimal pnl   = (BigDecimal) row.get("realised_pnl");
            return start.add(pnl);
        } catch (Exception e) {
            return null; // no prior day for this strategy - first ever run
        }
    }

    /**
     * UI-facing: set (or change) a strategy's capital allocation. Intended
     * to be called from a dashboard action. Updates TODAY's starting_capital
     * directly - available capital recalculates immediately on the next
     * read (starting_capital + realised_pnl - margin_used). Margin already
     * in use and today's realised P&L are left untouched; this only changes
     * the base allocation, not what's already happened today.
     *
     * Safe to call before a strategy has ever run today (creates the row)
     * or any time after (updates it in place) - and the new value
     * automatically carries forward to tomorrow via the normal
     * computeYesterdayEndingCapital() path, with no separate persistence
     * step needed.
     */
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

    // =======================================================================
    // ENTRY - debit margin
    // =======================================================================

    /**
     * Called the moment a position is genuinely opened (PAPER: at simulated
     * fill; LIVE: only after broker fill confirmation) - never at signal
     * time, to avoid reserving capital for a trade that may never actually
     * execute.
     */
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

    // =======================================================================
    // EXIT - release margin, credit/debit realised P&L
    // =======================================================================

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

    // =======================================================================
    // READ-ONLY ACCESSORS - for dashboard / risk checks
    // =======================================================================

    /**
     * Available capital for ONE specific strategy. Each strategy now
     * carries its own independent starting capital, margin used, and
     * realised P&L - AI's available capital is completely unaffected by
     * News's trades, and vice versa.
     */
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

    /** All strategies' summaries for today, for a combined dashboard view. */
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

    // =======================================================================
    // DAILY/WEEKLY CLEANUP
    // =======================================================================

    @Scheduled(cron = "0 0 7 * * MON", zone = "Asia/Kolkata")
    public void weeklyCleanup() {
        try {
            // Keep 90 days of ledger entry history for audit purposes; the
            // daily summary table (ai_news_capital_ledger) is small and kept
            // indefinitely since it's just a few rows per day.
            jdbc.update("DELETE FROM ai_news_ledger_entries WHERE trade_date < ?",
                    LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(90));
        } catch (Exception e) {
            log.debug("[AI-LEDGER] Weekly cleanup failed (non-fatal): {}", e.getMessage());
        }
    }
}