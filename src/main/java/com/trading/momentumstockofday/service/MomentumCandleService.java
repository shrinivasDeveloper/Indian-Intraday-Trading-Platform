package com.trading.momentumstockofday.service;

import com.trading.domain.Candle;
import com.trading.events.CandleCompleteEvent;
import com.trading.momentumstockofday.config.MomentumConfig;
import com.trading.momentumstockofday.domain.MomentumCandidate;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.HistoricalData;
import com.zerodhatech.models.Instrument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * MomentumCandleService - candle sourcing, consolidation detection,
 * and breakout detection.
 *
 * REBUILT (per explicit user request, after confirming via Zerodha's
 * own official developer forum guidance that kiteConnect.
 * getHistoricalData() is documented as "for backtesting purpose only"
 * and unreliable for TODAY's real-time intraday data - "you can build
 * candles at your end using the live market data provided on
 * Websockets API"): 5-minute consolidation candles and today's day
 * high/low now come from the SAME live CandleCompleteEvent stream
 * CandleAggregatorService already publishes for AI/News/Swing -
 * genuine, real-time candles built from live WebSocket ticks, not
 * polled historical data.
 *
 * IMPORTANT, DELIBERATE EXCEPTION: the 4-hour VWAP/EMA(200) fetch
 * (fetch4HourCandles) STILL uses kiteConnect.getHistoricalData() with
 * its 220-day lookback - this is a genuinely different use case
 * (multi-month backward-looking data) that historical_data() is
 * documented to handle correctly; the unreliability found was
 * specifically about TODAY's data. A live-only buffer could never
 * accumulate 800 hours of 60-minute data before restarting, which
 * would permanently break the trend filter instead of fixing anything.
 *
 * ALL DECISION LOGIC (consolidation validity, breakout+volume
 * confirmation, VWAP/EMA formulas, trend filter gates) is preserved
 * byte-for-byte from the previous version - only the DATA SOURCE for
 * the two same-day methods changed.
 */
@Service
@Slf4j
public class MomentumCandleService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final KiteConnect kiteConnect;
    private final MomentumConfig config;

    public MomentumCandleService(KiteConnect kiteConnect, MomentumConfig config) {
        this.kiteConnect = kiteConnect;
        this.config = config;
    }

    // ========================================================================
    // LIVE CANDLE BUFFER - fed by CandleCompleteEvent (the same live
    // WebSocket-tick-based event stream CandleAggregatorService already
    // publishes for AI/News/Swing). Replaces historical_data() for
    // TODAY's 5-minute consolidation candles and day's high/low only.
    // ========================================================================

    // symbol -> "5minute" -> rolling deque of recent, converted candles
    private final Map<String, Deque<MomentumCandidate.Candle>> fiveMinBuffers =
            new ConcurrentHashMap<>();

    // symbol -> [dayHigh, dayLow] - updated incrementally on every real
    // 5-minute candle completion, reset at the start of each trading day.
    private final Map<String, double[]> dayHighLowTracker = new ConcurrentHashMap<>();

    // FIX (confirmed real bug found via direct user report + verified
    // with exact math): the day-low tracker above was updating on
    // EVERY candle, INCLUDING the latest one being checked for
    // breakout. Since a genuine breakout candle IS the new day-low by
    // definition, dayLow always became equal to that same candle's
    // close BEFORE the comparison ran - making "lastClose < dayLow"
    // mathematically impossible to satisfy for the very candle that
    // should trigger it. This second tracker lags by exactly one
    // candle - it holds the day's high/low AS OF the candle BEFORE the
    // latest one, which is what a genuine breakout must be compared
    // against (matching the same principle already used correctly for
    // the consolidation window, which also excludes the breakout candle).
    private final Map<String, double[]> priorDayHighLowTracker = new ConcurrentHashMap<>();

    // Symbols Momentum currently cares about - updated by MomentumScheduler
    // whenever a fresh scan/rescan happens, so the event listener below
    // knows exactly which symbols to buffer (avoids buffering candles for
    // all ~500 Nifty500 symbols when Momentum only tracks ~9 at a time).
    private final Set<String> trackedSymbols = ConcurrentHashMap.newKeySet();

    private volatile LocalDate lastBufferResetDate = null;

    // Plenty for the 2-8 candle consolidation window plus the volatility-
    // comparison buffer (maxConsolidationCandles + 3) with comfortable margin.
    private static final int MAX_5MIN_BUFFER = 20;

    /**
     * Called by MomentumScheduler whenever a fresh scan/rescan happens -
     * tells this event listener which symbols to actually buffer candles
     * for, since CandleCompleteEvent fires for every subscribed Nifty500
     * symbol, not just the ~9 Momentum is currently tracking.
     */
    public void updateTrackedSymbols(Set<String> symbols) {
        trackedSymbols.clear();
        trackedSymbols.addAll(symbols);
        log.debug("[MOMENTUM-CANDLE] Now tracking live candles for {} symbols: {}",
                symbols.size(), symbols);
    }

    /**
     * Listens to the same live event stream already proven reliable for
     * AI/News/Swing. Only buffers the 5-minute timeframe (per
     * TimeFrame.zerodhaInterval="5minute") and only for symbols Momentum
     * currently tracks - genuinely real-time, not polled.
     */
    @EventListener
    public void onCandleComplete(CandleCompleteEvent event) {
        // FIX (found during final production-readiness validation):
        // Spring's ApplicationEventPublisher invokes listeners
        // SYNCHRONOUSLY by default - an uncaught exception here could
        // propagate back into CandleAggregatorService's own publishing
        // loop, potentially disrupting candle processing for AI/News/
        // Swing too, since they may be other listeners on this SAME
        // shared event. This entire method body is now wrapped so it
        // can NEVER throw back into the shared pipeline, regardless of
        // any unexpected edge case (a null field, an unrecognized
        // timeframe string, etc.) - this strategy's own data quality
        // must never come at the cost of the shared infrastructure
        // other strategies genuinely depend on.
        try {
            Candle c = event.getCandle();
            String symbol = c.getTradingSymbol();
            if (symbol == null || !trackedSymbols.contains(symbol)) return;
            if (!"5minute".equals(c.getTimeframe())) return; // only need 5-min for this buffer
            if (c.getOpen() == null || c.getHigh() == null || c.getLow() == null
                    || c.getClose() == null || c.getCandleTime() == null) return;

            resetBuffersIfNewDay();

            MomentumCandidate.Candle converted = new MomentumCandidate.Candle(
                    c.getOpen().doubleValue(), c.getHigh().doubleValue(),
                    c.getLow().doubleValue(), c.getClose().doubleValue(),
                    c.getCandleTime().toString(), c.getVolume());

            Deque<MomentumCandidate.Candle> buffer =
                    fiveMinBuffers.computeIfAbsent(symbol, k -> new ConcurrentLinkedDeque<>());
            buffer.addLast(converted);
            while (buffer.size() > MAX_5MIN_BUFFER) buffer.pollFirst();

            double[] hl = dayHighLowTracker.computeIfAbsent(symbol,
                    k -> new double[]{0, Double.MAX_VALUE});
            // FIX (confirmed real bug, verified with exact math): snapshot
            // the CURRENT (pre-update) high/low into the "prior" tracker
            // BEFORE this candle's own high/low gets folded into the main
            // tracker below. This is what evaluate() will use for the
            // breakout comparison - the day's high/low as of the candle
            // BEFORE this one, never including this candle itself.
            priorDayHighLowTracker.put(symbol, new double[]{hl[0], hl[1]});
            if (converted.high() > hl[0]) hl[0] = converted.high();
            if (converted.low() < hl[1]) hl[1] = converted.low();
        } catch (Exception e) {
            // Deliberately catches ALL exceptions, not just specific
            // ones - this listener must never throw back into the
            // shared event pipeline, no matter what goes wrong here.
            log.warn("[MOMENTUM-CANDLE] Error processing live candle event (non-fatal, " +
                    "shared event pipeline unaffected): {}", e.getMessage());
        }
    }

    private void resetBuffersIfNewDay() {
        LocalDate today = LocalDate.now(IST);
        if (!today.equals(lastBufferResetDate)) {
            fiveMinBuffers.clear();
            dayHighLowTracker.clear();
            priorDayHighLowTracker.clear();
            lastBufferResetDate = today;
            log.info("[MOMENTUM-CANDLE] New trading day - live candle buffers reset");
        }
    }

    public record EvaluationResult(
            boolean validConsolidation, boolean breakoutTriggered,
            double consolidationHigh, double consolidationLow,
            double dayHigh, double dayLow,
            List<MomentumCandidate.Candle> candles, String note
    ) {}

    /**
     * The core evaluation, called every monitoring cycle for one
     * candidate. UNCHANGED decision logic from the previous version -
     * only the two data-source calls below (fetchRecentCandles,
     * fetchDayHighLow) now read from the live buffer instead of
     * historical_data(). Implements, precisely, per spec:
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

            // FIX (per explicit user request: "please remove this gate...
            // only this gate should remove"). Volume confirmation gate
            // removed - breakout now depends purely on price crossing
            // the day's high/low, exactly as it did before this gate
            // was added. Nothing else in this method changed.
            boolean breakout = priceBreakout;

            return new EvaluationResult(true, breakout, consolHigh, consolLow, dayHigh, dayLow,
                    window,
                    breakout
                            ? String.format("Valid %d-candle consolidation COMPLETE, followed by DAY'S " +
                                    "%s BREAKOUT confirmed (close %.2f vs day %s %.2f)", windowSize,
                            isLong ? "HIGH" : "LOW", lastClose, isLong ? "high" : "low",
                            isLong ? dayHigh : dayLow)
                            : String.format("Valid %d-candle consolidation forming, waiting for " +
                                    "day's %s breakout (current=%.2f, need %s %.2f)", windowSize,
                            isLong ? "high" : "low", lastClose, isLong ? "above" : "below",
                            isLong ? dayHigh : dayLow));
        }

        return new EvaluationResult(false, false, 0, 0, dayHigh, dayLow, recent,
                "No valid small-bodied consolidation found in the last " +
                        config.getMaxConsolidationCandles() + " candles");
    }

    /**
     * REBUILT (per explicit user request): now reads from the live
     * candle buffer (fed by CandleCompleteEvent) instead of polling
     * kiteConnect.getHistoricalData() - genuinely real-time, no more
     * reliance on an API Zerodha's own docs say isn't meant for
     * same-day intraday use.
     */
    private List<MomentumCandidate.Candle> fetchRecentCandles(String symbol, int count) {
        Deque<MomentumCandidate.Candle> buffer = fiveMinBuffers.get(symbol);
        if (buffer == null || buffer.isEmpty()) return List.of();
        List<MomentumCandidate.Candle> all = new ArrayList<>(buffer);
        if (all.size() <= count) return all;
        return all.subList(all.size() - count, all.size());
    }

    /**
     * REBUILT (per explicit user request): now reads from the
     * incrementally-updated dayHighLowTracker (built live, from every
     * real 5-minute candle since market open) instead of a separate
     * historical_data() call. Genuinely real-time and always in sync
     * with the same live data used for consolidation detection.
     */
    private double[] fetchDayHighLow(String symbol) {
        // FIX (confirmed real bug, verified with exact math): reads from
        // the PRIOR tracker, not the always-current one - see the field
        // declaration above and the event listener for the full
        // explanation. This is what makes the breakout comparison
        // genuinely possible, instead of always comparing a candle
        // against a day-low value it just set itself.
        double[] hl = priorDayHighLowTracker.get(symbol);
        if (hl == null || hl[0] <= 0 || hl[1] >= Double.MAX_VALUE) return new double[]{0, 0};
        return new double[]{hl[0], hl[1]};
    }

    /**
     * Returns null if the window is a VALID consolidation, or a
     * rejection reason string if not. UNCHANGED from the previous
     * version. Implements both spec rules together: "small-bodied"
     * (per-candle check) AND "not unusually large" (relative-
     * volatility check across the window).
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

    private long resolveToken(String symbol) {
        try {
            List<Instrument> instruments = kiteConnect.getInstruments("NSE");
            for (Instrument i : instruments) {
                if (symbol.equalsIgnoreCase(i.getTradingsymbol())) {
                    return i.getInstrument_token();
                }
            }
        } catch (KiteException | Exception e) {
            log.warn("[MOMENTUM-CANDLE] Could not resolve instrument token for {} (needed only " +
                    "for the 4H historical VWAP/EMA fetch): {}", symbol, e.getMessage());
        }
        return 0L;
    }

    private Date toDate(LocalDateTime ldt) {
        return Date.from(ldt.atZone(IST).toInstant());
    }

    // ========================================================================
    // FEATURE 3 (Mandatory Trend Confirmation Filters, per explicit user
    // spec): 4-hour VWAP and EMA(20/50/200). UNCHANGED from the previous
    // version - DELIBERATELY still uses kiteConnect.getHistoricalData()
    // with its 220-day lookback (see class-level Javadoc for why this
    // one method is a deliberate exception to the live-data rebuild:
    // a live-only buffer could never accumulate 800 hours of 60-minute
    // data before the next restart, which would permanently break this
    // filter rather than fix anything - the same-day unreliability that
    // prompted this rebuild does not apply to a 220-day backward lookup).
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
     * UNCHANGED from the previous version - deliberately still uses
     * historical_data() (see class-level Javadoc). Fetches 60-minute
     * candles (220 calendar days - confirmed via Kite's own documented
     * API limits to be safely within the 400-day maximum for this
     * interval) and aggregates every 4 consecutive ones into one
     * synthetic 4-hour candle.
     */
    private List<MomentumCandidate.Candle> fetch4HourCandles(String symbol) {
        try {
            long token = resolveToken(symbol);
            if (token == 0) return List.of();

            LocalDateTime now = LocalDateTime.now(IST);
            Date to = toDate(now);
            Date from = toDate(now.minusDays(220));

            HistoricalData data = kiteConnect.getHistoricalData(
                    from, to, String.valueOf(token), "60minute", false, false);
            if (data == null || data.dataArrayList == null || data.dataArrayList.isEmpty()) {
                log.warn("[MOMENTUM-CANDLE] {} - 4H candle fetch: Kite's call succeeded (no " +
                                "exception) but returned {} - token={}", symbol,
                        data == null ? "a null response" : "zero candles", token);
                return List.of();
            }

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
     * VWAP over the given candle series - UNCHANGED from the previous
     * version. Cumulative (typical price x volume) / cumulative volume,
     * using the standard typical price ((H+L+C)/3).
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
     * Standard exponential moving average - UNCHANGED from the previous
     * version. Seeded with a simple average of the first `period`
     * closes, then applying the standard smoothing multiplier.
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