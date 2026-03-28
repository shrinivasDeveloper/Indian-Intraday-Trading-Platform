package com.trading.scanner.service;

import com.trading.analysis.service.KeyLevelService;
import com.trading.domain.Candle;
import com.trading.domain.enums.TradeDirection;
import com.trading.events.CandleCompleteEvent;
import com.trading.events.ScannerSignalEvent;
import com.trading.events.TickReceivedEvent;
import com.trading.marketdata.service.GapDataService;
import com.trading.marketdata.service.MarketTimingService;
import com.trading.marketdata.service.VixService;
import com.trading.regime.service.MarketDirectionService;
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
import java.math.MathContext;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SevenGateScannerService — Institutional Grade
 *
 * UPGRADED:
 *
 * Gate 3 — Compression (was: BB Width only):
 *   Now also checks for NR7 (Narrowest Range of last 7 candles) and
 *   Inside Bar patterns. These are the exact setups professional Indian
 *   traders look for before a breakout — tight price action that indicates
 *   institutional accumulation/distribution before the move.
 *
 *   NR7:  Today's high-low range is the smallest of the last 7 candles.
 *         This is a classic volatility squeeze. The move that follows NR7
 *         is typically 2-3x the NR7 range. Used by SEBI-registered traders.
 *
 *   Inside Bar: High < previous candle high AND Low > previous candle low.
 *         The market is "digesting" the previous move, coiling for a break.
 *         Extremely reliable when combined with BB compression.
 *
 *   Volume Contraction: Volume declining over 3 candles confirms compression.
 *         Institutional selling/buying is done — retail is quiet = coiled spring.
 *
 * Gate 4 — Breakout Trigger (was: fixed 2x volume multiplier):
 *   Now uses TIME-RELATIVE volume multiplier. 1.5x at 9:30 is normal
 *   (early morning volume is always high). 1.5x at 1:30 PM is MASSIVE.
 *
 *   Time-relative multipliers:
 *     09:15–10:00 → 1.5x  (opening flush, volume naturally elevated)
 *     10:00–12:00 → 1.8x  (mid-morning, meaningful spike)
 *     12:00–13:30 → 2.2x  (lunch session, high volume = institutional)
 *     13:30–14:40 → 1.7x  (afternoon, decent spike)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SevenGateScannerService {

    private final ApplicationEventPublisher   publisher;
    private final MarketDirectionService      marketDirection;
    private final SectorStrengthService       sectorStrength;
    private final SectorClassificationService sectorClassify;
    private final VixService                  vixService;
    private final MarketTimingService         timingService;
    private final GapDataService              gapData;
    private final KeyLevelService             keyLevelService;

    @Value("${scanner.entry-mode:AGGRESSIVE}")  private String entryMode;
    @Value("${scanner.bb-width-max:2.0}")        private double bbWidthMax;
    @Value("${scanner.max-sl-pct:2.0}")          private double maxSlPct;
    @Value("${scanner.min-price:50}")             private double minPrice;
    @Value("${scanner.min-volume:500000}")        private long   minVolume;
    @Value("${scanner.cooldown-seconds:1800}")    private long   cooldownSeconds;
    @Value("${scanner.retest-tolerance:0.003}")   private double retestTolerance;
    @Value("${scanner.min-gap-pct:0.5}")          private double minGapPct;
    @Value("${trading.capital:100000}")           private String capitalStr;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final Map<String, Deque<Candle>> buffers5m      = new ConcurrentHashMap<>();
    private final Map<String, Deque<Candle>> buffers15m     = new ConcurrentHashMap<>();
    private final Map<String, ArmedStock>    armedStocks    = new ConcurrentHashMap<>();
    private final Map<String, Integer>       gateRejections = new ConcurrentHashMap<>();
    private final Map<String, Instant>       cooldownMap    = new ConcurrentHashMap<>();
    private final Map<String, Integer>       reentryCount   = new ConcurrentHashMap<>();
    private final Map<String, LocalDate>     slHitDate      = new ConcurrentHashMap<>();

    public record ArmedStock(
            String                 symbol,
            long                   token,
            TradeDirection         direction,
            BigDecimal             compressionHigh,
            BigDecimal             compressionLow,
            BigDecimal             stopLoss,
            BigDecimal             target,
            BigDecimal             vwap,
            double                 atr,
            GapDataService.GapType gapType,
            Instant                armedAt,
            boolean                isReentry,
            double                 minRR
    ) {}

    // ══════════════════════════════════════════════════════════════════════
    // LAYER 1+2 — Candle-based gates
    // ══════════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tradingExecutor")
    public void onCandle(CandleCompleteEvent event) {
        Candle c = event.getCandle();
        if ("5minute".equals(c.getTimeframe())) {
            Deque<Candle> buf = buffers5m.computeIfAbsent(
                    c.getTradingSymbol(), k -> new ArrayDeque<>());
            buf.addFirst(c);
            if (buf.size() > 200) ((ArrayDeque<Candle>) buf).removeLast();
            scan5min(c);
        }
        if ("15minute".equals(c.getTimeframe())) {
            Deque<Candle> buf = buffers15m.computeIfAbsent(
                    c.getTradingSymbol(), k -> new ArrayDeque<>());
            buf.addFirst(c);
            if (buf.size() > 50) ((ArrayDeque<Candle>) buf).removeLast();
        }
    }

    private void scan5min(Candle c) {
        String sym = c.getTradingSymbol();

        if (timingService.isObservationPeriod()) return;
        if (!timingService.isEntryAllowed()) return;
        if (!vixService.isTradeAllowed()) { reject("VIX_EXTREME", sym); return; }
        if (isInCooldown(sym)) { reject("COOLDOWN", sym); return; }

        List<Candle> history5m = new ArrayList<>(
                buffers5m.getOrDefault(sym, new ArrayDeque<>()));
        if (history5m.size() < 50) return;

        // ── Gate 1: Market Direction ──────────────────────────────────────────
        MarketDirectionService.MarketDirectionResult dir =
                marketDirection.getCurrentDirection();
        if (!dir.isTradeable()) {
            reject("GATE1_MARKET_DIRECTION", sym);
            return;
        }
        boolean forLong = dir.isLong();

        // ── Gate 2: Sector Alignment ──────────────────────────────────────────
        if (!sectorStrength.isSectorAligned(sym, forLong)) {
            reject("GATE2_SECTOR", sym);
            return;
        }

        // ── Gate 3: Compression (UPGRADED — NR7 + Inside Bar + BB) ───────────
        CompressionResult compression = checkCompression(history5m);
        if (!compression.passed()) {
            reject("GATE3_COMPRESSION", sym);
            log.debug("Gate3 FAIL {}: {}", sym, compression.failReason());
            return;
        }

        // ── Gate 5: Key Level ─────────────────────────────────────────────────
        KeyLevelService.KeyLevelResult keyLevels = keyLevelService.getKeyLevels(sym);
        BigDecimal entryLevel = forLong ? compression.high() : compression.low();
        if (!checkKeyLevel(entryLevel, keyLevels, forLong)) {
            reject("GATE5_KEY_LEVEL", sym);
            return;
        }

        // ── Gate 6: Liquidity ─────────────────────────────────────────────────
        if (!checkLiquidity(history5m, c)) {
            reject("GATE6_LIQUIDITY", sym);
            return;
        }

        // ── Gate 7: Risk Gate ─────────────────────────────────────────────────
        RiskResult risk = checkRisk(sym, entryLevel, compression, keyLevels, forLong);
        if (!risk.passed()) {
            reject("GATE7_RISK", sym);
            log.debug("Gate7 FAIL {}: {}", sym, risk.failReason());
            return;
        }

        TradeDirection tradeDir = forLong ? TradeDirection.LONG : TradeDirection.SHORT;
        GapDataService.GapType gap = gapData.getGapType(sym);
        boolean isReentry = reentryCount.getOrDefault(sym, 0) > 0;

        ArmedStock armed = new ArmedStock(
                sym, c.getInstrumentToken(), tradeDir,
                compression.high(), compression.low(),
                risk.stopLoss(), risk.target(),
                keyLevels.vwap(), compression.atr(),
                gap, Instant.now(), isReentry, risk.minRR()
        );

        armedStocks.put(sym, armed);
        log.info("ARMED: {} dir={} compHigh={} compLow={} sl={} tgt={} RR={} gap={} nr7={} insideBar={}",
                sym, tradeDir,
                compression.high(), compression.low(),
                risk.stopLoss(), risk.target(),
                String.format("%.2f", risk.rr()),
                gap, compression.isNr7(), compression.isInsideBar());
    }

    // ══════════════════════════════════════════════════════════════════════
    // LAYER 3 — Gate 4: Tick-based breakout (UPGRADED — time-relative volume)
    // ══════════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tradingExecutor")
    public void onTick(TickReceivedEvent tick) {
        String sym = tick.getTradingSymbol();
        ArmedStock armed = armedStocks.get(sym);
        if (armed == null) return;
        if (!timingService.isEntryAllowed()) return;

        BigDecimal ltp = tick.getLastTradedPrice();

        boolean longBreakout  = armed.direction() == TradeDirection.LONG
                && ltp.compareTo(armed.compressionHigh()) > 0;
        boolean shortBreakout = armed.direction() == TradeDirection.SHORT
                && ltp.compareTo(armed.compressionLow()) < 0;

        if (!longBreakout && !shortBreakout) return;

        List<Candle> history = new ArrayList<>(
                buffers5m.getOrDefault(sym, new ArrayDeque<>()));
        if (history.isEmpty()) return;

        Candle current = history.get(0);

        // ── Gate 4: Breakout Validation ───────────────────────────────────────

        // TIME-RELATIVE volume multiplier (institutional upgrade)
        double volMultiplier = getTimeRelativeVolumeMultiplier();
        double avgVol = history.subList(1, Math.min(21, history.size()))
                .stream().mapToLong(Candle::getVolume).average().orElse(0);

        if (current.getVolume() < avgVol * volMultiplier) {
            reject("GATE4_VOLUME", sym);
            log.debug("Gate4 FAIL {}: vol {:.0f} < {:.0f}x avg {:.0f} (time-relative multiplier)",
                    sym, (double)current.getVolume(), volMultiplier, avgVol);
            return;
        }

        // Body strength: candle body >= 60% of range
        if (current.bodyPct().compareTo(new BigDecimal("0.60")) < 0) {
            reject("GATE4_BODY_STRENGTH", sym);
            return;
        }

        // VWAP filter
        if (armed.vwap().compareTo(BigDecimal.ZERO) > 0) {
            boolean aboveVwap = ltp.compareTo(armed.vwap()) > 0;
            if (armed.direction() == TradeDirection.LONG && !aboveVwap) {
                reject("GATE4_VWAP", sym); return;
            }
            if (armed.direction() == TradeDirection.SHORT && aboveVwap) {
                reject("GATE4_VWAP", sym); return;
            }
        }

        // Buy/sell pressure: institutional confirmation
        long buyVol  = tick.getTotalBuyQuantity();
        long sellVol = tick.getTotalSellQuantity();
        if (armed.direction() == TradeDirection.LONG && buyVol <= sellVol) {
            reject("GATE4_BUY_PRESSURE", sym); return;
        }
        if (armed.direction() == TradeDirection.SHORT && sellVol <= buyVol) {
            reject("GATE4_SELL_PRESSURE", sym); return;
        }

        // Conservative mode: wait for retest
        if ("CONSERVATIVE".equalsIgnoreCase(entryMode)) {
            double tol = retestTolerance;
            boolean retested = armed.direction() == TradeDirection.LONG
                    ? ltp.compareTo(armed.compressionHigh()) <= 0
                    && ltp.compareTo(armed.compressionHigh()
                    .multiply(BigDecimal.valueOf(1 - tol))) >= 0
                    : ltp.compareTo(armed.compressionLow()) >= 0
                    && ltp.compareTo(armed.compressionLow()
                    .multiply(BigDecimal.valueOf(1 + tol))) <= 0;
            if (!retested) return;
        }

        // ALL 7 GATES PASSED
        log.info("ALL 7 GATES PASSED: {} dir={} entry={} sl={} target={} gap={} RR={} " +
                        "volMultiplier={}x (time-relative)",
                sym, armed.direction(), ltp,
                armed.stopLoss(), armed.target(),
                armed.gapType(), String.format("%.2f", armed.minRR()),
                String.format("%.1f", volMultiplier));

        armedStocks.remove(sym);

        publisher.publishEvent(new ScannerSignalEvent(
                this, sym, armed.token(),
                BigDecimal.ZERO,
                BigDecimal.valueOf(avgVol > 0 ? (double) current.getVolume() / avgVol : 1),
                BigDecimal.ZERO,
                sectorClassify.getSector(sym)
        ));
    }

    // ══════════════════════════════════════════════════════════════════════
    // GATE 3 — Institutional Compression Check
    // ══════════════════════════════════════════════════════════════════════

    private record CompressionResult(
            boolean    passed,
            BigDecimal high,
            BigDecimal low,
            double     atr,
            boolean    isNr7,
            boolean    isInsideBar,
            String     failReason
    ) {}

    /**
     * Upgraded compression check:
     *
     * ORIGINAL (kept): BB width < bbWidthMax, ATR not expanding, 5-candle range < 1.5x ATR
     *
     * ADDED — Institutional setups (any one is sufficient alongside BB compression):
     *   a) NR7: today's range is narrowest of last 7 candles
     *   b) Inside Bar: high < prev high AND low > prev low (indecision before move)
     *   c) Volume contraction: declining volume = institutions have stopped selling
     *
     * If NEITHER BB compression NOR any institutional pattern is present → FAIL.
     * This prevents entering on "random quiet" stocks with no setup.
     */
    private CompressionResult checkCompression(List<Candle> history) {
        if (history.size() < 20)
            return comprFail("Not enough candles (" + history.size() + ")");

        List<Candle> last5 = history.subList(0, Math.min(5, history.size()));
        double atr14 = atr14(history);

        // ── Check 1: BB width ─────────────────────────────────────────────────
        double bandWidth = bollingerBandWidth(history, 20);
        boolean bbCompressed = bandWidth <= bbWidthMax;

        // ── Check 2: ATR not expanding (volatility contraction) ───────────────
        double atr0 = candleRange(last5.get(0));
        double atr1 = last5.size() > 1 ? candleRange(last5.get(1)) : atr0;
        boolean atrOk = atr0 <= atr1 * 1.2;

        // ── Check 3: 5-candle range < 1.5x ATR14 ─────────────────────────────
        BigDecimal rangeHigh = last5.stream().map(Candle::getHigh)
                .max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
        BigDecimal rangeLow  = last5.stream().map(Candle::getLow)
                .min(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
        double range5 = rangeHigh.subtract(rangeLow).doubleValue();
        boolean tightRange = range5 <= atr14 * 1.5;

        // ── INSTITUTIONAL Check A: NR7 ────────────────────────────────────────
        // Current candle range < range of every candle in last 7
        boolean isNr7 = false;
        if (history.size() >= 7) {
            double curRange = candleRange(history.get(0));
            isNr7 = history.subList(1, 7).stream()
                    .allMatch(c -> curRange < candleRange(c));
        }

        // ── INSTITUTIONAL Check B: Inside Bar ─────────────────────────────────
        // Current candle completely inside the previous candle's range
        boolean isInsideBar = false;
        if (history.size() >= 2) {
            Candle cur  = history.get(0);
            Candle prev = history.get(1);
            isInsideBar = cur.getHigh().compareTo(prev.getHigh()) < 0
                    && cur.getLow().compareTo(prev.getLow())   > 0;
        }

        // ── INSTITUTIONAL Check C: Volume contraction ─────────────────────────
        boolean volContraction = false;
        if (history.size() >= 4) {
            volContraction = history.get(0).getVolume() < history.get(1).getVolume()
                    && history.get(1).getVolume() < history.get(2).getVolume()
                    && history.get(2).getVolume() < history.get(3).getVolume();
        }

        // ── Decision ──────────────────────────────────────────────────────────
        // Need: BB compressed OR institutional pattern (NR7/InsideBar)
        // AND: ATR not expanding AND range is tight
        boolean hasSetup = bbCompressed || isNr7 || isInsideBar;

        if (!hasSetup) {
            return comprFail(String.format(
                    "No compression: BB=%.1f%% (>%.1f%%), no NR7, no InsideBar",
                    bandWidth, bbWidthMax));
        }
        if (!atrOk) {
            return comprFail("ATR expanding — breakout may already be underway");
        }
        if (!tightRange) {
            return comprFail(String.format(
                    "5-candle range %.2f > 1.5x ATR %.2f — too wide", range5, atr14));
        }

        // Log what type of compression was found
        String setupType = bbCompressed ? "BB_SQUEEZE" : isNr7 ? "NR7" : "INSIDE_BAR";
        if (volContraction) setupType += "+VOL_CONTRACTION";
        log.debug("Gate3 PASS {}: {} BB={}% NR7={} IB={} volContr={}",
                "?", setupType,
                String.format("%.1f", bandWidth), isNr7, isInsideBar, volContraction);

        return new CompressionResult(true, rangeHigh, rangeLow, atr14,
                isNr7, isInsideBar, null);
    }

    private CompressionResult comprFail(String reason) {
        return new CompressionResult(false, BigDecimal.ZERO, BigDecimal.ZERO,
                0, false, false, reason);
    }

    // ══════════════════════════════════════════════════════════════════════
    // GATE 4 — Time-Relative Volume Multiplier
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Returns the volume breakout threshold multiplier for the current time.
     *
     * WHY: Volume is NOT uniform throughout the day.
     *   - Opening 45 min: volume surges as overnight orders execute
     *   - Mid-morning: volume settles to "normal" levels
     *   - Lunch: volume drops to minimum — a spike here is HUGE signal
     *   - Afternoon: volume picks up with intraday momentum players
     *
     * Using a fixed 2x threshold is wrong:
     *   - At 9:30 AM: 2x = normal (opening rush), too easy to satisfy = false signals
     *   - At 1:00 PM: 2x = extraordinary (lunch session), meaningful = real breakout
     *
     * Time-calibrated thresholds:
     *   09:15–10:00 → 1.5x  (open is always busy, be less strict)
     *   10:00–12:00 → 1.8x  (mid-morning, meaningful volume spike)
     *   12:00–13:30 → 2.2x  (lunch = low baseline, any spike is institutional)
     *   13:30–14:40 → 1.7x  (afternoon session volume builds)
     */
    private double getTimeRelativeVolumeMultiplier() {
        LocalTime now = LocalTime.now(IST);

        if (now.isBefore(LocalTime.of(10, 0)))  return 1.5;  // opening rush
        if (now.isBefore(LocalTime.of(12, 0)))  return 1.8;  // mid-morning
        if (now.isBefore(LocalTime.of(13, 30))) return 2.2;  // lunch (most significant)
        return 1.7;                                           // afternoon
    }

    // ══════════════════════════════════════════════════════════════════════
    // GATE 5 — Key Level Check
    // ══════════════════════════════════════════════════════════════════════

    private boolean checkKeyLevel(BigDecimal entryLevel,
                                  KeyLevelService.KeyLevelResult keyLevels,
                                  boolean forLong) {
        if (keyLevels.supports().isEmpty() && keyLevels.resistances().isEmpty())
            return true; // not enough data, don't block

        if (!keyLevels.isNearKeyLevel(entryLevel, forLong, 0.3))
            return false;

        if (keyLevels.poc().compareTo(BigDecimal.ZERO) > 0) {
            return forLong
                    ? keyLevels.isAbovePoc(entryLevel)
                    : keyLevels.isBelowPoc(entryLevel);
        }
        return true;
    }

    // ══════════════════════════════════════════════════════════════════════
    // GATE 6 — Liquidity Check
    // ══════════════════════════════════════════════════════════════════════

    private boolean checkLiquidity(List<Candle> history, Candle current) {
        if (current.getClose().compareTo(BigDecimal.valueOf(minPrice)) < 0) return false;
        double avgVol = history.subList(0, Math.min(20, history.size()))
                .stream().mapToLong(Candle::getVolume).average().orElse(0);
        return avgVol >= minVolume;
    }

    // ══════════════════════════════════════════════════════════════════════
    // GATE 7 — Risk Gate
    // ══════════════════════════════════════════════════════════════════════

    private record RiskResult(
            boolean passed, BigDecimal stopLoss, BigDecimal target,
            double rr, double minRR, String failReason
    ) {}

    private RiskResult riskFail(String r) {
        return new RiskResult(false, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, r);
    }

    private RiskResult checkRisk(String sym, BigDecimal entryLevel,
                                 CompressionResult compression,
                                 KeyLevelService.KeyLevelResult keyLevels,
                                 boolean forLong) {
        BigDecimal buffer = entryLevel.multiply(new BigDecimal("0.002"));
        BigDecimal sl, target;
        if (forLong) {
            sl     = compression.low().subtract(buffer);
            target = findNextTarget(entryLevel, keyLevels.resistances(), true);
        } else {
            sl     = compression.high().add(buffer);
            target = findNextTarget(entryLevel, keyLevels.supports(), false);
        }

        if (entryLevel.compareTo(BigDecimal.ZERO) == 0) return riskFail("Entry price zero");

        double slDistPct = Math.abs(
                entryLevel.subtract(sl).doubleValue() / entryLevel.doubleValue()) * 100;
        if (slDistPct == 0)       return riskFail("SL distance zero");
        if (slDistPct > maxSlPct) return riskFail(
                String.format("SL %.2f%% > max %.1f%%", slDistPct, maxSlPct));

        double distToTarget = Math.abs(target.subtract(entryLevel).doubleValue());
        double distToSl     = Math.abs(entryLevel.subtract(sl).doubleValue());
        double rr           = distToSl > 0 ? distToTarget / distToSl : 0;

        double minRR = timingService.getMinRR(vixService.extraRrRequirement());
        GapDataService.GapType gap = gapData.getGapType(sym);
        if (gap == GapDataService.GapType.GAP_AND_GO) minRR = Math.min(minRR, 2.0);
        if (gap == GapDataService.GapType.GAP_FILLED)  minRR = Math.max(minRR, 3.0);
        if (reentryCount.getOrDefault(sym, 0) > 0) minRR = Math.max(minRR, 3.0);

        if (rr < minRR) return riskFail(
                String.format("RR %.2f < %.2f required", rr, minRR));

        return new RiskResult(true, sl, target, rr, minRR, null);
    }

    private BigDecimal findNextTarget(BigDecimal entryLevel,
                                      List<KeyLevelService.KeyLevel> levels,
                                      boolean above) {
        BigDecimal best = null;
        for (KeyLevelService.KeyLevel l : levels) {
            boolean valid = above
                    ? l.price().compareTo(entryLevel) > 0
                    : l.price().compareTo(entryLevel) < 0;
            if (valid) {
                if (best == null) best = l.price();
                else if (above  && l.price().compareTo(best) < 0) best = l.price();
                else if (!above && l.price().compareTo(best) > 0) best = l.price();
            }
        }
        if (best == null) {
            BigDecimal slDist = entryLevel.multiply(new BigDecimal("0.015"));
            best = above
                    ? entryLevel.add(slDist.multiply(BigDecimal.valueOf(3)))
                    : entryLevel.subtract(slDist.multiply(BigDecimal.valueOf(3)));
        }
        return best;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Cooldown management
    // ══════════════════════════════════════════════════════════════════════

    public void startCooldown(String symbol) {
        String sym = symbol.toUpperCase();
        cooldownMap.put(sym, Instant.now().plusSeconds(cooldownSeconds));
        reentryCount.merge(sym, 1, Integer::sum);
        slHitDate.put(sym, LocalDate.now());
        armedStocks.remove(sym);
        log.info("Cooldown started for {} ({} sec)", sym, cooldownSeconds);
    }

    private boolean isInCooldown(String symbol) {
        String  sym   = symbol.toUpperCase();
        Instant until = cooldownMap.get(sym);
        if (until == null) return false;
        if (Instant.now().isAfter(until)) { cooldownMap.remove(sym); return false; }
        return true;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Daily reset
    // ══════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 45 8 * * MON-FRI", zone = "Asia/Kolkata")
    public void resetDaily() {
        cooldownMap.clear(); reentryCount.clear(); slHitDate.clear();
        armedStocks.clear(); gateRejections.clear();
        buffers5m.clear(); buffers15m.clear();
        log.info("Scanner daily reset complete");
    }

    // ══════════════════════════════════════════════════════════════════════
    // Dashboard getters
    // ══════════════════════════════════════════════════════════════════════

    public Map<String, ArmedStock> getArmedStocks() {
        return Collections.unmodifiableMap(armedStocks);
    }

    public Map<String, Integer> getGateRejections() {
        return Collections.unmodifiableMap(gateRejections);
    }

    public int getArmedCount() { return armedStocks.size(); }

    public boolean isInCooldownPublic(String symbol) { return isInCooldown(symbol); }

    // ══════════════════════════════════════════════════════════════════════
    // Private helpers
    // ══════════════════════════════════════════════════════════════════════

    private void reject(String gate, String symbol) {
        gateRejections.merge(gate, 1, Integer::sum);
        log.debug("REJECT gate={} sym={}", gate, symbol);
    }

    /** Bollinger Band width as % of price */
    private double bollingerBandWidth(List<Candle> candles, int period) {
        if (candles.size() < period) return 99;
        double sum = 0;
        for (int i = 0; i < period; i++)
            sum += candles.get(i).getClose().doubleValue();
        double mean = sum / period;
        double variance = 0;
        for (int i = 0; i < period; i++) {
            double diff = candles.get(i).getClose().doubleValue() - mean;
            variance += diff * diff;
        }
        double stdDev = Math.sqrt(variance / period);
        double upper  = mean + 2 * stdDev;
        double lower  = mean - 2 * stdDev;
        return mean > 0 ? (upper - lower) / mean * 100 : 99;
    }

    private double candleRange(Candle c) {
        return c.getHigh().subtract(c.getLow()).doubleValue();
    }

    private double atr14(List<Candle> c) {
        int n = Math.min(14, c.size() - 1);
        if (n == 0) return 0;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double tr = Math.max(
                    c.get(i).getHigh().subtract(c.get(i).getLow()).doubleValue(),
                    Math.max(
                            Math.abs(c.get(i).getHigh().subtract(c.get(i + 1).getClose()).doubleValue()),
                            Math.abs(c.get(i).getLow().subtract(c.get(i + 1).getClose()).doubleValue())
                    ));
            sum += tr;
        }
        return sum / n;
    }
}