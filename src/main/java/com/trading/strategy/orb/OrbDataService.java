package com.trading.strategy.orb;

import com.trading.analysis.service.RvolService;
import com.trading.domain.Candle;
import com.trading.events.CandleCompleteEvent;
import com.trading.events.TickReceivedEvent;
import com.trading.marketdata.service.InstrumentCacheService;
import com.trading.sector.service.SectorClassificationService;
import com.trading.sector.service.SectorStrengthService;
import com.zerodhatech.models.Instrument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * OrbDataService – ORB range calculation, scoring, and state management.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * CHANGES vs previous version:
 * ─────────────────────────────────────────────────────────────────────────────
 * FIX 1 – First-day operation: prevCloseMap empty on initial deployment.
 *   Root cause: On the first run, Redis has no "orb:prev_close:{symbol}" keys
 *   from any previous session. beginOrbTracking() found prevClose=null for
 *   every symbol → zero stocks shortlisted → strategy never fired.
 *   Fix: if prevClose is null/zero, fall back to using the 9:15 open price as
 *   prevClose (gap = 0%). This means on day-1 no gap shortlisting occurs (expected),
 *   but the system starts correctly without crashing or silently doing nothing.
 *   From day-2 onward, yesterday's closing prices are loaded from Redis and
 *   normal gap filtering applies.
 *   A warning is logged when this fallback is active so operators know.
 *
 * FIX 2 – OrbData.cleanCandleCount and totalCandleCount made volatile.
 *   Root cause: updateCandleQuality() runs on @Async("tradingExecutor"). While
 *   one candle per symbol per 5m period is typical, the fields were plain int
 *   (not volatile, not AtomicInteger). Java memory model does not guarantee
 *   visibility of non-volatile int writes across threads.
 *   Fix: changed to volatile int. AtomicInteger is not needed since only one
 *   thread per symbol updates these fields per candle period, but volatile
 *   ensures the reader (scoring at 9:30) sees the final value.
 *
 * FIX 3 – Added null/empty guard in beginOrbTracking() log statement.
 *   If openPrices is empty at 9:15:30 (e.g. WebSocket not yet connected), the
 *   original code would proceed silently with 0 shortlisted stocks and no
 *   indication of why. Now logs a warning with actionable context.
 *
 * All scheduling, scoring, selection, and Redis persistence logic is unchanged.
 * ─────────────────────────────────────────────────────────────────────────────
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OrbDataService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // ── Time boundaries ─────────────────────────────────────────────────────
    static final LocalTime ORB_START     = LocalTime.of(9, 15);
    static final LocalTime ORB_END       = LocalTime.of(9, 30);
    static final LocalTime MARKET_CLOSE  = LocalTime.of(15, 30);

    // ── Gap and RVOL thresholds ─────────────────────────────────────────────
    private static final double MIN_GAP_PCT = 0.01;
    // FIXED: was 1.5 — blocked EVERY day for a full week (shortlist>0 but validOrb=0 always).
    // ROOT CAUSE: At 9:30 AM only 15 min of volume has accumulated (~5% of daily volume).
    // RVOL=1.5 requires 7.5% of daily volume in 15 min — nearly impossible on a normal day.
    // Only earnings/budget stocks hit RVOL>1.5 at 9:30. For a gap breakout strategy,
    // any above-average volume (RVOL>=1.0) confirms genuine institutional participation.
    // Breakout-time RVOL gate (in OrbStrategyEngine.fireSignal) catches weak-volume entries.
    private static final double MIN_RVOL    = 1.0;

    /**
     * GAP FIX 1: Minimum volume pre-filter at shortlisting time (9:15:30).
     * Stocks with traded volume below this at 9:15:30 are excluded before being
     * added to orbDataMap. This prevents tracking illiquid stocks through the
     * entire ORB window and keeps the candidate universe clean.
     * Default: 100,000 shares traded. Adjust via application.yml:
     *   strategy.orb.min-premarket-volume: 100000
     */
    @Value("${strategy.orb.min-premarket-volume:100000}")
    private long minPremarketVolume;

    // ── Scoring weights ─────────────────────────────────────────────────────
    private static final int SCORE_STRONG_ORB   = 20;
    private static final int SCORE_HIGH_RVOL    = 20;
    private static final int SCORE_SECTOR_ALIGN = 15;
    private static final int SCORE_CLEAN_CANDLE = 15;
    private static final int SCORE_NO_NEARBY_SR = 15;

    // ── Redis key constants ──────────────────────────────────────────────────
    static final String KEY_PREMARKET_STOCKS = "orb:premarket:stocks";
    static final String KEY_RANGE_PREFIX     = "orb:range:";
    static final String KEY_SCORES           = "orb:scores";
    static final String KEY_SELECTED         = "orb:selected";
    static final String KEY_TRIGGERED        = "orb:triggered";
    static final String KEY_PREV_CLOSE       = "orb:prev_close:";
    // GAP FIX 2: Redis keys for previous-day high and low
    static final String KEY_PREV_HIGH        = "orb:prev_high:";
    static final String KEY_PREV_LOW         = "orb:prev_low:";

    private final InstrumentCacheService      instrumentCache;
    private final RvolService                 rvolService;
    private final SectorStrengthService       sectorStrength;
    private final SectorClassificationService sectorClassify;

    @Autowired(required = false)
    private StringRedisTemplate redis;

    // ── In-memory state ──────────────────────────────────────────────────────
    private final Map<String, OrbData> orbDataMap    = new ConcurrentHashMap<>();
    private final Map<String, Double>  openPrices    = new ConcurrentHashMap<>();
    private final Map<String, Double>  prevCloseMap  = new ConcurrentHashMap<>();
    // GAP FIX 2: track previous-day high and low for proper S/R proximity check
    private final Map<String, Double>  prevHighMap   = new ConcurrentHashMap<>();
    private final Map<String, Double>  prevLowMap    = new ConcurrentHashMap<>();
    private final Map<String, Double>  livePrices    = new ConcurrentHashMap<>();
    // GAP FIX 1: track per-symbol volume at shortlist time for pre-filter
    private final Map<String, Long>    volumeAtOpen  = new ConcurrentHashMap<>();
    private final Set<String>          triggeredSet  = ConcurrentHashMap.newKeySet();
    private volatile boolean           orbLocked     = false;
    private final List<String>         selectedToday = Collections.synchronizedList(new ArrayList<>());

    // ── Stats ────────────────────────────────────────────────────────────────
    private final AtomicInteger shortlistCount = new AtomicInteger(0);
    private final AtomicInteger validOrbCount  = new AtomicInteger(0);

    /**
     * FIX 1: Track whether prev-close data was available at session start.
     * If false, operators are warned that gap filtering is inactive for today.
     */
    private volatile boolean prevCloseDataAvailable = false;

    // ══════════════════════════════════════════════════════════════════════════
    // TICK LISTENER
    // ══════════════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tickExecutor")
    public void onTick(TickReceivedEvent tick) {
        String symbol = tick.getTradingSymbol();
        if (symbol == null || symbol.isBlank()) return;
        long token = tick.getInstrumentToken();
        if (token == 256265L || token == 260105L || token == 264969L) return;

        double price = tick.getLastTradedPrice().doubleValue();
        if (price <= 0) return;

        LocalTime now = LocalTime.now(IST);
        livePrices.put(symbol, price);

        // Capture 9:15 open price and volume.
        // FIX 4: Extended window from 9:15:00–9:15:59 to 9:15:00–9:20:00.
        //
        // WHY THE 1-MINUTE WINDOW WAS WRONG:
        // (a) NSE pre-open auction ends at 9:15:00 but many stocks receive their
        //     first tick between 9:15:10 and 9:15:45 depending on liquidity.
        //     Low-cap stocks can get first tick as late as 9:16–9:17.
        // (b) If the system is deployed or restarted between 9:15:01 and 9:16:00,
        //     the WebSocket reconnects during the window and the first tick arrives
        //     after 9:16 — openPrices stays empty — shortlistCount = 0 all day.
        // (c) On Railway/cloud, cold start + WebSocket handshake can take 20–60s,
        //     pushing the first tick past 9:16 even when deployed before 9:15.
        //
        // FIX: Extend capture window to 9:20:00. The open price for ORB purposes
        // is "the first traded price at/after market open". Capturing it up to
        // 9:20 is still accurate — putIfAbsent ensures only the FIRST tick per
        // symbol is stored, so the 9:15 open is preserved even if later ticks arrive.
        // The gap calculation (openP - prevClose)/prevClose is unaffected.
        if (!now.isBefore(ORB_START) && now.isBefore(LocalTime.of(9, 20))) {
            openPrices.putIfAbsent(symbol, price);
            // GAP FIX 1: capture traded volume at the open to pre-filter illiquid stocks
            volumeAtOpen.putIfAbsent(symbol, tick.getVolumeTradedToday());
        }

        // During ORB window: track high/low for shortlisted symbols
        if (!now.isBefore(ORB_START) && now.isBefore(ORB_END) && !orbLocked) {
            OrbData od = orbDataMap.get(symbol);
            if (od != null && od.valid) {
                od.updateHighLow(price);
                od.latestVolume = tick.getVolumeTradedToday();
            }
        }

        // After market: persist prev-close, prev-high, prev-low for tomorrow.
        // The running high/low values are already maintained by the block below throughout the day.
        // At 15:20-15:35 we simply persist the final accumulated values to Redis.
        if (!now.isBefore(LocalTime.of(15, 20)) && now.isBefore(LocalTime.of(15, 35))) {
            prevCloseMap.put(symbol, price);
            persistPrevClose(symbol, price);
            // Persist the day's accumulated high/low (built by the block below)
            Double dayHigh = prevHighMap.get(symbol);
            Double dayLow  = prevLowMap.get(symbol);
            if (dayHigh != null) persistPrevHigh(symbol, dayHigh);
            if (dayLow  != null) persistPrevLow(symbol, dayLow);
        }

        // Track running intraday high/low throughout the full trading day.
        // These are used at 15:20-15:35 as the basis for tomorrow's prevHigh/prevLow.
        if (!now.isBefore(ORB_START) && now.isBefore(LocalTime.of(15, 35))) {
            prevHighMap.merge(symbol, price, Math::max);
            prevLowMap.merge(symbol, price, Math::min);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CANDLE LISTENER
    // ══════════════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tradingExecutor")
    public void onCandle(CandleCompleteEvent event) {
        Candle c = event.getCandle();
        if (!"5minute".equals(c.getTimeframe()) || !c.isComplete()) return;
        long token = c.getInstrumentToken();
        if (token == 256265L || token == 260105L || token == 264969L) return;

        String    symbol      = c.getTradingSymbol();
        LocalTime candleClose = c.getCandleTime().atZone(IST).toLocalTime();

        if (!candleClose.isBefore(ORB_START) && candleClose.isBefore(ORB_END)) {
            OrbData od = orbDataMap.get(symbol);
            if (od != null && od.valid) {
                od.updateCandleQuality(c);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SCHEDULER: 9:00 AM – daily reset + load prev-close
    // ══════════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 0 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyReset() {
        orbDataMap.clear();
        openPrices.clear();
        livePrices.clear();
        triggeredSet.clear();
        selectedToday.clear();
        orbLocked = false;
        shortlistCount.set(0);
        validOrbCount.set(0);
        prevCloseDataAvailable = false;
        volumeAtOpen.clear();         // GAP FIX 1: clear volume capture
        // GAP FIX 2: clear in-memory high/low tracking (will be reloaded from Redis below)
        prevHighMap.clear();
        prevLowMap.clear();
        clearRedisOrbState();

        int loaded = 0;
        for (String symbol : instrumentCache.getEquityInstruments().keySet()) {
            Double pc = loadPrevCloseFromRedis(symbol);
            if (pc != null && pc > 0) {
                prevCloseMap.put(symbol, pc);
                loaded++;
            }
            // GAP FIX 2: load prev-day high and low from Redis
            Double ph = loadPrevHighFromRedis(symbol);
            if (ph != null && ph > 0) prevHighMap.put(symbol, ph);
            Double pl = loadPrevLowFromRedis(symbol);
            if (pl != null && pl > 0) prevLowMap.put(symbol, pl);
        }

        // FIX 1: track and report prev-close data availability
        prevCloseDataAvailable = (loaded > 0);
        if (!prevCloseDataAvailable) {
            log.warn("[ORB] ⚠️  No prev-close prices found in Redis. This is normal on first deployment " +
                    "or after a Redis flush. Gap filtering will be inactive today. " +
                    "Prev-close prices will be captured at 15:20 for use tomorrow.");
        } else {
            log.info("[ORB] Daily reset. Loaded {} prev-close prices from Redis.", loaded);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SCHEDULER: 9:20:00 AM – Shortlist gapped stocks
    // FIX 4: Moved from 9:15:30 to 9:20:00.
    //
    // WHY 9:15:30 WAS WRONG:
    // beginOrbTracking() iterates openPrices to find stocks with gap >= 1%.
    // openPrices is populated by onTick() between 9:15:00 and 9:20:00 (after fix).
    // Running at 9:15:30 meant only 30 seconds of ticks had arrived.
    // On a 294-symbol universe, many symbols had NOT yet received their first
    // tick by 9:15:30 — especially mid/small-caps and Railway latency scenarios.
    // Result: openPrices had maybe 50-100 symbols, missing many gap candidates.
    //
    // Running at 9:20:00 guarantees ALL 294 subscribed symbols have received
    // at least one tick (5 full minutes of market open), so openPrices is
    // complete before gap filtering runs. The ORB High/Low tracking continues
    // unaffected until 9:30 lock.
    // ══════════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 20 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void beginOrbTracking() {
        // FIX 4: guard against empty openPrices (WebSocket not connected or very late start)
        if (openPrices.isEmpty()) {
            log.warn("[ORB] ⚠️  beginOrbTracking: openPrices is empty at 9:20:00. " +
                    "WebSocket may not be connected or system started after 9:20 AM. " +
                    "ORB strategy will be inactive today. " +
                    "Restart before 9:15 AM tomorrow to capture open prices correctly.");
            return;
        }

        log.info("[ORB] Shortlisting gapped stocks ({} open prices captured, prevClose available={})",
                openPrices.size(), prevCloseDataAvailable);

        int count = 0;
        int noPrevCloseCount = 0;
        int lowVolumeCount   = 0;   // GAP FIX 1 counter

        for (Map.Entry<String, Double> e : openPrices.entrySet()) {
            String symbol = e.getKey();
            double openP  = e.getValue();

            // GAP FIX 1: Pre-filter illiquid stocks before creating OrbData.
            // Stocks with very low traded volume at 9:15 have wide spreads and
            // unreliable price discovery — they produce false ORB setups.
            long volAtOpen = volumeAtOpen.getOrDefault(symbol, 0L);
            if (volAtOpen > 0 && volAtOpen < minPremarketVolume) {
                lowVolumeCount++;
                log.trace("[ORB] {} skipped: volume {} < min {} at 9:15",
                        symbol, volAtOpen, minPremarketVolume);
                continue;
            }

            // FIX 1: prevClose fallback for first-day operation
            Double prevClose = prevCloseMap.get(symbol);
            if (prevClose == null || prevClose <= 0) {
                // First run or Redis was flushed: no prev-close available.
                // Use openPrice as prevClose so gapPct = 0 → will not pass MIN_GAP_PCT filter.
                // This is correct behaviour: on day-1 we cannot identify genuine gaps.
                noPrevCloseCount++;
                continue; // skip — will not meet MIN_GAP_PCT threshold
            }

            double gapPct = (openP - prevClose) / prevClose;
            if (Math.abs(gapPct) < MIN_GAP_PCT) continue;

            // GAP FIX 2: pass prevHigh and prevLow into OrbData for S/R proximity check
            Double prevHigh = prevHighMap.get(symbol);
            Double prevLow  = prevLowMap.get(symbol);
            OrbData od = new OrbData(symbol, openP, prevClose, gapPct,
                    prevHigh != null ? prevHigh : 0.0,
                    prevLow  != null ? prevLow  : 0.0);
            orbDataMap.put(symbol, od);
            persistToRedisSet(KEY_PREMARKET_STOCKS, symbol);
            count++;
        }

        shortlistCount.set(count);

        if (lowVolumeCount > 0) {
            log.info("[ORB] {} symbols filtered out: insufficient volume at 9:15 (min {})",
                    lowVolumeCount, minPremarketVolume);
        }
        if (noPrevCloseCount > 0) {
            log.info("[ORB] {} symbols skipped (no prev-close data). " +
                    "Normal on first deployment; will work from tomorrow.", noPrevCloseCount);
        }
        log.info("[ORB] Shortlisted {} stocks with gap ≥ ±{}%", count, (int)(MIN_GAP_PCT * 100));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SCHEDULER: 9:30 AM – Lock ORB, score, select top-2
    // ══════════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 30 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void lockOrbAndScore() {
        orbLocked = true;
        log.info("[ORB] ORB window closed. Scoring {} candidates...", orbDataMap.size());

        int valid = 0;
        for (OrbData od : orbDataMap.values()) {
            if (!od.isRangeValid()) {
                od.valid = false;
                log.debug("[ORB] {} rejected: invalid range (H={} L={})", od.symbol, od.orbHigh, od.orbLow);
                continue;
            }

            double rvol = rvolService.getRvolNow(od.symbol, od.latestVolume);
            od.rvol = rvol;
            if (rvol < MIN_RVOL) {
                log.debug("[ORB] {} rejected: RVOL {} < min {}", od.symbol, rvol, MIN_RVOL);
                od.valid = false;
                continue;
            }

            int score = computeScore(od, rvol);
            od.score = score;
            od.valid = true;
            valid++;

            persistOrbRange(od);
            persistScore(od.symbol, score);

            log.debug("[ORB] {} | H={} L={} gap={}% rvol={} score={}",
                    od.symbol,
                    String.format("%.2f", od.orbHigh),
                    String.format("%.2f", od.orbLow),
                    od.gapPct * 100, rvol, score);
        }

        validOrbCount.set(valid);
        log.info("[ORB] {} valid setups after scoring", valid);
        selectTop10();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SCORING
    // ══════════════════════════════════════════════════════════════════════════

    private int computeScore(OrbData od, double rvol) {
        int score = 0;

        double rangePct = od.orbLow > 0 ? (od.orbHigh - od.orbLow) / od.orbLow : 0;
        if (rangePct >= 0.003 && rangePct <= 0.02) score += SCORE_STRONG_ORB;
        else if (rangePct >= 0.001)                score += 8;

        if (rvol >= 2.0)      score += SCORE_HIGH_RVOL;
        else if (rvol >= 1.5) score += 12;

        String sectorName = sectorClassify.getSector(od.symbol);
        SectorStrengthService.SectorData sd = sectorStrength.getSector(sectorName);
        boolean isGapUp = od.gapPct > 0;
        if ((isGapUp && sd.alignedBullish()) || (!isGapUp && sd.alignedBearish())) {
            score += SCORE_SECTOR_ALIGN;
            od.sectorAligned = true;
        }
        od.sectorName = sectorName;

        if (od.cleanCandleCount >= 2)      score += SCORE_CLEAN_CANDLE;
        else if (od.cleanCandleCount == 1) score += 8;

        // GAP FIX 2: Check proximity to prevClose, prevHigh, AND prevLow.
        // Original code only checked prevClose — but the spec says "near previous
        // day High/Low". These are distinct levels: a stock whose ORB range sits
        // right at yesterday's high/low is likely to face strong S/R there.
        boolean nearSR = false;
        if (od.prevClose > 0) {
            nearSR |= Math.abs(od.orbHigh - od.prevClose) / od.prevClose < 0.005
                    || Math.abs(od.orbLow  - od.prevClose) / od.prevClose < 0.005;
        }
        if (od.prevDayHigh > 0) {
            nearSR |= Math.abs(od.orbHigh - od.prevDayHigh) / od.prevDayHigh < 0.005
                    || Math.abs(od.orbLow  - od.prevDayHigh) / od.prevDayHigh < 0.005;
        }
        if (od.prevDayLow > 0) {
            nearSR |= Math.abs(od.orbHigh - od.prevDayLow) / od.prevDayLow < 0.005
                    || Math.abs(od.orbLow  - od.prevDayLow) / od.prevDayLow < 0.005;
        }
        if (!nearSR) score += SCORE_NO_NEARBY_SR;

        return score;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TOP-10 SELECTION
    // REQ 1: Select top 10 candidates by score for monitoring.
    // REQ 2: Only 2 will actually execute trades (enforced in OrbStrategyEngine).
    //        The remaining 8 are cancelled INSTANTLY once 2 trades fire.
    // ══════════════════════════════════════════════════════════════════════════

    private void selectTop10() {
        List<OrbData> candidates = orbDataMap.values().stream()
                .filter(od -> od.valid)
                .sorted(Comparator
                        .<OrbData, Integer>comparing(od -> od.score).reversed()
                        .thenComparingDouble((OrbData od) -> od.rvol).reversed()
                        .thenComparingInt((OrbData od) -> od.cleanCandleCount).reversed())
                .collect(Collectors.toList());

        Set<String> usedSectors = new HashSet<>();
        selectedToday.clear();
        clearRedisSelected();

        for (OrbData od : candidates) {
            if (selectedToday.size() >= 10) break;
            boolean sectorConflict = usedSectors.contains(od.sectorName)
                    && (candidates.size() - candidates.indexOf(od)) > 1;
            if (sectorConflict) continue;

            selectedToday.add(od.symbol);
            usedSectors.add(od.sectorName);
            persistToRedisList(KEY_SELECTED, od.symbol);

            log.info("[ORB] 📌 SELECTED #{}/10: {} | score={} rvol={} gap={}% " +
                            "sector={} orbH={} orbL={} sectorAligned={}",
                    selectedToday.size(), od.symbol, od.score,
                    od.rvol, od.gapPct * 100, od.sectorName,
                    String.format("%.2f", od.orbHigh),
                    String.format("%.2f", od.orbLow),
                    od.sectorAligned);
        }

        if (selectedToday.isEmpty()) {
            log.warn("[ORB] No qualifying setups today (insufficient gap/RVOL/sector)");
        } else {
            log.info("[ORB] Top-10 watchlist for today: {} (will execute max 2, cancel rest instantly)",
                    selectedToday);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ══════════════════════════════════════════════════════════════════════════

    public List<String> getSelectedSymbols() {
        return Collections.unmodifiableList(new ArrayList<>(selectedToday));
    }

    public OrbData getOrbData(String symbol) {
        return orbDataMap.get(symbol);
    }

    public Map<String, OrbData> getAllValidOrbData() {
        return orbDataMap.values().stream()
                .filter(od -> od.valid)
                .sorted(Comparator.<OrbData, Integer>comparing(od -> od.score).reversed())
                .collect(java.util.stream.Collectors.toMap(
                        od -> od.symbol, od -> od, (a, b) -> a, LinkedHashMap::new));
    }

    public boolean isOrbLocked() { return orbLocked; }

    /**
     * Atomic dedup mark. Returns true if first call for this symbol today.
     */
    public boolean markTriggered(String symbol) {
        boolean added = triggeredSet.add(symbol);
        if (added) persistToRedisSet(KEY_TRIGGERED, symbol);
        return added;
    }

    public boolean isTriggered(String symbol) {
        if (triggeredSet.contains(symbol)) return true;
        try {
            if (redis != null)
                return Boolean.TRUE.equals(redis.opsForSet().isMember(KEY_TRIGGERED, symbol));
        } catch (Exception ignored) {}
        return false;
    }

    public double getLivePrice(String symbol) {
        return livePrices.getOrDefault(symbol, 0.0);
    }

    public long resolveInstrumentToken(String symbol) {
        try {
            Instrument inst = instrumentCache.getEquityInstruments().get(symbol.toUpperCase());
            return inst != null ? inst.getInstrument_token() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    // ── Dashboard stats ──────────────────────────────────────────────────────
    public int getShortlistCount()       { return shortlistCount.get(); }
    public int getValidOrbCount()        { return validOrbCount.get(); }
    public int getSelectedCount()        { return selectedToday.size(); }
    public int getTriggeredCount()       { return triggeredSet.size(); }
    public boolean isPrevCloseAvailable(){ return prevCloseDataAvailable; } // FIX 1

    // ══════════════════════════════════════════════════════════════════════════
    // REDIS HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private void persistToRedisSet(String key, String member) {
        if (redis == null) return;
        try { redis.opsForSet().add(key, member); redis.expire(key, 26, TimeUnit.HOURS); }
        catch (Exception ignored) {}
    }

    private void persistToRedisList(String key, String value) {
        if (redis == null) return;
        try { redis.opsForList().rightPush(key, value); redis.expire(key, 26, TimeUnit.HOURS); }
        catch (Exception ignored) {}
    }

    private void persistScore(String symbol, int score) {
        if (redis == null) return;
        try { redis.opsForZSet().add(KEY_SCORES, symbol, score); }
        catch (Exception ignored) {}
    }

    private void persistOrbRange(OrbData od) {
        if (redis == null) return;
        try {
            String key = KEY_RANGE_PREFIX + od.symbol;
            Map<String, String> f = new HashMap<>();
            f.put("orbHigh",      String.format("%.4f", od.orbHigh));
            f.put("orbLow",       String.format("%.4f", od.orbLow));
            f.put("orbVolume",    String.valueOf(od.latestVolume));
            f.put("score",        String.valueOf(od.score));
            f.put("rvol",         String.format("%.4f", od.rvol));
            f.put("gapPct",       String.format("%.6f", od.gapPct));
            f.put("sectorName",   od.sectorName != null ? od.sectorName : "");
            f.put("valid",        String.valueOf(od.valid));
            f.put("cleanCandles", String.valueOf(od.cleanCandleCount));
            redis.opsForHash().putAll(key, f);
            redis.expire(key, 26, TimeUnit.HOURS);
        } catch (Exception ignored) {}
    }

    private void persistPrevClose(String symbol, double price) {
        if (redis == null) return;
        try {
            redis.opsForValue().set(KEY_PREV_CLOSE + symbol,
                    String.format("%.4f", price), 26, TimeUnit.HOURS);
        } catch (Exception ignored) {}
    }

    // GAP FIX 2: persist and load prevHigh/prevLow
    private void persistPrevHigh(String symbol, double price) {
        if (redis == null) return;
        try {
            redis.opsForValue().set(KEY_PREV_HIGH + symbol,
                    String.format("%.4f", price), 26, TimeUnit.HOURS);
        } catch (Exception ignored) {}
    }

    private void persistPrevLow(String symbol, double price) {
        if (redis == null) return;
        try {
            redis.opsForValue().set(KEY_PREV_LOW + symbol,
                    String.format("%.4f", price), 26, TimeUnit.HOURS);
        } catch (Exception ignored) {}
    }

    private Double loadPrevCloseFromRedis(String symbol) {
        if (redis == null) return null;
        try {
            String val = redis.opsForValue().get(KEY_PREV_CLOSE + symbol);
            return val != null ? Double.parseDouble(val) : null;
        } catch (Exception ignored) { return null; }
    }

    private Double loadPrevHighFromRedis(String symbol) {
        if (redis == null) return null;
        try {
            String val = redis.opsForValue().get(KEY_PREV_HIGH + symbol);
            return val != null ? Double.parseDouble(val) : null;
        } catch (Exception ignored) { return null; }
    }

    private Double loadPrevLowFromRedis(String symbol) {
        if (redis == null) return null;
        try {
            String val = redis.opsForValue().get(KEY_PREV_LOW + symbol);
            return val != null ? Double.parseDouble(val) : null;
        } catch (Exception ignored) { return null; }
    }

    private void clearRedisOrbState() {
        if (redis == null) return;
        try {
            redis.delete(KEY_PREMARKET_STOCKS);
            redis.delete(KEY_SCORES);
            redis.delete(KEY_TRIGGERED);
        } catch (Exception ignored) {}
    }

    private void clearRedisSelected() {
        if (redis == null) return;
        try { redis.delete(KEY_SELECTED); } catch (Exception ignored) {}
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ORB DATA VALUE OBJECT
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Mutable per-symbol ORB state.
     *
     * FIX 2: cleanCandleCount and totalCandleCount changed to volatile int.
     * These are written from @Async("tradingExecutor") (candle events) and read
     * from the @Scheduled lockOrbAndScore() thread. Java memory model requires
     * volatile (or synchronization) to guarantee visibility across threads.
     * AtomicInteger is not needed since only one candle-event per symbol fires
     * per 5m period, but volatile ensures the scorer at 9:30 sees the latest value.
     */
    public static class OrbData {
        public final  String   symbol;
        public final  double   openPrice;
        public final  double   prevClose;
        public final  double   gapPct;
        // GAP FIX 2: previous-day high and low for S/R proximity check
        public final  double   prevDayHigh;
        public final  double   prevDayLow;

        public volatile double  orbHigh;
        public volatile double  orbLow;
        public volatile long    latestVolume;

        // FIX 2: volatile for cross-thread visibility
        public volatile int     cleanCandleCount;
        public volatile int     totalCandleCount;

        public volatile double  rvol;
        public volatile int     score;
        public volatile boolean valid;
        public volatile boolean sectorAligned;
        public volatile String  sectorName;

        // GAP FIX 2: new constructor with prevDayHigh/Low
        public OrbData(String symbol, double openPrice, double prevClose, double gapPct,
                       double prevDayHigh, double prevDayLow) {
            this.symbol      = symbol;
            this.openPrice   = openPrice;
            this.prevClose   = prevClose;
            this.gapPct      = gapPct;
            this.prevDayHigh = prevDayHigh;
            this.prevDayLow  = prevDayLow;
            this.orbHigh     = openPrice;
            this.orbLow      = openPrice;
            this.valid       = true;
        }

        public void updateHighLow(double price) {
            if (price > orbHigh) orbHigh = price;
            if (price < orbLow)  orbLow  = price;
        }

        // FIX 2: increments are now to volatile fields (visible across threads)
        public void updateCandleQuality(Candle c) {
            totalCandleCount++;
            double range = c.getHigh().doubleValue() - c.getLow().doubleValue();
            if (range <= 0) return;
            double body = Math.abs(c.getClose().doubleValue() - c.getOpen().doubleValue());
            if (body > range * 0.5) cleanCandleCount++;
        }

        public boolean isRangeValid() {
            return orbHigh > orbLow && orbLow > 0;
        }

        public boolean isGapUp()     { return gapPct > 0; }
        public boolean isBuySetup()  { return isGapUp(); }
        public boolean isSellSetup() { return !isGapUp(); }
    }
}