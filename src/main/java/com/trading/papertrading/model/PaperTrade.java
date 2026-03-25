package com.trading.papertrading.model;

import com.trading.domain.enums.TradeDirection;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * In-memory paper trade — mirrors Trade entity but without JPA.
 * No DB writes: fast, no transaction overhead.
 */
@Data
@Builder
public class PaperTrade {

    private String         id;            // UUID
    private LocalDate      tradeDate;
    private String         tradingSymbol;
    private long           instrumentToken;
    private TradeDirection direction;

    // Entry
    private BigDecimal entryPrice;
    private BigDecimal fillPrice;         // simulated fill (with slippage)
    private int        quantity;
    private Instant    entryTime;
    private String     strategyName;
    private BigDecimal probabilityScore;

    // Risk levels
    private BigDecimal stopLoss;
    private BigDecimal target;

    // Exit
    private BigDecimal exitPrice;
    private Instant    exitTime;
    private String     exitReason;        // STOPLOSS, TARGET, TIME_EXIT, MANUAL
    private String     status;            // OPEN, CLOSED

    // P&L
    private BigDecimal grossPnl;
    private BigDecimal slippage;
    private BigDecimal brokerage;         // simulated: ₹40 flat
    private BigDecimal netPnl;

    // Management state
    private boolean slAtBreakeven;
    private boolean trailActive;
    private boolean halfExited;
    private int     remainingQty;
    private BigDecimal currentSl;         // live SL (may have moved)

    public boolean isOpen()   { return "OPEN".equals(status); }
    public boolean isClosed() { return "CLOSED".equals(status); }

    /** Returns net P&L for display */
    public String pnlDisplay() {
        if (netPnl == null) return "₹0";
        return (netPnl.doubleValue() >= 0 ? "+" : "") + "₹" +
                String.format("%.2f", netPnl.doubleValue());
    }
}