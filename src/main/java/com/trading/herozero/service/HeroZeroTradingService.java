package com.trading.herozero.service;

import com.trading.herozero.config.HeroZeroConfig;
import com.trading.herozero.domain.HeroZeroTrade;
import com.trading.herozero.exception.HeroZeroException;
import com.trading.herozero.repository.HeroZeroTradeRepository;
import com.trading.herozero.util.HeroZeroHolidayChecker;
import com.trading.herozero.util.MonthlyExpiryCalculator;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Instrument;
import com.zerodhatech.models.Order;
import com.zerodhatech.models.OrderParams;
import com.zerodhatech.models.Quote;
import com.zerodhatech.kiteconnect.utils.Constants;
import org.json.JSONException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Date;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HeroZeroTradingService - the actual business logic for the Hero or
 * Zero Monthly Expiry strategy.
 *
 * INDEPENDENCE: depends ONLY on the raw KiteConnect bean (the same
 * broker-connectivity singleton every strategy in this app already
 * shares - this is infrastructure, not business logic) plus this
 * module's own config/repository/util classes. Zero imports from
 * com.trading.ai, com.trading.strategy.news, or com.trading.swing.
 *
 * Index -> underlying exchange:segment mapping used for instrument
 * lookups: NIFTY/BANKNIFTY/FINNIFTY/MIDCPNIFTY options trade on NFO;
 * SENSEX options trade on BFO (BSE F&O segment).
 */
@Service
@Slf4j
public class HeroZeroTradingService {

    private static final String STRATEGY_NAME = "HERO_OR_ZERO_MONTHLY_EXPIRY";
    private static final Map<String, String> INDEX_SEGMENT = Map.of(
            "NIFTY", "NFO-OPT", "BANKNIFTY", "NFO-OPT", "FINNIFTY", "NFO-OPT",
            "MIDCPNIFTY", "NFO-OPT", "SENSEX", "BFO-OPT"
    );
    private static final Map<String, String> INDEX_EXCHANGE = Map.of(
            "NIFTY", "NFO", "BANKNIFTY", "NFO", "FINNIFTY", "NFO",
            "MIDCPNIFTY", "NFO", "SENSEX", "BFO"
    );

    private final HeroZeroConfig config;
    private final HeroZeroTradeRepository repo;
    private final MonthlyExpiryCalculator expiryCalculator;
    private final KiteConnect kiteConnect;
    private final com.trading.shared.risk.AccountMarginGuard marginGuard;
    private final com.trading.shared.risk.CrossStrategyPositionRegistry positionRegistry;

    // Duplicate-protection: per-index lock, cleared daily. Prevents a
    // rapid double-invocation (e.g. scheduler race, manual trigger
    // overlap) from placing two entries for the same index+expiry.
    private final Map<String, Long> entryLocks = new ConcurrentHashMap<>();
    private static final long ENTRY_LOCK_EXPIRY_MS = 60_000;

    public HeroZeroTradingService(HeroZeroConfig config, HeroZeroTradeRepository repo,
                                  MonthlyExpiryCalculator expiryCalculator, KiteConnect kiteConnect,
                                  com.trading.shared.risk.AccountMarginGuard marginGuard,
                                  com.trading.shared.risk.CrossStrategyPositionRegistry positionRegistry) {
        this.config = config;
        this.repo = repo;
        this.expiryCalculator = expiryCalculator;
        this.kiteConnect = kiteConnect;
        this.marginGuard = marginGuard;
        this.positionRegistry = positionRegistry;
    }

    // =======================================================================
    // ENTRY - the exact 10-step sequence from the spec's "Trade Execution"
    // =======================================================================

    public void attemptEntry(String index) {
        String lockKey = index.toUpperCase();
        long now = System.currentTimeMillis();
        Long existingLock = entryLocks.get(lockKey);
        if (existingLock != null && (now - existingLock) < ENTRY_LOCK_EXPIRY_MS) {
            log.warn("[HERO-ZERO] {} entry already being processed - skipping duplicate attempt", index);
            return;
        }
        entryLocks.put(lockKey, now);
        try {
            doAttemptEntry(index);
        } finally {
            entryLocks.remove(lockKey);
        }
    }

    private void doAttemptEntry(String index) {
        LocalDate today = LocalDate.now();

        // STEP 1: Validate Monthly Expiry
        MonthlyExpiryCalculator.ExpiryResult expiry = expiryCalculator.calculate(index, today);
        if (!expiry.actualExpiry().isEqual(today)) {
            log.info("[HERO-ZERO] {} - today is NOT monthly expiry (actual expiry: {}) - skip, no order",
                    index, expiry.actualExpiry());
            return;
        }
        if (expiry.wasShifted()) {
            log.warn("[HERO-ZERO] HOLIDAY EXPIRY ALERT - {} : Original Expiry={} Holiday={} " +
                            "Shifted Expiry={} - Today IS Monthly Expiry",
                    index, expiry.naturalExpiry(), expiry.holidayDate(), expiry.actualExpiry());
        } else {
            log.info("[HERO-ZERO] {} - Monthly Expiry confirmed for today ({})", index, today);
        }

        // Duplicate-protection: never re-enter the same index+expiry twice
        // (covers restart-during-entry-window and any re-trigger).
        if (repo.existsTradeForExpiry(index, expiry.actualExpiry())) {
            log.info("[HERO-ZERO] {} - trade already exists for expiry {} - skipping duplicate entry",
                    index, expiry.actualExpiry());
            return;
        }

        // STEP 2: Validate Market Open (holiday/weekend)
        if (HeroZeroHolidayChecker.isMarketClosedToday()) {
            log.info("[HERO-ZERO] {} - market closed today (weekend/holiday) - skip", index);
            saveSkippedTrade(index, expiry.actualExpiry(), "SKIP_MARKET_CLOSED");
            return;
        }
        // STEP 3: Validate Holiday Rules - already applied above via expiryCalculator

        try {
            // STEP 4: Fetch Option Chain
            List<Instrument> chain = fetchOptionChain(index, expiry.actualExpiry());
            if (chain.isEmpty()) {
                log.warn("[HERO-ZERO] {} - option chain empty for expiry {} - skip", index, expiry.actualExpiry());
                saveSkippedTrade(index, expiry.actualExpiry(), "SKIP_MARKET_DATA_UNAVAILABLE");
                return;
            }

            // STEP 5 + 6: Identify Correct CE and PE (nearest to target premium)
            BigDecimal targetPremium = config.getTargetPremium().get(index.toUpperCase());
            StrikeSelection ceSel = selectStrike(chain, "CE", targetPremium);
            StrikeSelection peSel = selectStrike(chain, "PE", targetPremium);

            if (ceSel == null || peSel == null) {
                log.warn("[HERO-ZERO] {} - suitable CE/PE strike not found near target premium {} - skip",
                        index, targetPremium);
                saveSkippedTrade(index, expiry.actualExpiry(), "SKIP_NO_LIQUID_STRIKE");
                return;
            }

            // STEP 7: Validate Liquidity
            if (ceSel.quote.volumeTradedToday < config.getMinLiquidityVolume()
                    || peSel.quote.volumeTradedToday < config.getMinLiquidityVolume()) {
                log.warn("[HERO-ZERO] {} - CE/PE volume below liquidity floor ({}) - skip",
                        index, config.getMinLiquidityVolume());
                saveSkippedTrade(index, expiry.actualExpiry(), "SKIP_ILLIQUID_CONTRACTS");
                return;
            }

            String tradeId = UUID.randomUUID().toString();
            Instant now = Instant.now();
            int qty = ceSel.instrument.getLot_size() * config.getQuantityLots();

            // STEP 8: Place CE Buy Order
            String ceOrderId;
            try {
                ceOrderId = placeMarketBuy(ceSel.instrument);
            } catch (KiteException | Exception e) {
                log.error("[HERO-ZERO] {} - CE buy order placement FAILED: {}", index, e.getMessage());
                saveSkippedTrade(index, expiry.actualExpiry(), "SKIP_ORDER_PLACEMENT_FAILED");
                return;
            }

            // STEP 9: Place PE Buy Order
            String peOrderId;
            try {
                peOrderId = placeMarketBuy(peSel.instrument);
            } catch (KiteException | Exception e) {
                // CRITICAL: CE already filled/pending at the broker, PE failed.
                // This is a genuine partial-execution state - log at highest
                // severity for manual review, per spec's "Handle gracefully:
                // Partial executions" requirement. Do NOT silently retry
                // (could create a duplicate CE leg) and do NOT silently
                // discard (a real CE position now exists, unhedged).
                log.error("[HERO-ZERO] *** CRITICAL - PARTIAL EXECUTION *** {} CE order {} was " +
                                "placed successfully but PE order FAILED: {}. A single, unhedged CE leg " +
                                "may now be open at the broker - manual review required immediately.",
                        index, ceOrderId, e.getMessage());
                HeroZeroTrade partial = HeroZeroTrade.builder()
                        .tradeId(tradeId).strategyName(STRATEGY_NAME).index(index.toUpperCase())
                        .monthlyExpiryDate(expiry.actualExpiry())
                        .ceTradingSymbol(ceSel.instrument.getTradingsymbol())
                        .ceStrike(new BigDecimal(ceSel.instrument.getStrike()))
                        .ceBuyOrderId(ceOrderId)
                        .quantity(qty)
                        .tradeStatus("ENTRY_FAILED")
                        .exitReason("PARTIAL_EXECUTION_PE_FAILED")
                        .createdAt(now).updatedAt(now)
                        .build();
                repo.save(partial);
                return;
            }

            // Poll both fills before persisting the real trade record -
            // never save a trade until we know the actual fill prices.
            FillResult ceFill = pollForFill(ceOrderId);
            FillResult peFill = pollForFill(peOrderId);

            BigDecimal cePremium = ceFill != null ? ceFill.avgPrice : null;
            BigDecimal pePremium = peFill != null ? peFill.avgPrice : null;
            BigDecimal totalPremium = (cePremium != null && pePremium != null)
                    ? cePremium.add(pePremium) : null;

            // STEP 10: Save Trade Details
            HeroZeroTrade trade = HeroZeroTrade.builder()
                    .tradeId(tradeId)
                    .strategyName(STRATEGY_NAME)
                    .index(index.toUpperCase())
                    .monthlyExpiryDate(expiry.actualExpiry())
                    .ceTradingSymbol(ceSel.instrument.getTradingsymbol())
                    .peTradingSymbol(peSel.instrument.getTradingsymbol())
                    .ceStrike(new BigDecimal(ceSel.instrument.getStrike()))
                    .peStrike(new BigDecimal(peSel.instrument.getStrike()))
                    .cePremium(cePremium)
                    .pePremium(pePremium)
                    .totalPremium(totalPremium)
                    .quantity(qty)
                    .entryTime(LocalTime.now())
                    .ceBuyOrderId(ceOrderId)
                    .peBuyOrderId(peOrderId)
                    .tradeStatus(ceFill != null && peFill != null ? "ACTIVE" : "ENTRY_PENDING")
                    .exitStatus("PENDING")
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            repo.save(trade);

            log.info("[HERO-ZERO] {} ENTRY COMPLETE - CE {} @ {} + PE {} @ {} = total premium {} " +
                            "| qty={} | tradeId={}", index, ceSel.instrument.getTradingsymbol(), cePremium,
                    peSel.instrument.getTradingsymbol(), pePremium, totalPremium, qty, tradeId);

        } catch (Exception e) {
            log.error("[HERO-ZERO] {} - unexpected error during entry sequence: {}", index, e.getMessage(), e);
            saveSkippedTrade(index, expiry.actualExpiry(), "SKIP_UNEXPECTED_ERROR: " + e.getMessage());
        }
    }

    private void saveSkippedTrade(String index, LocalDate expiryDate, String reason) {
        Instant now = Instant.now();
        repo.save(HeroZeroTrade.builder()
                .tradeId(UUID.randomUUID().toString())
                .strategyName(STRATEGY_NAME)
                .index(index.toUpperCase())
                .monthlyExpiryDate(expiryDate)
                .tradeStatus("SKIPPED")
                .exitReason(reason)
                .createdAt(now).updatedAt(now)
                .build());
    }

    // =======================================================================
    // EXIT - mandatory 3:10 PM exit, per spec: "Regardless of Profit,
    // Loss, Target, Market Direction, Volatility"
    // =======================================================================

    public void forceExitAll() {
        List<HeroZeroTrade> active = repo.findActive();
        for (HeroZeroTrade trade : active) {
            try {
                exitTrade(trade);
            } catch (Exception e) {
                log.error("[HERO-ZERO] Exit failed for trade {} ({}) - continuing with remaining " +
                        "trades: {}", trade.getTradeId(), trade.getIndex(), e.getMessage(), e);
            }
        }
    }

    private void exitTrade(HeroZeroTrade trade) {
        if (!"ACTIVE".equals(trade.getTradeStatus()) && !"ENTRY_PENDING".equals(trade.getTradeStatus())) {
            return; // already exited or never properly entered
        }

        String ceSellOrderId = null, peSellOrderId = null;
        BigDecimal ceExitPrice = null, peExitPrice = null;

        try {
            ceSellOrderId = placeMarketSell(trade.getCeTradingSymbol(), trade.getQuantity(),
                    indexExchange(trade.getIndex()));
            FillResult f = pollForFill(ceSellOrderId);
            if (f != null) ceExitPrice = f.avgPrice;
        } catch (KiteException | Exception e) {
            log.error("[HERO-ZERO] CE exit FAILED for trade {}: {}", trade.getTradeId(), e.getMessage());
        }

        try {
            peSellOrderId = placeMarketSell(trade.getPeTradingSymbol(), trade.getQuantity(),
                    indexExchange(trade.getIndex()));
            FillResult f = pollForFill(peSellOrderId);
            if (f != null) peExitPrice = f.avgPrice;
        } catch (KiteException | Exception e) {
            log.error("[HERO-ZERO] PE exit FAILED for trade {}: {}", trade.getTradeId(), e.getMessage());
        }

        BigDecimal pnl = null;
        if (ceExitPrice != null && peExitPrice != null && trade.getTotalPremium() != null) {
            BigDecimal exitTotal = ceExitPrice.add(peExitPrice);
            pnl = exitTotal.subtract(trade.getTotalPremium())
                    .multiply(BigDecimal.valueOf(trade.getQuantity()));
        }

        if (ceExitPrice != null && peExitPrice != null) {
            repo.recordExit(trade.getTradeId(), ceExitPrice, peExitPrice, pnl,
                    ceSellOrderId, peSellOrderId, LocalTime.now(), "MANDATORY_3_10_EXIT");
            log.info("[HERO-ZERO] {} EXIT COMPLETE - tradeId={} P&L={}", trade.getIndex(),
                    trade.getTradeId(), pnl);
        } else {
            repo.updateStatus(trade.getTradeId(), "EXIT_FAILED", "FAILED",
                    "MANDATORY_3_10_EXIT_PARTIAL_OR_FAILED - manual review required");
            log.error("[HERO-ZERO] *** CRITICAL *** {} exit incomplete for trade {} - " +
                            "manual review required immediately (position may still be open at broker)",
                    trade.getIndex(), trade.getTradeId());
        }
    }

    // =======================================================================
    // STRIKE SELECTION
    // =======================================================================

    private record StrikeSelection(Instrument instrument, Quote quote) {}

    /**
     * Finds the strike whose live premium is nearest to targetPremium.
     * Tie-break per spec: "Lower strike for CE, Higher strike for PE."
     */
    private StrikeSelection selectStrike(List<Instrument> chain, String optionType, BigDecimal targetPremium) {
        List<Instrument> matching = chain.stream()
                .filter(i -> optionType.equals(i.getInstrument_type()))
                .toList();
        if (matching.isEmpty()) return null;

        Map<String, Quote> quotes = fetchQuotes(matching);

        StrikeSelection best = null;
        BigDecimal bestDiff = null;
        for (Instrument inst : matching) {
            Quote q = quotes.get(inst.getExchange() + ":" + inst.getTradingsymbol());
            if (q == null || q.lastPrice <= 0) continue;
            BigDecimal premium = BigDecimal.valueOf(q.lastPrice);
            BigDecimal diff = premium.subtract(targetPremium).abs();

            if (best == null || diff.compareTo(bestDiff) < 0) {
                best = new StrikeSelection(inst, q);
                bestDiff = diff;
            } else if (diff.compareTo(bestDiff) == 0) {
                // Tie-break: lower strike for CE, higher strike for PE
                boolean preferNew = "CE".equals(optionType)
                        ? new BigDecimal(inst.getStrike()).compareTo(new BigDecimal(best.instrument.getStrike())) < 0
                        : new BigDecimal(inst.getStrike()).compareTo(new BigDecimal(best.instrument.getStrike())) > 0;
                if (preferNew) {
                    best = new StrikeSelection(inst, q);
                    bestDiff = diff;
                }
            }
        }
        return best;
    }

    // =======================================================================
    // KITE CONNECT INTEGRATION - instrument/quote/order helpers
    // =======================================================================

    private List<Instrument> fetchOptionChain(String index, LocalDate expiry) {
        try {
            String exchange = INDEX_EXCHANGE.get(index.toUpperCase());
            List<Instrument> all = kiteConnect.getInstruments(exchange);
            String segment = INDEX_SEGMENT.get(index.toUpperCase());
            return all.stream()
                    .filter(i -> segment.equals(i.getSegment()))
                    .filter(i -> i.getName() != null && i.getName().equalsIgnoreCase(index))
                    .filter(i -> i.getExpiry() != null && toLocalDate(i.getExpiry()).isEqual(expiry))
                    .filter(i -> "CE".equals(i.getInstrument_type()) || "PE".equals(i.getInstrument_type()))
                    .toList();
        } catch (KiteException | IOException | JSONException e) {
            log.error("[HERO-ZERO] Failed to fetch option chain for {}: {}", index, e.getMessage());
            return List.of();
        }
    }

    private LocalDate toLocalDate(Date d) {
        return d.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
    }

    private Map<String, Quote> fetchQuotes(List<Instrument> instruments) {
        try {
            String[] keys = instruments.stream()
                    .map(i -> i.getExchange() + ":" + i.getTradingsymbol())
                    .toArray(String[]::new);
            return kiteConnect.getQuote(keys);
        } catch (KiteException | IOException | JSONException e) {
            log.warn("[HERO-ZERO] getQuote failed for option chain: {}", e.getMessage());
            return Map.of();
        }
    }

    private String indexExchange(String index) {
        return INDEX_EXCHANGE.get(index.toUpperCase());
    }

    private String placeMarketBuy(Instrument inst) throws KiteException, IOException, JSONException {
        // FIX (found during a full platform production-readiness review):
        // wired in the same two cross-strategy safeguards already applied
        // to Swing and the shared AI/News order client. Zero changes to
        // the actual order construction below (marketProtection, product
        // type, quantity calculation all untouched).
        int qty = inst.getLot_size() * config.getQuantityLots();
        try {
            String quoteKey = inst.getExchange() + ":" + inst.getTradingsymbol();
            Quote q = kiteConnect.getQuote(new String[]{quoteKey}).get(quoteKey);
            if (q != null && q.lastPrice > 0) {
                BigDecimal estimatedCost = BigDecimal.valueOf(q.lastPrice).multiply(BigDecimal.valueOf(qty));
                var marginResult = marginGuard.checkSufficientMargin(estimatedCost, "HERO_ZERO");
                if (!marginResult.sufficient()) {
                    throw new HeroZeroException(
                            "Insufficient account margin for " + inst.getTradingsymbol() +
                                    " (need ~Rs." + estimatedCost + ", available Rs." +
                                    marginResult.availableMargin() + ")");
                }
            }
        } catch (HeroZeroException e) {
            throw e; // re-throw the deliberate margin-insufficiency signal
        } catch (KiteException | Exception e) {
            log.debug("[HERO-ZERO] Pre-order margin/quote check skipped (non-fatal): {}", e.getMessage());
        }
        positionRegistry.checkAndWarnIfHeldElsewhere(inst.getTradingsymbol(), "HERO_ZERO");

        OrderParams p = new OrderParams();
        p.tradingsymbol = inst.getTradingsymbol();
        p.exchange = inst.getExchange();
        p.transactionType = Constants.TRANSACTION_TYPE_BUY;
        p.quantity = qty;
        p.orderType = Constants.ORDER_TYPE_MARKET;
        p.product = Constants.PRODUCT_MIS;
        p.validity = Constants.VALIDITY_DAY;
        p.marketProtection = -1; // SEBI-required for market orders - see session-wide fix
        Order order = kiteConnect.placeOrder(p, Constants.VARIETY_REGULAR);
        positionRegistry.registerPosition(inst.getTradingsymbol(), "HERO_ZERO");
        return order.orderId;
    }

    private String placeMarketSell(String tradingSymbol, int qty, String exchange)
            throws KiteException, IOException, JSONException {
        OrderParams p = new OrderParams();
        p.tradingsymbol = tradingSymbol;
        p.exchange = exchange;
        p.transactionType = Constants.TRANSACTION_TYPE_SELL;
        p.quantity = qty;
        p.orderType = Constants.ORDER_TYPE_MARKET;
        p.product = Constants.PRODUCT_MIS;
        p.validity = Constants.VALIDITY_DAY;
        p.marketProtection = -1;
        Order order = kiteConnect.placeOrder(p, Constants.VARIETY_REGULAR);
        positionRegistry.releasePosition(tradingSymbol, "HERO_ZERO");
        return order.orderId;
    }

    private record FillResult(BigDecimal avgPrice, int filledQty) {}

    private FillResult pollForFill(String orderId) {
        for (int attempt = 0; attempt < config.getOrderPollMaxAttempts(); attempt++) {
            try {
                List<Order> history = kiteConnect.getOrderHistory(orderId);
                if (history != null && !history.isEmpty()) {
                    Order latest = history.get(history.size() - 1);
                    if ("COMPLETE".equals(latest.status)) {
                        return new FillResult(new BigDecimal(latest.averagePrice),
                                Integer.parseInt(latest.filledQuantity));
                    }
                    if ("REJECTED".equals(latest.status) || "CANCELLED".equals(latest.status)) {
                        log.error("[HERO-ZERO] Order {} ended in status {}: {}",
                                orderId, latest.status, latest.statusMessage);
                        return null;
                    }
                }
            } catch (KiteException | Exception e) {
                log.warn("[HERO-ZERO] Poll attempt {} for order {} failed (will retry): {}",
                        attempt, orderId, e.getMessage());
            }
            try { Thread.sleep(config.getOrderPollIntervalMs()); } catch (InterruptedException ignored) {}
        }
        return null;
    }
}