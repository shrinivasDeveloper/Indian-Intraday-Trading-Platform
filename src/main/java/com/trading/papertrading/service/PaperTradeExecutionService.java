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
 *   2. No ZerodhaOrderClient — fills simulated at LTP ± slippage
 *   3. Trade entity stored in-memory only (no JPA/DB save)
 *   4. Registers with PaperTradeManagementService instead of TradeManagementService
 *
 * FIXES vs original:
 *
 *   FIX 1 — timeStopMinutes passed to PaperTradeManagementService.register()
 *     TradeApprovedEvent carries timeStopMinutes from the strategy's TradeSignal
 *     (e.g. VAP Pullback = 30 min, 7-Gate = 0 = no time stop).
 *     The original register() call had no timeStopMinutes parameter — time stops
 *     were never enforced in paper mode. Now they are.
 *
 *     If TradeApprovedEvent doesn't have getTimeStopMinutes() (older event class),
 *     defaults safely to 0 (no time stop). No breaking change.
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
    // ENTRY — fires on TradeApprovedEvent (same as live TradeExecutionService)
    // ══════════════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tradingExecutor")
    public void onTradeApproved(TradeApprovedEvent event) {

        // Only run in PAPER mode
        if (!"PAPER".equalsIgnoreCase(tradingMode)) {
            log.debug("[LIVE] Skipping TradeApprovedEvent — mode is {}. PaperTradeExecutionService handles this.",
                    tradingMode);
            return;
        }

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

        // Simulate ENTRY fill — LTP ± slippage (no API call)
        BigDecimal rawEntry  = event.getEntryPrice();
        BigDecimal fillPrice = simulateEntryFill(rawEntry, event.getDirection());

        String entryOrderId = "PAPER-ENTRY-" + sym + "-" + System.currentTimeMillis();
        String slOrderId    = "PAPER-SL-"    + sym + "-" + System.currentTimeMillis();

        // Build Trade entity — identical structure to live
        Trade trade = Trade.builder()
                .tradeDate(LocalDate.now())
                .tradingSymbol(sym)
                .instrumentToken(event.getInstrumentToken())
                .direction(event.getDirection())
                .status("OPEN")
                .entryTime(Instant.now())
                .entryPrice(fillPrice)      // paper: simulated fill price
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

        // ATR estimate (2× SL distance)
        double estimatedAtr = event.getStopLoss() != null && event.getEntryPrice() != null
                ? event.getEntryPrice().subtract(event.getStopLoss()).abs()
                .multiply(BigDecimal.valueOf(2)).doubleValue()
                : 0.0;

        boolean strongTrend = isStrongTrend();

        // FIX 1: Read timeStopMinutes from event (defaults to 0 if not present)
        int timeStopMinutes = 0;
        try {
            timeStopMinutes = event.getTimeStopMinutes();
        } catch (Exception ignored) {
            // TradeApprovedEvent may not have this field in older versions
            // 0 = no time stop — safe default
        }

        // Register with PaperTradeManagementService — now passes timeStopMinutes
        paperManagement.register(trade, estimatedAtr,
                timingService.getCurrentWindow(), strongTrend, timeStopMinutes);

        log.info("[PAPER] Trade registered: {} fill={} (raw={} slip={}%) atr={} " +
                        "window={} strongTrend={} timeStop={}min",
                sym, fillPrice, rawEntry,
                String.format("%.3f", ENTRY_SLIP * 100),
                String.format("%.2f", estimatedAtr),
                timingService.getCurrentWindow(), strongTrend,
                timeStopMinutes > 0 ? timeStopMinutes : "none");

        publishResult(sym, "ENTERED", entryOrderId, slOrderId,
                fillPrice, null, BigDecimal.ZERO, null);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SHUTDOWN — close all positions on app shutdown
    // ══════════════════════════════════════════════════════════════════════════

    @PreDestroy
    public void onShutdown() {
        if (!"PAPER".equalsIgnoreCase(tradingMode)) return;
        if (activeTrades.isEmpty()) return;
        log.warn("[PAPER] App shutdown — force closing {} positions", activeTrades.size());
        new ArrayList<>(activeTrades.values())
                .forEach(trade -> closeTrade(trade, trade.getEntryPrice(), "APP_SHUTDOWN"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // closeTrade — called by PaperTradeManagementService
    // Mirrors TradeExecutionService.closeTrade() exactly
    // ══════════════════════════════════════════════════════════════════════════

    public void closeTrade(Trade trade, BigDecimal exitPrice, String reason) {
        if (!"OPEN".equals(trade.getStatus())) return;
        String sym = trade.getTradingSymbol();

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

        // Flat ₹40 brokerage per round trip
        BigDecimal brokerage = BigDecimal.valueOf(40.0);
        BigDecimal netPnl    = pnl.subtract(brokerage);

        trade.setStatus("CLOSED");
        trade.setExitTime(Instant.now());
        trade.setExitPrice(exitPrice);
        trade.setExitReason(reason);
        trade.setNetPnl(netPnl);
        trade.setUpdatedAt(Instant.now());

        activeTrades.remove(sym);

        // Update virtual account — full close
        account.applyPnl(netPnl);

        // Notify risk service — releases sector slot, records P&L for CB
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

    // ══════════════════════════════════════════════════════════════════════════
    // Dashboard getters — same signatures as live TradeExecutionService
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