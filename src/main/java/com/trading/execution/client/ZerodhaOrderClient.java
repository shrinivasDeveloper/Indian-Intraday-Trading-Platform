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
                // FIX (found via direct user report: real Zerodha manual
                // margin for a trade was ~Rs.20,000, but this code
                // demanded Rs.99,756+ - roughly 5x too much, exactly
                // matching typical MIS intraday leverage. Confirmed real
                // root cause: estimatedCost was raw price x quantity -
                // the FULL, UNLEVERAGED cash value - completely ignoring
                // Zerodha's real MIS leverage. This caused genuinely
                // valid trades to be rejected, costing real missed
                // opportunities. Fixed by calling Zerodha's own real
                // order-margin calculation API (getMarginCalculation) -
                // the exact same calculation Kite itself uses for manual
                // orders - instead of a naive, unleveraged cash estimate.
                BigDecimal estimatedCost = getRealMarginRequired(symbol, txType, qty, price);
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

    /**
     * Calls Zerodha's own real order-margin calculation API - the exact
     * same calculation Kite itself uses for manual orders, correctly
     * accounting for MIS intraday leverage (unlike a naive price x
     * quantity estimate, which assumes full, unleveraged cash cost).
     * Falls back to the conservative full-cash estimate ONLY if this
     * real API call itself fails - never silently under-checks margin,
     * just avoids the confirmed over-estimation bug in the common case.
     */
    private BigDecimal getRealMarginRequired(String symbol, String txType, int qty, double price) {
        try {
            var params = new com.zerodhatech.models.MarginCalculationParams();
            params.tradingSymbol = symbol;
            params.exchange = Constants.EXCHANGE_NSE;
            params.transactionType = txType;
            params.variety = Constants.VARIETY_REGULAR;
            params.product = Constants.PRODUCT_MIS;
            params.orderType = Constants.ORDER_TYPE_MARKET;
            params.quantity = qty;
            params.price = 0; // 0 for MARKET orders, matching the same convention used elsewhere
            params.triggerPrice = 0;

            var results = kiteConnect.getMarginCalculation(List.of(params));
            if (results != null && !results.isEmpty()) {
                double realMargin = results.get(0).total;
                if (realMargin > 0) {
                    log.debug("[AI-NEWS-ORDER] Real margin for {} qty={}: Rs.{} (vs naive cash " +
                            "estimate Rs.{})", symbol, qty, realMargin, price * qty);
                    return BigDecimal.valueOf(realMargin);
                }
            }
        } catch (KiteException | Exception e) {
            log.debug("[AI-NEWS-ORDER] Real margin calculation failed for {} - falling back to " +
                    "conservative full-cash estimate: {}", symbol, e.getMessage());
        }
        // Fallback: the original, conservative (over-)estimate - never
        // silently under-checks margin if the real API call fails.
        return BigDecimal.valueOf(price).multiply(BigDecimal.valueOf(qty));
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
            // Uses the safe 0.05 fallback explicitly (confirmed via
            // direct search: this method has zero callers anywhere in
            // the codebase currently, and doesn't have easy access to
            // the symbol here without an extra lookup) - if this method
            // is ever wired up for real use, the caller should be
            // updated to pass the real symbol through for full accuracy.
            p.triggerPrice = roundToTickSize(newTrigger, 0.05); // double autoboxed to Double [OK]
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
        // FIX (found via direct user report - TWO real Zerodha
        // rejections, on different stocks with DIFFERENT tick sizes:
        // COLPAL at 0.10, then POLYCAB at 0.50). Confirmed mathematically
        // that rounding to a fixed 0.05 does NOT guarantee alignment
        // with coarser tick sizes - only 1-in-10 multiples of 0.05 also
        // align to 0.50, so the earlier "round to 0.05" fix was
        // insufficient for POLYCAB. Now uses the REAL, per-instrument
        // tick_size field from Kite's own Instrument model (confirmed
        // via bytecode: Instrument.tick_size is a genuine double field)
        // - correct for every stock, not just ones with 0.05 or 0.10
        // tick sizes.
        double tickSize = getTickSize(symbol);
        p.price            = roundToTickSize(price, tickSize);     // double -> Double autobox [OK]
        p.triggerPrice     = roundToTickSize(trigger, tickSize);    // double -> Double autobox [OK]
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

    // Cache of real tick_size per symbol, resolved once and reused -
    // avoids re-fetching the full ~9,946-instrument list on every
    // single order.
    private final Map<String, Double> tickSizeCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Looks up the REAL, per-instrument tick size from Kite's own
     * Instrument model - confirmed via bytecode this is a genuine field
     * Kite provides directly, not something that needs guessing. Falls
     * back to 0.05 (NSE's most common tick size) only if the lookup
     * itself fails, so this can never leave price completely unrounded.
     */
    private double getTickSize(String symbol) {
        Double cached = tickSizeCache.get(symbol);
        if (cached != null && cached > 0) return cached;
        try {
            List<com.zerodhatech.models.Instrument> instruments = kiteConnect.getInstruments("NSE");
            for (com.zerodhatech.models.Instrument i : instruments) {
                if (symbol.equalsIgnoreCase(i.tradingsymbol)) {
                    double tick = i.tick_size;
                    if (tick > 0) {
                        tickSizeCache.put(symbol, tick);
                        return tick;
                    }
                    break;
                }
            }
        } catch (KiteException | Exception e) {
            log.debug("[AI-NEWS-ORDER] Could not resolve real tick size for {} - falling back " +
                    "to 0.05: {}", symbol, e.getMessage());
        }
        return 0.05; // safe fallback - NSE's most common tick size
    }

    /** Rounds to the given tick size - now the REAL, per-instrument
     *  value from Kite (see getTickSize() above), not a fixed guess. */
    private double roundToTickSize(double price, double tickSize) {
        if (price <= 0) return price; // 0.0 is the correct sentinel for
        // market orders - leave untouched
        return Math.round(price / tickSize) * tickSize;
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