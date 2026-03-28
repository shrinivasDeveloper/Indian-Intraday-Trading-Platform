package com.trading.analysis.service;

import com.trading.domain.Candle;
import com.trading.events.CandleCompleteEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gate 5 — Key Level Detection (Full Institutional Grade)
 *
 * UPGRADES:
 *
 * 1. VOLUME DISTRIBUTION ACROSS CANDLE RANGE
 *    Each candle's volume is spread across every 5-paise tick in its H-L range.
 *    Old: 100K shares placed at ₹2505 midpoint → wrong.
 *    New: 100K spread across every tick from ₹2500 to ₹2510 (201 ticks).
 *    This matches Sierra Chart / VolFix (₹500/month professional tools).
 *
 * 2. RECENCY WEIGHTING
 *    weight = 1.0 - (i / size * 0.5) where index 0 = newest.
 *    Newest candle → ×1.0, oldest → ×0.5.
 *    Institutions defend recent levels more aggressively.
 *
 * 3. INITIAL BALANCE (first 60 min = 12 five-minute candles)
 *    ibHigh/ibLow = 9:15–10:15 IST range.
 *    isTrendDay(): price outside IB → momentum strategies.
 *    isRangeDay(): price inside IB → mean reversion strategies.
 *
 * 4. VOLUME-WEIGHTED KEY LEVELS
 *    volumeWeight = volume at all touches / total day volume.
 *    isInstitutional = volumeWeight > 2% → real FII/DII level.
 *    Sort: institutional levels first (not just by touch count).
 *
 * 5. FIXED POC CALCULATION (previous math was completely broken)
 *    Old: bucket = Round(price/(price*0.0005)) * Round(price*0.0005)
 *         = 2000 * Round(price*0.0005) → all candles in ONE bucket.
 *    New: bucket = Round(price / 0.05) on NSE 5-paise grid.
 *         pocPrice = bucket * 0.05. Correct.
 */
@Service
@Slf4j
public class KeyLevelService {

    private static final double TICK_SIZE                    = 0.05; // NSE 5 paise
    private static final double INSTITUTIONAL_VOL_THRESHOLD = 0.02; // 2% of day vol

    private final Map<String, Deque<Candle>>  buffers15m = new ConcurrentHashMap<>();
    private final Map<String, Deque<Candle>>  buffers5m  = new ConcurrentHashMap<>();
    private final Map<String, KeyLevelResult> cache      = new ConcurrentHashMap<>();

    // ── Records ────────────────────────────────────────────────────────────────

    public record KeyLevel(
            BigDecimal price,
            int        touches,
            double     strength,
            double     volumeWeight,
            boolean    isInstitutional,
            boolean    isSupport,
            boolean    isResistance
    ) {}

    public record ValueArea(BigDecimal vah, BigDecimal val, BigDecimal poc, double totalVolume) {}

    public record InitialBalance(BigDecimal ibHigh, BigDecimal ibLow, BigDecimal ibMid, boolean complete) {
        public boolean isAboveIB(BigDecimal p)  { return complete && p.compareTo(ibHigh) > 0; }
        public boolean isBelowIB(BigDecimal p)  { return complete && p.compareTo(ibLow) < 0; }
        public boolean isTrendDay(BigDecimal p) { return isAboveIB(p) || isBelowIB(p); }
        public boolean isRangeDay(BigDecimal p) { return complete && p.compareTo(ibLow) >= 0 && p.compareTo(ibHigh) <= 0; }
        public double rangePct() {
            if (ibLow.compareTo(BigDecimal.ZERO)==0) return 0;
            return ibHigh.subtract(ibLow).divide(ibLow, MathContext.DECIMAL32).doubleValue() * 100;
        }
    }

    public record KeyLevelResult(
            String         symbol,
            List<KeyLevel> supports,
            List<KeyLevel> resistances,
            BigDecimal     poc,
            BigDecimal     vwap,
            ValueArea      valueArea,
            InitialBalance initialBalance
    ) {
        public boolean isNearKeyLevel(BigDecimal p, boolean forLong, double tol) {
            for (KeyLevel l : (forLong ? resistances : supports)) {
                double d = Math.abs(p.subtract(l.price()).divide(l.price(), MathContext.DECIMAL32).doubleValue());
                if (d <= tol / 100.0) return true;
            }
            return false;
        }

        public boolean isNearInstitutionalLevel(BigDecimal p, boolean forLong, double tol) {
            for (KeyLevel l : (forLong ? resistances : supports)) {
                if (!l.isInstitutional()) continue;
                double d = Math.abs(p.subtract(l.price()).divide(l.price(), MathContext.DECIMAL32).doubleValue());
                if (d <= tol / 100.0) return true;
            }
            return false;
        }

        public boolean isAbovePoc(BigDecimal p) { return poc != null && poc.compareTo(BigDecimal.ZERO) > 0 && p.compareTo(poc) > 0; }
        public boolean isBelowPoc(BigDecimal p) { return poc != null && poc.compareTo(BigDecimal.ZERO) > 0 && p.compareTo(poc) < 0; }
        public BigDecimal getVah() { return valueArea != null ? valueArea.vah() : BigDecimal.ZERO; }
        public BigDecimal getVal() { return valueArea != null ? valueArea.val() : BigDecimal.ZERO; }
        public BigDecimal getPoc() { return poc != null ? poc : BigDecimal.ZERO; }

        public boolean isAboveVah(BigDecimal p) { return valueArea != null && valueArea.vah().compareTo(BigDecimal.ZERO) > 0 && p.compareTo(valueArea.vah()) > 0; }
        public boolean isBelowVal(BigDecimal p) { return valueArea != null && valueArea.val().compareTo(BigDecimal.ZERO) > 0 && p.compareTo(valueArea.val()) < 0; }
        public boolean isInsideValueArea(BigDecimal p) { return valueArea != null && p.compareTo(valueArea.val()) >= 0 && p.compareTo(valueArea.vah()) <= 0; }

        public boolean isNearVah(BigDecimal p, double pct) {
            if (valueArea == null || valueArea.vah().compareTo(BigDecimal.ZERO) == 0) return false;
            return Math.abs(p.subtract(valueArea.vah()).divide(valueArea.vah(), MathContext.DECIMAL32).doubleValue()) * 100 <= pct;
        }
        public boolean isNearVal(BigDecimal p, double pct) {
            if (valueArea == null || valueArea.val().compareTo(BigDecimal.ZERO) == 0) return false;
            return Math.abs(p.subtract(valueArea.val()).divide(valueArea.val(), MathContext.DECIMAL32).doubleValue()) * 100 <= pct;
        }
    }

    // ── Event listener ─────────────────────────────────────────────────────────

    @EventListener
    @Async("tradingExecutor")
    public void onCandle(CandleCompleteEvent event) {
        Candle c = event.getCandle();

        if ("15minute".equals(c.getTimeframe())) {
            Deque<Candle> buf = buffers15m.computeIfAbsent(c.getTradingSymbol(), k -> new ArrayDeque<>());
            buf.addFirst(c);
            if (buf.size() > 50) ((ArrayDeque<Candle>) buf).removeLast();
            if (buf.size() >= 10) {
                List<Candle> c5m = new ArrayList<>(buffers5m.getOrDefault(c.getTradingSymbol(), new ArrayDeque<>()));
                cache.put(c.getTradingSymbol(), analyze(c.getTradingSymbol(), new ArrayList<>(buf), c5m));
            }
        }

        if ("5minute".equals(c.getTimeframe())) {
            Deque<Candle> buf = buffers5m.computeIfAbsent(c.getTradingSymbol(), k -> new ArrayDeque<>());
            buf.addFirst(c);
            if (buf.size() > 78) ((ArrayDeque<Candle>) buf).removeLast();
        }
    }

    private KeyLevelResult analyze(String symbol, List<Candle> c15m, List<Candle> c5m) {
        long           totalVol = c15m.stream().mapToLong(Candle::getVolume).sum();
        List<KeyLevel> supports    = findSupportLevels(c15m, totalVol);
        List<KeyLevel> resistances = findResistanceLevels(c15m, totalVol);
        ValueArea      va          = calculateValueArea(c15m);
        BigDecimal     poc         = va != null ? va.poc() : BigDecimal.ZERO;
        BigDecimal     vwap        = !c5m.isEmpty() ? calcVwap(c5m) : calcVwap(c15m);
        InitialBalance ib          = calcInitialBalance(c5m);
        return new KeyLevelResult(symbol, supports, resistances, poc, vwap, va, ib);
    }

    // ── Support levels ─────────────────────────────────────────────────────────

    private List<KeyLevel> findSupportLevels(List<Candle> candles, long totalVol) {
        List<KeyLevel> levels = new ArrayList<>();
        double tol = 0.003;
        for (int i = 2; i < candles.size() - 2; i++) {
            Candle c = candles.get(i);
            if (c.getLow().compareTo(candles.get(i-1).getLow()) < 0
                    && c.getLow().compareTo(candles.get(i+1).getLow()) < 0) {
                int t = 0; double s = 0; long tv = 0;
                for (int j = 0; j < candles.size(); j++) {
                    double d = Math.abs(candles.get(j).getLow().subtract(c.getLow())
                            .divide(c.getLow(), MathContext.DECIMAL32).doubleValue());
                    if (d <= tol) { t++; s += 1.0/(j+1); tv += candles.get(j).getVolume(); }
                }
                if (t >= 2) {
                    double vw = totalVol > 0 ? (double) tv / totalVol : 0;
                    levels.add(new KeyLevel(c.getLow(), t, s, vw, vw >= INSTITUTIONAL_VOL_THRESHOLD, true, false));
                }
            }
        }
        levels.sort((a,b) -> { int v = Double.compare(b.volumeWeight(),a.volumeWeight()); return v!=0?v:Double.compare(b.strength(),a.strength()); });
        return levels.subList(0, Math.min(5, levels.size()));
    }

    // ── Resistance levels ──────────────────────────────────────────────────────

    private List<KeyLevel> findResistanceLevels(List<Candle> candles, long totalVol) {
        List<KeyLevel> levels = new ArrayList<>();
        double tol = 0.003;
        for (int i = 2; i < candles.size() - 2; i++) {
            Candle c = candles.get(i);
            if (c.getHigh().compareTo(candles.get(i-1).getHigh()) > 0
                    && c.getHigh().compareTo(candles.get(i+1).getHigh()) > 0) {
                int t = 0; double s = 0; long tv = 0;
                for (int j = 0; j < candles.size(); j++) {
                    double d = Math.abs(candles.get(j).getHigh().subtract(c.getHigh())
                            .divide(c.getHigh(), MathContext.DECIMAL32).doubleValue());
                    if (d <= tol) { t++; s += 1.0/(j+1); tv += candles.get(j).getVolume(); }
                }
                if (t >= 2) {
                    double vw = totalVol > 0 ? (double) tv / totalVol : 0;
                    levels.add(new KeyLevel(c.getHigh(), t, s, vw, vw >= INSTITUTIONAL_VOL_THRESHOLD, false, true));
                }
            }
        }
        levels.sort((a,b) -> { int v = Double.compare(b.volumeWeight(),a.volumeWeight()); return v!=0?v:Double.compare(b.strength(),a.strength()); });
        return levels.subList(0, Math.min(5, levels.size()));
    }

    // ── Value Area — Volume Distribution + Recency Weighting + Gaussian TPO ───

    private ValueArea calculateValueArea(List<Candle> candles) {
        if (candles.isEmpty()) return null;

        TreeMap<Long, Long> volProfile = new TreeMap<>();
        long totalVol = 0;

        // UPGRADE 1+2: Distribute volume across H-L range with recency weighting
        for (int i = 0; i < candles.size(); i++) {
            Candle c       = candles.get(i);
            double high    = c.getHigh().doubleValue();
            double low     = c.getLow().doubleValue();
            double weight  = 1.0 - ((double) i / candles.size() * 0.5); // recency
            long   wVol    = Math.round(c.getVolume() * weight);

            long startBucket = Math.round(low  / TICK_SIZE);
            long endBucket   = Math.round(high / TICK_SIZE);
            long numBuckets  = (endBucket - startBucket) + 1;

            if (numBuckets > 0) {
                long vpb = Math.max(1L, wVol / numBuckets);
                for (long b = startBucket; b <= endBucket; b++)
                    volProfile.merge(b, vpb, Long::sum);
            }
            totalVol += wVol;
        }

        if (totalVol == 0) return null;

        // UPGRADE 5: Fixed POC (5-paise grid)
        long pocBucket = 0, maxVol = 0;
        for (Map.Entry<Long, Long> e : volProfile.entrySet())
            if (e.getValue() > maxVol) { maxVol = e.getValue(); pocBucket = e.getKey(); }

        BigDecimal poc = BigDecimal.valueOf(pocBucket * TICK_SIZE).setScale(2, RoundingMode.HALF_UP);

        // Gaussian TPO expansion from POC → 70% value area
        List<Long> keys   = new ArrayList<>(volProfile.keySet());
        int  pocIdx       = keys.indexOf(pocBucket);
        int  lo = pocIdx, hi = pocIdx;
        long enclosed     = maxVol;
        double target     = totalVol * 0.70;

        while (enclosed < target && (lo > 0 || hi < keys.size() - 1)) {
            long below = (lo > 0) ? volProfile.getOrDefault(keys.get(lo-1), 0L) : -1L;
            long above = (hi < keys.size()-1) ? volProfile.getOrDefault(keys.get(hi+1), 0L) : -1L;
            if (above >= below && hi < keys.size()-1) { hi++; enclosed += above; }
            else if (lo > 0) { lo--; enclosed += Math.max(0L, below); }
            else break;
        }

        BigDecimal vah = BigDecimal.valueOf(keys.get(hi) * TICK_SIZE).setScale(2, RoundingMode.HALF_UP);
        BigDecimal val = BigDecimal.valueOf(keys.get(lo) * TICK_SIZE).setScale(2, RoundingMode.HALF_UP);
        return new ValueArea(vah, val, poc, totalVol);
    }

    // ── Initial Balance ────────────────────────────────────────────────────────

    private InitialBalance calcInitialBalance(List<Candle> c5m) {
        InitialBalance empty = new InitialBalance(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false);
        if (c5m == null || c5m.isEmpty()) return empty;
        final int N = 12;
        boolean complete = c5m.size() >= N;
        List<Candle> ib = complete ? c5m.subList(c5m.size()-N, c5m.size()) : c5m;
        BigDecimal h = ib.stream().map(Candle::getHigh).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal l = ib.stream().map(Candle::getLow).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal m = h.add(l).divide(BigDecimal.valueOf(2), MathContext.DECIMAL32);
        return new InitialBalance(h, l, m, complete);
    }

    private BigDecimal calcVwap(List<Candle> c) {
        BigDecimal pv = BigDecimal.ZERO, vs = BigDecimal.ZERO;
        for (Candle x : c) {
            BigDecimal typ = x.getHigh().add(x.getLow()).add(x.getClose()).divide(BigDecimal.valueOf(3), MathContext.DECIMAL32);
            BigDecimal vol = BigDecimal.valueOf(x.getVolume());
            pv = pv.add(typ.multiply(vol));
            vs = vs.add(vol);
        }
        return vs.compareTo(BigDecimal.ZERO)==0 ? BigDecimal.ZERO : pv.divide(vs, MathContext.DECIMAL32);
    }

    public KeyLevelResult getKeyLevels(String symbol) {
        return cache.getOrDefault(symbol, new KeyLevelResult(
                symbol, List.of(), List.of(), BigDecimal.ZERO, BigDecimal.ZERO, null,
                new InitialBalance(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false)));
    }
}