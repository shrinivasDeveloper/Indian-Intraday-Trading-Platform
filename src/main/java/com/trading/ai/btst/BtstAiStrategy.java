package com.trading.ai.btst;

import com.trading.ai.AiTradingSystem;
import com.trading.ai.data.AiMarketDataService;
import com.trading.domain.enums.TradeDirection;
import com.trading.events.SmartChannelPullbackSignalEvent;
import com.trading.marketdata.service.InstrumentCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * BtstAiStrategy — Buy Today Sell Tomorrow
 * Completely separate from AiTradingSystem. Zero shared state.
 * Disabled by default — enable via ai.btst.enabled=true in application.yml
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
    private final ApplicationEventPublisher publisher;
    private final StringRedisTemplate      redis;

    @Value("${trading.capital:100000}")  private double capital;
    @Value("${ai.btst.risk-pct:0.01}")  private double riskPct;

    private final AtomicBoolean firedToday  = new AtomicBoolean(false);
    private final AtomicBoolean exitPending = new AtomicBoolean(false);
    private volatile BtstPosition currentPosition = null;

    public BtstAiStrategy(AiTradingSystem aiSystem,
                          InstrumentCacheService instrumentCache,
                          AiMarketDataService aiData,
                          ApplicationEventPublisher publisher,
                          StringRedisTemplate redis) {
        this.aiSystem        = aiSystem;
        this.instrumentCache = instrumentCache;
        this.aiData          = aiData;
        this.publisher       = publisher;
        this.redis           = redis;
    }

    @PostConstruct
    public void onStartup() {
        try {
            String saved = redis.opsForValue().get(REDIS_KEY);
            if (saved != null && !saved.isBlank()) {
                currentPosition = BtstPosition.fromRedis(saved);
                exitPending.set(true);
                log.info("[BTST] ✅ Loaded overnight position: {} {} entry=₹{} qty={}",
                        currentPosition.symbol(), currentPosition.direction(),
                        currentPosition.entryPrice(), currentPosition.qty());
            } else {
                log.info("[BTST] No overnight position — ready for today");
            }
        } catch (Exception e) {
            log.warn("[BTST] Startup load failed: {}", e.getMessage());
        }
    }

    // ── Exit: 9:20 AM next morning ─────────────────────────────────────────
    @Scheduled(cron = "0 20 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void exitBtstPosition() {
        if (!exitPending.get() || currentPosition == null) return;
        BtstPosition pos = currentPosition;
        try {
            Long tokenObj = instrumentCache.getToken("NSE", pos.symbol());
            if (tokenObj == null) { log.warn("[BTST] Token not found for {}", pos.symbol()); return; }
            long token = tokenObj;

            // Get live price from AiMarketDataService
            double ltp = aiData.getLtp(pos.symbol());
            if (ltp <= 0) ltp = pos.entryPrice().doubleValue(); // fallback to entry
            BigDecimal exitPrice = BigDecimal.valueOf(Math.round(ltp * 100.0) / 100.0);

            TradeDirection exitDir = "LONG".equals(pos.direction())
                    ? TradeDirection.SHORT : TradeDirection.LONG;

            SmartChannelPullbackSignalEvent exitSignal = new SmartChannelPullbackSignalEvent(
                    this, pos.symbol(), token, exitDir,
                    exitPrice, pos.sl(), pos.t1(), pos.t2(),
                    pos.qty(), BigDecimal.valueOf(capital * riskPct),
                    STRATEGY_NAME,
                    (int)(pos.confidenceScore()),       // confidence as int×100 proxy
                    pos.sector() != null ? pos.sector() : "Other",
                    0.0,                                // sectorChange
                    "BTST_EXIT",                        // channelQuality
                    "BTST_EXIT_NEXT_DAY",               // signalType
                    1.0,                                // pressureRatio
                    1.0,                                // rvol proxy
                    pos.confidenceScore() >= 90,        // strongTrend
                    "MARKET",                           // entryMode
                    "BTST_" + pos.direction(),          // signalLabel
                    0,                                  // candleCloseDelay
                    pos.confidenceScore(),              // scoreCategory
                    50,                                 // scoreSentiment
                    100,                                // scoreRecency
                    80,                                 // scoreSource
                    pos.confidenceScore(),              // scoreKeyword
                    pos.confidenceScore(),              // totalScore
                    0                                   // timeStopMin
            );
            publisher.publishEvent(exitSignal);

            log.info("[BTST] ✅ Exit fired: {} @ ₹{}", pos.symbol(),
                    String.format("%.2f", ltp));
            redis.delete(REDIS_KEY);
            currentPosition = null;
            exitPending.set(false);
        } catch (Exception e) {
            log.error("[BTST] Exit failed: {}", e.getMessage());
        }
    }

    // ── Entry: 14:00–14:30 scan ─────────────────────────────────────────────
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
            String bestPattern = "—";

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
            log.info("[BTST] 🌙 BTST candidate: {} score={}/100 pattern={}", bestSymbol, bestScore, bestPattern);
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
            int        qty = (int) Math.floor((capital * riskPct) / riskPerShare);
            if (qty <= 0) return;

            SmartChannelPullbackSignalEvent signal = new SmartChannelPullbackSignalEvent(
                    this, symbol, token, TradeDirection.LONG,
                    entry, sl, t1, t2,
                    qty, BigDecimal.valueOf(capital * riskPct),
                    STRATEGY_NAME,
                    score,                   // confidence proxy
                    "Other",                 // sector
                    0.0,                     // sectorChange
                    "BTST_AI",               // channelQuality
                    "BTST_OVERNIGHT",        // signalType
                    1.0,                     // pressureRatio
                    score / 100.0,           // rvol proxy
                    score >= 90,             // strongTrend
                    "MARKET",                // entryMode
                    "BTST_LONG",             // signalLabel
                    0,                       // candleCloseDelay
                    score,                   // scoreCategory
                    50,                      // scoreSentiment
                    100,                     // scoreRecency
                    80,                      // scoreSource
                    score,                   // scoreKeyword
                    score,                   // totalScore
                    0                        // timeStopMin
            );
            publisher.publishEvent(signal);

            BtstPosition position = new BtstPosition(
                    symbol, "LONG", entry, sl, t1, t2,
                    qty, score, "Other", pattern, LocalDate.now().toString());
            redis.opsForValue().set(REDIS_KEY, position.toRedis());
            currentPosition = position;
            firedToday.set(true);

            log.info("[BTST] ✅ BTST ENTRY: {} LONG ₹{} qty={} SL=₹{} T1=₹{} score={}/100",
                    symbol, String.format("%.2f", ltp), qty,
                    String.format("%.2f", sl.doubleValue()),
                    String.format("%.2f", t1.doubleValue()), score);
        } catch (Exception e) {
            log.error("[BTST] Entry failed for {}: {}", symbol, e.getMessage());
        }
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