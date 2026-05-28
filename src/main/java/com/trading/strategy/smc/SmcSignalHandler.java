package com.trading.strategy.smc;

import com.trading.events.SmartChannelPullbackSignalEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * SmcSignalHandler
 * ─────────────────────────────────────────────────────────────────────────────
 * Routes SmcSignalEvent into the standard trade execution pipeline.
 *
 * WHY THIS CLASS EXISTS:
 *   SmcInstitutionalStrategyEngine fires SmcSignalEvent — an SMC-owned event
 *   class that has no dependency on SCPS strategy code. This handler converts
 *   it to SmartChannelPullbackSignalEvent (the platform's standard signal
 *   envelope) and publishes it, so the existing pipeline handles it unchanged:
 *
 *     SmcSignalEvent (SMC-owned, fired by SmcInstitutionalStrategyEngine)
 *       ↓ this class
 *     SmartChannelPullbackSignalEvent (strategy="SMC_INSTITUTIONAL_V1")
 *       ↓ SmartChannelSignalHandler (platform router — generic, not SCPS-specific)
 *     TradeApprovedEvent
 *       ↓ PaperTradeExecutionService  [EXISTING — unchanged]
 *       ↓ PaperTradeManagementService [EXISTING — unchanged]
 *
 * ISOLATION GUARANTEE:
 *   - Only processes SmcSignalEvent. No other strategy fires this event.
 *   - SmartChannelSignalHandler is a GENERIC router used by HighRR, SCPS,
 *     News, Scalp, and SMC. It routes by strategyName in the event, so
 *     SMC_INSTITUTIONAL_V1 signals are handled correctly without affecting
 *     any other strategy.
 *   - HighRROrderExecutionService guards on strategyName == HIGH_RR_INTRADAY_V1
 *     so it will ignore SMC signals.
 *   - PaperTradeExecutionService's SELF_MANAGED_STRATEGIES does NOT include
 *     SMC_INSTITUTIONAL_V1, so SMC trades go through standard paper execution.
 *
 * NOTE: SmartChannelSignalHandler must be preserved as long as SMC is active.
 *   It is a platform-level router, not a SCPS-specific class.
 * ─────────────────────────────────────────────────────────────────────────────
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SmcSignalHandler {

    private static final String SMC_STRATEGY = "SMC_INSTITUTIONAL_V1";

    private final ApplicationEventPublisher publisher;

    /**
     * Receives SmcSignalEvent, wraps it in SmartChannelPullbackSignalEvent,
     * and publishes to the standard pipeline.
     *
     * SmartChannelPullbackSignalEvent constructor arg mapping (29 params):
     *  0  source
     *  1  tradingSymbol
     *  2  instrumentToken       long
     *  3  direction             TradeDirection
     *  4  entryPrice            BigDecimal
     *  5  stopLoss              BigDecimal
     *  6  target1               BigDecimal
     *  7  target2               BigDecimal
     *  8  quantity              int
     *  9  riskAmount            BigDecimal
     *  10 strategyName          String   → "SMC_INSTITUTIONAL_V1"
     *  11 probabilityScore      double
     *  12 sectorName            String
     *  13 sectorChangePercent   double   → 0.0 (SMC does not use gap%)
     *  14 channelQuality        String   → setupType (e.g. "SMC_BUY_SETUP")
     *  15 pullbackStrength      String   → "INSTITUTIONAL"
     *  16 pullbackPercent       double   → rrRatio
     *  17 rvol                  double   → 1.0 (not tracked at signal level)
     *  18 vwapAligned           boolean  → liquiditySweepDetected
     *  19 entryType             String   → "LIMIT"
     *  20 marketBias            String   → direction label
     *  21 scoreVwap             int      → 0
     *  22 scoreRvol             int      → 0
     *  23 scoreContinuation     int      → confidenceScore
     *  24 scoreCleanEntry       int      → 0
     *  25 scoreEarlyEntry       int      → 0
     *  26 scoreNoNearbySR       int      → 0
     *  27 totalScore            int
     *  28 timeStopMinutes       int
     */
    @EventListener
    @Async("tradingExecutor")
    public void onSignal(SmcSignalEvent event) {
        if (!SMC_STRATEGY.equals(event.getStrategyName())) return;

        try {
            String marketBias = event.getDirection() != null
                    ? event.getDirection().name() + "_BIAS" : "UNKNOWN_BIAS";

            SmartChannelPullbackSignalEvent signal = new SmartChannelPullbackSignalEvent(
                    this,                               // 0  source
                    event.getTradingSymbol(),            // 1  symbol
                    event.getInstrumentToken(),          // 2  token
                    event.getDirection(),                // 3  direction
                    event.getEntryPrice(),               // 4  entry
                    event.getStopLoss(),                 // 5  sl
                    event.getTarget1(),                  // 6  target1
                    event.getTarget2(),                  // 7  target2
                    event.getQuantity(),                 // 8  qty
                    event.getRiskAmount(),               // 9  riskAmount
                    event.getStrategyName(),             // 10 strategyName = SMC_INSTITUTIONAL_V1
                    event.getProbabilityScore(),         // 11 probabilityScore (double)
                    event.getSectorName(),               // 12 sectorName
                    0.0,                                // 13 sectorChangePercent (not used by SMC)
                    event.getSetupType(),                // 14 channelQuality → setupType
                    "INSTITUTIONAL",                    // 15 pullbackStrength
                    event.getRrRatio(),                  // 16 pullbackPercent → rr
                    1.0,                                // 17 rvol (not tracked at signal)
                    event.isLiquiditySweepDetected(),    // 18 vwapAligned → liquiditySweep
                    "LIMIT",                            // 19 entryType
                    marketBias,                         // 20 marketBias
                    0,                                  // 21 scoreVwap
                    0,                                  // 22 scoreRvol
                    event.getConfidenceScore(),          // 23 scoreContinuation → confidence
                    0,                                  // 24 scoreCleanEntry
                    0,                                  // 25 scoreEarlyEntry
                    0,                                  // 26 scoreNoNearbySR
                    event.getTotalScore(),               // 27 totalScore
                    event.getTimeStopMinutes()           // 28 timeStop
            );

            publisher.publishEvent(signal);

            log.info("[SMC-HANDLER] Signal routed: {} {} entry={} sl={} T1={} RR={} conf={}",
                    event.getTradingSymbol(),
                    event.getDirection(),
                    event.getEntryPrice(),
                    event.getStopLoss(),
                    event.getTarget1(),
                    String.format("%.2f", event.getRrRatio()),
                    event.getConfidenceScore());

        } catch (Exception e) {
            log.error("[SMC-HANDLER] Failed to route signal for {}: {}",
                    event.getTradingSymbol(), e.getMessage());
        }
    }
}