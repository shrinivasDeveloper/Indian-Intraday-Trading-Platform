package com.trading.ai.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * AiPerformanceMetrics — overall AI system performance stats.
 * Displayed on dashboard and used by AiTradeSelectionService.
 */
@Getter
@AllArgsConstructor
public class AiPerformanceMetrics {
    private final int    totalTrades;
    private final int    wins;
    private final int    losses;
    private final double winRate;         // 0.0–1.0
    private final double expectancy;      // avg R-multiple per trade
    private final double profitFactor;    // wins/losses count ratio
    private final double totalPnl;        // cumulative ₹
    private final double maxDrawdown;     // worst drawdown ₹
    private final double sharpeRatio;     // risk-adjusted return

    public static AiPerformanceMetrics empty() {
        return new AiPerformanceMetrics(0,0,0,0,0,0,0,0,0);
    }
}