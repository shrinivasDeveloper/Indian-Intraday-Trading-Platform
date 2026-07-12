package com.trading.momentumstockofday.repository;

import com.trading.momentumstockofday.domain.MomentumTrade;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * MomentumTradeRepository - own dedicated table
 * (momentum_stock_of_day_trades), self-healing schema (same proven
 * pattern already used throughout this codebase for every other
 * strategy) - zero shared schema with any existing strategy.
 */
@Repository
@Slf4j
public class MomentumTradeRepository {

    private final JdbcTemplate jdbc;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public MomentumTradeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void ensureTableExists() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS momentum_stock_of_day_trades (
                trade_id            VARCHAR(40)   NOT NULL PRIMARY KEY,
                symbol              VARCHAR(30)   NOT NULL,
                sector              VARCHAR(60)   NOT NULL,
                sector_rank         INT           NOT NULL,
                direction           VARCHAR(6)    NOT NULL,
                entry_price         DECIMAL(12,2) NOT NULL,
                stop_loss           DECIMAL(12,2) NOT NULL,
                target              DECIMAL(12,2) NOT NULL,
                consolidation_high  DECIMAL(12,2),
                consolidation_low   DECIMAL(12,2),
                quantity            INT           NOT NULL,
                entry_order_id      VARCHAR(40),
                exit_order_id       VARCHAR(40),
                exit_price          DECIMAL(12,2),
                status              VARCHAR(10)   NOT NULL DEFAULT 'ACTIVE',
                exit_reason         VARCHAR(40),
                trailing_active     BOOLEAN       NOT NULL DEFAULT FALSE,
                current_trail_stop  DECIMAL(12,2),
                trade_date          DATE          NOT NULL,
                entry_time          TIMESTAMP     NOT NULL,
                exit_time           TIMESTAMP,
                INDEX idx_trade_date (trade_date),
                INDEX idx_status (status)
            )
            """);
    }

    public MomentumTrade save(MomentumTrade t) {
        String id = t.getTradeId() != null ? t.getTradeId() : UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO momentum_stock_of_day_trades
                (trade_id, symbol, sector, sector_rank, direction, entry_price, stop_loss,
                 target, consolidation_high, consolidation_low, quantity, entry_order_id,
                 status, trailing_active, trade_date, entry_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', FALSE, ?, ?)
            """, id, t.getSymbol(), t.getSector(), t.getSectorRank(), t.getDirection(),
                t.getEntryPrice(), t.getStopLoss(), t.getTarget(),
                t.getConsolidationHigh(), t.getConsolidationLow(), t.getQuantity(),
                t.getEntryOrderId(), Timestamp.valueOf(LocalDate.now(IST).atStartOfDay()),
                Timestamp.valueOf(LocalDateTime.now(IST)));
        return t.toBuilder().tradeId(id).build();
    }

    public void updateTrailingStop(String tradeId, BigDecimal newTrailStop) {
        jdbc.update("""
            UPDATE momentum_stock_of_day_trades
            SET trailing_active = TRUE, current_trail_stop = ?
            WHERE trade_id = ?
            """, newTrailStop, tradeId);
    }

    public void markClosed(String tradeId, BigDecimal exitPrice, String exitOrderId, String reason) {
        jdbc.update("""
            UPDATE momentum_stock_of_day_trades
            SET status = 'CLOSED', exit_price = ?, exit_order_id = ?, exit_reason = ?,
                exit_time = ?
            WHERE trade_id = ?
            """, exitPrice, exitOrderId, reason, Timestamp.valueOf(LocalDateTime.now(IST)), tradeId);
    }

    public List<MomentumTrade> findActive() {
        return jdbc.query(
                "SELECT * FROM momentum_stock_of_day_trades WHERE status = 'ACTIVE'",
                this::mapRow);
    }

    public boolean existsTradeToday() {
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM momentum_stock_of_day_trades WHERE trade_date = ?",
                    Integer.class, LocalDate.now(IST));
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
    }

    public List<MomentumTrade> findToday() {
        return jdbc.query(
                "SELECT * FROM momentum_stock_of_day_trades WHERE trade_date = ?",
                this::mapRow, LocalDate.now(IST));
    }

    /** FIX (per explicit user request: "Hero-zero and momentum, all
     *  the strategies will show, not only AI and News"). Returns
     *  today's realised P&L (CLOSED trades only) for the dashboard's
     *  cross-strategy aggregation. Computed in Java, direction-aware,
     *  from the already-tested findToday() results - avoids a complex
     *  conditional SQL expression for a simple, already-available
     *  calculation. */
    public BigDecimal getTodaysRealisedPnl() {
        BigDecimal total = BigDecimal.ZERO;
        for (MomentumTrade t : findToday()) {
            if (!"CLOSED".equals(t.getStatus()) || t.getExitPrice() == null) continue;
            boolean isLong = "LONG".equals(t.getDirection());
            BigDecimal diff = isLong
                    ? t.getExitPrice().subtract(t.getEntryPrice())
                    : t.getEntryPrice().subtract(t.getExitPrice());
            total = total.add(diff.multiply(BigDecimal.valueOf(t.getQuantity())));
        }
        return total;
    }

    public int getTodaysTradeCount() {
        return findToday().size();
    }

    private MomentumTrade mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return MomentumTrade.builder()
                .tradeId(rs.getString("trade_id"))
                .symbol(rs.getString("symbol"))
                .sector(rs.getString("sector"))
                .sectorRank(rs.getInt("sector_rank"))
                .direction(rs.getString("direction"))
                .entryPrice(rs.getBigDecimal("entry_price"))
                .stopLoss(rs.getBigDecimal("stop_loss"))
                .target(rs.getBigDecimal("target"))
                .consolidationHigh(rs.getBigDecimal("consolidation_high"))
                .consolidationLow(rs.getBigDecimal("consolidation_low"))
                .quantity(rs.getInt("quantity"))
                .entryOrderId(rs.getString("entry_order_id"))
                .exitOrderId(rs.getString("exit_order_id"))
                .exitPrice(rs.getBigDecimal("exit_price"))
                .status(rs.getString("status"))
                .exitReason(rs.getString("exit_reason"))
                .trailingActive(rs.getBoolean("trailing_active"))
                .currentTrailStop(rs.getBigDecimal("current_trail_stop"))
                .tradeDate(rs.getDate("trade_date").toLocalDate())
                .entryTime(rs.getTimestamp("entry_time").toLocalDateTime())
                .exitTime(rs.getTimestamp("exit_time") != null
                        ? rs.getTimestamp("exit_time").toLocalDateTime() : null)
                .build();
    }
}