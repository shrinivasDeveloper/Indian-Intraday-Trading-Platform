package com.trading.swing.auto.domain;

import java.math.BigDecimal;

/**
 * A fully-evaluated stock candidate - sector, momentum, and the final
 * confidence score that determines selection.
 *
 * REMOVED (per explicit instruction: "remove the fundamentals in my
 * swing trading completely"). The fundamentals field previously here
 * has been removed - confirmed via a full codebase search that nothing
 * outside AutoStockSelectionEngine ever read StockCandidate.fundamentals(),
 * so this is a safe, clean removal with zero impact elsewhere.
 */
public record StockCandidate(
        String symbol,
        String companyName,
        String exchange,
        String sectorName,
        BigDecimal lastClose,
        int confidenceScore,        // 0-100
        String scoreBreakdown       // human-readable, for logging/dashboard
) {}