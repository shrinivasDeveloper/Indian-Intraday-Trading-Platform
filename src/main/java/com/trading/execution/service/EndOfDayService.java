package com.trading.execution.service;

import com.trading.auth.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * End of Day sequence:
 *   14:40 → Entry window closes (handled by MarketTimingService)
 *   15:00 → Logout from Zerodha
 *
 * CLEANUP: removed the tradeExecution dependency and logDailySummary() —
 * TradeExecutionService (and the LIVE-mode trades it tracked) served only
 * the other strategies (SCPS/ORB/HighRR/SMC), now deleted. AI and News
 * track their own daily summary independently (AiNewsCapitalLedger,
 * AiLearningEngine) — this service's remaining job, the Zerodha logout
 * step, is generic and stays regardless of which strategies are active.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EndOfDayService {

    private final AuthService authService;

    @Scheduled(cron = "0 1 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void runEodSequence() {
        log.warn("=== END OF DAY SEQUENCE STARTING ===");

        try {
            authService.serverSideLogout();
            log.info("Zerodha logout complete");
        } catch (Exception e) {
            log.error("Zerodha logout failed: {}", e.getMessage());
        }

        log.warn("=== END OF DAY SEQUENCE COMPLETE ===");
    }
}