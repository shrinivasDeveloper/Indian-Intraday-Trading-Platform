// ============================================================
// NEW FILE — v7.0 REQUIREMENT 6
// Path: src/main/java/com/trading/ranking/service/StockRankingEngine.java
// PURPOSE: Implements the Stock Ranking Engine from v7.0 prompt.
//          FinalScore = Probability × RelativeStrength × VolumeStrength
//                     × SectorStrength × StructureQuality × EntryDistance
//          Selects TOP 2-3 stocks only per cycle.
//          Integrated into StrategyEvaluatorService — only top-ranked
//          stocks pass to execution.
// ============================================================
package com.trading.ranking.service;

import com.trading.analysis.service.RvolService;
import com.trading.domain.Candle;
import com.trading.sector.service.SectorStrengthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * StockRankingEngine — v7.0 Section 6.
 *
 * PROBLEM THIS SOLVES:
 *   Without ranking, all stocks that pass probability threshold get traded.
 *   On a TREND_DAY, 10-15 stocks might qualify at prob ≥ 65. Trading all
 *   of them violates the 10-2-3 slot manager and dilutes capital.
 *
 * RANKING FORMULA (multiplicative — all factors ≥ 0, result 0-100):
 *   FinalScore = base_probability
 *              × relativeStrengthFactor   (0.5 – 1.5)
 *              × volumeStrengthFactor     (0.7 – 1.3)
 *              × sectorStrengthFactor     (0.6 – 1.4)
 *              × structureQualityFactor   (0.8 – 1.2)
 *              × entryDistanceFactor      (0.7 – 1.0)
 *
 * SELECTION: Only TOP 2-3 stocks per evaluation cycle are allowed to trade.
 *
 * USAGE:
 *   StrategyEvaluatorService calls:
 *     int rank = rankingEngine.getRank(symbol);
 *     if (rank > 3) skip;  // only top-3 pass
 *
 * RESET: Rankings reset every 5 minutes (fresh evaluation each candle cycle).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StockRankingEngine {

    private final RvolService          rvolService;
    private final SectorStrengthService sectorStrength;

    private static final int MAX_RANK = 3; // Top 3 stocks only

    // Current cycle rankings: symbol → rank (1 = best)
    private final Map<String, Integer> currentRankings = new ConcurrentHashMap<>();

    // Candidate scores collected during current candle cycle: symbol → finalScore
    private final Map<String, Double> candidateScores = new ConcurrentHashMap<>();

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Called by StrategyEvaluatorService BEFORE executing a trade.
     * Returns true if this stock is ranked within top MAX_RANK for this cycle.
     *
     * @param symbol stock symbol
     * @return true if allowed to trade (rank ≤ 3), false if should skip
     */
    public boolean isTopRanked(String symbol) {
        Integer rank = currentRankings.get(symbol);
        if (rank == null) return true; // No ranking data yet → allow (startup)
        boolean allowed = rank <= MAX_RANK;
        if (!allowed) {
            log.debug("[RANK] {} rank={} > {} — SKIPPED", symbol, rank, MAX_RANK);
        }
        return allowed;
    }

    /**
     * Returns the current rank of a symbol (1 = best, null = not ranked).
     */
    public Integer getRank(String symbol) {
        return currentRankings.get(symbol);
    }

    /**
     * Called by StrategyEvaluatorService when a signal passes probability check.
     * Submits the signal as a ranking candidate with its computed final score.
     *
     * @param symbol       trading symbol
     * @param probability  raw probability score from ProbabilityEngine (0-100)
     * @param candles5m    latest 5m candles for volume/structure scoring
     * @param sectorName   sector classification of the stock
     */
    public void submitCandidate(String symbol, double probability,
                                List<Candle> candles5m, String sectorName) {
        double finalScore = computeFinalScore(symbol, probability, candles5m, sectorName);
        candidateScores.put(symbol, finalScore);
        log.debug("[RANK] Candidate: {} prob={:.1f} finalScore={:.2f}", symbol, probability, finalScore);
        // Rebuild rankings after each submission
        rebuildRankings();
    }

    /**
     * Returns all current rankings for dashboard display.
     */
    public Map<String, Integer> getAllRankings() {
        return Collections.unmodifiableMap(currentRankings);
    }

    /**
     * Returns top-N candidates with their scores for dashboard.
     */
    public List<RankedStock> getTopCandidates(int n) {
        return candidateScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(n)
                .map(e -> new RankedStock(e.getKey(), e.getValue(),
                        currentRankings.getOrDefault(e.getKey(), 999)))
                .collect(Collectors.toList());
    }

    public record RankedStock(String symbol, double finalScore, int rank) {}

    // ── Score computation ─────────────────────────────────────────────────────

    private double computeFinalScore(String symbol, double probability,
                                     List<Candle> candles5m, String sectorName) {
        // 1. Base probability (0-100) normalized to 0-1
        double base = probability / 100.0;

        // 2. Relative Strength Factor (0.5 – 1.5)
        //    How strongly is this stock moving vs its sector?
        double rsFactor = computeRelativeStrengthFactor(symbol, candles5m);

        // 3. Volume Strength Factor (0.7 – 1.3)
        //    RVOL above or below average?
        double volFactor = computeVolumeStrengthFactor(symbol, candles5m);

        // 4. Sector Strength Factor (0.6 – 1.4)
        //    Is the stock's sector one of the top performers today?
        double sectorFactor = computeSectorStrengthFactor(sectorName);

        // 5. Structure Quality Factor (0.8 – 1.2)
        //    Clean HH/HL or LH/LL structure?
        double structureFactor = computeStructureQualityFactor(candles5m);

        // 6. Entry Distance Factor (0.7 – 1.0)
        //    Is price close to the ideal entry or has it moved away?
        double entryDistFactor = computeEntryDistanceFactor(candles5m);

        double finalScore = base * rsFactor * volFactor * sectorFactor
                * structureFactor * entryDistFactor * 100;

        log.debug("[RANK] {} score={:.2f} | base={:.2f} rs={:.2f} vol={:.2f} sector={:.2f} struct={:.2f} entry={:.2f}",
                symbol, finalScore, base, rsFactor, volFactor, sectorFactor, structureFactor, entryDistFactor);

        return finalScore;
    }

    private double computeRelativeStrengthFactor(String symbol, List<Candle> candles5m) {
        if (candles5m == null || candles5m.size() < 2) return 1.0;
        // Price change over last 3 candles as proxy for RS
        Candle latest = candles5m.get(0);
        Candle anchor = candles5m.get(Math.min(3, candles5m.size() - 1));
        if (anchor.getClose().compareTo(BigDecimal.ZERO) == 0) return 1.0;
        double chgPct = latest.getClose().subtract(anchor.getClose())
                .divide(anchor.getClose(), MathContext.DECIMAL32).doubleValue() * 100;
        // Strong momentum (+2%+) → 1.5, weak (<0.5%) → 0.5, neutral → 1.0
        if (chgPct >= 2.0)  return 1.5;
        if (chgPct >= 1.0)  return 1.3;
        if (chgPct >= 0.5)  return 1.1;
        if (chgPct >= 0.0)  return 1.0;
        if (chgPct >= -0.5) return 0.9;
        return 0.5; // Negative momentum — penalize
    }

    private double computeVolumeStrengthFactor(String symbol, List<Candle> candles5m) {
        if (candles5m == null || candles5m.isEmpty()) return 1.0;
        double rvol = rvolService.getRvolNow(symbol, candles5m.get(0).getVolume());
        if (rvol >= 2.0) return 1.3;
        if (rvol >= 1.5) return 1.2;
        if (rvol >= 1.2) return 1.1;
        if (rvol >= 1.0) return 1.0;
        if (rvol >= 0.8) return 0.9;
        return 0.7; // Well below average — penalize
    }

    private double computeSectorStrengthFactor(String sectorName) {
        if (sectorName == null) return 1.0;
        SectorStrengthService.SectorData sector = sectorStrength.getSector(sectorName);
        if (sector == null) return 1.0;
        // Top sector → 1.4, bottom sector → 0.6
        if (sector.isTopSector())    return 1.4;
        if (sector.isBottomSector()) return 0.6;
        double chg = sector.changePercent();
        if (chg >= 1.0)  return 1.2;
        if (chg >= 0.3)  return 1.1;
        if (chg >= -0.3) return 1.0;
        if (chg >= -1.0) return 0.9;
        return 0.7;
    }

    private double computeStructureQualityFactor(List<Candle> candles5m) {
        if (candles5m == null || candles5m.size() < 4) return 1.0;
        int n = Math.min(4, candles5m.size());
        int cleanMoves = 0;
        for (int i = 0; i < n - 1; i++) {
            Candle cur  = candles5m.get(i);
            Candle prev = candles5m.get(i + 1);
            boolean hhhl = cur.getHigh().compareTo(prev.getHigh()) > 0
                    && cur.getLow().compareTo(prev.getLow()) > 0;
            boolean lhll = cur.getHigh().compareTo(prev.getHigh()) < 0
                    && cur.getLow().compareTo(prev.getLow()) < 0;
            if (hhhl || lhll) cleanMoves++;
        }
        if (cleanMoves >= 3) return 1.2;  // Very clean structure
        if (cleanMoves >= 2) return 1.1;
        if (cleanMoves >= 1) return 1.0;
        return 0.8;  // Choppy — penalize
    }

    private double computeEntryDistanceFactor(List<Candle> candles5m) {
        // Proxy: if body of signal candle is large (price already moved), penalize
        if (candles5m == null || candles5m.isEmpty()) return 1.0;
        Candle c = candles5m.get(0);
        BigDecimal range = c.getHigh().subtract(c.getLow());
        if (range.compareTo(BigDecimal.ZERO) == 0) return 1.0;
        double bodyPct = c.getClose().subtract(c.getOpen()).abs()
                .divide(range, MathContext.DECIMAL32).doubleValue();
        // Entry near the open of the signal candle → 1.0 (ideal)
        // Entry after body already formed (chasing) → 0.7
        if (bodyPct >= 0.8) return 0.7;  // Already ran — entry is late
        if (bodyPct >= 0.6) return 0.85;
        return 1.0;  // Still early in the move
    }

    // ── Ranking rebuild ───────────────────────────────────────────────────────

    private void rebuildRankings() {
        currentRankings.clear();
        List<Map.Entry<String, Double>> sorted = candidateScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .collect(Collectors.toList());

        for (int i = 0; i < sorted.size(); i++) {
            currentRankings.put(sorted.get(i).getKey(), i + 1);
        }

        if (!sorted.isEmpty()) {
            log.info("[RANK] Rankings updated: {} candidates. Top3: {}",
                    sorted.size(),
                    sorted.subList(0, Math.min(3, sorted.size())).stream()
                            .map(e -> e.getKey() + "(" + String.format("%.1f", e.getValue()) + ")")
                            .collect(Collectors.joining(", ")));
        }
    }

    // ── Cycle reset ───────────────────────────────────────────────────────────

    /**
     * Reset rankings every 5 minutes aligned with candle close.
     * This ensures fresh ranking for each new candle cycle.
     */
    @Scheduled(cron = "0 */5 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void resetCycle() {
        candidateScores.clear();
        currentRankings.clear();
        log.debug("[RANK] Cycle reset — rankings cleared");
    }

    @Scheduled(cron = "0 15 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyReset() {
        candidateScores.clear();
        currentRankings.clear();
        log.info("[RANK] Daily reset complete");
    }
}