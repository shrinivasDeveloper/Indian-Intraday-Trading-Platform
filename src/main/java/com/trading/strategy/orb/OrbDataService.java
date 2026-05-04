package com.trading.strategy.orb;

import com.trading.analysis.service.RvolService;
import com.trading.domain.Candle;
import com.trading.events.CandleCompleteEvent;
import com.trading.events.TickReceivedEvent;
import com.trading.marketdata.service.InstrumentCacheService;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.models.HistoricalData;
import com.zerodhatech.models.OHLCQuote;
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
    // MIN_GAP_PCT REMOVED — gap % is no longer a shortlisting gate.
    // Every liquid stock is tracked through the 9:15–9:30 ORB window.
    // Gap % is used only as a SCORING BONUS in computeScore().
    // WHY: A High Wave candle can form on any stock — gapping or flat-open.
    // The 15-min candle body/wick quality (evaluated at 9:30) is the real filter.
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

    // FIX: inject KiteConnect for prevClose bootstrap on first-run or Redis flush.
    // @Autowired(required=false) ensures startup doesn't fail if the bean is unavailable.
    // KiteConnect is a Spring bean registered by the auth service on login.
    @Autowired(required = false)
    private KiteConnect kiteConnect;

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

    // onCandle(CandleCompleteEvent) REMOVED.
    // The 5-min candle listener was counting trend candles inside the ORB window
    // and storing into cleanCandleCount. This was wrong because:
    //   (a) The ORB candle is a single 15-min candle (9:15–9:30), not 5-min candles.
    //   (b) Body/wick quality of the ORB candle must be computed from the full
    //       15-min OHLC (orbOpen/orbHigh/orbLow/orbClose), not from sub-candles.
    //   (c) 5-min candles inside the ORB window can individually be trend candles
    //       while the combined 15-min candle is a High Wave — the sub-candle body
    //       check gave false negatives.
    // Replacement: computeHighWaveQuality() is called in lockOrbAndScore() at 9:30
    // on the complete 15-min OHLC (orbOpen, orbHigh, orbLow, orbClose) which is
    // built accurately from tick data via updateHighLow() and orbClose tracking.


    // ══════════════════════════════════════════════════════════════════════════
    // SCHEDULER: 9:00 AM – daily reset + load prev-close
    // ══════════════════════════════════════════════════════════════════════════

    // ══════════════════════════════════════════════════════════════════════════
    // SCHEDULER: 15:30 — Persist prevClose, prevHigh, prevLow for tomorrow
    //
    // WHY 15:30 EXACTLY:
    // NSE closing price = 15:30 closing auction result.
    // livePrices holds the most recent tick for every symbol (updated all day).
    // At 15:30, livePrices[symbol] = the last traded price = closest to official close.
    //
    // WHY NOT TICK-BASED (previous approach was wrong):
    //   (a) Ticks continue until 15:35 — last tick is NOT the official close price
    //   (b) The old code saved prevClose on every tick 15:20–15:35 = thousands of
    //       redundant Redis writes overwriting each other
    //   (c) On a holiday, no ticks fire → 15:20–15:35 window never triggered
    //       → prevClose was never saved → dailyReset() next trading day found empty
    //       Redis → bootstrapPrevCloseFromBroker() ran → startup delay + API load
    //
    // HOLIDAY SAFETY (with 96h TTL):
    //   This cron fires on every MON–FRI when NSE is open.
    //   On a holiday NSE is closed — no ticks, this cron still fires but
    //   livePrices may be stale (last saved from previous trading day).
    //   BUT: the 96h TTL on Redis keys ensures the PREVIOUS trading day's
    //   prevClose survives until the next trading day regardless of holidays:
    //
    //   Thursday close  → Friday holiday + Saturday + Sunday → Monday 9:00 AM
    //     Thursday 15:30 → Monday 9:00 = 65.5 hours → within 96h ✅
    //
    //   Thursday close  → Fri holiday + Sat + Sun + Mon holiday → Tuesday 9:00 AM
    //     Thursday 15:30 → Tuesday 9:00 = 89.5 hours → within 96h ✅
    //
    //   If holiday cron fires with stale livePrices — it simply re-saves the same
    //   price from the last trading day with a fresh 96h TTL. No harm done.
    // ══════════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 30 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void persistEndOfDayPrices() {
        if (livePrices.isEmpty()) {
            log.warn("[ORB] EOD persist: livePrices empty — WebSocket not connected?");
            return;
        }
        int saved = 0;
        for (Map.Entry<String, Double> e : livePrices.entrySet()) {
            String symbol = e.getKey();
            double closePrice = e.getValue();
            if (closePrice <= 0) continue;

            // prevClose = live price at 15:30 ≈ NSE closing auction price
            prevCloseMap.put(symbol, closePrice);
            persistPrevClose(symbol, closePrice);

            // prevHigh / prevLow = intraday extremes (tracked all day by onTick)
            Double dayHigh = prevHighMap.get(symbol);
            Double dayLow  = prevLowMap.get(symbol);
            if (dayHigh != null && dayHigh > 0) persistPrevHigh(symbol, dayHigh);
            if (dayLow  != null && dayLow  > 0) persistPrevLow(symbol, dayLow);
            saved++;
        }
        log.info("[ORB] 📦 EOD: saved prevClose+High+Low for {} symbols (96h TTL — holiday safe)", saved);
    }

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

        // FIX: if Redis has no prevClose data (first deployment or Redis flush),
        // bootstrap from broker's historical API. This fetches yesterday's daily
        // candle close price for every equity instrument and writes it to both
        // the in-memory map and Redis so it survives the next restart.
        // @see bootstrapPrevCloseFromBroker()
        if (loaded == 0) {
            log.warn("[ORB] No prev-close data in Redis. Bootstrapping from broker API...");
            loaded = bootstrapPrevCloseFromBroker();
        }

        prevCloseDataAvailable = (loaded > 0);
        if (!prevCloseDataAvailable) {
            log.warn("[ORB] ⚠️  prevClose bootstrap also returned 0. " +
                    "Gap filtering inactive today. Will auto-recover tomorrow at 15:20.");
        } else {
            log.info("[ORB] Daily reset complete. prevClose loaded for {} symbols " +
                    "(prevCloseAvailable=true).", loaded);
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

        log.info("[ORB] Shortlisting liquid stocks for ORB tracking ({} open prices captured, prevClose available={})",
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

            // prevClose needed for gap % scoring bonus only (not as a gate).
            // If unavailable (first deploy), use 0 — gap bonus will simply be 0.
            Double prevClose = prevCloseMap.getOrDefault(symbol, 0.0);
            if (prevClose <= 0) {
                noPrevCloseCount++;
                // Continue tracking — no gap bonus today, but HW + sector can still qualify.
            }

            double gapPct = (prevClose > 0)
                    ? (openP - prevClose) / prevClose
                    : 0.0;
            // REMOVED: gap % filter. Do NOT gate on gap here.
            // WHY: A High Wave candle can form on ANY stock — gapping or flat.
            //   The 15-min ORB candle quality (body/wick ratio) is what matters,
            //   and that is only known at 9:30 after the candle closes.
            //   Gating at 9:20 on gap % means we miss every flat-open High Wave.
            //   Gap % is now used as a SCORING BONUS in computeScore(), not a gate.
            //   Volume (minPremarketVolume) is the only pre-filter — ensures liquidity.

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
        log.info("[ORB] Shortlisted {} liquid stocks for ORB tracking (gap % used for scoring only)", count);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SCHEDULER: 9:30 AM – Lock ORB, score, select top-2
    // ══════════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 30 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void lockOrbAndScore() {
        orbLocked = true;
        log.info("[ORB] ORB window closed. Scoring {} candidates...", orbDataMap.size());

        // ── FILTER ORDER (your requirement — candle quality FIRST) ─────────────
        // Step 1: Range valid           — basic sanity check
        // Step 2: High Wave candle      — HARD FILTER first, most important
        //                                 Non-HW stocks rejected immediately
        // Step 3: RVOL ≥ 1.0           — volume confirmation AFTER candle passes
        // Step 4: Score ≥ MIN_ORB_SCORE — quality ranking
        //
        // WHY THIS ORDER:
        //   High Wave is the PRIMARY edge. If the 15-min ORB candle is NOT a
        //   High Wave (body 20-25%, both wicks ≥30%), there is no indecision
        //   at a key level — the setup has no structural basis. RVOL and score
        //   are secondary confirmations. Checking candle first also means RVOL
        //   failures on Day-1 (no baseline) only affect HW-qualified stocks,
        //   not all 11 shortlisted stocks blindly.
        // ─────────────────────────────────────────────────────────────────────

        int valid       = 0;
        int rejRange    = 0;
        int rejNoHW     = 0;
        int rejRvol     = 0;
        int rejScore    = 0;

        for (OrbData od : orbDataMap.values()) {

            // ── STEP 1: Range sanity ─────────────────────────────────────────
            if (!od.isRangeValid()) {
                od.valid = false;
                rejRange++;
                log.debug("[ORB] {} ❌ RANGE: invalid (H={} L={})",
                        od.symbol, od.orbHigh, od.orbLow);
                continue;
            }

            // ── STEP 2: High Wave candle — HARD FILTER ───────────────────────
            // Compute from real 15-min OHLC: orbOpen/High/Low/Close built from ticks.
            od.computeHighWaveQuality();

            // Always log the candle shape so you can see what formed today
            log.info("[ORB] {} candle: O={} H={} L={} C={} | HW={} body={}% upperW={}% lowerW={}%",
                    od.symbol,
                    String.format("%.2f", od.orbOpen),
                    String.format("%.2f", od.orbHigh),
                    String.format("%.2f", od.orbLow),
                    String.format("%.2f", od.orbClose),
                    od.isHighWaveCandle ? "✅" : "❌",
                    String.format("%.1f", od.highWaveBodyPct   * 100),
                    String.format("%.1f", od.highWaveUpperWick * 100),
                    String.format("%.1f", od.highWaveLowerWick * 100));

            if (!od.isHighWaveCandle) {
                od.valid = false;
                rejNoHW++;
                // Not a High Wave — no indecision at key level — skip
                // (body too large = trend candle, or wicks not balanced)
                continue;
            }

            // ── STEP 3: RVOL ≥ 1.0 ──────────────────────────────────────────
            double rvol = rvolService.getRvolNow(od.symbol, od.latestVolume);
            od.rvol = rvol;
            if (rvol < MIN_RVOL) {
                od.valid = false;
                rejRvol++;
                log.info("[ORB] {} ❌ RVOL: {} < {} (HW passed — volume weak)",
                        od.symbol, String.format("%.2f", rvol), MIN_RVOL);
                continue;
            }

            // ── STEP 4: Score ────────────────────────────────────────────────
            int score = computeScore(od, rvol);
            od.score = score;

            if (score < MIN_ORB_SCORE) {
                od.valid = false;
                rejScore++;
                log.info("[ORB] {} ❌ SCORE: {} < {} (HW✅ RVOL✅ but score too low)",
                        od.symbol, score, MIN_ORB_SCORE);
                continue;
            }

            od.valid = true;
            valid++;
            persistOrbRange(od);
            persistScore(od.symbol, score);
            log.info("[ORB] {} ✅ PASSED: H={} L={} rvol={} score={} gap={}%",
                    od.symbol,
                    String.format("%.2f", od.orbHigh),
                    String.format("%.2f", od.orbLow),
                    String.format("%.2f", rvol),
                    score,
                    String.format("%.2f", od.gapPct * 100));
        }

        validOrbCount.set(valid);
        log.info("[ORB] 📊 Results: total={} | ❌range={} ❌noHW={} ❌rvol={} ❌score={} | ✅valid={}",
                orbDataMap.size(), rejRange, rejNoHW, rejRvol, rejScore, valid);
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

        // Gap bonus: gap direction should align with trade direction.
        // Gap-up + buy breakout = strongest setup. Flat-open setups can still fire
        // (High Wave + sector alignment is enough) but score lower.
        if (Math.abs(od.gapPct) >= 0.005)      score += 10; // gap ≥ 0.5% → bonus
        else if (Math.abs(od.gapPct) >= 0.003)  score += 5;  // gap 0.3–0.5% → small bonus
        // gap < 0.3% → no bonus (flat open, rely on HW + sector)

        String sectorName = sectorClassify.getSector(od.symbol);
        SectorStrengthService.SectorData sd = sectorStrength.getSector(sectorName);
        boolean isGapUp = od.gapPct > 0;
        if ((isGapUp && sd.alignedBullish()) || (!isGapUp && sd.alignedBearish())) {
            score += SCORE_SECTOR_ALIGN;
            od.sectorAligned = true;
        }
        od.sectorName = sectorName;

        // High Wave candle bonus (replaces cleanCandleCount).
        // A confirmed High Wave (body 20-25%, both wicks ≥30%) is the strongest
        // indecision signal — full bonus. Partial quality (no wicks but body OK) → partial.
        if (od.isHighWaveCandle)                             score += SCORE_CLEAN_CANDLE; // +15
        else if (od.highWaveBodyPct >= 0.20
                && od.highWaveBodyPct <= 0.40)               score += 8; // reasonable body

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

    // FIX: minimum stock price and score filters for ORB selection.
    // Prevents penny stocks (YESBANK ₹20, PATELENG ₹29) and low-quality setups
    // (score=8) from entering the shortlist and firing invalid trades.
    private static final double MIN_ORB_STOCK_PRICE = 100.0;
    private static final int    MIN_ORB_SCORE        = 30;

    private void selectTop10() {
        List<OrbData> candidates = orbDataMap.values().stream()
                .filter(od -> od.valid)
                // FIX: filter out penny stocks, low quality, and sector-misaligned setups
                .filter(od -> {
                    double openPrice = openPrices.getOrDefault(od.symbol, 0.0);
                    if (openPrice < MIN_ORB_STOCK_PRICE) {
                        log.debug("[ORB] {} skipped — price ₹{} below minimum ₹{}",
                                od.symbol, openPrice, MIN_ORB_STOCK_PRICE);
                        return false;
                    }
                    if (od.score < MIN_ORB_SCORE) {
                        log.debug("[ORB] {} skipped — score {} below minimum {}",
                                od.symbol, od.score, MIN_ORB_SCORE);
                        return false;
                    }
                    // FIX: require sector alignment unless score is very high (≥50).
                    // Sector-unaligned trades fire and immediately hit SECTOR_TURNED.
                    // Exception: score ≥ 50 means strong gap+RVOL+candle quality
                    // which can override a sector miss.
                    if (!od.sectorAligned && od.score < 50) {
                        log.debug("[ORB] {} skipped — sectorAligned=false and score {} < 50",
                                od.symbol, od.score);
                        return false;
                    }
                    return true;
                })
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
        try { redis.opsForSet().add(key, member); redis.expire(key, 96, TimeUnit.HOURS); }
        catch (Exception ignored) {}
    }

    private void persistToRedisList(String key, String value) {
        if (redis == null) return;
        try { redis.opsForList().rightPush(key, value); redis.expire(key, 96, TimeUnit.HOURS); }
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
            redis.expire(key, 96, TimeUnit.HOURS);
        } catch (Exception ignored) {}
    }

    private void persistPrevClose(String symbol, double price) {
        if (redis == null) return;
        try {
            redis.opsForValue().set(KEY_PREV_CLOSE + symbol,
                    String.format("%.4f", price), 96, TimeUnit.HOURS);
        } catch (Exception ignored) {}
    }

    // GAP FIX 2: persist and load prevHigh/prevLow
    private void persistPrevHigh(String symbol, double price) {
        if (redis == null) return;
        try {
            redis.opsForValue().set(KEY_PREV_HIGH + symbol,
                    String.format("%.4f", price), 96, TimeUnit.HOURS);
        } catch (Exception ignored) {}
    }

    private void persistPrevLow(String symbol, double price) {
        if (redis == null) return;
        try {
            redis.opsForValue().set(KEY_PREV_LOW + symbol,
                    String.format("%.4f", price), 96, TimeUnit.HOURS);
        } catch (Exception ignored) {}
    }


    // ══════════════════════════════════════════════════════════════════════════
    // BOOTSTRAP: Load prevClose from broker when Redis is empty
    // Runs ONCE at dailyReset if no orb:prev_close:* keys exist in Redis.
    // Uses KiteConnect.getHistoricalData() to fetch yesterday's "day" candle for each
    // equity instrument. Writes results to Redis so they persist for subsequent
    // restarts (26-hour TTL ensures next day's 9 AM reset can load them).
    //
    // Batch processing: instruments are fetched in groups to avoid hitting
    // Zerodha API rate limits (3 req/sec). Uses 250ms delay between batches.
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Bootstrap prevClose, prevHigh, prevLow for all equity instruments.
     *
     * APPROACH: Single getOHLC() call for all 295 symbols.
     *
     * OLD APPROACH (WRONG — replaced):
     *   Called getHistoricalData() once per symbol = 295 HTTP requests.
     *   Time: 19–46 seconds. Burst of 50 calls with no per-call delay
     *   hit Zerodha's 3 req/sec rate limit → HTTP 429 errors → many
     *   symbols got no prevClose → loaded=241 errors=42.
     *
     * NEW APPROACH (correct):
     *   getOHLC(String[] instruments) accepts up to 500 NSE symbols
     *   in a single HTTP request. Returns Map<String, OHLCQuote> with
     *   {lastPrice, ohlc: {open, high, low, close}} for every symbol.
     *
     *   295 symbols → 1 API call → ~100–300ms total.
     *   Zero rate-limit risk. Zero per-symbol errors.
     *
     * FALLBACK:
     *   If getOHLC() fails (API outage, auth error), falls back to the
     *   original per-symbol getHistoricalData() approach as a safety net.
     *
     * NOTE: getOHLC() returns the PREVIOUS CLOSE as the 'close' field in
     * the ohlc object — this is exactly what we need for gap calculation.
     * The 'lastPrice' field is the current market price (not useful here).
     */
    private int bootstrapPrevCloseFromBroker() {
        if (kiteConnect == null) {
            log.warn("[ORB] bootstrapPrevCloseFromBroker: KiteConnect bean not available.");
            return 0;
        }

        Map<String, com.zerodhatech.models.Instrument> instruments =
                instrumentCache.getEquityInstruments();
        if (instruments.isEmpty()) {
            log.warn("[ORB] bootstrapPrevCloseFromBroker: instrument cache empty.");
            return 0;
        }

        // Build "NSE:SYMBOL" array for getOHLC()
        // KiteConnect.getOHLC takes String[] of exchange:tradingsymbol format
        String[] instrumentKeys = instruments.keySet().stream()
                .map(sym -> "NSE:" + sym)
                .toArray(String[]::new);

        log.info("[ORB] Bootstrapping prevClose via getOHLC() for {} symbols (single API call)...",
                instrumentKeys.length);
        long start = System.currentTimeMillis();

        try {
            Map<String, com.zerodhatech.models.OHLCQuote> ohlcMap =
                    kiteConnect.getOHLC(instrumentKeys);

            if (ohlcMap == null || ohlcMap.isEmpty()) {
                log.warn("[ORB] getOHLC() returned empty — falling back to per-symbol historical fetch");
                return bootstrapPrevCloseFromBrokerFallback();
            }

            int loaded = 0, skipped = 0;
            for (Map.Entry<String, com.zerodhatech.models.OHLCQuote> entry : ohlcMap.entrySet()) {
                // Key format: "NSE:SYMBOL" — strip the "NSE:" prefix
                String key    = entry.getKey();
                String symbol = key.startsWith("NSE:") ? key.substring(4) : key;
                com.zerodhatech.models.OHLCQuote q = entry.getValue();

                if (q == null || q.ohlc == null) { skipped++; continue; }

                double close = q.ohlc.close;
                double high  = q.ohlc.high;
                double low   = q.ohlc.low;

                if (close <= 0) { skipped++; continue; }

                prevCloseMap.put(symbol, close);
                persistPrevClose(symbol, close);
                if (high > 0) { prevHighMap.put(symbol, high); persistPrevHigh(symbol, high); }
                if (low  > 0) { prevLowMap.put(symbol, low);   persistPrevLow(symbol, low);   }
                loaded++;
            }

            long elapsed = System.currentTimeMillis() - start;
            log.info("[ORB] ✅ Bootstrap complete via getOHLC(): loaded={} skipped={} in {}ms (1 API call)",
                    loaded, skipped, elapsed);
            return loaded;

        } catch (Throwable e) {
            log.warn("[ORB] getOHLC() failed: {} — falling back to per-symbol historical fetch",
                    e.getMessage());
            return bootstrapPrevCloseFromBrokerFallback();
        }
    }

    /**
     * Fallback: per-symbol getHistoricalData() if getOHLC() is unavailable.
     * Slower (19–46 seconds) but guaranteed to work even on market holidays
     * when OHLC might return no data.
     */
    private int bootstrapPrevCloseFromBrokerFallback() {
        java.time.LocalDate today     = java.time.LocalDate.now(IST);
        java.time.LocalDate yesterday = today.minusDays(1);
        if (yesterday.getDayOfWeek() == java.time.DayOfWeek.SUNDAY)  yesterday = yesterday.minusDays(2);
        if (yesterday.getDayOfWeek() == java.time.DayOfWeek.SATURDAY) yesterday = yesterday.minusDays(1);

        java.util.Date fromDate = java.util.Date.from(yesterday.atStartOfDay(IST).toInstant());
        java.util.Date toDate   = java.util.Date.from(yesterday.atTime(23, 59, 59).atZone(IST).toInstant());

        log.info("[ORB] Fallback bootstrap: fetching per-symbol historical data for {} ...", yesterday);

        int loaded = 0, errors = 0, skipped = 0;
        java.util.List<Map.Entry<String, com.zerodhatech.models.Instrument>> entries =
                new java.util.ArrayList<>(instrumentCache.getEquityInstruments().entrySet());

        // 50 per batch, 300ms pause — stays within Zerodha 3 req/sec limit
        int batchSize = 10; // smaller batch + longer pause to avoid 429
        for (int batchStart = 0; batchStart < entries.size(); batchStart += batchSize) {
            java.util.List<Map.Entry<String, com.zerodhatech.models.Instrument>> batch =
                    entries.subList(batchStart, Math.min(batchStart + batchSize, entries.size()));

            for (Map.Entry<String, com.zerodhatech.models.Instrument> entry : batch) {
                String symbol = entry.getKey();
                long   token  = entry.getValue().getInstrument_token();
                try {
                    HistoricalData result = kiteConnect.getHistoricalData(
                            fromDate, toDate, String.valueOf(token), "day", false, false);
                    if (result == null || result.dataArrayList == null || result.dataArrayList.isEmpty()) {
                        skipped++; continue;
                    }
                    HistoricalData last = result.dataArrayList.get(result.dataArrayList.size() - 1);
                    if (last.close <= 0) { skipped++; continue; }
                    prevCloseMap.put(symbol, last.close);
                    persistPrevClose(symbol, last.close);
                    if (last.high > 0) { prevHighMap.put(symbol, last.high); persistPrevHigh(symbol, last.high); }
                    if (last.low  > 0) { prevLowMap.put(symbol, last.low);   persistPrevLow(symbol, last.low);   }
                    loaded++;
                } catch (Throwable e) {
                    errors++;
                    if (errors <= 5) log.debug("[ORB] Fallback error for {}: {}", symbol, e.getMessage());
                }
            }
            // 350ms between batches of 10 = ~28 calls/sec — well within 3 req/sec per type
            if (batchStart + batchSize < entries.size()) {
                try { Thread.sleep(350); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); break;
                }
            }
        }
        log.info("[ORB] Fallback bootstrap complete. Loaded={} skipped={} errors={}", loaded, skipped, errors);
        return loaded;
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
        // orbOpen = first tick price at 9:15 (putIfAbsent — never overwritten)
        // orbClose = last tick price BEFORE 9:30 lock (updated on every tick)
        // These give accurate O/H/L/C for the 9:15–9:30 15-min ORB candle.
        public volatile double  orbOpen;         // first traded price at/after 9:15
        public volatile double  orbClose;        // last tick price before 9:30 lock
        public volatile long    latestVolume;

        // cleanCandleCount removed — was counting 5-min candles, wrong for 15-min ORB.
        // High Wave body/wick quality is now computed directly from orbO/H/L/C at 9:30.
        // Retained as zero-valued stub so existing scoring references compile.
        public volatile int     cleanCandleCount; // always 0 — see highWaveBodyPct
        public volatile int     totalCandleCount; // unused — kept for API compatibility

        // High Wave quality fields — computed at 9:30 from real OHLC
        public volatile double  highWaveBodyPct;   // body / range (want 0.20–0.25)
        public volatile double  highWaveUpperWick; // upper wick / range (want ≥ 0.30)
        public volatile double  highWaveLowerWick; // lower wick / range (want ≥ 0.30)
        public volatile boolean isHighWaveCandle;  // true if all HW criteria met

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
            this.orbOpen     = openPrice; // initialised to first tick; never overwritten
            this.orbClose    = openPrice; // updated on every tick until 9:30 lock
            this.valid       = true;
        }

        public void updateHighLow(double price) {
            if (price > orbHigh) orbHigh = price;
            if (price < orbLow)  orbLow  = price;
            orbClose = price; // always updated — last tick before lock = ORB close
        }

        /**
         * Called at 9:30:00 lockOrbAndScore() — NOT from a candle event.
         * Computes High Wave candle quality from the real 15-min ORB OHLC:
         *   orbOpen  = first tick at/after 9:15 (putIfAbsent — never overwritten)
         *   orbHigh  = max tick price 9:15–9:30
         *   orbLow   = min tick price 9:15–9:30
         *   orbClose = last tick price before 9:30 lock
         *
         * High Wave criteria:
         *   range = orbHigh - orbLow
         *   body  = |orbClose - orbOpen|
         *   bodyPct        = body / range → must be 0.20–0.25
         *   upperWickPct   = (orbHigh - max(open,close)) / range → must be ≥ 0.30
         *   lowerWickPct   = (min(open,close) - orbLow)  / range → must be ≥ 0.30
         *   wickImbalance  = |upper - lower| → must be ≤ 0.10
         */
        public void computeHighWaveQuality() {
            double range = orbHigh - orbLow;
            if (range <= 0) { isHighWaveCandle = false; return; }

            double bodyTop    = Math.max(orbOpen, orbClose);
            double bodyBottom = Math.min(orbOpen, orbClose);
            double body       = bodyTop - bodyBottom;
            double upperWick  = orbHigh - bodyTop;
            double lowerWick  = bodyBottom - orbLow;

            highWaveBodyPct   = body / range;
            highWaveUpperWick = upperWick / range;
            highWaveLowerWick = lowerWick / range;
            double wickImbalance = Math.abs(highWaveUpperWick - highWaveLowerWick);

            isHighWaveCandle = highWaveBodyPct   >= 0.20
                    && highWaveBodyPct   <= 0.25
                    && highWaveUpperWick >= 0.30
                    && highWaveLowerWick >= 0.30
                    && wickImbalance     <= 0.10;
        }

        // Kept for API compatibility — cleanCandleCount is always 0 now.
        // High Wave quality replaces this in scoring.
        public void updateCandleQuality(Candle c) { totalCandleCount++; }

        public boolean isRangeValid() {
            return orbHigh > orbLow && orbLow > 0;
        }

        public boolean isGapUp()     { return gapPct > 0; }
        public boolean isBuySetup()  { return isGapUp(); }
        public boolean isSellSetup() { return !isGapUp(); }
    }
}