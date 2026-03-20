package com.trading.strategy;

import com.trading.domain.Candle;
import com.trading.domain.enums.TradeDirection;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Component
public class MomentumStrategy implements TradingStrategy {

    @Override public String name() { return "MOMENTUM"; }

    @Override
    public Optional<TradeSignal> generateSignal(String symbol,
                                                  List<Candle> candles1m,
                                                  List<Candle> candles15m,
                                                  MarketContext ctx) {
        if (candles15m.size() < 5) return Optional.empty();
        if (!ctx.regime().isTradeable()) return Optional.empty();

        Candle c0 = candles15m.get(0);
        Candle c1 = candles15m.get(1);
        Candle c2 = candles15m.get(2);

        boolean up = c0.isBullish() && c1.isBullish() && c2.isBullish()
            && c0.getVolume() > c1.getVolume() && c1.getVolume() > c2.getVolume()
            && c0.getClose().compareTo(c1.getClose()) > 0;

        boolean down = c0.isBearish() && c1.isBearish() && c2.isBearish()
            && c0.getVolume() > c1.getVolume() && c1.getVolume() > c2.getVolume()
            && c0.getClose().compareTo(c1.getClose()) < 0;

        if (up && ctx.regime().isLongFavourable()) {
            BigDecimal entry  = c0.getClose();
            BigDecimal sl     = c0.getLow();
            BigDecimal risk   = entry.subtract(sl);
            BigDecimal target = entry.add(risk.multiply(new BigDecimal("1.5")));
            return Optional.of(new TradeSignal(TradeDirection.LONG, entry, sl, target, 75, name()));
        }
        if (down && ctx.regime().isShortFavourable()) {
            BigDecimal entry  = c0.getClose();
            BigDecimal sl     = c0.getHigh();
            BigDecimal risk   = sl.subtract(entry);
            BigDecimal target = entry.subtract(risk.multiply(new BigDecimal("1.5")));
            return Optional.of(new TradeSignal(TradeDirection.SHORT, entry, sl, target, 75, name()));
        }
        return Optional.empty();
    }
}
