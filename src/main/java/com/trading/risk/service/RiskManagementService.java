package com.trading.risk.service;

import com.trading.events.ProbabilityScoreEvent;
import com.trading.events.TradeApprovedEvent;
import com.trading.position.service.PositionSizerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class RiskManagementService {

    private final ApplicationEventPublisher publisher;
    private final CircuitBreakerService     circuitBreaker;
    private final PositionSizerService      positionSizer;

    @Value("${trading.capital:100000}")
    private String capitalStr;

    private final Map<String, Integer> sectorExposure = new ConcurrentHashMap<>();

    private BigDecimal capital() { return new BigDecimal(capitalStr); }

    @EventListener
    @Async("tradingExecutor")
    public void onProbabilityScore(ProbabilityScoreEvent event) {
        if (!"EXECUTE".equals(event.getDecision())) return;

        String sym    = event.getTradingSymbol();
        BigDecimal cap = capital();

        // Gate 1 — circuit breaker uses Permission.isAllowed()
        CircuitBreakerService.Permission perm = circuitBreaker.checkPermission(cap);
        if (!perm.isAllowed()) {
            log.warn("RISK REJECTED {}: {}", sym, perm.reason());
            return;
        }

        // Gate 2 — one trade per sector
        String sector = resolveSector(sym);
        if (sectorExposure.getOrDefault(sector, 0) >= 1) {
            log.warn("RISK REJECTED {}: sector '{}' already has open trade", sym, sector);
            return;
        }

        // Gate 3 — valid entry and SL
        if (event.getEntryPrice() == null || event.getEntryPrice().compareTo(BigDecimal.ZERO) == 0
         || event.getStopLoss()   == null || event.getStopLoss().compareTo(BigDecimal.ZERO)   == 0) {
            log.warn("RISK REJECTED {}: entry or SL is zero", sym);
            return;
        }

        // Gate 4 — position sizing
        PositionSizerService.PositionSize size = positionSizer.calculate(
            cap, event.getEntryPrice(), event.getStopLoss(),
            sym, event.getDirection().name());

        if (!size.isValid()) {
            log.warn("RISK REJECTED {}: {}", sym, size.invalidReason());
            return;
        }

        sectorExposure.merge(sector, 1, Integer::sum);
        circuitBreaker.recordTradeEntered();

        publisher.publishEvent(new TradeApprovedEvent(this,
            sym, event.getInstrumentToken(),
            event.getDirection(), event.getEntryPrice(),
            event.getStopLoss(), event.getTarget(),
            size.quantity(), size.actualRisk(),
            event.getTotalScore(), event.getStrategyName()));

        log.info("TRADE APPROVED: {} dir={} qty={} entry={} sl={} target={}",
            sym, event.getDirection(), size.quantity(),
            event.getEntryPrice(), event.getStopLoss(), event.getTarget());
    }

    public void onTradeClosed(String symbol, BigDecimal pnl) {
        circuitBreaker.recordPnl(pnl);
        sectorExposure.merge(resolveSector(symbol), -1,
            (a, b) -> Math.max(0, a + b));
    }

    private String resolveSector(String sym) {
        if (List.of("HDFCBANK","ICICIBANK","SBIN","KOTAKBANK","AXISBANK").contains(sym)) return "Banking";
        if (List.of("TCS","INFY","WIPRO","HCLTECH","TECHM").contains(sym)) return "IT";
        if (List.of("SUNPHARMA","DRREDDY","CIPLA","DIVISLAB").contains(sym)) return "Pharma";
        if (List.of("RELIANCE","ONGC","BPCL").contains(sym)) return "Energy";
        if (List.of("MARUTI","TATAMOTORS","BAJAJ-AUTO","EICHERMOT").contains(sym)) return "Auto";
        return "Other";
    }
}
