package com.trading.events;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;
import java.math.BigDecimal;
import java.time.Instant;

@Getter
public class ScannerSignalEvent extends ApplicationEvent {
    private final String     tradingSymbol;
    private final long       instrumentToken;
    private final BigDecimal gapPercent;
    private final BigDecimal volumeRatio;
    private final BigDecimal atrPercent;
    private final String     sectorClassification;
    private final Instant    scanTime;

    public ScannerSignalEvent(Object src, String sym, long token,
                               BigDecimal gap, BigDecimal vol,
                               BigDecimal atr, String sector) {
        super(src);
        tradingSymbol = sym;         instrumentToken = token;
        gapPercent = gap;            volumeRatio = vol;
        atrPercent = atr;            sectorClassification = sector;
        scanTime = Instant.now();
    }
}
