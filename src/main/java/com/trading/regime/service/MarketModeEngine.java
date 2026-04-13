package com.trading.regime.service;

import com.trading.analysis.service.RvolService;
import com.trading.domain.Candle;
import com.trading.events.CandleCompleteEvent;
import com.trading.marketdata.service.InstrumentCacheService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;

/**
 * MarketModeEngine — classifies each trading day into one of 6 market day types.
 *
 * FIX: All SLF4J log format strings corrected.
 *   {:.2f} does NOT work in SLF4J — it appears literally in output.
 *   All numeric formatting now uses String.format("%.2f", value) wrappers.
 *   This was visible in startup logs:
 *     [MODE] IB complete: High=23961.25 Low=23828.5 Range={:.2f}%  ← BUG
 *   After fix:
 *     [MODE] IB complete: High=23961.25 Low=23828.5 Range=0.56%    ← CORRECT
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MarketModeEngine {

    private final InstrumentCacheService instrumentCache;
    private final RvolService            rvolService;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public enum MarketMode {
        TREND_DAY, DOUBLE_DISTRIBUTION, NORMAL_DAY,
        NORMAL_VARIATION, NEUTRAL_DAY, NON_TREND_DAY
    }

    public enum TradeTier { GOLD, NORMAL, SKIP }

    public record MarketModeResult(
            MarketMode mode,
            double     ibRangePct,
            double     ibHigh,
            double     ibLow,
            double     ibMid,
            boolean    ibComplete,
            boolean    brokeIbHigh,
            boolean    brokeIbLow,
            boolean    afternoon,
            double     niftyRvol,
            double     minProbability,
            double     riskPct,
            String     activeStrategies,
            String     rationale
    ) {
        public boolean isTradeDay()           { return mode != MarketMode.NON_TREND_DAY; }
        public boolean allowsTrendStrategies(){ return mode == MarketMode.TREND_DAY || mode == MarketMode.NORMAL_VARIATION; }
        public boolean allowsRangeStrategies(){ return mode == MarketMode.NORMAL_DAY || mode == MarketMode.NEUTRAL_DAY || mode == MarketMode.NORMAL_VARIATION || mode == MarketMode.DOUBLE_DISTRIBUTION; }
        public boolean allowsPullback()       { return mode != MarketMode.NON_TREND_DAY; }
        public double positionMultiplier(double probability) {
            if (probability >= 75) return 1.2;
            if (probability >= 60) return 1.0;
            return 0.0;
        }
        public TradeTier tier(double probability) {
            if (probability >= 75)             return TradeTier.GOLD;
            if (probability >= minProbability) return TradeTier.NORMAL;
            return TradeTier.SKIP;
        }
        public int trailEma() { return mode == MarketMode.TREND_DAY ? 9 : 20; }
        public String exitStrategyLabel() {
            return switch (mode) {
                case TREND_DAY           -> "EMA9 trailing SL";
                case NORMAL_VARIATION    -> "EMA20 trailing SL";
                case NORMAL_DAY          -> "EMA20 trailing SL";
                case NEUTRAL_DAY         -> "VWAP exit";
                case DOUBLE_DISTRIBUTION -> "EMA20 trailing SL";
                case NON_TREND_DAY       -> "No trades";
            };
        }
    }

    // ── State ──────────────────────────────────────────────────────────────
    private final Deque<Candle> niftyBuffer5m  = new ArrayDeque<>();
    private final Deque<Candle> niftyBuffer15m = new ArrayDeque<>();

    private volatile double  ibHigh         = 0;
    private volatile double  ibLow          = Double.MAX_VALUE;
    private volatile boolean ibComplete     = false;
    private volatile boolean brokeIbHigh    = false;
    private volatile boolean brokeIbLow     = false;
    private volatile boolean afternoonBreak = false;
    private volatile double  afternoonHigh  = 0;

    @Getter
    private volatile MarketModeResult currentMode = initialMode();

    private static MarketModeResult initialMode() {
        return new MarketModeResult(
                MarketMode.NORMAL_DAY, 0, 0, 0, 0,
                false, false, false, false, 1.0,
                60, 0.5, "Waiting for IB",
                "Waiting for Initial Balance (9:15–10:15 IST)"
        );
    }

    // ── Warmup methods ────────────────────────────────────────────────────

    public void preload5mCandles(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) return;
        synchronized (niftyBuffer5m) {
            niftyBuffer5m.clear();
            candles.forEach(c -> {
                niftyBuffer5m.addFirst(c);
                if (niftyBuffer5m.size() > 100) ((ArrayDeque<Candle>) niftyBuffer5m).removeLast();
            });
        }
        log.info("[MODE] Pre-loaded {} Nifty 5m candles", niftyBuffer5m.size());
        for (Candle c : candles) updateIbTracking(c);
        recalculateMode();
    }

    public void forceComputeIbIfMissing(List<Candle> candles5m) {
        if (ibComplete) return;
        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(LocalTime.of(10, 30))) return;

        log.warn("[MODE] IB not set at {}. Force-computing from {} 5m candles.", now, candles5m.size());

        double tmpHigh = 0;
        double tmpLow  = Double.MAX_VALUE;

        for (Candle c : candles5m) {
            LocalTime ct = c.getCandleTime() != null
                    ? c.getCandleTime().atZone(IST).toLocalTime()
                    : LocalTime.now(IST);
            if (!ct.isBefore(LocalTime.of(9, 15)) && ct.isBefore(LocalTime.of(10, 15))) {
                tmpHigh = Math.max(tmpHigh, c.getHigh().doubleValue());
                tmpLow  = Math.min(tmpLow,  c.getLow().doubleValue());
            }
        }

        if (tmpHigh > 0 && tmpLow < Double.MAX_VALUE) {
            ibHigh    = tmpHigh;
            ibLow     = tmpLow;
            ibComplete = true;
            double r = (ibHigh - ibLow) / ibLow * 100;
            // FIX: String.format for decimal in SLF4J
            log.info("[MODE] Force-computed IB: High={} Low={} Range={}%",
                    String.format("%.2f", ibHigh),
                    String.format("%.2f", ibLow),
                    String.format("%.2f", r));

            if (!candles5m.isEmpty()) {
                double latestClose = candles5m.get(candles5m.size() - 1).getClose().doubleValue();
                if (latestClose > ibHigh) { brokeIbHigh = true; log.info("[MODE] IB HIGH already broken at {}", String.format("%.2f", latestClose)); }
                if (latestClose < ibLow)  { brokeIbLow  = true; log.info("[MODE] IB LOW already broken at {}",  String.format("%.2f", latestClose)); }
            }
            recalculateMode();
        } else {
            log.warn("[MODE] Could not force-compute IB — no candles in 9:15-10:15 window");
        }
    }

    // ── Event listeners ───────────────────────────────────────────────────

    @EventListener
    @Async("tradingExecutor")
    public void onCandle(CandleCompleteEvent event) {
        Candle c = event.getCandle();
        boolean isNifty = (c.getInstrumentToken() == instrumentCache.getNiftyToken());
        if (!isNifty) return;

        if ("5minute".equals(c.getTimeframe())) {
            synchronized (niftyBuffer5m) {
                niftyBuffer5m.addFirst(c);
                if (niftyBuffer5m.size() > 100) ((ArrayDeque<Candle>) niftyBuffer5m).removeLast();
            }
            updateIbTracking(c);
        }
        if ("15minute".equals(c.getTimeframe())) {
            synchronized (niftyBuffer15m) {
                niftyBuffer15m.addFirst(c);
                if (niftyBuffer15m.size() > 300) ((ArrayDeque<Candle>) niftyBuffer15m).removeLast();
            }
        }
        recalculateMode();
    }

    // ── IB tracking ───────────────────────────────────────────────────────

    private void updateIbTracking(Candle c) {
        LocalTime t = c.getCandleTime() != null
                ? c.getCandleTime().atZone(IST).toLocalTime()
                : LocalTime.now(IST);
        double high  = c.getHigh().doubleValue();
        double low   = c.getLow().doubleValue();
        double close = c.getClose().doubleValue();

        if (!t.isBefore(LocalTime.of(9, 15)) && t.isBefore(LocalTime.of(10, 15))) {
            ibHigh = Math.max(ibHigh, high);
            ibLow  = Math.min(ibLow, low);
        }

        if (!ibComplete && !t.isBefore(LocalTime.of(10, 15))
                && ibHigh > 0 && ibLow < Double.MAX_VALUE) {
            ibComplete = true;
            double ibRng = ibLow > 0 ? (ibHigh - ibLow) / ibLow * 100 : 0;
            // FIX: String.format for decimal in SLF4J
            log.info("[MODE] IB complete: High={} Low={} Range={}%",
                    String.format("%.2f", ibHigh),
                    String.format("%.2f", ibLow),
                    String.format("%.2f", ibRng));
        }

        if (ibComplete && ibHigh > 0 && ibLow < Double.MAX_VALUE) {
            if (!brokeIbHigh && close > ibHigh) {
                brokeIbHigh = true;
                log.info("[MODE] IB HIGH broken: price={} ibHigh={}",
                        String.format("%.2f", close), String.format("%.2f", ibHigh));
            }
            if (!brokeIbLow && close < ibLow) {
                brokeIbLow = true;
                log.info("[MODE] IB LOW broken: price={} ibLow={}",
                        String.format("%.2f", close), String.format("%.2f", ibLow));
            }
            if (!t.isBefore(LocalTime.of(13, 0)) && !afternoonBreak) {
                if (brokeIbHigh && close > (afternoonHigh > 0 ? afternoonHigh : ibHigh)) {
                    afternoonBreak = true;
                    afternoonHigh  = close;
                    log.info("[MODE] Afternoon breakout detected at {}", String.format("%.2f", close));
                }
            }
            if (!t.isBefore(LocalTime.of(13, 0))) {
                afternoonHigh = Math.max(afternoonHigh, close);
            }
        }
    }

    // ── Core mode classification ──────────────────────────────────────────

    private void recalculateMode() {
        List<Candle> c15m;
        List<Candle> c5m;
        synchronized (niftyBuffer15m) { c15m = new ArrayList<>(niftyBuffer15m); }
        synchronized (niftyBuffer5m)  { c5m  = new ArrayList<>(niftyBuffer5m);  }

        if (!ibComplete || c15m.size() < 20) return;

        double ibRangePct   = ibLow > 0 ? (ibHigh - ibLow) / ibLow * 100 : 0;
        double niftyRvol    = c5m.isEmpty() ? 1.0
                : rvolService.getRvolNow("NIFTY", c5m.get(0).getVolume());

        LocalTime now       = LocalTime.now(IST);
        boolean isAfternoon = !now.isBefore(LocalTime.of(13, 0));

        double ema20  = ema(c15m, 20);
        double ema50  = ema(c15m, 50);
        double ema200 = c15m.size() >= 200 ? ema(c15m, 200) : 0;
        boolean fullBullStack = ema20 > ema50 && (ema200 == 0 || ema50 > ema200);
        boolean fullBearStack = ema20 < ema50 && (ema200 == 0 || ema50 < ema200);

        MarketMode mode;
        String     rationale;
        double     minProb;
        double     riskPct;
        String     activeStrategies;

        if (ibRangePct < 0.4) {
            mode             = MarketMode.NON_TREND_DAY;
            rationale        = String.format("IB range %.2f%% < 0.4%% — no institutional participation. NO TRADES.", ibRangePct);
            minProb          = 999;
            riskPct          = 0;
            activeStrategies = "No trades — IB too narrow";

        } else if (ibRangePct > 0.8 && (brokeIbHigh || brokeIbLow)
                && niftyRvol >= 2.0 && (fullBullStack || fullBearStack)) {
            mode             = MarketMode.TREND_DAY;
            String dir       = brokeIbHigh ? "BULLISH" : "BEARISH";
            rationale        = String.format("TREND DAY (%s): IB %.2f%% + IB break + RVOL %.1fx + EMA stack aligned", dir, ibRangePct, niftyRvol);
            minProb          = 65;
            riskPct          = 1.0;
            activeStrategies = "Trend-following, breakout, momentum";

        } else if (ibRangePct >= 0.5 && brokeIbHigh && isAfternoon && afternoonBreak) {
            mode             = MarketMode.DOUBLE_DISTRIBUTION;
            rationale        = String.format("DOUBLE DISTRIBUTION: Morning IB %.2f%% + afternoon breakout continuation", ibRangePct);
            minProb          = 65;
            riskPct          = 0.75;
            activeStrategies = "Trend-following, pullback";

        } else if (brokeIbHigh && brokeIbLow) {
            mode             = MarketMode.NEUTRAL_DAY;
            rationale        = String.format("NEUTRAL DAY: Both IB sides broken — choppy. VAH/VAL reversals only. IB %.2f%%", ibRangePct);
            minProb          = 70;
            riskPct          = 0.5;
            activeStrategies = "Mean-reversion, range edges only";

        } else if (ibRangePct >= 1.0 && !brokeIbHigh && !brokeIbLow) {
            mode             = MarketMode.NORMAL_DAY;
            rationale        = String.format("NORMAL DAY: Wide IB %.2f%% but price inside — trade range edges only", ibRangePct);
            minProb          = 60;
            riskPct          = 0.5;
            activeStrategies = "Range edges, pullback";

        } else if ((brokeIbHigh || brokeIbLow) && !(brokeIbHigh && brokeIbLow)) {
            mode             = MarketMode.NORMAL_VARIATION;
            String side      = brokeIbHigh ? "bull side" : "bear side";
            rationale        = String.format("NORMAL VARIATION: IB %.2f%%, broke %s and holding", ibRangePct, side);
            minProb          = 65;
            riskPct          = 0.75;
            activeStrategies = "Breakout, pullback";

        } else {
            mode             = MarketMode.NORMAL_DAY;
            rationale        = String.format("IB %.2f%% — awaiting clear break. Range strategies preferred.", ibRangePct);
            minProb          = 60;
            riskPct          = 0.5;
            activeStrategies = "Range edges, pullback";
        }

        double ibMidLocal = ibHigh > 0 ? (ibHigh + ibLow) / 2.0 : 0;
        MarketModeResult prev = currentMode;
        currentMode = new MarketModeResult(
                mode, ibRangePct,
                ibHigh, ibLow < Double.MAX_VALUE ? ibLow : 0,
                ibMidLocal, ibComplete,
                brokeIbHigh, brokeIbLow,
                isAfternoon, niftyRvol,
                minProb, riskPct,
                activeStrategies, rationale
        );

        if (prev.mode() != mode) {
            log.info("[MODE] Changed: {} → {} | {}", prev.mode(), mode, rationale);
        }
    }

    // ── Daily reset ───────────────────────────────────────────────────────

    @Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyReset() {
        ibHigh = 0; ibLow = Double.MAX_VALUE;
        ibComplete = false; brokeIbHigh = false; brokeIbLow = false;
        afternoonBreak = false; afternoonHigh = 0;
        currentMode = initialMode();
        synchronized (niftyBuffer5m) { niftyBuffer5m.clear(); }
        log.info("[MODE] Daily reset complete — IB tracking cleared");
    }

    private double ema(List<Candle> candles, int p) {
        if (candles.size() < p) return 0.0;
        double k = 2.0 / (p + 1);
        int warmup = Math.min(2 * p, candles.size());
        double e = candles.get(warmup - 1).getClose().doubleValue();
        for (int i = warmup - 2; i >= 0; i--)
            e = candles.get(i).getClose().doubleValue() * k + e * (1 - k);
        return e;
    }
}