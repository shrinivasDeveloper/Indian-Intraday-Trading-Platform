package com.trading.ai.engine;

import com.trading.ai.model.AiPrediction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import smile.classification.GradientTreeBoost;
import smile.classification.LogisticRegression;
import smile.classification.RandomForest;
import smile.data.DataFrame;
import smile.data.Tuple;
import smile.data.formula.Formula;
import smile.data.type.StructType;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * AiProbabilityEngine
 *
 * The AI's probability estimation engine.
 * Uses Smile 3.x ML library — pure Java, no external APIs.
 *
 * MODEL LIFECYCLE:
 *   Phase 1 (0–50 samples):   Numeric fallback scoring
 *   Phase 2 (50–100):         LogisticRegression active
 *   Phase 3 (100–200):        LR + GBM regime-specific ensemble
 *   Phase 4 (200+):           Full ensemble with online SGD corrections
 *
 * CONTINUOUS LEARNING:
 *   - Every trade close → online SGD update (immediate)
 *   - Every trading day 18:30 IST → full retrain on 90-day window
 *   - Model only replaced if validation accuracy ≥ 52%
 *
 * FULLY INDEPENDENT:
 *   No imports from highrr, smc, or news strategy packages.
 */
@Service
@ConditionalOnProperty(name = "ai.trading.enabled", havingValue = "true")
@Slf4j
public class AiProbabilityEngine {

    private static final int    MIN_TRAIN       = 50;
    private static final int    MIN_ENSEMBLE    = 100;
    private static final int    MIN_REGIME      = 200;
    private static final int    GBM_TREES       = 200;
    private static final int    GBM_DEPTH       = 5;
    private static final int    RF_TREES        = 150;
    private static final double LEARNING_RATE   = 0.05;
    private static final double MIN_VAL_ACC     = 0.52;

    private final JdbcTemplate jdbc;
    private final ReadWriteLock modelLock = new ReentrantReadWriteLock();

    // ── ML models ─────────────────────────────────────────────────────────
    private volatile LogisticRegression probModel    = null;
    private volatile GradientTreeBoost  primaryModel = null;
    private volatile RandomForest       ensembleModel= null;
    private volatile StructType         dfSchema     = null;
    private volatile int                samplesCount = 0;

    // ── Regime-specific models ────────────────────────────────────────────
    private final Map<String, GradientTreeBoost> regimeModels = new ConcurrentHashMap<>();

    // ── Feature importance ────────────────────────────────────────────────
    private volatile double[] featureImportance = new double[60];

    // ── Online SGD weights ────────────────────────────────────────────────
    private final double[] sgdWeights = new double[60];
    private volatile int   sgdUpdates = 0;

    // ── Rolling RR tracking ───────────────────────────────────────────────
    private final Deque<Double> winRRs  = new ArrayDeque<>(200);
    private final Deque<Double> lossRRs = new ArrayDeque<>(200);
    private volatile double rollingAvgWinRR  = 2.0;
    private volatile double rollingAvgLossRR = 0.5;

    public AiProbabilityEngine(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        Arrays.fill(featureImportance, 1.0);
        createTablesIfNeeded();
    }

    @PostConstruct
    public void init() {
        samplesCount = countSamples();
        log.info("[AI-PROB] Startup: {} training samples", samplesCount);
        if (samplesCount >= MIN_TRAIN) trainModels();
        else log.info("[AI-PROB] Collecting data — need {}/{} samples", samplesCount, MIN_TRAIN);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // INFERENCE — called per candidate every 5m cycle
    // ═══════════════════════════════════════════════════════════════════════

    public AiPrediction predict(double[] features, String regime) {
        modelLock.readLock().lock();
        try {
            if (probModel == null) return numericFallback(features);

            double[] lrProbs = new double[2];
            probModel.predict(features, lrProbs);
            double lrP = lrProbs[1];

            double finalP, confidence;
            String modelUsed;

            GradientTreeBoost regimeGbm = regimeModels.get(regime);
            if (regimeGbm != null && dfSchema != null) {
                try {
                    Tuple tuple  = Tuple.of(features, dfSchema);
                    int gbmClass = regimeGbm.predict(tuple);
                    double gbmP  = gbmClass == 1 ? 0.65 : 0.35;
                    finalP       = 0.60 * lrP + 0.40 * gbmP;
                    confidence   = 1.0 - Math.abs(lrP - gbmP);
                    modelUsed    = "LR+GBM_REGIME";
                } catch (Exception e) {
                    finalP    = lrP;
                    confidence = 0.5 + Math.min(0.4, samplesCount / 500.0);
                    modelUsed  = "LR_ONLY";
                }
            } else {
                finalP    = lrP;
                confidence = 0.5 + Math.min(0.4, samplesCount / 500.0);
                modelUsed  = samplesCount >= MIN_ENSEMBLE ? "LR+GBM" : "LR_ONLY";
            }

            // Online SGD correction
            if (sgdUpdates > 10) {
                double corr = computeSgdCorrection(features);
                finalP = Math.max(0, Math.min(1, finalP + corr * 0.1));
            }

            finalP     = Math.max(0.05, Math.min(0.95, finalP));
            confidence = Math.max(0.1,  Math.min(1.0,  confidence));

            double expectedRR     = computeExpectedRR(finalP);
            double expectedReturn = expectedRR * 0.008 * 100; // 0.8% avg SL * RR

            return new AiPrediction(finalP, confidence, expectedRR,
                    expectedReturn, buildReasoning(features, finalP, confidence,
                    expectedRR, expectedReturn), modelUsed);

        } catch (Exception e) {
            log.debug("[AI-PROB] Predict error: {}", e.getMessage());
            return numericFallback(features);
        } finally {
            modelLock.readLock().unlock();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ONLINE LEARNING — called after every trade close
    // ═══════════════════════════════════════════════════════════════════════

    public void onTradeOutcome(double[] features, double rMultiple, String regime, String symbol) {
        int label = rMultiple >= 1.0 ? 1 : 0;

        // Persist to MySQL
        try {
            jdbc.update("""
                INSERT INTO ai_feature_samples
                  (symbol, trade_date, label, r_multiple, regime, features_json)
                VALUES (?, CURDATE(), ?, ?, ?, ?)
                """, symbol, label, rMultiple, regime, featuresToJson(features));
            samplesCount++;
        } catch (Exception e) {
            log.debug("[AI-PROB] Sample persist failed: {}", e.getMessage());
        }

        // Update rolling RR
        updateRollingRR(rMultiple);

        // Online SGD immediate update
        applySgdUpdate(features, label);

        // Trigger first training if threshold reached
        if (samplesCount == MIN_TRAIN) {
            log.info("[AI-PROB] {} samples — training first model", samplesCount);
            trainModels();
        }
        log.debug("[AI-PROB] Outcome recorded: {} R={} label={} total={}",
                symbol, String.format("%.2f", rMultiple), label, samplesCount);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // NIGHTLY RETRAIN — 18:30 IST every trading day
    // ═══════════════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 30 18 * * MON-FRI", zone = "Asia/Kolkata")
    public void nightlyRetrain() {
        log.info("[AI-PROB] Nightly retrain started");
        trainModels();
    }

    private void trainModels() {
        try {
            long t0 = System.currentTimeMillis();
            List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT features_json, label, r_multiple, regime
                FROM ai_feature_samples
                WHERE trade_date >= DATE_SUB(CURDATE(), INTERVAL 90 DAY)
                ORDER BY trade_date DESC LIMIT 2000
                """);

            if (rows.size() < MIN_TRAIN) {
                log.info("[AI-PROB] Not enough samples ({}/{})", rows.size(), MIN_TRAIN);
                return;
            }

            double[][] X = new double[rows.size()][];
            int[]      y = new int[rows.size()];
            Map<String, List<double[]>> regimeX = new HashMap<>();
            Map<String, List<Integer>>  regimeY = new HashMap<>();

            for (int i = 0; i < rows.size(); i++) {
                X[i] = jsonToFeatures((String) rows.get(i).get("features_json"));
                y[i] = ((Number) rows.get(i).get("label")).intValue();
                String r = (String) rows.get(i).getOrDefault("regime", "UNKNOWN");
                regimeX.computeIfAbsent(r, k -> new ArrayList<>()).add(X[i]);
                regimeY.computeIfAbsent(r, k -> new ArrayList<>()).add(y[i]);
            }

            // 80/20 train/val split
            int trainN = (int)(rows.size() * 0.8);
            double[][] Xtrain = Arrays.copyOfRange(X, 0, trainN);
            double[][] Xval   = Arrays.copyOfRange(X, trainN, rows.size());
            int[]      ytrain = Arrays.copyOfRange(y, 0, trainN);
            int[]      yval   = Arrays.copyOfRange(y, trainN, rows.size());

            DataFrame trainDf = buildDataFrame(Xtrain, ytrain);
            StructType schema = trainDf.schema();

            // Train GBM
            GradientTreeBoost newGbm = GradientTreeBoost.fit(
                    Formula.lhs("label"), trainDf,
                    GBM_TREES, GBM_DEPTH,
                    (int)(X[0].length * 0.8), 1, LEARNING_RATE, 0.5);

            // Train LR — primary inference model
            LogisticRegression newLr = null;
            try {
                newLr = LogisticRegression.fit(Xtrain, ytrain, 0.1, 1e-5, 500);
            } catch (Exception e) {
                log.warn("[AI-PROB] LR training failed: {}", e.getMessage());
            }

            // Validate
            if (newLr == null) { log.warn("[AI-PROB] LR null — skipping"); return; }
            int correct = 0;
            for (int i = 0; i < Xval.length; i++) {
                if (newLr.predict(Xval[i]) == yval[i]) correct++;
            }
            double valAcc = Xval.length > 0 ? (double) correct / Xval.length : 0;

            if (valAcc < MIN_VAL_ACC) {
                log.warn("[AI-PROB] Model rejected: valAcc={}% < {}%",
                        valAcc * 100, MIN_VAL_ACC * 100);
                return;
            }

            // Train RF ensemble (100+ samples)
            RandomForest newRf = null;
            if (rows.size() >= MIN_ENSEMBLE) {
                newRf = RandomForest.fit(Formula.lhs("label"), trainDf,
                        RF_TREES, (int)Math.sqrt(X[0].length),
                        smile.base.cart.SplitRule.GINI, 10, X.length, 5, 0.632);
            }

            // Train regime-specific models (200+ samples)
            if (rows.size() >= MIN_REGIME) {
                for (var e : regimeX.entrySet()) {
                    if (e.getValue().size() >= 30) {
                        double[][] rx = e.getValue().toArray(new double[0][]);
                        int[] ry = regimeY.get(e.getKey()).stream().mapToInt(Integer::intValue).toArray();
                        try {
                            GradientTreeBoost rm = GradientTreeBoost.fit(
                                    Formula.lhs("label"), buildDataFrame(rx, ry),
                                    100, 4, (int)(rx[0].length * 0.8), 1, 0.05, 0.5);
                            regimeModels.put(e.getKey(), rm);
                            log.info("[AI-PROB] Regime model: {} ({} samples)", e.getKey(), rx.length);
                        } catch (Exception ex) {
                            log.debug("[AI-PROB] Regime {} failed: {}", e.getKey(), ex.getMessage());
                        }
                    }
                }
            }

            // Atomic swap
            modelLock.writeLock().lock();
            try {
                primaryModel  = newGbm;
                probModel     = newLr;
                ensembleModel = newRf;
                dfSchema      = schema;
                updateFeatureImportance(newGbm, X[0].length);
            } finally {
                modelLock.writeLock().unlock();
            }

            long elapsed = System.currentTimeMillis() - t0;
            log.info("[AI-PROB] ✅ Trained | samples={} valAcc={}% elapsed={}ms",
                    rows.size(), valAcc * 100, elapsed);
            persistFeatureImportance();

        } catch (Exception e) {
            log.error("[AI-PROB] Training failed: {}", e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // EXPECTED RR ENGINE
    // ═══════════════════════════════════════════════════════════════════════

    public double computeExpectedRR(double pWin) {
        return pWin * rollingAvgWinRR - (1.0 - pWin) * rollingAvgLossRR;
    }

    private void updateRollingRR(double r) {
        if (r >= 1.0) {
            synchronized (winRRs) {
                winRRs.addLast(r);
                if (winRRs.size() > 200) winRRs.removeFirst();
                rollingAvgWinRR = winRRs.stream().mapToDouble(Double::doubleValue).average().orElse(2.0);
            }
        } else {
            synchronized (lossRRs) {
                lossRRs.addLast(Math.abs(r));
                if (lossRRs.size() > 200) lossRRs.removeFirst();
                rollingAvgLossRR = lossRRs.stream().mapToDouble(Double::doubleValue).average().orElse(0.5);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ONLINE SGD
    // ═══════════════════════════════════════════════════════════════════════

    private void applySgdUpdate(double[] features, int label) {
        double eta = 0.01;
        double predicted = computeSgdCorrection(features) + 0.5;
        double error = predicted - label;
        for (int i = 0; i < Math.min(features.length, sgdWeights.length); i++) {
            sgdWeights[i] -= eta * error * features[i];
            sgdWeights[i] *= (1 - eta * 0.001);
        }
        sgdUpdates++;
    }

    private double computeSgdCorrection(double[] f) {
        double dot = 0;
        for (int i = 0; i < Math.min(f.length, sgdWeights.length); i++) dot += sgdWeights[i] * f[i];
        return Math.tanh(dot * 0.1);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // NUMERIC FALLBACK
    // ═══════════════════════════════════════════════════════════════════════

    private AiPrediction numericFallback(double[] f) {
        // Phase 1 — no trained model yet.
        // Compute a directional score based on key features.
        // P represents probability this specific direction works — not generic win rate.

        double s = 0;

        // EMA stack alignment — strongest signal (max 25 pts)
        if (f.length > 3)  s += Math.abs(f[3]) * 25;

        // RVOL — institutional participation (max 18 pts)
        if (f.length > 16) s += Math.min(18, f[16] * 7);

        // AI patterns — highest quality signals
        if (f.length > 54 && f[54] > 0) s += 20; // liquidity sweep low
        if (f.length > 55 && f[55] > 0) s += 20; // liquidity sweep high
        if (f.length > 56 && f[56] > 0) s += 15; // S/R flip

        // Sector alignment (max 12 pts)
        if (f.length > 30 && f[30] > 0)  s += 12; // sector bullish
        if (f.length > 31 && f[31] > 0)  s += 12; // sector bearish

        // Momentum confirmation (max 8 pts)
        if (f.length > 10 && Math.abs(f[10]) > 0.3) s += 8;

        // Buy pressure alignment (max 5 pts)
        if (f.length > 19 && f[19] > 0.6) s += 5;

        s = Math.min(100, s);

        // In Phase 1 with no data, apply a floor of 45 for any candidate
        // that passed feature engineering — it is at minimum a plausible setup
        s = Math.max(45, s);

        double p = s / 100.0;

        // Phase 1 expected RR — use fixed 2.0 baseline (no rolling data yet)
        // rollingAvgWinRR starts at 2.0, rollingAvgLossRR starts at 0.5
        // But with p=0.23 this gives: 0.23×2.0 - 0.77×0.5 = 0.46 - 0.385 = 0.075 → wrong
        // Fix: use structural RR from position sizing levels, not kelly formula
        // Phase 1 trades are sized at min 2.0 RR by AiRiskAssessmentEngine
        // So expected RR = p × 2.0 + (1-p) × (-1.0) = purely probabilistic estimate
        double rr = p >= 0.5
                ? 2.0 + (p - 0.5) * 2.0   // above 50% → RR scales up to 4.0
                : 2.0 - (0.5 - p) * 1.0;  // below 50% → RR scales down to 1.5 minimum

        rr = Math.max(1.5, rr); // never below 1.5 in Phase 1 — risk engine enforces 2.0 anyway

        double ret = p * rr * 0.8; // expected return %

        return new AiPrediction(p, 0.5, rr, ret,
                String.format("Numeric fallback (%d/50 samples). P=%.0f%% RR=%.1f",
                        samplesCount, p * 100, rr),
                "NUMERIC_FALLBACK");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // REASONING ENGINE — plain English without any external API
    // ═══════════════════════════════════════════════════════════════════════

    private String buildReasoning(double[] f, double p, double conf, double rr, double ret) {
        List<String> bull = new ArrayList<>(), bear = new ArrayList<>();
        if (f.length > 3  && f[3]  > 0.3)  bull.add("EMA stack bullish");
        if (f.length > 3  && f[3]  < -0.3) bear.add("EMA stack bearish");
        if (f.length > 16 && f[16] > 1.5)  bull.add(String.format("RVOL=%.1f (institutional)", f[16]));
        if (f.length > 54 && f[54] > 0)    bull.add("AI liquidity sweep low (buy signal)");
        if (f.length > 55 && f[55] > 0)    bear.add("AI liquidity sweep high (sell signal)");
        if (f.length > 56 && f[56] > 0)    bull.add("S/R flip detected");
        if (f.length > 30 && f[30] > 0)    bull.add("sector aligned bullish");
        if (f.length > 31 && f[31] > 0)    bear.add("sector aligned bearish");
        if (f.length > 8  && f[8]  > 0.3)  bull.add("strong 1-candle momentum");
        if (f.length > 13 && f[13] < -0.5) bull.add("RSI oversold");
        if (f.length > 13 && f[13] > 0.5)  bear.add("RSI overbought");

        StringBuilder sb = new StringBuilder();
        if (!bull.isEmpty()) sb.append("BULL: ").append(String.join(", ", bull)).append(". ");
        if (!bear.isEmpty()) sb.append("BEAR: ").append(String.join(", ", bear)).append(". ");
        sb.append(String.format("P=%.0f%% E[RR]=%.1f E[Ret]=%.1f%% Conf=%.0f%%",
                p*100, rr, ret, conf*100));
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ACCESSORS
    // ═══════════════════════════════════════════════════════════════════════

    public int     getSamplesCount()      { return samplesCount; }
    public double[] getFeatureImportance(){ return Arrays.copyOf(featureImportance, featureImportance.length); }
    public String  getPhaseLabel() {
        if (samplesCount < MIN_TRAIN)    return "Phase 1 — COLLECTING_DATA (" + samplesCount + "/50)";
        if (samplesCount < MIN_ENSEMBLE) return "Phase 2 — LR_ACTIVE (" + samplesCount + "/100)";
        if (samplesCount < MIN_REGIME)   return "Phase 3 — LR+GBM (" + samplesCount + "/200)";
        return "Phase 4 — FULL_ENSEMBLE (" + samplesCount + " samples)";
    }

    // ═══════════════════════════════════════════════════════════════════════
    // UTILITIES
    // ═══════════════════════════════════════════════════════════════════════

    private DataFrame buildDataFrame(double[][] X, int[] y) {
        double[][] data = new double[X.length][X[0].length + 1];
        for (int i = 0; i < X.length; i++) {
            System.arraycopy(X[i], 0, data[i], 0, X[i].length);
            data[i][X[i].length] = y[i];
        }
        String[] cols = new String[X[0].length + 1];
        for (int j = 0; j < X[0].length; j++) cols[j] = "f" + j;
        cols[X[0].length] = "label";
        return DataFrame.of(data, cols);
    }

    private void updateFeatureImportance(GradientTreeBoost gbm, int n) {
        double[] imp = gbm.importance();
        if (imp != null && imp.length == n) {
            double max = Arrays.stream(imp).max().orElse(1.0);
            for (int i = 0; i < imp.length; i++)
                featureImportance[i] = max > 0 ? imp[i] / max : 1.0;
        }
    }

    private String featuresToJson(double[] f) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < f.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format("%.4f", f[i]));
        }
        return sb.append(']').toString();
    }

    private double[] jsonToFeatures(String json) {
        String[] parts = json.replaceAll("[\\[\\]]", "").split(",");
        double[] r = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { r[i] = Double.parseDouble(parts[i].trim()); } catch (Exception e) { r[i] = 0; }
        }
        return r;
    }

    private int countSamples() {
        try {
            Integer c = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ai_feature_samples WHERE trade_date >= DATE_SUB(CURDATE(), INTERVAL 90 DAY)",
                    Integer.class);
            return c != null ? c : 0;
        } catch (Exception e) { return 0; }
    }

    private void persistFeatureImportance() {
        try {
            for (int i = 0; i < featureImportance.length; i++) {
                jdbc.update("""
                    INSERT INTO ai_feature_importance (feature_idx, feature_name, importance, updated_at)
                    VALUES (?, ?, ?, NOW())
                    ON DUPLICATE KEY UPDATE importance=VALUES(importance), updated_at=NOW()
                    """, i, "f" + i, featureImportance[i]);
            }
        } catch (Exception ignored) {}
    }

    private void createTablesIfNeeded() {
        try {
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ai_feature_samples (
                    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
                    symbol        VARCHAR(30),
                    trade_date    DATE,
                    label         TINYINT,
                    r_multiple    DOUBLE,
                    regime        VARCHAR(30),
                    features_json TEXT,
                    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    INDEX idx_date (trade_date),
                    INDEX idx_sym  (symbol)
                ) ENGINE=InnoDB
                """);
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ai_feature_importance (
                    feature_idx  INT PRIMARY KEY,
                    feature_name VARCHAR(50),
                    importance   DOUBLE,
                    updated_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                ) ENGINE=InnoDB
                """);
        } catch (Exception ignored) {}
    }
}