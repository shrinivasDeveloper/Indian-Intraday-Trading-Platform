package com.trading.events;

import com.trading.domain.enums.TradeDirection;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;
import java.math.BigDecimal;

/**
 * ProbabilityScoreEvent — fired by StrategyEvaluatorService when a signal passes
 * validation. Consumed by RiskManagementService.
 *
 * FIX: Added timeStopMinutes field so the strategy's time-stop instruction
 * survives the full event pipeline:
 *
 *   TradeSignal.timeStopMinutes
 *     → StrategyEvaluatorService.fireProbabilityEvent(... timeStopMinutes)
 *     → ProbabilityScoreEvent.timeStopMinutes          ← this class
 *     → RiskManagementService reads it
 *     → TradeApprovedEvent.timeStopMinutes
 *     → PaperTradeExecutionService reads it
 *     → PaperTradeManagementService.register(... timeStopMinutes)
 *     → enforced on every tick
 *
 * Backward compatibility:
 *   - Old 18-param constructor kept, defaults timeStopMinutes to 0.
 *   - New 19-param constructor adds timeStopMinutes as the last param.
 *   - All existing callers continue to compile with no changes.
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

    /** FIX: strategy time stop in minutes (0 = no time stop) */
    private final int timeStopMinutes;

    // ── Original 18-param constructor — backward compatible ──────────────────
    public ProbabilityScoreEvent(Object src, String sym, long token,
                                 BigDecimal score, String decision,
                                 TradeDirection dir, BigDecimal entry,
                                 BigDecimal sl, BigDecimal tgt, String strategy,
                                 BigDecimal r, BigDecimal s, BigDecimal st,
                                 BigDecimal p, BigDecimal v, BigDecimal vw,
                                 BigDecimal vo, BigDecimal l) {
        this(src, sym, token, score, decision, dir, entry, sl, tgt, strategy,
                r, s, st, p, v, vw, vo, l, 0);
    }

    // ── New 19-param constructor — carries timeStopMinutes ────────────────────
    public ProbabilityScoreEvent(Object src, String sym, long token,
                                 BigDecimal score, String decision,
                                 TradeDirection dir, BigDecimal entry,
                                 BigDecimal sl, BigDecimal tgt, String strategy,
                                 BigDecimal r, BigDecimal s, BigDecimal st,
                                 BigDecimal p, BigDecimal v, BigDecimal vw,
                                 BigDecimal vo, BigDecimal l,
                                 int timeStopMinutes) {
        super(src);
        tradingSymbol    = sym;      instrumentToken = token;
        totalScore       = score;    this.decision   = decision;
        direction        = dir;      entryPrice      = entry;
        stopLoss         = sl;       this.target     = tgt;
        strategyName     = strategy; regimeScore     = r;
        sectorScore      = s;        structureScore  = st;
        patternScore     = p;        volumeScore     = v;
        vwapScore        = vw;       volatilityScore = vo;
        liquidityScore   = l;
        this.timeStopMinutes = timeStopMinutes;
    }
}