package com.trading.events;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;
import java.math.BigDecimal;

@Getter
public class TradeExecutionResultEvent extends ApplicationEvent {
    private final String     tradingSymbol;
    private final String     status;
    private final String     entryOrderId;
    private final String     slOrderId;
    private final BigDecimal entryPrice;
    private final BigDecimal exitPrice;
    private final BigDecimal netPnl;
    private final String     exitReason;

    public TradeExecutionResultEvent(Object src, String sym, String status,
                                      String entryOId, String slOId,
                                      BigDecimal entry, BigDecimal exit,
                                      BigDecimal pnl, String reason) {
        super(src);
        tradingSymbol = sym;  this.status = status;
        entryOrderId = entryOId; slOrderId = slOId;
        entryPrice = entry;   exitPrice = exit;
        netPnl = pnl;         exitReason = reason;
    }
}
