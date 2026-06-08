package com.trading.ai.engine;

import com.trading.ai.model.AiTradeDecision;
import com.trading.domain.entity.Trade;
import com.trading.domain.enums.TradeDirection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AiPaperTradeExecutionService
 *
 * Isolated paper trade execution for AI_TRADING_V1 strategy.
 * Uses in-memory Trade tracking — same pattern as PaperTradeExecutionService.
 * Does NOT use PaperTradeRepository (does not exist in this platform).
 * Does NOT call PaperTradeExecutionService or RiskManagementService.
 */
@Service
@ConditionalOnProperty(name = "ai.trading.enabled", havingValue = "true")
@Slf4j
@RequiredArgsConstructor
public class AiPaperTradeExecutionService {

    private static final String AI_STRATEGY = "AI_TRADING_V1";

    private final AiTradeManagementService tradeManager;

    @Value("${trading.mode:PAPER}")
    private String tradingMode;

    // In-memory trade store — same pattern as PaperTradeExecutionService
    private final Map<String, Trade> activeTrades = new ConcurrentHashMap<>();
    private final List<Trade>        todayTrades  = Collections.synchronizedList(new ArrayList<>());

    public boolean execute(AiTradeDecision decision) {
        if (!"PAPER".equals(tradingMode)) {
            log.warn("[AI-EXEC] Live mode not supported yet — PAPER only");
            return false;
        }
        if (decision.getPositionSize() <= 0) return false;

        try {
            Trade trade = Trade.builder()
                    .tradeDate(LocalDate.now())
                    .tradingSymbol(decision.getSymbol())
                    .instrumentToken(0L)
                    .direction(TradeDirection.valueOf(decision.getDirection()))
                    .status("OPEN")
                    .entryTime(Instant.now())
                    .entryPrice(decision.getEntryPrice())
                    .quantity(decision.getPositionSize())
                    .stopLoss(decision.getStopLoss())
                    .target(decision.getTarget1())
                    .strategyName(AI_STRATEGY)
                    .probabilityScore(java.math.BigDecimal.valueOf((int)(decision.getProbabilityOfSuccess() * 100)))
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            activeTrades.put(decision.getSymbol(), trade);
            todayTrades.add(trade);
            tradeManager.registerTrade(trade, decision);

            log.info("[AI-EXEC] ✅ Trade created: {} {} qty={} entry={} sl={} t1={} | " +
                            "P(success)={:.0f}% E[RR]={:.1f} E[Ret]={:.1f}%",
                    decision.getSymbol(), decision.getDirection(),
                    decision.getPositionSize(),
                    decision.getEntryPrice(), decision.getStopLoss(), decision.getTarget1(),
                    decision.getProbabilityOfSuccess() * 100,
                    decision.getExpectedRR(), decision.getExpectedReturn());
            return true;

        } catch (Exception e) {
            log.error("[AI-EXEC] Execution failed for {}: {}", decision.getSymbol(), e.getMessage());
            return false;
        }
    }

    public void closeTrade(String symbol, BigDecimal exitPrice, String reason) {
        Trade trade = activeTrades.remove(symbol);
        if (trade == null) return;
        trade.setStatus("CLOSED");
        trade.setExitPrice(exitPrice);
        trade.setExitTime(Instant.now());
        trade.setExitReason(reason);
        trade.setUpdatedAt(Instant.now());
        boolean isLong = trade.getDirection() == TradeDirection.LONG;
        double pnl = (isLong
                ? exitPrice.subtract(trade.getEntryPrice())
                : trade.getEntryPrice().subtract(exitPrice))
                .multiply(BigDecimal.valueOf(trade.getQuantity())).doubleValue();
        trade.setNetPnl(BigDecimal.valueOf(pnl).setScale(2, java.math.RoundingMode.HALF_UP));
    }

    public Map<String, Trade> getActiveTrades() { return Collections.unmodifiableMap(activeTrades); }
    public List<Trade>        getTodayTrades()  { return Collections.unmodifiableList(todayTrades); }

    public void dailyReset() {
        activeTrades.clear();
        todayTrades.clear();
    }
}