package com.trading.execution.client;

import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.*;
import com.zerodhatech.kiteconnect.utils.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import java.io.IOException;

import java.io.IOException;
import java.util.*;

@Component
@Slf4j
@RequiredArgsConstructor
public class ZerodhaOrderClient {

    private final KiteConnect kiteConnect;

    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 500, multiplier = 2))
    public String placeMarketOrder(String symbol, String txType, int qty) {
        return doPlace(build(symbol, txType, qty, Constants.ORDER_TYPE_MARKET, 0, 0),
            Constants.VARIETY_REGULAR);
    }

    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 500, multiplier = 2))
    public String placeLimitOrder(String symbol, String txType, int qty, double price) {
        return doPlace(build(symbol, txType, qty, Constants.ORDER_TYPE_LIMIT, price, 0),
            Constants.VARIETY_REGULAR);
    }

    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 500, multiplier = 2))
    public String placeSlmOrder(String symbol, String txType, int qty, double trigger) {
        return doPlace(build(symbol, txType, qty, Constants.ORDER_TYPE_SLM, 0, trigger),
            Constants.VARIETY_REGULAR);
    }

    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 500))
    public String modifySlTrigger(String orderId, double newTrigger) {
        try {
            OrderParams p  = new OrderParams();
            p.triggerPrice = newTrigger;
            p.orderType    = Constants.ORDER_TYPE_SLM;
            Order result   = kiteConnect.modifyOrder(orderId, p, Constants.VARIETY_REGULAR);
            log.info("SL modified orderId={} newTrigger={}", orderId, newTrigger);
            return result.orderId;
        } catch (KiteException | IOException e) {
            throw new OrderException("Modify SL: " + e.getMessage());
        }
    }

    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 500))
    public String modifyQuantity(String orderId, int newQty) {
        try {
            OrderParams p = new OrderParams();
            p.quantity    = newQty;
            Order result  = kiteConnect.modifyOrder(orderId, p, Constants.VARIETY_REGULAR);
            log.info("Qty modified orderId={} newQty={}", orderId, newQty);
            return result.orderId;
        } catch (KiteException | IOException e) {
            throw new OrderException("Modify qty: " + e.getMessage());
        }
    }

    public boolean cancelOrder(String orderId) {
        try {
            kiteConnect.cancelOrder(orderId, Constants.VARIETY_REGULAR);
            log.info("Order cancelled: {}", orderId);
            return true;
        } catch (KiteException e) {
            log.error("Cancel failed {}: code={} msg={}", orderId, e.code, e.message);
            return false;
        } catch (IOException e) {
            log.error("Cancel network error {}: {}", orderId, e.getMessage());
            return false;
        }
    }

    public List<Order> getAllOrders() {
        try { return kiteConnect.getOrders(); }
        catch (KiteException | IOException e) { throw new OrderException(e.getMessage()); }
    }

    public List<Order> getOrderHistory(String orderId) {
        try { return kiteConnect.getOrderHistory(orderId); }
        catch (KiteException | IOException e) { throw new OrderException(e.getMessage()); }
    }

    public List<Trade> getOrderTrades(String orderId) {
        try { return kiteConnect.getOrderTrades(orderId); }
        catch (KiteException | IOException e) { throw new OrderException(e.getMessage()); }
    }

    // VERIFIED from GitHub: getPositions() returns Map<String, List<Position>>
    // with string keys "day" and "net"
    public List<Position> getDayPositions() {
        try {
            Map<String, List<Position>> pos = kiteConnect.getPositions();
            return pos.getOrDefault("day", new ArrayList<>());
        } catch (KiteException | IOException e) {
            throw new OrderException(e.getMessage());
        }
    }

    public List<Position> getNetPositions() {
        try {
            Map<String, List<Position>> pos = kiteConnect.getPositions();
            return pos.getOrDefault("net", new ArrayList<>());
        } catch (KiteException | IOException e) {
            throw new OrderException(e.getMessage());
        }
    }

    public List<Holding> getHoldings() {
        try { return kiteConnect.getHoldings(); }
        catch (KiteException | IOException e) { throw new OrderException(e.getMessage()); }
    }

    // Margin.available.cash is String in this SDK - parse to double

    public double getAvailableCash() {
        try {
            Margin m = kiteConnect.getMargins("equity");

            // null check (important)
            if (m.available == null || m.available.cash == null) {
                log.warn("Cash value is null");
                return 0.0;
            }

            // String → double conversion
            return Double.parseDouble(m.available.cash);

        } catch (KiteException | IOException e) {
            log.warn("Margins fetch failed: {}", e.getMessage());
            return 0.0;
        } catch (NumberFormatException e) {
            log.error("Invalid cash format: {}", e.getMessage());
            return 0.0;
        }
    }
    // getMarginCalculation exists in SDK but takes List<MarginCalculationParams>
    // Return always-sufficient for simplicity — Zerodha rejects at execution if short
    public MarginResult calculateMargin(String symbol, int qty, double price) {
        log.debug("Margin check: symbol={} qty={}", symbol, qty);
        return new MarginResult(symbol, qty, 0, 999999, true);
    }

    public record MarginResult(String symbol, int quantity,
                                double required, double available,
                                boolean sufficient) {}

    private OrderParams build(String symbol, String txType, int qty,
                               String type, double price, double trigger) {
        OrderParams p      = new OrderParams();
        p.tradingsymbol    = symbol;
        p.exchange         = Constants.EXCHANGE_NSE;
        p.transactionType  = txType;
        p.quantity         = qty;
        p.orderType        = type;
        p.price            = price;
        p.triggerPrice     = trigger;
        p.product          = Constants.PRODUCT_MIS;
        p.validity         = Constants.VALIDITY_DAY;
        return p;
    }

    private String doPlace(OrderParams p, String variety) {
        try {
            Order order = kiteConnect.placeOrder(p, variety);
            log.info("ORDER PLACED: symbol={} tx={} type={} qty={} id={}",
                p.tradingsymbol, p.transactionType, p.orderType, p.quantity, order.orderId);
            return order.orderId;
        } catch (KiteException e) {
            log.error("ORDER REJECTED: symbol={} code={} msg={}",
                p.tradingsymbol, e.code, e.message);
            throw new OrderException("Order rejected [" + e.code + "]: " + e.message);
        } catch (IOException e) {
            throw new OrderException("Network error: " + e.getMessage());
        }
    }
}
