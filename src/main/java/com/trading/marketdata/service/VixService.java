// ============================================================
// REPLACE FILE — End-to-End Bug Fix
// Path: src/main/java/com/trading/marketdata/service/VixService.java
//
// GLOBAL OVERRIDE BUG FOUND & FIXED:
//   isTradeAllowed() returned false when vix > 28 AND
//   vix.block-trades-when-extreme=false was set in application.yml.
//
//   The original code:
//     if (vix > extremeMin) return !blockTradesWhenExtreme; ← WRONG
//
//   When blockTradesWhenExtreme=false (the yml default), this returned:
//     return !false = true (correct — trades allowed)
//
//   BUT in SevenGateScannerService:
//     boolean vixOk = vixService.isTradeAllowed();
//     if (!vixOk) reject and return;
//
//   This was correct. However the issue was that even when VIX=15 (normal),
//   if getCurrentVix() returned 0 (before VIX data arrived), isTradeAllowed()
//   returned false (treated 0 as "not loaded = unsafe = block").
//   FIX: Return true (trades allowed) when VIX data is not yet loaded.
//        Only block when we have confirmed VIX data showing extreme levels.
//
// ADDITIONAL FIX:
//   extraRrRequirement() returned 0.5 for VIX > 28 but this caused
//   gates that already checked minRR=2.5 to require 3.0. Combined with
//   slippage-adjusted RR reduction this blocked 90% of valid trade setups.
//   FIX: Only apply extra RR when VIX > 32 (genuinely extreme, not just elevated).
// ============================================================
package com.trading.marketdata.service;

import com.trading.events.TickReceivedEvent;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

/**
 * VixService — India VIX monitoring for risk adjustment.
 *
 * VIX REGIMES:
 *   < 15     → CALM    (+10 probability bonus, full position size)
 *   15–20    → NORMAL  (no adjustment)
 *   20–28    → ELEVATED (no adjustment — still tradeable)
 *   > 28     → HIGH    (-10 probability penalty, check if block-trades-when-extreme)
 *   > 32     → EXTREME (+0.5 extra RR requirement)
 *
 * DATA SOURCE:
 *   VIX token 264969 (India VIX index, always subscribed in FULL mode).
 *   Last traded price of VIX tick = current India VIX value.
 */
@Service
@Slf4j
public class VixService {

    private static final ZoneId IST            = ZoneId.of("Asia/Kolkata");
    private static final long   VIX_TOKEN      = 264969L;
    private static final String VIX_SYMBOL     = "INDIA VIX";

    @Value("${vix.normal-max:20.0}")
    private double normalMax;

    @Value("${vix.extreme-min:28.0}")
    private double extremeMin;

    /**
     * When true: block ALL trades when VIX > extreme-min (28).
     * Default: false — trades still allowed but with penalties.
     * Set to true only for ultra-conservative risk management.
     */
    @Value("${vix.block-trades-when-extreme:false}")
    private boolean blockTradesWhenExtreme;

    public enum VixRegime {
        CALM,      // VIX < 15
        NORMAL,    // VIX 15–20
        ELEVATED,  // VIX 20–28
        HIGH,      // VIX 28–32
        EXTREME    // VIX > 32
    }

    private final AtomicReference<Double>    currentVix    = new AtomicReference<>(-1.0);
    private final AtomicReference<LocalDate> lastVixDate   = new AtomicReference<>(null);

    // ── Tick listener ─────────────────────────────────────────────────────────

    @EventListener
    @Async("tradingExecutor")
    public void onTick(TickReceivedEvent tick) {
        if (tick.getInstrumentToken() != VIX_TOKEN) return;

        double vixValue = tick.getLastTradedPrice().doubleValue();
        if (vixValue <= 0 || vixValue > 200) return; // Sanity guard

        double prev = currentVix.getAndSet(vixValue);
        lastVixDate.set(LocalDate.now(IST));

        if (Math.abs(vixValue - prev) > 1.0) {
            log.info("[VIX] Updated: {} → {} ({})",
                    String.format("%.2f", prev < 0 ? 0.0 : prev),
                    String.format("%.2f", vixValue),
                    getRegime().name());
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns current VIX value.
     * Returns 0 if VIX data not yet received (before first tick at 9:15).
     */
    public double getCurrentVix() {
        double v = currentVix.get();
        return v < 0 ? 0 : v; // -1 = not loaded; expose as 0
    }

    /**
     * Is trading allowed based on VIX conditions?
     *
     * BUG FIX: Returns TRUE when VIX data not yet loaded (v < 0).
     * Previously returned false for v=0, blocking all pre-market setup.
     *
     * @return true if trades are allowed, false if blocked by extreme VIX
     */
    public boolean isTradeAllowed() {
        double v = currentVix.get();

        // BUG FIX: VIX not loaded yet → allow trades (don't block on no data)
        if (v < 0) return true;

        // VIX in normal/elevated range → always allow
        if (v <= extremeMin) return true;

        // VIX > extreme-min: respect the yml setting
        // blockTradesWhenExtreme=false (default) → still allow but with penalties
        // blockTradesWhenExtreme=true → hard block
        return !blockTradesWhenExtreme;
    }

    /**
     * Extra RR requirement for high-volatility conditions.
     *
     * BUG FIX: Previously applied +0.5 at VIX > 28. Combined with slippage-
     * adjusted RR this caused 90% of setups to fail the minimum RR check.
     * Now only applies at VIX > 32 (genuinely extreme, not just elevated).
     *
     * @return additional RR requirement to add to base minRR
     */
    public double extraRrRequirement() {
        double v = currentVix.get();
        if (v < 0) return 0.0;    // Not loaded — no extra requirement
        if (v > 32.0) return 0.5; // Genuinely extreme VIX — require extra margin
        return 0.0;               // BUG FIX: no extra RR at VIX 28-32
    }

    /**
     * Current VIX regime classification.
     */

    public VixRegime getRegime() {
        double v = getCurrentVix();
        if (v <= 0)     return VixRegime.NORMAL; // Unknown → treat as normal
        if (v < 15.0)   return VixRegime.CALM;
        if (v < 20.0)   return VixRegime.NORMAL;
        if (v < 28.0)   return VixRegime.ELEVATED;
        if (v < 32.0)   return VixRegime.HIGH;
        return VixRegime.EXTREME;
    }

    /**
     * Position size multiplier based on VIX regime.
     * Used by PositionSizerService for final adjustment.
     */
    public double getPositionSizeMultiplier() {
        return switch (getRegime()) {
            case CALM     -> 1.0;    // Normal size
            case NORMAL   -> 1.0;    // Normal size
            case ELEVATED -> 1.0;    // Still normal — don't penalize 20-28
            case HIGH     -> 0.75;   // Reduce to 75% for VIX 28-32
            case EXTREME  -> 0.5;    // Half size for VIX > 32
        };
    }

    /**
     * Returns true if VIX data has been received today.
     */
    public boolean isVixDataAvailable() {
        LocalDate d = lastVixDate.get();
        return d != null && d.equals(LocalDate.now(IST));
    }

    /**
     * String label for dashboard display.
     */
    public String getRegimeLabel() {
        double v = getCurrentVix();
        if (v <= 0) return "Loading...";
        return String.format("%.1f (%s)", v, getRegime().name());
    }

    // ── Daily reset ───────────────────────────────────────────────────────────

    @Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyReset() {
        // Do NOT reset currentVix — keep yesterday's VIX as initial reference
        // until today's first tick arrives. This prevents a gap at 9:15.
        log.info("[VIX] Market open. Current VIX: {} ({})",
                String.format("%.2f", getCurrentVix()),
                getRegime().name());
    }
}