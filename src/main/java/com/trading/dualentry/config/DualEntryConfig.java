package com.trading.dualentry.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.LocalTime;

/**
 * DualEntryConfig — configuration for the new, isolated Breakout +
 * Pullback strategy (per explicit user request).
 *
 * COMPLETELY SEPARATE from MomentumConfig - own YAML namespace
 * (dual-entry-strategy.*), own defaults, zero shared fields. Changing
 * a value here has zero effect on Momentum's own config or behavior.
 */
@Component
@ConfigurationProperties(prefix = "dual-entry-strategy")
@Getter
@Setter
public class DualEntryConfig {

    private boolean enabled = false; // disabled by default - explicit opt-in, same safety
    // posture as every other new strategy this session

    private LocalTime selectionTime = LocalTime.of(9, 25);
    private int topSectorsCount = 2;
    private int topStocksPerSector = 3;

    private double capital = 10000.0;
    private int minConsolidationCandles = 2;
    private int maxConsolidationCandles = 8;
    private double maxCandleBodyPct = 0.003;
    private double volatilityRejectMultiple = 2.0;
    private double riskRewardRatio = 1.5;
    private int maxTradesPerDay = 2;
    private double trailingStopConsolidationRangeMultiple = 0.5;

    private long monitoringIntervalMs = 30000;
    private LocalTime forceExitTime = LocalTime.of(15, 15);

    private long orderPollIntervalMs = 3000;
    private int orderPollMaxAttempts = 10;

    // Order Book gate thresholds - RESOLVED (per explicit user
    // authorization, no longer pending): spec items #10 and #11
    // described the same "Hard Order Book Gate" with conflicting
    // numbers (#10: uniform 1.50 ratio, 3 samples; #11: asymmetric
    // 1.50 LONG / 3.00 SHORT, 5 samples). #11 is adopted as final -
    // it is the more specific, directionally-complete version, and a
    // uniform 1.50 would leave SHORT under-protected relative to what
    // #11 explicitly specifies for that direction. This is the
    // authoritative, production value - not subject to further change
    // without an explicit new instruction.
    private double orderBookLongRatioMin = 1.50;
    private double orderBookShortRatioMin = 3.00;
    private int orderBookConsecutiveSamples = 5;
}