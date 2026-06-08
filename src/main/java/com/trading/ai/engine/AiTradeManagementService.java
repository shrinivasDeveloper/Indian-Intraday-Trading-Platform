package com.trading.ai.engine;

import com.trading.ai.model.*;
import com.trading.domain.entity.Trade;
import com.trading.domain.enums.TradeDirection;
import com.trading.events.CandleCompleteEvent;
import com.trading.marketdata.service.MarketDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AiTradeManagementService — Trade Management Engine
 *
 * Monitors all open AI positions on every 1-minute candle.
 * Uses in-memory Trade tracking — no repository, no JPA.
 * Completely isolated from PaperTradeManagementService.
 *
 * Exit logic:
 *   1. SL hit           → immediate close
 *   2. T1 hit           → move SL to breakeven + start trailing
 *   3. T2 hit           → full close
 *   4. Trailing stop    → trail SL 0.5% after T1
 *   5. EOD (15:05)      → closeAllPositions("AI_EOD")
 */
@Service
@ConditionalOnProperty(name = "ai.trading.enabled", havingValue = "true")
@Slf4j
@RequiredArgsConstructor
public class AiTradeManagementService {

    private final MarketDataService marketDataService;

    private AiTradingModuleV2Reference moduleRef;

    // Active AI trades: symbol → AiManagedTrade
    private final Map<String, AiManagedTrade> activeTrades = new ConcurrentHashMap<>();

    // ── Registration ─────────────────────────────────────────────────────────

    public void registerTrade(Trade trade, AiTradeDecision decision) {
        activeTrades.put(trade.getTradingSymbol(),
                new AiManagedTrade(trade, decision, false));
        log.debug("[AI-MGMT] Registered: {} {} | SL={} T1={}",
                trade.getTradingSymbol(), decision.getDirection(),
                trade.getStopLoss(), trade.getTarget());
    }

    public void setModuleRef(AiTradingModuleV2Reference ref) {
        this.moduleRef = ref;
    }

    // ── Monitor on every 1m candle ────────────────────────────────────────────

    @EventListener
    @Async("tradingExecutor")
    public void onCandle(CandleCompleteEvent event) {
        if (!"minute".equals(event.getCandle().getTimeframe())) return;
        if (activeTrades.isEmpty()) return;

        String symbol = event.getCandle().getTradingSymbol();
        AiManagedTrade mt = activeTrades.get(symbol);
        if (mt == null) return;

        Map<String, BigDecimal> prices = marketDataService.getLastPricesSimple();
        BigDecimal ltp = prices.get(symbol);
        if (ltp == null) return;

        evaluate(symbol, mt, ltp);
    }

    private void evaluate(String symbol, AiManagedTrade mt, BigDecimal ltp) {
        Trade trade  = mt.trade();
        boolean long_ = trade.getDirection() == TradeDirection.LONG;
        double price  = ltp.doubleValue();
        double sl     = trade.getStopLoss().doubleValue();
        double t1     = trade.getTarget().doubleValue();
        double t2     = decision(mt).getTarget2() != null
                ? decision(mt).getTarget2().doubleValue()
                : t1 * (long_ ? 1.015 : 0.985);
        double entry  = trade.getEntryPrice().doubleValue();

        // SL hit
        if ((long_ && price <= sl) || (!long_ && price >= sl)) {
            close(symbol, mt, ltp, "SL_HIT"); return;
        }
        // T2 hit
        if ((long_ && price >= t2) || (!long_ && price <= t2)) {
            close(symbol, mt, ltp, "TARGET_2"); return;
        }
        // T1 hit → move SL to breakeven
        if (!mt.t1Reached() && ((long_ && price >= t1) || (!long_ && price <= t1))) {
            double be = long_ ? entry * 1.001 : entry * 0.999;
            trade.setStopLoss(BigDecimal.valueOf(be).setScale(2, RoundingMode.HALF_UP));
            activeTrades.put(symbol, new AiManagedTrade(trade, mt.decision(), true));
            log.info("[AI-MGMT] T1 hit: {} | SL moved to breakeven ₹{:.2f}", symbol, be);
            return;
        }
        // Trailing stop after T1
        if (mt.t1Reached()) {
            double cur = trade.getStopLoss().doubleValue();
            double trail = long_ ? price * 0.995 : price * 1.005;
            if ((long_ && trail > cur) || (!long_ && trail < cur)) {
                trade.setStopLoss(BigDecimal.valueOf(trail).setScale(2, RoundingMode.HALF_UP));
            }
        }
    }

    private void close(String symbol, AiManagedTrade mt, BigDecimal exitPrice, String reason) {
        Trade trade  = mt.trade();
        boolean long_ = trade.getDirection() == TradeDirection.LONG;
        double entry  = trade.getEntryPrice().doubleValue();
        double exit   = exitPrice.doubleValue();
        double slDist = Math.abs(entry - mt.decision().getStopLoss().doubleValue());
        double pnl    = (long_ ? exit - entry : entry - exit) * trade.getQuantity();
        double rMul   = slDist > 0 ? (long_ ? exit - entry : entry - exit) / slDist : 0;

        trade.setStatus("CLOSED");
        trade.setExitPrice(exitPrice);
        trade.setExitTime(Instant.now());
        trade.setExitReason(reason);
        trade.setNetPnl(BigDecimal.valueOf(pnl).setScale(2, RoundingMode.HALF_UP));
        trade.setUpdatedAt(Instant.now());
        activeTrades.remove(symbol);

        AiTradeOutcome outcome = AiTradeOutcome.builder()
                .symbol(symbol)
                .direction(mt.decision().getDirection())
                .entryPrice(trade.getEntryPrice())
                .exitPrice(exitPrice)
                .pnl(trade.getNetPnl())
                .rMultiple(rMul)
                .exitReason(reason)
                .outcomeType(pnl > 0 ? "WIN" : pnl < 0 ? "LOSS" : "BREAKEVEN")
                .confidence(mt.decision().getConfidence())
                .qualityScore(mt.decision().getTradeQualityScore())
                .reasoning(mt.decision().getReasoning())
                .dominantFactor(mt.decision().getDominantFactor())
                .featureVectorAtEntry(mt.decision().getFeatureVector() != null
                        ? mt.decision().getFeatureVector().getFeatures() : null)
                .entryTime(trade.getEntryTime())
                .exitTime(Instant.now())
                .build();

        if (moduleRef != null) moduleRef.onPositionClosed(symbol, outcome);

        log.info("[AI-MGMT] Closed: {} | {} | P&L=₹{:.0f} | R={:.2f} | {}",
                symbol, outcome.getOutcomeType(), pnl, rMul, reason);
    }

    public void closeAllPositions(String reason) {
        Map<String, BigDecimal> prices = marketDataService.getLastPricesSimple();
        new java.util.ArrayList<>(activeTrades.keySet()).forEach(sym -> {
            AiManagedTrade mt = activeTrades.get(sym);
            if (mt == null) return;
            BigDecimal ltp = prices.getOrDefault(sym, mt.trade().getEntryPrice());
            close(sym, mt, ltp, reason);
        });
    }

    public int getActiveCount() { return activeTrades.size(); }

    private AiTradeDecision decision(AiManagedTrade mt) { return mt.decision(); }

    // ── Inner record ─────────────────────────────────────────────────────────

    private record AiManagedTrade(Trade trade, AiTradeDecision decision, boolean t1Reached) {}

    /** Avoids circular dependency with AiTradingModuleV2 */
    public interface AiTradingModuleV2Reference {
        void onPositionClosed(String symbol, AiTradeOutcome outcome);
    }
}