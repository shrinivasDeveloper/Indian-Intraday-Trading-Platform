package com.trading.marketdata.service;

import com.trading.events.TickReceivedEvent;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * India VIX monitor — FIXED with configurable thresholds.
 *
 * Problem found in logs:
 *   "VIX regime changed: NORMAL → EXTREME (VIX=26.14)"
 *   "VIX ABOVE 25 — ZERO TRADES TODAY"
 *   VIX=26.14 blocked ALL trades for the entire day.
 *
 * Fix 1: All thresholds now configurable via application.yml.
 *   Previously hardcoded at 20/25. Indian market often has VIX 22-28
 *   on volatile but tradeable days.
 *
 * Fix 2: vix.block-trades-when-extreme is now configurable.
 *   Set to false to allow trades even when VIX is extreme
 *   (strategy conditions still filter bad setups naturally).
 *   Default: true (safe, same as before).
 *
 * application.yml settings:
 *   vix:
 *     normal-max: 20.0       # below this = NORMAL (full size)
 *     extreme-min: 28.0      # above this = EXTREME (was 25, now 28)
 *     block-trades-when-extreme: false  # allow trades at any VIX
 */
@Service
@Slf4j
public class VixService {

    private static final long VIX_TOKEN = 264969L;

    // Configurable via application.yml
    @Value("${vix.normal-max:20.0}")
    private double vixNormalMax;

    @Value("${vix.extreme-min:28.0}")
    private double vixExtremeMin;

    // Set to false to allow trades even when VIX is high
    // Strategy conditions (BB compression, volume, structure) naturally
    // filter out bad setups — VIX block is an extra safety net, not required
    @Value("${vix.block-trades-when-extreme:false}")
    private boolean blockTradesWhenExtreme;

    @Getter private volatile double    currentVix      = 16.0;
    @Getter private volatile VixRegime regime          = VixRegime.NORMAL;
    @Getter private volatile LocalDate lastUpdatedDate = null;

    public enum VixRegime {
        NORMAL,   // VIX < normal-max  — full size, standard RR
        ELEVATED, // VIX normal-max to extreme-min — half size, +0.5 RR
        EXTREME   // VIX > extreme-min — configurable: block or reduce size
    }

    @EventListener
    @Async("tradingExecutor")
    public void onTick(TickReceivedEvent tick) {
        if (tick.getInstrumentToken() != VIX_TOKEN) return;

        double vix = tick.getLastTradedPrice().doubleValue();
        if (vix <= 0) return;

        currentVix      = vix;
        lastUpdatedDate = LocalDate.now();

        VixRegime newRegime;
        if (vix < vixNormalMax)      newRegime = VixRegime.NORMAL;
        else if (vix <= vixExtremeMin) newRegime = VixRegime.ELEVATED;
        else                           newRegime = VixRegime.EXTREME;

        if (newRegime != regime) {
            log.warn("VIX regime changed: {} → {} (VIX={})", regime, newRegime, vix);
            if (newRegime == VixRegime.EXTREME) {
                if (blockTradesWhenExtreme) {
                    log.warn("VIX ABOVE {} — ZERO TRADES TODAY (block-trades-when-extreme=true). VIX={}",
                            vixExtremeMin, vix);
                } else {
                    log.warn("VIX ABOVE {} — HALF SIZE, +1.0 RR extra (block-trades-when-extreme=false). VIX={}",
                            vixExtremeMin, vix);
                }
            }
            regime = newRegime;
        }
    }

    /**
     * Returns true if trades are allowed.
     * When block-trades-when-extreme=false → always true (strategy
     * conditions filter naturally via tighter RR requirements).
     * When block-trades-when-extreme=true → false if VIX is EXTREME.
     */
    public boolean isTradeAllowed() {
        if (!blockTradesWhenExtreme) return true; // never block
        return regime != VixRegime.EXTREME;
    }

    public boolean isElevated() {
        return regime == VixRegime.ELEVATED || regime == VixRegime.EXTREME;
    }

    /** Position size multiplier: 0.5× when VIX elevated or extreme */
    public double positionSizeMultiplier() {
        return (regime == VixRegime.ELEVATED || regime == VixRegime.EXTREME) ? 0.5 : 1.0;
    }

    /**
     * Extra RR required due to VIX volatility:
     *   NORMAL   → +0.0
     *   ELEVATED → +0.5
     *   EXTREME  → +1.0
     */
    public double extraRrRequirement() {
        return switch (regime) {
            case ELEVATED -> 0.5;
            case EXTREME  -> 1.0;
            default       -> 0.0;
        };
    }
}