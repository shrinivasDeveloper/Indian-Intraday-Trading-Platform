package com.trading.swing.auto.domain;

import java.math.BigDecimal;

/**
 * A stock's fundamental snapshot for Rule 4 — sourced from NSE's public
 * shareholding-pattern and financial-results data (NseDataClient), NOT
 * Zerodha's API (which has none of this). Any field can be null if NSE's
 * data was unavailable for that symbol — callers must handle null
 * explicitly, never treat missing data as a passing value.
 */
public record FundamentalSnapshot(
        String symbol,
        BigDecimal promoterHoldingPct,
        BigDecimal fiiHoldingPct,
        BigDecimal fiiHoldingPctPreviousQuarter,
        BigDecimal diiHoldingPct,
        BigDecimal publicHoldingPct,
        BigDecimal salesGrowthPct,
        BigDecimal profitGrowthPct
) {
    public boolean isComplete() {
        return promoterHoldingPct != null && fiiHoldingPct != null
                && diiHoldingPct != null && publicHoldingPct != null;
    }
}