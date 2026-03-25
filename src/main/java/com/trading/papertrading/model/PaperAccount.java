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
 */
@Component
@Slf4j
public class PaperAccount {

    @Value("${trading.capital:100000}")
    private BigDecimal initialCapital;

    private final AtomicReference<BigDecimal> capital = new AtomicReference<>();

    // Daily stats — reset at 8:45 IST
    private volatile int        dailyTrades    = 0;
    private volatile int        dailyWins      = 0;
    private volatile double     dailyGrossWin  = 0;
    private volatile double     dailyGrossLoss = 0;
    private volatile BigDecimal dailyPnl       = BigDecimal.ZERO;

    // Cumulative stats — never reset
    private volatile int        totalTrades    = 0;
    private volatile int        totalWins      = 0;
    private volatile double     totalGrossWin  = 0;
    private volatile double     totalGrossLoss = 0;
    private volatile BigDecimal totalPnl       = BigDecimal.ZERO;
    private volatile BigDecimal peakCapital;
    private volatile BigDecimal maxDrawdownRs  = BigDecimal.ZERO;
    private volatile BigDecimal maxDrawdown    = BigDecimal.ZERO;

    private void ensureInit() {
        capital.compareAndSet(null, initialCapital);
        if (peakCapital == null) peakCapital = initialCapital;
    }

    public synchronized void applyPnl(BigDecimal netPnl) {
        ensureInit();
        capital.updateAndGet(c -> c.add(netPnl));

        dailyPnl  = dailyPnl.add(netPnl);
        totalPnl  = totalPnl.add(netPnl);
        dailyTrades++;
        totalTrades++;

        double pnlD = netPnl.doubleValue();
        if (pnlD > 0) {
            dailyWins++;
            totalWins++;
            dailyGrossWin  += pnlD;
            totalGrossWin  += pnlD;
        } else {
            dailyGrossLoss  += Math.abs(pnlD);
            totalGrossLoss  += Math.abs(pnlD);
        }

        BigDecimal cur = capital.get();
        if (cur.compareTo(peakCapital) > 0) peakCapital = cur;
        BigDecimal dd = peakCapital.subtract(cur);
        if (dd.compareTo(maxDrawdown) > 0) {
            maxDrawdown   = dd;
            maxDrawdownRs = dd;
        }
    }

    @Scheduled(cron = "0 45 8 * * MON-FRI", zone = "Asia/Kolkata")
    public void resetDaily() {
        dailyTrades = 0; dailyWins = 0;
        dailyGrossWin = 0; dailyGrossLoss = 0;
        dailyPnl = BigDecimal.ZERO;
        log.info("[PAPER] Daily account reset — capital=₹{}", getCapital());
    }

    public synchronized void hardReset() {
        ensureInit();
        capital.set(initialCapital);
        peakCapital = initialCapital;
        maxDrawdown = BigDecimal.ZERO;
        maxDrawdownRs = BigDecimal.ZERO;
        dailyTrades = 0; dailyWins = 0;
        dailyGrossWin = 0; dailyGrossLoss = 0;
        dailyPnl = BigDecimal.ZERO;
        totalTrades = 0; totalWins = 0;
        totalGrossWin = 0; totalGrossLoss = 0;
        totalPnl = BigDecimal.ZERO;
        log.warn("[PAPER] Account HARD RESET — capital restored to ₹{}", initialCapital);
    }

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

    public double getDailyWinRate()      { return dailyTrades > 0  ? (double) dailyWins  / dailyTrades  : 0; }
    public double getTotalWinRate()      { return totalTrades > 0  ? (double) totalWins  / totalTrades  : 0; }
    public double getDailyProfitFactor() { return dailyGrossLoss > 0 ? dailyGrossWin  / dailyGrossLoss  : 0; }
    public double getTotalProfitFactor() { return totalGrossLoss > 0 ? totalGrossWin / totalGrossLoss : 0; }

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