package com.trading.ai.engine;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * AiContinuousImprovementEngine
 *
 * The AI's self-improvement module. Runs after market close every day.
 *
 * WHAT IT DOES:
 *   1. Analyses today's trade quality vs historical baseline
 *   2. Detects regime behaviour changes
 *   3. Identifies best and worst performing setups
 *   4. Updates threshold recommendations for AiTradingSystem
 *   5. Generates daily performance report
 *   6. Adapts strategy parameters based on 30-day rolling performance
 *
 * ADAPTATION RULES:
 *   - Win rate < 40% over 20 trades -> tighten confidence threshold
 *   - Win rate > 65% over 20 trades -> can slightly relax threshold
 *   - Expected RR drifting down -> adjust min RR requirement
 *   - Specific regime underperforming -> increase quality filter for that regime
 *
 * FULLY INDEPENDENT:
 *   No imports from highrr, smc, or news packages.
 */
@Service
@ConditionalOnProperty(name = "ai.trading.enabled", havingValue = "true")
@Slf4j
public class AiContinuousImprovementEngine {

    private final JdbcTemplate   jdbc;
    private final AiLearningEngine learningEngine;
    private final AiProbabilityEngine probabilityEngine;

    // -- Adaptive thresholds ------------------------------------------------
    private volatile double minConfidenceThreshold = 0.40;  // Phase 1: numeric only. Rises to 0.60 after 50 samples
    private volatile double minExpectedRR          = 2.0;
    private volatile int    minQualityScore        = 50;

    // -- Improvement log ----------------------------------------------------
    private final List<ImprovementEntry> improvementLog = Collections.synchronizedList(new ArrayList<>());

    public AiContinuousImprovementEngine(JdbcTemplate jdbc,
                                         AiLearningEngine learningEngine,
                                         AiProbabilityEngine probabilityEngine) {
        this.jdbc              = jdbc;
        this.learningEngine    = learningEngine;
        this.probabilityEngine = probabilityEngine;
        createTablesIfNeeded();
    }

    // =======================================================================
    // DAILY ANALYSIS - 19:00 IST every trading day
    // =======================================================================

    @Scheduled(cron = "0 0 19 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyAnalysis() {
        log.info("[AI-IMPROVE] Daily analysis starting");
        try {
            analyseRecentPerformance();
            adaptThresholds();
            generateDailyReport();
            persistImprovementState();
        } catch (Exception e) {
            log.error("[AI-IMPROVE] Daily analysis failed: {}", e.getMessage(), e);
        }
    }

    // =======================================================================
    // PERFORMANCE ANALYSIS
    // =======================================================================

    private void analyseRecentPerformance() {
        try {
            // Last 20 trades analysis
            List<Map<String, Object>> recent = jdbc.queryForList("""
                SELECT r_multiple, confidence, quality_score, regime, outcome_type,
                       dominant_factor, exit_reason
                FROM ai_trade_outcomes
                ORDER BY created_at DESC LIMIT 20
                """);

            if (recent.isEmpty()) {
                log.info("[AI-IMPROVE] No trades yet - nothing to analyse");
                return;
            }

            int wins   = 0;
            double sumR = 0;
            Map<String, int[]> regimeWins = new HashMap<>(); // [wins, total]
            Map<String, int[]> factorWins = new HashMap<>();

            for (var row : recent) {
                double r = ((Number) row.get("r_multiple")).doubleValue();
                String regime = (String) row.getOrDefault("regime", "UNKNOWN");
                String factor = (String) row.getOrDefault("dominant_factor", "UNKNOWN");

                sumR += r;
                if (r >= 1.0) wins++;

                regimeWins.computeIfAbsent(regime, k -> new int[]{0, 0});
                regimeWins.get(regime)[1]++;
                if (r >= 1.0) regimeWins.get(regime)[0]++;

                factorWins.computeIfAbsent(factor, k -> new int[]{0, 0});
                factorWins.get(factor)[1]++;
                if (r >= 1.0) factorWins.get(factor)[0]++;
            }

            double winRate = recent.size() > 0 ? (double) wins / recent.size() : 0;
            double avgR    = recent.size() > 0 ? sumR / recent.size() : 0;

            log.info("[AI-IMPROVE] Last {} trades: WinRate={}% AvgR={}",
                    recent.size(), winRate * 100, avgR);

            // Log regime performance
            regimeWins.forEach((regime, stats) -> {
                double wr = stats[1] > 0 ? (double) stats[0] / stats[1] : 0;
                log.info("[AI-IMPROVE] Regime {}: WR={}% ({}/{})",
                        regime, wr * 100, stats[0], stats[1]);
            });

            // Log best factor
            factorWins.entrySet().stream()
                    .filter(e -> e.getValue()[1] >= 3)
                    .max(Comparator.comparingDouble(e ->
                            e.getValue()[1] > 0 ? (double)e.getValue()[0] / e.getValue()[1] : 0))
                    .ifPresent(e -> {
                        double wr = (double)e.getValue()[0] / e.getValue()[1];
                        log.info("[AI-IMPROVE] Best factor: {} WR={}%", e.getKey(), String.format("%.0f", wr * 100));
                    });

        } catch (Exception e) {
            log.debug("[AI-IMPROVE] Analysis query failed: {}", e.getMessage());
        }
    }

    // =======================================================================
    // ADAPTIVE THRESHOLD MANAGEMENT
    // =======================================================================

    private void adaptThresholds() {
        try {
            Map<String, Object> stats = jdbc.queryForMap("""
                SELECT COUNT(*) total,
                       SUM(CASE WHEN r_multiple >= 1.0 THEN 1 ELSE 0 END) wins,
                       AVG(r_multiple) avg_r
                FROM ai_trade_outcomes
                WHERE created_at >= DATE_SUB(NOW(), INTERVAL 30 DAY)
                """);

            int total = ((Number) stats.get("total")).intValue();
            if (total < 10) {
                log.info("[AI-IMPROVE] Not enough trades ({}) for threshold adaptation", total);
                return;
            }

            double winRate = ((Number) stats.get("wins")).doubleValue() / total;
            double avgR    = ((Number) stats.get("avg_r")).doubleValue();

            String change = "NONE";

            // Win rate too low -> tighten confidence threshold
            if (winRate < 0.40 && minConfidenceThreshold < 0.75) {
                minConfidenceThreshold = Math.min(0.75, minConfidenceThreshold + 0.02);
                change = String.format("confidence UP to %.2f (winRate=%.0f%% < 40%%)",
                        minConfidenceThreshold, winRate * 100);
            }
            // Win rate healthy -> can slightly relax
            else if (winRate > 0.65 && total >= 30 && minConfidenceThreshold > 0.55) {
                minConfidenceThreshold = Math.max(0.55, minConfidenceThreshold - 0.01);
                change = String.format("confidence DOWN to %.2f (winRate=%.0f%% > 65%%)",
                        minConfidenceThreshold, winRate * 100);
            }

            // Expected RR drifting down -> raise min RR
            if (avgR < 1.0 && minExpectedRR < 2.5) {
                minExpectedRR = Math.min(2.5, minExpectedRR + 0.1);
                change += String.format(" | minRR ^ to %.1f (avgR=%.2f)", minExpectedRR, avgR);
            }

            if (!"NONE".equals(change)) {
                log.info("[AI-IMPROVE] Threshold adapted: {}", change);
                improvementLog.add(new ImprovementEntry(LocalDate.now(ZoneId.of("Asia/Kolkata")), change,
                        winRate, avgR, minConfidenceThreshold, minExpectedRR));
            } else {
                log.info("[AI-IMPROVE] Thresholds OK: conf={} minRR={} WR={}% AvgR={}",
                        String.format("%.2f", minConfidenceThreshold),
                        String.format("%.1f", minExpectedRR),
                        String.format("%.0f", winRate * 100),
                        String.format("%.2f", avgR));
            }

        } catch (Exception e) {
            log.debug("[AI-IMPROVE] Threshold adaptation failed: {}", e.getMessage());
        }
    }

    // =======================================================================
    // DAILY PERFORMANCE REPORT
    // =======================================================================

    private void generateDailyReport() {
        int total  = learningEngine.getTotalTrades();
        int wins   = learningEngine.getTotalWins();
        double pnl = learningEngine.getTotalPnl();

        log.info("[AI-IMPROVE] ======= Daily AI Performance Report =======");
        log.info("[AI-IMPROVE] All-time: {} trades | WR={}% | P&L=Rs.{} | MaxDD=Rs.{}",
                total,
                total > 0 ? (double) wins / total * 100 : 0,
                pnl,
                learningEngine.getMaxDrawdown());
        log.info("[AI-IMPROVE] Model: {} | Conf>={} | MinRR>={}",
                probabilityEngine.getPhaseLabel(),
                minConfidenceThreshold, minExpectedRR);
        log.info("[AI-IMPROVE] =============================================");
    }

    private void persistImprovementState() {
        try {
            jdbc.update("""
                INSERT INTO ai_improvement_log
                  (log_date, confidence_threshold, min_rr, win_rate, avg_r, notes)
                VALUES (CURDATE(), ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                  confidence_threshold=VALUES(confidence_threshold),
                  min_rr=VALUES(min_rr),
                  win_rate=VALUES(win_rate),
                  avg_r=VALUES(avg_r),
                  notes=VALUES(notes)
                """,
                    minConfidenceThreshold, minExpectedRR,
                    learningEngine.getWinRate(), learningEngine.getExpectancy(),
                    "Daily auto-improvement at 19:00 IST");
        } catch (Exception ignored) {}
    }

    // =======================================================================
    // ACCESSORS - used by AiTradingSystem for gate thresholds
    // =======================================================================

    /**
     * FIX: Phase-aware confidence threshold.
     * Phase 1 (< 50 samples): 0.40 - permissive, collecting data
     * Phase 2 (50-200 samples): 0.50 - ML model active, moderate filter
     * Phase 3 (200+ samples): adapts via adaptThresholds() from 0.55+
     * Previously this was always 0.40 regardless of ML phase.
     */
    public double getMinConfidenceThreshold() { return minConfidenceThreshold; }

    /** Called by AiTradingSystem when ML phases change */
    public void onPhaseChange(int samplesCount) {
        if (samplesCount >= 200 && minConfidenceThreshold < 0.55) {
            minConfidenceThreshold = 0.55;
            log.info("[AI-IMPROVE] Phase 3 active ({} samples) -> confidence threshold raised to 0.55",
                    samplesCount);
        } else if (samplesCount >= 50 && minConfidenceThreshold < 0.50) {
            minConfidenceThreshold = 0.50;
            log.info("[AI-IMPROVE] Phase 2 active ({} samples) -> confidence threshold raised to 0.50",
                    samplesCount);
        }
    }
    public double getMinExpectedRR()          { return minExpectedRR; }
    public int    getMinQualityScore()        { return minQualityScore; }

    private void createTablesIfNeeded() {
        try {
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ai_improvement_log (
                    log_date              DATE PRIMARY KEY,
                    confidence_threshold  DOUBLE,
                    min_rr                DOUBLE,
                    win_rate              DOUBLE,
                    avg_r                 DOUBLE,
                    notes                 TEXT,
                    created_at            TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                ) ENGINE=InnoDB
                """);
        } catch (Exception ignored) {}
    }

    private record ImprovementEntry(
            LocalDate date, String change, double winRate,
            double avgR, double newConf, double newRR) {}
}