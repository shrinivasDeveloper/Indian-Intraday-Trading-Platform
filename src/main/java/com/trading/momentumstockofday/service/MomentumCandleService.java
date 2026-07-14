package com.trading.momentumstockofday.service;

import com.trading.momentumstockofday.config.MomentumConfig;
import com.trading.momentumstockofday.domain.MomentumCandidate;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.HistoricalData;
import com.zerodhatech.models.Instrument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MomentumCandleService - independent candle fetching, consolidation
 * detection, and breakout detection.
 *
 * INDEPENDENCE (per explicit requirement): does NOT use any existing
 * strategy's CandleAggregatorService, WebSocket tick pipeline, or any
 * other strategy-specific candle infrastructure. Fetches candles
 * directly via KiteConnect.getHistoricalData() (verified via bytecode:
 * a genuinely neutral, shared broker API, not strategy logic) - a
 * self-contained, on-demand pull for just the (at most) 9 tracked
 * stocks, completely separate from how AI/News/Swing build their own
 * candles from live ticks.
 */
@Service
@Slf4j
public class MomentumCandleService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final KiteConnect kiteConnect;
    private final MomentumConfig config;

    // Cache of instrument_token per symbol, resolved once per day -
    // getHistoricalData needs the numeric token, not the trading symbol.
    private final Map<String, Long> tokenCache = new ConcurrentHashMap<>();

    // FIX (added during thorough production-readiness validation of
    // the resolveToken fix below): tracks the last failure time per
    // symbol, so a genuinely persistent failure (not just a transient
    // one) doesn't retry the full ~9,946-instrument fetch every single
    // 30-second monitoring cycle, forever. Guarantees eventual recovery
    // from transient failures while avoiding excessive API load if a
    // symbol truly, repeatedly cannot be resolved.
    private final Map<String, Long> lastFailureTime = new ConcurrentHashMap<>();
    private static final long RETRY_BACKOFF_MS = 5 * 60 * 1000; // 5 minutes

    public MomentumCandleService(KiteConnect kiteConnect, MomentumConfig config) {
        this.kiteConnect = kiteConnect;
        this.config = config;
    }

    public record EvaluationResult(
            boolean validConsolidation, boolean breakoutTriggered,
            double consolidationHigh, double consolidationLow,
            double dayHigh, double dayLow,
            List<MomentumCandidate.Candle> candles, String note
    ) {}

    /**
     * The core evaluation, called every monitoring cycle for one
     * candidate. Implements, precisely, per spec:
     *   - "Wait for the stock to consolidate for 2 to 4 candles (max)"
     *   - "consolidation candles should be small-bodied... not large
     *      or highly volatile"
     *   - "Avoid taking trades if any consolidation candle is
     *      unusually large"
     *   - MANDATORY BREAKOUT CONFIRMATION (per explicit user spec):
     *     "Long Trade: Enter only when the stock breaks above the
     *     day's high. Short Trade: Enter only when the stock breaks
     *     below the day's low." The consolidation pattern remains a
     *     required PRECONDITION (must still exist and be valid), but
     *     the actual breakout TRIGGER level is now the day's high/low,
     *     not the tight consolidation window's own high/low.
     */
    public EvaluationResult evaluate(MomentumCandidate candidate) {
        List<MomentumCandidate.Candle> recent = fetchRecentCandles(candidate.getSymbol(),
                config.getMaxConsolidationCandles() + 3); // small buffer for volatility comparison
        if (recent.size() < config.getMinConsolidationCandles() + 1) { // +1 for the separate breakout candle
            return new EvaluationResult(false, false, 0, 0, 0, 0, recent,
                    "Not enough candle history yet (" + recent.size() + " available)");
        }

        // FIX (Mandatory Breakout Confirmation, per explicit user spec):
        // fetch the REAL day's high/low explicitly from market open
        // (9:15 AM) to now - not derived from the small consolidation
        // window, and not assumed from a fixed lookback window that
        // could miss part of the session on a late-day check.
        double[] dayHighLow = fetchDayHighLow(candidate.getSymbol());
        double dayHigh = dayHighLow[0];
        double dayLow = dayHighLow[1];
        if (dayHigh <= 0 || dayLow <= 0) {
            return new EvaluationResult(false, false, 0, 0, 0, 0, recent,
                    "Could not determine today's real high/low yet - skipping this cycle");
        }

        // Try consolidation windows from smallest to largest, per spec's
        // explicit "2 to 4 candles" range.
        // FIX (per explicit user request: "need to trade after day high
        // or low breaks and WITH CONSOLIDATION COMPLETE"): the
        // consolidation window is now the candles BEFORE the latest one
        // - a genuinely COMPLETED pattern - and the breakout candle
        // (the LATEST one) is checked SEPARATELY, no longer required to
        // itself be small-bodied. This matches standard breakout logic:
        // consolidation forms and completes first, THEN a subsequent
        // candle breaks beyond it - rather than requiring the breakout
        // candle to simultaneously satisfy the tight consolidation body
        // limit itself (which previously only allowed quiet, barely-
        // there breakouts to ever qualify).
        MomentumCandidate.Candle breakoutCandle = recent.get(recent.size() - 1);

        for (int windowSize = config.getMinConsolidationCandles();
             windowSize <= config.getMaxConsolidationCandles()
                     && windowSize + 1 <= recent.size(); // +1 reserves room for the breakout candle
             windowSize++) {

            // The consolidation window is the windowSize candles
            // immediately BEFORE the breakout candle - NOT including it.
            List<MomentumCandidate.Candle> window = recent.subList(
                    recent.size() - windowSize - 1, recent.size() - 1);

            String rejectReason = checkConsolidationValidity(window);
            if (rejectReason != null) continue; // try a different window size

            double consolHigh = window.stream().mapToDouble(MomentumCandidate.Candle::high).max().orElse(0);
            double consolLow = window.stream().mapToDouble(MomentumCandidate.Candle::low).min().orElse(0);

            double lastClose = breakoutCandle.close();
            boolean isLong = "LONG".equals(candidate.getDirection());
            // Breakout confirmed against the DAY'S high/low, checked on
            // the SEPARATE breakout candle (not part of the consolidation
            // window itself).
            boolean priceBreakout = isLong ? lastClose > dayHigh : lastClose < dayLow;

            // FEATURE (Volume Profile Confirmation, per explicit user
            // spec's pipeline step 4): the breakout candle's volume must
            // exceed 1.2x the window's average volume - confirms
            // genuine market interest behind the move, not a thin,
            // low-conviction breakout. Deliberately a MODERATE threshold
            // (not an aggressive 2x+ requirement) per explicit user
            // instruction to avoid losing valid opportunities - this
            // filters obviously weak breakouts without being overly
            // restrictive. Average is computed from the CONSOLIDATION
            // window only (prior, calmer candles) - a cleaner, more
            // meaningful baseline than including the breakout candle's
            // own volume in its own comparison average.
            double avgVolume = window.stream().mapToLong(MomentumCandidate.Candle::volume)
                    .average().orElse(0);
            long breakoutCandleVolume = breakoutCandle.volume();
            boolean volumeConfirmed = avgVolume > 0 && breakoutCandleVolume >= avgVolume * 1.2;
            boolean breakout = priceBreakout && volumeConfirmed;

            String volumeNote = priceBreakout
                    ? (volumeConfirmed
                    ? String.format(" | Volume confirmed (%d vs %.0f avg, %.1fx)",
                    breakoutCandleVolume, avgVolume,
                    avgVolume > 0 ? breakoutCandleVolume / avgVolume : 0)
                    : String.format(" | Volume NOT confirmed (%d vs %.0f avg, need 1.2x) - " +
                            "price broke out but volume too thin, waiting for real conviction",
                    breakoutCandleVolume, avgVolume))
                    : "";

            return new EvaluationResult(true, breakout, consolHigh, consolLow, dayHigh, dayLow,
                    window,
                    breakout
                            ? String.format("Valid %d-candle consolidation COMPLETE, followed by DAY'S " +
                                    "%s BREAKOUT confirmed (close %.2f vs day %s %.2f)%s", windowSize,
                            isLong ? "HIGH" : "LOW", lastClose, isLong ? "high" : "low",
                            isLong ? dayHigh : dayLow, volumeNote)
                            : String.format("Valid %d-candle consolidation forming, waiting for " +
                                    "day's %s breakout (current=%.2f, need %s %.2f)%s", windowSize,
                            isLong ? "high" : "low", lastClose, isLong ? "above" : "below",
                            isLong ? dayHigh : dayLow, volumeNote));
        }

        return new EvaluationResult(false, false, 0, 0, dayHigh, dayLow, recent,
                "No valid small-bodied consolidation found in the last " +
                        config.getMaxConsolidationCandles() + " candles");
    }

    /**
     * Fetches today's real high/low explicitly from market open (9:15
     * AM IST) to now - a dedicated fetch, not derived from the same
     * short window used for consolidation detection, and not assumed
     * from a fixed lookback that could miss part of the session on a
     * late-day check.
     */
    private double[] fetchDayHighLow(String symbol) {
        try {
            long token = resolveToken(symbol);
            if (token == 0) return new double[]{0, 0};

            LocalDateTime now = LocalDateTime.now(IST);
            LocalDateTime marketOpen = now.toLocalDate().atTime(9, 15);
            if (now.isBefore(marketOpen)) return new double[]{0, 0};

            HistoricalData data = kiteConnect.getHistoricalData(
                    toDate(marketOpen), toDate(now), String.valueOf(token),
                    config.getCandleInterval(), false, false);
            if (data == null || data.dataArrayList == null || data.dataArrayList.isEmpty()) {
                return new double[]{0, 0};
            }

            double high = 0, low = Double.MAX_VALUE;
            for (Object obj : data.dataArrayList) {
                HistoricalData d = (HistoricalData) obj;
                if (d.high > high) high = d.high;
                if (d.low < low) low = d.low;
            }
            return (high > 0 && low < Double.MAX_VALUE) ? new double[]{high, low} : new double[]{0, 0};
        } catch (KiteException | Exception e) {
            // FIX (per direct user report: "can't see momentum logs in
            // Railway, no errors, works locally"). Upgraded from DEBUG
            // to WARN - if Railway's deployed environment has ANY
            // environment variable overriding application.yml's
            // "com.trading: DEBUG" setting (common in production
            // deployments, outside this codebase's control), a DEBUG
            // message would be silently suppressed there while still
            // appearing locally. WARN is visible regardless.
            log.warn("[MOMENTUM-CANDLE] Failed to fetch today's high/low for {} (non-fatal, " +
                    "will retry next cycle): {}", symbol, e.getMessage());
            return new double[]{0, 0};
        }
    }

    /**
     * Returns null if the window is a VALID consolidation, or a
     * rejection reason string if not. Implements both spec rules
     * together: "small-bodied" (per-candle check) AND "not unusually
     * large" (relative-volatility check across the window).
     */
    private String checkConsolidationValidity(List<MomentumCandidate.Candle> window) {
        double avgRange = window.stream().mapToDouble(MomentumCandidate.Candle::range)
                .average().orElse(0);
        if (avgRange <= 0) return "zero average range - no real data";

        for (MomentumCandidate.Candle c : window) {
            double bodyPct = c.close() > 0 ? c.body() / c.close() : 0;
            if (bodyPct > config.getMaxCandleBodyPct()) {
                return String.format("candle body %.3f%% exceeds max %.3f%%",
                        bodyPct * 100, config.getMaxCandleBodyPct() * 100);
            }
            // Per spec: "avoid taking trades if any of the consolidation
            // candles are unusually large" - a candle whose own range is
            // a large multiple of the window's average range indicates
            // one volatile outlier candle, even if its body happened to
            // be small (e.g. a long-wicked doji) - reject the whole
            // window, don't just skip that one candle.
            if (c.range() > avgRange * config.getVolatilityRejectMultiple()) {
                return String.format("candle range %.2f is %.1fx the window average (%.2f) - " +
                                "unusually volatile, not a healthy consolidation", c.range(),
                        c.range() / avgRange, avgRange);
            }
        }
        return null; // valid
    }

    /**
     * Fetches the most recent N candles directly from Kite's historical
     * data API - genuinely independent, on-demand, per-symbol.
     */
    private List<MomentumCandidate.Candle> fetchRecentCandles(String symbol, int count) {
        try {
            long token = resolveToken(symbol);
            if (token == 0) return List.of();

            LocalDateTime now = LocalDateTime.now(IST);
            Date to = toDate(now);
            Date from = toDate(now.minusHours(6)); // generous window, trimmed below

            HistoricalData data = kiteConnect.getHistoricalData(
                    from, to, String.valueOf(token), config.getCandleInterval(), false, false);
            if (data == null || data.dataArrayList == null) return List.of();

            List<MomentumCandidate.Candle> all = new ArrayList<>();
            for (Object obj : data.dataArrayList) {
                HistoricalData d = (HistoricalData) obj;
                all.add(new MomentumCandidate.Candle(d.open, d.high, d.low, d.close, d.timeStamp,
                        d.volume));
            }
            if (all.size() <= count) return all;
            return all.subList(all.size() - count, all.size());
        } catch (KiteException | Exception e) {
            log.warn("[MOMENTUM-CANDLE] Failed to fetch candles for {} (non-fatal, will retry " +
                    "next cycle): {}", symbol, e.getMessage());
            return List.of();
        }
    }

    private long resolveToken(String symbol) {
        // FIX (found via direct user report: "Not enough candle history
        // yet (0 available)" persisting all day for every stock).
        // Confirmed real root cause: computeIfAbsent() permanently
        // cached the 0L failure sentinel on ANY exception or lookup
        // miss - since the map key is no longer "absent" once cached,
        // a symbol that failed to resolve even ONCE (e.g. a transient
        // network hiccup during the initial burst of 9 near-simultaneous
        // lookups right after 9:25 AM selection) would NEVER be retried
        // again for the rest of the day, permanently returning empty
        // candles. Now only caches genuine, successful resolutions - a
        // failed lookup gets a fresh retry on the very next evaluation
        // cycle instead of being stuck forever.
        Long cached = tokenCache.get(symbol);
        if (cached != null && cached > 0) return cached;

        // FIX (added during thorough production-readiness validation):
        // guards against a genuinely persistent failure retrying the
        // full ~9,946-instrument fetch every single 30-second cycle
        // forever. After a failure, waits RETRY_BACKOFF_MS before
        // trying again - still guarantees eventual recovery from a
        // transient issue, without excessive API load if a symbol
        // truly, repeatedly cannot be resolved.
        Long lastFailure = lastFailureTime.get(symbol);
        if (lastFailure != null && (System.currentTimeMillis() - lastFailure) < RETRY_BACKOFF_MS) {
            return 0L; // still within backoff window - skip this cycle's attempt
        }

        try {
            List<Instrument> instruments = kiteConnect.getInstruments("NSE");
            for (Instrument i : instruments) {
                if (symbol.equalsIgnoreCase(i.getTradingsymbol())) {
                    long token = i.getInstrument_token();
                    tokenCache.put(symbol, token); // only cache real successes
                    lastFailureTime.remove(symbol); // clear any prior backoff
                    return token;
                }
            }
            lastFailureTime.put(symbol, System.currentTimeMillis());
            log.warn("[MOMENTUM-CANDLE] {} not found in NSE instrument list - will retry " +
                    "in {} minutes rather than every cycle", symbol, RETRY_BACKOFF_MS / 60000);
        } catch (KiteException | Exception e) {
            lastFailureTime.put(symbol, System.currentTimeMillis());
            log.warn("[MOMENTUM-CANDLE] Could not resolve instrument token for {} - will retry " +
                            "in {} minutes rather than every cycle: {}",
                    symbol, RETRY_BACKOFF_MS / 60000, e.getMessage());
        }
        return 0L; // NOT cached - next call will genuinely retry
    }

    private Date toDate(LocalDateTime ldt) {
        return Date.from(ldt.atZone(IST).toInstant());
    }

    // ========================================================================
    // FEATURE 3 (Mandatory Trend Confirmation Filters, per explicit user
    // spec): 4-hour VWAP and EMA(20/50/200). KiteConnect has no native
    // 4-hour interval, so this fetches 60-minute candles and aggregates
    // every 4 consecutive ones into a synthetic 4-hour candle - the
    // standard way to build a higher timeframe from a lower one.
    // ========================================================================

    public record TrendFilterResult(boolean passed, String reason,
                                    double vwap4h, double ema20, double ema50, double ema200) {}

    /**
     * Checks BOTH mandatory trend filters together, per explicit spec:
     *   VWAP (4H): LONG needs price above 4H VWAP, SHORT needs price below.
     *   EMA (4H):  LONG needs 20>50>200, SHORT needs 20<50<200.
     * Rejects (passed=false) if EITHER condition fails - both are
     * mandatory, checked together as the final gate immediately before
     * trade execution, per spec: "applied after all existing
     * validations and immediately before trade execution."
     */
    public TrendFilterResult checkTrendFilters(String symbol, String direction, double currentPrice) {
        List<MomentumCandidate.Candle> fourHourCandles = fetch4HourCandles(symbol);
        // Need at least 200 candles for a genuine EMA(200) - per spec's
        // own mandatory requirement; a shorter series would produce a
        // misleading, not-yet-converged EMA(200) value.
        if (fourHourCandles.size() < 200) {
            return new TrendFilterResult(false, "Not enough 4H candle history for EMA(200) - " +
                    "have " + fourHourCandles.size() + ", need 200", 0, 0, 0, 0);
        }

        double vwap4h = computeVwap(fourHourCandles);
        double ema20 = computeEma(fourHourCandles, 20);
        double ema50 = computeEma(fourHourCandles, 50);
        double ema200 = computeEma(fourHourCandles, 200);

        boolean isLong = "LONG".equals(direction);

        boolean vwapOk = isLong ? currentPrice > vwap4h : currentPrice < vwap4h;
        if (!vwapOk) {
            return new TrendFilterResult(false, String.format(
                    "4H VWAP filter FAILED: price=%.2f %s required side of VWAP=%.2f (need %s)",
                    currentPrice, isLong ? "not above" : "not below", vwap4h,
                    isLong ? "price > VWAP" : "price < VWAP"),
                    vwap4h, ema20, ema50, ema200);
        }

        boolean emaOk = isLong ? (ema20 > ema50 && ema50 > ema200) : (ema20 < ema50 && ema50 < ema200);
        if (!emaOk) {
            return new TrendFilterResult(false, String.format(
                    "4H EMA trend filter FAILED: EMA20=%.2f EMA50=%.2f EMA200=%.2f - need %s",
                    ema20, ema50, ema200, isLong ? "20>50>200" : "20<50<200"),
                    vwap4h, ema20, ema50, ema200);
        }

        return new TrendFilterResult(true, String.format(
                "4H trend filters PASSED: VWAP=%.2f (price %s) | EMA20=%.2f EMA50=%.2f " +
                        "EMA200=%.2f (%s)", vwap4h, isLong ? "above" : "below", ema20, ema50, ema200,
                isLong ? "20>50>200" : "20<50<200"), vwap4h, ema20, ema50, ema200);
    }

    /**
     * Fetches 60-minute candles (220 calendar days - confirmed via
     * Kite's own documented API limits to be safely within the
     * 400-day maximum for this interval - producing enough real 4-hour
     * candles after aggregation to satisfy EMA(200)'s genuine
     * convergence requirement) and aggregates every 4 consecutive ones
     * into one synthetic 4-hour candle.
     */
    private List<MomentumCandidate.Candle> fetch4HourCandles(String symbol) {
        try {
            long token = resolveToken(symbol);
            if (token == 0) return List.of();

            LocalDateTime now = LocalDateTime.now(IST);
            Date to = toDate(now);
            // FIX (found via direct user question, confirmed with exact
            // math): 35 calendar days only produced ~39 real 4-hour
            // candles after aggregation - far short of the 200 required
            // for EMA(200) below, meaning this filter was unconditionally
            // rejecting every single trade, every day, regardless of
            // actual VWAP/EMA alignment. 220 calendar days (~157 trading
            // days) produces ~246 four-hour candles - comfortably above
            // 200, with margin for holidays/market closures. Zero change
            // to the EMA/VWAP formulas or the 200-candle requirement
            // itself - purely fixes the fetch window to actually supply
            // enough real history for that existing requirement to ever
            // be satisfiable.
            Date from = toDate(now.minusDays(220));

            HistoricalData data = kiteConnect.getHistoricalData(
                    from, to, String.valueOf(token), "60minute", false, false);
            if (data == null || data.dataArrayList == null) return List.of();

            List<MomentumCandidate.Candle> hourly = new ArrayList<>();
            for (Object obj : data.dataArrayList) {
                HistoricalData d = (HistoricalData) obj;
                hourly.add(new MomentumCandidate.Candle(d.open, d.high, d.low, d.close,
                        d.timeStamp, d.volume));
            }

            // Aggregate every 4 consecutive 60-minute candles into one
            // synthetic 4-hour candle: open=first, high=max, low=min,
            // close=last, volume=sum - the standard higher-timeframe
            // aggregation approach.
            List<MomentumCandidate.Candle> fourHour = new ArrayList<>();
            for (int i = 0; i + 4 <= hourly.size(); i += 4) {
                List<MomentumCandidate.Candle> group = hourly.subList(i, i + 4);
                double open = group.get(0).open();
                double close = group.get(group.size() - 1).close();
                double high = group.stream().mapToDouble(MomentumCandidate.Candle::high).max().orElse(0);
                double low = group.stream().mapToDouble(MomentumCandidate.Candle::low).min().orElse(0);
                long volume = group.stream().mapToLong(MomentumCandidate.Candle::volume).sum();
                fourHour.add(new MomentumCandidate.Candle(open, high, low, close,
                        group.get(0).timestamp(), volume));
            }
            return fourHour;
        } catch (KiteException | Exception e) {
            log.warn("[MOMENTUM-CANDLE] Failed to fetch 4H candles for {} (non-fatal, will " +
                    "retry next cycle): {}", symbol, e.getMessage());
            return List.of();
        }
    }

    /**
     * VWAP over the given candle series - cumulative (typical price x
     * volume) / cumulative volume, using the standard typical price
     * ((H+L+C)/3). Computed over the full fetched 4H series (not reset
     * daily like an intraday VWAP), matching how a "higher timeframe
     * VWAP" is conventionally used by traders on that timeframe.
     */
    private double computeVwap(List<MomentumCandidate.Candle> candles) {
        double cumPV = 0;
        long cumVol = 0;
        for (MomentumCandidate.Candle c : candles) {
            double typicalPrice = (c.high() + c.low() + c.close()) / 3.0;
            cumPV += typicalPrice * c.volume();
            cumVol += c.volume();
        }
        return cumVol > 0 ? cumPV / cumVol : 0;
    }

    /**
     * Standard exponential moving average over the candle series'
     * closes, seeded with a simple average of the first `period`
     * closes (the conventional EMA seeding approach), then applying
     * the standard smoothing multiplier (2/(period+1)) across the
     * remaining candles.
     */
    private double computeEma(List<MomentumCandidate.Candle> candles, int period) {
        if (candles.size() < period) return 0;
        double multiplier = 2.0 / (period + 1);
        double ema = candles.subList(0, period).stream()
                .mapToDouble(MomentumCandidate.Candle::close).average().orElse(0);
        for (int i = period; i < candles.size(); i++) {
            double close = candles.get(i).close();
            ema = (close - ema) * multiplier + ema;
        }
        return ema;
    }
}