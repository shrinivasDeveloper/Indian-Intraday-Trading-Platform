// ============================================================
// NEW FILE — v7.0 REQUIREMENT 1
// Path: src/main/java/com/trading/regime/service/MarketPhaseEngine.java
// PURPOSE: Implements the Market Phase Engine from v7.0 prompt.
//   EARLY PHASE (9:15–10:15): mode=UNKNOWN, enable ORB + momentum, lower threshold
//   CONFIRMED PHASE (after 10:15): lock IB, full strategy engine activates
//   FAILSAFE: if time > 10:30 AND IB not set → FORCE IB calculation
// ============================================================
package com.trading.regime.service;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * MarketPhaseEngine — v7.0 Section 1.
 *
 * PHASES:
 *   EARLY     (9:15–10:15): ORB + momentum active. Mode = UNKNOWN.
 *                           Lower probability threshold (58).
 *                           Early boost: if rvol > 1.2 → prob += 5
 *   CONFIRMED (10:15+):     IB locked, full strategy matrix active.
 *                           Normal thresholds from MarketModeEngine.
 *   PRE_OPEN  (before 9:15): No trades.
 *   CLOSED    (after 15:30): No trades.
 *
 * INTEGRATION:
 *   StrategyEvaluatorService reads getCurrentPhase() to:
 *     1. Decide which strategies to run (ORB early, all confirmed)
 *     2. Apply the correct probability threshold
 *     3. Apply early boost if applicable
 *
 * FAILSAFE (v7.0 FIX 4):
 *   isIbForceNeeded() returns true when time > 10:30 and IB phase just confirmed.
 *   MarketModeEngine.forceComputeIbIfMissing() is called in this case.
 */
@Service
@Slf4j
public class MarketPhaseEngine {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public enum MarketPhase {
        PRE_OPEN,    // Before 9:15
        EARLY,       // 9:15 – 10:15 (ORB window, mode unknown)
        CONFIRMED,   // 10:15+ (IB locked, full strategies)
        CLOSED       // After 15:30
    }

    @Getter
    private volatile MarketPhase currentPhase = MarketPhase.PRE_OPEN;
    private volatile boolean phaseLogged = false;

    // ── Phase evaluation (called every 5 seconds by scheduler) ───────────────

    @Scheduled(fixedDelay = 5000)
    public void evaluatePhase() {
        LocalTime now = LocalTime.now(IST);
        MarketPhase newPhase;

        if (now.isBefore(LocalTime.of(9, 15))) {
            newPhase = MarketPhase.PRE_OPEN;
        } else if (now.isBefore(LocalTime.of(10, 15))) {
            newPhase = MarketPhase.EARLY;
        } else if (now.isBefore(LocalTime.of(15, 30))) {
            newPhase = MarketPhase.CONFIRMED;
        } else {
            newPhase = MarketPhase.CLOSED;
        }

        if (newPhase != currentPhase || !phaseLogged) {
            log.info("[PHASE] {} → {} at {}", currentPhase, newPhase, now);
            currentPhase = newPhase;
            phaseLogged = true;
        }
    }

    // ── Public helpers ────────────────────────────────────────────────────────

    /**
     * Is the system currently in the early ORB phase?
     * During EARLY: ORB and momentum strategies are enabled with lower threshold.
     */
    public boolean isEarlyPhase() {
        return currentPhase == MarketPhase.EARLY;
    }

    /**
     * Is the full strategy engine active?
     * During CONFIRMED: all strategies run with normal probability thresholds.
     */
    public boolean isConfirmedPhase() {
        return currentPhase == MarketPhase.CONFIRMED;
    }

    /**
     * Are any trades allowed right now?
     */
    public boolean isTradeAllowed() {
        return currentPhase == MarketPhase.EARLY
                || currentPhase == MarketPhase.CONFIRMED;
    }

    /**
     * v7.0 DYNAMIC THRESHOLD:
     *   Early (before 10:30)   → 58
     *   Confirmed TREND_DAY    → 62
     *   Confirmed NORMAL_DAY   → 60
     *   Confirmed NEUTRAL_DAY  → 68
     *
     * @param modeBasedThreshold threshold from MarketModeEngine (60, 65, 70...)
     * @return adjusted threshold for current phase
     */
    public double getAdjustedThreshold(double modeBasedThreshold) {
        if (isEarlyPhase()) return 58.0; // Lower threshold for early momentum
        return modeBasedThreshold;        // Normal threshold post IB
    }

    /**
     * v7.0 EARLY BOOST:
     * If time < 10:15 AND rvol > 1.2 → probability += 5
     *
     * @param rvol current relative volume
     * @return bonus points to add to probability
     */
    public double getEarlyBoost(double rvol) {
        if (isEarlyPhase() && rvol > 1.2) return 5.0;
        return 0.0;
    }

    /**
     * FAILSAFE (v7.0 FIX 4):
     * Returns true when we've just entered CONFIRMED phase but IB was not yet computed.
     * Caller should invoke MarketModeEngine.forceComputeIbIfMissing() when true.
     */
    public boolean isIbForceNeeded() {
        LocalTime now = LocalTime.now(IST);
        return currentPhase == MarketPhase.CONFIRMED
                && now.isAfter(LocalTime.of(10, 30));
    }

    /**
     * Is the ORB strategy specifically allowed right now?
     * ORB is valid in EARLY phase AND up to 13:00 in CONFIRMED phase.
     */
    public boolean isOrbAllowed() {
        LocalTime now = LocalTime.now(IST);
        return isTradeAllowed() && now.isBefore(LocalTime.of(13, 0));
    }

    @Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyReset() {
        currentPhase = MarketPhase.PRE_OPEN;
        phaseLogged = false;
        log.info("[PHASE] Daily reset → PRE_OPEN");
    }
}