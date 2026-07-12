package com.trading.momentumstockofday.domain;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * MomentumTrade - a single, real, persisted trade taken by this
 * strategy. Own dedicated table (momentum_stock_of_day_trades) - zero
 * shared schema with any existing strategy.
 */
@Getter
@Builder(toBuilder = true)
public class MomentumTrade {
    private String tradeId;
    private String symbol;
    private String sector;
    private int sectorRank;
    private String direction;          // LONG or SHORT
    private BigDecimal entryPrice;
    private BigDecimal stopLoss;
    private BigDecimal target;
    private BigDecimal consolidationHigh;
    private BigDecimal consolidationLow;
    private int quantity;
    private String entryOrderId;
    private String exitOrderId;
    private BigDecimal exitPrice;
    private String status;             // ACTIVE, CLOSED
    private String exitReason;         // TARGET_HIT_TRAILING, SL_HIT, FORCE_EXIT
    private boolean trailingActive;
    private BigDecimal currentTrailStop;
    private LocalDate tradeDate;
    private LocalDateTime entryTime;
    private LocalDateTime exitTime;
}