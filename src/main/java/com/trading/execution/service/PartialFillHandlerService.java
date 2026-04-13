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

/**
 * PartialFillHandlerService — handles partial fills, cancellations, rejections.
 *
 * JAR-VERIFIED:
 *   Trade.quantity     → String  (must parse with Integer.parseInt)
 *   Trade.averagePrice → String  (must parse with Double.parseDouble)
 *
 *   Order status constants (from Constants class — verified string values):
 *     "COMPLETE"  = Constants.ORDER_COMPLETE
 *     "OPEN"      = Constants.ORDER_OPEN
 *     "CANCELLED" = Constants.ORDER_CANCELLED  (uppercase)
 *     "REJECTED"  = Constants.ORDER_REJECTED   (uppercase)
 *
 *   NOTE: WebSocket order update strings come as-is from Zerodha JSON.
 *   The OrderUpdateEvent.getStatus() values match these constants exactly.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PartialFillHandlerService {

    private final ZerodhaOrderClient orderClient;

    record PendingEntry(String symbol, int intendedQty, String slOrderId) {}
    private final Map<String, PendingEntry> tracked = new ConcurrentHashMap<>();

    public void track(String entryOrderId, String symbol, int qty, String slOrderId) {
        tracked.put(entryOrderId, new PendingEntry(symbol, qty, slOrderId));
        log.debug("[FILL] Tracking entry: {} symbol={} qty={}", entryOrderId, symbol, qty);
    }

    @EventListener
    @Async("tradingExecutor")
    public void onOrderUpdate(OrderUpdateEvent event) {
        PendingEntry ctx = tracked.get(event.getOrderId());
        if (ctx == null) return;

        // JAR-VERIFIED order status strings: "COMPLETE", "OPEN", "CANCELLED", "REJECTED"
        switch (event.getStatus()) {
            case "COMPLETE" -> {
                if (event.getFilledQuantity() != ctx.intendedQty()) {
                    log.warn("[FILL] Mismatch {}: intended={} filled={}",
                            ctx.symbol(), ctx.intendedQty(), event.getFilledQuantity());
                    orderClient.modifyQuantity(ctx.slOrderId(), event.getFilledQuantity());
                }
                verifyFills(event.getOrderId(), ctx.symbol());
                tracked.remove(event.getOrderId());
            }
            case "OPEN" -> {
                if (event.getFilledQuantity() > 0
                        && event.getFilledQuantity() < ctx.intendedQty()) {
                    log.warn("[FILL] PARTIAL FILL: {} {}/{}", ctx.symbol(),
                            event.getFilledQuantity(), ctx.intendedQty());
                    orderClient.modifyQuantity(ctx.slOrderId(), event.getFilledQuantity());
                    orderClient.cancelOrder(event.getOrderId());
                    tracked.remove(event.getOrderId());
                }
            }
            case "CANCELLED", "REJECTED" -> {
                log.warn("[FILL] Entry {} {} reason={}", event.getStatus(),
                        ctx.symbol(), event.getRejectionReason());
                if (ctx.slOrderId() != null)
                    orderClient.cancelOrder(ctx.slOrderId());
                tracked.remove(event.getOrderId());
            }
        }
    }

    /**
     * Verify fills from trade history.
     *
     * JAR-VERIFIED: Trade.quantity and Trade.averagePrice are String public fields.
     * Must convert via String.valueOf() then parse — or direct field access.
     */
    private void verifyFills(String orderId, String symbol) {
        try {
            List<Trade> trades = orderClient.getOrderTrades(orderId);
            int    totalQty   = 0;
            double totalValue = 0;

            for (Trade t : trades) {
                // JAR-VERIFIED: Trade.quantity is String — parse to int
                int qty = 0;
                try {
                    qty = Integer.parseInt(String.valueOf(t.quantity).trim());
                } catch (NumberFormatException ignored) {}

                // JAR-VERIFIED: Trade.averagePrice is String — parse to double
                double price = 0.0;
                try {
                    price = Double.parseDouble(String.valueOf(t.averagePrice).trim());
                } catch (NumberFormatException ignored) {}

                totalQty   += qty;
                totalValue += qty * price;
            }

            double avg = totalQty > 0 ? totalValue / totalQty : 0;
            log.info("[FILL] Verified: {} qty={} avgPrice={:.2f}", symbol, totalQty, avg);
        } catch (Exception e) {
            log.warn("[FILL] Cannot verify fills for {}: {}", orderId, e.getMessage());
        }
    }
}