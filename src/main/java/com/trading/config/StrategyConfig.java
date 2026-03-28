package com.trading.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * StrategyConfig — centralised @ConfigurationProperties for all strategy settings.
 *
 * BINDING PREFIX: "strategy"
 *
 * PURPOSE:
 *   Previously all strategy values were scattered across dozens of @Value annotations
 *   in different strategy classes. A typo silently fell back to the default with no
 *   compile-time error and no IDE help. This class fixes that:
 *
 *   1. All properties validated at startup — bad YAML fails the application immediately.
 *   2. Change any threshold in application.yml without recompiling.
 *   3. One place to read ALL strategy parameters for ops/debugging.
 *   4. Gravity filter, RVOL slotting, Spring detection all become toggles.
 *
 * HOW TO USE:
 *   @Autowired private StrategyConfig cfg;
 *   double pct = cfg.getGlobal().getGravityExhaustionPct();  // returns 1.5
 *   boolean springOn = cfg.getRangeBreakout().isSpringEnabled(); // returns true
 *
 * NSE 5-PAISE PRECISION:
 *   All SL buffers use BigDecimal (not double) so ₹0.05 tick precision is preserved.
 *   Never use double for price/SL calculations that hit the Zerodha API.
 *
 * MATCHING application.yml SECTION:
 *   strategy:
 *     global:
 *       gravity-exhaustion-pct: 1.5
 *       rvol-time-slot-enabled: true
 *       min-rvol-trend: 1.3
 *       …
 *     auto-mode:
 *       reversal-gain-min-pct: 2.8
 *       …
 *     range-breakout:
 *       spring-enabled: true
 *       poc-sl-buffer: 0.0005
 *       …
 *     pullback:
 *       impulse-min-pct: 1.5
 *       …
 *     rvol:
 *       history-days: 5
 *       …
 *
 * NOTE: Also add @EnableConfigurationProperties(StrategyConfig.class) to your
 *   main @SpringBootApplication class, OR add to any existing @Configuration class.
 *   Alternatively, annotate this class with @Configuration (done here).
 */
@Configuration
@ConfigurationProperties(prefix = "strategy")
@EnableConfigurationProperties
@Data
public class StrategyConfig {

    private boolean enabled = true;

    private Global        global        = new Global();
    private AutoMode      autoMode      = new AutoMode();
    private RangeBreakout rangeBreakout = new RangeBreakout();
    private Orb           orb           = new Orb();
    private Pullback      pullback      = new Pullback();
    private Rvol          rvol          = new Rvol();

    // ══════════════════════════════════════════════════════════════════════════
    // GLOBAL AMT (Auction Market Theory) CONTEXT
    // Applies across ALL strategies — no strategy can override these.
    // ══════════════════════════════════════════════════════════════════════════

    @Data
    public static class Global {

        /**
         * GRAVITY / EXHAUSTION FILTER — "The Rubber Band" principle.
         *
         * Rule: If |price − POC| / POC > this value → cancel ALL signals.
         *
         * Why: POC = highest-volume price = "fair value" by institutional definition.
         * When price is 1.5%+ from POC, the rubber band is stretched.
         * Institutions USE this level to exit. Retail ENTERS here and gets trapped.
         * The trade will snap back to POC before continuing → Bull Trap / Bear Trap.
         *
         * In ₹ terms: 1.5% on ₹23,000 = ₹345 from fair value. That's overextended.
         * application.yml: gravity-exhaustion-pct: 1.5
         */
        private double gravityExhaustionPct = 1.5;

        /**
         * POC PROXIMITY for PullbackDetectionService Stage 1.
         * Only allow impulse detection if price is within this % of POC or VAH.
         * Guards against chasing an already-overextended impulse.
         * application.yml: poc-proximity-pct: 1.0
         */
        private double pocProximityPct = 1.0;

        /**
         * RVOL TIME-SLOT enabled flag.
         * true  → RvolService compares volume to the SAME 5-min slot of last 5 days.
         *         "1.5x at 11:30 AM (lunch lull) = institutional"
         *         "1.5x at 9:20 AM (open rush) = normal noise"
         * false → falls back to simple N-candle rolling average.
         * application.yml: rvol-time-slot-enabled: true
         */
        private boolean rvolTimeSlotEnabled = true;

        /**
         * Minimum RVOL for TREND signals.
         * RVOL < 1.3 at trend entry = low conviction = skip.
         * application.yml: min-rvol-trend: 1.3
         */
        private double minRvolTrend = 1.3;

        /**
         * Minimum RVOL for REVERSAL signals.
         * Lower than trend because the exhaustion candle itself provides confirmation.
         * application.yml: min-rvol-reversal: 1.2
         */
        private double minRvolReversal = 1.2;

        /**
         * Minimum RVOL for IMPULSE detection (PullbackDetectionService Stage 1).
         * Highest threshold — we only track impulses with strong institutional backing.
         * application.yml: min-rvol-impulse: 1.5
         */
        private double minRvolImpulse = 1.5;

        /**
         * BREAKEVEN R TRIGGER — risk migration.
         * Once trade reaches 1.5R profit → SL moves to Breakeven.
         * This creates a "free trade" — zero remaining risk, massive upside.
         * application.yml: breakeven-r-trigger: 1.5
         */
        private double breakevenRTrigger = 1.5;

        /**
         * PARTIAL EXIT trigger.
         * Book 50% of position at 3R. Let 50% trail to the moon.
         * Turns a good trade into a guaranteed win + lottery ticket.
         * application.yml: partial-exit-r: 3.0
         */
        private double partialExitR = 3.0;

        /**
         * TRAILING MODE — how to trail SL after trigger.
         * Values:
         *   CANDLE_LOW    → trail at low of prev 5-min candle (breakouts)
         *   VWAP_MINUS_01 → trail at VWAP − 0.1% (trends — breathes with market)
         *   BREAKEVEN_ONLY→ just move to BE, then exit at POC (reversals)
         *   NONE          → use TradeManagementService ATR default
         * application.yml: trailing-mode: CANDLE_LOW
         */
        private String trailingMode = "CANDLE_LOW";

        /**
         * GLOBAL TIME STOP.
         * If trade hasn't hit 0.5R profit within this duration → exit at market.
         * "If institutions aren't pushing immediately, context has changed."
         * application.yml: global-time-stop: 30m
         */
        private Duration globalTimeStop = Duration.ofMinutes(30);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // AUTO MODE STRATEGY
    // ══════════════════════════════════════════════════════════════════════════

    @Data
    public static class AutoMode {
        /** Minimum sector change % for TREND mode */
        private double sectorMoveMinPct = 0.5;

        /**
         * DYNAMIC REVERSAL THRESHOLD.
         * Was 4.0% — runaway trends, entry is too late.
         * Now 2.8% — institutional mean reversion sweet spot.
         * application.yml: reversal-gain-min-pct: 2.8
         */
        private double reversalGainMinPct = 2.8;

        /** Maximum sector change % for RANGE mode */
        private double rangeSectorMaxPct = 0.3;

        /** Minimum wick ratio for exhaustion candle (0.6 = 60% of range is wick) */
        private double wickRatioMin = 0.6;

        /** Volume multiplier for range breakout within RANGE mode */
        private double rangeBreakoutVolume = 2.0;

        /** Trend signal RR target */
        private double trendRr = 2.5;

        /** Reversal signal RR target (overridden by POC if available) */
        private double reversalRr = 2.0;

        /** Range signal RR target (overridden by POC if available) */
        private double rangeRr = 1.5;

        /** Trend trailing trigger in R-multiples (2R → trail at VWAP−0.1%) */
        private double trendTrailTriggerR = 2.0;

        /**
         * Reversal time stop — 45 min = 9 five-min candles.
         * POC target should be hit within 45 min on a mean-reversion trade.
         * If it's not moving after 45 min → the context has changed → exit.
         * application.yml: reversal-time-stop: 45m
         */
        private Duration reversalTimeStop = Duration.ofMinutes(45);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // RANGE BREAKOUT STRATEGY
    // ══════════════════════════════════════════════════════════════════════════

    @Data
    public static class RangeBreakout {
        /** Minimum touches at support AND resistance */
        private int    minTouches               = 2;
        /** Tolerance % for touch detection (0.2 = ±0.2%) */
        private double touchTolerancePct        = 0.2;
        /** Volume multiplier vs consolidation average on breakout candle */
        private double volumeBreakoutMultiplier = 1.5;
        /** Minimum body ratio for breakout candle */
        private double bodyRatioMin             = 0.5;
        /** Number of candles in consolidation box */
        private int    consolidationCandles     = 16;
        /** Maximum range % for tight consolidation */
        private double maxRangePct              = 4.0;
        /** Default RR target */
        private double rr                       = 2.0;
        /** Entry time window */
        private String entryStart               = "09:45";
        private String entryEnd                 = "11:30";

        /**
         * SPRING / UPTHRUST detection toggle.
         * Spring: price wick below rL (stop hunt) + close back inside + then breaks rH.
         * Score = 95. These are the highest-conviction institutional setups.
         * application.yml: spring-enabled: true
         */
        private boolean springEnabled = true;

        /**
         * Maximum depth below support for a valid Spring.
         * 0.6 = wick can go at most 0.6% below rL.
         * Deeper than 0.6% = real breakdown, not a liquidity grab.
         * application.yml: spring-max-depth-pct: 0.6
         */
        private double springMaxDepthPct = 0.6;

        /**
         * Spring recovery candles.
         * Close must be back inside the box within this many candles.
         * 2 candles = 10 minutes. If it takes longer, it's not a stop hunt.
         * application.yml: spring-recovery-candles: 2
         */
        private int springRecoveryCandles = 2;

        /** Spring signal score */
        private double springScore = 95.0;

        /** Normal breakout signal score */
        private double normalBreakoutScore = 80.0;

        /**
         * BREAKOUT CANDLE SL BUFFER — the key to 4:1 RR.
         * SL = breakout candle low − pocSlBuffer.
         * 0.0005 = 5 paise on NSE 5-paise tick grid.
         *
         * Old SL = rL (box bottom) → RR ~2.0
         * New SL = candle low − 5 paise → RR ~4.0+
         *
         * "On a real institutional impulse, the breakout candle is NEVER retraced."
         * application.yml: poc-sl-buffer: 0.0005
         */
        private BigDecimal pocSlBuffer = new BigDecimal("0.0005");

        /** Trailing trigger (R-multiple) — move SL to breakeven at 1.5R */
        private double trailTriggerR = 1.5;

        /** Time stop — exit if not 0.5R within 30 min */
        private Duration timeStop = Duration.ofMinutes(30);

        /** RVOL minimum for normal breakout */
        private double minRvolBreakout = 1.4;

        /** RVOL minimum for Spring (lower — pattern is already institutional confirmation) */
        private double minRvolSpring = 1.2;

        /** VAH proximity % for institutional confirmation (0.5 = within 0.5% of VAH) */
        private double vahProximityPct = 0.5;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ORB STRATEGY
    // ══════════════════════════════════════════════════════════════════════════

    @Data
    public static class Orb {
        private double niftyMinChangePct  = 0.2;
        private double sectorMinChangePct = 0.3;
        private double volumeMinMultiplier = 1.2;
        private double retestTolerancePct = 0.5;
        private double bodyRatioMin       = 0.50;
        private double wickRatioMax       = 0.40;
        private double rr                 = 2.0;
        private String entryStart         = "09:30";
        private String entryEnd           = "13:00";
        private int    orbCandles         = 3;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PULLBACK STRATEGY (VAP — Value Area Pullback)
    // ══════════════════════════════════════════════════════════════════════════

    @Data
    public static class Pullback {
        /**
         * Minimum % above VAH for impulse to be considered "institutional."
         * 1.5% = institutions pushed price 1.5% above fair value = committed.
         * application.yml: impulse-min-pct: 1.5
         */
        private double   impulseMinPct  = 1.5;
        private double   minRvolImpulse = 1.5;
        private double   minRsiImpulse  = 65.0;

        /**
         * Pullback volume ratio.
         * Pullback candles must be < 70% of impulse average volume.
         * High volume on pullback = distribution, not profit-taking → abort.
         * application.yml: pullback-vol-ratio: 0.7
         */
        private double   pullbackVolRatio = 0.7;
        private double   rr               = 3.0;

        /**
         * SL buffer below POC (NSE 5-paise precision).
         * SL = tighter of (POC × (1 - buffer)) and reversal candle low.
         * application.yml: poc-sl-buffer: 0.0005
         */
        private BigDecimal pocSlBuffer  = new BigDecimal("0.0005");
        private String   entryStart     = "09:45";
        private String   entryEnd       = "14:00";

        /**
         * Impulse state TTL.
         * If the pullback doesn't start within 45 min of the impulse → reset.
         * Stale impulses have no edge.
         * application.yml: state-ttl-minutes: 45
         */
        private Duration stateTtl = Duration.ofMinutes(45);
        private Duration timeStop = Duration.ofMinutes(30);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // RVOL SERVICE
    // ══════════════════════════════════════════════════════════════════════════

    @Data
    public static class Rvol {
        /**
         * Rolling history in trading days.
         * 5 = compare to same 5-min slot across last 5 trading days.
         * application.yml: history-days: 5
         */
        private int    historyDays     = 5;

        /**
         * Slot size in minutes. Must match CandleAggregatorService (5 min).
         * application.yml: slot-minutes: 5
         */
        private int    slotMinutes     = 5;

        private double minRvolForImpulse = 1.5;
        private double minRvolForSignal  = 1.3;
        private double minRvolForSpring  = 1.2;
    }
}