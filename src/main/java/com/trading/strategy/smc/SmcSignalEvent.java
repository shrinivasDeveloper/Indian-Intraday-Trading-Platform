package com.trading.strategy.smc;

import com.trading.domain.enums.TradeDirection;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * SmcSignalEvent
 * ─────────────────────────────────────────────────────────────────────────────
 * Dedicated signal event for SMC_INSTITUTIONAL_V1.
 *
 * WHY THIS EXISTS (not SmartChannelPullbackSignalEvent):
 *   SmartChannelPullbackSignalEvent belongs to the SCPS strategy pipeline and
 *   is routed through SmartChannelSignalHandler, which contains SCPS-specific
 *   risk logic. Using it for SMC would couple two independent strategies and
 *   break SMC if SCPS is ever modified or removed.
 *
 *   This event is routed through SmcSignalHandler, which publishes
 *   TradeApprovedEvent directly — the same final event that PaperTradeExecutionService
 *   and PaperTradeManagementService listen to. Zero impact on SCPS, HighRR, or
 *   any other strategy.
 *
 * PIPELINE:
 *   SmcInstitutionalStrategyEngine
 *     → SmcSignalEvent
 *     → SmcSignalHandler.onSignal()
 *       → RiskManagementService.checkSlots()
 *       → TradeApprovedEvent
 *         → PaperTradeExecutionService   [EXISTING — unchanged]
 *         → PaperTradeManagementService  [EXISTING — unchanged]
 *
 * Fields mirror TradeApprovedEvent exactly so PaperTradeExecutionService
 * can process it without any changes.
 * ─────────────────────────────────────────────────────────────────────────────
 */
@Getter
public class SmcSignalEvent extends ApplicationEvent {

    private final String         tradingSymbol;
    private final long           instrumentToken;
    private final TradeDirection direction;
    private final BigDecimal     entryPrice;
    private final BigDecimal     stopLoss;
    private final BigDecimal     target1;        // T1 = 1:3 RR minimum
    private final BigDecimal     target2;        // T2 = 1:4 RR
    private final int            quantity;
    private final BigDecimal     riskAmount;
    private final String         strategyName;   // always "SMC_INSTITUTIONAL_V1"
    private final double         probabilityScore;
    private final String         sectorName;
    private final double         rrRatio;
    private final String         setupType;
    private final int            confidenceScore;
    private final int            totalScore;
    private final int            timeStopMinutes;
    private final boolean        liquiditySweepDetected;
    private final Instant        signalTimestamp;

    public SmcSignalEvent(Object source,
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
                          double rrRatio,
                          String setupType,
                          int confidenceScore,
                          int totalScore,
                          int timeStopMinutes,
                          boolean liquiditySweepDetected) {
        super(source);
        this.tradingSymbol         = tradingSymbol;
        this.instrumentToken       = instrumentToken;
        this.direction             = direction;
        this.entryPrice            = entryPrice;
        this.stopLoss              = stopLoss;
        this.target1               = target1;
        this.target2               = target2;
        this.quantity              = quantity;
        this.riskAmount            = riskAmount;
        this.strategyName          = strategyName;
        this.probabilityScore      = probabilityScore;
        this.sectorName            = sectorName;
        this.rrRatio               = rrRatio;
        this.setupType             = setupType;
        this.confidenceScore       = confidenceScore;
        this.totalScore            = totalScore;
        this.timeStopMinutes       = timeStopMinutes;
        this.liquiditySweepDetected = liquiditySweepDetected;
        this.signalTimestamp       = Instant.now();
    }
}