package com.trading.domain.enums;

/**
 * MarketRegime — enum for classifying overall market conditions.
 *
 * STATUS: Currently unused. All services that referenced this enum
 * (StrategyEvaluatorService, SevenGateScannerService, ProbabilityEngine)
 * have been deleted.
 *
 * Retained for future strategy implementations. When a new strategy engine
 * needs to classify market regime conditions, use this enum.
 *
 * If not needed in the new strategy engine, this file can be safely deleted.
 */
public enum MarketRegime {
    STRONG_TRENDING_UP,
    STRONG_TRENDING_DOWN,
    WEAK_MIXED,
    SIDEWAYS;

    public boolean isTradeable()       { return this == STRONG_TRENDING_UP || this == STRONG_TRENDING_DOWN; }
    public boolean isLongFavourable()  { return this == STRONG_TRENDING_UP; }
    public boolean isShortFavourable() { return this == STRONG_TRENDING_DOWN; }
}