package com.trading.papertrading.service;

import com.trading.domain.Candle;
import com.trading.domain.entity.Trade;
import com.trading.domain.enums.TradeDirection;
import com.trading.events.CandleCompleteEvent;
import com.trading.events.TickReceivedEvent;
import com.trading.marketdata.service.MarketTimingService;
import com.trading.regime.service.MarketDirectionService;
import com.trading.scanner.service.SevenGateScannerService;
import com.trading.sector.service.SectorClassificationService;
import com.trading.sector.service.SectorStrengthService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Paper trading management — exact mirror of TradeManagementService.
 *
 * ALL 9 scenarios from live TradeManagementService are handled identically:
 *   1. SL hit          → closeTrade + cooldown
 *   2. Target hit      → closeTrade
 *   3. Breakeven at 1R → update Trade.stopLoss in memory (no API call)
 *   4. Trailing SL     → update Trade.stopLoss in memory (no API call)
 *   5. Partial exit    → calculate partial P&L, update remainingQty
 *   6. Market turns    → exit at LTP
 *   7. Sector turns    → exit at LTP
 *   8. Momentum candle → skip trailing
 *   9. Force close     → 15:00 IST
 *
 * Differences from live TradeManagementService:
 *   - orderClient.modifySlTrigger() → just update Trade.stopLoss in memory
 *   - orderClient.placeMarketOrder() for partial exit → simulate fill at LTP
 *   - closeTrade() calls PaperTradeExecutionService.closeTrade() instead of
 *     calling orderClient and publishing event directly
 *   - All config values read from same application.yml properties (no changes)
 *
 * Same ManagedTrade record structure as live.
 * Same config @Value fields as live.
 */
@Service
@Slf4j
public class PaperTradeManagementService {

    private final PaperTradeExecutionService  executor;
    private final MarketDirectionService      marketDirection;
    private final SectorStrengthService       sectorStrength;
    private final SectorClassificationService sectorClassify;
    private final SevenGateScannerService     scanner;
    private final MarketTimingService         timing;

    public PaperTradeManagementService(
            PaperTradeExecutionService executor,
            MarketDirectionService marketDirection,
            SectorStrengthService sectorStrength,
            SectorClassificationService sectorClassify,
            SevenGateScannerService scanner,
            MarketTimingService timing) {
        this.executor        = executor;
        this.marketDirection = marketDirection;
        this.sectorStrength  = sectorStrength;
        this.sectorClassify  = sectorClassify;
        this.scanner         = scanner;
        this.timing          = timing;
    }

    // ── Same config @Value fields as live TradeManagementService ─────
    @Value("${trading.mode:LIVE}")
    private String tradingMode;

    @Value("${trading.breakeven-r:1.0}")
    private double breakevenR;

    @Value("${trading.trail-start-r:3.0}")
    private double trailStartR;

    @Value("${trading.trail-atr-multiplier:1.0}")
    private double trailAtrMultiplier;

    @Value("${trading.trail-tighten-r:4.0}")
    private double trailTightenR;

    @Value("${trading.trail-tight-atr-multiplier:0.5}")
    private double trailTightAtrMultiplier;

    @Value("${trading.partial-exit-lunch-r:1.0}")
    private double partialExitLunchR;

    @Value("${trading.partial-exit-moderate-r:1.5}")
    private double partialExitModerateR;

    @Value("${trading.skip-trail-on-momentum:true}")
    private boolean skipTrailOnMomentum;

    @Value("${trading.exit-on-market-turn:true}")
    private boolean exitOnMarketTurn;

    @Value("${trading.exit-on-sector-turn:true}")
    private boolean exitOnSectorTurn;

    // Slippage for paper trade exits
    private static final double SL_SLIP     = 0.001;   // 0.10% extra slip on SL exit
    private static final double TARGET_SLIP = 0.0005;  // 0.05% slip on target exit
    private static final double EOD_SLIP    = 0.0015;  // 0.15% slip on EOD/market-turn exit

    // ── State — same structure as live TradeManagementService ────────
    private final Map<String, ManagedTrade>  activeTrades = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal>    lastPrices   = new ConcurrentHashMap<>();

    // ══════════════════════════════════════════════════════════════════
    // ManagedTrade record — IDENTICAL to live TradeManagementService
    // ══════════════════════════════════════════════════════════════════

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
            boolean        strongTrend
    ) {}

    // ══════════════════════════════════════════════════════════════════
    // Register — called by PaperTradeExecutionService after entry
    // IDENTICAL signature to live TradeManagementService.register()
    // ══════════════════════════════════════════════════════════════════

    public void register(Trade trade, double atr,
                         MarketTimingService.TimeWindow entryWindow,
                         boolean strongTrend) {
        BigDecimal entry = trade.getEntryPrice();
        BigDecimal sl    = trade.getStopLoss();
        BigDecimal rDist = entry.subtract(sl).abs();

        activeTrades.put(trade.getTradingSymbol(), new ManagedTrade(
                trade, sl, rDist, atr,
                false, false, false,
                trade.getQuantity(), trade.getQuantity(),
                entryWindow, strongTrend
        ));

        log.info("[PAPER] Trade registered: {} dir={} entry={} sl={} 1R={} " +
                        "beAt={}R trailAt={}R atr={} window={} strongTrend={}",
                trade.getTradingSymbol(), trade.getDirection(),
                entry, sl, rDist, breakevenR, trailStartR,
                String.format("%.2f", atr), entryWindow, strongTrend);
    }

    // ══════════════════════════════════════════════════════════════════
    // SCENARIO 1,2,3,5: Tick-level monitoring
    // IDENTICAL to live TradeManagementService.onTick() / manageTrade()
    // ══════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tradingExecutor")
    public void onTick(TickReceivedEvent tick) {
        if (!"PAPER".equalsIgnoreCase(tradingMode)) return;

        String sym = tick.getTradingSymbol();
        BigDecimal ltp = tick.getLastTradedPrice();
        lastPrices.put(sym, ltp);

        manageTrade(sym, ltp);
    }

    private void manageTrade(String sym, BigDecimal ltp) {
        ManagedTrade mt = activeTrades.get(sym);
        if (mt == null) return;

        Trade   t     = mt.trade();
        boolean long_ = t.getDirection() == TradeDirection.LONG;

        // SCENARIO 1: SL hit — same logic as live
        if (long_  ? ltp.compareTo(t.getStopLoss()) <= 0
                : ltp.compareTo(t.getStopLoss()) >= 0) {
            // Paper: simulate SL fill with extra slippage (worst case)
            BigDecimal slFill = simulateSlFill(t.getStopLoss(), t.getDirection());
            closeTrade(sym, slFill, "STOPLOSS_HIT");
            scanner.startCooldown(sym);  // same cooldown as live
            return;
        }

        // SCENARIO 2: Target hit — same logic as live
        if (long_  ? ltp.compareTo(t.getTarget()) >= 0
                : ltp.compareTo(t.getTarget()) <= 0) {
            // Paper: simulate target fill with small slippage
            BigDecimal targetFill = simulateTargetFill(t.getTarget(), t.getDirection());
            closeTrade(sym, targetFill, "TARGET_HIT");
            return;
        }

        // Calculate R-multiple — same as live
        BigDecimal rDist = mt.rDistance();
        if (rDist.compareTo(BigDecimal.ZERO) == 0) return;

        double profit = long_
                ? ltp.subtract(t.getEntryPrice()).doubleValue()
                : t.getEntryPrice().subtract(ltp).doubleValue();
        double rMultiple = profit / rDist.doubleValue();

        // SCENARIO 3: Breakeven at breakevenR — same as live
        if (!mt.slAtBreakeven() && rMultiple >= breakevenR) {
            moveSlToBreakeven(sym, mt);
            mt = activeTrades.get(sym);
        }

        // SCENARIO 5: Partial exit — same as live
        handlePartialExit(sym, mt, ltp, rMultiple);
    }

    // ══════════════════════════════════════════════════════════════════
    // SCENARIO 4: Trailing SL on 5min candle
    // IDENTICAL to live TradeManagementService.onCandle() / updateTrailingSl()
    // ══════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tradingExecutor")
    public void onCandle(CandleCompleteEvent event) {
        if (!"PAPER".equalsIgnoreCase(tradingMode)) return;
        if (!"5minute".equals(event.getCandle().getTimeframe())) return;

        String sym = event.getCandle().getTradingSymbol();
        ManagedTrade mt = activeTrades.get(sym);
        if (mt == null) return;

        // SCENARIO 8: Skip trailing on momentum candle — same as live
        if (skipTrailOnMomentum && isMomentumCandle(event.getCandle())) {
            log.debug("[PAPER] Momentum candle on {} — skip trailing", sym);
            return;
        }

        updateTrailingSl(sym, mt, event.getCandle().getClose());
    }

    private void updateTrailingSl(String sym, ManagedTrade mt, BigDecimal price) {
        // Must be at breakeven before trailing — same as live
        if (!mt.slAtBreakeven()) return;

        Trade   t     = mt.trade();
        boolean long_ = t.getDirection() == TradeDirection.LONG;

        double profit = long_
                ? price.subtract(t.getEntryPrice()).doubleValue()
                : t.getEntryPrice().subtract(price).doubleValue();
        double rMultiple = mt.rDistance().doubleValue() > 0
                ? profit / mt.rDistance().doubleValue() : 0;

        // Trailing ONLY activates at trailStartR — same as live
        if (rMultiple < trailStartR) {
            log.debug("[PAPER] Trail not active for {}: {}R < {}R",
                    sym, String.format("%.2f", rMultiple), trailStartR);
            return;
        }

        // Log first activation — same as live
        if (!mt.trailActive()) {
            log.info("[PAPER] 🎯 Trailing SL ACTIVATED: {} at {}R! atr={} multiplier={}",
                    sym, String.format("%.2f", rMultiple),
                    String.format("%.2f", mt.atr()), trailAtrMultiplier);
            ManagedTrade updated = new ManagedTrade(
                    mt.trade(), mt.originalSl(), mt.rDistance(), mt.atr(),
                    mt.slAtBreakeven(), true, mt.halfExited(),
                    mt.qty(), mt.remainingQty(),
                    mt.entryWindow(), mt.strongTrend());
            activeTrades.put(sym, updated);
            mt = updated;
        }

        // Phase A/B trail — same logic as live
        double atrMultiplier = rMultiple >= trailTightenR
                ? trailTightAtrMultiplier   // Phase B: 0.5 ATR
                : trailAtrMultiplier;        // Phase A: 1.0 ATR

        double trailDist = mt.atr() * atrMultiplier;

        BigDecimal newSl = long_
                ? price.subtract(BigDecimal.valueOf(trailDist))
                : price.add(BigDecimal.valueOf(trailDist));

        // Only move SL in favorable direction — same as live
        boolean improve = long_
                ? newSl.compareTo(t.getStopLoss()) > 0
                : newSl.compareTo(t.getStopLoss()) < 0;

        if (improve) {
            // Paper: update Trade.stopLoss directly in memory
            // Live: orderClient.modifySlTrigger(t.getSlOrderId(), newSl)
            t.setStopLoss(newSl);
            t.setUpdatedAt(Instant.now());
            log.info("[PAPER] Trail SL updated: {} newSl={} rMultiple={} atrMult={}",
                    sym, String.format("%.2f", newSl.doubleValue()),
                    String.format("%.2f", rMultiple), atrMultiplier);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // SCENARIO 5: Partial exit
    // IDENTICAL logic to live TradeManagementService.handlePartialExit()
    // ══════════════════════════════════════════════════════════════════

    private void handlePartialExit(String sym, ManagedTrade mt,
                                   BigDecimal ltp, double rMultiple) {
        if (mt.halfExited()) return;
        if (mt.remainingQty() <= 1) return;

        double halfExitAt = 0;

        // Lunch window → partial exit at partialExitLunchR — same as live
        if (mt.entryWindow() == MarketTimingService.TimeWindow.LUNCH) {
            halfExitAt = partialExitLunchR;
        }
        // Moderate trend → partial exit at partialExitModerateR — same as live
        else if (!mt.strongTrend()) {
            halfExitAt = partialExitModerateR;
        }
        // Strong trend → no partial exit, hold to target — same as live

        if (halfExitAt > 0 && rMultiple >= halfExitAt) {
            int halfQty = mt.remainingQty() / 2;

            // Paper: simulate partial exit fill at LTP (no API call)
            // Live: orderClient.placeMarketOrder(sym, sellOrBuy, halfQty)
            BigDecimal partialFill = mt.trade().getDirection() == TradeDirection.LONG
                    ? ltp.multiply(BigDecimal.valueOf(1 - 0.0005))
                    : ltp.multiply(BigDecimal.valueOf(1 + 0.0005));
            partialFill = partialFill.setScale(2, RoundingMode.HALF_UP);

            ManagedTrade updated = new ManagedTrade(
                    mt.trade(), mt.originalSl(), mt.rDistance(), mt.atr(),
                    mt.slAtBreakeven(), mt.trailActive(), true,
                    mt.qty(), mt.remainingQty() - halfQty,
                    mt.entryWindow(), mt.strongTrend());
            activeTrades.put(sym, updated);

            log.info("[PAPER] Partial exit: {} qty={} at {} ({}R) window={} strongTrend={}",
                    sym, halfQty, partialFill,
                    String.format("%.2f", halfExitAt),
                    mt.entryWindow(), mt.strongTrend());
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Move SL to breakeven
    // IDENTICAL to live (except no orderClient.modifySlTrigger call)
    // ══════════════════════════════════════════════════════════════════

    private void moveSlToBreakeven(String sym, ManagedTrade mt) {
        Trade t = mt.trade();

        // Paper: directly set SL = entryPrice in memory
        // Live: orderClient.modifySlTrigger(t.getSlOrderId(), t.getEntryPrice())
        t.setStopLoss(t.getEntryPrice());
        t.setUpdatedAt(Instant.now());

        ManagedTrade updated = new ManagedTrade(
                mt.trade(), mt.originalSl(), mt.rDistance(), mt.atr(),
                true, mt.trailActive(), mt.halfExited(),
                mt.qty(), mt.remainingQty(),
                mt.entryWindow(), mt.strongTrend());
        activeTrades.put(sym, updated);

        log.info("[PAPER] ✅ SL moved to BREAKEVEN: {} entry={} at {}R",
                sym, t.getEntryPrice(), breakevenR);
    }

    // ══════════════════════════════════════════════════════════════════
    // SCENARIO 6 + 7: Market/sector alignment on 15min candle
    // IDENTICAL to live TradeManagementService.onCandle15m()
    // ══════════════════════════════════════════════════════════════════

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
            String sym     = entry.getKey();
            ManagedTrade mt = entry.getValue();
            Trade t         = mt.trade();
            boolean forLong = t.getDirection() == TradeDirection.LONG;

            // SCENARIO 6: Market turned — same as live
            if (exitOnMarketTurn) {
                boolean marketTurned = forLong
                        ? dir.direction() == MarketDirectionService.Direction.BEARISH
                        : dir.direction() == MarketDirectionService.Direction.BULLISH;
                if (marketTurned) {
                    BigDecimal ltp = lastPrices.getOrDefault(sym, t.getEntryPrice());
                    BigDecimal eodFill = simulateEodFill(ltp, t.getDirection());
                    log.warn("[PAPER] Market turned against {} — exiting", sym);
                    closeTrade(sym, eodFill, "MARKET_TURNED");
                    continue;
                }
            }

            // SCENARIO 7: Sector turned — same as live
            if (exitOnSectorTurn) {
                if (!sectorStrength.isSectorAligned(sym, forLong)) {
                    BigDecimal ltp = lastPrices.getOrDefault(sym, t.getEntryPrice());
                    BigDecimal eodFill = simulateEodFill(ltp, t.getDirection());
                    log.warn("[PAPER] Sector turned against {} — exiting", sym);
                    closeTrade(sym, eodFill, "SECTOR_TURNED");
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // SCENARIO 9: Force close at 15:00 IST
    // IDENTICAL to live TradeManagementService.forceCloseAll()
    // ══════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 0 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void forceCloseAll() {
        if (!"PAPER".equalsIgnoreCase(tradingMode)) return;
        if (activeTrades.isEmpty()) return;
        log.warn("[PAPER] FORCE CLOSE — {} positions at 15:00", activeTrades.size());
        new ArrayList<>(activeTrades.keySet()).forEach(sym -> {
            BigDecimal ltp = lastPrices.getOrDefault(sym,
                    activeTrades.get(sym).trade().getEntryPrice());
            BigDecimal eodFill = simulateEodFill(ltp,
                    activeTrades.get(sym).trade().getDirection());
            closeTrade(sym, eodFill, "TIME_EXIT_15:00");
        });
    }

    // ══════════════════════════════════════════════════════════════════
    // closeTrade — delegates to PaperTradeExecutionService
    // Live version: calls orderClient.placeMarketOrder + publishes event
    // Paper version: directly calls executor.closeTrade() (no API)
    // ══════════════════════════════════════════════════════════════════

    private void closeTrade(String sym, BigDecimal exitPrice, String reason) {
        ManagedTrade mt = activeTrades.remove(sym);
        if (mt == null) return;
        // Delegate to executor — it handles P&L calc, account update, event publish
        executor.closeTrade(mt.trade(), exitPrice, reason);
    }

    // ══════════════════════════════════════════════════════════════════
    // Dashboard getters — same signatures as live TradeManagementService
    // ══════════════════════════════════════════════════════════════════

    public Collection<ManagedTrade> getActiveTrades() {
        return Collections.unmodifiableCollection(activeTrades.values());
    }

    public Map<String, BigDecimal> getLastPrices() {
        return Collections.unmodifiableMap(lastPrices);
    }

    /** Called by PaperTradeExecutionService for P&L calc on close */
    public int getRemainingQty(String sym, int defaultQty) {
        ManagedTrade mt = activeTrades.get(sym);
        return mt != null ? mt.remainingQty() : defaultQty;
    }

    // ══════════════════════════════════════════════════════════════════
    // Fill simulation helpers
    // ══════════════════════════════════════════════════════════════════

    private BigDecimal simulateSlFill(BigDecimal slPrice, TradeDirection dir) {
        // Worst-case: extra slippage on SL exits (gap candle scenario)
        double filled = dir == TradeDirection.LONG
                ? slPrice.doubleValue() * (1 - SL_SLIP)     // long: fill below SL
                : slPrice.doubleValue() * (1 + SL_SLIP);    // short: fill above SL
        return BigDecimal.valueOf(filled).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal simulateTargetFill(BigDecimal targetPrice, TradeDirection dir) {
        double filled = dir == TradeDirection.LONG
                ? targetPrice.doubleValue() * (1 - TARGET_SLIP)
                : targetPrice.doubleValue() * (1 + TARGET_SLIP);
        return BigDecimal.valueOf(filled).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal simulateEodFill(BigDecimal ltp, TradeDirection dir) {
        // EOD/market-turn exits — thin market, higher slippage
        double filled = dir == TradeDirection.LONG
                ? ltp.doubleValue() * (1 - EOD_SLIP)
                : ltp.doubleValue() * (1 + EOD_SLIP);
        return BigDecimal.valueOf(filled).setScale(2, RoundingMode.HALF_UP);
    }

    /** Same isMomentumCandle() as live TradeManagementService */
    private boolean isMomentumCandle(Candle c) {
        return c.bodyPct().compareTo(new BigDecimal("0.80")) >= 0;
    }
}