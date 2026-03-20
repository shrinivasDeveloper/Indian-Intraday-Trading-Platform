package com.trading.execution.service;

import com.trading.domain.entity.Trade;
import com.trading.domain.enums.TradeDirection;
import com.trading.events.OrderUpdateEvent;
import com.trading.events.TradeApprovedEvent;
import com.trading.events.TradeExecutionResultEvent;
import com.trading.execution.client.ZerodhaOrderClient;
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
import java.math.MathContext;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class TradeExecutionService {

    private final ZerodhaOrderClient        orderClient;
    private final ApplicationEventPublisher publisher;
    private final RiskManagementService     riskService;

    // FROM original application.yml
    @Value("${trading.trade-window-start:09:30}") private String windowStart;
    @Value("${trading.trade-window-end:14:30}")   private String windowEnd;

    // NEW: in-memory trade tracking (for dashboard)
    private final Map<String, Trade>      activeTrades = new ConcurrentHashMap<>();
    private final List<Trade>             todayTrades  = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, BigDecimal> lastPrices   = new ConcurrentHashMap<>();

    // ── Entry — same logic as original, adds trade tracking ──────────

    @EventListener
    @Async("tradingExecutor")
    public void onTradeApproved(TradeApprovedEvent event) {
        // Trading window check (unchanged from original)
        if (!isWithinWindow()) {
            log.warn("Outside trading window — rejected: {}", event.getTradingSymbol());
            return;
        }

        String sym = event.getTradingSymbol();
        int    qty = event.getQuantity();

        // Guard: no duplicate (new)
        if (activeTrades.containsKey(sym)) {
            log.warn("Trade already active for {} — skipping", sym);
            return;
        }

        log.info("Executing: {} dir={} qty={} entry={}",
                sym, event.getDirection(), qty, event.getEntryPrice());

        // Exact same logic as original using Constants (verified in JAR)
        String txType   = event.getDirection().name().equals("LONG")
                ? Constants.TRANSACTION_TYPE_BUY
                : Constants.TRANSACTION_TYPE_SELL;
        String slTxType = event.getDirection().name().equals("LONG")
                ? Constants.TRANSACTION_TYPE_SELL
                : Constants.TRANSACTION_TYPE_BUY;

        // Place entry order (same as original)
        String entryOrderId;
        try {
            entryOrderId = orderClient.placeMarketOrder(sym, txType, qty);
        } catch (Exception e) {
            log.error("Entry order failed {}: {}", sym, e.getMessage());
            publishResult(sym, "REJECTED", null, null,
                    null, null, BigDecimal.ZERO, e.getMessage());
            return;
        }

        // Place stop-loss order (same as original)
        String slOrderId = null;
        try {
            slOrderId = orderClient.placeSlmOrder(
                    sym, slTxType, qty, event.getStopLoss().doubleValue());
        } catch (Exception e) {
            log.error("SL order failed for {} — entry open without SL: {}", sym, e.getMessage());
        }

        // NEW: build Trade and store for dashboard
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

        activeTrades.put(sym, trade);
        todayTrades.add(trade);

        publishResult(sym, "ENTERED", entryOrderId, slOrderId,
                event.getEntryPrice(), null, BigDecimal.ZERO, null);
    }

    // ── Order Update — same as original + close tracking ─────────────

    @EventListener
    @Async("tradingExecutor")
    public void onOrderUpdate(OrderUpdateEvent event) {
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
                        log.error("Order REJECTED {}: {}", trade.getTradingSymbol(),
                                event.getRejectionReason());
                        closeTrade(trade, trade.getEntryPrice(), "ORDER_REJECTED");
                    }
                });
    }

    // ── NEW: Price update for trailing SL (called from tick stream) ───

    public void updateLastPrice(String symbol, BigDecimal price) {
        lastPrices.put(symbol, price);
        Trade trade = activeTrades.get(symbol);
        if (trade == null || !"OPEN".equals(trade.getStatus())) return;

        if (trade.getDirection() == TradeDirection.LONG) {
            if (price.compareTo(trade.getStopLoss()) <= 0) { closeTrade(trade, trade.getStopLoss(), "STOPLOSS_HIT"); return; }
            if (price.compareTo(trade.getTarget())   >= 0) { closeTrade(trade, trade.getTarget(),   "TARGET_HIT");   return; }
            trailToBreakeven(trade, price, true);
        } else {
            if (price.compareTo(trade.getStopLoss()) >= 0) { closeTrade(trade, trade.getStopLoss(), "STOPLOSS_HIT"); return; }
            if (price.compareTo(trade.getTarget())   <= 0) { closeTrade(trade, trade.getTarget(),   "TARGET_HIT");   return; }
            trailToBreakeven(trade, price, false);
        }
    }

    // ── NEW: Force close at 15:15 IST ─────────────────────────────────

    @Scheduled(cron = "0 15 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void forceCloseAll() {
        if (activeTrades.isEmpty()) return;
        log.warn("TIME EXIT — force closing {} positions", activeTrades.size());
        new ArrayList<>(activeTrades.values()).forEach(trade -> {
            BigDecimal ltp = lastPrices.getOrDefault(
                    trade.getTradingSymbol(), trade.getEntryPrice());
            closeTrade(trade, ltp, "TIME_EXIT");
        });
    }

    @PreDestroy
    public void onShutdown() { forceCloseAll(); }

    // ── NEW: Dashboard getters ────────────────────────────────────────

    public Collection<Trade> getActiveTrades() {
        return Collections.unmodifiableCollection(activeTrades.values());
    }

    public List<Trade> getTodayTrades(LocalDate date) {
        return todayTrades.stream()
                .filter(t -> date.equals(t.getTradeDate()))
                .collect(Collectors.toList());
    }

    // ── Helpers ───────────────────────────────────────────────────────

    // Exact same signature as original publishResult
    private void publishResult(String sym, String status,
                               String entryOId, String slOId,
                               BigDecimal entry, BigDecimal exit,
                               BigDecimal pnl, String reason) {
        publisher.publishEvent(new TradeExecutionResultEvent(
                this, sym, status, entryOId, slOId, entry, exit, pnl, reason));
    }

    private void closeTrade(Trade trade, BigDecimal exitPrice, String reason) {
        if (!"OPEN".equals(trade.getStatus())) return;
        String sym = trade.getTradingSymbol();
        BigDecimal pnl = calcPnl(trade, exitPrice);

        // Lombok @Data generates setters on Trade entity
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

    private BigDecimal calcPnl(Trade t, BigDecimal exitPrice) {
        BigDecimal diff = t.getDirection() == TradeDirection.LONG
                ? exitPrice.subtract(t.getEntryPrice())
                : t.getEntryPrice().subtract(exitPrice);
        return diff.multiply(BigDecimal.valueOf(t.getQuantity()));
    }

    // Trail SL to breakeven at 0.5% profit
    // Uses modifySlTrigger(orderId, double) — verified in your ZerodhaOrderClient
    private void trailToBreakeven(Trade t, BigDecimal ltp, boolean isLong) {
        if (t.getEntryPrice().compareTo(BigDecimal.ZERO) == 0) return;
        if (t.getSlOrderId() == null) return;

        BigDecimal gainPct = isLong
                ? ltp.subtract(t.getEntryPrice()).divide(t.getEntryPrice(), MathContext.DECIMAL32)
                : t.getEntryPrice().subtract(ltp).divide(t.getEntryPrice(), MathContext.DECIMAL32);

        boolean slAlreadyAtEntry = isLong
                ? t.getStopLoss().compareTo(t.getEntryPrice()) >= 0
                : t.getStopLoss().compareTo(t.getEntryPrice()) <= 0;

        if (gainPct.compareTo(new BigDecimal("0.005")) >= 0 && !slAlreadyAtEntry) {
            try {
                orderClient.modifySlTrigger(
                        t.getSlOrderId(), t.getEntryPrice().doubleValue());
                t.setStopLoss(t.getEntryPrice());
                t.setUpdatedAt(Instant.now());
                log.info("Trailing SL → breakeven: {}", t.getTradingSymbol());
            } catch (Exception e) {
                log.warn("Trail SL update failed {}: {}", t.getTradingSymbol(), e.getMessage());
            }
        }
    }

    // Exact same as original
    private boolean isWithinWindow() {
        LocalTime now   = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        LocalTime start = LocalTime.parse(windowStart);
        LocalTime end   = LocalTime.parse(windowEnd);
        return !now.isBefore(start) && !now.isAfter(end);
    }
}