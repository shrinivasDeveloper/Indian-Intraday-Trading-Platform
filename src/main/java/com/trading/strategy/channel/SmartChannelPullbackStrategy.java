package com.trading.strategy.channel;

import com.trading.analysis.service.RvolService;
import com.trading.analysis.service.TechnicalAnalysisService;
import com.trading.domain.Candle;
import com.trading.domain.enums.TradeDirection;
import com.trading.events.CandleCompleteEvent;
import com.trading.events.SmartChannelPullbackSignalEvent;
import com.trading.marketdata.service.LatencyMonitor;
import com.trading.marketdata.service.MarketPressureService;
import com.trading.marketdata.service.MarketPressureService.PressureSnapshot;
import com.trading.marketdata.service.MarketTimingService;
import com.trading.marketdata.service.VixService;
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
 * SmartChannelPullbackStrategy — v3 (Market Pressure Edition).
 *
 * KEY CHANGE: Direction is now derived from MarketPressureService, NOT MarketDirectionService.
 *   - BUY_PRESSURE  → find pullbacks at channel support  (BULLISH or SIDEWAYS channels)
 *   - SELL_PRESSURE → find pullbacks at channel resistance (BEARISH or SIDEWAYS channels)
 *   - NEUTRAL       → skip (no clear market-wide conviction)
 *
 * Sideways market is handled differently: both BUY and SELL signals can fire
 * inside SIDEWAYS channels depending on whether pressure is BUY or SELL.
 * No separate "sideways-only" strategy needed.
 *
 * ALL ORIGINAL GATES PRESERVED + DETAILED REJECTION LOGGING.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SmartChannelPullbackStrategy {

    private static final ZoneId IST           = ZoneId.of("Asia/Kolkata");
    private static final String STRATEGY_NAME = "SMART_CHANNEL_PULLBACK_V3";

    private static final LocalTime ENTRY_START = LocalTime.of(9, 40);
    private static final LocalTime ENTRY_END   = LocalTime.of(14, 40);

    private static final double PULLBACK_BEST_MIN = 0.003;
    private static final double PULLBACK_BEST_MAX = 0.005;
    private static final double PULLBACK_GOOD_MAX = 0.008;
    private static final double PULLBACK_LATE_MAX = 0.010;

    private static final double LARGE_CAP_SKIP   = 0.03;
    private static final double MID_CAP_SKIP     = 0.05;
    private static final long   SYMBOL_COOLDOWN_MS = 60 * 60 * 1000L;

    private final ApplicationEventPublisher   publisher;
    private final MarketPressureService       pressureService;
    private final SectorStrengthService       sectorStrength;
    private final SectorClassificationService sectorClassify;
    private final ChannelDetectionService     channelDetection;
    private final RvolService                 rvolService;
    private final TechnicalAnalysisService    technicalAnalysis;
    private final MarketTimingService         timingService;
    private final VixService                  vixService;
    private final CircuitBreakerService       circuitBreaker;
    private final PositionSizerService        positionSizer;
    private final PaperAccount               paperAccount;
    private final LatencyMonitor             latencyMonitor;

    @Value("${trading.mode:PAPER}")
    private String tradingMode;

    @Value("${trading.capital:100000}")
    private BigDecimal capital;

    @Value("${strategy.smart-channel-pullback.enabled:true}")
    private boolean strategyEnabled;

    @Value("${strategy.smart-channel-pullback.time-stop-minutes:60}")
    private int timeStopMinutes;

    @Value("${strategy.smart-channel-pullback.min-rvol:1.0}")
    private double minRvol;

    @Value("${strategy.smart-channel-pullback.require-high-quality-channel:false}")
    private boolean requireHighQualityChannel;

    @Value("${strategy.smart-channel-pullback.max-signals-per-session:3}")
    private int maxSignalsPerSession;

    @Value("${strategy.smart-channel-pullback.sector-buy-threshold:0.05}")
    private double sectorBuyThreshold;

    @Value("${strategy.smart-channel-pullback.sector-sell-threshold:-0.05}")
    private double sectorSellThreshold;

    private final Map<String, Long> lastSignalTime    = new ConcurrentHashMap<>();
    private final Set<String>       activeSignals     = ConcurrentHashMap.newKeySet();
    private volatile int            sessionSignalCount = 0;

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
        if (sessionSignalCount >= maxSignalsPerSession) return;

        evaluateStock(c.getTradingSymbol(), c);
    }

    private void evaluateStock(String symbol, Candle latestCandle) {

        if (activeSignals.contains(symbol)) return;
        Long lastFired = lastSignalTime.get(symbol);
        if (lastFired != null && System.currentTimeMillis() - lastFired < SYMBOL_COOLDOWN_MS) return;

        // ── GATE 1: MARKET PRESSURE (replaces MarketDirectionService) ─────────
        PressureSnapshot pressure = pressureService.getSnapshot();

        if (!pressure.isActionable()) {
            log.trace("[SCPS] {} skipped — pressure not actionable (dir={} ratio={} syms={} locked={})",
                    symbol, pressure.direction(),
                    String.format("%.3f", pressure.ratio()),
                    pressure.totalSymbols(), pressure.openLocked());
            return;
        }

        boolean isBuy      = pressure.isBuy();
        TradeDirection dir = isBuy ? TradeDirection.LONG : TradeDirection.SHORT;
        String bias        = isBuy ? "BUY_PRESSURE" : "SELL_PRESSURE";

        // ── GATE 2: Sector filter ─────────────────────────────────────────────
        String sectorName = sectorClassify.getSector(symbol);
        SectorStrengthService.SectorData sectorData = sectorStrength.getSector(sectorName);
        double sectorChg = sectorData.changePercent();

        if (isBuy && sectorChg < 0) {
            log.debug("REJECTED: {} → sector {} bearish ({:.2f}%) vs BUY pressure",
                    symbol, sectorName, sectorChg);
            return;
        }
        if (!isBuy && sectorChg > 0) {
            log.debug("REJECTED: {} → sector {} bullish ({:.2f}%) vs SELL pressure",
                    symbol, sectorName, sectorChg);
            return;
        }
        if (isBuy && sectorChg < sectorBuyThreshold) {
            log.debug("REJECTED: {} → sector {} change {:.2f}% below threshold {}",
                    symbol, sectorName, sectorChg, sectorBuyThreshold);
            return;
        }
        if (!isBuy && sectorChg > sectorSellThreshold) {
            log.debug("REJECTED: {} → sector {} change {:.2f}% above threshold {}",
                    symbol, sectorName, sectorChg, sectorSellThreshold);
            return;
        }

        // ── GATE 3: VWAP alignment ────────────────────────────────────────────
        TechnicalAnalysisService.TechnicalStructure structure = technicalAnalysis.getStructure(symbol);
        if (structure.vwap().compareTo(BigDecimal.ZERO) != 0) {
            double vwap  = structure.vwap().doubleValue();
            double price = latestCandle.getClose().doubleValue();
            boolean vwapOk = isBuy ? price >= vwap : price <= vwap;
            if (!vwapOk) {
                log.debug("REJECTED: {} → price {:.2f} not aligned with VWAP {:.2f} for {}",
                        symbol, price, vwap, bias);
                return;
            }
        }

        // ── GATE 4: Channel validation ────────────────────────────────────────
        ChannelDetectionService.ChannelResult channel = channelDetection.getChannel(symbol);
        if (!channel.isValid()) {
            log.trace("REJECTED: {} → channel invalid: {}", symbol, channel.reason());
            return;
        }
        if (channel.isTransitioning()) {
            log.trace("REJECTED: {} → channel transitioning", symbol);
            return;
        }
        if (requireHighQualityChannel && !channel.isHighQuality()) {
            log.debug("REJECTED: {} → channel not HIGH_QUALITY", symbol);
            return;
        }

        // Channel must align with pressure direction
        boolean channelOk = isBuy
                ? (channel.type() == ChannelDetectionService.ChannelType.BULLISH
                || channel.type() == ChannelDetectionService.ChannelType.SIDEWAYS)
                : (channel.type() == ChannelDetectionService.ChannelType.BEARISH
                || channel.type() == ChannelDetectionService.ChannelType.SIDEWAYS);

        if (!channelOk) {
            log.debug("REJECTED: {} → channel type {} does not fit {}", symbol, channel.type(), bias);
            return;
        }

        // ── GATE 5: Pullback zone ─────────────────────────────────────────────
        double currentPrice = latestCandle.getClose().doubleValue();
        if (!channel.isPriceInPullbackZone(currentPrice)) {
            log.trace("REJECTED: {} → price {:.2f} not in pullback zone [{:.2f},{:.2f}]",
                    symbol, currentPrice, channel.pullbackZoneBottom(), channel.pullbackZoneTop());
            return;
        }

        double pullbackPct = isBuy
                ? (currentPrice - channel.supportPrice()) / channel.supportPrice()
                : (channel.resistancePrice() - currentPrice) / channel.resistancePrice();

        if (pullbackPct > PULLBACK_LATE_MAX) {
            log.debug("REJECTED: {} → pullback {:.2f}% > 1% (too deep)", symbol, pullbackPct * 100);
            return;
        }
        if (pullbackPct < PULLBACK_BEST_MIN) {
            log.trace("REJECTED: {} → pullback {:.2f}% < 0.3% (too shallow)", symbol, pullbackPct * 100);
            return;
        }

        String pullbackStrength = pullbackPct <= PULLBACK_BEST_MAX ? "BEST"
                : pullbackPct <= PULLBACK_GOOD_MAX ? "GOOD" : "LATE";

        // ── GATE 6: Overextension ─────────────────────────────────────────────
        double dayChange = Math.abs(currentPrice - channel.supportPrice()) / channel.supportPrice();
        double skipAt    = "LARGE".equals(getCapType(symbol)) ? LARGE_CAP_SKIP : MID_CAP_SKIP;
        if (dayChange >= skipAt) {
            log.debug("REJECTED: {} → overextended {:.2f}%", symbol, dayChange * 100);
            return;
        }

        // ── Build trade params ─────────────────────────────────────────────────
        BigDecimal entryPrice = latestCandle.getClose().setScale(2, RoundingMode.HALF_UP);
        BigDecimal stopLoss;
        BigDecimal target1;
        BigDecimal target2;

        if (isBuy) {
            stopLoss = BigDecimal.valueOf(channel.supportPrice() * 0.998).setScale(2, RoundingMode.FLOOR);
            BigDecimal r = entryPrice.subtract(stopLoss).abs();
            target1 = entryPrice.add(r.multiply(BigDecimal.valueOf(2)));
            target2 = entryPrice.add(r.multiply(BigDecimal.valueOf(3)));
        } else {
            stopLoss = BigDecimal.valueOf(channel.resistancePrice() * 1.002).setScale(2, RoundingMode.CEILING);
            BigDecimal r = stopLoss.subtract(entryPrice).abs();
            target1 = entryPrice.subtract(r.multiply(BigDecimal.valueOf(2)));
            target2 = entryPrice.subtract(r.multiply(BigDecimal.valueOf(3)));
        }

        BigDecimal risk = entryPrice.subtract(stopLoss).abs();
        if (risk.compareTo(BigDecimal.ZERO) == 0) return;

        double slipAdj   = entryPrice.doubleValue() * 0.0005;
        double adjRisk   = risk.doubleValue() + slipAdj;
        double adjReward = target1.subtract(entryPrice).abs().doubleValue() - slipAdj;
        double rrRatio   = adjReward / adjRisk;
        if (rrRatio < 1.8) {
            log.debug("REJECTED: {} → RR {:.2f} < 1.8 after slippage", symbol, rrRatio);
            return;
        }

        BigDecimal cap = resolveCapital();
        PositionSizerService.PositionSize pos =
                positionSizer.calculate(cap, entryPrice, stopLoss, symbol, dir.name());
        if (!pos.isValid() || pos.quantity() <= 0) {
            log.debug("REJECTED: {} → invalid position size: {}", symbol, pos.invalidReason());
            return;
        }

        // ── Scoring ────────────────────────────────────────────────────────────
        double rvol = rvolService.getRvolNow(symbol, latestCandle.getVolume());
        boolean vwapAligned = isVwapAligned(latestCandle, structure, isBuy);

        int scoreVwap     = vwapAligned ? 15 : 0;
        int scoreRvol     = rvol >= 1.2 ? 20 : rvol >= 1 ? 10 : 0;
        int scorePressure = pressure.ratio() >= 2.0 ? 20 : pressure.ratio() >= 1.5 ? 15
                : pressure.ratio() >= 1.2 ? 10 : 5;
        int scoreClean    = isCleanEntry(currentPrice, channel, isBuy) ? 15 : 0;
        int scoreEarly    = "BEST".equals(pullbackStrength) ? 15 : 0;
        int scoreNoSR     = !hasNearbyStructure(entryPrice, structure, isBuy) ? 15 : 0;
        int totalScore    = scoreVwap + scoreRvol + scorePressure + scoreClean + scoreEarly + scoreNoSR;

        log.info("[SCPS] 🚀 SIGNAL: {} | {} | entry={} sl={} T1={} | pullback={}({}%) | " +
                        "RVOL={} | pressure={} ratio={} | sector={}({}%) | score={}",
                symbol, dir, entryPrice, stopLoss, target1,
                pullbackStrength, String.format("%.2f", pullbackPct * 100),
                String.format("%.2f", rvol),
                pressure.direction(), String.format("%.3f", pressure.ratio()),
                sectorName, String.format("%.2f", sectorChg), totalScore);

        SmartChannelPullbackSignalEvent signal = new SmartChannelPullbackSignalEvent(
                this, symbol, latestCandle.getInstrumentToken(), dir,
                entryPrice, stopLoss, target1, target2,
                pos.quantity(), pos.actualRisk(),
                STRATEGY_NAME, totalScore,
                sectorName, sectorData.changePercent(),
                channel.isHighQuality() ? "HIGH_QUALITY" : "VALID",
                pullbackStrength, pullbackPct, rvol, vwapAligned, "LIMIT", bias,
                scoreVwap, scoreRvol, scorePressure, scoreClean, scoreEarly, scoreNoSR,
                totalScore, timeStopMinutes
        );

        publisher.publishEvent(signal);

        lastSignalTime.put(symbol, System.currentTimeMillis());
        activeSignals.add(symbol);
        sessionSignalCount++;

        log.info("[SCPS] Signal #{} fired for {}", sessionSignalCount, symbol);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private boolean isVwapAligned(Candle c, TechnicalAnalysisService.TechnicalStructure s,
                                  boolean isBuy) {
        if (s.vwap() == null || s.vwap().compareTo(BigDecimal.ZERO) == 0) return false;
        double vwap = s.vwap().doubleValue();
        double price = c.getClose().doubleValue();
        return isBuy ? price >= vwap : price <= vwap;
    }

    private boolean isCleanEntry(double price, ChannelDetectionService.ChannelResult ch, boolean isBuy) {
        double target = isBuy ? ch.supportPrice() : ch.resistancePrice();
        return Math.abs(price - target) <= target * 0.002;
    }

    private boolean hasNearbyStructure(BigDecimal entry,
                                       TechnicalAnalysisService.TechnicalStructure s,
                                       boolean isBuy) {
        double p = entry.doubleValue(), tol = p * 0.005;
        if (isBuy) return s.resistanceZones().stream().anyMatch(r -> Math.abs(r.doubleValue() - p) < tol);
        else       return s.supportZones().stream().anyMatch(su -> Math.abs(su.doubleValue() - p) < tol);
    }

    private String getCapType(String sym) {
        return Set.of("RELIANCE","TCS","HDFCBANK","INFY","ICICIBANK","HINDUNILVR","ITC","SBIN",
                "BHARTIARTL","KOTAKBANK","LT","BAJFINANCE","HCLTECH","ASIANPAINT","AXISBANK",
                "MARUTI","SUNPHARMA","TITAN","BAJAJFINSV","ULTRACEMCO","ONGC","WIPRO","TECHM",
                "NTPC","POWERGRID","JSWSTEEL","TATAMOTORS","TATASTEEL").contains(sym) ? "LARGE" : "MID";
    }

    private BigDecimal resolveCapital() {
        return "PAPER".equalsIgnoreCase(tradingMode) ? paperAccount.getCapital() : capital;
    }

    public void onSignalClosed(String symbol) {
        activeSignals.remove(symbol);
        log.debug("[SCPS] Signal lock released for {}", symbol);
    }

    public int        getSessionSignalCount() { return sessionSignalCount; }
    public int        getActiveSignalCount()  { return activeSignals.size(); }
    public Set<String> getActiveSignals()     { return Collections.unmodifiableSet(activeSignals); }
    public boolean    isEnabled()             { return strategyEnabled; }

    @Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyReset() {
        lastSignalTime.clear();
        activeSignals.clear();
        sessionSignalCount = 0;
        log.info("[SCPS] Daily reset complete");
    }
}