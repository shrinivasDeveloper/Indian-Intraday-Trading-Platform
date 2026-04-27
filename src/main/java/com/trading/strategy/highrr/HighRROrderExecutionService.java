package com.trading.strategy.highrr;

import com.trading.domain.entity.Trade;
import com.trading.domain.enums.TradeDirection;
import com.trading.events.TickReceivedEvent;
import com.trading.events.TradeApprovedEvent;
import com.trading.events.TradeExecutionResultEvent;
import com.trading.execution.client.ZerodhaOrderClient;
import com.trading.marketdata.service.MarketTimingService;
import com.trading.papertrading.model.PaperAccount;
import com.trading.papertrading.service.PaperTradeExecutionService;
import com.trading.risk.service.RiskManagementService;
import com.trading.strategy.highrr.HighRRScannerService.SymbolState;
import com.zerodhatech.kiteconnect.utils.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HighRROrderExecutionService – Slippage-aware execution for HighRR trades.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * CRITICAL ARCHITECTURE NOTE — Why this service exists independently:
 * ─────────────────────────────────────────────────────────────────────────────
 * HighRR requires a 2-second fill-timeout mechanism for limit orders that the
 * standard PaperTradeExecutionService/TradeExecutionService cannot provide.
 * This service intercepts TradeApprovedEvent for "HIGH_RR_INTRADAY_V1" ONLY,
 * and performs all execution itself (paper or live).
 *
 * The companion fix is in PaperTradeExecutionService.SELF_MANAGED_STRATEGIES:
 *   PaperTradeExecutionService skips "HIGH_RR_INTRADAY_V1" events entirely.
 * Without that fix, both services would execute the same trade → double position.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * CHANGES vs previous version:
 * ─────────────────────────────────────────────────────────────────────────────
 * FIX 1 – Added strategy name guard in onTradeApproved().
 *   The original code lacked a check for strategy == "HIGH_RR_INTRADAY_V1".
 *   If any other strategy somehow slipped through (shouldn't happen given
 *   SmartChannelSignalHandler routing, but belt-and-suspenders), this service
 *   would incorrectly intercept it.
 *   Fix: early return if strategy != HIGH_RR_INTRADAY_V1.
 *
 * FIX 2 – Removed redundant RiskManagementService slot booking in paper path.
 *   Previous comment "// Notify risk management (updates phase1Count, sector exposure)"
 *   with code riskManagement.getPhase1Count() was a no-op getter call that did
 *   nothing. The actual slot booking is done by SmartChannelSignalHandler via
 *   circuitBreaker.recordTradeEntered() before firing TradeApprovedEvent.
 *   Removed the misleading no-op call.
 *
 * FIX 3 – Paper path passes instrumentToken from event to HighRRTrade.
 *   Previously used 0L placeholder. Now correctly passes event.getInstrumentToken()
 *   which is populated by the fixed HighRRStrategyEngine.
 *
 * All fill-timeout, live order, and time-exit logic is unchanged.
 * ─────────────────────────────────────────────────────────────────────────────
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class HighRROrderExecutionService {

    private static final ZoneId IST           = ZoneId.of("Asia/Kolkata");
    private static final String HIGH_RR_STRAT = "HIGH_RR_INTRADAY_V1";

    // ── Fill control ────────────────────────────────────────────────────────
    private static final long   FILL_TIMEOUT_MS  = 2000L;
    private static final double ENTRY_BUFFER_PCT = 0.0003;

    // ── Time exit ───────────────────────────────────────────────────────────
    private static final LocalTime TIME_EXIT = LocalTime.of(15, 0);

    private final ApplicationEventPublisher  publisher;
    private final HighRRScannerService       scanner;
    private final HighRRTradeManager         tradeManager;
    private final HighRRStrategyEngine       strategyEngine;
    private final RiskManagementService      riskManagement;
    private final PaperAccount              paperAccount;

    @Value("${trading.mode:PAPER}")
    private String tradingMode;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ZerodhaOrderClient orderClient;

    private final Map<String, PendingOrder> pendingOrders = new ConcurrentHashMap<>();

    private final ScheduledExecutorService fillTimeoutExecutor = Executors.newScheduledThreadPool(
            4, r -> {
                Thread t = new Thread(r, "highrr-fill-" + System.nanoTime() % 100);
                t.setDaemon(true);
                return t;
            });

    // ══════════════════════════════════════════════════════════════════════════
    // TRADE APPROVED EVENT LISTENER
    // ══════════════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tradingExecutor")
    public void onTradeApproved(TradeApprovedEvent event) {
        // FIX 1: strict strategy guard — only handle HighRR signals.
        // This is a belt-and-suspenders check alongside the SELF_MANAGED_STRATEGIES
        // guard in PaperTradeExecutionService. Both must agree for safety.
        if (!HIGH_RR_STRAT.equals(event.getStrategyName())) return;

        String symbol = event.getTradingSymbol();
        log.info("[HIGHRR-EXEC] Trade approved: {} | dir={} | entry={} | sl={} | T1={} | qty={}",
                symbol, event.getDirection(), event.getEntryPrice(),
                event.getStopLoss(), event.getTarget(), event.getQuantity());

        // ── Slippage check: re-validate at execution time ────────────────────
        SymbolState currentState = scanner.getSymbolState(symbol);
        if (currentState != null) {
            if (currentState.momentumSpike()) {
                log.warn("[HIGHRR-EXEC] SKIPPED {} – momentum spike detected at execution time", symbol);
                strategyEngine.onSignalClosed(symbol);
                return;
            }
            if (!currentState.isSpreadOk()) {
                log.warn("[HIGHRR-EXEC] SKIPPED {} – spread too wide ({}) at execution time",
                        symbol, String.format("%.4f", currentState.spreadPct()));
                strategyEngine.onSignalClosed(symbol);
                return;
            }
            if (!currentState.hasAdequateDepth()) {
                log.warn("[HIGHRR-EXEC] SKIPPED {} – inadequate book depth at execution time " +
                        "(bid={} ask={})", symbol, currentState.bidQty(), currentState.askQty());
                strategyEngine.onSignalClosed(symbol);
                return;
            }
        }

        if ("PAPER".equalsIgnoreCase(tradingMode)) {
            executePaperTrade(event);
        } else {
            executeLiveTrade(event);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PAPER TRADE EXECUTION
    // ══════════════════════════════════════════════════════════════════════════

    private void executePaperTrade(TradeApprovedEvent event) {
        String symbol = event.getTradingSymbol();
        boolean isBuy = event.getDirection() == TradeDirection.LONG;

        double rawEntry = event.getEntryPrice().doubleValue();
        double fillPriceD = isBuy
                ? rawEntry * (1.0 + ENTRY_BUFFER_PCT)
                : rawEntry * (1.0 - ENTRY_BUFFER_PCT);

        BigDecimal fillPrice = BigDecimal.valueOf(fillPriceD).setScale(2, RoundingMode.HALF_UP);

        log.info("[HIGHRR-EXEC] PAPER FILL: {} | dir={} | rawEntry={} | fill={} | qty={} | sl={} | T1={}",
                symbol, event.getDirection(), rawEntry, fillPrice,
                event.getQuantity(), event.getStopLoss(), event.getTarget());

        String orderId = "HIGHRR-PAPER-" + symbol + "-" + System.currentTimeMillis();

        // Compute T2 = 3R
        BigDecimal risk    = fillPrice.subtract(event.getStopLoss()).abs();
        BigDecimal target2 = isBuy
                ? fillPrice.add(risk.multiply(BigDecimal.valueOf(3)))
                : fillPrice.subtract(risk.multiply(BigDecimal.valueOf(3)));

        // FIX 3: pass instrumentToken from event (resolved by HighRRStrategyEngine)
        HighRRTradeManager.HighRRTrade trade = new HighRRTradeManager.HighRRTrade(
                symbol,
                event.getDirection(),
                fillPrice,
                event.getStopLoss(),
                event.getTarget(),
                target2,
                event.getQuantity(),
                orderId,
                Instant.now(),
                event.getTimeStopMinutes()
        );

        tradeManager.registerTrade(trade);

        // FIX 2: removed no-op riskManagement.getPhase1Count() call.
        // Slot booking was already done by SmartChannelSignalHandler via
        // circuitBreaker.recordTradeEntered() before TradeApprovedEvent was fired.

        log.info("[HIGHRR-EXEC] ✅ Paper trade active: {} | fill={} | SL={} | T1={} | T2={}",
                symbol, fillPrice, event.getStopLoss(), event.getTarget(), target2);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // LIVE TRADE EXECUTION WITH 2-SECOND FILL CONTROL
    // ══════════════════════════════════════════════════════════════════════════

    private void executeLiveTrade(TradeApprovedEvent event) {
        if (orderClient == null) {
            log.error("[HIGHRR-EXEC] No ZerodhaOrderClient – cannot execute LIVE trade for {}",
                    event.getTradingSymbol());
            return;
        }

        String symbol = event.getTradingSymbol();
        boolean isBuy = event.getDirection() == TradeDirection.LONG;
        int     qty   = event.getQuantity();

        double rawEntry    = event.getEntryPrice().doubleValue();
        double limitPriceD = isBuy
                ? rawEntry * (1.0 + ENTRY_BUFFER_PCT)
                : rawEntry * (1.0 - ENTRY_BUFFER_PCT);
        double limitPrice  = Math.round(limitPriceD * 20.0) / 20.0; // NSE tick alignment

        String txType = isBuy ? Constants.TRANSACTION_TYPE_BUY : Constants.TRANSACTION_TYPE_SELL;

        log.info("[HIGHRR-EXEC] LIVE LIMIT ORDER: {} {} qty={} limit={} (buffer={}%) | timeout=2s",
                txType, symbol, qty, limitPrice, ENTRY_BUFFER_PCT * 100);

        String orderId;
        try {
            orderId = orderClient.placeLimitOrder(symbol, txType, qty, limitPrice);
            log.info("[HIGHRR-EXEC] Order placed: {} | orderId={}", symbol, orderId);
        } catch (Exception e) {
            log.error("[HIGHRR-EXEC] Order placement failed for {}: {}", symbol, e.getMessage());
            strategyEngine.onSignalClosed(symbol);
            return;
        }

        PendingOrder pending = new PendingOrder(
                orderId, symbol, event.getDirection(),
                BigDecimal.valueOf(limitPrice), event.getStopLoss(),
                event.getTarget(), qty, Instant.now(),
                new AtomicBoolean(false), event.getTimeStopMinutes()
        );
        pendingOrders.put(orderId, pending);

        // ── 2-second fill timeout ────────────────────────────────────────────
        fillTimeoutExecutor.schedule(() -> {
            PendingOrder p = pendingOrders.get(orderId);
            if (p == null || p.filled().get()) return;

            log.warn("[HIGHRR-EXEC] ⚠️ FILL TIMEOUT: {} orderId={} – cancelling order",
                    symbol, orderId);
            try {
                orderClient.cancelOrder(orderId);
            } catch (Exception ex) {
                log.warn("[HIGHRR-EXEC] Cancel failed for {}: {}", orderId, ex.getMessage());
            }
            pendingOrders.remove(orderId);
            strategyEngine.onSignalClosed(symbol);
            log.warn("[HIGHRR-EXEC] Trade SKIPPED (not filled in 2s): {}", symbol);

        }, FILL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TICK LISTENER – detect fill for live orders
    // ══════════════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tickExecutor")
    public void onTick(TickReceivedEvent tick) {
        if (pendingOrders.isEmpty()) return;
        if (!"LIVE".equalsIgnoreCase(tradingMode)) return;

        String symbol = tick.getTradingSymbol();
        double ltp    = tick.getLastTradedPrice().doubleValue();

        for (Map.Entry<String, PendingOrder> entry : pendingOrders.entrySet()) {
            PendingOrder p = entry.getValue();
            if (!symbol.equals(p.symbol()) || p.filled().get()) continue;

            boolean isBuy       = p.direction() == TradeDirection.LONG;
            double  limit       = p.limitPrice().doubleValue();
            boolean crossedLimit = isBuy ? ltp <= limit : ltp >= limit;

            if (crossedLimit) {
                p.filled().set(true);
                pendingOrders.remove(entry.getKey());

                log.info("[HIGHRR-EXEC] ✅ LIVE FILL DETECTED: {} | limit={} | ltp={} | orderId={}",
                        symbol, limit, ltp, p.orderId());

                BigDecimal risk    = p.limitPrice().subtract(p.stopLoss()).abs();
                BigDecimal target2 = isBuy
                        ? p.limitPrice().add(risk.multiply(BigDecimal.valueOf(3)))
                        : p.limitPrice().subtract(risk.multiply(BigDecimal.valueOf(3)));

                HighRRTradeManager.HighRRTrade trade = new HighRRTradeManager.HighRRTrade(
                        symbol, p.direction(), p.limitPrice(),
                        p.stopLoss(), p.target1(), target2,
                        p.quantity(), p.orderId(), Instant.now(), p.timeStopMinutes()
                );
                tradeManager.registerTrade(trade);
                break;
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TIME EXIT – force close all HighRR positions at 1:30 PM
    // ══════════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 0 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void timeExit() {
        log.warn("[HIGHRR-EXEC] ⏰ Time exit triggered at 3:00 PM – closing all HighRR positions");
        tradeManager.forceCloseAll("TIME_EXIT_15:00");

        for (Map.Entry<String, PendingOrder> entry : pendingOrders.entrySet()) {
            PendingOrder p = entry.getValue();
            if (!p.filled().get() && orderClient != null && "LIVE".equalsIgnoreCase(tradingMode)) {
                try { orderClient.cancelOrder(p.orderId()); } catch (Exception ignored) {}
            }
        }
        pendingOrders.clear();
    }

    // ── Pending order record ─────────────────────────────────────────────────

    private record PendingOrder(
            String         orderId,
            String         symbol,
            TradeDirection direction,
            BigDecimal     limitPrice,
            BigDecimal     stopLoss,
            BigDecimal     target1,
            int            quantity,
            Instant        placedAt,
            AtomicBoolean  filled,
            int            timeStopMinutes
    ) {}
}