// ========== MODIFIED FILE ==========
// Path: src/main/java/com/trading/marketdata/service/MarketDataStartupService.java
// CHANGES vs original:
//   1. Added NiftyHistoricalLoaderService dependency (one new constructor param).
//   2. Added loadNiftyHistory() call BEFORE streaming starts.
//      This warm-ups MarketDirectionService buffer immediately so direction is
//      BULLISH / BEARISH right from first live candle, not SIDEWAYS for 8 days.
//   3. Zero changes to existing logic — purely additive.
// ============================================================================

package com.trading.marketdata.service;

import com.trading.auth.model.ZerodhaToken;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * MarketDataStartupService – FIXED.
 *
 * ROOT CAUSE of SIDEWAYS-always bug:
 *   MarketDirectionService.recalculate() requires 200 Nifty 15-min candles
 *   to compute EMA200. The buffer starts empty. Live candles trickle in at
 *   25/day (market hours). Result: SIDEWAYS for ~8 trading days, blocking
 *   ALL strategy signals the entire time.
 *
 * FIX: Step 2a — call niftyHistoricalLoader.loadNiftyHistory() right after
 *   instrument cache is built and BEFORE WebSocket streaming starts.
 *   This fetches last 300 historical Nifty 15-min candles, fills the buffer,
 *   and triggers recalculate() so direction is BULLISH/BEARISH immediately.
 *
 * FIX: Step 1 (pre-existing) — ALL Nifty500 tokens go into fullTokens (FULL mode)
 *   so CandleAggregatorService gets tick timestamps to build 5min/15min candles.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MarketDataStartupService {

    private final InstrumentCacheService      instrumentCache;
    private final MarketDataService           marketDataService;
    private final NiftyHistoricalLoaderService niftyHistoricalLoader; // ← ADDED

    public void onTokenReady(ZerodhaToken token) {
        log.info("Token ready — starting market data pipeline");

        // ── Step 1: Build instrument cache ────────────────────────────────────
        try {
            instrumentCache.build();
        } catch (Exception e) {
            log.warn("Instrument cache build failed — continuing: {}", e.getMessage());
        }

        // ── Step 2a: Pre-load Nifty 15-min history (THE CRITICAL FIX) ─────────
        // This MUST happen before streaming starts. It warms up MarketDirectionService
        // so direction is correct from the first live candle, not after 8 days.
        try {
            log.info("Pre-loading Nifty 15-min history for market direction warm-up...");
            niftyHistoricalLoader.loadNiftyHistory();
        } catch (Exception e) {
            log.warn("Nifty history pre-load failed — market direction may show SIDEWAYS until " +
                    "200 live 15-min candles accumulate: {}", e.getMessage());
        }

        // ── Step 2b: Build subscription token lists ────────────────────────────
        List<Long> fullTokens;
        List<Long> quoteTokens;

        try {
            // ALL Nifty500 tokens → FULL mode
            // buildNifty500Tokens() already includes:
            //   - NIFTY_TOKEN (256265) — for Gate 1 market direction
            //   - BANKNIFTY_TOKEN (260105)
            //   - VIX_TOKEN (264969) — for VixService
            //   - All ~402 resolved Nifty500 stock tokens
            //
            // FULL mode gives CandleAggregatorService the tick timestamps
            // it needs to build 5min and 15min candles for every stock.
            fullTokens  = new ArrayList<>(instrumentCache.buildNifty500Tokens());
            quoteTokens = List.of(); // nothing in quote mode

            log.info("Subscription: {} tokens ALL in FULL mode", fullTokens.size());

        } catch (Exception e) {
            log.warn("Subscription list build failed — using fallback: {}", e.getMessage());
            fullTokens  = new ArrayList<>();
            fullTokens.add(256265L); // Nifty 50
            fullTokens.add(264969L); // VIX
            quoteTokens = List.of();
        }

        // ── Step 3: Start WebSocket streaming ─────────────────────────────────
        try {
            marketDataService.startStreaming(
                    token.getAccessToken(), fullTokens, quoteTokens);
            log.info("Market data pipeline READY — full={} quote={} (ALL in FULL mode)",
                    fullTokens.size(), quoteTokens.size());
        } catch (Exception e) {
            log.error("WebSocket startup failed: {}", e.getMessage());
        }
    }
}