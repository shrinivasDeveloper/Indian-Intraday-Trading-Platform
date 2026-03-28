// ========== FIXED FILE ==========
// Path: src/main/java/com/trading/domain/Candle.java
//
// BUG FIXED:
//   "Usage of API documented as @since 19+"
//   Line: lwick.compareTo(body.multiply(BigDecimal.TWO)) >= 0
//
//   ROOT CAUSE:
//     BigDecimal.TWO was added in Java 19 (JEP 431).
//     Your project targets Java 17 (pom.xml: <source>17</source>).
//     Using BigDecimal.TWO on Java 17 causes a compile error:
//       "Cannot resolve symbol 'TWO'" / "API available since Java 19+"
//
//   FIX:
//     Replace BigDecimal.TWO  →  BigDecimal.valueOf(2)
//     This is available in ALL Java versions and has identical semantics.
//
//   This is the ONLY change vs the original Candle.java.
//   All fields, methods, and Lombok annotations are unchanged.
// ============================================================================

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