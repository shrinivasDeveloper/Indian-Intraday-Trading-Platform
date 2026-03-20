package com.trading.execution.service;

import com.trading.auth.service.AuthService;
import com.trading.domain.entity.Trade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * End of Day sequence:
 *   14:40 → Entry window closes (handled by MarketTimingService)
 *   15:00 → Force close all + cancel orders + logout + save daily summary
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EndOfDayService {

    private final TradeExecutionService  tradeExecution;
    private final AuthService            authService;

    // TradeManagementService handles force close via its own @Scheduled at 15:00
    // EndOfDayService handles summary logging + logout

    @Scheduled(cron = "0 1 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void runEodSequence() {
        log.warn("=== END OF DAY SEQUENCE STARTING ===");

        // Step 1: Log daily summary
        try {
            logDailySummary();
        } catch (Exception e) {
            log.error("Daily summary logging failed: {}", e.getMessage());
        }

        // Step 2: Logout from Zerodha
        try {
            authService.serverSideLogout();
            log.info("Zerodha logout complete");
        } catch (Exception e) {
            log.error("Zerodha logout failed: {}", e.getMessage());
        }

        log.warn("=== END OF DAY SEQUENCE COMPLETE ===");
    }

    private void logDailySummary() {
        LocalDate today = LocalDate.now();
        List<Trade> todayTrades = tradeExecution.getTodayTrades(today);

        if (todayTrades.isEmpty()) {
            log.info("EOD Summary [{}]: No trades today", today);
            return;
        }

        long wins   = todayTrades.stream()
                .filter(t -> t.getNetPnl() != null
                        && t.getNetPnl().compareTo(BigDecimal.ZERO) > 0)
                .count();
        long losses = todayTrades.size() - wins;

        BigDecimal totalPnl = todayTrades.stream()
                .filter(t -> t.getNetPnl() != null)
                .map(Trade::getNetPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Trade best = todayTrades.stream()
                .filter(t -> t.getNetPnl() != null)
                .max(java.util.Comparator.comparing(Trade::getNetPnl))
                .orElse(null);

        Trade worst = todayTrades.stream()
                .filter(t -> t.getNetPnl() != null)
                .min(java.util.Comparator.comparing(Trade::getNetPnl))
                .orElse(null);

        log.info("=== DAILY SUMMARY [{}] ===", today);
        log.info("Trades: {} | Wins: {} | Losses: {} | Net P&L: ₹{}",
                todayTrades.size(), wins, losses, totalPnl);
        if (best  != null)
            log.info("Best trade:  {} ₹{}", best.getTradingSymbol(),  best.getNetPnl());
        if (worst != null)
            log.info("Worst trade: {} ₹{}", worst.getTradingSymbol(), worst.getNetPnl());
        log.info("==============================");
    }
}