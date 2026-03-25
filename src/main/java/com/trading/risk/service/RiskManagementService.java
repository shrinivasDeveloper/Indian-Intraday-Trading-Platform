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
 * RiskManagementService — FIXED.
 *
 * Fix 1: Sector exposure uses SectorClassificationService properly.
 *   Old: only 20 hardcoded symbols got real sectors → 490 stocks all
 *        went to "Other" → only 1 trade ever allowed (sector limit).
 *   New: all 500 Nifty500 stocks get their real sector → diversity works.
 *
 * Fix 2: circuitBreaker.recordTradeEntered() moved to AFTER trade approved.
 *   Old: CB count incremented even if order rejected → CB trips on failures.
 *   New: CB count only incremented when TradeApprovedEvent actually fires.
 *
 * Fix 3: Daily sector exposure reset at 8:45 IST.
 *   Old: sectorExposure never reset → after first trade, that sector
 *        blocked ALL day even after the trade closed.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RiskManagementService {

    private final ApplicationEventPublisher  publisher;
    private final CircuitBreakerService      circuitBreaker;
    private final PositionSizerService       positionSizer;
    private final SectorClassificationService sectorClassify; // FIX 1

    @Value("${trading.capital:100000}")
    private String capitalStr;

    // sector → number of open trades in that sector
    private final Map<String, Integer> sectorExposure = new ConcurrentHashMap<>();

    private BigDecimal capital() { return new BigDecimal(capitalStr); }

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

        // Gate 2 — One trade per sector (using real sector classification)
        // FIX 1: use SectorClassificationService not hardcoded list
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

        // FIX 2: update sector exposure + CB ONLY when actually approving
        // (old code did this before order placement → CB tripped on failures)
        sectorExposure.merge(sector, 1, Integer::sum);
        circuitBreaker.recordTradeEntered();

        publisher.publishEvent(new TradeApprovedEvent(this,
                sym, event.getInstrumentToken(),
                event.getDirection(), event.getEntryPrice(),
                event.getStopLoss(), event.getTarget(),
                size.quantity(), size.actualRisk(),
                event.getTotalScore(), event.getStrategyName()));

        log.info("TRADE APPROVED: {} dir={} qty={} entry={} sl={} target={} sector={}",
                sym, event.getDirection(), size.quantity(),
                event.getEntryPrice(), event.getStopLoss(), event.getTarget(), sector);
    }

    /** Called by TradeExecutionService when a trade closes */
    public void onTradeClosed(String symbol, BigDecimal pnl) {
        circuitBreaker.recordPnl(pnl);
        // FIX 3: release sector slot when trade closes
        String sector = sectorClassify.getSector(symbol);
        sectorExposure.merge(sector, -1, (a, b) -> Math.max(0, a + b));
        log.debug("Sector '{}' slot released for {}", sector, symbol);
    }

    // FIX 3: reset sector exposure daily at 8:45 IST
    @Scheduled(cron = "0 45 8 * * MON-FRI", zone = "Asia/Kolkata")
    public void resetDaily() {
        sectorExposure.clear();
        log.info("RiskManagementService: sector exposure reset");
    }
}