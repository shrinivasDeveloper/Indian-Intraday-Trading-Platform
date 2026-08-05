package com.trading.dualentry.domain;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * DualEntryTrade - a single, real, persisted trade taken by the new
 * Dual-Entry (Breakout + Pullback) strategy. Own dedicated table
 * (dual_entry_trades) - zero shared schema with Momentum or any
 * existing strategy, per explicit isolation requirement.
 */
@Getter
@Builder(toBuilder = true)
public class DualEntryTrade {
    private String tradeId;
    private String symbol;
    private String sector;
    private Integer sectorRank;
    private String direction;
    private String entryMode; // BREAKOUT or PULLBACK
    private BigDecimal entryPrice;
    private BigDecimal stopLoss;
    private BigDecimal target;
    private BigDecimal currentTrailStop;
    private boolean trailingActive;
    private BigDecimal consolidationHigh;
    private BigDecimal consolidationLow;
    private int quantity;
    private String entryOrderId;
    private String exitOrderId;
    private BigDecimal exitPrice;
    private String exitReason;
    private String status; // ACTIVE, CLOSED
    private LocalDate tradeDate;
    private LocalDateTime entryTime;
    private LocalDateTime exitTime;
}