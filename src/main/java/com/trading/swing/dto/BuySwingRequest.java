package com.trading.swing.dto;

import java.math.BigDecimal;

/**
 * Request body for placing a manual swing BUY.
 * Exactly one of targetPct / targetPrice should be supplied — if both are
 * given, targetPrice wins (more explicit). If neither is given, the
 * request is rejected at validation — a swing trade must have a target.
 */
public record BuySwingRequest(
        String symbol,
        String exchange,
        int quantity,
        BigDecimal targetPct,
        BigDecimal targetPrice
) {}