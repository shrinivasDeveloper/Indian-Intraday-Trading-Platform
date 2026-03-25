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
 * Strategy 4 — ORB + VWAP + Sector + Nifty (9:30–12:30 only)
 *
 * Runs INDEPENDENTLY. Does NOT need 7-gate scanner.
 *
 * ALL 9 conditions must pass for BUY:
 *   1. Time: 9:30–12:30
 *   2. Nifty Bullish: niftyBullish=true + change ≥+0.3% + HH/HL in 15min
 *   3. Stock price > VWAP
 *   4. Sector in Top 2 (sectorIsTop=true) + sectorChangePct ≥+0.5% + sectorAlignedBull
 *   5. Stock breaks ORH (first 15min high)
 *   6. Retest: price came back to ORH or VWAP before current candle
 *   7. Confirmation candle: body ≥55%, close near high
 *   8. HH/HL in last 3 × 5min candles
 *   9. Volume ≥1.5× average
 */
@Component
@Slf4j
public class ORBStrategy implements TradingStrategy {

    @Value("${strategy.orb.nifty-min-change-pct:0.3}")
    private double niftyMinChangePct;

    @Value("${strategy.orb.sector-min-change-pct:0.5}")
    private double sectorMinChangePct;

    @Value("${strategy.orb.volume-min-multiplier:1.5}")
    private double volumeMultiplier;

    @Value("${strategy.orb.retest-tolerance-pct:0.3}")
    private double retestTolPct;

    @Value("${strategy.orb.body-ratio-min:0.55}")
    private double bodyRatioMin;

    @Value("${strategy.orb.wick-ratio-max:0.35}")
    private double wickRatioMax;

    @Value("${strategy.orb.rr:2.5}")
    private double rr;

    @Value("${strategy.orb.entry-start:09:30}")
    private String entryStart;

    @Value("${strategy.orb.entry-end:12:30}")
    private String entryEnd;

    @Value("${strategy.orb.orb-candles:3}")
    private int orbCandles;

    @Override
    public String name() { return "ORB_VWAP_SECTOR"; }

    @Override
    public Optional<TradeSignal> generateSignal(String symbol,
                                                List<Candle> candles5m,
                                                List<Candle> candles15m,
                                                TradingStrategy.MarketContext ctx) {
        // CONDITION 1: Time filter
        if (!withinTime()) return Optional.empty();
        if (candles5m.size() < orbCandles + 5) return Optional.empty();
        if (candles15m.size() < 6) return Optional.empty();

        Candle cur  = candles5m.get(0);
        BigDecimal vwap = ctx.vwap();
        if (vwap.compareTo(BigDecimal.ZERO) == 0) return Optional.empty();

        // Opening Range = oldest orbCandles entries (most recent = index 0, oldest = last)
        int start = Math.max(0, candles5m.size() - orbCandles);
        List<Candle> orbPeriod = candles5m.subList(start, candles5m.size());
        BigDecimal orbH = orbPeriod.stream().map(Candle::getHigh).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal orbL = orbPeriod.stream().map(Candle::getLow).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        if (orbH.compareTo(BigDecimal.ZERO) == 0 || orbL.compareTo(BigDecimal.ZERO) == 0)
            return Optional.empty();

        // Average volume (last 20 candles, skip current)
        double avgVol = candles5m.subList(1, Math.min(21, candles5m.size()))
                .stream().mapToLong(Candle::getVolume).average().orElse(1);

        // ── BUY: all 9 conditions ──────────────────────────────────────
        boolean c2_nifty  = ctx.niftyBullish()
                && ctx.niftyChangePct() >= niftyMinChangePct
                && hasHHHL(candles15m.subList(0, Math.min(6, candles15m.size())));
        boolean c3_vwap   = cur.getClose().compareTo(vwap) > 0;
        boolean c4_sector = ctx.sectorIsTop()
                && ctx.sectorChangePct() >= sectorMinChangePct
                && ctx.sectorAlignedBull();
        boolean c5_orb    = cur.getClose().compareTo(orbH) > 0;
        boolean c6_retest = retestOccurred(candles5m.subList(1, Math.min(5, candles5m.size())), orbH, vwap);
        boolean c7_candle = isStrongBullCandle(cur);
        boolean c8_struct = hasHHHL(candles5m.subList(0, Math.min(4, candles5m.size())));
        boolean c9_vol    = cur.getVolume() >= avgVol * volumeMultiplier;

        log.debug("[ORB] {} BUY nifty={} vwap={} sector={} orb={} retest={} candle={} struct={} vol={}",
                symbol, c2_nifty, c3_vwap, c4_sector, c5_orb, c6_retest, c7_candle, c8_struct, c9_vol);

        if (c2_nifty && c3_vwap && c4_sector && c5_orb && c6_retest && c7_candle && c8_struct && c9_vol) {
            BigDecimal sl   = orbL.multiply(new BigDecimal("0.999"));
            BigDecimal risk = cur.getClose().subtract(sl);
            if (risk.compareTo(BigDecimal.ZERO) <= 0) return Optional.empty();
            BigDecimal target = cur.getClose().add(risk.multiply(BigDecimal.valueOf(rr)));
            log.info("[ORB] BUY {} orbH={} entry={} sl={} target={}", symbol, orbH, cur.getClose(), sl, target);
            return Optional.of(new TradeSignal(TradeDirection.LONG, cur.getClose(), sl, target, 85, name()));
        }

        // ── SELL: mirror conditions ────────────────────────────────────
        boolean s2 = ctx.niftyBearish()
                && ctx.niftyChangePct() <= -niftyMinChangePct
                && hasLHLL(candles15m.subList(0, Math.min(6, candles15m.size())));
        boolean s3 = cur.getClose().compareTo(vwap) < 0;
        boolean s4 = ctx.sectorIsBottom()
                && ctx.sectorChangePct() <= -sectorMinChangePct
                && ctx.sectorAlignedBear();
        boolean s5 = cur.getClose().compareTo(orbL) < 0;
        boolean s6 = retestOccurred(candles5m.subList(1, Math.min(5, candles5m.size())), orbL, vwap);
        boolean s7 = isStrongBearCandle(cur);
        boolean s8 = hasLHLL(candles5m.subList(0, Math.min(4, candles5m.size())));

        if (s2 && s3 && s4 && s5 && s6 && s7 && s8 && c9_vol) {
            BigDecimal sl   = orbH.multiply(new BigDecimal("1.001"));
            BigDecimal risk = sl.subtract(cur.getClose());
            if (risk.compareTo(BigDecimal.ZERO) <= 0) return Optional.empty();
            BigDecimal target = cur.getClose().subtract(risk.multiply(BigDecimal.valueOf(rr)));
            log.info("[ORB] SELL {} orbL={} entry={} sl={} target={}", symbol, orbL, cur.getClose(), sl, target);
            return Optional.of(new TradeSignal(TradeDirection.SHORT, cur.getClose(), sl, target, 85, name()));
        }

        return Optional.empty();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private boolean retestOccurred(List<Candle> prev, BigDecimal level, BigDecimal vwap) {
        BigDecimal tol  = level.multiply(BigDecimal.valueOf(retestTolPct / 100.0));
        BigDecimal vtol = vwap.compareTo(BigDecimal.ZERO) > 0
                ? vwap.multiply(BigDecimal.valueOf(retestTolPct / 100.0)) : BigDecimal.ZERO;
        for (Candle c : prev) {
            boolean atLevel = c.getLow().subtract(level).abs().compareTo(tol) <= 0
                    || c.getHigh().subtract(level).abs().compareTo(tol) <= 0;
            boolean atVwap  = vwap.compareTo(BigDecimal.ZERO) > 0
                    && (c.getLow().subtract(vwap).abs().compareTo(vtol) <= 0
                    || c.getHigh().subtract(vwap).abs().compareTo(vtol) <= 0);
            if (atLevel || atVwap) return true;
        }
        return false;
    }

    private boolean isStrongBullCandle(Candle c) {
        if (!c.isBullish()) return false;
        BigDecimal range = c.getHigh().subtract(c.getLow());
        if (range.compareTo(BigDecimal.ZERO) == 0) return false;
        double bodyR = c.getClose().subtract(c.getOpen()).divide(range, MathContext.DECIMAL32).doubleValue();
        double uwR   = c.getHigh().subtract(c.getClose()).divide(range, MathContext.DECIMAL32).doubleValue();
        return bodyR >= bodyRatioMin && uwR <= wickRatioMax;
    }

    private boolean isStrongBearCandle(Candle c) {
        if (!c.isBearish()) return false;
        BigDecimal range = c.getHigh().subtract(c.getLow());
        if (range.compareTo(BigDecimal.ZERO) == 0) return false;
        double bodyR = c.getOpen().subtract(c.getClose()).divide(range, MathContext.DECIMAL32).doubleValue();
        double lwR   = c.getClose().subtract(c.getLow()).divide(range, MathContext.DECIMAL32).doubleValue();
        return bodyR >= bodyRatioMin && lwR <= wickRatioMax;
    }

    private boolean hasHHHL(List<Candle> c) {
        if (c.size() < 3) return false;
        int count = 0;
        for (int i = 0; i < c.size() - 1; i++)
            if (c.get(i).getHigh().compareTo(c.get(i+1).getHigh()) > 0
                    && c.get(i).getLow().compareTo(c.get(i+1).getLow()) > 0) count++;
        return count >= 2;
    }

    private boolean hasLHLL(List<Candle> c) {
        if (c.size() < 3) return false;
        int count = 0;
        for (int i = 0; i < c.size() - 1; i++)
            if (c.get(i).getHigh().compareTo(c.get(i+1).getHigh()) < 0
                    && c.get(i).getLow().compareTo(c.get(i+1).getLow()) < 0) count++;
        return count >= 2;
    }

    private boolean withinTime() {
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        return !now.isBefore(LocalTime.parse(entryStart))
                && !now.isAfter(LocalTime.parse(entryEnd));
    }
}