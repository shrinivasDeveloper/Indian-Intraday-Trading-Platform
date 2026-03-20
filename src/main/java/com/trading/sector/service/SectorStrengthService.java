package com.trading.sector.service;

import com.trading.events.TickReceivedEvent;
import com.trading.marketdata.service.InstrumentCacheService;
import com.trading.regime.service.MarketDirectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gate 2 — Sector Alignment.
 *
 * Every tick updates the live price for each symbol.
 * Every 5 minutes recalculates sector strength.
 *
 * Sector passes if:
 *   1. At least 60% of stocks in sector are green (uptrend) or red (downtrend)
 *   2. Sector relative strength vs Nifty > 1.0 (uptrend) or < 1.0 (downtrend)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SectorStrengthService {

    private final SectorClassificationService sectorService;
    private final InstrumentCacheService      instrumentCache;
    private final MarketDirectionService      marketDirection;

    // symbol → last price
    private final Map<String, BigDecimal> lastPrices  = new ConcurrentHashMap<>();
    // symbol → open price (for change% calculation)
    private final Map<String, BigDecimal> openPrices  = new ConcurrentHashMap<>();
    // sector → SectorData
    private final Map<String, SectorData> sectorCache = new ConcurrentHashMap<>();

    public record SectorData(
            String  name,
            double  relativeStrength,
            double  changePercent,
            double  greenPct,          // % of stocks up today
            boolean alignedBullish,
            boolean alignedBearish,
            int     totalStocks,
            int     greenStocks,
            int     redStocks
    ) {
        public boolean isAligned(boolean forLong) {
            return forLong ? alignedBullish : alignedBearish;
        }
    }

    @EventListener
    @Async("tradingExecutor")
    public void onTick(TickReceivedEvent tick) {
        String symbol = tick.getTradingSymbol();
        lastPrices.put(symbol, tick.getLastTradedPrice());
        // Store open price once per day
        openPrices.computeIfAbsent(symbol, k -> tick.getOpenPrice());
    }

    // Recalculate every 5 minutes during market hours
    @Scheduled(cron = "0 */5 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void recalculate() {
        try {
            MarketDirectionService.Direction dir = marketDirection.getCurrentDirection().direction();
            boolean forLong  = dir == MarketDirectionService.Direction.BULLISH;
            boolean forShort = dir == MarketDirectionService.Direction.BEARISH;

            // Get Nifty change% as benchmark
            double niftyChg = getChangePercent("NIFTY 50");
            if (niftyChg == 0) niftyChg = getChangePercent("NIFTY");

            for (String sector : sectorService.getAllSectorNames()) {
                List<String> symbols = sectorService.getSymbolsInSector(sector);
                if (symbols.isEmpty()) continue;

                int green = 0, red = 0, total = 0;
                double totalChg = 0;

                for (String sym : symbols) {
                    BigDecimal last = lastPrices.get(sym);
                    BigDecimal open = openPrices.get(sym);
                    if (last == null || open == null
                            || open.compareTo(BigDecimal.ZERO) == 0) continue;

                    double chg = last.subtract(open)
                            .divide(open, java.math.MathContext.DECIMAL32)
                            .multiply(BigDecimal.valueOf(100)).doubleValue();

                    totalChg += chg;
                    total++;
                    if (chg > 0) green++;
                    else red++;
                }

                if (total == 0) continue;

                double avgChg     = totalChg / total;
                double greenPct   = (double) green / total * 100;
                double rs         = niftyChg != 0 ? avgChg / niftyChg : 1.0;

                boolean alignBull = greenPct >= 60 && rs > 1.0;
                boolean alignBear = (100 - greenPct) >= 60 && rs < 1.0;

                sectorCache.put(sector, new SectorData(
                        sector, rs, avgChg, greenPct,
                        alignBull, alignBear, total, green, red));
            }
        } catch (Exception e) {
            log.error("Sector recalculation failed: {}", e.getMessage());
        }
    }

    public SectorData getSector(String sectorName) {
        return sectorCache.getOrDefault(sectorName,
                new SectorData(sectorName, 1.0, 0.0, 50.0,
                        false, false, 0, 0, 0));
    }

    public boolean isSectorAligned(String symbol, boolean forLong) {
        String sectorName = sectorService.getSector(symbol);
        SectorData data   = getSector(sectorName);
        return data.isAligned(forLong);
    }

    public Map<String, SectorData> getAllSectors() {
        return Collections.unmodifiableMap(sectorCache);
    }

    private double getChangePercent(String symbol) {
        BigDecimal last = lastPrices.get(symbol);
        BigDecimal open = openPrices.get(symbol);
        if (last == null || open == null
                || open.compareTo(BigDecimal.ZERO) == 0) return 0;
        return last.subtract(open)
                .divide(open, java.math.MathContext.DECIMAL32)
                .multiply(BigDecimal.valueOf(100)).doubleValue();
    }
}