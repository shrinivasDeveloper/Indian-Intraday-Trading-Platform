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
 * Full trade management after entry:
 *
 * Phase 1 (0 to 1R):    SL stays at original level
 * Phase 2 (at 1R):      SL moves to entry (breakeven)
 * Phase 3 (1.5R+):      Trailing at 1 ATR
 * Phase 4 (2R+):        Trailing at 0.5 ATR
 *
 * Partial exits:
 *   Strong trend:  full hold to target
 *   Moderate:      half exit at 1.5R, trail rest to 2R
 *   Lunch window:  half exit at 1R, trail rest to 1.5R
 *
 * Continuous monitoring:
 *   Every 15min check if market direction still supports trade
 *   If market turns → exit immediately
 *   If sector turns → exit immediately
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TradeManagementService {

    private final ZerodhaOrderClient       orderClient;
    private final ApplicationEventPublisher publisher;
    private final MarketDirectionService    marketDirection;
    private final SectorStrengthService     sectorStrength;
    private final SectorClassificationService sectorClassify;
    private final SevenGateScannerService   scanner;
    private final MarketTimingService       timing;

    // symbol → active managed trade
    private final Map<String, ManagedTrade> activeTrades = new ConcurrentHashMap<>();

    // symbol → last known price
    private final Map<String, BigDecimal>   lastPrices   = new ConcurrentHashMap<>();

    public record ManagedTrade(
            Trade          trade,
            BigDecimal     originalSl,
            BigDecimal     rDistance,       // 1R distance (entry to original SL)
            double         atr,
            boolean        slAtBreakeven,
            boolean        halfExited,
            int            qty,
            int            remainingQty,
            MarketTimingService.TimeWindow entryWindow,
            boolean        strongTrend
    ) {}

    // ── Register new trade for management ────────────────────────────

    public void register(Trade trade, double atr,
                         MarketTimingService.TimeWindow entryWindow,
                         boolean strongTrend) {
        BigDecimal entry   = trade.getEntryPrice();
        BigDecimal sl      = trade.getStopLoss();
        BigDecimal rDist   = entry.subtract(sl).abs();

        activeTrades.put(trade.getTradingSymbol(), new ManagedTrade(
                trade, sl, rDist, atr, false, false,
                trade.getQuantity(), trade.getQuantity(),
                entryWindow, strongTrend
        ));

        log.info("Trade registered for management: {} dir={} entry={} sl={} 1R={}",
                trade.getTradingSymbol(), trade.getDirection(), entry, sl, rDist);
    }

    // ── Tick-level price tracking ─────────────────────────────────────

    @EventListener
    @Async("tradingExecutor")
    public void onTick(TickReceivedEvent tick) {
        String sym = tick.getTradingSymbol();
        lastPrices.put(sym, tick.getLastTradedPrice());
        manageTrade(sym, tick.getLastTradedPrice());
    }

    // ── 5min candle — update trailing SL ─────────────────────────────

    @EventListener
    @Async("tradingExecutor")
    public void onCandle(CandleCompleteEvent event) {
        if (!"5minute".equals(event.getCandle().getTimeframe())) return;

        String sym = event.getCandle().getTradingSymbol();
        ManagedTrade mt = activeTrades.get(sym);
        if (mt == null) return;

        // Skip trailing on momentum candle (body > 2x avg)
        if (isMomentumCandle(event.getCandle())) {
            log.debug("Momentum candle on {} — skip trailing", sym);
            return;
        }

        updateTrailingSl(sym, mt, event.getCandle().getClose());
    }

    // ── 15min candle — check market/sector alignment ──────────────────

    @EventListener
    @Async("tradingExecutor")
    public void onCandle15m(CandleCompleteEvent event) {
        if (!"15minute".equals(event.getCandle().getTimeframe())) return;
        checkAllTradesAlignment();
    }

    // ── Core trade management logic ───────────────────────────────────

    private void manageTrade(String sym, BigDecimal ltp) {
        ManagedTrade mt = activeTrades.get(sym);
        if (mt == null) return;

        Trade  t     = mt.trade();
        boolean long_ = t.getDirection() == TradeDirection.LONG;

        // SL hit
        if (long_ ? ltp.compareTo(t.getStopLoss()) <= 0
                : ltp.compareTo(t.getStopLoss()) >= 0) {
            closeTrade(sym, t.getStopLoss(), "STOPLOSS_HIT");
            scanner.startCooldown(sym);
            return;
        }

        // Target hit
        if (long_ ? ltp.compareTo(t.getTarget()) >= 0
                : ltp.compareTo(t.getTarget()) <= 0) {
            closeTrade(sym, t.getTarget(), "TARGET_HIT");
            return;
        }

        BigDecimal rDist = mt.rDistance();
        if (rDist.compareTo(BigDecimal.ZERO) == 0) return;

        double profit = long_
                ? ltp.subtract(t.getEntryPrice()).doubleValue()
                : t.getEntryPrice().subtract(ltp).doubleValue();
        double rMultiple = profit / rDist.doubleValue();

        // Phase 2: move SL to breakeven at 1R
        if (!mt.slAtBreakeven() && rMultiple >= 1.0) {
            moveSlToBreakeven(sym, mt);
        }

        // Partial exit logic
        handlePartialExit(sym, mt, ltp, rMultiple);
    }

    private void updateTrailingSl(String sym, ManagedTrade mt, BigDecimal price) {
        if (!mt.slAtBreakeven()) return; // don't trail until breakeven

        Trade  t     = mt.trade();
        boolean long_ = t.getDirection() == TradeDirection.LONG;

        double profit = long_
                ? price.subtract(t.getEntryPrice()).doubleValue()
                : t.getEntryPrice().subtract(price).doubleValue();
        double rMultiple = mt.rDistance().doubleValue() > 0
                ? profit / mt.rDistance().doubleValue() : 0;

        if (rMultiple < 1.5) return; // trailing only starts at 1.5R

        // Trail distance: 1 ATR until 2R, then 0.5 ATR
        double trailDist = rMultiple >= 2.0 ? mt.atr() * 0.5 : mt.atr();
        BigDecimal newSl = long_
                ? price.subtract(BigDecimal.valueOf(trailDist))
                : price.add(BigDecimal.valueOf(trailDist));

        // Only move SL in favorable direction
        boolean improve = long_
                ? newSl.compareTo(t.getStopLoss()) > 0
                : newSl.compareTo(t.getStopLoss()) < 0;

        if (improve) {
            try {
                orderClient.modifySlTrigger(t.getSlOrderId(), newSl.doubleValue());
                t.setStopLoss(newSl);
                t.setUpdatedAt(Instant.now());
                log.info("Trail SL updated: {} newSl={} rMultiple={}",
                        sym, newSl, String.format("%.2f", rMultiple));
            } catch (Exception e) {
                log.warn("Trail SL update failed {}: {}", sym, e.getMessage());
            }
        }
    }

    private void handlePartialExit(String sym, ManagedTrade mt,
                                   BigDecimal ltp, double rMultiple) {
        if (mt.halfExited()) return;

        boolean shouldHalfExit = false;
        double  halfExitAt     = 0;

        if (mt.entryWindow() == MarketTimingService.TimeWindow.LUNCH) {
            halfExitAt = 1.0; // half exit at 1R in lunch window
        } else if (!mt.strongTrend()) {
            halfExitAt = 1.5; // moderate trend: half exit at 1.5R
        }

        if (halfExitAt > 0 && rMultiple >= halfExitAt && mt.remainingQty() > 1) {
            int halfQty = mt.remainingQty() / 2;
            try {
                // Partial close at market
                orderClient.placeMarketOrder(
                        mt.trade().getTradingSymbol(),
                        mt.trade().getDirection() == TradeDirection.LONG ? "SELL" : "BUY",
                        halfQty);

                // Update managed trade
                ManagedTrade updated = new ManagedTrade(
                        mt.trade(), mt.originalSl(), mt.rDistance(), mt.atr(),
                        mt.slAtBreakeven(), true, mt.qty(),
                        mt.remainingQty() - halfQty,
                        mt.entryWindow(), mt.strongTrend()
                );
                activeTrades.put(sym, updated);

                log.info("Partial exit: {} qty={} at {} ({}R)",
                        sym, halfQty, ltp, String.format("%.2f", halfExitAt));
            } catch (Exception e) {
                log.error("Partial exit failed {}: {}", sym, e.getMessage());
            }
        }
    }

    private void moveSlToBreakeven(String sym, ManagedTrade mt) {
        Trade t = mt.trade();
        try {
            orderClient.modifySlTrigger(t.getSlOrderId(), t.getEntryPrice().doubleValue());
            t.setStopLoss(t.getEntryPrice());
            t.setUpdatedAt(Instant.now());

            ManagedTrade updated = new ManagedTrade(
                    mt.trade(), mt.originalSl(), mt.rDistance(), mt.atr(),
                    true, mt.halfExited(), mt.qty(), mt.remainingQty(),
                    mt.entryWindow(), mt.strongTrend()
            );
            activeTrades.put(sym, updated);

            log.info("SL moved to breakeven: {} entry={}", sym, t.getEntryPrice());
        } catch (Exception e) {
            log.warn("Breakeven SL move failed {}: {}", sym, e.getMessage());
        }
    }

    // ── Continuous market/sector alignment check ──────────────────────

    private void checkAllTradesAlignment() {
        MarketDirectionService.MarketDirectionResult dir =
                marketDirection.getCurrentDirection();

        for (Map.Entry<String, ManagedTrade> entry : activeTrades.entrySet()) {
            String sym = entry.getKey();
            ManagedTrade mt = entry.getValue();
            Trade t = mt.trade();
            boolean forLong = t.getDirection() == TradeDirection.LONG;

            // Market turned against trade
            boolean marketTurned = forLong
                    ? dir.direction() == MarketDirectionService.Direction.BEARISH
                    : dir.direction() == MarketDirectionService.Direction.BULLISH;

            if (marketTurned) {
                BigDecimal ltp = lastPrices.getOrDefault(sym, t.getEntryPrice());
                log.warn("Market turned against {} — exiting at market", sym);
                closeTrade(sym, ltp, "MARKET_TURNED");
                continue;
            }

            // Sector turned against trade
            if (!sectorStrength.isSectorAligned(sym, forLong)) {
                BigDecimal ltp = lastPrices.getOrDefault(sym, t.getEntryPrice());
                log.warn("Sector turned against {} — exiting at market", sym);
                closeTrade(sym, ltp, "SECTOR_TURNED");
            }
        }
    }

    // ── Force close at 15:00 ─────────────────────────────────────────

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

    // ── Close trade ───────────────────────────────────────────────────

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

        log.info("Trade CLOSED: {} reason={} pnl={}", sym, reason, pnl);

        publisher.publishEvent(new TradeExecutionResultEvent(
                this, sym, "CLOSED",
                t.getEntryOrderId(), t.getSlOrderId(),
                t.getEntryPrice(), exitPrice, pnl, reason));
    }

    // ── Dashboard getters ─────────────────────────────────────────────

    public Collection<ManagedTrade> getActiveTrades() {
        return Collections.unmodifiableCollection(activeTrades.values());
    }

    public Map<String, BigDecimal> getLastPrices() {
        return Collections.unmodifiableMap(lastPrices);
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private boolean isMomentumCandle(Candle c) {
        // Body > 2x typical body — don't trail on momentum candles
        return c.bodyPct().compareTo(new BigDecimal("0.80")) >= 0;
    }
}