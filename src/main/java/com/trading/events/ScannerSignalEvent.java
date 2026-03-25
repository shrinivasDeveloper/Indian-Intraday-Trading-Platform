package com.trading.events;

import com.trading.domain.enums.TradeDirection;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * ScannerSignalEvent — fired by SevenGateScannerService when all 7 gates pass.
 *
 * Updated: added direction, entryPrice, stopLoss, target from ArmedStock.
 * Original 7-arg constructor kept for backward compatibility.
 * New 11-arg constructor carries full trade parameters for StrategyEvaluatorService.
 */
@Getter
public class ScannerSignalEvent extends ApplicationEvent {

    // Original fields (unchanged — do not rename)
    private final String     tradingSymbol;
    private final long       instrumentToken;
    private final BigDecimal gapPercent;
    private final BigDecimal volumeRatio;
    private final BigDecimal atrPercent;
    private final String     sectorClassification;
    private final Instant    scanTime;

    // New fields — null when using original constructor
    private final TradeDirection direction;
    private final BigDecimal     entryPrice;
    private final BigDecimal     stopLoss;
    private final BigDecimal     target;

    /** Original constructor — backward compatible */
    public ScannerSignalEvent(Object src, String sym, long token,
                              BigDecimal gap, BigDecimal vol,
                              BigDecimal atr, String sector) {
        super(src);
        tradingSymbol       = sym;
        instrumentToken     = token;
        gapPercent          = gap;
        volumeRatio         = vol;
        atrPercent          = atr;
        sectorClassification= sector;
        scanTime            = Instant.now();
        direction           = null;
        entryPrice          = null;
        stopLoss            = null;
        target              = null;
    }

    /** Full constructor — carries trade parameters from ArmedStock */
    public ScannerSignalEvent(Object src, String sym, long token,
                              BigDecimal gap, BigDecimal vol,
                              BigDecimal atr, String sector,
                              TradeDirection dir, BigDecimal entry,
                              BigDecimal sl, BigDecimal tgt) {
        super(src);
        tradingSymbol       = sym;
        instrumentToken     = token;
        gapPercent          = gap;
        volumeRatio         = vol;
        atrPercent          = atr;
        sectorClassification= sector;
        scanTime            = Instant.now();
        direction           = dir;
        entryPrice          = entry;
        stopLoss            = sl;
        target              = tgt;
    }
}