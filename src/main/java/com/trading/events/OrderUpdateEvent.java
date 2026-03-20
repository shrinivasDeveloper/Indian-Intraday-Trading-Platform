package com.trading.events;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class OrderUpdateEvent extends ApplicationEvent {
    private final String orderId;
    private final String tradingSymbol;
    private final String status;
    private final int    filledQuantity;
    private final int    pendingQuantity;
    private final double averagePrice;
    private final String rejectionReason;

    public OrderUpdateEvent(Object src, String oid, String sym,
                             String status, int filled, int pending,
                             double avgPrice, String reason) {
        super(src);
        orderId = oid;           tradingSymbol = sym;
        this.status = status;    filledQuantity = filled;
        pendingQuantity = pending; averagePrice = avgPrice;
        rejectionReason = reason;
    }
}
