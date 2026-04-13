package com.trading.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * StrategyConfig — centralised @ConfigurationProperties for strategy settings.
 *
 * BINDING PREFIX: "strategy"
 *
 * ACTIVE INNER CLASSES:
 *   - Global          → PaperTradeManagementService
 *   - Rvol            → documented for future use
 *   - SmartChannelPullback → SmartChannelPullbackStrategy (NEW)
 *
 * HOW TO USE:
 *   @Autowired private StrategyConfig cfg;
 *   cfg.getGlobal().getBreakevenRTrigger()
 *   cfg.getSmartChannelPullback().isEnabled()
 */
@Configuration
@ConfigurationProperties(prefix = "strategy")
@EnableConfigurationProperties
@Data
public class StrategyConfig {

    private boolean enabled = true;

    private Global              global              = new Global();
    private Rvol                rvol                = new Rvol();
    private SmartChannelPullback smartChannelPullback = new SmartChannelPullback();

    // ══════════════════════════════════════════════════════════════════════
    // GLOBAL — applies across all strategies
    // Actively used by PaperTradeManagementService
    // ══════════════════════════════════════════════════════════════════════

    @Data
    public static class Global {

        private double gravityExhaustionPct    = 1.5;
        private double pocProximityPct         = 1.0;
        private boolean rvolTimeSlotEnabled    = true;
        private double minRvolTrend            = 1.3;
        private double minRvolReversal         = 1.2;
        private double minRvolImpulse          = 1.5;
        private double breakevenRTrigger       = 1.5;
        private double partialExitR            = 3.0;
        private String trailingMode            = "CANDLE_LOW";
        private Duration globalTimeStop        = Duration.ofMinutes(30);
        private double trendTrailTriggerR      = 2.0;
    }

    // ══════════════════════════════════════════════════════════════════════
    // RVOL — relative volume service configuration
    // ══════════════════════════════════════════════════════════════════════

    @Data
    public static class Rvol {

        private int    historyDays       = 5;
        private int    slotMinutes       = 5;
        private double minRvolForImpulse = 1.5;
        private double minRvolForSignal  = 1.3;
        private double minRvolForSpring  = 1.2;
    }

    // ══════════════════════════════════════════════════════════════════════
    // SMART CHANNEL PULLBACK — SmartChannelPullbackStrategy_v2
    // ══════════════════════════════════════════════════════════════════════

    @Data
    public static class SmartChannelPullback {

        /**
         * Master enable/disable switch.
         * application.yml: strategy.smart-channel-pullback.enabled: true
         */
        private boolean enabled = true;

        /**
         * Require HIGH_QUALITY channel (≥3 touches) before firing.
         * If false (default), VALID (≥2 touches) is sufficient.
         * application.yml: strategy.smart-channel-pullback.require-high-quality-channel: false
         */
        private boolean requireHighQualityChannel = false;

        /**
         * Time stop in minutes. Trade closes if not at 0.5R within this duration.
         * Falls back to StrategyConfig.global.globalTimeStop if 0.
         * application.yml: strategy.smart-channel-pullback.time-stop-minutes: 60
         */
        private int timeStopMinutes = 60;

        /**
         * Minimum RVOL to consider a signal. Signals below this fire but score lower.
         * application.yml: strategy.smart-channel-pullback.min-rvol: 1.0
         */
        private double minRvol = 1.0;

        /**
         * Maximum signals per session (9:40–14:40).
         * Prevents over-trading on very active days.
         * application.yml: strategy.smart-channel-pullback.max-signals-per-session: 3
         */
        private int maxSignalsPerSession = 3;

        /**
         * Sector buy threshold. Sector must be ≥ this % for BUY.
         * application.yml: strategy.smart-channel-pullback.sector-buy-threshold: 0.3
         */
        private double sectorBuyThreshold = 0.3;

        /**
         * Sector sell threshold. Sector must be ≤ this % for SELL.
         * application.yml: strategy.smart-channel-pullback.sector-sell-threshold: -0.3
         */
        private double sectorSellThreshold = -0.3;

        /**
         * Minimum slippage-adjusted RR. Signals with RR < this are rejected.
         * Default 1.8 accounts for 0.05% entry + 0.05% exit slippage on 1:2 target.
         * application.yml: strategy.smart-channel-pullback.min-adjusted-rr: 1.8
         */
        private double minAdjustedRr = 1.8;

        /**
         * Symbol cooldown in minutes. Same symbol can't fire again within this window.
         * Prevents re-entering same broken setup.
         * application.yml: strategy.smart-channel-pullback.symbol-cooldown-minutes: 60
         */
        private int symbolCooldownMinutes = 60;

        /**
         * Maximum channel width to consider (as % of price).
         * Very wide channels (>5%) often mean the stock is in a wide range — skip.
         * application.yml: strategy.smart-channel-pullback.max-channel-width-pct: 5.0
         */
        private double maxChannelWidthPct = 5.0;

        /**
         * T1 reward:risk multiplier.
         * application.yml: strategy.smart-channel-pullback.t1-rr: 2.0
         */
        private double t1Rr = 2.0;

        /**
         * T2 reward:risk multiplier.
         * application.yml: strategy.smart-channel-pullback.t2-rr: 3.0
         */
        private double t2Rr = 3.0;
    }
}