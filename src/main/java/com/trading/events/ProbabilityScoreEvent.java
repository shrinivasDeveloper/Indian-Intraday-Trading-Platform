package com.trading.events;

import com.trading.domain.enums.TradeDirection;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * ProbabilityScoreEvent — carries a fully-scored trade signal through the pipeline.
 *
 * STATUS: Stub event — currently no strategy engine publishes this event.
 * The former StrategyEvaluatorService that fired this event has been removed.
 * This class is retained so that PaperTradeExecutionService and
 * RiskManagementService compile without modification, and will be activated
 * automatically once a new strategy engine is wired in.
 *
 * When a new strategy engine is added, it should:
 *   1. Compute a probability score (0–100) for a candidate signal.
 *   2. Construct a ProbabilityScoreEvent using the full constructor below.
 *   3. Call applicationEventPublisher.publishEvent(event).
 *   RiskManagementService will gate-check the event and publish TradeApprovedEvent
 *   if all slots and circuit breaker conditions pass.
 *
 * STALE SIGNAL GUARD (Flaw 3 fix — still active):
 *   signalTimestamp is set at signal-fire time.
 *   Any consumer should reject events where (now - signalTimestamp) > threshold.
 *   Default threshold: 30 seconds (configurable in the consumer).
 *
 * IMPACT SLIPPAGE:
 *   impactSlipPct carries the dynamic entry slippage computed by
 *   PositionSizerService.ImpactCostCalculator at signal time.
 *   0.0 means "not computed" — PaperTradeExecutionService falls back to its
 *   static ENTRY_SLIP constant when this is zero.
 */
@Getter
public class ProbabilityScoreEvent extends ApplicationEvent {

    private final String         tradingSymbol;
    private final long           instrumentToken;
    private final BigDecimal     totalScore;
    private final String         decision;
    private final TradeDirection direction;
    private final BigDecimal     entryPrice;
    private final BigDecimal     stopLoss;
    private final BigDecimal     target;
    private final String         strategyName;
    private final BigDecimal     regimeScore;
    private final BigDecimal     sectorScore;
    private final BigDecimal     structureScore;
    private final BigDecimal     patternScore;
    private final BigDecimal     volumeScore;
    private final BigDecimal     vwapScore;
    private final BigDecimal     volatilityScore;
    private final BigDecimal     liquidityScore;

    /** Strategy time stop in minutes (0 = no time stop) */
    private final int timeStopMinutes;

    /**
     * Timestamp set at signal-fire time.
     * Consumers use this to reject stale signals (default: reject if > 30s old).
     */
    private final Instant signalTimestamp;

    /**
     * Dynamic entry slippage % computed by ImpactCostCalculator at signal time.
     * Replaces the flat ENTRY_SLIP=0.0005 constant in PaperTradeExecutionService.
     * 0.0 means "not computed" — PaperTradeExecutionService falls back to formula.
     */
    private final double impactSlipPct;

    // ── Original 18-param constructor — backward compatible ──────────────────
    public ProbabilityScoreEvent(Object src, String sym, long token,
                                 BigDecimal score, String decision,
                                 TradeDirection dir, BigDecimal entry,
                                 BigDecimal sl, BigDecimal tgt, String strategy,
                                 BigDecimal r, BigDecimal s, BigDecimal st,
                                 BigDecimal p, BigDecimal v, BigDecimal vw,
                                 BigDecimal vo, BigDecimal l) {
        this(src, sym, token, score, decision, dir, entry, sl, tgt, strategy,
                r, s, st, p, v, vw, vo, l, 0, Instant.now(), 0.0);
    }

    // ── 19-param constructor — carries timeStopMinutes ───────────────────────
    public ProbabilityScoreEvent(Object src, String sym, long token,
                                 BigDecimal score, String decision,
                                 TradeDirection dir, BigDecimal entry,
                                 BigDecimal sl, BigDecimal tgt, String strategy,
                                 BigDecimal r, BigDecimal s, BigDecimal st,
                                 BigDecimal p, BigDecimal v, BigDecimal vw,
                                 BigDecimal vo, BigDecimal l,
                                 int timeStopMinutes) {
        this(src, sym, token, score, decision, dir, entry, sl, tgt, strategy,
                r, s, st, p, v, vw, vo, l, timeStopMinutes, Instant.now(), 0.0);
    }

    // ── Full constructor — carries all fields including timestamp + impactSlip
    public ProbabilityScoreEvent(Object src, String sym, long token,
                                 BigDecimal score, String decision,
                                 TradeDirection dir, BigDecimal entry,
                                 BigDecimal sl, BigDecimal tgt, String strategy,
                                 BigDecimal r, BigDecimal s, BigDecimal st,
                                 BigDecimal p, BigDecimal v, BigDecimal vw,
                                 BigDecimal vo, BigDecimal l,
                                 int timeStopMinutes,
                                 Instant signalTimestamp,
                                 double impactSlipPct) {
        super(src);
        tradingSymbol    = sym;
        instrumentToken  = token;
        totalScore       = score;
        this.decision    = decision;
        direction        = dir;
        entryPrice       = entry;
        stopLoss         = sl;
        this.target      = tgt;
        strategyName     = strategy;
        regimeScore      = r;
        sectorScore      = s;
        structureScore   = st;
        patternScore     = p;
        volumeScore      = v;
        vwapScore        = vw;
        volatilityScore  = vo;
        liquidityScore   = l;
        this.timeStopMinutes  = timeStopMinutes;
        this.signalTimestamp  = signalTimestamp != null ? signalTimestamp : Instant.now();
        this.impactSlipPct    = impactSlipPct;
    }
}