package com.trading.events;

import com.trading.domain.enums.TradeDirection;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;
import java.math.BigDecimal;

/**
 * TradeApprovedEvent — fired when all risk gates pass for a trade signal.
 *
 * STATUS: Stub event — currently no service publishes this event.
 * The former StrategyEvaluatorService and onProbabilityScore() pipeline
 * that ultimately fired this event have been removed.
 * This class is retained so that PaperTradeExecutionService and
 * TradeExecutionService compile without modification.
 *
 * When a new strategy engine is wired in, the publishing flow is:
 *   NewStrategyEngine → ProbabilityScoreEvent
 *     → RiskManagementService (gates: slot count, circuit breaker, sector)
 *     → TradeApprovedEvent
 *     → PaperTradeExecutionService.onTradeApproved() (PAPER mode)
 *     → TradeExecutionService.onTradeApproved()      (LIVE mode)
 *
 * timeStopMinutes pipeline (previously broken, now correct):
 *   The timeStopMinutes field flows from ProbabilityScoreEvent → TradeApprovedEvent
 *   → PaperTradeExecutionService → PaperTradeManagementService.register()
 *   → enforced on every tick in manageTrade().
 *   Strategies that don't specify a time stop pass 0, which falls back to
 *   StrategyConfig.global.globalTimeStop (default 30 minutes).
 *
 * Backward compatibility:
 *   The original 11-param constructor defaults timeStopMinutes to 0.
 *   All existing callers continue to compile unchanged.
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

    /** Strategy-specific time stop in minutes (0 = none, use 15:00 IST only) */
    private final int timeStopMinutes;

    // ── Original 11-param constructor — backward compatible ──────────────────
    // All existing callers continue to compile unchanged. timeStopMinutes = 0.
    public TradeApprovedEvent(Object src, String sym, long token,
                              TradeDirection dir, BigDecimal entry,
                              BigDecimal sl, BigDecimal tgt,
                              int qty, BigDecimal risk,
                              BigDecimal score, String strategy) {
        this(src, sym, token, dir, entry, sl, tgt, qty, risk, score, strategy, 0);
    }

    // ── Full 12-param constructor — carries timeStopMinutes ──────────────────
    // To be called by RiskManagementService after reading timeStopMinutes
    // from ProbabilityScoreEvent, once a new strategy engine is active.
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