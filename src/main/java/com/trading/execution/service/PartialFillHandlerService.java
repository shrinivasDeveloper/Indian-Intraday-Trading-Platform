package com.trading.execution.service;

import com.trading.events.OrderUpdateEvent;
import com.trading.execution.client.ZerodhaOrderClient;
import com.zerodhatech.models.Trade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class PartialFillHandlerService {

    private final ZerodhaOrderClient orderClient;

    record PendingEntry(String symbol, int intendedQty, String slOrderId) {}
    private final Map<String, PendingEntry> tracked = new ConcurrentHashMap<>();

    public void track(String entryOrderId, String symbol, int qty, String slOrderId) {
        tracked.put(entryOrderId, new PendingEntry(symbol, qty, slOrderId));
        log.debug("Tracking entry: {} symbol={} qty={}", entryOrderId, symbol, qty);
    }

    @EventListener
    @Async("tradingExecutor")
    public void onOrderUpdate(OrderUpdateEvent event) {
        PendingEntry ctx = tracked.get(event.getOrderId());
        if (ctx == null) return;

        switch (event.getStatus()) {
            case "COMPLETE" -> {
                if (event.getFilledQuantity() != ctx.intendedQty()) {
                    log.warn("Fill mismatch {}: intended={} filled={}",
                        ctx.symbol(), ctx.intendedQty(), event.getFilledQuantity());
                    orderClient.modifyQuantity(ctx.slOrderId(), event.getFilledQuantity());
                }
                verifyFills(event.getOrderId(), ctx.symbol());
                tracked.remove(event.getOrderId());
            }
            case "OPEN" -> {
                if (event.getFilledQuantity() > 0
                        && event.getFilledQuantity() < ctx.intendedQty()) {
                    log.warn("PARTIAL FILL: {} {}/{}", ctx.symbol(),
                        event.getFilledQuantity(), ctx.intendedQty());
                    orderClient.modifyQuantity(ctx.slOrderId(), event.getFilledQuantity());
                    orderClient.cancelOrder(event.getOrderId());
                    tracked.remove(event.getOrderId());
                }
            }
            case "CANCELLED", "REJECTED" -> {
                log.warn("Entry {} {} reason={}", event.getStatus(),
                    ctx.symbol(), event.getRejectionReason());
                if (ctx.slOrderId() != null)
                    orderClient.cancelOrder(ctx.slOrderId());
                tracked.remove(event.getOrderId());
            }
        }
    }

    private void verifyFills(String orderId, String symbol) {
        try {
            List<Trade> trades = orderClient.getOrderTrades(orderId);
            int    totalQty   = 0;
            double totalValue = 0;
            for (Trade t : trades) {
                // VERIFIED from JAR: Trade.quantity is String, Trade.averagePrice is String
                int qty = 0;
                try { qty = Integer.parseInt(String.valueOf(t.quantity).trim()); }
                catch (Exception ignored) {}

                double price = 0;
                try { price = Double.parseDouble(String.valueOf(t.averagePrice).trim()); }
                catch (Exception ignored) {}

                totalQty   += qty;
                totalValue += qty * price;
            }
            double avg = totalQty > 0 ? totalValue / totalQty : 0;
            log.info("Fill verified: {} qty={} avgPrice={}", symbol, totalQty, avg);
        } catch (Exception e) {
            log.warn("Cannot verify fills for {}: {}", orderId, e.getMessage());
        }
    }
}
