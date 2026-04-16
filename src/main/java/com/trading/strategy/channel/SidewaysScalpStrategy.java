package com.trading.strategy.channel;

import com.trading.analysis.service.RvolService;
import com.trading.domain.Candle;
import com.trading.domain.enums.TradeDirection;
import com.trading.events.CandleCompleteEvent;
import com.trading.events.SmartChannelPullbackSignalEvent;
import com.trading.marketdata.service.LatencyMonitor;
import com.trading.marketdata.service.MarketPressureService;
import com.trading.marketdata.service.MarketPressureService.PressureSnapshot;
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
 * SidewaysScalpStrategy v2 - Market Pressure Edition.
 * Direction now from MarketPressureService. No sideways-only filter.
 * BUY pressure -> scalp near support. SELL pressure -> scalp near resistance.
 * Both BULLISH, BEARISH, and SIDEWAYS channels can produce signals.
 */
@Service @Slf4j @RequiredArgsConstructor
public class SidewaysScalpStrategy {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String STRATEGY_NAME = "SCALP_PRESSURE_V2";
    private static final LocalTime ENTRY_START = LocalTime.of(9, 45);
    private static final LocalTime ENTRY_END   = LocalTime.of(14, 0);
    private static final double MIN_WIDTH = 0.6, MAX_WIDTH = 1.8;
    private static final double PROXIMITY  = 0.004;
    private static final double MIN_BODY   = 0.30, MAX_BODY = 0.85, MIN_WICK = 0.15;
    private static final double T1_PCT = 0.005, T2_PCT = 0.008, SL_PCT = 0.003, MIN_RR = 1.5;
    private static final long   COOLDOWN_MS = 30 * 60 * 1000L;

    private final ApplicationEventPublisher   publisher;
    private final MarketPressureService       pressureService;
    private final ChannelDetectionService     channelDetection;
    private final RvolService                 rvolService;
    private final SectorStrengthService       sectorStrength;
    private final SectorClassificationService sectorClassify;
    private final MarketTimingService         timingService;
    private final CircuitBreakerService       circuitBreaker;
    private final PositionSizerService        positionSizer;
    private final PaperAccount               paperAccount;
    private final LatencyMonitor             latencyMonitor;

    @Value("${trading.mode:PAPER}") private String tradingMode;
    @Value("${trading.capital:100000}") private BigDecimal capital;
    @Value("${strategy.sideways-scalp.enabled:true}") private boolean strategyEnabled;
    @Value("${strategy.sideways-scalp.min-rvol:1.1}") private double minRvol;
    @Value("${strategy.sideways-scalp.max-signals-per-session:5}") private int maxSignalsPerSession;
    @Value("${strategy.sideways-scalp.time-stop-minutes:20}") private int timeStopMinutes;

    private final Map<String, Long> lastSignalTime = new ConcurrentHashMap<>();
    private final Set<String>       activeSignals  = ConcurrentHashMap.newKeySet();
    private volatile int            sessionSignalCount = 0;

    @EventListener @Async("tradingExecutor")
    public void onCandle(CandleCompleteEvent event) {
        Candle c = event.getCandle();
        if (!"5minute".equals(c.getTimeframe()) || !c.isComplete()) return;
        if (!strategyEnabled || latencyMonitor.isStale()) return;
        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(ENTRY_START) || now.isAfter(ENTRY_END)) return;
        if (!circuitBreaker.checkPermission(resolveCapital()).isAllowed()) return;
        if (sessionSignalCount >= maxSignalsPerSession) return;
        evaluateForScalp(c.getTradingSymbol(), c);
    }

    private void evaluateForScalp(String symbol, Candle candle) {
        if (activeSignals.contains(symbol)) return;
        Long lf = lastSignalTime.get(symbol);
        if (lf != null && System.currentTimeMillis() - lf < COOLDOWN_MS) return;

        PressureSnapshot pressure = pressureService.getSnapshot();
        if (!pressure.isActionable()) return;

        boolean isBuy = pressure.isBuy();
        TradeDirection direction = isBuy ? TradeDirection.LONG : TradeDirection.SHORT;

        ChannelDetectionService.ChannelResult channel = channelDetection.getChannel(symbol);
        if (!channel.isValid() || channel.isTransitioning()) return;

        double widthPct = channel.channelWidthPct();
        if (widthPct < MIN_WIDTH || widthPct > MAX_WIDTH) return;

        boolean channelOk = isBuy
                ? (channel.type() == ChannelDetectionService.ChannelType.BULLISH
                || channel.type() == ChannelDetectionService.ChannelType.SIDEWAYS)
                : (channel.type() == ChannelDetectionService.ChannelType.BEARISH
                || channel.type() == ChannelDetectionService.ChannelType.SIDEWAYS);
        if (!channelOk) return;

        double price = candle.getClose().doubleValue();
        double support = channel.supportPrice(), resistance = channel.resistancePrice();
        if (support <= 0 || resistance <= 0 || resistance <= support) return;

        boolean nearLevel = isBuy
                ? (price - support) / support <= PROXIMITY
                : (resistance - price) / resistance <= PROXIMITY;
        if (!nearLevel) return;

        double rangePos = (price - support) / (resistance - support);
        if (isBuy && rangePos > 0.35) return;
        if (!isBuy && rangePos < 0.65) return;

        if (!isQualityCandle(candle)) return;
        if (isBuy && !isBullishReversal(candle)) return;
        if (!isBuy && !isBearishReversal(candle)) return;

        double rvol = rvolService.getRvolNow(symbol, candle.getVolume());
        if (rvol < minRvol) return;

        String sectorName = sectorClassify.getSector(symbol);
        SectorStrengthService.SectorData sd = sectorStrength.getSector(sectorName);
        double sc = sd.changePercent();
        if (isBuy && sc < -0.5) return;
        if (!isBuy && sc > 0.5)  return;

        BigDecimal entry = candle.getClose().setScale(2, RoundingMode.HALF_UP);
        BigDecimal sl, t1, t2;
        if (isBuy) {
            sl = BigDecimal.valueOf(support * (1.0 - SL_PCT)).setScale(2, RoundingMode.FLOOR);
            t1 = entry.multiply(BigDecimal.valueOf(1.0 + T1_PCT)).setScale(2, RoundingMode.HALF_UP);
            t2 = entry.multiply(BigDecimal.valueOf(1.0 + T2_PCT)).setScale(2, RoundingMode.HALF_UP);
        } else {
            sl = BigDecimal.valueOf(resistance * (1.0 + SL_PCT)).setScale(2, RoundingMode.CEILING);
            t1 = entry.multiply(BigDecimal.valueOf(1.0 - T1_PCT)).setScale(2, RoundingMode.HALF_UP);
            t2 = entry.multiply(BigDecimal.valueOf(1.0 - T2_PCT)).setScale(2, RoundingMode.HALF_UP);
        }

        BigDecimal risk = entry.subtract(sl).abs();
        if (risk.compareTo(BigDecimal.ZERO) == 0) return;
        if (t1.subtract(entry).abs().doubleValue() / risk.doubleValue() < MIN_RR) return;

        BigDecimal cap = resolveCapital();
        PositionSizerService.PositionSize pos = positionSizer.calculate(cap, entry, sl, symbol, direction.name());
        if (!pos.isValid() || pos.quantity() <= 0) return;

        int scoreRvol     = rvol >= 1.2 ? 20 : rvol >= 1.1 ? 12 : 5;
        int scoreWidth    = (widthPct >= 0.8 && widthPct <= 1.2) ? 20 : 10;
        int scoreProx     = Math.abs(price - (isBuy ? support : resistance)) / (isBuy ? support : resistance) <= 0.0015 ? 20 : 10;
        int scoreCandle   = hasStrongWick(candle, isBuy) ? 20 : 10;
        int scorePressure = pressure.ratio() >= 2.0 ? 20 : pressure.ratio() >= 1.5 ? 15 : 10;
        int totalScore    = scoreRvol + scoreWidth + scoreProx + scoreCandle + scorePressure;

        log.info("[SCALP] SIGNAL: {} | {} | entry={} sl={} T1={} | RVOL={} width={}% pressure={}({}) score={}",
                symbol, direction, entry, sl, t1, String.format("%.2f", rvol),
                String.format("%.2f", widthPct), pressure.direction(),
                String.format("%.3f", pressure.ratio()), totalScore);

        publisher.publishEvent(new SmartChannelPullbackSignalEvent(
                this, symbol, candle.getInstrumentToken(), direction, entry, sl, t1, t2,
                pos.quantity(), pos.actualRisk(), STRATEGY_NAME, totalScore,
                sectorName, sd.changePercent(), channel.isHighQuality() ? "HIGH_QUALITY" : "VALID",
                "SCALP_EDGE", (price - (isBuy ? support : resistance)) / (isBuy ? support : resistance),
                rvol, false, "LIMIT", isBuy ? "BUY_PRESSURE" : "SELL_PRESSURE",
                0, scoreRvol, scorePressure, scoreCandle, scoreProx, scoreWidth,
                totalScore, timeStopMinutes));

        lastSignalTime.put(symbol, System.currentTimeMillis());
        activeSignals.add(symbol);
        sessionSignalCount++;
        log.info("[SCALP] Signal #{} fired for {}", sessionSignalCount, symbol);
    }

    private boolean isQualityCandle(Candle c) {
        double range = c.getHigh().doubleValue() - c.getLow().doubleValue();
        if (range <= 0) return false;
        double bp = Math.abs(c.getClose().doubleValue() - c.getOpen().doubleValue()) / range;
        return bp >= MIN_BODY && bp <= MAX_BODY;
    }

    private boolean isBullishReversal(Candle c) {
        double o = c.getOpen().doubleValue(), h = c.getHigh().doubleValue();
        double l = c.getLow().doubleValue(), cl = c.getClose().doubleValue();
        double range = h - l; if (range <= 0) return false;
        double lw = Math.min(o, cl) - l;
        return cl > o && lw >= (cl - o) && (cl - l) / range >= 0.5 && lw / range >= MIN_WICK;
    }

    private boolean isBearishReversal(Candle c) {
        double o = c.getOpen().doubleValue(), h = c.getHigh().doubleValue();
        double l = c.getLow().doubleValue(), cl = c.getClose().doubleValue();
        double range = h - l; if (range <= 0) return false;
        double uw = h - Math.max(o, cl);
        return cl < o && uw >= (o - cl) && (cl - l) / range <= 0.5 && uw / range >= MIN_WICK;
    }

    private boolean hasStrongWick(Candle c, boolean isBull) {
        double range = c.getHigh().doubleValue() - c.getLow().doubleValue();
        if (range <= 0) return false;
        if (isBull) { double lw = Math.min(c.getOpen().doubleValue(), c.getClose().doubleValue()) - c.getLow().doubleValue(); return lw / range >= 0.25; }
        else         { double uw = c.getHigh().doubleValue() - Math.max(c.getOpen().doubleValue(), c.getClose().doubleValue()); return uw / range >= 0.25; }
    }

    private BigDecimal resolveCapital() { return "PAPER".equalsIgnoreCase(tradingMode) ? paperAccount.getCapital() : capital; }
    public void onSignalClosed(String s) { activeSignals.remove(s); }
    public int getSessionSignalCount()   { return sessionSignalCount; }
    public int getActiveSignalCount()    { return activeSignals.size(); }
    public Set<String> getActiveSignals(){ return Collections.unmodifiableSet(activeSignals); }
    public boolean isEnabled()           { return strategyEnabled; }

    @Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyReset() { lastSignalTime.clear(); activeSignals.clear(); sessionSignalCount = 0; log.info("[SCALP] Daily reset"); }
}