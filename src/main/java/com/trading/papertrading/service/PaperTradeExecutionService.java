package com.trading.papertrading.service;

import com.trading.domain.entity.Trade;
import com.trading.domain.enums.TradeDirection;
import com.trading.events.TradeApprovedEvent;
import com.trading.events.TradeExecutionResultEvent;
import com.trading.marketdata.service.MarketTimingService;
import com.trading.papertrading.model.PaperAccount;
import com.trading.risk.service.RiskManagementService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Paper trading execution — exact mirror of TradeExecutionService.
 *
 * Differences from live TradeExecutionService:
 *   1. Only fires when trading.mode=PAPER
 *   2. No ZerodhaOrderClient — fills are simulated at LTP ± slippage
 *   3. Trade entity is stored in-memory only (no JPA/DB save)
 *   4. Registers with PaperTradeManagementService instead of TradeManagementService
 *
 * Everything else is IDENTICAL to live execution:
 *   - Same TradeApprovedEvent listener
 *   - Same position sizing (1% risk rule)
 *   - Same Trade entity fields
 *   - Same closeTrade() P&L calculation
 *   - Same riskService.onTradeClosed() call
 *   - Same TradeExecutionResultEvent publishing
 *   - Same isWithinWindow() check
 *   - Same @PreDestroy force close
 */
@Service
@Slf4j
public class PaperTradeExecutionService {

    private final ApplicationEventPublisher  publisher;
    private final RiskManagementService      riskService;
    private final PaperTradeManagementService paperManagement;
    private final MarketTimingService        timingService;
    private final PaperAccount               account;

    // @Lazy on paperManagement to break circular dependency
    public PaperTradeExecutionService(
            ApplicationEventPublisher publisher,
            RiskManagementService riskService,
            @Lazy PaperTradeManagementService paperManagement,
            MarketTimingService timingService,
            PaperAccount account) {
        this.publisher        = publisher;
        this.riskService      = riskService;
        this.paperManagement  = paperManagement;
        this.timingService    = timingService;
        this.account          = account;
    }

    @Value("${trading.mode:LIVE}")
    private String tradingMode;

    @Value("${trading.risk-per-trade:0.01}")
    private BigDecimal riskPerTrade;

    @Value("${trading.max-position-pct:0.20}")
    private BigDecimal maxPositionPct;

    // Slippage constants — same as live market impact
    private static final double ENTRY_SLIP = 0.0005;  // 0.05% entry slippage

    // ── In-memory trade tracking (same fields as live TradeExecutionService) ──
    private final Map<String, Trade> activeTrades = new ConcurrentHashMap<>();
    private final List<Trade>        todayTrades  = Collections.synchronizedList(new ArrayList<>());

    // ══════════════════════════════════════════════════════════════════
    // ENTRY — fires on TradeApprovedEvent (same as live)
    // ══════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tradingExecutor")
    public void onTradeApproved(TradeApprovedEvent event) {

        // ── Only run in PAPER mode ────────────────────────────────────
        if (!"PAPER".equalsIgnoreCase(tradingMode)) return;

        // Trading window check — same as live
        if (!isWithinWindow()) {
            log.warn("[PAPER] Outside trading window — rejected: {}", event.getTradingSymbol());
            return;
        }

        String sym = event.getTradingSymbol();
        int    qty = event.getQuantity();

        // Guard: no duplicate active trade — same as live
        if (activeTrades.containsKey(sym)) {
            log.warn("[PAPER] Trade already active for {} — skipping", sym);
            return;
        }

        log.info("[PAPER] Executing: {} dir={} qty={} entry={} sl={} target={}",
                sym, event.getDirection(), qty,
                event.getEntryPrice(), event.getStopLoss(), event.getTarget());

        // ── Simulate ENTRY fill (instead of live market order) ────────
        // Live: orderClient.placeMarketOrder(sym, txType, qty) → orderId
        // Paper: simulate fill at entryPrice + slippage
        BigDecimal rawEntry  = event.getEntryPrice();
        BigDecimal fillPrice = simulateEntryFill(rawEntry, event.getDirection());

        String entryOrderId = "PAPER-ENTRY-" + sym + "-" + System.currentTimeMillis();

        // ── No SL-M order needed — management service monitors ticks ──
        // Live: orderClient.placeSlmOrder(sym, slTxType, qty, sl) → slOrderId
        // Paper: we just record sl and monitor in PaperTradeManagementService
        String slOrderId = "PAPER-SL-" + sym + "-" + System.currentTimeMillis();

        // ── Build Trade entity — identical to live ────────────────────
        Trade trade = Trade.builder()
                .tradeDate(LocalDate.now())
                .tradingSymbol(sym)
                .instrumentToken(event.getInstrumentToken())
                .direction(event.getDirection())
                .status("OPEN")
                .entryTime(Instant.now())
                .entryPrice(fillPrice)          // paper: use simulated fill price
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

        // ── Store for dashboard — same as live ────────────────────────
        activeTrades.put(sym, trade);
        todayTrades.add(trade);

        // ── Register with PaperTradeManagementService ─────────────────
        // Live: tradeManagement.register(trade, estimatedAtr, window, strongTrend)
        double estimatedAtr = event.getStopLoss() != null && event.getEntryPrice() != null
                ? event.getEntryPrice().subtract(event.getStopLoss()).abs()
                .multiply(BigDecimal.valueOf(2)).doubleValue()
                : 0.0;
        boolean strongTrend = isStrongTrend();

        paperManagement.register(trade, estimatedAtr,
                timingService.getCurrentWindow(), strongTrend);

        log.info("[PAPER] ✅ Trade registered: {} fill={} (raw={} slip={}%) atr={} window={} strongTrend={}",
                sym, fillPrice, rawEntry,
                String.format("%.3f", ENTRY_SLIP * 100),
                String.format("%.2f", estimatedAtr),
                timingService.getCurrentWindow(), strongTrend);

        publishResult(sym, "ENTERED", entryOrderId, slOrderId,
                fillPrice, null, BigDecimal.ZERO, null);
    }

    // ══════════════════════════════════════════════════════════════════
    // SHUTDOWN — close all on app shutdown (same as live)
    // ══════════════════════════════════════════════════════════════════

    @PreDestroy
    public void onShutdown() {
        if (!"PAPER".equalsIgnoreCase(tradingMode)) return;
        if (activeTrades.isEmpty()) return;
        log.warn("[PAPER] App shutdown — force closing {} positions", activeTrades.size());
        new ArrayList<>(activeTrades.values())
                .forEach(trade -> closeTrade(trade, trade.getEntryPrice(), "APP_SHUTDOWN"));
    }

    // ══════════════════════════════════════════════════════════════════
    // closeTrade — called by PaperTradeManagementService
    // Mirrors TradeExecutionService.closeTrade() exactly
    // ══════════════════════════════════════════════════════════════════

    public void closeTrade(Trade trade, BigDecimal exitPrice, String reason) {
        if (!"OPEN".equals(trade.getStatus())) return;
        String sym = trade.getTradingSymbol();

        // P&L calculation — identical to live TradeExecutionService.closeTrade()
        BigDecimal pnl = trade.getDirection() == TradeDirection.LONG
                ? exitPrice.subtract(trade.getEntryPrice())
                .multiply(BigDecimal.valueOf(
                        activeTrades.containsKey(sym)
                                ? getRemainingQty(sym, trade.getQuantity())
                                : trade.getQuantity()))
                : trade.getEntryPrice().subtract(exitPrice)
                .multiply(BigDecimal.valueOf(
                        activeTrades.containsKey(sym)
                                ? getRemainingQty(sym, trade.getQuantity())
                                : trade.getQuantity()));

        // Deduct simulated brokerage (₹40 flat per round trip — Zerodha)
        BigDecimal brokerage = BigDecimal.valueOf(40.0);
        BigDecimal netPnl    = pnl.subtract(brokerage);

        trade.setStatus("CLOSED");
        trade.setExitTime(Instant.now());
        trade.setExitPrice(exitPrice);
        trade.setExitReason(reason);
        trade.setNetPnl(netPnl);
        trade.setUpdatedAt(Instant.now());

        activeTrades.remove(sym);

        // Update virtual account
        account.applyPnl(netPnl);

        // Same as live — notify risk service so sector slot is released
        riskService.onTradeClosed(sym, netPnl);

        log.info("[PAPER] Trade CLOSED: {} reason={} gross=₹{} brok=₹{} NET=₹{}",
                sym, reason,
                String.format("%.2f", pnl.doubleValue()),
                String.format("%.2f", brokerage.doubleValue()),
                String.format("%.2f", netPnl.doubleValue()));

        publishResult(sym, "CLOSED",
                trade.getEntryOrderId(), trade.getSlOrderId(),
                trade.getEntryPrice(), exitPrice, netPnl, reason);
    }

    // ══════════════════════════════════════════════════════════════════
    // Dashboard getters — same signatures as live TradeExecutionService
    // ══════════════════════════════════════════════════════════════════

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

    // ══════════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════════

    /**
     * Simulate market order fill — LTP ± slippage.
     * Long: buy slightly higher than signal price
     * Short: sell slightly lower than signal price
     */
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
        // Use same timing service as live — checks 9:40–14:40
        return timingService.isEntryAllowed();
    }

    /** Same logic as live TradeExecutionService.isStrongTrend() */
    private boolean isStrongTrend() {
        try {
            MarketTimingService.TimeWindow window = timingService.getCurrentWindow();
            return window != MarketTimingService.TimeWindow.LUNCH
                    && window != MarketTimingService.TimeWindow.LATE;
        } catch (Exception e) {
            return false;
        }
    }

    private int getRemainingQty(String sym, int defaultQty) {
        // PaperTradeManagementService tracks remainingQty via ManagedTrade
        // For P&L calc we use it from ManagedTrade via paperManagement
        return paperManagement.getRemainingQty(sym, defaultQty);
    }
}