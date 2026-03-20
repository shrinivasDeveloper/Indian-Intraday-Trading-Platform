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

@Service
@Slf4j
@RequiredArgsConstructor
public class CircuitBreakerService {

    private final ApplicationEventPublisher publisher;

    // ── Config (unchanged from original) ─────────────────────────────
    @Getter
    @Value("${circuit-breaker.max-trades-per-day:2}")
    private int maxPerDay;

    @Value("${circuit-breaker.daily-loss-cap-pct:-3.0}")
    private double dailyCap;

    @Value("${circuit-breaker.weekly-drawdown-cap-pct:-6.0}")
    private double weeklyCap;

    @Value("${circuit-breaker.monthly-drawdown-cap-pct:-12.0}")
    private double monthlyCap;

    // ── State (unchanged from original) ──────────────────────────────
    @Getter
    private volatile boolean active  = true;

    @Getter
    private volatile String  disableReason = null;

    private final AtomicInteger              tradesToday = new AtomicInteger(0);
    private final AtomicReference<BigDecimal> dailyPnl   = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicReference<BigDecimal> weeklyPnl  = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicReference<BigDecimal> monthlyPnl = new AtomicReference<>(BigDecimal.ZERO);

    // ── NEW: getters for dashboard (only additions) ───────────────────
    public int        getTradesToday()  { return tradesToday.get(); }
    public BigDecimal getDailyPnl()     { return dailyPnl.get(); }
    public BigDecimal getWeeklyPnl()    { return weeklyPnl.get(); }
    public BigDecimal getMonthlyPnl()   { return monthlyPnl.get(); }

    // NEW: manual reset for dashboard emergency button
    public void manualReset() {
        tradesToday.set(0);
        dailyPnl.set(BigDecimal.ZERO);
        active = true;
        disableReason = null;
        log.warn("Circuit breaker MANUALLY RESET via dashboard");
        publisher.publishEvent(new CircuitBreakerEvent(this, "MANUAL_RESET", "Reset by user"));
    }

    // ── Everything below is UNCHANGED from your original ─────────────

    public record Permission(boolean ok, String reason) {
        public static Permission allow()         { return new Permission(true,  null); }
        public static Permission block(String r) { return new Permission(false, r); }
        public boolean isAllowed() { return ok; }
    }

    public Permission checkPermission(BigDecimal capital) {
        if (!active) return Permission.block(disableReason);
        if (tradesToday.get() >= maxPerDay)
            return Permission.block("Daily trade limit " + tradesToday.get() + "/" + maxPerDay);

        if (capital.compareTo(BigDecimal.ZERO) > 0) {
            double d = pct(dailyPnl.get(), capital);
            if (d <= dailyCap) {
                trip("DAILY_CAP", "Daily loss " + d + "%");
                return Permission.block(disableReason);
            }
            double w = pct(weeklyPnl.get(), capital);
            if (w <= weeklyCap) {
                trip("WEEKLY_CAP", "Weekly loss " + w + "%");
                return Permission.block(disableReason);
            }
            double m = pct(monthlyPnl.get(), capital);
            if (m <= monthlyCap) {
                trip("MONTHLY_CAP", "Monthly loss " + m + "%");
                return Permission.block(disableReason);
            }
        }
        return Permission.allow();
    }

    public void recordTradeEntered() { tradesToday.incrementAndGet(); }

    public void recordPnl(BigDecimal pnl) {
        dailyPnl.updateAndGet(v -> v.add(pnl));
        weeklyPnl.updateAndGet(v -> v.add(pnl));
        monthlyPnl.updateAndGet(v -> v.add(pnl));
    }

    // 08:45 IST = 03:15 UTC
    @Scheduled(cron = "0 15 3 * * MON-FRI", zone = "UTC")
    public void resetDaily() {
        tradesToday.set(0);
        dailyPnl.set(BigDecimal.ZERO);
        active = true;
        disableReason = null;
        log.info("Circuit breaker daily reset");
    }

    @Scheduled(cron = "0 0 3 * * MON", zone = "UTC")
    public void resetWeekly() { weeklyPnl.set(BigDecimal.ZERO); }

    @Scheduled(cron = "0 0 3 1 * *", zone = "UTC")
    public void resetMonthly() { monthlyPnl.set(BigDecimal.ZERO); }

//    public boolean isActive() { return active; }

    private void trip(String type, String reason) {
        active = false;
        disableReason = reason;
        log.warn("CIRCUIT BREAKER TRIPPED [{}]: {}", type, reason);
        publisher.publishEvent(new CircuitBreakerEvent(this, type, reason));
    }

    private double pct(BigDecimal pnl, BigDecimal capital) {
        return pnl.divide(capital, MathContext.DECIMAL32)
                .multiply(BigDecimal.valueOf(100)).doubleValue();
    }
}