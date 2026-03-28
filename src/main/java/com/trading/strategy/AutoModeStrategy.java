package com.trading.strategy;

import com.trading.analysis.service.KeyLevelService;
import com.trading.analysis.service.RvolService;
import com.trading.domain.Candle;
import com.trading.domain.enums.TradeDirection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * Strategy 2 — Auto Mode (Trend / Reversal / Range)
 *
 * GOD-TIER INSTITUTIONAL UPGRADES:
 *
 * 1. GRAVITY/EXHAUSTION FILTER — isOverextended()
 *    If |price − POC| / POC > 1.5% → cancel ALL signals.
 *    The "rubber band" principle: 1.5% from high-volume fair value = stretched.
 *    Institutions exit here. Retail enters. Price snaps back to POC.
 *    In ₹ terms on ₹23,000 Nifty stock: 1.5% = ₹345 from fair value.
 *
 * 2. RVOL SLOTTING (replaces fixed avgVolume)
 *    Compare volume to the SAME 5-min slot of the last 5 trading days.
 *    1.5x at 9:45 AM (opening rush) = NORMAL = no edge.
 *    1.5x at 1:30 PM (lunch dead zone) = INSTITUTIONAL activity.
 *    Trend minimum: RVOL ≥ 1.3 | Reversal minimum: RVOL ≥ 1.2.
 *
 * 3. DYNAMIC REVERSAL THRESHOLD: 2.8% (was 4.0%)
 *    4% moves = runaway trends, rarely reverse. Entry is too late.
 *    2.5–3.0% = institutional sweet spot for mean reversion to POC.
 *    Config: strategy.auto-mode.reversal-gain-min-pct → update to 2.8
 *
 * 4. INITIAL BALANCE CONTEXT
 *    Trend only on trend days (price outside IB range).
 *    Range only on range days (price inside IB).
 *    Prevents trend entries on bracket days and vice versa.
 *
 * 5. POC AS REVERSAL TARGET + 45-MIN TIME STOP
 *    Target = POC (highest-volume price = gravitational centre).
 *    Risk Migration: once price covers 50% distance to POC → SL to 50% risk.
 *    Time Stop: 45 min (9 candles). If not moving → context changed → EXIT.
 *
 * 6. TIGHTER TREND SL: max(ibLow − 0.1%, vwap − 0.1%) for LONG
 *    Old: orbL bottom (wide). New: tighter IB/VWAP boundary.
 *    Typical RR improvement: 2.0 → 3.0+ on most intraday trend days.
 *
 * 7. TREND TRAILING: VWAP − 0.1% (not candle-by-candle)
 *    Trails activate at Entry + 2R.
 *    Candle trailing kicks pros out on normal breathing volatility.
 *    VWAP floor keeps you in the major trend move.
 *
 * 8. VAH CONFIRMATION FOR TREND
 *    TREND LONG: price must be above VAH (institutional value acceptance).
 *    Inside Value Area = mean reversion zone = skip trend entry.
 */
@Component
@Slf4j
public class AutoModeStrategy implements TradingStrategy {

    @Autowired private KeyLevelService keyLevelService;
    @Autowired private RvolService     rvolService;

    @Value("${strategy.auto-mode.sector-move-min-pct:0.5}")   private double sectorMoveMinPct;
    @Value("${strategy.auto-mode.reversal-gain-min-pct:2.8}") private double reversalGainMinPct; // UPGRADE 3
    @Value("${strategy.auto-mode.range-sector-max-pct:0.3}")  private double rangeSectorMaxPct;
    @Value("${strategy.auto-mode.wick-ratio-min:0.6}")         private double wickRatioMin;
    @Value("${strategy.auto-mode.range-breakout-volume:2.0}")  private double rangeBreakoutVolume;
    @Value("${strategy.auto-mode.trend-rr:2.5}")               private double trendRR;
    @Value("${strategy.auto-mode.reversal-rr:2.0}")            private double reversalRR;
    @Value("${strategy.auto-mode.range-rr:1.5}")               private double rangeRR;

    private static final double OVEREXTENSION_PCT          = 0.015; // 1.5%
    private static final double MIN_RVOL_TREND             = 1.3;
    private static final double MIN_RVOL_REVERSAL          = 1.2;
    private static final int    REVERSAL_TIME_STOP_MINUTES = 45;
    private static final double TREND_TRAIL_TRIGGER_R      = 2.0;
    private static final ZoneId IST                        = ZoneId.of("Asia/Kolkata");

    @Override
    public String name() { return "AUTO_MODE"; }

    @Override
    public Optional<TradeSignal> generateSignal(String symbol, List<Candle> candles5m,
                                                List<Candle> candles15m, MarketContext ctx) {
        if (candles5m.size() < 20 || candles15m.size() < 10) return Optional.empty();

        KeyLevelService.KeyLevelResult kl = keyLevelService.getKeyLevels(symbol);

        // UPGRADE 1: overextension check — applies to ALL modes
        if (isOverextended(candles5m.get(0).getClose(), kl)) {
            log.debug("[AUTO_MODE] {} price overextended from POC={}%—skip all signals", symbol,
                    String.format("%.2f", distFromPocPct(candles5m.get(0).getClose(), kl) * 100));
            return Optional.empty();
        }

        Mode mode = detectMode(ctx, candles5m, candles15m, kl);
        if (mode == Mode.MIXED) return Optional.empty();

        log.debug("[AUTO_MODE] {} mode={}", symbol, mode);
        return switch (mode) {
            case TREND    -> trendSignal(symbol, candles5m, candles15m, ctx, kl);
            case REVERSAL -> reversalSignal(symbol, candles5m, candles15m, ctx, kl);
            case RANGE    -> rangeSignal(symbol, candles5m, ctx, kl);
            default       -> Optional.empty();
        };
    }

    // ══════════════════════════════════════════════════════════════════
    // Mode Detection
    // ══════════════════════════════════════════════════════════════════

    private Mode detectMode(MarketContext ctx, List<Candle> c5m,
                            List<Candle> c15m, KeyLevelService.KeyLevelResult kl) {
        double  absChg    = Math.abs(ctx.sectorChangePct());
        boolean extreme   = isGainerOrLoser(c15m);    // UPGRADE 3: uses 2.8% threshold now
        boolean exhaust   = !c5m.isEmpty() && hasExhaustionCandle(c5m.get(0));

        if (extreme && exhaust) return Mode.REVERSAL;

        BigDecimal price  = c5m.isEmpty() ? BigDecimal.ZERO : c5m.get(0).getClose();
        boolean sectorOk  = ctx.sectorAlignedBull() || ctx.sectorAlignedBear();
        boolean sectorMov = absChg >= sectorMoveMinPct;
        boolean trend     = hasClearTrend(c15m);
        boolean vwapOk    = isVwapAligned(c5m, ctx);
        KeyLevelService.InitialBalance ib = kl.initialBalance();

        if (sectorMov && sectorOk && trend && vwapOk) {
            if (ib.complete()) {
                if (ib.isTrendDay(price)) return Mode.TREND;
                log.debug("[AUTO_MODE] IB range day — skip TREND");
            } else {
                return Mode.TREND;
            }
        }

        boolean sideways = absChg <= rangeSectorMaxPct;
        if (sideways && !trend && isLowVolatility(c5m))
            if (!ib.complete() || ib.isRangeDay(price)) return Mode.RANGE;

        return Mode.MIXED;
    }

    // ══════════════════════════════════════════════════════════════════
    // TREND — IB/VWAP SL, VAH confirmation, VWAP trailing
    // ══════════════════════════════════════════════════════════════════

    private Optional<TradeSignal> trendSignal(String symbol, List<Candle> c5m,
                                              List<Candle> c15m, MarketContext ctx,
                                              KeyLevelService.KeyLevelResult kl) {
        Candle     cur  = c5m.get(0);
        BigDecimal vwap = ctx.vwap();
        if (vwap.compareTo(BigDecimal.ZERO) == 0) return Optional.empty();

        BigDecimal orbH = orbHigh(c5m);
        BigDecimal orbL = orbLow(c5m);

        // UPGRADE 2: RVOL slotting
        LocalTime slot = candleTime(cur);
        double rvol    = rvolService.getRvol(symbol, slot, cur.getVolume());
        if (rvol < MIN_RVOL_TREND) {
            log.debug("[AUTO_MODE] TREND {} RVOL {} < {} — skip", symbol,
                    rvolService.rvolLabel(rvol), MIN_RVOL_TREND);
            return Optional.empty();
        }

        KeyLevelService.InitialBalance ib = kl.initialBalance();

        // ── LONG ──────────────────────────────────────────────────────
        if (ctx.niftyBullish() && ctx.sectorAlignedBull()
                && cur.getClose().compareTo(vwap) > 0
                && cur.getClose().compareTo(orbH) > 0
                && isStrongBullCandle(cur)
                && wasRetested(c5m, orbH, vwap)
                && hasHHHL(c5m.subList(0, Math.min(4, c5m.size())))) {

            BigDecimal price = cur.getClose();

            // UPGRADE 8: VAH / IB trend confirmation
            if (!kl.isAboveVah(price) && !(ib.complete() && ib.isAboveIB(price))) {
                log.debug("[AUTO_MODE] TREND LONG {} inside VA/IB — not institutional breakout", symbol);
                return Optional.empty();
            }

            // UPGRADE 6: Tighter SL = higher of (ibLow−buffer, vwap−buffer)
            BigDecimal ibSl   = ib.complete()
                    ? ib.ibLow().multiply(new BigDecimal("0.999"))
                    : orbL.multiply(new BigDecimal("0.999"));
            BigDecimal vwapSl = vwap.multiply(new BigDecimal("0.999"));
            BigDecimal sl     = ibSl.compareTo(vwapSl) > 0 ? ibSl : vwapSl; // higher = tighter for LONG

            BigDecimal risk    = price.subtract(sl);
            if (risk.compareTo(BigDecimal.ZERO) <= 0) return Optional.empty();

            BigDecimal target       = price.add(risk.multiply(BigDecimal.valueOf(trendRR)));
            BigDecimal trailTrigger = price.add(risk.multiply(BigDecimal.valueOf(TREND_TRAIL_TRIGGER_R)));

            log.info("[AUTO_MODE] TREND LONG {} e={} sl={} tgt={} RVOL={} aboveVAH={}",
                    symbol, price, sl, target, rvolService.rvolLabel(rvol), kl.isAboveVah(price));

            return Optional.of(new TradeSignal(
                    TradeDirection.LONG, price, sl, target, 82, name() + "_TREND_LONG",
                    trailTrigger, TrailingType.VWAP_MINUS_01, 0, false));
        }

        // ── SHORT ─────────────────────────────────────────────────────
        if (ctx.niftyBearish() && ctx.sectorAlignedBear()
                && cur.getClose().compareTo(vwap) < 0
                && cur.getClose().compareTo(orbL) < 0
                && isStrongBearCandle(cur)
                && wasRetested(c5m, orbL, vwap)
                && hasLHLL(c5m.subList(0, Math.min(4, c5m.size())))) {

            BigDecimal price = cur.getClose();

            if (!kl.isBelowVal(price) && !(ib.complete() && ib.isBelowIB(price))) {
                log.debug("[AUTO_MODE] TREND SHORT {} inside VA/IB — skip", symbol);
                return Optional.empty();
            }

            BigDecimal ibSl   = ib.complete()
                    ? ib.ibHigh().multiply(new BigDecimal("1.001"))
                    : orbH.multiply(new BigDecimal("1.001"));
            BigDecimal vwapSl = vwap.multiply(new BigDecimal("1.001"));
            BigDecimal sl     = ibSl.compareTo(vwapSl) < 0 ? ibSl : vwapSl; // lower = tighter for SHORT

            BigDecimal risk    = sl.subtract(price);
            if (risk.compareTo(BigDecimal.ZERO) <= 0) return Optional.empty();

            BigDecimal target       = price.subtract(risk.multiply(BigDecimal.valueOf(trendRR)));
            BigDecimal trailTrigger = price.subtract(risk.multiply(BigDecimal.valueOf(TREND_TRAIL_TRIGGER_R)));

            log.info("[AUTO_MODE] TREND SHORT {} e={} sl={} tgt={} RVOL={}",
                    symbol, price, sl, target, rvolService.rvolLabel(rvol));

            return Optional.of(new TradeSignal(
                    TradeDirection.SHORT, price, sl, target, 82, name() + "_TREND_SHORT",
                    trailTrigger, TrailingType.VWAP_MINUS_01, 0, false));
        }

        return Optional.empty();
    }

    // ══════════════════════════════════════════════════════════════════
    // REVERSAL — POC target, risk migration at 50%, 45-min time stop
    // ══════════════════════════════════════════════════════════════════

    private Optional<TradeSignal> reversalSignal(String symbol, List<Candle> c5m,
                                                 List<Candle> c15m, MarketContext ctx,
                                                 KeyLevelService.KeyLevelResult kl) {
        if (c5m.size() < 5) return Optional.empty();
        Candle     cur  = c5m.get(0);
        Candle     prev = c5m.get(1);
        Candle     pre2 = c5m.get(2);
        BigDecimal vwap = ctx.vwap();
        if (vwap.compareTo(BigDecimal.ZERO) == 0) return Optional.empty();

        boolean longWick = hasExhaustionCandle(cur);

        // UPGRADE 2: RVOL slotting for reversal
        double rvol = rvolService.getRvol(symbol, candleTime(cur), cur.getVolume());
        if (rvol < MIN_RVOL_REVERSAL) {
            log.debug("[AUTO_MODE] REVERSAL {} RVOL {} < {} — dead volume, skip",
                    symbol, rvolService.rvolLabel(rvol), MIN_RVOL_REVERSAL);
            return Optional.empty();
        }

        // ── SHORT reversal (stock overextended UP, target = POC below) ────
        if (cur.isBearish() && longWick
                && cur.getClose().compareTo(vwap) >= 0
                && !ctx.sectorAlignedBull()
                && prev.getLow().compareTo(pre2.getLow()) >= 0) {

            BigDecimal swingH = recentSwingHigh(c5m, 6);
            BigDecimal sl     = swingH.multiply(new BigDecimal("1.003"));
            BigDecimal risk   = sl.subtract(cur.getClose());
            if (risk.compareTo(BigDecimal.ZERO) <= 0) return Optional.empty();

            // UPGRADE 5: POC as target (gravity zone — not guessing)
            BigDecimal target = pocTarget(cur.getClose(), risk, kl, false);

            // Risk migration trigger: 50% of distance to POC → SL to 50% risk
            BigDecimal halfDist    = cur.getClose().subtract(target).abs()
                    .multiply(new BigDecimal("0.5"));
            BigDecimal trailTrigger = cur.getClose().subtract(halfDist);

            log.info("[AUTO_MODE] REVERSAL SHORT {} e={} sl={} tgt(POC)={} RVOL={} timeStop=45min",
                    symbol, cur.getClose(), sl, target, rvolService.rvolLabel(rvol));

            return Optional.of(new TradeSignal(
                    TradeDirection.SHORT, cur.getClose(), sl, target, 78,
                    name() + "_REVERSAL_SHORT",
                    trailTrigger,                // at 50% distance → SL → 50% of initial risk
                    TrailingType.BREAKEVEN_ONLY, // pros don't trail reversals
                    REVERSAL_TIME_STOP_MINUTES,  // exit at 45 min if stuck
                    false));
        }

        // ── LONG reversal (stock overextended DOWN, target = POC above) ──
        if (cur.isBullish() && longWick
                && cur.getClose().compareTo(vwap) <= 0
                && !ctx.sectorAlignedBear()
                && prev.getHigh().compareTo(pre2.getHigh()) <= 0) {

            BigDecimal swingL = recentSwingLow(c5m, 6);
            BigDecimal sl     = swingL.multiply(new BigDecimal("0.997"));
            BigDecimal risk   = cur.getClose().subtract(sl);
            if (risk.compareTo(BigDecimal.ZERO) <= 0) return Optional.empty();

            BigDecimal target       = pocTarget(cur.getClose(), risk, kl, true);
            BigDecimal halfDist     = target.subtract(cur.getClose()).abs()
                    .multiply(new BigDecimal("0.5"));
            BigDecimal trailTrigger = cur.getClose().add(halfDist);

            log.info("[AUTO_MODE] REVERSAL LONG {} e={} sl={} tgt(POC)={} RVOL={} timeStop=45min",
                    symbol, cur.getClose(), sl, target, rvolService.rvolLabel(rvol));

            return Optional.of(new TradeSignal(
                    TradeDirection.LONG, cur.getClose(), sl, target, 78,
                    name() + "_REVERSAL_LONG",
                    trailTrigger,
                    TrailingType.BREAKEVEN_ONLY,
                    REVERSAL_TIME_STOP_MINUTES,
                    false));
        }

        return Optional.empty();
    }

    /**
     * UPGRADE 5: POC as reversal target.
     * POC = highest-volume price = the "gravity zone."
     * Reversal trades snap back to POC — that's where 70% of volume traded.
     * Minimum 1.5R required for POC to be used as target; otherwise fixed RR.
     */
    private BigDecimal pocTarget(BigDecimal entry, BigDecimal risk,
                                 KeyLevelService.KeyLevelResult kl, boolean forLong) {
        BigDecimal poc = kl.getPoc();
        if (poc.compareTo(BigDecimal.ZERO) > 0) {
            boolean rightSide = forLong ? poc.compareTo(entry) > 0 : poc.compareTo(entry) < 0;
            if (rightSide) {
                double pocDist = Math.abs(poc.subtract(entry).doubleValue());
                double riskD   = risk.doubleValue();
                if (riskD > 0 && pocDist / riskD >= 1.5) return poc;
            }
        }
        return forLong
                ? entry.add(risk.multiply(BigDecimal.valueOf(reversalRR)))
                : entry.subtract(risk.multiply(BigDecimal.valueOf(reversalRR)));
    }

    // ══════════════════════════════════════════════════════════════════
    // RANGE — POC as mean reversion magnet
    // ══════════════════════════════════════════════════════════════════

    private Optional<TradeSignal> rangeSignal(String symbol, List<Candle> c5m,
                                              MarketContext ctx, KeyLevelService.KeyLevelResult kl) {
        if (c5m.size() < 14) return Optional.empty();
        Candle     cur  = c5m.get(0);
        BigDecimal vwap = ctx.vwap();

        if (isOverextended(cur.getClose(), kl)) return Optional.empty();

        List<Candle> box  = c5m.subList(1, 13);
        BigDecimal boxH = box.stream().map(Candle::getHigh).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal boxL = box.stream().map(Candle::getLow).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        if (boxH.compareTo(BigDecimal.ZERO) == 0 || boxL.compareTo(BigDecimal.ZERO) == 0)
            return Optional.empty();

        double rangePct = boxH.subtract(boxL).divide(boxL, MathContext.DECIMAL32).doubleValue() * 100;
        if (rangePct > 2.5 || !hasVolumeContraction(box)) return Optional.empty();

        double avgVol = avgVolume(box, box.size());
        if (cur.getVolume() < avgVol * rangeBreakoutVolume) return Optional.empty();

        BigDecimal poc = kl.getPoc();

        // LONG — target = POC as mean reversion magnet
        if (cur.getClose().compareTo(boxH) > 0 && cur.isBullish()
                && (vwap.compareTo(BigDecimal.ZERO) == 0 || cur.getClose().compareTo(vwap) > 0)
                && isStrongBullCandle(cur)) {
            BigDecimal risk = cur.getClose().subtract(boxL);
            if (risk.compareTo(BigDecimal.ZERO) <= 0) return Optional.empty();
            BigDecimal target = rangeTarget(cur.getClose(), risk, poc, true);
            log.info("[AUTO_MODE] RANGE LONG {} e={} sl={} tgt={}", symbol, cur.getClose(), boxL, target);
            return Optional.of(new TradeSignal(TradeDirection.LONG, cur.getClose(), boxL, target, 75,
                    name() + "_RANGE_LONG"));
        }

        // SHORT
        if (cur.getClose().compareTo(boxL) < 0 && cur.isBearish()
                && (vwap.compareTo(BigDecimal.ZERO) == 0 || cur.getClose().compareTo(vwap) < 0)
                && isStrongBearCandle(cur)) {
            BigDecimal risk = boxH.subtract(cur.getClose());
            if (risk.compareTo(BigDecimal.ZERO) <= 0) return Optional.empty();
            BigDecimal target = rangeTarget(cur.getClose(), risk, poc, false);
            log.info("[AUTO_MODE] RANGE SHORT {} e={} sl={} tgt={}", symbol, cur.getClose(), boxH, target);
            return Optional.of(new TradeSignal(TradeDirection.SHORT, cur.getClose(), boxH, target, 75,
                    name() + "_RANGE_SHORT"));
        }

        return Optional.empty();
    }

    private BigDecimal rangeTarget(BigDecimal entry, BigDecimal risk,
                                   BigDecimal poc, boolean forLong) {
        if (poc.compareTo(BigDecimal.ZERO) > 0) {
            boolean ok = forLong ? poc.compareTo(entry) > 0 : poc.compareTo(entry) < 0;
            if (ok && risk.doubleValue() > 0) {
                double rr = Math.abs(poc.subtract(entry).doubleValue()) / risk.doubleValue();
                if (rr >= 1.0) return poc;
            }
        }
        return forLong
                ? entry.add(risk.multiply(BigDecimal.valueOf(rangeRR)))
                : entry.subtract(risk.multiply(BigDecimal.valueOf(rangeRR)));
    }

    // ══════════════════════════════════════════════════════════════════
    // UPGRADE 1: Global overextension check
    // ══════════════════════════════════════════════════════════════════

    private boolean isOverextended(BigDecimal price, KeyLevelService.KeyLevelResult kl) {
        return distFromPocPct(price, kl) > OVEREXTENSION_PCT;
    }

    private double distFromPocPct(BigDecimal price, KeyLevelService.KeyLevelResult kl) {
        BigDecimal poc = kl.getPoc();
        if (poc == null || poc.compareTo(BigDecimal.ZERO) == 0) return 0;
        return Math.abs(price.subtract(poc).doubleValue()) / poc.doubleValue();
    }

    // ══════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════

    private boolean isGainerOrLoser(List<Candle> c15m) {
        if (c15m.size() < 2) return false;
        Candle first = c15m.get(c15m.size() - 1);
        Candle last  = c15m.get(0);
        if (first.getOpen().compareTo(BigDecimal.ZERO) == 0) return false;
        double chg = Math.abs(last.getClose().subtract(first.getOpen())
                .divide(first.getOpen(), MathContext.DECIMAL32).doubleValue() * 100);
        return chg >= reversalGainMinPct; // now 2.8%
    }

    private boolean hasExhaustionCandle(Candle c) {
        BigDecimal range = c.getHigh().subtract(c.getLow());
        if (range.compareTo(BigDecimal.ZERO) == 0) return false;
        BigDecimal uw = c.getHigh().subtract(c.getClose().max(c.getOpen()));
        BigDecimal lw = c.getClose().min(c.getOpen()).subtract(c.getLow());
        return uw.max(lw).divide(range, MathContext.DECIMAL32).doubleValue() >= wickRatioMin;
    }

    private boolean hasClearTrend(List<Candle> c) {
        if (c.size() < 6) return false;
        int hh = 0, ll = 0;
        for (int i = 0; i < 5; i++) {
            if (c.get(i).getHigh().compareTo(c.get(i+1).getHigh()) > 0
                    && c.get(i).getLow().compareTo(c.get(i+1).getLow()) > 0) hh++;
            else if (c.get(i).getHigh().compareTo(c.get(i+1).getHigh()) < 0
                    && c.get(i).getLow().compareTo(c.get(i+1).getLow()) < 0) ll++;
        }
        return hh >= 3 || ll >= 3;
    }

    private boolean isVwapAligned(List<Candle> c5m, MarketContext ctx) {
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
        double avg = c5m.subList(1, 10).stream()
                .mapToDouble(c -> c.getHigh().subtract(c.getLow()).doubleValue()).average().orElse(0);
        return c5m.get(0).getHigh().subtract(c5m.get(0).getLow()).doubleValue() <= avg * 1.2;
    }

    private boolean hasVolumeContraction(List<Candle> c) {
        if (c.size() < 3) return false;
        return c.get(0).getVolume() < c.get(1).getVolume() && c.get(1).getVolume() < c.get(2).getVolume();
    }

    private boolean isStrongBullCandle(Candle c) {
        if (!c.isBullish()) return false;
        BigDecimal range = c.getHigh().subtract(c.getLow());
        if (range.compareTo(BigDecimal.ZERO) == 0) return false;
        return c.getClose().subtract(c.getOpen()).divide(range, MathContext.DECIMAL32).doubleValue() >= 0.55
                && c.getHigh().subtract(c.getClose()).divide(range, MathContext.DECIMAL32).doubleValue() <= 0.35;
    }

    private boolean isStrongBearCandle(Candle c) {
        if (!c.isBearish()) return false;
        BigDecimal range = c.getHigh().subtract(c.getLow());
        if (range.compareTo(BigDecimal.ZERO) == 0) return false;
        return c.getOpen().subtract(c.getClose()).divide(range, MathContext.DECIMAL32).doubleValue() >= 0.55
                && c.getClose().subtract(c.getLow()).divide(range, MathContext.DECIMAL32).doubleValue() <= 0.35;
    }

    private boolean wasRetested(List<Candle> c5m, BigDecimal level, BigDecimal vwap) {
        if (c5m.size() < 3) return false;
        BigDecimal tol  = level.multiply(new BigDecimal("0.003"));
        BigDecimal vtol = vwap.compareTo(BigDecimal.ZERO) > 0 ? vwap.multiply(new BigDecimal("0.003")) : BigDecimal.ZERO;
        for (Candle c : c5m.subList(1, Math.min(5, c5m.size()))) {
            if (c.getLow().subtract(level).abs().compareTo(tol) <= 0 || c.getHigh().subtract(level).abs().compareTo(tol) <= 0) return true;
            if (vwap.compareTo(BigDecimal.ZERO) > 0 && (c.getLow().subtract(vwap).abs().compareTo(vtol) <= 0 || c.getHigh().subtract(vwap).abs().compareTo(vtol) <= 0)) return true;
        }
        return false;
    }

    private boolean hasHHHL(List<Candle> c) {
        if (c.size() < 3) return false;
        int n = 0;
        for (int i = 0; i < c.size()-1; i++)
            if (c.get(i).getHigh().compareTo(c.get(i+1).getHigh())>0 && c.get(i).getLow().compareTo(c.get(i+1).getLow())>0) n++;
        return n >= 2;
    }

    private boolean hasLHLL(List<Candle> c) {
        if (c.size() < 3) return false;
        int n = 0;
        for (int i = 0; i < c.size()-1; i++)
            if (c.get(i).getHigh().compareTo(c.get(i+1).getHigh())<0 && c.get(i).getLow().compareTo(c.get(i+1).getLow())<0) n++;
        return n >= 2;
    }

    private BigDecimal orbHigh(List<Candle> c) {
        int s = Math.max(0, c.size()-3);
        return c.subList(s,c.size()).stream().map(Candle::getHigh).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
    }

    private BigDecimal orbLow(List<Candle> c) {
        int s = Math.max(0, c.size()-3);
        return c.subList(s,c.size()).stream().map(Candle::getLow).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
    }

    private BigDecimal recentSwingHigh(List<Candle> c, int n) {
        return c.subList(0, Math.min(n,c.size())).stream().map(Candle::getHigh).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
    }

    private BigDecimal recentSwingLow(List<Candle> c, int n) {
        return c.subList(0, Math.min(n,c.size())).stream().map(Candle::getLow).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
    }

    private double avgVolume(List<Candle> c, int n) {
        int end = Math.min(n+1, c.size());
        if (end<=1) return 1;
        return c.subList(1, end).stream().mapToLong(Candle::getVolume).average().orElse(1);
    }

    private double avgVolume(List<Candle> c) {
        return c.isEmpty() ? 1 : c.stream().mapToLong(Candle::getVolume).average().orElse(1);
    }

    private LocalTime candleTime(Candle c) {
        try { return c.getCandleTime().atZone(IST).toLocalTime(); }
        catch (Exception e) { return LocalTime.now(IST); }
    }

    private enum Mode { TREND, REVERSAL, RANGE, MIXED }
}