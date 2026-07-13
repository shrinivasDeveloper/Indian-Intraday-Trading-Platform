package com.trading.momentumstockofday.domain;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/**
 * MomentumCandidate - one of the (at most) 9 stocks selected at 9:25 AM
 * and continuously monitored thereafter. In-memory only - this is
 * live, intraday tracking state, not a persisted trade record (see
 * MomentumTrade for that).
 */
@Getter
@Setter
public class MomentumCandidate {

    private final String symbol;
    private final String companyName;
    private final String sector;
    private final int sectorRank;      // 1, 2, or 3 - per spec priority order
    private final int stockRank;       // 1, 2, or 3 within its sector
    private final String direction;    // "LONG" or "SHORT" - fixed at selection time,
    // from the sector's trend (per spec: "trade
    // only in the direction of the sector's trend")
    private final double selectionPrice; // price at 9:25 AM, for reference/logging only

    /** Consolidation tracking - updated every monitoring cycle. */
    private volatile List<Candle> consolidationCandles = List.of();
    private volatile double consolidationHigh = 0;
    private volatile double consolidationLow = 0;
    private volatile boolean validConsolidation = false;
    private volatile String lastEvaluationNote = "Awaiting first evaluation";

    public MomentumCandidate(String symbol, String companyName, String sector,
                             int sectorRank, int stockRank, String direction,
                             double selectionPrice) {
        this.symbol = symbol;
        this.companyName = companyName;
        this.sector = sector;
        this.sectorRank = sectorRank;
        this.stockRank = stockRank;
        this.direction = direction;
        this.selectionPrice = selectionPrice;
    }

    /** Simple candle record - open/high/low/close only, exactly what's
     *  needed for consolidation/breakout detection. Independent of any
     *  existing strategy's own Candle class. */
    public record Candle(double open, double high, double low, double close, String timestamp,
                         long volume) {
        public double body() { return Math.abs(close - open); }
        public double range() { return high - low; }
    }
}