package com.trading.ai.model;

import lombok.Getter;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AiSymbolHistory — per-symbol trading history tracked by AiLearningService.
 * Used as Group G features in AiFeatureEngineeringService.
 */
@Getter
public class AiSymbolHistory {
    private final String symbol;
    private int   totalTrades   = 0;
    private int   wins          = 0;
    private double totalR       = 0.0;
    private int   timesThisWeek = 0;
    private double lastOutcome  = 0.0; // +1 win, -1 loss, 0 none

    public AiSymbolHistory(String symbol) { this.symbol = symbol; }

    public void recordOutcome(AiTradeOutcome outcome) {
        totalTrades++;
        totalR += outcome.getRMultiple();
        if (outcome.getRMultiple() >= 1.0) { wins++; lastOutcome = 1.0; }
        else { lastOutcome = -1.0; }
        timesThisWeek++;
    }

    public void loadFromDb(int total, int w, double avgR) {
        totalTrades = total; wins = w; totalR = avgR * total;
    }

    public double getWinRate()         { return totalTrades > 0 ? (double) wins / totalTrades : 0.5; }
    public double getAvgRMultiple()    { return totalTrades > 0 ? totalR / totalTrades : 1.0; }

    public static AiSymbolHistory empty(String symbol) { return new AiSymbolHistory(symbol); }
}