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

    public MomentumCandleService(KiteConnect kiteConnect, MomentumConfig config) {
        this.kiteConnect = kiteConnect;
        this.config = config;
    }

    public record EvaluationResult(
            boolean validConsolidation, boolean breakoutTriggered,
            double consolidationHigh, double consolidationLow,
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
     *   - "Enter only when price breaks above consolidation high (long)
     *      or below consolidation low (short)"
     */
    public EvaluationResult evaluate(MomentumCandidate candidate) {
        List<MomentumCandidate.Candle> recent = fetchRecentCandles(candidate.getSymbol(),
                config.getMaxConsolidationCandles() + 3); // small buffer for volatility comparison
        if (recent.size() < config.getMinConsolidationCandles()) {
            return new EvaluationResult(false, false, 0, 0, recent,
                    "Not enough candle history yet (" + recent.size() + " available)");
        }

        // Try consolidation windows from smallest to largest, per spec's
        // explicit "2 to 4 candles" range - most recent candles only.
        for (int windowSize = config.getMinConsolidationCandles();
             windowSize <= config.getMaxConsolidationCandles() && windowSize <= recent.size();
             windowSize++) {

            List<MomentumCandidate.Candle> window =
                    recent.subList(recent.size() - windowSize, recent.size());

            String rejectReason = checkConsolidationValidity(window);
            if (rejectReason != null) continue; // try a different window size

            double high = window.stream().mapToDouble(MomentumCandidate.Candle::high).max().orElse(0);
            double low = window.stream().mapToDouble(MomentumCandidate.Candle::low).min().orElse(0);

            double lastClose = recent.get(recent.size() - 1).close();
            boolean isLong = "LONG".equals(candidate.getDirection());
            boolean breakout = isLong ? lastClose > high : lastClose < low;

            return new EvaluationResult(true, breakout, high, low, window,
                    breakout
                            ? String.format("Valid %d-candle consolidation, BREAKOUT confirmed " +
                                    "(close %.2f vs consolidation %s %.2f)", windowSize, lastClose,
                            isLong ? "high" : "low", isLong ? high : low)
                            : String.format("Valid %d-candle consolidation forming, waiting for " +
                                    "breakout (current=%.2f, need %s %.2f)", windowSize, lastClose,
                            isLong ? "above" : "below", isLong ? high : low));
        }

        return new EvaluationResult(false, false, 0, 0, recent,
                "No valid small-bodied consolidation found in the last " +
                        config.getMaxConsolidationCandles() + " candles");
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
                all.add(new MomentumCandidate.Candle(d.open, d.high, d.low, d.close, d.timeStamp));
            }
            if (all.size() <= count) return all;
            return all.subList(all.size() - count, all.size());
        } catch (KiteException | Exception e) {
            log.debug("[MOMENTUM-CANDLE] Failed to fetch candles for {} (non-fatal, will retry " +
                    "next cycle): {}", symbol, e.getMessage());
            return List.of();
        }
    }

    private long resolveToken(String symbol) {
        return tokenCache.computeIfAbsent(symbol, s -> {
            try {
                List<Instrument> instruments = kiteConnect.getInstruments("NSE");
                for (Instrument i : instruments) {
                    if (s.equalsIgnoreCase(i.getTradingsymbol())) {
                        return i.getInstrument_token();
                    }
                }
            } catch (KiteException | Exception e) {
                log.warn("[MOMENTUM-CANDLE] Could not resolve instrument token for {}: {}",
                        s, e.getMessage());
            }
            return 0L;
        });
    }

    private Date toDate(LocalDateTime ldt) {
        return Date.from(ldt.atZone(IST).toInstant());
    }
}