package com.trading.swing.auto.repository;

import com.trading.swing.auto.domain.DailyBar;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * DailyBarRepository - own table (swing_auto_daily_bars), storing the
 * full NSE bhavcopy history this auto-selection feature needs for
 * sector performance and momentum analysis. At full backfill (~252
 * trading days x the full NSE equity list), this reaches several
 * million rows - indexed and batch-inserted accordingly.
 */
@Repository
@Slf4j
public class DailyBarRepository {

    private final JdbcTemplate jdbc;

    public DailyBarRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        ensureTableExists();
    }

    private void ensureTableExists() {
        try {
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS swing_auto_daily_bars (
                    symbol      VARCHAR(30)   NOT NULL,
                    trade_date  DATE          NOT NULL,
                    open_price  DECIMAL(14,2) NOT NULL,
                    high_price  DECIMAL(14,2) NOT NULL,
                    low_price   DECIMAL(14,2) NOT NULL,
                    close_price DECIMAL(14,2) NOT NULL,
                    volume      BIGINT        NOT NULL,
                    series      VARCHAR(5)    NOT NULL,
                    PRIMARY KEY (symbol, trade_date),
                    INDEX idx_date (trade_date)
                )
                """);
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS swing_auto_backfill_progress (
                    id                 INT PRIMARY KEY DEFAULT 1,
                    earliest_date_done DATE,
                    latest_date_done   DATE,
                    updated_at         TIMESTAMP NOT NULL
                )
                """);
        } catch (Exception e) {
            log.error("[SWING-AUTO-REPO] Could not create bhavcopy storage tables: {}", e.getMessage());
        }
    }

    private static final RowMapper<DailyBar> MAPPER = (rs, i) -> new DailyBar(
            rs.getString("symbol"),
            rs.getDate("trade_date").toLocalDate(),
            rs.getBigDecimal("open_price"),
            rs.getBigDecimal("high_price"),
            rs.getBigDecimal("low_price"),
            rs.getBigDecimal("close_price"),
            rs.getLong("volume"),
            rs.getString("series")
    );

    /**
     * Bulk insert for one day's worth of bars (one parsed bhavcopy file
     * = thousands of rows). Uses batchUpdate for efficiency rather than
     * one INSERT per row. ON DUPLICATE KEY ignores re-inserts of a date
     * already stored (idempotent - safe to re-run for the same date).
     */
    public void saveAll(List<DailyBar> bars) {
        if (bars.isEmpty()) return;
        jdbc.batchUpdate("""
            INSERT INTO swing_auto_daily_bars
              (symbol, trade_date, open_price, high_price, low_price, close_price, volume, series)
            VALUES (?,?,?,?,?,?,?,?)
            ON DUPLICATE KEY UPDATE
              open_price=VALUES(open_price), high_price=VALUES(high_price),
              low_price=VALUES(low_price), close_price=VALUES(close_price),
              volume=VALUES(volume), series=VALUES(series)
            """,
                bars.stream().map(b -> new Object[]{
                        b.symbol(), java.sql.Date.valueOf(b.tradeDate()),
                        b.open(), b.high(), b.low(), b.close(), b.volume(), b.series()
                }).toList());
        log.info("[SWING-AUTO-REPO] Saved {} bars for {}", bars.size(), bars.get(0).tradeDate());
    }

    public List<DailyBar> findBySymbol(String symbol, LocalDate fromDate) {
        return jdbc.query("""
            SELECT * FROM swing_auto_daily_bars
            WHERE symbol = ? AND trade_date >= ?
            ORDER BY trade_date
            """, MAPPER, symbol, java.sql.Date.valueOf(fromDate));
    }

    public List<DailyBar> findBySymbolsAndDate(List<String> symbols, LocalDate date) {
        if (symbols.isEmpty()) return List.of();
        String placeholders = String.join(",", symbols.stream().map(s -> "?").toList());
        Object[] params = new Object[symbols.size() + 1];
        for (int i = 0; i < symbols.size(); i++) params[i] = symbols.get(i);
        params[symbols.size()] = java.sql.Date.valueOf(date);
        return jdbc.query(
                "SELECT * FROM swing_auto_daily_bars WHERE symbol IN (" + placeholders +
                        ") AND trade_date = ?", MAPPER, params);
    }

    /**
     * Counts the number of DISTINCT REAL trading days strictly between
     * fromDate (exclusive) and toDate (inclusive), using this table's
     * actual bhavcopy calendar rather than naive calendar-day
     * subtraction - correctly excludes weekends and market holidays,
     * since only genuine trading days ever appear in this table at all.
     * Needed for the 10-trading-day cooling period (per explicit
     * instruction). Uses the whole table's distinct dates (not scoped
     * to one symbol) since the market-wide trading calendar is the
     * same for every stock.
     */
    public int countTradingDaysBetween(LocalDate fromDateExclusive, LocalDate toDateInclusive) {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(DISTINCT trade_date) FROM swing_auto_daily_bars
            WHERE trade_date > ? AND trade_date <= ?
            """, Integer.class,
                java.sql.Date.valueOf(fromDateExclusive), java.sql.Date.valueOf(toDateInclusive));
        return count != null ? count : 0;
    }

    public boolean hasDataForDate(LocalDate date) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM swing_auto_daily_bars WHERE trade_date = ?",
                Integer.class, java.sql.Date.valueOf(date));
        return count != null && count > 0;
    }

    public LocalDate findEarliestDateStored() {
        try {
            java.sql.Date d = jdbc.queryForObject(
                    "SELECT MIN(trade_date) FROM swing_auto_daily_bars", java.sql.Date.class);
            return d != null ? d.toLocalDate() : null;
        } catch (Exception e) {
            return null;
        }
    }

    public int countDistinctDatesStored() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT trade_date) FROM swing_auto_daily_bars", Integer.class);
        return count != null ? count : 0;
    }
}