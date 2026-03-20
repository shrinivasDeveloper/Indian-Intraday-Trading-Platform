// ── TickReceivedEvent.java ────────────────────────────────────────
package com.trading.events;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;
import java.math.BigDecimal;
import java.time.Instant;

@Getter
public class TickReceivedEvent extends ApplicationEvent {
    private final long       instrumentToken;
    private final String     tradingSymbol;
    private final BigDecimal lastTradedPrice;
    private final BigDecimal openPrice, highPrice, lowPrice, closePrice;
    private final long       volumeTradedToday;
    private final long       totalBuyQuantity, totalSellQuantity;
    private final long       oi, oiDayHigh, oiDayLow;
    private final BigDecimal averageTradedPrice, changePercent;
    private final Instant    tickTimestamp;

    public TickReceivedEvent(Object src, long token, String symbol,
                              BigDecimal ltp, BigDecimal open, BigDecimal high,
                              BigDecimal low, BigDecimal close,
                              long vol, long buyQty, long sellQty,
                              long oi, long oiHigh, long oiLow,
                              BigDecimal atp, BigDecimal chg, Instant ts) {
        super(src);
        instrumentToken = token;    tradingSymbol = symbol;
        lastTradedPrice = ltp;      openPrice = open;
        highPrice = high;           lowPrice = low;
        closePrice = close;         volumeTradedToday = vol;
        totalBuyQuantity = buyQty;  totalSellQuantity = sellQty;
        this.oi = oi;               oiDayHigh = oiHigh;
        oiDayLow = oiLow;           averageTradedPrice = atp;
        changePercent = chg;        tickTimestamp = ts;
    }
}
