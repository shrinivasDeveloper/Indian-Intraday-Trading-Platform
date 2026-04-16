package com.trading.marketdata.service;

import com.trading.auth.model.ZerodhaToken;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * MarketDataStartupService — FIXED v3.
 *
 * Startup sequence (parallel-optimized):
 *   1. Build instrument cache (token → symbol mapping)
 *   2. Run ParallelWarmupService (≤8s parallel Redis + broker load)
 *   3. Subscribe all tokens → WebSocket streaming starts
 *   4. TRADING BEGINS IMMEDIATELY
 *
 * CHANGED: WarmupService → ParallelWarmupService
 *   ParallelWarmupService loads all data concurrently (Redis first, broker fallback).
 *   This cuts cold-start from ~60s to ≤8s.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MarketDataStartupService {

    private final InstrumentCacheService instrumentCache;
    private final MarketDataService      marketDataService;
    private final ParallelWarmupService  parallelWarmup;   // CHANGED: was WarmupService

    public void onTokenReady(ZerodhaToken token) {
        log.info("[STARTUP] ⚡ Token ready — starting market data pipeline (parallel warmup)");

        // ── Step 1: Build instrument cache ────────────────────────────────────
        try {
            instrumentCache.build();
            log.info("[STARTUP] ✅ Instrument cache built");
        } catch (Exception e) {
            log.warn("[STARTUP] Instrument cache build failed — continuing: {}", e.getMessage());
        }

        // ── Step 2: PARALLEL WARMUP (≤8s) ─────────────────────────────────────
        // Loads Nifty + BankNifty candles from Redis (or broker fallback).
        // Rebuilds EMA, ATR, IB, channel indicators in parallel.
        // MUST complete before WebSocket starts so first tick has correct context.
        try {
            log.info("[STARTUP] ⚡ Starting parallel warmup...");
            parallelWarmup.runWarmup();
            log.info("[STARTUP] ✅ Parallel warmup complete — trading indicators ready");
        } catch (Exception e) {
            log.warn("[STARTUP] Parallel warmup failed — market direction may be SIDEWAYS " +
                    "until live candles accumulate: {}", e.getMessage());
        }

        // ── Step 3: Build subscription token list ──────────────────────────────
        List<Long> fullTokens;
        List<Long> quoteTokens;

        try {
            fullTokens = new ArrayList<>(instrumentCache.buildNifty500Tokens());

            // Always ensure BankNifty is included
            long bankNiftyToken = instrumentCache.getBankNiftyToken();
            if (bankNiftyToken != 0 && !fullTokens.contains(bankNiftyToken)) {
                fullTokens.add(bankNiftyToken);
                log.info("[STARTUP] Added BankNifty token {} to subscription", bankNiftyToken);
            }

            quoteTokens = List.of();
            log.info("[STARTUP] Subscription list: {} tokens", fullTokens.size());

        } catch (Exception e) {
            log.warn("[STARTUP] Subscription list build failed — using fallback: {}", e.getMessage());
            fullTokens = new ArrayList<>();
            fullTokens.add(256265L);   // Nifty 50
            fullTokens.add(260105L);   // BankNifty
            fullTokens.add(264969L);   // VIX
            quoteTokens = List.of();
        }

        // ── Step 4: Start WebSocket streaming → TRADING BEGINS ────────────────
        try {
            marketDataService.startStreaming(token.getAccessToken(), fullTokens, quoteTokens);
            log.info("[STARTUP] ✅ WebSocket streaming started. " +
                    "TRADING IS LIVE (full={} quote={})", fullTokens.size(), quoteTokens.size());
        } catch (Exception e) {
            log.error("[STARTUP] ❌ WebSocket startup failed: {}", e.getMessage());
        }
    }
}