-- ============================================================
-- Indian Intraday Trading Platform — MySQL Schema
-- ============================================================

CREATE TABLE IF NOT EXISTS zerodha_tokens (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_id   VARCHAR(30)    NOT NULL,
    access_token VARCHAR(1000)  NOT NULL,
    public_token VARCHAR(1000),
    user_id      VARCHAR(30),
    token_date   DATE           NOT NULL,
    expires_at   DATETIME,
    created_at   DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_account_date (account_id, token_date)
);

CREATE TABLE IF NOT EXISTS trades (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    trade_date        DATE         NOT NULL,
    trading_symbol    VARCHAR(50)  NOT NULL,
    instrument_token  BIGINT       NOT NULL,
    direction         VARCHAR(5)   NOT NULL,
    status            VARCHAR(30)  NOT NULL DEFAULT 'PENDING',
    entry_time        DATETIME,
    entry_price       DECIMAL(15,4),
    entry_order_id    VARCHAR(50),
    quantity          INT,
    stop_loss         DECIMAL(15,4),
    target            DECIMAL(15,4),
    risk_amount       DECIMAL(15,4),
    sl_order_id       VARCHAR(50),
    exit_time         DATETIME,
    exit_price        DECIMAL(15,4),
    exit_reason       VARCHAR(100),
    gross_pnl         DECIMAL(15,4),
    net_pnl           DECIMAL(15,4),
    probability_score DECIMAL(5,2),
    strategy_name     VARCHAR(50),
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_trades_date   (trade_date),
    INDEX idx_trades_symbol (trading_symbol, trade_date)
);

CREATE TABLE IF NOT EXISTS `orders` (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    trade_id         BIGINT,
    zerodha_order_id VARCHAR(50) UNIQUE,
    trading_symbol   VARCHAR(50)  NOT NULL,
    order_type       VARCHAR(10)  NOT NULL,
    transaction_type VARCHAR(5)   NOT NULL,
    quantity         INT          NOT NULL,
    price            DECIMAL(15,4),
    trigger_price    DECIMAL(15,4),
    status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    filled_quantity  INT          DEFAULT 0,
    average_price    DECIMAL(15,4),
    rejection_reason TEXT,
    placed_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (trade_id) REFERENCES trades(id)
);

CREATE TABLE IF NOT EXISTS candles (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    instrument_token BIGINT        NOT NULL,
    trading_symbol   VARCHAR(50)   NOT NULL,
    timeframe        VARCHAR(15)   NOT NULL,
    open             DECIMAL(15,4) NOT NULL,
    high             DECIMAL(15,4) NOT NULL,
    low              DECIMAL(15,4) NOT NULL,
    close            DECIMAL(15,4) NOT NULL,
    volume           BIGINT        NOT NULL,
    oi               BIGINT        DEFAULT 0,
    candle_time      DATETIME      NOT NULL,
    is_complete      TINYINT(1)    NOT NULL DEFAULT 0,
    INDEX idx_candles_lookup (instrument_token, timeframe, candle_time)
);

CREATE TABLE IF NOT EXISTS market_regimes (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    index_symbol VARCHAR(20)  NOT NULL,
    timeframe    VARCHAR(15)  NOT NULL,
    regime       VARCHAR(30)  NOT NULL,
    ema20        DECIMAL(15,4),
    ema50        DECIMAL(15,4),
    ema200       DECIMAL(15,4),
    atr_percent  DECIMAL(8,4),
    detected_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS probability_scores (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    trading_symbol   VARCHAR(50)  NOT NULL,
    instrument_token BIGINT       NOT NULL,
    total_score      DECIMAL(5,2) NOT NULL,
    decision         VARCHAR(10)  NOT NULL,
    suggested_dir    VARCHAR(5),
    calculated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS circuit_breaker_state (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    state_date     DATE          NOT NULL UNIQUE,
    is_active      TINYINT(1)    NOT NULL DEFAULT 1,
    trades_today   INT           NOT NULL DEFAULT 0,
    daily_pnl      DECIMAL(15,4) NOT NULL DEFAULT 0,
    weekly_pnl     DECIMAL(15,4) NOT NULL DEFAULT 0,
    monthly_pnl    DECIMAL(15,4) NOT NULL DEFAULT 0,
    disable_reason TEXT,
    last_updated   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

INSERT IGNORE INTO circuit_breaker_state (state_date) VALUES (CURDATE());

CREATE TABLE IF NOT EXISTS notification_log (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    channel          VARCHAR(20)  NOT NULL,
    message          TEXT         NOT NULL,
    is_sent          TINYINT(1)   NOT NULL DEFAULT 0,
    sent_at          DATETIME,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS backtest_runs (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    strategy_name    VARCHAR(50)   NOT NULL,
    start_date       DATE          NOT NULL,
    end_date         DATE          NOT NULL,
    initial_capital  DECIMAL(15,4) NOT NULL,
    total_trades     INT,
    win_rate         DECIMAL(6,4),
    profit_factor    DECIMAL(8,4),
    max_drawdown_pct DECIMAL(8,4),
    expectancy       DECIMAL(15,4),
    final_capital    DECIMAL(15,4),
    started_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS backtest_trades (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id           BIGINT        NOT NULL,
    trade_date       DATE          NOT NULL,
    trading_symbol   VARCHAR(50)   NOT NULL,
    direction        VARCHAR(5)    NOT NULL,
    entry_price      DECIMAL(15,4) NOT NULL,
    exit_price       DECIMAL(15,4),
    quantity         INT           NOT NULL,
    stop_loss        DECIMAL(15,4),
    target           DECIMAL(15,4),
    net_pnl          DECIMAL(15,4),
    probability_score DECIMAL(5,2),
    exit_reason      VARCHAR(50),
    entry_time       DATETIME,
    exit_time        DATETIME,
    FOREIGN KEY (run_id) REFERENCES backtest_runs(id) ON DELETE CASCADE
);
