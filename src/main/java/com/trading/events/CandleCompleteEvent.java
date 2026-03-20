// FILE: src/main/java/com/trading/events/CandleCompleteEvent.java
package com.trading.events;

import com.trading.domain.Candle;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class CandleCompleteEvent extends ApplicationEvent {
    private final Candle candle;
    public CandleCompleteEvent(Object src, Candle c) { super(src); this.candle = c; }
}
