package com.trading.swing.service;

import com.trading.marketdata.service.InstrumentCacheService;
import com.trading.marketdata.service.MarketDataService;
import com.trading.swing.config.ManualSwingConfig;
import com.trading.swing.domain.ManualSwingTrade;
import com.trading.swing.dto.BuySwingRequest;
import com.trading.swing.dto.InstrumentSearchResult;
import com.trading.swing.dto.SwingTradeResponse;
import com.trading.swing.repository.ManualSwingTradeRepository;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.models.Instrument;
import com.zerodhatech.models.Order;
import com.zerodhatech.models.Quote;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * ManualSwingTradingService - the actual business logic for the Manual
 * Swing Trading module. Genuinely independent: depends only on the
 * generic, shared-infrastructure services every strategy in this app
 * already shares (InstrumentCacheService, MarketDataService, the
 * authenticated KiteConnect bean), plus its own dedicated repository,
 * order client, and configuration - zero dependency on any AI/News class.
 *
 * EXIT-RULE INTERPRETATION (documented, not assumed silently): the spec's
 * exit-rules section was genuinely ambiguous on first read. Re-read
 * carefully per explicit instruction rather than asking again, with one
 * supporting structural clue: BTST, the existing strategy this module
 * most resembles, also exits at exactly 9:20 AM, a 5-minute gap from
 * market open at 9:15. That gap leaves almost no realistic window for an
 * independent 5%-profit check to fire before 9:20 except on a genuine
 * gap-up - meaning the design is a single-extra-day hold: buy day 1,
 * resolve day 2, either via an early gap-up clearing the profit
 * threshold, or an unconditional 9:20 AM force-exit if not.
 *   - Buy day: zero monitoring, completely skipped.
 *   - Next trading day: monitor continuously through market hours. Sell
 *     immediately if profit reaches the configured quick-profit threshold
 *     (default 5%) OR the user's own target, whichever comes first. At
 *     9:20 AM, force-exit unconditionally if still open, regardless of P&L.
 */
@Service
@Slf4j
public class ManualSwingTradingService {

    private final ManualSwingTradeRepository repo;
    private final ManualSwingOrderClient orderClient;
    private final InstrumentCacheService instrumentCache;
    private final MarketDataService marketDataService;
    private final KiteConnect kiteConnect;
    private final ManualSwingConfig config;

    private LocalTime forceExitTime;

    /**
     * BUY-side duplicate-click protection. The spec explicitly requires
     * "BUY orders are stored only once" - without this, a double-click,
     * a network retry, or two browser tabs could place two real buy
     * orders for the same symbol in the same moment. Per-symbol, with a
     * short expiry (config: buyLockExpiryMs) so a genuinely new buy
     * later isn't ever permanently blocked by a stale lock from a
     * crashed earlier attempt.
     */
    private final Map<String, Long> buyLocks = new ConcurrentHashMap<>();

    /** Tracks which trades we've already logged "monitoring started" for,
     *  so that log line fires exactly once per trade, not every tick. */
    private final Set<String> monitoringStartedLogged = ConcurrentHashMap.newKeySet();

    public ManualSwingTradingService(ManualSwingTradeRepository repo,
                                     ManualSwingOrderClient orderClient,
                                     InstrumentCacheService instrumentCache,
                                     MarketDataService marketDataService,
                                     KiteConnect kiteConnect,
                                     ManualSwingConfig config) {
        this.repo = repo;
        this.orderClient = orderClient;
        this.instrumentCache = instrumentCache;
        this.marketDataService = marketDataService;
        this.kiteConnect = kiteConnect;
        this.config = config;
    }

    @PostConstruct
    public void init() {
        this.forceExitTime = LocalTime.parse(config.getForceExitTime());
        List<ManualSwingTrade> active = repo.findActive();
        log.info("[SWING] Application restart recovery: module initialised, found {} ACTIVE " +
                        "trade(s) in the database - monitoring will resume for all of them automatically, " +
                        "no manual recovery needed. quickProfitTarget={}% forceExitTime={}",
                active.size(), config.getProfitTargetPct(), forceExitTime);
        // Restart/crash recovery - reconcile anything left in ORDER_PLACED
        // from before a restart. Never blindly resets state; checks the
        // REAL broker order status first.
        reconcileStuckSellOrders();
    }

    // ===================================================================
    // INSTRUMENT SEARCH - including live % change via KiteConnect's own
    // quote API (OHLC.close = previous close), independently of any
    // AI/News service.
    // ===================================================================

    public List<InstrumentSearchResult> searchInstruments(String query) {
        if (query == null || query.isBlank()) return List.of();
        String q = query.trim().toUpperCase();

        List<Instrument> matches = instrumentCache.getEquityInstruments().values().stream()
                .filter(i -> i.getTradingsymbol().toUpperCase().contains(q)
                        || (i.getName() != null && i.getName().toUpperCase().contains(q)))
                .limit(50) // keep the picker responsive
                .collect(Collectors.toList());

        if (matches.isEmpty()) return List.of();

        Map<String, Quote> quotes = fetchQuotes(matches);

        return matches.stream()
                .map(i -> {
                    String key = i.getExchange() + ":" + i.getTradingsymbol();
                    Quote quote = quotes.get(key);
                    BigDecimal ltp = null, changePct = null;
                    if (quote != null) {
                        ltp = BigDecimal.valueOf(quote.lastPrice);
                        if (quote.ohlc != null && quote.ohlc.close > 0) {
                            BigDecimal prevClose = BigDecimal.valueOf(quote.ohlc.close);
                            changePct = ltp.subtract(prevClose)
                                    .divide(prevClose, 6, RoundingMode.HALF_UP)
                                    .multiply(BigDecimal.valueOf(100));
                        }
                    }
                    return new InstrumentSearchResult(
                            i.getTradingsymbol(), i.getName(), i.getExchange(), ltp, changePct);
                })
                .collect(Collectors.toList());
    }

    private Map<String, Quote> fetchQuotes(List<Instrument> instruments) {
        try {
            String[] keys = instruments.stream()
                    .map(i -> i.getExchange() + ":" + i.getTradingsymbol())
                    .toArray(String[]::new);
            return kiteConnect.getQuote(keys);
        } catch (com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException ex) {
            log.warn("[SWING] getQuote KiteException for instrument search: {}", ex.message);
            return Map.of();
        } catch (java.io.IOException ex) {
            log.warn("[SWING] getQuote IOException for instrument search: {}", ex.getMessage());
            return Map.of();
        }
    }

    /**
     * FIX (found during a "does this depend on AI/News's scope" check):
     * the shared MarketDataService.getLastPricesSimple() cache only
     * contains symbols AI/News actually subscribe to via the WebSocket
     * feed - a few hundred to a couple thousand symbols, not the full
     * ~9,900-instrument universe this module lets the user buy from.
     * Without this fallback, a swing trade on any symbol outside that
     * subscription scope would never get a live price, meaning NONE of
     * its exit conditions - including the unconditional 9:20 AM force-
     * exit - would ever fire. This module's correctness must not depend
     * on what AI/News happen to be watching.
     *
     * Tries the fast, free, already-cached WebSocket price first; only
     * falls back to a direct (rate-limited, slower) REST quote call when
     * genuinely necessary.
     */
    private BigDecimal resolveLivePrice(String symbol, String exchange) {
        BigDecimal cached = marketDataService.getLastPricesSimple().get(symbol);
        if (cached != null) return cached;

        try {
            Quote quote = kiteConnect.getQuote(new String[]{exchange + ":" + symbol})
                    .get(exchange + ":" + symbol);
            return quote != null ? BigDecimal.valueOf(quote.lastPrice) : null;
        } catch (com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException ex) {
            log.warn("[SWING] Direct quote KiteException for {}: {}", symbol, ex.message);
            return null;
        } catch (java.io.IOException ex) {
            log.warn("[SWING] Direct quote IOException for {}: {}", symbol, ex.getMessage());
            return null;
        }
    }

    // ===================================================================
    // BUY FLOW
    // ===================================================================

    public SwingTradeResponse placeBuy(BuySwingRequest req) {
        return placeBuyInternal(req, ManualSwingTrade.TradeSource.MANUAL, null, null);
    }

    /**
     * Used only by AutoSwingScheduler for an auto-selected stock. Takes
     * the company name and exchange directly from the already-resolved
     * StockCandidate, rather than looking the symbol up in
     * instrumentCache.getEquityInstruments() - that cache is NSE-only
     * (confirmed by reading its source), but the auto-selection engine
     * deliberately scans the full NSE+BSE universe, so an auto-picked
     * stock may not exist in that narrower cache at all.
     */
    public SwingTradeResponse placeAutoBuy(String symbol, String exchange, String companyName,
                                           int quantity, BigDecimal targetPct) {
        BuySwingRequest req = new BuySwingRequest(symbol, exchange, quantity, targetPct, null);
        return placeBuyInternal(req, ManualSwingTrade.TradeSource.AUTO, companyName, exchange);
    }

    private SwingTradeResponse placeBuyInternal(BuySwingRequest req, ManualSwingTrade.TradeSource source,
                                                String preResolvedCompanyName, String preResolvedExchange) {
        log.info("[SWING] {} BUY initiated: symbol={} exchange={} qty={}",
                source, req.symbol(), req.exchange(), req.quantity());

        if (req.quantity() <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        if (req.targetPct() == null && req.targetPrice() == null) {
            throw new IllegalArgumentException("Either targetPct or targetPrice is required");
        }

        // -- Duplicate-click / duplicate-submission protection ----------
        String lockKey = req.symbol().toUpperCase();
        long now = System.currentTimeMillis();
        Long existingLock = buyLocks.get(lockKey);
        if (existingLock != null && (now - existingLock) < config.getBuyLockExpiryMs()) {
            log.warn("[SWING] Duplicate prevention: BUY for {} rejected - an identical buy " +
                            "request is already being processed (within {}ms lock window)",
                    lockKey, config.getBuyLockExpiryMs());
            throw new IllegalArgumentException(
                    "A buy request for " + lockKey + " is already being processed - please wait");
        }
        buyLocks.put(lockKey, now);

        try {
            return doPlaceBuy(req, source, preResolvedCompanyName, preResolvedExchange);
        } finally {
            buyLocks.remove(lockKey);
        }
    }

    private SwingTradeResponse doPlaceBuy(BuySwingRequest req, ManualSwingTrade.TradeSource source,
                                          String preResolvedCompanyName, String preResolvedExchange) {
        String companyName;
        String exchange;

        if (preResolvedCompanyName != null) {
            // AUTO path - already resolved by the selection engine against
            // the full NSE+BSE universe, skip the NSE-only cache entirely.
            companyName = preResolvedCompanyName;
            exchange = preResolvedExchange != null ? preResolvedExchange : req.exchange();
        } else {
            Instrument inst = instrumentCache.getEquityInstruments()
                    .get(req.symbol().toUpperCase());
            if (inst == null) {
                throw new IllegalArgumentException("Unknown or non-tradable symbol: " + req.symbol());
            }
            companyName = inst.getName();
            exchange = req.exchange() != null ? req.exchange() : inst.getExchange();
        }

        // -- Place the real CNC BUY order ------------------------------
        String buyOrderId;
        try {
            buyOrderId = orderClient.placeBuyMarketOrder(req.symbol(), exchange, req.quantity());
        } catch (Exception e) {
            log.error("[SWING] BUY failed for {}: {}", req.symbol(), e.getMessage());
            throw new ManualSwingOrderClient.ManualSwingOrderException(
                    "Buy order placement failed: " + e.getMessage());
        }

        // -- Wait for fill confirmation - never save a trade until confirmed --
        FillResult fill = pollForFill(buyOrderId);
        if (fill == null) {
            log.error("[SWING] BUY order {} for {} did not confirm filled within poll window - " +
                    "NOT saving a trade record", buyOrderId, req.symbol());
            throw new ManualSwingOrderClient.ManualSwingOrderException(
                    "Buy order placed but fill could not be confirmed - check Zerodha directly " +
                            "before retrying, to avoid a duplicate position. Order ID: " + buyOrderId);
        }

        BigDecimal buyPrice = fill.avgPrice();
        BigDecimal targetPrice = resolveTargetPrice(buyPrice, req.targetPct(), req.targetPrice());

        Instant now = Instant.now();
        ManualSwingTrade trade = ManualSwingTrade.builder()
                .tradeId(UUID.randomUUID().toString())
                .symbol(req.symbol().toUpperCase())
                .companyName(companyName)
                .exchange(exchange)
                .quantity(fill.filledQty())
                .buyPrice(buyPrice)
                .buyDate(LocalDate.now())
                .buyTime(LocalTime.now())
                .targetPct(req.targetPct())
                .targetPrice(targetPrice)
                .zerodhaBuyOrderId(buyOrderId)
                .zerodhaSellOrderId(null)
                .sellPrice(null)
                .productType("CNC")
                .tradeSource(source)
                .tradeStatus(ManualSwingTrade.TradeStatus.ACTIVE)
                .sellStatus(ManualSwingTrade.SellStatus.PENDING)
                .exitReason(null)
                .createdAt(now)
                .updatedAt(now)
                .build();

        // -- Partial-failure handling: the order is ALREADY filled at the
        // broker at this point. If saving to the DB fails for any reason,
        // we must NEVER just silently report "buy failed" - that would
        // mean losing track of a REAL position. Log this at the highest
        // severity, with every detail needed for manual reconciliation,
        // distinctly from a genuine order-placement failure. -------------
        try {
            repo.save(trade);
        } catch (Exception e) {
            log.error("[SWING] *** CRITICAL - PARTIAL FAILURE *** Buy order {} for {} FILLED " +
                            "successfully at the broker (qty={} price={}) but saving the trade record " +
                            "to the database FAILED: {}. THIS POSITION EXISTS AT ZERODHA AND IS NOT " +
                            "CURRENTLY TRACKED BY THIS MODULE - manual reconciliation required immediately. " +
                            "tradeId={} buyOrderId={}",
                    buyOrderId, req.symbol(), fill.filledQty(), buyPrice, e.getMessage(),
                    trade.getTradeId(), buyOrderId, e);
            throw new ManualSwingOrderClient.ManualSwingOrderException(
                    "Buy order filled at the broker (orderId=" + buyOrderId + ", qty=" +
                            fill.filledQty() + ", price=" + buyPrice + ") but could NOT be saved to the " +
                            "database. This is a real position - please verify in Zerodha and contact " +
                            "support for manual reconciliation. Do not retry this buy.");
        }

        log.info("[SWING] BUY successful - Trade saved: {} {} qty={} buyPrice={} target={} orderId={}",
                trade.getTradeId(), trade.getSymbol(), trade.getQuantity(),
                trade.getBuyPrice(), trade.getTargetPrice(), buyOrderId);

        return SwingTradeResponse.from(trade, buyPrice);
    }

    private BigDecimal resolveTargetPrice(BigDecimal buyPrice, BigDecimal targetPct, BigDecimal targetPriceInput) {
        // targetPrice wins if both given - more explicit than a derived percentage
        if (targetPriceInput != null) return targetPriceInput;
        return buyPrice.add(buyPrice.multiply(targetPct).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
    }

    // ===================================================================
    // ORDER FILL POLLING
    // ===================================================================

    private record FillResult(BigDecimal avgPrice, int filledQty) {}

    private FillResult pollForFill(String orderId) {
        for (int attempt = 0; attempt < config.getOrderPollMaxAttempts(); attempt++) {
            try {
                List<Order> history = orderClient.getOrderHistory(orderId);
                if (history != null && !history.isEmpty()) {
                    Order latest = history.get(history.size() - 1);
                    if ("COMPLETE".equals(latest.status)) {
                        return new FillResult(
                                new BigDecimal(latest.averagePrice),
                                Integer.parseInt(latest.filledQuantity));
                    }
                    if ("REJECTED".equals(latest.status) || "CANCELLED".equals(latest.status)) {
                        log.error("[SWING] Order {} ended in status {} - {}",
                                orderId, latest.status, latest.statusMessage);
                        return null;
                    }
                }
            } catch (Exception e) {
                log.warn("[SWING] Poll attempt {} for order {} failed (will retry): {}",
                        attempt, orderId, e.getMessage());
            }
            try { Thread.sleep(config.getOrderPollIntervalMs()); } catch (InterruptedException ignored) {}
        }
        return null;
    }

    // ===================================================================
    // EXIT MONITORING - called by the dedicated scheduler
    // ===================================================================

    public void checkAndExitIfNeeded(ManualSwingTrade trade) {
        // RULE 1: never monitor on the purchase day, no matter what.
        if (trade.getBuyDate().isEqual(LocalDate.now())) {
            return;
        }
        if (trade.getSellStatus() != ManualSwingTrade.SellStatus.PENDING) {
            return; // already being sold or already sold - nothing to do
        }

        if (monitoringStartedLogged.add(trade.getTradeId())) {
            log.info("[SWING] Trade monitoring started: {} {} (buy date {}, now monitoring)",
                    trade.getTradeId(), trade.getSymbol(), trade.getBuyDate());
        }

        BigDecimal ltp = resolveLivePrice(trade.getSymbol(), trade.getExchange());
        if (ltp == null) {
            log.warn("[SWING] No live price available for {} (neither WebSocket cache nor " +
                    "direct quote) - skip this cycle, will retry next tick", trade.getSymbol());
            return;
        }

        BigDecimal gainPct = ltp.subtract(trade.getBuyPrice())
                .divide(trade.getBuyPrice(), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        boolean targetHit = ltp.compareTo(trade.getTargetPrice()) >= 0;
        boolean quickProfitHit = gainPct.compareTo(BigDecimal.valueOf(config.getProfitTargetPct())) >= 0;
        boolean pastForceExitTime = !LocalTime.now().isBefore(forceExitTime);

        String exitReason = null;
        if (targetHit) exitReason = "TARGET_HIT";
        else if (quickProfitHit) exitReason = "QUICK_PROFIT_5PCT";
        else if (pastForceExitTime) exitReason = "FORCE_EXIT_9_20";

        if (exitReason == null) return; // nothing to do yet, keep holding

        log.info("[SWING] Target reached / exit triggered for {}: reason={} ltp={} buyPrice={} gain={}%",
                trade.getSymbol(), exitReason, ltp, trade.getBuyPrice(), gainPct);
        attemptSell(trade, exitReason);
    }

    private void attemptSell(ManualSwingTrade trade, String exitReason) {
        // Atomic claim - the actual duplicate-protection mechanism for the
        // SELL side. If another scheduler tick already claimed this
        // trade, back off rather than attempt a second sell.
        if (!repo.claimForSell(trade.getTradeId())) {
            log.info("[SWING] Duplicate prevention: {} already claimed for sell by another " +
                    "monitoring cycle - skipping this attempt", trade.getTradeId());
            return;
        }

        String sellOrderId;
        try {
            sellOrderId = orderClient.placeSellMarketOrder(
                    trade.getSymbol(), trade.getExchange(), trade.getQuantity());
            repo.recordSellOrderId(trade.getTradeId(), sellOrderId);
            log.info("[SWING] SELL initiated: {} orderId={} reason={}",
                    trade.getSymbol(), sellOrderId, exitReason);
        } catch (Exception e) {
            log.error("[SWING] SELL failed for {}: {}", trade.getSymbol(), e.getMessage());
            repo.markSellFailed(trade.getTradeId(), e.getMessage());
            return;
        }

        FillResult fill = pollForFill(sellOrderId);
        if (fill == null) {
            log.error("[SWING] SELL order {} for {} did not confirm filled - will reconcile " +
                            "against real broker status on next restart, not blindly retried",
                    sellOrderId, trade.getSymbol());
            // Deliberately left in ORDER_PLACED here - reconcileStuckSellOrders()
            // checks the REAL broker status before deciding whether to retry
            // or mark complete. Never assume failure just because polling
            // timed out - the order may have filled after our last check.
            return;
        }

        repo.markSellCompleted(trade.getTradeId(), fill.avgPrice(), exitReason);
        log.info("[SWING] SELL successful: {} sellPrice={} reason={} - trade CLOSED",
                trade.getSymbol(), fill.avgPrice(), exitReason);
    }

    // ===================================================================
    // RESTART / CRASH RECOVERY
    // ===================================================================

    public void reconcileStuckSellOrders() {
        List<ManualSwingTrade> stuck = repo.findStuckOrderPlaced();
        if (stuck.isEmpty()) return;
        log.warn("[SWING] Application restart recovery: {} trade(s) found with a sell order " +
                "in flight at last shutdown - reconciling against real broker status before " +
                "resuming monitoring", stuck.size());

        for (ManualSwingTrade t : stuck) {
            if (t.getZerodhaSellOrderId() == null) {
                repo.revertStuckSellToPending(t.getTradeId());
                log.warn("[SWING] {} had no recorded sell order ID - reverted to PENDING for retry",
                        t.getTradeId());
                continue;
            }
            try {
                List<Order> history = orderClient.getOrderHistory(t.getZerodhaSellOrderId());
                if (history != null && !history.isEmpty()) {
                    Order latest = history.get(history.size() - 1);
                    if ("COMPLETE".equals(latest.status)) {
                        repo.markSellCompleted(t.getTradeId(),
                                new BigDecimal(latest.averagePrice), t.getExitReason());
                        log.info("[SWING] Application restart recovery: {} sell had ACTUALLY " +
                                "completed at the broker before restart - correctly marked " +
                                "CLOSED, no duplicate sell placed", t.getTradeId());
                        continue;
                    }
                }
                repo.revertStuckSellToPending(t.getTradeId());
                log.warn("[SWING] Application restart recovery: {} sell order {} never " +
                                "completed - reverted to PENDING for retry",
                        t.getTradeId(), t.getZerodhaSellOrderId());
            } catch (Exception e) {
                log.error("[SWING] Application restart recovery: could not verify order {} " +
                                "for {} - leaving as-is rather than guessing: {}",
                        t.getZerodhaSellOrderId(), t.getTradeId(), e.getMessage());
            }
        }
    }

    @PreDestroy
    public void onShutdown() {
        log.info("[SWING] Scheduler stopped - application shutting down. All ACTIVE trade " +
                "state is persisted in the database and will resume automatically on next startup.");
    }

    // ===================================================================
    // READ - for the controller
    // ===================================================================

    /**
     * NOTE: deliberately uses the cached WebSocket price ONLY here, not
     * resolveLivePrice()'s REST fallback. This method is called on every
     * UI poll (every 10s, possibly from multiple browser tabs) - using
     * the REST fallback here would mean a real Zerodha API call per
     * active untracked-symbol trade, per poll, purely for cosmetic
     * display. That's a meaningfully different cost profile from
     * checkAndExitIfNeeded()'s fallback, which runs once a minute via
     * the scheduler and gates an actual money-moving decision. A trade
     * on a symbol outside AI/News's subscription scope will correctly
     * show "-" for current price here until it resolves - a minor
     * display gap, not a correctness issue, given the real exit logic
     * is unaffected by this choice.
     */
    public List<SwingTradeResponse> getTrades(String filter) {
        Map<String, BigDecimal> livePrices = marketDataService.getLastPricesSimple();
        List<ManualSwingTrade> trades = repo.findAll();

        return trades.stream()
                .filter(t -> matchesFilter(t, filter))
                .map(t -> SwingTradeResponse.from(t, livePrices.get(t.getSymbol())))
                .collect(Collectors.toList());
    }

    private boolean matchesFilter(ManualSwingTrade t, String filter) {
        if (filter == null || filter.isBlank() || "ALL".equalsIgnoreCase(filter)) return true;
        switch (filter.toUpperCase()) {
            case "ACTIVE": return t.getTradeStatus() == ManualSwingTrade.TradeStatus.ACTIVE;
            case "CLOSED": return t.getTradeStatus() == ManualSwingTrade.TradeStatus.CLOSED;
            case "TODAY":  return t.getBuyDate().isEqual(LocalDate.now());
            case "PROFIT": case "LOSS": {
                BigDecimal ref = t.getTradeStatus() == ManualSwingTrade.TradeStatus.CLOSED
                        ? t.getSellPrice() : marketDataService.getLastPricesSimple().get(t.getSymbol());
                if (ref == null) return false;
                boolean inProfit = ref.compareTo(t.getBuyPrice()) > 0;
                return "PROFIT".equalsIgnoreCase(filter) ? inProfit : !inProfit;
            }
            default: return true;
        }
    }
}