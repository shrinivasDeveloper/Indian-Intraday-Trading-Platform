package com.trading.backtest.controller;

import com.trading.backtest.service.IntradayBacktestEngine;
import com.trading.backtest.service.IntradayBacktestEngine.BacktestConfig;
import com.trading.backtest.service.IntradayBacktestEngine.BacktestResult;
import com.trading.backtest.service.IntradayBacktestEngine.BacktestSignal;
import com.trading.backtest.service.IntradayBacktestEngine.GapType;
import com.trading.domain.Candle;
import com.trading.domain.enums.TradeDirection;
import com.trading.marketdata.client.ZerodhaMarketDataClient;
import com.trading.marketdata.service.InstrumentCacheService;
import com.trading.sector.service.SectorClassificationService;
import com.zerodhatech.models.HistoricalData;
import com.zerodhatech.models.Instrument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.MathContext;
import java.text.SimpleDateFormat;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/backtest")
@Slf4j
@RequiredArgsConstructor
public class BacktestController {

    private final IntradayBacktestEngine      backtestEngine;
    private final ZerodhaMarketDataClient     marketDataClient;
    private final InstrumentCacheService      instrumentCache;
    private final SectorClassificationService sectorService;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // ── Request record ────────────────────────────────────────────────

    public record BacktestRequest(
            String  symbol,
            String  startDate,
            String  endDate,
            double  capital,
            double  assumedVix,
            String  entryMode
    ) {}

    // ── Run backtest ──────────────────────────────────────────────────

    @PostMapping("/run")
    public ResponseEntity<?> run(@RequestBody BacktestRequest request) {
        try {
            log.info("Backtest request: symbol={} {} → {} capital={}",
                    request.symbol(), request.startDate(),
                    request.endDate(), request.capital());

            // Resolve instrument token from cache
            Instrument inst = instrumentCache.getEquityInstruments()
                    .get(request.symbol().toUpperCase());
            if (inst == null) {
                return ResponseEntity.badRequest()
                        .body("Symbol not found: " + request.symbol()
                                + ". Make sure the app has loaded instruments.");
            }

            long   token  = inst.getInstrument_token();
            String sector = sectorService.getSector(request.symbol());

            // Fetch 15-minute historical candles from Zerodha
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            HistoricalData raw = marketDataClient.getHistoricalData(
                    token, "15minute",
                    sdf.parse(request.startDate() + " 09:00:00"),
                    sdf.parse(request.endDate()   + " 15:30:00"),
                    false);

            if (raw == null || raw.dataArrayList == null || raw.dataArrayList.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body("No historical data returned for "
                                + request.symbol()
                                + " between " + request.startDate()
                                + " and "     + request.endDate());
            }

            // Convert HistoricalData → Candle list
            List<Candle> candles = new ArrayList<>();
            for (HistoricalData d : raw.dataArrayList) {
                Instant ts = parseTimestamp(d.timeStamp);
                candles.add(Candle.builder()
                        .instrumentToken(token)
                        .tradingSymbol(request.symbol().toUpperCase())
                        .timeframe("15minute")
                        .open(BigDecimal.valueOf(d.open))
                        .high(BigDecimal.valueOf(d.high))
                        .low(BigDecimal.valueOf(d.low))
                        .close(BigDecimal.valueOf(d.close))
                        .volume((long) d.volume)
                        .candleTime(ts)
                        .complete(true)
                        .build());
            }

            log.info("Loaded {} candles for {}", candles.size(), request.symbol());

            // Generate signals from candle data
            Map<LocalDate, List<BacktestSignal>> signalsByDate =
                    generateSignals(candles, request.symbol().toUpperCase(), sector);

            // Build backtest config
            BacktestConfig config = new BacktestConfig(
                    LocalDate.parse(request.startDate()),
                    LocalDate.parse(request.endDate()),
                    BigDecimal.valueOf(request.capital() > 0 ? request.capital() : 100000),
                    request.assumedVix() > 0 ? request.assumedVix() : 16.0,
                    request.entryMode() != null && !request.entryMode().isBlank()
                            ? request.entryMode() : "AGGRESSIVE"
            );

            // Run the backtest
            BacktestResult result = backtestEngine.run(config, signalsByDate);

            return ResponseEntity.ok(buildResponse(result));

        } catch (Exception e) {
            log.error("Backtest failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body("Backtest failed: " + e.getMessage());
        }
    }

    // ── Generate signals from candle history ──────────────────────────

    private Map<LocalDate, List<BacktestSignal>> generateSignals(
            List<Candle> candles, String symbol, String sector) {

        Map<LocalDate, List<BacktestSignal>> signalsByDate = new TreeMap<>();

        // Sort oldest → newest
        candles.sort(Comparator.comparing(Candle::getCandleTime));

        for (int i = 50; i < candles.size(); i++) {
            Candle        current = candles.get(i);
            ZonedDateTime zdt     = current.getCandleTime().atZone(IST);
            LocalDate     date    = zdt.toLocalDate();
            LocalTime     time    = zdt.toLocalTime();

            // Only 9:40 - 14:40 entries
            if (time.isBefore(LocalTime.of(9, 40)))  continue;
            if (time.isAfter(LocalTime.of(14, 40)))  continue;
            if (zdt.getDayOfWeek() == DayOfWeek.SATURDAY
                    || zdt.getDayOfWeek() == DayOfWeek.SUNDAY) continue;

            List<Candle> visible = candles.subList(0, i + 1);
            BacktestSignal sig   = generateSignalFromCandles(symbol, sector, current, visible, time);
            if (sig != null) {
                signalsByDate.computeIfAbsent(date, k -> new ArrayList<>()).add(sig);
            }
        }
        return signalsByDate;
    }

    private BacktestSignal generateSignalFromCandles(
            String symbol, String sector,
            Candle current, List<Candle> visible, LocalTime time) {

        if (visible.size() < 21) return null;

        // ── Gate 3: Compression (BB squeeze) ─────────────────────────
        double bbWidth = bollingerBandWidth(visible, 20);
        if (bbWidth > 2.0)
            return gateReject(symbol, sector, 3);

        // ── Gate 4: Breakout — close > 20-bar high with 2x volume ─────
        BigDecimal hi20 = visible.subList(visible.size() - 21, visible.size() - 1)
                .stream().map(Candle::getHigh)
                .max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);

        double avgVol = visible.subList(visible.size() - 21, visible.size() - 1)
                .stream().mapToLong(Candle::getVolume).average().orElse(0);

        boolean breakout = current.getClose().compareTo(hi20) > 0
                && current.getVolume() > avgVol * 2;
        if (!breakout)
            return gateReject(symbol, sector, 4);

        // ── Gate 7: Risk — SL < 2%, RR meets window minimum ──────────
        BigDecimal entry  = current.getClose();
        BigDecimal sl     = current.getLow();
        BigDecimal slDist = entry.subtract(sl).abs();

        if (slDist.compareTo(BigDecimal.ZERO) == 0)
            return null;

        double slPct = slDist.doubleValue() / entry.doubleValue() * 100;
        if (slPct > 2.0)
            return gateReject(symbol, sector, 7);

        // Target based on RR requirement for current window
        double minRR = getMinRR(time);
        BigDecimal target = entry.add(
                slDist.multiply(BigDecimal.valueOf(minRR)));
        double rr = target.subtract(entry)
                .divide(slDist, MathContext.DECIMAL32).doubleValue();

        if (rr < minRR)
            return gateReject(symbol, sector, 7);

        String window = getWindow(time);

        return new BacktestSignal(
                symbol, sector, TradeDirection.LONG,
                entry, sl, target, rr,
                window, GapType.NO_GAP, 0  // 0 = all gates passed
        );
    }

    private BacktestSignal gateReject(String symbol, String sector, int gate) {
        return new BacktestSignal(
                symbol, sector, TradeDirection.LONG,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                0, "UNKNOWN", GapType.NO_GAP, gate);
    }

    // ── Build API response map ────────────────────────────────────────

    private Map<String, Object> buildResponse(BacktestResult r) {
        Map<String, Object> resp = new LinkedHashMap<>();

        // Core metrics
        resp.put("totalTrades",    r.totalTrades());
        resp.put("wins",           r.wins());
        resp.put("losses",         r.losses());
        resp.put("winRate",        String.format("%.1f%%", r.winRate() * 100));
        resp.put("profitFactor",   String.format("%.2f",  r.profitFactor()));
        resp.put("avgWin",         String.format("₹%.2f", r.avgWin()));
        resp.put("avgLoss",        String.format("₹%.2f", r.avgLoss()));
        resp.put("maxDrawdownPct", String.format("%.2f%%", r.maxDrawdownPct()));
        resp.put("maxDrawdownRs",  String.format("₹%.2f", r.maxDrawdownRs()));
        resp.put("expectancy",     String.format("₹%.2f", r.expectancy()));

        // Costs
        resp.put("totalBrokerage", String.format("₹%.2f", r.totalBrokerage()));
        resp.put("totalSlippage",  String.format("₹%.2f", r.totalSlippage()));

        // Streaks + daily stats
        resp.put("longestWinStreak",  r.longestWinStreak());
        resp.put("longestLossStreak", r.longestLossStreak());
        resp.put("avgTradesPerDay",   String.format("%.1f", r.avgTradesPerDay()));
        resp.put("zeroDays",          r.zeroDays());
        resp.put("totalTradingDays",  r.totalDays());

        // Gate rejections
        resp.put("gateRejections", r.gateRejections());

        // Window breakdown
        Map<String, Object> windows = new LinkedHashMap<>();
        r.windowStats().forEach((k, v) -> windows.put(k, Map.of(
                "trades",       v.trades(),
                "wins",         v.wins(),
                "winRate",      String.format("%.1f%%", v.winRate() * 100),
                "profitFactor", String.format("%.2f",   v.profitFactor())
        )));
        resp.put("windowBreakdown", windows);

        // Monthly breakdown
        Map<String, Object> months = new LinkedHashMap<>();
        r.monthStats().forEach((k, v) -> months.put(k, Map.of(
                "trades",       v.trades(),
                "wins",         v.wins(),
                "winRate",      String.format("%.1f%%", v.winRate() * 100),
                "totalPnl",     String.format("₹%.2f",  v.totalPnl()),
                "profitFactor", String.format("%.2f",   v.profitFactor())
        )));
        resp.put("monthlyBreakdown", months);

        // Overfitting check
        resp.put("overfitWarning", r.overfitWarning());
        resp.put("overfitNote",    r.overfitNote());

        // Last 20 trades for preview
        List<Map<String, Object>> recent = new ArrayList<>();
        List<IntradayBacktestEngine.BacktestTrade> trades = r.trades();
        int start = Math.max(0, trades.size() - 20);
        for (IntradayBacktestEngine.BacktestTrade t
                : trades.subList(start, trades.size())) {
            recent.add(Map.of(
                    "date",       t.date().toString(),
                    "symbol",     t.symbol(),
                    "direction",  t.direction().name(),
                    "entry",      t.entryPrice(),
                    "exit",       t.exitPrice(),
                    "qty",        t.quantity(),
                    "netPnl",     t.netPnl(),
                    "exitReason", t.exitReason(),
                    "window",     t.timeWindow()
            ));
        }
        resp.put("recentTrades", recent);

        return resp;
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private double getMinRR(LocalTime time) {
        if (time.isBefore(LocalTime.of(11, 0)))  return 2.5;
        if (time.isBefore(LocalTime.of(12, 30))) return 3.0;
        if (time.isBefore(LocalTime.of(14, 0)))  return 2.5;
        return 3.0;
    }

    private String getWindow(LocalTime time) {
        if (time.isBefore(LocalTime.of(11, 0)))  return "PRIME_MORNING";
        if (time.isBefore(LocalTime.of(12, 30))) return "LUNCH";
        if (time.isBefore(LocalTime.of(14, 0)))  return "AFTERNOON";
        return "LATE";
    }

    private double bollingerBandWidth(List<Candle> candles, int period) {
        if (candles.size() < period) return 99;
        List<Candle> sub  = candles.subList(candles.size() - period, candles.size());
        double       sum  = sub.stream().mapToDouble(c -> c.getClose().doubleValue()).sum();
        double       mean = sum / period;
        double       var  = sub.stream()
                .mapToDouble(c -> Math.pow(c.getClose().doubleValue() - mean, 2))
                .sum() / period;
        double std = Math.sqrt(var);
        return mean > 0 ? (4 * std) / mean * 100 : 99;
    }

    private Instant parseTimestamp(String ts) {
        if (ts == null || ts.isBlank()) return Instant.now();
        try {
            String clean = ts.replaceAll("\\+\\d{2}:\\d{2}$", "").trim();
            LocalDateTime ldt = LocalDateTime.parse(clean,
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            return ldt.atZone(IST).toInstant();
        } catch (Exception e) {
            try { return Instant.parse(ts); }
            catch (Exception e2) { return Instant.now(); }
        }
    }
}