package com.trading.events;

import com.trading.domain.enums.TradeDirection;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * ProbabilityScoreEvent — fired by StrategyEvaluatorService when a signal passes
 * validation. Consumed by RiskManagementService.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * FLAW 3 FIX — Stale Signal Guard:
 *   Added signalTimestamp (Instant) field set at the moment the signal fires.
 *   RiskManagementService Gate-0 rejects the event if
 *   (now - signalTimestamp) > max-signal-age-seconds (default 30s).
 *
 *   Why: With @Async("tradingExecutor"), the event queue can back up during
 *   a market spike. A signal that was valid at 09:45:00 may be executed at
 *   09:45:35 when the price has already moved 0.8% — entering a stale breakout.
 *
 * BACKWARD COMPATIBILITY:
 *   Original 18-param constructor preserved — defaults timeStopMinutes=0
 *   and signalTimestamp=Instant.now() (safe default, gate will not reject
 *   events constructed without explicit timestamp).
 * ═══════════════════════════════════════════════════════════════════════
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
     * FLAW 3 FIX: Timestamp set at signal-fire time.
     * RiskManagementService uses this to reject signals older than 30s.
     */
    private final Instant signalTimestamp;

    /**
     * Dynamic entry slippage % computed by ImpactCostCalculator at signal time.
     * Replaces the flat ENTRY_SLIP=0.0005 constant in PaperTradeExecutionService.
     * 0.0 means "not computed" — PaperTradeExecutionService falls back to formula.
     */
    private final double impactSlipPct;

    // ── Original 18-param constructor — backward compatible ───────────────────
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

    // ── 19-param constructor — carries timeStopMinutes ────────────────────────
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

    // ── Full constructor — carries all fields including timestamp + impactSlip ─
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