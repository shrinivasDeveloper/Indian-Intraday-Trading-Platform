package com.trading.momentumstockofday.repository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;

/**
 * MomentumCapitalRepository - UI-settable capital for this strategy.
 *
 * INDEPENDENCE (per explicit requirement): own dedicated table
 * (momentum_capital_allocation), zero shared schema or dependency with
 * AI/News's AiNewsCapitalLedger. Mirrors that same proven "fixed
 * capital, settable from the UI" pattern already established this
 * session, but as a genuinely separate, standalone table - Momentum
 * never reads or depends on AI/News's ledger in any way.
 */
@Repository
@Slf4j
public class MomentumCapitalRepository {

    private final JdbcTemplate jdbc;
    private static final BigDecimal DEFAULT_CAPITAL = BigDecimal.valueOf(10000.0);

    public MomentumCapitalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void ensureTableExists() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS momentum_capital_allocation (
                id                INT PRIMARY KEY DEFAULT 1,
                allocated_capital DECIMAL(12,2) NOT NULL DEFAULT 10000.00,
                updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                                  ON UPDATE CURRENT_TIMESTAMP,
                CONSTRAINT single_row CHECK (id = 1)
            )
            """);
        // Ensure exactly one row always exists, so getCapital() never
        // needs to handle an empty-table case.
        jdbc.update("""
            INSERT IGNORE INTO momentum_capital_allocation (id, allocated_capital)
            VALUES (1, ?)
            """, DEFAULT_CAPITAL);
    }

    /** Returns the currently-allocated capital, as set via the UI -
     *  same "fixed per trade, not a depleting balance" model already
     *  proven for AI/News, applied independently here. */
    public BigDecimal getCapital() {
        try {
            BigDecimal capital = jdbc.queryForObject(
                    "SELECT allocated_capital FROM momentum_capital_allocation WHERE id = 1",
                    BigDecimal.class);
            return capital != null ? capital : DEFAULT_CAPITAL;
        } catch (Exception e) {
            log.warn("[MOMENTUM-CAPITAL] Failed to read allocated capital - using default " +
                    "Rs.{}: {}", DEFAULT_CAPITAL, e.getMessage());
            return DEFAULT_CAPITAL;
        }
    }

    public void setCapital(BigDecimal newCapital) {
        jdbc.update("UPDATE momentum_capital_allocation SET allocated_capital = ? WHERE id = 1",
                newCapital);
        log.info("[MOMENTUM-CAPITAL] Allocated capital updated to Rs.{}", newCapital);
    }
}