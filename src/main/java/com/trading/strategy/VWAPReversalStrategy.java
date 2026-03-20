// ── VWAPReversalStrategy.java ─────────────────────────────────────
package com.trading.strategy;

import com.trading.domain.Candle;
import com.trading.domain.enums.TradeDirection;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.Optional;

@Component
public class VWAPReversalStrategy implements TradingStrategy {

    @Override public String name() { return "VWAP_REVERSAL"; }

    @Override
    public Optional<TradeSignal> generateSignal(String symbol,
                                                  List<Candle> candles1m,
                                                  List<Candle> candles15m,
                                                  MarketContext ctx) {
        if (candles15m.size() < 20) return Optional.empty();
        if (!ctx.regime().isTradeable()) return Optional.empty();
        if (!ctx.structure().vwapConfluence()) return Optional.empty();

        Candle     current = candles15m.get(0);
        BigDecimal vwap    = ctx.structure().vwap();
        BigDecimal price   = current.getClose();
        if (vwap.compareTo(BigDecimal.ZERO) == 0) return Optional.empty();

        BigDecimal atr  = atr(candles15m, 14);
        BigDecimal dist = price.subtract(vwap).abs().divide(vwap, MathContext.DECIMAL32);
        if (dist.compareTo(new BigDecimal("0.003")) > 0) return Optional.empty();

        if (current.isBullish() && ctx.regime().isLongFavourable()) {
            BigDecimal sl     = vwap.subtract(atr.multiply(new BigDecimal("0.5")));
            BigDecimal risk   = price.subtract(sl);
            BigDecimal target = price.add(risk.multiply(BigDecimal.valueOf(2)));
            return Optional.of(new TradeSignal(TradeDirection.LONG, price, sl, target, 76, name()));
        }
        if (current.isBearish() && ctx.regime().isShortFavourable()) {
            BigDecimal sl     = vwap.add(atr.multiply(new BigDecimal("0.5")));
            BigDecimal risk   = sl.subtract(price);
            BigDecimal target = price.subtract(risk.multiply(BigDecimal.valueOf(2)));
            return Optional.of(new TradeSignal(TradeDirection.SHORT, price, sl, target, 76, name()));
        }
        return Optional.empty();
    }

    private BigDecimal atr(List<Candle> c, int p) {
        int n = Math.min(p, c.size() - 1);
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            BigDecimal tr = c.get(i).getHigh().subtract(c.get(i).getLow()).abs()
                .max(c.get(i).getHigh().subtract(c.get(i+1).getClose()).abs())
                .max(c.get(i).getLow().subtract(c.get(i+1).getClose()).abs());
            sum = sum.add(tr);
        }
        return n == 0 ? BigDecimal.ZERO : sum.divide(BigDecimal.valueOf(n), MathContext.DECIMAL32);
    }
}
