package com.trading.dualentry.repository;

import com.trading.dualentry.domain.DualEntryTrade;
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
import java.util.UUID;

/**
 * DualEntryTradeRepository - own dedicated table (dual_entry_trades),
 * self-healing schema (same proven pattern as every other strategy
 * this session) - zero shared schema with Momentum or any existing
 * strategy, per explicit isolation requirement.
 */
@Repository
@Slf4j
public class DualEntryTradeRepository {

    private final JdbcTemplate jdbc;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public DualEntryTradeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void ensureTableExists() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS dual_entry_trades (
                trade_id            VARCHAR(40)   NOT NULL PRIMARY KEY,
                symbol              VARCHAR(30)   NOT NULL,
                sector              VARCHAR(60)   NOT NULL,
                sector_rank         INT           NOT NULL,
                direction           VARCHAR(6)    NOT NULL,
                entry_mode          VARCHAR(10)   NOT NULL,
                entry_price         DECIMAL(12,2) NOT NULL,
                stop_loss           DECIMAL(12,2) NOT NULL,
                target              DECIMAL(12,2) NOT NULL,
                current_trail_stop  DECIMAL(12,2),
                trailing_active     BOOLEAN       NOT NULL DEFAULT FALSE,
                consolidation_high  DECIMAL(12,2),
                consolidation_low   DECIMAL(12,2),
                quantity            INT           NOT NULL,
                entry_order_id      VARCHAR(40),
                exit_order_id       VARCHAR(40),
                exit_price          DECIMAL(12,2),
                exit_reason         VARCHAR(40),
                status              VARCHAR(10)   NOT NULL,
                trade_date          DATE          NOT NULL,
                entry_time          TIMESTAMP     NOT NULL,
                exit_time           TIMESTAMP
            )
            """);
    }

    public DualEntryTrade save(DualEntryTrade t) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO dual_entry_trades
            (trade_id, symbol, sector, sector_rank, direction, entry_mode, entry_price,
             stop_loss, target, consolidation_high, consolidation_low, quantity,
             entry_order_id, status, trade_date, entry_time)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?, 'ACTIVE', ?, ?)
            """, id, t.getSymbol(), t.getSector(), t.getSectorRank(), t.getDirection(),
                t.getEntryMode(), t.getEntryPrice(), t.getStopLoss(), t.getTarget(),
                t.getConsolidationHigh(), t.getConsolidationLow(), t.getQuantity(),
                t.getEntryOrderId(), LocalDate.now(IST), Timestamp.valueOf(LocalDateTime.now(IST)));
        return t.toBuilder().tradeId(id).status("ACTIVE")
                .tradeDate(LocalDate.now(IST)).entryTime(LocalDateTime.now(IST)).build();
    }

    public void updateTrailingStop(String tradeId, BigDecimal newStop) {
        jdbc.update("UPDATE dual_entry_trades SET trailing_active = TRUE, current_trail_stop = ? " +
                "WHERE trade_id = ?", newStop, tradeId);
    }

    public void markClosed(String tradeId, BigDecimal exitPrice, String exitOrderId, String reason) {
        jdbc.update("UPDATE dual_entry_trades SET status='CLOSED', exit_price=?, exit_order_id=?, " +
                        "exit_reason=?, exit_time=? WHERE trade_id=?", exitPrice, exitOrderId, reason,
                Timestamp.valueOf(LocalDateTime.now(IST)), tradeId);
    }

    public List<DualEntryTrade> findActive() {
        return jdbc.query("SELECT * FROM dual_entry_trades WHERE status IN ('ACTIVE','ENTRY_PENDING')",
                (rs, i) -> DualEntryTrade.builder()
                        .tradeId(rs.getString("trade_id")).symbol(rs.getString("symbol"))
                        .sector(rs.getString("sector")).sectorRank(rs.getInt("sector_rank"))
                        .direction(rs.getString("direction")).entryMode(rs.getString("entry_mode"))
                        .entryPrice(rs.getBigDecimal("entry_price")).stopLoss(rs.getBigDecimal("stop_loss"))
                        .target(rs.getBigDecimal("target"))
                        .currentTrailStop(rs.getBigDecimal("current_trail_stop"))
                        .trailingActive(rs.getBoolean("trailing_active"))
                        .consolidationHigh(rs.getBigDecimal("consolidation_high"))
                        .consolidationLow(rs.getBigDecimal("consolidation_low"))
                        .quantity(rs.getInt("quantity")).entryOrderId(rs.getString("entry_order_id"))
                        .status(rs.getString("status")).build());
    }

    public int countTradesToday() {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dual_entry_trades WHERE trade_date = ?", Integer.class, LocalDate.now(IST));
        return c == null ? 0 : c;
    }

    public List<DualEntryTrade> findToday() {
        return jdbc.query("SELECT * FROM dual_entry_trades WHERE trade_date = ? ORDER BY entry_time DESC",
                (rs, i) -> DualEntryTrade.builder()
                        .tradeId(rs.getString("trade_id")).symbol(rs.getString("symbol"))
                        .sector(rs.getString("sector")).sectorRank(rs.getInt("sector_rank"))
                        .direction(rs.getString("direction")).entryMode(rs.getString("entry_mode"))
                        .entryPrice(rs.getBigDecimal("entry_price")).stopLoss(rs.getBigDecimal("stop_loss"))
                        .target(rs.getBigDecimal("target")).quantity(rs.getInt("quantity"))
                        .status(rs.getString("status")).exitPrice(rs.getBigDecimal("exit_price"))
                        .exitReason(rs.getString("exit_reason")).build(),
                LocalDate.now(IST));
    }
}