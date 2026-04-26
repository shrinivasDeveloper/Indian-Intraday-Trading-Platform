package com.trading.strategy.smc;

import com.trading.domain.Candle;
import com.trading.domain.enums.TradeDirection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * SmcAnalyser — stateless Smart Money Concept rule engine.
 *
 * All methods are pure functions: given candle lists and parameters → result.
 * No Spring state. Safe to call from any thread concurrently.
 *
 * ── HARD RULES (all 5 must pass) ──────────────────────────────────────────
 *   Rule 1 — HTF (4H):  price vs EMA50
 *   Rule 2 — MTF (1H):  market structure HH/HL or LH/LL
 *   Rule 3 — ETF (15m): fresh FVG (age <= 6 candles)
 *   Rule 4 — ADX > 20 on 15min
 *   Rule 5 — Liquidity sweep confirmed on 15min (recency <= 4 candles)
 *
 * ── SCORING (max 9) ───────────────────────────────────────────────────────
 *   +2  Strong FVG (gap/ATR >= 0.5)
 *   +2  Price on correct VWAP side
 *   +1  S/R distance > 1%
 *   +1  Volume > 1.5× 20-day average
 *   +1  ADX > 25
 *   +1  Sweep age <= 2 candles (very fresh)
 *   +1  FVG age <= 3 candles (very fresh)
 */
@Component
@Slf4j
public class SmcAnalyser {

    // ── Constants ─────────────────────────────────────────────────────────────
    private static final int    EMA50_PERIOD          = 50;
    private static final int    ADX_PERIOD            = 14;
    private static final double ADX_MIN               = 20.0;
    private static final double ADX_STRONG            = 25.0;
    private static final int    FVG_MAX_AGE           = 6;     // candles
    private static final int    FVG_FRESH_AGE         = 3;     // candles (bonus)
    private static final int    SWEEP_MAX_AGE         = 4;     // candles
    private static final int    SWEEP_FRESH_AGE       = 2;     // candles (bonus)
    private static final double SWEEP_EQ_TOLERANCE    = 0.003; // 0.3% for "equal" highs/lows
    private static final double FVG_STRONG_ATR_RATIO  = 0.5;   // gap/ATR for strong FVG bonus
    private static final double SR_MIN_DISTANCE_PCT   = 0.01;  // 1% from S/R
    private static final double VOL_MULTIPLIER_MIN    = 1.5;   // 1.5× 20-day avg
    private static final double SL_ATR_FACTOR         = 1.5;   // SL = entry ± ATR×1.5
    private static final double RR_TARGET1            = 2.5;   // T1 = risk × 2.5
    private static final double RR_TARGET2            = 3.5;   // T2 = risk × 3.5

    // ══════════════════════════════════════════════════════════════════════════
    // MAIN ENTRY POINT
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Analyse one symbol and return a complete SmcSetupScore.
     *
     * @param symbol    NSE trading symbol
     * @param h4        4H candles, newest first (index 0)
     * @param h1        1H candles, newest first
     * @param m15       15min candles, newest first
     * @param avgVolume 20-day average volume for the symbol
     */
    public SmcSetupScore analyse(String symbol,
                                 List<Candle> h4,
                                 List<Candle> h1,
                                 List<Candle> m15,
                                 double avgVolume) {

        log.trace("[SMC-ANALYSER] Analysing {} | 4H={} 1H={} 15M={}",
                symbol, h4.size(), h1.size(), m15.size());

        // ── Minimum data guard ────────────────────────────────────────────────
        if (h4.size() < 5 || h1.size() < 4 || m15.size() < ADX_PERIOD + 2) {
            return SmcSetupScore.failed(symbol,
                    String.format("Insufficient data: 4H=%d 1H=%d 15M=%d",
                            h4.size(), h1.size(), m15.size()));
        }

        double currentPrice = h4.get(0).getClose().doubleValue();
        double atr14        = calcAtr(m15, ADX_PERIOD);
        double adx          = calcAdx(m15, ADX_PERIOD);

        // ── RULE 1: 4H HTF Trend — price vs EMA50 ────────────────────────────
        double ema50 = calcEma(h4, EMA50_PERIOD);
        boolean bullishHtf = ema50 > 0 && currentPrice > ema50;
        boolean bearishHtf = ema50 > 0 && currentPrice < ema50;

        if (!bullishHtf && !bearishHtf) {
            return SmcSetupScore.failed(symbol,
                    String.format("Rule1 fail: price=%.2f ema50=%.2f (no clear 4H trend)",
                            currentPrice, ema50));
        }

        // Implied direction from Rule 1
        TradeDirection direction = bullishHtf ? TradeDirection.LONG : TradeDirection.SHORT;
        log.trace("[SMC-ANALYSER] {} Rule1 PASS — direction={} price={} ema50={}",
                symbol, direction, String.format("%.2f", currentPrice), String.format("%.2f", ema50));

        // ── RULE 2: 1H Structure — HH/HL or LH/LL ────────────────────────────
        int structure = detectStructure(h1, 4); // 0=neutral,1=bullish,-1=bearish
        boolean structOk = (direction == TradeDirection.LONG  && structure ==  1) ||
                (direction == TradeDirection.SHORT && structure == -1);

        if (!structOk) {
            return SmcSetupScore.failed(symbol,
                    String.format("Rule2 fail: 1H structure=%s conflicts with 4H direction=%s",
                            structure == 1 ? "BULLISH" : structure == -1 ? "BEARISH" : "NEUTRAL",
                            direction));
        }
        log.trace("[SMC-ANALYSER] {} Rule2 PASS — 1H structure confirmed", symbol);

        // ── RULE 3: 15min FVG — fresh (age <= 6) ─────────────────────────────
        int[] fvgResult = findFvg(m15, direction); // [found(0/1), age, gapSize*1000]
        if (fvgResult[0] == 0) {
            return SmcSetupScore.failed(symbol,
                    String.format("Rule3 fail: no %s FVG in last %d candles",
                            direction, FVG_MAX_AGE));
        }
        int fvgAge      = fvgResult[1];
        double fvgGap   = fvgResult[2] / 1000.0;
        log.trace("[SMC-ANALYSER] {} Rule3 PASS — FVG age={} gap={}", symbol, fvgAge, String.format("%.2f", fvgGap));

        // ── RULE 4: ADX > 20 ─────────────────────────────────────────────────
        if (adx < ADX_MIN) {
            return SmcSetupScore.failed(symbol,
                    String.format("Rule4 fail: ADX=%.1f < %.0f (ranging market)", adx, ADX_MIN));
        }
        log.trace("[SMC-ANALYSER] {} Rule4 PASS — ADX={}", symbol, String.format("%.1f", adx));

        // ── RULE 5: Liquidity Sweep — mandatory, recency <= 4 ────────────────
        int sweepAge = findLiquiditySweep(m15, direction);
        if (sweepAge < 0) {
            return SmcSetupScore.failed(symbol,
                    String.format("Rule5 fail: no %s liquidity sweep in last %d candles",
                            direction == TradeDirection.LONG ? "bullish" : "bearish",
                            SWEEP_MAX_AGE));
        }
        log.trace("[SMC-ANALYSER] {} Rule5 PASS — sweep_age={}", symbol, sweepAge);

        // ── ALL 5 RULES PASSED — now score ────────────────────────────────────
        List<String> reasons = new ArrayList<>();
        reasons.add(String.format("Rule1: 4H %s — price %.2f %s EMA50 %.2f",
                direction == TradeDirection.LONG ? "BULLISH" : "BEARISH",
                currentPrice, direction == TradeDirection.LONG ? ">" : "<", ema50));
        reasons.add(String.format("Rule2: 1H structure %s (HH/HL or LH/LL confirmed)",
                direction == TradeDirection.LONG ? "BULLISH" : "BEARISH"));
        reasons.add(String.format("Rule3: %s FVG — age=%d candles gap=%.2f",
                direction, fvgAge, fvgGap));
        reasons.add(String.format("Rule4: ADX=%.1f > %.0f (trending)", adx, ADX_MIN));
        reasons.add(String.format("Rule5: Liquidity sweep %d candles ago",
                sweepAge));

        int scoreStrongFvg = 0, scoreVwap = 0, scoreSr = 0,
                scoreVol = 0, scoreAdx25 = 0, scoreSweepFresh = 0, scoreFvgFresh = 0;

        // +2 Strong FVG
        if (atr14 > 0 && fvgGap / atr14 >= FVG_STRONG_ATR_RATIO) {
            scoreStrongFvg = 2;
            reasons.add(String.format("Score+2: Strong FVG — gap/ATR=%.2f >= %.1f",
                    fvgGap / atr14, FVG_STRONG_ATR_RATIO));
        }

        // +2 VWAP side
        double vwap = calcVwap(m15);
        boolean vwapOk = (direction == TradeDirection.LONG  && currentPrice > vwap) ||
                (direction == TradeDirection.SHORT && currentPrice < vwap);
        if (vwapOk) {
            scoreVwap = 2;
            reasons.add(String.format("Score+2: Price %.2f %s VWAP %.2f",
                    currentPrice, direction == TradeDirection.LONG ? "above" : "below", vwap));
        }

        // +1 S/R distance
        double nearestSr = nearestSupportResistance(m15, 20);
        double srDist    = Math.abs(currentPrice - nearestSr) / currentPrice;
        if (srDist >= SR_MIN_DISTANCE_PCT) {
            scoreSr = 1;
            reasons.add(String.format("Score+1: S/R distance %.2f%% >= 1%%", srDist * 100));
        }

        // +1 Volume
        double latestVol = (double) m15.get(0).getVolume();
        double avgVol20  = avgVolume > 0 ? avgVolume : latestVol; // fallback
        if (latestVol >= avgVol20 * VOL_MULTIPLIER_MIN) {
            scoreVol = 1;
            reasons.add(String.format("Score+1: Volume %.0f = %.1f× avg",
                    latestVol, latestVol / avgVol20));
        }

        // +1 ADX > 25
        if (adx >= ADX_STRONG) {
            scoreAdx25 = 1;
            reasons.add(String.format("Score+1: ADX=%.1f > %.0f (strong trend)", adx, ADX_STRONG));
        }

        // +1 Sweep freshness
        if (sweepAge <= SWEEP_FRESH_AGE) {
            scoreSweepFresh = 1;
            reasons.add(String.format("Score+1: Sweep very fresh — %d candles ago", sweepAge));
        }

        // +1 FVG freshness
        if (fvgAge <= FVG_FRESH_AGE) {
            scoreFvgFresh = 1;
            reasons.add(String.format("Score+1: FVG very fresh — %d candles ago", fvgAge));
        }

        int totalScore = scoreStrongFvg + scoreVwap + scoreSr + scoreVol +
                scoreAdx25 + scoreSweepFresh + scoreFvgFresh;

        // ── Compute SL and targets ─────────────────────────────────────────────
        BigDecimal entry = BigDecimal.valueOf(currentPrice).setScale(2, RoundingMode.HALF_UP);
        BigDecimal risk  = BigDecimal.valueOf(atr14 * SL_ATR_FACTOR).setScale(2, RoundingMode.HALF_UP);

        BigDecimal sl, t1, t2;
        if (direction == TradeDirection.LONG) {
            sl = entry.subtract(risk).setScale(2, RoundingMode.FLOOR);
            t1 = entry.add(risk.multiply(BigDecimal.valueOf(RR_TARGET1))).setScale(2, RoundingMode.HALF_UP);
            t2 = entry.add(risk.multiply(BigDecimal.valueOf(RR_TARGET2))).setScale(2, RoundingMode.HALF_UP);
        } else {
            sl = entry.add(risk).setScale(2, RoundingMode.CEILING);
            t1 = entry.subtract(risk.multiply(BigDecimal.valueOf(RR_TARGET1))).setScale(2, RoundingMode.HALF_UP);
            t2 = entry.subtract(risk.multiply(BigDecimal.valueOf(RR_TARGET2))).setScale(2, RoundingMode.HALF_UP);
        }

        return new SmcSetupScore(
                symbol, direction,
                true, true, true, true, true,
                scoreStrongFvg, scoreVwap, scoreSr, scoreVol, scoreAdx25,
                scoreSweepFresh, scoreFvgFresh, totalScore,
                entry, sl, t1, t2,
                atr14, adx, fvgAge, sweepAge, vwap, currentPrice,
                reasons, null
        );
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TECHNICAL CALCULATIONS — all pure, stateless
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Exponential Moving Average, newest-first list.
     * Returns 0 if insufficient data.
     */
    double calcEma(List<Candle> candles, int period) {
        int available = Math.min(2 * period, candles.size());
        if (available < period) return 0.0;
        double k = 2.0 / (period + 1);
        // seed from oldest available candle
        double ema = candles.get(available - 1).getClose().doubleValue();
        for (int i = available - 2; i >= 0; i--) {
            ema = candles.get(i).getClose().doubleValue() * k + ema * (1 - k);
        }
        return ema;
    }

    /**
     * ATR(14) — Average True Range, newest-first list.
     */
    double calcAtr(List<Candle> candles, int period) {
        int n = Math.min(period, candles.size() - 1);
        if (n <= 0) return 0.0;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double h  = candles.get(i).getHigh().doubleValue();
            double l  = candles.get(i).getLow().doubleValue();
            double pc = candles.get(i + 1).getClose().doubleValue();
            sum += Math.max(h - l, Math.max(Math.abs(h - pc), Math.abs(l - pc)));
        }
        return sum / n;
    }

    /**
     * ADX(period) calculation using Wilder's method.
     * Returns 0 if insufficient data.
     */
    double calcAdx(List<Candle> candles, int period) {
        if (candles.size() < period * 2 + 1) return 0.0;

        // Build +DM, -DM, TR arrays (oldest first for Wilder's smoothing)
        int n = candles.size() - 1;
        double[] plusDm  = new double[n];
        double[] minusDm = new double[n];
        double[] tr      = new double[n];

        for (int i = 0; i < n; i++) {
            // index 0 = newest, so newer is lower index
            int cur  = n - 1 - i;
            int prev = n - i;
            double highDiff = candles.get(cur).getHigh().doubleValue()
                    - candles.get(prev).getHigh().doubleValue();
            double lowDiff  = candles.get(prev).getLow().doubleValue()
                    - candles.get(cur).getLow().doubleValue();

            plusDm[i]  = (highDiff > lowDiff && highDiff > 0) ? highDiff : 0;
            minusDm[i] = (lowDiff > highDiff && lowDiff  > 0) ? lowDiff  : 0;

            double h  = candles.get(cur).getHigh().doubleValue();
            double l  = candles.get(cur).getLow().doubleValue();
            double pc = candles.get(prev).getClose().doubleValue();
            tr[i] = Math.max(h - l, Math.max(Math.abs(h - pc), Math.abs(l - pc)));
        }

        // Wilder's initial smoothing
        double smoothTr = 0, smoothPlus = 0, smoothMinus = 0;
        for (int i = 0; i < period; i++) {
            smoothTr    += tr[i];
            smoothPlus  += plusDm[i];
            smoothMinus += minusDm[i];
        }

        double[] dx = new double[n - period];
        for (int i = period; i < n; i++) {
            smoothTr    = smoothTr    - smoothTr    / period + tr[i];
            smoothPlus  = smoothPlus  - smoothPlus  / period + plusDm[i];
            smoothMinus = smoothMinus - smoothMinus / period + minusDm[i];

            double diPlus  = smoothTr > 0 ? 100 * smoothPlus  / smoothTr : 0;
            double diMinus = smoothTr > 0 ? 100 * smoothMinus / smoothTr : 0;
            double diSum   = diPlus + diMinus;
            dx[i - period] = diSum > 0 ? 100 * Math.abs(diPlus - diMinus) / diSum : 0;
        }

        // Average DX over period = ADX
        int dxLen = dx.length;
        if (dxLen < period) return 0.0;
        double adxVal = 0;
        for (int i = dxLen - period; i < dxLen; i++) adxVal += dx[i];
        return adxVal / period;
    }

    /**
     * VWAP calculated over available 15min candles.
     * Simple cumulative VWAP = sum(typical_price × volume) / sum(volume)
     */
    double calcVwap(List<Candle> candles) {
        double cumTpv = 0, cumVol = 0;
        for (Candle c : candles) {
            double tp  = (c.getHigh().doubleValue() + c.getLow().doubleValue()
                    + c.getClose().doubleValue()) / 3.0;
            double vol = (double) c.getVolume();
            cumTpv += tp * vol;
            cumVol += vol;
        }
        return cumVol > 0 ? cumTpv / cumVol : candles.get(0).getClose().doubleValue();
    }

    /**
     * Market structure detection on 1H candles.
     * Returns: 1=bullish(HH+HL), -1=bearish(LH+LL), 0=mixed/neutral
     *
     * Algorithm: compare last n swing highs and lows
     */
    int detectStructure(List<Candle> h1, int swingCount) {
        if (h1.size() < swingCount * 2) return 0;

        // Find swing highs and lows in last 2*swingCount candles
        int end = Math.min(swingCount * 2, h1.size());
        double prevHigh  = h1.get(1).getHigh().doubleValue();
        double prevLow   = h1.get(1).getLow().doubleValue();
        double curHigh   = h1.get(0).getHigh().doubleValue();
        double curLow    = h1.get(0).getLow().doubleValue();

        // Simple 2-candle structure check (most recent vs previous)
        boolean hhhl = curHigh > prevHigh && curLow > prevLow;   // higher high + higher low
        boolean lhll = curHigh < prevHigh && curLow < prevLow;   // lower high + lower low

        if (hhhl) return 1;
        if (lhll) return -1;

        // Expanded check: look at 4-candle window
        if (h1.size() >= 4) {
            double h0 = h1.get(0).getHigh().doubleValue();
            double l0 = h1.get(0).getLow().doubleValue();
            double h1v = h1.get(1).getHigh().doubleValue();
            double l1v = h1.get(1).getLow().doubleValue();
            double h2 = h1.get(2).getHigh().doubleValue();
            double l2 = h1.get(2).getLow().doubleValue();
            double h3 = h1.get(3).getHigh().doubleValue();
            double l3 = h1.get(3).getLow().doubleValue();

            // Bullish: recent high > older high AND recent low > older low
            if (h0 > h2 && l0 > l2 && h1v > h3) return 1;
            // Bearish: recent high < older high AND recent low < older low
            if (h0 < h2 && l0 < l2 && l1v < l3) return -1;
        }
        return 0;
    }

    /**
     * Fair Value Gap detection.
     * Bullish FVG: candle[i+2].high < candle[i].low  (gap up — buy-side FVG)
     * Bearish FVG: candle[i+2].low  > candle[i].high (gap down — sell-side FVG)
     *
     * Searches from newest candles backward up to FVG_MAX_AGE.
     * Returns [1, age_in_candles, gap_size×1000] or [0, -1, 0] if not found.
     */
    int[] findFvg(List<Candle> m15, TradeDirection direction) {
        int searchDepth = Math.min(FVG_MAX_AGE + 2, m15.size() - 2);

        for (int i = 0; i < searchDepth; i++) {
            double highI   = m15.get(i).getHigh().doubleValue();
            double lowI    = m15.get(i).getLow().doubleValue();
            double highI2  = m15.get(i + 2).getHigh().doubleValue();
            double lowI2   = m15.get(i + 2).getLow().doubleValue();

            if (direction == TradeDirection.LONG) {
                // Bullish FVG: gap between [i].low and [i+2].high (price jumps up, gap left below)
                if (lowI > highI2) {
                    double gap = lowI - highI2;
                    return new int[]{1, i + 1, (int)(gap * 1000)};
                }
            } else {
                // Bearish FVG: gap between [i+2].low and [i].high (price drops, gap left above)
                if (lowI2 > highI) {
                    double gap = lowI2 - highI;
                    return new int[]{1, i + 1, (int)(gap * 1000)};
                }
            }
        }
        return new int[]{0, -1, 0};
    }

    /**
     * Liquidity sweep detection.
     *
     * Bullish sweep (BUY setup): price swept equal lows then closed above.
     *   - Find a zone of equal lows (within SWEEP_EQ_TOLERANCE)
     *   - Confirm price dipped below, then closed back above (wick below + close above)
     *
     * Bearish sweep (SELL setup): price swept equal highs then closed below.
     *   - Find equal highs, wick above, close below
     *
     * Returns: candle index (age) of the sweep candle, or -1 if not found.
     */
    int findLiquiditySweep(List<Candle> m15, TradeDirection direction) {
        int searchDepth = Math.min(SWEEP_MAX_AGE + 4, m15.size() - 4);

        for (int i = 0; i < searchDepth; i++) {
            Candle c = m15.get(i);
            double high  = c.getHigh().doubleValue();
            double low   = c.getLow().doubleValue();
            double close = c.getClose().doubleValue();
            double open  = c.getOpen().doubleValue();

            if (direction == TradeDirection.LONG) {
                // Bullish sweep: lower wick penetrates a prior swing low zone, body closes above
                // Find prior equal lows in next 4-8 candles (they form the liquidity pool)
                for (int j = i + 2; j < Math.min(i + 8, m15.size()); j++) {
                    double refLow = m15.get(j).getLow().doubleValue();
                    // Check if the sweep candle went below refLow and closed back above
                    if (low < refLow && close > refLow && close > open) {
                        // Confirm there's another candle near refLow (equal lows)
                        for (int k = j + 1; k < Math.min(j + 4, m15.size()); k++) {
                            double otherLow = m15.get(k).getLow().doubleValue();
                            if (Math.abs(otherLow - refLow) / refLow <= SWEEP_EQ_TOLERANCE) {
                                log.trace("[SMC-ANALYSER] Bullish sweep at candle[{}] swept lows ~{}", i, String.format("%.2f", refLow));
                                return i;
                            }
                        }
                    }
                }
            } else {
                // Bearish sweep: upper wick penetrates prior swing highs, body closes below
                for (int j = i + 2; j < Math.min(i + 8, m15.size()); j++) {
                    double refHigh = m15.get(j).getHigh().doubleValue();
                    if (high > refHigh && close < refHigh && close < open) {
                        for (int k = j + 1; k < Math.min(j + 4, m15.size()); k++) {
                            double otherHigh = m15.get(k).getHigh().doubleValue();
                            if (Math.abs(otherHigh - refHigh) / refHigh <= SWEEP_EQ_TOLERANCE) {
                                log.trace("[SMC-ANALYSER] Bearish sweep at candle[{}] swept highs ~{}", i, String.format("%.2f", refHigh));
                                return i;
                            }
                        }
                    }
                }
            }
        }
        return -1; // no sweep found
    }

    /**
     * Nearest significant support or resistance level.
     * Simple implementation: highest high and lowest low in last n candles.
     * Returns the nearer of the two to current price.
     */
    double nearestSupportResistance(List<Candle> m15, int lookback) {
        int n = Math.min(lookback, m15.size());
        double maxHigh = Double.MIN_VALUE, minLow = Double.MAX_VALUE;
        for (int i = 1; i <= n; i++) {
            maxHigh = Math.max(maxHigh, m15.get(i < m15.size() ? i : m15.size()-1).getHigh().doubleValue());
            minLow  = Math.min(minLow,  m15.get(i < m15.size() ? i : m15.size()-1).getLow().doubleValue());
        }
        double cur = m15.get(0).getClose().doubleValue();
        return (Math.abs(cur - maxHigh) < Math.abs(cur - minLow)) ? maxHigh : minLow;
    }
}