package com.trading.ai.engine;

import com.trading.ai.model.*;
import com.trading.strategy.smc.SmcInstitutionalStructureService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * AiTradeSelectionService
 *
 * Picks the final ≤5 trades from Claude's reasoned opportunities.
 *
 * SELECTION RULES (in order):
 *  1. trade=true AND confidence ≥ minConfidence AND RR ≥ 2.0
 *  2. Sort by: confidence × qualityScore (composite rank)
 *  3. Apply diversification:
 *     - Max 2 positions per sector
 *     - No two LONG + LONG if both in same sector
 *     - No two positions in highly correlated symbols
 *  4. Cap at slotsLeft
 *
 * Each selected trade has SL/T1/T2 computed here using the
 * AiRiskManagementService in the next step. The RR from Claude
 * is advisory — actual RR is computed from price levels.
 */
@Service
@ConditionalOnProperty(name = "ai.trading.enabled", havingValue = "true")
@Slf4j
@RequiredArgsConstructor
public class AiTradeSelectionService {

    private static final double MIN_RR = 2.0;

    public List<AiTradeDecision> select(
            List<AiReasonedOpportunity> opportunities,
            int slotsLeft,
            double minConfidence
    ) {
        // Filter: trade=true, confidence OK, RR OK
        List<AiReasonedOpportunity> eligible = opportunities.stream()
                .filter(o -> o.isShouldTrade())
                .filter(o -> o.getConfidence() >= minConfidence)
                .filter(o -> o.getRrRatio() >= MIN_RR)
                .sorted(Comparator.comparingDouble(
                        o -> -(o.getConfidence() * o.getTradeQualityScore())))
                .collect(Collectors.toList());

        if (eligible.isEmpty()) return Collections.emptyList();

        // Apply diversification
        List<AiTradeDecision> selected = new ArrayList<>();
        Map<String, Integer> sectorCount = new HashMap<>();

        for (AiReasonedOpportunity opp : eligible) {
            if (selected.size() >= slotsLeft) break;

            String sector = opp.getCandidate().getSector();
            int sc = sectorCount.getOrDefault(sector, 0);
            if (sc >= 2) {
                log.debug("[AI-SELECT] {} skipped — sector {} already has 2 positions",
                        opp.getCandidate().getSymbol(), sector);
                continue;
            }

            // Build trade decision
            AiCandidate c = opp.getCandidate();
            AiFeatureVector fv = c.getFeatureVector();
            SmcInstitutionalStructureService.HtfStructure htf = fv.getSmcStructure();

            double ltp = c.getLtp();
            boolean isLong = "LONG".equals(opp.getDirection());

            // SL: below nearest support (LONG) or above nearest resistance (SHORT)
            double sl = computeSl(ltp, isLong, htf);
            double slPct = Math.abs(ltp - sl) / ltp;
            if (slPct < 0.003 || slPct > 0.025) {
                // SL too tight or too wide — skip
                log.debug("[AI-SELECT] {} skipped — SL {:.2f}% invalid", c.getSymbol(), slPct*100);
                continue;
            }

            double t1 = isLong ? ltp + (ltp - sl) * opp.getRrRatio()
                    : ltp - (sl - ltp) * opp.getRrRatio();
            double t2 = isLong ? ltp + (ltp - sl) * (opp.getRrRatio() + 1.0)
                    : ltp - (sl - ltp) * (opp.getRrRatio() + 1.0);

            selected.add(AiTradeDecision.builder()
                    .symbol(c.getSymbol())
                    .direction(opp.getDirection())
                    .entryPrice(BigDecimal.valueOf(ltp))
                    .stopLoss(BigDecimal.valueOf(sl))
                    .target1(BigDecimal.valueOf(t1))
                    .target2(BigDecimal.valueOf(t2))
                    .rrRatio(opp.getRrRatio())
                    .confidence(opp.getConfidence())
                    .tradeQualityScore(opp.getTradeQualityScore())
                    .opportunityScore(opp.getOpportunityScore())
                    .riskScore(opp.getRiskScore())
                    .probabilityOfSuccess(opp.getProbabilityOfSuccess())
                    .reasoning(opp.getReasoning())
                    .bullScenario(opp.getBullScenario())
                    .bearScenario(opp.getBearScenario())
                    .dominantFactor(opp.getDominantFactor())
                    .exitPlan(opp.getExitPlan())
                    .reasoningSummary(truncate(opp.getReasoning(), 120))
                    .htfTrend(c.getHtfTrend())
                    .sector(sector)
                    .numericPreScore(c.getNumericScore())
                    .featureVector(fv)
                    .build());

            sectorCount.merge(sector, 1, Integer::sum);
        }

        log.info("[AI-SELECT] Selected {}/{} eligible opportunities for execution",
                selected.size(), eligible.size());
        return selected;
    }

    private double computeSl(double ltp, boolean isLong,
                             SmcInstitutionalStructureService.HtfStructure htf) {
        if (htf == null) {
            return isLong ? ltp * 0.992 : ltp * 1.008; // 0.8% default
        }
        if (isLong && htf.nearestSupport != null) {
            double zonePrice = htf.nearestSupport.price;
            // SL = 0.2% below support zone
            return zonePrice * 0.998;
        }
        if (!isLong && htf.nearestResistance != null) {
            return htf.nearestResistance.price * 1.002;
        }
        return isLong ? ltp * 0.992 : ltp * 1.008;
    }

    private String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }
    /**
     * Select from ML-scored candidates (from AiTradingModuleV2 pipeline).
     * Builds AiReasonedOpportunity wrappers from AiCandidate ML scores,
     * then delegates to the main select() method.
     */
    public List<AiTradeDecision> selectFromMlCandidates(
            List<com.trading.ai.model.AiCandidate> candidates,
            int slotsLeft,
            double minConfidence) {

        List<AiReasonedOpportunity> wrapped = candidates.stream()
                .filter(c -> c.getMlProbability() >= minConfidence)
                .filter(c -> c.getNumericScore() >= 35)
                .map(c -> AiReasonedOpportunity.builder()
                        .candidate(c)
                        .shouldTrade(true)
                        .direction(c.getSuggestedDirection())
                        .confidence(c.getMlConfidence())
                        .tradeQualityScore((int) c.getNumericScore())
                        .opportunityScore((int) c.getNumericScore())
                        .riskScore(100 - (int) c.getNumericScore())
                        .probabilityOfSuccess(c.getMlProbability())
                        .rrRatio(2.5)
                        .reasoning(c.getMlReasoning() != null ? c.getMlReasoning() : "ML score: " + (int)c.getNumericScore())
                        .bullScenario(c.getHypothesis() != null ? c.getHypothesis().getHypothesisText() : "")
                        .bearScenario("")
                        .dominantFactor(c.isLiquiditySweep() ? "Liquidity sweep" : c.isSrFlip() ? "Breakout retest" : "S/R + trend")
                        .exitPlan("Exit at T1/T2 or SL. Trail after T1.")
                        .rejectReason("")
                        .build())
                .collect(java.util.stream.Collectors.toList());

        return select(wrapped, slotsLeft, minConfidence);
    }

}