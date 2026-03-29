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
 * PaperTradeExecutionService — simulates Zerodha order execution for paper mode.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * CHANGES vs previous version:
 *
 * 1. closeTrade() now accepts a boolean reachedPhase2 parameter.
 *    This is forwarded to riskService.onTradeClosed(symbol, pnl, strategy, reachedPhase2)
 *    so RiskManagementService can correctly decrement phase1Count:
 *      - reachedPhase2=true  → phase1Count was already decremented at migration time,
 *                              do NOT decrement again.
 *      - reachedPhase2=false → trade closed while still in Phase-1,
 *                              decrement phase1Count now.
 *
 * 2. Backward-compatible 3-param closeTrade(trade, exitPrice, reason) preserved
 *    for any callers that don't know about phase2 yet (defaults to false).
 *
 * 3. riskService.onTradeClosed() now called with all 4 params for accurate
 *    10-2-3 slot manager counter management.
 *
 * PRESERVED UNCHANGED:
 *   - Entry slippage simulation
 *   - Trading window and duplicate trade guards
 *   - ATR estimation, strongTrend logic
 *   - timeStopMinutes pipeline (FIX 1)
 *   - PaperAccount.applyPnl() call
 *   - publishResult() event publishing
 * ═══════════════════════════════════════════════════════════════════════
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

    @Value("${trading.mode:LIVE}")
    private String tradingMode;

    @Value("${trading.risk-per-trade:0.01}")
    private BigDecimal riskPerTrade;

    @Value("${trading.max-position-pct:0.20}")
    private BigDecimal maxPositionPct;

    // Entry slippage — 0.05% market order impact
    private static final double ENTRY_SLIP = 0.0005;

    // ── In-memory trade tracking ───────────────────────────────────────────────
    private final Map<String, Trade> activeTrades = new ConcurrentHashMap<>();
    private final List<Trade>        todayTrades  = Collections.synchronizedList(new ArrayList<>());

    // ══════════════════════════════════════════════════════════════════════════
    // ENTRY — fires on TradeApprovedEvent
    // ══════════════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tradingExecutor")
    public void onTradeApproved(TradeApprovedEvent event) {

        if (!"PAPER".equalsIgnoreCase(tradingMode)) {
            log.debug("[LIVE] Skipping TradeApprovedEvent — mode is {}.", tradingMode);
            return;
        }

        if (!isWithinWindow()) {
            log.warn("[PAPER] Outside trading window — rejected: {}", event.getTradingSymbol());
            return;
        }

        String sym = event.getTradingSymbol();
        int    qty = event.getQuantity();

        if (activeTrades.containsKey(sym)) {
            log.warn("[PAPER] Trade already active for {} — skipping", sym);
            return;
        }

        log.info("[PAPER] Executing: {} dir={} qty={} entry={} sl={} target={}",
                sym, event.getDirection(), qty,
                event.getEntryPrice(), event.getStopLoss(), event.getTarget());

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
                .strategyName(event.getStrategyName())
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

        log.info("[PAPER] Registered: {} fill={} (raw={} slip={:.3f}%) atr={:.2f} " +
                        "window={} trend={} timeStop={}",
                sym, fillPrice, rawEntry, ENTRY_SLIP * 100, estimatedAtr,
                timingService.getCurrentWindow(), strongTrend,
                timeStopMinutes > 0 ? timeStopMinutes + "min" : "none");

        publishResult(sym, "ENTERED", entryOrderId, slOrderId,
                fillPrice, null, BigDecimal.ZERO, null);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // closeTrade — called by PaperTradeManagementService
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Full close with phase-2 flag.
     *
     * @param trade          the trade entity to close
     * @param exitPrice      tick-aligned exit fill price
     * @param reason         exit reason string (SL, TARGET, TIME_STOP, etc.)
     * @param reachedPhase2  true if trade had slAtBreakeven=true when closed.
     *                       Forwarded to RiskManagementService to avoid double-decrementing
     *                       phase1Count (it was already decremented at migration time).
     */
    public void closeTrade(Trade trade, BigDecimal exitPrice,
                           String reason, boolean reachedPhase2) {
        if (!"OPEN".equals(trade.getStatus())) return;
        String sym      = trade.getTradingSymbol();
        String strategy = trade.getStrategyName();

        // P&L — uses remainingQty from management service for partial-exit accuracy
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

        // Flat ₹40 brokerage per round trip (full close leg)
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

        // Notify risk service — releases sector slot, strategy slot, phase1Count, records P&L
        riskService.onTradeClosed(sym, netPnl, strategy, reachedPhase2);

        log.info("[PAPER] CLOSED: {} reason={} gross=₹{:.2f} brok=₹{:.2f} NET=₹{:.2f} phase2={}",
                sym, reason,
                pnl.doubleValue(), brokerage.doubleValue(), netPnl.doubleValue(),
                reachedPhase2);

        publishResult(sym, "CLOSED",
                trade.getEntryOrderId(), trade.getSlOrderId(),
                trade.getEntryPrice(), exitPrice, netPnl, reason);
    }

    /**
     * Backward-compatible 3-param overload.
     * Assumes trade never reached Phase-2 (conservative — decrements phase1Count).
     * Used by app shutdown handler and any legacy callers.
     */
    public void closeTrade(Trade trade, BigDecimal exitPrice, String reason) {
        closeTrade(trade, exitPrice, reason, false);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Shutdown — close all on app stop
    // ══════════════════════════════════════════════════════════════════════════

    @PreDestroy
    public void onShutdown() {
        if (!"PAPER".equalsIgnoreCase(tradingMode)) return;
        if (activeTrades.isEmpty()) return;
        log.warn("[PAPER] App shutdown — force closing {} positions", activeTrades.size());
        new ArrayList<>(activeTrades.values())
                .forEach(trade -> closeTrade(trade, trade.getEntryPrice(), "APP_SHUTDOWN", false));
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