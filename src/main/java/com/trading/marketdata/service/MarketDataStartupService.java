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
    // FIX (per explicit user request: "how can we make websocket feed
    // matching sector heatmap all stocks" - Option A, expanding the
    // shared WebSocket subscription for every strategy, not just
    // Momentum).
    private final com.trading.sectorheatmap.service.SectorHeatmapDataService sectorHeatmapDataService;
    private final com.zerodhatech.kiteconnect.KiteConnect kiteConnect;

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

            // FIX (per explicit user request: "how can we make websocket
            // feed matching sector heatmap all stocks" - Option A).
            // The Nifty500 list above only covers ~500 stocks, but the
            // Sector Heatmap tracks the full ~751-stock Nifty Total
            // Market universe. Momentum (and any future strategy) can
            // only get live ticks for symbols actually subscribed here -
            // this expands the SHARED subscription to cover that full
            // universe, benefiting every strategy, not just Momentum.
            // Resolves each additional symbol's token directly via
            // KiteConnect's own Instrument list (confirmed via bytecode
            // earlier this session: getTradingsymbol()/getInstrument_token()
            // are genuine, real fields) - a single extra call, made once
            // at startup, not on any repeated/hot path.
            int addedFromHeatmap = 0;
            try {
                java.util.Set<String> heatmapSymbols = sectorHeatmapDataService.getAllTrackedSymbols();
                if (heatmapSymbols.isEmpty()) {
                    log.warn("[STARTUP] Sector Heatmap has no symbols loaded yet - skipping " +
                            "subscription expansion this startup (Nifty500's {} tokens still " +
                            "subscribed correctly; heatmap-only stocks will lack live ticks " +
                            "until the next restart, once the heatmap has data)", fullTokens.size());
                } else {
                    java.util.List<com.zerodhatech.models.Instrument> allInstruments =
                            kiteConnect.getInstruments("NSE");
                    java.util.Map<String, Long> symbolToToken = new java.util.HashMap<>();
                    for (com.zerodhatech.models.Instrument inst : allInstruments) {
                        symbolToToken.put(inst.getTradingsymbol(), inst.getInstrument_token());
                    }
                    for (String symbol : heatmapSymbols) {
                        // FIX: renamed from "token" - that name was
                        // already the method parameter (ZerodhaToken
                        // token), causing a real "already defined in
                        // scope" compile error.
                        Long instrumentToken = symbolToToken.get(symbol);
                        if (instrumentToken != null && !fullTokens.contains(instrumentToken)) {
                            fullTokens.add(instrumentToken);
                            addedFromHeatmap++;
                        }
                    }
                    log.info("[STARTUP] Expanded subscription with {} additional Sector Heatmap " +
                            "symbols not already in the Nifty500 list", addedFromHeatmap);
                }
                // FIX: KiteException extends java.lang.Throwable directly
                // (confirmed via bytecode earlier this session), NOT
                // Exception - "catch (Exception e)" alone does not catch
                // it, since kiteConnect.getInstruments() above is
                // declared to throw it. Same class of bug already fixed
                // once in Hero-Zero tonight - fixed here too.
            } catch (com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException | Exception e) {
                log.warn("[STARTUP] Sector Heatmap subscription expansion failed - continuing " +
                        "with the base Nifty500 subscription only: {}", e.getMessage());
            }

            quoteTokens = List.of();
            log.info("[STARTUP] Subscription list: {} tokens ({} Nifty500 + BankNifty, {} " +
                            "additional from Sector Heatmap)", fullTokens.size(),
                    fullTokens.size() - addedFromHeatmap, addedFromHeatmap);

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