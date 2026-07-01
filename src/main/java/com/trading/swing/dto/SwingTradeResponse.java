package com.trading.swing.dto;

import com.trading.swing.domain.ManualSwingTrade;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * What the UI actually displays — the persisted trade plus currentPrice
 * (live, looked up at response time — never persisted) and a correctly
 * computed PnL: UNREALIZED for ACTIVE trades (vs current price), REALIZED
 * for CLOSED trades (vs actual sell price) — never mixing the two.
 */
public record SwingTradeResponse(
        String tradeId,
        String symbol,
        String companyName,
        String exchange,
        int quantity,
        BigDecimal buyPrice,
        BigDecimal currentPrice,
        BigDecimal sellPrice,
        BigDecimal targetPrice,
        BigDecimal targetPct,
        BigDecimal investedAmount,
        BigDecimal pnl,             // unrealized if ACTIVE, realized if CLOSED
        BigDecimal pnlPct,
        boolean realized,
        LocalDate buyDate,
        LocalTime buyTime,
        String tradeStatus,
        String sellStatus,
        String exitReason
) {
    public static SwingTradeResponse from(ManualSwingTrade t, BigDecimal currentPrice) {
        BigDecimal invested = t.getBuyPrice().multiply(BigDecimal.valueOf(t.getQuantity()));
        boolean isClosed = t.getTradeStatus() == ManualSwingTrade.TradeStatus.CLOSED;
        BigDecimal referencePrice = isClosed ? t.getSellPrice() : currentPrice;

        BigDecimal pnl = null, pnlPct = null;
        if (referencePrice != null) {
            pnl = referencePrice.subtract(t.getBuyPrice()).multiply(BigDecimal.valueOf(t.getQuantity()));
            if (t.getBuyPrice().signum() > 0) {
                pnlPct = referencePrice.subtract(t.getBuyPrice())
                        .divide(t.getBuyPrice(), 6, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
            }
        }

        return new SwingTradeResponse(
                t.getTradeId(), t.getSymbol(), t.getCompanyName(), t.getExchange(),
                t.getQuantity(), t.getBuyPrice(), currentPrice, t.getSellPrice(),
                t.getTargetPrice(), t.getTargetPct(), invested, pnl, pnlPct, isClosed,
                t.getBuyDate(), t.getBuyTime(),
                t.getTradeStatus().name(), t.getSellStatus().name(), t.getExitReason()
        );
    }
}