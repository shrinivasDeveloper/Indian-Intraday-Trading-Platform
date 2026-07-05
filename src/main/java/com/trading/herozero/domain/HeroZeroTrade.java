package com.trading.herozero.domain;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * HeroZeroTrade - the single source of truth for one Monthly Expiry
 * "Hero or Zero" trade (one CE buy + one PE buy, same index, same
 * expiry, same entry cycle).
 *
 * INDEPENDENCE: this class has ZERO imports from any other strategy
 * package (com.trading.ai, com.trading.strategy.news,
 * com.trading.swing) - confirmed by design, not just by omission.
 * Every field below matches the spec's exact "Database Persistence"
 * list, nothing added, nothing omitted.
 */
@Getter
@Setter
@Builder
public class HeroZeroTrade {

    private String tradeId;                 // UUID, generated at creation
    private String strategyName;            // constant: "HERO_OR_ZERO_MONTHLY_EXPIRY"
    private String index;                   // NIFTY / BANKNIFTY / FINNIFTY / MIDCPNIFTY / SENSEX
    private LocalDate monthlyExpiryDate;    // the (possibly holiday-shifted) actual trading date

    private String ceTradingSymbol;
    private String peTradingSymbol;
    private BigDecimal ceStrike;
    private BigDecimal peStrike;
    private BigDecimal cePremium;           // actual fill price
    private BigDecimal pePremium;           // actual fill price
    private BigDecimal totalPremium;        // cePremium + pePremium (per lot, at entry)

    private Integer quantity;               // lot size x lots

    private LocalTime entryTime;
    private LocalTime exitTime;

    private String ceBuyOrderId;
    private String peBuyOrderId;
    private String ceSellOrderId;
    private String peSellOrderId;

    /** ACTIVE, ENTRY_FAILED, ENTRY_PENDING, EXITED, EXIT_FAILED, SKIPPED */
    private String tradeStatus;

    /** PENDING, PLACED, COMPLETE, FAILED - independent from tradeStatus,
     *  since entry can succeed while exit is still pending. */
    private String exitStatus;

    private BigDecimal ceExitPrice;
    private BigDecimal peExitPrice;
    private BigDecimal pnl;                 // realized P&L after both legs exit

    /** Human-readable reason: "MANDATORY_3_10_EXIT", "SKIP_NOT_MONTHLY_EXPIRY",
     *  "SKIP_HOLIDAY", "SKIP_NO_LIQUID_STRIKE", "SKIP_MARKET_DATA_UNAVAILABLE",
     *  "SKIP_ORDER_PLACEMENT_FAILED", "SKIP_CIRCUIT_BREAKER" etc. */
    private String exitReason;

    private Instant createdAt;
    private Instant updatedAt;
}