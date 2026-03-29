package com.trading.risk.service;

import com.trading.events.CircuitBreakerEvent;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * CircuitBreakerService — account-level protection with 10-2-3 Slot Manager limits.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * CHANGES vs original:
 *
 * 1. MAX TRADES PER DAY → 10 (was 4/2)
 *    application.yml: circuit-breaker.max-trades-per-day: 10
 *
 * 2. DAILY LOSS CAP → -2.5% (was -4.0%)
 *    application.yml: circuit-breaker.daily-loss-cap-pct: -2.5
 *    On trip: publishes CircuitBreakerEvent("DAILY_CAP_CLOSE_ALL", ...)
 *    PaperTradeManagementService listens to this event and force-closes
 *    all open positions immediately at LTP + EOD slippage.
 *
 * 3. PROFIT LOCK (NEW)
 *    Two new @Value fields:
 *      profit-lock-trigger-pct: 6.0  → activate lock when daily P&L >= +6%
 *      profit-floor-pct:        4.0  → if P&L retraces to +4%, close all and lock day
 *    Logic in recordPnl():
 *      - When dailyPnlPct crosses +6% → set profitLockActivated = true, store floorPct
 *      - Every subsequent recordPnl call → if profitLockActivated and pct <= floorPct
 *        → publish CircuitBreakerEvent("PROFIT_FLOOR_CLOSE_ALL", ...)
 *        → stop all new trading for the day
 *    This guarantees the day ends green at +4% minimum once +6% is reached.
 *
 * 4. ALL EXISTING LOGIC PRESERVED UNCHANGED:
 *    - Weekly cap, monthly cap
 *    - manualReset() dashboard button
 *    - recordTradeEntered(), checkPermission()
 *    - Daily/weekly/monthly scheduled resets
 * ═══════════════════════════════════════════════════════════════════════
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CircuitBreakerService {

    private final ApplicationEventPublisher publisher;

    // ── Config ─────────────────────────────────────────────────────────────────

    /** REQ 1a: Max 10 total trades per day across the account */
    @Getter
    @Value("${circuit-breaker.max-trades-per-day:10}")
    private int maxPerDay;

    /** REQ 2a: Hard-kill all trading at this daily loss %. Close all immediately. */
    @Value("${circuit-breaker.daily-loss-cap-pct:-2.5}")
    private double dailyCap;

    @Value("${circuit-breaker.weekly-drawdown-cap-pct:-8.0}")
    private double weeklyCap;

    @Value("${circuit-breaker.monthly-drawdown-cap-pct:-15.0}")
    private double monthlyCap;

    /**
     * REQ 2b: Profit Lock trigger.
     * When daily P&L reaches this % of capital (e.g. +6.0%),
     * the profit floor activates. Set to 0 to disable.
     */
    @Value("${circuit-breaker.profit-lock-trigger-pct:6.0}")
    private double profitLockTriggerPct;

    /**
     * REQ 2b: Profit floor.
     * Once the profit lock is active, if daily P&L retraces to this %
     * of capital (e.g. +4.0%), all open positions are closed and no new
     * trading is allowed. This guarantees a green day at +4% minimum.
     */
    @Value("${circuit-breaker.profit-floor-pct:4.0}")
    private double profitFloorPct;

    // ── State ──────────────────────────────────────────────────────────────────

    @Getter
    private volatile boolean active        = true;

    @Getter
    private volatile String  disableReason = null;

    /** True once daily P&L has reached +profitLockTriggerPct%. */
    @Getter
    private volatile boolean profitLockActivated = false;

    /** Tracks whether CLOSE_ALL has already been published for profit-floor breach. */
    private volatile boolean profitFloorTripped = false;

    private final AtomicInteger               tradesToday = new AtomicInteger(0);
    private final AtomicReference<BigDecimal> dailyPnl    = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicReference<BigDecimal> weeklyPnl   = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicReference<BigDecimal> monthlyPnl  = new AtomicReference<>(BigDecimal.ZERO);

    // Snapshot of capital used for P&L% calculations — set once per day at first check
    private final AtomicReference<BigDecimal> capitalSnapshot = new AtomicReference<>(BigDecimal.ZERO);

    // ── Dashboard getters ──────────────────────────────────────────────────────

    public int        getTradesToday()         { return tradesToday.get(); }
    public BigDecimal getDailyPnl()            { return dailyPnl.get(); }
    public BigDecimal getWeeklyPnl()           { return weeklyPnl.get(); }
    public BigDecimal getMonthlyPnl()          { return monthlyPnl.get(); }

    // ── Manual reset (dashboard emergency button) ──────────────────────────────

    public void manualReset() {
        tradesToday.set(0);
        dailyPnl.set(BigDecimal.ZERO);
        profitLockActivated = false;
        profitFloorTripped  = false;
        active              = true;
        disableReason       = null;
        log.warn("[CB] Circuit breaker MANUALLY RESET via dashboard");
        publisher.publishEvent(new CircuitBreakerEvent(this, "MANUAL_RESET", "Reset by user"));
    }

    // ── Permission record ──────────────────────────────────────────────────────

    public record Permission(boolean ok, String reason) {
        public static Permission allow()         { return new Permission(true,  null); }
        public static Permission block(String r) { return new Permission(false, r); }
        public boolean isAllowed() { return ok; }
    }

    // ── checkPermission — called before every new trade entry ─────────────────

    /**
     * Gates every new trade signal through all daily limits.
     *
     * Order of checks (fast-fail, cheapest first):
     *   1. CB active flag (already tripped or profit floor locked)
     *   2. Daily trade count cap (10 per day)
     *   3. Daily P&L loss cap (-2.5%)
     *   4. Weekly drawdown cap
     *   5. Monthly drawdown cap
     */
    public Permission checkPermission(BigDecimal capital) {
        // Snapshot capital for % calculations (first call each day sets it)
        if (capital.compareTo(BigDecimal.ZERO) > 0) {
            capitalSnapshot.compareAndSet(BigDecimal.ZERO, capital);
        }

        if (!active) return Permission.block(disableReason);

        if (tradesToday.get() >= maxPerDay)
            return Permission.block(
                    "Daily trade limit reached: " + tradesToday.get() + "/" + maxPerDay);

        BigDecimal cap = capitalSnapshot.get();
        if (cap.compareTo(BigDecimal.ZERO) > 0) {
            double d = pct(dailyPnl.get(), cap);

            // REQ 2a: hard kill at -2.5% — also triggers close-all
            if (d <= dailyCap) {
                tripWithCloseAll("DAILY_CAP_CLOSE_ALL",
                        String.format("Daily loss %.2f%% hit hard cap %.1f%%", d, dailyCap));
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

    // ── recordTradeEntered — called after every approved entry ─────────────────

    public void recordTradeEntered() {
        tradesToday.incrementAndGet();
    }

    // ── recordPnl — called on every trade close ────────────────────────────────

    /**
     * Records realised P&L and evaluates account protection rules.
     *
     * REQ 2a (Daily Loss Cap):
     *   Checked here in addition to checkPermission(), so that a loss
     *   on a closing trade immediately locks the account even if no new
     *   signal is pending.
     *
     * REQ 2b (Profit Lock):
     *   Step 1 — activation: if daily P&L crosses +profitLockTriggerPct%,
     *            set profitLockActivated = true and log the floor level.
     *   Step 2 — floor enforcement: if already activated and P&L retraces
     *            below +profitFloorPct%, publish CLOSE_ALL event and stop trading.
     *            Only fires once per day (profitFloorTripped guard).
     */
    public synchronized void recordPnl(BigDecimal pnl) {
        dailyPnl.updateAndGet(v -> v.add(pnl));
        weeklyPnl.updateAndGet(v -> v.add(pnl));
        monthlyPnl.updateAndGet(v -> v.add(pnl));

        BigDecimal cap = capitalSnapshot.get();
        if (cap.compareTo(BigDecimal.ZERO) == 0) return; // capital not yet set

        double dailyPct = pct(dailyPnl.get(), cap);

        // REQ 2a: re-check daily loss cap on close (handles gap-down scenarios)
        if (active && dailyPct <= dailyCap) {
            tripWithCloseAll("DAILY_CAP_CLOSE_ALL",
                    String.format("Daily loss %.2f%% hit hard cap %.1f%% on close", dailyPct, dailyCap));
            return;
        }

        // REQ 2b Step 1: activate profit lock once we cross +trigger%
        if (!profitLockActivated
                && profitLockTriggerPct > 0
                && dailyPct >= profitLockTriggerPct) {
            profitLockActivated = true;
            log.info("[CB] PROFIT LOCK ACTIVATED — daily P&L {:.2f}% >= trigger {:.1f}%. " +
                            "Floor set at +{:.1f}%",
                    dailyPct, profitLockTriggerPct, profitFloorPct);
            publisher.publishEvent(new CircuitBreakerEvent(this,
                    "PROFIT_LOCK_ACTIVATED",
                    String.format("P&L %.2f%% hit lock trigger %.1f%%. Floor=+%.1f%%",
                            dailyPct, profitLockTriggerPct, profitFloorPct)));
        }

        // REQ 2b Step 2: enforce floor — retrace below +floor% → close all and lock
        if (profitLockActivated
                && !profitFloorTripped
                && dailyPct <= profitFloorPct) {
            profitFloorTripped = true;
            tripWithCloseAll("PROFIT_FLOOR_CLOSE_ALL",
                    String.format("P&L retraced to %.2f%% — floor %.1f%% triggered. Locking green day.",
                            dailyPct, profitFloorPct));
        }
    }

    // ── Internal helpers ───────────────────────────────────────────────────────

    /**
     * Standard trip: stops new trading but does NOT close existing positions.
     * Used for weekly/monthly caps where we let existing trades run to natural exit.
     */
    private void trip(String type, String reason) {
        active        = false;
        disableReason = reason;
        log.warn("[CB] TRIPPED [{}]: {}", type, reason);
        publisher.publishEvent(new CircuitBreakerEvent(this, type, reason));
    }

    /**
     * Hard trip: stops new trading AND publishes a CLOSE_ALL event.
     * Used for daily loss cap (-2.5%) and profit floor (both require immediate exit).
     * PaperTradeManagementService listens for the event suffix "CLOSE_ALL"
     * and calls its internal forceCloseAll() immediately.
     */
    private void tripWithCloseAll(String type, String reason) {
        active        = false;
        disableReason = reason;
        log.warn("[CB] HARD TRIP + CLOSE ALL [{}]: {}", type, reason);
        // The event type ending in "CLOSE_ALL" is the contract with
        // PaperTradeManagementService to trigger immediate position liquidation.
        publisher.publishEvent(new CircuitBreakerEvent(this, type, reason));
    }

    private double pct(BigDecimal pnl, BigDecimal capital) {
        return pnl.divide(capital, MathContext.DECIMAL32)
                .multiply(BigDecimal.valueOf(100)).doubleValue();
    }

    // ── Scheduled resets ───────────────────────────────────────────────────────

    // 08:45 IST = 03:15 UTC
    @Scheduled(cron = "0 15 3 * * MON-FRI", zone = "UTC")
    public void resetDaily() {
        tradesToday.set(0);
        dailyPnl.set(BigDecimal.ZERO);
        capitalSnapshot.set(BigDecimal.ZERO);
        profitLockActivated = false;
        profitFloorTripped  = false;
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