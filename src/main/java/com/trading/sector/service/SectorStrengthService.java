// ============================================================
// REPLACE FILE — End-to-End Bug Fix
// Path: src/main/java/com/trading/sector/service/SectorStrengthService.java
//
// GLOBAL OVERRIDE BUG FOUND & FIXED:
//   isSectorAligned(symbol, forLong) was used as a HARD GATE in:
//     1. SevenGateScannerService Gate 2 — blocks ALL stocks before even checking compression
//     2. TradeManagementService checkAllTradesAlignment() — force-closes existing trades
//     3. PaperTradeManagementService checkAllTradesAlignment() — same
//
//   BUG: isSectorAligned() returned false when:
//     a) sectorData was null (sector not found in map) → blocked entire gate
//     b) alignedBullish/alignedBearish was not set (startup state)
//     c) sector had only 1-2 stocks (all mid-cap sectors) → green% = 100% or 0%
//        which exceeded the threshold in wrong direction
//
//   With isSectorAligned() returning false due to (a), Gate 2 rejected EVERY stock
//   in the first 30-60 minutes of the day when sector data was accumulating.
//   Result: ZERO 7-gate signals before 10:30 AM. This was the primary cause
//   of the "scanner shows signals only after 1-2 PM" problem.
//
//   FIX: Return true (allow trade) when sector data is insufficient/unknown.
//        Only reject when we have CONFIRMED data showing sector is against trade.
//        Add minimum stock count check (need >= 3 stocks to determine sector strength).
// ============================================================
package com.trading.sector.service;

import com.trading.domain.Candle;
import com.trading.events.CandleCompleteEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SectorStrengthService — Real-time sector classification and strength tracking.
 *
 * SECTOR DATA FLOW:
 *   5m candle → update stock's % change → recalculate sector metrics
 *   Metrics: changePercent, greenPct, relativeStrength, alignedBullish/Bearish
 *
 * ALIGNMENT LOGIC:
 *   alignedBullish = greenPct >= min-aligned-pct AND sectorRS >= min-rs
 *   alignedBearish = greenPct <= (100 - min-aligned-pct) AND sectorRS <= (2 - min-rs)
 *
 * GLOBAL OVERRIDE FIX:
 *   isSectorAligned() returns true (allow) when data is insufficient.
 *   Only blocks when we have enough stocks AND confirmed opposite alignment.
 */
@Service
@Slf4j
public class SectorStrengthService {

    @Value("${sector.min-aligned-pct:55}")
    private double minAlignedPct;

    @Value("${sector.min-relative-strength:0.8}")
    private double minRelativeStrength;

    /** Minimum number of tracked stocks to make alignment determination */
    private static final int MIN_STOCKS_FOR_ALIGNMENT = 3;

    // ── Data structures ───────────────────────────────────────────────────────

    public record SectorData(
            String  name,
            double  changePercent,
            boolean alignedBullish,
            boolean alignedBearish,
            boolean isTopSector,
            boolean isBottomSector,
            double  relativeStrength,
            int     totalStocks,
            int     greenStocks,
            int     redStocks,
            double  greenPct
    ) {
        public static SectorData empty(String name) {
            return new SectorData(name, 0, false, false, false, false, 1.0, 0, 0, 0, 50.0);
        }
    }

    // symbol → open price (captured at 9:15 or first candle)
    private final Map<String, Double> openPrices    = new ConcurrentHashMap<>();
    // symbol → latest close price
    private final Map<String, Double> latestPrices  = new ConcurrentHashMap<>();
    // symbol → sector name
    private final Map<String, String> symbolSector  = new ConcurrentHashMap<>();
    // sector → computed SectorData
    private final Map<String, SectorData> sectorData = new ConcurrentHashMap<>();

    // ── Candle listener ───────────────────────────────────────────────────────

    @EventListener
    @Async("tradingExecutor")
    public void onCandle(CandleCompleteEvent event) {
        if (!"5minute".equals(event.getCandle().getTimeframe())) return;
        if (!event.getCandle().isComplete()) return;

        Candle c   = event.getCandle();
        String sym = c.getTradingSymbol();
        double cl  = c.getClose().doubleValue();
        double op  = c.getOpen().doubleValue();

        // Capture open price on first candle
        openPrices.putIfAbsent(sym, op);
        latestPrices.put(sym, cl);

        // Recalculate sector for this symbol's sector
        String sector = symbolSector.get(sym);
        if (sector != null) {
            recalculateSector(sector);
        }
    }

    // ── Sector calculation ────────────────────────────────────────────────────

    private void recalculateSector(String sectorName) {
        // Find all stocks in this sector
        List<String> stocks = new ArrayList<>();
        for (Map.Entry<String, String> e : symbolSector.entrySet()) {
            if (sectorName.equals(e.getValue())) stocks.add(e.getKey());
        }

        if (stocks.isEmpty()) return;

        int    green     = 0;
        int    red       = 0;
        double sumChange = 0;
        int    counted   = 0;

        for (String sym : stocks) {
            Double open  = openPrices.get(sym);
            Double close = latestPrices.get(sym);
            if (open == null || close == null || open == 0) continue;
            double chg = (close - open) / open * 100;
            sumChange += chg;
            if (chg > 0) green++;
            else         red++;
            counted++;
        }

        if (counted == 0) return;

        double avgChange = sumChange / counted;
        double greenPct  = counted > 0 ? (double) green / counted * 100 : 50.0;
        double rs        = 1.0 + avgChange / 10.0; // normalized RS

        // Need minimum stocks for reliable alignment
        boolean hasSufficientData = counted >= MIN_STOCKS_FOR_ALIGNMENT;
        boolean alignedBull = hasSufficientData
                && greenPct >= minAlignedPct
                && rs >= minRelativeStrength;
        boolean alignedBear = hasSufficientData
                && greenPct <= (100 - minAlignedPct)
                && rs <= (2 - minRelativeStrength);

        sectorData.put(sectorName, new SectorData(
                sectorName, avgChange, alignedBull, alignedBear,
                false, false, rs,
                counted, green, red, greenPct
        ));
    }

    /** Rank sectors after each candle cycle and mark top/bottom */
    @Scheduled(fixedDelay = 60000) // every 60 seconds
    public void rankSectors() {
        if (sectorData.isEmpty()) return;

        List<Map.Entry<String, SectorData>> sorted = new ArrayList<>(sectorData.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue().changePercent(),
                a.getValue().changePercent()));

        Set<String> top    = new HashSet<>();
        Set<String> bottom = new HashSet<>();
        if (!sorted.isEmpty()) top.add(sorted.get(0).getKey());
        if (sorted.size() > 1) top.add(sorted.get(1).getKey());
        if (sorted.size() > 1) bottom.add(sorted.get(sorted.size() - 1).getKey());
        if (sorted.size() > 2) bottom.add(sorted.get(sorted.size() - 2).getKey());

        // Rebuild with top/bottom flags
        Map<String, SectorData> updated = new ConcurrentHashMap<>();
        for (Map.Entry<String, SectorData> e : sectorData.entrySet()) {
            SectorData d = e.getValue();
            updated.put(e.getKey(), new SectorData(
                    d.name(), d.changePercent(), d.alignedBullish(), d.alignedBearish(),
                    top.contains(e.getKey()), bottom.contains(e.getKey()),
                    d.relativeStrength(), d.totalStocks(), d.greenStocks(),
                    d.redStocks(), d.greenPct()
            ));
        }
        sectorData.putAll(updated);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Get sector data for a sector name.
     * Returns SectorData.empty() if not found — never returns null.
     */
    public SectorData getSector(String sectorName) {
        if (sectorName == null) return SectorData.empty("UNKNOWN");
        return sectorData.getOrDefault(sectorName, SectorData.empty(sectorName));
    }

    /**
     * GLOBAL OVERRIDE FIX:
     * Is the sector aligned with the intended trade direction?
     *
     * Returns TRUE (allow) when:
     *   - Sector data not available yet (startup/insufficient stocks)
     *   - Sector is neutral (not confirmed against trade)
     *
     * Returns FALSE (block) ONLY when:
     *   - We have sufficient stock data (>= 3 stocks)
     *   - AND sector is confirmed opposite to trade direction
     *
     * This prevents the "Gate 2 blocks everything before 10:30" issue.
     */
    public boolean isSectorAligned(String symbol, boolean forLong) {
        String sector = symbolSector.get(symbol);
        if (sector == null) {
            // Symbol not mapped → allow (don't block on missing data)
            return true;
        }

        SectorData data = sectorData.get(sector);
        if (data == null || data.totalStocks() < MIN_STOCKS_FOR_ALIGNMENT) {
            // Insufficient data → allow (don't block on startup)
            log.debug("[SECTOR] {} sector='{}' insufficient data ({} stocks) → ALLOW",
                    symbol, sector, data == null ? 0 : data.totalStocks());
            return true;
        }

        if (forLong) {
            // For LONG: blocked only if sector is CONFIRMED bearish
            // Not blocked if neutral (neither bull nor bear)
            boolean blocked = data.alignedBearish();
            if (blocked) {
                log.debug("[SECTOR] {} sector='{}' confirmed BEARISH → block LONG trade",
                        symbol, sector);
            }
            return !blocked;
        } else {
            // For SHORT: blocked only if sector is CONFIRMED bullish
            boolean blocked = data.alignedBullish();
            if (blocked) {
                log.debug("[SECTOR] {} sector='{}' confirmed BULLISH → block SHORT trade",
                        symbol, sector);
            }
            return !blocked;
        }
    }

    /**
     * Register a symbol → sector mapping.
     * Called by SectorClassificationService during instrument cache build.
     */
    public void registerSymbol(String symbol, String sector) {
        if (symbol != null && sector != null) {
            symbolSector.put(symbol.toUpperCase(), sector);
        }
    }

    /**
     * Build sector mappings from existing symbolSector map.
     * Called by SectorClassificationService.
     */
    public void buildFromInstruments(List<com.zerodhatech.models.Instrument> instruments) {
        // Instruments don't have sector info — this is populated by
        // SectorClassificationService which maps NSE symbols to sectors.
        // No action needed here.
    }

    public List<SectorData> getAllSectors() {
        return new ArrayList<>(sectorData.values());
    }

    @Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyReset() {
        openPrices.clear();
        latestPrices.clear();
        sectorData.clear();
        log.info("[SECTOR] Daily reset — {} symbols mapped to sectors",
                symbolSector.size());
    }
}