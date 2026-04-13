package com.trading.events;

import com.trading.domain.enums.TradeDirection;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * SmartChannelPullbackSignalEvent — fired by SmartChannelPullbackStrategy
 * when all gates pass and a valid pullback entry is confirmed.
 *
 * SIGNAL PIPELINE:
 *   SmartChannelPullbackStrategy
 *     → SmartChannelPullbackSignalEvent
 *     → RiskManagementService (slot/CB gates)
 *     → TradeApprovedEvent
 *     → PaperTradeExecutionService / TradeExecutionService
 *
 * SCORING (post-entry, 0–100):
 *   VWAP aligned       → +15
 *   RVOL ≥ 1.5         → +20
 *   RVOL 1.2–1.5       → +10
 *   Strong continuation→ +15
 *   Clean entry        → +20
 *   Early entry        → +15
 *   No nearby S/R      → +15
 *
 * DECISION:
 *   ≥ 60 → HOLD_STRONG
 *   40–60→ HOLD_CAREFULLY
 *   < 40 → EXIT_FAST
 */
@Getter
public class SmartChannelPullbackSignalEvent extends ApplicationEvent {

    // ── Core trade params ──────────────────────────────────────────────────
    private final String         tradingSymbol;
    private final long           instrumentToken;
    private final TradeDirection direction;
    private final BigDecimal     entryPrice;
    private final BigDecimal     stopLoss;
    private final BigDecimal     target1;        // T1 = 1:2 RR
    private final BigDecimal     target2;        // T2 = 1:3 RR
    private final int            quantity;
    private final BigDecimal     riskAmount;

    // ── Strategy metadata ──────────────────────────────────────────────────
    private final String  strategyName;
    private final double  probabilityScore;
    private final Instant signalTimestamp;

    // ── Signal context ─────────────────────────────────────────────────────
    private final String  sectorName;
    private final double  sectorChangePercent;
    private final String  channelQuality;      // "HIGH_QUALITY" | "VALID"
    private final String  pullbackStrength;    // "BEST" | "GOOD" | "LATE"
    private final double  pullbackPercent;
    private final double  rvol;
    private final boolean vwapAligned;
    private final String  entryType;           // "LIMIT" | "MARKET"
    private final String  marketBias;          // "STRONG_BULLISH" | "STRONG_BEARISH"

    // ── Post-entry score breakdown ─────────────────────────────────────────
    private final int scoreVwap;
    private final int scoreRvol;
    private final int scoreContinuation;
    private final int scoreCleanEntry;
    private final int scoreEarlyEntry;
    private final int scoreNoNearbySR;
    private final int totalScore;

    // ── Time stop (minutes) ────────────────────────────────────────────────
    private final int timeStopMinutes;

    public SmartChannelPullbackSignalEvent(Object source,
                                           String tradingSymbol,
                                           long instrumentToken,
                                           TradeDirection direction,
                                           BigDecimal entryPrice,
                                           BigDecimal stopLoss,
                                           BigDecimal target1,
                                           BigDecimal target2,
                                           int quantity,
                                           BigDecimal riskAmount,
                                           String strategyName,
                                           double probabilityScore,
                                           String sectorName,
                                           double sectorChangePercent,
                                           String channelQuality,
                                           String pullbackStrength,
                                           double pullbackPercent,
                                           double rvol,
                                           boolean vwapAligned,
                                           String entryType,
                                           String marketBias,
                                           int scoreVwap,
                                           int scoreRvol,
                                           int scoreContinuation,
                                           int scoreCleanEntry,
                                           int scoreEarlyEntry,
                                           int scoreNoNearbySR,
                                           int totalScore,
                                           int timeStopMinutes) {
        super(source);
        this.tradingSymbol       = tradingSymbol;
        this.instrumentToken     = instrumentToken;
        this.direction           = direction;
        this.entryPrice          = entryPrice;
        this.stopLoss            = stopLoss;
        this.target1             = target1;
        this.target2             = target2;
        this.quantity            = quantity;
        this.riskAmount          = riskAmount;
        this.strategyName        = strategyName;
        this.probabilityScore    = probabilityScore;
        this.signalTimestamp     = Instant.now();
        this.sectorName          = sectorName;
        this.sectorChangePercent = sectorChangePercent;
        this.channelQuality      = channelQuality;
        this.pullbackStrength    = pullbackStrength;
        this.pullbackPercent     = pullbackPercent;
        this.rvol                = rvol;
        this.vwapAligned         = vwapAligned;
        this.entryType           = entryType;
        this.marketBias          = marketBias;
        this.scoreVwap           = scoreVwap;
        this.scoreRvol           = scoreRvol;
        this.scoreContinuation   = scoreContinuation;
        this.scoreCleanEntry     = scoreCleanEntry;
        this.scoreEarlyEntry     = scoreEarlyEntry;
        this.scoreNoNearbySR     = scoreNoNearbySR;
        this.totalScore          = totalScore;
        this.timeStopMinutes     = timeStopMinutes;
    }

    /** Decision label based on total score */
    public String getDecision() {
        if (totalScore >= 60) return "HOLD_STRONG";
        if (totalScore >= 40) return "HOLD_CAREFULLY";
        return "EXIT_FAST";
    }

    /** Whether signal is stale (>30s old) */
    public boolean isStale() {
        return Instant.now().getEpochSecond() - signalTimestamp.getEpochSecond() > 30;
    }
}