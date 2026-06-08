package com.trading.ai.engine.proprietary;

import com.trading.regime.service.MarketDirectionService;
import com.trading.marketdata.service.MarketPressureService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * MarketRegimeClassifier
 *
 * Classifies the current market into one of four regimes.
 * Routes the ML engine to the appropriate regime-specific model.
 *
 * REGIMES:
 *   TRENDING   — Nifty in clear trend, ATR > 0.30%, breadth > 1.3 or < 0.75
 *   RANGING    — Nifty oscillating, ATR 0.15–0.30%, breadth 0.85–1.15
 *   VOLATILE   — ATR > 0.50%, VIX > 18, large intraday swings
 *   CHOPPY     — ATR < 0.15%, very tight range, no clear direction
 *
 * WHY THIS MATTERS:
 *   Features that predict well in trending markets (EMA stack, momentum)
 *   are poor predictors in ranging markets. Features that predict well
 *   in ranging markets (S/R proximity, mean reversion) are poor in trending.
 *
 *   By routing to regime-specific GBM models, we get:
 *   - Trending regime: weight momentum, breakout, direction features higher
 *   - Ranging regime:  weight S/R, RSI, mean-reversion features higher
 *   - Volatile regime: weight liquidity sweeps, wide SLs, fast exits higher
 *   - Choppy regime:   don't trade (return CHOPPY → AiTradingModule skips cycle)
 */
@Service
@ConditionalOnProperty(name = "ai.trading.enabled", havingValue = "true")
@Slf4j
public class MarketRegimeClassifier {

    private final MarketDirectionService  marketDir;
    private final MarketPressureService   pressureService;
    private final JdbcTemplate            jdbc;

    private volatile String  currentRegime  = "UNKNOWN";
    private volatile int     regimeDuration = 0; // consecutive cycles in same regime
    private volatile LocalDate regimeStartDate = LocalDate.now();

    // Thresholds (tunable via ML)
    private static final double ATR_TRENDING  = 0.30;
    private static final double ATR_VOLATILE  = 0.50;
    private static final double ATR_CHOPPY    = 0.15;
    private static final double BREADTH_BULL  = 1.30;
    private static final double BREADTH_BEAR  = 0.75;

    public MarketRegimeClassifier(MarketDirectionService marketDir,
                                  MarketPressureService pressureService,
                                  JdbcTemplate jdbc) {
        this.marketDir      = marketDir;
        this.pressureService = pressureService;
        this.jdbc           = jdbc;
        createTableIfNeeded();
    }

    /**
     * Classify current market regime.
     * Called at each cycle start. Returns "CHOPPY" → signal to skip trading.
     */
    public String classify() {
        try {
            var dir = marketDir.getCurrentDirection();
            if (dir == null) return "UNKNOWN";

            double atrPct = dir.niftyAtrPct();
            double breadth = 1.0;
            try {
                var snap = pressureService.getSnapshot();
                if (snap != null && snap.totalSymbols() > 100) {
                    breadth = snap.ratio();
                }
            } catch (Exception ignored) {}

            String newRegime;
            if (atrPct < ATR_CHOPPY) {
                newRegime = "CHOPPY";
            } else if (atrPct > ATR_VOLATILE) {
                newRegime = "VOLATILE";
            } else if (atrPct > ATR_TRENDING && (breadth > BREADTH_BULL || breadth < BREADTH_BEAR)) {
                newRegime = "TRENDING";
            } else {
                newRegime = "RANGING";
            }

            if (!newRegime.equals(currentRegime)) {
                log.info("[AI-REGIME] Regime change: {} → {} (ATR={:.2f}% breadth={:.2f})",
                        currentRegime, newRegime, atrPct, breadth);
                persistRegimeChange(currentRegime, newRegime, regimeDuration);
                regimeDuration  = 0;
                regimeStartDate = LocalDate.now();
            } else {
                regimeDuration++;
            }
            currentRegime = newRegime;
            return currentRegime;

        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    public String getCurrentRegime()  { return currentRegime; }
    public int    getRegimeDuration() { return regimeDuration; }

    private void persistRegimeChange(String from, String to, int duration) {
        try {
            jdbc.update("""
                INSERT INTO ai_regime_history (regime_from, regime_to, duration_cycles, change_date)
                VALUES (?, ?, ?, CURDATE())
                """, from, to, duration);
        } catch (Exception ignored) {}
    }

    private void createTableIfNeeded() {
        try {
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ai_regime_history (
                    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
                    regime_from     VARCHAR(20),
                    regime_to       VARCHAR(20),
                    duration_cycles INT,
                    change_date     DATE,
                    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                ) ENGINE=InnoDB
                """);
        } catch (Exception ignored) {}
    }
}