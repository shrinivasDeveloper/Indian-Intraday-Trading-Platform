package com.trading.risk.service;

import com.trading.events.ProbabilityScoreEvent;
import com.trading.events.TradeApprovedEvent;
import com.trading.position.service.PositionSizerService;
import com.trading.sector.service.SectorClassificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RiskManagementService — gates every signal through 4 checks before approving.
 *
 * PRESERVED FIXES:
 *   Fix 1: Sector exposure uses SectorClassificationService (not hardcoded list)
 *   Fix 2: circuitBreaker.recordTradeEntered() only called AFTER trade approved
 *   Fix 3: Daily sector exposure reset at 8:45 IST
 *
 * NEW FIX — timeStopMinutes pipeline completion:
 *   ProbabilityScoreEvent now carries timeStopMinutes (added by StrategyEvaluatorService).
 *   This service reads it and passes it into TradeApprovedEvent using the new
 *   12-param constructor. Without this step, the time stop value arrived here
 *   and was discarded — it never reached PaperTradeExecutionService or
 *   PaperTradeManagementService.
 *
 *   VAP Pullback: timeStopMinutes=30 flows all the way to tick-level enforcement.
 *   All other strategies: timeStopMinutes=0 (no time stop, only 15:00 IST close).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RiskManagementService {

    private final ApplicationEventPublisher   publisher;
    private final CircuitBreakerService       circuitBreaker;
    private final PositionSizerService        positionSizer;
    private final SectorClassificationService sectorClassify;

    @Value("${trading.capital:100000}")
    private String capitalStr;

    // sector → number of open trades in that sector
    private final Map<String, Integer> sectorExposure = new ConcurrentHashMap<>();

    private BigDecimal capital() { return new BigDecimal(capitalStr); }

    // ══════════════════════════════════════════════════════════════════════════
    // Gate every signal through 4 checks
    // ══════════════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tradingExecutor")
    public void onProbabilityScore(ProbabilityScoreEvent event) {
        if (!"EXECUTE".equals(event.getDecision())) return;

        String     sym = event.getTradingSymbol();
        BigDecimal cap = capital();

        // Gate 1 — Circuit breaker
        CircuitBreakerService.Permission perm = circuitBreaker.checkPermission(cap);
        if (!perm.isAllowed()) {
            log.warn("RISK REJECTED {}: CB — {}", sym, perm.reason());
            return;
        }

        // Gate 2 — One trade per sector (Fix 1: real sector classification)
        String sector = sectorClassify.getSector(sym);
        if (sectorExposure.getOrDefault(sector, 0) >= 1) {
            log.warn("RISK REJECTED {}: sector '{}' already has open trade", sym, sector);
            return;
        }

        // Gate 3 — Valid entry and SL
        if (event.getEntryPrice() == null
                || event.getEntryPrice().compareTo(BigDecimal.ZERO) == 0
                || event.getStopLoss() == null
                || event.getStopLoss().compareTo(BigDecimal.ZERO) == 0) {
            log.warn("RISK REJECTED {}: entry or SL is zero", sym);
            return;
        }

        // Gate 4 — Position sizing (1% risk rule)
        PositionSizerService.PositionSize size = positionSizer.calculate(
                cap, event.getEntryPrice(), event.getStopLoss(),
                sym, event.getDirection().name());

        if (!size.isValid()) {
            log.warn("RISK REJECTED {}: sizing — {}", sym, size.invalidReason());
            return;
        }

        // Fix 2: update CB and sector ONLY after all gates pass
        sectorExposure.merge(sector, 1, Integer::sum);
        circuitBreaker.recordTradeEntered();

        // FIX: pass timeStopMinutes from the signal through to execution
        // Uses the new 12-param TradeApprovedEvent constructor.
        // timeStopMinutes=0 for strategies without a time stop — no behaviour change.
        publisher.publishEvent(new TradeApprovedEvent(this,
                sym, event.getInstrumentToken(),
                event.getDirection(), event.getEntryPrice(),
                event.getStopLoss(), event.getTarget(),
                size.quantity(), size.actualRisk(),
                event.getTotalScore(), event.getStrategyName(),
                event.getTimeStopMinutes()));   // FIX: was missing

        log.info("TRADE APPROVED: {} dir={} qty={} entry={} sl={} target={} sector={} timeStop={}min",
                sym, event.getDirection(), size.quantity(),
                event.getEntryPrice(), event.getStopLoss(), event.getTarget(), sector,
                event.getTimeStopMinutes() > 0 ? event.getTimeStopMinutes() : "none");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Called by TradeExecutionService / PaperTradeExecutionService on close
    // ══════════════════════════════════════════════════════════════════════════

    public void onTradeClosed(String symbol, BigDecimal pnl) {
        circuitBreaker.recordPnl(pnl);
        // Fix 3: release sector slot when trade closes
        String sector = sectorClassify.getSector(symbol);
        sectorExposure.merge(sector, -1, (a, b) -> Math.max(0, a + b));
        log.debug("Sector '{}' slot released for {}", sector, symbol);
    }

    // Fix 3: reset sector exposure daily at 8:45 IST
    @Scheduled(cron = "0 45 8 * * MON-FRI", zone = "Asia/Kolkata")
    public void resetDaily() {
        sectorExposure.clear();
        log.info("RiskManagementService: sector exposure reset");
    }
}