package com.trading.swing.domain;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * ManualSwingTrade — the single source of truth for one manual CNC swing
 * trade, end to end: BUY → (skip purchase day) → monitor from next trading
 * day → SELL (target hit, or 9:20 AM force-exit) → CLOSED.
 *
 * Deliberately a plain @Value POJO (immutable, Lombok-generated getters),
 * not a JPA @Entity — consistent with how every other trading table in
 * this codebase is handled (JdbcTemplate + CREATE TABLE IF NOT EXISTS,
 * e.g. AiNewsCapitalLedger), and required anyway since the project runs
 * with spring.jpa.hibernate.ddl-auto: none.
 */
@Value
@Builder(toBuilder = true)
public class ManualSwingTrade {

    String      tradeId;            // UUID, generated at BUY time
    String      symbol;             // NSE trading symbol, e.g. "RELIANCE"
    String      companyName;        // from Zerodha instrument master
    String      exchange;           // NSE/BSE

    int         quantity;
    BigDecimal  buyPrice;           // ACTUAL fill price, not the price shown at click time
    LocalDate   buyDate;
    LocalTime   buyTime;

    BigDecimal  targetPct;          // nullable — user can specify either pct or price
    BigDecimal  targetPrice;        // ALWAYS resolved to an absolute price at BUY time,
    // even if the user entered a percentage, so monitoring
    // never needs to recompute from a stale buyPrice

    String      zerodhaBuyOrderId;
    String      zerodhaSellOrderId; // null until a sell order is actually placed
    BigDecimal  sellPrice;          // ACTUAL fill price, null until actually sold

    String      productType;        // always "CNC" — enforced, never settable to anything else
    TradeSource tradeSource;        // MANUAL (UI buy click) or AUTO (selection engine)

    TradeStatus tradeStatus;        // ACTIVE / CLOSED
    SellStatus  sellStatus;         // PENDING / ORDER_PLACED / COMPLETED / FAILED
    String      exitReason;         // null until closed: "TARGET_HIT" / "FORCE_EXIT_9_20" / "MANUAL"

    Instant     createdAt;
    Instant     updatedAt;

    public enum TradeStatus { ACTIVE, CLOSED }
    public enum SellStatus  { PENDING, ORDER_PLACED, COMPLETED, FAILED }
    public enum TradeSource { MANUAL, AUTO }
}