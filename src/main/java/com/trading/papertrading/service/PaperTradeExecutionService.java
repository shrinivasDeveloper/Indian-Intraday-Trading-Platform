package com.trading.papertrading.service;

import com.trading.domain.entity.Trade;
import com.trading.domain.enums.TradeDirection;
import com.trading.events.TradeApprovedEvent;
import com.trading.events.TradeExecutionResultEvent;
import com.trading.marketdata.service.MarketTimingService;
import com.trading.papertrading.model.PaperAccount;
import com.trading.risk.service.RiskManagementService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * PaperTradeExecutionService – simulates Zerodha order execution for paper mode.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * CRITICAL FIX: Strategy isolation for HighRR and ORB
 * ─────────────────────────────────────────────────────────────────────────────
 * Root cause: This service previously responded to ALL TradeApprovedEvents
 * regardless of strategy name. When HighRROrderExecutionService (or any future
 * strategy with its own execution engine) also handles the same event, it caused:
 *   1. Double trade execution for the same signal
 *   2. Two trade managers (PaperTradeManagementService + HighRRTradeManager)
 *      monitoring the same symbol simultaneously → duplicate SL/target fires
 *   3. Double P&L accounting in PaperAccount and RiskManagementService
 *
 * Fix: SELF_MANAGED_STRATEGIES set. Any strategy listed here has its own
 * execution pipeline and must NOT be handled by this service. This is the
 * single point of control for strategy isolation — add/remove strategies here.
 *
 * Currently self-managed:
 *   - HIGH_RR_INTRADAY_V1 → HighRROrderExecutionService + HighRRTradeManager
 *   - ORB_BREAKOUT_V1     → ORB uses PaperTradeExecutionService (NOT self-managed)
 *     NOTE: ORB signals flow through standard pipeline. Only HighRR is excluded.
 *
 * CHANGES vs previous version:
 *   1. SELF_MANAGED_STRATEGIES constant added (line ~80)
 *   2. onTradeApproved() early-return check added (line ~115)
 *   3. onShutdown() respects same filter (line ~200)
 *   All other logic is identical to the previous fixed version.
 * ─────────────────────────────────────────────────────────────────────────────
 */
@Service
@Slf4j
public class PaperTradeExecutionService {

    private final ApplicationEventPublisher   publisher;
    private final RiskManagementService       riskService;
    private final PaperTradeManagementService paperManagement;
    private final MarketTimingService         timingService;
    private final PaperAccount               account;

    // @Lazy on paperManagement to break circular dependency
    public PaperTradeExecutionService(
            ApplicationEventPublisher publisher,
            RiskManagementService riskService,
            @Lazy PaperTradeManagementService paperManagement,
            MarketTimingService timingService,
            PaperAccount account) {
        this.publisher       = publisher;
        this.riskService     = riskService;
        this.paperManagement = paperManagement;
        this.timingService   = timingService;
        this.account         = account;
    }

    /**
     * Strategies that manage their own paper execution pipeline.
     * This service must NOT handle TradeApprovedEvents for these strategies
     * to prevent double execution, duplicate P&L, and conflicting trade managers.
     *
     * HIGH_RR_INTRADAY_V1: handled by HighRROrderExecutionService + HighRRTradeManager.
     */
    private static final Set<String> SELF_MANAGED_STRATEGIES = Set.of(
            "HIGH_RR_INTRADAY_V1"
            // Add future self-managed strategies here, e.g.:
            // "MY_CUSTOM_STRATEGY_V1"
    );

    @Value("${trading.mode:LIVE}")
    private String tradingMode;

    @Value("${trading.risk-per-trade:0.01}")
    private BigDecimal riskPerTrade;

    @Value("${trading.max-position-pct:0.20}")
    private BigDecimal maxPositionPct;

    // Entry slippage – 0.05% market order impact
    private static final double ENTRY_SLIP = 0.0005;

    // ── In-memory trade tracking ────────────────────────────────────────────
    private final Map<String, Trade> activeTrades = new ConcurrentHashMap<>();
    private final List<Trade>        todayTrades  = Collections.synchronizedList(new ArrayList<>());

    // ══════════════════════════════════════════════════════════════════════════
    // ENTRY – fires on TradeApprovedEvent
    // ══════════════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tradingExecutor")
    public void onTradeApproved(TradeApprovedEvent event) {

        // ── Mode guard ──────────────────────────────────────────────────────
        if (!"PAPER".equalsIgnoreCase(tradingMode)) {
            log.debug("[LIVE] Skipping TradeApprovedEvent – mode is {}.", tradingMode);
            return;
        }

        // ── CRITICAL FIX: Strategy isolation guard ──────────────────────────
        // Strategies in SELF_MANAGED_STRATEGIES have their own execution engine.
        // Processing them here would cause double execution and conflicting managers.
        String strategyName = event.getStrategyName();
        if (strategyName != null && SELF_MANAGED_STRATEGIES.contains(strategyName)) {
            log.debug("[PAPER] Skipping TradeApprovedEvent for self-managed strategy: {}. " +
                    "Execution handled by its dedicated service.", strategyName);
            return;
        }

        // ── Time window guard ───────────────────────────────────────────────
        if (!isWithinWindow()) {
            log.warn("[PAPER] Outside trading window – rejected: {}", event.getTradingSymbol());
            return;
        }

        String sym = event.getTradingSymbol();
        int    qty = event.getQuantity();

        if (activeTrades.containsKey(sym)) {
            log.warn("[PAPER] Trade already active for {} – skipping", sym);
            return;
        }

        // ── Cross-strategy symbol conflict guard ────────────────────────────
        // Prevents two different strategies from trading the same symbol at the
        // same time. Proved necessary on 2026-04-21 when PRESTIGE appeared in
        // both MARKET_PRESSURE channel scan and SCPS pullback zone simultaneously.
        if (riskService.isSymbolAlreadyActive(sym)) {
            String holdingStrategy = riskService.getActiveStrategyForSymbol(sym);
            log.warn("[PAPER] Symbol {} already held by {} — rejecting {} signal to avoid double exposure",
                    sym, holdingStrategy, strategyName);
            return;
        }

        // FIX: Global minimum RR floor of 2.0 — safety net for all strategies.
        // Individual strategies enforce their own RR gates, but this catches
        // any edge case where a sub-2R signal slips through.
        // HighRR is self-managed (skipped above) so this only applies to
        // ORB, SCPS, Scalp, News, SMC.
        if (event.getStopLoss() != null && event.getTarget() != null
                && event.getEntryPrice() != null) {
            double reward = event.getTarget().subtract(event.getEntryPrice()).abs().doubleValue();
            double risk   = event.getEntryPrice().subtract(event.getStopLoss()).abs().doubleValue();
            if (risk > 0) {
                double rr = reward / risk;
                if (rr < 2.0) {
                    log.warn("[PAPER] ❌ Global RR floor: {} signal rejected — RR={} < 2.0 (reward={} risk={}). " +
                                    "Strategy: {}",
                            sym, String.format("%.2f", rr),
                            String.format("%.2f", reward), String.format("%.2f", risk),
                            strategyName);
                    return;
                }
            }
        }

        log.info("[PAPER] Executing: {} dir={} qty={} entry={} sl={} target={} strategy={}",
                sym, event.getDirection(), qty,
                event.getEntryPrice(), event.getStopLoss(), event.getTarget(),
                strategyName);

        BigDecimal rawEntry  = event.getEntryPrice();
        BigDecimal fillPrice = simulateEntryFill(rawEntry, event.getDirection());

        String entryOrderId = "PAPER-ENTRY-" + sym + "-" + System.currentTimeMillis();
        String slOrderId    = "PAPER-SL-"    + sym + "-" + System.currentTimeMillis();

        Trade trade = Trade.builder()
                .tradeDate(LocalDate.now())
                .tradingSymbol(sym)
                .instrumentToken(event.getInstrumentToken())
                .direction(event.getDirection())
                .status("OPEN")
                .entryTime(Instant.now())
                .entryPrice(fillPrice)
                .entryOrderId(entryOrderId)
                .quantity(qty)
                .stopLoss(event.getStopLoss())
                .target(event.getTarget())
                .slOrderId(slOrderId)
                .probabilityScore(event.getProbabilityScore())
                .strategyName(strategyName)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        activeTrades.put(sym, trade);
        todayTrades.add(trade);

        double estimatedAtr = event.getStopLoss() != null && event.getEntryPrice() != null
                ? event.getEntryPrice().subtract(event.getStopLoss()).abs()
                .multiply(BigDecimal.valueOf(2)).doubleValue()
                : 0.0;

        boolean strongTrend = isStrongTrend();

        int timeStopMinutes = 0;
        try {
            timeStopMinutes = event.getTimeStopMinutes();
        } catch (Exception ignored) { }

        paperManagement.register(trade, estimatedAtr,
                timingService.getCurrentWindow(), strongTrend, timeStopMinutes);

        log.info("[PAPER] Registered: {} fill={} (raw={} slip={}%) atr={} " +
                        "window={} trend={} timeStop={}",
                sym, fillPrice, rawEntry, ENTRY_SLIP * 100, estimatedAtr,
                timingService.getCurrentWindow(), strongTrend,
                timeStopMinutes > 0 ? timeStopMinutes + "min" : "none");

        publishResult(sym, "ENTERED", entryOrderId, slOrderId,
                fillPrice, null, BigDecimal.ZERO, null);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // closeTrade – called by PaperTradeManagementService
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Full close with phase-2 flag.
     */
    public void closeTrade(Trade trade, BigDecimal exitPrice,
                           String reason, boolean reachedPhase2) {
        if (!"OPEN".equals(trade.getStatus())) return;
        String sym      = trade.getTradingSymbol();
        String strategy = trade.getStrategyName();

        BigDecimal pnl = trade.getDirection() == TradeDirection.LONG
                ? exitPrice.subtract(trade.getEntryPrice())
                .multiply(BigDecimal.valueOf(
                        activeTrades.containsKey(sym)
                                ? paperManagement.getRemainingQty(sym, trade.getQuantity())
                                : trade.getQuantity()))
                : trade.getEntryPrice().subtract(exitPrice)
                .multiply(BigDecimal.valueOf(
                        activeTrades.containsKey(sym)
                                ? paperManagement.getRemainingQty(sym, trade.getQuantity())
                                : trade.getQuantity()));

        BigDecimal brokerage = BigDecimal.valueOf(40.0);
        BigDecimal netPnl    = pnl.subtract(brokerage);

        trade.setStatus("CLOSED");
        trade.setExitTime(Instant.now());
        trade.setExitPrice(exitPrice);
        trade.setExitReason(reason);
        trade.setNetPnl(netPnl);
        trade.setUpdatedAt(Instant.now());

        activeTrades.remove(sym);
        account.applyPnl(netPnl);
        riskService.onTradeClosed(sym, netPnl, strategy, reachedPhase2);

        log.info("[PAPER] CLOSED: {} reason={} gross=₹{} brok=₹{} NET=₹{} phase2={}",
                sym, reason,
                pnl.doubleValue(), brokerage.doubleValue(), netPnl.doubleValue(),
                reachedPhase2);

        publishResult(sym, "CLOSED",
                trade.getEntryOrderId(), trade.getSlOrderId(),
                trade.getEntryPrice(), exitPrice, netPnl, reason);
    }

    /**
     * Backward-compatible 3-param overload.
     */
    public void closeTrade(Trade trade, BigDecimal exitPrice, String reason) {
        closeTrade(trade, exitPrice, reason, false);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Shutdown – close all on app stop (respects strategy isolation)
    // ══════════════════════════════════════════════════════════════════════════

    @PreDestroy
    public void onShutdown() {
        if (!"PAPER".equalsIgnoreCase(tradingMode)) return;
        if (activeTrades.isEmpty()) return;
        log.warn("[PAPER] App shutdown – force closing {} positions", activeTrades.size());
        new ArrayList<>(activeTrades.values())
                .forEach(trade -> {
                    // Only close trades NOT managed by a self-managed strategy.
                    // Self-managed strategies handle their own shutdown.
                    String strat = trade.getStrategyName();
                    if (strat == null || !SELF_MANAGED_STRATEGIES.contains(strat)) {
                        closeTrade(trade, trade.getEntryPrice(), "APP_SHUTDOWN", false);
                    }
                });
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Dashboard getters
    // ══════════════════════════════════════════════════════════════════════════

    public Collection<Trade> getActiveTrades() {
        return Collections.unmodifiableCollection(activeTrades.values());
    }

    public List<Trade> getTodayTrades(LocalDate date) {
        return todayTrades.stream()
                .filter(t -> date.equals(t.getTradeDate()))
                .collect(Collectors.toList());
    }

    public List<Trade> getAllTrades() {
        return Collections.unmodifiableList(todayTrades);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    private BigDecimal simulateEntryFill(BigDecimal price, TradeDirection dir) {
        double filled = dir == TradeDirection.LONG
                ? price.doubleValue() * (1 + ENTRY_SLIP)
                : price.doubleValue() * (1 - ENTRY_SLIP);
        return BigDecimal.valueOf(filled).setScale(2, RoundingMode.HALF_UP);
    }

    private void publishResult(String sym, String status,
                               String entryOId, String slOId,
                               BigDecimal entry, BigDecimal exit,
                               BigDecimal pnl, String reason) {
        publisher.publishEvent(new TradeExecutionResultEvent(
                this, sym, status, entryOId, slOId, entry, exit, pnl, reason));
    }

    private boolean isWithinWindow() {
        return timingService.isEntryAllowed();
    }

    private boolean isStrongTrend() {
        try {
            MarketTimingService.TimeWindow window = timingService.getCurrentWindow();
            return window != MarketTimingService.TimeWindow.LUNCH
                    && window != MarketTimingService.TimeWindow.LATE;
        } catch (Exception e) {
            return false;
        }
    }
}