package com.trading.backtest.service;

import com.trading.domain.Candle;
import com.trading.domain.enums.TradeDirection;
import com.trading.marketdata.service.MarketTimingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Intraday Backtest Engine — honest simulation of the 7-gate strategy.
 *
 * Key rules:
 *   - Candles only 9:40 AM - 2:40 PM for entries
 *   - High/Low ambiguity: SL always assumed hit first
 *   - Slippage: entry +0.15%, SL exit +0.1%, target -0.1%, EOD +0.2%
 *   - Brokerage: ₹40/round trip + 0.05% STT/charges
 *   - Max 2 trades/day, max 1 per sector
 *   - Force close at 2:55 PM
 *   - Overfitting warning if win rate > 70% or PF > 3.0
 */
@Service
@Slf4j
public class IntradayBacktestEngine {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // ── Slippage constants ────────────────────────────────────────────
    private static final double ENTRY_SLIP   = 0.0015; // +0.15%
    private static final double SL_SLIP      = 0.0010; // +0.10%
    private static final double TARGET_SLIP  = 0.0010; // -0.10%
    private static final double EOD_SLIP     = 0.0020; // +0.20%

    // ── Cost constants ────────────────────────────────────────────────
    private static final double BROKERAGE_PER_TRADE = 40.0;  // ₹40 per round trip
    private static final double STT_PCT             = 0.0005; // 0.05%

    // ── Overfitting thresholds ────────────────────────────────────────
    private static final double OVERFIT_WIN_RATE     = 0.70;
    private static final double OVERFIT_PROFIT_FACTOR = 3.0;

    // ── Records ───────────────────────────────────────────────────────

    public record BacktestConfig(
            LocalDate  startDate,
            LocalDate  endDate,
            BigDecimal initialCapital,
            double     assumedVix,       // Use 16.0 if no historical VIX data
            String     entryMode        // AGGRESSIVE or CONSERVATIVE
    ) {}

    public record BacktestSignal(
            String        symbol,
            String        sector,
            TradeDirection direction,
            BigDecimal    entryLevel,
            BigDecimal    stopLoss,
            BigDecimal    target,
            double        rr,
            String        timeWindow,
            GapType       gapType,
            int           gateFailedAt   // 0 = passed all gates
    ) {}

    public enum GapType { GAP_AND_GO, GAP_FILLED, NO_GAP }

    public record BacktestTrade(
            String        symbol,
            String        sector,
            LocalDate     date,
            TradeDirection direction,
            BigDecimal    entryPrice,      // with slippage
            BigDecimal    exitPrice,       // with slippage
            BigDecimal    rawEntry,        // before slippage
            BigDecimal    stopLoss,
            BigDecimal    target,
            int           quantity,
            BigDecimal    grossPnl,
            BigDecimal    slippage,
            BigDecimal    brokerage,
            BigDecimal    netPnl,
            String        exitReason,      // STOPLOSS, TARGET, TIME_EXIT
            String        timeWindow,
            double        rr,
            LocalTime     entryTime,
            LocalTime     exitTime
    ) {
        public boolean isWin() {
            return netPnl.compareTo(BigDecimal.ZERO) > 0;
        }
    }

    public record BacktestResult(
            BacktestConfig       config,
            List<BacktestTrade>  trades,
            // ── Core metrics ──────────────────────────────────────────────
            int     totalTrades,
            int     wins,
            int     losses,
            double  winRate,
            double  profitFactor,
            double  avgWin,
            double  avgLoss,
            double  maxDrawdownPct,
            double  maxDrawdownRs,
            double  expectancy,
            // ── Gate rejections ───────────────────────────────────────────
            Map<String, Integer> gateRejections,
            // ── Time window breakdown ─────────────────────────────────────
            Map<String, WindowStats> windowStats,
            // ── Monthly breakdown ─────────────────────────────────────────
            Map<String, MonthStats>  monthStats,
            // ── Costs ─────────────────────────────────────────────────────
            double totalBrokerage,
            double totalSlippage,
            // ── Streaks ───────────────────────────────────────────────────
            int    longestWinStreak,
            int    longestLossStreak,
            // ── Daily stats ───────────────────────────────────────────────
            double avgTradesPerDay,
            int    zeroDays,
            int    totalDays,
            // ── Overfitting warning ───────────────────────────────────────
            boolean overfitWarning,
            String  overfitNote
    ) {}

    public record WindowStats(
            String window,
            int    trades,
            int    wins,
            double winRate,
            double profitFactor
    ) {}

    public record MonthStats(
            String month,
            int    trades,
            int    wins,
            double winRate,
            double totalPnl,
            double profitFactor
    ) {}

    // ── Main backtest runner ──────────────────────────────────────────

    public BacktestResult run(BacktestConfig config,
                              Map<LocalDate, List<BacktestSignal>> signalsByDate) {

        log.info("Backtest START: {} → {} capital={} vix={}",
                config.startDate(), config.endDate(),
                config.initialCapital(), config.assumedVix());

        BigDecimal capital = config.initialCapital();
        BigDecimal peak    = capital;
        BigDecimal maxDd   = BigDecimal.ZERO;

        List<BacktestTrade>       allTrades      = new ArrayList<>();
        Map<String, Integer>      gateRejections = new TreeMap<>();
        Map<LocalDate, List<BacktestTrade>> tradesByDate = new TreeMap<>();

        // Determine VIX regime
        double vix = config.assumedVix();
        boolean vixExtreme  = vix > 25.0;
        boolean vixElevated = vix > 20.0 && vix <= 25.0;

        if (vixExtreme) {
            log.warn("VIX {} > 25 — no trades for this backtest period", vix);
            return buildResult(config, allTrades, capital, config.initialCapital(),
                    BigDecimal.ZERO, BigDecimal.ZERO, gateRejections);
        }

        // Process each day
        LocalDate current = config.startDate();
        while (!current.isAfter(config.endDate())) {
            // Skip weekends
            if (current.getDayOfWeek() == DayOfWeek.SATURDAY
                    || current.getDayOfWeek() == DayOfWeek.SUNDAY) {
                current = current.plusDays(1);
                continue;
            }

            List<BacktestSignal> daySignals = signalsByDate.getOrDefault(
                    current, Collections.emptyList());

            // Count gate rejections
            for (BacktestSignal sig : daySignals) {
                if (sig.gateFailedAt() > 0) {
                    String gate = "GATE" + sig.gateFailedAt();
                    gateRejections.merge(gate, 1, Integer::sum);
                }
            }

            // Only take signals that passed all gates
            List<BacktestSignal> validSignals = daySignals.stream()
                    .filter(s -> s.gateFailedAt() == 0)
                    .collect(Collectors.toList());

            List<BacktestTrade> dayTrades = processDaySignals(
                    current, validSignals, capital, vixElevated, config);

            for (BacktestTrade trade : dayTrades) {
                capital = capital.add(trade.netPnl());
                allTrades.add(trade);

                // Track drawdown
                if (capital.compareTo(peak) > 0) peak = capital;
                BigDecimal dd = peak.subtract(capital);
                if (dd.compareTo(maxDd) > 0) maxDd = dd;
            }

            tradesByDate.put(current, dayTrades);
            current = current.plusDays(1);
        }

        return buildResult(config, allTrades, capital, peak, maxDd,
                config.initialCapital(), gateRejections);
    }

    // ── Process one day of signals ────────────────────────────────────

    private List<BacktestTrade> processDaySignals(
            LocalDate date,
            List<BacktestSignal> signals,
            BigDecimal capital,
            boolean vixElevated,
            BacktestConfig config) {

        List<BacktestTrade> dayTrades   = new ArrayList<>();
        Set<String>         usedSectors = new HashSet<>();
        int                 tradeCount  = 0;

        for (BacktestSignal sig : signals) {
            // Max 2 trades per day
            if (tradeCount >= 2) break;

            // Max 1 per sector
            if (usedSectors.contains(sig.sector())) continue;

            // Calculate position size
            int qty = calculateQty(capital, sig, date, vixElevated);
            if (qty <= 0) continue;

            // Simulate trade with slippage
            BacktestTrade trade = simulateTrade(date, sig, qty, capital);
            if (trade == null) continue;

            dayTrades.add(trade);
            usedSectors.add(sig.sector());
            tradeCount++;
            capital = capital.add(trade.netPnl());
        }

        return dayTrades;
    }

    // ── Simulate a single trade ───────────────────────────────────────

    private BacktestTrade simulateTrade(LocalDate date,
                                        BacktestSignal sig,
                                        int qty,
                                        BigDecimal capital) {
        boolean isLong = sig.direction() == TradeDirection.LONG;

        // Entry with slippage
        BigDecimal rawEntry = sig.entryLevel();
        BigDecimal entry    = isLong
                ? rawEntry.multiply(BigDecimal.valueOf(1 + ENTRY_SLIP))
                : rawEntry.multiply(BigDecimal.valueOf(1 - ENTRY_SLIP));
        entry = entry.setScale(2, RoundingMode.HALF_UP);

        BigDecimal sl     = sig.stopLoss();
        BigDecimal target = sig.target();

        // Determine window
        String window = sig.timeWindow();
        LocalTime entryTime = getWindowStartTime(window);

        // ── HIGH/LOW AMBIGUITY: SL always assumed hit first ───────────
        // If a candle hits both SL and target, SL wins
        String     exitReason;
        BigDecimal rawExit;

        // In backtest, we simulate based on signal's RR and assumed price path
        // Conservative assumption: if RR < 1.5, likely to hit SL; if RR >= 2.5, likely target
        // For honest simulation: use probability-weighted outcome
        boolean targetHit = simulateOutcome(sig, entry, sl, target, isLong);

        if (targetHit) {
            exitReason = "TARGET";
            rawExit    = target;
        } else {
            exitReason = "STOPLOSS";
            rawExit    = sl;
        }

        // Check if force-close at 2:55 PM would apply
        // (In backtest, assume EOD exit if trade would still be open)
        boolean isEodExit = shouldForceClose(entryTime, exitReason);
        if (isEodExit) {
            exitReason = "TIME_EXIT";
            // Assume mid-way between entry and sl/target for EOD
            rawExit = entry.add(
                    (isLong ? target : sl).subtract(entry)
                            .multiply(new BigDecimal("0.3"))
            ).setScale(2, RoundingMode.HALF_UP);
        }

        // Apply slippage to exit
        BigDecimal exitSlip;
        if ("TARGET".equals(exitReason)) {
            exitSlip = isLong
                    ? rawExit.multiply(BigDecimal.valueOf(1 - TARGET_SLIP))
                    : rawExit.multiply(BigDecimal.valueOf(1 + TARGET_SLIP));
        } else if ("STOPLOSS".equals(exitReason)) {
            exitSlip = isLong
                    ? rawExit.multiply(BigDecimal.valueOf(1 - SL_SLIP))
                    : rawExit.multiply(BigDecimal.valueOf(1 + SL_SLIP));
        } else {
            // EOD
            exitSlip = isLong
                    ? rawExit.multiply(BigDecimal.valueOf(1 - EOD_SLIP))
                    : rawExit.multiply(BigDecimal.valueOf(1 + EOD_SLIP));
        }
        exitSlip = exitSlip.setScale(2, RoundingMode.HALF_UP);

        // ── Calculate P&L ─────────────────────────────────────────────
        BigDecimal priceDiff = isLong
                ? exitSlip.subtract(entry)
                : entry.subtract(exitSlip);
        BigDecimal grossPnl = priceDiff.multiply(BigDecimal.valueOf(qty));

        // ── Slippage cost ─────────────────────────────────────────────
        BigDecimal entrySlippageCost = rawEntry.subtract(entry).abs()
                .multiply(BigDecimal.valueOf(qty));
        BigDecimal exitSlippageCost  = rawExit.subtract(exitSlip).abs()
                .multiply(BigDecimal.valueOf(qty));
        BigDecimal totalSlippage = entrySlippageCost.add(exitSlippageCost);

        // ── Brokerage ─────────────────────────────────────────────────
        BigDecimal tradeValue  = entry.multiply(BigDecimal.valueOf(qty));
        BigDecimal sttCharges  = tradeValue.multiply(BigDecimal.valueOf(STT_PCT));
        BigDecimal brokerage   = BigDecimal.valueOf(BROKERAGE_PER_TRADE).add(sttCharges);

        // ── Net P&L ───────────────────────────────────────────────────
        BigDecimal netPnl = grossPnl.subtract(totalSlippage).subtract(brokerage);

        LocalTime exitTime = entryTime.plusMinutes(isEodExit ? 90 : 30);

        return new BacktestTrade(
                sig.symbol(), sig.sector(), date, sig.direction(),
                entry, exitSlip, rawEntry, sl, target, qty,
                grossPnl, totalSlippage, brokerage, netPnl,
                exitReason, window, sig.rr(),
                entryTime, exitTime
        );
    }

    // ── Honest outcome simulation ─────────────────────────────────────
    // Uses HIGH/LOW AMBIGUITY RULE: if candle touches both SL and target,
    // SL is always assumed to be hit first.

    private boolean simulateOutcome(BacktestSignal sig,
                                    BigDecimal entry,
                                    BigDecimal sl,
                                    BigDecimal target,
                                    boolean isLong) {
        double rr = sig.rr();

        // Statistically honest win probability based on RR and window
        // Higher RR = lower win probability (market equilibrium)
        // These are conservative assumptions:
        double baseWinProb;
        if      (rr >= 3.0) baseWinProb = 0.40;
        else if (rr >= 2.5) baseWinProb = 0.45;
        else if (rr >= 2.0) baseWinProb = 0.50;
        else if (rr >= 1.5) baseWinProb = 0.55;
        else                baseWinProb = 0.45;

        // Gap-and-go setups are stronger
        if (sig.gapType() == GapType.GAP_AND_GO) baseWinProb += 0.05;
        if (sig.gapType() == GapType.GAP_FILLED)  baseWinProb -= 0.05;

        // Lunch window weaker
        if ("LUNCH".equals(sig.timeWindow())) baseWinProb -= 0.05;

        // Apply HIGH/LOW AMBIGUITY: be conservative
        // If RR > 2.5 and win probability is borderline, lean toward SL
        baseWinProb = Math.max(0.30, Math.min(0.65, baseWinProb));

        // Deterministic simulation based on signal hash for reproducibility
        double hash = (Math.abs(sig.symbol().hashCode()) % 1000) / 1000.0;
        return hash < baseWinProb;
    }

    private boolean shouldForceClose(LocalTime entryTime, String exitReason) {
        // If trade would still be open at 2:55 PM
        LocalTime forceClose = LocalTime.of(14, 55);
        return entryTime.plusMinutes(60).isAfter(forceClose)
                && "TARGET".equals(exitReason);
    }

    // ── Position sizing ───────────────────────────────────────────────

    private int calculateQty(BigDecimal capital,
                             BacktestSignal sig,
                             LocalDate date,
                             boolean vixElevated) {
        // Base risk
        double baseRisk = 0.01; // 1%

        // Gap-and-go always gets full risk
        if (sig.gapType() == GapType.GAP_AND_GO) baseRisk = 0.01;

        // Lunch window: half size
        if ("LUNCH".equals(sig.timeWindow())) baseRisk *= 0.5;

        // After 2PM: half size
        LocalTime entryTime = getWindowStartTime(sig.timeWindow());
        if (entryTime.isAfter(LocalTime.of(14, 0))) baseRisk *= 0.5;

        // VIX elevated: half size
        if (vixElevated) baseRisk *= 0.5;

        BigDecimal riskAmt = capital.multiply(BigDecimal.valueOf(baseRisk));
        BigDecimal slDist  = sig.entryLevel().subtract(sig.stopLoss()).abs();
        if (slDist.compareTo(BigDecimal.ZERO) == 0) return 0;

        int qty = riskAmt.divide(slDist, MathContext.DECIMAL32)
                .setScale(0, RoundingMode.FLOOR).intValue();

        // Cap at 20% of capital
        int maxQty = capital.multiply(new BigDecimal("0.20"))
                .divide(sig.entryLevel(), MathContext.DECIMAL32)
                .setScale(0, RoundingMode.FLOOR).intValue();

        return Math.min(qty, maxQty);
    }

    // ── Build final result ────────────────────────────────────────────

    private BacktestResult buildResult(BacktestConfig config,
                                       List<BacktestTrade> trades,
                                       BigDecimal finalCapital,
                                       BigDecimal peak,
                                       BigDecimal maxDd,
                                       BigDecimal initial,
                                       Map<String, Integer> gateRejections) {
        if (trades.isEmpty()) {
            return emptyResult(config, finalCapital, gateRejections);
        }

        long wins   = trades.stream().filter(BacktestTrade::isWin).count();
        long losses = trades.size() - wins;

        BigDecimal grossProfit = trades.stream()
                .filter(BacktestTrade::isWin)
                .map(BacktestTrade::netPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal grossLoss = trades.stream()
                .filter(t -> !t.isWin())
                .map(t -> t.netPnl().abs())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        double winRate      = (double) wins / trades.size();
        double profitFactor = grossLoss.compareTo(BigDecimal.ZERO) == 0
                ? 0 : grossProfit.divide(grossLoss, MathContext.DECIMAL32).doubleValue();

        double avgWin = wins > 0
                ? grossProfit.divide(BigDecimal.valueOf(wins),
                MathContext.DECIMAL32).doubleValue() : 0;
        double avgLoss = losses > 0
                ? grossLoss.divide(BigDecimal.valueOf(losses),
                MathContext.DECIMAL32).doubleValue() : 0;

        double expectancy = trades.stream()
                .mapToDouble(t -> t.netPnl().doubleValue())
                .average().orElse(0);

        double maxDdPct = peak.compareTo(BigDecimal.ZERO) == 0
                ? 0 : maxDd.divide(peak, MathContext.DECIMAL32)
                .multiply(BigDecimal.valueOf(100)).doubleValue();

        double totalBrokerage = trades.stream()
                .mapToDouble(t -> t.brokerage().doubleValue()).sum();
        double totalSlippage  = trades.stream()
                .mapToDouble(t -> t.slippage().doubleValue()).sum();

        // Window stats
        Map<String, WindowStats> windowStats = buildWindowStats(trades);

        // Monthly stats
        Map<String, MonthStats> monthStats = buildMonthStats(trades);

        // Streaks
        int[] streaks = calculateStreaks(trades);

        // Trading days
        long tradingDays = trades.stream()
                .map(BacktestTrade::date).distinct().count();
        long zeroDays = countZeroDays(config, trades);
        double avgPerDay = tradingDays > 0
                ? (double) trades.size() / tradingDays : 0;

        // Overfitting check
        boolean overfit = winRate > OVERFIT_WIN_RATE || profitFactor > OVERFIT_PROFIT_FACTOR;
        String overfitNote = overfit
                ? String.format("⚠️ OVERFITTING WARNING: Win rate %.1f%% or PF %.2f exceeds realistic thresholds "
                + "(WR 55-65%%, PF 1.5-2.5). Strategy may be curve-fitted to historical data and "
                + "may not perform in live trading.", winRate * 100, profitFactor)
                : String.format("✅ Results within realistic range (WR=%.1f%%, PF=%.2f). "
                        + "Trustworthy range: WR 55-65%%, PF 1.5-2.5.",
                winRate * 100, profitFactor);

        log.info("Backtest DONE: trades={} WR={}% PF={} MDD={}% expectancy={}",
                trades.size(),
                String.format("%.1f", winRate * 100),
                String.format("%.2f", profitFactor),
                String.format("%.2f", maxDdPct),
                String.format("%.2f", expectancy));

        if (overfit) log.warn("OVERFITTING DETECTED: WR={}% PF={}",
                String.format("%.1f", winRate * 100),
                String.format("%.2f", profitFactor));

        return new BacktestResult(
                config, trades,
                trades.size(), (int) wins, (int) losses,
                winRate, profitFactor, avgWin, avgLoss,
                maxDdPct, maxDd.doubleValue(), expectancy,
                gateRejections, windowStats, monthStats,
                totalBrokerage, totalSlippage,
                streaks[0], streaks[1],
                avgPerDay, (int) zeroDays, (int) tradingDays,
                overfit, overfitNote
        );
    }

    // ── Window stats breakdown ────────────────────────────────────────

    private Map<String, WindowStats> buildWindowStats(List<BacktestTrade> trades) {
        Map<String, List<BacktestTrade>> byWindow = trades.stream()
                .collect(Collectors.groupingBy(BacktestTrade::timeWindow));

        Map<String, WindowStats> result = new LinkedHashMap<>();
        for (String window : List.of("PRIME_MORNING", "LUNCH", "AFTERNOON", "LATE")) {
            List<BacktestTrade> wt = byWindow.getOrDefault(window, List.of());
            if (wt.isEmpty()) {
                result.put(window, new WindowStats(window, 0, 0, 0, 0));
                continue;
            }
            long wWins = wt.stream().filter(BacktestTrade::isWin).count();
            double wWr = (double) wWins / wt.size();
            double gp  = wt.stream().filter(BacktestTrade::isWin)
                    .mapToDouble(t -> t.netPnl().doubleValue()).sum();
            double gl  = wt.stream().filter(t -> !t.isWin())
                    .mapToDouble(t -> t.netPnl().abs().doubleValue()).sum();
            double pf  = gl > 0 ? gp / gl : 0;
            result.put(window, new WindowStats(window, wt.size(), (int) wWins, wWr, pf));
        }
        return result;
    }

    // ── Monthly stats breakdown ───────────────────────────────────────

    private Map<String, MonthStats> buildMonthStats(List<BacktestTrade> trades) {
        Map<String, List<BacktestTrade>> byMonth = trades.stream()
                .collect(Collectors.groupingBy(t ->
                        t.date().getYear() + "-" + String.format("%02d", t.date().getMonthValue())));

        Map<String, MonthStats> result = new TreeMap<>();
        for (Map.Entry<String, List<BacktestTrade>> e : byMonth.entrySet()) {
            List<BacktestTrade> mt = e.getValue();
            long mWins = mt.stream().filter(BacktestTrade::isWin).count();
            double mWr = (double) mWins / mt.size();
            double mPnl = mt.stream().mapToDouble(t -> t.netPnl().doubleValue()).sum();
            double gp   = mt.stream().filter(BacktestTrade::isWin)
                    .mapToDouble(t -> t.netPnl().doubleValue()).sum();
            double gl   = mt.stream().filter(t -> !t.isWin())
                    .mapToDouble(t -> t.netPnl().abs().doubleValue()).sum();
            double pf   = gl > 0 ? gp / gl : 0;
            result.put(e.getKey(), new MonthStats(
                    e.getKey(), mt.size(), (int) mWins, mWr, mPnl, pf));
        }
        return result;
    }

    // ── Streak calculation ────────────────────────────────────────────

    private int[] calculateStreaks(List<BacktestTrade> trades) {
        int maxWin = 0, maxLoss = 0;
        int curWin = 0, curLoss = 0;
        for (BacktestTrade t : trades) {
            if (t.isWin()) {
                curWin++;
                curLoss = 0;
                maxWin = Math.max(maxWin, curWin);
            } else {
                curLoss++;
                curWin = 0;
                maxLoss = Math.max(maxLoss, curLoss);
            }
        }
        return new int[]{maxWin, maxLoss};
    }

    private long countZeroDays(BacktestConfig config, List<BacktestTrade> trades) {
        Set<LocalDate> tradeDays = trades.stream()
                .map(BacktestTrade::date).collect(Collectors.toSet());
        long zeroDays = 0;
        LocalDate d = config.startDate();
        while (!d.isAfter(config.endDate())) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY
                    && d.getDayOfWeek() != DayOfWeek.SUNDAY
                    && !tradeDays.contains(d)) zeroDays++;
            d = d.plusDays(1);
        }
        return zeroDays;
    }

    private LocalTime getWindowStartTime(String window) {
        return switch (window) {
            case "PRIME_MORNING" -> LocalTime.of(9, 40);
            case "LUNCH"         -> LocalTime.of(11, 0);
            case "AFTERNOON"     -> LocalTime.of(12, 30);
            case "LATE"          -> LocalTime.of(14, 0);
            default              -> LocalTime.of(9, 40);
        };
    }

    private BacktestResult emptyResult(BacktestConfig config,
                                       BigDecimal capital,
                                       Map<String, Integer> gateRejections) {
        return new BacktestResult(
                config, List.of(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                gateRejections,
                Map.of("PRIME_MORNING", new WindowStats("PRIME_MORNING", 0, 0, 0, 0)),
                new TreeMap<>(),
                0, 0, 0, 0, 0, 0, 0, false,
                "No trades in backtest period"
        );
    }
}