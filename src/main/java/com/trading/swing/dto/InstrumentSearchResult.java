package com.trading.swing.dto;

import java.math.BigDecimal;

/**
 * One row in the instrument search/picker UI.
 */
public record InstrumentSearchResult(
        String symbol,
        String companyName,
        String exchange,
        BigDecimal ltp,
        BigDecimal changePct
) {}