package com.trading.herozero.repository;

import com.trading.herozero.domain.HeroZeroTrade;
import jakarta.annotation.PostConstruct;
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
 * HeroZeroTradeRepository - own dedicated table (hero_zero_trades),
 * completely independent of every other strategy's table (manual_swing_
 * trades, ai_trade_outcomes, news_scored_items, etc.) - confirmed zero
 * shared schema, per the spec's explicit independence requirement.
 */
@Repository
@Slf4j
public class HeroZeroTradeRepository {

    private final JdbcTemplate jdbc;

    public HeroZeroTradeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void ensureTableExists() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS hero_zero_trades (
                trade_id              VARCHAR(36)   PRIMARY KEY,
                strategy_name         VARCHAR(50)   NOT NULL,
                index_name            VARCHAR(20)   NOT NULL,
                monthly_expiry_date   DATE          NOT NULL,
                ce_trading_symbol     VARCHAR(40),
                pe_trading_symbol     VARCHAR(40),
                ce_strike             DECIMAL(10,2),
                pe_strike             DECIMAL(10,2),
                ce_premium            DECIMAL(10,2),
                pe_premium            DECIMAL(10,2),
                total_premium         DECIMAL(10,2),
                quantity              INT,
                entry_time            TIME,
                exit_time             TIME,
                ce_buy_order_id       VARCHAR(30),
                pe_buy_order_id       VARCHAR(30),
                ce_sell_order_id      VARCHAR(30),
                pe_sell_order_id      VARCHAR(30),
                trade_status          VARCHAR(20)   NOT NULL,
                exit_status           VARCHAR(20),
                ce_exit_price         DECIMAL(10,2),
                pe_exit_price         DECIMAL(10,2),
                pnl                   DECIMAL(12,2),
                exit_reason           VARCHAR(100),
                created_at            TIMESTAMP     NOT NULL,
                updated_at            TIMESTAMP     NOT NULL,
                INDEX idx_expiry_date (monthly_expiry_date),
                INDEX idx_index_expiry (index_name, monthly_expiry_date),
                INDEX idx_status (trade_status)
            )
            """);
    }

    private static final RowMapper<HeroZeroTrade> MAPPER = (rs, rowNum) -> HeroZeroTrade.builder()
            .tradeId(rs.getString("trade_id"))
            .strategyName(rs.getString("strategy_name"))
            .index(rs.getString("index_name"))
            .monthlyExpiryDate(rs.getDate("monthly_expiry_date").toLocalDate())
            .ceTradingSymbol(rs.getString("ce_trading_symbol"))
            .peTradingSymbol(rs.getString("pe_trading_symbol"))
            .ceStrike(rs.getBigDecimal("ce_strike"))
            .peStrike(rs.getBigDecimal("pe_strike"))
            .cePremium(rs.getBigDecimal("ce_premium"))
            .pePremium(rs.getBigDecimal("pe_premium"))
            .totalPremium(rs.getBigDecimal("total_premium"))
            .quantity(rs.getObject("quantity") != null ? rs.getInt("quantity") : null)
            .entryTime(rs.getTime("entry_time") != null ? rs.getTime("entry_time").toLocalTime() : null)
            .exitTime(rs.getTime("exit_time") != null ? rs.getTime("exit_time").toLocalTime() : null)
            .ceBuyOrderId(rs.getString("ce_buy_order_id"))
            .peBuyOrderId(rs.getString("pe_buy_order_id"))
            .ceSellOrderId(rs.getString("ce_sell_order_id"))
            .peSellOrderId(rs.getString("pe_sell_order_id"))
            .tradeStatus(rs.getString("trade_status"))
            .exitStatus(rs.getString("exit_status"))
            .ceExitPrice(rs.getBigDecimal("ce_exit_price"))
            .peExitPrice(rs.getBigDecimal("pe_exit_price"))
            .pnl(rs.getBigDecimal("pnl"))
            .exitReason(rs.getString("exit_reason"))
            .createdAt(rs.getTimestamp("created_at").toInstant())
            .updatedAt(rs.getTimestamp("updated_at").toInstant())
            .build();

    public void save(HeroZeroTrade t) {
        jdbc.update("""
            INSERT INTO hero_zero_trades
              (trade_id, strategy_name, index_name, monthly_expiry_date,
               ce_trading_symbol, pe_trading_symbol, ce_strike, pe_strike,
               ce_premium, pe_premium, total_premium, quantity,
               entry_time, exit_time, ce_buy_order_id, pe_buy_order_id,
               ce_sell_order_id, pe_sell_order_id, trade_status, exit_status,
               ce_exit_price, pe_exit_price, pnl, exit_reason,
               created_at, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
                t.getTradeId(), t.getStrategyName(), t.getIndex(),
                java.sql.Date.valueOf(t.getMonthlyExpiryDate()),
                t.getCeTradingSymbol(), t.getPeTradingSymbol(),
                t.getCeStrike(), t.getPeStrike(), t.getCePremium(), t.getPePremium(),
                t.getTotalPremium(), t.getQuantity(),
                t.getEntryTime() != null ? java.sql.Time.valueOf(t.getEntryTime()) : null,
                t.getExitTime() != null ? java.sql.Time.valueOf(t.getExitTime()) : null,
                t.getCeBuyOrderId(), t.getPeBuyOrderId(), t.getCeSellOrderId(), t.getPeSellOrderId(),
                t.getTradeStatus(), t.getExitStatus(),
                t.getCeExitPrice(), t.getPeExitPrice(), t.getPnl(), t.getExitReason(),
                Timestamp.from(t.getCreatedAt()), Timestamp.from(t.getUpdatedAt()));
    }

    public void updateStatus(String tradeId, String tradeStatus, String exitStatus, String exitReason) {
        jdbc.update("""
            UPDATE hero_zero_trades SET trade_status = ?, exit_status = ?,
              exit_reason = ?, updated_at = ? WHERE trade_id = ?
            """, tradeStatus, exitStatus, exitReason, Timestamp.from(Instant.now()), tradeId);
    }

    public void recordExit(String tradeId, BigDecimal ceExitPrice, BigDecimal peExitPrice,
                           BigDecimal pnl, String ceSellOrderId, String peSellOrderId,
                           LocalTime exitTime, String exitReason) {
        jdbc.update("""
            UPDATE hero_zero_trades SET
              ce_exit_price = ?, pe_exit_price = ?, pnl = ?,
              ce_sell_order_id = ?, pe_sell_order_id = ?, exit_time = ?,
              trade_status = 'EXITED', exit_status = 'COMPLETE',
              exit_reason = ?, updated_at = ?
            WHERE trade_id = ?
            """, ceExitPrice, peExitPrice, pnl, ceSellOrderId, peSellOrderId,
                java.sql.Time.valueOf(exitTime), exitReason, Timestamp.from(Instant.now()), tradeId);
    }

    public Optional<HeroZeroTrade> findById(String tradeId) {
        List<HeroZeroTrade> r = jdbc.query(
                "SELECT * FROM hero_zero_trades WHERE trade_id = ?", MAPPER, tradeId);
        return r.isEmpty() ? Optional.empty() : Optional.of(r.get(0));
    }

    public List<HeroZeroTrade> findActive() {
        return jdbc.query(
                "SELECT * FROM hero_zero_trades WHERE trade_status IN ('ACTIVE','ENTRY_PENDING')",
                MAPPER);
    }

    /** Duplicate-protection: has this index already had an entry attempt
     *  (any status) for this exact expiry date? Prevents a restart from
     *  re-entering the same trade twice. */
    public boolean existsTradeForExpiry(String index, LocalDate expiryDate) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM hero_zero_trades WHERE index_name = ? AND monthly_expiry_date = ?",
                Integer.class, index, java.sql.Date.valueOf(expiryDate));
        return count != null && count > 0;
    }

    public List<HeroZeroTrade> findAll() {
        return jdbc.query("SELECT * FROM hero_zero_trades ORDER BY created_at DESC", MAPPER);
    }
}