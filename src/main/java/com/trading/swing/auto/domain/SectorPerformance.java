package com.trading.swing.auto.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * One sector's aggregated performance across the 4 timeframes Rule 2
 * requires, plus whether it actually clears every qualification gate.
 *
 * weeklyPct/monthlyPct/yearlyPct can be NULL — this means genuinely
 * insufficient backfilled history for that timeframe yet, NOT a real
 * 0% reading. Never treat a null here as zero; check disqualificationReason
 * for the actual, human-readable explanation of why a sector didn't
 * qualify (which correctly distinguishes "insufficient data" from "real
 * performance below threshold").
 */
public record SectorPerformance(
        String sectorName,
        BigDecimal dailyPct,
        BigDecimal weeklyPct,
        BigDecimal monthlyPct,
        BigDecimal yearlyPct,
        List<String> symbolsInSector,
        boolean qualifies,
        String disqualificationReason // null if it qualifies
) {}