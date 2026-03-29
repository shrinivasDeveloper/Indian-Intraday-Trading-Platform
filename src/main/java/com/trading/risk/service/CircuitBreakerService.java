package com.trading.risk.service;

import com.trading.events.CircuitBreakerEvent;
import com.trading.marketdata.service.VixService;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * CircuitBreakerService — account-level protection with dynamic profit floor.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * FLAW 5 FIX — Dynamic Trailing Profit Floor:
 *
 *   PROBLEM: Fixed floor at +4% is static. If P&L reaches +10%, the floor
 *   stays at +4% — you could give back 6% before the trigger fires. On a
 *   strong trending day this is unacceptable risk management.
 *
 *   SOLUTION: The floor trails the P&L dynamically using VIX to compute
 *   the "give-back" width (how much retrace to tolerate before locking).
 *
 *   Dynamic Give-Back Formula:
 *     dailyVol      = vixValue / sqrt(252) / 100    (annualised VIX → 1-day sigma)
 *     giveBack      = max(minGivebackPct, dailyVol × givebackMultiplier)
 *     dynamicFloor  = currentDailyPnlPct − giveBack
 *     trailingFloor = max(trailingFloor, dynamicFloor)
 *     trailingFloor = max(trailingFloor, profitFloorPct)  ← never below static floor
 *
 *   On every recordPnl() call after lock activation:
 *     if dailyPnlPct <= trailingFloor → CLOSE_ALL and lock the day
 *
 *   Numerical examples (VIX=15, givebackMultiplier=2.0, minGiveback=0.5%):
 *     P&L=+6%:  dailyVol=0.944% → giveback=max(0.5,1.888)=1.888% → floor=4.11% → trailing=max(4.0,4.11)=4.11%
 *     P&L=+8%:  giveback=1.888% → dynamic=6.11% → trailing=max(4.11,6.11)=6.11%
 *     P&L=+10%: giveback=1.888% → dynamic=8.11% → trailing=max(6.11,8.11)=8.11%
 *     P&L retraces to 8.00% → 8.00 <= 8.11 → CLOSE_ALL → day locked at ~8%
 *
 *   VIX=25 example (volatile day):
 *     dailyVol=1.573% → giveback=max(0.5,3.147)=3.147%
 *     P&L=+6%: dynamic=2.853% → trailing=max(4.0,2.853)=4.0% (static floor dominates)
 *     P&L=+8%: dynamic=4.853% → trailing=max(4.0,4.853)=4.853%
 *     High VIX = wider give-back = floor rises more slowly. Appropriate: volatile days
 *     have larger natural swings, tighter floor would close prematurely.
 *
 *   Config (all in application.yml):
 *     circuit-breaker.profit-lock-trigger-pct:  6.0
 *     circuit-breaker.profit-floor-pct:         4.0   (static minimum floor)
 *     circuit-breaker.giveback-multiplier:      2.0
 *     circuit-breaker.min-giveback-pct:         0.5
 *
 * ═══════════════════════════════════════════════════════════════════════
 * THREAD SAFETY:
 *   profitLockActivated and profitFloorTripped are volatile booleans.
 *   trailingFloor is a volatile double — reads/writes are atomic on 64-bit JVMs.
 *   recordPnl() is synchronized to prevent concurrent floor updates from
 *   multiple closing trades racing to update trailingFloor.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * PRESERVED FROM ORIGINAL:
 *   - Daily/weekly/monthly P&L caps
 *   - maxPerDay trade count
 *   - manualReset() dashboard button
 *   - All @Scheduled resets
 * ═══════════════════════════════════════════════════════════════════════
 */
@Service
@Slf4j
public class CircuitBreakerService {

    private final ApplicationEventPublisher publisher;
    private final VixService               vixService;

    // @Lazy on VixService to avoid potential startup ordering issues
    public CircuitBreakerService(ApplicationEventPublisher publisher,
                                 @Lazy VixService vixService) {
        this.publisher  = publisher;
        this.vixService = vixService;
    }

    // ── Config ─────────────────────────────────────────────────────────────────

    @Getter
    @Value("${circuit-breaker.max-trades-per-day:10}")
    private int maxPerDay;

    @Value("${circuit-breaker.daily-loss-cap-pct:-2.5}")
    private double dailyCap;

    @Value("${circuit-breaker.weekly-drawdown-cap-pct:-8.0}")
    private double weeklyCap;

    @Value("${circuit-breaker.monthly-drawdown-cap-pct:-15.0}")
    private double monthlyCap;

    /** Profit lock activates when daily P&L crosses this % (e.g. +6.0%). */
    @Value("${circuit-breaker.profit-lock-trigger-pct:6.0}")
    private double profitLockTriggerPct;

    /** Static minimum floor — trailing floor never goes below this. */
    @Value("${circuit-breaker.profit-floor-pct:4.0}")
    private double profitFloorPct;

    /**
     * FLAW 5 FIX: Give-back multiplier.
     * dynamicGiveBack = dailyVol × givebackMultiplier.
     * Higher = wider tolerated retrace on normal-volatility days.
     */
    @Value("${circuit-breaker.giveback-multiplier:2.0}")
    private double givebackMultiplier;

    /**
     * FLAW 5 FIX: Minimum give-back regardless of VIX.
     * Even on ultra-low-VIX days, allow at least 0.5% retrace before locking.
     */
    @Value("${circuit-breaker.min-giveback-pct:0.5}")
    private double minGivebackPct;

    // ── State ──────────────────────────────────────────────────────────────────

    @Getter private volatile boolean active             = true;
    @Getter private volatile String  disableReason      = null;
    @Getter private volatile boolean profitLockActivated = false;

    /**
     * FLAW 5 FIX: Trailing floor — starts at profitFloorPct and only moves up.
     * Updated on every recordPnl() call while lock is active.
     */
    private volatile double trailingFloor = 0.0;

    /** Guards against firing CLOSE_ALL more than once per day. */
    private volatile boolean profitFloorTripped = false;

    private final AtomicInteger               tradesToday     = new AtomicInteger(0);
    private final AtomicReference<BigDecimal> dailyPnl        = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicReference<BigDecimal> weeklyPnl       = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicReference<BigDecimal> monthlyPnl      = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicReference<BigDecimal> capitalSnapshot  = new AtomicReference<>(BigDecimal.ZERO);

    // ── Dashboard getters ──────────────────────────────────────────────────────

    public int        getTradesToday()        { return tradesToday.get(); }
    public BigDecimal getDailyPnl()           { return dailyPnl.get(); }
    public BigDecimal getWeeklyPnl()          { return weeklyPnl.get(); }
    public BigDecimal getMonthlyPnl()         { return monthlyPnl.get(); }
    public double     getTrailingFloor()      { return trailingFloor; }

    // ── Manual reset ───────────────────────────────────────────────────────────

    public void manualReset() {
        tradesToday.set(0);
        dailyPnl.set(BigDecimal.ZERO);
        profitLockActivated = false;
        profitFloorTripped  = false;
        trailingFloor       = 0.0;
        active              = true;
        disableReason       = null;
        log.warn("[CB] MANUALLY RESET via dashboard");
        publisher.publishEvent(new CircuitBreakerEvent(this, "MANUAL_RESET", "Reset by user"));
    }

    // ── Permission record ──────────────────────────────────────────────────────

    public record Permission(boolean ok, String reason) {
        public static Permission allow()         { return new Permission(true,  null); }
        public static Permission block(String r) { return new Permission(false, r); }
        public boolean isAllowed() { return ok; }
    }

    // ── checkPermission ────────────────────────────────────────────────────────

    public Permission checkPermission(BigDecimal capital) {
        if (capital.compareTo(BigDecimal.ZERO) > 0)
            capitalSnapshot.compareAndSet(BigDecimal.ZERO, capital);

        if (!active) return Permission.block(disableReason);

        if (tradesToday.get() >= maxPerDay)
            return Permission.block("Daily trade limit: " + tradesToday.get() + "/" + maxPerDay);

        BigDecimal cap = capitalSnapshot.get();
        if (cap.compareTo(BigDecimal.ZERO) > 0) {
            double d = pct(dailyPnl.get(), cap);
            if (d <= dailyCap) {
                tripWithCloseAll("DAILY_CAP_CLOSE_ALL",
                        String.format("Daily loss %.2f%% >= cap %.1f%%", d, dailyCap));
                return Permission.block(disableReason);
            }
            double w = pct(weeklyPnl.get(), cap);
            if (w <= weeklyCap) {
                trip("WEEKLY_CAP", String.format("Weekly loss %.2f%%", w));
                return Permission.block(disableReason);
            }
            double m = pct(monthlyPnl.get(), cap);
            if (m <= monthlyCap) {
                trip("MONTHLY_CAP", String.format("Monthly loss %.2f%%", m));
                return Permission.block(disableReason);
            }
        }
        return Permission.allow();
    }

    public void recordTradeEntered() { tradesToday.incrementAndGet(); }

    // ── recordPnl — dynamic profit floor logic ────────────────────────────────

    /**
     * Records realised P&L and evaluates all account protection rules.
     *
     * synchronized: multiple trades can close at the same millisecond during a
     * market spike. Without synchronisation, two threads could both update
     * trailingFloor concurrently, with one overwriting the other's higher value.
     */
    public synchronized void recordPnl(BigDecimal pnl) {
        dailyPnl.updateAndGet(v -> v.add(pnl));
        weeklyPnl.updateAndGet(v -> v.add(pnl));
        monthlyPnl.updateAndGet(v -> v.add(pnl));

        BigDecimal cap = capitalSnapshot.get();
        if (cap.compareTo(BigDecimal.ZERO) == 0) return;

        double dailyPct = pct(dailyPnl.get(), cap);

        // Daily loss cap — hard kill
        if (active && dailyPct <= dailyCap) {
            tripWithCloseAll("DAILY_CAP_CLOSE_ALL",
                    String.format("Daily loss %.2f%% hit cap %.1f%% on close", dailyPct, dailyCap));
            return;
        }

        // Profit lock — activation
        if (!profitLockActivated
                && profitLockTriggerPct > 0
                && dailyPct >= profitLockTriggerPct) {
            profitLockActivated = true;
            // Initialise trailing floor at static minimum
            trailingFloor = profitFloorPct;
            log.info("[CB] PROFIT LOCK ACTIVATED: P&L={:.2f}% >= trigger={:.1f}%. " +
                            "Initial floor={:.2f}% (static). VIX={:.1f}",
                    dailyPct, profitLockTriggerPct, trailingFloor, getCurrentVix());
            publisher.publishEvent(new CircuitBreakerEvent(this, "PROFIT_LOCK_ACTIVATED",
                    String.format("P&L=%.2f%% hit trigger=%.1f%%. Floor initialised at %.2f%%",
                            dailyPct, profitLockTriggerPct, trailingFloor)));
        }

        // FLAW 5 FIX: Dynamic trailing floor — update on every P&L record
        if (profitLockActivated && !profitFloorTripped) {
            updateDynamicTrailingFloor(dailyPct);

            // Check if current P&L has retreated to or below the trailing floor
            if (dailyPct <= trailingFloor) {
                profitFloorTripped = true;
                tripWithCloseAll("PROFIT_FLOOR_CLOSE_ALL",
                        String.format("P&L=%.2f%% retraced to floor=%.2f%% (VIX=%.1f). Locking green day.",
                                dailyPct, trailingFloor, getCurrentVix()));
            }
        }
    }

    // ── Dynamic floor calculation (FLAW 5 FIX) ────────────────────────────────

    /**
     * Computes and updates the trailing profit floor using current VIX.
     *
     * The floor moves up as P&L rises (trailing behaviour).
     * It never moves down — max() ensures monotonic increase.
     * It never goes below profitFloorPct (static safety net).
     *
     * dailyVol = VIX / sqrt(252) / 100
     *   Converts annualised implied volatility (VIX as %) to a 1-trading-day sigma.
     *   sqrt(252) ≈ 15.87 — standard trading days per year.
     *
     * giveBack = max(minGivebackPct, dailyVol × givebackMultiplier)
     *   How much daily move to tolerate before locking profits.
     *   minGivebackPct prevents the floor from becoming unrealistically tight on
     *   ultra-low-VIX days (VIX < 10 → dailyVol < 0.63% → giveBack < 1.26%).
     *
     * dynamicFloor = currentPnlPct − giveBack
     *   The floor sits exactly one giveBack below the current P&L high-water mark.
     */
    private void updateDynamicTrailingFloor(double currentPnlPct) {
        double vix      = getCurrentVix();
        double dailyVol = vix / Math.sqrt(252.0) / 100.0 * 100.0; // result in %
        double giveBack = Math.max(minGivebackPct, dailyVol * givebackMultiplier);

        double dynamicFloor  = currentPnlPct - giveBack;
        double newFloor      = Math.max(trailingFloor, Math.max(profitFloorPct, dynamicFloor));

        if (newFloor > trailingFloor) {
            log.info("[CB] Trailing floor raised: {:.2f}% → {:.2f}% " +
                            "(P&L={:.2f}% giveBack={:.2f}% VIX={:.1f} dailyVol={:.3f}%)",
                    trailingFloor, newFloor, currentPnlPct, giveBack, vix, dailyVol);
            trailingFloor = newFloor;
        }
    }

    private double getCurrentVix() {
        try {
            return vixService.getCurrentVix();
        } catch (Exception e) {
            log.debug("[CB] VIX unavailable, using default 15.0: {}", e.getMessage());
            return 15.0; // safe default — moderate volatility assumption
        }
    }

    // ── Internal trip helpers ──────────────────────────────────────────────────

    /**
     * Hard trip: stops new entries AND publishes CLOSE_ALL event.
     * PaperTradeManagementService listens for event types ending in "CLOSE_ALL".
     */
    private void tripWithCloseAll(String type, String reason) {
        active        = false;
        disableReason = reason;
        log.warn("[CB] HARD TRIP + CLOSE ALL [{}]: {}", type, reason);
        publisher.publishEvent(new CircuitBreakerEvent(this, type, reason));
    }

    /** Soft trip: stops new entries but does NOT close existing trades. */
    private void trip(String type, String reason) {
        active        = false;
        disableReason = reason;
        log.warn("[CB] TRIPPED [{}]: {}", type, reason);
        publisher.publishEvent(new CircuitBreakerEvent(this, type, reason));
    }

    private double pct(BigDecimal pnl, BigDecimal capital) {
        return pnl.divide(capital, MathContext.DECIMAL32)
                .multiply(BigDecimal.valueOf(100)).doubleValue();
    }

    // ── Scheduled resets ───────────────────────────────────────────────────────

    @Scheduled(cron = "0 15 3 * * MON-FRI", zone = "UTC") // 08:45 IST
    public void resetDaily() {
        tradesToday.set(0);
        dailyPnl.set(BigDecimal.ZERO);
        capitalSnapshot.set(BigDecimal.ZERO);
        profitLockActivated = false;
        profitFloorTripped  = false;
        trailingFloor       = 0.0;
        active              = true;
        disableReason       = null;
        log.info("[CB] Daily reset complete");
    }

    @Scheduled(cron = "0 0 3 * * MON", zone = "UTC")
    public void resetWeekly() {
        weeklyPnl.set(BigDecimal.ZERO);
        log.info("[CB] Weekly P&L reset");
    }

    @Scheduled(cron = "0 0 3 1 * *", zone = "UTC")
    public void resetMonthly() {
        monthlyPnl.set(BigDecimal.ZERO);
        log.info("[CB] Monthly P&L reset");
    }
}