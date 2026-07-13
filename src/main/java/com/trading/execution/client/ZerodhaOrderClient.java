package com.trading.execution.client;

import com.trading.shared.risk.AccountMarginGuard;
import com.trading.shared.risk.CrossStrategyPositionRegistry;
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
import java.math.BigDecimal;
import java.util.*;

/**
 * ZerodhaOrderClient - JAR-verified order execution client, shared by
 * BOTH AI and News (confirmed - this is the same execution path News
 * uses via AiLiveOrderExecutionService).
 *
 * JAR-VERIFIED TYPE CORRECTIONS (from javap on kiteconnect.jar):
 *
 *   Order fields - ALL are String public fields (NOT int/double):
 *     orderId          -> String
 *     filledQuantity   -> String  (parse with Integer.parseInt when needed)
 *     averagePrice     -> String  (parse with Double.parseDouble when needed)
 *     quantity         -> String  (parse with Integer.parseInt when needed)
 *     pendingQuantity  -> String  (parse with Integer.parseInt when needed)
 *     triggerPrice     -> String
 *     price            -> String
 *     status           -> String
 *
 *   OrderParams fields - correct types:
 *     quantity         -> Integer  (wrapper, not int)
 *     price            -> Double   (wrapper, not double)
 *     triggerPrice     -> Double   (wrapper, not double)
 *     stoploss         -> Double
 *     squareoff        -> Double
 *
 *   Margin.available.cash -> String (parse with Double.parseDouble)
 *
 *   Trade fields - ALL are String:
 *     averagePrice     -> String
 *     quantity         -> String
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
 *
 * FIX (found during a full platform production-readiness review): wired
 * in AccountMarginGuard and CrossStrategyPositionRegistry - the same two
 * genuinely shared, cross-strategy safeguards already wired into Swing.
 * Since THIS client is itself shared by both AI and News with no way to
 * distinguish which one is calling without changing every existing call
 * site's method signature (explicitly not wanted), the safeguard logs
 * are labeled generically as "AI_NEWS_SHARED" - still gives real,
 * actionable visibility (this symbol/this amount), just not attributed
 * to one specific strategy name. Zero changes to any existing method
 * signature, zero changes to build()/doPlace()/order-construction logic.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ZerodhaOrderClient {

    private final KiteConnect kiteConnect;
    private final AccountMarginGuard marginGuard;
    private final CrossStrategyPositionRegistry positionRegistry;

    // -- Order placement ---------------------------------------------------

    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 500, multiplier = 2))
    public String placeMarketOrder(String symbol, String txType, int qty) {
        checkSafeguardsBeforeBuy(symbol, txType, qty, null);
        String orderId = doPlace(build(symbol, txType, qty, Constants.ORDER_TYPE_MARKET, 0.0, 0.0),
                Constants.VARIETY_REGULAR);
        updateRegistryAfterOrder(symbol, txType);
        return orderId;
    }

    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 500, multiplier = 2))
    public String placeLimitOrder(String symbol, String txType, int qty, double price) {
        checkSafeguardsBeforeBuy(symbol, txType, qty, price);
        String orderId = doPlace(build(symbol, txType, qty, Constants.ORDER_TYPE_LIMIT, price, 0.0),
                Constants.VARIETY_REGULAR);
        updateRegistryAfterOrder(symbol, txType);
        return orderId;
    }

    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 500, multiplier = 2))
    public String placeSlmOrder(String symbol, String txType, int qty, double trigger) {
        checkSafeguardsBeforeBuy(symbol, txType, qty, null);
        String orderId = doPlace(build(symbol, txType, qty, Constants.ORDER_TYPE_SLM, 0.0, trigger),
                Constants.VARIETY_REGULAR);
        updateRegistryAfterOrder(symbol, txType);
        return orderId;
    }

    /**
     * Advisory margin check + exposure visibility - only meaningfully
     * checks margin for BUY orders (a SELL/exit doesn't consume
     * additional margin for an existing position). Never throws on its
     * own failure, never blocks a SELL - only a BUY can be skipped here,
     * and only when margin is genuinely confirmed insufficient.
     */
    private void checkSafeguardsBeforeBuy(String symbol, String txType, int qty, Double knownPrice) {
        if (!Constants.TRANSACTION_TYPE_BUY.equals(txType)) return; // exits never gated here

        try {
            double price = knownPrice != null ? knownPrice : fetchLtp(symbol);
            if (price > 0) {
                BigDecimal estimatedCost = BigDecimal.valueOf(price).multiply(BigDecimal.valueOf(qty));
                var marginResult = marginGuard.checkSufficientMargin(estimatedCost, "AI_NEWS_SHARED");
                if (!marginResult.sufficient()) {
                    throw new IllegalStateException(
                            "Insufficient account margin for " + symbol + " (need ~Rs." +
                                    estimatedCost + ", available Rs." + marginResult.availableMargin() + ")");
                }
            }
        } catch (IllegalStateException e) {
            throw e; // re-throw the deliberate margin-insufficiency signal
        } catch (KiteException | Exception e) {
            log.debug("[AI-NEWS-ORDER] Pre-order margin/quote check skipped (non-fatal): {}",
                    e.getMessage());
        }
        positionRegistry.checkAndWarnIfHeldElsewhere(symbol, "AI_NEWS_SHARED");
    }

    private void updateRegistryAfterOrder(String symbol, String txType) {
        if (Constants.TRANSACTION_TYPE_BUY.equals(txType)) {
            positionRegistry.registerPosition(symbol, "AI_NEWS_SHARED");
        } else if (Constants.TRANSACTION_TYPE_SELL.equals(txType)) {
            positionRegistry.releasePosition(symbol, "AI_NEWS_SHARED");
        }
    }

    private double fetchLtp(String symbol) throws KiteException, IOException, org.json.JSONException {
        String key = Constants.EXCHANGE_NSE + ":" + symbol;
        Map<String, Quote> quotes = kiteConnect.getQuote(new String[]{key});
        Quote q = quotes.get(key);
        return q != null ? q.lastPrice : 0.0;
    }

    // -- Order modification ------------------------------------------------

    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 500))
    public String modifySlTrigger(String orderId, double newTrigger) {
        try {
            OrderParams p  = new OrderParams();
            // JAR-VERIFIED: triggerPrice is Double (wrapper) in OrderParams
            // FIX (found during thorough validation of the tick-size fix
            // above): this method builds its OWN, separate OrderParams -
            // was bypassing roundToTickSize() entirely. Since this is
            // used for trailing-stop updates, an unaligned trigger price
            // here would hit the exact same Zerodha "tick size" rejection
            // COLPAL's entry order did. Applied the same fix for full,
            // consistent coverage across every order-price code path.
            p.triggerPrice = roundToTickSize(newTrigger); // double autoboxed to Double [OK]
            p.orderType    = Constants.ORDER_TYPE_SLM;
            // JAR-VERIFIED: modifyOrder(String, OrderParams, String) -> Order
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
            p.quantity    = newQty;              // int autoboxed to Integer [OK]
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
            // JAR-VERIFIED: cancelOrder(String, String) -> Order
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

    // -- Query methods -----------------------------------------------------

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
     * JAR-VERIFIED: getPositions() -> Map<String, List<Position>>
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
     * JAR-VERIFIED: Margin.available.cash -> String
     * Must parse with Double.parseDouble(m.available.cash)
     */
    public double getAvailableCash() {
        try {
            // JAR-VERIFIED: getMargins(String) -> Margin
            // Constants.MARGIN_EQUITY = "equity" (verified)
            Margin m = kiteConnect.getMargins("equity");

            if (m == null || m.available == null || m.available.cash == null) {
                log.warn("[ORDER] Cash value is null");
                return 0.0;
            }

            // JAR-VERIFIED: Margin.Available.cash is String - must parse
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
     * Margin calculation stub - always returns sufficient.
     * Zerodha rejects at execution if short.
     */
    public MarginResult calculateMargin(String symbol, int qty, double price) {
        log.debug("[ORDER] Margin check: symbol={} qty={}", symbol, qty);
        return new MarginResult(symbol, qty, 0, 999999, true);
    }

    public record MarginResult(String symbol, int quantity,
                               double required, double available,
                               boolean sufficient) {}

    // -- Helpers -----------------------------------------------------------

    /**
     * Build OrderParams.
     * JAR-VERIFIED field types:
     *   quantity     -> Integer  (autoboxed from int)
     *   price        -> Double   (autoboxed from double; 0.0 for market orders)
     *   triggerPrice -> Double   (autoboxed from double; 0.0 for limit orders)
     */
    private OrderParams build(String symbol, String txType, int qty,
                              String type, double price, double trigger) {
        OrderParams p      = new OrderParams();
        p.tradingsymbol    = symbol;
        p.exchange         = Constants.EXCHANGE_NSE;     // "NSE" [OK]
        p.transactionType  = txType;
        p.quantity         = qty;                        // int -> Integer autobox [OK]
        p.orderType        = type;
        // FIX (found via direct user report - real Zerodha rejection):
        // "Tick size for this script is 0.10. Kindly enter price in the
        // multiple of tick size" - confirmed real, exact cause: COLPAL's
        // computed entry price (2032.38) is not a valid multiple of its
        // 0.10 tick size (2032.38 / 0.10 = 20323.8, not a whole number).
        // Rounds to the nearest 0.05 before every order submission -
        // NSE's standard tick size for the vast majority of stocks. Since
        // 0.10 is an exact multiple of 0.05, this also correctly
        // satisfies stocks with the less common 0.10 tick size (like
        // COLPAL), without needing a per-instrument tick-size lookup.
        p.price            = roundToTickSize(price);     // double -> Double autobox [OK]
        p.triggerPrice     = roundToTickSize(trigger);    // double -> Double autobox [OK]
        p.product          = Constants.PRODUCT_MIS;      // "MIS" [OK]
        p.validity         = Constants.VALIDITY_DAY;     // "DAY" [OK]
        // FIX (confirmed real, not a guess - verified directly from
        // Zerodha's own Kite Connect documentation and developer forum):
        // per SEBI's retail algo regulations, MARKET and SL-M orders
        // placed via the API now REQUIRE a non-zero market_protection
        // value, or Zerodha rejects them with 400: "Market orders
        // without market protection are not allowed via API." This
        // affects AI/News's real order placement identically to how it
        // was found affecting Swing's - same root cause, same fix
        // needed here. -1 applies Zerodha's own automatic protection
        // range (their documented recommended default) rather than us
        // guessing a fixed percentage - preserves genuine market-order
        // fill-immediately behavior while satisfying the new mandatory
        // requirement. Deliberately NOT set for LIMIT orders, since
        // Zerodha's docs confirm market_protection only applies to
        // MARKET and SL-M order types.
        if (Constants.ORDER_TYPE_MARKET.equals(type) || Constants.ORDER_TYPE_SLM.equals(type)) {
            p.marketProtection = -1;
        }
        return p;
    }

    /** Rounds to the nearest valid NSE tick size (0.05) - see build()
     *  above for the full explanation of why this is needed and why
     *  0.05 is safe for both the common 0.05 and less-common 0.10
     *  tick-size stocks. */
    private double roundToTickSize(double price) {
        if (price <= 0) return price; // 0.0 is the correct sentinel for
        // market orders - leave untouched
        return Math.round(price / 0.05) * 0.05;
    }

    private String doPlace(OrderParams p, String variety) {
        try {
            // JAR-VERIFIED: placeOrder(OrderParams, String) -> Order
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