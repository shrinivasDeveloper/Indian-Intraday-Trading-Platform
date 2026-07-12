package com.trading.momentumstockofday.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * MomentumConfig - configuration for the Momentum Stock of the Day
 * strategy.
 *
 * INDEPENDENCE (per explicit requirement): this entire module
 * (momentumstockofday.*) is completely separate from AI, News, Swing,
 * and Hero-or-Zero - zero shared business logic, zero shared config
 * keys, own dedicated namespace ("momentum-stock-of-day" prefix).
 * Only genuinely neutral, shared infrastructure is used: KiteConnect
 * (the broker bean every strategy uses) and the SectorHeatmapDataService
 * (itself a separate, independent module built earlier - not a
 * strategy).
 *
 * Disabled by default (enabled=false) - must be explicitly turned on.
 */
@Component
@ConfigurationProperties(prefix = "momentum-stock-of-day")
@Getter
@Setter
public class MomentumConfig {

    private boolean enabled = false;

    /** Per spec: "At 9:25 AM, identify and rank all sectors." */
    private String selectionTime = "09:25";

    /** Per spec: "top three performing sectors" / "top three stocks". */
    private int topSectorsCount = 3;
    private int topStocksPerSector = 3;

    /** Fixed capital used for position sizing - same "fixed per trade,
     *  not a depleting balance" model already established for AI/News
     *  earlier this session, applied independently here since this
     *  module has zero dependency on their capital ledger. */
    private double capital = 10000.0;

    /** Per spec: "consolidate for 2 to 4 candles (maximum)." */
    private int minConsolidationCandles = 2;
    private int maxConsolidationCandles = 4;

    /** Candle interval used for consolidation/breakout detection. */
    private String candleInterval = "5minute";

    /** Per spec: "small-bodied candles... not large or highly
     *  volatile." A candle's body (|close-open|) as a fraction of its
     *  own price is compared against this threshold - below it counts
     *  as "small-bodied" and eligible for consolidation. */
    private double maxCandleBodyPct = 0.003; // 0.3%

    /** Per spec: "avoid taking trades if any of the consolidation
     *  candles are unusually large" - a separate, stricter check: if
     *  any single candle's full range (high-low) exceeds this multiple
     *  of the AVERAGE range of the other candles in the same
     *  consolidation window, the whole consolidation is rejected as
     *  too volatile, even if its body was technically small. */
    private double volatilityRejectMultiple = 2.0;

    /** Per explicit request: allow up to this many trades per day
     *  (was fixed at 1). After a trade closes, if this cap hasn't been
     *  reached, monitoring resumes for the remaining, not-yet-traded
     *  candidates, still in strict sector-priority order. */
    private int maxTradesPerDay = 2;

    /** Per spec: "Initial target should be 1:1.5 Risk-to-Reward." */
    private double riskRewardRatio = 1.5;

    /** Per spec: "Once the initial target is reached, automatically
     *  activate a trailing stop-loss." Trail distance as a fraction of
     *  the ORIGINAL consolidation range (not a fixed %), so it scales
     *  naturally with each stock's own volatility. */
    private double trailingStopConsolidationRangeMultiple = 0.5;

    /** Monitoring tick interval - how often the 9 tracked stocks are
     *  re-evaluated for a valid consolidation/breakout. */
    private long monitoringIntervalMs = 60000; // 1 minute

    /** Mandatory EOD exit - same safety principle already proven for
     *  every other strategy this session (Swing 9:20 AM, AI/News 3:15
     *  PM, Hero-or-Zero exit time) - independently defined here. */
    private String forceExitTime = "15:15";

    private long orderPollIntervalMs = 3000;
    private int orderPollMaxAttempts = 10;
}