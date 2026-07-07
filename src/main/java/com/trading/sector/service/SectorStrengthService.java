package com.trading.sector.service;

import com.trading.domain.Candle;
import com.trading.events.CandleCompleteEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SectorStrengthService - intraday sector strength tracking and direction engine.
 *
 * BUGS FIXED vs previous version:
 *
 *   BUG 1 (PRIMARY - sectors always show 0):
 *     recalculateSectors() rate-limiter was broken:
 *       if (lastCalcEpoch.compareAndSet(lastCalcEpoch.get(), now))
 *     lastCalcEpoch.get() is called TWICE - once for the expected value, once inside
 *     compareAndSet. Another thread can change it between the two reads, making the
 *     CAS always fail in practice on a busy system. Sectors NEVER recalculated from
 *     live ticks, so every sector permanently showed changePercent=0, totalStocks=0.
 *     FIX: Read the value once into a local variable and use that for both checks:
 *       long prev = lastCalcEpoch.get();
 *       if (now - prev > 4_000 && lastCalcEpoch.compareAndSet(prev, now))
 *
 *   BUG 2 (Gate 2 silently disabled):
 *     isSectorAligned(String sectorName, boolean isBull) was called from
 *     TradeManagementService and PaperTradeManagementService with `symbol` (e.g. "RELIANCE")
 *     instead of `sectorName` (e.g. "Banking & Finance"). Since sectorMap is keyed by
 *     sector name, sectorMap.get("RELIANCE") always returned null -> always returned true
 *     -> Gate 2 alignment check was completely disabled silently.
 *     FIX: Added isSectorAlignedForSymbol(String symbol, boolean isBull) overload that
 *     resolves the sector internally via symbolSector map. Callers should use this.
 *     The original isSectorAligned(sectorName, isBull) is preserved for direct sector lookups.
 *
 *   BUG 3 (startup recalculate wipes sectors):
 *     buildFromInstruments() called recalculateSectors() immediately at startup when
 *     latestPrices was empty. This produced sectors with count=0 and wrote them to
 *     sectorMap. Because the rate-limiter was also broken (Bug 1), these empty entries
 *     persisted all day.
 *     FIX: buildFromInstruments() no longer calls recalculateSectors(). Sector data
 *     will be populated naturally once the first batch of 5M candles arrives.
 *     Also: recalculateSectors() now skips sectors where no price data exists yet
 *     (count == 0) instead of writing empty SectorData records.
 *
 *   BUG 4 (NEW - mid-day restart shows wrong sector %, this session's fix):
 *     openPrices.putIfAbsent(sym, open) inside onCandle() captures whichever 5-minute
 *     candle this service instance happens to see FIRST as the symbol's "day open"
 *     reference price. openPrices was pure in-memory (ConcurrentHashMap), with zero
 *     persistence anywhere in this file.
 *       - Restart BEFORE market open: openPrices starts empty, the genuinely first
 *         candle of the day (9:15-9:20 AM) is correctly captured as day-open. Correct.
 *       - Restart MID-DAY (e.g. 11:30 AM): openPrices ALSO starts empty, but the real
 *         market open (9:15 AM) already happened hours ago. The first candle THIS NEW
 *         INSTANCE sees (e.g. 11:35 AM) gets wrongly captured as "day open" via
 *         putIfAbsent, even though it isn't. Every changePercent computed afterward
 *         measures change-since-restart instead of change-since-actual-market-open -
 *         a completely different, wrong number. This is the exact symptom reported:
 *         "before market deploy = okay, mid-day deploy = different calculation."
 *     FIX: Added JdbcTemplate-backed persistence for openPrices. Every NEWLY-captured
 *     open price (i.e., where putIfAbsent genuinely inserted, not a no-op) is written
 *     to sector_open_prices (symbol, trade_date, open_price). On startup
 *     (ApplicationReadyEvent, fires on every restart including mid-day),
 *     reconcileOpenPricesFromDatabase() pre-loads today's already-known opens BEFORE
 *     any live candle can incorrectly overwrite them - since openPrices is then
 *     already populated for those symbols, the live onCandle()'s putIfAbsent
 *     correctly becomes a no-op instead of capturing a wrong restart-time price.
 *     All DB operations are wrapped in try/catch - a DB hiccup never blocks live
 *     sector tracking, it only loses the restart-recovery safety net for that run.
 *
 *   ADDED: getSectorDirection(sectorName) - unchanged from previous version.
 *   ADDED: symbolSector map for reverse lookup (symbol -> sector name).
 *          Populated by registerSymbol(), enables isSectorAlignedForSymbol().
 */
@Service
@Slf4j
public class SectorStrengthService {

    // -- Thresholds --------------------------------------------------------------

    private static final double BULL_CHG_THRESHOLD = 0.30;
    private static final double BEAR_CHG_THRESHOLD = 0.30;
    private static final double GREEN_BULL_MIN      = 55.0;
    private static final double GREEN_BEAR_MAX      = 45.0;
    private static final double RS_NEUTRAL          = 0.0;

    // -- Internal state ----------------------------------------------------------

    private final Map<String, Double>        openPrices      = new ConcurrentHashMap<>();
    private final Map<String, Double>        latestPrices    = new ConcurrentHashMap<>();
    private final Map<String, SectorData>    sectorMap       = new ConcurrentHashMap<>();
    private final Map<String, List<String>>  symbolsBySector = new ConcurrentHashMap<>();

    /**
     * BUG 2 FIX: Reverse lookup - symbol -> sector name.
     * Populated by registerSymbol() so isSectorAlignedForSymbol() works correctly.
     */
    private final Map<String, String>        symbolSector    = new ConcurrentHashMap<>();

    private final AtomicLong lastCalcEpoch = new AtomicLong(0);

    // -- BUG 4 FIX: persistence for openPrices -----------------------------------
    private final JdbcTemplate jdbc;

    public SectorStrengthService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        ensureOpenPricesTableExists();
    }

    private void ensureOpenPricesTableExists() {
        try {
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS sector_open_prices (
                    symbol      VARCHAR(20) NOT NULL,
                    trade_date  DATE        NOT NULL,
                    open_price  DOUBLE      NOT NULL,
                    PRIMARY KEY (symbol, trade_date)
                )
                """);
        } catch (Exception e) {
            log.warn("[SECTOR] Could not create sector_open_prices table - " +
                    "restart-recovery disabled this session, live tracking still " +
                    "works normally: {}", e.getMessage());
        }
    }

    /**
     * BUG 4 FIX: Runs on every application startup (including mid-day restarts).
     * Pre-loads today's already-known day-open prices from the database BEFORE
     * any live candle event can run - this is what prevents onCandle()'s
     * putIfAbsent from wrongly capturing a restart-time price as "day open".
     */
    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOpenPricesFromDatabase() {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT symbol, open_price FROM sector_open_prices WHERE trade_date = ?",
                    LocalDate.now(ZoneId.of("Asia/Kolkata")));
            if (rows.isEmpty()) {
                log.info("[SECTOR] Reconciliation: no day-open prices found in database " +
                        "yet today (normal for a before-market start).");
                return;
            }
            for (Map<String, Object> row : rows) {
                String symbol = (String) row.get("symbol");
                double open   = ((Number) row.get("open_price")).doubleValue();
                openPrices.putIfAbsent(symbol, open);
            }
            log.info("[SECTOR] [OK] Reconciled {} day-open price(s) from database - " +
                    "mid-day restart will compute correct change% from true market " +
                    "open, not from restart time.", rows.size());
        } catch (Exception e) {
            log.warn("[SECTOR] reconcileOpenPricesFromDatabase failed - falling back " +
                            "to capturing day-open from the next live candle, as before this " +
                            "fix existed (correct before market open, wrong if mid-day): {}",
                    e.getMessage());
        }
    }

    private void persistOpenPrice(String symbol, double open) {
        try {
            jdbc.update("""
                INSERT INTO sector_open_prices (symbol, trade_date, open_price)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE open_price = open_price
                """,
                    symbol, LocalDate.now(ZoneId.of("Asia/Kolkata")), open);
        } catch (Exception e) {
            log.debug("[SECTOR] persistOpenPrice failed for {} (non-fatal): {}",
                    symbol, e.getMessage());
        }
    }

    // -- Public direction enum ---------------------------------------------------

    public enum SectorTrendDirection { BULLISH, BEARISH, NEUTRAL }

    public record SectorDirectionResult(
            String              sectorName,
            SectorTrendDirection direction,
            boolean             isBull,
            double              changePercent,
            double              greenPct,
            double              relativeStrength,
            double              confidence,
            String              reason
    ) {
        public boolean isTradeable() { return direction != SectorTrendDirection.NEUTRAL; }
    }

    // -- Core direction logic ----------------------------------------------------

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

        if (chg >= BULL_CHG_THRESHOLD && gp >= GREEN_BULL_MIN && rs >= RS_NEUTRAL) {
            double conf = computeConfidence(chg, BULL_CHG_THRESHOLD, gp, GREEN_BULL_MIN, 100.0);
            String reason = String.format("BULLISH: change=+%.2f%% greenPct=%.0f%% RS=%.2f", chg, gp, rs);
            log.debug("[SECTOR-DIR] {} -> BULLISH (conf={:.2f}) {}", sectorName, conf, reason);
            return new SectorDirectionResult(sectorName,
                    SectorTrendDirection.BULLISH, true, chg, gp, rs, conf, reason);
        }

        if (chg <= -BEAR_CHG_THRESHOLD && gp <= GREEN_BEAR_MAX && rs <= RS_NEUTRAL) {
            double conf = computeConfidence(-chg, BEAR_CHG_THRESHOLD, 100.0 - gp, 100.0 - GREEN_BEAR_MAX, 100.0);
            String reason = String.format("BEARISH: change=%.2f%% greenPct=%.0f%% RS=%.2f", chg, gp, rs);
            log.debug("[SECTOR-DIR] {} -> BEARISH (conf={:.2f}) {}", sectorName, conf, reason);
            return new SectorDirectionResult(sectorName,
                    SectorTrendDirection.BEARISH, false, chg, gp, rs, conf, reason);
        }

        String reason = String.format(
                "NEUTRAL: change=%.2f%% (need >=+/-%.2f%%) greenPct=%.0f%% RS=%.2f",
                chg, BULL_CHG_THRESHOLD, gp, rs);
        return new SectorDirectionResult(sectorName,
                SectorTrendDirection.NEUTRAL, false, chg, gp, rs, 0.0, reason);
    }

    private double computeConfidence(double chgVal, double chgMin,
                                     double gpVal, double gpMin, double gpMax) {
        double chgScore = Math.min(1.0, (chgVal - chgMin) / (chgMin * 2));
        double gpScore  = Math.min(1.0, (gpVal  - gpMin)  / (gpMax - gpMin));
        return Math.min(1.0, (chgScore + gpScore) / 2.0);
    }

    // -- Existing API ------------------------------------------------------------

    /**
     * Called by SectorClassificationService for each instrument during build.
     * Populates both the sector->symbols map and the symbol->sector reverse map.
     */
    public void registerSymbol(String symbol, String sectorName) {
        symbolsBySector.computeIfAbsent(sectorName,
                k -> Collections.synchronizedList(new ArrayList<>())).add(symbol);
        // BUG 2 FIX: store reverse mapping for isSectorAlignedForSymbol()
        symbolSector.put(symbol.toUpperCase(), sectorName);
    }

    /**
     * BUG 2 FIX: Check sector alignment using a SYMBOL (e.g. "RELIANCE").
     * Use this from TradeManagementService and PaperTradeManagementService.
     * Resolves sector internally so callers don't need to know the sector name.
     *
     * @param symbol   trading symbol (e.g. "RELIANCE")
     * @param isBull   true = LONG trade (need bullish sector), false = SHORT
     * @return true if sector direction still aligns with the trade direction
     */
    public boolean isSectorAlignedForSymbol(String symbol, boolean isBull) {
        String sector = symbolSector.get(symbol.toUpperCase());
        if (sector == null) return true; // not classified -> give benefit of the doubt
        return isSectorAligned(sector, isBull);
    }

    /**
     * Check sector alignment using SECTOR NAME (e.g. "Banking & Finance").
     * Use this when you already have the sector name.
     * Called from checkAllTradesAlignment() in management services - but those
     * services currently pass `symbol`. Use isSectorAlignedForSymbol() instead.
     *
     * @param sectorName  the sector name (key in sectorMap)
     * @param isBull      true = LONG, false = SHORT
     * @return true if aligned
     */
    public boolean isSectorAligned(String sectorName, boolean isBull) {
        SectorData sd = sectorMap.get(sectorName);
        if (sd == null) return true; // no data yet -> benefit of the doubt
        if (isBull)  return sd.changePercent() >= -0.10;
        else         return sd.changePercent() <=  0.10;
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

    // -- Candle event - price tracking -------------------------------------------

    @EventListener
    @Async("tradingExecutor")
    public void onCandle(CandleCompleteEvent event) {
        Candle c = event.getCandle();
        if (!"5minute".equals(c.getTimeframe()) || !c.isComplete()) return;

        String sym   = c.getTradingSymbol();
        double close = c.getClose().doubleValue();
        double open  = c.getOpen().doubleValue();

        // BUG 4 FIX: only persist when this is a GENUINE first-capture (i.e. the
        // symbol truly had no day-open yet - either real start-of-day, or the
        // database reconciliation above hasn't covered it). If reconciliation
        // already populated this symbol's true 9:15 AM open, this putIfAbsent
        // correctly becomes a no-op and nothing gets overwritten.
        Double previousOpen = openPrices.putIfAbsent(sym, open);
        if (previousOpen == null) {
            // This call genuinely inserted a new value - persist it so a future
            // mid-day restart can recover this exact day-open price.
            persistOpenPrice(sym, open);
        }
        latestPrices.put(sym, close);

        // BUG 1 FIX: read value once into local variable for atomic CAS
        long now  = System.currentTimeMillis();
        long prev = lastCalcEpoch.get();
        if (now - prev > 4_000 && lastCalcEpoch.compareAndSet(prev, now)) {
            recalculateSectors();
        }
    }

    // -- Sector recalculation ----------------------------------------------------

    private void recalculateSectors() {
        if (symbolsBySector.isEmpty()) {
            log.debug("[SECTOR] symbolsBySector empty - instrument cache not built yet");
            return;
        }

        double mktAvgChg = computeMarketAvgChange();

        symbolsBySector.forEach((sectorName, symbols) -> {
            if (symbols.isEmpty()) return;

            int    green = 0, red = 0;
            double totalChg = 0;
            int    count = 0;

            for (String sym : symbols) {
                Double openP  = openPrices.get(sym);
                Double closeP = latestPrices.get(sym);
                if (openP == null || closeP == null || openP == 0) continue;

                double chg = (closeP - openP) / openP * 100;
                totalChg += chg;
                count++;
                if (chg > 0) green++;
                else if (chg < 0) red++;
            }

            // BUG 3 FIX: skip sectors with no price data - don't write empty records
            if (count == 0) return;

            double avgChg  = totalChg / count;
            double gPct    = (double) green / count * 100;
            double rs      = mktAvgChg != 0 ? avgChg / Math.abs(mktAvgChg) : 0;

            boolean bull = avgChg >= BULL_CHG_THRESHOLD && gPct >= GREEN_BULL_MIN;
            boolean bear = avgChg <= -BEAR_CHG_THRESHOLD && gPct <= GREEN_BEAR_MAX;

            SectorData prev = sectorMap.get(sectorName);
            boolean wasTop  = prev != null && prev.isTopSector();
            boolean wasBot  = prev != null && prev.isBottomSector();

            sectorMap.put(sectorName, new SectorData(
                    sectorName, avgChg, gPct, green, red,
                    count, rs, bull, bear, wasTop, wasBot
            ));
        });

        if (!sectorMap.isEmpty()) {
            rankSectors();
            log.debug("[SECTOR] Recalculated {} sectors. Top: {}",
                    sectorMap.size(),
                    sectorMap.values().stream()
                            .max(Comparator.comparingDouble(SectorData::changePercent))
                            .map(s -> s.name() + " " + String.format("%.2f%%", s.changePercent()))
                            .orElse("none"));
        }
    }

    private void rankSectors() {
        List<SectorData> sorted = new ArrayList<>(sectorMap.values());
        sorted.sort(Comparator.comparingDouble(SectorData::changePercent).reversed());
        int n = sorted.size();
        for (int i = 0; i < n; i++) {
            SectorData s   = sorted.get(i);
            boolean top    = i < Math.max(1, n / 4);
            boolean bot    = i >= n - Math.max(1, n / 4);
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
        double total = 0;
        int    count = 0;
        for (Map.Entry<String, Double> e : latestPrices.entrySet()) {
            Double op = openPrices.get(e.getKey());
            if (op != null && op > 0) {
                total += (e.getValue() - op) / op * 100;
                count++;
            }
        }
        return count > 0 ? total / count : 0;
    }

    // -- Initialisation ----------------------------------------------------------

    /**
     * BUG 3 FIX: No longer calls recalculateSectors() at startup.
     * symbolsBySector is already populated via registerSymbol() calls from
     * SectorClassificationService before this is invoked.
     * Sector data will populate naturally once the first 5M candles arrive.
     */
    public void buildFromInstruments(List<com.zerodhatech.models.Instrument> instruments) {
        log.info("[SECTOR] Sector strength engine ready - {} sectors, {} symbols registered",
                symbolsBySector.size(),
                symbolsBySector.values().stream().mapToInt(List::size).sum());
        // Intentionally NOT calling recalculateSectors() here:
        // latestPrices is empty at startup so it would write count=0 records.
        // Live candle events will trigger recalculation once ticks start flowing.
    }

    // -- Daily reset -------------------------------------------------------------

    @Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyReset() {
        openPrices.clear();
        latestPrices.clear();
        sectorMap.clear();
        lastCalcEpoch.set(0);
        // NOTE: symbolsBySector and symbolSector are NOT cleared -
        // sector<->symbol mappings are built once at startup from instruments.
        // BUG 4 FIX: clear stale (yesterday's or older) persisted open prices
        // so they can never be reconciled into a future day by mistake.
        try {
            jdbc.update("DELETE FROM sector_open_prices WHERE trade_date < ?", LocalDate.now(ZoneId.of("Asia/Kolkata")));
        } catch (Exception e) {
            log.debug("[SECTOR] Daily DB cleanup failed (non-fatal): {}", e.getMessage());
        }
        log.info("[SECTOR] Daily reset complete - prices cleared, sector mappings retained");
    }

    // -- Data record -------------------------------------------------------------

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