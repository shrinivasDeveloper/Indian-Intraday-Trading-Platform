package com.trading.dualentry.scheduler;

import com.trading.dualentry.config.DualEntryConfig;
import com.trading.dualentry.domain.DualEntryTrade;
import com.trading.dualentry.exception.DualEntryStrategyException;
import com.trading.dualentry.service.DualEntryCandleService;
import com.trading.dualentry.service.DualEntryTradingService;
import com.trading.momentumstockofday.domain.MomentumCandidate;
import com.trading.momentumstockofday.service.MomentumSelectionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * DualEntryScheduler — own, fully isolated scheduler for the new
 * Breakout + Pullback strategy (per explicit user request).
 *
 * OWN state entirely: own candidate list, own active-trade reference,
 * own daily trade counter, own traded-symbols set, own lock. Zero
 * shared mutable state with MomentumScheduler. The ONLY thing reused
 * is MomentumSelectionService.selectCandidates() - stock selection,
 * explicitly "same as momentum" per the request - which is itself a
 * pure, stateless, side-effect-free computation each time it's called
 * (reads live sector/price data, returns a fresh list; keeps no
 * per-caller state), so calling it from two independent schedulers
 * has zero interaction risk between them.
 */
@Component
@Slf4j
public class DualEntryScheduler {

    private final DualEntryConfig config;
    private final MomentumSelectionService selectionService;
    private final DualEntryCandleService candleService;
    private final DualEntryTradingService tradingService;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private final ReentrantLock tickLock = new ReentrantLock();

    private volatile List<MomentumCandidate> todaysCandidates = List.of();
    private final AtomicReference<DualEntryTrade> activeTrade = new AtomicReference<>();
    private final AtomicInteger tradesToday = new AtomicInteger(0);
    private final Set<String> tradedSymbolsToday = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean scanDoneToday = new AtomicBoolean(false);
    private volatile LocalDate lastResetDate = LocalDate.now(IST);

    public DualEntryScheduler(DualEntryConfig config, MomentumSelectionService selectionService,
                              DualEntryCandleService candleService, DualEntryTradingService tradingService) {
        this.config = config;
        this.selectionService = selectionService;
        this.candleService = candleService;
        this.tradingService = tradingService;
    }

    @Scheduled(fixedRate = 30000)
    public void tick() {
        if (!config.isEnabled()) return;
        if (!tickLock.tryLock()) return;
        try {
            LocalDate today = LocalDate.now(IST);
            if (!today.equals(lastResetDate)) {
                todaysCandidates = List.of();
                activeTrade.set(null);
                tradesToday.set(0);
                tradedSymbolsToday.clear();
                scanDoneToday.set(false);
                lastResetDate = today;
                log.info("[DUAL-ENTRY-SCHEDULER] Daily reset complete");
            }

            LocalTime now = LocalTime.now(IST);
            if (now.isAfter(config.getForceExitTime())) {
                DualEntryTrade active = activeTrade.get();
                if (active != null) {
                    boolean exited = tradingService.exitTrade(active, "FORCE_EXIT_EOD");
                    if (exited) activeTrade.set(null);
                    else log.warn("[DUAL-ENTRY-SCHEDULER] EOD force-exit failed, retrying next cycle");
                }
                return;
            }

            if (!scanDoneToday.get() && !now.isBefore(config.getSelectionTime())) {
                todaysCandidates = selectionService.selectCandidates();
                scanDoneToday.set(true);
                log.info("[DUAL-ENTRY-SCHEDULER] Selection complete - {} candidate(s)", todaysCandidates.size());
                return;
            }

            DualEntryTrade active = activeTrade.get();
            if (active != null) {
                DualEntryTrade updated = tradingService.monitorActiveTrade(active);
                activeTrade.set(updated);
                return;
            }

            if (tradesToday.get() >= config.getMaxTradesPerDay()) return;
            if (todaysCandidates.isEmpty()) return;

            for (MomentumCandidate candidate : todaysCandidates) {
                if (tradedSymbolsToday.contains(candidate.getSymbol())) continue;

                var breakout = candleService.evaluateBreakout(candidate);
                if (breakout.validConsolidation() && breakout.breakoutTriggered()) {
                    try {
                        DualEntryTrade trade = tradingService.enterBreakout(candidate,
                                breakout.consolidationHigh(), breakout.consolidationLow());
                        activeTrade.set(trade);
                        tradesToday.incrementAndGet();
                        tradedSymbolsToday.add(candidate.getSymbol());
                        log.info("[DUAL-ENTRY-SCHEDULER] BREAKOUT trade entered: {}", candidate.getSymbol());
                    } catch (DualEntryStrategyException e) {
                        log.info("[DUAL-ENTRY-SCHEDULER] Breakout entry rejected for {}: {}",
                                candidate.getSymbol(), e.getMessage());
                        continue;
                    }
                    return;
                }

                var pullback = candleService.evaluatePullback(candidate);
                if (pullback.triggered()) {
                    try {
                        DualEntryTrade trade = tradingService.enterPullback(candidate,
                                pullback.level(), pullback.dailyAtr(), pullback.direction());
                        activeTrade.set(trade);
                        tradesToday.incrementAndGet();
                        tradedSymbolsToday.add(candidate.getSymbol());
                        log.info("[DUAL-ENTRY-SCHEDULER] PULLBACK trade entered: {}", candidate.getSymbol());
                    } catch (DualEntryStrategyException e) {
                        log.info("[DUAL-ENTRY-SCHEDULER] Pullback entry rejected for {}: {}",
                                candidate.getSymbol(), e.getMessage());
                        continue;
                    }
                    return;
                }
            }
        } finally {
            tickLock.unlock();
        }
    }
}