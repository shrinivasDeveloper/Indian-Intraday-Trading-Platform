package com.trading.execution.service;

import com.trading.domain.Candle;
import com.trading.domain.entity.Trade;
import com.trading.domain.enums.TradeDirection;
import com.trading.events.CandleCompleteEvent;
import com.trading.events.TickReceivedEvent;
import com.trading.events.TradeExecutionResultEvent;
import com.trading.execution.client.ZerodhaOrderClient;
import com.trading.marketdata.service.MarketTimingService;
import com.trading.regime.service.MarketDirectionService;
import com.trading.scanner.service.SevenGateScannerService;
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
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Full trade management after entry — fully dynamic via application.yml.
 *
 * ALL scenarios handled:
 *   1. SL hit → close trade + cooldown
 *   2. Target hit → close trade
 *   3. Breakeven at breakevenR (default 1R)
 *   4. Trailing SL at trailStartR (default 3R)
 *      Phase A: trail at trailAtrMultiplier × ATR (default 1.0)
 *      Phase B: tighten at trailTightenR (default 4R) → 0.5 ATR
 *   5. Partial exit:
 *      Lunch window → half exit at partialExitLunchR (default 1R)
 *      Moderate trend → half exit at partialExitModerateR (default 1.5R)
 *      Strong trend → hold full to target
 *   6. Market turns → exit immediately
 *   7. Sector turns → exit immediately
 *   8. Momentum candle → skip trailing
 *   9. Force close at 15:00 IST
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TradeManagementService {

    private final ZerodhaOrderClient          orderClient;
    private final ApplicationEventPublisher   publisher;
    private final MarketDirectionService      marketDirection;
    private final SectorStrengthService       sectorStrength;
    private final SectorClassificationService sectorClassify;
    private final SevenGateScannerService     scanner;
    private final MarketTimingService         timing;

    // ══════════════════════════════════════════════════════════════════════
    // ALL configurable via application.yml
    // ══════════════════════════════════════════════════════════════════════

    /** R at which SL moves to breakeven. Default: 1.0 */
    @Value("${trading.breakeven-r:1.0}")
    private double breakevenR;

    /** R at which trailing SL activates. Default: 3.0 */
    @Value("${trading.trail-start-r:3.0}")
    private double trailStartR;

    /** ATR multiplier for trail distance. Default: 1.0 */
    @Value("${trading.trail-atr-multiplier:1.0}")
    private double trailAtrMultiplier;

    /** R at which trail tightens. Default: 4.0 */
    @Value("${trading.trail-tighten-r:4.0}")
    private double trailTightenR;

    /** ATR multiplier when trail tightens. Default: 0.5 */
    @Value("${trading.trail-tight-atr-multiplier:0.5}")
    private double trailTightAtrMultiplier;

    /** R for half exit in LUNCH window. Default: 1.0 */
    @Value("${trading.partial-exit-lunch-r:1.0}")
    private double partialExitLunchR;

    /** R for half exit in moderate trend. Default: 1.5 */
    @Value("${trading.partial-exit-moderate-r:1.5}")
    private double partialExitModerateR;

    /** Skip trailing on momentum candles. Default: true */
    @Value("${trading.skip-trail-on-momentum:true}")
    private boolean skipTrailOnMomentum;

    /** Exit if market direction changes. Default: true */
    @Value("${trading.exit-on-market-turn:true}")
    private boolean exitOnMarketTurn;

    /** Exit if sector turns against trade. Default: true */
    @Value("${trading.exit-on-sector-turn:true}")
    private boolean exitOnSectorTurn;

    // ══════════════════════════════════════════════════════════════════════
    // State
    // ══════════════════════════════════════════════════════════════════════

    private final Map<String, ManagedTrade> activeTrades = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal>   lastPrices   = new ConcurrentHashMap<>();

    // ══════════════════════════════════════════════════════════════════════
    // ManagedTrade — ALL original fields preserved + trailActive added
    // ══════════════════════════════════════════════════════════════════════

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

    // ══════════════════════════════════════════════════════════════════════
    // Register — called by TradeExecutionService after entry
    // ══════════════════════════════════════════════════════════════════════

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

        log.info("Trade registered: {} dir={} entry={} sl={} 1R={} " +
                        "breakevenAt={}R trailAt={}R atr={} window={} strongTrend={}",
                trade.getTradingSymbol(), trade.getDirection(),
                entry, sl, rDist, breakevenR, trailStartR,
                String.format("%.2f", atr), entryWindow, strongTrend);
    }

    // ══════════════════════════════════════════════════════════════════════
    // SCENARIO 1,2,3,5: Tick-level monitoring
    // ══════════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tradingExecutor")
    public void onTick(TickReceivedEvent tick) {
        String sym = tick.getTradingSymbol();
        lastPrices.put(sym, tick.getLastTradedPrice());
        manageTrade(sym, tick.getLastTradedPrice());
    }

    private void manageTrade(String sym, BigDecimal ltp) {
        ManagedTrade mt = activeTrades.get(sym);
        if (mt == null) return;

        Trade   t     = mt.trade();
        boolean long_ = t.getDirection() == TradeDirection.LONG;

        // SCENARIO 1: SL hit
        if (long_ ? ltp.compareTo(t.getStopLoss()) <= 0
                : ltp.compareTo(t.getStopLoss()) >= 0) {
            closeTrade(sym, t.getStopLoss(), "STOPLOSS_HIT");
            scanner.startCooldown(sym);
            return;
        }

        // SCENARIO 2: Target hit
        if (long_ ? ltp.compareTo(t.getTarget()) >= 0
                : ltp.compareTo(t.getTarget()) <= 0) {
            closeTrade(sym, t.getTarget(), "TARGET_HIT");
            return;
        }

        // Calculate R-multiple
        BigDecimal rDist = mt.rDistance();
        if (rDist.compareTo(BigDecimal.ZERO) == 0) return;

        double profit = long_
                ? ltp.subtract(t.getEntryPrice()).doubleValue()
                : t.getEntryPrice().subtract(ltp).doubleValue();
        double rMultiple = profit / rDist.doubleValue();

        // SCENARIO 3: Breakeven at breakevenR (default 1R)
        if (!mt.slAtBreakeven() && rMultiple >= breakevenR) {
            moveSlToBreakeven(sym, mt);
            mt = activeTrades.get(sym);
        }

        // SCENARIO 5: Partial exit
        handlePartialExit(sym, mt, ltp, rMultiple);
    }

    // ══════════════════════════════════════════════════════════════════════
    // SCENARIO 4: Trailing SL on 5min candle
    // Activates ONLY at trailStartR (default 3R)
    // ══════════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tradingExecutor")
    public void onCandle(CandleCompleteEvent event) {
        if (!"5minute".equals(event.getCandle().getTimeframe())) return;

        String sym = event.getCandle().getTradingSymbol();
        ManagedTrade mt = activeTrades.get(sym);
        if (mt == null) return;

        // SCENARIO 8: Skip trailing on momentum candle
        if (skipTrailOnMomentum && isMomentumCandle(event.getCandle())) {
            log.debug("Momentum candle on {} — skip trailing", sym);
            return;
        }

        updateTrailingSl(sym, mt, event.getCandle().getClose());
    }

    private void updateTrailingSl(String sym, ManagedTrade mt, BigDecimal price) {
        // Must be at breakeven before trailing
        if (!mt.slAtBreakeven()) return;

        Trade   t     = mt.trade();
        boolean long_ = t.getDirection() == TradeDirection.LONG;

        double profit = long_
                ? price.subtract(t.getEntryPrice()).doubleValue()
                : t.getEntryPrice().subtract(price).doubleValue();
        double rMultiple = mt.rDistance().doubleValue() > 0
                ? profit / mt.rDistance().doubleValue() : 0;

        // Trailing ONLY activates at trailStartR (default 3R)
        if (rMultiple < trailStartR) {
            log.debug("Trail not active for {}: {}R < {}R",
                    sym, String.format("%.2f", rMultiple), trailStartR);
            return;
        }

        // Log first activation
        if (!mt.trailActive()) {
            log.info("🎯 Trailing SL ACTIVATED: {} at {}R! atr={} multiplier={}",
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

        // Phase A: trailStartR to trailTightenR → normal ATR trail
        // Phase B: beyond trailTightenR → tight ATR trail
        double atrMultiplier = rMultiple >= trailTightenR
                ? trailTightAtrMultiplier   // default 0.5 ATR
                : trailAtrMultiplier;        // default 1.0 ATR

        double trailDist = mt.atr() * atrMultiplier;

        BigDecimal newSl = long_
                ? price.subtract(BigDecimal.valueOf(trailDist))
                : price.add(BigDecimal.valueOf(trailDist));

        // Only move SL in favorable direction
        boolean improve = long_
                ? newSl.compareTo(t.getStopLoss()) > 0
                : newSl.compareTo(t.getStopLoss()) < 0;

        if (improve && t.getSlOrderId() != null) {
            try {
                orderClient.modifySlTrigger(t.getSlOrderId(), newSl.doubleValue());
                t.setStopLoss(newSl);
                t.setUpdatedAt(Instant.now());
                log.info("Trail SL updated: {} newSl={} rMultiple={} atrMult={}",
                        sym, newSl,
                        String.format("%.2f", rMultiple), atrMultiplier);
            } catch (Exception e) {
                log.warn("Trail SL update failed {}: {}", sym, e.getMessage());
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // SCENARIO 5: Partial exit
    // ══════════════════════════════════════════════════════════════════════

    private void handlePartialExit(String sym, ManagedTrade mt,
                                   BigDecimal ltp, double rMultiple) {
        if (mt.halfExited()) return;
        if (mt.remainingQty() <= 1) return;

        double halfExitAt = 0;

        // Lunch window → half exit at partialExitLunchR (default 1R)
        if (mt.entryWindow() == MarketTimingService.TimeWindow.LUNCH) {
            halfExitAt = partialExitLunchR;
        }
        // Moderate trend → half exit at partialExitModerateR (default 1.5R)
        else if (!mt.strongTrend()) {
            halfExitAt = partialExitModerateR;
        }
        // Strong trend → no partial exit, hold to target

        if (halfExitAt > 0 && rMultiple >= halfExitAt) {
            int halfQty = mt.remainingQty() / 2;
            try {
                orderClient.placeMarketOrder(
                        mt.trade().getTradingSymbol(),
                        mt.trade().getDirection() == TradeDirection.LONG
                                ? "SELL" : "BUY",
                        halfQty);

                ManagedTrade updated = new ManagedTrade(
                        mt.trade(), mt.originalSl(), mt.rDistance(), mt.atr(),
                        mt.slAtBreakeven(), mt.trailActive(), true,
                        mt.qty(), mt.remainingQty() - halfQty,
                        mt.entryWindow(), mt.strongTrend());
                activeTrades.put(sym, updated);

                log.info("Partial exit: {} qty={} at {} ({}R) window={} strongTrend={}",
                        sym, halfQty, ltp,
                        String.format("%.2f", halfExitAt),
                        mt.entryWindow(), mt.strongTrend());
            } catch (Exception e) {
                log.error("Partial exit failed {}: {}", sym, e.getMessage());
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Move SL to breakeven
    // ══════════════════════════════════════════════════════════════════════

    private void moveSlToBreakeven(String sym, ManagedTrade mt) {
        Trade t = mt.trade();
        if (t.getSlOrderId() == null) return;
        try {
            orderClient.modifySlTrigger(t.getSlOrderId(), t.getEntryPrice().doubleValue());
            t.setStopLoss(t.getEntryPrice());
            t.setUpdatedAt(Instant.now());

            ManagedTrade updated = new ManagedTrade(
                    mt.trade(), mt.originalSl(), mt.rDistance(), mt.atr(),
                    true, mt.trailActive(), mt.halfExited(),
                    mt.qty(), mt.remainingQty(),
                    mt.entryWindow(), mt.strongTrend());
            activeTrades.put(sym, updated);

            log.info("✅ SL moved to BREAKEVEN: {} entry={} at {}R",
                    sym, t.getEntryPrice(), breakevenR);
        } catch (Exception e) {
            log.warn("Breakeven SL move failed {}: {}", sym, e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // SCENARIO 6 + 7: Market/sector alignment (every 15min candle)
    // ══════════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tradingExecutor")
    public void onCandle15m(CandleCompleteEvent event) {
        if (!"15minute".equals(event.getCandle().getTimeframe())) return;
        checkAllTradesAlignment();
    }

    private void checkAllTradesAlignment() {
        if (!exitOnMarketTurn && !exitOnSectorTurn) return;

        MarketDirectionService.MarketDirectionResult dir =
                marketDirection.getCurrentDirection();

        for (Map.Entry<String, ManagedTrade> entry : activeTrades.entrySet()) {
            String sym = entry.getKey();
            ManagedTrade mt = entry.getValue();
            Trade t = mt.trade();
            boolean forLong = t.getDirection() == TradeDirection.LONG;

            // SCENARIO 6: Market turned
            if (exitOnMarketTurn) {
                boolean marketTurned = forLong
                        ? dir.direction() == MarketDirectionService.Direction.BEARISH
                        : dir.direction() == MarketDirectionService.Direction.BULLISH;
                if (marketTurned) {
                    BigDecimal ltp = lastPrices.getOrDefault(sym, t.getEntryPrice());
                    log.warn("Market turned against {} — exiting", sym);
                    closeTrade(sym, ltp, "MARKET_TURNED");
                    continue;
                }
            }

            // SCENARIO 7: Sector turned
            if (exitOnSectorTurn) {
                if (!sectorStrength.isSectorAligned(sym, forLong)) {
                    BigDecimal ltp = lastPrices.getOrDefault(sym, t.getEntryPrice());
                    log.warn("Sector turned against {} — exiting", sym);
                    closeTrade(sym, ltp, "SECTOR_TURNED");
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // SCENARIO 9: Force close at 15:00 IST
    // ══════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 0 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void forceCloseAll() {
        if (activeTrades.isEmpty()) return;
        log.warn("FORCE CLOSE — {} positions at 15:00", activeTrades.size());
        new ArrayList<>(activeTrades.keySet()).forEach(sym -> {
            BigDecimal ltp = lastPrices.getOrDefault(sym,
                    activeTrades.get(sym).trade().getEntryPrice());
            closeTrade(sym, ltp, "TIME_EXIT_15:00");
        });
    }

    // ══════════════════════════════════════════════════════════════════════
    // Close trade
    // ══════════════════════════════════════════════════════════════════════

    private void closeTrade(String sym, BigDecimal exitPrice, String reason) {
        ManagedTrade mt = activeTrades.remove(sym);
        if (mt == null) return;

        Trade t = mt.trade();
        BigDecimal pnl = t.getDirection() == TradeDirection.LONG
                ? exitPrice.subtract(t.getEntryPrice())
                .multiply(BigDecimal.valueOf(mt.remainingQty()))
                : t.getEntryPrice().subtract(exitPrice)
                .multiply(BigDecimal.valueOf(mt.remainingQty()));

        t.setStatus("CLOSED");
        t.setExitTime(Instant.now());
        t.setExitPrice(exitPrice);
        t.setExitReason(reason);
        t.setNetPnl(pnl);
        t.setUpdatedAt(Instant.now());

        log.info("Trade CLOSED: {} reason={} pnl={} " +
                        "slAtBreakeven={} trailActive={} halfExited={}",
                sym, reason, pnl,
                mt.slAtBreakeven(), mt.trailActive(), mt.halfExited());

        publisher.publishEvent(new TradeExecutionResultEvent(
                this, sym, "CLOSED",
                t.getEntryOrderId(), t.getSlOrderId(),
                t.getEntryPrice(), exitPrice, pnl, reason));
    }

    // ══════════════════════════════════════════════════════════════════════
    // Dashboard getters
    // ══════════════════════════════════════════════════════════════════════

    public Collection<ManagedTrade> getActiveTrades() {
        return Collections.unmodifiableCollection(activeTrades.values());
    }

    public Map<String, BigDecimal> getLastPrices() {
        return Collections.unmodifiableMap(lastPrices);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════

    private boolean isMomentumCandle(Candle c) {
        return c.bodyPct().compareTo(new BigDecimal("0.80")) >= 0;
    }
}