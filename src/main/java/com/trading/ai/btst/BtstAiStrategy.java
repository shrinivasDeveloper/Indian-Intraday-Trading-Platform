package com.trading.ai.btst;

import com.trading.ai.AiTradingSystem;
import com.trading.ai.data.AiMarketDataService;
import com.trading.ai.execution.AiLiveOrderExecutionService;
import com.trading.ai.execution.AiNewsCapitalLedger;
import com.trading.domain.enums.TradeDirection;
import com.trading.marketdata.service.InstrumentCacheService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * BtstAiStrategy - Buy Today Sell Tomorrow
 * Completely separate from AiTradingSystem. Zero shared state.
 * Disabled by default - enable via ai.btst.enabled=true in application.yml
 *
 * INDEPENDENCE FIX (cleanup audit): removed SmartChannelPullbackSignalEvent /
 * ApplicationEventPublisher - BTST no longer routes through the shared
 * platform pipeline (SmartChannelSignalHandler -> TradeApprovedEvent ->
 * PaperTradeExecutionService), which belongs to the strategies being
 * permanently removed. Executes directly via AiLiveOrderExecutionService
 * (LIVE) or direct ledger debit/credit (PAPER), reusing the exact same
 * independent infrastructure AI's main trading system and News already use
 * - strategyName="BTST_AI_V1" throughout. BTST has no intraday SL/T1/T2
 * monitoring (it holds overnight and exits at a fixed time regardless of
 * price), so unlike AI/News it doesn't need its own TradeManagementEngine -
 * just entry and exit execution.
 */
@Service
@ConditionalOnProperty(name = "ai.btst.enabled", havingValue = "true")
@Slf4j
public class BtstAiStrategy {

    private static final String   STRATEGY_NAME  = "BTST_AI_V1";
    private static final String   REDIS_KEY      = "btst:position";
    private static final int      MIN_SCORE      = 85;
    private static final LocalTime ENTRY_START   = LocalTime.of(14, 0);
    private static final LocalTime ENTRY_END     = LocalTime.of(14, 30);
    private static final LocalTime EXIT_TIME     = LocalTime.of(9, 20);

    private final AiTradingSystem          aiSystem;
    private final InstrumentCacheService   instrumentCache;
    private final AiMarketDataService      aiData;           // live prices via getLtp()
    private final StringRedisTemplate      redis;
    private final AiLiveOrderExecutionService liveOrderService;
    private final AiNewsCapitalLedger         capitalLedger;

    @Value("${trading.capital:100000}")  private double capital;
    @Value("${ai.btst.risk-pct:0.01}")  private double riskPct;
    @Value("${trading.mode:PAPER}")     private String tradingMode;

    private boolean isLiveMode() { return "LIVE".equalsIgnoreCase(tradingMode); }

    private final AtomicBoolean firedToday  = new AtomicBoolean(false);
    private final AtomicBoolean exitPending = new AtomicBoolean(false);
    private volatile BtstPosition currentPosition = null;

    // Holds the score/pattern context for a LIVE entry order while awaiting
    // fill confirmation - keyed by orderId, mirroring AiTradingSystem's
    // identical pattern for its own LIVE entries.
    private record PendingBtstEntry(String symbol, long token, int score, String pattern) {}
    private final Map<String, PendingBtstEntry> pendingEntry = new ConcurrentHashMap<>();
    private volatile String pendingExitOrderId = null;

    public BtstAiStrategy(AiTradingSystem aiSystem,
                          InstrumentCacheService instrumentCache,
                          AiMarketDataService aiData,
                          StringRedisTemplate redis,
                          AiLiveOrderExecutionService liveOrderService,
                          AiNewsCapitalLedger capitalLedger) {
        this.aiSystem        = aiSystem;
        this.instrumentCache = instrumentCache;
        this.aiData          = aiData;
        this.redis           = redis;
        this.liveOrderService = liveOrderService;
        this.capitalLedger    = capitalLedger;
    }

    @PostConstruct
    public void wireLiveCallbacks() {
        liveOrderService.setOnEntryFilled(STRATEGY_NAME, this::onLiveEntryFilled);
        liveOrderService.setOnEntryRejected(STRATEGY_NAME, this::onLiveEntryRejected);
        liveOrderService.setOnExitFilled(STRATEGY_NAME, this::onLiveExitFilled);
        liveOrderService.setOnExitRejected(STRATEGY_NAME, this::onLiveExitRejected);
    }


    @PostConstruct
    public void onStartup() {
        try {
            String saved = redis.opsForValue().get(REDIS_KEY);
            if (saved != null && !saved.isBlank()) {
                currentPosition = BtstPosition.fromRedis(saved);
                exitPending.set(true);
                log.info("[BTST] [OK] Loaded overnight position: {} {} entry=Rs.{} qty={}",
                        currentPosition.symbol(), currentPosition.direction(),
                        currentPosition.entryPrice(), currentPosition.qty());
            } else {
                log.info("[BTST] No overnight position - ready for today");
            }
        } catch (Exception e) {
            log.warn("[BTST] Startup load failed: {}", e.getMessage());
        }
    }

    // -- Exit: 9:20 AM next morning -----------------------------------------
    @Scheduled(cron = "0 20 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void exitBtstPosition() {
        if (!exitPending.get() || currentPosition == null) return;
        BtstPosition pos = currentPosition;
        try {
            // Get live price from AiMarketDataService
            double ltp = aiData.getLtp(pos.symbol());
            if (ltp <= 0) ltp = pos.entryPrice().doubleValue(); // fallback to entry
            BigDecimal exitPrice = BigDecimal.valueOf(Math.round(ltp * 100.0) / 100.0);

            if (isLiveMode()) {
                // wasLong=true - BTST is LONG-only (see fireBtstEntry)
                String orderId = liveOrderService.placeExitOrder(
                        pos.symbol(), true, pos.qty(), exitPrice.doubleValue(),
                        STRATEGY_NAME, "BTST_EXIT_9_20");
                if (orderId == null) {
                    log.error("[BTST] [WARN] LIVE exit order placement did not succeed for {} - " +
                            "position remains open, will retry next scheduled cycle.", pos.symbol());
                    return; // exitPending stays true - currentPosition stays set, retries tomorrow at 9:20
                }
                pendingExitOrderId = orderId;
                log.info("[BTST] LIVE exit order placed, awaiting fill: {} orderId={}",
                        pos.symbol(), orderId);
                // redis/currentPosition cleared in onLiveExitFilled(), once the
                // broker confirms the fill - not here, since the position is
                // still genuinely open until then.
                return;
            }

            // PAPER mode - direct, immediate settlement
            BigDecimal grossPnl = exitPrice.subtract(pos.entryPrice())
                    .multiply(BigDecimal.valueOf(pos.qty()));
            capitalLedger.recordExit(pos.symbol(), STRATEGY_NAME,
                    pos.entryPrice().multiply(BigDecimal.valueOf(pos.qty())),
                    grossPnl, grossPnl.compareTo(BigDecimal.ZERO) > 0);

            log.info("[BTST] [OK] Exit fired (PAPER): {} @ Rs.{} P&L=Rs.{}", pos.symbol(),
                    String.format("%.2f", ltp), grossPnl);
            redis.delete(REDIS_KEY);
            currentPosition = null;
            exitPending.set(false);
        } catch (Exception e) {
            log.error("[BTST] Exit failed: {}", e.getMessage());
        }
    }

    private void onLiveExitFilled(String symbol, AiLiveOrderExecutionService.FillResult fill) {
        if (currentPosition == null || !currentPosition.symbol().equals(symbol)) return;
        BtstPosition pos = currentPosition;
        BigDecimal actualExit = BigDecimal.valueOf(fill.avgFillPrice());
        BigDecimal grossPnl = actualExit.subtract(pos.entryPrice())
                .multiply(BigDecimal.valueOf(fill.filledQty()));
        capitalLedger.recordExit(symbol, STRATEGY_NAME,
                pos.entryPrice().multiply(BigDecimal.valueOf(pos.qty())),
                grossPnl, grossPnl.compareTo(BigDecimal.ZERO) > 0);

        log.info("[BTST] [OK] LIVE exit CONFIRMED: {} @ Rs.{} qty={} P&L=Rs.{}",
                symbol, actualExit, fill.filledQty(), grossPnl);
        redis.delete(REDIS_KEY);
        currentPosition = null;
        exitPending.set(false);
        pendingExitOrderId = null;
    }

    private void onLiveExitRejected(String symbol, String statusMessage) {
        if (currentPosition == null || !currentPosition.symbol().equals(symbol)) return;
        log.error("[BTST] [WARN] LIVE exit order rejected/cancelled for {} - reason: {}. " +
                "Position remains open, will retry next scheduled cycle.", symbol, statusMessage);
        pendingExitOrderId = null;
        // exitPending stays true, currentPosition stays set - next scheduled
        // exitBtstPosition() run (tomorrow 9:20, since this only runs once
        // per day) will attempt again. Given this is a once-daily schedule,
        // a rejection here warrants checking manually before the next session.
    }


    // -- Entry: 14:00-14:30 scan ---------------------------------------------
    @Scheduled(fixedRate = 300_000)
    public void scanForBtstEntry() {
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        if (now.isBefore(ENTRY_START) || now.isAfter(ENTRY_END)) return;
        if (firedToday.get() || currentPosition != null) return;

        try {
            Map<String, Integer> watchlistScores = aiSystem.getWatchlistScores();
            if (watchlistScores.isEmpty()) return;

            String bestSymbol = null;
            int    bestScore  = 0;
            String bestPattern = "-";

            for (Map.Entry<String, Integer> entry : watchlistScores.entrySet()) {
                String sym   = entry.getKey();
                int    score = entry.getValue();
                if (score < MIN_SCORE) continue;
                if (!"LONG".equals(aiSystem.getWatchlistDirection(sym))) continue;
                if (aiSystem.isSymbolInActivePosition(sym)) continue;
                double ltp = aiData.getLtp(sym);
                if (ltp <= 0) continue;
                if (score > bestScore) {
                    bestScore  = score;
                    bestSymbol = sym;
                    bestPattern = aiSystem.getWatchlistPattern(sym);
                }
            }

            if (bestSymbol == null) return;
            log.info("[BTST] [NIGHT] BTST candidate: {} score={}/100 pattern={}", bestSymbol, bestScore, bestPattern);
            fireBtstEntry(bestSymbol, bestScore, bestPattern);
        } catch (Exception e) {
            log.error("[BTST] Scan failed: {}", e.getMessage());
        }
    }

    private void fireBtstEntry(String symbol, int score, String pattern) {
        try {
            Long tokenObj = instrumentCache.getToken("NSE", symbol);
            if (tokenObj == null) return;
            long token = tokenObj;

            double ltp = aiData.getLtp(symbol);
            if (ltp <= 0) return;

            double slPct        = computeSlPct(ltp);
            double slDist       = ltp * slPct;
            BigDecimal entry    = BigDecimal.valueOf(Math.round(ltp * 100.0) / 100.0);
            BigDecimal sl       = BigDecimal.valueOf(Math.round((ltp - slDist) * 100.0) / 100.0);
            double riskPerShare = entry.doubleValue() - sl.doubleValue();
            if (riskPerShare <= 0) return;

            BigDecimal t1  = BigDecimal.valueOf(Math.round((ltp + riskPerShare * 2.0) * 100.0) / 100.0);
            BigDecimal t2  = BigDecimal.valueOf(Math.round((ltp + riskPerShare * 3.2) * 100.0) / 100.0);
            // FIX (critical, found before going live): same capital-sufficiency
            // cap applied to AI/News - risk-only sizing can size a position
            // whose total value exceeds available capital entirely.
            int riskBasedQty  = (int) Math.floor((capital * riskPct) / riskPerShare);
            int affordableQty = (int) Math.floor(capital / entry.doubleValue());
            int qty = Math.min(riskBasedQty, affordableQty);
            if (qty <= 0) return;

            if (isLiveMode()) {
                String orderId = liveOrderService.placeEntryOrder(
                        symbol, true, qty, ltp, STRATEGY_NAME);
                if (orderId == null) {
                    log.warn("[BTST] LIVE entry order not placed for {} (blocked or failed) - " +
                            "no position opened.", symbol);
                    return;
                }
                pendingEntry.put(orderId, new PendingBtstEntry(symbol, token, score, pattern));
                log.info("[BTST] LIVE entry order placed, awaiting fill: {} orderId={}",
                        symbol, orderId);
                // firedToday/currentPosition committed in onLiveEntryFilled(),
                // once the broker confirms the fill - using the actual fill
                // price, not this signal-time ltp.
                return;
            }

            // PAPER mode - direct, immediate fill simulation
            capitalLedger.debitMargin(symbol, STRATEGY_NAME, entry.multiply(BigDecimal.valueOf(qty)));
            BtstPosition position = new BtstPosition(
                    symbol, "LONG", entry, sl, t1, t2,
                    qty, score, "Other", pattern, LocalDate.now(ZoneId.of("Asia/Kolkata")).toString());
            redis.opsForValue().set(REDIS_KEY, position.toRedis());
            currentPosition = position;
            exitPending.set(true);
            firedToday.set(true);

            log.info("[BTST] [OK] BTST ENTRY (PAPER): {} LONG Rs.{} qty={} SL=Rs.{} T1=Rs.{} score={}/100",
                    symbol, String.format("%.2f", ltp), qty,
                    String.format("%.2f", sl.doubleValue()),
                    String.format("%.2f", t1.doubleValue()), score);
        } catch (Exception e) {
            log.error("[BTST] Entry failed for {}: {}", symbol, e.getMessage());
        }
    }

    private void onLiveEntryFilled(String symbol, AiLiveOrderExecutionService.FillResult fill) {
        PendingBtstEntry ctx = null;
        String matchedOrderId = null;
        for (Map.Entry<String, PendingBtstEntry> e : pendingEntry.entrySet()) {
            if (e.getValue().symbol().equals(symbol)) { ctx = e.getValue(); matchedOrderId = e.getKey(); break; }
        }
        if (ctx == null) {
            log.error("[BTST] onLiveEntryFilled: no pending context for {} - cannot register " +
                    "position. orderId={}", symbol, fill.orderId());
            return;
        }
        pendingEntry.remove(matchedOrderId);

        BigDecimal actualEntry = BigDecimal.valueOf(fill.avgFillPrice());
        double riskPerShareActual = actualEntry.doubleValue() * computeSlPct(actualEntry.doubleValue());
        BigDecimal sl = BigDecimal.valueOf(Math.round((actualEntry.doubleValue() - riskPerShareActual) * 100.0) / 100.0);
        double riskPerShare = actualEntry.doubleValue() - sl.doubleValue();
        BigDecimal t1 = BigDecimal.valueOf(Math.round((actualEntry.doubleValue() + riskPerShare * 2.0) * 100.0) / 100.0);
        BigDecimal t2 = BigDecimal.valueOf(Math.round((actualEntry.doubleValue() + riskPerShare * 3.2) * 100.0) / 100.0);

        capitalLedger.debitMargin(symbol, STRATEGY_NAME,
                actualEntry.multiply(BigDecimal.valueOf(fill.filledQty())));

        BtstPosition position = new BtstPosition(
                symbol, "LONG", actualEntry, sl, t1, t2,
                fill.filledQty(), ctx.score(), "Other", ctx.pattern(), LocalDate.now(ZoneId.of("Asia/Kolkata")).toString());
        redis.opsForValue().set(REDIS_KEY, position.toRedis());
        currentPosition = position;
        exitPending.set(true);
        firedToday.set(true);

        log.info("[BTST] [OK] LIVE entry CONFIRMED: {} LONG qty={} actualEntry=Rs.{} SL=Rs.{} T1=Rs.{}",
                symbol, fill.filledQty(), actualEntry, sl, t1);
    }

    private void onLiveEntryRejected(String symbol, String statusMessage) {
        pendingEntry.entrySet().removeIf(e -> e.getValue().symbol().equals(symbol));
        log.warn("[BTST] LIVE entry order rejected/cancelled for {} - reason: {}. " +
                "No position was opened; firedToday NOT set, may retry within today's " +
                "remaining entry window (14:00-14:30).", symbol, statusMessage);
    }

    private double computeSlPct(double price) {
        if      (price <= 130)  return 0.020;
        else if (price <= 170)  return 0.017;
        else if (price <= 200)  return 0.013;
        else if (price <= 400)  return 0.010;
        else if (price <= 700)  return 0.007;
        else if (price <= 1200) return 0.006;
        else                    return 0.005;
    }

    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Kolkata")
    public void dailyReset() {
        firedToday.set(false);
        log.info("[BTST] Daily reset");
    }

    public Map<String, Object> getStatus() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("enabled",     true);
        s.put("firedToday",  firedToday.get());
        s.put("exitPending", exitPending.get());
        s.put("hasPosition", currentPosition != null);
        if (currentPosition != null) {
            s.put("symbol",    currentPosition.symbol());
            s.put("direction", currentPosition.direction());
            s.put("entry",     currentPosition.entryPrice());
            s.put("sl",        currentPosition.sl());
            s.put("t1",        currentPosition.t1());
            s.put("qty",       currentPosition.qty());
            s.put("score",     currentPosition.confidenceScore());
            s.put("pattern",   currentPosition.pattern());
            s.put("date",      currentPosition.date());
        }
        return s;
    }
}