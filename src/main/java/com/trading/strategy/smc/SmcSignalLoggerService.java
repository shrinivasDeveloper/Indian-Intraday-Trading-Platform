package com.trading.strategy.smc;

// SmcSignalLoggerService listens to SmcSignalEvent — SMC-owned event, no SCPS dependency
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SmcSignalLoggerService
 * ─────────────────────────────────────────────────────────────────────────────
 * Listens to SmcSignalEvent and logs SMC_INSTITUTIONAL_V1
 * signals to the application log for analytics and performance tracking.
 *
 * NO database dependency — logs to SLF4J only.
 * The trade itself is persisted by PaperTradeExecutionService via the
 * existing Trade entity, which already covers all required trade data.
 *
 * Confirmed SmcSignalEvent fields (from actual source):
 *   getTarget1()          → BigDecimal  (NOT getTarget())
 *   getProbabilityScore() → double      (NOT int)
 *   getTotalScore()       → int
 *
 * Scope: SMC_INSTITUTIONAL_V1 signals ONLY. All other strategies are ignored.
 * Zero impact on existing strategies or execution pipeline.
 * ─────────────────────────────────────────────────────────────────────────────
 */
@Service
@Slf4j
public class SmcSignalLoggerService {

    private static final String SMC_STRATEGY = "SMC_INSTITUTIONAL_V1";

    private final AtomicInteger signalCountToday = new AtomicInteger(0);

    @EventListener
    @Async("tradingExecutor")
    public void onSignal(SmcSignalEvent event) {
        // SmcSignalEvent is only fired by SMC_INSTITUTIONAL_V1 — no guard needed

        try {
            String     symbol     = event.getTradingSymbol();
            String     direction  = event.getDirection() != null
                    ? event.getDirection().name() : "UNKNOWN";
            BigDecimal entry      = event.getEntryPrice();
            BigDecimal sl         = event.getStopLoss();
            BigDecimal target1    = event.getTarget1();           // confirmed: getTarget1()
            int        totalScore = event.getTotalScore();         // confirmed: int
            int        confidence = (int) Math.round((int) Math.round(event.getProbabilityScore())); // double → int

            // Compute RR from entry/sl/target1
            double rr = 0.0;
            if (entry != null && sl != null && target1 != null) {
                double reward = target1.subtract(entry).abs().doubleValue();
                double risk   = entry.subtract(sl).abs().doubleValue();
                if (risk > 0) rr = reward / risk;
            }

            signalCountToday.incrementAndGet();

            log.info("[SMC-LOGGER] Signal #{} | {} {} | entry={} sl={} T1={} | RR={} score={} conf={} | setup={}",
                    signalCountToday.get(),
                    symbol, direction,
                    entry, sl, target1,
                    String.format("%.2f", rr),
                    totalScore, confidence,
                    event.getSetupType());

        } catch (Exception e) {
            // Non-critical: never let logging failure affect signal pipeline
            log.debug("[SMC-LOGGER] Failed to log signal for {}: {}",
                    event.getTradingSymbol(), e.getMessage());
        }
    }

    /** Returns number of SMC signals fired today. Used by DashboardController. */
    public int getSignalCountToday() {
        return signalCountToday.get();
    }

    /** Reset daily counter — called from SmcInstitutionalStrategyEngine.dailyReset(). */
    public void resetDailyCount() {
        signalCountToday.set(0);
    }
}