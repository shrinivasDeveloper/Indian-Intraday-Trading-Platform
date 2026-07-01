package com.trading.swing.auto.domain;

import java.math.BigDecimal;

/**
 * A fully-evaluated stock candidate — sector, momentum, fundamentals,
 * and the final confidence score that determines selection.
 */
public record StockCandidate(
        String symbol,
        String companyName,
        String exchange,
        String sectorName,
        BigDecimal lastClose,
        FundamentalSnapshot fundamentals,
        int confidenceScore,        // 0-100
        String scoreBreakdown       // human-readable, for logging/dashboard
) {}