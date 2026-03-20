package com.trading.events;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class CircuitBreakerEvent extends ApplicationEvent {
    private final String eventType;
    private final String reason;

    public CircuitBreakerEvent(Object src, String type, String reason) {
        super(src);
        this.eventType = type;
        this.reason = reason;
    }
}
