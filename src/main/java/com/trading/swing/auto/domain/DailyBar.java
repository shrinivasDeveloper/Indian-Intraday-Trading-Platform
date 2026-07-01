package com.trading.swing.auto.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One row of NSE's daily bhavcopy — one stock, one trading day, full
 * EOD OHLCV. This is the raw material for sector performance (Rule 1/2)
 * and stock momentum (Rule 3) — both computed from accumulated daily
 * bars, not live ticks (this auto-selection feature runs once a day at
 * 3pm, not intraday).
 */
public record DailyBar(
        String symbol,
        LocalDate tradeDate,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        long volume,
        String series // EQ, BE, etc. — only EQ/BE are genuinely tradable equity
) {}