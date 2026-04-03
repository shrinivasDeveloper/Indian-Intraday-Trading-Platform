package com.trading.events;

import com.trading.domain.enums.TradeDirection;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * ScannerSignalEvent — fired by SevenGateScannerService when all 7 gates pass.
 *
 * FIELDS:
 *   Original fields (unchanged for backward compat):
 *     tradingSymbol, instrumentToken, gapPercent, volumeRatio, atrPercent,
 *     sectorClassification, scanTime
 *
 *   Added for trade parameters (full constructor):
 *     direction, entryPrice, stopLoss, target
 *
 *   Added for stale-signal guard (StrategyEvaluatorService.isSignalStale()):
 *     scanTime is already Instant.now() — used as the signal creation timestamp.
 *     StrategyEvaluatorService reads getScanTime() to check signal age.
 *
 * CONSTRUCTORS:
 *   1. Original 7-arg constructor (backward compatible — direction/entry/sl/target = null)
 *   2. Full 11-arg constructor (carries trade parameters from ArmedStock)
 */
@Getter
public class ScannerSignalEvent extends ApplicationEvent {

    // ── Original fields (unchanged — do not rename) ─────────────────────

    private final String     tradingSymbol;
    private final long       instrumentToken;
    private final BigDecimal gapPercent;
    private final BigDecimal volumeRatio;
    private final BigDecimal atrPercent;
    private final String     sectorClassification;

    /**
     * Timestamp when the signal was created (Instant.now() at construction time).
     * Used by StrategyEvaluatorService.isSignalStale() to reject old signals:
     *   if (now - scanTime > maxSignalAgeSeconds) → reject
     */
    private final Instant scanTime;

    // ── New fields — null when using original 7-arg constructor ─────────

    private final TradeDirection direction;
    private final BigDecimal     entryPrice;
    private final BigDecimal     stopLoss;
    private final BigDecimal     target;

    // ── Constructor 1: original 7-arg (backward compatible) ──────────────

    /**
     * Original constructor — backward compatible.
     * direction, entryPrice, stopLoss, target will be null.
     * Used by SevenGateScannerService when it only has gap/volume/atr data.
     */
    public ScannerSignalEvent(Object src, String sym, long token,
                              BigDecimal gap, BigDecimal vol,
                              BigDecimal atr, String sector) {
        super(src);
        this.tradingSymbol       = sym;
        this.instrumentToken     = token;
        this.gapPercent          = gap;
        this.volumeRatio         = vol;
        this.atrPercent          = atr;
        this.sectorClassification = sector;
        this.scanTime            = Instant.now();
        this.direction           = null;
        this.entryPrice          = null;
        this.stopLoss            = null;
        this.target              = null;
    }

    // ── Constructor 2: full 11-arg (carries ArmedStock trade parameters) ──

    /**
     * Full constructor — carries trade parameters from ArmedStock.
     * Used when SevenGateScannerService has all trade params ready at signal fire.
     */
    public ScannerSignalEvent(Object src, String sym, long token,
                              BigDecimal gap, BigDecimal vol,
                              BigDecimal atr, String sector,
                              TradeDirection dir, BigDecimal entry,
                              BigDecimal sl, BigDecimal tgt) {
        super(src);
        this.tradingSymbol       = sym;
        this.instrumentToken     = token;
        this.gapPercent          = gap;
        this.volumeRatio         = vol;
        this.atrPercent          = atr;
        this.sectorClassification = sector;
        this.scanTime            = Instant.now();
        this.direction           = dir;
        this.entryPrice          = entry;
        this.stopLoss            = sl;
        this.target              = tgt;
    }
}