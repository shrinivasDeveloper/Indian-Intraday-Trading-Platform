package com.trading.herozero.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.Map;

/**
 * HeroZeroConfig - all tunable parameters for the Hero or Zero Monthly
 * Expiry strategy, bound from application.yml under "hero-zero.*".
 *
 * HONEST NOTE on expiry weekday assignment: NSE/BSE have changed which
 * weekday each index's monthly expiry falls on multiple times via
 * SEBI circulars (e.g. the 2025 rationalization to a single weekly
 * expiry per exchange). Rather than hardcode a specific weekday that
 * could silently go stale after the next circular, this is fully
 * configurable - VERIFY the current, correct expiry weekday for each
 * index directly against NSE/BSE's own current circulars before
 * relying on the defaults below.
 */
@Configuration
@ConfigurationProperties(prefix = "hero-zero")
@Getter
@Setter
public class HeroZeroConfig {

    /** Master on/off switch - defaults to false, must be explicitly
     *  enabled, given this places real, undefined-max-loss-until-
     *  premium-decided option orders automatically. */
    private boolean enabled = false;

    private LocalTime entryTime = LocalTime.of(14, 30);
    private LocalTime exitTime  = LocalTime.of(15, 10);
    private LocalTime strikeSelectionStartTime = LocalTime.of(14, 25);

    private int quantityLots = 1;

    /** Target premium per index, per spec. */
    private Map<String, BigDecimal> targetPremium = Map.of(
            "NIFTY",     BigDecimal.valueOf(15),
            "BANKNIFTY", BigDecimal.valueOf(25),
            "FINNIFTY",  BigDecimal.valueOf(15),
            "MIDCPNIFTY", BigDecimal.valueOf(5),
            "SENSEX",    BigDecimal.valueOf(30)
    );

    /**
     * Expiry weekday per index - 1=Monday ... 7=Sunday (ISO-8601).
     * HONEST DEFAULT, NOT VERIFIED LIVE: as of recent NSE/BSE circulars,
     * commonly cited assignments are NIFTY=Tuesday(2), BANKNIFTY/
     * FINNIFTY/MIDCPNIFTY historically Thursday-family, SENSEX=Friday -
     * but these have changed before and may change again. VERIFY against
     * NSE/BSE's current circular before trusting these defaults.
     */
    private Map<String, Integer> expiryWeekday = Map.of(
            "NIFTY",      2,   // Tuesday - VERIFY
            "BANKNIFTY",  4,   // Thursday - VERIFY
            "FINNIFTY",   2,   // Tuesday - VERIFY
            "MIDCPNIFTY", 5,   // Friday - VERIFY
            "SENSEX",     5    // Friday - VERIFY
    );

    /** Minimum liquidity floor - if an option's own recent volume is
     *  below this, it's treated as illiquid and skipped, per spec's
     *  explicit "Option contracts are illiquid" skip condition. */
    private long minLiquidityVolume = 500;

    private int orderPollMaxAttempts = 10;
    private int orderPollIntervalMs = 3000;
}