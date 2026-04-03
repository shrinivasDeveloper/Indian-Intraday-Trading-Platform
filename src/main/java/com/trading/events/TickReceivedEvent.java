package com.trading.events;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * TickReceivedEvent — fired by MarketDataService for every WebSocket tick.
 *
 * BUGS FIXED:
 *   1. totalBuyQuantity and totalSellQuantity were missing.
 *      SevenGateScannerService Gate 4e: boolean pressureOk = (buyVol > sellVol)
 *      With both = 0, pressureOk was ALWAYS FALSE → 100% of 7-gate signals blocked.
 *
 *   2. lastTradedQuantity and volumeTradedToday were missing.
 *      CandleAggregatorService uses volumeTradedToday to compute per-candle delta.
 *      With volumeTradedToday=0, all candles had volume=0 → RVOL always broken.
 *
 *   3. getOpenInterest() does not exist on Tick.
 *      Correct Zerodha SDK method is getOi(). MarketDataService uses tick.getOi().
 *      TickReceivedEvent stores it as openInterest field.
 *
 * CONSUMED BY:
 *   - CandleAggregatorService   (builds OHLCV candles)
 *   - SevenGateScannerService   (Gate 4: breakout + buy/sell pressure)
 *   - PaperTradeManagementService (SL/target tick monitoring)
 *   - TradeManagementService    (SL/target tick monitoring)
 *   - PartialCandleProcessor    (forming candle state)
 *   - VixService                (India VIX price from tick)
 */
@Getter
public class TickReceivedEvent extends ApplicationEvent {

    private final long       instrumentToken;
    private final String     tradingSymbol;
    private final BigDecimal lastTradedPrice;

    /**
     * Volume of the LAST individual trade (tick lot).
     * NOT the cumulative day volume.
     */
    private final long lastTradedQuantity;

    /**
     * Cumulative volume traded so far today (NSE day total).
     * CandleAggregatorService computes per-candle delta:
     *   candleVolume = volumeTradedToday(tick) - volumeAtCandleOpen
     *
     * BUG FIX: was missing → all candles had volume=0 → RVOL always broken.
     */
    private final long volumeTradedToday;

    /**
     * Total pending BUY quantity in the order book at time of tick.
     * Used by SevenGateScannerService Gate 4e (buy pressure check).
     *
     * BUG FIX: was missing → pressureOk=false for EVERY tick → all 7-gate signals blocked.
     */
    private final long totalBuyQuantity;

    /**
     * Total pending SELL quantity in the order book at time of tick.
     * Used by SevenGateScannerService Gate 4e (sell pressure check).
     *
     * BUG FIX: was missing → pressureOk=false for EVERY tick → all 7-gate signals blocked.
     */
    private final long totalSellQuantity;

    /**
     * Open interest (futures/options).
     * Stored from tick.getOi() (NOT tick.getOpenInterest() which doesn't exist).
     * Available for potential F&O analysis.
     */
    private final long openInterest;

    /**
     * Timestamp of the last trade from the exchange.
     * Used by CandleAggregatorService to align ticks to 5-min/15-min periods.
     */
    private final Instant tickTimestamp;

    // ── Full constructor (used by MarketDataService) ──────────────────────

    public TickReceivedEvent(Object source,
                             long instrumentToken,
                             String tradingSymbol,
                             BigDecimal lastTradedPrice,
                             long lastTradedQuantity,
                             long volumeTradedToday,
                             long totalBuyQuantity,
                             long totalSellQuantity,
                             long openInterest,
                             Instant tickTimestamp) {
        super(source);
        this.instrumentToken    = instrumentToken;
        this.tradingSymbol      = tradingSymbol;
        this.lastTradedPrice    = lastTradedPrice;
        this.lastTradedQuantity = lastTradedQuantity;
        this.volumeTradedToday  = volumeTradedToday;
        this.totalBuyQuantity   = totalBuyQuantity;
        this.totalSellQuantity  = totalSellQuantity;
        this.openInterest       = openInterest;
        this.tickTimestamp      = tickTimestamp != null ? tickTimestamp : Instant.now();
    }

    // ── Backward-compatible 3-arg constructor (for tests, backtest engine) ──

    /**
     * Minimal constructor — sets all volume/pressure fields to 0.
     * Used in: backtest engine, unit tests, synthetic tick injection.
     * Gate 4 pressure check will use default (0 vs 0 = false) but this
     * is acceptable in backtesting where order book data is not available.
     */
    public TickReceivedEvent(Object source, long instrumentToken,
                             String tradingSymbol, BigDecimal lastTradedPrice) {
        this(source, instrumentToken, tradingSymbol, lastTradedPrice,
                0L, 0L, 0L, 0L, 0L, Instant.now());
    }
}