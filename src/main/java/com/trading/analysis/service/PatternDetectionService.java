package com.trading.analysis.service;

import com.trading.domain.Candle;
import com.trading.domain.enums.TradeDirection;
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
public class PatternDetectionService {

    private final Map<String, Deque<Candle>>  buffers      = new ConcurrentHashMap<>();
    private final Map<String, PatternResult>  patternCache = new ConcurrentHashMap<>();

    @EventListener
    @Async("tradingExecutor")
    public void onCandle(CandleCompleteEvent e) {
        Candle c = e.getCandle();
        if (!"15minute".equals(c.getTimeframe())) return;

        Deque<Candle> buf = buffers.computeIfAbsent(c.getTradingSymbol(), k -> new ArrayDeque<>());
        buf.addFirst(c);
        if (buf.size() > 100) ((ArrayDeque<Candle>) buf).removeLast();

        List<Candle> h = new ArrayList<>(buf);
        if (h.size() < 20) return;

        PatternResult result = detect(c.getTradingSymbol(), h);
        if (result.patternFound())
            log.info("PATTERN: {} {} score={}", c.getTradingSymbol(), result.patternName(), result.score());
        patternCache.put(c.getTradingSymbol(), result);
    }

    private PatternResult detect(String symbol, List<Candle> candles) {
        PatternResult r;
        r = detectTripleTop(symbol, candles);    if (r.patternFound()) return r;
        r = detectTripleBottom(symbol, candles); if (r.patternFound()) return r;
        r = detectBreakout(symbol, candles);     if (r.patternFound()) return r;
        r = detectFakeBreakout(symbol, candles); if (r.patternFound()) return r;
        r = detectRejectionCandle(symbol, candles); if (r.patternFound()) return r;
        return PatternResult.none(symbol);
    }

    private PatternResult detectTripleTop(String symbol, List<Candle> candles) {
        List<Integer> peaks = findLocalPeaks(candles, 5);
        if (peaks.size() < 3) return PatternResult.none(symbol);
        for (int i = 0; i <= peaks.size() - 3; i++) {
            Candle p1 = candles.get(peaks.get(i));
            Candle p2 = candles.get(peaks.get(i + 1));
            Candle p3 = candles.get(peaks.get(i + 2));
            BigDecimal avg = p1.getHigh().add(p2.getHigh()).add(p3.getHigh())
                .divide(BigDecimal.valueOf(3), MathContext.DECIMAL32);
            boolean aligned = isWithinPct(p1.getHigh(), avg, "0.015") &&
                               isWithinPct(p2.getHigh(), avg, "0.015") &&
                               isWithinPct(p3.getHigh(), avg, "0.015");
            boolean volDecline = p3.getVolume() < p2.getVolume() && p2.getVolume() < p1.getVolume();
            if (aligned && volDecline)
                return new PatternResult(symbol, "TRIPLE_TOP", TradeDirection.SHORT,
                    p3.getHigh(), BigDecimal.valueOf(82), true);
        }
        return PatternResult.none(symbol);
    }

    private PatternResult detectTripleBottom(String symbol, List<Candle> candles) {
        List<Integer> troughs = findLocalTroughs(candles, 5);
        if (troughs.size() < 3) return PatternResult.none(symbol);
        for (int i = 0; i <= troughs.size() - 3; i++) {
            Candle t1 = candles.get(troughs.get(i));
            Candle t2 = candles.get(troughs.get(i + 1));
            Candle t3 = candles.get(troughs.get(i + 2));
            BigDecimal avg = t1.getLow().add(t2.getLow()).add(t3.getLow())
                .divide(BigDecimal.valueOf(3), MathContext.DECIMAL32);
            boolean aligned = isWithinPct(t1.getLow(), avg, "0.015") &&
                               isWithinPct(t2.getLow(), avg, "0.015") &&
                               isWithinPct(t3.getLow(), avg, "0.015");
            boolean volDecline = t3.getVolume() < t2.getVolume() && t2.getVolume() < t1.getVolume();
            if (aligned && volDecline)
                return new PatternResult(symbol, "TRIPLE_BOTTOM", TradeDirection.LONG,
                    t3.getLow(), BigDecimal.valueOf(82), true);
        }
        return PatternResult.none(symbol);
    }

    private PatternResult detectBreakout(String symbol, List<Candle> candles) {
        if (candles.size() < 20) return PatternResult.none(symbol);
        Candle current = candles.get(0);
        BigDecimal resistance = candles.subList(1, 20).stream()
            .map(Candle::getHigh).max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
        BigDecimal avgVol = candles.subList(1, 20).stream()
            .map(c -> BigDecimal.valueOf(c.getVolume()))
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal.valueOf(19), MathContext.DECIMAL32);
        boolean priceBreakout = current.getClose().compareTo(resistance) > 0;
        boolean volConfirm    = BigDecimal.valueOf(current.getVolume())
            .compareTo(avgVol.multiply(new BigDecimal("1.5"))) > 0;
        if (priceBreakout && volConfirm)
            return new PatternResult(symbol, "BREAKOUT", TradeDirection.LONG,
                resistance, BigDecimal.valueOf(78), true);
        return PatternResult.none(symbol);
    }

    private PatternResult detectFakeBreakout(String symbol, List<Candle> candles) {
        if (candles.size() < 5) return PatternResult.none(symbol);
        Candle prev    = candles.get(1);
        Candle current = candles.get(0);
        BigDecimal resistance = candles.subList(2, Math.min(20, candles.size())).stream()
            .map(Candle::getHigh).max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
        boolean prevBrokeOut   = prev.getHigh().compareTo(resistance) > 0;
        boolean closedBelow    = prev.getClose().compareTo(resistance) < 0;
        boolean currentBearish = current.isBearish();
        if (prevBrokeOut && closedBelow && currentBearish)
            return new PatternResult(symbol, "FAKE_BREAKOUT", TradeDirection.SHORT,
                resistance, BigDecimal.valueOf(75), true);
        return PatternResult.none(symbol);
    }

    private PatternResult detectRejectionCandle(String symbol, List<Candle> candles) {
        if (candles.isEmpty()) return PatternResult.none(symbol);
        Candle c = candles.get(0);
        BigDecimal range = c.range();
        if (range.compareTo(BigDecimal.ZERO) == 0) return PatternResult.none(symbol);
        BigDecimal upperWick = c.getHigh().subtract(c.getClose().max(c.getOpen()));
        BigDecimal lowerWick = c.getClose().min(c.getOpen()).subtract(c.getLow());
        BigDecimal wickRatio = upperWick.max(lowerWick).divide(range, MathContext.DECIMAL32);
        if (wickRatio.compareTo(new BigDecimal("0.65")) >= 0) {
            TradeDirection dir = upperWick.compareTo(lowerWick) > 0
                ? TradeDirection.SHORT : TradeDirection.LONG;
            return new PatternResult(symbol, "REJECTION_CANDLE", dir,
                c.getClose(), BigDecimal.valueOf(70), true);
        }
        return PatternResult.none(symbol);
    }

    private List<Integer> findLocalPeaks(List<Candle> c, int lb) {
        List<Integer> peaks = new ArrayList<>();
        for (int i = lb; i < c.size() - lb; i++) {
            boolean isPeak = true;
            for (int j = 1; j <= lb; j++) {
                if (c.get(i).getHigh().compareTo(c.get(i-j).getHigh()) <= 0 ||
                    c.get(i).getHigh().compareTo(c.get(i+j).getHigh()) <= 0) {
                    isPeak = false; break;
                }
            }
            if (isPeak) peaks.add(i);
        }
        return peaks;
    }

    private List<Integer> findLocalTroughs(List<Candle> c, int lb) {
        List<Integer> troughs = new ArrayList<>();
        for (int i = lb; i < c.size() - lb; i++) {
            boolean isTrough = true;
            for (int j = 1; j <= lb; j++) {
                if (c.get(i).getLow().compareTo(c.get(i-j).getLow()) >= 0 ||
                    c.get(i).getLow().compareTo(c.get(i+j).getLow()) >= 0) {
                    isTrough = false; break;
                }
            }
            if (isTrough) troughs.add(i);
        }
        return troughs;
    }

    private boolean isWithinPct(BigDecimal a, BigDecimal b, String pct) {
        if (b.compareTo(BigDecimal.ZERO) == 0) return false;
        return a.subtract(b).abs().divide(b, MathContext.DECIMAL32)
            .compareTo(new BigDecimal(pct)) <= 0;
    }

    public PatternResult getPattern(String symbol) {
        return patternCache.getOrDefault(symbol, PatternResult.none(symbol));
    }

    public record PatternResult(
        String        symbol,
        String        patternName,
        TradeDirection direction,
        BigDecimal    keyLevel,
        BigDecimal    score,
        boolean       patternFound
    ) {
        public static PatternResult none(String s) {
            return new PatternResult(s, "NONE", null,
                BigDecimal.ZERO, BigDecimal.valueOf(50), false);
        }
    }
}
