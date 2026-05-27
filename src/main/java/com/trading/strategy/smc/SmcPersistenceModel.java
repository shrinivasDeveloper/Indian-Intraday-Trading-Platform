package com.trading.strategy.smc;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * SmcPersistenceModel
 * ─────────────────────────────────────────────────────────────────────────────
 * MySQL entity classes and Spring Data repositories for SMC_INSTITUTIONAL_V1.
 *
 * Tables:
 *   smc_daily_candles   — 1-year historical daily OHLCV per symbol
 *   smc_signals         — every signal fired (pass/reject) with full metadata
 *
 * Uses existing MySQL datasource — no new datasource configuration needed.
 * Tables auto-created via spring.jpa.hibernate.ddl-auto=update (existing setting).
 * ─────────────────────────────────────────────────────────────────────────────
 */
public class SmcPersistenceModel {

    // ══════════════════════════════════════════════════════════════════════════
    // ENTITY: SMC daily candle store
    // ══════════════════════════════════════════════════════════════════════════

    @Entity
    @Table(name = "smc_daily_candles",
            indexes = {
                    @Index(name = "idx_smc_dc_symbol_date", columnList = "symbol, candle_date"),
                    @Index(name = "idx_smc_dc_symbol",      columnList = "symbol")
            },
            uniqueConstraints = @UniqueConstraint(columnNames = {"symbol", "candle_date"}))
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SmcDailyCandle {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(nullable = false, length = 30)
        private String symbol;

        @Column(name = "candle_date", nullable = false)
        private LocalDate candleDate;

        @Column(nullable = false)
        private double open;

        @Column(nullable = false)
        private double high;

        @Column(nullable = false)
        private double low;

        @Column(nullable = false)
        private double close;

        @Column(nullable = false)
        private long volume;

        @Column(name = "created_at")
        private LocalDateTime createdAt;

        @PrePersist
        void prePersist() { createdAt = LocalDateTime.now(); }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ENTITY: SMC signal log
    // ══════════════════════════════════════════════════════════════════════════

    @Entity
    @Table(name = "smc_signals",
            indexes = {
                    @Index(name = "idx_smc_sig_symbol", columnList = "symbol"),
                    @Index(name = "idx_smc_sig_date",   columnList = "signal_date")
            })
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SmcSignal {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(nullable = false, length = 30)
        private String symbol;

        @Column(name = "signal_date", nullable = false)
        private LocalDate signalDate;

        @Column(name = "signal_time")
        private LocalDateTime signalTime;

        /** BUY or SELL */
        @Column(length = 10)
        private String direction;

        /** Setup type: SUPPORT_BOUNCE, LIQUIDITY_SWEEP_LOW, etc. */
        @Column(name = "setup_type", length = 40)
        private String setupType;

        /** HTF trend at time of signal: BULLISH / BEARISH / SIDEWAYS */
        @Column(name = "htf_trend", length = 20)
        private String htfTrend;

        @Column(name = "entry_price")
        private double entryPrice;

        @Column(name = "stop_loss")
        private double stopLoss;

        @Column(name = "target1")
        private double target1;

        @Column(name = "target2")
        private double target2;

        @Column(name = "risk_reward")
        private double riskReward;

        @Column(name = "confidence_score")
        private int confidenceScore;

        /** FIRED or REJECTED */
        @Column(length = 10)
        private String status;

        /** Rejection reason if status=REJECTED */
        @Column(name = "rejection_reason", length = 100)
        private String rejectionReason;

        /** Candle pattern that triggered entry */
        @Column(name = "candle_pattern", length = 30)
        private String candlePattern;

        @Column(name = "liquidity_sweep")
        private boolean liquiditySweep;

        @Column(name = "sector", length = 40)
        private String sector;

        @Column(name = "created_at")
        private LocalDateTime createdAt;

        @PrePersist
        void prePersist() { createdAt = LocalDateTime.now(); }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // REPOSITORIES
    // ══════════════════════════════════════════════════════════════════════════

    // ══════════════════════════════════════════════════════════════════════════
    // ENTITY: SMC intraday candle store (15m + 5m OHLCV for historical analysis)
    // ══════════════════════════════════════════════════════════════════════════

    @Entity
    @Table(name = "smc_intraday_candles",
            indexes = {
                    @Index(name = "idx_smc_ic_symbol_tf", columnList = "symbol, timeframe, candle_time")
            },
            uniqueConstraints = @UniqueConstraint(columnNames = {"symbol", "timeframe", "candle_time"}))
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SmcIntradayCandle {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        @Column(nullable = false, length = 30)  private String symbol;
        @Column(nullable = false, length = 5)   private String timeframe; // "5m" or "15m"
        @Column(name = "candle_time", nullable = false) private LocalDateTime candleTime;
        private double open, high, low, close;
        private long volume;
        @Column(name = "created_at") private LocalDateTime createdAt;
        @PrePersist void prePersist() { createdAt = LocalDateTime.now(); }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ENTITY: SMC trade log (actual paper trades executed by this strategy)
    // ══════════════════════════════════════════════════════════════════════════

    @Entity
    @Table(name = "smc_trades",
            indexes = { @Index(name = "idx_smc_tr_symbol_date", columnList = "symbol, trade_date") })
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SmcTrade {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        @Column(nullable = false, length = 30)  private String symbol;
        @Column(name = "trade_date")            private LocalDate tradeDate;
        @Column(name = "entry_time")            private LocalDateTime entryTime;
        @Column(name = "exit_time")             private LocalDateTime exitTime;
        @Column(length = 10)                    private String direction;
        @Column(name = "entry_price")           private double entryPrice;
        @Column(name = "exit_price")            private double exitPrice;
        @Column(name = "stop_loss")             private double stopLoss;
        @Column(name = "target1")               private double target1;
        @Column(name = "risk_reward")           private double riskReward;
        @Column(name = "pnl")                   private double pnl;
        @Column(name = "quantity")              private int quantity;
        @Column(name = "setup_type", length = 40) private String setupType;
        @Column(name = "exit_reason", length = 40) private String exitReason;  // SL_HIT, TARGET1, TIME_STOP
        @Column(name = "confidence_score")      private int confidenceScore;
        @Column(name = "created_at")            private LocalDateTime createdAt;
        @PrePersist void prePersist() { createdAt = LocalDateTime.now(); }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ENTITY: SMC analytics (daily performance metrics for strategy review)
    // ══════════════════════════════════════════════════════════════════════════

    @Entity
    @Table(name = "smc_analytics",
            indexes = { @Index(name = "idx_smc_an_date", columnList = "analytics_date") })
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SmcAnalytics {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        @Column(name = "analytics_date", nullable = false, unique = true) private LocalDate analyticsDate;
        @Column(name = "total_signals")         private int totalSignals;
        @Column(name = "signals_fired")         private int signalsFired;
        @Column(name = "signals_rejected")      private int signalsRejected;
        @Column(name = "trades_won")            private int tradesWon;
        @Column(name = "trades_lost")           private int tradesLost;
        @Column(name = "total_pnl")             private double totalPnl;
        @Column(name = "avg_rr")                private double avgRr;
        @Column(name = "avg_confidence")        private double avgConfidence;
        @Column(name = "win_rate")              private double winRate;
        @Column(name = "max_drawdown")          private double maxDrawdown;    // risk metric
        @Column(name = "sharpe_ratio")          private double sharpeRatio;    // performance metric
        @Column(name = "created_at")            private LocalDateTime createdAt;
        @PrePersist void prePersist() { createdAt = LocalDateTime.now(); }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ENTITY: SMC backtest results (historical strategy validation records)
    // ══════════════════════════════════════════════════════════════════════════

    @Entity
    @Table(name = "smc_backtests",
            indexes = { @Index(name = "idx_smc_bt_run", columnList = "run_date") })
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SmcBacktest {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        @Column(name = "run_date")              private LocalDateTime runDate;
        @Column(name = "from_date")             private LocalDate fromDate;
        @Column(name = "to_date")               private LocalDate toDate;
        @Column(name = "total_trades")          private int totalTrades;
        @Column(name = "win_rate")              private double winRate;
        @Column(name = "total_pnl")             private double totalPnl;
        @Column(name = "avg_rr")                private double avgRr;
        @Column(name = "max_drawdown")          private double maxDrawdown;
        @Column(name = "sharpe_ratio")          private double sharpeRatio;
        @Column(name = "min_confidence_used")   private int minConfidenceUsed;
        @Column(name = "notes", length = 500)   private String notes;
        @Column(name = "created_at")            private LocalDateTime createdAt;
        @PrePersist void prePersist() { createdAt = LocalDateTime.now(); }
    }

}