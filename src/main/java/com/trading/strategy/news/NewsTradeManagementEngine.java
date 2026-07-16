package com.trading.strategy.news;

import com.trading.ai.execution.AiLiveOrderExecutionService;
import com.trading.ai.execution.AiNewsCapitalLedger;
import com.trading.domain.Candle;
import com.trading.domain.entity.Trade;
import com.trading.domain.enums.TradeDirection;
import com.trading.events.CandleCompleteEvent;
import com.trading.events.TickReceivedEvent;
import com.trading.marketdata.service.MarketTimingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NewsTradeManagementEngine
 *
 * INDEPENDENCE: News's own, complete position-management engine — entry
 * registration, SL/target/trailing/partial-exit/market-or-sector-turn exits,
 * EOD force close. Faithfully replicates the EXACT 4-phase model News
 * currently relies on via the shared PaperTradeManagementService (same
 * thresholds, same formulas, same fill-slippage simulation, same brokerage
 * calculation) — by explicit instruction: "whatever logic we have in paper
 * trading, same trailing." This is NOT a simplified or different model; it
 * is the same behavior, just implemented independently so News no longer
 * needs PaperTradeExecutionService / PaperTradeManagementService /
 * SmartChannelPullbackSignalEvent / SmartChannelSignalHandler / the shared
 * RiskManagementService cross-strategy symbol check — all of which belong
 * to the other strategies being permanently removed.
 *
 * 4-PHASE MODEL (identical thresholds to PaperTradeManagementService):
 *   Phase 1 — Fixed SL
 *   Phase 2 — Breakeven at 1.5R (StrategyConfig.Global.breakevenRTrigger)
 *   Phase 3 — ATR trailing at 2.0R (trendTrailTriggerR), 1.0×ATR distance,
 *             tightening to 0.5×ATR after partial exit
 *   Phase 4 — Partial exit (half qty) at 3.0R (partialExitR), or 1.0R
 *             during LUNCH window (partialExitLunchR), skipped entirely
 *             if strongTrend (i.e. NOT during LUNCH/LATE windows)
 *
 * estimatedAtr = |entry - SL| × 2.0 — this is the EXACT formula
 * PaperTradeExecutionService uses; it is not a real market-derived ATR,
 * so no new market-data dependency is introduced by computing it here.
 *
 * SIMPLIFICATION (explicit decision): market-turn and sector-turn exits
 * have been removed — by design, News relies only on SL, target, and a
 * 3:15 PM EOD square-off. That extra filtering was inherited from
 * PaperTradeManagementService's design for longer-holding trend
 * strategies; it had no demonstrated benefit for News's fast,
 * catalyst-driven trades, and risked cutting good trades short on
 * unrelated market noise. MarketTimingService remains — it's still used
 * for entryWindow/strongTrend classification and the LUNCH-window
 * partial-exit threshold, unrelated to the removed exit filters.
 */
@Service
@Slf4j
public class NewsTradeManagementEngine {

    private final MarketTimingService    timing;
    private final JdbcTemplate            jdbc;
    private final AiLiveOrderExecutionService liveOrderService;
    private final AiNewsCapitalLedger         capitalLedger;
    private final com.trading.risk.service.CircuitBreakerService circuitBreaker;

    @Value("${trading.mode:PAPER}")
    private String tradingMode;

    private boolean isLiveMode() { return "LIVE".equalsIgnoreCase(tradingMode); }

    // ── Exact thresholds from StrategyConfig.Global (verified against the
    // real source — see class header) ───────────────────────────────────
    private static final double BREAKEVEN_R_TRIGGER     = 1.5;
    private static final double TREND_TRAIL_TRIGGER_R   = 2.0;
    private static final double PARTIAL_EXIT_R          = 3.0;
    private static final double PARTIAL_EXIT_LUNCH_R    = 1.0;

    // ── Exact @Value defaults from PaperTradeManagementService ───────────
    private static final double TRAIL_ATR_MULTIPLIER       = 1.0;
    private static final double TRAIL_TIGHT_ATR_MULTIPLIER = 0.5;
    private static final boolean SKIP_TRAIL_ON_MOMENTUM     = true;
    // REMOVED: EXIT_ON_MARKET_TURN / EXIT_ON_SECTOR_TURN — by explicit
    // decision, News relies only on SL/target/EOD. Market/sector-turn
    // exits were inherited from PaperTradeManagementService's design for
    // longer-holding trend strategies; unproven benefit for News's fast,
    // catalyst-driven trades, and risked cutting good trades short on
    // unrelated market noise.

    // ── Exact slippage constants ──────────────────────────────────────────
    private static final double SL_SLIP     = 0.001;
    private static final double TARGET_SLIP = 0.0005;
    private static final double EOD_SLIP    = 0.0015;

    // ── NSE 5-paise tick ───────────────────────────────────────────────────
    private static final BigDecimal TICK = new BigDecimal("0.05");

    private final Map<String, ManagedNewsTrade> activeTrades = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal>       lastPrices   = new ConcurrentHashMap<>();

    public NewsTradeManagementEngine(MarketTimingService timing,
                                     JdbcTemplate jdbc,
                                     AiLiveOrderExecutionService liveOrderService,
                                     AiNewsCapitalLedger capitalLedger,
                                     com.trading.risk.service.CircuitBreakerService circuitBreaker) {
        this.timing            = timing;
        this.jdbc              = jdbc;
        this.liveOrderService  = liveOrderService;
        this.capitalLedger     = capitalLedger;
        this.circuitBreaker    = circuitBreaker;
        ensureTableExists();
        liveOrderService.setOnExitFilled("NEWS_CATALYST_V1", this::onLiveExitFilled);
        liveOrderService.setOnExitRejected("NEWS_CATALYST_V1", this::onLiveExitRejected);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ManagedNewsTrade — mirrors PaperTradeManagementService.ManagedTrade
    // ═══════════════════════════════════════════════════════════════════════

    public static class ManagedNewsTrade {
        public final Trade           trade;
        public final BigDecimal      originalSl;
        public final BigDecimal      rDistance;
        public final double          atr;
        public volatile boolean      slAtBreakeven  = false;
        public volatile boolean      trailActive    = false;
        public volatile boolean      halfExited     = false;
        public final int             qty;
        public volatile int          remainingQty;
        public final MarketTimingService.TimeWindow entryWindow;
        public final boolean         strongTrend;
        public final Instant         entryInstant;
        // AtomicBoolean, not volatile boolean — closes the same race
        // AiTradeManagementEngine had: two near-simultaneous ticks for the
        // same symbol (separate tickExecutor threads) could otherwise both
        // pass a plain "if (flag) return; flag = true;" check before
        // either set it. compareAndSet() at the actual close() guard makes
        // this atomic. AiLiveOrderExecutionService's persisted
        // live_order_locks table remains as genuine defense-in-depth.
        public final java.util.concurrent.atomic.AtomicBoolean exitOrderPending =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        public volatile String       pendingExitReason = null;

        public ManagedNewsTrade(Trade trade, double atr,
                                MarketTimingService.TimeWindow entryWindow,
                                boolean strongTrend) {
            this.trade        = trade;
            this.originalSl   = trade.getStopLoss();
            this.rDistance    = trade.getEntryPrice().subtract(trade.getStopLoss()).abs();
            this.atr          = atr;
            this.qty          = trade.getQuantity();
            this.remainingQty = trade.getQuantity();
            this.entryWindow  = entryWindow;
            this.strongTrend  = strongTrend;
            this.entryInstant = Instant.now();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PERSISTENCE — restart-safety, same pattern as AiTradeManagementEngine
    // ═══════════════════════════════════════════════════════════════════════

    private void ensureTableExists() {
        try {
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS news_open_positions (
                    symbol            VARCHAR(20)  PRIMARY KEY,
                    trade_date        DATE         NOT NULL,
                    direction         VARCHAR(5)    NOT NULL,
                    instrument_token  BIGINT,
                    entry_price       DECIMAL(12,2) NOT NULL,
                    quantity          INT           NOT NULL,
                    original_sl       DECIMAL(12,2) NOT NULL,
                    current_sl        DECIMAL(12,2) NOT NULL,
                    target            DECIMAL(12,2) NOT NULL,
                    atr               DOUBLE        NOT NULL,
                    sl_at_breakeven   BOOLEAN       NOT NULL DEFAULT FALSE,
                    trail_active      BOOLEAN       NOT NULL DEFAULT FALSE,
                    half_exited       BOOLEAN       NOT NULL DEFAULT FALSE,
                    remaining_qty     INT           NOT NULL,
                    entry_window      VARCHAR(20),
                    strong_trend      BOOLEAN,
                    entry_time        TIMESTAMP     NOT NULL,
                    updated_at        TIMESTAMP     NOT NULL
                )
                """);
        } catch (Exception e) {
            log.warn("[NEWS-MGMT] Could not create news_open_positions table — " +
                    "persistence disabled this session: {}", e.getMessage());
        }
    }

    private void persistPosition(ManagedNewsTrade mt) {
        try {
            Trade t = mt.trade;
            jdbc.update("""
                INSERT INTO news_open_positions
                  (symbol, trade_date, direction, instrument_token, entry_price,
                   quantity, original_sl, current_sl, target, atr, sl_at_breakeven,
                   trail_active, half_exited, remaining_qty, entry_window,
                   strong_trend, entry_time, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE
                  current_sl = ?, sl_at_breakeven = ?, trail_active = ?,
                  half_exited = ?, remaining_qty = ?, updated_at = ?
                """,
                    t.getTradingSymbol(), LocalDate.now(), t.getDirection().name(),
                    t.getInstrumentToken(), t.getEntryPrice(), t.getQuantity(),
                    mt.originalSl, t.getStopLoss(), t.getTarget(), mt.atr,
                    mt.slAtBreakeven, mt.trailActive, mt.halfExited, mt.remainingQty,
                    mt.entryWindow != null ? mt.entryWindow.name() : null, mt.strongTrend,
                    java.sql.Timestamp.from(mt.entryInstant), java.sql.Timestamp.from(Instant.now()),
                    // ON DUPLICATE KEY UPDATE params:
                    t.getStopLoss(), mt.slAtBreakeven, mt.trailActive, mt.halfExited,
                    mt.remainingQty, java.sql.Timestamp.from(Instant.now()));
        } catch (Exception e) {
            log.debug("[NEWS-MGMT] persistPosition failed for {} (non-fatal): {}",
                    mt.trade.getTradingSymbol(), e.getMessage());
        }
    }

    private void removePersistedPosition(String symbol) {
        try {
            jdbc.update("DELETE FROM news_open_positions WHERE symbol = ?", symbol);
        } catch (Exception e) {
            log.debug("[NEWS-MGMT] removePersistedPosition failed for {} (non-fatal): {}",
                    symbol, e.getMessage());
        }
    }

    /** Called from NewsTradingStrategy at startup to restore today's state. */
    public void reconcileFromDatabase() {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT * FROM news_open_positions WHERE trade_date = ?", LocalDate.now());
            if (rows.isEmpty()) {
                log.info("[NEWS-MGMT] Reconciliation: no open positions found in database.");
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
                        .stopLoss((BigDecimal) row.get("current_sl"))
                        .target((BigDecimal) row.get("target"))
                        .strategyName("NEWS_CATALYST_V1")
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();
                MarketTimingService.TimeWindow window = row.get("entry_window") != null
                        ? MarketTimingService.TimeWindow.valueOf((String) row.get("entry_window"))
                        : null;
                ManagedNewsTrade mt = new ManagedNewsTrade(trade,
                        ((Number) row.get("atr")).doubleValue(), window,
                        Boolean.TRUE.equals(row.get("strong_trend")));
                mt.slAtBreakeven  = Boolean.TRUE.equals(row.get("sl_at_breakeven"));
                mt.trailActive    = Boolean.TRUE.equals(row.get("trail_active"));
                mt.halfExited     = Boolean.TRUE.equals(row.get("half_exited"));
                mt.remainingQty   = ((Number) row.get("remaining_qty")).intValue();
                activeTrades.put(symbol, mt);
                log.info("[NEWS-MGMT] Reconciled open position: {} {} qty={} currentSl={} " +
                                "breakeven={} trailActive={} halfExited={}",
                        symbol, trade.getDirection(), mt.remainingQty, trade.getStopLoss(),
                        mt.slAtBreakeven, mt.trailActive, mt.halfExited);
            }
            log.info("[NEWS-MGMT] ✅ Reconciliation complete — {} position(s) restored", rows.size());
        } catch (Exception e) {
            log.warn("[NEWS-MGMT] reconcileFromDatabase failed — starting with empty " +
                    "activeTrades: {}", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // REGISTER — entry. estimatedAtr computed exactly as
    // PaperTradeExecutionService does: |entry-SL| × 2.0
    // ═══════════════════════════════════════════════════════════════════════

    public void register(Trade trade) {
        double estimatedAtr = trade.getEntryPrice() != null && trade.getStopLoss() != null
                ? trade.getEntryPrice().subtract(trade.getStopLoss()).abs()
                .multiply(BigDecimal.valueOf(2)).doubleValue()
                : 0.0;
        MarketTimingService.TimeWindow window = timing.getCurrentWindow();
        boolean strongTrend = window != MarketTimingService.TimeWindow.LUNCH
                && window != MarketTimingService.TimeWindow.LATE;

        ManagedNewsTrade mt = new ManagedNewsTrade(trade, estimatedAtr, window, strongTrend);
        activeTrades.put(trade.getTradingSymbol(), mt);
        persistPosition(mt);
        // FIX (same confirmed gap as AI's identical fix): recordTradeEntered()
        // was never called anywhere - wired in here, at the exact point a
        // News position genuinely opens.
        circuitBreaker.recordTradeEntered();

        log.info("[NEWS-MGMT] Registered: {} dir={} entry={} sl={} 1R={} atr={} " +
                        "window={} strongTrend={}",
                trade.getTradingSymbol(), trade.getDirection(), trade.getEntryPrice(),
                trade.getStopLoss(), mt.rDistance, String.format("%.2f", estimatedAtr),
                window, strongTrend);
    }

    public int getOpenCount() { return activeTrades.size(); }
    public boolean hasPosition(String symbol) { return activeTrades.containsKey(symbol); }

    // ═══════════════════════════════════════════════════════════════════════
    // TICK-LEVEL MONITORING — Phase 1 (SL/Target) + Phase 4 (partial exit)
    // Exact same structure as PaperTradeManagementService.onTick/manageTrade
    // ═══════════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tickExecutor")
    public void onTick(TickReceivedEvent tick) {
        String     sym = tick.getTradingSymbol();
        BigDecimal ltp = tick.getLastTradedPrice();
        lastPrices.put(sym, ltp);
        manageTrade(sym, ltp);
    }

    private void manageTrade(String sym, BigDecimal ltp) {
        ManagedNewsTrade mt = activeTrades.get(sym);
        if (mt == null || mt.exitOrderPending.get()) return;

        Trade   t     = mt.trade;
        boolean long_ = t.getDirection() == TradeDirection.LONG;

        // Phase 1: SL hit
        if (long_ ? ltp.compareTo(t.getStopLoss()) <= 0
                : ltp.compareTo(t.getStopLoss()) >= 0) {
            BigDecimal slFill = simulateSlFill(t.getStopLoss(), ltp, t.getDirection());
            log.info("[NEWS-MGMT] SL HIT: {} sl={} ltp={} fill={}", sym, t.getStopLoss(), ltp, slFill);
            close(sym, slFill, "STOPLOSS_HIT");
            return;
        }

        // Target hit
        if (long_ ? ltp.compareTo(t.getTarget()) >= 0
                : ltp.compareTo(t.getTarget()) <= 0) {
            BigDecimal targetFill = simulateTargetFill(t.getTarget(), t.getDirection());
            close(sym, targetFill, "TARGET_HIT");
            return;
        }

        // R-multiple
        if (mt.rDistance.compareTo(BigDecimal.ZERO) == 0) return;
        double profit = long_
                ? ltp.subtract(t.getEntryPrice()).doubleValue()
                : t.getEntryPrice().subtract(ltp).doubleValue();
        double rMultiple = profit / mt.rDistance.doubleValue();

        // Phase 2: Breakeven
        if (!mt.slAtBreakeven && rMultiple >= BREAKEVEN_R_TRIGGER) {
            moveSlToBreakeven(sym, mt);
        }

        // Phase 4: Partial exit
        handlePartialExit(sym, mt, ltp, rMultiple);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PHASE 3: Trailing SL — every 5-min candle close. Exact same structure
    // as PaperTradeManagementService.onCandle/updateTrailingSl.
    // ═══════════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tradingExecutor")
    public void onCandle(CandleCompleteEvent event) {
        if (!"5minute".equals(event.getCandle().getTimeframe())) return;
        String sym = event.getCandle().getTradingSymbol();
        ManagedNewsTrade mt = activeTrades.get(sym);
        if (mt == null || mt.exitOrderPending.get()) return;

        if (SKIP_TRAIL_ON_MOMENTUM && isMomentumCandle(event.getCandle())) {
            log.debug("[NEWS-MGMT] Momentum candle {} — skip trailing", sym);
            return;
        }
        updateTrailingSl(sym, mt, event.getCandle().getClose());
    }

    private void updateTrailingSl(String sym, ManagedNewsTrade mt, BigDecimal price) {
        if (!mt.slAtBreakeven) return;

        Trade   t     = mt.trade;
        boolean long_ = t.getDirection() == TradeDirection.LONG;

        double profit = long_
                ? price.subtract(t.getEntryPrice()).doubleValue()
                : t.getEntryPrice().subtract(price).doubleValue();
        double rMultiple = mt.rDistance.doubleValue() > 0 ? profit / mt.rDistance.doubleValue() : 0;

        if (rMultiple < TREND_TRAIL_TRIGGER_R) {
            log.debug("[NEWS-MGMT] Trail inactive {}: {}R < {}R", sym,
                    String.format("%.2f", rMultiple), TREND_TRAIL_TRIGGER_R);
            return;
        }

        if (!mt.trailActive) {
            mt.trailActive = true;
            log.info("[NEWS-MGMT] Phase-3 TRAIL ACTIVATED: {} at {}R",
                    sym, String.format("%.2f", rMultiple));
        }

        double atrMultiplier = mt.halfExited ? TRAIL_TIGHT_ATR_MULTIPLIER : TRAIL_ATR_MULTIPLIER;
        double trailDist = mt.atr * atrMultiplier;
        BigDecimal rawSl = long_
                ? price.subtract(BigDecimal.valueOf(trailDist))
                : price.add(BigDecimal.valueOf(trailDist));
        BigDecimal newSl = alignToTick(rawSl, long_ ? RoundingMode.FLOOR : RoundingMode.CEILING);

        boolean improve = long_
                ? newSl.compareTo(t.getStopLoss()) > 0
                : newSl.compareTo(t.getStopLoss()) < 0;

        if (improve) {
            t.setStopLoss(newSl);
            t.setUpdatedAt(Instant.now());
            persistPosition(mt);
            log.info("[NEWS-MGMT] Trail SL updated: {} newSl={} {}R atrMult={}",
                    sym, newSl, String.format("%.2f", rMultiple), atrMultiplier);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PHASE 4: Partial exit — exact same structure as
    // PaperTradeManagementService.handlePartialExit, including the identical
    // brokerage/STT/exchange/SEBI/GST cost calculation for net P&L accuracy.
    // ═══════════════════════════════════════════════════════════════════════

    private void handlePartialExit(String sym, ManagedNewsTrade mt, BigDecimal ltp, double rMultiple) {
        if (mt.halfExited) return;
        if (mt.remainingQty <= 1) return;

        double halfExitAt = 0;
        if (mt.entryWindow == MarketTimingService.TimeWindow.LUNCH) {
            halfExitAt = PARTIAL_EXIT_LUNCH_R;
        } else if (!mt.strongTrend) {
            halfExitAt = PARTIAL_EXIT_R;
        }
        // strongTrend=true and not LUNCH → halfExitAt stays 0 → partial exit
        // skipped entirely, exactly matching PaperTradeManagementService.

        if (halfExitAt > 0 && rMultiple >= halfExitAt) {
            int halfQty = mt.remainingQty / 2;
            boolean long_ = mt.trade.getDirection() == TradeDirection.LONG;

            BigDecimal rawFill = long_
                    ? ltp.multiply(BigDecimal.valueOf(1.0 - TARGET_SLIP), MathContext.DECIMAL64)
                    : ltp.multiply(BigDecimal.valueOf(1.0 + TARGET_SLIP), MathContext.DECIMAL64);
            BigDecimal partialFill = alignToTick(rawFill, long_ ? RoundingMode.FLOOR : RoundingMode.CEILING);

            BigDecimal entryPrice = mt.trade.getEntryPrice();
            BigDecimal grossPnl = long_
                    ? partialFill.subtract(entryPrice).multiply(BigDecimal.valueOf(halfQty))
                    : entryPrice.subtract(partialFill).multiply(BigDecimal.valueOf(halfQty));

            BigDecimal exitCost = NewsBrokerageCalculator.exitLegCost(
                    partialFill, halfQty, mt.trade.getDirection());
            BigDecimal netPnl = grossPnl.subtract(exitCost);

            // INDEPENDENCE: credits the AI/News-only ledger directly, instead of
            // account.applyPartialPnl() (PaperAccount, shared with other strategies).
            capitalLedger.recordExit(sym, "NEWS_CATALYST_V1", BigDecimal.ZERO,
                    netPnl, netPnl.compareTo(BigDecimal.ZERO) > 0);

            mt.halfExited    = true;
            mt.remainingQty  = mt.remainingQty - halfQty;
            persistPosition(mt);

            log.info("[NEWS-MGMT] Phase-4 PARTIAL EXIT: {} qty={} fill={} ({}R) net={}",
                    sym, halfQty, partialFill, String.format("%.2f", halfExitAt),
                    String.format("%.2f", netPnl.doubleValue()));
        }
    }

    private void moveSlToBreakeven(String sym, ManagedNewsTrade mt) {
        Trade t = mt.trade;
        t.setStopLoss(t.getEntryPrice());
        t.setUpdatedAt(Instant.now());
        mt.slAtBreakeven = true;
        persistPosition(mt);
        log.info("[NEWS-MGMT] Phase-2 BREAKEVEN: {} entry={} triggered at {}R",
                sym, t.getEntryPrice(), BREAKEVEN_R_TRIGGER);
        // INDEPENDENCE: omits riskManagement.notifyPhase2Migration(sym) — that
        // notifies the shared RiskManagementService for OTHER strategies'
        // cross-strategy awareness, not needed for News's own independent logic.
    }

    // ═══════════════════════════════════════════════════════════════════════
    // EOD force close — 3:15 PM, standardized across both AI and News
    // (previously 15:00; AI's EOD also moved to 15:15 to match)
    // ═══════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 15 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void forceCloseAll() {
        if (activeTrades.isEmpty()) return;
        log.warn("[NEWS-MGMT] FORCE CLOSE 15:00 — {} positions", activeTrades.size());
        new ArrayList<>(activeTrades.keySet()).forEach(sym -> {
            ManagedNewsTrade mt = activeTrades.get(sym);
            if (mt == null || mt.exitOrderPending.get()) return;
            BigDecimal ltp = lastPrices.getOrDefault(sym, mt.trade.getEntryPrice());
            BigDecimal eodFill = simulateEodFill(ltp, mt.trade.getDirection());
            close(sym, eodFill, "TIME_EXIT_15:00");
        });
    }

    // FIX (confirmed real bug found from direct user report: NAM-INDIA's
    // trailing stop showed a level implying the target had genuinely
    // been reached, but the position remained stuck ACTIVE rather than
    // exiting). Root cause confirmed via code inspection: SL/target
    // monitoring (manageTrade()) only ever ran inside the event-driven
    // onTick() listener above - there was NO periodic, time-based
    // fallback at all. If a symbol doesn't receive a live tick for a
    // stretch (low liquidity, infrequent trades), the SL/target check
    // simply never runs during that gap, even if the true market price
    // has already crossed the target. This periodic safety-net check
    // reuses the exact same, unmodified manageTrade() method and the
    // already-maintained lastPrices map - it does not duplicate or
    // alter the existing tick-driven logic, it purely adds a fallback
    // so a quiet symbol can never get permanently stuck.
    @Scheduled(fixedRate = 30000)
    public void periodicSafetyCheck() {
        if (activeTrades.isEmpty()) return;
        for (String sym : new ArrayList<>(activeTrades.keySet())) {
            BigDecimal ltp = lastPrices.get(sym);
            if (ltp == null) continue; // no price observed yet at all - nothing to check against
            manageTrade(sym, ltp);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CLOSE — router (LIVE places a real exit order, PAPER closes directly)
    // Same dual-mode pattern as AiTradeManagementEngine.
    // ═══════════════════════════════════════════════════════════════════════

    public void close(String symbol, BigDecimal exitPrice, String reason) {
        ManagedNewsTrade mt = activeTrades.get(symbol);
        if (mt == null) return;

        if (isLiveMode()) {
            // compareAndSet(false, true) atomically checks-and-sets in ONE
            // operation — the actual fix for the race a plain
            // "if (flag) return; flag = true;" pattern has.
            if (!mt.exitOrderPending.compareAndSet(false, true)) return;
            mt.pendingExitReason = reason;

            boolean isLong = mt.trade.getDirection() == TradeDirection.LONG;
            String orderId = liveOrderService.placeExitOrder(
                    symbol, isLong, mt.remainingQty, exitPrice.doubleValue(),
                    "NEWS_CATALYST_V1", reason);

            if (orderId == null) {
                mt.exitOrderPending.set(false);
                mt.pendingExitReason = null;
                log.error("[NEWS-MGMT] LIVE exit order placement did not succeed for {} " +
                        "({}) — position remains open, will retry next cycle.", symbol, reason);
            }
            return;
        }
        actuallyClosePosition(symbol, exitPrice, reason);
    }

    private void actuallyClosePosition(String symbol, BigDecimal exitPrice, String reason) {
        ManagedNewsTrade mt = activeTrades.remove(symbol);
        if (mt == null) return;
        removePersistedPosition(symbol);

        Trade trade = mt.trade;
        boolean isLong = trade.getDirection() == TradeDirection.LONG;
        BigDecimal entryPrice = trade.getEntryPrice();

        BigDecimal grossPnl = isLong
                ? exitPrice.subtract(entryPrice).multiply(BigDecimal.valueOf(mt.remainingQty))
                : entryPrice.subtract(exitPrice).multiply(BigDecimal.valueOf(mt.remainingQty));
        BigDecimal exitCost = NewsBrokerageCalculator.exitLegCost(exitPrice, mt.remainingQty, trade.getDirection());
        BigDecimal netPnl = grossPnl.subtract(exitCost);

        trade.setStatus("CLOSED");
        trade.setExitPrice(exitPrice);
        trade.setExitTime(Instant.now());
        trade.setExitReason(reason);
        trade.setNetPnl(netPnl);
        trade.setUpdatedAt(Instant.now());

        capitalLedger.recordExit(symbol, "NEWS_CATALYST_V1",
                entryPrice.multiply(BigDecimal.valueOf(mt.qty)),
                netPnl, netPnl.compareTo(BigDecimal.ZERO) > 0);

        // FIX (same confirmed gap as AI's identical fix): recordPnl()
        // was never called anywhere - wired in here, at the exact point
        // real, realised P&L is finalized.
        circuitBreaker.recordPnl(netPnl);

        log.info("[NEWS-MGMT] CLOSED: {} {} @ {} | P&L=₹{} reason={}",
                symbol, trade.getDirection(), exitPrice, netPnl, reason);

        if (onClosedCallback != null) {
            try { onClosedCallback.accept(symbol); }
            catch (Exception e) { log.debug("[NEWS-MGMT] onClosed callback error: {}", e.getMessage()); }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LIVE CALLBACKS
    // ═══════════════════════════════════════════════════════════════════════

    private void onLiveExitFilled(String symbol, AiLiveOrderExecutionService.FillResult fill) {
        ManagedNewsTrade mt = activeTrades.get(symbol);
        if (mt == null) return; // belongs to AI, not News — AiTradeManagementEngine handles it
        String reason = mt.pendingExitReason != null ? mt.pendingExitReason : "LIVE_EXIT";
        log.info("[NEWS-MGMT] LIVE exit fill confirmed: {} avgPrice={} qty={} reason={}",
                symbol, fill.avgFillPrice(), fill.filledQty(), reason);
        actuallyClosePosition(symbol, BigDecimal.valueOf(fill.avgFillPrice()), reason);
    }

    private void onLiveExitRejected(String symbol, String statusMessage) {
        ManagedNewsTrade mt = activeTrades.get(symbol);
        if (mt == null) return;
        log.error("[NEWS-MGMT] ⚠️ LIVE exit order rejected/cancelled for {} — position " +
                "remains open. Reason: {}. Clearing exit-pending flag.", symbol, statusMessage);
        mt.exitOrderPending.set(false);
        mt.pendingExitReason = null;
    }

    /**
     * NOTE: AiLiveOrderExecutionService dispatches callbacks keyed by the
     * strategyName persisted with each order (see its class header) — so
     * this handler only ever fires for News's own exit orders, never AI's,
     * even though both strategies share this same execution service. The
     * defensive activeTrades.get(symbol)==null check below remains as a
     * safety net, not as the primary correctness mechanism.
     */
    private java.util.function.Consumer<String> onClosedCallback;
    public void setOnClosedCallback(java.util.function.Consumer<String> callback) {
        this.onClosedCallback = callback;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Daily reset
    // ═══════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyReset() {
        int count = activeTrades.size();
        if (count > 0) {
            log.warn("[NEWS-MGMT] Daily reset clearing {} stale positions", count);
            activeTrades.clear();
        }
        try {
            jdbc.update("DELETE FROM news_open_positions WHERE trade_date < ?", LocalDate.now());
        } catch (Exception e) {
            log.debug("[NEWS-MGMT] Daily DB cleanup failed (non-fatal): {}", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Dashboard accessors
    // ═══════════════════════════════════════════════════════════════════════

    public Collection<ManagedNewsTrade> getActiveTrades() {
        return Collections.unmodifiableCollection(activeTrades.values());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // NSE tick alignment — exact same formula as PaperTradeManagementService
    // ═══════════════════════════════════════════════════════════════════════

    static BigDecimal alignToTick(BigDecimal price, RoundingMode mode) {
        BigDecimal ticks = price.multiply(BigDecimal.valueOf(20), MathContext.DECIMAL64).setScale(0, mode);
        return ticks.divide(BigDecimal.valueOf(20), 2, RoundingMode.UNNECESSARY);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Fill simulation — exact same formulas as PaperTradeManagementService
    // ═══════════════════════════════════════════════════════════════════════

    static BigDecimal simulateSlFill(BigDecimal slPrice, BigDecimal ltp, TradeDirection dir) {
        if (dir == TradeDirection.LONG) {
            BigDecimal raw = slPrice.multiply(BigDecimal.valueOf(1.0 - SL_SLIP), MathContext.DECIMAL64);
            return alignToTick(raw.min(ltp), RoundingMode.FLOOR);
        } else {
            BigDecimal raw = slPrice.multiply(BigDecimal.valueOf(1.0 + SL_SLIP), MathContext.DECIMAL64);
            return alignToTick(raw.max(ltp), RoundingMode.CEILING);
        }
    }

    static BigDecimal simulateTargetFill(BigDecimal targetPrice, TradeDirection dir) {
        if (dir == TradeDirection.LONG) {
            BigDecimal raw = targetPrice.multiply(BigDecimal.valueOf(1.0 - TARGET_SLIP), MathContext.DECIMAL64);
            return alignToTick(raw, RoundingMode.FLOOR);
        } else {
            BigDecimal raw = targetPrice.multiply(BigDecimal.valueOf(1.0 + TARGET_SLIP), MathContext.DECIMAL64);
            return alignToTick(raw, RoundingMode.CEILING);
        }
    }

    static BigDecimal simulateEodFill(BigDecimal ltp, TradeDirection dir) {
        if (dir == TradeDirection.LONG) {
            BigDecimal raw = ltp.multiply(BigDecimal.valueOf(1.0 - EOD_SLIP), MathContext.DECIMAL64);
            return alignToTick(raw, RoundingMode.FLOOR);
        } else {
            BigDecimal raw = ltp.multiply(BigDecimal.valueOf(1.0 + EOD_SLIP), MathContext.DECIMAL64);
            return alignToTick(raw, RoundingMode.CEILING);
        }
    }

    private boolean isMomentumCandle(Candle c) {
        return c.bodyPct().compareTo(new BigDecimal("0.80")) >= 0;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Brokerage — exact same formula as PaperTradeManagementService's
    // NseBrokerageCalculator
    // ═══════════════════════════════════════════════════════════════════════

    static final class NewsBrokerageCalculator {
        private static final BigDecimal BROKERAGE_RATE    = new BigDecimal("0.0003");
        private static final BigDecimal BROKERAGE_CAP     = new BigDecimal("20.00");
        private static final BigDecimal STT_RATE          = new BigDecimal("0.00025");
        private static final BigDecimal EXCHANGE_TXN_RATE = new BigDecimal("0.0000335");
        private static final BigDecimal SEBI_RATE         = new BigDecimal("0.000001");
        private static final BigDecimal GST_RATE          = new BigDecimal("0.18");

        private NewsBrokerageCalculator() {}

        static BigDecimal exitLegCost(BigDecimal fillPrice, int qty, TradeDirection direction) {
            BigDecimal turnover    = fillPrice.multiply(BigDecimal.valueOf(qty));
            BigDecimal brokerage   = turnover.multiply(BROKERAGE_RATE).min(BROKERAGE_CAP);
            BigDecimal stt         = direction == TradeDirection.LONG
                    ? turnover.multiply(STT_RATE) : BigDecimal.ZERO;
            BigDecimal exchangeTxn = turnover.multiply(EXCHANGE_TXN_RATE);
            BigDecimal sebi        = turnover.multiply(SEBI_RATE);
            BigDecimal gst         = brokerage.add(exchangeTxn).add(sebi).multiply(GST_RATE);
            return brokerage.add(stt).add(exchangeTxn).add(sebi).add(gst)
                    .setScale(2, RoundingMode.CEILING);
        }
    }
}