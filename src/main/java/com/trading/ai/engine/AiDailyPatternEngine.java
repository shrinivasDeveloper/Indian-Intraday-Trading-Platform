package com.trading.ai.engine;

import com.trading.domain.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * AiDailyPatternEngine — detects 22 price-action and SMC patterns on DAILY candles.
 *
 * All patterns validated STRICTLY on daily timeframe (1-year data).
 * Returns a DailyPatterns record with individual scores (-1 to +1 per pattern).
 *
 * Patterns implemented:
 *   SMC:            BOS, CHOCH, Liquidity Sweep, Order Block, FVG
 *   Wyckoff:        Accumulation, Distribution
 *   Supply/Demand:  Supply Zone, Demand Zone (enhanced from f[47])
 *   Chart Patterns: Triple Top/Bottom, Head and Shoulders
 *   Triangles:      Ascending, Descending, Symmetrical, Expanding
 *   Channels:       Rising, Falling, Breakout, Reversal
 *   Trendlines:     Breakout, Bounce
 *
 * +1.0 = strong bullish pattern confirmed
 * -1.0 = strong bearish pattern confirmed
 *  0.0 = no pattern / neutral
 */
@Component
@ConditionalOnProperty(name = "ai.trading.enabled", havingValue = "true")
@Slf4j
public class AiDailyPatternEngine {

    // Strictness thresholds
    private static final double SWING_PCT        = 0.005; // FIX: was 0.3% — 0.5% better for daily BOS confirmation
    private static final double CLUSTER_PCT      = 0.015; // 1.5% — pattern level cluster tolerance
    private static final double FVG_MIN_PCT      = 0.003; // 0.3% — minimum FVG gap size
    // FIX: was 0.020 — lower boundary 0%, upper 1.5% (OB zone = candle body + small extension)
    private static final double OB_TOLERANCE_UP  = 0.015; // 1.5% above OB top
    private static final double OB_TOLERANCE_DN  = 0.003; // 0.3% below OB bottom (near-miss only)
    private static final double TRENDLINE_TOL    = 0.012; // 1.2% — trendline touch tolerance
    private static final double CHANNEL_TOL      = 0.015; // 1.5% — channel boundary tolerance
    private static final int    MIN_SWING_SEP    = 10;    // FIX: was 3 — daily triple top needs 10+ days between peaks
    private static final int    LOOKBACK_PATTERN = 120;   // candles used for pattern detection
    // 120 days (6 months) gives enough room for:
    // Triple Top/Bottom (3 peaks × 10d = 40d min)
    // H&S (LS + Head + RS = 40-60d typical)
    // Triangle (3 touches per side = 40d min)
    // Was 60 — too tight for longer patterns

    // ═══════════════════════════════════════════════════════════════════════
    // MAIN ENTRY — compute all daily patterns for one symbol
    // ═══════════════════════════════════════════════════════════════════════

    public record DailyPatterns(
            double bos,               // f[60] Break of Structure
            double choch,             // f[61] Change of Character
            double orderBlock,        // f[62] Order Block proximity
            double fvg,               // f[63] Fair Value Gap
            double accumDist,         // f[64] Accumulation / Distribution
            double triplePat,         // f[65] Triple Top / Triple Bottom
            double headShoulders,     // f[66] Head and Shoulders
            double triangle,          // f[67] Triangle (ascending/descending/symmetrical)
            double channel,           // f[68] Channel (rising/falling/breakout/reversal)
            double trendlinePat       // f[69] Trendline breakout / bounce on daily
    ) {
        /** Count of bullish daily patterns confirmed */
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
        /** Count of bearish daily patterns confirmed */
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

        // Swing points used by multiple patterns
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

    // ═══════════════════════════════════════════════════════════════════════
    // SMC PATTERNS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * BOS — Break of Structure.
     * Bullish BOS: daily close breaks above recent swing high with volume.
     * Bearish BOS: daily close breaks below recent swing low with volume.
     * Strict: must happen within last 3 candles, volume > 1.2× average.
     */
    private double detectBOS(double ltp, List<Candle> w,
                             List<SwingPoint> highs, List<SwingPoint> lows) {
        if (highs.isEmpty() || lows.isEmpty()) return 0;
        int n = w.size();
        double avgVol = averageVolume(w, 20);

        // Proximity tolerance: price must still be within 1.5% of BOS level.
        // BOS fires when daily close broke the swing high.
        // By 11AM, if stock ran 4% above BOS level → opportunity gone → skip.
        final double BOS_PROXIMITY = 0.015; // 1.5%

        // Bullish BOS: recent close breaks above a prior swing high
        for (int i = n - 3; i < n; i++) {
            if (i < 0) continue;
            double close = w.get(i).getClose().doubleValue();
            double vol   = w.get(i).getVolume();
            for (SwingPoint sh : highs) {
                if (sh.index >= i) continue;
                if (sh.index < i - 15) break;
                if (close > sh.price * (1 + SWING_PCT) && vol > avgVol * 1.2) {
                    // PROXIMITY: ltp must be within 1.5% above the BOS swing level
                    // If stock already ran 3%+ above BOS level → skip
                    if (ltp <= sh.price * (1 + BOS_PROXIMITY)) {
                        return 1.0; // confirmed bullish BOS, price still near level
                    }
                    return 0; // BOS confirmed but price moved too far — opportunity gone
                }
            }
        }

        // Bearish BOS: recent close breaks below a prior swing low
        for (int i = n - 3; i < n; i++) {
            if (i < 0) continue;
            double close = w.get(i).getClose().doubleValue();
            double vol   = w.get(i).getVolume();
            for (SwingPoint sl : lows) {
                if (sl.index >= i) continue;
                if (sl.index < i - 15) break;
                if (close < sl.price * (1 - SWING_PCT) && vol > avgVol * 1.2) {
                    // PROXIMITY: ltp must be within 1.5% below the BOS swing level
                    if (ltp >= sl.price * (1 - BOS_PROXIMITY)) {
                        return -1.0; // confirmed bearish BOS, price still near level
                    }
                    return 0; // BOS confirmed but price dropped too far — gone
                }
            }
        }
        return 0;
    }

    /**
     * CHOCH — Change of Character.
     * After minimum 3 lower highs (downtrend): price makes higher high → bull CHOCH.
     * After minimum 3 higher lows (uptrend): price makes lower low → bear CHOCH.
     * Strict: requires confirmed sequence of at least 3 before change.
     */
    private double detectCHOCH(double ltp, List<Candle> w,
                               List<SwingPoint> highs, List<SwingPoint> lows) {
        // FIX: List is sorted NEWEST FIRST (index descending).
        // highs[0] = newest swing high, highs[1] = older, highs[2] = even older.
        //
        // DOWNTREND (lower highs over time) means:
        //   newest high < second-newest high < third-newest etc.
        //   i.e., highs[0] < highs[1] < highs[2] in newest-first order
        //   So: highs[i].price > highs[i-1].price for i=1,2,3 = downtrend structure
        //
        // Bull CHOCH: was in downtrend, LATEST high breaks ABOVE prev high
        //   Downtrend confirmed by highs[1] > highs[2] > highs[3]
        //   CHOCH: highs[0] > highs[1] (latest > second-latest = structure break)

        // Check bull CHOCH: was in downtrend (older highs higher), now latest is higher
        if (highs.size() >= 4) {
            boolean downtrend = true;
            for (int i = 2; i < Math.min(4, highs.size()); i++) {
                if (highs.get(i).price <= highs.get(i - 1).price) {
                    downtrend = false;
                    break;
                }
            }
            if (downtrend && highs.get(0).price > highs.get(1).price * (1 + SWING_PCT)) {
                // PROXIMITY: ltp must be within 1.5% above the CHOCH level (highs[1])
                // If stock already ran 3%+ above the broken high → opportunity gone
                double chochLevel = highs.get(1).price;
                if (ltp <= chochLevel * 1.015) {
                    return 1.0; // bull CHOCH confirmed, price still near level
                }
                return 0; // CHOCH confirmed but price ran too far
            }
        }

        // Check bear CHOCH: was in uptrend (older lows lower), now latest is lower
        if (lows.size() >= 4) {
            boolean uptrend = true;
            for (int i = 2; i < Math.min(4, lows.size()); i++) {
                if (lows.get(i).price >= lows.get(i - 1).price) {
                    uptrend = false;
                    break;
                }
            }
            if (uptrend && lows.get(0).price < lows.get(1).price * (1 - SWING_PCT)) {
                // PROXIMITY: ltp must be within 1.5% below the CHOCH level (lows[1])
                double chochLevel = lows.get(1).price;
                if (ltp >= chochLevel * 0.985) {
                    return -1.0; // bear CHOCH confirmed, price still near level
                }
                return 0; // CHOCH confirmed but price dropped too far
            }
        }
        return 0;
    }

    /**
     * Order Block.
     * Bullish OB: last bearish candle before a bullish BOS.
     *   Price returning to OB zone = institutional demand.
     * Bearish OB: last bullish candle before a bearish BOS.
     *   Price returning to OB zone = institutional supply.
     * Strict: OB must be clear candle body, price currently inside OB zone.
     */
    private double detectOrderBlock(double ltp, List<Candle> w,
                                    List<SwingPoint> highs, List<SwingPoint> lows,
                                    double atr) {
        int n = w.size();
        if (n < 10) return 0;

        double avgVol = averageVolume(w, 20);

        // Find bullish BOS candles in recent history (last 20 candles)
        for (int i = Math.max(1, n - 20); i < n; i++) {
            double close = w.get(i).getClose().doubleValue();
            double vol   = w.get(i).getVolume();
            // Is this a bullish BOS candle?
            boolean isBullBOS = false;
            for (SwingPoint sh : highs) {
                if (sh.index < i && sh.index >= i - 15) {
                    if (close > sh.price * (1 + SWING_PCT) && vol > avgVol * 1.1) {
                        isBullBOS = true;
                        break;
                    }
                }
            }
            if (isBullBOS) {
                // FIX: was looking back 10 candles — OB is the LAST opposing candle
                // Standard SMC: look back max 3 candles before BOS for OB
                for (int j = i - 1; j >= Math.max(0, i - 3); j--) {
                    double obOpen  = w.get(j).getOpen().doubleValue();
                    double obClose = w.get(j).getClose().doubleValue();
                    if (obClose < obOpen) { // bearish candle = bullish OB
                        double obTop = obOpen;
                        double obBot = obClose;
                        // Is current price inside the OB zone?
                        if (ltp >= obBot * (1 - OB_TOLERANCE_DN)
                                && ltp <= obTop * (1 + OB_TOLERANCE_UP)) {
                            return 1.0; // price at bullish order block
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
                    if (close < sl.price * (1 - SWING_PCT) && vol > averageVolume(w, 20) * 1.1) {
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
                        if (ltp >= obBot * (1 - OB_TOLERANCE_UP)
                                && ltp <= obTop * (1 + OB_TOLERANCE_DN)) {
                            return -1.0; // price at bearish order block
                        }
                        break;
                    }
                }
            }
        }
        return 0;
    }

    /**
     * FVG — Fair Value Gap (STRICT version).
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
                // Entry: price is now pulling back INTO the gap from above
                // ltp must be inside gap AND last close was above gap
                if (priceAboveGap && ltp >= h1 * 0.999 && ltp <= l3 * 1.002) {
                    double lastClose = w.get(n - 2).getClose().doubleValue();
                    // Last close was above the gap bottom = entering from above
                    if (lastClose > ltp) {
                        return 1.0; // bullish FVG pullback entry
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
                // Entry: price bouncing up into the gap from below
                if (priceBelowGap && ltp >= h3 * 0.998 && ltp <= l1 * 1.001) {
                    double lastClose = w.get(n - 2).getClose().doubleValue();
                    if (lastClose < ltp) {
                        return -1.0; // bearish FVG bounce entry
                    }
                }
            }
        }
        return 0;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // WYCKOFF PATTERNS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Accumulation / Distribution.
     * Accumulation: price range contracting, holding near lows, volume declining.
     *   Last candle breaks above range = Spring/markup signal.
     * Distribution: range contracting at highs, volume declining, then break down.
     * Strict: requires 15+ days of contraction before calling it.
     */
    private double detectAccumDist(double ltp, List<Candle> w, double atr) {
        int n = w.size();
        if (n < 20) return 0;

        int look = Math.min(20, n);
        List<Candle> recent = w.subList(n - look, n);

        // Price range in window
        double high = recent.stream().mapToDouble(c -> c.getHigh().doubleValue()).max().orElse(0);
        double low  = recent.stream().mapToDouble(c -> c.getLow().doubleValue()).min().orElse(0);
        if (high == 0 || low == 0) return 0;
        double range = (high - low) / ((high + low) / 2.0);

        // Is range contracting? (range < 8% over 20 days = tight consolidation)
        if (range > 0.08) return 0; // too wide — not accumulation/distribution

        // FIX: Wyckoff accumulation uses DIRECTIONAL volume check
        // Accumulation: up-day average volume > down-day average volume
        // (institutions buying quietly on down days but absorbing on up days)
        // Old code compared total early vs late volume — wrong.
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
        // STRICT: up-day avg volume must be clearly higher than down-day avg volume
        // Real accumulation = institutions buying actively on up days
        // Old: >= 0.9 (fired on almost every range-bound stock)
        boolean volDeclining = avgUpVol > avgDownVol * 1.2; // up days 20%+ more volume than down days

        // Where is price within the range?
        double rangePos = (ltp - low) / (high - low);

        // Accumulation: range is tight, volume declining, price near lows, last candle up
        if (volDeclining && rangePos < 0.35) {
            // Near bottom of consolidation = potential accumulation
            Candle last = recent.get(look - 1);
            boolean lastBull = last.getClose().doubleValue() > last.getOpen().doubleValue();
            return lastBull ? 0.8 : 0.4; // stronger if last candle is bullish
        }

        // Distribution: tight range, volume declining, price near highs, last candle down
        if (volDeclining && rangePos > 0.65) {
            Candle last = recent.get(look - 1);
            boolean lastBear = last.getClose().doubleValue() < last.getOpen().doubleValue();
            return lastBear ? -0.8 : -0.4;
        }

        return 0;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CHART PATTERNS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Triple Top / Triple Bottom.
     * Three peaks at approximately same level (within CLUSTER_PCT) separated by valleys.
     * Triple Bottom: three troughs at same level — bullish reversal signal.
     * Triple Top: three peaks at same level — bearish reversal signal.
     * Strict: peaks separated by at least MIN_SWING_SEP candles each.
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
                    // Price bouncing from third bottom
                    if (Math.abs(ltp - l3.price) / l3.price < 0.03 && ltp > l3.price) {
                        return 1.0; // triple bottom confirmed
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
                    if (Math.abs(ltp - h3.price) / h3.price < 0.03 && ltp < h3.price) {
                        return -1.0; // triple top confirmed
                    }
                }
            }
        }
        return 0;
    }

    /**
     * Head and Shoulders (top) and Inverse Head and Shoulders (bottom).
     * H&S Top: left shoulder, higher head, right shoulder (lower than head, near left).
     *   Bearish reversal. SHORT when price breaks neckline.
     * Inv H&S: inverted at lows. Bullish reversal. LONG when price breaks neckline.
     * Strict: head must be clearly higher/lower than both shoulders.
     *         Shoulders within 3% of each other.
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
                        return -1.0; // confirmed H&S breakdown, still tradeable
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
                        return 1.0; // confirmed inv H&S breakout, still tradeable
                    }
                    return 0; // too far above neckline — chasing the long
                }
            }
        }
        return 0;
    }

    /**
     * Triangle Patterns (Ascending, Descending, Symmetrical, Expanding).
     * Needs at least 2 swing highs + 2 swing lows to define trendlines.
     * Ascending:  horizontal highs + rising lows → LONG breakout
     * Descending: horizontal lows  + falling highs → SHORT breakdown
     * Symmetrical: converging highs and lows → neutral (break either way)
     * Expanding:  diverging highs and lows → volatile
     */
    /**
     * Triangle Patterns — STRICT version.
     *
     * Requires minimum 3 swing highs AND 3 swing lows.
     * Two points define any line — 3+ touches are required to confirm
     * a real horizontal resistance or rising support.
     *
     * Ascending:   highs within 1% of each other (flat resistance, 3 tests)
     *              + rising lows (2+ higher lows)
     *              + price near flat resistance = breakout imminent
     *
     * Descending:  lows within 1% of each other (flat support, 3 tests)
     *              + falling highs
     *
     * Symmetrical: removed — too many false positives. Two converging lines
     *              defined by 2 points each is not a validated triangle.
     */
    private double detectTriangle(double ltp, List<Candle> w,
                                  List<SwingPoint> highs, List<SwingPoint> lows) {
        // Require minimum 3 swing points per side for a validated triangle
        if (highs.size() < 3 || lows.size() < 3) return 0;

        // Flat resistance check: all swing highs within 1% of the average high
        double avgHigh = highs.stream().limit(3).mapToDouble(p -> p.price).average().orElse(0);
        boolean flatHighs = avgHigh > 0 && highs.stream().limit(3)
                .allMatch(p -> Math.abs(p.price - avgHigh) / avgHigh < 0.010);

        // Flat support check: all swing lows within 1% of the average low
        double avgLow = lows.stream().limit(3).mapToDouble(p -> p.price).average().orElse(0);
        boolean flatLows = avgLow > 0 && lows.stream().limit(3)
                .allMatch(p -> Math.abs(p.price - avgLow) / avgLow < 0.010);

        // Rising lows: each successive low is higher than the previous
        // List is newest-first, so lows[0] > lows[1] > lows[2] = rising lows
        boolean risingLows = lows.get(0).price > lows.get(1).price * (1 + 0.003)
                && lows.get(1).price > lows.get(2).price * (1 + 0.003);

        // Falling highs: each successive high is lower
        // List newest-first: highs[0] < highs[1] < highs[2] = falling highs
        boolean fallingHighs = highs.get(0).price < highs.get(1).price * (1 - 0.003)
                && highs.get(1).price < highs.get(2).price * (1 - 0.003);

        // ── Ascending triangle (LONG setup) ──────────────────────────────
        // Flat resistance tested 3 times + rising lows
        if (flatHighs && risingLows) {
            // Price near flat resistance = approaching breakout point
            if (ltp > avgHigh * 0.990 && ltp < avgHigh * 1.010) return 0.8;
            // Confirmed breakout above resistance with clear margin
            if (ltp > avgHigh * 1.010) return 1.0;
        }

        // ── Descending triangle (SHORT setup) ─────────────────────────────
        // Flat support tested 3 times + falling highs
        if (flatLows && fallingHighs) {
            if (ltp < avgLow * 1.010 && ltp > avgLow * 0.990) return -0.8;
            if (ltp < avgLow * 0.990) return -1.0;
        }

        // Symmetrical and Expanding triangles REMOVED — insufficient validation
        // without at minimum 3 validated touches per side and a confirmed breakout

        return 0;
    }

    /**
     * Channel Patterns (Rising, Falling, Breakout, Reversal).
     * Uses linear regression on swing highs and lows.
     * Rising channel:  both slopes positive — bounce off lower channel line (LONG)
     * Falling channel: both slopes negative — bounce off upper channel line (SHORT)
     * Channel breakout: price breaks beyond channel (momentum trade)
     * Channel reversal: price touches channel extreme and reverses
     */
    private double detectChannel(double ltp, List<Candle> w,
                                 List<SwingPoint> highs, List<SwingPoint> lows) {
        // FIX: require 3+ swing points per side (slopeOf now requires 3+)
        if (highs.size() < 3 || lows.size() < 3) return 0;

        // Compute slope direction from recent swing highs and lows
        double highSlope = slopeOf(highs);
        double lowSlope  = slopeOf(lows);

        // Project channel boundaries to current position
        SwingPoint latestHigh = highs.get(0);
        SwingPoint latestLow  = lows.get(0);
        int n = w.size();

        double projectedHighLine = latestHigh.price + highSlope * (n - 1 - latestHigh.index);
        double projectedLowLine  = latestLow.price  + lowSlope  * (n - 1 - latestLow.index);

        // Rising channel: both slopes positive (FIX: threshold 0.001 — was 0.003 missing slow channels)
        if (highSlope > 0.001 && lowSlope > 0.001) {
            // Price near lower channel line = bounce entry for LONG
            if (projectedLowLine > 0 && Math.abs(ltp - projectedLowLine) / projectedLowLine < CHANNEL_TOL) {
                return 0.9; // rising channel — at support line
            }
            // Price near upper channel line = potential SHORT or exit LONG
            if (projectedHighLine > 0 && Math.abs(ltp - projectedHighLine) / projectedHighLine < CHANNEL_TOL) {
                return -0.4; // at resistance of rising channel
            }
            // Breakout above upper channel = strong momentum LONG
            if (projectedHighLine > 0 && ltp > projectedHighLine * (1 + CHANNEL_TOL)) {
                return 1.0; // rising channel breakout
            }
        }

        // Falling channel: both slopes negative (FIX: threshold 0.001)
        if (highSlope < -0.001 && lowSlope < -0.001) {
            // Price near upper channel line = SHORT entry
            if (projectedHighLine > 0 && Math.abs(ltp - projectedHighLine) / projectedHighLine < CHANNEL_TOL) {
                return -0.9; // falling channel — at resistance line
            }
            // Price near lower channel line = potential LONG or cover SHORT
            if (projectedLowLine > 0 && Math.abs(ltp - projectedLowLine) / projectedLowLine < CHANNEL_TOL) {
                return 0.4; // at support of falling channel
            }
            // Breakdown below lower channel = strong momentum SHORT
            if (projectedLowLine > 0 && ltp < projectedLowLine * (1 - CHANNEL_TOL)) {
                return -1.0; // falling channel breakdown
            }
        }

        return 0;
    }

    /**
     * Trendline Breakout and Bounce on DAILY candles.
     * Uses actual swing points (not arbitrary candle indices).
     * Bounce: price touching trendline and rejecting — continuation setup.
     * Breakout: price closing beyond trendline with volume — momentum setup.
     * Strict: needs 3+ touches to validate trendline before calling breakout.
     */
    private double detectTrendlinePattern(double ltp, List<Candle> w,
                                          List<SwingPoint> highs, List<SwingPoint> lows,
                                          double atr) {
        int n = w.size();
        double avgVol = averageVolume(w, 20);

        // Rising trendline (support): 3+ swing lows on the same line
        if (lows.size() >= 3) {
            // Define line from oldest to newest low
            SwingPoint l1 = lows.get(lows.size() - 1); // oldest
            SwingPoint l2 = lows.get(0);                // newest
            if (l2.index > l1.index) {
                double slope     = (l2.price - l1.price) / (l2.index - l1.index);
                double projected = l2.price + slope * (n - 1 - l2.index);

                // Count INTERMEDIATE touches only (exclude l1 and l2 — definition points)
                // l1 and l2 are always on the line by construction.
                // Real validation requires 2+ intermediate points within tolerance.
                int touches = 0;
                for (SwingPoint l : lows) {
                    if (l.index == l1.index || l.index == l2.index) continue; // skip definition points
                    double expected = l2.price + slope * (n - 1 - l.index);
                    if (expected > 0 && Math.abs(l.price - expected) / expected < TRENDLINE_TOL) {
                        touches++;
                    }
                }

                // Require 2 independent intermediate touches (not the definition points)
                if (touches >= 2 && projected > 0) {
                    double dist = (ltp - projected) / projected;

                    // Bounce: price at trendline (within 1.2%)
                    if (Math.abs(dist) < TRENDLINE_TOL && slope > 0) {
                        return 0.9; // trendline bounce — bullish
                    }
                    // Breakout: price closed above with volume (rising trendline = no signal for LONG)
                    // Breakdown below rising trendline = bearish
                    if (dist < -TRENDLINE_TOL) {
                        double lastVol = w.get(n - 1).getVolume();
                        return lastVol > avgVol * 1.2 ? -1.0 : -0.6; // trendline breakdown
                    }
                }
            }
        }

        // Falling trendline (resistance): 3+ swing highs on the same line
        if (highs.size() >= 3) {
            SwingPoint h1 = highs.get(highs.size() - 1);
            SwingPoint h2 = highs.get(0);
            if (h2.index > h1.index) {
                double slope     = (h2.price - h1.price) / (h2.index - h1.index);
                double projected = h2.price + slope * (n - 1 - h2.index);

                int touches = 0;
                for (SwingPoint h : highs) {
                    if (h.index == h1.index || h.index == h2.index) continue; // skip definition points
                    double expected = h2.price + slope * (n - 1 - h.index);
                    if (expected > 0 && Math.abs(h.price - expected) / expected < TRENDLINE_TOL) {
                        touches++;
                    }
                }

                // Require 2 independent intermediate touches
                if (touches >= 2 && projected > 0) {
                    double dist = (ltp - projected) / projected;

                    // Bounce at falling trendline = bearish (rejection)
                    if (Math.abs(dist) < TRENDLINE_TOL && slope < 0) {
                        return -0.9; // falling trendline rejection — bearish
                    }
                    // Breakout ABOVE falling trendline = bullish
                    if (dist > TRENDLINE_TOL) {
                        double lastVol = w.get(n - 1).getVolume();
                        return lastVol > avgVol * 1.2 ? 1.0 : 0.6; // trendline breakout
                    }
                }
            }
        }

        return 0;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    record SwingPoint(int index, double price) {}

    /** Swing highs: local maxima with 3 candles on each side (daily = meaningful swings) */
    private List<SwingPoint> findSwingHighs(List<Candle> w) {
        List<SwingPoint> result = new ArrayList<>();
        int n = w.size();
        int lb = 3; // FIX: was 2 — too many minor swings. 3 candles each side for daily
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
        // Sort newest first
        result.sort((a, b) -> Integer.compare(b.index, a.index));
        return result;
    }

    /** Swing lows: local minima — 3 candles each side for daily */
    private List<SwingPoint> findSwingLows(List<Candle> w) {
        List<SwingPoint> result = new ArrayList<>();
        int n = w.size();
        int lb = 3; // FIX: was 2 — 3 candles each side for meaningful daily swings
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

    /**
     * Linear regression slope across ALL swing points.
     * FIX: was using only first and last point — any 2 points define a line.
     * Now uses least-squares regression across all points for robust slope.
     * Also requires minimum 3 points for a reliable channel slope.
     */
    private double slopeOf(List<SwingPoint> points) {
        if (points.size() < 3) return 0; // FIX: require 3+ points
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
        // Normalise by average price to get per-candle percentage slope
        double avgPrice = sumY / n;
        return avgPrice > 0 ? rawSlope / avgPrice : 0;
    }

    private DailyPatterns emptyPatterns() {
        return new DailyPatterns(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}