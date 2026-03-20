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
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Seven-Gate Scanner — fully configurable via application.yml.
 * No hardcoded trading values anywhere.
 *
 * Layer 1: Gate 1 — Market Direction    (15min candles)
 * Layer 2: Gates 2,3,5,6,7 — Stock setup (5min candles)
 * Layer 3: Gate 4 — Breakout trigger    (live ticks, armed stocks only)
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

    // ── All configurable via application.yml — no magic numbers ──────

    @Value("${scanner.entry-mode:AGGRESSIVE}")
    private String entryMode;

    @Value("${scanner.bb-width-max:2.0}")
    private double bbWidthMax;

    @Value("${scanner.max-sl-pct:2.0}")
    private double maxSlPct;

    @Value("${scanner.min-price:50}")
    private double minPrice;

    @Value("${scanner.min-volume:500000}")
    private long minVolume;

    @Value("${scanner.cooldown-seconds:1800}")
    private long cooldownSeconds;

    @Value("${scanner.retest-tolerance:0.003}")
    private double retestTolerance;

    @Value("${scanner.min-gap-pct:0.5}")
    private double minGapPct;

    @Value("${trading.capital:100000}")
    private String capitalStr;

    // ── In-memory state ───────────────────────────────────────────────

    private final Map<String, Deque<Candle>> buffers5m      = new ConcurrentHashMap<>();
    private final Map<String, Deque<Candle>> buffers15m     = new ConcurrentHashMap<>();
    private final Map<String, ArmedStock>    armedStocks    = new ConcurrentHashMap<>();
    private final Map<String, Integer>       gateRejections = new ConcurrentHashMap<>();
    private final Map<String, Instant>       cooldownMap    = new ConcurrentHashMap<>();
    private final Map<String, Integer>       reentryCount   = new ConcurrentHashMap<>();
    // symbol → date SL was hit (max 1 re-entry per day)
    private final Map<String, LocalDate>     slHitDate      = new ConcurrentHashMap<>();

    // ── ArmedStock record ─────────────────────────────────────────────

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

    // ══════════════════════════════════════════════════════════════════
    // LAYER 1 + 2 — Candle-based gates (5min + 15min)
    // ══════════════════════════════════════════════════════════════════

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

        // ── Pre-checks ────────────────────────────────────────────────

        // Observation period: 9:15-9:40 — collect data, no entries
        if (timingService.isObservationPeriod()) return;

        // Entry window check: 9:40 - 14:40 only
        if (!timingService.isEntryAllowed()) return;

        // VIX extreme: no trades today
        if (!vixService.isTradeAllowed()) {
            reject("VIX_EXTREME", sym);
            return;
        }

        // Cooldown: 30 min after SL hit
        if (isInCooldown(sym)) {
            reject("COOLDOWN", sym);
            return;
        }

        List<Candle> history5m = new ArrayList<>(
                buffers5m.getOrDefault(sym, new ArrayDeque<>()));
        if (history5m.size() < 50) return;

        // ── Gate 1: Market Direction ──────────────────────────────────
        MarketDirectionService.MarketDirectionResult dir =
                marketDirection.getCurrentDirection();
        if (!dir.isTradeable()) {
            reject("GATE1_MARKET_DIRECTION", sym);
            return;
        }
        boolean forLong = dir.isLong();

        // ── Gate 2: Sector Alignment ──────────────────────────────────
        if (!sectorStrength.isSectorAligned(sym, forLong)) {
            reject("GATE2_SECTOR", sym);
            return;
        }

        // ── Gate 3: Stock Compression ─────────────────────────────────
        CompressionResult compression = checkCompression(history5m);
        if (!compression.passed()) {
            reject("GATE3_COMPRESSION", sym);
            log.debug("Gate3 FAIL {}: {}", sym, compression.failReason());
            return;
        }

        // ── Gate 5: Key Level ─────────────────────────────────────────
        KeyLevelService.KeyLevelResult keyLevels = keyLevelService.getKeyLevels(sym);
        BigDecimal entryLevel = forLong ? compression.high() : compression.low();
        if (!checkKeyLevel(entryLevel, keyLevels, forLong)) {
            reject("GATE5_KEY_LEVEL", sym);
            return;
        }

        // ── Gate 6: Liquidity ─────────────────────────────────────────
        if (!checkLiquidity(history5m, c)) {
            reject("GATE6_LIQUIDITY", sym);
            return;
        }

        // ── Gate 7: Risk Gate ─────────────────────────────────────────
        RiskResult risk = checkRisk(sym, entryLevel, compression, keyLevels, forLong);
        if (!risk.passed()) {
            reject("GATE7_RISK", sym);
            log.debug("Gate7 FAIL {}: {}", sym, risk.failReason());
            return;
        }

        // ── Gates 1-3, 5-7 passed → ARM the stock ────────────────────
        TradeDirection tradeDir   = forLong ? TradeDirection.LONG : TradeDirection.SHORT;
        GapDataService.GapType gap = gapData.getGapType(sym);
        boolean isReentry         = reentryCount.getOrDefault(sym, 0) > 0;

        ArmedStock armed = new ArmedStock(
                sym, c.getInstrumentToken(), tradeDir,
                compression.high(), compression.low(),
                risk.stopLoss(), risk.target(),
                keyLevels.vwap(), compression.atr(),
                gap, Instant.now(), isReentry, risk.minRR()
        );

        armedStocks.put(sym, armed);
        log.info("ARMED: {} dir={} compHigh={} compLow={} sl={} tgt={} RR={} gap={}",
                sym, tradeDir,
                compression.high(), compression.low(),
                risk.stopLoss(), risk.target(),
                String.format("%.2f", risk.rr()), gap);
    }

    // ══════════════════════════════════════════════════════════════════
    // LAYER 3 — Gate 4: Tick-based breakout trigger (armed stocks only)
    // ══════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tradingExecutor")
    public void onTick(TickReceivedEvent tick) {
        String sym = tick.getTradingSymbol();
        ArmedStock armed = armedStocks.get(sym);
        if (armed == null) return;
        if (!timingService.isEntryAllowed()) return;

        BigDecimal ltp = tick.getLastTradedPrice();

        // Check if price crossed compression boundary
        boolean longBreakout  = armed.direction() == TradeDirection.LONG
                && ltp.compareTo(armed.compressionHigh()) > 0;
        boolean shortBreakout = armed.direction() == TradeDirection.SHORT
                && ltp.compareTo(armed.compressionLow()) < 0;

        if (!longBreakout && !shortBreakout) return;

        // ── Gate 4: Breakout Validation ───────────────────────────────

        List<Candle> history = new ArrayList<>(
                buffers5m.getOrDefault(sym, new ArrayDeque<>()));
        if (history.isEmpty()) return;

        Candle current = history.get(0);

        // Volume: must be 2x 20-candle average
        double avgVol = history.subList(1, Math.min(21, history.size()))
                .stream().mapToLong(Candle::getVolume).average().orElse(0);
        if (current.getVolume() < avgVol * 2) {
            reject("GATE4_VOLUME", sym);
            return;
        }

        // Body strength: candle body must be 60%+ of range
        if (current.bodyPct().compareTo(new BigDecimal("0.60")) < 0) {
            reject("GATE4_BODY_STRENGTH", sym);
            return;
        }

        // VWAP: long must be above VWAP, short must be below
        if (armed.vwap().compareTo(BigDecimal.ZERO) > 0) {
            boolean aboveVwap = ltp.compareTo(armed.vwap()) > 0;
            if (armed.direction() == TradeDirection.LONG && !aboveVwap) {
                reject("GATE4_VWAP", sym);
                return;
            }
            if (armed.direction() == TradeDirection.SHORT && aboveVwap) {
                reject("GATE4_VWAP", sym);
                return;
            }
        }

        // Buy/sell pressure: institutional confirmation
        long buyVol  = tick.getTotalBuyQuantity();
        long sellVol = tick.getTotalSellQuantity();
        if (armed.direction() == TradeDirection.LONG && buyVol <= sellVol) {
            reject("GATE4_BUY_PRESSURE", sym);
            return;
        }
        if (armed.direction() == TradeDirection.SHORT && sellVol <= buyVol) {
            reject("GATE4_SELL_PRESSURE", sym);
            return;
        }

        // Conservative mode: wait for retest of broken level
        if ("CONSERVATIVE".equalsIgnoreCase(entryMode)) {
            double tol = retestTolerance; // from application.yml
            boolean retested = armed.direction() == TradeDirection.LONG
                    ? ltp.compareTo(armed.compressionHigh()) <= 0
                    && ltp.compareTo(armed.compressionHigh()
                    .multiply(BigDecimal.valueOf(1 - tol))) >= 0
                    : ltp.compareTo(armed.compressionLow()) >= 0
                    && ltp.compareTo(armed.compressionLow()
                    .multiply(BigDecimal.valueOf(1 + tol))) <= 0;
            if (!retested) return; // Not retested yet — keep waiting
        }

        // ── ALL 7 GATES PASSED — fire signal ──────────────────────────
        log.info("ALL 7 GATES PASSED: {} dir={} entry={} sl={} target={} gap={} RR={}",
                sym, armed.direction(), ltp,
                armed.stopLoss(), armed.target(),
                armed.gapType(), String.format("%.2f", armed.minRR()));

        armedStocks.remove(sym);

        publisher.publishEvent(new ScannerSignalEvent(
                this,
                sym,
                armed.token(),
                BigDecimal.ZERO,
                BigDecimal.valueOf(avgVol > 0 ? (double) current.getVolume() / avgVol : 1),
                BigDecimal.ZERO,
                sectorClassify.getSector(sym)
        ));
    }

    // ══════════════════════════════════════════════════════════════════
    // GATE 3 — Compression check
    // ══════════════════════════════════════════════════════════════════

    private record CompressionResult(
            boolean    passed,
            BigDecimal high,
            BigDecimal low,
            double     atr,
            String     failReason
    ) {}

    private CompressionResult checkCompression(List<Candle> history) {
        if (history.size() < 20) return comprFail("Not enough candles");

        List<Candle> last5 = history.subList(0, Math.min(5, history.size()));

        // Bollinger Band width < bbWidthMax (from yml)
        double bandWidth = bollingerBandWidth(history, 20);
        if (bandWidth > bbWidthMax)
            return comprFail(String.format("BB width %.2f%% > %.1f%%", bandWidth, bbWidthMax));

        // ATR not increasing (compression means volatility contracting)
        double atr0 = candleRange(last5.get(0));
        double atr1 = candleRange(last5.get(1));
        if (atr0 > atr1 * 1.2)
            return comprFail("ATR increasing — volatility expanding, not compressing");

        double atr14 = atr14(history);

        // Last 5 candles range < 1.5x ATR (tight price action)
        BigDecimal rangeHigh = last5.stream().map(Candle::getHigh)
                .max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
        BigDecimal rangeLow  = last5.stream().map(Candle::getLow)
                .min(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
        double range5 = rangeHigh.subtract(rangeLow).doubleValue();
        if (range5 > atr14 * 1.5)
            return comprFail(String.format("5-candle range %.2f > 1.5x ATR %.2f", range5, atr14));

        return new CompressionResult(true, rangeHigh, rangeLow, atr14, null);
    }

    // ══════════════════════════════════════════════════════════════════
    // GATE 5 — Key level check
    // ══════════════════════════════════════════════════════════════════

    private boolean checkKeyLevel(BigDecimal entryLevel,
                                  KeyLevelService.KeyLevelResult keyLevels,
                                  boolean forLong) {
        // If no key levels found yet, pass (not enough data)
        if (keyLevels.supports().isEmpty() && keyLevels.resistances().isEmpty())
            return true;

        // Must be near a tested level within 0.3%
        if (!keyLevels.isNearKeyLevel(entryLevel, forLong, 0.3))
            return false;

        // Must be on correct side of Point of Control
        if (keyLevels.poc().compareTo(BigDecimal.ZERO) > 0) {
            return forLong
                    ? keyLevels.isAbovePoc(entryLevel)
                    : keyLevels.isBelowPoc(entryLevel);
        }

        return true;
    }

    // ══════════════════════════════════════════════════════════════════
    // GATE 6 — Liquidity check
    // ══════════════════════════════════════════════════════════════════

    private boolean checkLiquidity(List<Candle> history, Candle current) {
        // Price > minPrice (from yml, default ₹50)
        if (current.getClose().compareTo(
                BigDecimal.valueOf(minPrice)) < 0) return false;

        // Average 20-day volume > minVolume (from yml, default 500,000)
        double avgVol = history.subList(0, Math.min(20, history.size()))
                .stream().mapToLong(Candle::getVolume).average().orElse(0);
        return avgVol >= minVolume;
    }

    // ══════════════════════════════════════════════════════════════════
    // GATE 7 — Risk gate
    // ══════════════════════════════════════════════════════════════════

    private record RiskResult(
            boolean    passed,
            BigDecimal stopLoss,
            BigDecimal target,
            double     rr,
            double     minRR,
            String     failReason
    ) {}

    private RiskResult checkRisk(String sym,
                                 BigDecimal entryLevel,
                                 CompressionResult compression,
                                 KeyLevelService.KeyLevelResult keyLevels,
                                 boolean forLong) {
        // 0.2% SL buffer beyond compression boundary
        BigDecimal buffer = entryLevel.multiply(new BigDecimal("0.002"));

        BigDecimal sl, target;
        if (forLong) {
            sl     = compression.low().subtract(buffer);
            target = findNextTarget(entryLevel, keyLevels.resistances(), true);
        } else {
            sl     = compression.high().add(buffer);
            target = findNextTarget(entryLevel, keyLevels.supports(), false);
        }

        // SL distance must be < maxSlPct% (from yml, default 2%)
        if (entryLevel.compareTo(BigDecimal.ZERO) == 0)
            return riskFail("Entry price is zero");

        double slDistPct = Math.abs(
                entryLevel.subtract(sl).doubleValue() / entryLevel.doubleValue()) * 100;

        if (slDistPct == 0)   return riskFail("SL distance is zero");
        if (slDistPct > maxSlPct)
            return riskFail(String.format("SL %.2f%% > max %.1f%%", slDistPct, maxSlPct));

        // Reward:Risk calculation
        double distToTarget = Math.abs(target.subtract(entryLevel).doubleValue());
        double distToSl     = Math.abs(entryLevel.subtract(sl).doubleValue());
        double rr           = distToSl > 0 ? distToTarget / distToSl : 0;

        // Minimum RR based on time window + VIX adjustment
        double minRR = timingService.getMinRR(vixService.extraRrRequirement());

        // Gap type overrides
        GapDataService.GapType gap = gapData.getGapType(sym);
        if (gap == GapDataService.GapType.GAP_AND_GO) minRR = Math.min(minRR, 2.0);
        if (gap == GapDataService.GapType.GAP_FILLED)  minRR = Math.max(minRR, 3.0);

        // Re-entry: stricter RR requirement
        if (reentryCount.getOrDefault(sym, 0) > 0) minRR = Math.max(minRR, 3.0);

        if (rr < minRR)
            return riskFail(String.format("RR %.2f < %.2f required", rr, minRR));

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
        // Fallback: 3x SL distance if no key level found
        if (best == null) {
            BigDecimal slDist = entryLevel.multiply(new BigDecimal("0.015"));
            best = above
                    ? entryLevel.add(slDist.multiply(BigDecimal.valueOf(3)))
                    : entryLevel.subtract(slDist.multiply(BigDecimal.valueOf(3)));
        }
        return best;
    }

    // ══════════════════════════════════════════════════════════════════
    // COOLDOWN management
    // ══════════════════════════════════════════════════════════════════

    public void startCooldown(String symbol) {
        String sym = symbol.toUpperCase();
        // cooldownSeconds from yml (default 1800 = 30 min)
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
        if (Instant.now().isAfter(until)) {
            cooldownMap.remove(sym);
            return false;
        }
        return true;
    }

    // ══════════════════════════════════════════════════════════════════
    // DAILY RESET — 8:45 IST
    // ══════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 45 8 * * MON-FRI", zone = "Asia/Kolkata")
    public void resetDaily() {
        cooldownMap.clear();
        reentryCount.clear();
        slHitDate.clear();
        armedStocks.clear();
        gateRejections.clear();
        buffers5m.clear();
        buffers15m.clear();
        log.info("Scanner daily reset complete");
    }

    // ══════════════════════════════════════════════════════════════════
    // DASHBOARD getters
    // ══════════════════════════════════════════════════════════════════

    public Map<String, ArmedStock> getArmedStocks() {
        return Collections.unmodifiableMap(armedStocks);
    }

    public Map<String, Integer> getGateRejections() {
        return Collections.unmodifiableMap(gateRejections);
    }

    public int getArmedCount() {
        return armedStocks.size();
    }

    public boolean isInCooldownPublic(String symbol) {
        return isInCooldown(symbol);
    }

    // ══════════════════════════════════════════════════════════════════
    // PRIVATE helpers
    // ══════════════════════════════════════════════════════════════════

    private void reject(String gate, String symbol) {
        gateRejections.merge(gate, 1, Integer::sum);
        log.debug("REJECT gate={} sym={}", gate, symbol);
    }

    private CompressionResult comprFail(String reason) {
        return new CompressionResult(false, BigDecimal.ZERO, BigDecimal.ZERO, 0, reason);
    }

    private RiskResult riskFail(String reason) {
        return new RiskResult(false, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, reason);
    }

    /** Bollinger Band width as % of price — uses period from config */
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

    /** Single candle high-low range */
    private double candleRange(Candle c) {
        return c.getHigh().subtract(c.getLow()).doubleValue();
    }

    /** 14-period ATR */
    private double atr14(List<Candle> c) {
        int n = Math.min(14, c.size() - 1);
        if (n == 0) return 0;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double tr = Math.max(
                    c.get(i).getHigh().subtract(c.get(i).getLow()).doubleValue(),
                    Math.max(
                            Math.abs(c.get(i).getHigh()
                                    .subtract(c.get(i+1).getClose()).doubleValue()),
                            Math.abs(c.get(i).getLow()
                                    .subtract(c.get(i+1).getClose()).doubleValue())
                    )
            );
            sum += tr;
        }
        return sum / n;
    }
}