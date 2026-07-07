package com.trading.swing.repository;

import com.trading.swing.domain.ManualSwingTrade;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * ManualSwingTradeRepository - own table (manual_swing_trades), own schema,
 * zero foreign keys into any existing table, zero shared code with the
 * AI/News ledger or order-lock tables. Genuinely independent persistence.
 *
 * Duplicate-protection design (see class doc on ManualSwingTradingService
 * for the full picture): SELL idempotency is enforced via a conditional
 * UPDATE - "claim" a trade for selling with
 * UPDATE ... SET sell_status='ORDER_PLACED' WHERE trade_id=? AND
 * sell_status='PENDING' - and check the affected-row count. If another
 * scheduler run already claimed it, this UPDATE affects 0 rows and the
 * caller correctly backs off. This is simpler and just as robust as a
 * separate lock table for this specific concern, and it's naturally
 * restart-safe since sell_status is the persisted source of truth.
 */
@Repository
@Slf4j
public class ManualSwingTradeRepository {

    private final JdbcTemplate jdbc;

    public ManualSwingTradeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        ensureTableExists();
        migrateTradeSourceColumnIfNeeded();
    }

    /**
     * FIX (learned from an earlier mistake in this same session with
     * AiNewsCapitalLedger): the manual_swing_trades table may already
     * exist from before this auto-selection feature was added - without
     * the trade_source column. CREATE TABLE IF NOT EXISTS does nothing
     * in that case, since the table already exists. Detects this
     * precisely via information_schema and adds the column with a
     * default if missing, rather than assuming a fresh table.
     */
    private void migrateTradeSourceColumnIfNeeded() {
        try {
            Integer hasColumn = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'manual_swing_trades'
                AND COLUMN_NAME = 'trade_source'
                """, Integer.class);
            if (hasColumn != null && hasColumn > 0) return; // already correct schema

            log.warn("[SWING-REPO] manual_swing_trades exists without trade_source column - " +
                    "adding it now (defaulting existing rows to MANUAL, since they predate the " +
                    "auto-selection feature and were all genuinely manual buys)");
            jdbc.execute("ALTER TABLE manual_swing_trades " +
                    "ADD COLUMN trade_source VARCHAR(10) NOT NULL DEFAULT 'MANUAL'");
        } catch (Exception e) {
            log.warn("[SWING-REPO] trade_source migration check failed (non-fatal): {}", e.getMessage());
        }
    }

    private void ensureTableExists() {
        try {
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS manual_swing_trades (
                    trade_id              VARCHAR(36)   NOT NULL PRIMARY KEY,
                    symbol                VARCHAR(30)   NOT NULL,
                    company_name          VARCHAR(150),
                    exchange              VARCHAR(10)   NOT NULL,
                    quantity              INT           NOT NULL,
                    buy_price             DECIMAL(14,2) NOT NULL,
                    buy_date              DATE          NOT NULL,
                    buy_time              TIME          NOT NULL,
                    target_pct            DECIMAL(8,4),
                    target_price          DECIMAL(14,2) NOT NULL,
                    zerodha_buy_order_id  VARCHAR(30)   NOT NULL,
                    zerodha_sell_order_id VARCHAR(30),
                    sell_price            DECIMAL(14,2),
                    product_type          VARCHAR(10)   NOT NULL DEFAULT 'CNC',
                    trade_source          VARCHAR(10)   NOT NULL DEFAULT 'MANUAL',
                    trade_status          VARCHAR(10)   NOT NULL DEFAULT 'ACTIVE',
                    sell_status           VARCHAR(15)   NOT NULL DEFAULT 'PENDING',
                    exit_reason           VARCHAR(30),
                    created_at            TIMESTAMP     NOT NULL,
                    updated_at            TIMESTAMP     NOT NULL,
                    INDEX idx_status (trade_status),
                    INDEX idx_buy_date (buy_date)
                )
                """);
        } catch (Exception e) {
            log.error("[SWING-REPO] Could not create manual_swing_trades table - this module " +
                    "will not function correctly until this is resolved: {}", e.getMessage());
        }
    }

    private static final RowMapper<ManualSwingTrade> MAPPER = (rs, i) -> ManualSwingTrade.builder()
            .tradeId(rs.getString("trade_id"))
            .symbol(rs.getString("symbol"))
            .companyName(rs.getString("company_name"))
            .exchange(rs.getString("exchange"))
            .quantity(rs.getInt("quantity"))
            .buyPrice(rs.getBigDecimal("buy_price"))
            .buyDate(rs.getDate("buy_date").toLocalDate())
            .buyTime(rs.getTime("buy_time").toLocalTime())
            .targetPct(rs.getBigDecimal("target_pct"))
            .targetPrice(rs.getBigDecimal("target_price"))
            .zerodhaBuyOrderId(rs.getString("zerodha_buy_order_id"))
            .zerodhaSellOrderId(rs.getString("zerodha_sell_order_id"))
            .sellPrice(rs.getBigDecimal("sell_price"))
            .productType(rs.getString("product_type"))
            .tradeSource(ManualSwingTrade.TradeSource.valueOf(rs.getString("trade_source")))
            .tradeStatus(ManualSwingTrade.TradeStatus.valueOf(rs.getString("trade_status")))
            .sellStatus(ManualSwingTrade.SellStatus.valueOf(rs.getString("sell_status")))
            .exitReason(rs.getString("exit_reason"))
            .createdAt(rs.getTimestamp("created_at").toInstant())
            .updatedAt(rs.getTimestamp("updated_at").toInstant())
            .build();

    public void save(ManualSwingTrade t) {
        jdbc.update("""
            INSERT INTO manual_swing_trades
              (trade_id, symbol, company_name, exchange, quantity, buy_price, buy_date, buy_time,
               target_pct, target_price, zerodha_buy_order_id, zerodha_sell_order_id, sell_price,
               product_type, trade_source, trade_status, sell_status, exit_reason, created_at, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
                t.getTradeId(), t.getSymbol(), t.getCompanyName(), t.getExchange(),
                t.getQuantity(), t.getBuyPrice(), java.sql.Date.valueOf(t.getBuyDate()),
                java.sql.Time.valueOf(t.getBuyTime()), t.getTargetPct(), t.getTargetPrice(),
                t.getZerodhaBuyOrderId(), t.getZerodhaSellOrderId(), t.getSellPrice(),
                t.getProductType(), t.getTradeSource().name(), t.getTradeStatus().name(),
                t.getSellStatus().name(), t.getExitReason(),
                Timestamp.from(t.getCreatedAt()), Timestamp.from(t.getUpdatedAt()));
    }

    /**
     * The actual gate for the auto-selection feature: "if a MANUAL trade
     * already exists today, never place an automated one." Deliberately
     * checks trade_source = 'MANUAL' specifically, not just "any trade
     * today" - an earlier AUTO trade today (which shouldn't normally
     * happen, since the auto-engine only fires once at 3pm, but this
     * stays correct even if it were ever called twice) must not be
     * mistaken for a manual one.
     */
    public boolean existsManualTradeToday() {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM manual_swing_trades
            WHERE buy_date = ? AND trade_source = 'MANUAL'
            """, Integer.class, java.sql.Date.valueOf(LocalDate.now()));
        return count != null && count > 0;
    }

    public boolean existsAutoTradeToday() {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM manual_swing_trades
            WHERE buy_date = ? AND trade_source = 'AUTO'
            """, Integer.class, java.sql.Date.valueOf(LocalDate.now()));
        return count != null && count > 0;
    }

    /**
     * Most recent buy_date for this symbol, across BOTH manual and auto
     * trades - needed for the 10-trading-day cooling period (per
     * explicit instruction: "if we traded today don't trade again,
     * cooling period is 10 trading days"). Returns empty if this
     * symbol has never been traded before. Purely additive - does not
     * touch existsManualTradeToday/existsAutoTradeToday or any other
     * existing method.
     */
    public Optional<LocalDate> findMostRecentBuyDate(String symbol) {
        List<java.sql.Date> dates = jdbc.query(
                "SELECT buy_date FROM manual_swing_trades WHERE symbol = ? " +
                        "ORDER BY buy_date DESC LIMIT 1",
                (rs, rowNum) -> rs.getDate("buy_date"), symbol);
        return dates.isEmpty() ? Optional.empty() : Optional.of(dates.get(0).toLocalDate());
    }

    public Optional<ManualSwingTrade> findById(String tradeId) {
        List<ManualSwingTrade> r = jdbc.query(
                "SELECT * FROM manual_swing_trades WHERE trade_id = ?", MAPPER, tradeId);
        return r.isEmpty() ? Optional.empty() : Optional.of(r.get(0));
    }

    public List<ManualSwingTrade> findActive() {
        return jdbc.query(
                "SELECT * FROM manual_swing_trades WHERE trade_status = 'ACTIVE' ORDER BY created_at",
                MAPPER);
    }

    public List<ManualSwingTrade> findAll() {
        return jdbc.query("SELECT * FROM manual_swing_trades ORDER BY created_at DESC", MAPPER);
    }

    /**
     * Atomically claims a trade for selling. Returns true only if THIS call
     * was the one that transitioned PENDING -> ORDER_PLACED - the actual
     * duplicate-protection mechanism. If another scheduler run (or a retry
     * racing with itself) already claimed it, this returns false and the
     * caller must back off, not retry.
     */
    public boolean claimForSell(String tradeId) {
        int rows = jdbc.update("""
            UPDATE manual_swing_trades
            SET sell_status = 'ORDER_PLACED', updated_at = ?
            WHERE trade_id = ? AND sell_status = 'PENDING'
            """, Timestamp.from(Instant.now()), tradeId);
        return rows == 1;
    }

    public void recordSellOrderId(String tradeId, String zerodhaSellOrderId) {
        jdbc.update("""
            UPDATE manual_swing_trades
            SET zerodha_sell_order_id = ?, updated_at = ?
            WHERE trade_id = ?
            """, zerodhaSellOrderId, Timestamp.from(Instant.now()), tradeId);
    }

    public void markSellCompleted(String tradeId, BigDecimal sellPrice, String exitReason) {
        jdbc.update("""
            UPDATE manual_swing_trades
            SET sell_price = ?, trade_status = 'CLOSED', sell_status = 'COMPLETED',
                exit_reason = ?, updated_at = ?
            WHERE trade_id = ?
            """, sellPrice, exitReason, Timestamp.from(Instant.now()), tradeId);
    }

    /**
     * FIX (found from direct user report): the app previously had ZERO
     * way to detect a position that was closed OUTSIDE its own sell
     * flow (e.g. manually via Zerodha's own app/web interface directly),
     * bypassing this app entirely - it would show ACTIVE forever, with
     * no automatic correction. Called by HoldingsReconciliationService
     * when a trade the app believes is ACTIVE is confirmed to no longer
     * appear in the broker's real holdings. sell_price is left NULL
     * since the app was never told the real exit price - this is
     * explicitly a "the broker says this is gone, correcting our stale
     * record" action, not a normal, price-known sell completion.
     */
    public void markClosedExternally(String tradeId) {
        jdbc.update("""
            UPDATE manual_swing_trades
            SET trade_status = 'CLOSED', sell_status = 'COMPLETED',
                exit_reason = 'CLOSED_EXTERNALLY_RECONCILED', updated_at = ?
            WHERE trade_id = ?
            """, Timestamp.from(Instant.now()), tradeId);
    }

    /**
     * Sell attempt failed (order rejected, API error, etc). Reverts
     * sell_status back to PENDING so the NEXT monitoring cycle retries -
     * trade_status stays ACTIVE throughout, per the spec's error-handling
     * requirement ("keep the trade ACTIVE... retry during next cycle").
     */
    public void markSellFailed(String tradeId, String reason) {
        jdbc.update("""
            UPDATE manual_swing_trades
            SET sell_status = 'PENDING', updated_at = ?
            WHERE trade_id = ?
            """, Timestamp.from(Instant.now()), tradeId);
        log.warn("[SWING-REPO] Sell attempt failed for {}, reverted to PENDING for retry: {}",
                tradeId, reason);
    }

    /**
     * Used only on startup, for trades found stuck in ORDER_PLACED (an
     * order was placed but the app crashed/restarted before the fill was
     * confirmed). NEVER blindly resets to PENDING - the caller must check
     * the real broker order status first and only call this if the order
     * genuinely never filled.
     */
    public List<ManualSwingTrade> findStuckOrderPlaced() {
        return jdbc.query(
                "SELECT * FROM manual_swing_trades WHERE trade_status = 'ACTIVE' " +
                        "AND sell_status = 'ORDER_PLACED'", MAPPER);
    }

    public void revertStuckSellToPending(String tradeId) {
        jdbc.update("""
            UPDATE manual_swing_trades
            SET sell_status = 'PENDING', updated_at = ?
            WHERE trade_id = ? AND sell_status = 'ORDER_PLACED'
            """, Timestamp.from(Instant.now()), tradeId);
    }
}