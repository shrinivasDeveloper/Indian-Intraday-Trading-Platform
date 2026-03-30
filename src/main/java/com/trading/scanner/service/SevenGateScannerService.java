// ═══════════════════════════════════════════════════════════════════════════════
// FILE: src/main/java/com/trading/scanner/service/SevenGateScannerService.java
// MODIFIED — added StrategyValidationTracker integration in scan5min() & onTick()
// All original logic is 100% unchanged. Only additions: tracker field + step recording.
// ═══════════════════════════════════════════════════════════════════════════════
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
import com.trading.validation.StrategyValidationTracker;
import com.trading.validation.ValidationStepResult;
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
 *
 * VALIDATION TRACKING (NEW):
 *   Every evaluation cycle for every stock now records per-step PASS/FAIL results
 *   to StrategyValidationTracker. The dashboard can query /api/validation/steps
 *   to see exactly which gate is failing for which stock in real time.
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
    // ── NEW: Validation tracker — injected via @RequiredArgsConstructor ────────
    private final StrategyValidationTracker   validationTracker;

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

    // ══════════════════════════════════════════════════════════════════════════
    // LAYER 1+2 — Candle-based gates
    // ══════════════════════════════════════════════════════════════════════════

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
        String sym  = c.getTradingSymbol();
        String dirLabel = "LONG"; // tentative until Gate 1 resolves

        // ── Steps list — built up incrementally and recorded at each exit ─────
        List<ValidationStepResult> steps = new ArrayList<>();

        // ── Pre-checks: timing / VIX / cooldown ──────────────────────────────
        if (timingService.isObservationPeriod()) return;
        if (!timingService.isEntryAllowed())     return;

        boolean vixOk = vixService.isTradeAllowed();
        steps.add(vstep(0, "VIX_CHECK", "VIX Trade Allowed", vixOk,
                "vix=" + String.format("%.1f", vixService.getCurrentVix())
                        + " regime=" + vixService.getRegime().name()));
        if (!vixOk) {
            reject("VIX_EXTREME", sym);
            validationTracker.record("SCANNER_7GATE", sym, dirLabel, steps);
            return;
        }

        boolean notCooldown = !isInCooldown(sym);
        steps.add(vstep(0, "COOLDOWN", "Not In Cooldown", notCooldown,
                notCooldown ? "No cooldown active" : "In cooldown until " + cooldownMap.get(sym)));
        if (!notCooldown) {
            reject("COOLDOWN", sym);
            validationTracker.record("SCANNER_7GATE", sym, dirLabel, steps);
            return;
        }

        List<Candle> history5m = new ArrayList<>(
                buffers5m.getOrDefault(sym, new ArrayDeque<>()));
        if (history5m.size() < 50) return;

        // ── Gate 1: Market Direction ───────────────────────────────────────────
        MarketDirectionService.MarketDirectionResult dir =
                marketDirection.getCurrentDirection();
        boolean g1 = dir.isTradeable();
        steps.add(vstep(1, "GATE1_MARKET_DIR", "Gate 1 — Market Direction", g1,
                "dir=" + dir.direction().name()
                        + " failReason=" + (dir.failReason() != null ? dir.failReason() : "OK")));
        if (!g1) {
            reject("GATE1_MARKET_DIRECTION", sym);
            validationTracker.record("SCANNER_7GATE", sym, dirLabel, steps);
            return;
        }

        boolean forLong = dir.isLong();
        dirLabel = forLong ? "LONG" : "SHORT";

        // ── Gate 2: Sector Alignment ──────────────────────────────────────────
        boolean g2 = sectorStrength.isSectorAligned(sym, forLong);
        String  sector = sectorClassify.getSector(sym);
        steps.add(vstep(2, "GATE2_SECTOR", "Gate 2 — Sector Alignment", g2,
                "sector=" + sector
                        + " forLong=" + forLong
                        + " aligned=" + g2));
        if (!g2) {
            reject("GATE2_SECTOR", sym);
            validationTracker.record("SCANNER_7GATE", sym, dirLabel, steps);
            return;
        }

        // ── Gate 3: Compression ───────────────────────────────────────────────
        CompressionResult compression = checkCompression(history5m);
        boolean g3 = compression.passed();
        steps.add(vstep(3, "GATE3_COMPRESSION", "Gate 3 — Compression (BB/NR7/IB)", g3,
                g3
                        ? "setup=BB=" + String.format("%.1f%%", bollingerBandWidth(history5m, 20))
                        + " NR7=" + compression.isNr7()
                        + " InsideBar=" + compression.isInsideBar()
                        : "FAIL: " + compression.failReason()));
        if (!g3) {
            reject("GATE3_COMPRESSION", sym);
            log.debug("Gate3 FAIL {}: {}", sym, compression.failReason());
            validationTracker.record("SCANNER_7GATE", sym, dirLabel, steps);
            return;
        }

        // ── Gate 5: Key Level ─────────────────────────────────────────────────
        KeyLevelService.KeyLevelResult keyLevels = keyLevelService.getKeyLevels(sym);
        BigDecimal entryLevel = forLong ? compression.high() : compression.low();
        boolean g5 = checkKeyLevel(entryLevel, keyLevels, forLong);
        steps.add(vstep(5, "GATE5_KEY_LEVEL", "Gate 5 — Key Level / POC Alignment", g5,
                "entry=" + r2(entryLevel)
                        + " poc=" + r2(keyLevels.poc())
                        + " vwap=" + r2(keyLevels.vwap())
                        + (g5 ? " ✓ near key level" : " ✗ not near key level")));
        if (!g5) {
            reject("GATE5_KEY_LEVEL", sym);
            validationTracker.record("SCANNER_7GATE", sym, dirLabel, steps);
            return;
        }

        // ── Gate 6: Liquidity ─────────────────────────────────────────────────
        Candle current5m = history5m.get(0);
        boolean g6 = checkLiquidity(history5m, current5m);
        double avgVol20 = history5m.subList(0, Math.min(20, history5m.size()))
                .stream().mapToLong(Candle::getVolume).average().orElse(0);
        steps.add(vstep(6, "GATE6_LIQUIDITY", "Gate 6 — Liquidity (Price + AvgVol)", g6,
                "price=" + r2(current5m.getClose())
                        + " minPrice=₹" + minPrice
                        + " avgVol=" + Math.round(avgVol20)
                        + " minVol=" + minVolume));
        if (!g6) {
            reject("GATE6_LIQUIDITY", sym);
            validationTracker.record("SCANNER_7GATE", sym, dirLabel, steps);
            return;
        }

        // ── Gate 7: Risk Gate ─────────────────────────────────────────────────
        RiskResult risk = checkRisk(sym, entryLevel, compression, keyLevels, forLong);
        boolean g7 = risk.passed();
        steps.add(vstep(7, "GATE7_RISK", "Gate 7 — Risk / RR Gate", g7,
                g7
                        ? "SL=" + r2(risk.stopLoss()) + " tgt=" + r2(risk.target())
                        + " RR=" + String.format("%.2f", risk.rr())
                        + " minRR=" + String.format("%.2f", risk.minRR())
                        : "FAIL: " + risk.failReason()));
        if (!g7) {
            reject("GATE7_RISK", sym);
            log.debug("Gate7 FAIL {}: {}", sym, risk.failReason());
            validationTracker.record("SCANNER_7GATE", sym, dirLabel, steps);
            return;
        }

        // Note: Gate 4 (breakout tick) is checked in onTick() — mark it as PENDING here
        steps.add(vstep(4, "GATE4_BREAKOUT_TICK",
                "Gate 4 — Breakout Tick + Volume (Pending tick)", true,
                "Gates 1-3,5-7 passed. Stock ARMED. Waiting for price to break "
                        + (forLong ? "above " + r2(compression.high()) : "below " + r2(compression.low()))));

        // Record all-gates-1-7-pass (gate 4 is async via tick)
        validationTracker.record("SCANNER_7GATE", sym, dirLabel, steps);

        // ── ARM the stock for Gate 4 tick monitoring ──────────────────────────
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

    // ══════════════════════════════════════════════════════════════════════════
    // LAYER 3 — Gate 4: Tick-based breakout (UPGRADED — time-relative volume)
    // ══════════════════════════════════════════════════════════════════════════

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
        String dirLabel = armed.direction().name();

        // ── Build Gate 4 step list for dashboard visibility ───────────────────
        List<ValidationStepResult> gate4Steps = new ArrayList<>();
        gate4Steps.add(vstep(4, "GATE4_PRICE_BREAK",
                "Gate 4a — Price Breaks Compression Level", true,
                "ltp=" + r2(ltp)
                        + (longBreakout
                        ? " broke above compHigh=" + r2(armed.compressionHigh())
                        : " broke below compLow="  + r2(armed.compressionLow()))));

        // ── TIME-RELATIVE volume multiplier ───────────────────────────────────
        double volMultiplier = getTimeRelativeVolumeMultiplier();
        double avgVol = history.subList(1, Math.min(21, history.size()))
                .stream().mapToLong(Candle::getVolume).average().orElse(0);
        boolean volOk = current.getVolume() >= avgVol * volMultiplier;
        gate4Steps.add(vstep(4, "GATE4_VOLUME",
                "Gate 4b — Volume ≥ " + String.format("%.1f", volMultiplier) + "× (time-relative)", volOk,
                "vol=" + current.getVolume()
                        + " avg=" + Math.round(avgVol)
                        + " ratio=" + String.format("%.2f", avgVol > 0 ? (double) current.getVolume() / avgVol : 0) + "×"
                        + " need≥" + String.format("%.1f", volMultiplier) + "× (time slot)"));
        if (!volOk) {
            reject("GATE4_VOLUME", sym);
            log.debug("Gate4 FAIL {}: vol {:.0f} < {:.0f}x avg {:.0f} (time-relative multiplier)",
                    sym, (double) current.getVolume(), volMultiplier, avgVol);
            validationTracker.record("SCANNER_7GATE", sym, dirLabel, gate4Steps);
            return;
        }

        // ── Body strength ─────────────────────────────────────────────────────
        boolean bodyOk = current.bodyPct().compareTo(new BigDecimal("0.60")) >= 0;
        gate4Steps.add(vstep(4, "GATE4_BODY",
                "Gate 4c — Candle Body ≥ 60% of Range", bodyOk,
                "bodyPct=" + String.format("%.0f%%", current.bodyPct().doubleValue() * 100)
                        + " need≥60%"));
        if (!bodyOk) {
            reject("GATE4_BODY_STRENGTH", sym);
            validationTracker.record("SCANNER_7GATE", sym, dirLabel, gate4Steps);
            return;
        }

        // ── VWAP filter ───────────────────────────────────────────────────────
        boolean vwapOk = true;
        String vwapDetail = "vwap=0 (skipped)";
        if (armed.vwap().compareTo(BigDecimal.ZERO) > 0) {
            boolean aboveVwap = ltp.compareTo(armed.vwap()) > 0;
            if (armed.direction() == TradeDirection.LONG)  vwapOk = aboveVwap;
            if (armed.direction() == TradeDirection.SHORT) vwapOk = !aboveVwap;
            vwapDetail = "ltp=" + r2(ltp) + " vwap=" + r2(armed.vwap())
                    + " aboveVwap=" + aboveVwap
                    + " needAbove=" + (armed.direction() == TradeDirection.LONG);
        }
        gate4Steps.add(vstep(4, "GATE4_VWAP",
                "Gate 4d — VWAP Alignment", vwapOk, vwapDetail));
        if (!vwapOk) {
            reject("GATE4_VWAP", sym);
            validationTracker.record("SCANNER_7GATE", sym, dirLabel, gate4Steps);
            return;
        }

        // ── Buy/sell pressure ─────────────────────────────────────────────────
        long buyVol  = tick.getTotalBuyQuantity();
        long sellVol = tick.getTotalSellQuantity();
        boolean pressureOk = armed.direction() == TradeDirection.LONG
                ? buyVol > sellVol
                : sellVol > buyVol;
        gate4Steps.add(vstep(4, "GATE4_PRESSURE",
                "Gate 4e — Order Book Pressure", pressureOk,
                "buyVol=" + buyVol + " sellVol=" + sellVol
                        + " need" + (armed.direction() == TradeDirection.LONG ? "Buy" : "Sell") + ">other"));
        if (!pressureOk) {
            reject(armed.direction() == TradeDirection.LONG
                    ? "GATE4_BUY_PRESSURE" : "GATE4_SELL_PRESSURE", sym);
            validationTracker.record("SCANNER_7GATE", sym, dirLabel, gate4Steps);
            return;
        }

        // ── Conservative mode retest ──────────────────────────────────────────
        if ("CONSERVATIVE".equalsIgnoreCase(entryMode)) {
            double tol = retestTolerance;
            boolean retested = armed.direction() == TradeDirection.LONG
                    ? ltp.compareTo(armed.compressionHigh()) <= 0
                    && ltp.compareTo(armed.compressionHigh()
                    .multiply(BigDecimal.valueOf(1 - tol))) >= 0
                    : ltp.compareTo(armed.compressionLow()) >= 0
                    && ltp.compareTo(armed.compressionLow()
                    .multiply(BigDecimal.valueOf(1 + tol))) <= 0;
            gate4Steps.add(vstep(4, "GATE4_RETEST",
                    "Gate 4f — Retest (CONSERVATIVE mode)", retested,
                    "mode=CONSERVATIVE tol=" + tol
                            + " ltp=" + r2(ltp)
                            + " level=" + r2(armed.direction() == TradeDirection.LONG
                            ? armed.compressionHigh() : armed.compressionLow())));
            if (!retested) {
                validationTracker.record("SCANNER_7GATE", sym, dirLabel, gate4Steps);
                return;
            }
        }

        // ── ALL 7 GATES PASSED ────────────────────────────────────────────────
        gate4Steps.add(vstep(4, "ALL_GATES_PASSED",
                "✅ ALL 7 GATES PASSED — Signal Fired", true,
                "entry=" + r2(ltp) + " sl=" + r2(armed.stopLoss())
                        + " tgt=" + r2(armed.target())
                        + " RR=" + String.format("%.2f", armed.minRR())
                        + " gap=" + armed.gapType()
                        + " volMult=" + String.format("%.1f", volMultiplier) + "×"));
        validationTracker.record("SCANNER_7GATE", sym, dirLabel, gate4Steps);

        log.info("ALL 7 GATES PASSED: {} dir={} entry={} sl={} target={} gap={} RR={} "
                        + "volMultiplier={}x (time-relative)",
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

    // ══════════════════════════════════════════════════════════════════════════
    // GATE 3 — Institutional Compression Check
    // ══════════════════════════════════════════════════════════════════════════

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
     * Upgraded compression check.
     * ORIGINAL (kept): BB width < bbWidthMax, ATR not expanding, 5-candle range < 1.5x ATR
     * ADDED — Institutional setups (any one is sufficient alongside BB compression):
     *   a) NR7: today's range is narrowest of last 7 candles
     *   b) Inside Bar: high < prev high AND low > prev low (indecision before move)
     *   c) Volume contraction: declining volume = institutions have stopped selling
     */
    private CompressionResult checkCompression(List<Candle> history) {
        if (history.size() < 20)
            return comprFail("Not enough candles (" + history.size() + ")");

        List<Candle> last5 = history.subList(0, Math.min(5, history.size()));
        double atr14 = atr14(history);

        double bandWidth    = bollingerBandWidth(history, 20);
        boolean bbCompressed = bandWidth <= bbWidthMax;

        double atr0 = candleRange(last5.get(0));
        double atr1 = last5.size() > 1 ? candleRange(last5.get(1)) : atr0;
        boolean atrOk = atr0 <= atr1 * 1.2;

        BigDecimal rangeHigh = last5.stream().map(Candle::getHigh)
                .max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
        BigDecimal rangeLow  = last5.stream().map(Candle::getLow)
                .min(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
        double range5 = rangeHigh.subtract(rangeLow).doubleValue();
        boolean tightRange = range5 <= atr14 * 1.5;

        boolean isNr7 = false;
        if (history.size() >= 7) {
            double curRange = candleRange(history.get(0));
            isNr7 = history.subList(1, 7).stream()
                    .allMatch(c -> curRange < candleRange(c));
        }

        boolean isInsideBar = false;
        if (history.size() >= 2) {
            Candle cur  = history.get(0);
            Candle prev = history.get(1);
            isInsideBar = cur.getHigh().compareTo(prev.getHigh()) < 0
                    && cur.getLow().compareTo(prev.getLow()) > 0;
        }

        boolean volContraction = false;
        if (history.size() >= 4) {
            volContraction = history.get(0).getVolume() < history.get(1).getVolume()
                    && history.get(1).getVolume() < history.get(2).getVolume()
                    && history.get(2).getVolume() < history.get(3).getVolume();
        }

        boolean hasSetup = bbCompressed || isNr7 || isInsideBar;
        if (!hasSetup)
            return comprFail(String.format(
                    "No compression: BB=%.1f%% (>%.1f%%), no NR7, no InsideBar",
                    bandWidth, bbWidthMax));
        if (!atrOk)
            return comprFail("ATR expanding — breakout may already be underway");
        if (!tightRange)
            return comprFail(String.format(
                    "5-candle range %.2f > 1.5x ATR %.2f — too wide", range5, atr14));

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

    // ══════════════════════════════════════════════════════════════════════════
    // GATE 4 — Time-Relative Volume Multiplier
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Time-calibrated volume breakout threshold.
     *   09:15–10:00 → 1.5x  (open is always busy)
     *   10:00–12:00 → 1.8x  (meaningful mid-morning spike)
     *   12:00–13:30 → 2.2x  (lunch — any spike is institutional)
     *   13:30–14:40 → 1.7x  (afternoon build)
     */
    private double getTimeRelativeVolumeMultiplier() {
        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(LocalTime.of(10, 0)))  return 1.5;
        if (now.isBefore(LocalTime.of(12, 0)))  return 1.8;
        if (now.isBefore(LocalTime.of(13, 30))) return 2.2;
        return 1.7;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // GATE 5 — Key Level Check
    // ══════════════════════════════════════════════════════════════════════════

    private boolean checkKeyLevel(BigDecimal entryLevel,
                                  KeyLevelService.KeyLevelResult keyLevels,
                                  boolean forLong) {
        if (keyLevels.supports().isEmpty() && keyLevels.resistances().isEmpty())
            return true;
        if (!keyLevels.isNearKeyLevel(entryLevel, forLong, 0.3))
            return false;
        if (keyLevels.poc().compareTo(BigDecimal.ZERO) > 0) {
            return forLong
                    ? keyLevels.isAbovePoc(entryLevel)
                    : keyLevels.isBelowPoc(entryLevel);
        }
        return true;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // GATE 6 — Liquidity Check
    // ══════════════════════════════════════════════════════════════════════════

    private boolean checkLiquidity(List<Candle> history, Candle current) {
        if (current.getClose().compareTo(BigDecimal.valueOf(minPrice)) < 0) return false;
        double avgVol = history.subList(0, Math.min(20, history.size()))
                .stream().mapToLong(Candle::getVolume).average().orElse(0);
        return avgVol >= minVolume;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // GATE 7 — Risk Gate
    // ══════════════════════════════════════════════════════════════════════════

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
        if (reentryCount.getOrDefault(sym, 0) > 0)    minRR = Math.max(minRR, 3.0);

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

    // ══════════════════════════════════════════════════════════════════════════
    // Cooldown management
    // ══════════════════════════════════════════════════════════════════════════

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

    // ══════════════════════════════════════════════════════════════════════════
    // Daily reset
    // ══════════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 45 8 * * MON-FRI", zone = "Asia/Kolkata")
    public void resetDaily() {
        cooldownMap.clear(); reentryCount.clear(); slHitDate.clear();
        armedStocks.clear(); gateRejections.clear();
        buffers5m.clear(); buffers15m.clear();
        log.info("Scanner daily reset complete");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Dashboard getters
    // ══════════════════════════════════════════════════════════════════════════

    public Map<String, ArmedStock> getArmedStocks() {
        return Collections.unmodifiableMap(armedStocks);
    }

    public Map<String, Integer> getGateRejections() {
        return Collections.unmodifiableMap(gateRejections);
    }

    public int getArmedCount() { return armedStocks.size(); }

    public boolean isInCooldownPublic(String symbol) { return isInCooldown(symbol); }

    // ══════════════════════════════════════════════════════════════════════════
    // Private helpers
    // ══════════════════════════════════════════════════════════════════════════

    private void reject(String gate, String symbol) {
        gateRejections.merge(gate, 1, Integer::sum);
        log.debug("REJECT gate={} sym={}", gate, symbol);
    }

    /** Build a ValidationStepResult conveniently. */
    private ValidationStepResult vstep(int num, String id, String label,
                                       boolean passed, String detail) {
        return new ValidationStepResult(num, id, label, passed, detail);
    }

    /** Round BigDecimal to 2 dp for display. */
    private BigDecimal r2(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v.setScale(2, RoundingMode.HALF_UP);
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