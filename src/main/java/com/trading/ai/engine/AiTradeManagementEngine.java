package com.trading.ai.engine;

import com.trading.ai.model.AiTradeDecision;
import com.trading.ai.model.AiTradeOutcome;
import com.trading.domain.entity.Trade;
import com.trading.domain.enums.TradeDirection;
import com.trading.marketdata.service.MarketDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * AiTradeManagementEngine
 *
 * Monitors all open AI positions on every 1-minute candle.
 * Handles: SL hit, T1 hit (breakeven), T2 hit, trailing SL, EOD exit.
 *
 * TRADE STATES:
 *   OPEN          → waiting for T1 or SL
 *   T1_REACHED    → SL moved to breakeven, trailing active
 *   CLOSED        → position exited, outcome recorded
 *
 * FULLY INDEPENDENT:
 *   No imports from highrr, smc, or news packages.
 *
 * PERSISTENCE — ADDED:
 *   openPositions was previously pure in-memory (ConcurrentHashMap), wiped
 *   on every JVM restart. A mid-market-hours redeploy with an open position
 *   would silently lose all trailing-SL/T1/T2 tracking for that position —
 *   confirmed via full pipeline trace, zero recovery mechanism existed.
 *   Now persisted to ai_open_positions (MySQL) on every state change, and
 *   reconciled back into openPositions on startup via reconcileFromDatabase().
 *   NOTE: this only covers AiTradeManagementEngine's own tracking (used for
 *   the learning callback and the AI's concurrent-position gate). AI trades
 *   are also routed through a separate "platform pipeline" event
 *   (SmartChannelPullbackSignalEvent) for Overview/Trades/Portfolio display
 *   — that listener's own persistence is outside this file and was not
 *   independently verified here; worth checking separately.
 */
@Service
@ConditionalOnProperty(name = "ai.trading.enabled", havingValue = "true")
@Slf4j
public class AiTradeManagementEngine {

    private final MarketDataService marketData;
    private final JdbcTemplate       jdbc;
    private final com.trading.ai.execution.AiLiveOrderExecutionService liveOrderService;
    private final com.trading.ai.execution.AiNewsCapitalLedger         capitalLedger;

    @org.springframework.beans.factory.annotation.Value("${trading.mode:PAPER}")
    private String tradingMode;

    private boolean isLiveMode() { return "LIVE".equalsIgnoreCase(tradingMode); }

    // ── Open positions: symbol → position ────────────────────────────────
    private final Map<String, AiPosition> openPositions = new ConcurrentHashMap<>();

    // ── Outcome callback — wired to AiLearningEngine ──────────────────────
    private Consumer<AiTradeOutcome> onClosedCallback;

    // ── EOD exit time ─────────────────────────────────────────────────────
    private static final LocalTime EOD_EXIT_TIME = LocalTime.of(15, 15); // standardized with News

    // ── Trailing SL distance ──────────────────────────────────────────────
    private static final double TRAIL_PCT = 0.005; // 0.5% trailing

    public AiTradeManagementEngine(MarketDataService marketData, JdbcTemplate jdbc,
                                   com.trading.ai.execution.AiLiveOrderExecutionService liveOrderService,
                                   com.trading.ai.execution.AiNewsCapitalLedger capitalLedger) {
        this.marketData       = marketData;
        this.jdbc              = jdbc;
        this.liveOrderService  = liveOrderService;
        this.capitalLedger     = capitalLedger;
        ensureTableExists();
        // Wire LIVE-mode fill/rejection callbacks once, at construction.
        // PAPER mode never triggers these — liveOrderService's own methods
        // are simply never called when trading.mode=PAPER, so this wiring
        // is harmless and inert in that case.
        liveOrderService.setOnExitFilled("AI_TRADING_V2", this::onLiveExitFilled);
        liveOrderService.setOnExitRejected("AI_TRADING_V2", this::onLiveExitRejected);
        liveOrderService.setOurOpenPositionsSupplier(() -> {
            Map<String, Integer> qtyMap = new java.util.HashMap<>();
            openPositions.forEach((sym, pos) -> qtyMap.put(sym, pos.trade.getQuantity()));
            return qtyMap;
        });
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PERSISTENCE — table auto-create, persist/remove/reconcile
    // All DB operations are best-effort: a DB hiccup never blocks or crashes
    // actual trade management — in-memory openPositions remains the source
    // of truth during the live session, the DB is purely a restart-recovery
    // safety net.
    // ═══════════════════════════════════════════════════════════════════════

    private void ensureTableExists() {
        try {
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ai_open_positions (
                    symbol            VARCHAR(20)  PRIMARY KEY,
                    trade_date        DATE         NOT NULL,
                    direction         VARCHAR(5)    NOT NULL,
                    instrument_token  BIGINT,
                    entry_price       DECIMAL(12,2) NOT NULL,
                    quantity          INT           NOT NULL,
                    original_sl       DECIMAL(12,2) NOT NULL,
                    current_sl        DECIMAL(12,2) NOT NULL,
                    target1           DECIMAL(12,2) NOT NULL,
                    target2           DECIMAL(12,2),
                    t1_reached        BOOLEAN       NOT NULL DEFAULT FALSE,
                    t2_reached        BOOLEAN       NOT NULL DEFAULT FALSE,
                    entry_time        TIMESTAMP     NOT NULL,
                    confidence        DOUBLE,
                    quality_score     INT,
                    reasoning         TEXT,
                    dominant_factor   VARCHAR(150),
                    strategy_name     VARCHAR(50),
                    probability_score INT,
                    updated_at        TIMESTAMP     NOT NULL
                )
                """);
        } catch (Exception e) {
            log.warn("[AI-MGMT] Could not create ai_open_positions table — " +
                    "persistence disabled this session, in-memory tracking " +
                    "still works normally: {}", e.getMessage());
        }
    }

    private void persistPosition(AiPosition pos) {
        try {
            Trade t = pos.trade;
            jdbc.update("""
                INSERT INTO ai_open_positions
                  (symbol, trade_date, direction, instrument_token, entry_price,
                   quantity, original_sl, current_sl, target1, target2,
                   t1_reached, t2_reached, entry_time, confidence, quality_score,
                   reasoning, dominant_factor, strategy_name, probability_score,
                   updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE
                  current_sl = ?, t1_reached = ?, t2_reached = ?, updated_at = ?
                """,
                    t.getTradingSymbol(), LocalDate.now(), t.getDirection().name(),
                    t.getInstrumentToken(), bd(pos.entry, 2), t.getQuantity(),
                    bd(pos.originalSl, 2), bd(pos.currentSl, 2),
                    pos.decision.getTarget1(), pos.decision.getTarget2(),
                    pos.t1Reached, pos.t2Reached, Timestamp(t.getEntryTime()),
                    pos.decision.getConfidence(), pos.decision.getTradeQualityScore(),
                    pos.decision.getReasoning(), pos.decision.getDominantFactor(),
                    t.getStrategyName(), (int) (pos.decision.getProbabilityOfSuccess() * 100),
                    Timestamp(Instant.now()),
                    // ON DUPLICATE KEY UPDATE params:
                    bd(pos.currentSl, 2), pos.t1Reached, pos.t2Reached, Timestamp(Instant.now()));
        } catch (Exception e) {
            log.debug("[AI-MGMT] persistPosition failed for {} (non-fatal): {}",
                    pos.trade.getTradingSymbol(), e.getMessage());
        }
    }

    private void removePersistedPosition(String symbol) {
        try {
            jdbc.update("DELETE FROM ai_open_positions WHERE symbol = ?", symbol);
        } catch (Exception e) {
            log.debug("[AI-MGMT] removePersistedPosition failed for {} (non-fatal): {}",
                    symbol, e.getMessage());
        }
    }

    /**
     * Called once from AiTradingSystem.init() on startup. Rebuilds openPositions
     * from the database so a mid-market restart resumes exactly where it left
     * off, instead of starting with an empty map while real positions are
     * still open. Reconstructs a minimal but functionally complete Trade +
     * AiTradeDecision pair sufficient for onCandle()/close() to keep managing
     * the position correctly (SL trail, T1/T2, EOD exit, learning callback).
     */
    public void reconcileFromDatabase() {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT * FROM ai_open_positions WHERE trade_date = ?", LocalDate.now());
            if (rows.isEmpty()) {
                log.info("[AI-MGMT] Reconciliation: no open positions found in database.");
                return;
            }
            for (Map<String, Object> row : rows) {
                String symbol = (String) row.get("symbol");
                Trade trade = Trade.builder()
                        .tradeDate(LocalDate.now())
                        .tradingSymbol(symbol)
                        .instrumentToken(row.get("instrument_token") != null
                                ? ((Number) row.get("instrument_token")).longValue() : 0L)
                        .direction(TradeDirection.valueOf((String) row.get("direction")))
                        .status("OPEN")
                        .entryTime(((java.sql.Timestamp) row.get("entry_time")).toInstant())
                        .entryPrice((BigDecimal) row.get("entry_price"))
                        .quantity(((Number) row.get("quantity")).intValue())
                        .stopLoss((BigDecimal) row.get("original_sl"))
                        .target((BigDecimal) row.get("target1"))
                        .strategyName((String) row.get("strategy_name"))
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();

                AiTradeDecision decision = AiTradeDecision.builder()
                        .symbol(symbol)
                        .direction((String) row.get("direction"))
                        .entryPrice((BigDecimal) row.get("entry_price"))
                        .stopLoss((BigDecimal) row.get("original_sl"))
                        .target1((BigDecimal) row.get("target1"))
                        .target2((BigDecimal) row.get("target2"))
                        .positionSize(((Number) row.get("quantity")).intValue())
                        .probabilityOfSuccess(row.get("probability_score") != null
                                ? ((Number) row.get("probability_score")).doubleValue() / 100.0 : 0.5)
                        .expectedRR(2.0).expectedReturn(0)
                        .confidence(row.get("confidence") != null
                                ? ((Number) row.get("confidence")).doubleValue() : 0.5)
                        .rrRatio(2.0)
                        .tradeQualityScore(row.get("quality_score") != null
                                ? ((Number) row.get("quality_score")).intValue() : 50)
                        .opportunityScore(50).riskScore(50)
                        .reasoning((String) row.get("reasoning"))
                        .bullScenario("").bearScenario("")
                        .dominantFactor((String) row.get("dominant_factor"))
                        .exitPlan("Restored from database after restart")
                        .reasoningSummary("Restored after restart")
                        .htfTrend("").sector("")
                        .numericPreScore(50)
                        .featureVector(null)
                        .build();

                AiPosition pos = new AiPosition(trade, decision);
                pos.currentSl  = ((BigDecimal) row.get("current_sl")).doubleValue();
                pos.t1Reached  = Boolean.TRUE.equals(row.get("t1_reached"));
                pos.t2Reached  = Boolean.TRUE.equals(row.get("t2_reached"));

                openPositions.put(symbol, pos);
                log.info("[AI-MGMT] Reconciled open position from database: {} {} qty={} " +
                                "entry={} currentSl={} t1Reached={} t2Reached={}",
                        symbol, trade.getDirection(), trade.getQuantity(),
                        pos.entry, pos.currentSl, pos.t1Reached, pos.t2Reached);
            }
            log.info("[AI-MGMT] ✅ Reconciliation complete — {} position(s) restored", rows.size());
        } catch (Exception e) {
            log.warn("[AI-MGMT] reconcileFromDatabase failed — starting with empty " +
                    "openPositions as before this fix existed: {}", e.getMessage());
        }
    }

    private static java.sql.Timestamp Timestamp(Instant instant) {
        return java.sql.Timestamp.from(instant);
    }

    public void setOnClosedCallback(Consumer<AiTradeOutcome> callback) {
        this.onClosedCallback = callback;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // REGISTER TRADE — called when AI executes a new trade
    // ═══════════════════════════════════════════════════════════════════════

    public void registerTrade(Trade trade, AiTradeDecision decision) {
        AiPosition pos = new AiPosition(trade, decision);
        openPositions.put(trade.getTradingSymbol(), pos);
        persistPosition(pos);
        log.info("[AI-MGMT] Registered: {} {} | SL={} T1={} T2={}",
                trade.getTradingSymbol(), trade.getDirection(),
                decision.getStopLoss(), decision.getTarget1(), decision.getTarget2());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PER-SYMBOL 1-MINUTE UPDATE — called on every 1m candle close
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * FIX (latency): SL/T1/T2 HIT checks now run on every tick, not just
     * once per 1-minute candle. This mirrors NewsTradeManagementEngine's
     * already-proven onTick()/manageTrade() pattern exactly — News has had
     * tick-speed exit monitoring since it was built; AI's exit checks were
     * previously gated behind onMinuteCandle(), meaning a position could go
     * up to 59 seconds without a single SL/target check. In a fast move —
     * exactly the kind that triggers a stop-loss — that gap is where
     * realized risk drifts above intended risk (this is the root cause
     * traced earlier: 1.0% intended SL becoming 1.2%+ realized).
     *
     * Only the HIT checks moved here. The gradual trailing-SL distance
     * update stays on onCandle() below — trailing doesn't need tick
     * urgency, and keeping it there avoids extra DB writes from tick-level
     * micro-movement noise. This is the exact same split already used in
     * NewsTradeManagementEngine (onTick for hits, onCandle for trail).
     */
    @org.springframework.context.event.EventListener
    @org.springframework.scheduling.annotation.Async("tickExecutor")
    public void onTick(com.trading.events.TickReceivedEvent tick) {
        String symbol = tick.getTradingSymbol();
        AiPosition pos = openPositions.get(symbol);
        if (pos == null || pos.exitOrderPending.get()) return;

        BigDecimal ltpBD = tick.getLastTradedPrice();
        if (ltpBD == null) return;
        double ltp = ltpBD.doubleValue();
        if (ltp <= 0) return;

        manageTradeHitChecks(symbol, pos, ltp);
    }

    /**
     * SL / T1 / T2 hit detection — extracted from the old onCandle() body,
     * now called from onTick() for tick-speed reaction. Pure hit-detection
     * only; the continuous trailing-distance update remains in onCandle().
     */
    private void manageTradeHitChecks(String symbol, AiPosition pos, double ltp) {
        boolean isLong = pos.trade.getDirection() == TradeDirection.LONG;
        double sl  = pos.currentSl;
        double t1  = pos.decision.getTarget1().doubleValue();
        double t2  = pos.decision.getTarget2().doubleValue();

        boolean t1Before = pos.t1Reached;
        boolean t2Before = pos.t2Reached;

        // ── SL hit check ─────────────────────────────────────────────────
        if ((isLong && ltp <= sl) || (!isLong && ltp >= sl)) {
            String reason = pos.t2Reached ? "TRAIL_HIT_T2"
                    : pos.t1Reached ? "TRAIL_HIT_T1"
                    : "SL_HIT";
            close(symbol, ltp, reason);
            return;
        }

        // ── T1 hit → SL moves to EXACTLY T1, trail 0.5% from here ──────
        if (!pos.t1Reached) {
            if ((isLong && ltp >= t1) || (!isLong && ltp <= t1)) {
                pos.t1Reached = true;
                pos.currentSl = t1;
                log.info("[AI-MGMT] {} T1 HIT @ {} → SL = T1 exactly {} — trailing 0.5%",
                        symbol,
                        String.format("%.2f", ltp),
                        String.format("%.2f", t1));
            }
        }

        // ── T2 hit → SL moves to EXACTLY T2, trail 0.3% from here ──────
        if (pos.t1Reached && !pos.t2Reached) {
            if ((isLong && ltp >= t2) || (!isLong && ltp <= t2)) {
                pos.t2Reached = true;
                pos.currentSl = t2;
                log.info("[AI-MGMT] {} T2 HIT @ {} → SL = T2 exactly {} — trailing 0.3%",
                        symbol,
                        String.format("%.2f", ltp),
                        String.format("%.2f", t2));
            }
        }

        // Persist ONLY if T1/T2/SL state actually changed this tick —
        // keeps DB writes proportional to real events, not the tick rate.
        if (pos.t1Reached != t1Before || pos.t2Reached != t2Before) {
            persistPosition(pos);
        }
    }

    /**
     * Continuous trailing-SL distance update — unchanged from before,
     * still runs once per 1-minute candle. SL/T1/T2 HIT detection has
     * moved to onTick() above; this method now only adjusts how far the
     * SL trails once T1 has already been reached.
     */
    public void onCandle(String symbol) {
        AiPosition pos = openPositions.get(symbol);
        if (pos == null) return;

        // LIVE MODE: an exit order is already in flight for this position —
        // skip re-evaluating entirely. The position will be properly
        // closed once onLiveExitFilled() fires from AiLiveOrderExecutionService.
        if (pos.exitOrderPending.get()) return;

        if (!pos.t1Reached) return; // trailing only matters after T1

        Map<String, BigDecimal> prices = marketData.getLastPricesSimple();
        BigDecimal ltpBD = prices.get(symbol);
        if (ltpBD == null) return;
        double ltp = ltpBD.doubleValue();
        if (ltp <= 0) return;

        boolean isLong = pos.trade.getDirection() == TradeDirection.LONG;
        double slBefore = pos.currentSl;

        // ── Continuous trailing SL ────────────────────────────────────────
        // After T1: trail 0.5% below/above current price
        // After T2: trail 0.3% (tighter — let winner run)
        // Trail ONLY moves in profit direction — never reverses
        // SL floor: T1 after T1 hit, T2 after T2 hit
        double trailPct = pos.t2Reached ? 0.003 : TRAIL_PCT;
        double trail = isLong
                ? ltp * (1 - trailPct)
                : ltp * (1 + trailPct);
        // Only move SL up (LONG) or down (SHORT) — never reverse
        if ((isLong && trail > pos.currentSl) || (!isLong && trail < pos.currentSl)) {
            pos.currentSl = trail;
            log.debug("[AI-MGMT] {} Trail SL → {} ({})",
                    symbol,
                    String.format("%.2f", trail),
                    pos.t2Reached ? "0.3% after T2" : "0.5% after T1");
        }

        // Persist ONLY if SL actually moved this candle — keeps DB writes
        // proportional to real trail events, not the 1-min poll rate.
        if (pos.currentSl != slBefore) {
            persistPosition(pos);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // EOD EXIT — 15:05 IST every trading day
    // ═══════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 15 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void eodExit() {
        if (openPositions.isEmpty()) return;
        log.info("[AI-MGMT] EOD exit — closing {} positions", openPositions.size());
        Map<String, BigDecimal> prices = marketData.getLastPricesSimple();
        new ArrayList<>(openPositions.keySet()).forEach(symbol -> {
            BigDecimal ltpBD = prices.get(symbol);
            if (ltpBD != null) close(symbol, ltpBD.doubleValue(), "AI_EOD");
        });
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CLOSE POSITION
    // ═══════════════════════════════════════════════════════════════════════

    public void close(String symbol, double exitPrice, String reason) {
        AiPosition pos = openPositions.get(symbol);
        if (pos == null) return;

        if (isLiveMode()) {
            // LIVE MODE: do NOT remove from openPositions or record an outcome
            // yet — we don't actually know the REAL exit price until the
            // broker confirms the fill. Mark exit-pending (in-memory fast
            // path) and place a real exit order. The actual close happens in
            // onLiveExitFilled() once AiLiveOrderExecutionService confirms
            // the fill via real broker order status.
            //
            // compareAndSet(false, true) atomically checks-and-sets in ONE
            // operation — this is the actual fix for the race a plain
            // "if (flag) return; flag = true;" pattern has: two threads
            // (e.g. two near-simultaneous ticks on separate tickExecutor
            // threads) could otherwise both pass the check before either
            // sets it, both proceeding to place a real exit order.
            if (!pos.exitOrderPending.compareAndSet(false, true)) return; // already in flight — no-op
            pos.pendingExitReason = reason;

            boolean isLong = pos.trade.getDirection() == TradeDirection.LONG;
            String orderId = liveOrderService.placeExitOrder(
                    symbol, isLong, pos.trade.getQuantity(), exitPrice,
                    pos.trade.getStrategyName(), reason);

            if (orderId == null) {
                // Placement failed or was blocked — un-pend so the NEXT
                // onCandle() cycle gets a chance to retry the exit decision.
                // (If it was blocked because a lock was already held — e.g.
                // an earlier attempt's order is genuinely still in flight at
                // the broker — this correctly leaves exitOrderPending=true
                // via that earlier attempt's own state, not this one.)
                pos.exitOrderPending.set(false);
                pos.pendingExitReason = null;
                log.error("[AI-MGMT] LIVE exit order placement did not succeed for {} " +
                                "({}) — position remains open, will retry next cycle.",
                        symbol, reason);
            }
            return;
        }

        // PAPER MODE — unchanged, exact original behaviour.
        actuallyClosePosition(symbol, exitPrice, reason);
    }

    /**
     * The REAL close logic — removes the position, computes P&L/R-multiple,
     * updates the Trade entity, fires the learning callback. Called directly
     * by close() in PAPER mode (immediate simulated fill), or by
     * onLiveExitFilled() below once a LIVE exit order's fill is confirmed by
     * the broker (using the ACTUAL average fill price, not the originally
     * intended exitPrice).
     */
    private void actuallyClosePosition(String symbol, double exitPrice, String reason) {
        AiPosition pos = openPositions.remove(symbol);
        if (pos == null) return;
        removePersistedPosition(symbol);

        Trade trade = pos.trade;
        boolean isLong = trade.getDirection() == TradeDirection.LONG;
        double entry   = pos.entry;

        // Compute P&L
        double pnlPer = isLong ? exitPrice - entry : entry - exitPrice;
        double totalPnl = pnlPer * trade.getQuantity();
        // Use ORIGINAL SL (not current trailed SL) for R calculation
        // currentSl changes during the trade — originalSl is fixed at entry
        double slDist   = Math.abs(entry - pos.originalSl);
        double rMultiple = slDist > 0 ? pnlPer / slDist : 0;

        // Update Trade entity
        trade.setStatus("CLOSED");
        trade.setExitPrice(bd(exitPrice, 2));
        trade.setExitTime(Instant.now());
        trade.setExitReason(reason);
        trade.setNetPnl(bd(totalPnl, 2));
        trade.setUpdatedAt(Instant.now());

        String outcomeType = rMultiple >= 1.0 ? "WIN"
                : rMultiple >= 0 ? "BREAKEVEN" : "LOSS";

        log.info("[AI-MGMT] CLOSED: {} {} @ {} | R={} P&L=₹{} reason={}",
                symbol, trade.getDirection(), exitPrice, rMultiple, totalPnl, reason);

        // INDEPENDENCE: credit/debit the AI/News-only ledger directly —
        // releases the margin that was reserved at entry and records the
        // realised P&L, with zero dependency on PaperAccount or any other
        // strategy's accounting.
        capitalLedger.recordExit(symbol, trade.getStrategyName(),
                BigDecimal.valueOf(entry).multiply(BigDecimal.valueOf(trade.getQuantity())),
                bd(totalPnl, 2), outcomeType.equals("WIN"));

        // Build outcome for learning engine
        AiTradeOutcome outcome = AiTradeOutcome.builder()
                .symbol(symbol)
                .direction(isLong ? "LONG" : "SHORT")
                .entryPrice(bd(entry, 2))
                .exitPrice(bd(exitPrice, 2))
                .pnl(bd(totalPnl, 2))
                .rMultiple(rMultiple)
                .exitReason(reason)
                .outcomeType(outcomeType)
                .confidence(pos.decision.getConfidence())
                .qualityScore(pos.decision.getTradeQualityScore())
                .reasoning(pos.decision.getReasoning())
                .dominantFactor(pos.decision.getDominantFactor())
                .featureVectorAtEntry(pos.decision.getFeatureVector() != null
                        ? pos.decision.getFeatureVector().getFeatures() : null)
                .featureVectorJson("[]")
                .entryTime(trade.getEntryTime())
                .exitTime(Instant.now())
                .regime("UNKNOWN") // filled by caller
                .build();

        // Fire learning callback
        if (onClosedCallback != null) {
            try { onClosedCallback.accept(outcome); }
            catch (Exception e) { log.debug("[AI-MGMT] Learning callback error: {}", e.getMessage()); }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LIVE MODE CALLBACKS — wired to AiLiveOrderExecutionService in the
    // constructor. Never invoked in PAPER mode.
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Fired when AiLiveOrderExecutionService confirms an EXIT order is
     * COMPLETE (real broker fill, not inferred). Uses the ACTUAL average
     * fill price for P&L/R-multiple — not the price that was true at the
     * moment the exit decision was made, which may differ slightly due to
     * slippage between decision and fill.
     */
    private void onLiveExitFilled(String symbol, com.trading.ai.execution.AiLiveOrderExecutionService.FillResult fill) {
        AiPosition pos = openPositions.get(symbol);
        String reason = pos != null && pos.pendingExitReason != null
                ? pos.pendingExitReason : "LIVE_EXIT";
        log.info("[AI-MGMT] LIVE exit fill confirmed: {} avgPrice={} qty={} reason={}",
                symbol, fill.avgFillPrice(), fill.filledQty(), reason);
        actuallyClosePosition(symbol, fill.avgFillPrice(), reason);
    }

    /**
     * Fired when AiLiveOrderExecutionService confirms an EXIT order was
     * REJECTED or CANCELLED by the broker/exchange. The position is still
     * genuinely open at the broker in this case — clear exitOrderPending so
     * the next onCandle() cycle retries the exit decision.
     */
    private void onLiveExitRejected(String symbol, String statusMessage) {
        AiPosition pos = openPositions.get(symbol);
        if (pos == null) return;
        log.error("[AI-MGMT] ⚠️ LIVE exit order was rejected/cancelled for {} — position " +
                        "remains open. Reason: {}. Clearing exit-pending flag so the next cycle retries.",
                symbol, statusMessage);
        pos.exitOrderPending.set(false);
        pos.pendingExitReason = null;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // DAILY RESET — 9:10 AM every trading day
    // FIX: Was missing entirely — caused ghost positions to survive midnight
    // and block the next trading session.
    // Clears all open positions at session start.
    // Safe because EOD exit at 15:05 closes all positions the day before.
    // ═══════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyReset() {
        int count = openPositions.size();
        if (count > 0) {
            log.warn("[AI-MGMT] Daily reset clearing {} stale positions (EOD exit may have failed)",
                    count);
            openPositions.clear();
        }
        // Clear yesterday's (or any stale) persisted rows so they never get
        // reconciled into today's session by mistake.
        try {
            jdbc.update("DELETE FROM ai_open_positions WHERE trade_date < ?", LocalDate.now());
        } catch (Exception e) {
            log.debug("[AI-MGMT] Daily DB cleanup failed (non-fatal): {}", e.getMessage());
        }
        log.info("[AI-MGMT] Daily reset complete — positions cleared");
    }

    /**
     * Force-clear all positions.
     * Called by AiTradingSystem.dailyReset() as a safety net in addition
     * to the scheduled reset above.
     */
    public void clearPositions() {
        openPositions.clear();
        try {
            jdbc.update("DELETE FROM ai_open_positions WHERE trade_date = ?", LocalDate.now());
        } catch (Exception e) {
            log.debug("[AI-MGMT] clearPositions DB cleanup failed (non-fatal): {}", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ACCESSORS
    // ═══════════════════════════════════════════════════════════════════════

    public Map<String, AiPosition> getOpenPositions() {
        return Collections.unmodifiableMap(openPositions);
    }

    public boolean hasPosition(String symbol) {
        return openPositions.containsKey(symbol);
    }

    public int getOpenCount() { return openPositions.size(); }

    private BigDecimal bd(double v, int scale) {
        return BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // POSITION STATE
    // ═══════════════════════════════════════════════════════════════════════

    public static class AiPosition {
        public final Trade           trade;
        public final AiTradeDecision decision;
        public final double          entry;
        public final double          originalSl;  // never changes — used for R calculation
        public volatile double       currentSl;
        public volatile boolean      t1Reached  = false;
        public volatile boolean      t2Reached  = false; // T2 hit → trail tightly

        // LIVE MODE: true from the moment an exit order has been placed with
        // the broker until its fill is confirmed. While true, onCandle() skips
        // re-evaluating SL/T1/T2 for this position — prevents a second exit
        // decision (and a second exit order) firing while the first is still
        // in flight. NOTE: AiLiveOrderExecutionService's persisted
        // live_order_locks table is a real, DB-level guarantee against a
        // duplicate broker order even across a restart — but relying on
        // that alone for an in-process race is sloppy: a plain volatile
        // boolean's check-then-set is NOT atomic, so two near-simultaneous
        // ticks for the same symbol (each on a separate tickExecutor
        // thread) could both pass the "is it already pending" check
        // before either sets it true. AtomicBoolean.compareAndSet() below
        // closes that race at the source, with the DB lock remaining as
        // genuine defense-in-depth, not the primary guard.
        public final java.util.concurrent.atomic.AtomicBoolean exitOrderPending =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        public volatile String       pendingExitReason = null;

        public AiPosition(Trade trade, AiTradeDecision decision) {
            this.trade      = trade;
            this.decision   = decision;
            this.entry      = trade.getEntryPrice().doubleValue();
            this.originalSl = decision.getStopLoss().doubleValue();
            this.currentSl  = decision.getStopLoss().doubleValue();
        }
    }
}