package com.trading.strategy.channel;

import com.trading.analysis.service.RvolService;
import com.trading.analysis.service.TechnicalAnalysisService;
import com.trading.domain.Candle;
import com.trading.domain.enums.TradeDirection;
import com.trading.events.CandleCompleteEvent;
import com.trading.events.SmartChannelPullbackSignalEvent;
import com.trading.marketdata.service.LatencyMonitor;
import com.trading.marketdata.service.MarketTimingService;
import com.trading.papertrading.model.PaperAccount;
import com.trading.position.service.PositionSizerService;
import com.trading.risk.service.CircuitBreakerService;
import com.trading.sector.service.SectorClassificationService;
import com.trading.sector.service.SectorStrengthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SmartChannelPullbackStrategy v2 — Sector-Based Direction (Gate 1 replaced).
 *
 * CHANGE: Gate 1 (Market Trend Direction) completely removed and replaced with
 * "Sector 15M Direction" gate that derives trade direction purely from sector
 * metrics — no global market index, no VIX, no ATR/EMA on Nifty.
 *
 * REMOVED from this class:
 *   - MarketDirectionService dependency (import + field + Gate 1 call)
 *   - VixService dependency (import + field + vixService.isTradeAllowed())
 *   - isTrendTradeable() / isTradeable() checks
 *   - SIDEWAYS market blocking
 *   - ATR frozen/chaotic condition blocking
 *   - dir.direction() == BULLISH logic (replaced by sector direction)
 *   - All related debug logs ([DEBUG] 15M trend, [SCPS] VIX blocked, etc.)
 *
 * NEW Gate 1 — SECTOR 15M DIRECTION:
 *   sectorStrength.getSectorDirection(sectorName) → SectorDirectionResult
 *   BULLISH:  changePercent ≥ +0.30% AND greenPct ≥ 55% AND RS ≥ 0.0
 *   BEARISH:  changePercent ≤ -0.30% AND greenPct ≤ 45% AND RS ≤ 0.0
 *   NEUTRAL:  skip — no directional bias for this symbol's sector
 *   isBull derived from sector direction (not Nifty EMA stack)
 *
 * NOTE: sectorName is resolved BEFORE Gate 1 (needed for direction lookup),
 * so Gate 2 (sector threshold validation) is now a confirming refinement check.
 *
 * All other gates (2–7.5), scoring, signal construction, lock management,
 * position sizing, 4-phase trade management — COMPLETELY UNCHANGED.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SmartChannelPullbackStrategy {

    private static final ZoneId    IST           = ZoneId.of("Asia/Kolkata");
    private static final String    STRATEGY_NAME = "SMART_CHANNEL_PULLBACK_V2";
    private static final LocalTime ENTRY_START   = LocalTime.of(9, 40);
    private static final LocalTime ENTRY_END     = LocalTime.of(14, 40);
    private static final double    SECTOR_BUY    =  0.003; // +0.30%
    private static final double    SECTOR_SELL   = -0.003; // -0.30%
    private static final double    PB_BEST_MIN   = 0.003;
    private static final double    PB_BEST_MAX   = 0.005;
    private static final double    PB_GOOD_MAX   = 0.008;
    private static final double    PB_LATE_MAX   = 0.010;
    private static final long      MAX_CH_AGE_MIN = 15L;
    private static final long      COOLDOWN_MS   = 3_600_000L;

    // ── Dependencies ──────────────────────────────────────────────────────
    // NOTE: MarketDirectionService and VixService intentionally removed.
    //       MarketTimingService kept for window validation.
    private final ApplicationEventPublisher   publisher;
    private final SectorStrengthService       sectorStrength;   // Gate 1 source of truth
    private final SectorClassificationService sectorClassify;
    private final ChannelDetectionService     channelDetection;
    private final RvolService                 rvolService;
    private final TechnicalAnalysisService    technicalAnalysis;
    private final MarketTimingService         timingService;
    private final CircuitBreakerService       circuitBreaker;
    private final PositionSizerService        positionSizer;
    private final PaperAccount                paperAccount;
    private final LatencyMonitor              latencyMonitor;

    @Value("${trading.mode:PAPER}")          private String     tradingMode;
    @Value("${trading.capital:100000}")      private BigDecimal capital;
    @Value("${strategy.smart-channel-pullback.enabled:true}")
    private boolean strategyEnabled;
    @Value("${strategy.smart-channel-pullback.time-stop-minutes:60}")
    private int     timeStopMinutes;
    @Value("${strategy.smart-channel-pullback.min-rvol:1.0}")
    private double  minRvol;
    @Value("${strategy.smart-channel-pullback.require-high-quality-channel:false}")
    private boolean requireHQ;
    @Value("${strategy.smart-channel-pullback.max-signals-per-session:3}")
    private int     maxSignals;

    // ── Per-session state ─────────────────────────────────────────────────
    // signalLock: atomic duplicate prevention (putIfAbsent is ConcurrentHashMap atomic)
    private final Map<String, Long> signalLock     = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSignalTime = new ConcurrentHashMap<>();
    private volatile int sessionSignalCount = 0;

    // ── Main trigger: every completed 5M candle ───────────────────────────

    @EventListener
    @Async("tradingExecutor")
    public void onCandle(CandleCompleteEvent event) {
        Candle c = event.getCandle();
        if (!"5minute".equals(c.getTimeframe()) || !c.isComplete()) return;
        if (!strategyEnabled || latencyMonitor.isStale()) return;

        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(ENTRY_START) || now.isAfter(ENTRY_END)) return;

        BigDecimal cap = resolveCapital();
        if (!circuitBreaker.checkPermission(cap).isAllowed()) return;
        if (sessionSignalCount >= maxSignals) return;

        log.info("[INFO] Market data received — {} at {}", c.getTradingSymbol(), now);
        evaluateStock(c.getTradingSymbol(), c, cap);
    }

    // ── Atomic lock wrapper ───────────────────────────────────────────────

    private void evaluateStock(String symbol, Candle candle, BigDecimal cap) {
        if (signalLock.putIfAbsent(symbol, System.currentTimeMillis()) != null) return;
        boolean signalFired = false;
        try {
            signalFired = runGates(symbol, candle, cap);
        } finally {
            if (!signalFired) signalLock.remove(symbol);
        }
    }

    // ── Full gate pipeline ────────────────────────────────────────────────

    private boolean runGates(String symbol, Candle candle, BigDecimal cap) {

        // Cooldown: 60 min between signals for same symbol
        Long last = lastSignalTime.get(symbol);
        if (last != null && System.currentTimeMillis() - last < COOLDOWN_MS) return false;

        // ═════════════════════════════════════════════════════════════════
        // GATE 1: SECTOR 15M DIRECTION
        // Replaces: MarketDirectionService.getCurrentDirection()
        // Source of trade direction = sector metrics ONLY (no Nifty, no VIX).
        //
        // SectorDirectionResult.direction:
        //   BULLISH  → isBull=true  → LONG setup
        //   BEARISH  → isBull=false → SHORT setup
        //   NEUTRAL  → skip (sector has no clear intraday bias)
        //
        // Algorithm (inside SectorStrengthService.getSectorDirection):
        //   BULLISH:  changePercent ≥ +0.30% AND greenPct ≥ 55% AND RS ≥ 0.0
        //   BEARISH:  changePercent ≤ -0.30% AND greenPct ≤ 45% AND RS ≤ 0.0
        //   NEUTRAL:  anything else → no trade
        // ═════════════════════════════════════════════════════════════════
        String sectorName = sectorClassify.getSector(symbol);
        SectorStrengthService.SectorDirectionResult sectorDir =
                sectorStrength.getSectorDirection(sectorName);

        log.debug("[GATE1] {} sector={} direction={} change={}% greenPct={}% conf={}",
                symbol, sectorName, sectorDir.direction(),
                String.format("%.2f", sectorDir.changePercent()),
                String.format("%.1f", sectorDir.greenPct()),
                String.format("%.2f", sectorDir.confidence()));

        if (!sectorDir.isTradeable()) {
            log.trace("[SCPS] {} sector NEUTRAL — no directional bias, skip", symbol);
            return false;
        }

        boolean      isBull = sectorDir.isBull();
        String       bias   = sectorDir.direction().name();
        TradeDirection td   = isBull ? TradeDirection.LONG : TradeDirection.SHORT;

        log.info("[INFO] Sector direction locked: {} → {} ({})", symbol, bias, sectorName);

        // ═════════════════════════════════════════════════════════════════
        // GATE 2: SECTOR THRESHOLD CONFIRMATION
        // Now a confirming check — direction already from Gate 1.
        // Validates that the sector change% is above minimum threshold.
        // Re-uses same sectorName from Gate 1 (no redundant getSector() call).
        // ═════════════════════════════════════════════════════════════════
        SectorStrengthService.SectorData sd = sectorStrength.getSector(sectorName);
        double sc   = sd.changePercent() / 100.0;
        boolean dirOk = isBull ? sc >= 0 : sc <= 0;
        boolean thrOk = isBull ? sc >= SECTOR_BUY : sc <= SECTOR_SELL;

        if (log.isTraceEnabled())
            log.trace("[TRACE] Checking sector: {} → {}%",
                    sectorName, String.format("%.2f", sd.changePercent()));
        log.debug("[DEBUG] Sector validation: Direction match: {} | Threshold pass: {}",
                dirOk ? "YES" : "NO", thrOk ? "YES" : "NO");

        if (!dirOk || !thrOk) return false;
        log.info("[INFO] Sector selected: {}", sectorName);

        // ═════════════════════════════════════════════════════════════════
        // GATE 3: STOCK VWAP ALIGNMENT (15M proxy)
        // ═════════════════════════════════════════════════════════════════
        TechnicalAnalysisService.TechnicalStructure ts = technicalAnalysis.getStructure(symbol);
        BigDecimal vwap = ts.vwap();
        if (vwap != null && vwap.compareTo(BigDecimal.ZERO) != 0) {
            boolean aligned = isBull
                    ? candle.getClose().compareTo(vwap) >= 0
                    : candle.getClose().compareTo(vwap) <= 0;
            if (!aligned) {
                log.trace("[SCPS] {} VWAP misaligned (close={} vwap={})",
                        symbol, candle.getClose(), vwap);
                return false;
            }
        }

        // ═════════════════════════════════════════════════════════════════
        // GATE 4: 5M CHANNEL VALIDATION
        // ═════════════════════════════════════════════════════════════════
        log.debug("[DEBUG] Stock scan: {}", symbol);
        ChannelDetectionService.ChannelResult ch = channelDetection.getChannel(symbol);

        log.debug("[DEBUG] Channel: Support touches: {} | Resistance touches: {} | Status: {}",
                ch.supportLine() != null ? ch.supportLine().touches() : 0,
                ch.resistanceLine() != null ? ch.resistanceLine().touches() : 0,
                ch.isValid() ? "VALID" : "INVALID");

        if (!ch.isValid()) {
            log.trace("[SCPS] {} no valid channel: {}", symbol, ch.reason());
            return false;
        }
        if (ch.ageInMinutes() > MAX_CH_AGE_MIN) {
            log.trace("[SCPS] {} channel stale ({}min)", symbol, ch.ageInMinutes());
            return false;
        }
        if (requireHQ && !ch.isHighQuality()) {
            log.trace("[SCPS] {} requires HIGH_QUALITY channel", symbol);
            return false;
        }
        boolean typeOk = (isBull  && ch.type() == ChannelDetectionService.ChannelType.BULLISH)
                || (!isBull && ch.type() == ChannelDetectionService.ChannelType.BEARISH);
        if (!typeOk) {
            log.trace("[SCPS] {} channel type {} mismatches sector bias {}",
                    symbol, ch.type(), bias);
            return false;
        }

        // ═════════════════════════════════════════════════════════════════
        // GATE 5: PULLBACK RULE (0.3–1.0% from trendline)
        // ═════════════════════════════════════════════════════════════════
        double price = candle.getClose().doubleValue();
        if (!ch.isPriceInPullbackZone(price)) {
            if (log.isTraceEnabled())
                log.trace("[SCPS] {} price {} not in pullback zone [{},{}]",
                        symbol,
                        String.format("%.2f", price),
                        String.format("%.2f", ch.pullbackZoneBottom()),
                        String.format("%.2f", ch.pullbackZoneTop()));
            return false;
        }

        double pbPct = isBull
                ? (price - ch.supportPrice())    / ch.supportPrice()
                : (ch.resistancePrice() - price) / ch.resistancePrice();

        log.debug("[DEBUG] Pullback: {}%", String.format("%.2f", pbPct * 100));

        if (pbPct > PB_LATE_MAX) {
            if (log.isTraceEnabled())
                log.trace("[SCPS] {} pullback {}% too deep (>1%)",
                        symbol, String.format("%.2f", pbPct * 100));
            return false;
        }

        String pbStrength;
        if      (pbPct >= PB_BEST_MIN && pbPct <= PB_BEST_MAX) pbStrength = "BEST";
        else if (pbPct >  PB_BEST_MAX && pbPct <= PB_GOOD_MAX) pbStrength = "GOOD";
        else if (pbPct >  PB_GOOD_MAX && pbPct <= PB_LATE_MAX) pbStrength = "LATE";
        else {
            log.trace("[SCPS] {} pullback {}% too shallow", symbol,
                    String.format("%.2f", pbPct * 100));
            return false; // TOO_EARLY
        }
        log.info("[INFO] Stock selected: {}", symbol);

        // ═════════════════════════════════════════════════════════════════
        // GATE 6: REJECTION CANDLE
        // ═════════════════════════════════════════════════════════════════
        if (!isRejectionCandle(candle, isBull)) {
            log.trace("[SCPS] {} no rejection candle at {}",
                    symbol, isBull ? "support" : "resistance");
            return false;
        }

        // ═════════════════════════════════════════════════════════════════
        // GATE 7: OVEREXTENSION FILTER
        // ═════════════════════════════════════════════════════════════════
        double dayChg = Math.abs(price - ch.supportPrice()) / ch.supportPrice();
        if (isOverextended(dayChg, getCapType(symbol))) {
            if (log.isTraceEnabled())
                log.trace("[SCPS] {} overextended {}%",
                        symbol, String.format("%.2f", dayChg * 100));
            return false;
        }

        // ═════════════════════════════════════════════════════════════════
        // BUILD SIGNAL — SL, TARGETS
        // ═════════════════════════════════════════════════════════════════
        BigDecimal entry = candle.getClose().setScale(2, RoundingMode.HALF_UP);
        BigDecimal sl    = isBull
                ? BigDecimal.valueOf(ch.supportPrice() * 0.998).setScale(2, RoundingMode.FLOOR)
                : BigDecimal.valueOf(ch.resistancePrice() * 1.002).setScale(2, RoundingMode.CEILING);
        BigDecimal risk  = entry.subtract(sl).abs();
        if (risk.compareTo(BigDecimal.ZERO) == 0) return false;

        BigDecimal t1 = isBull
                ? entry.add(risk.multiply(BigDecimal.valueOf(2)))
                : entry.subtract(risk.multiply(BigDecimal.valueOf(2)));
        BigDecimal t2 = isBull
                ? entry.add(risk.multiply(BigDecimal.valueOf(3)))
                : entry.subtract(risk.multiply(BigDecimal.valueOf(3)));

        // ═════════════════════════════════════════════════════════════════
        // GATE 7.5: SLIPPAGE-ADJUSTED RR PRE-FLIGHT (≥1.8)
        // ═════════════════════════════════════════════════════════════════
        double slip   = entry.doubleValue() * 0.0005; // 0.05% slippage
        double adjRR  = (t1.subtract(entry).abs().doubleValue() - slip)
                / (risk.doubleValue() + slip);
        if (adjRR < 1.8) {
            if (log.isTraceEnabled())
                log.trace("[SCPS] {} adj-RR {} < 1.8 — reject",
                        symbol, String.format("%.2f", adjRR));
            return false;
        }

        // ═════════════════════════════════════════════════════════════════
        // POSITION SIZING
        // ═════════════════════════════════════════════════════════════════
        PositionSizerService.PositionSize pos =
                positionSizer.calculate(cap, entry, sl, symbol, td.name());
        if (!pos.isValid() || pos.quantity() <= 0) {
            log.trace("[SCPS] {} invalid position size: {}", symbol, pos.invalidReason());
            return false;
        }

        // ═════════════════════════════════════════════════════════════════
        // SCORING (0–100)
        // ═════════════════════════════════════════════════════════════════
        double rvol = rvolService.getRvolNow(symbol, candle.getVolume());
        boolean vwapAligned = vwap != null && vwap.compareTo(BigDecimal.ZERO) != 0
                && (isBull ? candle.getClose().compareTo(vwap) >= 0
                : candle.getClose().compareTo(vwap) <= 0);

        int sVwap  = vwapAligned ? 15 : 0;
        int sRvol  = rvol >= 1.5 ? 20 : rvol >= 1.2 ? 10 : 0;
        int sCont  = (isBull ? candle.isBullish() : candle.isBearish()) ? 15 : 0;
        int sClean = Math.abs(price - (isBull ? ch.supportPrice() : ch.resistancePrice()))
                <= (isBull ? ch.supportPrice() : ch.resistancePrice()) * 0.002 ? 20 : 0;
        int sEarly = "BEST".equals(pbStrength) ? 15 : 0;
        int sNoSR  = !hasNearbyStructure(entry, ts, isBull) ? 15 : 0;
        int total  = sVwap + sRvol + sCont + sClean + sEarly + sNoSR;

        log.debug("[DEBUG] Score: vwap={} rvol={} cont={} clean={} early={} noSR={} TOTAL={}",
                sVwap, sRvol, sCont, sClean, sEarly, sNoSR, total);

        // ═════════════════════════════════════════════════════════════════
        // FIRE SIGNAL
        // ═════════════════════════════════════════════════════════════════
        log.info("[INFO] Entry signal generated: {} | dir={} entry={} sl={} T1={} T2={} score={}",
                symbol, td, entry, sl, t1, t2, total);
        log.info("[INFO] Order placed: LIMIT @ {} | qty={} | risk=₹{} | RR={}",
                entry, pos.quantity(),
                String.format("%.2f", pos.actualRisk().doubleValue()),
                String.format("%.2f", adjRR));

        publisher.publishEvent(new SmartChannelPullbackSignalEvent(
                this, symbol, candle.getInstrumentToken(), td,
                entry, sl, t1, t2, pos.quantity(), pos.actualRisk(),
                STRATEGY_NAME, total,
                sectorName, sd.changePercent(),
                ch.isHighQuality() ? "HIGH_QUALITY" : "VALID",
                pbStrength, pbPct, rvol, vwapAligned, "LIMIT", bias,
                sVwap, sRvol, sCont, sClean, sEarly, sNoSR, total,
                timeStopMinutes));

        lastSignalTime.put(symbol, System.currentTimeMillis());
        sessionSignalCount++;
        log.info("[INFO] Execution confirmed — signal #{} this session: {}",
                sessionSignalCount, symbol);
        return true; // signal fired — lock stays until trade closes
    }

    // ── Entry quality helpers (UNCHANGED) ─────────────────────────────────

    private boolean isRejectionCandle(Candle c, boolean bull) {
        double o  = c.getOpen().doubleValue(),  h  = c.getHigh().doubleValue();
        double l  = c.getLow().doubleValue(),   cl = c.getClose().doubleValue();
        double range = h - l;
        if (range == 0) return false;
        double body = Math.abs(cl - o), bodyPct = body / range;
        if (bull) {
            double lw = Math.min(o, cl) - l, cp = (cl - l) / range;
            return lw >= body && cp >= 0.5 && bodyPct <= 0.6;
        } else {
            double uw = h - Math.max(o, cl), cp = (cl - l) / range;
            return uw >= body && cp <= 0.5 && bodyPct <= 0.6;
        }
    }

    private boolean isOverextended(double chg, String capType) {
        return chg >= (capType.equals("LARGE") ? 0.03 : capType.equals("SMALL") ? 0.06 : 0.05);
    }

    private String getCapType(String s) {
        return Set.of("RELIANCE","TCS","HDFCBANK","INFY","ICICIBANK","HINDUNILVR","ITC",
                        "SBIN","BHARTIARTL","KOTAKBANK","LT","BAJFINANCE","HCLTECH","ASIANPAINT",
                        "AXISBANK","MARUTI","SUNPHARMA","TITAN","BAJAJFINSV","ULTRACEMCO","ONGC",
                        "WIPRO","TECHM","NTPC","POWERGRID","JSWSTEEL","TATAMOTORS","TATASTEEL")
                .contains(s) ? "LARGE" : "MID";
    }

    private boolean hasNearbyStructure(BigDecimal entry,
                                       TechnicalAnalysisService.TechnicalStructure ts,
                                       boolean bull) {
        double p = entry.doubleValue(), tol = p * 0.005;
        return (bull ? ts.resistanceZones() : ts.supportZones()).stream()
                .anyMatch(z -> Math.abs(z.doubleValue() - p) < tol);
    }

    // ── Lifecycle (UNCHANGED) ─────────────────────────────────────────────

    /** Called by SmartChannelSignalHandler when a trade closes */
    public void onSignalClosed(String symbol) {
        signalLock.remove(symbol);
        log.debug("[SCPS] Lock released: {} — ready for next setup", symbol);
    }

    @Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyReset() {
        signalLock.clear();
        lastSignalTime.clear();
        sessionSignalCount = 0;
        log.info("[SCPS] Daily reset complete");
    }

    // ── Capital resolution (UNCHANGED) ────────────────────────────────────

    private BigDecimal resolveCapital() {
        return "PAPER".equalsIgnoreCase(tradingMode) ? paperAccount.getCapital() : capital;
    }

    // ── Dashboard helpers (UNCHANGED) ─────────────────────────────────────

    public int     getSessionSignalCount()  { return sessionSignalCount; }
    public int     getActiveSignalCount()   { return signalLock.size(); }
    public Set<String> getActiveSignals()   { return Collections.unmodifiableSet(signalLock.keySet()); }
    public boolean isEnabled()              { return strategyEnabled; }
}