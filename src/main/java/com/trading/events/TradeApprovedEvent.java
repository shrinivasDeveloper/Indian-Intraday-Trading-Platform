package com.trading.events;

import com.trading.domain.enums.TradeDirection;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;
import java.math.BigDecimal;

@Getter
public class TradeApprovedEvent extends ApplicationEvent {
    private final String         tradingSymbol;
    private final long           instrumentToken;
    private final TradeDirection direction;
    private final BigDecimal     entryPrice;
    private final BigDecimal     stopLoss;
    private final BigDecimal     target;
    private final int            quantity;
    private final BigDecimal     riskAmount;
    private final BigDecimal     probabilityScore;
    private final String         strategyName;

    public TradeApprovedEvent(Object src, String sym, long token,
                               TradeDirection dir, BigDecimal entry,
                               BigDecimal sl, BigDecimal tgt,
                               int qty, BigDecimal risk,
                               BigDecimal score, String strategy) {
        super(src);
        tradingSymbol = sym;     instrumentToken = token;
        direction = dir;         entryPrice = entry;
        stopLoss = sl;           target = tgt;
        quantity = qty;          riskAmount = risk;
        probabilityScore = score; strategyName = strategy;
    }
}
