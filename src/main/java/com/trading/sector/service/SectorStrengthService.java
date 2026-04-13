package com.trading.sector.service;

import com.trading.domain.Candle;
import com.trading.events.CandleCompleteEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SectorStrengthService — intraday sector strength tracking and direction engine.
 *
 * ADDED: getSectorDirection(sectorName) — sector-based 15M direction.
 *   Used by SmartChannelPullbackStrategy Gate 1 (replaces MarketDirectionService).
 *   Derives trade direction (LONG/SHORT/NEUTRAL) purely from sector metrics.
 *   No global market index reference. No VIX. No EMA on Nifty.
 *
 * DIRECTION ALGORITHM (15M sector-based):
 *   BULLISH when ALL of:
 *     changePercent  ≥ +BULL_THRESHOLD (+0.30%)   sector moving up
 *     greenPct       ≥ GREEN_BULL_MIN  (55%)       majority of stocks green
 *     relativeStrength ≥ RS_MIN        (0.0)       at least neutral RS
 *
 *   BEARISH when ALL of:
 *     changePercent  ≤ -BEAR_THRESHOLD (-0.30%)   sector moving down
 *     greenPct       ≤ GREEN_BEAR_MAX  (45%)       majority of stocks red
 *     relativeStrength ≤ RS_BEAR_MAX   (0.0)       at least neutral negative RS
 *
 *   NEUTRAL otherwise → strategy skips this symbol (no trade bias)
 *
 *   Confidence score (0.0–1.0) is also returned:
 *     Based on magnitude of changePercent and greenPct deviation from threshold.
 *     Used for logging / future scoring weight.
 *
 * All existing methods are UNCHANGED. Only getSectorDirection() is new.
 */
@Service
@Slf4j
public class SectorStrengthService {

    // ── Thresholds ────────────────────────────────────────────────────────

    private static final double BULL_CHG_THRESHOLD = 0.30;   // +0.30% sector change → bullish
    private static final double BEAR_CHG_THRESHOLD = 0.30;   // -0.30% sector change → bearish
    private static final double GREEN_BULL_MIN      = 55.0;  // ≥55% stocks green → bullish
    private static final double GREEN_BEAR_MAX      = 45.0;  // ≤45% stocks green → bearish
    private static final double RS_NEUTRAL          = 0.0;   // RS cutoff

    // ── Internal state ────────────────────────────────────────────────────

    /** Per-symbol open price (first candle of session) for day-change tracking */
    private final Map<String, Double>  openPrices      = new ConcurrentHashMap<>();
    /** Per-symbol latest close price */
    private final Map<String, Double>  latestPrices    = new ConcurrentHashMap<>();
    /** Sector → SectorData (updated on every 5M candle batch) */
    private final Map<String, SectorData> sectorMap    = new ConcurrentHashMap<>();
    /** Sector → symbols — populated by SectorClassificationService.registerSymbol() */
    private final Map<String, List<String>> symbolsBySector = new ConcurrentHashMap<>();
    /** Epoch of last sector recalculation */
    private final AtomicLong lastCalcEpoch = new AtomicLong(0);

    // ── Public direction enum ─────────────────────────────────────────────

    public enum SectorTrendDirection { BULLISH, BEARISH, NEUTRAL }

    /**
     * Result of sector 15M direction evaluation.
     * Used by SmartChannelPullbackStrategy Gate 1 to:
     *   (a) determine whether to trade this symbol at all
     *   (b) set isBullMarket for all downstream gates
     */
    public record SectorDirectionResult(
            String              sectorName,
            SectorTrendDirection direction,
            boolean             isBull,         // convenience: true=LONG, false=SHORT
            double              changePercent,
            double              greenPct,
            double              relativeStrength,
            double              confidence,     // 0.0–1.0 strength of the signal
            String              reason
    ) {
        public boolean isTradeable() { return direction != SectorTrendDirection.NEUTRAL; }
    }

    // ── Core direction logic ──────────────────────────────────────────────

    /**
     * Derive trade direction from sector 15M data.
     *
     * This is the NEW Gate 1 for SmartChannelPullbackStrategy.
     * Replaces MarketDirectionService.getCurrentDirection() entirely.
     * No Nifty index reference. No VIX. Pure sector metrics only.
     *
     * @param sectorName  from SectorClassificationService.getSector(symbol)
     * @return SectorDirectionResult with direction and confidence
     */
    public SectorDirectionResult getSectorDirection(String sectorName) {
        SectorData sd = sectorMap.get(sectorName);

        if (sd == null || sd.totalStocks() == 0) {
            return new SectorDirectionResult(sectorName,
                    SectorTrendDirection.NEUTRAL, false,
                    0, 0, 0, 0,
                    "No sector data yet for: " + sectorName);
        }

        double chg = sd.changePercent();
        double gp  = sd.greenPct();
        double rs  = sd.relativeStrength();

        // ── BULLISH: sector trending up with stock confirmation ───────────
        if (chg >= BULL_CHG_THRESHOLD
                && gp  >= GREEN_BULL_MIN
                && rs  >= RS_NEUTRAL) {

            double conf = computeConfidence(chg, BULL_CHG_THRESHOLD, gp, GREEN_BULL_MIN, 100.0);
            String reason = String.format(
                    "BULLISH: change=+%.2f%% greenPct=%.0f%% RS=%.2f",
                    chg, gp, rs);
            log.debug("[SECTOR-DIR] {} → BULLISH (conf={}) {}", sectorName,
                    String.format("%.2f", conf), reason);
            return new SectorDirectionResult(sectorName,
                    SectorTrendDirection.BULLISH, true,
                    chg, gp, rs, conf, reason);
        }

        // ── BEARISH: sector trending down with stock confirmation ─────────
        if (chg <= -BEAR_CHG_THRESHOLD
                && gp  <= GREEN_BEAR_MAX
                && rs  <= RS_NEUTRAL) {

            double conf = computeConfidence(-chg, BEAR_CHG_THRESHOLD, 100.0 - gp, 100.0 - GREEN_BEAR_MAX, 100.0);
            String reason = String.format(
                    "BEARISH: change=%.2f%% greenPct=%.0f%% RS=%.2f",
                    chg, gp, rs);
            log.debug("[SECTOR-DIR] {} → BEARISH (conf={}) {}", sectorName,
                    String.format("%.2f", conf), reason);
            return new SectorDirectionResult(sectorName,
                    SectorTrendDirection.BEARISH, false,
                    chg, gp, rs, conf, reason);
        }

        // ── NEUTRAL: sector is mixed or insufficient momentum ─────────────
        String reason = String.format(
                "NEUTRAL: change=%.2f%% (need ≥±%.2f%%) greenPct=%.0f%% RS=%.2f",
                chg, BULL_CHG_THRESHOLD, gp, rs);
        log.trace("[SECTOR-DIR] {} → NEUTRAL: {}", sectorName, reason);
        return new SectorDirectionResult(sectorName,
                SectorTrendDirection.NEUTRAL, false,
                chg, gp, rs, 0.0, reason);
    }

    /**
     * Compute signal confidence as a 0–1 score.
     * Measures how far the values are ABOVE their respective thresholds.
     */
    private double computeConfidence(double chgVal, double chgMin,
                                     double gpVal, double gpMin, double gpMax) {
        double chgScore = Math.min(1.0, (chgVal - chgMin) / (chgMin * 2));
        double gpScore  = Math.min(1.0, (gpVal  - gpMin)  / (gpMax - gpMin));
        return Math.min(1.0, (chgScore + gpScore) / 2.0);
    }

    // ── Existing API (UNCHANGED) ──────────────────────────────────────────

    /**
     * Called by SectorClassificationService for each instrument during build.
     * Populates the sector → symbols map used by recalculateSectors().
     * This is the bridge that SectorClassificationService already calls at line 147.
     */
    public void registerSymbol(String sectorName, String symbol) {
        symbolsBySector.computeIfAbsent(sectorName,
                k -> Collections.synchronizedList(new ArrayList<>())).add(symbol);
    }

    /**
     * Called by PaperTradeManagementService and TradeManagementService
     * to check if a sector supports continued holding of a trade.
     *
     * @param sectorName the sector of the stock being managed
     * @param isBull     true = LONG trade (need bullish sector), false = SHORT (need bearish)
     * @return true if the sector direction still aligns with the trade direction
     */
    public boolean isSectorAligned(String sectorName, boolean isBull) {
        SectorData sd = sectorMap.get(sectorName);
        if (sd == null) return true; // no data yet — give benefit of the doubt
        if (isBull)  return sd.changePercent() >= -0.10; // sector hasn't turned sharply red
        else         return sd.changePercent() <=  0.10; // sector hasn't turned sharply green
    }

    public SectorData getSector(String sectorName) {
        return sectorMap.getOrDefault(sectorName, emptySector(sectorName));
    }

    public List<SectorData> getAllSectors() {
        return new ArrayList<>(sectorMap.values());
    }

    public List<SectorData> getTopBullishSectors(int n) {
        return sectorMap.values().stream()
                .filter(SectorData::alignedBullish)
                .sorted(Comparator.comparingDouble(SectorData::changePercent).reversed())
                .limit(n)
                .toList();
    }

    public List<SectorData> getTopBearishSectors(int n) {
        return sectorMap.values().stream()
                .filter(SectorData::alignedBearish)
                .sorted(Comparator.comparingDouble(SectorData::changePercent))
                .limit(n)
                .toList();
    }

    // ── Candle event — price tracking ─────────────────────────────────────

    @EventListener
    @Async("tradingExecutor")
    public void onCandle(CandleCompleteEvent event) {
        Candle c = event.getCandle();
        if (!"5minute".equals(c.getTimeframe()) || !c.isComplete()) return;

        String sym = c.getTradingSymbol();
        double close = c.getClose().doubleValue();
        double open  = c.getOpen().doubleValue();

        // Track open (first candle of day)
        openPrices.putIfAbsent(sym, open);
        latestPrices.put(sym, close);

        // Recalculate sectors every candle batch (rate-limited to 4s to avoid thrash)
        long now = System.currentTimeMillis();
        if (now - lastCalcEpoch.get() > 4_000) {
            if (lastCalcEpoch.compareAndSet(lastCalcEpoch.get(), now)) {
                recalculateSectors();
            }
        }
    }

    // ── Sector recalculation ──────────────────────────────────────────────

    private void recalculateSectors() {
        // Use internal symbolsBySector map — populated by registerSymbol()
        // (called from SectorClassificationService during instrument build)
        Map<String, List<String>> bySector = symbolsBySector;
        if (bySector.isEmpty()) return;

        // Reference market change (all tracked symbols avg) for RS
        double mktAvgChg = computeMarketAvgChange();

        bySector.forEach((sectorName, symbols) -> {
            if (symbols.isEmpty()) return;

            int    green = 0, red = 0;
            double totalChg = 0;
            int    count = 0;

            for (String sym : symbols) {
                Double openP = openPrices.get(sym);
                Double closeP = latestPrices.get(sym);
                if (openP == null || closeP == null || openP == 0) continue;

                double chg = (closeP - openP) / openP * 100;
                totalChg += chg;
                count++;
                if (chg > 0) green++;
                else if (chg < 0) red++;
            }

            if (count == 0) return;

            double avgChg  = totalChg / count;
            double gPct    = (double) green / count * 100;
            double rs      = mktAvgChg != 0 ? avgChg / Math.abs(mktAvgChg) : 0;

            boolean bull   = avgChg >= BULL_CHG_THRESHOLD && gPct >= GREEN_BULL_MIN;
            boolean bear   = avgChg <= -BEAR_CHG_THRESHOLD && gPct <= GREEN_BEAR_MAX;

            SectorData prev = sectorMap.get(sectorName);
            boolean wasTop  = prev != null && prev.isTopSector();
            boolean wasBot  = prev != null && prev.isBottomSector();

            sectorMap.put(sectorName, new SectorData(
                    sectorName, avgChg, gPct, green, red,
                    count, rs, bull, bear,
                    wasTop,  // updated in rankSectors()
                    wasBot
            ));
        });

        rankSectors();
    }

    private void rankSectors() {
        List<SectorData> sorted = new ArrayList<>(sectorMap.values());
        sorted.sort(Comparator.comparingDouble(SectorData::changePercent).reversed());
        int n = sorted.size();
        for (int i = 0; i < n; i++) {
            SectorData s = sorted.get(i);
            boolean top = i < Math.max(1, n / 4);
            boolean bot = i >= n - Math.max(1, n / 4);
            sectorMap.put(s.name(), new SectorData(
                    s.name(), s.changePercent(), s.greenPct(),
                    s.greenStocks(), s.redStocks(), s.totalStocks(),
                    s.relativeStrength(), s.alignedBullish(), s.alignedBearish(),
                    top, bot
            ));
        }
    }

    private double computeMarketAvgChange() {
        if (latestPrices.isEmpty()) return 0;
        double total = 0; int count = 0;
        for (Map.Entry<String, Double> e : latestPrices.entrySet()) {
            Double op = openPrices.get(e.getKey());
            if (op != null && op > 0) { total += (e.getValue() - op) / op * 100; count++; }
        }
        return count > 0 ? total / count : 0;
    }

    // ── Initialisation (called by InstrumentCacheService after build) ──────

    public void buildFromInstruments(List<com.zerodhatech.models.Instrument> instruments) {
        // SectorClassificationService has already called registerSymbol() for each
        // instrument by the time this is invoked. Just log and trigger first calc.
        log.info("[SECTOR] Sector strength engine ready — {} sectors registered, {} symbols",
                symbolsBySector.size(),
                symbolsBySector.values().stream().mapToInt(List::size).sum());
        if (!symbolsBySector.isEmpty()) {
            recalculateSectors();
        }
    }

    // ── Daily reset ───────────────────────────────────────────────────────

    @Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyReset() {
        openPrices.clear();
        latestPrices.clear();
        sectorMap.clear();
        lastCalcEpoch.set(0);
        // NOTE: symbolsBySector is NOT cleared — sector-symbol mapping
        // is built once at startup from instruments and doesn't change daily.
        log.info("[SECTOR] Daily reset complete — prices cleared, sector mappings retained");
    }

    // ── Data record (UNCHANGED) ───────────────────────────────────────────

    public record SectorData(
            String  name,
            double  changePercent,
            double  greenPct,
            int     greenStocks,
            int     redStocks,
            int     totalStocks,
            double  relativeStrength,
            boolean alignedBullish,
            boolean alignedBearish,
            boolean isTopSector,
            boolean isBottomSector
    ) {}

    private SectorData emptySector(String name) {
        return new SectorData(name, 0, 50, 0, 0, 0, 0, false, false, false, false);
    }
}