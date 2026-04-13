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

import java.io.IOException;
import java.util.*;

/**
 * ZerodhaOrderClient — JAR-verified order execution client.
 *
 * JAR-VERIFIED TYPE CORRECTIONS (from javap on kiteconnect.jar):
 *
 *   Order fields — ALL are String public fields (NOT int/double):
 *     orderId          → String
 *     filledQuantity   → String  (parse with Integer.parseInt when needed)
 *     averagePrice     → String  (parse with Double.parseDouble when needed)
 *     quantity         → String  (parse with Integer.parseInt when needed)
 *     pendingQuantity  → String  (parse with Integer.parseInt when needed)
 *     triggerPrice     → String
 *     price            → String
 *     status           → String
 *
 *   OrderParams fields — correct types:
 *     quantity         → Integer  (wrapper, not int)
 *     price            → Double   (wrapper, not double)
 *     triggerPrice     → Double   (wrapper, not double)
 *     stoploss         → Double
 *     squareoff        → Double
 *
 *   Margin.available.cash → String (parse with Double.parseDouble)
 *
 *   Trade fields — ALL are String:
 *     averagePrice     → String
 *     quantity         → String
 *
 *   Constants (verified string values):
 *     ORDER_TYPE_MARKET = "MARKET"
 *     ORDER_TYPE_LIMIT  = "LIMIT"
 *     ORDER_TYPE_SL     = "SL"
 *     ORDER_TYPE_SLM    = "SL-M"
 *     VARIETY_REGULAR   = "regular"
 *     PRODUCT_MIS       = "MIS"
 *     VALIDITY_DAY      = "DAY"
 *     EXCHANGE_NSE      = "NSE"
 *     TRANSACTION_TYPE_BUY  = "BUY"
 *     TRANSACTION_TYPE_SELL = "SELL"
 *     ORDER_COMPLETE    = "COMPLETE"
 *     ORDER_OPEN        = "OPEN"
 *     ORDER_CANCELLED   = "CANCELLED"
 *     ORDER_REJECTED    = "REJECTED"
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ZerodhaOrderClient {

    private final KiteConnect kiteConnect;

    // ── Order placement ───────────────────────────────────────────────────

    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 500, multiplier = 2))
    public String placeMarketOrder(String symbol, String txType, int qty) {
        return doPlace(build(symbol, txType, qty, Constants.ORDER_TYPE_MARKET, 0.0, 0.0),
                Constants.VARIETY_REGULAR);
    }

    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 500, multiplier = 2))
    public String placeLimitOrder(String symbol, String txType, int qty, double price) {
        return doPlace(build(symbol, txType, qty, Constants.ORDER_TYPE_LIMIT, price, 0.0),
                Constants.VARIETY_REGULAR);
    }

    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 500, multiplier = 2))
    public String placeSlmOrder(String symbol, String txType, int qty, double trigger) {
        return doPlace(build(symbol, txType, qty, Constants.ORDER_TYPE_SLM, 0.0, trigger),
                Constants.VARIETY_REGULAR);
    }

    // ── Order modification ────────────────────────────────────────────────

    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 500))
    public String modifySlTrigger(String orderId, double newTrigger) {
        try {
            OrderParams p  = new OrderParams();
            // JAR-VERIFIED: triggerPrice is Double (wrapper) in OrderParams
            p.triggerPrice = newTrigger;          // double autoboxed to Double ✓
            p.orderType    = Constants.ORDER_TYPE_SLM;
            // JAR-VERIFIED: modifyOrder(String, OrderParams, String) → Order
            Order result   = kiteConnect.modifyOrder(orderId, p, Constants.VARIETY_REGULAR);
            log.info("SL modified orderId={} newTrigger={}", orderId, newTrigger);
            // JAR-VERIFIED: Order.orderId is String public field
            return result.orderId;
        } catch (KiteException e) {
            // JAR-VERIFIED: KiteException.message and .code are public fields
            throw new OrderException("Modify SL [" + e.code + "]: " + e.message);
        } catch (IOException e) {
            throw new OrderException("Modify SL network: " + e.getMessage());
        }
    }

    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 500))
    public String modifyQuantity(String orderId, int newQty) {
        try {
            OrderParams p = new OrderParams();
            // JAR-VERIFIED: quantity is Integer (wrapper) in OrderParams
            p.quantity    = newQty;              // int autoboxed to Integer ✓
            Order result  = kiteConnect.modifyOrder(orderId, p, Constants.VARIETY_REGULAR);
            log.info("Qty modified orderId={} newQty={}", orderId, newQty);
            // JAR-VERIFIED: Order.orderId is String
            return result.orderId;
        } catch (KiteException e) {
            throw new OrderException("Modify qty [" + e.code + "]: " + e.message);
        } catch (IOException e) {
            throw new OrderException("Modify qty network: " + e.getMessage());
        }
    }

    public boolean cancelOrder(String orderId) {
        try {
            // JAR-VERIFIED: cancelOrder(String, String) → Order
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

    // ── Query methods ─────────────────────────────────────────────────────

    public List<Order> getAllOrders() {
        try { return kiteConnect.getOrders(); }
        catch (KiteException e) { throw new OrderException("[" + e.code + "]: " + e.message); }
        catch (IOException e)   { throw new OrderException(e.getMessage()); }
    }

    public List<Order> getOrderHistory(String orderId) {
        try { return kiteConnect.getOrderHistory(orderId); }
        catch (KiteException e) { throw new OrderException("[" + e.code + "]: " + e.message); }
        catch (IOException e)   { throw new OrderException(e.getMessage()); }
    }

    public List<Trade> getOrderTrades(String orderId) {
        try { return kiteConnect.getOrderTrades(orderId); }
        catch (KiteException e) { throw new OrderException("[" + e.code + "]: " + e.message); }
        catch (IOException e)   { throw new OrderException(e.getMessage()); }
    }

    /**
     * JAR-VERIFIED: getPositions() → Map<String, List<Position>>
     * Keys are "day" and "net" (lowercase, confirmed).
     */
    public List<Position> getDayPositions() {
        try {
            Map<String, List<Position>> pos = kiteConnect.getPositions();
            return pos.getOrDefault("day", new ArrayList<>());
        } catch (KiteException e) { throw new OrderException("[" + e.code + "]: " + e.message); }
        catch (IOException e)     { throw new OrderException(e.getMessage()); }
    }

    public List<Position> getNetPositions() {
        try {
            Map<String, List<Position>> pos = kiteConnect.getPositions();
            return pos.getOrDefault("net", new ArrayList<>());
        } catch (KiteException e) { throw new OrderException("[" + e.code + "]: " + e.message); }
        catch (IOException e)     { throw new OrderException(e.getMessage()); }
    }

    public List<Holding> getHoldings() {
        try { return kiteConnect.getHoldings(); }
        catch (KiteException e) { throw new OrderException("[" + e.code + "]: " + e.message); }
        catch (IOException e)   { throw new OrderException(e.getMessage()); }
    }

    /**
     * JAR-VERIFIED: Margin.available.cash → String
     * Must parse with Double.parseDouble(m.available.cash)
     */
    public double getAvailableCash() {
        try {
            // JAR-VERIFIED: getMargins(String) → Margin
            // Constants.MARGIN_EQUITY = "equity" (verified)
            Margin m = kiteConnect.getMargins("equity");

            if (m == null || m.available == null || m.available.cash == null) {
                log.warn("[ORDER] Cash value is null");
                return 0.0;
            }

            // JAR-VERIFIED: Margin.Available.cash is String — must parse
            return Double.parseDouble(m.available.cash);

        } catch (KiteException e) {
            log.warn("[ORDER] Margins fetch failed: code={} msg={}", e.code, e.message);
            return 0.0;
        } catch (IOException e) {
            log.warn("[ORDER] Margins network failed: {}", e.getMessage());
            return 0.0;
        } catch (NumberFormatException e) {
            log.error("[ORDER] Invalid cash format: {}", e.getMessage());
            return 0.0;
        }
    }

    /**
     * Margin calculation stub — always returns sufficient.
     * Zerodha rejects at execution if short.
     */
    public MarginResult calculateMargin(String symbol, int qty, double price) {
        log.debug("[ORDER] Margin check: symbol={} qty={}", symbol, qty);
        return new MarginResult(symbol, qty, 0, 999999, true);
    }

    public record MarginResult(String symbol, int quantity,
                               double required, double available,
                               boolean sufficient) {}

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Build OrderParams.
     * JAR-VERIFIED field types:
     *   quantity     → Integer  (autoboxed from int)
     *   price        → Double   (autoboxed from double; 0.0 for market orders)
     *   triggerPrice → Double   (autoboxed from double; 0.0 for limit orders)
     */
    private OrderParams build(String symbol, String txType, int qty,
                              String type, double price, double trigger) {
        OrderParams p      = new OrderParams();
        p.tradingsymbol    = symbol;
        p.exchange         = Constants.EXCHANGE_NSE;     // "NSE" ✓
        p.transactionType  = txType;
        p.quantity         = qty;                        // int → Integer autobox ✓
        p.orderType        = type;
        p.price            = price;                      // double → Double autobox ✓
        p.triggerPrice     = trigger;                    // double → Double autobox ✓
        p.product          = Constants.PRODUCT_MIS;      // "MIS" ✓
        p.validity         = Constants.VALIDITY_DAY;     // "DAY" ✓
        return p;
    }

    private String doPlace(OrderParams p, String variety) {
        try {
            // JAR-VERIFIED: placeOrder(OrderParams, String) → Order
            Order order = kiteConnect.placeOrder(p, variety);
            log.info("[ORDER] PLACED: symbol={} tx={} type={} qty={} id={}",
                    p.tradingsymbol, p.transactionType,
                    p.orderType, p.quantity, order.orderId);
            // JAR-VERIFIED: Order.orderId is String public field
            return order.orderId;
        } catch (KiteException e) {
            log.error("[ORDER] REJECTED: symbol={} code={} msg={}",
                    p.tradingsymbol, e.code, e.message);
            throw new OrderException("Order rejected [" + e.code + "]: " + e.message);
        } catch (IOException e) {
            throw new OrderException("Network error: " + e.getMessage());
        }
    }
}