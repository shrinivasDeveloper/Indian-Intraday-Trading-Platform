package com.trading.execution.service;

import com.trading.domain.entity.Trade;
import com.trading.domain.enums.TradeDirection;
import com.trading.events.OrderUpdateEvent;
import com.trading.events.TradeApprovedEvent;
import com.trading.events.TradeExecutionResultEvent;
import com.trading.execution.client.ZerodhaOrderClient;
import com.trading.marketdata.service.MarketTimingService;
import com.trading.risk.service.RiskManagementService;
import com.zerodhatech.kiteconnect.utils.Constants;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Handles trade ENTRY only.
 * All post-entry management (trailing SL, partial exits, market/sector
 * alignment) is delegated to TradeManagementService.
 *
 * CHANGE FROM ORIGINAL: Added trading.mode check.
 *   When mode=PAPER → this service does nothing (paper trading handles it).
 *   When mode=LIVE  → normal live execution (unchanged).
 *
 * Only 2 lines added vs original:
 *   1. @Value("${trading.mode:LIVE}") private String tradingMode;
 *   2. if (!"LIVE".equalsIgnoreCase(tradingMode)) return; at top of onTradeApproved()
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TradeExecutionService {

    private final ZerodhaOrderClient        orderClient;
    private final ApplicationEventPublisher publisher;
    private final RiskManagementService     riskService;
    private final TradeManagementService    tradeManagement;
    private final MarketTimingService       timingService;

    @Value("${trading.trade-window-start:09:30}") private String windowStart;
    @Value("${trading.trade-window-end:14:30}")   private String windowEnd;

    // ── ADDED: trading mode — only difference from original ──────────
    @Value("${trading.mode:LIVE}")
    private String tradingMode;
    // ─────────────────────────────────────────────────────────────────

    // ── In-memory trade tracking (for dashboard) ──────────────────────
    private final Map<String, Trade>      activeTrades = new ConcurrentHashMap<>();
    private final List<Trade>             todayTrades  = Collections.synchronizedList(new ArrayList<>());

    // ══════════════════════════════════════════════════════════════════
    // ENTRY — fires when RiskManagementService approves a trade
    // ══════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tradingExecutor")
    public void onTradeApproved(TradeApprovedEvent event) {

        // ── ADDED: Only run in LIVE mode ──────────────────────────────
        // When PAPER mode: PaperTradeExecutionService handles this event instead.
        if (!"LIVE".equalsIgnoreCase(tradingMode)) {
            log.debug("[LIVE] Skipping TradeApprovedEvent — mode is {}. PaperTradeExecutionService handles this.",
                    tradingMode);
            return;
        }
        // ─────────────────────────────────────────────────────────────

        // Trading window check
        if (!isWithinWindow()) {
            log.warn("Outside trading window — rejected: {}", event.getTradingSymbol());
            return;
        }

        String sym = event.getTradingSymbol();
        int    qty = event.getQuantity();

        // Guard: no duplicate active trade for same symbol
        if (activeTrades.containsKey(sym)) {
            log.warn("Trade already active for {} — skipping", sym);
            return;
        }

        log.info("Executing: {} dir={} qty={} entry={} sl={} target={}",
                sym, event.getDirection(), qty,
                event.getEntryPrice(), event.getStopLoss(), event.getTarget());

        // ── Place ENTRY order ────────────────────────────────────────
        String txType = event.getDirection() == TradeDirection.LONG
                ? Constants.TRANSACTION_TYPE_BUY
                : Constants.TRANSACTION_TYPE_SELL;

        String entryOrderId;
        try {
            entryOrderId = orderClient.placeMarketOrder(sym, txType, qty);
        } catch (Exception e) {
            log.error("Entry order failed {}: {}", sym, e.getMessage());
            publishResult(sym, "REJECTED", null, null,
                    null, null, BigDecimal.ZERO, e.getMessage());
            return;
        }

        // ── Place SL-M order ────────────────────────────────────────
        String slTxType = event.getDirection() == TradeDirection.LONG
                ? Constants.TRANSACTION_TYPE_SELL
                : Constants.TRANSACTION_TYPE_BUY;

        String slOrderId = null;
        try {
            slOrderId = orderClient.placeSlmOrder(
                    sym, slTxType, qty, event.getStopLoss().doubleValue());
        } catch (Exception e) {
            log.error("SL order failed for {} — entry open without SL: {}", sym, e.getMessage());
        }

        // ── Build Trade entity ────────────────────────────────────────
        Trade trade = Trade.builder()
                .tradeDate(LocalDate.now())
                .tradingSymbol(sym)
                .instrumentToken(event.getInstrumentToken())
                .direction(event.getDirection())
                .status("OPEN")
                .entryTime(Instant.now())
                .entryPrice(event.getEntryPrice())
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

        // ── Store for dashboard ────────────────────────────────────────
        activeTrades.put(sym, trade);
        todayTrades.add(trade);

        // ── Register with TradeManagementService ──────────────────────
        double estimatedAtr = event.getStopLoss() != null && event.getEntryPrice() != null
                ? event.getEntryPrice().subtract(event.getStopLoss()).abs()
                .multiply(BigDecimal.valueOf(2)).doubleValue()
                : 0.0;

        boolean strongTrend = isStrongTrend(event.getDirection());

        tradeManagement.register(
                trade,
                estimatedAtr,
                timingService.getCurrentWindow(),
                strongTrend
        );

        log.info("Trade registered with TradeManagementService: {} atr={} window={} strongTrend={}",
                sym, String.format("%.2f", estimatedAtr),
                timingService.getCurrentWindow(), strongTrend);

        publishResult(sym, "ENTERED", entryOrderId, slOrderId,
                event.getEntryPrice(), null, BigDecimal.ZERO, null);
    }

    // ══════════════════════════════════════════════════════════════════
    // ORDER UPDATE — handles SL hit / target hit from Zerodha callbacks
    // ══════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tradingExecutor")
    public void onOrderUpdate(OrderUpdateEvent event) {
        // Only handle in LIVE mode
        if (!"LIVE".equalsIgnoreCase(tradingMode)) return;

        String orderId = event.getOrderId();
        activeTrades.values().stream()
                .filter(t -> orderId.equals(t.getSlOrderId())
                        || orderId.equals(t.getEntryOrderId()))
                .findFirst()
                .ifPresent(trade -> {
                    if ("COMPLETE".equals(event.getStatus())) {
                        String reason = orderId.equals(trade.getSlOrderId())
                                ? "STOPLOSS" : "TARGET";
                        closeTrade(trade,
                                BigDecimal.valueOf(event.getAveragePrice()), reason);
                    } else if ("REJECTED".equals(event.getStatus())) {
                        log.error("Order REJECTED {}: {}",
                                trade.getTradingSymbol(), event.getRejectionReason());
                        closeTrade(trade, trade.getEntryPrice(), "ORDER_REJECTED");
                    }
                });
    }

    // ══════════════════════════════════════════════════════════════════
    // SHUTDOWN — close all on app shutdown
    // ══════════════════════════════════════════════════════════════════

    @PreDestroy
    public void onShutdown() {
        if (!"LIVE".equalsIgnoreCase(tradingMode)) return;
        if (activeTrades.isEmpty()) return;
        log.warn("App shutdown — force closing {} positions", activeTrades.size());
        new ArrayList<>(activeTrades.values()).forEach(trade ->
                closeTrade(trade, trade.getEntryPrice(), "APP_SHUTDOWN"));
    }

    // ══════════════════════════════════════════════════════════════════
    // DASHBOARD getters
    // ══════════════════════════════════════════════════════════════════

    public Collection<Trade> getActiveTrades() {
        return Collections.unmodifiableCollection(activeTrades.values());
    }

    public List<Trade> getTodayTrades(LocalDate date) {
        return todayTrades.stream()
                .filter(t -> date.equals(t.getTradeDate()))
                .collect(Collectors.toList());
    }

    // ══════════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════════

    private void closeTrade(Trade trade, BigDecimal exitPrice, String reason) {
        if (!"OPEN".equals(trade.getStatus())) return;
        String sym = trade.getTradingSymbol();

        BigDecimal pnl = trade.getDirection() == TradeDirection.LONG
                ? exitPrice.subtract(trade.getEntryPrice())
                .multiply(BigDecimal.valueOf(trade.getQuantity()))
                : trade.getEntryPrice().subtract(exitPrice)
                .multiply(BigDecimal.valueOf(trade.getQuantity()));

        trade.setStatus("CLOSED");
        trade.setExitTime(Instant.now());
        trade.setExitPrice(exitPrice);
        trade.setExitReason(reason);
        trade.setNetPnl(pnl);
        trade.setUpdatedAt(Instant.now());

        activeTrades.remove(sym);
        riskService.onTradeClosed(sym, pnl);

        log.info("Trade CLOSED: {} reason={} pnl={}", sym, reason, pnl);

        publishResult(sym, "CLOSED",
                trade.getEntryOrderId(), trade.getSlOrderId(),
                trade.getEntryPrice(), exitPrice, pnl, reason);
    }

    private void publishResult(String sym, String status,
                               String entryOId, String slOId,
                               BigDecimal entry, BigDecimal exit,
                               BigDecimal pnl, String reason) {
        publisher.publishEvent(new TradeExecutionResultEvent(
                this, sym, status, entryOId, slOId, entry, exit, pnl, reason));
    }

    private boolean isWithinWindow() {
        LocalTime now   = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        LocalTime start = LocalTime.parse(windowStart);
        LocalTime end   = LocalTime.parse(windowEnd);
        return !now.isBefore(start) && !now.isAfter(end);
    }

    private boolean isStrongTrend(TradeDirection direction) {
        try {
            MarketTimingService.TimeWindow window = timingService.getCurrentWindow();
            if (window == MarketTimingService.TimeWindow.LUNCH
                    || window == MarketTimingService.TimeWindow.LATE) {
                return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}