package com.trading.ai.engine;

import com.trading.domain.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConditionalOnProperty(name = "ai.trading.enabled", havingValue = "true")
@Slf4j
public class AiDailyPatternEngine {

    private static final double SWING_PCT        = 0.005;
    private static final double CLUSTER_PCT      = 0.015;
    private static final double FVG_MIN_PCT      = 0.003;
    private static final double OB_TOLERANCE_UP  = 0.015;
    private static final double OB_TOLERANCE_DN  = 0.003;
    private static final double TRENDLINE_TOL    = 0.012;
    private static final double CHANNEL_TOL      = 0.015;
    private static final int    MIN_SWING_SEP    = 10;
    private static final int    LOOKBACK_PATTERN = 120;

    public record DailyPatterns(
            double bos, double choch, double orderBlock, double fvg, double accumDist,
            double triplePat, double headShoulders, double triangle, double channel,
            double trendlinePat
    ) {
        public int bullishCount() {
            int c = 0;
            if (bos > 0.5) c++;
            if (choch > 0.5) c++;
            if (orderBlock > 0.5) c++;
            if (fvg > 0.5) c++;
            if (accumDist > 0.5) c++;
            if (triplePat > 0.5) c++;
            if (headShoulders > 0.5) c++;
            if (triangle > 0.5) c++;
            if (channel > 0.5) c++;
            if (trendlinePat > 0.5) c++;
            return c;
        }
        public int bearishCount() {
            int c = 0;
            if (bos < -0.5) c++;
            if (choch < -0.5) c++;
            if (orderBlock < -0.5) c++;
            if (fvg < -0.5) c++;
            if (accumDist < -0.5) c++;
            if (triplePat < -0.5) c++;
            if (headShoulders < -0.5) c++;
            if (triangle < -0.5) c++;
            if (channel < -0.5) c++;
            if (trendlinePat < -0.5) c++;
            return c;
        }
        public double[] toFeatures() {
            return new double[]{bos, choch, orderBlock, fvg, accumDist,
                    triplePat, headShoulders, triangle, channel, trendlinePat};
        }
    }

    public DailyPatterns detect(double ltp, List<Candle> daily) {
        if (daily.size() < 20) return emptyPatterns();

        int n = daily.size();
        int lb = Math.min(LOOKBACK_PATTERN, n);
        List<Candle> window = daily.subList(n - lb, n);

        List<SwingPoint> highs = findSwingHighs(window);
        List<SwingPoint> lows  = findSwingLows(window);

        double atr = computeATR(window, Math.min(14, window.size() - 1));

        return new DailyPatterns(
                detectBOS(ltp, window, highs, lows),
                detectCHOCH(ltp, window, highs, lows),
                detectOrderBlock(ltp, window, highs, lows, atr),
                detectFVG(ltp, window, atr),
                detectAccumDist(ltp, window, atr),
                detectTriplePattern(ltp, window, highs, lows),
                detectHeadAndShoulders(ltp, window, highs, lows),
                detectTriangle(ltp, window, highs, lows),
                detectChannel(ltp, window, highs, lows),
                detectTrendlinePattern(ltp, window, highs, lows, atr)
        );
    }

    private double detectBOS(double ltp, List<Candle> w,
                             List<SwingPoint> highs, List<SwingPoint> lows) {
        if (highs.isEmpty() || lows.isEmpty()) return 0;
        int n = w.size();
        double avgVol = averageVolume(w, 20);
        final double BOS_PROXIMITY = 0.015;

        for (int i = n - 3; i < n; i++) {
            if (i < 0) continue;
            double close = w.get(i).getClose().doubleValue();
            double vol   = w.get(i).getVolume();
            for (SwingPoint sh : highs) {
                if (sh.index >= i) continue;
                if (sh.index < i - 15) break;
                if (close > sh.price * (1 + SWING_PCT) && vol > avgVol * 1.2) {
                    if (ltp <= sh.price * (1 + BOS_PROXIMITY)) {
                        return 1.0;
                    }
                    return 0;
                }
            }
        }

        for (int i = n - 3; i < n; i++) {
            if (i < 0) continue;
            double close = w.get(i).getClose().doubleValue();
            double vol   = w.get(i).getVolume();
            for (SwingPoint sl : lows) {
                if (sl.index >= i) continue;
                if (sl.index < i - 15) break;
                if (close < sl.price * (1 - SWING_PCT) && vol > avgVol * 1.2) {
                    if (ltp >= sl.price * (1 - BOS_PROXIMITY)) {
                        return -1.0;
                    }
                    return 0;
                }
            }
        }
        return 0;
    }

    private double detectCHOCH(double ltp, List<Candle> w,
                               List<SwingPoint> highs, List<SwingPoint> lows) {
        if (highs.size() >= 4) {
            boolean downtrend = true;
            for (int i = 2; i < Math.min(4, highs.size()); i++) {
                if (highs.get(i).price <= highs.get(i - 1).price) {
                    downtrend = false;
                    break;
                }
            }
            if (downtrend && highs.get(0).price > highs.get(1).price * (1 + SWING_PCT)) {
                double chochLevel = highs.get(1).price;
                if (ltp <= chochLevel * 1.015) {
                    return 1.0;
                }
                return 0;
            }
        }

        if (lows.size() >= 4) {
            boolean uptrend = true;
            for (int i = 2; i < Math.min(4, lows.size()); i++) {
                if (lows.get(i).price >= lows.get(i - 1).price) {
                    uptrend = false;
                    break;
                }
            }
            if (uptrend && lows.get(0).price < lows.get(1).price * (1 - SWING_PCT)) {
                double chochLevel = lows.get(1).price;
                if (ltp >= chochLevel * 0.985) {
                    return -1.0;
                }
                return 0;
            }
        }
        return 0;
    }

    /**
     * Order Block (STRICT, further tightened per explicit user request
     * for better win rate - 3 confirmed gaps fixed):
     *
     *   a) Volume threshold aligned to 1.2x (was 1.1x) - matches
     *      detectBOS()'s own established standard elsewhere in this
     *      file exactly. Previously Order Block's underlying BOS
     *      confirmation was weaker than the dedicated BOS pattern's.
     *
     *   b) Freshness check added - verifies this is the FIRST time
     *      price has returned to the OB zone since it formed. In
     *      genuine SMC theory, an order block's resting institutional
     *      orders are typically consumed on the first retest; a zone
     *      that's already been revisited (whether it held or not) is a
     *      depleted, weaker signal - previously any retest, first or
     *      fifth, was credited identically.
     *
     *   c) Invalidation check added - verifies price hasn't already
     *      closed cleanly through the OB's far side (below the bullish
     *      OB's bottom, or above the bearish OB's top) at any point
     *      since it formed. A level price has already broken through is
     *      a failed zone, not valid support/resistance - previously
     *      this was never checked at all.
     */
    private double detectOrderBlock(double ltp, List<Candle> w,
                                    List<SwingPoint> highs, List<SwingPoint> lows,
                                    double atr) {
        int n = w.size();
        if (n < 10) return 0;

        double avgVol = averageVolume(w, 20);

        for (int i = Math.max(1, n - 20); i < n; i++) {
            double close = w.get(i).getClose().doubleValue();
            double vol   = w.get(i).getVolume();
            boolean isBullBOS = false;
            for (SwingPoint sh : highs) {
                if (sh.index < i && sh.index >= i - 15) {
                    // FIX: aligned to 1.2x, matching detectBOS()
                    if (close > sh.price * (1 + SWING_PCT) && vol > avgVol * 1.2) {
                        isBullBOS = true;
                        break;
                    }
                }
            }
            if (isBullBOS) {
                for (int j = i - 1; j >= Math.max(0, i - 3); j--) {
                    double obOpen  = w.get(j).getOpen().doubleValue();
                    double obClose = w.get(j).getClose().doubleValue();
                    if (obClose < obOpen) { // bearish candle = bullish OB
                        double obTop = obOpen;
                        double obBot = obClose;

                        // FIX: freshness + invalidation check across every
                        // candle since the OB formed (from the BOS candle
                        // at i, up to but excluding the current/last candle).
                        boolean alreadyRetested = false;
                        boolean invalidated = false;
                        for (int m = i + 1; m < n - 1; m++) {
                            double mLow   = w.get(m).getLow().doubleValue();
                            double mClose = w.get(m).getClose().doubleValue();
                            if (mLow <= obTop * (1 + OB_TOLERANCE_UP)
                                    && mLow >= obBot * (1 - OB_TOLERANCE_DN)) {
                                alreadyRetested = true; // price already dipped
                                // into this zone before
                            }
                            if (mClose < obBot * (1 - OB_TOLERANCE_DN)) {
                                invalidated = true; // price already closed
                                // clean through the zone
                            }
                        }

                        // Is current price inside the OB zone, fresh, and not invalidated?
                        if (!alreadyRetested && !invalidated
                                && ltp >= obBot * (1 - OB_TOLERANCE_DN)
                                && ltp <= obTop * (1 + OB_TOLERANCE_UP)) {
                            return 1.0; // price at a fresh, valid bullish order block
                        }
                        break; // only use the most recent bearish candle
                    }
                }
            }
        }

        // Bearish OB: find bearish BOS then look for last bullish candle
        for (int i = Math.max(1, n - 20); i < n; i++) {
            double close = w.get(i).getClose().doubleValue();
            double vol   = w.get(i).getVolume();
            boolean isBearBOS = false;
            for (SwingPoint sl : lows) {
                if (sl.index < i && sl.index >= i - 15) {
                    // FIX: aligned to 1.2x, matching detectBOS()
                    if (close < sl.price * (1 - SWING_PCT) && vol > avgVol * 1.2) {
                        isBearBOS = true;
                        break;
                    }
                }
            }
            if (isBearBOS) {
                for (int j = i - 1; j >= Math.max(0, i - 3); j--) {
                    double obOpen  = w.get(j).getOpen().doubleValue();
                    double obClose = w.get(j).getClose().doubleValue();
                    if (obClose > obOpen) { // bullish candle = bearish OB
                        double obTop = obClose;
                        double obBot = obOpen;

                        // FIX: same freshness + invalidation check, mirrored
                        boolean alreadyRetested = false;
                        boolean invalidated = false;
                        for (int m = i + 1; m < n - 1; m++) {
                            double mHigh  = w.get(m).getHigh().doubleValue();
                            double mClose = w.get(m).getClose().doubleValue();
                            if (mHigh >= obBot * (1 - OB_TOLERANCE_UP)
                                    && mHigh <= obTop * (1 + OB_TOLERANCE_DN)) {
                                alreadyRetested = true;
                            }
                            if (mClose > obTop * (1 + OB_TOLERANCE_DN)) {
                                invalidated = true; // price already closed
                                // clean through the zone
                            }
                        }

                        if (!alreadyRetested && !invalidated
                                && ltp >= obBot * (1 - OB_TOLERANCE_UP)
                                && ltp <= obTop * (1 + OB_TOLERANCE_DN)) {
                            return -1.0; // price at a fresh, valid bearish order block
                        }
                        break;
                    }
                }
            }
        }
        return 0;
    }

    /**
     * FVG — Fair Value Gap (STRICT version, further tightened per
     * explicit user request for better win rate).
     *
     * Three-candle pattern identifying institutional imbalance zones.
     *
     * Bullish FVG: w[i].high < w[i+2].low (gap between candle i high and candle i+2 low)
     *   Formed during a sharp up-move (impulse candle in middle).
     *   Valid entry: price has since moved UP above l3, then PULLED BACK into the gap
     *   from above. Price must be approaching the gap from above, not still stuck in it.
     *
     * Bearish FVG: w[i].low > w[i+2].high
     *   Valid entry: price has since dropped below h3, then BOUNCED UP into the gap
     *   from below.
     *
     * Gap minimum: 0.5% (increased from 0.3%) — small gaps are just noise.
     * Confirmation: price must have been on the far side of the gap after formation.
     *
     * FIX (per explicit user request - freshness gap found, same class
     * already fixed for sweep and order block): previously confirmed
     * price traveled away from the gap, but never checked whether the
     * gap had ALREADY been filled/retested on an earlier visit before
     * this current one. A gap that's already been partially or fully
     * filled once is a weaker, already-consumed imbalance - now
     * requires this to be the genuine first return to the gap since it
     * formed.
     */
    private double detectFVG(double ltp, List<Candle> w, double atr) {
        int n = w.size();
        if (n < 5) return 0;
        double FVG_STRICT_PCT = 0.005; // 0.5% minimum gap — tighter than before

        // Scan last 20 daily candles for FVG zones
        for (int i = Math.max(0, n - 20); i < n - 2; i++) {
            double h1 = w.get(i).getHigh().doubleValue();
            double l1 = w.get(i).getLow().doubleValue();
            double h3 = w.get(i + 2).getHigh().doubleValue();
            double l3 = w.get(i + 2).getLow().doubleValue();

            // ── Bullish FVG ───────────────────────────────────────────────
            double bullGap = l3 - h1;
            if (bullGap > 0 && bullGap / h1 > FVG_STRICT_PCT) {
                // Confirm price traveled above gap after formation
                // (price must have closed above l3 on some candle after i+2)
                boolean priceAboveGap = false;
                for (int k = i + 3; k < n - 1; k++) {
                    if (w.get(k).getClose().doubleValue() > l3 * 1.003) {
                        priceAboveGap = true;
                        break;
                    }
                }
                // FIX: check no earlier candle already dipped into this
                // gap zone before now - if so, it's already been
                // retested/consumed once, not a fresh first return.
                boolean alreadyFilled = false;
                for (int k = i + 3; k < n - 1; k++) {
                    double kLow = w.get(k).getLow().doubleValue();
                    if (kLow <= l3 * 1.002 && kLow >= h1 * 0.999) {
                        alreadyFilled = true;
                        break;
                    }
                }
                // Entry: price is now pulling back INTO the gap from above
                // ltp must be inside gap AND last close was above gap
                if (priceAboveGap && !alreadyFilled
                        && ltp >= h1 * 0.999 && ltp <= l3 * 1.002) {
                    double lastClose = w.get(n - 2).getClose().doubleValue();
                    // Last close was above the gap bottom = entering from above
                    if (lastClose > ltp) {
                        return 1.0; // bullish FVG pullback entry, fresh gap
                    }
                }
            }

            // ── Bearish FVG ───────────────────────────────────────────────
            double bearGap = l1 - h3;
            if (bearGap > 0 && bearGap / l1 > FVG_STRICT_PCT) {
                // Price must have dropped below h3 after formation
                boolean priceBelowGap = false;
                for (int k = i + 3; k < n - 1; k++) {
                    if (w.get(k).getClose().doubleValue() < h3 * 0.997) {
                        priceBelowGap = true;
                        break;
                    }
                }
                // FIX: same freshness check, mirrored
                boolean alreadyFilled = false;
                for (int k = i + 3; k < n - 1; k++) {
                    double kHigh = w.get(k).getHigh().doubleValue();
                    if (kHigh >= h3 * 0.998 && kHigh <= l1 * 1.001) {
                        alreadyFilled = true;
                        break;
                    }
                }
                // Entry: price bouncing up into the gap from below
                if (priceBelowGap && !alreadyFilled
                        && ltp >= h3 * 0.998 && ltp <= l1 * 1.001) {
                    double lastClose = w.get(n - 2).getClose().doubleValue();
                    if (lastClose < ltp) {
                        return -1.0; // bearish FVG bounce entry, fresh gap
                    }
                }
            }
        }
        return 0;
    }

    private double detectAccumDist(double ltp, List<Candle> w, double atr) {
        int n = w.size();
        if (n < 20) return 0;

        int look = Math.min(20, n);
        List<Candle> recent = w.subList(n - look, n);

        double high = recent.stream().mapToDouble(c -> c.getHigh().doubleValue()).max().orElse(0);
        double low  = recent.stream().mapToDouble(c -> c.getLow().doubleValue()).min().orElse(0);
        if (high == 0 || low == 0) return 0;
        double range = (high - low) / ((high + low) / 2.0);

        if (range > 0.08) return 0;

        double upVol = 0, downVol = 0;
        int upCount = 0, downCount = 0;
        for (Candle can : recent) {
            double vol   = can.getVolume();
            boolean isUp = can.getClose().doubleValue() >= can.getOpen().doubleValue();
            if (isUp) { upVol += vol; upCount++; }
            else       { downVol += vol; downCount++; }
        }
        double avgUpVol   = upCount   > 0 ? upVol   / upCount   : 1;
        double avgDownVol = downCount > 0 ? downVol / downCount : 1;
        boolean volDeclining = avgUpVol > avgDownVol * 1.2;

        double rangePos = (ltp - low) / (high - low);

        if (volDeclining && rangePos < 0.35) {
            Candle last = recent.get(look - 1);
            boolean lastBull = last.getClose().doubleValue() > last.getOpen().doubleValue();
            return lastBull ? 0.8 : 0.4;
        }

        if (volDeclining && rangePos > 0.65) {
            Candle last = recent.get(look - 1);
            boolean lastBear = last.getClose().doubleValue() < last.getOpen().doubleValue();
            return lastBear ? -0.8 : -0.4;
        }

        return 0;
    }

    /**
     * Triple Top / Triple Bottom (further tightened per explicit user
     * request for better win rate).
     * Three peaks at approximately same level (within CLUSTER_PCT) separated by valleys.
     * Triple Bottom: three troughs at same level — bullish reversal signal.
     * Triple Top: three peaks at same level — bearish reversal signal.
     * Strict: peaks separated by at least MIN_SWING_SEP candles each.
     *
     * FIX (per explicit user request - invalidation gap found, same
     * class already fixed for order block): previously only checked
     * price is currently near the 3rd touch, but never verified price
     * hadn't already decisively broken through that level (and come
     * back) since that 3rd touch formed. A level that's already failed
     * once is not a genuine, still-intact pattern - now requires no
     * decisive break has occurred since the pattern completed.
     */
    private double detectTriplePattern(double ltp, List<Candle> w,
                                       List<SwingPoint> highs, List<SwingPoint> lows) {
        // Triple Bottom
        if (lows.size() >= 3) {
            for (int i = 0; i < lows.size() - 2; i++) {
                SwingPoint l1 = lows.get(i);
                SwingPoint l2 = lows.get(i + 1);
                SwingPoint l3 = lows.get(i + 2);
                // Strict separation
                if (Math.abs(l1.index - l2.index) < MIN_SWING_SEP) continue;
                if (Math.abs(l2.index - l3.index) < MIN_SWING_SEP) continue;
                // All three at same level
                double ref = l1.price;
                if (Math.abs(l2.price - ref) / ref < CLUSTER_PCT
                        && Math.abs(l3.price - ref) / ref < CLUSTER_PCT) {
                    // FIX: verify no decisive break BELOW the triple-
                    // bottom level occurred since the 3rd (most recent)
                    // touch - a genuine break invalidates the pattern.
                    boolean brokenSince = false;
                    for (int m = l1.index + 1; m < w.size(); m++) {
                        if (w.get(m).getClose().doubleValue() < ref * (1 - CLUSTER_PCT)) {
                            brokenSince = true;
                            break;
                        }
                    }
                    // Price bouncing from third bottom
                    if (!brokenSince
                            && Math.abs(ltp - l3.price) / l3.price < 0.03 && ltp > l3.price) {
                        return 1.0; // triple bottom confirmed, still intact
                    }
                }
            }
        }

        // Triple Top
        if (highs.size() >= 3) {
            for (int i = 0; i < highs.size() - 2; i++) {
                SwingPoint h1 = highs.get(i);
                SwingPoint h2 = highs.get(i + 1);
                SwingPoint h3 = highs.get(i + 2);
                if (Math.abs(h1.index - h2.index) < MIN_SWING_SEP) continue;
                if (Math.abs(h2.index - h3.index) < MIN_SWING_SEP) continue;
                double ref = h1.price;
                if (Math.abs(h2.price - ref) / ref < CLUSTER_PCT
                        && Math.abs(h3.price - ref) / ref < CLUSTER_PCT) {
                    // FIX: verify no decisive break ABOVE the triple-top
                    // level occurred since the 3rd touch.
                    boolean brokenSince = false;
                    for (int m = h1.index + 1; m < w.size(); m++) {
                        if (w.get(m).getClose().doubleValue() > ref * (1 + CLUSTER_PCT)) {
                            brokenSince = true;
                            break;
                        }
                    }
                    if (!brokenSince
                            && Math.abs(ltp - h3.price) / h3.price < 0.03 && ltp < h3.price) {
                        return -1.0; // triple top confirmed, still intact
                    }
                }
            }
        }
        return 0;
    }

    /**
     * Head and Shoulders (top) and Inverse Head and Shoulders (bottom),
     * further tightened per explicit user request for better win rate.
     * H&S Top: left shoulder, higher head, right shoulder (lower than head, near left).
     *   Bearish reversal. SHORT when price breaks neckline.
     * Inv H&S: inverted at lows. Bullish reversal. LONG when price breaks neckline.
     * Strict: head must be clearly higher/lower than both shoulders.
     *         Shoulders within 3% of each other.
     *
     * FIX (per explicit user request - invalidation gap found, same
     * class already fixed for order block): the confirmed-breakdown/
     * breakout checks already required proximity, but never verified
     * price hadn't already reclaimed back through the neckline (closed
     * back on the wrong side) since the actual break - a genuine
     * failed breakdown/breakout, not a still-valid one.
     */
    private double detectHeadAndShoulders(double ltp, List<Candle> w,
                                          List<SwingPoint> highs, List<SwingPoint> lows) {
        // H&S Top (bearish)
        if (highs.size() >= 3) {
            for (int i = 0; i < highs.size() - 2; i++) {
                SwingPoint ls = highs.get(i + 2); // left shoulder (oldest)
                SwingPoint h  = highs.get(i + 1); // head (middle)
                SwingPoint rs = highs.get(i);     // right shoulder (newest)
                // Head must be higher than both shoulders by at least 1%
                if (h.price <= ls.price * 1.01 || h.price <= rs.price * 1.01) continue;
                // Shoulders at similar level (within 3%)
                if (Math.abs(ls.price - rs.price) / ls.price > 0.03) continue;
                // Price currently near right shoulder level = forming pattern
                if (Math.abs(ltp - rs.price) / rs.price < 0.02) {
                    return -0.8; // H&S top forming — bearish
                }
                // Price breaking below neckline (low between shoulder and head)
                double neckline = Math.min(
                        w.subList(ls.index, h.index + 1).stream()
                                .mapToDouble(c -> c.getLow().doubleValue()).min().orElse(ltp),
                        w.subList(h.index, rs.index + 1).stream()
                                .mapToDouble(c -> c.getLow().doubleValue()).min().orElse(ltp));
                if (ltp < neckline * 0.995) {
                    // PROXIMITY: price must be within 2% below neckline
                    // If price already dropped 4%+ below neckline → short opportunity gone
                    if (ltp >= neckline * 0.980) {
                        // FIX: verify price hasn't already reclaimed back
                        // above the neckline since the right shoulder -
                        // a genuine break-then-reclaim invalidates the setup.
                        boolean reclaimed = false;
                        for (int m = rs.index + 1; m < w.size() - 1; m++) {
                            if (w.get(m).getClose().doubleValue() > neckline * 1.003) {
                                reclaimed = true;
                                break;
                            }
                        }
                        if (!reclaimed) {
                            return -1.0; // confirmed H&S breakdown, still tradeable
                        }
                        return 0; // already reclaimed - failed breakdown
                    }
                    return 0; // too far below neckline — chasing the short
                }
            }
        }

        // Inverse H&S (bullish)
        if (lows.size() >= 3) {
            for (int i = 0; i < lows.size() - 2; i++) {
                SwingPoint ls = lows.get(i + 2);
                SwingPoint h  = lows.get(i + 1);
                SwingPoint rs = lows.get(i);
                // Head lower than both shoulders
                if (h.price >= ls.price * 0.99 || h.price >= rs.price * 0.99) continue;
                if (Math.abs(ls.price - rs.price) / ls.price > 0.03) continue;
                if (Math.abs(ltp - rs.price) / rs.price < 0.02) {
                    return 0.8; // inv H&S forming — bullish
                }
                double neckline = Math.max(
                        w.subList(ls.index, h.index + 1).stream()
                                .mapToDouble(c -> c.getHigh().doubleValue()).max().orElse(ltp),
                        w.subList(h.index, rs.index + 1).stream()
                                .mapToDouble(c -> c.getHigh().doubleValue()).max().orElse(ltp));
                if (ltp > neckline * 1.005) {
                    // PROXIMITY: price must be within 2% above neckline
                    // If price already ran 4%+ above neckline → long opportunity gone
                    if (ltp <= neckline * 1.020) {
                        // FIX: verify price hasn't already fallen back
                        // below the neckline since the right shoulder.
                        boolean reclaimed = false;
                        for (int m = rs.index + 1; m < w.size() - 1; m++) {
                            if (w.get(m).getClose().doubleValue() < neckline * 0.997) {
                                reclaimed = true;
                                break;
                            }
                        }
                        if (!reclaimed) {
                            return 1.0; // confirmed inv H&S breakout, still tradeable
                        }
                        return 0; // already fell back - failed breakout
                    }
                    return 0; // too far above neckline — chasing the long
                }
            }
        }
        return 0;
    }

    private double detectTriangle(double ltp, List<Candle> w,
                                  List<SwingPoint> highs, List<SwingPoint> lows) {
        if (highs.size() < 3 || lows.size() < 3) return 0;

        double avgHigh = highs.stream().limit(3).mapToDouble(p -> p.price).average().orElse(0);
        boolean flatHighs = avgHigh > 0 && highs.stream().limit(3)
                .allMatch(p -> Math.abs(p.price - avgHigh) / avgHigh < 0.010);

        double avgLow = lows.stream().limit(3).mapToDouble(p -> p.price).average().orElse(0);
        boolean flatLows = avgLow > 0 && lows.stream().limit(3)
                .allMatch(p -> Math.abs(p.price - avgLow) / avgLow < 0.010);

        boolean risingLows = lows.get(0).price > lows.get(1).price * (1 + 0.003)
                && lows.get(1).price > lows.get(2).price * (1 + 0.003);

        boolean fallingHighs = highs.get(0).price < highs.get(1).price * (1 - 0.003)
                && highs.get(1).price < highs.get(2).price * (1 - 0.003);

        if (flatHighs && risingLows) {
            if (ltp > avgHigh * 0.990 && ltp < avgHigh * 1.010) return 0.8;
            if (ltp > avgHigh * 1.010) return 1.0;
        }

        if (flatLows && fallingHighs) {
            if (ltp < avgLow * 1.010 && ltp > avgLow * 0.990) return -0.8;
            if (ltp < avgLow * 0.990) return -1.0;
        }

        return 0;
    }

    private double detectChannel(double ltp, List<Candle> w,
                                 List<SwingPoint> highs, List<SwingPoint> lows) {
        if (highs.size() < 3 || lows.size() < 3) return 0;

        double highSlope = slopeOf(highs);
        double lowSlope  = slopeOf(lows);

        SwingPoint latestHigh = highs.get(0);
        SwingPoint latestLow  = lows.get(0);
        int n = w.size();

        double projectedHighLine = latestHigh.price + highSlope * (n - 1 - latestHigh.index);
        double projectedLowLine  = latestLow.price  + lowSlope  * (n - 1 - latestLow.index);

        if (highSlope > 0.001 && lowSlope > 0.001) {
            if (projectedLowLine > 0 && Math.abs(ltp - projectedLowLine) / projectedLowLine < CHANNEL_TOL) {
                return 0.9;
            }
            if (projectedHighLine > 0 && Math.abs(ltp - projectedHighLine) / projectedHighLine < CHANNEL_TOL) {
                return -0.4;
            }
            if (projectedHighLine > 0 && ltp > projectedHighLine * (1 + CHANNEL_TOL)) {
                return 1.0;
            }
        }

        if (highSlope < -0.001 && lowSlope < -0.001) {
            if (projectedHighLine > 0 && Math.abs(ltp - projectedHighLine) / projectedHighLine < CHANNEL_TOL) {
                return -0.9;
            }
            if (projectedLowLine > 0 && Math.abs(ltp - projectedLowLine) / projectedLowLine < CHANNEL_TOL) {
                return 0.4;
            }
            if (projectedLowLine > 0 && ltp < projectedLowLine * (1 - CHANNEL_TOL)) {
                return -1.0;
            }
        }

        return 0;
    }

    private double detectTrendlinePattern(double ltp, List<Candle> w,
                                          List<SwingPoint> highs, List<SwingPoint> lows,
                                          double atr) {
        int n = w.size();
        double avgVol = averageVolume(w, 20);

        if (lows.size() >= 3) {
            SwingPoint l1 = lows.get(lows.size() - 1);
            SwingPoint l2 = lows.get(0);
            if (l2.index > l1.index) {
                double slope     = (l2.price - l1.price) / (l2.index - l1.index);
                double projected = l2.price + slope * (n - 1 - l2.index);

                int touches = 0;
                for (SwingPoint l : lows) {
                    if (l.index == l1.index || l.index == l2.index) continue;
                    double expected = l2.price + slope * (n - 1 - l.index);
                    if (expected > 0 && Math.abs(l.price - expected) / expected < TRENDLINE_TOL) {
                        touches++;
                    }
                }

                if (touches >= 2 && projected > 0) {
                    double dist = (ltp - projected) / projected;

                    if (Math.abs(dist) < TRENDLINE_TOL && slope > 0) {
                        return 0.9;
                    }
                    if (dist < -TRENDLINE_TOL) {
                        double lastVol = w.get(n - 1).getVolume();
                        return lastVol > avgVol * 1.2 ? -1.0 : -0.6;
                    }
                }
            }
        }

        if (highs.size() >= 3) {
            SwingPoint h1 = highs.get(highs.size() - 1);
            SwingPoint h2 = highs.get(0);
            if (h2.index > h1.index) {
                double slope     = (h2.price - h1.price) / (h2.index - h1.index);
                double projected = h2.price + slope * (n - 1 - h2.index);

                int touches = 0;
                for (SwingPoint h : highs) {
                    if (h.index == h1.index || h.index == h2.index) continue;
                    double expected = h2.price + slope * (n - 1 - h.index);
                    if (expected > 0 && Math.abs(h.price - expected) / expected < TRENDLINE_TOL) {
                        touches++;
                    }
                }

                if (touches >= 2 && projected > 0) {
                    double dist = (ltp - projected) / projected;

                    if (Math.abs(dist) < TRENDLINE_TOL && slope < 0) {
                        return -0.9;
                    }
                    if (dist > TRENDLINE_TOL) {
                        double lastVol = w.get(n - 1).getVolume();
                        return lastVol > avgVol * 1.2 ? 1.0 : 0.6;
                    }
                }
            }
        }

        return 0;
    }

    record SwingPoint(int index, double price) {}

    private List<SwingPoint> findSwingHighs(List<Candle> w) {
        List<SwingPoint> result = new ArrayList<>();
        int n = w.size();
        int lb = 3;
        for (int i = lb; i < n - lb; i++) {
            double h = w.get(i).getHigh().doubleValue();
            boolean isSwing = true;
            for (int j = i - lb; j <= i + lb; j++) {
                if (j != i && w.get(j).getHigh().doubleValue() >= h) {
                    isSwing = false;
                    break;
                }
            }
            if (isSwing) result.add(new SwingPoint(i, h));
        }
        result.sort((a, b) -> Integer.compare(b.index, a.index));
        return result;
    }

    private List<SwingPoint> findSwingLows(List<Candle> w) {
        List<SwingPoint> result = new ArrayList<>();
        int n = w.size();
        int lb = 3;
        for (int i = lb; i < n - lb; i++) {
            double l = w.get(i).getLow().doubleValue();
            boolean isSwing = true;
            for (int j = i - lb; j <= i + lb; j++) {
                if (j != i && w.get(j).getLow().doubleValue() <= l) {
                    isSwing = false;
                    break;
                }
            }
            if (isSwing) result.add(new SwingPoint(i, l));
        }
        result.sort((a, b) -> Integer.compare(b.index, a.index));
        return result;
    }

    private double computeATR(List<Candle> candles, int period) {
        if (candles.size() < 2) return 0;
        double sum = 0;
        int count = Math.min(period, candles.size() - 1);
        for (int i = candles.size() - count; i < candles.size(); i++) {
            double h = candles.get(i).getHigh().doubleValue();
            double l = candles.get(i).getLow().doubleValue();
            sum += (h - l);
        }
        return count > 0 ? sum / count : 0;
    }

    private double averageVolume(List<Candle> candles, int period) {
        int count = Math.min(period, candles.size());
        double sum = 0;
        for (int i = candles.size() - count; i < candles.size(); i++) {
            sum += candles.get(i).getVolume();
        }
        return count > 0 ? sum / count : 1;
    }

    private double slopeOf(List<SwingPoint> points) {
        if (points.size() < 3) return 0;
        int n = points.size();
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        for (SwingPoint p : points) {
            sumX  += p.index;
            sumY  += p.price;
            sumXY += (double) p.index * p.price;
            sumX2 += (double) p.index * p.index;
        }
        double denom = n * sumX2 - sumX * sumX;
        if (denom == 0) return 0;
        double rawSlope = (n * sumXY - sumX * sumY) / denom;
        double avgPrice = sumY / n;
        return avgPrice > 0 ? rawSlope / avgPrice : 0;
    }

    private DailyPatterns emptyPatterns() {
        return new DailyPatterns(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}