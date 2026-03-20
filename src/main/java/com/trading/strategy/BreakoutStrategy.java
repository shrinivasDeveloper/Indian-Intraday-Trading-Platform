package com.trading.strategy;

import com.trading.domain.Candle;
import com.trading.domain.enums.TradeDirection;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Component
public class BreakoutStrategy implements TradingStrategy {

    @Override public String name() { return "BREAKOUT"; }

    @Override
    public Optional<TradeSignal> generateSignal(String symbol,
                                                  List<Candle> candles1m,
                                                  List<Candle> candles15m,
                                                  MarketContext ctx) {
        if (candles15m.size() < 21) return Optional.empty();
        if (!ctx.regime().isLongFavourable()) return Optional.empty();

        Candle current = candles15m.get(0);

        BigDecimal resistance = candles15m.subList(1, 21).stream()
            .map(Candle::getHigh).max(Comparator.naturalOrder())
            .orElse(BigDecimal.ZERO);

        BigDecimal avgVol = candles15m.subList(1, 21).stream()
            .map(c -> BigDecimal.valueOf(c.getVolume()))
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal.valueOf(20), MathContext.DECIMAL32);

        boolean priceBreakout = current.getClose().compareTo(resistance) > 0;
        boolean volConfirm    = BigDecimal.valueOf(current.getVolume())
            .compareTo(avgVol.multiply(new BigDecimal("1.5"))) > 0;

        if (!priceBreakout || !volConfirm) return Optional.empty();

        BigDecimal entry  = current.getClose();
        BigDecimal sl     = current.getLow();
        BigDecimal risk   = entry.subtract(sl);
        BigDecimal target = entry.add(risk.multiply(BigDecimal.valueOf(2)));

        return Optional.of(new TradeSignal(TradeDirection.LONG, entry, sl, target, 80, name()));
    }
}
