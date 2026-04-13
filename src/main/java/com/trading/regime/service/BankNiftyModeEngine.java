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
 * BankNiftyModeEngine — independent market mode engine for BankNifty.
 *
 * FIX: All SLF4J log format strings corrected.
 *   {:.2f} format strings appeared literally in output. All numeric
 *   formatting now uses String.format("%.2f", value) wrappers.
 *
 *   Also fixed: IB HIGH/LOW broken logs now include proper labels
 *   ("price=" prefix) consistent with MarketModeEngine output.
 *
 *   Visible in startup logs:
 *     [BNFMODE] IB complete: High=55315.5 Low=54797.5 Range={:.2f}%  ← BUG
 *     [BNFMODE] IB HIGH broken: 55324.4   ← missing "price=" label
 *   After fix:
 *     [BNFMODE] IB complete: High=55315.50 Low=54797.50 Range=0.95%  ← CORRECT
 *     [BNFMODE] IB HIGH broken: price=55324.40                        ← CORRECT
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BankNiftyModeEngine {

    private final InstrumentCacheService instrumentCache;
    private final RvolService            rvolService;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final Set<String> BANKNIFTY_STOCKS = Set.of(
            "HDFCBANK", "ICICIBANK", "KOTAKBANK", "AXISBANK", "SBIN",
            "BANKBARODA", "PNB", "CANBK", "INDUSINDBK", "FEDERALBNK",
            "IDFCFIRSTB", "BANDHANBNK", "AUBANK", "RBLBANK", "YESBANK"
    );

    // ── IB State ──────────────────────────────────────────────────────────
    private final Deque<Candle> buffer5m  = new ArrayDeque<>();
    private final Deque<Candle> buffer15m = new ArrayDeque<>();

    private volatile double  ibHigh         = 0;
    private volatile double  ibLow          = Double.MAX_VALUE;
    private volatile boolean ibComplete     = false;
    private volatile boolean brokeIbHigh    = false;
    private volatile boolean brokeIbLow     = false;
    private volatile boolean afternoonBreak = false;
    private volatile double  afternoonHigh  = 0;

    @Getter
    private volatile MarketModeEngine.MarketModeResult currentMode = initialMode();

    private static MarketModeEngine.MarketModeResult initialMode() {
        return new MarketModeEngine.MarketModeResult(
                MarketModeEngine.MarketMode.NORMAL_DAY,
                0, 0, 0, 0, false, false, false, false,
                1.0, 60, 0.5,
                "Waiting for IB",
                "BankNifty: Waiting for IB (9:15–10:15 IST)"
        );
    }

    // ── Index mapping ─────────────────────────────────────────────────────

    public boolean isBankNiftyStock(String symbol) {
        return BANKNIFTY_STOCKS.contains(symbol.toUpperCase());
    }

    public MarketModeEngine.MarketModeResult getModeForSymbol(
            String symbol, MarketModeEngine.MarketModeResult niftyMode) {
        return isBankNiftyStock(symbol) ? currentMode : niftyMode;
    }

    // ── Event listener ────────────────────────────────────────────────────

    @EventListener
    @Async("tradingExecutor")
    public void onCandle(CandleCompleteEvent event) {
        Candle c = event.getCandle();
        boolean isBnf = (c.getInstrumentToken() == instrumentCache.getBankNiftyToken());
        if (!isBnf) return;

        if ("5minute".equals(c.getTimeframe())) {
            synchronized (buffer5m) {
                buffer5m.addFirst(c);
                if (buffer5m.size() > 100) ((ArrayDeque<Candle>) buffer5m).removeLast();
            }
            updateIbTracking(c);
        }
        if ("15minute".equals(c.getTimeframe())) {
            synchronized (buffer15m) {
                buffer15m.addFirst(c);
                if (buffer15m.size() > 300) ((ArrayDeque<Candle>) buffer15m).removeLast();
            }
        }
        recalculateMode();
    }

    // ── Preload (called by WarmupService) ─────────────────────────────────

    public void preload15mCandles(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) return;
        synchronized (buffer15m) {
            buffer15m.clear();
            candles.forEach(c -> {
                buffer15m.addFirst(c);
                if (buffer15m.size() > 300) ((ArrayDeque<Candle>) buffer15m).removeLast();
            });
        }
        log.info("[BNFMODE] Pre-loaded {} BankNifty 15m candles", buffer15m.size());
        recalculateMode();
    }

    public void preload5mCandles(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) return;
        synchronized (buffer5m) {
            buffer5m.clear();
            candles.forEach(c -> {
                buffer5m.addFirst(c);
                if (buffer5m.size() > 100) ((ArrayDeque<Candle>) buffer5m).removeLast();
            });
        }
        log.info("[BNFMODE] Pre-loaded {} BankNifty 5m candles", buffer5m.size());
        for (Candle c : candles) updateIbTracking(c);
    }

    public void forceComputeIbIfMissing(List<Candle> candles5m) {
        if (ibComplete) return;
        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(LocalTime.of(10, 30))) return;

        log.warn("[BNFMODE] IB not set at {}. Force-computing from {} candles.", now, candles5m.size());
        for (Candle c : candles5m) {
            LocalTime ct = c.getCandleTime() != null
                    ? c.getCandleTime().atZone(IST).toLocalTime()
                    : LocalTime.now(IST);
            if (!ct.isBefore(LocalTime.of(9, 15)) && ct.isBefore(LocalTime.of(10, 15))) {
                ibHigh = Math.max(ibHigh, c.getHigh().doubleValue());
                ibLow  = Math.min(ibLow,  c.getLow().doubleValue());
            }
        }
        if (ibHigh > 0 && ibLow < Double.MAX_VALUE) {
            ibComplete = true;
            // FIX: String.format for SLF4J
            log.info("[BNFMODE] Forced IB computed: High={} Low={}",
                    String.format("%.2f", ibHigh), String.format("%.2f", ibLow));
            recalculateMode();
        }
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
            ibLow  = Math.min(ibLow,  low);
        }

        if (!ibComplete && !t.isBefore(LocalTime.of(10, 15))
                && ibHigh > 0 && ibLow < Double.MAX_VALUE) {
            ibComplete = true;
            double r = (ibHigh - ibLow) / ibLow * 100;
            // FIX: String.format for SLF4J decimal formatting
            log.info("[BNFMODE] IB complete: High={} Low={} Range={}%",
                    String.format("%.2f", ibHigh),
                    String.format("%.2f", ibLow),
                    String.format("%.2f", r));
        }

        if (ibComplete && ibHigh > 0 && ibLow < Double.MAX_VALUE) {
            if (!brokeIbHigh && close > ibHigh) {
                brokeIbHigh = true;
                // FIX: Added "price=" label consistent with MarketModeEngine
                log.info("[BNFMODE] IB HIGH broken: price={}", String.format("%.2f", close));
            }
            if (!brokeIbLow && close < ibLow) {
                brokeIbLow = true;
                log.info("[BNFMODE] IB LOW broken: price={}", String.format("%.2f", close));
            }
            if (!t.isBefore(LocalTime.of(13, 0)) && !afternoonBreak) {
                if (brokeIbHigh && close > (afternoonHigh > 0 ? afternoonHigh : ibHigh)) {
                    afternoonBreak = true;
                    afternoonHigh  = close;
                    log.info("[BNFMODE] Afternoon breakout at {}", String.format("%.2f", close));
                }
            }
            if (!t.isBefore(LocalTime.of(13, 0))) afternoonHigh = Math.max(afternoonHigh, close);
        }
    }

    // ── Mode classification ───────────────────────────────────────────────

    private void recalculateMode() {
        List<Candle> c15m;
        List<Candle> c5m;
        synchronized (buffer15m) { c15m = new ArrayList<>(buffer15m); }
        synchronized (buffer5m)  { c5m  = new ArrayList<>(buffer5m);  }

        if (!ibComplete || c15m.size() < 10) return;

        double ibRangePct   = ibLow > 0 ? (ibHigh - ibLow) / ibLow * 100 : 0;
        double bnfRvol      = c5m.isEmpty() ? 1.0
                : rvolService.getRvolNow("BANKNIFTY", c5m.get(0).getVolume());
        double ema20        = ema(c15m, 20);
        double ema50        = ema(c15m, 50);
        double ema200       = c15m.size() >= 200 ? ema(c15m, 200) : 0;
        boolean bullStack   = ema20 > ema50 && (ema200 == 0 || ema50 > ema200);
        boolean bearStack   = ema20 < ema50 && (ema200 == 0 || ema50 < ema200);
        boolean isAfternoon = !LocalTime.now(IST).isBefore(LocalTime.of(13, 0));

        MarketModeEngine.MarketMode mode;
        String  rationale;
        double  minProb;
        double  riskPct;
        String  activeStrats;

        if (ibRangePct < 0.4) {
            mode = MarketModeEngine.MarketMode.NON_TREND_DAY;
            rationale = String.format("BNF: IB %.2f%% < 0.4%% — NO TRADES", ibRangePct);
            minProb = 999; riskPct = 0;
            activeStrats = "No trades — IB too narrow";

        } else if (ibRangePct > 0.8 && (brokeIbHigh || brokeIbLow)
                && bnfRvol >= 2.0 && (bullStack || bearStack)) {
            mode = MarketModeEngine.MarketMode.TREND_DAY;
            rationale = String.format("BNF TREND: IB %.2f%% + RVOL %.1fx + EMA stack", ibRangePct, bnfRvol);
            minProb = 65; riskPct = 1.0;
            activeStrats = "Trend-following, breakout, momentum";

        } else if (ibRangePct >= 0.5 && brokeIbHigh && isAfternoon && afternoonBreak) {
            mode = MarketModeEngine.MarketMode.DOUBLE_DISTRIBUTION;
            rationale = String.format("BNF DOUBLE DIST: %.2f%% + afternoon break", ibRangePct);
            minProb = 65; riskPct = 0.75;
            activeStrats = "Trend-following, pullback";

        } else if (brokeIbHigh && brokeIbLow) {
            mode = MarketModeEngine.MarketMode.NEUTRAL_DAY;
            rationale = String.format("BNF NEUTRAL: both IB sides broken. IB %.2f%%", ibRangePct);
            minProb = 70; riskPct = 0.5;
            activeStrats = "Mean-reversion, range edges only";

        } else if (ibRangePct >= 1.0 && !brokeIbHigh && !brokeIbLow) {
            mode = MarketModeEngine.MarketMode.NORMAL_DAY;
            rationale = String.format("BNF NORMAL: wide IB %.2f%% inside range", ibRangePct);
            minProb = 60; riskPct = 0.5;
            activeStrats = "Range edges, pullback";

        } else if ((brokeIbHigh || brokeIbLow) && !(brokeIbHigh && brokeIbLow)) {
            mode = MarketModeEngine.MarketMode.NORMAL_VARIATION;
            rationale = String.format("BNF NORMAL_VAR: IB %.2f%% one-sided break", ibRangePct);
            minProb = 65; riskPct = 0.75;
            activeStrats = "Breakout, pullback";

        } else {
            mode = MarketModeEngine.MarketMode.NORMAL_DAY;
            rationale = String.format("BNF: IB %.2f%% — awaiting break", ibRangePct);
            minProb = 60; riskPct = 0.5;
            activeStrats = "Range edges, pullback";
        }

        double ibMid = ibHigh > 0 ? (ibHigh + ibLow) / 2.0 : 0;
        MarketModeEngine.MarketMode prev = currentMode.mode();
        currentMode = new MarketModeEngine.MarketModeResult(
                mode, ibRangePct,
                ibHigh, ibLow < Double.MAX_VALUE ? ibLow : 0,
                ibMid, ibComplete,
                brokeIbHigh, brokeIbLow,
                isAfternoon, bnfRvol,
                minProb, riskPct,
                activeStrats, rationale
        );

        if (prev != mode) log.info("[BNFMODE] {} → {} | {}", prev, mode, rationale);
    }

    // ── Daily reset ───────────────────────────────────────────────────────

    @Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyReset() {
        ibHigh = 0; ibLow = Double.MAX_VALUE;
        ibComplete = false; brokeIbHigh = false; brokeIbLow = false;
        afternoonBreak = false; afternoonHigh = 0;
        currentMode = initialMode();
        synchronized (buffer5m) { buffer5m.clear(); }
        log.info("[BNFMODE] Daily reset complete");
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