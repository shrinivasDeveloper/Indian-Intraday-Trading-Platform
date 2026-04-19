package com.trading.strategy.highrr;

import com.trading.domain.enums.TradeDirection;
import com.trading.events.TickReceivedEvent;
import com.trading.events.TradeExecutionResultEvent;
import com.trading.execution.client.ZerodhaOrderClient;
import com.trading.papertrading.model.PaperAccount;
import com.trading.risk.service.RiskManagementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Lazy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HighRRTradeManager — Post-entry exit management for HighRR trades.
 *
 * COMPLETELY ISOLATED from PaperTradeManagementService and TradeManagementService.
 * Runs its own tick listener and manages only trades opened by HighRROrderExecutionService.
 *
 * EXIT TRIGGERS:
 *   1. SL hit   → immediate exit (tick-level monitoring on tickExecutor)
 *   2. Target hit → immediate exit (T1 = 2R, T2 = 3R if enabled)
 *   3. Time exit → 1:30 PM hard close
 *   4. Momentum exit → if trend reverses and candle is against position
 *   5. Force close → called by HighRROrderExecutionService
 *
 * PAPER mode: simulates fills with slippage, updates PaperAccount
 * LIVE mode:  places market orders via ZerodhaOrderClient
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class HighRRTradeManager {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // Slippage for paper exits
    private static final double SL_SLIP_PCT  = 0.001;  // 0.1% extra slippage on SL hit
    private static final double EOD_SLIP_PCT = 0.0015; // 0.15% for time exit

    private final ApplicationEventPublisher publisher;
    private final PaperAccount             paperAccount;
    private final HighRRScannerService     scanner;
    private final RiskManagementService    riskManagement;

    @Value("${trading.mode:PAPER}")
    private String tradingMode;

    // Optional LIVE order client
    @Autowired(required = false)
    private ZerodhaOrderClient orderClient;

    // HighRRStrategyEngine injected lazily to avoid circular dependency
    private HighRRStrategyEngine strategyEngine;

    @Autowired
    public void setStrategyEngine(@Lazy HighRRStrategyEngine strategyEngine) {
        this.strategyEngine = strategyEngine;
    }

    // ── Active trades: symbol → HighRRTrade ───────────────────────────────────
    private final Map<String, HighRRTrade> activeTrades = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal>  lastPrices   = new ConcurrentHashMap<>();

    // ══════════════════════════════════════════════════════════════════════════
    // TRADE REGISTRATION
    // ══════════════════════════════════════════════════════════════════════════

    public void registerTrade(HighRRTrade trade) {
        activeTrades.put(trade.symbol(), trade);
        lastPrices.put(trade.symbol(), trade.entryPrice());
        log.info("[HIGHRR-MANAGER] Trade registered: {} | dir={} | entry={} | sl={} | T1={} | T2={}",
                trade.symbol(), trade.direction(),
                trade.entryPrice(), trade.stopLoss(),
                trade.target1(), trade.target2());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TICK LISTENER — SL / Target monitoring (real-time, tickExecutor)
    // ══════════════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tickExecutor")
    public void onTick(TickReceivedEvent tick) {
        if (activeTrades.isEmpty()) return;

        String     symbol = tick.getTradingSymbol();
        BigDecimal ltp    = tick.getLastTradedPrice();
        if (ltp == null || ltp.compareTo(BigDecimal.ZERO) <= 0) return;

        lastPrices.put(symbol, ltp);

        HighRRTrade trade = activeTrades.get(symbol);
        if (trade == null) return;

        boolean isBuy = trade.direction() == TradeDirection.LONG;
        double  price = ltp.doubleValue();
        double  sl    = trade.stopLoss().doubleValue();
        double  t1    = trade.target1().doubleValue();

        // ── SL HIT ────────────────────────────────────────────────────────────
        boolean slHit = isBuy ? price <= sl : price >= sl;
        if (slHit) {
            log.warn("[HIGHRR-MANAGER] 🔴 SL HIT: {} | ltp={} | sl={}", symbol, price, sl);
            closeTrade(symbol, ltp, "STOPLOSS_HIT");
            return;
        }

        // ── TARGET HIT (T1 = 2R) ─────────────────────────────────────────────
        boolean t1Hit = isBuy ? price >= t1 : price <= t1;
        if (t1Hit) {
            log.info("[HIGHRR-MANAGER] 🎯 TARGET HIT: {} | ltp={} | T1={}", symbol, price, t1);
            closeTrade(symbol, ltp, "TARGET_HIT");
            return;
        }

        // ── TIME STOP check ───────────────────────────────────────────────────
        if (trade.timeStopMinutes() > 0) {
            long elapsedMin = (Instant.now().getEpochSecond() - trade.entryTime().getEpochSecond()) / 60;
            if (elapsedMin >= trade.timeStopMinutes()) {
                log.warn("[HIGHRR-MANAGER] ⏱ TIME STOP: {} elapsed={}min limit={}min",
                        symbol, elapsedMin, trade.timeStopMinutes());
                closeTrade(symbol, ltp, "TIME_STOP_" + trade.timeStopMinutes() + "MIN");
                return;
            }
        }

        // ── MOMENTUM EXIT: trend reversed against position ─────────────────────
        checkMomentumExit(symbol, trade, ltp);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // MOMENTUM EXIT — exit if trend flips against position
    // ══════════════════════════════════════════════════════════════════════════

    private void checkMomentumExit(String symbol, HighRRTrade trade, BigDecimal ltp) {
        HighRRScannerService.SymbolState state = scanner.getSymbolState(symbol);
        if (state == null) return;

        boolean isBuy = trade.direction() == TradeDirection.LONG;

        // Exit BUY if trend is now DOWNTREND
        if (isBuy && state.isTrendingDown()) {
            // Only exit if also below entry (we have some loss), not just trend flip
            if (ltp.compareTo(trade.entryPrice()) < 0) {
                log.warn("[HIGHRR-MANAGER] ⚡ MOMENTUM EXIT (no trend): {} | trend={} | ltp={}",
                        symbol, state.trend(), ltp);
                closeTrade(symbol, ltp, "MOMENTUM_EXIT");
            }
        }

        // Exit SELL if trend is now UPTREND
        if (!isBuy && state.isTrendingUp()) {
            if (ltp.compareTo(trade.entryPrice()) > 0) {
                log.warn("[HIGHRR-MANAGER] ⚡ MOMENTUM EXIT (no trend): {} | trend={} | ltp={}",
                        symbol, state.trend(), ltp);
                closeTrade(symbol, ltp, "MOMENTUM_EXIT");
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // FORCE CLOSE ALL (called by HighRROrderExecutionService at time exit)
    // ══════════════════════════════════════════════════════════════════════════

    public void forceCloseAll(String reason) {
        if (activeTrades.isEmpty()) return;
        log.warn("[HIGHRR-MANAGER] Force closing {} positions: {}", activeTrades.size(), reason);

        new ArrayList<>(activeTrades.keySet()).forEach(symbol -> {
            BigDecimal ltp = lastPrices.getOrDefault(symbol,
                    activeTrades.get(symbol).entryPrice());
            closeTrade(symbol, ltp, reason);
        });
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CLOSE TRADE — compute P&L, update paper account, publish result event
    // ══════════════════════════════════════════════════════════════════════════

    private void closeTrade(String symbol, BigDecimal ltp, String reason) {
        HighRRTrade trade = activeTrades.remove(symbol);
        if (trade == null) return;

        boolean    isBuy    = trade.direction() == TradeDirection.LONG;
        BigDecimal exitFill = simulateExitFill(ltp, reason, trade.direction());

        // Gross P&L
        BigDecimal pnl;
        if (isBuy) {
            pnl = exitFill.subtract(trade.entryPrice())
                    .multiply(BigDecimal.valueOf(trade.quantity()));
        } else {
            pnl = trade.entryPrice().subtract(exitFill)
                    .multiply(BigDecimal.valueOf(trade.quantity()));
        }

        // Flat brokerage: ₹40 per round trip
        BigDecimal brokerage = BigDecimal.valueOf(40.0);
        BigDecimal netPnl    = pnl.subtract(brokerage);

        // Update paper account
        if ("PAPER".equalsIgnoreCase(tradingMode)) {
            paperAccount.applyPnl(netPnl);
        }

        // Notify risk management (releases slots)
        riskManagement.onTradeClosed(symbol, netPnl, "HIGH_RR_INTRADAY_V1", false);

        // Release strategy engine lock
        if (strategyEngine != null) {
            strategyEngine.onSignalClosed(symbol);
        }

        String pnlStr = String.format("%.2f", netPnl.doubleValue());
        String emoji  = netPnl.doubleValue() > 0 ? "✅" : "❌";
        log.info("[HIGHRR-MANAGER] {} CLOSED: {} | reason={} | entry={} | exit={} | P&L=₹{}",
                emoji, symbol, reason, trade.entryPrice(), exitFill, pnlStr);

        // Publish close event for dashboard and notification service
        publisher.publishEvent(new TradeExecutionResultEvent(
                this, symbol, "CLOSED",
                trade.orderId(), null,
                trade.entryPrice(), exitFill, netPnl, reason
        ));

        // Place exit order in LIVE mode
        if ("LIVE".equalsIgnoreCase(tradingMode) && orderClient != null) {
            String exitTxType = isBuy ? "SELL" : "BUY";
            try {
                orderClient.placeMarketOrder(symbol, exitTxType, trade.quantity());
                log.info("[HIGHRR-MANAGER] Live exit order placed: {} {} qty={}",
                        exitTxType, symbol, trade.quantity());
            } catch (Exception e) {
                log.error("[HIGHRR-MANAGER] Live exit order failed for {}: {}", symbol, e.getMessage());
            }
        }
    }

    // ── Exit fill simulation (paper mode) ──────────────────────────────────────

    private BigDecimal simulateExitFill(BigDecimal ltp, String reason, TradeDirection dir) {
        double price = ltp.doubleValue();
        boolean isBuy = dir == TradeDirection.LONG;

        double slipPct;
        if (reason.contains("STOPLOSS")) {
            slipPct = SL_SLIP_PCT;    // 0.1% worse on SL
        } else if (reason.contains("TIME_EXIT") || reason.contains("FORCE")) {
            slipPct = EOD_SLIP_PCT;   // 0.15% for forced exits
        } else {
            slipPct = 0.0005;         // 0.05% normal exit
        }

        double filled = isBuy
                ? price * (1.0 - slipPct)   // sell below market
                : price * (1.0 + slipPct);  // buy above market

        return BigDecimal.valueOf(filled).setScale(2,
                isBuy ? RoundingMode.FLOOR : RoundingMode.CEILING);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DAILY RESET
    // ══════════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyReset() {
        activeTrades.clear();
        lastPrices.clear();
        log.info("[HIGHRR-MANAGER] Daily reset complete");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DASHBOARD HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    public int getActiveTradeCount() { return activeTrades.size(); }

    public Collection<HighRRTrade> getActiveTrades() {
        return Collections.unmodifiableCollection(activeTrades.values());
    }

    public Map<String, BigDecimal> getLastPrices() {
        return Collections.unmodifiableMap(lastPrices);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HIGH RR TRADE RECORD
    // ══════════════════════════════════════════════════════════════════════════

    public record HighRRTrade(
            String         symbol,
            TradeDirection direction,
            BigDecimal     entryPrice,
            BigDecimal     stopLoss,
            BigDecimal     target1,       // 2R
            BigDecimal     target2,       // 3R
            int            quantity,
            String         orderId,
            Instant        entryTime,
            int            timeStopMinutes
    ) {
        public boolean isLong()  { return direction == TradeDirection.LONG; }
        public boolean isShort() { return direction == TradeDirection.SHORT; }

        public double currentRMultiple(double ltp) {
            double risk = entryPrice.subtract(stopLoss).abs().doubleValue();
            if (risk == 0) return 0;
            double profit = isLong() ? ltp - entryPrice.doubleValue()
                    : entryPrice.doubleValue() - ltp;
            return profit / risk;
        }
    }
}