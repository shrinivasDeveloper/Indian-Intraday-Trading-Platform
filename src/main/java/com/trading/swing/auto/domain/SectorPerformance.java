package com.trading.swing.auto.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * One sector's aggregated performance - PURE RANKING data only.
 *
 * CORRECTED (per explicit spec correction: "sector qualification is
 * different... stock qualification, not sector"). Sectors are NEVER
 * gated/disqualified by a threshold - they are only RANKED, using
 * Daily/Weekly/Monthly performance (yearly is NOT part of sector
 * ranking per the corrected spec - removed entirely). The actual
 * pass/fail qualification threshold (4-6% daily, >=15% weekly, monthly
 * >= weekly+5%) applies to INDIVIDUAL STOCKS within a sector, checked
 * separately in AutoStockSelectionEngine/StockQualificationService -
 * never to the sector's own averaged numbers.
 *
 * weeklyPct/monthlyPct can be NULL - genuinely insufficient backfilled
 * history for that timeframe yet, NOT a real 0% reading.
 */
public record SectorPerformance(
        String sectorName,
        BigDecimal dailyPct,
        BigDecimal weeklyPct,
        BigDecimal monthlyPct,
        List<String> symbolsInSector
) {}