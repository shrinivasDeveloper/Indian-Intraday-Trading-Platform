package com.trading.strategy;

import com.trading.analysis.service.KeyLevelService;
import com.trading.analysis.service.RvolService;
import com.trading.domain.Candle;
import com.trading.domain.enums.TradeDirection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Strategy 5 — Value Area Pullback (VAP)
 *
 * The institutionally correct way to trade WITH the trend — not chasing breakouts.
 *
 * ══════════════════════════════════════════════════════════════════════
 * THE PHILOSOPHY (Why This Is the Safest Entry):
 *
 *   Breakout traders buy when price is ABOVE VAH (overextended, chasing).
 *   Pullback traders wait for price to RETURN to VAH (buying on a dip,
 *   at the exact level institutions defended earlier). Same direction,
 *   far better entry price, tighter SL, lower risk.
 *
 *   "Buy the dip in a strong stock" — tighter SL, higher win rate,
 *   easier psychology compared to chasing a breakout candle.
 *
 * ══════════════════════════════════════════════════════════════════════
 * THE 4-STAGE STATE MACHINE (per symbol):
 *
 *   STAGE 1 — IMPULSE DETECTED:
 *     Price > VAH + 1.5% on RVOL ≥ 1.5 AND 5m RSI > 65.
 *     This proves: institutions pushed price above fair value with conviction.
 *     We now WAIT for a pullback. Do NOT enter here.
 *
 *   STAGE 2 — PULLBACK CONFIRMED:
 *     Price is falling back toward VAH on DECLINING volume.
 *     Pullback candle volumes < 70% of average impulse volume.
 *     This confirms: it's just profit-taking, not a reversal.
 *     Institutions are NOT selling. Retail is taking profits.
 *
 *   STAGE 3 — ENTRY READY (Buy Zone):
 *     Price enters the "Buy Zone": between VAH and EMA9/VWAP.
 *     A HAMMER or BULLISH ENGULFING candle forms in the buy zone.
 *     This is the institutional re-entry / accumulation point.
 *
 *   STAGE 4 — SIGNAL FIRED:
 *     Entry: Close of the hammer/engulfing candle.
 *     SL: max(0.2% below POC, low of reversal candle) — whichever is HIGHER
 *         (tighter SL = better RR).
 *     Target: Entry + 3 × risk (1:3 RR minimum).
 *     Trailing: SL → Breakeven once price clears previous swing high.
 *
 * ══════════════════════════════════════════════════════════════════════
 * RISK MANAGEMENT (embedded in TradeSignal):
 *   trailingTriggerPrice = previous swing high (move SL to breakeven here)
 *   trailingType = BREAKEVEN_ONLY (don't trail aggressively — respect the trend)
 *   timeStopMinutes = 30 (if no movement in 30 min, exit — context changed)
 *
 * ══════════════════════════════════════════════════════════════════════
 * COMPLETE STATE RESET:
 *   State resets if: impulse is > 45 minutes old (stale), or price has
 *   fallen BELOW POC (trend reversal — the whole thesis is invalidated).
 * ══════════════════════════════════════════════════════════════════════
 */
@Component
@Slf4j
public class PullbackDetectionService implements TradingStrategy {

    @Autowired private KeyLevelService keyLevelService;
    @Autowired private RvolService     rvolService;

    // ── Config ────────────────────────────────────────────────────────────────
    @Value("${strategy.pullback.impulse-min-pct:1.5}")      private double impulsePct;   // 1.5% above VAH
    @Value("${strategy.pullback.min-rvol-impulse:1.5}")     private double minRvolImpulse;
    @Value("${strategy.pullback.min-rsi-impulse:65.0}")     private double minRsiImpulse;
    @Value("${strategy.pullback.pullback-vol-ratio:0.7}")   private double pbVolRatio;    // pull candles < 70% of impulse avg
    @Value("${strategy.pullback.rr:3.0}")                   private double targetRR;      // 1:3 RR
    @Value("${strategy.pullback.poc-sl-buffer:0.002}")      private double pocSlBuffer;   // 0.2% below POC
    @Value("${strategy.pullback.entry-start:09:45}")        private String entryStart;
    @Value("${strategy.pullback.entry-end:14:00}")          private String entryEnd;
    @Value("${strategy.pullback.state-ttl-minutes:45}")     private int    stateTtlMin;   // stale impulse TTL

    private static final double OVEREXTENSION_PCT = 0.015;
    private static final ZoneId IST               = ZoneId.of("Asia/Kolkata");

    // ── Per-symbol state ──────────────────────────────────────────────────────

    private enum PbStage { NONE, IMPULSE, PULLBACK }

    private record PbState(
            PbStage    stage,
            BigDecimal impulseHigh,   // highest price during impulse
            double     impulseAvgVol, // average volume of impulse candles
            long       stageStartMs,  // System.currentTimeMillis() when stage began
            int        pullbackCount  // number of pullback candles observed
    ) {}

    private final Map<String, PbState> states = new ConcurrentHashMap<>();

    @Override
    public String name() { return "VAP_PULLBACK"; }

    @Override
    public Optional<TradeSignal> generateSignal(String symbol,
                                                List<Candle> candles5m,
                                                List<Candle> candles15m,
                                                MarketContext ctx) {
        if (!withinTime()) return Optional.empty();
        if (candles5m.size() < 20) return Optional.empty();

        // Only operate in BULLISH Nifty environment (VAP is a long-only strategy)
        if (!ctx.niftyBullish()) return Optional.empty();

        KeyLevelService.KeyLevelResult kl = keyLevelService.getKeyLevels(symbol);

        Candle cur  = candles5m.get(0);
        BigDecimal price = cur.getClose();

        // GLOBAL: if price has fallen below POC, thesis is invalidated → reset
        if (kl.getPoc().compareTo(BigDecimal.ZERO) > 0 && price.compareTo(kl.getPoc()) < 0) {
            resetState(symbol, "price below POC — trend invalidated");
            return Optional.empty();
        }

        PbState state = states.getOrDefault(symbol,
                new PbState(PbStage.NONE, BigDecimal.ZERO, 0, 0, 0));

        // Reset stale states (impulse older than TTL minutes)
        if (state.stage() != PbStage.NONE) {
            long ageMs = System.currentTimeMillis() - state.stageStartMs();
            if (ageMs > stateTtlMin * 60_000L) {
                resetState(symbol, "state TTL exceeded (" + stateTtlMin + " min)");
                state = states.get(symbol);
            }
        }

        return switch (state.stage()) {
            case NONE     -> checkForImpulse(symbol, candles5m, kl, ctx, cur, price);
            case IMPULSE  -> checkForPullback(symbol, candles5m, kl, state, cur, price);
            case PULLBACK -> checkForEntry(symbol, candles5m, kl, state, ctx, cur, price);
        };
    }

    // ══════════════════════════════════════════════════════════════════
    // Stage 1: Look for Impulse (price > VAH + 1.5%, RVOL, RSI)
    // ══════════════════════════════════════════════════════════════════

    private Optional<TradeSignal> checkForImpulse(String symbol, List<Candle> c5m,
                                                  KeyLevelService.KeyLevelResult kl,
                                                  MarketContext ctx, Candle cur,
                                                  BigDecimal price) {
        BigDecimal vah = kl.getVah();
        if (vah.compareTo(BigDecimal.ZERO) == 0) return Optional.empty(); // no VA yet

        // Is price at least 1.5% above VAH?
        double pctAboveVah = price.subtract(vah).doubleValue() / vah.doubleValue();
        if (pctAboveVah < impulsePct / 100.0) return Optional.empty();

        // RVOL check — institutional conviction required for impulse
        LocalTime slot = candleTime(cur);
        double rvol = rvolService.getRvol(symbol, slot, cur.getVolume());
        if (rvol < minRvolImpulse) return Optional.empty();

        // RSI > 65 — momentum confirmation
        double rsi = rsi14(c5m);
        if (rsi < minRsiImpulse) return Optional.empty();

        // Average volume of last 6 candles = "impulse average"
        double impulseAvgVol = c5m.subList(0, Math.min(6, c5m.size()))
                .stream().mapToLong(Candle::getVolume).average().orElse(0);

        BigDecimal impulseHigh = c5m.subList(0, Math.min(6, c5m.size()))
                .stream().map(Candle::getHigh).max(BigDecimal::compareTo).orElse(price);

        states.put(symbol, new PbState(
                PbStage.IMPULSE, impulseHigh, impulseAvgVol,
                System.currentTimeMillis(), 0));

        log.info("[VAP] {} IMPULSE detected: price={} VAH={} +{}% RVOL={} RSI={:.1f}",
                symbol, price, vah, String.format("%.2f", pctAboveVah * 100),
                rvolService.rvolLabel(rvol), rsi);

        return Optional.empty(); // Don't enter yet — wait for pullback
    }

    // ══════════════════════════════════════════════════════════════════
    // Stage 2: Watch for Pullback (declining volume, price returning to VAH)
    // ══════════════════════════════════════════════════════════════════

    private Optional<TradeSignal> checkForPullback(String symbol, List<Candle> c5m,
                                                   KeyLevelService.KeyLevelResult kl,
                                                   PbState state, Candle cur,
                                                   BigDecimal price) {
        BigDecimal vah  = kl.getVah();
        BigDecimal vwap = c5m.isEmpty() ? BigDecimal.ZERO : computeVwap(c5m);

        // Has price started coming back down? (current < impulse high)
        if (price.compareTo(state.impulseHigh()) >= 0) {
            // Price still rising — extend the impulse high
            states.put(symbol, new PbState(
                    PbStage.IMPULSE, cur.getHigh().compareTo(state.impulseHigh()) > 0
                    ? cur.getHigh() : state.impulseHigh(),
                    state.impulseAvgVol(), state.stageStartMs(), 0));
            return Optional.empty();
        }

        // Price is pulling back — is volume declining? (confirming: not a reversal)
        boolean weakVolume = cur.getVolume() < state.impulseAvgVol() * pbVolRatio;
        if (!weakVolume) {
            log.debug("[VAP] {} pullback vol NOT declining: {} vs impulse avg {} — reset",
                    symbol, cur.getVolume(), (long) state.impulseAvgVol());
            // High volume on pullback = distribution = reset
            resetState(symbol, "pullback on high volume — possible reversal");
            return Optional.empty();
        }

        // Is price approaching Buy Zone (VAH to VWAP)?
        boolean inApproach = price.compareTo(vah) <= 0
                || price.subtract(vah).divide(vah, MathContext.DECIMAL32).doubleValue() < 0.01;

        int newCount = state.pullbackCount() + 1;
        states.put(symbol, new PbState(
                inApproach ? PbStage.PULLBACK : PbStage.IMPULSE,
                state.impulseHigh(), state.impulseAvgVol(),
                inApproach ? System.currentTimeMillis() : state.stageStartMs(),
                newCount));

        if (inApproach) {
            log.info("[VAP] {} PULLBACK confirmed after {} candles: price={} VAH={} VWAP={}",
                    symbol, newCount, price, vah, vwap);
        }

        return Optional.empty(); // Still waiting for entry confirmation
    }

    // ══════════════════════════════════════════════════════════════════
    // Stage 3: Entry — Hammer or Bullish Engulfing in Buy Zone
    // ══════════════════════════════════════════════════════════════════

    private Optional<TradeSignal> checkForEntry(String symbol, List<Candle> c5m,
                                                KeyLevelService.KeyLevelResult kl,
                                                PbState state, MarketContext ctx,
                                                Candle cur, BigDecimal price) {
        if (c5m.size() < 3) return Optional.empty();

        BigDecimal vah  = kl.getVah();
        BigDecimal val  = kl.getVal();
        BigDecimal poc  = kl.getPoc();
        BigDecimal vwap = computeVwap(c5m);
        BigDecimal ema9 = ema(c5m, 9);

        // Buy Zone: between VAH and EMA9 (or VWAP if EMA9 not available)
        BigDecimal buyZoneFloor = val.compareTo(BigDecimal.ZERO) > 0 ? val : poc;
        BigDecimal buyZoneCeil  = vah.compareTo(BigDecimal.ZERO) > 0 ? vah : vwap;

        // Is price in the buy zone?
        if (price.compareTo(buyZoneFloor) < 0) {
            // Price fell BELOW VAL — thesis broken, POC support failed
            resetState(symbol, "price below VAL — trend broken");
            return Optional.empty();
        }

        // Is a reversal candle forming in the buy zone?
        boolean inBuyZone = price.compareTo(buyZoneCeil.multiply(new BigDecimal("1.005"))) <= 0
                && price.compareTo(buyZoneFloor.multiply(new BigDecimal("0.998"))) >= 0;

        if (!inBuyZone) return Optional.empty();

        // Reversal candle: Hammer or Bullish Engulfing
        boolean hammer = isHammer(cur);
        boolean engulf = isEngulfing(cur, c5m.size() > 1 ? c5m.get(1) : cur);

        if (!hammer && !engulf) return Optional.empty();

        // ── Compute SL (tighter of: 0.2% below POC, or low of reversal candle) ──
        BigDecimal slPoc      = poc.multiply(new BigDecimal("0.998")); // 0.2% below POC
        BigDecimal slCandle   = cur.getLow().multiply(new BigDecimal("0.999")); // candle low − 0.1%
        // For LONG: higher SL = tighter
        BigDecimal sl         = slPoc.compareTo(slCandle) > 0 ? slPoc : slCandle;

        BigDecimal risk = price.subtract(sl);
        if (risk.compareTo(BigDecimal.ZERO) <= 0) return Optional.empty();

        // Target = Entry + 3R
        BigDecimal target = price.add(risk.multiply(BigDecimal.valueOf(targetRR)));

        // Trailing trigger = previous swing high (move to breakeven once that clears)
        BigDecimal prevSwingHigh = c5m.subList(1, Math.min(8, c5m.size())).stream()
                .map(Candle::getHigh).max(BigDecimal::compareTo).orElse(target);

        resetState(symbol, "signal fired");

        log.info("[VAP] {} ENTRY {} sl={} tgt={} (RR=3) candleType={} buyZone=[{}-{}]",
                symbol, price, sl, target, hammer ? "HAMMER" : "BULLISH_ENGULFING",
                buyZoneFloor, buyZoneCeil);

        return Optional.of(new TradeSignal(
                TradeDirection.LONG, price, sl, target, 82, name(),
                prevSwingHigh,              // trailing trigger = move SL to BE when prev high clears
                TrailingType.BREAKEVEN_ONLY,// don't trail — breakeven + then hold to target
                30,                         // 30-min time stop
                false));
    }

    // ══════════════════════════════════════════════════════════════════
    // Candle pattern detection
    // ══════════════════════════════════════════════════════════════════

    /**
     * HAMMER:
     *   - Lower wick ≥ 2× the body length (big shadow below)
     *   - Close in the upper 30% of the candle range
     *   - Upper wick ≤ 20% of range (small upper shadow)
     *
     *   "Institutions tried to push it down, failed. Buyers stepped in."
     */
    private boolean isHammer(Candle c) {
        BigDecimal range = c.getHigh().subtract(c.getLow());
        if (range.compareTo(BigDecimal.ZERO) == 0) return false;

        BigDecimal body  = c.getClose().subtract(c.getOpen()).abs();
        BigDecimal lwick = c.getClose().min(c.getOpen()).subtract(c.getLow()); // lower wick
        BigDecimal uwick = c.getHigh().subtract(c.getClose().max(c.getOpen())); // upper wick

        double bodyR  = body.divide(range, MathContext.DECIMAL32).doubleValue();
        double lwickR = lwick.divide(range, MathContext.DECIMAL32).doubleValue();
        double uwickR = uwick.divide(range, MathContext.DECIMAL32).doubleValue();

        // Lower wick ≥ 2× body, close in upper 30%, small upper wick
        boolean lwickBig  = body.compareTo(BigDecimal.ZERO) > 0 && lwick.compareTo(body.multiply(new BigDecimal("2"))) >= 0;
        boolean closeHigh = c.getClose().subtract(c.getLow()).divide(range, MathContext.DECIMAL32).doubleValue() >= 0.7;
        boolean uwickSmall = uwickR <= 0.2;

        return lwickBig && closeHigh && uwickSmall;
    }

    /**
     * BULLISH ENGULFING:
     *   - Current candle is BULLISH
     *   - Previous candle was BEARISH (the thing being engulfed)
     *   - Current body FULLY contains the previous candle's body
     *   - Current volume > previous volume (strength confirmation)
     *
     *   "Buyers overwhelmed sellers completely."
     */
    private boolean isEngulfing(Candle cur, Candle prev) {
        if (!cur.isBullish() || !prev.isBearish()) return false;

        // Current close > prev open AND current open < prev close
        boolean engulfs = cur.getClose().compareTo(prev.getOpen()) > 0
                && cur.getOpen().compareTo(prev.getClose()) < 0;

        boolean higherVol = cur.getVolume() > prev.getVolume();

        return engulfs && higherVol;
    }

    // ══════════════════════════════════════════════════════════════════
    // Technical indicators
    // ══════════════════════════════════════════════════════════════════

    /**
     * RSI-14 (standard Wilder's RSI).
     * candles[0] = newest. Compares candles[i] (newer) to candles[i+1] (older).
     * Positive diff = price went up.
     */
    private double rsi14(List<Candle> candles) {
        int period = 14;
        if (candles.size() < period + 1) return 50.0;

        double gainSum = 0, lossSum = 0;
        for (int i = 0; i < period; i++) {
            double diff = candles.get(i).getClose().doubleValue()
                    - candles.get(i + 1).getClose().doubleValue();
            if (diff > 0) gainSum += diff;
            else lossSum += Math.abs(diff);
        }

        double avgGain = gainSum / period;
        double avgLoss = lossSum / period;
        if (avgLoss == 0) return 100.0;

        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1 + rs));
    }

    /**
     * EMA (seeds oldest candle in 2p window, iterates toward newest).
     * candles[0] = newest, so we seed at candles[warmup-1] and walk to 0.
     */
    private BigDecimal ema(List<Candle> candles, int p) {
        if (candles.size() < p) return BigDecimal.ZERO;
        double k      = 2.0 / (p + 1);
        int    warmup = Math.min(2 * p, candles.size());
        double e      = candles.get(warmup - 1).getClose().doubleValue();
        for (int i = warmup - 2; i >= 0; i--)
            e = candles.get(i).getClose().doubleValue() * k + e * (1 - k);
        return BigDecimal.valueOf(e);
    }

    /**
     * Intraday VWAP from candle list.
     */
    private BigDecimal computeVwap(List<Candle> candles) {
        BigDecimal pvSum = BigDecimal.ZERO, volSum = BigDecimal.ZERO;
        for (Candle c : candles) {
            BigDecimal typ = c.getHigh().add(c.getLow()).add(c.getClose())
                    .divide(BigDecimal.valueOf(3), MathContext.DECIMAL32);
            BigDecimal vol = BigDecimal.valueOf(c.getVolume());
            pvSum  = pvSum.add(typ.multiply(vol));
            volSum = volSum.add(vol);
        }
        return volSum.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO : pvSum.divide(volSum, MathContext.DECIMAL32);
    }

    // ══════════════════════════════════════════════════════════════════
    // State management
    // ══════════════════════════════════════════════════════════════════

    private void resetState(String symbol, String reason) {
        PbState old = states.get(symbol);
        if (old != null && old.stage() != PbStage.NONE) {
            log.debug("[VAP] {} state reset: {} (was {})", symbol, reason, old.stage());
        }
        states.put(symbol, new PbState(PbStage.NONE, BigDecimal.ZERO, 0, 0, 0));
    }

    /** Daily reset at 8:45 IST */
    @Scheduled(cron = "0 45 8 * * MON-FRI", zone = "Asia/Kolkata")
    public void resetDaily() {
        states.clear();
        log.info("[VAP] Daily state reset complete");
    }

    private boolean withinTime() {
        LocalTime now = LocalTime.now(IST);
        return !now.isBefore(LocalTime.parse(entryStart))
                && !now.isAfter(LocalTime.parse(entryEnd));
    }

    private LocalTime candleTime(Candle c) {
        try { return c.getCandleTime().atZone(IST).toLocalTime(); }
        catch (Exception e) { return LocalTime.now(IST); }
    }
}