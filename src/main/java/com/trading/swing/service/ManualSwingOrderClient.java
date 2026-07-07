package com.trading.swing.service;

import com.trading.shared.risk.AccountMarginGuard;
import com.trading.shared.risk.CrossStrategyPositionRegistry;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.kiteconnect.utils.Constants;
import com.zerodhatech.models.Order;
import com.zerodhatech.models.OrderParams;
import com.zerodhatech.models.Quote;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;

/**
 * ManualSwingOrderClient - CNC-only order placement for the Manual Swing
 * Trading module.
 *
 * Deliberately NOT built on top of ZerodhaOrderClient: that class
 * hardcodes Constants.PRODUCT_MIS inside its private build() helper
 * (verified directly against its source) - reusing it here would place
 * INTRADAY orders for what must always be a CNC trade, the exact opposite
 * of this module's explicit requirement. This class shares only the
 * underlying authenticated KiteConnect bean (the same legitimate shared
 * infrastructure pattern used throughout this app - e.g. AI and News both
 * share the same JdbcTemplate/KiteConnect for the same reason) - zero
 * shared business logic, zero shared order-construction code.
 *
 * FIX (found during a full production-readiness review): wired in
 * AccountMarginGuard and CrossStrategyPositionRegistry - two genuinely
 * shared, cross-strategy safeguards (capital/margin and symbol exposure
 * are real, singular, shared resources across the whole Zerodha account,
 * unlike strategy-specific business logic). Purely additive - zero
 * changes to the actual order-building/placement code below, zero
 * changes to this class's public method signatures, zero changes to
 * any caller.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ManualSwingOrderClient {

    private final KiteConnect kiteConnect;
    private final AccountMarginGuard marginGuard;
    private final CrossStrategyPositionRegistry positionRegistry;

    public String placeBuyMarketOrder(String symbol, String exchange, int qty) {
        // FIX: cross-strategy safeguards, checked right before order
        // placement - advisory margin check (skips the attempt rather
        // than letting it fail at the broker) and exposure visibility
        // (warns only, never blocks). Zero change to the actual order
        // construction/placement below.
        try {
            String quoteKey = exchange + ":" + symbol;
            Quote q = kiteConnect.getQuote(new String[]{quoteKey}).get(quoteKey);
            if (q != null && q.lastPrice > 0) {
                BigDecimal estimatedCost = BigDecimal.valueOf(q.lastPrice)
                        .multiply(BigDecimal.valueOf(qty));
                var marginResult = marginGuard.checkSufficientMargin(estimatedCost, "SWING");
                if (!marginResult.sufficient()) {
                    throw new ManualSwingOrderException(
                            "Insufficient account margin for this order (need ~Rs." + estimatedCost +
                                    ", available Rs." + marginResult.availableMargin() + ") - order not attempted");
                }
            }
        } catch (ManualSwingOrderException e) {
            throw e; // re-throw the deliberate margin-insufficiency exception
        } catch (KiteException | Exception e) {
            log.debug("[SWING] Pre-order margin/quote check skipped (non-fatal): {}", e.getMessage());
        }
        positionRegistry.checkAndWarnIfHeldElsewhere(symbol, "SWING");

        String orderId = doPlace(build(symbol, exchange, Constants.TRANSACTION_TYPE_BUY, qty));
        positionRegistry.registerPosition(symbol, "SWING");
        return orderId;
    }

    public String placeSellMarketOrder(String symbol, String exchange, int qty) {
        String orderId = doPlace(build(symbol, exchange, Constants.TRANSACTION_TYPE_SELL, qty));
        positionRegistry.releasePosition(symbol, "SWING");
        return orderId;
    }

    private OrderParams build(String symbol, String exchange, String txType, int qty) {
        OrderParams p   = new OrderParams();
        p.exchange         = exchange;                     // NSE or BSE, caller-supplied
        p.tradingsymbol    = symbol;
        p.transactionType  = txType;
        p.quantity         = qty;
        p.orderType        = Constants.ORDER_TYPE_MARKET;
        p.product          = Constants.PRODUCT_CNC;        // ALWAYS CNC - the entire point of this client
        p.validity         = Constants.VALIDITY_DAY;
        // FIX (confirmed real, not a guess - verified directly from
        // Zerodha's own Kite Connect documentation and developer forum):
        // per SEBI's retail algo regulations, MARKET orders placed via
        // the API now REQUIRE a non-zero market_protection value, or
        // Zerodha rejects them outright with exactly the 400 error seen:
        // "Market orders without market protection are not allowed via
        // API." -1 tells Zerodha to apply its own automatic protection
        // range based on live price (Kite's documented recommended
        // default), rather than us guessing a fixed percentage - this
        // preserves true "market order" behavior (fill immediately at
        // the best available price) while satisfying the new mandatory
        // requirement. A value of 0 is explicitly treated as an
        // UNPROTECTED market order by Zerodha and would be rejected the
        // same way - -1 is not optional, it's the only value that
        // genuinely restores prior market-order behavior under this
        // new rule.
        p.marketProtection = -1;
        return p;
    }

    private String doPlace(OrderParams p) {
        try {
            Order order = kiteConnect.placeOrder(p, Constants.VARIETY_REGULAR);
            log.info("[SWING-ORDER] PLACED: symbol={} tx={} product=CNC qty={} id={}",
                    p.tradingsymbol, p.transactionType, p.quantity, order.orderId);
            return order.orderId;
        } catch (KiteException e) {
            log.error("[SWING-ORDER] REJECTED: symbol={} code={} msg={}",
                    p.tradingsymbol, e.code, e.message);
            throw new ManualSwingOrderException("Order rejected [" + e.code + "]: " + e.message);
        } catch (IOException e) {
            throw new ManualSwingOrderException("Network error: " + e.getMessage());
        }
    }

    public List<Order> getOrderHistory(String orderId) {
        try {
            return kiteConnect.getOrderHistory(orderId);
        } catch (KiteException e) {
            throw new ManualSwingOrderException("[" + e.code + "]: " + e.message);
        } catch (IOException e) {
            throw new ManualSwingOrderException(e.getMessage());
        }
    }

    public static class ManualSwingOrderException extends RuntimeException {
        public ManualSwingOrderException(String msg) { super(msg); }
    }

    /**
     * FIX (added for the auto-selection fallback-to-next-candidate
     * feature): a distinct subtype specifically for the "filled at the
     * broker but DB save failed" scenario - a REAL, untracked position
     * now exists. This must NEVER be treated the same as an ordinary
     * "order didn't fill" failure (which is safe to retry with a
     * different stock) - callers that need to distinguish "safe to try
     * another candidate" from "STOP immediately, human intervention
     * needed" can catch this specific subtype. Existing code that only
     * catches the base ManualSwingOrderException is completely
     * unaffected - it still catches this too, exactly as before.
     */
    public static class PartialFailureException extends ManualSwingOrderException {
        public PartialFailureException(String msg) { super(msg); }
    }
}