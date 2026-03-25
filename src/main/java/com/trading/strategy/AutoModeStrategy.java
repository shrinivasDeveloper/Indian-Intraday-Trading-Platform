package com.trading.strategy;

import com.trading.domain.Candle;
import com.trading.domain.enums.TradeDirection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.Optional;

/**
 * Strategy 2 — Auto Mode (Trend / Reversal / Range)
 *
 * Runs INDEPENDENTLY on every 5min candle.
 * Does NOT need 7-gate scanner to pass first.
 *
 * TREND:    sector moving ≥0.5%, aligned with market, clear HH/HL or LH/LL
 * REVERSAL: stock moved ≥4% from open, exhaustion candle (long wick)
 * RANGE:    sector sideways, no clear trend, low volatility
 * MIXED:    skip — no clear setup
 */
@Component
@Slf4j
public class AutoModeStrategy implements TradingStrategy {

    @Value("${strategy.auto-mode.sector-move-min-pct:0.5}")
    private double sectorMoveMinPct;

    @Value("${strategy.auto-mode.reversal-gain-min-pct:4.0}")
    private double reversalGainMinPct;

    @Value("${strategy.auto-mode.range-sector-max-pct:0.3}")
    private double rangeSectorMaxPct;

    @Value("${strategy.auto-mode.wick-ratio-min:0.6}")
    private double wickRatioMin;

    @Value("${strategy.auto-mode.range-breakout-volume:2.0}")
    private double rangeBreakoutVolume;

    @Value("${strategy.auto-mode.trend-rr:2.5}")
    private double trendRR;

    @Value("${strategy.auto-mode.reversal-rr:2.0}")
    private double reversalRR;

    @Value("${strategy.auto-mode.range-rr:1.5}")
    private double rangeRR;

    @Override
    public String name() { return "AUTO_MODE"; }

    @Override
    public Optional<TradeSignal> generateSignal(String symbol,
                                                List<Candle> candles5m,
                                                List<Candle> candles15m,
                                                TradingStrategy.MarketContext ctx) {
        if (candles5m.size() < 20 || candles15m.size() < 10) return Optional.empty();

        Mode mode = detectMode(ctx, candles5m, candles15m);
        if (mode == Mode.MIXED) return Optional.empty();

        log.debug("[AUTO_MODE] {} mode={}", symbol, mode);

        return switch (mode) {
            case TREND    -> trendSignal(symbol, candles5m, candles15m, ctx);
            case REVERSAL -> reversalSignal(symbol, candles5m, candles15m, ctx);
            case RANGE    -> rangeSignal(symbol, candles5m, ctx);
            case MIXED    -> Optional.empty();
        };
    }

    // ── Mode detection ────────────────────────────────────────────────────

    private Mode detectMode(TradingStrategy.MarketContext ctx,
                            List<Candle> c5m, List<Candle> c15m) {
        double absSectorChg = Math.abs(ctx.sectorChangePct());
        boolean extreme    = isGainerOrLoser(c15m);
        boolean exhaustion = !c5m.isEmpty() && hasExhaustionCandle(c5m.get(0));

        if (extreme && exhaustion) return Mode.REVERSAL;

        boolean sectorAligned = ctx.sectorAlignedBull() || ctx.sectorAlignedBear();
        boolean sectorMoving  = absSectorChg >= sectorMoveMinPct;
        boolean clearTrend    = hasClearTrend(c15m);
        boolean vwapAligned   = isVwapAligned(c5m, ctx);
        if (sectorMoving && sectorAligned && clearTrend && vwapAligned) return Mode.TREND;

        boolean sectorSideways = absSectorChg <= rangeSectorMaxPct;
        if (sectorSideways && !clearTrend && isLowVolatility(c5m)) return Mode.RANGE;

        return Mode.MIXED;
    }

    // ── TREND MODE ────────────────────────────────────────────────────────

    private Optional<TradeSignal> trendSignal(String symbol, List<Candle> c5m,
                                              List<Candle> c15m,
                                              TradingStrategy.MarketContext ctx) {
        Candle cur  = c5m.get(0);
        BigDecimal vwap = ctx.vwap();
        if (vwap.compareTo(BigDecimal.ZERO) == 0) return Optional.empty();

        BigDecimal orbH = orbHigh(c5m);
        BigDecimal orbL = orbLow(c5m);
        double avgVol = avgVolume(c5m, 20);
        boolean volOk = cur.getVolume() >= avgVol * 1.3;

        // LONG
        if (ctx.niftyBullish() && ctx.sectorAlignedBull()
                && cur.getClose().compareTo(vwap) > 0
                && cur.getClose().compareTo(orbH) > 0
                && isStrongBullCandle(cur)
                && wasRetested(c5m, orbH, vwap)
                && volOk
                && hasHHHL(c5m.subList(0, Math.min(4, c5m.size())))) {
            BigDecimal sl   = orbL.multiply(new BigDecimal("0.999"));
            BigDecimal risk = cur.getClose().subtract(sl);
            if (risk.compareTo(BigDecimal.ZERO) <= 0) return Optional.empty();
            BigDecimal target = cur.getClose().add(risk.multiply(BigDecimal.valueOf(trendRR)));
            log.info("[AUTO_MODE] TREND LONG: {} entry={} sl={}", symbol, cur.getClose(), sl);
            return Optional.of(new TradeSignal(TradeDirection.LONG, cur.getClose(), sl, target, 82,
                    name() + "_TREND_LONG"));
        }

        // SHORT
        if (ctx.niftyBearish() && ctx.sectorAlignedBear()
                && cur.getClose().compareTo(vwap) < 0
                && cur.getClose().compareTo(orbL) < 0
                && isStrongBearCandle(cur)
                && wasRetested(c5m, orbL, vwap)
                && volOk
                && hasLHLL(c5m.subList(0, Math.min(4, c5m.size())))) {
            BigDecimal sl   = orbH.multiply(new BigDecimal("1.001"));
            BigDecimal risk = sl.subtract(cur.getClose());
            if (risk.compareTo(BigDecimal.ZERO) <= 0) return Optional.empty();
            BigDecimal target = cur.getClose().subtract(risk.multiply(BigDecimal.valueOf(trendRR)));
            log.info("[AUTO_MODE] TREND SHORT: {} entry={} sl={}", symbol, cur.getClose(), sl);
            return Optional.of(new TradeSignal(TradeDirection.SHORT, cur.getClose(), sl, target, 82,
                    name() + "_TREND_SHORT"));
        }

        return Optional.empty();
    }

    // ── REVERSAL MODE ─────────────────────────────────────────────────────

    private Optional<TradeSignal> reversalSignal(String symbol, List<Candle> c5m,
                                                 List<Candle> c15m,
                                                 TradingStrategy.MarketContext ctx) {
        if (c5m.size() < 5) return Optional.empty();
        Candle cur  = c5m.get(0);
        Candle prev = c5m.get(1);
        Candle pre2 = c5m.get(2);
        BigDecimal vwap = ctx.vwap();
        if (vwap.compareTo(BigDecimal.ZERO) == 0) return Optional.empty();

        boolean longWick = hasExhaustionCandle(cur);
        double avgVol = avgVolume(c5m, 10);
        boolean volSpike = cur.getVolume() >= avgVol * 1.2;

        // SHORT reversal
        if (cur.isBearish() && longWick
                && cur.getClose().compareTo(vwap) >= 0
                && !ctx.sectorAlignedBull()
                && prev.getLow().compareTo(pre2.getLow()) >= 0
                && volSpike) {
            BigDecimal swingH = recentSwingHigh(c5m, 6);
            BigDecimal sl = swingH.multiply(new BigDecimal("1.003"));
            BigDecimal risk = sl.subtract(cur.getClose());
            if (risk.compareTo(BigDecimal.ZERO) <= 0) return Optional.empty();
            BigDecimal target = cur.getClose().subtract(risk.multiply(BigDecimal.valueOf(reversalRR)));
            log.info("[AUTO_MODE] REVERSAL SHORT: {} entry={} sl={}", symbol, cur.getClose(), sl);
            return Optional.of(new TradeSignal(TradeDirection.SHORT, cur.getClose(), sl, target, 78,
                    name() + "_REVERSAL_SHORT"));
        }

        // LONG reversal
        if (cur.isBullish() && longWick
                && cur.getClose().compareTo(vwap) <= 0
                && !ctx.sectorAlignedBear()
                && prev.getHigh().compareTo(pre2.getHigh()) <= 0
                && volSpike) {
            BigDecimal swingL = recentSwingLow(c5m, 6);
            BigDecimal sl = swingL.multiply(new BigDecimal("0.997"));
            BigDecimal risk = cur.getClose().subtract(sl);
            if (risk.compareTo(BigDecimal.ZERO) <= 0) return Optional.empty();
            BigDecimal target = cur.getClose().add(risk.multiply(BigDecimal.valueOf(reversalRR)));
            log.info("[AUTO_MODE] REVERSAL LONG: {} entry={} sl={}", symbol, cur.getClose(), sl);
            return Optional.of(new TradeSignal(TradeDirection.LONG, cur.getClose(), sl, target, 78,
                    name() + "_REVERSAL_LONG"));
        }

        return Optional.empty();
    }

    // ── RANGE MODE ────────────────────────────────────────────────────────

    private Optional<TradeSignal> rangeSignal(String symbol, List<Candle> c5m,
                                              TradingStrategy.MarketContext ctx) {
        if (c5m.size() < 14) return Optional.empty();
        Candle cur  = c5m.get(0);
        BigDecimal vwap = ctx.vwap();

        List<Candle> box = c5m.subList(1, 13);
        BigDecimal boxH = box.stream().map(Candle::getHigh).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal boxL = box.stream().map(Candle::getLow).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        if (boxH.compareTo(BigDecimal.ZERO) == 0 || boxL.compareTo(BigDecimal.ZERO) == 0) return Optional.empty();

        double rangePct = boxH.subtract(boxL).divide(boxL, MathContext.DECIMAL32).doubleValue() * 100;
        if (rangePct > 2.5) return Optional.empty();

        if (!hasVolumeContraction(box)) return Optional.empty();

        double avgVol = avgVolume(box, box.size());
        if (cur.getVolume() < avgVol * rangeBreakoutVolume) return Optional.empty();

        // LONG
        if (cur.getClose().compareTo(boxH) > 0 && cur.isBullish()
                && (vwap.compareTo(BigDecimal.ZERO) == 0 || cur.getClose().compareTo(vwap) > 0)
                && isStrongBullCandle(cur)) {
            BigDecimal risk = cur.getClose().subtract(boxL);
            if (risk.compareTo(BigDecimal.ZERO) <= 0) return Optional.empty();
            BigDecimal target = cur.getClose().add(risk.multiply(BigDecimal.valueOf(rangeRR)));
            log.info("[AUTO_MODE] RANGE LONG: {} entry={} sl={}", symbol, cur.getClose(), boxL);
            return Optional.of(new TradeSignal(TradeDirection.LONG, cur.getClose(), boxL, target, 75,
                    name() + "_RANGE_LONG"));
        }

        // SHORT
        if (cur.getClose().compareTo(boxL) < 0 && cur.isBearish()
                && (vwap.compareTo(BigDecimal.ZERO) == 0 || cur.getClose().compareTo(vwap) < 0)
                && isStrongBearCandle(cur)) {
            BigDecimal risk = boxH.subtract(cur.getClose());
            if (risk.compareTo(BigDecimal.ZERO) <= 0) return Optional.empty();
            BigDecimal target = cur.getClose().subtract(risk.multiply(BigDecimal.valueOf(rangeRR)));
            log.info("[AUTO_MODE] RANGE SHORT: {} entry={} sl={}", symbol, cur.getClose(), boxH);
            return Optional.of(new TradeSignal(TradeDirection.SHORT, cur.getClose(), boxH, target, 75,
                    name() + "_RANGE_SHORT"));
        }

        return Optional.empty();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private boolean isGainerOrLoser(List<Candle> c15m) {
        if (c15m.size() < 2) return false;
        Candle first = c15m.get(c15m.size() - 1);
        Candle last  = c15m.get(0);
        if (first.getOpen().compareTo(BigDecimal.ZERO) == 0) return false;
        double chg = Math.abs(last.getClose().subtract(first.getOpen())
                .divide(first.getOpen(), MathContext.DECIMAL32).doubleValue() * 100);
        return chg >= reversalGainMinPct;
    }

    private boolean hasExhaustionCandle(Candle c) {
        BigDecimal range = c.getHigh().subtract(c.getLow());
        if (range.compareTo(BigDecimal.ZERO) == 0) return false;
        BigDecimal uw = c.getHigh().subtract(c.getClose().max(c.getOpen()));
        BigDecimal lw = c.getClose().min(c.getOpen()).subtract(c.getLow());
        double wr = uw.max(lw).divide(range, MathContext.DECIMAL32).doubleValue();
        return wr >= wickRatioMin;
    }

    private boolean hasClearTrend(List<Candle> c) {
        if (c.size() < 6) return false;
        int hhhl = 0, lhll = 0;
        for (int i = 0; i < 5; i++) {
            if (c.get(i).getHigh().compareTo(c.get(i+1).getHigh()) > 0
                    && c.get(i).getLow().compareTo(c.get(i+1).getLow()) > 0) hhhl++;
            else if (c.get(i).getHigh().compareTo(c.get(i+1).getHigh()) < 0
                    && c.get(i).getLow().compareTo(c.get(i+1).getLow()) < 0) lhll++;
        }
        return hhhl >= 3 || lhll >= 3;
    }

    private boolean isVwapAligned(List<Candle> c5m, TradingStrategy.MarketContext ctx) {
        if (c5m.isEmpty()) return false;
        BigDecimal price = c5m.get(0).getClose();
        BigDecimal vwap  = ctx.vwap();
        if (vwap.compareTo(BigDecimal.ZERO) == 0) return true;
        if (ctx.niftyBullish()) return price.compareTo(vwap) > 0;
        if (ctx.niftyBearish()) return price.compareTo(vwap) < 0;
        return false;
    }

    private boolean isLowVolatility(List<Candle> c5m) {
        if (c5m.size() < 10) return false;
        double avgRange = c5m.subList(1, 10).stream()
                .mapToDouble(c -> c.getHigh().subtract(c.getLow()).doubleValue())
                .average().orElse(0);
        double curRange = c5m.get(0).getHigh().subtract(c5m.get(0).getLow()).doubleValue();
        return curRange <= avgRange * 1.2;
    }

    private boolean hasVolumeContraction(List<Candle> candles) {
        if (candles.size() < 3) return false;
        return candles.get(0).getVolume() < candles.get(1).getVolume()
                && candles.get(1).getVolume() < candles.get(2).getVolume();
    }

    private boolean isStrongBullCandle(Candle c) {
        if (!c.isBullish()) return false;
        BigDecimal range = c.getHigh().subtract(c.getLow());
        if (range.compareTo(BigDecimal.ZERO) == 0) return false;
        double bodyR = c.getClose().subtract(c.getOpen()).divide(range, MathContext.DECIMAL32).doubleValue();
        double uwR   = c.getHigh().subtract(c.getClose()).divide(range, MathContext.DECIMAL32).doubleValue();
        return bodyR >= 0.55 && uwR <= 0.35;
    }

    private boolean isStrongBearCandle(Candle c) {
        if (!c.isBearish()) return false;
        BigDecimal range = c.getHigh().subtract(c.getLow());
        if (range.compareTo(BigDecimal.ZERO) == 0) return false;
        double bodyR = c.getOpen().subtract(c.getClose()).divide(range, MathContext.DECIMAL32).doubleValue();
        double lwR   = c.getClose().subtract(c.getLow()).divide(range, MathContext.DECIMAL32).doubleValue();
        return bodyR >= 0.55 && lwR <= 0.35;
    }

    private boolean wasRetested(List<Candle> c5m, BigDecimal level, BigDecimal vwap) {
        if (c5m.size() < 3) return false;
        BigDecimal tol  = level.multiply(new BigDecimal("0.003"));
        BigDecimal vtol = vwap.compareTo(BigDecimal.ZERO) > 0
                ? vwap.multiply(new BigDecimal("0.003")) : BigDecimal.ZERO;
        for (Candle c : c5m.subList(1, Math.min(5, c5m.size()))) {
            boolean atLevel = c.getLow().subtract(level).abs().compareTo(tol) <= 0
                    || c.getHigh().subtract(level).abs().compareTo(tol) <= 0;
            boolean atVwap = vwap.compareTo(BigDecimal.ZERO) > 0
                    && (c.getLow().subtract(vwap).abs().compareTo(vtol) <= 0
                    || c.getHigh().subtract(vwap).abs().compareTo(vtol) <= 0);
            if (atLevel || atVwap) return true;
        }
        return false;
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

    private BigDecimal orbHigh(List<Candle> c) {
        int start = Math.max(0, c.size() - 3);
        return c.subList(start, c.size()).stream()
                .map(Candle::getHigh).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
    }

    private BigDecimal orbLow(List<Candle> c) {
        int start = Math.max(0, c.size() - 3);
        return c.subList(start, c.size()).stream()
                .map(Candle::getLow).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
    }

    private BigDecimal recentSwingHigh(List<Candle> c, int n) {
        return c.subList(0, Math.min(n, c.size())).stream()
                .map(Candle::getHigh).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
    }

    private BigDecimal recentSwingLow(List<Candle> c, int n) {
        return c.subList(0, Math.min(n, c.size())).stream()
                .map(Candle::getLow).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
    }

    private double avgVolume(List<Candle> c, int n) {
        int end = Math.min(n + 1, c.size());
        if (end <= 1) return 1;
        return c.subList(1, end).stream().mapToLong(Candle::getVolume).average().orElse(1);
    }

    private double avgVolume(List<Candle> c) {
        if (c.isEmpty()) return 1;
        return c.stream().mapToLong(Candle::getVolume).average().orElse(1);
    }

    private enum Mode { TREND, REVERSAL, RANGE, MIXED }
}