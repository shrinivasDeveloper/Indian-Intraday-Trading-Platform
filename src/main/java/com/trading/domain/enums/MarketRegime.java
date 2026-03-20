package com.trading.domain.enums;

public enum MarketRegime {
    STRONG_TRENDING_UP, STRONG_TRENDING_DOWN, WEAK_MIXED, SIDEWAYS;
    public boolean isTradeable()       { return this==STRONG_TRENDING_UP||this==STRONG_TRENDING_DOWN; }
    public boolean isLongFavourable()  { return this==STRONG_TRENDING_UP; }
    public boolean isShortFavourable() { return this==STRONG_TRENDING_DOWN; }
}
