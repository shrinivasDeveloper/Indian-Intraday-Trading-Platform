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
                log.warn("[MOMENTUM-TRADE] Poll attempt {} for order {} failed (will retry): {}",
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
        return enterBreakoutInternal(candidate, consolidationHigh, consolidationLow, false,
                candidate.getDirection());
    }

    /**
     * PULLBACK ENTRY (per explicit user request, additive path): enters at
     * a confluence-validated daily/30-min resistance (SHORT) or support
     * (LONG) rejection, detected by evaluatePullback(). Routes through the
     * EXACT SAME full machinery as breakout entries via synthetic bounds:
     * passing (level, level - dailyAtr) for SHORT / (level + dailyAtr,
     * level) for LONG makes the existing structural-stop code compute stop
     * = level +/- 0.3x dailyAtr and the trailing distance 0.5x dailyAtr -
     * mathematically exactly the intended pullback risk model, with zero
     * duplicated sizing/margin/S-R-gate/fresh-price/fill/DB code. The ONLY
     * behavioral difference is pullbackMode=true switching the trend
     * filter to the pullback-aware variant (see checkPullbackTrendFilter).
     */
    public MomentumTrade enterPullback(MomentumCandidate candidate, double level, double dailyAtr,
                                       String direction) {
        boolean isLong = "LONG".equals(direction);
        double syntheticHigh = isLong ? level + dailyAtr : level;
        double syntheticLow  = isLong ? level : level - dailyAtr;
        return enterBreakoutInternal(candidate, syntheticHigh, syntheticLow, true, direction);
    }

    private MomentumTrade enterBreakoutInternal(MomentumCandidate candidate, double consolidationHigh,
                                                double consolidationLow, boolean pullbackMode,
                                                String direction) {
        String symbol = candidate.getSymbol();
        boolean isLong = "LONG".equals(direction);

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
            // FIX (per explicit user agreement this session, structure-
            // based SL with skip rule): the stop now sits beyond the
            // OPPOSITE consolidation edge + a 0.3x-range buffer - the
            // genuine invalidation point, outside the exact zone where
            // clustered stops get swept (the prior price-tiered-only
            // stop could land INSIDE the consolidation on wider
            // patterns, mechanically explaining "valid entry, immediate
            // SL hit"). The price-tier model (computeSlPct) is RETAINED
            // as the AFFORDABILITY CEILING: if the structural distance
            // exceeds what the tier allows, the trade is SKIPPED
            // entirely - a setup whose invalidation point is too far
            // away for intraday R:R isn't a trade with a bad stop, it
            // isn't a valid intraday trade at all (user's own correct
            // reasoning). A proportional floor (0.3x the tier distance)
            // guards against the historically-documented degenerate
            // too-tight case (the Rs.0.65-risk example above) - if the
            // structure is tighter than the floor, the stop widens to
            // the floor, which is still beyond the structure and still
            // within the tier ceiling.
            double consolRangeForSl = consolidationHigh - consolidationLow;
            double structBuffer = consolRangeForSl * 0.3;
            double structuralStop = isLong
                    ? consolidationLow - structBuffer
                    : consolidationHigh + structBuffer;
            double structuralRisk = isLong ? entry - structuralStop : structuralStop - entry;
            double tierRisk = entry * computeSlPct(entry);
            if (structuralRisk <= 0) {
                throw new MomentumStrategyException(symbol + " - invalid structural risk (entry=" +
                        entry + " structuralStop=" + structuralStop + "), skipping");
            }
            if (structuralRisk > tierRisk) {
                throw new MomentumStrategyException(String.format(
                        "%s - consolidation too wide for intraday R:R: structural stop distance " +
                                "%.2f exceeds the price-tier maximum %.2f - correct stop is unaffordable " +
                                "intraday, skipping rather than placing a stop inside the pattern",
                        symbol, structuralRisk, tierRisk));
            }
            double floorRisk = tierRisk * 0.3;
            // FIX (per explicit user request, live evidence: TATACOMM
            // SHORT stopped out on a 3.08-rupee move on a Rs.1745 stock
            // - a fixed 0.3x-tier floor has no relationship to what the
            // stock is ACTUALLY moving right now, so a tight
            // consolidation could produce a stop sitting well inside
            // normal noise). The floor is now the LARGER of the old
            // 0.3x-tier value and 0.5x the stock's LIVE 5-min ATR - a
            // noise-aware minimum. If even this floor exceeds the tier
            // ceiling, the trade is SKIPPED (same already-agreed
            // principle: an unaffordable-but-correct stop is not a
            // valid intraday trade) rather than forcing a stop tighter
            // than the stock's real movement. Fails open to the
            // original floor only if live ATR data is unavailable -
            // never blocks a trade on a data hiccup.
            double liveAtr5m = candleService.compute5MinAtr(symbol);
            if (liveAtr5m > 0) {
                double noiseFloor = liveAtr5m * 0.5;
                if (noiseFloor > floorRisk) {
                    if (noiseFloor > tierRisk) {
                        throw new MomentumStrategyException(String.format(
                                "%s - even the noise-aware minimum stop (0.5x live 5-min ATR = " +
                                        "%.2f) exceeds the price-tier maximum %.2f - this stock's current " +
                                        "volatility makes any correct stop unaffordable intraday, skipping",
                                symbol, noiseFloor, tierRisk));
                    }
                    log.info("[MOMENTUM-TRADE] {} - structural/old floor ({}) was tighter than " +
                                    "this stock's live 5-min noise (0.5x ATR = {}) - widening the stop " +
                                    "to the noise-aware floor to avoid a stop-hunt", symbol,
                            String.format("%.2f", floorRisk), String.format("%.2f", noiseFloor));
                    floorRisk = noiseFloor;
                }
            }
            double stopLoss;
            double riskPerShare;
            if (structuralRisk < floorRisk) {
                riskPerShare = floorRisk;
                stopLoss = isLong ? entry - floorRisk : entry + floorRisk;
            } else {
                riskPerShare = structuralRisk;
                stopLoss = structuralStop;
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
            var trendResult = pullbackMode
                    ? candleService.checkPullbackTrendFilter(symbol, direction, entry)
                    : candleService.checkTrendFilters(symbol, direction, entry);
            if (!trendResult.passed()) {
                throw new MomentumStrategyException(symbol + " - " + trendResult.reason());
            }
            log.info("[MOMENTUM-TRADE] {} trend filters passed: {}", symbol, trendResult.reason());

            // NEW GATE (per explicit user request: "add Daily Support/
            // Resistance/trendline Validation Gate... execute after all
            // existing momentum validations pass and before trade
            // execution"). A new, independent, additional check - does
            // NOT modify the trend filter above or any other existing
            // validation. If this fails, the same rejection path already
            // used for every other gate fires - scanning simply
            // continues to the next candidate, exactly as before.
            var srGateResult = candleService.checkHigherTimeframeGate(
                    symbol, direction, entry, riskPerShare);
            if (!srGateResult.passed()) {
                throw new MomentumStrategyException(symbol + " - " + srGateResult.reason());
            }
            log.info("[MOMENTUM-TRADE] {} daily S/R gate passed: {}", symbol, srGateResult.reason());

            // NEW GATE (per explicit user request: "same apply for 30
            // minute... cross checking 30 minutes support/resistance/
            // trend/retest all logic we have, please implement same
            // logic but 30 minutes as well"). A new, independent,
            // additional check running right after the daily gate above -
            // does NOT modify the daily gate, trend filter, or any other
            // existing validation. Same rejection path as every other
            // gate - scanning continues to the next candidate normally.
            var srGate30mResult = candleService.check30MinuteHigherTimeframeGate(
                    symbol, direction, entry, riskPerShare);
            if (!srGate30mResult.passed()) {
                throw new MomentumStrategyException(symbol + " - " + srGate30mResult.reason());
            }
            log.info("[MOMENTUM-TRADE] {} 30-min S/R gate passed: {}", symbol, srGate30mResult.reason());

            // FIX (confirmed real gap found via direct user report: two
            // trades today passed every gate, then immediately reversed
            // and hit stop-loss). Root cause: entry price was fetched
            // once at the top of this method, then used stale through
            // risk validity, quantity, margin, trend filter, daily S/R,
            // and 30-min S/R gates - none of which re-check the CURRENT
            // live price. If either S/R gate hit a cache miss (a real,
            // multi-second API fetch) or any other gate took meaningful
            // time, the market could move significantly during that
            // window - and since this is a MARKET order, it fills at
            // whatever price exists at that moment, not the price all
            // the gates validated against. This final check re-fetches
            // a fresh LTP immediately before the order fires and rejects
            // if price has already moved meaningfully against the
            // intended direction - specifically, 30% of the planned
            // risk-per-share: a proportional, principled threshold
            // (not an arbitrary number) - if price has already moved a
            // third of the way toward the stop-loss before the order
            // even reaches the market, the breakout has very likely
            // already started failing.
            double freshLtp = fetchLtp(symbol);
            double reversalThreshold = riskPerShare * 0.3;
            boolean alreadyReversing = isLong
                    ? freshLtp < entry - reversalThreshold
                    : freshLtp > entry + reversalThreshold;
            if (alreadyReversing) {
                throw new MomentumStrategyException(String.format(
                        "%s - price already moved against %s direction since entry was locked in " +
                                "(entry=%.2f, fresh LTP=%.2f, moved %.2f of planned %.2f risk) - breakout " +
                                "likely already reversing, skipping rather than chasing a failing move",
                        symbol, direction, entry, freshLtp,
                        Math.abs(freshLtp - entry), riskPerShare));
            }

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
            // FIX (structure-based SL, same session): uses the SAME
            // structural logic as the pre-order computation above -
            // critical consistency. The structural stop is a fixed
            // PRICE LEVEL (consolidation edge + buffer), so it stays
            // put; only the risk DISTANCE recomputes from the real
            // fill. Safety fallback: if the real fill drifted across
            // the skip boundary (position is already open - skipping
            // is no longer possible), fall back to the tier stop
            // rather than ever placing a stop inside the pattern.
            double realTierRisk = realEntry * computeSlPct(realEntry);
            double realStructuralRisk = isLong
                    ? realEntry - structuralStop : structuralStop - realEntry;
            double realFloorRisk = realTierRisk * 0.3;
            // FIX (same noise-aware floor as the pre-order computation,
            // reusing liveAtr5m already fetched above in this method -
            // zero extra API call). Position is already open here, so
            // skipping isn't possible; widen toward the noise floor,
            // capped at the tier ceiling exactly like the existing
            // tier-fallback branch below already does.
            if (liveAtr5m > 0) {
                double realNoiseFloor = Math.min(liveAtr5m * 0.5, realTierRisk);
                if (realNoiseFloor > realFloorRisk) realFloorRisk = realNoiseFloor;
            }
            double realStopLoss;
            double realRiskPerShare;
            if (realStructuralRisk <= 0 || realStructuralRisk > realTierRisk) {
                realRiskPerShare = realTierRisk;
                realStopLoss = isLong ? realEntry - realTierRisk : realEntry + realTierRisk;
            } else if (realStructuralRisk < realFloorRisk) {
                realRiskPerShare = realFloorRisk;
                realStopLoss = isLong ? realEntry - realFloorRisk : realEntry + realFloorRisk;
            } else {
                realRiskPerShare = realStructuralRisk;
                realStopLoss = structuralStop;
            }
            double realTarget = realRiskPerShare > 0
                    ? (isLong ? realEntry + realRiskPerShare * config.getRiskRewardRatio()
                    : realEntry - realRiskPerShare * config.getRiskRewardRatio())
                    : realEntry; // should be unreachable now (tier fallback is always
            // positive) - kept as a safety net regardless

            positionRegistry.registerPosition(symbol, "MOMENTUM_STOCK_OF_DAY");

            MomentumTrade trade = MomentumTrade.builder()
                    .symbol(symbol)
                    .sector(candidate.getSector())
                    .sectorRank(candidate.getSectorRank())
                    .direction(direction)
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
                            "target={} (consolidation {}-{})", direction, symbol, realQty,
                    realEntry, realStopLoss, realTarget, consolidationLow, consolidationHigh);
            return saved;

        } catch (MomentumStrategyException e) {
            throw e;
        } catch (KiteException | Exception e) {
            throw new MomentumStrategyException("Order placement failed for " + symbol, e);
        }
    }

    /** Per spec: "Once the initial target is reached, automatically
     *  activate a trailing stop-loss to capture additional momentum."
     *
     *  FIX (confirmed real bug via direct code trace - root cause of
     *  "target reached but profit not booked"): this method used to
     *  update ONLY the database row via repository.updateTrailingStop()
     *  and return void. MomentumTrade has @Getter only (no setters), so
     *  the in-memory trade object handed in was NEVER updated - every
     *  subsequent call (from the SAME stale object the scheduler kept
     *  reusing) still read trade.getCurrentTrailStop()==null and fell
     *  back to the ORIGINAL stop-loss for the actual exit decision,
     *  even though the database (and dashboard) correctly showed the
     *  tightened trailing stop. The position could ride all the way
     *  back to the original SL - giving back the entire target gain -
     *  before ever exiting. Now RETURNS the updated trade (via
     *  toBuilder(), since fields are immutable) so the caller can keep
     *  its reference current; returns the SAME object unchanged if no
     *  update happened this tick. */
    public MomentumTrade checkAndUpdateTrailingStop(MomentumTrade trade, double currentPrice) {
        boolean isLong = "LONG".equals(trade.getDirection());
        double target = trade.getTarget().doubleValue();
        boolean targetReached = isLong ? currentPrice >= target : currentPrice <= target;

        if (!targetReached && !trade.isTrailingActive()) return trade;

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
            BigDecimal newStopBd = BigDecimal.valueOf(newTrailStop).setScale(2, RoundingMode.HALF_UP);
            repository.updateTrailingStop(trade.getTradeId(), newStopBd);
            log.info("[MOMENTUM-TRADE] {} trailing stop updated: {} -> {}", trade.getSymbol(),
                    currentStop, newTrailStop);
            // FIX: return an updated COPY so the caller's reference is
            // no longer stale - this is what was missing before.
            return trade.toBuilder()
                    .trailingActive(true)
                    .currentTrailStop(newStopBd)
                    .build();
        }
        return trade;
    }

    /**
     * FIX (confirmed serious bug found via direct user report - NAM-INDIA
     * showed ACTIVE forever in the UI, with no exit order ever appearing
     * in the broker's order list). Root cause: this method's own catch
     * block already logged "will retry next cycle" on failure, but never
     * actually communicated that failure to the caller - every caller
     * was unconditionally clearing its in-memory active-trade reference
     * right after calling this, regardless of whether the exit genuinely
     * succeeded. If placeMarketOrder() or pollForFill() ever threw (
     * network blip, transient API error, order rejection), the database
     * correctly stayed ACTIVE (markClosed() never ran) - but the
     * scheduler's only reference to retry it was destroyed anyway,
     * turning a single transient failure into a PERMANENT ghost position:
     * still ACTIVE in the UI/database forever, never monitored or
     * retried again until the app happened to restart. Now returns
     * true/false so every caller can correctly keep retrying instead of
     * silently abandoning the trade.
     */
    public boolean exitTrade(MomentumTrade trade, String reason) {
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
            return true;
        } catch (KiteException | Exception e) {
            log.error("[MOMENTUM-TRADE] Exit FAILED for {} ({}): {} - will retry next cycle",
                    trade.getSymbol(), reason, e.getMessage());
            return false;
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
     *  FIX (same root-cause fix as checkAndUpdateTrailingStop): now
     *  returns the CURRENT trade object (updated if the trailing stop
     *  moved this tick) instead of a bare boolean, so the caller can
     *  keep its reference fresh instead of reusing a permanently-stale
     *  object. Returns null if the trade just closed. */
    public MomentumTrade monitorActiveTrade(MomentumTrade trade) {
        try {
            double ltp = fetchLtp(trade.getSymbol());
            if (ltp <= 0) return trade; // couldn't check this cycle - assume still active, retry next cycle

            boolean isLong = "LONG".equals(trade.getDirection());
            double sl = trade.getStopLoss().doubleValue();
            double trailStop = trade.getCurrentTrailStop() != null
                    ? trade.getCurrentTrailStop().doubleValue() : sl;

            boolean slHit = isLong ? ltp <= trailStop : ltp >= trailStop;
            if (slHit) {
                boolean exited = exitTrade(trade, trade.isTrailingActive() ? "TRAILING_STOP_HIT" : "SL_HIT");
                return exited ? null : trade; // FIX: only report "closed" (null) if the exit genuinely
                // succeeded - otherwise stay ACTIVE so the scheduler
                // keeps retrying next cycle with the same trade
            }

            // FIX: use the RETURNED (possibly-updated) trade from here
            // on - this is the actual fix for the stale-object bug.
            // Same tick, so if the trail stop just moved, the very
            // next monitoring cycle already sees the fresh value.
            return checkAndUpdateTrailingStop(trade, ltp);
        } catch (KiteException | Exception e) {
            log.warn("[MOMENTUM-TRADE] Monitoring check failed for {} (non-fatal, retry next " +
                    "cycle): {}", trade.getSymbol(), e.getMessage());
            return trade; // couldn't determine - assume still active, retry next cycle
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
            log.warn("[MOMENTUM-TRADE] Real margin calculation failed for {} - falling back " +
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
            log.warn("[MOMENTUM-TRADE] Reconciliation check failed for {} (non-fatal, will " +
                    "retry next cycle): {}", trade.getSymbol(), e.getMessage());
            return true; // couldn't determine - assume still valid, retry next cycle
        }
    }
}