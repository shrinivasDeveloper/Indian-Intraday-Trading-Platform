package com.trading.analysis.service;

import com.trading.domain.Candle;
import com.trading.events.CandleCompleteEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class TechnicalAnalysisService {

    private final Map<String, Deque<Candle>>        buffers        = new ConcurrentHashMap<>();
    private final Map<String, TechnicalStructure>   structureCache = new ConcurrentHashMap<>();

    @EventListener
    @Async("tradingExecutor")
    public void onCandle(CandleCompleteEvent e) {
        Candle c = e.getCandle();
        if (!"15minute".equals(c.getTimeframe())) return;

        Deque<Candle> buf = buffers.computeIfAbsent(c.getTradingSymbol(), k -> new ArrayDeque<>());
        buf.addFirst(c);
        if (buf.size() > 200) ((ArrayDeque<Candle>) buf).removeLast();

        List<Candle> h = new ArrayList<>(buf);
        if (h.size() < 30) return;

        structureCache.put(c.getTradingSymbol(), analyze(c.getTradingSymbol(), h));
    }

    private TechnicalStructure analyze(String symbol, List<Candle> candles) {
        List<BigDecimal> supports    = detectSupportZones(candles);
        List<BigDecimal> resistances = detectResistanceZones(candles);
        BigDecimal       vwap        = calculateVWAP(candles);
        BigDecimal       price       = candles.get(0).getClose();
        boolean          confluence  = isNearVWAP(price, vwap);
        BigDecimal       score       = scoreStructure(supports, resistances, price, vwap);
        return new TechnicalStructure(symbol, supports, resistances, vwap, confluence, score);
    }

    private List<BigDecimal> detectSupportZones(List<Candle> candles) {
        List<BigDecimal> zones = new ArrayList<>();
        for (int i = 2; i < candles.size() - 2; i++) {
            Candle c = candles.get(i);
            if (c.getLow().compareTo(candles.get(i - 1).getLow()) < 0 &&
                c.getLow().compareTo(candles.get(i + 1).getLow()) < 0) {
                if (countTouches(candles, c.getLow(), true) >= 2)
                    zones.add(c.getLow());
            }
        }
        return consolidateZones(zones);
    }

    private List<BigDecimal> detectResistanceZones(List<Candle> candles) {
        List<BigDecimal> zones = new ArrayList<>();
        for (int i = 2; i < candles.size() - 2; i++) {
            Candle c = candles.get(i);
            if (c.getHigh().compareTo(candles.get(i - 1).getHigh()) > 0 &&
                c.getHigh().compareTo(candles.get(i + 1).getHigh()) > 0) {
                if (countTouches(candles, c.getHigh(), false) >= 2)
                    zones.add(c.getHigh());
            }
        }
        return consolidateZones(zones);
    }

    private int countTouches(List<Candle> candles, BigDecimal level, boolean isSupport) {
        BigDecimal tol = level.multiply(new BigDecimal("0.003"));
        int count = 0;
        for (Candle c : candles) {
            BigDecimal price = isSupport ? c.getLow() : c.getHigh();
            if (price.subtract(level).abs().compareTo(tol) <= 0) count++;
        }
        return count;
    }

    private List<BigDecimal> consolidateZones(List<BigDecimal> zones) {
        if (zones.isEmpty()) return zones;
        zones.sort(Comparator.naturalOrder());
        List<BigDecimal> merged = new ArrayList<>();
        BigDecimal cur = zones.get(0);
        for (int i = 1; i < zones.size(); i++) {
            if (zones.get(i).subtract(cur).abs()
                .compareTo(cur.multiply(new BigDecimal("0.005"))) > 0) {
                merged.add(cur);
                cur = zones.get(i);
            }
        }
        merged.add(cur);
        return merged;
    }

    private BigDecimal calculateVWAP(List<Candle> candles) {
        BigDecimal pvSum = BigDecimal.ZERO, volSum = BigDecimal.ZERO;
        for (Candle c : candles) {
            BigDecimal typical = c.getHigh().add(c.getLow()).add(c.getClose())
                .divide(BigDecimal.valueOf(3), MathContext.DECIMAL32);
            BigDecimal vol = BigDecimal.valueOf(c.getVolume());
            pvSum  = pvSum.add(typical.multiply(vol));
            volSum = volSum.add(vol);
        }
        return volSum.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
            : pvSum.divide(volSum, MathContext.DECIMAL32);
    }

    private boolean isNearVWAP(BigDecimal price, BigDecimal vwap) {
        if (vwap.compareTo(BigDecimal.ZERO) == 0) return false;
        return price.subtract(vwap).abs().divide(vwap, MathContext.DECIMAL32)
            .compareTo(new BigDecimal("0.003")) <= 0;
    }

    private BigDecimal scoreStructure(List<BigDecimal> supports, List<BigDecimal> resistances,
                                       BigDecimal price, BigDecimal vwap) {
        BigDecimal score = BigDecimal.valueOf(40);
        if (!supports.isEmpty())    score = score.add(BigDecimal.valueOf(20));
        if (!resistances.isEmpty()) score = score.add(BigDecimal.valueOf(20));
        if (isNearVWAP(price, vwap))score = score.add(BigDecimal.valueOf(20));
        return score.min(BigDecimal.valueOf(100));
    }

    public TechnicalStructure getStructure(String symbol) {
        return structureCache.getOrDefault(symbol, TechnicalStructure.empty(symbol));
    }

    public record TechnicalStructure(
        String           symbol,
        List<BigDecimal> supportZones,
        List<BigDecimal> resistanceZones,
        BigDecimal       vwap,
        boolean          vwapConfluence,
        BigDecimal       structureScore
    ) {
        public static TechnicalStructure empty(String s) {
            return new TechnicalStructure(s, List.of(), List.of(),
                BigDecimal.ZERO, false, BigDecimal.valueOf(50));
        }
    }
}
