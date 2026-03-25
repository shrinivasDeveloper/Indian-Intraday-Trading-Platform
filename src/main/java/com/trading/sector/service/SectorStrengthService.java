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
import java.math.MathContext;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gate 2 — Sector Alignment + Ranking.
 *
 * Every tick: update live price per symbol.
 * Every 5min: recalculate all sector stats + top/bottom ranking.
 *
 * Gate 2 (backward compat): isSectorAligned(symbol, forLong)
 * New for strategies: isTopSector, isBottomSector, changePercent
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SectorStrengthService {

    private final SectorClassificationService sectorService;
    private final InstrumentCacheService      instrumentCache;
    private final MarketDirectionService      marketDirection;

    private final Map<String, BigDecimal> lastPrices  = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> openPrices  = new ConcurrentHashMap<>();
    private final Map<String, SectorData> sectorCache = new ConcurrentHashMap<>();

    // ── SectorData ────────────────────────────────────────────────────────

    public record SectorData(
            String  name,
            double  relativeStrength,
            double  changePercent,
            double  greenPct,
            boolean alignedBullish,
            boolean alignedBearish,
            boolean isTopSector,
            boolean isBottomSector,
            int     totalStocks,
            int     greenStocks,
            int     redStocks
    ) {
        /** Gate 2 check — backward compatible with SevenGateScannerService */
        public boolean isAligned(boolean forLong) {
            return forLong ? alignedBullish : alignedBearish;
        }
    }

    // ── Tick handler ──────────────────────────────────────────────────────

    @EventListener
    @Async("tradingExecutor")
    public void onTick(TickReceivedEvent tick) {
        String sym = tick.getTradingSymbol();
        lastPrices.put(sym, tick.getLastTradedPrice());
        openPrices.computeIfAbsent(sym, k -> tick.getOpenPrice());
    }

    // ── Recalculate every 5min ────────────────────────────────────────────

    @Scheduled(cron = "0 */5 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void recalculate() {
        try {
            double niftyChg = getNiftyChange();

            // Compute raw stats per sector
            // [0]=avgChg [1]=greenPct [2]=rs [3]=green [4]=red [5]=total
            Map<String, double[]> raw = new LinkedHashMap<>();

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
                            .divide(open, MathContext.DECIMAL32)
                            .multiply(BigDecimal.valueOf(100)).doubleValue();
                    totalChg += chg;
                    total++;
                    if (chg > 0) green++; else red++;
                }

                if (total == 0) continue;
                double avgChg   = totalChg / total;
                double greenPct = (double) green / total * 100;
                double rs       = niftyChg != 0 ? avgChg / Math.abs(niftyChg) : 1.0;
                raw.put(sector, new double[]{avgChg, greenPct, rs, green, red, total});
            }

            // Rank sectors: top 2 = strongest, bottom 2 = weakest
            List<Map.Entry<String, double[]>> ranked = new ArrayList<>(raw.entrySet());
            ranked.sort((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]));

            Set<String> top    = new HashSet<>();
            Set<String> bottom = new HashSet<>();
            for (int i = 0; i < Math.min(2, ranked.size()); i++)
                top.add(ranked.get(i).getKey());
            for (int i = Math.max(0, ranked.size() - 2); i < ranked.size(); i++)
                bottom.add(ranked.get(i).getKey());

            // Build SectorData
            for (Map.Entry<String, double[]> e : raw.entrySet()) {
                String   s = e.getKey();
                double[] d = e.getValue();
                boolean bull = d[1] >= 60 && d[2] > 1.0;
                boolean bear = (100 - d[1]) >= 60 && d[2] < 1.0;
                sectorCache.put(s, new SectorData(
                        s, d[2], d[0], d[1],
                        bull, bear,
                        top.contains(s), bottom.contains(s),
                        (int) d[5], (int) d[3], (int) d[4]));
            }

        } catch (Exception e) {
            log.error("Sector recalculation failed: {}", e.getMessage());
        }
    }

    // ── Public API ────────────────────────────────────────────────────────

    /** Get SectorData by sector name. Safe default if not yet calculated. */
    public SectorData getSector(String sectorName) {
        return sectorCache.getOrDefault(sectorName,
                new SectorData(sectorName, 1.0, 0.0, 50.0,
                        false, false, false, false, 0, 0, 0));
    }

    /** Gate 2 check — used by SevenGateScannerService (backward compatible) */
    public boolean isSectorAligned(String symbol, boolean forLong) {
        String     sectorName = sectorService.getSector(symbol);
        SectorData data       = getSector(sectorName);
        return data.isAligned(forLong);
    }

    /** All sectors — used by DashboardController */
    public Map<String, SectorData> getAllSectors() {
        return Collections.unmodifiableMap(sectorCache);
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private double getNiftyChange() {
        double chg = getChangePercent("NIFTY 50");
        if (chg == 0) chg = getChangePercent("NIFTY");
        return chg;
    }

    private double getChangePercent(String symbol) {
        BigDecimal last = lastPrices.get(symbol);
        BigDecimal open = openPrices.get(symbol);
        if (last == null || open == null || open.compareTo(BigDecimal.ZERO) == 0) return 0;
        return last.subtract(open).divide(open, MathContext.DECIMAL32)
                .multiply(BigDecimal.valueOf(100)).doubleValue();
    }
}