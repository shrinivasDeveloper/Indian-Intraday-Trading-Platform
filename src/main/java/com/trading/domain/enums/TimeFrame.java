package com.trading.domain.enums;

public enum TimeFrame {
    ONE_MIN("minute", 1),
    FIVE_MIN("5minute", 5),
    FIFTEEN_MIN("15minute", 15),
    SIXTY_MIN("60minute", 60),
    DAY("day", 1440);

    public final String zerodhaInterval;
    public final int    minutes;

    TimeFrame(String z, int m) {
        this.zerodhaInterval = z;
        this.minutes = m;
    }
}
