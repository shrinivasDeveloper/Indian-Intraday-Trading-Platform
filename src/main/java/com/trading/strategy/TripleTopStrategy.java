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
public class TripleTopStrategy implements TradingStrategy {

    @Override public String name() { return "TRIPLE_TOP_BOTTOM"; }

    @Override
    public Optional<TradeSignal> generateSignal(String symbol,
                                                  List<Candle> candles1m,
                                                  List<Candle> candles15m,
                                                  MarketContext ctx) {
        if (candles15m.size() < 30) return Optional.empty();

        String pattern = ctx.pattern().patternName();
        if (!"TRIPLE_TOP".equals(pattern) && !"TRIPLE_BOTTOM".equals(pattern))
            return Optional.empty();

        Candle     current  = candles15m.get(0);
        BigDecimal keyLevel = ctx.pattern().keyLevel();
        int        lookback = Math.min(30, candles15m.size());

        if ("TRIPLE_TOP".equals(pattern)) {
            BigDecimal neckline = candles15m.subList(0, lookback).stream()
                .map(Candle::getLow).min(Comparator.naturalOrder()).orElse(current.getLow());
            if (current.getClose().compareTo(neckline) < 0) {
                BigDecimal height = keyLevel.subtract(neckline);
                BigDecimal target = neckline.subtract(height);
                BigDecimal sl     = keyLevel.multiply(new BigDecimal("1.003"));
                return Optional.of(new TradeSignal(TradeDirection.SHORT,
                    current.getClose(), sl, target,
                    ctx.pattern().score().doubleValue(), name()));
            }
        }

        if ("TRIPLE_BOTTOM".equals(pattern)) {
            BigDecimal neckline = candles15m.subList(0, lookback).stream()
                .map(Candle::getHigh).max(Comparator.naturalOrder()).orElse(current.getHigh());
            if (current.getClose().compareTo(neckline) > 0) {
                BigDecimal height = neckline.subtract(keyLevel);
                BigDecimal target = neckline.add(height);
                BigDecimal sl     = keyLevel.multiply(new BigDecimal("0.997"));
                return Optional.of(new TradeSignal(TradeDirection.LONG,
                    current.getClose(), sl, target,
                    ctx.pattern().score().doubleValue(), name()));
            }
        }
        return Optional.empty();
    }
}
