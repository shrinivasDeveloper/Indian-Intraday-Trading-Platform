// ============================================================
// REPLACE FILE (full replacement)
// Path: src/main/java/com/trading/marketdata/service/MarketDataStartupService.java
// CHANGES vs original:
//   1. Replaced NiftyHistoricalLoaderService with WarmupService
//      (WarmupService covers Nifty + BankNifty + 5m + 15m + IB persistence)
//   2. Added getBankNiftyToken() to subscription token list
//   3. All original token subscription logic preserved
// ============================================================
package com.trading.marketdata.service;

import com.trading.auth.model.ZerodhaToken;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * MarketDataStartupService — FIXED v2.
 *
 * Startup sequence:
 *   1. Build instrument cache (token → symbol mapping)
 *   2. Run WarmupService (pre-loads 300 Nifty + BankNifty candles, computes IB if missed)
 *   3. Subscribe all tokens in FULL mode → WebSocket streaming starts
 *
 * This order guarantees:
 *   - Direction is BULLISH/BEARISH from first live candle (not SIDEWAYS for 8 days)
 *   - IB is already computed if restart happened mid-session
 *   - BankNifty mode is correct from startup
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MarketDataStartupService {

    private final InstrumentCacheService instrumentCache;
    private final MarketDataService      marketDataService;
    private final WarmupService          warmupService;  // REPLACES NiftyHistoricalLoaderService

    public void onTokenReady(ZerodhaToken token) {
        log.info("Token ready — starting market data pipeline");

        // ── Step 1: Build instrument cache ────────────────────────────────────
        try {
            instrumentCache.build();
        } catch (Exception e) {
            log.warn("Instrument cache build failed — continuing: {}", e.getMessage());
        }

        // ── Step 2: Warmup (THE CRITICAL FIX — ISSUES 1, 2, 5) ──────────────
        // Pre-loads Nifty + BankNifty history. Forces IB computation if mid-session.
        // MUST happen before WebSocket streaming starts.
        try {
            log.info("Running WarmupService (Nifty + BankNifty historical pre-load)...");
            warmupService.runWarmup();
        } catch (Exception e) {
            log.warn("WarmupService failed — market direction may be SIDEWAYS until 200 live candles: {}", e.getMessage());
        }

        // ── Step 3: Build subscription token list ─────────────────────────────
        List<Long> fullTokens;
        List<Long> quoteTokens;

        try {
            fullTokens = new ArrayList<>(instrumentCache.buildNifty500Tokens());

            // Ensure BankNifty token is included (important for BankNiftyModeEngine)
            long bankNiftyToken = instrumentCache.getBankNiftyToken();
            if (bankNiftyToken != 0 && !fullTokens.contains(bankNiftyToken)) {
                fullTokens.add(bankNiftyToken);
                log.info("Added BankNifty token {} to FULL subscription", bankNiftyToken);
            }

            quoteTokens = List.of();
            log.info("Subscription: {} tokens ALL in FULL mode", fullTokens.size());

        } catch (Exception e) {
            log.warn("Subscription list build failed — using fallback: {}", e.getMessage());
            fullTokens  = new ArrayList<>();
            fullTokens.add(256265L);  // Nifty 50
            fullTokens.add(260105L);  // BankNifty
            fullTokens.add(264969L);  // VIX
            quoteTokens = List.of();
        }

        // ── Step 4: Start WebSocket streaming ─────────────────────────────────
        try {
            marketDataService.startStreaming(token.getAccessToken(), fullTokens, quoteTokens);
            log.info("Market data pipeline READY — full={} quote={}", fullTokens.size(), quoteTokens.size());
        } catch (Exception e) {
            log.error("WebSocket startup failed: {}", e.getMessage());
        }
    }
}