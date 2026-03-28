package com.trading.events;

import com.trading.domain.enums.TradeDirection;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;
import java.math.BigDecimal;

/**
 * TradeApprovedEvent — fired by RiskManagementService when all gates pass.
 *
 * FIX: Added timeStopMinutes field.
 *
 * PROBLEM — the old pipeline broke timeStopMinutes silently:
 *
 *   TradeSignal (has timeStopMinutes=30 for VAP)
 *     ↓ StrategyEvaluatorService.fireProbabilityEvent()
 *   ProbabilityScoreEvent  ← timeStopMinutes dropped here
 *     ↓ RiskManagementService.onProbabilityScore()
 *   TradeApprovedEvent     ← not present here either
 *     ↓ PaperTradeExecutionService.onTradeApproved()
 *   paperManagement.register(..., 0)  ← always 0, time stop never enforced
 *
 * FIX — the field now flows all the way through:
 *   ProbabilityScoreEvent  carries timeStopMinutes (see ProbabilityScoreEvent.java)
 *   RiskManagementService  reads it and passes it here
 *   PaperTradeExecutionService reads event.getTimeStopMinutes() → register()
 *   PaperTradeManagementService enforces it on every tick
 *
 * Backward compatibility: old 11-param constructor kept unchanged.
 * New 12-param constructor adds timeStopMinutes as the last parameter.
 * All existing callers of the 11-param constructor continue to compile — they
 * will produce trades with timeStopMinutes=0 (no time stop), which is correct
 * for strategies that don't specify one.
 */
@Getter
public class TradeApprovedEvent extends ApplicationEvent {

    private final String         tradingSymbol;
    private final long           instrumentToken;
    private final TradeDirection direction;
    private final BigDecimal     entryPrice;
    private final BigDecimal     stopLoss;
    private final BigDecimal     target;
    private final int            quantity;
    private final BigDecimal     riskAmount;
    private final BigDecimal     probabilityScore;
    private final String         strategyName;

    /** FIX: strategy-specific time stop in minutes (0 = none, use 15:00 IST only) */
    private final int timeStopMinutes;

    // ── Original 11-param constructor — backward compatible ──────────────────
    // All existing callers (live TradeExecutionService, tests, etc.) continue
    // to compile unchanged. timeStopMinutes defaults to 0.
    public TradeApprovedEvent(Object src, String sym, long token,
                              TradeDirection dir, BigDecimal entry,
                              BigDecimal sl, BigDecimal tgt,
                              int qty, BigDecimal risk,
                              BigDecimal score, String strategy) {
        this(src, sym, token, dir, entry, sl, tgt, qty, risk, score, strategy, 0);
    }

    // ── New 12-param constructor — carries timeStopMinutes ────────────────────
    // Called by RiskManagementService.onProbabilityScore() after reading
    // timeStopMinutes from ProbabilityScoreEvent.
    public TradeApprovedEvent(Object src, String sym, long token,
                              TradeDirection dir, BigDecimal entry,
                              BigDecimal sl, BigDecimal tgt,
                              int qty, BigDecimal risk,
                              BigDecimal score, String strategy,
                              int timeStopMinutes) {
        super(src);
        tradingSymbol    = sym;      instrumentToken  = token;
        direction        = dir;      entryPrice       = entry;
        stopLoss         = sl;       target           = tgt;
        quantity         = qty;      riskAmount       = risk;
        probabilityScore = score;    strategyName     = strategy;
        this.timeStopMinutes = timeStopMinutes;
    }
}