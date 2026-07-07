package com.trading.herozero.scheduler;

import com.trading.herozero.config.HeroZeroConfig;
import com.trading.herozero.repository.HeroZeroTradeRepository;
import com.trading.herozero.service.HeroZeroTradingService;
import com.trading.herozero.util.HeroZeroHolidayChecker;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HeroZeroScheduler - the ONLY scheduler for this strategy.
 *
 * INDEPENDENCE: runs on its own dedicated thread pool bean
 * (heroZeroTaskScheduler, defined in HeroZeroSchedulerConfig) -
 * completely separate from every other strategy's scheduler thread.
 * Never shares a thread with AI, News, or Swing's schedulers, so a
 * slow entry/exit sequence here can never delay or block any other
 * module's time-critical work, and vice versa.
 */
@Component
@Slf4j
public class HeroZeroScheduler {

    private static final Set<String> INDEXES = Set.of(
            "NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX");

    private final HeroZeroConfig config;
    private final HeroZeroTradingService service;
    private final HeroZeroTradeRepository repo;

    // Once-per-day guards, one per index, so each index's entry only
    // ever fires once even if the scheduler tick overlaps the entry
    // window multiple times.
    private final java.util.Map<String, AtomicBoolean> entryFiredToday = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicBoolean exitFiredToday = new AtomicBoolean(false);
    // FIX (same confirmed critical timezone bug found platform-wide -
    // bare LocalDate.now()/LocalTime.now() use the JVM default zone,
    // UTC on Railway, not India time. Hero-or-Zero's 2:30/3:10 PM
    // entry/exit timing depends entirely on this being correctly IST.)
    private static final java.time.ZoneId IST = java.time.ZoneId.of("Asia/Kolkata");
    private java.time.LocalDate lastResetDate = java.time.LocalDate.now(IST).minusDays(1);

    public HeroZeroScheduler(HeroZeroConfig config, HeroZeroTradingService service,
                             HeroZeroTradeRepository repo) {
        this.config = config;
        this.service = service;
        this.repo = repo;
        for (String idx : INDEXES) entryFiredToday.put(idx, new AtomicBoolean(false));
    }

    /**
     * Restart/crash recovery, per spec: "Load all ACTIVE trades. Resume
     * monitoring automatically. Never duplicate BUY or SELL orders."
     * Since the actual exit logic is time-triggered (not price-
     * triggered), "resuming monitoring" here means: if the app restarts
     * AFTER 3:10 PM with trades still ACTIVE (exit never completed
     * before a crash), immediately force-exit them now rather than
     * waiting for tomorrow's scheduler tick.
     */
    // FIX (found during production-readiness cross-check, a real and
    // serious bug): recoverOnStartup() previously called
    // service.forceExitAll() directly without ever setting
    // exitFiredToday - meaning if the app restarted PAST exit time
    // (exactly the condition that triggers this recovery path), the
    // very next scheduled tick() (within 30 seconds) would ALSO see
    // "past exit time" as true and call forceExitAll() a SECOND time,
    // concurrently, while the first call might still be mid-poll for
    // fill confirmation (up to 30 seconds per leg). This could have
    // resulted in a genuine DUPLICATE SELL ORDER for the same position
    // - directly violating the spec's explicit "Prevent Duplicate SELL
    // Orders" requirement. Fixed with two layers: the shared
    // exitFiredToday flag (so tick() never re-triggers after recovery
    // already did), AND a re-entrancy guard around the actual exit
    // execution itself (so even a genuine double-call from any future
    // code path can never run forceExitAll() concurrently with itself).
    private final AtomicBoolean exitInProgress = new AtomicBoolean(false);

    @PostConstruct
    public void recoverOnStartup() {
        if (!config.isEnabled()) {
            log.info("[HERO-ZERO] Strategy disabled (hero-zero.enabled=false) - scheduler idle");
            return;
        }
        var active = repo.findActive();
        log.info("[HERO-ZERO] Restart recovery: found {} ACTIVE trade(s) in database", active.size());
        if (active.isEmpty()) return;

        if (HeroZeroHolidayChecker.isMarketClosedToday()
                || LocalTime.now(IST).isAfter(config.getExitTime())) {
            log.warn("[HERO-ZERO] Restart recovery: {} ACTIVE trade(s) found past the mandatory " +
                    "exit time or on a non-trading day - force-exiting immediately to avoid " +
                    "carrying an unintended overnight position", active.size());
            exitFiredToday.set(true); // FIX: mark BEFORE exiting, so tick() can never re-trigger
            safeForceExitAll();
        } else {
            log.info("[HERO-ZERO] Restart recovery: {} ACTIVE trade(s) found, still within " +
                            "today's trading window - normal scheduled exit at {} will handle them",
                    active.size(), config.getExitTime());
        }
    }

    /** Re-entrancy-guarded wrapper around service.forceExitAll() - the
     *  second layer of defense against any concurrent double-exit call,
     *  regardless of which caller (recovery or scheduled tick) triggers it. */
    private void safeForceExitAll() {
        if (!exitInProgress.compareAndSet(false, true)) {
            log.warn("[HERO-ZERO] Exit already in progress - skipping concurrent duplicate call");
            return;
        }
        try {
            service.forceExitAll();
        } finally {
            exitInProgress.set(false);
        }
    }

    @Scheduled(fixedRate = 30_000, scheduler = "heroZeroTaskScheduler")
    public void tick() {
        if (!config.isEnabled()) return;

        java.time.LocalDate today = java.time.LocalDate.now(IST);
        if (!today.equals(lastResetDate)) {
            entryFiredToday.values().forEach(b -> b.set(false));
            exitFiredToday.set(false);
            lastResetDate = today;
        }

        if (HeroZeroHolidayChecker.isMarketClosedToday()) {
            return; // weekend/holiday - nothing to do today
        }

        LocalTime now = LocalTime.now(IST);

        // ENTRY WINDOW - fire once per index, at or after entry time
        if (!now.isBefore(config.getEntryTime())) {
            for (String index : INDEXES) {
                AtomicBoolean fired = entryFiredToday.get(index);
                if (fired.compareAndSet(false, true)) {
                    log.info("[HERO-ZERO] Entry window reached for {} - attempting entry", index);
                    try {
                        service.attemptEntry(index);
                    } catch (Exception e) {
                        log.error("[HERO-ZERO] Unexpected error during {} entry attempt: {}",
                                index, e.getMessage(), e);
                    }
                }
            }
        }

        // EXIT WINDOW - mandatory, fires once, regardless of P&L
        if (!now.isBefore(config.getExitTime()) && exitFiredToday.compareAndSet(false, true)) {
            log.info("[HERO-ZERO] Mandatory exit time reached ({}) - force-exiting all active trades",
                    config.getExitTime());
            try {
                safeForceExitAll();
            } catch (Exception e) {
                log.error("[HERO-ZERO] Unexpected error during mandatory exit: {}", e.getMessage(), e);
            }
        }
    }
}