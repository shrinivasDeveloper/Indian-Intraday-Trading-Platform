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
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
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
 * IMPORTANT, DELIBERATE EXCEPTION: the VWAP/EMA(200) trend-filter
 * fetch (fetch5MinuteHistoryCandles) STILL uses kiteConnect.
 * getHistoricalData() with a 10-day lookback - this is a genuinely
 * different use case (multi-day backward-looking data) that
 * historical_data() is documented to handle correctly; the
 * unreliability found was specifically about TODAY's data. A
 * live-only buffer (bounded to a small window for consolidation
 * purposes) could never hold the 200 candles' worth of history this
 * filter genuinely needs.
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

    private final com.trading.sectorheatmap.service.SectorHeatmapDataService sectorHeatmapDataService;

    public MomentumCandleService(KiteConnect kiteConnect, MomentumConfig config,
                                 com.trading.sectorheatmap.service.SectorHeatmapDataService sectorHeatmapDataService) {
        this.kiteConnect = kiteConnect;
        this.config = config;
        this.sectorHeatmapDataService = sectorHeatmapDataService;
    }

    /**
     * FIX (per explicit user request: "not 500 symbol all stocks please"
     * - a full, upfront bootstrap covering the ENTIRE Sector Heatmap
     * universe, ~751 stocks, not just the narrower ~500 Nifty500
     * subset AI itself covers). Matches AI's own proven bootstrap
     * pattern (fetch once at startup, throttled, with progress logging)
     * but is Momentum's own, completely independent implementation -
     * zero calls into AI's actual AiMarketDataService bean, which
     * remains unsafe to depend on (it's @ConditionalOnProperty and
     * would not exist at all if AI is ever disabled).
     *
     * Delayed 150 seconds (longer than AI's own 90-second delay) so
     * this genuinely runs AFTER both AI's and any other bootstrap
     * work, avoiding competing for Zerodha's API rate limits during
     * the busiest part of startup.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Async("tradingExecutor")
    public void bootstrapDailyCandles() {
        // FIX (confirmed real root cause from production logs): the
        // previous 150-second delay only waited past AI's own 90-second
        // STARTING delay - but AI's bootstrap then runs continuously for
        // ~2.5 more minutes after that, making ~1500 historical-data
        // calls the entire time. Momentum's retry window was landing
        // squarely in the MIDDLE of AI's still-actively-running
        // bootstrap, not after it finished - explaining why every retry
        // attempt failed despite the backoff logic itself working
        // correctly. Extended to 360 seconds (6 minutes) - genuinely
        // past AI's full bootstrap duration (90s delay + ~2.5min
        // runtime + safety margin), not just its starting point.
        try { Thread.sleep(360_000); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return;
        }

        Set<String> symbols = sectorHeatmapDataService.getAllTrackedSymbols();
        if (symbols.isEmpty()) {
            log.warn("[MOMENTUM-CANDLE] Daily candle bootstrap skipped - Sector Heatmap has " +
                    "no symbols loaded yet. The existing cache-on-first-use fallback in " +
                    "fetchDailyCandles() will still fetch on demand for any symbol actually " +
                    "selected as a candidate.");
            return;
        }

        // FIX (confirmed real bug found from production logs: "bootstrap
        // complete - 0/751 symbols cached"). The instrument token cache's
        // backoff (5 minutes) was longer than the ENTIRE bootstrap loop's
        // runtime (~3.75 minutes for 751 symbols at 300ms each) -
        // meaning a single early failure guaranteed the cache could
        // never recover within the same bootstrap run, wasting the
        // entire loop. This dedicated, upfront retry gives the
        // instrument cache a genuine, focused chance to populate BEFORE
        // committing the whole 751-symbol loop to it - up to 3
        // attempts, 15 seconds apart (enough time for a temporary
        // rate-limit to clear, without materially delaying the
        // bootstrap's own start).
        Map<String, Long> tokenCache = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            tokenCache = getOrBuildInstrumentTokenCache();
            if (!tokenCache.isEmpty()) break;
            if (attempt < 3) {
                log.warn("[MOMENTUM-CANDLE] Instrument cache still empty (attempt {}/3) - " +
                        "waiting 15s before the bootstrap's dedicated retry", attempt);
                try { Thread.sleep(15_000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        if (tokenCache == null || tokenCache.isEmpty()) {
            log.warn("[MOMENTUM-CANDLE] Daily candle bootstrap could not populate the " +
                    "instrument cache after 3 dedicated attempts - skipping this bootstrap run " +
                    "entirely. The existing cache-on-first-use fallback in fetchDailyCandles() " +
                    "will still fetch on demand for any symbol actually selected as a candidate " +
                    "later, once the rate limit has genuinely cleared.");
            return;
        }

        log.info("[MOMENTUM-CANDLE] Daily candle bootstrap starting: {} symbols (full Sector " +
                "Heatmap universe, ~1 year each)", symbols.size());

        int loaded = 0;
        LocalDate today = LocalDate.now(IST);
        for (String symbol : symbols) {
            try {
                List<MomentumCandidate.Candle> daily = fetchDailyCandlesFromApi(symbol);
                if (!daily.isEmpty()) {
                    dailyCandleCache.put(symbol, daily);
                    dailyCandleCacheDate.put(symbol, today);
                    loaded++;
                }
                if (loaded % 50 == 0 && loaded > 0) {
                    log.info("[MOMENTUM-CANDLE] Daily candle bootstrap progress: {}/{}",
                            loaded, symbols.size());
                }
                // Throttle to avoid Zerodha rate limits - same interval
                // AI's own bootstrap uses.
                Thread.sleep(300);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.debug("[MOMENTUM-CANDLE] Daily candle bootstrap failed for {}: {}",
                        symbol, e.getMessage());
            }
        }

        log.info("[MOMENTUM-CANDLE] Daily candle bootstrap complete - {}/{} symbols cached",
                loaded, symbols.size());
    }

    /**
     * FIX (per explicit user request: 30-min gate had no bootstrap,
     * unlike daily - meaning the first trade attempt for any symbol
     * each day risked a real, synchronous API call delay at the exact
     * moment of order entry). Mirrors the daily bootstrap's exact
     * proven structure (same throttle, same error handling, same
     * progress logging) - the only deliberate difference is timing:
     * scheduled to start at 660 seconds (11 minutes), genuinely AFTER
     * the daily bootstrap finishes (~360s start + ~225s runtime =
     * ~585s completion), not competing with it - avoiding the exact
     * rate-limit contention class of issue already found and fixed
     * once tonight for the daily bootstrap's own timing. By this point
     * the instrument token cache is already warm from the daily
     * bootstrap (shared via resolveToken()), so no dedicated retry
     * loop is needed here - a quick single check is sufficient.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Async("tradingExecutor")
    public void bootstrap30MinuteCandles() {
        try { Thread.sleep(900_000); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return;
        }

        Set<String> symbols = sectorHeatmapDataService.getAllTrackedSymbols();
        if (symbols.isEmpty()) {
            log.warn("[MOMENTUM-CANDLE] 30-min candle bootstrap skipped - Sector Heatmap has " +
                    "no symbols loaded yet. The existing cache-on-first-use fallback in " +
                    "fetch30MinuteHistoryCandles() will still fetch on demand for any symbol " +
                    "actually selected as a candidate.");
            return;
        }

        // Quick check only (not the full 3-attempt dedicated retry the
        // daily bootstrap needs) - by 11 minutes in, the instrument
        // token cache should already be warm from the daily bootstrap's
        // own successful run 5 minutes earlier.
        Map<String, Long> tokenCache = getOrBuildInstrumentTokenCache();
        if (tokenCache.isEmpty()) {
            log.warn("[MOMENTUM-CANDLE] 30-min candle bootstrap skipped - instrument token " +
                    "cache still empty even after the daily bootstrap should have warmed it. " +
                    "The existing cache-on-first-use fallback will still fetch on demand for " +
                    "any symbol actually selected as a candidate later.");
            return;
        }

        log.info("[MOMENTUM-CANDLE] 30-min candle bootstrap starting: {} symbols (full Sector " +
                "Heatmap universe, ~60 days each)", symbols.size());

        int loaded = 0;
        long nowMs = System.currentTimeMillis();
        for (String symbol : symbols) {
            try {
                List<MomentumCandidate.Candle> thirtyMin = fetch30MinuteCandlesFromApi(symbol);
                if (!thirtyMin.isEmpty()) {
                    thirtyMinCandleCache.put(symbol, thirtyMin);
                    thirtyMinCandleCacheTimestamp.put(symbol, nowMs);
                    loaded++;
                }
                if (loaded % 50 == 0 && loaded > 0) {
                    log.info("[MOMENTUM-CANDLE] 30-min candle bootstrap progress: {}/{}",
                            loaded, symbols.size());
                }
                // Same throttle as the daily bootstrap - avoid Zerodha rate limits.
                Thread.sleep(300);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.debug("[MOMENTUM-CANDLE] 30-min candle bootstrap failed for {}: {}",
                        symbol, e.getMessage());
            }
        }

        log.info("[MOMENTUM-CANDLE] 30-min candle bootstrap complete - {}/{} symbols cached",
                loaded, symbols.size());
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

    // FIX (per explicit user request: "same bootstrap... reuse in
    // momentum for daily candle fetch and store"). Momentum's OWN,
    // independent cache-once-per-day pattern for the daily S/R gate -
    // matching AI's proven "fetch once, reuse throughout the day"
    // approach, but built independently here (NOT reusing AI's actual
    // AiMarketDataService bean, which is unsafe to depend on since it's
    // @ConditionalOnProperty(ai.trading.enabled) and would not exist at
    // all if AI is ever disabled - Momentum must never be able to fail
    // to start just because AI is off). Unlike AI's full upfront
    // bootstrap of all ~500 symbols, this is a lazy, cache-on-first-use
    // pattern - appropriate since Momentum's tracked symbols change
    // every 15 minutes (only ~9 at a time), so pre-fetching symbols
    // that may never even be selected would be wasted work.
    private final Map<String, List<MomentumCandidate.Candle>> dailyCandleCache =
            new ConcurrentHashMap<>();
    private final Map<String, LocalDate> dailyCandleCacheDate = new ConcurrentHashMap<>();

    // FIX (per explicit user request: "same apply for 30 minute...
    // implement same logic but 30 minutes as well"). Cache for 30-min
    // candles, mirroring the daily cache's pattern but with a shorter,
    // time-based refresh instead of once-per-day - a new 30-min candle
    // genuinely completes every 30 minutes, so this cache expires after
    // 25 minutes (slightly less than the candle interval, ensuring
    // reasonably fresh data without excessive re-fetching).
    private final Map<String, List<MomentumCandidate.Candle>> thirtyMinCandleCache =
            new ConcurrentHashMap<>();
    private final Map<String, Long> thirtyMinCandleCacheTimestamp = new ConcurrentHashMap<>();
    private static final long THIRTY_MIN_CACHE_EXPIRY_MS = 25 * 60 * 1000;

    // FIX (confirmed real bug found from production logs: resolveToken()
    // was re-fetching the ENTIRE ~9,929-instrument NSE list from Kite on
    // EVERY single call - and during the 751-symbol daily-candle
    // bootstrap, this happened 751 times in rapid succession, almost
    // certainly hitting Zerodha's rate limits and causing every single
    // symbol resolution to fail, confirmed by the "null" exception
    // message pattern seen for even the most well-known, definitely-real
    // stocks like ICICIBANK and TATASTEEL). Caches the full symbol-to-
    // token map, fetched once and reused, refreshed once per day -
    // matching the same proven caching principle already used elsewhere
    // tonight (the daily candle cache itself, and ZerodhaOrderClient's
    // tick-size cache).
    private volatile Map<String, Long> instrumentTokenCache = null;
    private volatile long instrumentTokenCacheLastFailureMs = 0;
    private static final long INSTRUMENT_CACHE_BACKOFF_MS = 10_000; // 10 seconds - short enough
    // for the bootstrap's own dedicated 3-attempt retry loop (15 seconds apart) to make
    // genuinely fresh attempts each time, while still preventing rapid-fire spam
    // elsewhere; the dedicated retry loop is now the primary recovery mechanism, this
    // is just a secondary safety net for calls outside that loop
    private volatile LocalDate instrumentTokenCacheDate = null;

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
            // FIX: clear the daily-candle cache too, per the same
            // caching pattern added for the S/R gate - prevents
            // unbounded memory growth from stale entries accumulating
            // across many trading days.
            dailyCandleCache.clear();
            dailyCandleCacheDate.clear();
            // FIX (found during final cross-check): clear the 30-min
            // cache on daily reset too, matching the same pattern - not
            // a correctness issue (25-min time-based expiry already
            // ensures data is never more than 25 minutes stale when
            // used), purely for consistency and hygiene.
            thirtyMinCandleCache.clear();
            thirtyMinCandleCacheTimestamp.clear();
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

            boolean isLong = "LONG".equals(candidate.getDirection());

            // FIX (per explicit user request: "For a long trade, the
            // consolidation range should be formed below the day's
            // high... for a short trade, the consolidation range
            // should be formed above the day's low"). The consolidation
            // window itself must sit on the correct side of the
            // relevant day level BEFORE a breakout can be considered -
            // for LONG, the window's own high must not have already
            // reached/crossed the day's high; for SHORT, the window's
            // own low must not have already reached/crossed the day's
            // low. If the consolidation has already breached that level
            // during its own formation, this window is rejected and a
            // different window size (still within the same YAML-
            // configured min/max candle range, unchanged) is tried.
            boolean correctlyPositioned = isLong ? consolHigh < dayHigh : consolLow > dayLow;
            if (!correctlyPositioned) continue; // try a different window size

            double lastClose = breakoutCandle.close();
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
            Map<String, Long> cache = getOrBuildInstrumentTokenCache();
            Long token = cache.get(symbol.toUpperCase());
            return token != null ? token : 0L;
        } catch (Exception e) {
            log.warn("[MOMENTUM-CANDLE] Could not resolve instrument token for {}: {}",
                    symbol, e.getMessage());
            return 0L;
        }
    }

    /**
     * FIX (confirmed real bug found from production logs): builds the
     * symbol-to-token map ONCE per day and reuses it for every lookup,
     * instead of the previous behavior of calling
     * kiteConnect.getInstruments("NSE") fresh on every single
     * resolveToken() call - which, during the 751-symbol bootstrap,
     * meant 751 rapid-fire calls to fetch the entire ~9,929-instrument
     * NSE list, almost certainly triggering Zerodha's rate limits and
     * causing every single symbol to fail resolution (confirmed by the
     * "null" exception message seen for even the most liquid, definitely-
     * real stocks). Thread-safe via double-checked locking on the same
     * daily-refresh principle already used for the candle cache.
     */
    private synchronized Map<String, Long> getOrBuildInstrumentTokenCache() {
        LocalDate today = LocalDate.now(IST);
        if (instrumentTokenCache != null && today.equals(instrumentTokenCacheDate)) {
            return instrumentTokenCache;
        }

        // FIX (confirmed real gap from production logs: the first fetch
        // failed due to a platform-wide rate-limit collision at
        // startup - multiple services all calling getInstruments()
        // simultaneously - and without this backoff, every subsequent
        // call in the 751-symbol bootstrap loop retried IMMEDIATELY,
        // hammering the still-rate-limited endpoint for the entire
        // bootstrap duration instead of giving it time to recover).
        long msSinceLastFailure = System.currentTimeMillis() - instrumentTokenCacheLastFailureMs;
        if (instrumentTokenCacheLastFailureMs > 0 && msSinceLastFailure < INSTRUMENT_CACHE_BACKOFF_MS) {
            return instrumentTokenCache != null ? instrumentTokenCache : Map.of();
        }

        try {
            List<Instrument> instruments = kiteConnect.getInstruments("NSE");
            Map<String, Long> fresh = new ConcurrentHashMap<>();
            for (Instrument i : instruments) {
                if (i.getTradingsymbol() != null) {
                    fresh.put(i.getTradingsymbol().toUpperCase(), i.getInstrument_token());
                }
            }
            instrumentTokenCache = fresh;
            instrumentTokenCacheDate = today;
            instrumentTokenCacheLastFailureMs = 0; // clear any prior backoff on success
            log.info("[MOMENTUM-CANDLE] Instrument token cache refreshed - {} symbols cached " +
                    "for today", fresh.size());
            return fresh;
        } catch (KiteException | Exception e) {
            instrumentTokenCacheLastFailureMs = System.currentTimeMillis();
            log.warn("[MOMENTUM-CANDLE] Failed to refresh instrument token cache - backing off " +
                            "for {} seconds before retrying (non-fatal): {}",
                    INSTRUMENT_CACHE_BACKOFF_MS / 1000, e.getMessage());
            return instrumentTokenCache != null ? instrumentTokenCache : Map.of();
        }
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
     *   VWAP: LONG needs price above VWAP, SHORT needs price below.
     *   EMA:  LONG needs 20>50>200, SHORT needs 20<50<200.
     * Rejects (passed=false) if EITHER condition fails - both are
     * mandatory, checked together as the final gate immediately before
     * trade execution, per spec: "applied after all existing
     * validations and immediately before trade execution."
     *
     * FIX (per explicit user request: "change it to 5 minutes"). Now
     * sourced from 5-minute candles directly, instead of aggregating
     * 60-minute candles into synthetic 4-hour ones. ONLY the candle
     * timeframe changed - the VWAP/EMA formulas, the 200-candle
     * requirement, and the gate logic below are completely unchanged.
     */
    public TrendFilterResult checkTrendFilters(String symbol, String direction, double currentPrice) {
        List<MomentumCandidate.Candle> trendCandles = fetch5MinuteHistoryCandles(symbol);
        // Need at least 200 candles for a genuine EMA(200) - per spec's
        // own mandatory requirement; a shorter series would produce a
        // misleading, not-yet-converged EMA(200) value.
        if (trendCandles.size() < 200) {
            return new TrendFilterResult(false, "Not enough 5-minute candle history for EMA(200) - " +
                    "have " + trendCandles.size() + ", need 200", 0, 0, 0, 0);
        }

        double vwap4h = computeVwap(trendCandles);
        double ema20 = computeEma(trendCandles, 20);
        double ema50 = computeEma(trendCandles, 50);
        double ema200 = computeEma(trendCandles, 200);

        boolean isLong = "LONG".equals(direction);

        boolean vwapOk = isLong ? currentPrice > vwap4h : currentPrice < vwap4h;
        if (!vwapOk) {
            return new TrendFilterResult(false, String.format(
                    "5-min VWAP filter FAILED: price=%.2f %s required side of VWAP=%.2f (need %s)",
                    currentPrice, isLong ? "not above" : "not below", vwap4h,
                    isLong ? "price > VWAP" : "price < VWAP"),
                    vwap4h, ema20, ema50, ema200);
        }

        boolean emaOk = isLong ? (ema20 > ema50 && ema50 > ema200) : (ema20 < ema50 && ema50 < ema200);
        if (!emaOk) {
            return new TrendFilterResult(false, String.format(
                    "5-min EMA trend filter FAILED: EMA20=%.2f EMA50=%.2f EMA200=%.2f - need %s",
                    ema20, ema50, ema200, isLong ? "20>50>200" : "20<50<200"),
                    vwap4h, ema20, ema50, ema200);
        }

        return new TrendFilterResult(true, String.format(
                "5-min trend filters PASSED: VWAP=%.2f (price %s) | EMA20=%.2f EMA50=%.2f " +
                        "EMA200=%.2f (%s)", vwap4h, isLong ? "above" : "below", ema20, ema50, ema200,
                isLong ? "20>50>200" : "20<50<200"), vwap4h, ema20, ema50, ema200);
    }

    /**
     * FIX (per explicit user request: "change it to 5 minutes...
     * apart from this dont change anything in the strategy"). Fetches
     * 5-minute candles directly (10 calendar days - verified safely
     * within Kite's own documented 100-day maximum for this interval,
     * comfortably producing 500+ real candles, well above the 200
     * needed for EMA(200)) - no aggregation needed, since 5-minute is
     * already the desired granularity. Still deliberately uses
     * historical_data() (see class-level Javadoc) rather than the live
     * event-driven buffer, since 200 candles of backward-looking
     * history is the same genuine backtesting-style use case
     * historical_data() is documented to handle correctly - only the
     * SAME-DAY, real-time consolidation candles needed the live rebuild.
     */
    private List<MomentumCandidate.Candle> fetch5MinuteHistoryCandles(String symbol) {
        try {
            long token = resolveToken(symbol);
            if (token == 0) return List.of();

            LocalDateTime now = LocalDateTime.now(IST);
            Date to = toDate(now);
            Date from = toDate(now.minusDays(10));

            HistoricalData data = kiteConnect.getHistoricalData(
                    from, to, String.valueOf(token), "5minute", false, false);
            if (data == null || data.dataArrayList == null || data.dataArrayList.isEmpty()) {
                log.warn("[MOMENTUM-CANDLE] {} - 5-min trend-filter candle fetch: Kite's call " +
                                "succeeded (no exception) but returned {} - token={}", symbol,
                        data == null ? "a null response" : "zero candles", token);
                return List.of();
            }

            List<MomentumCandidate.Candle> candles = new ArrayList<>();
            for (Object obj : data.dataArrayList) {
                HistoricalData d = (HistoricalData) obj;
                candles.add(new MomentumCandidate.Candle(d.open, d.high, d.low, d.close,
                        d.timeStamp, d.volume));
            }
            return candles;
        } catch (KiteException | Exception e) {
            log.warn("[MOMENTUM-CANDLE] Failed to fetch 5-min trend-filter candles for {} " +
                    "(non-fatal, will retry next cycle): {}", symbol, e.getMessage());
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

    // ========================================================================
    // NEW GATE (per explicit user request): Daily Support/Resistance and
    // Trendline validation - a genuinely INDEPENDENT, additional gate.
    // Does NOT modify evaluate(), checkConsolidationValidity(),
    // checkTrendFilters(), or any other existing method in this file -
    // purely additive. Fetches its own 1-year Daily data independently
    // (rather than reusing AI's data, since AI's actual daily-candle
    // storage file was not available here to verify safely - per the
    // explicit instruction not to risk any interference with AI).
    // ========================================================================

    public record HigherTimeframeGateResult(boolean passed, String reason) {}

    /**
     * Checks whether the daily price structure supports the configured
     * minimum Risk:Reward before allowing a trade, per explicit spec:
     *   LONG:  nearest daily resistance (horizontal or descending
     *          trendline) must be far enough above entry to allow the
     *          configured R:R - UNLESS price has already broken that
     *          resistance with a strong move, or broken-and-retested it
     *          as new support.
     *   SHORT: mirror image, using support and ascending trendlines.
     * Called ONLY from enterBreakout() (once per actual trade attempt,
     * the same place the existing VWAP/EMA trend filter runs) - never
     * from the 30-second monitoring loop or the 15-minute scan, so this
     * cannot introduce any latency to scanning or monitoring.
     */
    public HigherTimeframeGateResult checkHigherTimeframeGate(String symbol, String direction,
                                                              double entry, double riskPerShare) {
        List<MomentumCandidate.Candle> daily = fetchDailyCandles(symbol);
        if (daily.size() < 30) {
            // Not enough daily history to identify meaningful structure -
            // fail safe by rejecting, rather than trading blind.
            return new HigherTimeframeGateResult(false,
                    "Not enough daily candle history for S/R analysis - have " +
                            daily.size() + ", need at least 30");
        }

        boolean isLong = "LONG".equals(direction);
        double minRequiredMove = riskPerShare * config.getRiskRewardRatio();

        // FIX (per explicit user request: "use the most technically
        // sound and production-ready approach rather than introducing
        // arbitrary hardcoded values"). ATR(14) is the data-driven,
        // volatility-normalized basis for every threshold below -
        // a stock's OWN natural noise level, not a fixed constant
        // applied identically to every stock regardless of behavior.
        double atr = computeDailyAtr(daily, 14);
        if (atr <= 0) {
            return new HigherTimeframeGateResult(false,
                    "Could not compute a valid ATR from daily data - cannot assess structure " +
                            "reliably");
        }

        // Major levels via clustering (per explicit user request: "not
        // every swing high or swing low... use a robust method... such
        // as multiple confirmations, clustering"). Groups nearby swing
        // points within an ATR-based tolerance (not a fixed %), keeps
        // only clusters with 2+ touches - the minimal, standard
        // definition of genuine support/resistance: a level touched
        // only once is just a swing point, not a level the market has
        // shown repeated interest in.
        List<Double> majorResistances = findMajorLevels(daily, atr, true);
        List<Double> majorSupports = findMajorLevels(daily, atr, false);
        Double trendlineLevel = isLong
                ? projectDescendingTrendline(daily, atr)
                : projectAscendingTrendline(daily, atr);

        Double nearestHorizontal = isLong
                ? majorResistances.stream().filter(h -> h > entry).min(Double::compareTo).orElse(null)
                : majorSupports.stream().filter(l -> l < entry).max(Double::compareTo).orElse(null);

        Double nearestLevel = combineNearest(isLong, nearestHorizontal, trendlineLevel, entry);

        if (nearestLevel == null) {
            return new HigherTimeframeGateResult(true,
                    "No significant daily " + (isLong ? "resistance" : "support") +
                            " found within range - path is clear");
        }

        double availableMove = isLong ? nearestLevel - entry : entry - nearestLevel;
        boolean sufficientRoom = availableMove >= minRequiredMove;

        if (sufficientRoom) {
            return new HigherTimeframeGateResult(true, String.format(
                    "Sufficient room to nearest major daily %s at %.2f (%.2f available, %.2f " +
                            "required for %.1f:1 R:R)", isLong ? "resistance" : "support", nearestLevel,
                    availableMove, minRequiredMove, config.getRiskRewardRatio()));
        }

        // FIX (per explicit user request: "do not hardcode a value such
        // as 0.5%... implement a technically sound method"). A breakout
        // is "strong" when price has moved beyond the level by at least
        // one ATR - a standard, volatility-normalized measure of a
        // genuinely significant move, not an arbitrary fixed percentage
        // applied identically regardless of the stock's own behavior.
        boolean alreadyBrokenStrong = isLong
                ? (entry - nearestLevel) >= atr
                : (nearestLevel - entry) >= atr;

        // FIX (per explicit user request: "avoid assumptions such as a
        // 10-day lookback or 0.5% tolerance... generic, production-ready
        // retest validation"). Finds the ACTUAL breakout candle (the
        // first daily close beyond the level, searched across the full
        // fetched history, not a fixed window) and checks whether price
        // has genuinely held on the correct side since that specific
        // point, using the same ATR-based tolerance throughout.
        boolean retestHolding = checkRetestHolding(daily, nearestLevel, isLong, atr);

        // FIX (per explicit user request, SHORT trade rejection
        // specifically: "implement actual rejection detection using
        // appropriate price action... visible bearish rejection"). For
        // SHORT, requires an explicit rejection candle at the retest -
        // a candle whose wick reaches toward/through the broken level
        // (upper wick, since this is resistance-from-below) while its
        // CLOSE stays back on the correct side - a genuine price-action
        // rejection pattern, not merely "closes have stayed below."
        // LONG keeps the "holding above" check per the prompt's own
        // differentiated wording (LONG says "holding above", SHORT
        // specifically says "gets rejected").
        boolean rejectionConfirmed = isLong
                ? retestHolding
                : checkRejectionCandle(daily, nearestLevel, atr);

        if (alreadyBrokenStrong) {
            return new HigherTimeframeGateResult(true, String.format(
                    "Nearest major daily %s at %.2f is close (%.2f available, %.2f required), " +
                            "but price has ALREADY broken it by >=1x ATR (%.2f) - allowed per strong-" +
                            "breakout exception", isLong ? "resistance" : "support", nearestLevel,
                    availableMove, minRequiredMove, atr));
        }

        if (rejectionConfirmed) {
            return new HigherTimeframeGateResult(true, String.format(
                    "Nearest major daily %s at %.2f is close (%.2f available, %.2f required), " +
                            "but price broke it and %s - allowed per retest-confirmation exception",
                    isLong ? "resistance" : "support", nearestLevel, availableMove, minRequiredMove,
                    isLong ? "is holding above it (now support)"
                            : "was rejected on retest (bearish rejection candle confirmed)"));
        }

        return new HigherTimeframeGateResult(false, String.format(
                "Nearest major daily %s at %.2f is too close (only %.2f available, need %.2f " +
                        "for %.1f:1 R:R) - not yet broken by a strong (>=1x ATR) move, no retest " +
                        "confirmation", isLong ? "resistance" : "support", nearestLevel, availableMove,
                minRequiredMove, config.getRiskRewardRatio()));
    }

    /** Picks the CLOSER of the horizontal level and the trendline level
     *  (whichever constrains the available room more), or whichever one
     *  exists if only one is present. Neither being present returns null. */
    private Double combineNearest(boolean isLong, Double horizontal, Double trendline, double entry) {
        if (horizontal == null) return trendline;
        if (trendline == null) return horizontal;
        return isLong ? Math.min(horizontal, trendline) : Math.max(horizontal, trendline);
    }

    /**
     * Standard ATR(14) over daily candles - the same True Range formula
     * used everywhere else in technical analysis: max of (high-low),
     * |high-prevClose|, |low-prevClose|, averaged over the period.
     * Independently implemented here (not reused from AI) to preserve
     * Momentum's required independence.
     */
    private double computeDailyAtr(List<MomentumCandidate.Candle> daily, int period) {
        if (daily.size() < period + 1) return 0;
        int start = daily.size() - period;
        double sumTr = 0;
        for (int i = start; i < daily.size(); i++) {
            double high = daily.get(i).high();
            double low = daily.get(i).low();
            double prevClose = daily.get(i - 1).close();
            double tr = Math.max(high - low, Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
            sumTr += tr;
        }
        return sumTr / period;
    }

    /**
     * FIX (per explicit user request: robust major-level identification
     * via clustering, not raw swing points). Detects raw swing points
     * (standard 3-candle-window swing high/low), then CLUSTERS nearby
     * ones together using an ATR-based tolerance - price zones within
     * roughly half an ATR of each other are treated as the same level,
     * since that's within the stock's own normal daily noise. Only
     * clusters with 2+ distinct touches are kept as "major" - a level
     * touched only once is just a swing point, not genuine support or
     * resistance by definition (which requires the market to have
     * shown repeated interest at that price).
     */
    private List<Double> findMajorLevels(List<MomentumCandidate.Candle> daily, double atr, boolean resistance) {
        int window = 3;
        List<Double> rawSwings = new ArrayList<>();
        for (int i = window; i < daily.size() - window; i++) {
            double val = resistance ? daily.get(i).high() : daily.get(i).low();
            boolean isSwing = true;
            for (int j = i - window; j <= i + window; j++) {
                if (j == i) continue;
                double other = resistance ? daily.get(j).high() : daily.get(j).low();
                if (resistance ? other > val : other < val) { isSwing = false; break; }
            }
            if (isSwing) rawSwings.add(val);
        }
        if (rawSwings.isEmpty()) return List.of();

        Collections.sort(rawSwings);
        double tolerance = atr * 0.5; // half an ATR - within normal daily noise for this stock

        List<Double> majorLevels = new ArrayList<>();
        List<Double> cluster = new ArrayList<>();
        for (double v : rawSwings) {
            // FIX (confirmed real bug found via direct user report,
            // verified with concrete numbers): previously compared each
            // new point only to the LAST-added point in the cluster
            // (chain-linking) - this let a cluster drift well beyond the
            // intended tolerance across a chain of points (e.g. 5 points
            // each 0.9 apart could span 3.6 total, nearly 4x a 1.0
            // tolerance, while each individual pairwise check still
            // passed). Now compares each new point to the cluster's
            // ANCHOR (the first point added to it) instead - this
            // genuinely bounds every point in the cluster to within
            // tolerance of where that cluster started, eliminating drift.
            if (cluster.isEmpty() || (v - cluster.get(0)) <= tolerance) {
                cluster.add(v);
            } else {
                if (cluster.size() >= 2) {
                    majorLevels.add(cluster.stream().mapToDouble(Double::doubleValue).average().orElse(0));
                }
                cluster.clear();
                cluster.add(v);
            }
        }
        if (cluster.size() >= 2) {
            majorLevels.add(cluster.stream().mapToDouble(Double::doubleValue).average().orElse(0));
        }
        return majorLevels;
    }

    /**
     * Projects a descending trendline resistance level at "today" by
     * connecting the two most recent swing highs, ONLY if the second is
     * genuinely lower than the first (a real descending trend) - then
     * linearly extrapolates that line forward to the current candle
     * index. Returns null if fewer than 2 swing highs exist, or if the
     * two most recent ones aren't actually descending.
     */
    /**
     * FIX (per explicit user spec: "Trendlines should be validated
     * using meaningful confirmations... ignore weak, insignificant, or
     * short-lived trendlines"). Searches ALL pairs of swing highs as
     * candidate descending-trendline anchors, then counts how many
     * OTHER swing highs independently lie close to (within ATR
     * tolerance of) each candidate line - a genuine confirmation, not
     * just 2 arbitrary points that happen to be descending. Requires
     * at least 3 total confirming points (the 2 anchors plus at least
     * 1 more) before accepting a trendline as real. Among qualifying
     * candidates, prefers the one with the MOST confirmations, then
     * the LONGEST span (explicitly favoring genuine, well-tested,
     * long-standing structure over short-lived coincidental lines).
     */
    private Double projectDescendingTrendline(List<MomentumCandidate.Candle> daily, double atr) {
        List<Double> vals = new ArrayList<>();
        List<Integer> idxs = new ArrayList<>();
        int window = 3;
        for (int i = window; i < daily.size() - window; i++) {
            double h = daily.get(i).high();
            boolean isSwing = true;
            for (int j = i - window; j <= i + window; j++) {
                if (j != i && daily.get(j).high() > h) { isSwing = false; break; }
            }
            if (isSwing) { idxs.add(i); vals.add(h); }
        }
        return fitDominantTrendline(idxs, vals, daily.size() - 1, atr, true);
    }

    /** Mirror of projectDescendingTrendline, for an ascending support line. */
    private Double projectAscendingTrendline(List<MomentumCandidate.Candle> daily, double atr) {
        List<Double> vals = new ArrayList<>();
        List<Integer> idxs = new ArrayList<>();
        int window = 3;
        for (int i = window; i < daily.size() - window; i++) {
            double l = daily.get(i).low();
            boolean isSwing = true;
            for (int j = i - window; j <= i + window; j++) {
                if (j != i && daily.get(j).low() < l) { isSwing = false; break; }
            }
            if (isSwing) { idxs.add(i); vals.add(l); }
        }
        return fitDominantTrendline(idxs, vals, daily.size() - 1, atr, false);
    }

    /**
     * Shared logic for both trendline directions: tries every pair of
     * swing points as trendline anchors, counts independent
     * confirmations from the remaining swing points, and returns the
     * projected value (at todayIdx) of whichever candidate line has
     * the most confirmations (ties broken by longest span). Returns
     * null if no candidate reaches the minimum 3-point confirmation
     * threshold - meaning no genuine, multiply-confirmed trendline
     * exists in the fetched history, which is a valid, honest outcome,
     * not a fallback to a weaker line.
     */
    private Double fitDominantTrendline(List<Integer> idxs, List<Double> vals, int todayIdx,
                                        double atr, boolean descending) {
        if (idxs.size() < 3) return null; // cannot have 3+ confirmations with fewer than 3 points at all

        double tolerance = atr * 0.5;
        double bestSlope = 0, bestIntercept = 0;
        int bestConfirmations = 0, bestSpan = 0;
        boolean found = false;

        for (int a = 0; a < idxs.size(); a++) {
            for (int b = a + 1; b < idxs.size(); b++) {
                int ia = idxs.get(a), ib = idxs.get(b);
                double va = vals.get(a), vb = vals.get(b);
                boolean correctDirection = descending ? vb < va : vb > va;
                if (!correctDirection) continue;

                double slope = (vb - va) / (double) (ib - ia);
                double intercept = va - slope * ia;

                int confirmations = 2; // the 2 anchor points themselves
                for (int c = 0; c < idxs.size(); c++) {
                    if (c == a || c == b) continue;
                    double projected = slope * idxs.get(c) + intercept;
                    if (Math.abs(vals.get(c) - projected) <= tolerance) confirmations++;
                }

                int span = ib - ia;
                boolean better = confirmations > bestConfirmations ||
                        (confirmations == bestConfirmations && span > bestSpan);

                if (confirmations >= 3 && better) {
                    bestSlope = slope;
                    bestIntercept = intercept;
                    bestConfirmations = confirmations;
                    bestSpan = span;
                    found = true;
                }
            }
        }

        if (!found) return null; // no genuinely multi-confirmed trendline exists
        return bestSlope * todayIdx + bestIntercept;
    }

    /**
     * FIX (per explicit user request: find the ACTUAL breakout point
     * rather than assuming a fixed lookback window). Searches the full
     * daily history for the FIRST candle whose close genuinely broke
     * past the level (beyond an ATR-based noise threshold, so a
     * single-candle wick spike doesn't count as a real break). From
     * that specific point forward, checks whether every subsequent
     * close has held on the correct side, within the same ATR-based
     * tolerance - a level that was broken but never actually held
     * (price closed back through it afterward) does not qualify.
     */
    private boolean checkRetestHolding(List<MomentumCandidate.Candle> daily, double level,
                                       boolean isLong, double atr) {
        double tolerance = atr * 0.5;
        int breakoutIdx = -1;
        for (int i = 0; i < daily.size(); i++) {
            double close = daily.get(i).close();
            boolean genuinelyBroken = isLong ? close > level + tolerance : close < level - tolerance;
            if (genuinelyBroken) { breakoutIdx = i; break; }
        }
        if (breakoutIdx < 0 || breakoutIdx >= daily.size() - 1) return false; // never broke, or broke on the very last candle (no retest yet)

        for (int i = breakoutIdx; i < daily.size(); i++) {
            double close = daily.get(i).close();
            boolean holding = isLong ? close >= level - tolerance : close <= level + tolerance;
            if (!holding) return false; // closed back through the level after breaking - did not hold
        }
        return true;
    }

    /**
     * FIX (per explicit user request, SHORT-specific: "implement actual
     * rejection detection using appropriate price action... visible
     * bearish rejection... closes back below the level"). Scans recent
     * daily candles for a genuine rejection pattern at the given level:
     * a candle whose HIGH reaches into or through the level (testing it
     * from below) while its CLOSE stays meaningfully back below the
     * level - the upper wick itself (high minus close) must be a
     * genuinely significant rejection, measured against ATR, not a
     * token difference. This is a real price-action pattern (a long
     * upper wick with a weak close, the classic bearish rejection
     * signature), not merely checking that closes have stayed below.
     */
    private boolean checkRejectionCandle(List<MomentumCandidate.Candle> daily, double level, double atr) {
        double approachTolerance = atr * 0.5;
        double minWickSize = atr * 0.3; // the rejection wick itself must be a meaningful fraction of ATR

        int lookback = Math.min(15, daily.size());
        List<MomentumCandidate.Candle> recent = daily.subList(daily.size() - lookback, daily.size());

        for (MomentumCandidate.Candle c : recent) {
            boolean testedTheLevel = c.high() >= level - approachTolerance;
            boolean closedBackBelow = c.close() < level - approachTolerance * 0.5;
            double upperWick = c.high() - Math.max(c.open(), c.close());
            boolean genuineRejectionWick = upperWick >= minWickSize;

            if (testedTheLevel && closedBackBelow && genuineRejectionWick) {
                return true;
            }
        }
        return false;
    }

    /**
     * Fetches roughly 1 year of Daily candles independently, via the
     * same proven historical_data() pattern already used for the trend
     * filter - NOT reused from AI, since AI's actual daily-candle
     * storage file was not available here to verify safely, per
     * explicit instruction not to risk any interference with AI's own
     * data or logic. Daily data is exactly the kind of multi-month
     * backward-looking use case historical_data() is documented to
     * handle correctly (confirmed earlier this session via Zerodha's
     * own guidance) - this is not the same-day-data unreliability that
     * prompted the live-tick rebuild for consolidation candles.
     */
    /**
     * FIX (per explicit user request: "same bootstrap... reuse in
     * momentum for daily candle fetch and store"). Cache-aware entry
     * point - checks Momentum's own daily-candle cache first before
     * hitting the API, matching AI's proven "fetch once per day,
     * reuse" pattern. Only re-fetches if this symbol has never been
     * cached today, or if the cached entry is from a PRIOR day (stale).
     * The actual fetch logic itself (below, fetchDailyCandlesFromApi())
     * is completely unchanged from before this caching was added.
     */
    private List<MomentumCandidate.Candle> fetchDailyCandles(String symbol) {
        LocalDate today = LocalDate.now(IST);
        LocalDate cachedDate = dailyCandleCacheDate.get(symbol);
        if (today.equals(cachedDate)) {
            List<MomentumCandidate.Candle> cached = dailyCandleCache.get(symbol);
            if (cached != null) {
                log.debug("[MOMENTUM-CANDLE] {} - using cached daily candles from today " +
                        "({} candles) instead of re-fetching", symbol, cached.size());
                return cached;
            }
        }

        List<MomentumCandidate.Candle> fresh = fetchDailyCandlesFromApi(symbol);
        if (!fresh.isEmpty()) {
            dailyCandleCache.put(symbol, fresh);
            dailyCandleCacheDate.put(symbol, today);
        }
        return fresh;
    }

    /**
     * The actual fetch logic - UNCHANGED from before caching was added.
     * Same 370-day window, same error handling, same historical_data()
     * call. Only called now via fetchDailyCandles() above when the
     * cache is empty or stale, instead of on every single gate check.
     */
    private List<MomentumCandidate.Candle> fetchDailyCandlesFromApi(String symbol) {
        try {
            long token = resolveToken(symbol);
            if (token == 0) return List.of();

            LocalDateTime now = LocalDateTime.now(IST);
            Date to = toDate(now);
            Date from = toDate(now.minusDays(370)); // ~1 year, small margin for holidays/weekends

            HistoricalData data = kiteConnect.getHistoricalData(
                    from, to, String.valueOf(token), "day", false, false);
            if (data == null || data.dataArrayList == null || data.dataArrayList.isEmpty()) {
                log.warn("[MOMENTUM-CANDLE] {} - daily S/R gate candle fetch: Kite's call " +
                                "succeeded (no exception) but returned {} - token={}", symbol,
                        data == null ? "a null response" : "zero candles", token);
                return List.of();
            }

            List<MomentumCandidate.Candle> candles = new ArrayList<>();
            for (Object obj : data.dataArrayList) {
                HistoricalData d = (HistoricalData) obj;
                candles.add(new MomentumCandidate.Candle(d.open, d.high, d.low, d.close,
                        d.timeStamp, d.volume));
            }
            return candles;
        } catch (KiteException | Exception e) {
            log.warn("[MOMENTUM-CANDLE] Failed to fetch daily S/R gate candles for {} " +
                    "(non-fatal, will retry next cycle): {}", symbol, e.getMessage());
            return List.of();
        }
    }

    // ========================================================================
    // NEW GATE (per explicit user request: "same apply for 30 minute...
    // cross checking 30 minutes support/resistance/trend/retest all
    // logic we have, please implement same logic but 30 minutes as
    // well"). Reuses the EXACT SAME generic helper methods already used
    // for the daily gate (findMajorLevels, projectDescendingTrendline,
    // projectAscendingTrendline, checkRetestHolding, checkRejectionCandle,
    // computeDailyAtr) - none of these are daily-specific, they operate
    // on whatever candle list is passed in. This is purely additive -
    // zero changes to the daily gate's own code or behavior.
    // ========================================================================

    /**
     * Cache-aware entry point for 30-min candles, mirroring
     * fetchDailyCandles()'s pattern but with a time-based (25-minute)
     * expiry instead of once-per-day, since 30-min data changes far
     * more frequently.
     */
    private List<MomentumCandidate.Candle> fetch30MinuteHistoryCandles(String symbol) {
        long nowMs = System.currentTimeMillis();
        Long cachedAt = thirtyMinCandleCacheTimestamp.get(symbol);
        if (cachedAt != null && (nowMs - cachedAt) < THIRTY_MIN_CACHE_EXPIRY_MS) {
            List<MomentumCandidate.Candle> cached = thirtyMinCandleCache.get(symbol);
            if (cached != null) return cached;
        }

        List<MomentumCandidate.Candle> fresh = fetch30MinuteCandlesFromApi(symbol);
        if (!fresh.isEmpty()) {
            thirtyMinCandleCache.put(symbol, fresh);
            thirtyMinCandleCacheTimestamp.put(symbol, nowMs);
        }
        return fresh;
    }

    /**
     * Raw fetch - same proven historical_data() pattern as the daily
     * fetch, but "30minute" interval with a 60-day lookback (well
     * within Kite's own documented 100-day maximum for this interval,
     * giving ~750 real 30-min candles - comfortably enough for genuine
     * swing detection using the same 3-candle-each-side window already
     * proven for daily).
     */
    private List<MomentumCandidate.Candle> fetch30MinuteCandlesFromApi(String symbol) {
        try {
            long token = resolveToken(symbol);
            if (token == 0) return List.of();

            LocalDateTime now = LocalDateTime.now(IST);
            Date to = toDate(now);
            Date from = toDate(now.minusDays(60));

            HistoricalData data = kiteConnect.getHistoricalData(
                    from, to, String.valueOf(token), "30minute", false, false);
            if (data == null || data.dataArrayList == null || data.dataArrayList.isEmpty()) {
                log.warn("[MOMENTUM-CANDLE] {} - 30-min S/R gate candle fetch: Kite's call " +
                                "succeeded (no exception) but returned {} - token={}", symbol,
                        data == null ? "a null response" : "zero candles", token);
                return List.of();
            }

            List<MomentumCandidate.Candle> candles = new ArrayList<>();
            for (Object obj : data.dataArrayList) {
                HistoricalData d = (HistoricalData) obj;
                candles.add(new MomentumCandidate.Candle(d.open, d.high, d.low, d.close,
                        d.timeStamp, d.volume));
            }
            return candles;
        } catch (KiteException | Exception e) {
            log.warn("[MOMENTUM-CANDLE] Failed to fetch 30-min S/R gate candles for {} " +
                    "(non-fatal, will retry next cycle): {}", symbol, e.getMessage());
            return List.of();
        }
    }

    /**
     * The 30-minute equivalent of checkHigherTimeframeGate() - identical
     * structure and logic, reusing every one of the same underlying
     * methods (findMajorLevels, trendline projection, retest/rejection
     * checks, ATR), just fed 30-min candles instead of daily. A lower
     * minimum-history bar (20 candles, roughly 2 trading days at 30-min)
     * reflects the much shorter real-world span this timeframe covers
     * compared to daily's ~1-year requirement.
     */
    public HigherTimeframeGateResult check30MinuteHigherTimeframeGate(String symbol, String direction,
                                                                      double entry, double riskPerShare) {
        List<MomentumCandidate.Candle> thirtyMin = fetch30MinuteHistoryCandles(symbol);
        if (thirtyMin.size() < 20) {
            return new HigherTimeframeGateResult(false,
                    "Not enough 30-min candle history for S/R analysis - have " +
                            thirtyMin.size() + ", need at least 20");
        }

        boolean isLong = "LONG".equals(direction);
        double minRequiredMove = riskPerShare * config.getRiskRewardRatio();

        double atr = computeDailyAtr(thirtyMin, 14);
        if (atr <= 0) {
            return new HigherTimeframeGateResult(false,
                    "Could not compute a valid ATR from 30-min data - cannot assess structure " +
                            "reliably");
        }

        List<Double> majorResistances = findMajorLevels(thirtyMin, atr, true);
        List<Double> majorSupports = findMajorLevels(thirtyMin, atr, false);
        Double trendlineLevel = isLong
                ? projectDescendingTrendline(thirtyMin, atr)
                : projectAscendingTrendline(thirtyMin, atr);

        Double nearestHorizontal = isLong
                ? majorResistances.stream().filter(h -> h > entry).min(Double::compareTo).orElse(null)
                : majorSupports.stream().filter(l -> l < entry).max(Double::compareTo).orElse(null);

        Double nearestLevel = combineNearest(isLong, nearestHorizontal, trendlineLevel, entry);

        if (nearestLevel == null) {
            return new HigherTimeframeGateResult(true,
                    "No significant 30-min " + (isLong ? "resistance" : "support") +
                            " found within range - path is clear");
        }

        double availableMove = isLong ? nearestLevel - entry : entry - nearestLevel;
        boolean sufficientRoom = availableMove >= minRequiredMove;

        if (sufficientRoom) {
            return new HigherTimeframeGateResult(true, String.format(
                    "Sufficient room to nearest major 30-min %s at %.2f (%.2f available, %.2f " +
                            "required for %.1f:1 R:R)", isLong ? "resistance" : "support", nearestLevel,
                    availableMove, minRequiredMove, config.getRiskRewardRatio()));
        }

        boolean alreadyBrokenStrong = isLong
                ? (entry - nearestLevel) >= atr
                : (nearestLevel - entry) >= atr;

        boolean retestHolding = checkRetestHolding(thirtyMin, nearestLevel, isLong, atr);

        boolean rejectionConfirmed = isLong
                ? retestHolding
                : checkRejectionCandle(thirtyMin, nearestLevel, atr);

        if (alreadyBrokenStrong) {
            return new HigherTimeframeGateResult(true, String.format(
                    "Nearest major 30-min %s at %.2f is close (%.2f available, %.2f required), " +
                            "but price has ALREADY broken it by >=1x ATR (%.2f) - allowed per strong-" +
                            "breakout exception", isLong ? "resistance" : "support", nearestLevel,
                    availableMove, minRequiredMove, atr));
        }

        if (rejectionConfirmed) {
            return new HigherTimeframeGateResult(true, String.format(
                    "Nearest major 30-min %s at %.2f is close (%.2f available, %.2f required), " +
                            "but price broke it and %s - allowed per retest-confirmation exception",
                    isLong ? "resistance" : "support", nearestLevel, availableMove, minRequiredMove,
                    isLong ? "is holding above it (now support)"
                            : "was rejected on retest (bearish rejection candle confirmed)"));
        }

        return new HigherTimeframeGateResult(false, String.format(
                "Nearest major 30-min %s at %.2f is too close (only %.2f available, need %.2f " +
                        "for %.1f:1 R:R) - not yet broken by a strong (>=1x ATR) move, no retest " +
                        "confirmation", isLong ? "resistance" : "support", nearestLevel, availableMove,
                minRequiredMove, config.getRiskRewardRatio()));
    }
}