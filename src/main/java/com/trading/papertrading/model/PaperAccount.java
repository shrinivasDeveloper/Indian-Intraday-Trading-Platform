package com.trading.papertrading.model;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Virtual paper trading account — singleton Spring bean.
 * Shared between PaperTradeExecutionService and PaperTradeManagementService.
 * Thread-safe. Reset daily at 8:45 IST.
 *
 * FIXES vs original:
 *
 *   FIX 1 — SEPARATE CAPITAL CONFIG KEY
 *     Original read @Value("${trading.capital:100000}") — the same key as live.
 *     Now reads @Value("${paper-trading.initial-capital:${trading.capital:100000}}")
 *     This means:
 *       - If paper-trading.initial-capital is set in yml → uses that value.
 *       - If not set → falls back to trading.capital → no breaking change.
 *     You can now set a different virtual capital for paper vs live:
 *       paper-trading:
 *         initial-capital: 500000   # ₹5L paper account
 *       trading:
 *         capital: 100000           # ₹1L live account
 *
 *   FIX 2 — applyPartialPnl() METHOD (new)
 *     Called by PaperTradeManagementService.handlePartialExit() to credit
 *     the P&L for the exited partial lot immediately.
 *     Does NOT increment dailyTrades / totalTrades counters — only full
 *     closeTrade() calls count as a trade (matching live semantics).
 *     This fixes the bug where partial exit profits were invisible on the
 *     dashboard until the remaining position fully closed.
 */
@Component
@Slf4j
public class PaperAccount {

    // FIX 1: Falls back to trading.capital if paper-specific key not set.
    // This makes the change fully backward-compatible with existing yml files.
    @Value("${paper-trading.initial-capital:${trading.capital:100000}}")
    private BigDecimal initialCapital;

    private final AtomicReference<BigDecimal> capital = new AtomicReference<>();

    // ── Daily stats — reset at 8:45 IST ───────────────────────────────────────
    private volatile int        dailyTrades     = 0;
    private volatile int        dailyWins       = 0;
    private volatile double     dailyGrossWin   = 0;
    private volatile double     dailyGrossLoss  = 0;
    private volatile BigDecimal dailyPnl        = BigDecimal.ZERO;

    // ── Cumulative stats — never reset ─────────────────────────────────────────
    private volatile int        totalTrades     = 0;
    private volatile int        totalWins       = 0;
    private volatile double     totalGrossWin   = 0;
    private volatile double     totalGrossLoss  = 0;
    private volatile BigDecimal totalPnl        = BigDecimal.ZERO;
    private volatile BigDecimal peakCapital;
    private volatile BigDecimal maxDrawdownRs   = BigDecimal.ZERO;
    private volatile BigDecimal maxDrawdown     = BigDecimal.ZERO;

    private void ensureInit() {
        capital.compareAndSet(null, initialCapital);
        if (peakCapital == null) peakCapital = initialCapital;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // applyPnl — called on FULL trade close
    // Increments trade counters, updates capital, drawdown.
    // ══════════════════════════════════════════════════════════════════════════

    public synchronized void applyPnl(BigDecimal netPnl) {
        ensureInit();
        capital.updateAndGet(c -> c.add(netPnl));

        dailyPnl   = dailyPnl.add(netPnl);
        totalPnl   = totalPnl.add(netPnl);
        dailyTrades++;
        totalTrades++;

        double pnlD = netPnl.doubleValue();
        if (pnlD > 0) {
            dailyWins++;
            totalWins++;
            dailyGrossWin  += pnlD;
            totalGrossWin  += pnlD;
        } else {
            dailyGrossLoss += Math.abs(pnlD);
            totalGrossLoss += Math.abs(pnlD);
        }

        updateDrawdown();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // FIX 2: applyPartialPnl — called on PARTIAL exit
    //
    // Credits the capital and P&L for the exited lot immediately, but does NOT
    // increment dailyTrades / totalTrades — those only fire on a full close.
    //
    // This is important for accurate daily P&L on the dashboard:
    // a trade that exits 50% at 2R and holds the rest should show
    // the 2R profit in real time, not wait until the full position closes.
    // ══════════════════════════════════════════════════════════════════════════

    public synchronized void applyPartialPnl(BigDecimal netPartialPnl) {
        ensureInit();
        capital.updateAndGet(c -> c.add(netPartialPnl));

        // Credit to P&L totals (capital and unrealised tracking)
        dailyPnl = dailyPnl.add(netPartialPnl);
        totalPnl = totalPnl.add(netPartialPnl);

        // Update win/loss gross buckets for accurate profit factor calculation
        double pnlD = netPartialPnl.doubleValue();
        if (pnlD > 0) {
            dailyGrossWin  += pnlD;
            totalGrossWin  += pnlD;
        } else {
            dailyGrossLoss += Math.abs(pnlD);
            totalGrossLoss += Math.abs(pnlD);
        }

        updateDrawdown();

        log.debug("[PAPER] Partial P&L credited: {} — capital now {}",
                String.format("%.2f", pnlD), getCapital());
    }

    private void updateDrawdown() {
        BigDecimal cur = capital.get();
        if (cur.compareTo(peakCapital) > 0) peakCapital = cur;
        BigDecimal dd = peakCapital.subtract(cur);
        if (dd.compareTo(maxDrawdown) > 0) {
            maxDrawdown   = dd;
            maxDrawdownRs = dd;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Daily reset at 8:45 IST — resets daily stats, not cumulative
    // ══════════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 45 8 * * MON-FRI", zone = "Asia/Kolkata")
    public void resetDaily() {
        dailyTrades = 0; dailyWins = 0;
        dailyGrossWin = 0; dailyGrossLoss = 0;
        dailyPnl = BigDecimal.ZERO;
        log.info("[PAPER] Daily account reset — capital=₹{}", getCapital());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Hard reset — resets everything including cumulative stats and capital
    // Called from PaperController POST /api/paper/reset
    // ══════════════════════════════════════════════════════════════════════════

    public synchronized void hardReset() {
        ensureInit();
        capital.set(initialCapital);
        peakCapital    = initialCapital;
        maxDrawdown    = BigDecimal.ZERO;
        maxDrawdownRs  = BigDecimal.ZERO;
        dailyTrades    = 0; dailyWins    = 0;
        dailyGrossWin  = 0; dailyGrossLoss = 0;
        dailyPnl       = BigDecimal.ZERO;
        totalTrades    = 0; totalWins    = 0;
        totalGrossWin  = 0; totalGrossLoss = 0;
        totalPnl       = BigDecimal.ZERO;
        log.warn("[PAPER] Account HARD RESET — capital restored to ₹{}", initialCapital);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Getters — all unchanged from original
    // ══════════════════════════════════════════════════════════════════════════

    public BigDecimal getCapital()         { ensureInit(); return capital.get(); }
    public BigDecimal getInitialCapital()  { return initialCapital; }
    public BigDecimal getDailyPnl()        { return dailyPnl; }
    public BigDecimal getTotalPnl()        { return totalPnl; }
    public BigDecimal getMaxDrawdown()     { return maxDrawdownRs; }
    public int    getDailyTrades()         { return dailyTrades; }
    public int    getDailyWins()           { return dailyWins; }
    public int    getDailyLosses()         { return dailyTrades - dailyWins; }
    public int    getTotalTrades()         { return totalTrades; }
    public int    getTotalWins()           { return totalWins; }
    public int    getTotalLosses()         { return totalTrades - totalWins; }

    public double getDailyWinRate() {
        return dailyTrades > 0 ? (double) dailyWins / dailyTrades : 0;
    }
    public double getTotalWinRate() {
        return totalTrades > 0 ? (double) totalWins / totalTrades : 0;
    }
    public double getDailyProfitFactor() {
        return dailyGrossLoss > 0 ? dailyGrossWin / dailyGrossLoss : 0;
    }
    public double getTotalProfitFactor() {
        return totalGrossLoss > 0 ? totalGrossWin / totalGrossLoss : 0;
    }
    public double getDailyReturnPct() {
        return initialCapital != null && initialCapital.compareTo(BigDecimal.ZERO) > 0
                ? dailyPnl.divide(initialCapital, MathContext.DECIMAL32).doubleValue() * 100 : 0;
    }
    public double getTotalReturnPct() {
        return initialCapital != null && initialCapital.compareTo(BigDecimal.ZERO) > 0
                ? totalPnl.divide(initialCapital, MathContext.DECIMAL32).doubleValue() * 100 : 0;
    }
    public double getMaxDrawdownPct() {
        return peakCapital != null && peakCapital.compareTo(BigDecimal.ZERO) > 0
                ? maxDrawdown.divide(peakCapital, MathContext.DECIMAL32).doubleValue() * 100 : 0;
    }
}