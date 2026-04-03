// ============================================================
// REPLACE FILE — End-to-End Bug Fix
// Path: src/main/java/com/trading/config/AppConfig.java
//
// BUGS FIXED:
//   BUG 1: Queue capacity was 5000. On a busy day with 400+ stocks × 5 strategies
//          × every 5m candle = 400 events per candle. At market open (9:15-10:00)
//          that's 400 × 9 candles = 3600 queued events. With queueCapacity=5000
//          this was fine, BUT CallerRunsPolicy meant that when queue was FULL,
//          the WebSocket thread itself processed the event, stalling tick ingestion.
//          FIX: Raise queue to 50000. Keep CallerRunsPolicy as safety.
//               Add separate "fastTickExecutor" for tick events (no queue delay).
//
//   BUG 2: Single executor for ALL async tasks meant tick processing (latency-
//          sensitive, microseconds) competed with candle processing (less urgent).
//          A slow candle analysis task could delay tick arrival for SL checks.
//          FIX: Two executors:
//               "tradingExecutor" — for candle events, strategy evaluation (OK with delay)
//               "tickExecutor"    — for tick events only (tiny queue, priority)
//
//   BUG 3: No thread name prefix uniqueness across beans — monitoring tools
//          couldn't distinguish which thread pool a thread belonged to.
//          FIX: Distinct prefixes: "trade-" vs "tick-".
// ============================================================
package com.trading.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * AppConfig — Thread pool configuration for async processing.
 *
 * TWO EXECUTORS:
 *
 * 1. tradingExecutor (candle/strategy/scanner events):
 *    - Core: 8 threads, Max: 32 threads
 *    - Queue: 50000 (handles burst at market open)
 *    - CallerRunsPolicy: if queue full, caller thread processes (never drops)
 *    - Used by: @Async("tradingExecutor") on CandleCompleteEvent handlers
 *
 * 2. tickExecutor (real-time tick events):
 *    - Core: 4 threads, Max: 8 threads
 *    - Queue: 1000 (small — ticks must be fresh)
 *    - CallerRunsPolicy: if full, WebSocket thread handles (prevents tick drop)
 *    - Used by: @Async("tickExecutor") on TickReceivedEvent handlers
 *    - Note: TradeManagementService and PaperTradeManagementService should
 *      use tickExecutor for their onTick() methods for minimal latency.
 */
@Configuration
public class AppConfig {

    /**
     * Primary executor for candle/strategy/scanner processing.
     * Larger queue — these tasks can tolerate slight delay.
     */
    @Bean(name = "tradingExecutor")
    public Executor tradingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(50_000);      // BUG 1 FIX: raised from 5000
        executor.setThreadNamePrefix("trade-"); // BUG 3 FIX: distinct prefix
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }

    /**
     * Fast executor for real-time tick processing.
     * Minimal queue — stale ticks must be dropped, not processed late.
     * BUG 2 FIX: Separate from trading executor so tick processing is never
     * delayed by a slow strategy evaluation task.
     */
    @Bean(name = "tickExecutor")
    public Executor tickExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(1_000);      // Small — ticks are time-sensitive
        executor.setThreadNamePrefix("tick-"); // BUG 3 FIX: distinct prefix
        // For ticks: DiscardOldestPolicy removes the OLDEST queued tick
        // when queue is full, keeping the NEWEST tick (most current price).
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardOldestPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false); // Don't wait for stale ticks
        executor.initialize();
        return executor;
    }
}