package com.trading.ai.engine;

import com.trading.ai.model.AiTradeDecision;
import com.trading.ai.model.AiTradeOutcome;
import com.trading.domain.entity.Trade;
import com.trading.domain.enums.TradeDirection;
import com.trading.marketdata.service.MarketDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * AiTradeManagementEngine
 *
 * Monitors all open AI positions on every 1-minute candle.
 * Handles: SL hit, T1 hit (breakeven), T2 hit, trailing SL, EOD exit.
 *
 * TRADE STATES:
 *   OPEN          → waiting for T1 or SL
 *   T1_REACHED    → SL moved to breakeven, trailing active
 *   CLOSED        → position exited, outcome recorded
 *
 * FULLY INDEPENDENT:
 *   No imports from highrr, smc, or news packages.
 */
@Service
@ConditionalOnProperty(name = "ai.trading.enabled", havingValue = "true")
@Slf4j
public class AiTradeManagementEngine {

    private final MarketDataService marketData;

    // ── Open positions: symbol → position ────────────────────────────────
    private final Map<String, AiPosition> openPositions = new ConcurrentHashMap<>();

    // ── Outcome callback — wired to AiLearningEngine ──────────────────────
    private Consumer<AiTradeOutcome> onClosedCallback;

    // ── EOD exit time ─────────────────────────────────────────────────────
    private static final LocalTime EOD_EXIT_TIME = LocalTime.of(15, 5);

    // ── Trailing SL distance ──────────────────────────────────────────────
    private static final double TRAIL_PCT = 0.005; // 0.5% trailing

    public AiTradeManagementEngine(MarketDataService marketData) {
        this.marketData = marketData;
    }

    public void setOnClosedCallback(Consumer<AiTradeOutcome> callback) {
        this.onClosedCallback = callback;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // REGISTER TRADE — called when AI executes a new trade
    // ═══════════════════════════════════════════════════════════════════════

    public void registerTrade(Trade trade, AiTradeDecision decision) {
        AiPosition pos = new AiPosition(trade, decision);
        openPositions.put(trade.getTradingSymbol(), pos);
        log.info("[AI-MGMT] Registered: {} {} | SL={} T1={} T2={}",
                trade.getTradingSymbol(), trade.getDirection(),
                decision.getStopLoss(), decision.getTarget1(), decision.getTarget2());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PER-SYMBOL 1-MINUTE UPDATE — called on every 1m candle close
    // ═══════════════════════════════════════════════════════════════════════

    public void onCandle(String symbol) {
        AiPosition pos = openPositions.get(symbol);
        if (pos == null) return;

        Map<String, BigDecimal> prices = marketData.getLastPricesSimple();
        BigDecimal ltpBD = prices.get(symbol);
        if (ltpBD == null) return;
        double ltp = ltpBD.doubleValue();
        if (ltp <= 0) return;

        boolean isLong = pos.trade.getDirection() == TradeDirection.LONG;
        double sl  = pos.currentSl;
        double t1  = pos.decision.getTarget1().doubleValue();
        double t2  = pos.decision.getTarget2().doubleValue();

        // ── SL hit check ─────────────────────────────────────────────────
        if ((isLong && ltp <= sl) || (!isLong && ltp >= sl)) {
            close(symbol, ltp, "SL_HIT");
            return;
        }

        // ── T2 hit check ─────────────────────────────────────────────────
        if ((isLong && ltp >= t2) || (!isLong && ltp <= t2)) {
            close(symbol, ltp, "TARGET_2");
            return;
        }

        // ── T1 hit check ─────────────────────────────────────────────────
        if (!pos.t1Reached) {
            if ((isLong && ltp >= t1) || (!isLong && ltp <= t1)) {
                pos.t1Reached = true;
                // Move SL to breakeven + 0.1%
                double be = isLong
                        ? pos.entry * 1.001
                        : pos.entry * 0.999;
                if ((isLong && be > sl) || (!isLong && be < sl)) {
                    pos.currentSl = be;
                    log.info("[AI-MGMT] {} T1 HIT @ {} → SL moved to breakeven {}",
                            symbol, String.format("%.2f", ltp), String.format("%.2f", be));
                }
            }
        }

        // ── Trailing SL (active after T1) ─────────────────────────────────
        if (pos.t1Reached) {
            double trail = isLong
                    ? ltp * (1 - TRAIL_PCT)
                    : ltp * (1 + TRAIL_PCT);
            if ((isLong && trail > pos.currentSl) || (!isLong && trail < pos.currentSl)) {
                pos.currentSl = trail;
                log.debug("[AI-MGMT] {} Trail SL → {}", symbol, String.format("%.2f", trail));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // EOD EXIT — 15:05 IST every trading day
    // ═══════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 5 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void eodExit() {
        if (openPositions.isEmpty()) return;
        log.info("[AI-MGMT] EOD exit — closing {} positions", openPositions.size());
        Map<String, BigDecimal> prices = marketData.getLastPricesSimple();
        new ArrayList<>(openPositions.keySet()).forEach(symbol -> {
            BigDecimal ltpBD = prices.get(symbol);
            if (ltpBD != null) close(symbol, ltpBD.doubleValue(), "AI_EOD");
        });
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CLOSE POSITION
    // ═══════════════════════════════════════════════════════════════════════

    public void close(String symbol, double exitPrice, String reason) {
        AiPosition pos = openPositions.remove(symbol);
        if (pos == null) return;

        Trade trade = pos.trade;
        boolean isLong = trade.getDirection() == TradeDirection.LONG;
        double entry   = pos.entry;

        // Compute P&L
        double pnlPer = isLong ? exitPrice - entry : entry - exitPrice;
        double totalPnl = pnlPer * trade.getQuantity();
        double slDist   = Math.abs(entry - pos.decision.getStopLoss().doubleValue());
        double rMultiple = slDist > 0 ? pnlPer / slDist : 0;

        // Update Trade entity
        trade.setStatus("CLOSED");
        trade.setExitPrice(bd(exitPrice, 2));
        trade.setExitTime(Instant.now());
        trade.setExitReason(reason);
        trade.setNetPnl(bd(totalPnl, 2));
        trade.setUpdatedAt(Instant.now());

        String outcomeType = rMultiple >= 1.0 ? "WIN"
                : rMultiple >= 0 ? "BREAKEVEN" : "LOSS";

        log.info("[AI-MGMT] CLOSED: {} {} @ {} | R={} P&L=₹{} reason={}",
                symbol, trade.getDirection(), exitPrice, rMultiple, totalPnl, reason);

        // Build outcome for learning engine
        AiTradeOutcome outcome = AiTradeOutcome.builder()
                .symbol(symbol)
                .direction(isLong ? "LONG" : "SHORT")
                .entryPrice(bd(entry, 2))
                .exitPrice(bd(exitPrice, 2))
                .pnl(bd(totalPnl, 2))
                .rMultiple(rMultiple)
                .exitReason(reason)
                .outcomeType(outcomeType)
                .confidence(pos.decision.getConfidence())
                .qualityScore(pos.decision.getTradeQualityScore())
                .reasoning(pos.decision.getReasoning())
                .dominantFactor(pos.decision.getDominantFactor())
                .featureVectorAtEntry(pos.decision.getFeatureVector() != null
                        ? pos.decision.getFeatureVector().getFeatures() : null)
                .featureVectorJson("[]")
                .entryTime(trade.getEntryTime())
                .exitTime(Instant.now())
                .regime("UNKNOWN") // filled by caller
                .build();

        // Fire learning callback
        if (onClosedCallback != null) {
            try { onClosedCallback.accept(outcome); }
            catch (Exception e) { log.debug("[AI-MGMT] Learning callback error: {}", e.getMessage()); }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // DAILY RESET — 9:10 AM every trading day
    // FIX: Was missing entirely — caused ghost positions to survive midnight
    // and block the next trading session.
    // Clears all open positions at session start.
    // Safe because EOD exit at 15:05 closes all positions the day before.
    // ═══════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyReset() {
        int count = openPositions.size();
        if (count > 0) {
            log.warn("[AI-MGMT] Daily reset clearing {} stale positions (EOD exit may have failed)",
                    count);
            openPositions.clear();
        }
        log.info("[AI-MGMT] Daily reset complete — positions cleared");
    }

    /**
     * Force-clear all positions.
     * Called by AiTradingSystem.dailyReset() as a safety net in addition
     * to the scheduled reset above.
     */
    public void clearPositions() {
        openPositions.clear();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ACCESSORS
    // ═══════════════════════════════════════════════════════════════════════

    public Map<String, AiPosition> getOpenPositions() {
        return Collections.unmodifiableMap(openPositions);
    }

    public boolean hasPosition(String symbol) {
        return openPositions.containsKey(symbol);
    }

    public int getOpenCount() { return openPositions.size(); }

    private BigDecimal bd(double v, int scale) {
        return BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // POSITION STATE
    // ═══════════════════════════════════════════════════════════════════════

    public static class AiPosition {
        public final Trade          trade;
        public final AiTradeDecision decision;
        public final double          entry;
        public volatile double       currentSl;
        public volatile boolean      t1Reached = false;

        public AiPosition(Trade trade, AiTradeDecision decision) {
            this.trade    = trade;
            this.decision = decision;
            this.entry    = trade.getEntryPrice().doubleValue();
            this.currentSl = decision.getStopLoss().doubleValue();
        }
    }
}