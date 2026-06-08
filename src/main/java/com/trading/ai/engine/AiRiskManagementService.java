package com.trading.ai.engine;

import com.trading.ai.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * AiRiskManagementService
 *
 * Applies position sizing and final risk validation.
 * Completely isolated from existing RiskManagementService.
 *
 * POSITION SIZING:
 *   riskAmount = capital × riskPerTrade (default 1%)
 *   slDistance = abs(entryPrice - stopLoss)
 *   quantity   = floor(riskAmount / slDistance)
 *   Capped at 5% of capital to prevent over-concentration.
 */
@Service
@ConditionalOnProperty(name = "ai.trading.enabled", havingValue = "true")
@Slf4j
@RequiredArgsConstructor
public class AiRiskManagementService {

    public AiTradeDecision applyRiskManagement(AiTradeDecision decision,
                                               BigDecimal capital,
                                               double riskPerTrade) {
        double cap     = capital.doubleValue();
        double entry   = decision.getEntryPrice().doubleValue();
        double sl      = decision.getStopLoss().doubleValue();
        double slDist  = Math.abs(entry - sl);

        if (slDist <= 0) {
            log.warn("[AI-RISK] SL distance zero for {} — skipping", decision.getSymbol());
            return decision.withPositionSize(0);
        }

        double riskAmount = cap * riskPerTrade;                    // e.g. ₹1000
        int qty = (int) Math.floor(riskAmount / slDist);           // shares

        // Cap: position value ≤ 5% of capital
        double maxQtyByCap = Math.floor(cap * 0.05 / entry);
        qty = (int) Math.min(qty, maxQtyByCap);

        if (qty <= 0) {
            log.warn("[AI-RISK] Quantity 0 for {} (entry={} sl={} risk={})",
                    decision.getSymbol(), entry, sl, riskAmount);
            return decision.withPositionSize(0);
        }

        BigDecimal riskAmt = BigDecimal.valueOf(qty * slDist).setScale(2, RoundingMode.HALF_UP);

        log.debug("[AI-RISK] {}: qty={} entry={} sl={} risk=₹{} rr={}",
                decision.getSymbol(), qty, entry, sl, riskAmt, decision.getRrRatio());

        return decision.withPositionSize(qty).withRiskAmount(riskAmt);
    }
}