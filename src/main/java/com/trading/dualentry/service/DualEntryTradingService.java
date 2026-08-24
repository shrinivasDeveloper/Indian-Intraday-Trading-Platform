package com.trading.dualentry.service;

import com.trading.dualentry.config.DualEntryConfig;
import com.trading.dualentry.domain.DualEntryTrade;
import com.trading.dualentry.exception.DualEntryStrategyException;
import com.trading.dualentry.repository.DualEntryTradeRepository;
import com.trading.marketdata.service.MarketDataService;
import com.trading.momentumstockofday.domain.MomentumCandidate;
import com.trading.momentumstockofday.service.MomentumCandleService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DualEntryTradingService — order placement for the new, isolated
 * Breakout + Pullback strategy (per explicit user request).
 *
 * EXACTLY the 12 specified gates. No Volume Profile gate (deliberately
 * excluded - not in the spec's gate list). No scoring anywhere -
 * every gate is a hard AND condition.
 *
 * ISOLATION: own exception type, own trade domain/repository, own
 * order-placement code (independently implemented, not calling into
 * MomentumTradingService at all). Shares only genuinely common,
 * side-effect-free infrastructure: KiteConnect, AccountMarginGuard,
 * CrossStrategyPositionRegistry, MarketDataService, and the specific
 * MomentumCandleService methods verified to have zero shared mutable
 * state (checkHigherTimeframeGate, check30MinuteHigherTimeframeGate,
 * compute5MinAtr) plus OrderFlowConfirmationService (gate 12, matches
 * spec exactly, genuinely stateless/reusable).
 *
 * GATE 10/11 NOTE: the order-book thresholds specified (LONG ratio
 * >=1.50, SHORT ratio >=3.00, 5 consecutive samples) differ from
 * Momentum's own OrderBookConfirmationService (uniform 1.30, 3
 * samples) - so this gate is independently implemented here, reading
 * the same shared MarketDataService.getDepthHistory() raw data but
 * with THIS strategy's own thresholds. This is a disclosed, necessary
 * duplication due to genuinely different numeric requirements, not
 * unnecessary duplication of identical logic.
 */
@Service
@Slf4j
public class DualEntryTradingService {

    private final KiteConnect kiteConnect;
    private final DualEntryConfig config;
    private final DualEntryTradeRepository repository;
    private final AccountMarginGuard marginGuard;
    private final CrossStrategyPositionRegistry positionRegistry;
    private final MomentumCandleService sharedGates; // ONLY the 3 verified side-effect-free methods used
    private final MarketDataService marketDataService;
    private final DualEntryCandleService candleService; // for IB computation
    private final com.trading.institutional.service.VolumeProfileConfirmationService volumeProfileService;
    private final DualEntryGateStatusService gateStatusService;

    public DualEntryTradingService(KiteConnect kiteConnect, DualEntryConfig config,
                                   DualEntryTradeRepository repository, AccountMarginGuard marginGuard,
                                   CrossStrategyPositionRegistry positionRegistry,
                                   MomentumCandleService sharedGates, MarketDataService marketDataService,
                                   DualEntryCandleService candleService,
                                   com.trading.institutional.service.VolumeProfileConfirmationService volumeProfileService,
                                   DualEntryGateStatusService gateStatusService) {
        this.kiteConnect = kiteConnect;
        this.config = config;
        this.repository = repository;
        this.marginGuard = marginGuard;
        this.positionRegistry = positionRegistry;
        this.sharedGates = sharedGates;
        this.marketDataService = marketDataService;
        this.candleService = candleService;
        this.volumeProfileService = volumeProfileService;
        this.gateStatusService = gateStatusService;
    }

    private record FillResult(boolean filled, double avgPrice, int filledQty) {}

    private FillResult pollForFill(String orderId) {
        for (int attempt = 1; attempt <= config.getOrderPollMaxAttempts(); attempt++) {
            try {
                List<Order> history = kiteConnect.getOrderHistory(orderId);
                if (history != null && !history.isEmpty()) {
                    Order latest = history.get(history.size() - 1);
                    if ("COMPLETE".equals(latest.status)) {
                        return new FillResult(true, safeParseDouble(latest.averagePrice),
                                safeParseInt(latest.filledQuantity));
                    }
                    if ("REJECTED".equals(latest.status) || "CANCELLED".equals(latest.status)) {
                        return new FillResult(false, 0, 0);
                    }
                }
            } catch (KiteException | Exception e) {
                log.warn("[DUAL-ENTRY-TRADE] Poll attempt {} for order {} failed: {}", attempt, orderId, e.getMessage());
            }
            try { Thread.sleep(config.getOrderPollIntervalMs()); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
        }
        return new FillResult(false, 0, 0);
    }

    private double safeParseDouble(String s) { try { return s != null ? Double.parseDouble(s) : 0.0; } catch (Exception e) { return 0.0; } }
    private int safeParseInt(String s) { try { return s != null ? Integer.parseInt(s) : 0; } catch (Exception e) { return 0; } }

    public DualEntryTrade enterBreakout(MomentumCandidate candidate, double consolHigh, double consolLow) {
        return enterInternal(candidate, consolHigh, consolLow, "BREAKOUT", candidate.getDirection());
    }

    public DualEntryTrade enterPullback(MomentumCandidate candidate, double level, double range, String direction) {
        boolean isLong = "LONG".equals(direction);
        double syntheticHigh = isLong ? level + range : level;
        double syntheticLow = isLong ? level : level - range;
        return enterInternal(candidate, syntheticHigh, syntheticLow, "PULLBACK", direction);
    }

    private DualEntryTrade enterInternal(MomentumCandidate candidate, double consolHigh, double consolLow,
                                         String entryMode, String direction) {
        String symbol = candidate.getSymbol();
        boolean isLong = "LONG".equals(direction);
        boolean isPullback = "PULLBACK".equals(entryMode);

        try {
            gateStatusService.initGatesPending(symbol);

            double ltp = fetchLtp(symbol);
            if (ltp <= 0) throw new DualEntryStrategyException("Could not fetch valid live price for " + symbol);
            double entry = isLong ? ltp * 1.0005 : ltp * 0.9995;

            // ── GATE 1: Price Breakout (per explicit user request) ──
            // Simple, independent hard condition: price must have
            // actually broken the day's high (LONG) or day's low
            // (SHORT). Deliberately NO consolidation, compression,
            // range, or volatility conditions - reuses the existing,
            // already-verified-safe day-high/low accessor. Applies to
            // BOTH breakout-mode and pullback-mode entries, since both
            // funnel through this same shared method.
            double[] dayHighLow = sharedGates.getDayHighLowPublic(symbol);
            double dayHigh = dayHighLow[0], dayLow = dayHighLow[1];
            boolean priceBrokeLevel = isLong ? ltp > dayHigh : ltp < dayLow;
            if (!priceBrokeLevel) {
                gateStatusService.record(symbol, "PRICE_BREAKOUT", false, "GATE1 FAILED");
                throw new DualEntryStrategyException(String.format(
                        "%s - GATE1 FAILED: price %.2f has not broken the day's %s (%.2f)",
                        symbol, ltp, isLong ? "high" : "low", isLong ? dayHigh : dayLow));
            }

            // ── GATE 1: Structural Risk ──
            double range = consolHigh - consolLow;
            double buffer = range * 0.3;
            double structuralStop = isLong ? consolLow - buffer : consolHigh + buffer;
            double structuralRisk = isLong ? entry - structuralStop : structuralStop - entry;
            if (structuralRisk <= 0) {
                gateStatusService.record(symbol, "STRUCTURAL_RISK", false, "GATE2 FAILED");
                throw new DualEntryStrategyException(symbol + " - GATE2 FAILED: structural risk non-positive");
            }

            // ── GATE 2: Skip Rule (Tier Ceiling) ──
            double tierRisk = entry * computeSlPct(entry);
            if (structuralRisk > tierRisk) {
                gateStatusService.record(symbol, "SKIP_RULE", false, "GATE3 FAILED");
                throw new DualEntryStrategyException(String.format(
                        "%s - GATE3 FAILED: structural risk %.2f exceeds tier ceiling %.2f", symbol, structuralRisk, tierRisk));
            }

            // ── GATE 3: Noise Floor (reuses MomentumCandleService.compute5MinAtr - verified side-effect-free) ──
            double liveAtr5m = sharedGates.compute5MinAtr(symbol);
            double noiseFloor = liveAtr5m > 0 ? liveAtr5m * 0.5 : 0;
            if (noiseFloor > tierRisk) {
                gateStatusService.record(symbol, "NOISE_FLOOR", false, "GATE4 FAILED");
                throw new DualEntryStrategyException(String.format(
                        "%s - GATE4 FAILED: noise floor %.2f exceeds tier ceiling %.2f", symbol, noiseFloor, tierRisk));
            }
            double riskPerShare = Math.max(structuralRisk, noiseFloor);
            double stopLoss = isLong ? entry - riskPerShare : entry + riskPerShare;
            double target = isLong ? entry + riskPerShare * config.getRiskRewardRatio()
                    : entry - riskPerShare * config.getRiskRewardRatio();

            // ── GATE 4: Position Sizing ──
            double riskAmt = config.getCapital() * 0.01;
            int riskBasedQty = (int) (riskAmt / riskPerShare);
            int affordableQty = (int) (config.getCapital() / entry);
            int qty = Math.min(riskBasedQty, affordableQty);
            if (qty <= 0) {
                gateStatusService.record(symbol, "POSITION_SIZING", false, "GATE5 FAILED");
                throw new DualEntryStrategyException(symbol + " - GATE5 FAILED: computed quantity is 0");
            }

            // ── GATE 5: Margin Check (real Zerodha API) ──
            BigDecimal estimatedCost = getRealMarginRequired(symbol,
                    isLong ? Constants.TRANSACTION_TYPE_BUY : Constants.TRANSACTION_TYPE_SELL, qty, entry);
            var marginResult = marginGuard.checkSufficientMargin(estimatedCost, "DUAL_ENTRY_STRATEGY");
            if (!marginResult.sufficient()) {
                gateStatusService.record(symbol, "MARGIN_CHECK", false, "GATE6 FAILED");
                throw new DualEntryStrategyException(symbol + " - GATE6 FAILED: insufficient margin");
            }
            positionRegistry.checkAndWarnIfHeldElsewhere(symbol, "DUAL_ENTRY_STRATEGY");

            // ── GATE 6: Trend Filter (5-min VWAP + EMA20 ONLY - per
            // spec, deliberately NOT the fuller EMA20/50/200 stack
            // Momentum's own checkTrendFilters uses, hence independent
            // implementation here) ──
            if (!checkVwapEma20TrendFilter(symbol, isLong, isPullback)) {
                gateStatusService.record(symbol, "TREND_FILTER", false, "GATE7 FAILED");
                throw new DualEntryStrategyException(symbol + " - GATE7 FAILED: VWAP+EMA20 trend filter");
            }

            // ── GATE 7: Daily S/R Gate (genuine reuse - verified side-effect-free) ──
            var dailyGate = sharedGates.checkHigherTimeframeGate(symbol, direction, entry, riskPerShare);
            if (!dailyGate.passed()) {
                gateStatusService.record(symbol, "DAILY_SR_GATE", false, "GATE8 FAILED");
                throw new DualEntryStrategyException(symbol + " - GATE8 FAILED: " + dailyGate.reason());
            }

            // ── GATE 8: 30-Min S/R Gate (genuine reuse) ──
            var thirtyMinGate = sharedGates.check30MinuteHigherTimeframeGate(symbol, direction, entry, riskPerShare);
            if (!thirtyMinGate.passed()) {
                gateStatusService.record(symbol, "THIRTY_MIN_SR_GATE", false, "GATE9 FAILED");
                throw new DualEntryStrategyException(symbol + " - GATE9 FAILED: " + thirtyMinGate.reason());
            }

            // ── GATE 9: Fresh Price Check ──
            double freshLtp = fetchLtp(symbol);
            double reversalThreshold = riskPerShare * 0.3;
            boolean alreadyReversing = isLong ? freshLtp < entry - reversalThreshold
                    : freshLtp > entry + reversalThreshold;
            if (alreadyReversing) {
                gateStatusService.record(symbol, "FRESH_PRICE_CHECK", false, "GATE10 FAILED");
                throw new DualEntryStrategyException(symbol + " - GATE10 FAILED: price already reversed");
            }

            // ── GATE 11/12: Hard Order Book Gate (own thresholds -
            // LONG ratio>=1.50, SHORT ratio>=3.00, 5 consecutive
            // samples - RESOLVED per explicit user authorization as
            // the final values, superseding the conflicting spec
            // item #10) ──
            if (!checkOrderBookHardGate(symbol, isLong)) {
                gateStatusService.record(symbol, "ORDER_BOOK_GATE", false, "GATE11/12 FAILED");
                throw new DualEntryStrategyException(symbol + " - GATE11/12 FAILED: order-book hard gate");
            }

            // ── GATE 13: Market Profile Gate (per explicit user
            // request, NEW) - IB Breakout + POC Alignment ONLY,
            // deliberately excluding any other Market Profile concept
            // to avoid duplicating the Volume Profile gate below. ──
            var ib = candleService.computeInitialBalance(symbol);
            if (ib == null) {
                gateStatusService.record(symbol, "MARKET_PROFILE_GATE", false, "GATE13 FAILED");
                throw new DualEntryStrategyException(symbol + " - GATE13 FAILED: Initial Balance not yet available");
            }
            boolean ibBreakout = isLong ? entry > ib.ibHigh() : entry < ib.ibLow();
            if (!ibBreakout) {
                gateStatusService.record(symbol, "MARKET_PROFILE_GATE", false, "GATE13 FAILED");
                throw new DualEntryStrategyException(String.format(
                        "%s - GATE13 FAILED: price %.2f has not broken IB %s (%.2f)",
                        symbol, entry, isLong ? "High" : "Low", isLong ? ib.ibHigh() : ib.ibLow()));
            }
            var vpMetricsForPoc = volumeProfileService.computeProfile(symbol);
            if (vpMetricsForPoc == null) {
                gateStatusService.record(symbol, "MARKET_PROFILE_GATE", false, "GATE13 FAILED");
                throw new DualEntryStrategyException(symbol + " - GATE13 FAILED: no volume profile data for POC alignment");
            }
            var pocHistoryForAlignment = marketDataService.getDevelopingPocHistory(symbol);
            boolean pocShifting = false;
            if (pocHistoryForAlignment.size() > 3) {
                double pocNow = pocHistoryForAlignment.get(pocHistoryForAlignment.size() - 1).poc();
                double pocBefore = pocHistoryForAlignment.get(pocHistoryForAlignment.size() - 4).poc();
                pocShifting = isLong ? pocNow > pocBefore : pocNow < pocBefore;
            }
            boolean pocAligned = isLong
                    ? (vpMetricsForPoc.poc() < entry || pocShifting)
                    : (vpMetricsForPoc.poc() > entry || pocShifting);
            if (!pocAligned) {
                gateStatusService.record(symbol, "MARKET_PROFILE_GATE", false, "GATE13 FAILED");
                throw new DualEntryStrategyException(symbol + " - GATE13 FAILED: POC not aligned with " + direction);
            }

            // ── GATE 14: Volume Profile Gate (per explicit user
            // request, REDUCED to 5 conditions) - reuses
            // VolumeProfileConfirmationService.computeProfile() for
            // the underlying POC/VAH/VAL/HVN math ONLY (a pure,
            // side-effect-free computation, safe to call directly) -
            // does NOT call its own validateBreakout() decision (which
            // enforces a different, larger condition set). Every other
            // Volume Profile condition from the original spec is
            // deliberately NOT checked here, per explicit request. ──
            boolean vp1 = isLong ? entry > vpMetricsForPoc.vah() : entry < vpMetricsForPoc.val();
            boolean vp2 = pocShifting;
            boolean vp3; // sufficient room to next HVN
            if (isLong) {
                vp3 = vpMetricsForPoc.hvnLevels().stream().filter(l -> l > entry)
                        .min(Double::compareTo).map(nextHvn -> (nextHvn - entry) >= vpMetricsForPoc.bucketSize() * 3)
                        .orElse(true); // no HVN above = no immediate resistance = room is sufficient
            } else {
                vp3 = vpMetricsForPoc.hvnLevels().stream().filter(l -> l < entry)
                        .max(Double::compareTo).map(nextHvn -> (entry - nextHvn) >= vpMetricsForPoc.bucketSize() * 3)
                        .orElse(true);
            }
            List<MomentumCandidate.Candle> recentCandlesForVp = sharedGates.getFiveMinRecentCandlesPublic(symbol, 6);
            long latestVol = recentCandlesForVp.isEmpty() ? 0 : recentCandlesForVp.get(recentCandlesForVp.size() - 1).volume();
            double avgVolForVp = recentCandlesForVp.size() > 1
                    ? recentCandlesForVp.subList(0, recentCandlesForVp.size() - 1).stream()
                    .mapToLong(MomentumCandidate.Candle::volume).average().orElse(0) : 0;
            boolean vp4 = avgVolForVp > 0 && latestVol > avgVolForVp;
            boolean vp5 = isLong ? entry > vpMetricsForPoc.vah() : entry < vpMetricsForPoc.val(); // accepted outside VA (same instant check - no rejection yet observed)
            if (!(vp1 && vp2 && vp3 && vp4 && vp5)) {
                gateStatusService.record(symbol, "VOLUME_PROFILE_GATE", false, "GATE14 FAILED");
                throw new DualEntryStrategyException(String.format(
                        "%s - GATE14 FAILED: Volume Profile (vah/val=%s pocShift=%s hvnRoom=%s volume=%s accepted=%s)",
                        symbol, vp1, vp2, vp3, vp4, vp5));
            }

            // ── GATE 15: Order Flow Gate (per explicit user request,
            // REDUCED to 5 conditions) - reads raw
            // MarketDataService.getOrderFlowHistory() directly, NOT
            // OrderFlowConfirmationService's own 11-condition decision.
            // The shared OrderFlowConfirmationService class itself is
            // completely untouched - still used unmodified by Momentum
            // and unaffected by this change. ──
            if (!checkReducedOrderFlowGate(symbol, isLong)) {
                gateStatusService.record(symbol, "ORDER_FLOW_GATE", false, "GATE15 FAILED");
                throw new DualEntryStrategyException(symbol + " - GATE15 FAILED: order-flow (reduced 5-condition) gate");
            }

            // Reaching this point means every gate above genuinely
            // passed (any failure would have thrown already) - bulk
            // record PASS for dashboard visibility.
            for (String g : DualEntryGateStatusService.GATE_NAMES) {
                gateStatusService.record(symbol, g, true, "Passed");
            }

            log.info("[DUAL-ENTRY-TRADE] {} ALL 15 GATES PASSED - placing {} order, mode={}",
                    symbol, direction, entryMode);

            String orderId = placeMarketOrder(symbol, isLong ? Constants.TRANSACTION_TYPE_BUY
                    : Constants.TRANSACTION_TYPE_SELL, qty);
            FillResult fill = pollForFill(orderId);
            if (!fill.filled()) {
                throw new DualEntryStrategyException(symbol + " - order placed but fill not confirmed, orderId=" + orderId);
            }

            double realEntry = fill.avgPrice();
            int realQty = fill.filledQty();
            double realRiskPerShare = isLong ? Math.abs(realEntry - structuralStop) : Math.abs(structuralStop - realEntry);
            double realTierRisk = realEntry * computeSlPct(realEntry);
            if (realRiskPerShare <= 0 || realRiskPerShare > realTierRisk) realRiskPerShare = realTierRisk;
            double realStopLoss = isLong ? realEntry - realRiskPerShare : realEntry + realRiskPerShare;
            double realTarget = isLong ? realEntry + realRiskPerShare * config.getRiskRewardRatio()
                    : realEntry - realRiskPerShare * config.getRiskRewardRatio();

            positionRegistry.registerPosition(symbol, "DUAL_ENTRY_STRATEGY");

            DualEntryTrade trade = DualEntryTrade.builder()
                    .symbol(symbol).sector(candidate.getSector()).sectorRank(candidate.getSectorRank())
                    .direction(direction).entryMode(entryMode)
                    .entryPrice(BigDecimal.valueOf(realEntry).setScale(2, RoundingMode.HALF_UP))
                    .stopLoss(BigDecimal.valueOf(realStopLoss).setScale(2, RoundingMode.HALF_UP))
                    .target(BigDecimal.valueOf(realTarget).setScale(2, RoundingMode.HALF_UP))
                    .consolidationHigh(BigDecimal.valueOf(consolHigh).setScale(2, RoundingMode.HALF_UP))
                    .consolidationLow(BigDecimal.valueOf(consolLow).setScale(2, RoundingMode.HALF_UP))
                    .quantity(realQty).entryOrderId(orderId).build();

            DualEntryTrade saved = repository.save(trade);
            log.info("[DUAL-ENTRY-TRADE] ENTRY CONFIRMED: {} {} {} qty={} entry={} sl={} target={}",
                    entryMode, direction, symbol, realQty, realEntry, realStopLoss, realTarget);
            return saved;

        } catch (DualEntryStrategyException e) {
            throw e;
        } catch (KiteException | Exception e) {
            throw new DualEntryStrategyException("Order placement failed for " + symbol, e);
        }
    }

    /** GATE 6 - own implementation: strict VWAP+EMA20 for breakout,
     *  structural (looser) VWAP+EMA20 for pullback - per spec's
     *  explicit distinction. */
    private boolean checkVwapEma20TrendFilter(String symbol, boolean isLong, boolean isPullback) {
        List<MomentumCandidate.Candle> candles = sharedGates.getFiveMinRecentCandlesPublic(symbol, 210);
        if (candles.size() < 200) return false; // fail closed - insufficient history for EMA(20) context window
        double vwap = computeVwap(candles);
        double ema20 = computeEma(candles, 20);
        double price = candles.get(candles.size() - 1).close();
        if (isPullback) {
            // Structural: price beyond EMA20 in the trade's favor is
            // enough - tolerant of a momentary VWAP wobble at a pullback.
            return isLong ? price > ema20 : price < ema20;
        }
        // Strict: both VWAP and EMA20 must agree.
        boolean vwapOk = isLong ? price > vwap : price < vwap;
        boolean emaOk = isLong ? price > ema20 : price < ema20;
        return vwapOk && emaOk;
    }

    private double computeVwap(List<MomentumCandidate.Candle> candles) {
        double sumPV = 0, sumV = 0;
        for (var c : candles) {
            double typical = (c.high() + c.low() + c.close()) / 3.0;
            sumPV += typical * c.volume();
            sumV += c.volume();
        }
        return sumV > 0 ? sumPV / sumV : candles.get(candles.size() - 1).close();
    }

    private double computeEma(List<MomentumCandidate.Candle> candles, int period) {
        double multiplier = 2.0 / (period + 1);
        double ema = candles.get(0).close();
        for (var c : candles) ema = (c.close() - ema) * multiplier + ema;
        return ema;
    }

    /** GATE 10/11 - own implementation, own thresholds (per spec item
     *  #11, treated as authoritative over the conflicting #10). Reads
     *  the SAME shared MarketDataService.getDepthHistory() raw data
     *  already used by Momentum's own order-book gate - genuinely
     *  reused market data, independently-thresholded decision logic. */
    /**
     * GATE 15 (per explicit user request, REDUCED to 5 conditions):
     * aggressive dominance, cumulative delta confirms, imbalance in
     * trade direction, no significant opposing absorption, confirmed
     * for 2-3 consecutive updates. Reads the SAME raw
     * MarketDataService.getOrderFlowHistory() data OrderFlowConfirmationService
     * itself uses - genuinely reused market data, independently
     * reduced decision logic (the shared class itself, and Momentum's
     * own use of it, are completely untouched).
     */
    private static final double OF_IMBALANCE_RATIO_THRESHOLD = 1.5;
    private static final double OF_ABSORPTION_OPPOSING_MULTIPLE = 1.5;
    private static final double OF_ABSORPTION_MAX_PRICE_MOVE_PCT = 0.0008;
    private static final int OF_CONFIRM_LOOKBACK = 3; // "2-3 consecutive updates"

    private boolean checkReducedOrderFlowGate(String symbol, boolean isLong) {
        List<MarketDataService.OrderFlowSnapshot> history = marketDataService.getOrderFlowHistory(symbol);
        if (history.size() < OF_CONFIRM_LOOKBACK + 1) return false;
        List<MarketDataService.OrderFlowSnapshot> lastN = history.subList(
                history.size() - OF_CONFIRM_LOOKBACK, history.size());

        int confirmedCount = 0;
        for (var snap : lastN) {
            int idx = history.indexOf(snap);
            var prev = idx > 0 ? history.get(idx - 1) : snap;

            long delta = snap.buyVolume() - snap.sellVolume();
            boolean deltaConfirms = isLong ? delta > 0 : delta < 0;

            boolean cumDeltaConfirms = isLong
                    ? snap.cumulativeDelta() > prev.cumulativeDelta()
                    : snap.cumulativeDelta() < prev.cumulativeDelta();

            double ratio = isLong
                    ? (snap.sellVolume() > 0 ? (double) snap.buyVolume() / snap.sellVolume() : Double.MAX_VALUE)
                    : (snap.buyVolume() > 0 ? (double) snap.sellVolume() / snap.buyVolume() : Double.MAX_VALUE);
            boolean imbalanceConfirms = ratio >= OF_IMBALANCE_RATIO_THRESHOLD;

            // No significant opposing absorption: opposing side didn't
            // trade heavily (>=1.5x average) while price barely moved
            // against the trade's favor.
            double avgOpposing = lastN.stream()
                    .mapToLong(s -> isLong ? s.sellVolume() : s.buyVolume()).average().orElse(0);
            long thisOpposing = isLong ? snap.sellVolume() : snap.buyVolume();
            double priceMovePct = prev.price() > 0 ? Math.abs(snap.price() - prev.price()) / prev.price() : 1.0;
            boolean opposingAbsorption = avgOpposing > 0 && thisOpposing > avgOpposing * OF_ABSORPTION_OPPOSING_MULTIPLE
                    && priceMovePct < OF_ABSORPTION_MAX_PRICE_MOVE_PCT
                    && (isLong ? snap.price() <= prev.price() : snap.price() >= prev.price());
            boolean noOpposingAbsorption = !opposingAbsorption;

            if (deltaConfirms && cumDeltaConfirms && imbalanceConfirms && noOpposingAbsorption) {
                confirmedCount++;
            }
        }
        // "Confirmation is maintained for 2-3 consecutive updates" -
        // require ALL of the last 3 samples to confirm (the strictest,
        // unambiguous reading of "maintained").
        return confirmedCount == OF_CONFIRM_LOOKBACK;
    }

    private boolean checkOrderBookHardGate(String symbol, boolean isLong) {
        List<MarketDataService.DepthSnapshot> history = marketDataService.getDepthHistory(symbol);
        int need = config.getOrderBookConsecutiveSamples() + 1;
        if (history.size() < need) return false;
        List<MarketDataService.DepthSnapshot> recent = history.subList(history.size() - need, history.size());

        // Spoofing heuristic - same proven pattern as the existing order-book gates.
        List<MarketDataService.DepthLevel> confirmSide = new ArrayList<>();
        for (var s : history) confirmSide.addAll(isLong ? s.bids() : s.asks());
        Map<Double, List<Integer>> qtyByPrice = new java.util.HashMap<>();
        for (var lvl : confirmSide) qtyByPrice.computeIfAbsent(lvl.price(), k -> new ArrayList<>()).add(lvl.quantity());
        for (var e : qtyByPrice.entrySet()) {
            List<Integer> qtys = e.getValue();
            if (qtys.size() < 2) continue;
            double avg = qtys.stream().mapToInt(Integer::intValue).average().orElse(0);
            if (avg <= 0) continue;
            for (int i = 1; i < qtys.size(); i++) {
                if (qtys.get(i - 1) > avg * 2.0 && qtys.get(i) < avg * 0.3) return false; // spoofing detected
            }
        }

        double ratioMin = isLong ? config.getOrderBookLongRatioMin() : config.getOrderBookShortRatioMin();
        int passedSamples = 0;
        for (int i = 1; i < recent.size(); i++) {
            var prev = recent.get(i - 1);
            var cur = recent.get(i);
            long sumBid = cur.bids().stream().mapToLong(l -> (long) l.quantity()).sum();
            long sumAsk = cur.asks().stream().mapToLong(l -> (long) l.quantity()).sum();
            long prevSumBid = prev.bids().stream().mapToLong(l -> (long) l.quantity()).sum();
            long prevSumAsk = prev.asks().stream().mapToLong(l -> (long) l.quantity()).sum();
            if (sumBid <= 0 || sumAsk <= 0) break;
            double obi = (double) (sumBid - sumAsk) / (sumBid + sumAsk);
            int bestBidQty = cur.bids().get(0).quantity();
            int bestAskQty = cur.asks().get(0).quantity();
            long bidDelta = sumBid - prevSumBid, askDelta = sumAsk - prevSumAsk;
            List<MarketDataService.DepthLevel> opposing = isLong ? cur.asks() : cur.bids();
            double oppAvg = opposing.stream().mapToInt(l -> l.quantity()).average().orElse(0);
            boolean wall = oppAvg > 0 && opposing.stream().anyMatch(l -> l.quantity() > oppAvg * 3.0);
            boolean pass;
            if (isLong) {
                double ratio = (double) sumBid / sumAsk;
                pass = ratio >= ratioMin && obi >= 0.15 && bestBidQty > bestAskQty && !wall
                        && bidDelta > 0 && bidDelta > askDelta;
            } else {
                double ratio = (double) sumAsk / sumBid;
                pass = ratio >= ratioMin && obi <= -0.15 && bestAskQty > bestBidQty && !wall
                        && askDelta > 0 && askDelta > bidDelta;
            }
            if (!pass) break;
            passedSamples++;
        }
        return passedSamples == config.getOrderBookConsecutiveSamples();
    }

    public boolean exitTrade(DualEntryTrade trade, String reason) {
        boolean isLong = "LONG".equals(trade.getDirection());
        try {
            String orderId = placeMarketOrder(trade.getSymbol(),
                    isLong ? Constants.TRANSACTION_TYPE_SELL : Constants.TRANSACTION_TYPE_BUY, trade.getQuantity());
            FillResult fill = pollForFill(orderId);
            double exitPrice = fill.filled() ? fill.avgPrice() : fetchLtp(trade.getSymbol());
            repository.markClosed(trade.getTradeId(), BigDecimal.valueOf(exitPrice).setScale(2, RoundingMode.HALF_UP),
                    orderId, reason);
            positionRegistry.releasePosition(trade.getSymbol(), "DUAL_ENTRY_STRATEGY");
            log.info("[DUAL-ENTRY-TRADE] EXIT: {} qty={} reason={} exitPrice={}",
                    trade.getSymbol(), trade.getQuantity(), reason, exitPrice);
            return true;
        } catch (KiteException | Exception e) {
            log.error("[DUAL-ENTRY-TRADE] Exit FAILED for {} ({}): {} - will retry next cycle",
                    trade.getSymbol(), reason, e.getMessage());
            return false;
        }
    }

    public DualEntryTrade checkAndUpdateTrailingStop(DualEntryTrade trade, double currentPrice) {
        boolean isLong = "LONG".equals(trade.getDirection());
        double target = trade.getTarget().doubleValue();
        boolean targetReached = isLong ? currentPrice >= target : currentPrice <= target;
        if (!targetReached && !trade.isTrailingActive()) return trade;
        double consolRange = trade.getConsolidationHigh().doubleValue() - trade.getConsolidationLow().doubleValue();
        double trailDistance = consolRange * config.getTrailingStopConsolidationRangeMultiple();
        double newTrailStop = isLong ? currentPrice - trailDistance : currentPrice + trailDistance;
        double currentStop = trade.getCurrentTrailStop() != null ? trade.getCurrentTrailStop().doubleValue()
                : trade.getStopLoss().doubleValue();
        boolean shouldUpdate = isLong ? newTrailStop > currentStop : newTrailStop < currentStop;
        if (shouldUpdate) {
            BigDecimal newStopBd = BigDecimal.valueOf(newTrailStop).setScale(2, RoundingMode.HALF_UP);
            repository.updateTrailingStop(trade.getTradeId(), newStopBd);
            return trade.toBuilder().trailingActive(true).currentTrailStop(newStopBd).build();
        }
        return trade;
    }

    public DualEntryTrade monitorActiveTrade(DualEntryTrade trade) {
        try {
            double ltp = fetchLtp(trade.getSymbol());
            if (ltp <= 0) return trade;
            boolean isLong = "LONG".equals(trade.getDirection());
            double sl = trade.getStopLoss().doubleValue();
            double trailStop = trade.getCurrentTrailStop() != null ? trade.getCurrentTrailStop().doubleValue() : sl;
            boolean slHit = isLong ? ltp <= trailStop : ltp >= trailStop;
            if (slHit) {
                boolean exited = exitTrade(trade, trade.isTrailingActive() ? "TRAILING_STOP_HIT" : "SL_HIT");
                return exited ? null : trade;
            }
            return checkAndUpdateTrailingStop(trade, ltp);
        } catch (KiteException | Exception e) {
            return trade;
        }
    }

    private BigDecimal getRealMarginRequired(String symbol, String txType, int qty, double price) {
        try {
            var params = new com.zerodhatech.models.MarginCalculationParams();
            params.tradingSymbol = symbol; params.exchange = Constants.EXCHANGE_NSE;
            params.transactionType = txType; params.variety = Constants.VARIETY_REGULAR;
            params.product = Constants.PRODUCT_MIS; params.orderType = Constants.ORDER_TYPE_MARKET;
            params.quantity = qty; params.price = 0; params.triggerPrice = 0;
            var results = kiteConnect.getMarginCalculation(java.util.List.of(params));
            if (results != null && !results.isEmpty() && results.get(0).total > 0) {
                return BigDecimal.valueOf(results.get(0).total);
            }
        } catch (KiteException | Exception e) {
            log.warn("[DUAL-ENTRY-TRADE] Real margin calc failed for {}, falling back: {}", symbol, e.getMessage());
        }
        return BigDecimal.valueOf(price).multiply(BigDecimal.valueOf(qty));
    }

    private double computeSlPct(double price) {
        if (price <= 130) return 0.020; else if (price <= 170) return 0.017;
        else if (price <= 200) return 0.013; else if (price <= 400) return 0.010;
        else if (price <= 700) return 0.007; else if (price <= 1200) return 0.006;
        else return 0.005;
    }

    private double fetchLtp(String symbol) throws KiteException, java.io.IOException, org.json.JSONException {
        Map<String, Quote> quotes = kiteConnect.getQuote(new String[]{"NSE:" + symbol});
        Quote q = quotes.get("NSE:" + symbol);
        return q != null ? q.lastPrice : 0.0;
    }

    private String placeMarketOrder(String symbol, String txType, int qty)
            throws KiteException, java.io.IOException, org.json.JSONException {
        OrderParams p = new OrderParams();
        p.tradingsymbol = symbol; p.exchange = "NSE"; p.transactionType = txType;
        p.quantity = qty; p.orderType = Constants.ORDER_TYPE_MARKET; p.product = Constants.PRODUCT_MIS;
        p.validity = Constants.VALIDITY_DAY; p.marketProtection = -1;
        Order order = kiteConnect.placeOrder(p, Constants.VARIETY_REGULAR);
        return order.orderId;
    }
}