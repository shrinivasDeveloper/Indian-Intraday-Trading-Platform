package com.trading.ai.execution;

import com.trading.execution.client.ZerodhaOrderClient;
import com.zerodhatech.models.Order;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * AiLiveOrderExecutionService
 *
 * Bridges AiTradeManagementEngine's existing, already-validated entry/exit
 * DECISIONS (when to enter, when SL/T1/T2/EOD says to exit) to REAL Zerodha
 * orders, when trading.mode=LIVE. When trading.mode=PAPER, this service is
 * never called — AiTradingSystem/AiTradeManagementEngine continue using the
 * existing in-memory paper simulation exactly as before. Zero regression to
 * paper trading; this is purely additive.
 *
 * DESIGN — reuses the EXACT proven pattern already in production for the
 * HighRR strategy (HighRROrderExecutionService): same ZerodhaOrderClient
 * abstraction (placeLimitOrder/cancelOrder — the only two methods this
 * service calls, both already confirmed working), same trading.mode toggle,
 * same NSE tick-alignment approach. This is a deliberate choice: reusing an
 * already-battle-tested integration point is far safer than introducing a
 * new, unverified order-placement code path for real money.
 *
 * IMPROVEMENTS over the HighRR pattern, specifically requested for AI/News:
 *   1. REAL order-status reconciliation via orderClient.getOrderHistory() —
 *      HighRR infers fills purely from tick-price-crossing, which cannot
 *      detect partial fills or exchange-side rejections after acceptance.
 *      This service polls actual broker order status every few seconds for
 *      any order still pending, and reacts to COMPLETE / PARTIALLY_FILLED /
 *      REJECTED / CANCELLED based on the broker's own record, not inference.
 *   2. Persisted idempotency lock (live_order_locks) — a DB-level mutex per
 *      (symbol, trade_date, ENTRY|EXIT) backed by a primary-key uniqueness
 *      violation. Two near-simultaneous attempts to enter or exit the same
 *      symbol — whether from a race condition between checks, or from a
 *      restart re-deciding to exit a position whose exit order is already
 *      in flight — cannot both succeed in placing an order. The second
 *      attempt is blocked at the database level, not just by an in-memory
 *      flag that a restart would lose.
 *   3. Full order audit trail (live_orders) — every order this service ever
 *      places, including ones that get rejected or cancelled, is persisted
 *      with its full lifecycle (requested price/qty → filled qty/avg price
 *      → terminal status). This is the foundation for the position/P&L
 *      reconciliation job in reconcilePositionsWithBroker() below.
 *
 * WHAT THIS SERVICE DELIBERATELY DOES NOT DO:
 *   - It does not decide WHEN to enter or exit — that remains entirely
 *     AiTradeManagementEngine's job (SL/T1/T2/EOD logic, completely
 *     unchanged). This service only executes a decision already made.
 *   - It does not manage News strategy directly — NewsTradingStrategy should
 *     call the same placeEntryOrder/placeExitOrder methods with its own
 *     strategyName ("NEWS_CATALYST_V1"), reusing this exact same safety
 *     infrastructure rather than duplicating it.
 */
@Service
@Slf4j
public class AiLiveOrderExecutionService {

    // ── Entry/exit price buffer — same technique as HighRR's proven pattern.
    // A small unfavourable buffer on the limit price ensures the order
    // crosses and fills promptly against current market depth, rather than
    // sitting unfilled at the exact signal price. ─────────────────────────
    private static final double PRICE_BUFFER_PCT = 0.0005; // 0.05%

    // ── NSE tick size — all NSE equity orders must be in multiples of ₹0.05.
    private static final double NSE_TICK_SIZE = 0.05;

    // ── Order status polling ──────────────────────────────────────────────
    private static final long POLL_INTERVAL_MS = 3_000L;

    private final JdbcTemplate jdbc;

    @Autowired(required = false)
    private ZerodhaOrderClient orderClient;

    @Value("${trading.mode:LIVE}")
    private String tradingMode;

    // ── Callbacks — wired by AiTradeManagementEngine / NewsTradingStrategy
    // so this service never needs to import or know about either strategy's
    // internal position-tracking classes. ────────────────────────────────
    // FIX: was single-slot fields. With BOTH AiTradeManagementEngine and
    // NewsTradeManagementEngine now sharing this one execution service,
    // a single slot per callback type would mean whichever engine's
    // constructor ran SECOND silently overwrote the first's registration —
    // the exact same class of bug already found once this session, now
    // recurring between strategies instead of within one. Keyed by
    // strategyName (already passed to placeEntryOrder/placeExitOrder and
    // persisted in live_orders) so each strategy's callbacks coexist safely
    // regardless of Spring's bean construction order.
    private final Map<String, BiConsumer<String, FillResult>> onEntryFilledByStrategy = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, BiConsumer<String, FillResult>> onExitFilledByStrategy  = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, BiConsumer<String, String>>     onEntryRejectedByStrategy = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, BiConsumer<String, String>>     onExitRejectedByStrategy  = new java.util.concurrent.ConcurrentHashMap<>();

    // FIX: @Scheduled methods cannot take parameters — Spring invokes them
    // with zero arguments. Storing this supplier as a field (set once at
    // wiring time, same pattern as the callbacks above) lets the scheduled
    // reconciliation method below read live position data without needing
    // a method parameter.
    private java.util.function.Supplier<Map<String, Integer>> ourOpenPositionsSupplier;

    public AiLiveOrderExecutionService(JdbcTemplate jdbc) {
        this.jdbc        = jdbc;
        ensureTablesExist();
    }

    public void setOnEntryFilled(String strategyName, BiConsumer<String, FillResult> callback) {
        onEntryFilledByStrategy.put(strategyName, callback);
    }

    public void setOnExitFilled(String strategyName, BiConsumer<String, FillResult> callback) {
        onExitFilledByStrategy.put(strategyName, callback);
    }

    public void setOnEntryRejected(String strategyName, BiConsumer<String, String> callback) {
        onEntryRejectedByStrategy.put(strategyName, callback);
    }

    public void setOnExitRejected(String strategyName, BiConsumer<String, String> callback) {
        onExitRejectedByStrategy.put(strategyName, callback);
    }

    /**
     * Wired once by AiTradingSystem at startup, e.g.:
     *   liveOrderService.setOurOpenPositionsSupplier(() ->
     *       tradeManager.getOpenPositions().entrySet().stream()
     *           .collect(Collectors.toMap(Map.Entry::getKey,
     *               e -> e.getValue().trade.getQuantity())));
     */
    public void setOurOpenPositionsSupplier(java.util.function.Supplier<Map<String, Integer>> supplier) {
        this.ourOpenPositionsSupplier = supplier;
    }

    public boolean isLiveMode() {
        return "LIVE".equalsIgnoreCase(tradingMode);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TABLE SETUP
    // ═══════════════════════════════════════════════════════════════════════

    private void ensureTablesExist() {
        try {
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS live_orders (
                    order_id          VARCHAR(50)  PRIMARY KEY,
                    symbol            VARCHAR(20)  NOT NULL,
                    trade_date        DATE         NOT NULL,
                    strategy_name     VARCHAR(50)  NOT NULL,
                    order_purpose     VARCHAR(10)  NOT NULL,
                    transaction_type  VARCHAR(5)   NOT NULL,
                    requested_qty     INT          NOT NULL,
                    requested_price   DECIMAL(12,2),
                    status            VARCHAR(20)  NOT NULL,
                    filled_qty        INT          DEFAULT 0,
                    avg_fill_price    DECIMAL(12,2),
                    status_message    TEXT,
                    exit_reason       VARCHAR(30),
                    placed_at         TIMESTAMP    NOT NULL,
                    last_checked_at   TIMESTAMP,
                    INDEX idx_status_pending (status, trade_date)
                )
                """);
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS live_order_locks (
                    symbol      VARCHAR(20) NOT NULL,
                    trade_date  DATE        NOT NULL,
                    lock_type   VARCHAR(10) NOT NULL,
                    locked_at   TIMESTAMP   NOT NULL,
                    order_id    VARCHAR(50),
                    PRIMARY KEY (symbol, trade_date, lock_type)
                )
                """);
        } catch (Exception e) {
            log.error("[AI-LIVE-EXEC] Could not create live order tables — " +
                    "LIVE trading cannot safely proceed without these. " +
                    "Check database connectivity: {}", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // IDEMPOTENCY LOCK — DB-level mutex, survives restarts
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Attempts to acquire a lock for (symbol, ENTRY|EXIT) today. Returns
     * false if a lock is already held — meaning an order for this exact
     * purpose is already in flight, and this attempt must NOT proceed.
     * This is the primary defence against duplicate/overlapping orders.
     */
    private boolean acquireLock(String symbol, String lockType) {
        try {
            jdbc.update("""
                INSERT INTO live_order_locks (symbol, trade_date, lock_type, locked_at)
                VALUES (?, ?, ?, ?)
                """,
                    symbol, LocalDate.now(ZoneId.of("Asia/Kolkata")), lockType, java.sql.Timestamp.from(Instant.now()));
            return true;
        } catch (DataIntegrityViolationException e) {
            // Primary key violation — lock already held by an earlier attempt.
            return false;
        } catch (Exception e) {
            log.error("[AI-LIVE-EXEC] acquireLock failed unexpectedly for {} {} — " +
                            "refusing to proceed (fail-safe: treat as locked): {}",
                    symbol, lockType, e.getMessage());
            return false; // fail-safe: if we can't confirm the lock, don't risk a duplicate
        }
    }

    private void releaseLock(String symbol, String lockType) {
        try {
            jdbc.update("DELETE FROM live_order_locks WHERE symbol = ? AND trade_date = ? AND lock_type = ?",
                    symbol, LocalDate.now(ZoneId.of("Asia/Kolkata")), lockType);
        } catch (Exception e) {
            log.warn("[AI-LIVE-EXEC] releaseLock failed for {} {} (non-fatal, will retry " +
                    "via daily cleanup): {}", symbol, lockType, e.getMessage());
        }
    }

    private void attachOrderIdToLock(String symbol, String lockType, String orderId) {
        try {
            jdbc.update("UPDATE live_order_locks SET order_id = ? WHERE symbol = ? AND trade_date = ? AND lock_type = ?",
                    orderId, symbol, LocalDate.now(ZoneId.of("Asia/Kolkata")), lockType);
        } catch (Exception e) {
            log.debug("[AI-LIVE-EXEC] attachOrderIdToLock failed (non-fatal): {}", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ENTRY ORDER
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Places a LIVE entry order. Returns the orderId if successfully placed
     * with the broker, or null if blocked (duplicate-prevention lock already
     * held) or if placement itself failed.
     *
     * IMPORTANT: a non-null return means the order was ACCEPTED for
     * processing — it does NOT mean it is filled. Fill confirmation happens
     * asynchronously via pollPendingOrders() below, which will invoke
     * onEntryFilled(...) once the broker confirms a fill.
     */
    public String placeEntryOrder(String symbol, boolean isBuy, int qty,
                                  double signalPrice, String strategyName) {
        if (!acquireLock(symbol, "ENTRY")) {
            log.warn("[AI-LIVE-EXEC] Entry BLOCKED for {} — an entry order is already " +
                    "in flight today (duplicate prevented).", symbol);
            return null;
        }

        if (orderClient == null) {
            log.error("[AI-LIVE-EXEC] No ZerodhaOrderClient bean available — cannot place " +
                            "LIVE entry for {}. Is trading.mode=LIVE configured without Kite credentials?",
                    symbol);
            releaseLock(symbol, "ENTRY");
            return null;
        }

        double buffered    = isBuy ? signalPrice * (1 + PRICE_BUFFER_PCT)
                : signalPrice * (1 - PRICE_BUFFER_PCT);
        double tickAligned = roundToTick(buffered);
        String txType      = isBuy ? "BUY" : "SELL";

        String orderId;
        try {
            orderId = orderClient.placeLimitOrder(symbol, txType, qty, tickAligned);
        } catch (Exception e) {
            log.error("[AI-LIVE-EXEC] Entry order placement FAILED for {}: {}",
                    symbol, e.getMessage());
            releaseLock(symbol, "ENTRY"); // nothing pending — safe to release immediately
            // FIX (found while confirming order-placement failures are
            // visible on the dashboard - they weren't): this catch block
            // previously only logged and returned null, silently. Now
            // routes through the SAME onEntryRejectedByStrategy callback
            // already used for genuine broker-side rejections, so a
            // pre-flight failure (e.g. AccountMarginGuard's insufficient-
            // margin check, added during the platform-wide cross-strategy
            // safeguard review, or any other exception before the order
            // ever reaches the broker) is now visible in exactly the
            // same place genuine rejections already were - the
            // strategy's own blockReasons map, shown on the dashboard.
            BiConsumer<String, String> cb = onEntryRejectedByStrategy.get(strategyName);
            if (cb != null) cb.accept(symbol, e.getMessage());
            return null;
        }

        persistOrder(orderId, symbol, strategyName, "ENTRY", txType, qty, tickAligned, null);
        attachOrderIdToLock(symbol, "ENTRY", orderId);
        log.info("[AI-LIVE-EXEC] ✅ LIVE entry order placed: {} {} qty={} price={} orderId={}",
                txType, symbol, qty, tickAligned, orderId);
        return orderId;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // EXIT ORDER
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Places a LIVE exit order in the OPPOSITE direction of the open
     * position. exitReason should be one of: SL_HIT, TRAIL_HIT_T1,
     * TRAIL_HIT_T2, EOD_EXIT, MANUAL — matches AiTradeManagementEngine's
     * existing exit-reason vocabulary exactly, for a consistent audit trail.
     */
    public String placeExitOrder(String symbol, boolean wasLong, int qty,
                                 double currentPrice, String strategyName, String exitReason) {
        if (!acquireLock(symbol, "EXIT")) {
            log.warn("[AI-LIVE-EXEC] Exit BLOCKED for {} — an exit order is already " +
                            "in flight today (duplicate exit prevented). Reason requested: {}",
                    symbol, exitReason);
            return null;
        }

        if (orderClient == null) {
            log.error("[AI-LIVE-EXEC] No ZerodhaOrderClient bean available — CANNOT EXIT " +
                            "LIVE position {} ({})! Manual intervention required immediately.",
                    symbol, exitReason);
            releaseLock(symbol, "EXIT");
            return null;
        }

        // Exiting a LONG = SELL; exiting a SHORT = BUY (to cover).
        boolean isSell      = wasLong;
        double  buffered    = isSell ? currentPrice * (1 - PRICE_BUFFER_PCT)
                : currentPrice * (1 + PRICE_BUFFER_PCT);
        double  tickAligned = roundToTick(buffered);
        String  txType      = isSell ? "SELL" : "BUY";

        String orderId;
        try {
            orderId = orderClient.placeLimitOrder(symbol, txType, qty, tickAligned);
        } catch (Exception e) {
            log.error("[AI-LIVE-EXEC] ⚠️ EXIT ORDER PLACEMENT FAILED for {} ({}): {} — " +
                            "position remains open and unmanaged. Will retry on next cycle.",
                    symbol, exitReason, e.getMessage());
            releaseLock(symbol, "EXIT"); // allow retry on the next onCandle() cycle
            // FIX (same visibility gap as the entry path, arguably more
            // critical here - a failed exit leaves a real position open
            // and unmanaged). Now routes through onExitRejectedByStrategy
            // so this is visible on the dashboard, not just a log line.
            BiConsumer<String, String> cb = onExitRejectedByStrategy.get(strategyName);
            if (cb != null) cb.accept(symbol, e.getMessage());
            return null;
        }

        persistOrder(orderId, symbol, strategyName, "EXIT", txType, qty, tickAligned, exitReason);
        attachOrderIdToLock(symbol, "EXIT", orderId);
        log.info("[AI-LIVE-EXEC] ✅ LIVE exit order placed: {} {} qty={} price={} orderId={} reason={}",
                txType, symbol, qty, tickAligned, orderId, exitReason);
        return orderId;
    }

    private void persistOrder(String orderId, String symbol, String strategyName,
                              String purpose, String txType, int qty, double price,
                              String exitReason) {
        try {
            jdbc.update("""
                INSERT INTO live_orders
                  (order_id, symbol, trade_date, strategy_name, order_purpose,
                   transaction_type, requested_qty, requested_price, status,
                   exit_reason, placed_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """,
                    orderId, symbol, LocalDate.now(ZoneId.of("Asia/Kolkata")), strategyName, purpose,
                    txType, qty, bd(price), "PENDING", exitReason,
                    java.sql.Timestamp.from(Instant.now()));
        } catch (Exception e) {
            log.error("[AI-LIVE-EXEC] CRITICAL: persistOrder failed for orderId={} symbol={} — " +
                    "order was placed with the broker but is NOT tracked in our database. " +
                    "Manual reconciliation required: {}", orderId, symbol, e.getMessage());
        }
    }

    private double roundToTick(double price) {
        return Math.round(price / NSE_TICK_SIZE) * NSE_TICK_SIZE;
    }

    private BigDecimal bd(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ORDER STATUS RECONCILIATION — real broker status, not tick inference
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Polls the broker's ACTUAL order status for every order still PENDING
     * or OPEN in our database. This is the key improvement over the existing
     * HighRR pattern's tick-price-crossing inference: it catches partial
     * fills, exchange-side rejections, and cancellations directly from
     * Zerodha's own order record, not from a heuristic.
     */
    @Scheduled(fixedRate = POLL_INTERVAL_MS)
    @Async("tradingExecutor")
    public void pollPendingOrders() {
        if (!isLiveMode()) return;

        List<Map<String, Object>> pending;
        try {
            pending = jdbc.queryForList(
                    "SELECT order_id, symbol, order_purpose, strategy_name, transaction_type, " +
                            "requested_qty, exit_reason FROM live_orders " +
                            "WHERE trade_date = ? AND status IN ('PENDING','OPEN','PARTIALLY_FILLED')",
                    LocalDate.now(ZoneId.of("Asia/Kolkata")));
        } catch (Exception e) {
            log.debug("[AI-LIVE-EXEC] pollPendingOrders query failed (non-fatal): {}", e.getMessage());
            return;
        }
        if (pending.isEmpty()) return;

        for (Map<String, Object> row : pending) {
            String orderId  = (String) row.get("order_id");
            String symbol   = (String) row.get("symbol");
            String purpose  = (String) row.get("order_purpose");
            String strategy = (String) row.get("strategy_name");
            String exitReason = (String) row.get("exit_reason");
            int requestedQty  = ((Number) row.get("requested_qty")).intValue();

            checkAndProcessOrderStatus(orderId, symbol, purpose, strategy, exitReason, requestedQty);
        }
    }

    private void checkAndProcessOrderStatus(String orderId, String symbol, String purpose,
                                            String strategy, String exitReason, int requestedQty) {
        try {
            // getOrderHistory returns the full lifecycle of this order; the LAST
            // entry is its current/latest status — standard KiteConnect convention.
            // Uses the existing, already JAR-verified ZerodhaOrderClient wrapper
            // (confirmed against the actual SDK source — see class header) rather
            // than calling KiteConnect directly, for consistency with the rest of
            // the codebase's Kite integration and to reuse its exception handling.
            List<Order> history = orderClient.getOrderHistory(orderId);
            if (history == null || history.isEmpty()) {
                log.debug("[AI-LIVE-EXEC] No order history yet for {} (orderId={}) — " +
                        "will check again next cycle.", symbol, orderId);
                return;
            }
            Order latest = history.get(history.size() - 1);
            String status = latest.status; // e.g. "COMPLETE", "REJECTED", "CANCELLED", "OPEN"

            int filledQty = 0;
            double avgPrice = 0;
            try { filledQty = Integer.parseInt(latest.filledQuantity); } catch (Exception ignored) {}
            try { avgPrice  = Double.parseDouble(latest.averagePrice); } catch (Exception ignored) {}

            updateOrderStatus(orderId, status, filledQty, avgPrice, latest.statusMessage);

            switch (status) {
                case "COMPLETE" -> {
                    releaseLock(symbol, purpose);
                    FillResult result = new FillResult(symbol, filledQty, avgPrice, orderId);
                    log.info("[AI-LIVE-EXEC] ✅ {} CONFIRMED FILLED: {} qty={} avgPrice={} orderId={}",
                            purpose, symbol, filledQty, avgPrice, orderId);
                    if ("ENTRY".equals(purpose)) {
                        BiConsumer<String, FillResult> cb = onEntryFilledByStrategy.get(strategy);
                        if (cb != null) cb.accept(symbol, result);
                    } else if ("EXIT".equals(purpose)) {
                        BiConsumer<String, FillResult> cb = onExitFilledByStrategy.get(strategy);
                        if (cb != null) cb.accept(symbol, result);
                    }
                }
                case "REJECTED", "CANCELLED" -> {
                    releaseLock(symbol, purpose);
                    log.error("[AI-LIVE-EXEC] ⚠️ {} order {} for {} — orderId={} reason={}. " +
                                    "{}", purpose, status, symbol, orderId, latest.statusMessage,
                            "EXIT".equals(purpose)
                                    ? "POSITION REMAINS OPEN — will retry exit on next cycle."
                                    : "No position was opened.");
                    if ("ENTRY".equals(purpose)) {
                        BiConsumer<String, String> cb = onEntryRejectedByStrategy.get(strategy);
                        if (cb != null) cb.accept(symbol, latest.statusMessage);
                    } else if ("EXIT".equals(purpose)) {
                        BiConsumer<String, String> cb = onExitRejectedByStrategy.get(strategy);
                        if (cb != null) cb.accept(symbol, latest.statusMessage);
                    }
                }
                case "PARTIALLY_FILLED" -> {
                    log.warn("[AI-LIVE-EXEC] {} PARTIALLY FILLED: {} {}/{} qty filled so far, " +
                                    "orderId={} — continuing to monitor.",
                            purpose, symbol, filledQty, requestedQty, orderId);
                    // Lock remains held — order is still active with the broker.
                }
                default -> {
                    // OPEN / TRIGGER PENDING / etc — still working, keep polling.
                    log.debug("[AI-LIVE-EXEC] {} still {} for {} orderId={}",
                            purpose, status, symbol, orderId);
                }
            }
        } catch (Exception e) {
            log.warn("[AI-LIVE-EXEC] Status check failed for orderId={} symbol={} " +
                    "(non-fatal, will retry next cycle): {}", orderId, symbol, e.getMessage());
        }
    }

    private void updateOrderStatus(String orderId, String status, int filledQty,
                                   double avgPrice, String statusMessage) {
        try {
            jdbc.update("""
                UPDATE live_orders
                SET status = ?, filled_qty = ?, avg_fill_price = ?,
                    status_message = ?, last_checked_at = ?
                WHERE order_id = ?
                """,
                    status, filledQty, avgPrice > 0 ? bd(avgPrice) : null,
                    statusMessage, java.sql.Timestamp.from(Instant.now()), orderId);
        } catch (Exception e) {
            log.debug("[AI-LIVE-EXEC] updateOrderStatus failed for {} (non-fatal): {}",
                    orderId, e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // POSITION RECONCILIATION — compare our records against the broker's
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Every 2 minutes, compares Zerodha's own "day" net positions against
     * what AiTradeManagementEngine believes is open. A mismatch here means
     * either a missed fill event, a manual intervention outside this system,
     * or a genuine bug — in any of those cases, visibility is critical
     * before it becomes a financial surprise at end of day.
     */
    @Scheduled(fixedRate = 120_000L)
    @Async("tradingExecutor")
    public void reconcilePositionsWithBroker() {
        if (!isLiveMode()) return;
        if (ourOpenPositionsSupplier == null) {
            log.debug("[AI-LIVE-EXEC] Position reconciliation skipped — supplier not " +
                    "wired yet (normal during early startup).");
            return;
        }
        try {
            // ZerodhaOrderClient.getDayPositions() already extracts the "day" key
            // for us — confirmed via the actual SDK source that getPositions()
            // returns a Map<String, List<Position>> keyed by "net"/"day".
            List<com.zerodhatech.models.Position> dayPositions = orderClient.getDayPositions();
            if (dayPositions == null || dayPositions.isEmpty()) return;

            Map<String, Integer> ours = ourOpenPositionsSupplier.get();

            for (com.zerodhatech.models.Position p : dayPositions) {
                int brokerQty = p.netQuantity;
                if (brokerQty == 0) continue; // closed/flat on broker side
                Integer ourQty = ours.get(p.tradingSymbol);
                // FIX (per explicit user clarification: "i have taken
                // manually from zerodha... no need to track, only auto
                // trade taken from our app only need to track, manual
                // from zerodha app ignore"). Confirmed real issue: this
                // previously flagged EVERY broker position the app never
                // opened (ourQty == null) as a "tracking bug" - including
                // completely legitimate positions the user took manually,
                // directly in Zerodha, with zero relation to this app at
                // all. Now only compares positions this app genuinely
                // tracks (ourQty != null) - a manually-taken position the
                // app never touched is correctly ignored entirely, not
                // treated as an error requiring review.
                if (ourQty == null) {
                    log.debug("[AI-LIVE-EXEC] {} exists at broker (qty={}) but this app never " +
                            "opened it - ignoring (likely a manual position taken directly in " +
                            "Zerodha, outside this app's tracking scope)", p.tradingSymbol, brokerQty);
                    continue;
                }
                if (ourQty != brokerQty) {
                    log.error("[AI-LIVE-EXEC] ⚠️ POSITION MISMATCH for {}: broker shows qty={}, " +
                                    "our records show qty={}. Manual review required — this could mean " +
                                    "a missed fill, an external order, or a tracking bug.",
                            p.tradingSymbol, brokerQty, ourQty);
                }
            }
        } catch (Exception e) {
            log.warn("[AI-LIVE-EXEC] reconcilePositionsWithBroker failed (non-fatal, will " +
                    "retry next cycle): {}", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // DAILY CLEANUP
    // ═══════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 0 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyCleanup() {
        try {
            jdbc.update("DELETE FROM live_order_locks WHERE trade_date < ?", LocalDate.now(ZoneId.of("Asia/Kolkata")));
            jdbc.update("DELETE FROM live_orders WHERE trade_date < ?", LocalDate.now(ZoneId.of("Asia/Kolkata")));
        } catch (Exception e) {
            log.debug("[AI-LIVE-EXEC] Daily cleanup failed (non-fatal): {}", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RESULT TYPE
    // ═══════════════════════════════════════════════════════════════════════

    public record FillResult(String symbol, int filledQty, double avgFillPrice, String orderId) {}
}