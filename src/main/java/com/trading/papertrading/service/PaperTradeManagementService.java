package com.trading.papertrading.service;

import com.trading.config.StrategyConfig;
import com.trading.domain.Candle;
import com.trading.domain.entity.Trade;
import com.trading.domain.enums.TradeDirection;
import com.trading.events.CandleCompleteEvent;
import com.trading.events.TickReceivedEvent;
import com.trading.marketdata.service.MarketTimingService;
import com.trading.papertrading.model.PaperAccount;
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
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Paper trading management — exact mirror of TradeManagementService.
 *
 * ALL 9 scenarios handled identically to live TradeManagementService:
 *   1. SL hit          → closeTrade + cooldown
 *   2. Target hit      → closeTrade
 *   3. Breakeven at 1R → update Trade.stopLoss in memory (no API call)
 *   4. Trailing SL     → update Trade.stopLoss in memory (no API call)
 *   5. Partial exit    → calculate partial P&L, credit PaperAccount, update remainingQty
 *   6. Market turns    → exit at LTP
 *   7. Sector turns    → exit at LTP
 *   8. Momentum candle → skip trailing
 *   9. Force close     → 15:00 IST
 *
 * ═══════════════════════════════════════════════════════════════════════
 * FIX 1 — TIME STOP
 *   TradeSignal specifies timeStopMinutes. manageTrade() checks elapsed
 *   time on every tick and calls closeTrade("TIME_STOP") if the trade
 *   has not resolved within the configured window.
 *
 * FIX 2 — PARTIAL EXIT P&L CREDITED TO PaperAccount
 *   handlePartialExit() calls account.applyPartialPnl() immediately.
 *   Credits capital WITHOUT incrementing trade counters.
 *
 * FIX 3 — PaperAccount injected (needed for FIX 2)
 *
 * ═══════════════════════════════════════════════════════════════════════
 * NSE 5-PAISE TICK ALIGNMENT  (NEW)
 *
 *   The previous setScale(2, HALF_UP) left fill prices at arbitrary
 *   1-paise values (e.g. ₹100.03) that NSE order books never quote,
 *   introducing systematic P&L leakage in simulation.
 *
 *   alignToTick(price, RoundingMode) enforces the 0.05 grid:
 *     LONG  SL  fill → FLOOR   (conservative — captures more loss)
 *     SHORT SL  fill → CEILING (conservative — captures more loss)
 *     LONG  target / partial / EOD fill → FLOOR   (less profit)
 *     SHORT target / partial / EOD fill → CEILING (less profit)
 *
 *   Uses pure BigDecimal arithmetic: multiply by 20 (exact), apply
 *   floor/ceiling, divide by 20. No double intermediates.
 *
 * GAP-DOWN / CIRCUIT-LIMIT SL  (NEW)
 *
 *   Old simulateSlFill only applied ±0.1% slippage against slPrice.
 *   Problem: if LTP gaps 2%+ past SL (circuit limit, news, illiquidity)
 *   the old code still filled at slPrice − 0.1%, understating the loss
 *   by ~1.9%.
 *
 *   New simulateSlFill(slPrice, ltp, dir) takes the actual market price:
 *     LONG  fill = min(slPrice × (1 − SL_SLIP), ltp)
 *     SHORT fill = max(slPrice × (1 + SL_SLIP), ltp)
 *   The "worse of" logic means gap events fill at real market depth.
 *   alignToTick is applied to the result.
 *
 * REAL NSE BROKERAGE MODEL  (NEW)
 *
 *   Old: flat ₹20 per partial exit regardless of position size.
 *   Problem: understates costs for large lots, overstates for small ones.
 *
 *   New: NseBrokerageCalculator.exitLegCost(fill, qty, dir) computes the
 *   exact exit-leg charges for NSE Equity Intraday (Zerodha):
 *
 *     Component            Rate                     Side
 *     ─────────────────────────────────────────────────────────
 *     Brokerage            min(0.03% turnover, ₹20) exit leg
 *     STT                  0.025% turnover          sell side only
 *     NSE Exchange Txn     0.00335% turnover        exit leg
 *     SEBI Charges         ₹10 per crore            exit leg
 *     GST                  18% on (brok+exch+SEBI)  exit leg
 *     Stamp Duty           0% on sell exit          (buy-side only)
 *     ─────────────────────────────────────────────────────────
 *   Entry-leg costs already charged by PaperOrderService.logFill().
 *
 * ═══════════════════════════════════════════════════════════════════════
 * REFACTOR — StrategyConfig as single source of truth (partial, safe):
 *   breakevenR           → cfg.getGlobal().getBreakevenRTrigger()   [1.5]
 *   trailStartR          → cfg.getAutoMode().getTrendTrailTriggerR() [2.0]
 *   partialExitModerateR → cfg.getGlobal().getPartialExitR()        [3.0]
 *   global time-stop fallback when timeStopMinutes == 0
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
    private final PaperAccount               account;
    private final StrategyConfig             cfg;

    public PaperTradeManagementService(
            PaperTradeExecutionService executor,
            MarketDirectionService marketDirection,
            SectorStrengthService sectorStrength,
            SectorClassificationService sectorClassify,
            SevenGateScannerService scanner,
            MarketTimingService timing,
            PaperAccount account,
            StrategyConfig cfg) {
        this.executor        = executor;
        this.marketDirection = marketDirection;
        this.sectorStrength  = sectorStrength;
        this.sectorClassify  = sectorClassify;
        this.scanner         = scanner;
        this.timing          = timing;
        this.account         = account;
        this.cfg             = cfg;
    }

    // ── @Value fields: only those with NO StrategyConfig equivalent ────────────
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

    // exitOnMarketTurn / exitOnSectorTurn: NOT mapped to cfg.isEnabled().
    // cfg.isEnabled() = strategy.enabled (signal kill-switch — unrelated concern).
    @Value("${trading.exit-on-market-turn:true}")
    private boolean exitOnMarketTurn;

    @Value("${trading.exit-on-sector-turn:true}")
    private boolean exitOnSectorTurn;

    // ── Slippage constants ─────────────────────────────────────────────────────
    //   SL_SLIP:     0.10% base slippage on SL orders (normal conditions).
    //                For gaps/circuits, actual LTP overrides — see simulateSlFill.
    //   TARGET_SLIP: 0.05% adverse slippage on target/partial exits.
    //   EOD_SLIP:    0.15% on EOD / market-turn / time-stop exits.
    static final double SL_SLIP     = 0.001;
    static final double TARGET_SLIP = 0.0005;
    static final double EOD_SLIP    = 0.0015;

    // ── NSE 5-paise tick constant ──────────────────────────────────────────────
    // Constructed from String, never from double 0.05 (IEEE 754 has no exact repr).
    static final BigDecimal TICK = new BigDecimal("0.05");

    // ── State ──────────────────────────────────────────────────────────────────
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
    // NSE TICK ALIGNMENT HELPER
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Aligns {@code price} to the nearest NSE 5-paise (₹0.05) tick boundary.
     *
     * <h3>Algorithm</h3>
     * Multiplying by 20 (exact reciprocal of 0.05) converts the price to a
     * "tick count" with no rounding error. Applying FLOOR or CEILING to that
     * integer then dividing by 20 gives the aligned grid price.
     * MathContext.DECIMAL64 prevents scale explosion on the intermediate.
     *
     * <h3>Conservative rounding convention</h3>
     * <ul>
     *   <li><b>LONG SL fill → FLOOR</b>: we exit lower → more loss captured
     *       in the simulation → conservative (never flatters paper P&L).</li>
     *   <li><b>SHORT SL fill → CEILING</b>: we exit higher → more loss.</li>
     *   <li><b>LONG target / partial / EOD → FLOOR</b>: less profit.</li>
     *   <li><b>SHORT target / partial / EOD → CEILING</b>: less profit.</li>
     * </ul>
     *
     * @param price raw computed fill price (may be off the 0.05 grid)
     * @param mode  {@link RoundingMode#FLOOR} or {@link RoundingMode#CEILING}
     * @return price snapped to the nearest valid NSE tick, scale=2
     */
    static BigDecimal alignToTick(BigDecimal price, RoundingMode mode) {
        BigDecimal ticks = price.multiply(BigDecimal.valueOf(20), MathContext.DECIMAL64)
                .setScale(0, mode);
        return ticks.divide(BigDecimal.valueOf(20), 2, RoundingMode.UNNECESSARY);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Register
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

        log.info("[PAPER] Trade registered: {} dir={} entry={} sl={} 1R={} " +
                        "beAt={}R trailAt={}R atr={} window={} strongTrend={} timeStop={}",
                trade.getTradingSymbol(), trade.getDirection(),
                entry, sl, rDist,
                cfg.getGlobal().getBreakevenRTrigger(),
                cfg.getAutoMode().getTrendTrailTriggerR(),
                String.format("%.2f", atr), entryWindow, strongTrend,
                timeStopMinutes > 0
                        ? timeStopMinutes + "min"
                        : "none(global=" + cfg.getGlobal().getGlobalTimeStop().toMinutes() + "m)");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SCENARIO 1, 2, 3, 5 + TIME STOP: Tick-level monitoring
    // ══════════════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tradingExecutor")
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

        // ── SCENARIO 1: SL hit ─────────────────────────────────────────────
        // ltp is passed to model gap-down / circuit fills correctly.
        if (long_ ? ltp.compareTo(t.getStopLoss()) <= 0
                : ltp.compareTo(t.getStopLoss()) >= 0) {
            BigDecimal slFill = simulateSlFill(t.getStopLoss(), ltp, t.getDirection());
            log.info("[PAPER] SL HIT: {} sl={} ltp={} fill={}",
                    sym, t.getStopLoss(), ltp, slFill);
            closeTrade(sym, slFill, "STOPLOSS_HIT");
            scanner.startCooldown(sym);
            return;
        }

        // ── SCENARIO 2: Target hit ─────────────────────────────────────────
        if (long_ ? ltp.compareTo(t.getTarget()) >= 0
                : ltp.compareTo(t.getTarget()) <= 0) {
            BigDecimal targetFill = simulateTargetFill(t.getTarget(), t.getDirection());
            closeTrade(sym, targetFill, "TARGET_HIT");
            return;
        }

        // ── TIME STOP ─────────────────────────────────────────────────────
        {
            long effectiveStopMinutes = mt.timeStopMinutes() > 0
                    ? mt.timeStopMinutes()
                    : cfg.getGlobal().getGlobalTimeStop().toMinutes();

            long elapsedMinutes = (Instant.now().getEpochSecond()
                    - mt.entryInstant().getEpochSecond()) / 60;

            if (elapsedMinutes >= effectiveStopMinutes) {
                BigDecimal eodFill = simulateEodFill(ltp, t.getDirection());
                String label = mt.timeStopMinutes() > 0 ? "STRATEGY" : "GLOBAL";
                log.warn("[PAPER] TIME STOP ({}): {} — {}min elapsed (limit={}min) fill={}",
                        label, sym, elapsedMinutes, effectiveStopMinutes, eodFill);
                closeTrade(sym, eodFill, "TIME_STOP_" + effectiveStopMinutes + "MIN");
                return;
            }
        }

        // ── R-multiple ─────────────────────────────────────────────────────
        BigDecimal rDist = mt.rDistance();
        if (rDist.compareTo(BigDecimal.ZERO) == 0) return;

        double profit = long_
                ? ltp.subtract(t.getEntryPrice()).doubleValue()
                : t.getEntryPrice().subtract(ltp).doubleValue();
        double rMultiple = profit / rDist.doubleValue();

        // ── SCENARIO 3: Breakeven ──────────────────────────────────────────
        if (!mt.slAtBreakeven() && rMultiple >= cfg.getGlobal().getBreakevenRTrigger()) {
            moveSlToBreakeven(sym, mt);
            mt = activeTrades.get(sym);
        }

        // ── SCENARIO 5: Partial exit ───────────────────────────────────────
        handlePartialExit(sym, mt, ltp, rMultiple);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SCENARIO 4: Trailing SL on 5-minute candle close
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
            log.debug("[PAPER] Momentum candle on {} — skip trailing", sym);
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

        double trailStartR = cfg.getAutoMode().getTrendTrailTriggerR();
        if (rMultiple < trailStartR) {
            log.debug("[PAPER] Trail not active for {}: {}R < {}R",
                    sym, String.format("%.2f", rMultiple), trailStartR);
            return;
        }

        if (!mt.trailActive()) {
            log.info("[PAPER] Trailing SL ACTIVATED: {} at {}R atr={} multiplier={}",
                    sym, String.format("%.2f", rMultiple),
                    String.format("%.2f", mt.atr()), trailAtrMultiplier);
            ManagedTrade updated = new ManagedTrade(
                    mt.trade(), mt.originalSl(), mt.rDistance(), mt.atr(),
                    mt.slAtBreakeven(), true, mt.halfExited(),
                    mt.qty(), mt.remainingQty(),
                    mt.entryWindow(), mt.strongTrend(),
                    mt.timeStopMinutes(), mt.entryInstant());
            activeTrades.put(sym, updated);
            mt = updated;
        }

        double atrMultiplier = rMultiple >= trailTightenR
                ? trailTightAtrMultiplier
                : trailAtrMultiplier;

        double     trailDist = mt.atr() * atrMultiplier;
        BigDecimal rawSl     = long_
                ? price.subtract(BigDecimal.valueOf(trailDist))
                : price.add(BigDecimal.valueOf(trailDist));

        // Trailing SL is a placement price — align to tick grid.
        // LONG: FLOOR (lower SL = conservative). SHORT: CEILING (higher SL = conservative).
        BigDecimal newSl = alignToTick(rawSl,
                long_ ? RoundingMode.FLOOR : RoundingMode.CEILING);

        boolean improve = long_
                ? newSl.compareTo(t.getStopLoss()) > 0
                : newSl.compareTo(t.getStopLoss()) < 0;

        if (improve) {
            t.setStopLoss(newSl);
            t.setUpdatedAt(Instant.now());
            log.info("[PAPER] Trail SL updated: {} newSl={} rMultiple={} atrMult={}",
                    sym, newSl, String.format("%.2f", rMultiple), atrMultiplier);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SCENARIO 5: Partial exit — real NSE brokerage model
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

            // Fill: apply TARGET_SLIP then align to tick.
            // Conservative: LONG exit → FLOOR (less profit). SHORT exit → CEILING.
            BigDecimal rawFill = long_
                    ? ltp.multiply(BigDecimal.valueOf(1.0 - TARGET_SLIP), MathContext.DECIMAL64)
                    : ltp.multiply(BigDecimal.valueOf(1.0 + TARGET_SLIP), MathContext.DECIMAL64);
            BigDecimal partialFill = alignToTick(rawFill,
                    long_ ? RoundingMode.FLOOR : RoundingMode.CEILING);

            // Gross P&L for the exited lot
            BigDecimal entryPrice = mt.trade().getEntryPrice();
            BigDecimal grossPnl   = long_
                    ? partialFill.subtract(entryPrice).multiply(BigDecimal.valueOf(halfQty))
                    : entryPrice.subtract(partialFill).multiply(BigDecimal.valueOf(halfQty));

            // Real NSE exit-leg cost (replaces flat ₹20)
            BigDecimal exitCost = NseBrokerageCalculator.exitLegCost(
                    partialFill, halfQty, mt.trade().getDirection());
            BigDecimal netPnl   = grossPnl.subtract(exitCost);

            account.applyPartialPnl(netPnl);

            log.info("[PAPER] Partial exit: {} qty={} fill={} ({}R) " +
                            "grossPnl={} exitCost={} netPnl={} window={} trend={}",
                    sym, halfQty, partialFill,
                    String.format("%.2f", halfExitAt),
                    String.format("%.2f", grossPnl.doubleValue()),
                    String.format("%.2f", exitCost.doubleValue()),
                    String.format("%.2f", netPnl.doubleValue()),
                    mt.entryWindow(), mt.strongTrend());

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
    // Move SL to breakeven
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

        log.info("[PAPER] SL → BREAKEVEN: {} entry={} at {}R",
                sym, t.getEntryPrice(), cfg.getGlobal().getBreakevenRTrigger());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SCENARIO 6 + 7: Market / sector alignment on 15-min candle
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
            String       sym    = entry.getKey();
            ManagedTrade mt     = entry.getValue();
            Trade        t      = mt.trade();
            boolean      forLong = t.getDirection() == TradeDirection.LONG;

            if (exitOnMarketTurn) {
                boolean marketTurned = forLong
                        ? dir.direction() == MarketDirectionService.Direction.BEARISH
                        : dir.direction() == MarketDirectionService.Direction.BULLISH;
                if (marketTurned) {
                    BigDecimal ltp     = lastPrices.getOrDefault(sym, t.getEntryPrice());
                    BigDecimal eodFill = simulateEodFill(ltp, t.getDirection());
                    log.warn("[PAPER] Market turned against {} — exiting at {}", sym, eodFill);
                    closeTrade(sym, eodFill, "MARKET_TURNED");
                    continue;
                }
            }

            if (exitOnSectorTurn) {
                if (!sectorStrength.isSectorAligned(sym, forLong)) {
                    BigDecimal ltp     = lastPrices.getOrDefault(sym, t.getEntryPrice());
                    BigDecimal eodFill = simulateEodFill(ltp, t.getDirection());
                    log.warn("[PAPER] Sector turned against {} — exiting at {}", sym, eodFill);
                    closeTrade(sym, eodFill, "SECTOR_TURNED");
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
        log.warn("[PAPER] FORCE CLOSE — {} positions at 15:00", activeTrades.size());
        new ArrayList<>(activeTrades.keySet()).forEach(sym -> {
            BigDecimal ltp = lastPrices.getOrDefault(sym,
                    activeTrades.get(sym).trade().getEntryPrice());
            BigDecimal eodFill = simulateEodFill(ltp,
                    activeTrades.get(sym).trade().getDirection());
            closeTrade(sym, eodFill, "TIME_EXIT_15:00");
        });
    }

    // ══════════════════════════════════════════════════════════════════════════
    // closeTrade
    // ══════════════════════════════════════════════════════════════════════════

    private void closeTrade(String sym, BigDecimal exitPrice, String reason) {
        ManagedTrade mt = activeTrades.remove(sym);
        if (mt == null) return;
        executor.closeTrade(mt.trade(), exitPrice, reason);
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

    /**
     * Simulates an SL fill with gap-down / circuit-limit awareness.
     *
     * <p>Normal case: fill = slPrice ± SL_SLIP → alignToTick.
     * <p>Gap case: if LTP has already moved further past SL than SL_SLIP would,
     * the fill is at LTP. Real NSE SL-M orders fill at prevailing best price,
     * not at trigger price, when the book has gapped through the level.
     *
     * <pre>
     * LONG:  rawFill = slPrice × (1 − 0.001)
     *        gapFill = min(rawFill, ltp)   ← ltp below rawFill = gap down
     *        result  = alignToTick(gapFill, FLOOR)
     *
     * SHORT: rawFill = slPrice × (1 + 0.001)
     *        gapFill = max(rawFill, ltp)   ← ltp above rawFill = gap up
     *        result  = alignToTick(gapFill, CEILING)
     * </pre>
     */
    static BigDecimal simulateSlFill(BigDecimal slPrice, BigDecimal ltp, TradeDirection dir) {
        if (dir == TradeDirection.LONG) {
            BigDecimal rawFill = slPrice.multiply(
                    BigDecimal.valueOf(1.0 - SL_SLIP), MathContext.DECIMAL64);
            BigDecimal gapFill = rawFill.min(ltp);
            return alignToTick(gapFill, RoundingMode.FLOOR);
        } else {
            BigDecimal rawFill = slPrice.multiply(
                    BigDecimal.valueOf(1.0 + SL_SLIP), MathContext.DECIMAL64);
            BigDecimal gapFill = rawFill.max(ltp);
            return alignToTick(gapFill, RoundingMode.CEILING);
        }
    }

    /**
     * Simulates a target fill with conservative tick alignment.
     * TARGET_SLIP is adverse (reduces profit).
     * LONG target → FLOOR. SHORT target → CEILING.
     */
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

    /**
     * Simulates an EOD / market-turn / time-stop fill.
     * EOD_SLIP is adverse. Same conservative tick alignment as target.
     */
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
    // NSE BROKERAGE CALCULATOR  —  EXIT LEG ONLY
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Computes NSE Equity Intraday exit-leg transaction costs, replacing the
     * former flat ₹20 fee.
     *
     * <h3>Charge schedule (NSE Equity Intraday, Zerodha, FY2024-25)</h3>
     * <pre>
     *  Component           Rate                       Side
     *  ──────────────────────────────────────────────────────────────────
     *  Brokerage           min(0.03% × turnover, ₹20) exit leg
     *  STT                 0.025% × turnover          SELL side only
     *  NSE Exchange Txn    0.00335% × turnover        exit leg
     *  SEBI Charges        ₹10 per crore              exit leg
     *  GST                 18% × (brok+exch+SEBI)     exit leg
     *  Stamp Duty          0%  on SELL exit            (buy-side only)
     *  ──────────────────────────────────────────────────────────────────
     * </pre>
     *
     * <p>Entry-leg costs (brokerage + STT + stamp on buy) are already charged
     * by {@code PaperOrderService.logFill()}. This class only charges the exit leg
     * to avoid double-counting.
     *
     * <p>STT on SHORT trades: STT applies on the sell leg. For a SHORT intraday
     * trade the sell is the entry (charged at entry), the exit is a buy-back
     * (no STT). Therefore STT here applies only when {@code direction == LONG}.
     */
    static final class NseBrokerageCalculator {

        /** Zerodha flat-rate: 0.03% of turnover, capped at ₹20 per order */
        private static final BigDecimal BROKERAGE_RATE    = new BigDecimal("0.0003");
        private static final BigDecimal BROKERAGE_CAP     = new BigDecimal("20.00");

        /** STT: 0.025% of sell-side turnover (NSE Equity Intraday) */
        private static final BigDecimal STT_RATE          = new BigDecimal("0.00025");

        /** NSE Exchange Transaction Charge: 0.00335% */
        private static final BigDecimal EXCHANGE_TXN_RATE = new BigDecimal("0.0000335");

        /** SEBI Charges: ₹10 per crore = 0.0001% = 0.000001 of turnover */
        private static final BigDecimal SEBI_RATE         = new BigDecimal("0.000001");

        /** GST on taxable services: 18% */
        private static final BigDecimal GST_RATE          = new BigDecimal("0.18");

        private NseBrokerageCalculator() {}

        /**
         * Returns total exit-leg transaction cost in ₹, rounded up (CEILING)
         * to 2 decimal places. CEILING ensures we never understate costs.
         *
         * @param fillPrice  tick-aligned actual fill price
         * @param qty        number of shares being exited
         * @param direction  trade direction (LONG or SHORT)
         * @return total deductible cost for this exit leg
         */
        static BigDecimal exitLegCost(BigDecimal fillPrice,
                                      int qty,
                                      TradeDirection direction) {
            BigDecimal turnover = fillPrice.multiply(BigDecimal.valueOf(qty));

            // Brokerage: min(0.03% × turnover, ₹20)
            BigDecimal brokerage = turnover.multiply(BROKERAGE_RATE).min(BROKERAGE_CAP);

            // STT: 0.025% on SELL turnover only.
            // LONG exit = sell shares → STT applies.
            // SHORT exit = buy-back → STT does NOT apply (was charged at short-entry).
            BigDecimal stt = direction == TradeDirection.LONG
                    ? turnover.multiply(STT_RATE)
                    : BigDecimal.ZERO;

            // NSE Exchange Transaction Charge: 0.00335%
            BigDecimal exchangeTxn = turnover.multiply(EXCHANGE_TXN_RATE);

            // SEBI charges: ₹10 per crore
            BigDecimal sebi = turnover.multiply(SEBI_RATE);

            // GST: 18% on (brokerage + exchange + SEBI)
            BigDecimal gst = brokerage.add(exchangeTxn).add(sebi)
                    .multiply(GST_RATE);

            // Stamp duty: 0% on sell exit (buy-side only)
            // For SHORT exit (a buy-back), stamp is negligible (~0.003%) and
            // is already accounted for at entry in PaperOrderService.
            BigDecimal total = brokerage.add(stt)
                    .add(exchangeTxn)
                    .add(sebi)
                    .add(gst);

            return total.setScale(2, RoundingMode.CEILING);
        }
    }
}