package com.trading.strategy;

import com.trading.domain.Candle;
import com.trading.domain.enums.TradeDirection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * Strategy 3 — Multi-Touch Range Breakout (9:45–11:30 only)
 *
 * Runs INDEPENDENTLY. Does NOT need 7-gate scanner.
 *
 * ALL conditions must pass:
 *   1. Time: 9:45–11:30
 *   2. Consolidation 30–60 min (12 × 5min candles)
 *   3. Tight range < maxRangePct%
 *   4. Clean horizontal structure (no spike candles inside)
 *   5. Min 3 touches at resistance AND 3 at support
 *   6. Volume contraction before breakout
 *   7. VWAP filter: BUY above, SELL below
 *   8. Breakout candle: body ≥60%, close near high/low
 *   9. Volume ≥2× consolidation average
 *  10. No fake breakout in last 2 candles
 */
@Component
@Slf4j
public class RangeBreakoutStrategy implements TradingStrategy {

    @Value("${strategy.range-breakout.min-touches:3}")
    private int minTouches;

    @Value("${strategy.range-breakout.touch-tolerance-pct:0.2}")
    private double touchTolerancePct;

    @Value("${strategy.range-breakout.volume-breakout-multiplier:2.0}")
    private double volumeMultiplier;

    @Value("${strategy.range-breakout.body-ratio-min:0.6}")
    private double bodyRatioMin;

    @Value("${strategy.range-breakout.consolidation-candles:12}")
    private int consolidationCandles;

    @Value("${strategy.range-breakout.rr:2.0}")
    private double rr;

    @Value("${strategy.range-breakout.entry-start:09:45}")
    private String entryStart;

    @Value("${strategy.range-breakout.entry-end:11:30}")
    private String entryEnd;

    @Value("${strategy.range-breakout.max-range-pct:3.0}")
    private double maxRangePct;

    @Override
    public String name() { return "RANGE_BREAKOUT_3TOUCH"; }

    @Override
    public Optional<TradeSignal> generateSignal(String symbol,
                                                List<Candle> candles5m,
                                                List<Candle> candles15m,
                                                TradingStrategy.MarketContext ctx) {
        // CONDITION 1: Time filter
        if (!withinTime()) return Optional.empty();
        if (candles5m.size() < consolidationCandles + 2) return Optional.empty();

        Candle cur  = candles5m.get(0);
        BigDecimal vwap = ctx.vwap();

        // CONDITION 2: Consolidation range (skip current candle = index 0)
        List<Candle> con = candles5m.subList(1, Math.min(consolidationCandles + 1, candles5m.size()));
        BigDecimal rH = con.stream().map(Candle::getHigh).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal rL = con.stream().map(Candle::getLow).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        if (rH.compareTo(BigDecimal.ZERO) == 0 || rL.compareTo(BigDecimal.ZERO) == 0) return Optional.empty();

        // CONDITION 3: Tight range
        double rangePct = rH.subtract(rL).divide(rL, MathContext.DECIMAL32).doubleValue() * 100;
        if (rangePct > maxRangePct) {
            log.debug("[RANGE_BK] {} range {}% too wide", symbol, String.format("%.2f", rangePct));
            return Optional.empty();
        }

        // CONDITION 4: Clean range (no spike candles)
        if (!isCleanRange(con, rH, rL)) return Optional.empty();

        // CONDITION 5: Touch count
        int rTouches = countTouches(con, rH, false);
        int sTouches = countTouches(con, rL, true);
        if (rTouches < minTouches || sTouches < minTouches) {
            log.debug("[RANGE_BK] {} touches R={} S={} < {}", symbol, rTouches, sTouches, minTouches);
            return Optional.empty();
        }

        // CONDITION 6: Volume contraction
        if (!hasVolumeContraction(con)) {
            log.debug("[RANGE_BK] {} no volume contraction", symbol);
            return Optional.empty();
        }

        // CONDITION 10: No recent fake breakout
        if (hadFakeBreakout(candles5m.subList(1, Math.min(3, candles5m.size())), rH, rL))
            return Optional.empty();

        // CONDITION 9: Volume on breakout candle
        double avgVol = con.stream().mapToLong(Candle::getVolume).average().orElse(1);
        if (cur.getVolume() < avgVol * volumeMultiplier) {
            log.debug("[RANGE_BK] {} vol {}x < {}x", symbol,
                    String.format("%.1f", cur.getVolume() / avgVol), volumeMultiplier);
            return Optional.empty();
        }

        // CONDITION 8: Breakout candle quality
        BigDecimal candleRange = cur.getHigh().subtract(cur.getLow());
        if (candleRange.compareTo(BigDecimal.ZERO) == 0) return Optional.empty();
        BigDecimal body = cur.getClose().subtract(cur.getOpen()).abs();
        double bodyRatio = body.divide(candleRange, MathContext.DECIMAL32).doubleValue();
        if (bodyRatio < bodyRatioMin) {
            log.debug("[RANGE_BK] {} body {}% < {}%", symbol,
                    String.format("%.0f", bodyRatio * 100),
                    String.format("%.0f", bodyRatioMin * 100));
            return Optional.empty();
        }

        // CONDITION 7: VWAP filter
        boolean aboveVwap = vwap.compareTo(BigDecimal.ZERO) == 0
                || cur.getClose().compareTo(vwap) >= 0;
        boolean belowVwap = vwap.compareTo(BigDecimal.ZERO) == 0
                || cur.getClose().compareTo(vwap) <= 0;

        // BUY signal
        if (cur.getClose().compareTo(rH) > 0 && cur.isBullish()
                && aboveVwap && closeNearHigh(cur)) {
            BigDecimal sl   = rL.multiply(new BigDecimal("0.999"));
            BigDecimal risk = cur.getClose().subtract(sl);
            if (risk.compareTo(BigDecimal.ZERO) <= 0) return Optional.empty();
            BigDecimal target = cur.getClose().add(risk.multiply(BigDecimal.valueOf(rr)));
            log.info("[RANGE_BK] BUY {} R={} S={} touches=R{}/S{} vol={}x",
                    symbol, rH, rL, rTouches, sTouches,
                    String.format("%.1f", cur.getVolume() / avgVol));
            return Optional.of(new TradeSignal(TradeDirection.LONG, cur.getClose(), sl, target, 80, name()));
        }

        // SELL signal
        if (cur.getClose().compareTo(rL) < 0 && cur.isBearish()
                && belowVwap && closeNearLow(cur)) {
            BigDecimal sl   = rH.multiply(new BigDecimal("1.001"));
            BigDecimal risk = sl.subtract(cur.getClose());
            if (risk.compareTo(BigDecimal.ZERO) <= 0) return Optional.empty();
            BigDecimal target = cur.getClose().subtract(risk.multiply(BigDecimal.valueOf(rr)));
            log.info("[RANGE_BK] SELL {} R={} S={} touches=R{}/S{} vol={}x",
                    symbol, rH, rL, rTouches, sTouches,
                    String.format("%.1f", cur.getVolume() / avgVol));
            return Optional.of(new TradeSignal(TradeDirection.SHORT, cur.getClose(), sl, target, 80, name()));
        }

        return Optional.empty();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private int countTouches(List<Candle> candles, BigDecimal level, boolean isSupport) {
        if (level.compareTo(BigDecimal.ZERO) == 0) return 0;
        BigDecimal tol = level.multiply(BigDecimal.valueOf(touchTolerancePct / 100.0));
        int count = 0;
        for (Candle c : candles) {
            BigDecimal price = isSupport ? c.getLow() : c.getHigh();
            if (price.subtract(level).abs().compareTo(tol) <= 0) count++;
        }
        return count;
    }

    private boolean isCleanRange(List<Candle> candles, BigDecimal rH, BigDecimal rL) {
        BigDecimal rangeSize = rH.subtract(rL);
        if (rangeSize.compareTo(BigDecimal.ZERO) == 0) return false;
        for (Candle c : candles) {
            BigDecimal body = c.getClose().subtract(c.getOpen()).abs();
            if (body.compareTo(rangeSize.multiply(new BigDecimal("0.70"))) > 0) return false;
        }
        return true;
    }

    private boolean hasVolumeContraction(List<Candle> candles) {
        if (candles.size() < 3) return false;
        return candles.get(0).getVolume() < candles.get(1).getVolume()
                && candles.get(1).getVolume() < candles.get(2).getVolume();
    }

    private boolean hadFakeBreakout(List<Candle> prevCandles, BigDecimal rH, BigDecimal rL) {
        for (Candle c : prevCandles) {
            boolean aboveAndBack = c.getHigh().compareTo(rH) > 0 && c.getClose().compareTo(rH) <= 0;
            boolean belowAndBack = c.getLow().compareTo(rL) < 0  && c.getClose().compareTo(rL) >= 0;
            if (aboveAndBack || belowAndBack) return true;
        }
        return false;
    }

    private boolean closeNearHigh(Candle c) {
        BigDecimal range = c.getHigh().subtract(c.getLow());
        if (range.compareTo(BigDecimal.ZERO) == 0) return false;
        return c.getHigh().subtract(c.getClose()).divide(range, MathContext.DECIMAL32)
                .compareTo(new BigDecimal("0.20")) <= 0;
    }

    private boolean closeNearLow(Candle c) {
        BigDecimal range = c.getHigh().subtract(c.getLow());
        if (range.compareTo(BigDecimal.ZERO) == 0) return false;
        return c.getClose().subtract(c.getLow()).divide(range, MathContext.DECIMAL32)
                .compareTo(new BigDecimal("0.20")) <= 0;
    }

    private boolean withinTime() {
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        return !now.isBefore(LocalTime.parse(entryStart))
                && !now.isAfter(LocalTime.parse(entryEnd));
    }
}