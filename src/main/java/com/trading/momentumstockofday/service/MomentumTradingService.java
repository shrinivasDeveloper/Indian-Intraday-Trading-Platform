package com.trading.momentumstockofday.service;

import com.trading.momentumstockofday.config.MomentumConfig;
import com.trading.momentumstockofday.domain.MomentumCandidate;
import com.trading.momentumstockofday.domain.MomentumTrade;
import com.trading.momentumstockofday.exception.MomentumStrategyException;
import com.trading.momentumstockofday.repository.MomentumTradeRepository;
import com.trading.shared.risk.AccountMarginGuard;
import com.trading.shared.risk.CrossStrategyPositionRegistry;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.kiteconnect.utils.Constants;
import com.zerodhatech.models.Order;
import com.zerodhatech.models.OrderParams;
import com.zerodhatech.models.Quote;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * MomentumTradingService - order placement and trade management for
 * this strategy.
 *
 * INDEPENDENCE (per explicit requirement): does NOT call into any
 * existing strategy's order-execution class (not
 * AiLiveOrderExecutionService, not ManualSwingOrderClient, not
 * HeroZeroTradingService). Builds and places its own orders directly
 * via KiteConnect, using the SAME proven, SAFE patterns already
 * validated repeatedly this session (market_protection=-1 for SEBI
 * compliance, explicit KiteException handling since it extends
 * Throwable not Exception, fill-confirmation polling matching Swing/
 * Hero-or-Zero's own proven pattern) - these are correct ENGINEERING
 * PRACTICES, not "strategy logic," and are independently re-
 * implemented here, not imported from anywhere else.
 *
 * FIX (found via thorough order-execution review, per explicit
 * request): the original version placed a market order and
 * immediately trusted the pre-calculated ESTIMATED price as if it
 * were the real fill - with zero confirmation the order actually
 * filled, at what price, or for how much quantity. Added genuine
 * fill-confirmation polling, matching the exact proven pattern
 * already used by Swing and Hero-or-Zero - the REAL, confirmed fill
 * price and quantity are now what gets recorded and used for SL/
 * target calculation, not an estimate.
 *
 * Uses AccountMarginGuard and CrossStrategyPositionRegistry - the same
 * genuinely shared, cross-strategy safeguards already wired into every
 * other strategy this session (capital/margin and symbol exposure are
 * real, shared account-level resources, not strategy-specific logic).
 */
@Service
@Slf4j
public class MomentumTradingService {

    private final KiteConnect kiteConnect;
    private final MomentumConfig config;
    private final MomentumTradeRepository repository;
    private final AccountMarginGuard marginGuard;
    private final CrossStrategyPositionRegistry positionRegistry;
    private final com.trading.momentumstockofday.repository.MomentumCapitalRepository capitalRepository;
    private final MomentumCandleService candleService;

    public MomentumTradingService(KiteConnect kiteConnect, MomentumConfig config,
                                  MomentumTradeRepository repository,
                                  AccountMarginGuard marginGuard,
                                  CrossStrategyPositionRegistry positionRegistry,
                                  com.trading.momentumstockofday.repository.MomentumCapitalRepository capitalRepository,
                                  MomentumCandleService candleService) {
        this.kiteConnect = kiteConnect;
        this.config = config;
        this.repository = repository;
        this.marginGuard = marginGuard;
        this.positionRegistry = positionRegistry;
        this.capitalRepository = capitalRepository;
        this.candleService = candleService;
    }

    /** Result of a confirmed (or timed-out) fill check. filled=false
     *  means the poll window expired without a COMPLETE status - the
     *  caller must decide how to handle that (Swing/Hero-or-Zero's own
     *  proven pattern: keep the position tracked, flag for manual
     *  review rather than silently assuming success or failure). */
    private record FillResult(boolean filled, double avgPrice, int filledQty) {}

    /**
     * Polls getOrderHistory for genuine COMPLETE status - same proven
     * pattern already used by Swing and Hero-or-Zero. Order's
     * averagePrice/filledQuantity/status are all String fields
     * (confirmed via bytecode inspection earlier this session), parsed
     * safely here.
     */
    private FillResult pollForFill(String orderId) {
        for (int attempt = 1; attempt <= config.getOrderPollMaxAttempts(); attempt++) {
            try {
                List<Order> history = kiteConnect.getOrderHistory(orderId);
                if (history != null && !history.isEmpty()) {
                    Order latest = history.get(history.size() - 1);
                    if ("COMPLETE".equals(latest.status)) {
                        double avgPrice = safeParseDouble(latest.averagePrice);
                        int filledQty = safeParseInt(latest.filledQuantity);
                        return new FillResult(true, avgPrice, filledQty);
                    }
                    if ("REJECTED".equals(latest.status) || "CANCELLED".equals(latest.status)) {
                        log.warn("[MOMENTUM-TRADE] Order {} was {} - not filled",
                                orderId, latest.status);
                        return new FillResult(false, 0, 0);
                    }
                }
            } catch (KiteException | Exception e) {
                log.debug("[MOMENTUM-TRADE] Poll attempt {} for order {} failed (will retry): {}",
                        attempt, orderId, e.getMessage());
            }
            try {
                Thread.sleep(config.getOrderPollIntervalMs());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.warn("[MOMENTUM-TRADE] Order {} fill could not be confirmed after {} attempts - " +
                "check Zerodha directly", orderId, config.getOrderPollMaxAttempts());
        return new FillResult(false, 0, 0);
    }

    private double safeParseDouble(String s) {
        try { return s != null ? Double.parseDouble(s) : 0.0; } catch (Exception e) { return 0.0; }
    }

    private int safeParseInt(String s) {
        try { return s != null ? Integer.parseInt(s) : 0; } catch (Exception e) { return 0; }
    }

    /**
     * Executes the actual breakout entry. Per spec:
     *   - SL uses AI's price-tiered % model (computeSlPct below) - a
     *     wider, price-proportional distance, not the tight
     *     consolidation range
     *   - Target at 1:1.5 RR
     */
    public MomentumTrade enterBreakout(MomentumCandidate candidate, double consolidationHigh,
                                       double consolidationLow) {
        String symbol = candidate.getSymbol();
        boolean isLong = "LONG".equals(candidate.getDirection());

        try {
            double ltp = fetchLtp(symbol);
            if (ltp <= 0) {
                throw new MomentumStrategyException("Could not fetch a valid live price for " + symbol);
            }

            double entry = isLong ? ltp * 1.0005 : ltp * 0.9995; // same 0.05% slippage buffer
            // already proven elsewhere

            // FIX (per explicit user request: "the current stop-loss is
            // too small... can we use the same stop-loss approach as the
            // AI Trading strategy"). Confirmed real issue: the previous
            // consolidation-range-based SL could be extremely tight (a
            // concrete example: Rs.850.55 entry, Rs.849.90 SL - only
            // Rs.0.65 risk, genuinely too small for a real position).
            // Replaced with AI's own proven, price-tiered % model
            // (AiRiskAssessmentEngine.computeSlPct()) - same exact tiers,
            // independently re-implemented here (computeSlPct() below) to
            // preserve Momentum's required independence from AI's code.
            // The consolidation high/low are UNCHANGED for their original
            // purpose - they still correctly trigger the breakout ENTRY
            // signal, and still size the trailing-stop distance later -
            // only the STOP-LOSS DISTANCE itself now comes from this
            // wider, price-proportional model instead of the tight
            // consolidation range.
            double slPct = computeSlPct(entry);
            double stopLoss = isLong ? entry * (1 - slPct) : entry * (1 + slPct);
            double riskPerShare = isLong ? entry - stopLoss : stopLoss - entry;
            if (riskPerShare <= 0) {
                throw new MomentumStrategyException(symbol + " - invalid risk (entry=" + entry +
                        " sl=" + stopLoss + ") - price-tiered SL computation degenerate, skipping");
            }
            double target = isLong
                    ? entry + riskPerShare * config.getRiskRewardRatio()
                    : entry - riskPerShare * config.getRiskRewardRatio();

            // Capital comes from the UI-settable MomentumCapitalRepository.
            // Quantity is genuinely risk-based: 1% of allocated capital is
            // the maximum amount risked on this trade - quantity = that
            // risk amount / the actual risk-per-share (now the price-
            // tiered SL distance above) - capped by what the capital can
            // actually afford, same dual-check pattern already proven
            // correct in AI's own risk engine.
            double capital = capitalRepository.getCapital().doubleValue();
            double riskAmt = capital * 0.01; // 1% of allocated capital
            int riskBasedQty = (int) (riskAmt / riskPerShare);
            int affordableQty = (int) (capital / entry);
            int qty = Math.min(riskBasedQty, affordableQty);
            if (qty <= 0) {
                throw new MomentumStrategyException(symbol + " - computed quantity is 0 " +
                        "(capital Rs." + capital + " riskAmt=Rs." + riskAmt + " riskPerShare=" +
                        riskPerShare + " riskBasedQty=" + riskBasedQty + " affordableQty=" +
                        affordableQty + ")");
            }

            BigDecimal estimatedCost = getRealMarginRequired(symbol, isLong ?
                    Constants.TRANSACTION_TYPE_BUY : Constants.TRANSACTION_TYPE_SELL, qty, entry);
            var marginResult = marginGuard.checkSufficientMargin(estimatedCost, "MOMENTUM_STOCK_OF_DAY");
            if (!marginResult.sufficient()) {
                throw new MomentumStrategyException(symbol + " - insufficient account margin " +
                        "(need ~Rs." + estimatedCost + ", available Rs." + marginResult.availableMargin() + ")");
            }
            positionRegistry.checkAndWarnIfHeldElsewhere(symbol, "MOMENTUM_STOCK_OF_DAY");

            // FEATURE 3 (Mandatory Trend Confirmation Filters, per
            // explicit user spec): the ABSOLUTE LAST gate, immediately
            // before order placement - after every other existing
            // validation (capital, risk, margin, position registry).
            // "A trade should only be placed if every validation step
            // passes successfully." Rejects clearly if either the 4H
            // VWAP or 4H EMA alignment filter fails - both are mandatory.
            var trendResult = candleService.checkTrendFilters(symbol, candidate.getDirection(), entry);
            if (!trendResult.passed()) {
                throw new MomentumStrategyException(symbol + " - " + trendResult.reason());
            }
            log.info("[MOMENTUM-TRADE] {} trend filters passed: {}", symbol, trendResult.reason());

            String orderId = placeMarketOrder(symbol, isLong ? Constants.TRANSACTION_TYPE_BUY
                    : Constants.TRANSACTION_TYPE_SELL, qty);

            // FIX (found via thorough order-execution review): confirm
            // the order genuinely FILLED before trusting any price -
            // same proven pattern as Swing/Hero-or-Zero. Uses the REAL
            // average fill price and REAL filled quantity for
            // everything downstream (SL/target recalculated from the
            // ACTUAL entry, not the pre-order estimate).
            FillResult fill = pollForFill(orderId);
            if (!fill.filled()) {
                throw new MomentumStrategyException(symbol + " - buy order placed (orderId=" +
                        orderId + ") but fill could not be confirmed - check Zerodha directly " +
                        "before retrying, to avoid a duplicate position");
            }

            double realEntry = fill.avgPrice();
            int realQty = fill.filledQty();
            // Recompute SL/target from the REAL fill price - if the
            // actual fill differs from the pre-order estimate (entirely
            // possible during a fast breakout), the recorded risk:reward
            // must reflect what was ACTUALLY entered, not the estimate.
            // Uses the SAME price-tiered SL model as the pre-order
            // estimate above - critical consistency, since a mismatched
            // formula here would make the saved trade's risk:reward
            // internally contradictory.
            double realSlPct = computeSlPct(realEntry);
            double realStopLoss = isLong ? realEntry * (1 - realSlPct) : realEntry * (1 + realSlPct);
            double realRiskPerShare = isLong ? realEntry - realStopLoss : realStopLoss - realEntry;
            double realTarget = realRiskPerShare > 0
                    ? (isLong ? realEntry + realRiskPerShare * config.getRiskRewardRatio()
                    : realEntry - realRiskPerShare * config.getRiskRewardRatio())
                    : realEntry; // should be unreachable now (a % of a positive price is
            // always positive) - kept as a safety net regardless

            positionRegistry.registerPosition(symbol, "MOMENTUM_STOCK_OF_DAY");

            MomentumTrade trade = MomentumTrade.builder()
                    .symbol(symbol)
                    .sector(candidate.getSector())
                    .sectorRank(candidate.getSectorRank())
                    .direction(candidate.getDirection())
                    .entryPrice(BigDecimal.valueOf(realEntry).setScale(2, RoundingMode.HALF_UP))
                    .stopLoss(BigDecimal.valueOf(realStopLoss).setScale(2, RoundingMode.HALF_UP))
                    .target(BigDecimal.valueOf(realTarget).setScale(2, RoundingMode.HALF_UP))
                    .consolidationHigh(BigDecimal.valueOf(consolidationHigh).setScale(2, RoundingMode.HALF_UP))
                    .consolidationLow(BigDecimal.valueOf(consolidationLow).setScale(2, RoundingMode.HALF_UP))
                    .quantity(realQty)
                    .entryOrderId(orderId)
                    .build();

            MomentumTrade saved = repository.save(trade);
            log.info("[MOMENTUM-TRADE] ENTRY CONFIRMED: {} {} qty={} realEntry={} sl={} " +
                            "target={} (consolidation {}-{})", candidate.getDirection(), symbol, realQty,
                    realEntry, realStopLoss, realTarget, consolidationLow, consolidationHigh);
            return saved;

        } catch (MomentumStrategyException e) {
            throw e;
        } catch (KiteException | Exception e) {
            throw new MomentumStrategyException("Order placement failed for " + symbol, e);
        }
    }

    /** Per spec: "Once the initial target is reached, automatically
     *  activate a trailing stop-loss to capture additional momentum." */
    public void checkAndUpdateTrailingStop(MomentumTrade trade, double currentPrice) {
        boolean isLong = "LONG".equals(trade.getDirection());
        double target = trade.getTarget().doubleValue();
        boolean targetReached = isLong ? currentPrice >= target : currentPrice <= target;

        if (!targetReached && !trade.isTrailingActive()) return;

        double consolidationRange = trade.getConsolidationHigh().doubleValue()
                - trade.getConsolidationLow().doubleValue();
        double trailDistance = consolidationRange * config.getTrailingStopConsolidationRangeMultiple();

        double newTrailStop = isLong ? currentPrice - trailDistance : currentPrice + trailDistance;
        double currentStop = trade.getCurrentTrailStop() != null
                ? trade.getCurrentTrailStop().doubleValue() : trade.getStopLoss().doubleValue();

        // Trailing stop only ever moves in the FAVORABLE direction -
        // never loosens, per standard trailing-stop discipline.
        boolean shouldUpdate = isLong ? newTrailStop > currentStop : newTrailStop < currentStop;
        if (shouldUpdate) {
            repository.updateTrailingStop(trade.getTradeId(),
                    BigDecimal.valueOf(newTrailStop).setScale(2, RoundingMode.HALF_UP));
            log.info("[MOMENTUM-TRADE] {} trailing stop updated: {} -> {}", trade.getSymbol(),
                    currentStop, newTrailStop);
        }
    }

    public void exitTrade(MomentumTrade trade, String reason) {
        boolean isLong = "LONG".equals(trade.getDirection());
        try {
            String orderId = placeMarketOrder(trade.getSymbol(),
                    isLong ? Constants.TRANSACTION_TYPE_SELL : Constants.TRANSACTION_TYPE_BUY,
                    trade.getQuantity());

            // FIX (found via thorough order-execution review): confirm
            // the exit genuinely filled and use its REAL average price,
            // instead of a separate LTP fetch that could differ from
            // what the order actually executed at.
            FillResult fill = pollForFill(orderId);
            double exitPrice = fill.filled() ? fill.avgPrice() : fetchLtp(trade.getSymbol());
            if (!fill.filled()) {
                log.warn("[MOMENTUM-TRADE] {} exit order {} fill could not be confirmed - " +
                        "recording last known LTP as a best-effort exit price, please verify " +
                        "in Zerodha directly", trade.getSymbol(), orderId);
            }

            repository.markClosed(trade.getTradeId(),
                    BigDecimal.valueOf(exitPrice).setScale(2, RoundingMode.HALF_UP), orderId, reason);
            positionRegistry.releasePosition(trade.getSymbol(), "MOMENTUM_STOCK_OF_DAY");
            log.info("[MOMENTUM-TRADE] EXIT {}: {} qty={} reason={} exitPrice={}",
                    fill.filled() ? "CONFIRMED" : "UNCONFIRMED (best-effort price)",
                    trade.getSymbol(), trade.getQuantity(), reason, exitPrice);
        } catch (KiteException | Exception e) {
            log.error("[MOMENTUM-TRADE] Exit FAILED for {} ({}): {} - will retry next cycle",
                    trade.getSymbol(), reason, e.getMessage());
        }
    }

    private String placeMarketOrder(String symbol, String txType, int qty)
            throws KiteException, java.io.IOException, org.json.JSONException {
        OrderParams p = new OrderParams();
        p.tradingsymbol = symbol;
        p.exchange = "NSE";
        p.transactionType = txType;
        p.quantity = qty;
        p.orderType = Constants.ORDER_TYPE_MARKET;
        p.product = Constants.PRODUCT_MIS;
        p.validity = Constants.VALIDITY_DAY;
        p.marketProtection = -1; // SEBI-required for market orders - same fix applied
        // consistently across every strategy this session
        Order order = kiteConnect.placeOrder(p, Constants.VARIETY_REGULAR);
        return order.orderId;
    }

    /** Per spec: monitor an active trade for SL hit, target hit
     *  (activating trailing stop), or trailing stop hit.
     *  Returns true if the trade is still active, false if it just
     *  closed - lets the caller (scheduler) know when it's safe to
     *  resume monitoring for a next trade, per the 2-trades-per-day
     *  enhancement. */
    public boolean monitorActiveTrade(MomentumTrade trade) {
        try {
            double ltp = fetchLtp(trade.getSymbol());
            if (ltp <= 0) return true; // couldn't check this cycle - assume still active, retry next cycle

            boolean isLong = "LONG".equals(trade.getDirection());
            double sl = trade.getStopLoss().doubleValue();
            double trailStop = trade.getCurrentTrailStop() != null
                    ? trade.getCurrentTrailStop().doubleValue() : sl;

            boolean slHit = isLong ? ltp <= trailStop : ltp >= trailStop;
            if (slHit) {
                exitTrade(trade, trade.isTrailingActive() ? "TRAILING_STOP_HIT" : "SL_HIT");
                return false; // closed
            }

            checkAndUpdateTrailingStop(trade, ltp);
            return true; // still active
        } catch (KiteException | Exception e) {
            log.debug("[MOMENTUM-TRADE] Monitoring check failed for {} (non-fatal, retry next " +
                    "cycle): {}", trade.getSymbol(), e.getMessage());
            return true; // couldn't determine - assume still active, retry next cycle
        }
    }

    /**
     * Calls Zerodha's own real order-margin calculation API - the exact
     * same fix confirmed for AI/News's margin check (same systemic bug
     * found independently here: naive price x quantity ignores real MIS
     * leverage, causing genuinely valid trades to be rejected). Falls
     * back to the conservative full-cash estimate ONLY if this real API
     * call itself fails - never silently under-checks margin.
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
            params.price = 0;
            params.triggerPrice = 0;

            var results = kiteConnect.getMarginCalculation(java.util.List.of(params));
            if (results != null && !results.isEmpty()) {
                double realMargin = results.get(0).total;
                if (realMargin > 0) return BigDecimal.valueOf(realMargin);
            }
        } catch (KiteException | Exception e) {
            log.debug("[MOMENTUM-TRADE] Real margin calculation failed for {} - falling back " +
                    "to conservative full-cash estimate: {}", symbol, e.getMessage());
        }
        return BigDecimal.valueOf(price).multiply(BigDecimal.valueOf(qty));
    }

    /**
     * Price-tiered stop-loss percentage - the exact same model as
     * AiRiskAssessmentEngine.computeSlPct(), independently re-implemented
     * here (not imported) to preserve Momentum's required independence
     * from AI's code. Per explicit user request: "can we use the same
     * stop-loss approach as the AI Trading strategy for the Momentum
     * strategy instead" - confirmed the previous consolidation-range-
     * based SL could be far too tight (e.g. Rs.0.65 risk on an Rs.850
     * stock); this gives a wider, price-proportional, genuinely tested
     * distance instead.
     */
    private double computeSlPct(double price) {
        if      (price <= 130)  return 0.020;  // 2.0%
        else if (price <= 170)  return 0.017;  // 1.7%
        else if (price <= 200)  return 0.013;  // 1.3%
        else if (price <= 400)  return 0.010;  // 1.0%
        else if (price <= 700)  return 0.007;  // 0.7%
        else if (price <= 1200) return 0.006;  // 0.6%
        else                    return 0.005;  // 0.5%
    }

    private double fetchLtp(String symbol) throws KiteException, java.io.IOException, org.json.JSONException {
        String key = "NSE:" + symbol;
        Map<String, Quote> quotes = kiteConnect.getQuote(new String[]{key});
        Quote q = quotes.get(key);
        return q != null ? q.lastPrice : 0.0;
    }

    /**
     * FIX (3rd confirmed gap from thorough order-execution review):
     * a basic reconciliation safety net - if an order was placed but
     * silently rejected moments later (margin issue, circuit limit,
     * etc.), or a position was somehow closed outside this strategy's
     * own tracking, this would previously never be detected - the
     * trade would sit as ACTIVE in the database forever with no real
     * position behind it. Compares the broker's REAL position for
     * this symbol against our recorded quantity; if the broker
     * genuinely shows zero, corrects our stale record. Deliberately
     * conservative - only ever CLOSES a stale record it can prove is
     * gone, never guesses or force-adjusts a quantity mismatch.
     */
    public boolean reconcileWithBroker(MomentumTrade trade) {
        try {
            List<com.zerodhatech.models.Position> positions = kiteConnect.getPositions().get("net");
            if (positions == null) return true; // couldn't check this cycle - assume still valid

            boolean foundAtBroker = false;
            for (com.zerodhatech.models.Position p : positions) {
                if (trade.getSymbol().equalsIgnoreCase(p.tradingSymbol) && p.netQuantity != 0) {
                    foundAtBroker = true;
                    break;
                }
            }

            if (!foundAtBroker) {
                log.warn("[MOMENTUM-TRADE] {} shows ACTIVE in our records, but broker confirms " +
                        "zero position - this trade was almost certainly closed outside this " +
                        "strategy's own tracking (rejected order we missed, or manual " +
                        "intervention). Correcting our stale record now.", trade.getSymbol());
                repository.markClosed(trade.getTradeId(), trade.getEntryPrice(),
                        "RECONCILED_EXTERNALLY", "CLOSED_EXTERNALLY_RECONCILED");
                positionRegistry.releasePosition(trade.getSymbol(), "MOMENTUM_STOCK_OF_DAY");
                return false; // no longer genuinely active
            }
            return true;
        } catch (KiteException | Exception e) {
            log.debug("[MOMENTUM-TRADE] Reconciliation check failed for {} (non-fatal, will " +
                    "retry next cycle): {}", trade.getSymbol(), e.getMessage());
            return true; // couldn't determine - assume still valid, retry next cycle
        }
    }
}