package com.trading.papertrading.service;

import com.trading.config.StrategyConfig;
import com.trading.domain.Candle;
import com.trading.domain.entity.Trade;
import com.trading.domain.enums.TradeDirection;
import com.trading.events.CandleCompleteEvent;
import com.trading.events.CircuitBreakerEvent;
import com.trading.events.TickReceivedEvent;
import com.trading.marketdata.service.MarketTimingService;
import com.trading.papertrading.model.PaperAccount;
import com.trading.regime.service.MarketDirectionService;
import com.trading.risk.service.RiskManagementService;
import com.trading.sector.service.SectorClassificationService;
import com.trading.sector.service.SectorStrengthService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PaperTradeManagementService — manages live paper trade risk migration.
 *
 * 4-PHASE SL MIGRATION:
 *
 *   Phase 1 — Fixed SL
 *   Phase 2 — Breakeven  (at cfg.getGlobal().getBreakevenRTrigger())
 *   Phase 3 — Trailing   (at cfg.getGlobal().getTrendTrailTriggerR())
 *   Phase 4 — Partial exit (at cfg.getGlobal().getPartialExitR())
 *
 * FIX: cfg.getAutoMode().getTrendTrailTriggerR() replaced with
 *      cfg.getGlobal().getTrendTrailTriggerR() at lines 222 and 338.
 *      AutoMode inner class was removed from StrategyConfig.
 *      trendTrailTriggerR is now in StrategyConfig.Global.
 */
@Service
@Slf4j
public class PaperTradeManagementService {

    private final PaperTradeExecutionService  executor;
    private final MarketDirectionService      marketDirection;
    private final SectorStrengthService       sectorStrength;
    private final SectorClassificationService sectorClassify;
    private final MarketTimingService         timing;
    private final PaperAccount               account;
    private final StrategyConfig             cfg;
    private final RiskManagementService      riskManagement;

    public PaperTradeManagementService(
            PaperTradeExecutionService executor,
            MarketDirectionService marketDirection,
            SectorStrengthService sectorStrength,
            SectorClassificationService sectorClassify,
            MarketTimingService timing,
            PaperAccount account,
            StrategyConfig cfg,
            @Lazy RiskManagementService riskManagement) {
        this.executor        = executor;
        this.marketDirection = marketDirection;
        this.sectorStrength  = sectorStrength;
        this.sectorClassify  = sectorClassify;
        this.timing          = timing;
        this.account         = account;
        this.cfg             = cfg;
        this.riskManagement  = riskManagement;
    }

    // ── @Value fields ─────────────────────────────────────────────────────────

    @Value("${trading.mode:LIVE}")
    private String tradingMode;

    @Value("${trading.trail-atr-multiplier:1.0}")
    private double trailAtrMultiplier;

    @Value("${trading.trail-tighten-r:4.0}")
    private double trailTightenR;

    @Value("${trading.trail-tight-atr-multiplier:0.5}")
    private double trailTightAtrMultiplier;

    @Value("${trading.partial-exit-lunch-r:1.0}")
    private double partialExitLunchR;

    @Value("${trading.skip-trail-on-momentum:true}")
    private boolean skipTrailOnMomentum;

    @Value("${trading.exit-on-market-turn:true}")
    private boolean exitOnMarketTurn;

    @Value("${trading.exit-on-sector-turn:true}")
    private boolean exitOnSectorTurn;

    // ── Slippage constants ────────────────────────────────────────────────────
    static final double SL_SLIP     = 0.001;
    static final double TARGET_SLIP = 0.0005;
    static final double EOD_SLIP    = 0.0015;

    // ── NSE 5-paise tick ──────────────────────────────────────────────────────
    static final BigDecimal TICK = new BigDecimal("0.05");

    // ── State ─────────────────────────────────────────────────────────────────
    private final Map<String, ManagedTrade> activeTrades = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal>   lastPrices   = new ConcurrentHashMap<>();

    // ══════════════════════════════════════════════════════════════════════════
    // ManagedTrade record
    // ══════════════════════════════════════════════════════════════════════════

    public record ManagedTrade(
            Trade          trade,
            BigDecimal     originalSl,
            BigDecimal     rDistance,
            double         atr,
            boolean        slAtBreakeven,
            boolean        trailActive,
            boolean        halfExited,
            int            qty,
            int            remainingQty,
            MarketTimingService.TimeWindow entryWindow,
            boolean        strongTrend,
            int            timeStopMinutes,
            Instant        entryInstant
    ) {}

    // ══════════════════════════════════════════════════════════════════════════
    // PUBLIC QUERY
    // ══════════════════════════════════════════════════════════════════════════

    public boolean isAnyTradeAtBreakevenOrBeyond() {
        return activeTrades.values().stream()
                .anyMatch(ManagedTrade::slAtBreakeven);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // NSE TICK ALIGNMENT
    // ══════════════════════════════════════════════════════════════════════════

    static BigDecimal alignToTick(BigDecimal price, RoundingMode mode) {
        BigDecimal ticks = price.multiply(BigDecimal.valueOf(20), MathContext.DECIMAL64)
                .setScale(0, mode);
        return ticks.divide(BigDecimal.valueOf(20), 2, RoundingMode.UNNECESSARY);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CIRCUIT BREAKER EVENT LISTENER
    // ══════════════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tradingExecutor")
    public void onCircuitBreakerEvent(CircuitBreakerEvent event) {
        if (!"PAPER".equalsIgnoreCase(tradingMode)) return;
        String type = event.getEventType();
        if (type == null || !type.endsWith("CLOSE_ALL")) return;
        if (activeTrades.isEmpty()) return;

        log.warn("[PAPER] CIRCUIT BREAKER CLOSE ALL [{}]: {} — liquidating {} position(s)",
                type, event.getReason(), activeTrades.size());
        emergencyCloseAll("CB_" + type);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Register — called by PaperTradeExecutionService after entry
    // ══════════════════════════════════════════════════════════════════════════

    public void register(Trade trade, double atr,
                         MarketTimingService.TimeWindow entryWindow,
                         boolean strongTrend,
                         int timeStopMinutes) {
        BigDecimal entry = trade.getEntryPrice();
        BigDecimal sl    = trade.getStopLoss();
        BigDecimal rDist = entry.subtract(sl).abs();

        activeTrades.put(trade.getTradingSymbol(), new ManagedTrade(
                trade, sl, rDist, atr,
                false, false, false,
                trade.getQuantity(), trade.getQuantity(),
                entryWindow, strongTrend,
                timeStopMinutes,
                Instant.now()
        ));

        // NOTE: cfg.getGlobal().getTrendTrailTriggerR() — AutoMode was removed
        log.info("[PAPER] Registered: {} dir={} entry={} sl={} 1R={} " +
                        "beAt={}R trailAt={}R atr={} window={} trend={} timeStop={}",
                trade.getTradingSymbol(), trade.getDirection(),
                entry, sl, rDist,
                cfg.getGlobal().getBreakevenRTrigger(),
                cfg.getGlobal().getTrendTrailTriggerR(),   // FIX: was getAutoMode()
                String.format("%.2f", atr), entryWindow, strongTrend,
                timeStopMinutes > 0
                        ? timeStopMinutes + "min"
                        : "none(global=" + cfg.getGlobal().getGlobalTimeStop().toMinutes() + "m)");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TICK-LEVEL MONITORING — Phase 1, 2, 4 + Time Stop
    // ══════════════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tickExecutor")
    public void onTick(TickReceivedEvent tick) {
        if (!"PAPER".equalsIgnoreCase(tradingMode)) return;
        String     sym = tick.getTradingSymbol();
        BigDecimal ltp = tick.getLastTradedPrice();
        lastPrices.put(sym, ltp);
        manageTrade(sym, ltp);
    }

    private void manageTrade(String sym, BigDecimal ltp) {
        ManagedTrade mt = activeTrades.get(sym);
        if (mt == null) return;

        Trade   t     = mt.trade();
        boolean long_ = t.getDirection() == TradeDirection.LONG;

        // Phase 1: SL hit
        if (long_ ? ltp.compareTo(t.getStopLoss()) <= 0
                : ltp.compareTo(t.getStopLoss()) >= 0) {
            BigDecimal slFill = simulateSlFill(t.getStopLoss(), ltp, t.getDirection());
            log.info("[PAPER] SL HIT: {} sl={} ltp={} fill={}",
                    sym, t.getStopLoss(), ltp, slFill);
            closeTrade(sym, slFill, "STOPLOSS_HIT", mt.slAtBreakeven());
            return;
        }

        // Target hit
        if (long_ ? ltp.compareTo(t.getTarget()) >= 0
                : ltp.compareTo(t.getTarget()) <= 0) {
            BigDecimal targetFill = simulateTargetFill(t.getTarget(), t.getDirection());
            closeTrade(sym, targetFill, "TARGET_HIT", mt.slAtBreakeven());
            return;
        }

        // Time Stop
        {
            long effectiveStop = mt.timeStopMinutes() > 0
                    ? mt.timeStopMinutes()
                    : cfg.getGlobal().getGlobalTimeStop().toMinutes();
            long elapsed = (Instant.now().getEpochSecond()
                    - mt.entryInstant().getEpochSecond()) / 60;
            if (elapsed >= effectiveStop) {
                BigDecimal eodFill = simulateEodFill(ltp, t.getDirection());
                String label = mt.timeStopMinutes() > 0 ? "STRATEGY" : "GLOBAL";
                log.warn("[PAPER] TIME STOP ({}): {} {}min elapsed (limit={}min) fill={}",
                        label, sym, elapsed, effectiveStop, eodFill);
                closeTrade(sym, eodFill,
                        "TIME_STOP_" + effectiveStop + "MIN", mt.slAtBreakeven());
                return;
            }
        }

        // R-multiple
        BigDecimal rDist = mt.rDistance();
        if (rDist.compareTo(BigDecimal.ZERO) == 0) return;
        double profit = long_
                ? ltp.subtract(t.getEntryPrice()).doubleValue()
                : t.getEntryPrice().subtract(ltp).doubleValue();
        double rMultiple = profit / rDist.doubleValue();

        // Phase 2: Breakeven
        if (!mt.slAtBreakeven()
                && rMultiple >= cfg.getGlobal().getBreakevenRTrigger()) {
            moveSlToBreakeven(sym, mt);
            mt = activeTrades.get(sym);
        }

        // Phase 4: Partial exit
        handlePartialExit(sym, mt, ltp, rMultiple);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PHASE 3: Trailing SL — every 5-min candle close
    // ══════════════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tradingExecutor")
    public void onCandle(CandleCompleteEvent event) {
        if (!"PAPER".equalsIgnoreCase(tradingMode)) return;
        if (!"5minute".equals(event.getCandle().getTimeframe())) return;

        String       sym = event.getCandle().getTradingSymbol();
        ManagedTrade mt  = activeTrades.get(sym);
        if (mt == null) return;

        if (skipTrailOnMomentum && isMomentumCandle(event.getCandle())) {
            log.debug("[PAPER] Momentum candle {} — skip trailing", sym);
            return;
        }

        updateTrailingSl(sym, mt, event.getCandle().getClose());
    }

    private void updateTrailingSl(String sym, ManagedTrade mt, BigDecimal price) {
        if (!mt.slAtBreakeven()) return;

        Trade   t     = mt.trade();
        boolean long_ = t.getDirection() == TradeDirection.LONG;

        double profit = long_
                ? price.subtract(t.getEntryPrice()).doubleValue()
                : t.getEntryPrice().subtract(price).doubleValue();
        double rMultiple = mt.rDistance().doubleValue() > 0
                ? profit / mt.rDistance().doubleValue() : 0;

        // FIX: was cfg.getAutoMode().getTrendTrailTriggerR()
        //      AutoMode removed — now in cfg.getGlobal().getTrendTrailTriggerR()
        double trailStartR = cfg.getGlobal().getTrendTrailTriggerR();

        if (rMultiple < trailStartR) {
            log.debug("[PAPER] Trail inactive {}: {:.2f}R < {:.2f}R",
                    sym, rMultiple, trailStartR);
            return;
        }

        if (!mt.trailActive()) {
            log.info("[PAPER] Phase-3 TRAIL ACTIVATED: {} at {:.2f}R", sym, rMultiple);
            ManagedTrade updated = new ManagedTrade(
                    mt.trade(), mt.originalSl(), mt.rDistance(), mt.atr(),
                    mt.slAtBreakeven(), true, mt.halfExited(),
                    mt.qty(), mt.remainingQty(),
                    mt.entryWindow(), mt.strongTrend(),
                    mt.timeStopMinutes(), mt.entryInstant());
            activeTrades.put(sym, updated);
            mt = updated;
        }

        double atrMultiplier = mt.halfExited()
                ? trailTightAtrMultiplier
                : trailAtrMultiplier;

        double     trailDist = mt.atr() * atrMultiplier;
        BigDecimal rawSl     = long_
                ? price.subtract(BigDecimal.valueOf(trailDist))
                : price.add(BigDecimal.valueOf(trailDist));

        BigDecimal newSl = alignToTick(rawSl,
                long_ ? RoundingMode.FLOOR : RoundingMode.CEILING);

        boolean improve = long_
                ? newSl.compareTo(t.getStopLoss()) > 0
                : newSl.compareTo(t.getStopLoss()) < 0;

        if (improve) {
            t.setStopLoss(newSl);
            t.setUpdatedAt(Instant.now());
            log.info("[PAPER] Trail SL updated: {} newSl={} {:.2f}R atrMult={}",
                    sym, newSl, rMultiple, atrMultiplier);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PHASE 4: Partial exit
    // ══════════════════════════════════════════════════════════════════════════

    private void handlePartialExit(String sym, ManagedTrade mt,
                                   BigDecimal ltp, double rMultiple) {
        if (mt.halfExited()) return;
        if (mt.remainingQty() <= 1) return;

        double halfExitAt = 0;
        if (mt.entryWindow() == MarketTimingService.TimeWindow.LUNCH) {
            halfExitAt = partialExitLunchR;
        } else if (!mt.strongTrend()) {
            halfExitAt = cfg.getGlobal().getPartialExitR();
        }

        if (halfExitAt > 0 && rMultiple >= halfExitAt) {
            int     halfQty = mt.remainingQty() / 2;
            boolean long_   = mt.trade().getDirection() == TradeDirection.LONG;

            BigDecimal rawFill = long_
                    ? ltp.multiply(
                    BigDecimal.valueOf(1.0 - TARGET_SLIP), MathContext.DECIMAL64)
                    : ltp.multiply(
                    BigDecimal.valueOf(1.0 + TARGET_SLIP), MathContext.DECIMAL64);
            BigDecimal partialFill = alignToTick(rawFill,
                    long_ ? RoundingMode.FLOOR : RoundingMode.CEILING);

            BigDecimal entryPrice = mt.trade().getEntryPrice();
            BigDecimal grossPnl   = long_
                    ? partialFill.subtract(entryPrice)
                    .multiply(BigDecimal.valueOf(halfQty))
                    : entryPrice.subtract(partialFill)
                    .multiply(BigDecimal.valueOf(halfQty));

            BigDecimal exitCost = NseBrokerageCalculator.exitLegCost(
                    partialFill, halfQty, mt.trade().getDirection());
            BigDecimal netPnl   = grossPnl.subtract(exitCost);

            account.applyPartialPnl(netPnl);

            log.info("[PAPER] Phase-4 PARTIAL EXIT: {} qty={} fill={} ({:.2f}R) net={}",
                    sym, halfQty, partialFill, halfExitAt,
                    String.format("%.2f", netPnl.doubleValue()));

            ManagedTrade updated = new ManagedTrade(
                    mt.trade(), mt.originalSl(), mt.rDistance(), mt.atr(),
                    mt.slAtBreakeven(), mt.trailActive(), true,
                    mt.qty(), mt.remainingQty() - halfQty,
                    mt.entryWindow(), mt.strongTrend(),
                    mt.timeStopMinutes(), mt.entryInstant());
            activeTrades.put(sym, updated);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PHASE 2: Move SL to breakeven
    // ══════════════════════════════════════════════════════════════════════════

    private void moveSlToBreakeven(String sym, ManagedTrade mt) {
        Trade t = mt.trade();
        t.setStopLoss(t.getEntryPrice());
        t.setUpdatedAt(Instant.now());

        ManagedTrade updated = new ManagedTrade(
                mt.trade(), mt.originalSl(), mt.rDistance(), mt.atr(),
                true, mt.trailActive(), mt.halfExited(),
                mt.qty(), mt.remainingQty(),
                mt.entryWindow(), mt.strongTrend(),
                mt.timeStopMinutes(), mt.entryInstant());
        activeTrades.put(sym, updated);

        log.info("[PAPER] Phase-2 BREAKEVEN: {} entry={} triggered at {}R",
                sym, t.getEntryPrice(), cfg.getGlobal().getBreakevenRTrigger());

        riskManagement.notifyPhase2Migration(sym);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SCENARIOS 6 + 7: Market / sector alignment on 15-min candle
    // ══════════════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tradingExecutor")
    public void onCandle15m(CandleCompleteEvent event) {
        if (!"PAPER".equalsIgnoreCase(tradingMode)) return;
        if (!"15minute".equals(event.getCandle().getTimeframe())) return;
        checkAllTradesAlignment();
    }

    private void checkAllTradesAlignment() {
        if (!exitOnMarketTurn && !exitOnSectorTurn) return;

        MarketDirectionService.MarketDirectionResult dir =
                marketDirection.getCurrentDirection();

        for (Map.Entry<String, ManagedTrade> entry : activeTrades.entrySet()) {
            String       sym     = entry.getKey();
            ManagedTrade mt      = entry.getValue();
            Trade        t       = mt.trade();
            boolean      forLong = t.getDirection() == TradeDirection.LONG;

            if (exitOnMarketTurn) {
                boolean marketTurned = forLong
                        ? dir.direction() == MarketDirectionService.Direction.BEARISH
                        : dir.direction() == MarketDirectionService.Direction.BULLISH;
                if (marketTurned) {
                    BigDecimal ltp     = lastPrices.getOrDefault(sym, t.getEntryPrice());
                    BigDecimal eodFill = simulateEodFill(ltp, t.getDirection());
                    log.warn("[PAPER] Market turned against {} — exiting at {}", sym, eodFill);
                    closeTrade(sym, eodFill, "MARKET_TURNED", mt.slAtBreakeven());
                    continue;
                }
            }

            if (exitOnSectorTurn) {
                if (!sectorStrength.isSectorAlignedForSymbol(sym, forLong)) {
                    BigDecimal ltp     = lastPrices.getOrDefault(sym, t.getEntryPrice());
                    BigDecimal eodFill = simulateEodFill(ltp, t.getDirection());
                    log.warn("[PAPER] Sector turned against {} — exiting at {}", sym, eodFill);
                    closeTrade(sym, eodFill, "SECTOR_TURNED", mt.slAtBreakeven());
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SCENARIO 9: Force close at 15:00 IST
    // ══════════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 0 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void forceCloseAll() {
        if (!"PAPER".equalsIgnoreCase(tradingMode)) return;
        if (activeTrades.isEmpty()) return;
        log.warn("[PAPER] FORCE CLOSE 15:00 — {} positions", activeTrades.size());
        emergencyCloseAll("TIME_EXIT_15:00");
    }

    private void emergencyCloseAll(String reason) {
        new ArrayList<>(activeTrades.keySet()).forEach(sym -> {
            ManagedTrade mt = activeTrades.get(sym);
            if (mt == null) return;
            BigDecimal ltp     = lastPrices.getOrDefault(sym, mt.trade().getEntryPrice());
            BigDecimal eodFill = simulateEodFill(ltp, mt.trade().getDirection());
            closeTrade(sym, eodFill, reason, mt.slAtBreakeven());
        });
    }

    // ══════════════════════════════════════════════════════════════════════════
    // closeTrade
    // ══════════════════════════════════════════════════════════════════════════

    private void closeTrade(String sym, BigDecimal exitPrice,
                            String reason, boolean reachedPhase2) {
        ManagedTrade mt = activeTrades.remove(sym);
        if (mt == null) return;
        executor.closeTrade(mt.trade(), exitPrice, reason, reachedPhase2);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Dashboard getters
    // ══════════════════════════════════════════════════════════════════════════

    public Collection<ManagedTrade> getActiveTrades() {
        return Collections.unmodifiableCollection(activeTrades.values());
    }

    public Map<String, BigDecimal> getLastPrices() {
        return Collections.unmodifiableMap(lastPrices);
    }

    public int getRemainingQty(String sym, int defaultQty) {
        ManagedTrade mt = activeTrades.get(sym);
        return mt != null ? mt.remainingQty() : defaultQty;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // FILL SIMULATION HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    static BigDecimal simulateSlFill(BigDecimal slPrice, BigDecimal ltp,
                                     TradeDirection dir) {
        if (dir == TradeDirection.LONG) {
            BigDecimal raw     = slPrice.multiply(
                    BigDecimal.valueOf(1.0 - SL_SLIP), MathContext.DECIMAL64);
            BigDecimal gapFill = raw.min(ltp);
            return alignToTick(gapFill, RoundingMode.FLOOR);
        } else {
            BigDecimal raw     = slPrice.multiply(
                    BigDecimal.valueOf(1.0 + SL_SLIP), MathContext.DECIMAL64);
            BigDecimal gapFill = raw.max(ltp);
            return alignToTick(gapFill, RoundingMode.CEILING);
        }
    }

    static BigDecimal simulateTargetFill(BigDecimal targetPrice, TradeDirection dir) {
        if (dir == TradeDirection.LONG) {
            BigDecimal raw = targetPrice.multiply(
                    BigDecimal.valueOf(1.0 - TARGET_SLIP), MathContext.DECIMAL64);
            return alignToTick(raw, RoundingMode.FLOOR);
        } else {
            BigDecimal raw = targetPrice.multiply(
                    BigDecimal.valueOf(1.0 + TARGET_SLIP), MathContext.DECIMAL64);
            return alignToTick(raw, RoundingMode.CEILING);
        }
    }

    static BigDecimal simulateEodFill(BigDecimal ltp, TradeDirection dir) {
        if (dir == TradeDirection.LONG) {
            BigDecimal raw = ltp.multiply(
                    BigDecimal.valueOf(1.0 - EOD_SLIP), MathContext.DECIMAL64);
            return alignToTick(raw, RoundingMode.FLOOR);
        } else {
            BigDecimal raw = ltp.multiply(
                    BigDecimal.valueOf(1.0 + EOD_SLIP), MathContext.DECIMAL64);
            return alignToTick(raw, RoundingMode.CEILING);
        }
    }

    private boolean isMomentumCandle(Candle c) {
        return c.bodyPct().compareTo(new BigDecimal("0.80")) >= 0;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // NSE BROKERAGE CALCULATOR — EXIT LEG ONLY
    // ══════════════════════════════════════════════════════════════════════════

    static final class NseBrokerageCalculator {

        private static final BigDecimal BROKERAGE_RATE    = new BigDecimal("0.0003");
        private static final BigDecimal BROKERAGE_CAP     = new BigDecimal("20.00");
        private static final BigDecimal STT_RATE          = new BigDecimal("0.00025");
        private static final BigDecimal EXCHANGE_TXN_RATE = new BigDecimal("0.0000335");
        private static final BigDecimal SEBI_RATE         = new BigDecimal("0.000001");
        private static final BigDecimal GST_RATE          = new BigDecimal("0.18");

        private NseBrokerageCalculator() {}

        static BigDecimal exitLegCost(BigDecimal fillPrice, int qty,
                                      TradeDirection direction) {
            BigDecimal turnover    = fillPrice.multiply(BigDecimal.valueOf(qty));
            BigDecimal brokerage   = turnover.multiply(BROKERAGE_RATE).min(BROKERAGE_CAP);
            BigDecimal stt         = direction == TradeDirection.LONG
                    ? turnover.multiply(STT_RATE) : BigDecimal.ZERO;
            BigDecimal exchangeTxn = turnover.multiply(EXCHANGE_TXN_RATE);
            BigDecimal sebi        = turnover.multiply(SEBI_RATE);
            BigDecimal gst         = brokerage.add(exchangeTxn).add(sebi)
                    .multiply(GST_RATE);
            return brokerage.add(stt).add(exchangeTxn).add(sebi).add(gst)
                    .setScale(2, RoundingMode.CEILING);
        }
    }
}