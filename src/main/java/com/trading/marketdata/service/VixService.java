package com.trading.marketdata.service;

import com.trading.events.TickReceivedEvent;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * India VIX monitor.
 * VIX token 264969 is subscribed in the main WebSocket stream.
 *
 * Rules:
 *   VIX < 20  → Normal   → full position size, standard RR requirements
 *   20-25     → Elevated → half position size, RR +0.5 on all windows
 *   > 25      → Extreme  → ZERO trades all day
 */
@Service
@Slf4j
public class VixService {

    private static final long   VIX_TOKEN       = 264969L;
    private static final double VIX_NORMAL_MAX  = 20.0;
    private static final double VIX_EXTREME_MIN = 25.0;

    @Getter private volatile double    currentVix     = 16.0; // safe default
    @Getter private volatile VixRegime regime         = VixRegime.NORMAL;
    @Getter private volatile LocalDate lastUpdatedDate = null;

    public enum VixRegime {
        NORMAL,   // VIX < 20  — full rules
        ELEVATED, // VIX 20-25 — half size, +0.5 RR
        EXTREME   // VIX > 25  — no trades today
    }

    @EventListener
    @Async("tradingExecutor")
    public void onTick(TickReceivedEvent tick) {
        if (tick.getInstrumentToken() != VIX_TOKEN) return;

        double vix = tick.getLastTradedPrice().doubleValue();
        if (vix <= 0) return;

        currentVix = vix;
        lastUpdatedDate = LocalDate.now();

        VixRegime newRegime;
        if (vix < VIX_NORMAL_MAX)       newRegime = VixRegime.NORMAL;
        else if (vix <= VIX_EXTREME_MIN) newRegime = VixRegime.ELEVATED;
        else                             newRegime = VixRegime.EXTREME;

        if (newRegime != regime) {
            log.warn("VIX regime changed: {} → {} (VIX={})", regime, newRegime, vix);
            if (newRegime == VixRegime.EXTREME) {
                log.warn("VIX ABOVE 25 — ZERO TRADES TODAY. VIX={}", vix);
            }
            regime = newRegime;
        }
    }

    public boolean isTradeAllowed() {
        return regime != VixRegime.EXTREME;
    }

    public boolean isElevated() {
        return regime == VixRegime.ELEVATED;
    }

    /** Position size multiplier based on VIX */
    public double positionSizeMultiplier() {
        return regime == VixRegime.ELEVATED ? 0.5 : 1.0;
    }

    /** Extra RR requirement added due to VIX */
    public double extraRrRequirement() {
        return regime == VixRegime.ELEVATED ? 0.5 : 0.0;
    }
}