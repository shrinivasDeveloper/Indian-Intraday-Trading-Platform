package com.trading.events;

import com.trading.domain.enums.TradeDirection;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;
import java.math.BigDecimal;

@Getter
public class ProbabilityScoreEvent extends ApplicationEvent {
    private final String        tradingSymbol;
    private final long          instrumentToken;
    private final BigDecimal    totalScore;
    private final String        decision;
    private final TradeDirection direction;
    private final BigDecimal    entryPrice;
    private final BigDecimal    stopLoss;
    private final BigDecimal    target;
    private final String        strategyName;
    private final BigDecimal    regimeScore;
    private final BigDecimal    sectorScore;
    private final BigDecimal    structureScore;
    private final BigDecimal    patternScore;
    private final BigDecimal    volumeScore;
    private final BigDecimal    vwapScore;
    private final BigDecimal    volatilityScore;
    private final BigDecimal    liquidityScore;

    public ProbabilityScoreEvent(Object src, String sym, long token,
                                  BigDecimal score, String decision,
                                  TradeDirection dir, BigDecimal entry,
                                  BigDecimal sl, BigDecimal tgt, String strategy,
                                  BigDecimal r, BigDecimal s, BigDecimal st,
                                  BigDecimal p, BigDecimal v, BigDecimal vw,
                                  BigDecimal vo, BigDecimal l) {
        super(src);
        tradingSymbol = sym;     instrumentToken = token;
        totalScore = score;      this.decision = decision;
        direction = dir;         entryPrice = entry;
        stopLoss = sl;           this.target = tgt;
        strategyName = strategy; regimeScore = r;
        sectorScore = s;         structureScore = st;
        patternScore = p;        volumeScore = v;
        vwapScore = vw;          volatilityScore = vo;
        liquidityScore = l;
    }
}
