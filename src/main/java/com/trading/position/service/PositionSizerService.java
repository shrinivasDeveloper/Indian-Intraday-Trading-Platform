package com.trading.position.service;

import com.trading.execution.service.MarginCheckService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * FIX #6: No longer injects ZerodhaOrderClient directly.
 * Uses MarginCheckService instead — breaks the circular dependency.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PositionSizerService {

    private final MarginCheckService marginCheckService;

    @Value("${trading.risk-per-trade:0.01}")   private BigDecimal riskPerTrade;
    @Value("${trading.max-position-pct:0.20}") private BigDecimal maxPositionPct;

    public PositionSize calculate(BigDecimal capital, BigDecimal entry,
                                   BigDecimal stopLoss, String symbol,
                                   String direction) {
        BigDecimal slDist = entry.subtract(stopLoss).abs();
        if (slDist.compareTo(BigDecimal.ZERO) == 0)
            return PositionSize.invalid("Stop-loss distance is zero");

        // Step 1: formula-based quantity
        BigDecimal riskAmt = capital.multiply(riskPerTrade);
        int qty = riskAmt.divide(slDist, MathContext.DECIMAL32)
            .setScale(0, RoundingMode.FLOOR).intValue();

        // Cap at max position size
        int maxQty = capital.multiply(maxPositionPct)
            .divide(entry, MathContext.DECIMAL32)
            .setScale(0, RoundingMode.FLOOR).intValue();
        qty = Math.min(qty, maxQty);
        if (qty <= 0) return PositionSize.invalid("Calculated quantity is zero");

        // Step 2: margin check (non-fatal)
        MarginCheckService.MarginResult margin =
            marginCheckService.check(symbol, direction, qty, entry);
        qty = margin.recommendedQty();
        if (qty <= 0) return PositionSize.invalid("Insufficient margin");

        // Step 3: final metrics
        BigDecimal actualRisk    = slDist.multiply(BigDecimal.valueOf(qty));
        BigDecimal actualRiskPct = actualRisk.divide(capital, MathContext.DECIMAL32)
            .multiply(BigDecimal.valueOf(100));

        log.info("Position sized: {} qty={} risk={}% entry={} sl={}",
            symbol, qty,
            actualRiskPct.setScale(2, RoundingMode.HALF_UP),
            entry, stopLoss);

        return PositionSize.valid(qty, riskAmt, actualRisk, actualRiskPct,
            entry.multiply(BigDecimal.valueOf(qty)));
    }

    public record PositionSize(
        boolean    valid,
        int        quantity,
        BigDecimal intendedRisk,
        BigDecimal actualRisk,
        BigDecimal actualRiskPct,
        BigDecimal totalValue,
        String     invalidReason
    ) {
        public static PositionSize valid(int q, BigDecimal ir, BigDecimal ar,
                                          BigDecimal arp, BigDecimal tv) {
            return new PositionSize(true, q, ir, ar, arp, tv, null);
        }
        public static PositionSize invalid(String reason) {
            return new PositionSize(false, 0, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, reason);
        }
        public boolean isValid() { return valid; }
    }
}
