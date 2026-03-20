package com.trading.marketdata.service;

import com.trading.auth.model.ZerodhaToken;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Starts the market data pipeline once a valid Zerodha token is available.
 *
 * FULL mode:  Nifty only (256265) — used for Gate 1 market direction
 * QUOTE mode: Nifty500 stocks (~402 tokens) — used for scanner + watchlist
 *
 * Note: BankNifty removed from Gate 1 — market direction now uses Nifty only.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MarketDataStartupService {

    private final InstrumentCacheService instrumentCache;
    private final MarketDataService      marketDataService;

    public void onTokenReady(ZerodhaToken token) {
        log.info("Token ready — starting market data pipeline");

        // Step 1: Build instrument cache
        try {
            instrumentCache.build();
        } catch (Exception e) {
            log.warn("Instrument cache build failed — continuing: {}", e.getMessage());
        }

        // Step 2: Build token subscription lists
        List<Long> fullTokens;
        List<Long> quoteTokens;
        try {
            // FULL mode — Nifty only for Gate 1
            // BankNifty removed — market direction now checks Nifty only
            fullTokens = List.of(instrumentCache.getNiftyToken());

            // QUOTE mode — all Nifty500 stocks + VIX
            quoteTokens = instrumentCache.buildNifty500Tokens();

        } catch (Exception e) {
            log.warn("Subscription list build failed — using Nifty only: {}", e.getMessage());
            fullTokens  = List.of(256265L); // Nifty hardcoded fallback
            quoteTokens = List.of(264969L); // VIX only
        }

        // Step 3: Start WebSocket streaming
        try {
            marketDataService.startStreaming(
                    token.getAccessToken(), fullTokens, quoteTokens);
            log.info("Market data pipeline READY — full={} quote={}",
                    fullTokens.size(), quoteTokens.size());
        } catch (Exception e) {
            log.error("WebSocket startup failed: {}", e.getMessage());
            log.error("Tick data will not be received. Restart after fixing credentials.");
        }
    }
}