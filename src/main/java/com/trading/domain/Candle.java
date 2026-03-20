package com.trading.domain;

import lombok.Builder;
import lombok.Value;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;

@Value
@Builder
public class Candle {
    long       instrumentToken;
    String     tradingSymbol;
    String     timeframe;
    BigDecimal open;
    BigDecimal high;
    BigDecimal low;
    BigDecimal close;
    long       volume;
    long       oi;
    Instant    candleTime;
    boolean    complete;

    public BigDecimal range()     { return high.subtract(low); }
    public boolean    isBullish() { return close.compareTo(open) > 0; }
    public boolean    isBearish() { return close.compareTo(open) < 0; }
    public BigDecimal bodyPct()   {
        return range().compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
            : close.subtract(open).abs().divide(range(), MathContext.DECIMAL32);
    }
}
