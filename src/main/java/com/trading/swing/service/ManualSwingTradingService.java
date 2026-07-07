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
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Instrument;
import com.zerodhatech.models.Order;
import com.zerodhatech.models.Quote;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
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

    // FIX (confirmed critical bug from a real production log - the
    // AutoSwingScheduler fired its 3 PM logic at 1:03 AM IST on
    // Railway). Bare LocalDate.now()/LocalTime.now() use the JVM's
    // default timezone - UTC on Railway, NOT India time. This affects
    // the ACTUAL force-exit comparison, buy-date recording, and every
    // "is this today" check in this file - all now explicitly
    // Asia/Kolkata-zoned.
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final ManualSwingTradeRepository repo;
    private final ManualSwingOrderClient orderClient;
    private final InstrumentCacheService instrumentCache;
    private final MarketDataService marketDataService;
    private final KiteConnect kiteConnect;
    private final ManualSwingConfig config;
    private final com.trading.swing.auto.repository.DailyBarRepository dailyBarRepo;

    // FIX (per explicit request: "small cap stocks don't trigger order
    // ... how can we fill that gap"). A genuinely illiquid stock has no
    // real counter-party to fill against - no amount of code makes it
    // trade faster, since there's simply nobody on the other side. What
    // CAN be fixed: catching this BEFORE wasting a real order attempt
    // and the full 30-second fill-confirmation poll, using volume data
    // this system already has from bhavcopy. Purely advisory for manual
    // trades (warns, does not block - your call remains final); a real,
    // additional filter for auto-selection (skips a candidate that's
    // unlikely to fill at all, before ever reaching Rule 4).
    private static final long MIN_AVG_DAILY_VOLUME = 10_000;

    /**
     * Checks the last 5 trading days' average volume from real bhavcopy
     * data. Returns null if genuinely liquid enough or if no history is
     * available (fails open - never blocks a trade purely due to a data
     * gap); returns a human-readable warning string if the stock's
     * recent volume is low enough that a fill is genuinely uncertain.
     */
    private String checkLiquidityWarning(String symbol) {
        try {
            var bars = dailyBarRepo.findBySymbol(symbol, LocalDate.now(IST).minusDays(10));
            if (bars.size() < 3) return null; // not enough history to judge - fail open
            long avgVolume = (long) bars.stream()
                    .skip(Math.max(0, bars.size() - 5))
                    .mapToLong(b -> b.volume())
                    .average().orElse(0);
            if (avgVolume < MIN_AVG_DAILY_VOLUME) {
                return String.format("LOW LIQUIDITY WARNING: %s averaged only %,d shares/day " +
                                "over the last 5 trading days (threshold: %,d) - your order may sit " +
                                "unfilled or fill at a poor price. Proceeding is your call.",
                        symbol, avgVolume, MIN_AVG_DAILY_VOLUME);
            }
        } catch (Exception e) {
            log.debug("[SWING] Liquidity check failed for {} (non-fatal, proceeding): {}",
                    symbol, e.getMessage());
        }
        return null; // liquid enough, or couldn't determine - fail open either way
    }

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

    // FIX (per explicit request: "add in search bar all the stocks for
    // manual trading"). instrumentCache.getEquityInstruments() is
    // confirmed NSE-only (built from a filter in InstrumentCacheService
    // that excludes BSE entirely) - a stock listed only on BSE could
    // never be found by manual search before this fix. This is a
    // dedicated, self-contained full NSE+BSE cache used ONLY for manual
    // instrument search - deliberately NOT touching or widening the
    // shared InstrumentCacheService, since that cache backs AI/News
    // sector classification and other systems that are correctly scoped
    // to NSE only. Refreshed once daily (new instrument listings/
    // delistings are rare intraday events); auto-trade's own universe
    // fetch in AutoStockSelectionEngine already covers full NSE+BSE
    // separately and is unaffected by this.
    private final AtomicReference<Map<String, Instrument>> fullUniverseCache =
            new AtomicReference<>(Collections.emptyMap());
    private volatile Instant fullUniverseCacheBuiltAt = Instant.EPOCH;

    public ManualSwingTradingService(ManualSwingTradeRepository repo,
                                     ManualSwingOrderClient orderClient,
                                     InstrumentCacheService instrumentCache,
                                     MarketDataService marketDataService,
                                     KiteConnect kiteConnect,
                                     ManualSwingConfig config,
                                     com.trading.swing.auto.repository.DailyBarRepository dailyBarRepo) {
        this.repo = repo;
        this.orderClient = orderClient;
        this.instrumentCache = instrumentCache;
        this.marketDataService = marketDataService;
        this.kiteConnect = kiteConnect;
        this.dailyBarRepo = dailyBarRepo;
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

        List<Instrument> matches = getFullUniverse().values().stream()
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

    // FIX (found during production readiness cross-check): the initial
    // version had no synchronization and no failure backoff. Without
    // these, concurrent search requests hitting a stale/empty cache
    // simultaneously would each fire duplicate kiteConnect.getInstruments()
    // calls, and a single transient failure (network blip, auth token not
    // ready yet at startup) would leave the cache permanently empty,
    // causing EVERY subsequent search keystroke for the rest of the day to
    // retry the same failing call with zero backoff - a real resource-
    // hammering risk under load, not just a theoretical one.
    private volatile Instant lastUniverseFetchAttempt = Instant.EPOCH;
    private static final long UNIVERSE_FAILURE_COOLDOWN_SECONDS = 300; // 5 min

    /**
     * Lazily builds/refreshes the full NSE+BSE search universe, once
     * per day. Falls back to serving the existing (possibly stale)
     * cache on fetch failure, rather than returning an empty search
     * result for the rest of the day on a single transient error.
     * Synchronized to prevent concurrent duplicate Kite API calls when
     * multiple search requests arrive while the cache is stale/empty.
     */
    private synchronized Map<String, Instrument> getFullUniverse() {
        boolean needsRefresh = fullUniverseCache.get().isEmpty()
                || Instant.now().isAfter(fullUniverseCacheBuiltAt.plusSeconds(86400));
        boolean inFailureCooldown = Instant.now().isBefore(
                lastUniverseFetchAttempt.plusSeconds(UNIVERSE_FAILURE_COOLDOWN_SECONDS));

        if (needsRefresh && !inFailureCooldown) {
            lastUniverseFetchAttempt = Instant.now();
            try {
                Map<String, Instrument> fresh = new LinkedHashMap<>();
                for (Instrument i : kiteConnect.getInstruments("NSE")) {
                    if (i.getTradingsymbol() != null
                            && ("EQ".equals(i.getInstrument_type()) || "BE".equals(i.getInstrument_type()))) {
                        fresh.putIfAbsent(i.getTradingsymbol().toUpperCase(), i);
                    }
                }
                int nseCount = fresh.size();
                for (Instrument i : kiteConnect.getInstruments("BSE")) {
                    if (i.getTradingsymbol() != null
                            && ("EQ".equals(i.getInstrument_type()) || "BE".equals(i.getInstrument_type()))) {
                        fresh.putIfAbsent(i.getTradingsymbol().toUpperCase(), i);
                    }
                }
                fullUniverseCache.set(fresh);
                fullUniverseCacheBuiltAt = Instant.now();
                log.info("[SWING] Full search universe refreshed: {} NSE + {} BSE-only = {} " +
                                "unique tradable symbols now searchable", nseCount,
                        fresh.size() - nseCount, fresh.size());
            } catch (KiteException | IOException e) {
                log.warn("[SWING] Failed to refresh full search universe - serving existing " +
                                "cache ({} symbols) and backing off for {}s rather than retrying on " +
                                "every subsequent search: {}",
                        fullUniverseCache.get().size(), UNIVERSE_FAILURE_COOLDOWN_SECONDS,
                        e.getMessage());
            }
        }
        return fullUniverseCache.get();
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

        // FIX (per explicit request: fill the small-cap illiquidity gap).
        // Advisory only - logged clearly so you see it immediately, but
        // deliberately does NOT block or throw. Catches the case BEFORE
        // wasting a real order attempt + 30s fill-confirmation poll on a
        // stock unlikely to fill.
        String liquidityWarning = checkLiquidityWarning(req.symbol());
        if (liquidityWarning != null) {
            log.warn("[SWING] {}", liquidityWarning);
        }

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
            // the full NSE+BSE universe, skip the cache entirely.
            companyName = preResolvedCompanyName;
            exchange = preResolvedExchange != null ? preResolvedExchange : req.exchange();
        } else {
            // FIX: was instrumentCache.getEquityInstruments() - NSE-only,
            // confirmed. A BSE-only stock found via the now-fixed full-
            // universe searchInstruments() would fail here with "Unknown
            // or non-tradable symbol" without this matching fix - search
            // and buy must use the same universe or finding a stock via
            // search would be misleading (found, but can't actually buy it).
            Instrument inst = getFullUniverse().get(req.symbol().toUpperCase());
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
        FillResult fill;
        try {
            fill = pollForFill(buyOrderId);
        } catch (OrderRejectedException rejected) {
            // FIX: definitive rejection (e.g. insufficient margin) - no
            // position was ever opened, nothing ambiguous, no duplicate-
            // position risk. Clear, accurate message with Zerodha's own
            // reason, instead of the misleading "check Zerodha directly
            // to avoid a duplicate position" warning this used to show.
            log.error("[SWING] BUY order {} for {} was REJECTED by broker: {}",
                    buyOrderId, req.symbol(), rejected.getMessage());
            throw new ManualSwingOrderClient.ManualSwingOrderException(
                    "Buy order rejected by Zerodha: " + rejected.getMessage() +
                            ". No position was opened - safe to retry once the issue is resolved.");
        }
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
                .buyDate(LocalDate.now(IST))
                .buyTime(LocalTime.now(IST))
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
            throw new ManualSwingOrderClient.PartialFailureException(
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

    /**
     * Thrown when the broker gave a CLEAR, definitive REJECTED/CANCELLED
     * status - as opposed to a genuinely ambiguous poll-timeout (order
     * still PENDING after all attempts). FIX (found via direct user
     * report: an insufficient-margin rejection was showing the same
     * "check Zerodha directly to avoid a duplicate position" warning as
     * a genuinely uncertain fill - misleading, since a REJECTED order
     * definitively means NO position was ever opened, nothing to check
     * for duplicates. Carries the real reason string from Zerodha
     * (e.g. "insufficient margin") so the UI can show the actual cause.
     */
    static final class OrderRejectedException extends RuntimeException {
        OrderRejectedException(String reason) { super(reason); }
    }

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
                        // FIX: previously just returned null here, indistinguishable
                        // from a genuine poll-timeout - now throws with the REAL
                        // reason from Zerodha, since this is a DEFINITIVE outcome,
                        // not an ambiguous one. No position was ever opened - there
                        // is nothing to "check for duplicates."
                        throw new OrderRejectedException(
                                latest.statusMessage != null && !latest.statusMessage.isBlank()
                                        ? latest.statusMessage
                                        : "Order " + latest.status + " by broker (no further reason given)");
                    }
                }
            } catch (OrderRejectedException rejected) {
                throw rejected; // propagate immediately - don't keep polling a dead order
            } catch (Exception e) {
                log.warn("[SWING] Poll attempt {} for order {} failed (will retry): {}",
                        attempt, orderId, e.getMessage());
            }
            try { Thread.sleep(config.getOrderPollIntervalMs()); } catch (InterruptedException ignored) {}
        }
        return null; // genuinely ambiguous - still PENDING after every attempt, unchanged from before
    }

    // ===================================================================
    // EXIT MONITORING - called by the dedicated scheduler
    // ===================================================================

    public void checkAndExitIfNeeded(ManualSwingTrade trade) {
        // RULE 1: never monitor on the purchase day, no matter what.
        if (trade.getBuyDate().isEqual(LocalDate.now(IST))) {
            return;
        }

        // FIX (found via explicit question: "buy Friday, Saturday/Sunday,
        // sell Monday - is this handled?"). CONFIRMED REAL BUG: the only
        // existing check above (buyDate.isEqual(today)) only skips the
        // exact purchase day - it does NOT skip weekends. Separately,
        // pastForceExitTime below only ever checked the CLOCK TIME
        // (9:20 AM), with zero awareness of what DAY it is. Combined,
        // this meant: buy on Friday -> Saturday once the clock passes
        // 9:20 AM, force-exit would have incorrectly triggered and
        // attempted a real SELL order on a day the market is closed.
        // Fixed here: market is CNC/equity, trades Mon-Fri only (no
        // Indian market holiday calendar is wired into this system, so
        // genuine market holidays on a weekday are NOT caught by this
        // specific fix - only weekends are, which is what was asked).
        // On a real market holiday landing on a weekday, this system
        // still has no way to know that without a holiday-calendar data
        // source - a separate, pre-existing limitation, not something
        // this fix claims to solve.
        // FIX (per explicit follow-up: "all the holiday also handled in
        // this strategy please check"). Upgraded from a weekend-only
        // check to also cover genuine NSE/BSE trading holidays - see
        // MarketHolidayChecker's class docstring for the honest caveat
        // on how that holiday list was compiled and its limitations.
        if (MarketHolidayChecker.isMarketClosedToday()) {
            return; // market closed (weekend or known holiday) - never attempt monitoring or exit
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
        boolean pastForceExitTime = !LocalTime.now(IST).isBefore(forceExitTime);

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

        FillResult fill;
        try {
            fill = pollForFill(sellOrderId);
        } catch (OrderRejectedException rejected) {
            // FIX: same issue as the buy side - a definitive REJECTED
            // status was previously falling into the same generic
            // null-handling as a genuine poll-timeout, meaning a
            // rejected sell order would sit in ORDER_PLACED forever
            // waiting for a reconciliation that will never find a
            // completed fill (because there isn't one - it was
            // rejected). Now marked failed immediately, with the real
            // reason, so this position doesn't get silently stuck.
            log.error("[SWING] SELL order {} for {} was REJECTED by broker: {}",
                    sellOrderId, trade.getSymbol(), rejected.getMessage());
            repo.markSellFailed(trade.getTradeId(), "Rejected: " + rejected.getMessage());
            return;
        }
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
            case "TODAY":  return t.getBuyDate().isEqual(LocalDate.now(IST));
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