// ========== MODIFIED FILE ==========
// Path: src/main/java/com/trading/analysis/service/KeyLevelService.java
// CHANGES vs original:
//   Only calculatePOC() is fixed. Everything else is IDENTICAL to your original.
//   Bug: The bucket math collapsed all prices into the same bucket.
//   Fix: Proper 0.05 tick-size bucketing with correct reconstruction.
// ============================================================================

package com.trading.analysis.service;

import com.trading.domain.Candle;
import com.trading.events.CandleCompleteEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gate 5 – Key Level Detection.
 *
 * Scans last 50 candles on 15-minute chart.
 * Finds price levels tested at least twice within 0.3% tolerance.
 * Recent tests weighted more heavily.
 * Also calculates Point of Control (POC) – price with most volume today.
 *
 * FIX in calculatePOC():
 *   OLD (broken):
 *     long bucket = Math.round(price / (price * 0.0005)) * Math.round(price * 0.0005);
 *   This simplifies to:
 *     = Math.round(1/0.0005) * Math.round(price * 0.0005)
 *     = 2000 * Math.round(price * 0.0005)
 *   For NIFTY at 22000: 2000 * Math.round(22000 * 0.0005) = 2000 * 11 = 22000.
 *   Every candle maps to the SAME bucket → POC is always just the last price. Wrong.
 *
 *   NEW (correct):
 *     Bucket on 5-paise (₹0.05) grid — the smallest NSE tick size.
 *     bucket = Math.round(price / TICK_SIZE)
 *     pocPrice = pocBucket * TICK_SIZE
 */
@Service
@Slf4j
public class KeyLevelService {

    // NSE minimum tick size (5 paise)
    private static final double TICK_SIZE = 0.05;

    private final Map<String, Deque<Candle>>  buffers15m = new ConcurrentHashMap<>();
    private final Map<String, Deque<Candle>>  buffers5m  = new ConcurrentHashMap<>();
    private final Map<String, KeyLevelResult> cache      = new ConcurrentHashMap<>();

    public record KeyLevel(
            BigDecimal price,
            int        touches,
            double     strength,   // weighted by recency
            boolean    isSupport,
            boolean    isResistance
    ) {}

    public record KeyLevelResult(
            String         symbol,
            List<KeyLevel> supports,
            List<KeyLevel> resistances,
            BigDecimal     poc,        // Point of Control
            BigDecimal     vwap
    ) {
        public boolean isNearKeyLevel(BigDecimal price, boolean forLong, double tolerancePct) {
            List<KeyLevel> levels = forLong ? resistances : supports;
            for (KeyLevel l : levels) {
                double diff = Math.abs(price.subtract(l.price())
                        .divide(l.price(), MathContext.DECIMAL32).doubleValue());
                if (diff <= tolerancePct / 100.0) return true;
            }
            return false;
        }

        public boolean isAbovePoc(BigDecimal price) {
            return poc != null && price.compareTo(poc) > 0;
        }

        public boolean isBelowPoc(BigDecimal price) {
            return poc != null && price.compareTo(poc) < 0;
        }
    }

    @EventListener
    @Async("tradingExecutor")
    public void onCandle(CandleCompleteEvent event) {
        Candle c = event.getCandle();

        if ("15minute".equals(c.getTimeframe())) {
            Deque<Candle> buf = buffers15m.computeIfAbsent(
                    c.getTradingSymbol(), k -> new ArrayDeque<>());
            buf.addFirst(c);
            if (buf.size() > 50) ((ArrayDeque<Candle>) buf).removeLast();

            if (buf.size() >= 10) {
                cache.put(c.getTradingSymbol(),
                        analyze(c.getTradingSymbol(), new ArrayList<>(buf)));
            }
        }

        if ("5minute".equals(c.getTimeframe())) {
            Deque<Candle> buf = buffers5m.computeIfAbsent(
                    c.getTradingSymbol(), k -> new ArrayDeque<>());
            buf.addFirst(c);
            if (buf.size() > 78) ((ArrayDeque<Candle>) buf).removeLast(); // ~1 day
        }
    }

    private KeyLevelResult analyze(String symbol, List<Candle> candles) {
        List<KeyLevel> supports     = findSupportLevels(candles);
        List<KeyLevel> resistances  = findResistanceLevels(candles);
        BigDecimal     poc          = calculatePOC(candles);
        BigDecimal     vwap         = calculateVWAP(candles);

        return new KeyLevelResult(symbol, supports, resistances, poc, vwap);
    }

    private List<KeyLevel> findSupportLevels(List<Candle> candles) {
        List<KeyLevel> levels = new ArrayList<>();
        double tol = 0.003; // 0.3%

        for (int i = 2; i < candles.size() - 2; i++) {
            Candle c = candles.get(i);
            // Local low
            if (c.getLow().compareTo(candles.get(i-1).getLow()) < 0
                    && c.getLow().compareTo(candles.get(i+1).getLow()) < 0) {

                int    touches  = 0;
                double strength = 0;
                for (int j = 0; j < candles.size(); j++) {
                    Candle other = candles.get(j);
                    double diff  = Math.abs(other.getLow().subtract(c.getLow())
                            .divide(c.getLow(), MathContext.DECIMAL32).doubleValue());
                    if (diff <= tol) {
                        touches++;
                        strength += 1.0 / (j + 1);
                    }
                }

                if (touches >= 2) {
                    levels.add(new KeyLevel(c.getLow(), touches, strength, true, false));
                }
            }
        }

        levels.sort((a, b) -> Double.compare(b.strength(), a.strength()));
        return levels.subList(0, Math.min(5, levels.size()));
    }

    private List<KeyLevel> findResistanceLevels(List<Candle> candles) {
        List<KeyLevel> levels = new ArrayList<>();
        double tol = 0.003;

        for (int i = 2; i < candles.size() - 2; i++) {
            Candle c = candles.get(i);
            if (c.getHigh().compareTo(candles.get(i-1).getHigh()) > 0
                    && c.getHigh().compareTo(candles.get(i+1).getHigh()) > 0) {

                int    touches  = 0;
                double strength = 0;
                for (int j = 0; j < candles.size(); j++) {
                    Candle other = candles.get(j);
                    double diff  = Math.abs(other.getHigh().subtract(c.getHigh())
                            .divide(c.getHigh(), MathContext.DECIMAL32).doubleValue());
                    if (diff <= tol) {
                        touches++;
                        strength += 1.0 / (j + 1);
                    }
                }

                if (touches >= 2) {
                    levels.add(new KeyLevel(c.getHigh(), touches, strength, false, true));
                }
            }
        }

        levels.sort((a, b) -> Double.compare(b.strength(), a.strength()));
        return levels.subList(0, Math.min(5, levels.size()));
    }

    /**
     * Point of Control – price level with highest volume today.
     *
     * FIX: Old code used:
     *   long bucket = Math.round(price / (price * 0.0005)) * Math.round(price * 0.0005);
     * which simplifies to 2000 * Math.round(price*0.0005), meaning every Nifty candle
     * maps to the same bucket (~price), making POC meaningless.
     *
     * New code: bucket on 5-paise grid (NSE minimum tick), then reconstruct price.
     */
    private BigDecimal calculatePOC(List<Candle> candles) {
        if (candles.isEmpty()) return BigDecimal.ZERO;

        // Build volume profile on 5-paise (₹0.05) tick-size grid
        // bucket = Math.round(price / 0.05)  →  price = bucket * 0.05
        Map<Long, Long> volProfile = new TreeMap<>();
        for (Candle c : candles) {
            double close  = c.getClose().doubleValue();
            long   bucket = Math.round(close / TICK_SIZE);
            volProfile.merge(bucket, c.getVolume(), Long::sum);
        }

        // Find bucket with maximum accumulated volume
        long maxVol    = 0;
        long pocBucket = 0;
        for (Map.Entry<Long, Long> e : volProfile.entrySet()) {
            if (e.getValue() > maxVol) {
                maxVol    = e.getValue();
                pocBucket = e.getKey();
            }
        }

        // Reconstruct price from bucket number
        double pocPrice = pocBucket * TICK_SIZE;
        return BigDecimal.valueOf(pocPrice).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateVWAP(List<Candle> candles) {
        BigDecimal pvSum  = BigDecimal.ZERO;
        BigDecimal volSum = BigDecimal.ZERO;
        for (Candle c : candles) {
            BigDecimal typical = c.getHigh().add(c.getLow()).add(c.getClose())
                    .divide(BigDecimal.valueOf(3), MathContext.DECIMAL32);
            BigDecimal vol = BigDecimal.valueOf(c.getVolume());
            pvSum  = pvSum.add(typical.multiply(vol));
            volSum = volSum.add(vol);
        }
        return volSum.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : pvSum.divide(volSum, MathContext.DECIMAL32);
    }

    public KeyLevelResult getKeyLevels(String symbol) {
        return cache.getOrDefault(symbol,
                new KeyLevelResult(symbol, List.of(), List.of(),
                        BigDecimal.ZERO, BigDecimal.ZERO));
    }
}