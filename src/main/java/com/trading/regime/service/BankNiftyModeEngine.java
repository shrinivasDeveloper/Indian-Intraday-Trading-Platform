// ============================================================
// NEW FILE
// Path: src/main/java/com/trading/regime/service/BankNiftyModeEngine.java
// PURPOSE: Solves CRITICAL ISSUE 2 — Nifty vs BankNifty divergence.
//          BankNifty has its OWN independent market mode, separate from Nifty.
//          Stocks are mapped to their primary index (HDFC Bank → BankNifty,
//          Reliance → Nifty) and strategies use the mapped index mode.
// ============================================================
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
 * BankNiftyModeEngine — Mirror of MarketModeEngine but for BankNifty.
 *
 * CRITICAL ISSUE 2 FIX:
 *   Problem: MarketModeEngine only used Nifty. When Nifty = NEUTRAL_DAY
 *   but BankNifty = TREND_DAY, all banking stocks got blocked incorrectly.
 *
 *   Solution:
 *   1. BankNiftyModeEngine independently classifies BankNifty's day type
 *   2. IndexMappingService maps each stock to its primary index
 *   3. StrategyEvaluatorService uses the CORRECT mode for each stock
 *
 * INDEX MAPPING (built-in defaults, extend as needed):
 *   BankNifty stocks: HDFCBANK, ICICIBANK, KOTAKBANK, AXISBANK, SBIN,
 *                     BANKBARODA, PNB, CANBK, INDUSINDBK, FEDERALBNK,
 *                     IDFCFIRSTB, BANDHANBNK
 *   Everything else → Nifty mode
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BankNiftyModeEngine {

    private final InstrumentCacheService instrumentCache;
    private final RvolService            rvolService;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // BankNifty-constituent stocks that should use BankNifty mode
    private static final Set<String> BANKNIFTY_STOCKS = Set.of(
            "HDFCBANK", "ICICIBANK", "KOTAKBANK", "AXISBANK", "SBIN",
            "BANKBARODA", "PNB", "CANBK", "INDUSINDBK", "FEDERALBNK",
            "IDFCFIRSTB", "BANDHANBNK", "AUBANK", "RBLBANK", "YESBANK"
    );

    // ── IB State ────────────────────────────────────────────────────────────
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
                MarketModeEngine.MarketMode.NORMAL_DAY, 0, 0, 0, 0, false,
                false, false, false, 1.0, 60, 0.5,
                "VAP_PULLBACK,RANGE_BREAKOUT_3TOUCH",
                "BankNifty: Waiting for IB (9:15–10:15 IST)"
        );
    }

    // ── Index mapping ────────────────────────────────────────────────────────

    /**
     * Returns true if this symbol should use BankNifty mode.
     * Returns false if it should use Nifty mode.
     */
    public boolean isBankNiftyStock(String symbol) {
        return BANKNIFTY_STOCKS.contains(symbol.toUpperCase());
    }

    /**
     * Get the correct MarketModeResult for a given symbol.
     * Stocks in BankNifty constituent list use BankNifty mode.
     * All others use Nifty mode (from MarketModeEngine).
     *
     * This is the SINGLE METHOD that StrategyEvaluatorService should call.
     */
    public MarketModeEngine.MarketModeResult getModeForSymbol(
            String symbol,
            MarketModeEngine.MarketModeResult niftyMode) {
        if (isBankNiftyStock(symbol)) {
            log.debug("[BNFMODE] {} → using BankNifty mode: {}", symbol, currentMode.mode());
            return currentMode;
        }
        return niftyMode;
    }

    // ── Event listener ───────────────────────────────────────────────────────

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

    // ── Preload (called by WarmupService) ────────────────────────────────────

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
        // Rebuild IB from historical candles
        for (Candle c : candles) updateIbTracking(c);
    }

    /**
     * CRITICAL FIX for scanner delay (ISSUE 5):
     * If IB window already passed (time > 10:30) and IB is not set,
     * force-compute IB from existing 5m buffer.
     */
    public void forceComputeIbIfMissing(List<Candle> candles5m) {
        if (ibComplete) return;
        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(LocalTime.of(10, 30))) return;

        log.warn("[BNFMODE] IB not set but time is {}. Force-computing from {} candles", now, candles5m.size());
        for (Candle c : candles5m) {
            LocalTime ct = c.getCandleTime() != null
                    ? c.getCandleTime().atZone(IST).toLocalTime()
                    : LocalTime.now(IST);
            if (!ct.isBefore(LocalTime.of(9, 15)) && ct.isBefore(LocalTime.of(10, 15))) {
                ibHigh = Math.max(ibHigh, c.getHigh().doubleValue());
                ibLow  = Math.min(ibLow, c.getLow().doubleValue());
            }
        }
        if (ibHigh > 0 && ibLow < Double.MAX_VALUE) {
            ibComplete = true;
            log.info("[BNFMODE] Forced IB computed: High={} Low={}", ibHigh, ibLow);
            recalculateMode();
        }
    }

    // ── IB tracking ──────────────────────────────────────────────────────────

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
        if (!ibComplete && !t.isBefore(LocalTime.of(10, 15)) && ibHigh > 0 && ibLow < Double.MAX_VALUE) {
            ibComplete = true;
            double r = (ibHigh - ibLow) / ibLow * 100;
            log.info("[BNFMODE] IB complete: High={} Low={} Range={:.2f}%", ibHigh, ibLow, r);
        }
        if (ibComplete && ibHigh > 0 && ibLow < Double.MAX_VALUE) {
            if (!brokeIbHigh && close > ibHigh) { brokeIbHigh = true; log.info("[BNFMODE] IB HIGH broken: {}", close); }
            if (!brokeIbLow  && close < ibLow)  { brokeIbLow  = true; log.info("[BNFMODE] IB LOW broken: {}",  close); }
            if (!t.isBefore(LocalTime.of(13, 0)) && !afternoonBreak) {
                if (brokeIbHigh && close > (afternoonHigh > 0 ? afternoonHigh : ibHigh)) {
                    afternoonBreak = true; afternoonHigh = close;
                    log.info("[BNFMODE] Afternoon breakout at {}", close);
                }
            }
            if (!t.isBefore(LocalTime.of(13, 0))) afternoonHigh = Math.max(afternoonHigh, close);
        }
    }

    // ── Mode classification (same logic as MarketModeEngine) ─────────────────

    private void recalculateMode() {
        List<Candle> c15m; List<Candle> c5m;
        synchronized (buffer15m) { c15m = new ArrayList<>(buffer15m); }
        synchronized (buffer5m)  { c5m  = new ArrayList<>(buffer5m);  }

        if (!ibComplete || c15m.size() < 10) return;

        double ibRangePct   = ibLow > 0 ? (ibHigh - ibLow) / ibLow * 100 : 0;
        double bnfRvol      = c5m.isEmpty() ? 1.0 : rvolService.getRvolNow("BANKNIFTY", c5m.get(0).getVolume());
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
            minProb = 999; riskPct = 0; activeStrats = "NONE";
        } else if (ibRangePct > 0.8 && (brokeIbHigh || brokeIbLow) && bnfRvol >= 2.0 && (bullStack || bearStack)) {
            mode = MarketModeEngine.MarketMode.TREND_DAY;
            rationale = String.format("BNF TREND: IB %.2f%% + RVOL %.1f× + EMA stack", ibRangePct, bnfRvol);
            minProb = 65; riskPct = 1.0;
            activeStrats = "AUTO_MODE,ORB_VWAP_SECTOR,SCANNER_7GATE,VAP_PULLBACK,RANGE_BREAKOUT_3TOUCH";
        } else if (ibRangePct >= 0.5 && brokeIbHigh && isAfternoon && afternoonBreak) {
            mode = MarketModeEngine.MarketMode.DOUBLE_DISTRIBUTION;
            rationale = String.format("BNF DOUBLE DIST: %.2f%% + afternoon break", ibRangePct);
            minProb = 65; riskPct = 0.75;
            activeStrats = "AUTO_MODE,ORB_VWAP_SECTOR,VAP_PULLBACK,RANGE_BREAKOUT_3TOUCH";
        } else if (brokeIbHigh && brokeIbLow) {
            mode = MarketModeEngine.MarketMode.NEUTRAL_DAY;
            rationale = String.format("BNF NEUTRAL: both IB sides broken. IB %.2f%%", ibRangePct);
            minProb = 70; riskPct = 0.5; activeStrats = "VAP_PULLBACK";
        } else if (ibRangePct >= 1.0 && !brokeIbHigh && !brokeIbLow) {
            mode = MarketModeEngine.MarketMode.NORMAL_DAY;
            rationale = String.format("BNF NORMAL: wide IB %.2f%% inside range", ibRangePct);
            minProb = 60; riskPct = 0.5; activeStrats = "VAP_PULLBACK,RANGE_BREAKOUT_3TOUCH";
        } else if ((brokeIbHigh || brokeIbLow) && !(brokeIbHigh && brokeIbLow)) {
            mode = MarketModeEngine.MarketMode.NORMAL_VARIATION;
            rationale = String.format("BNF NORMAL_VAR: IB %.2f%% one-sided break", ibRangePct);
            minProb = 65; riskPct = 0.75;
            activeStrats = "AUTO_MODE,VAP_PULLBACK,RANGE_BREAKOUT_3TOUCH";
        } else {
            mode = MarketModeEngine.MarketMode.NORMAL_DAY;
            rationale = String.format("BNF: IB %.2f%% — awaiting break", ibRangePct);
            minProb = 60; riskPct = 0.5; activeStrats = "VAP_PULLBACK,RANGE_BREAKOUT_3TOUCH";
        }

        double ibMid = ibHigh > 0 ? (ibHigh + ibLow) / 2.0 : 0;
        MarketModeEngine.MarketMode prev = currentMode.mode();
        currentMode = new MarketModeEngine.MarketModeResult(
                mode, ibRangePct, ibHigh, ibLow < Double.MAX_VALUE ? ibLow : 0,
                ibMid, ibComplete, brokeIbHigh, brokeIbLow,
                isAfternoon, bnfRvol, minProb, riskPct, activeStrats, rationale
        );
        if (prev != mode) log.info("[BNFMODE] {} → {} | {}", prev, mode, rationale);
    }

    // ── Daily reset ──────────────────────────────────────────────────────────

    @Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyReset() {
        ibHigh = 0; ibLow = Double.MAX_VALUE;
        ibComplete = false; brokeIbHigh = false; brokeIbLow = false;
        afternoonBreak = false; afternoonHigh = 0;
        currentMode = initialMode();
        synchronized (buffer5m) { buffer5m.clear(); }
        log.info("[BNFMODE] Daily reset complete");
    }

    // ── EMA helper ───────────────────────────────────────────────────────────

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