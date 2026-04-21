package com.trading.strategy.highrr;

import com.trading.domain.enums.TradeDirection;
import com.trading.events.TickReceivedEvent;
import com.trading.papertrading.model.PaperAccount;
import com.trading.risk.service.RiskManagementService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * HighRRTradeManager — Real-time SL/target monitoring and exit execution
 * for HIGH_RR_INTRADAY_V1 trades.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * ARCHITECTURE:
 *   HighRROrderExecutionService registers trades here after fill confirmation.
 *   This service watches every tick for SL/T1/time-stop conditions and exits.
 *   P&L is applied via PaperAccount; risk slots released via RiskManagementService.
 *
 * DASHBOARD ADDITIONS (non-breaking):
 *   Added HighRRClosedTrade record + closedTrades list so DashboardController
 *   can display full trade history without touching Trade domain entities.
 *   All existing trading logic (registerTrade, onTick, forceCloseAll) is unchanged.
 * ─────────────────────────────────────────────────────────────────────────────
 */
@Service
@Slf4j
public class HighRRTradeManager {

    private static final ZoneId   IST        = ZoneId.of("Asia/Kolkata");
    private static final String   STRATEGY   = "HIGH_RR_INTRADAY_V1";
    private static final double   EXIT_SLIP  = 0.0003;   // 0.03% exit slippage

    // ── Dependencies ────────────────────────────────────────────────────────
    private final PaperAccount          paperAccount;
    private final RiskManagementService riskManagement;

    // @Lazy breaks the circular: HighRRTradeManager → HighRRStrategyEngine
    //                             HighRRStrategyEngine → (indirectly) HighRRTradeManager
    @Lazy
    @Autowired
    private HighRRStrategyEngine strategyEngine;

    @Value("${trading.mode:PAPER}")
    private String tradingMode;

    public HighRRTradeManager(PaperAccount paperAccount,
                              RiskManagementService riskManagement) {
        this.paperAccount   = paperAccount;
        this.riskManagement = riskManagement;
    }

    // ── Active trade storage ─────────────────────────────────────────────────
    // Key: symbol.  One active HighRR trade per symbol at a time.
    private final Map<String, HighRRTrade> activeTrades = new ConcurrentHashMap<>();

    // ── Closed trade history — dashboard visibility ──────────────────────────
    // Populated whenever a trade closes (SL, T1, T2, time stop, force close).
    // Read-only from DashboardController. Zero impact on trading logic.
    private final List<HighRRClosedTrade> closedTrades =
            Collections.synchronizedList(new ArrayList<>());

    // ══════════════════════════════════════════════════════════════════════════
    // TRADE REGISTRATION
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Called by HighRROrderExecutionService after paper fill confirmation.
     * Registers the trade for real-time tick monitoring.
     */
    public void registerTrade(HighRRTrade trade) {
        if (activeTrades.containsKey(trade.symbol())) {
            log.warn("[HIGHRR-MGR] Already tracking {} — duplicate register ignored", trade.symbol());
            return;
        }
        activeTrades.put(trade.symbol(), trade);

        // Register in cross-strategy symbol map so PaperTradeExecutionService
        // rejects any other strategy that tries to trade this symbol simultaneously.
        // Without this call, activeSymbolMap never knew HighRR was holding the symbol —
        // riskService.isSymbolAlreadyActive() would return false even with an open HighRR trade.
        riskManagement.onTradeOpened(trade.symbol(), STRATEGY, false);

        log.info("[HIGHRR-MGR] \u2705 Registered: {} | dir={} | entry=\u20b9{} | SL=\u20b9{} | T1=\u20b9{} | T2=\u20b9{} | qty={}",
                trade.symbol(), trade.direction(), trade.fillPrice(),
                trade.stopLoss(), trade.target(), trade.target2(), trade.quantity());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TICK MONITORING — SL / TARGET / TIME STOP
    // ══════════════════════════════════════════════════════════════════════════

    @EventListener
    @Async("tickExecutor")
    public void onTick(TickReceivedEvent tick) {
        if (activeTrades.isEmpty()) return;

        String symbol = tick.getTradingSymbol();
        HighRRTrade trade = activeTrades.get(symbol);
        if (trade == null) return;

        double ltp    = tick.getLastTradedPrice().doubleValue();
        boolean isLong = trade.direction() == TradeDirection.LONG;

        // ── Time stop ────────────────────────────────────────────────────────
        if (trade.timeStopMinutes() > 0) {
            long elapsedMins = (Instant.now().toEpochMilli() - trade.entryTime().toEpochMilli()) / 60000L;
            if (elapsedMins >= trade.timeStopMinutes()) {
                log.info("[HIGHRR-MGR] ⏰ Time stop: {} elapsed={}min limit={}min",
                        symbol, elapsedMins, trade.timeStopMinutes());
                exitTrade(trade, ltp, "TIME_STOP_" + trade.timeStopMinutes() + "min");
                return;
            }
        }

        double sl  = trade.stopLoss().doubleValue();
        double t1  = trade.target().doubleValue();
        double t2  = trade.target2().doubleValue();

        if (isLong) {
            // ── LONG: SL below entry, targets above ─────────────────────────
            if (ltp <= sl) {
                log.info("[HIGHRR-MGR] 🛑 SL hit: {} ltp={} sl={}", symbol, ltp, sl);
                exitTrade(trade, ltp, "STOP_LOSS");
            } else if (ltp >= t2) {
                log.info("[HIGHRR-MGR] 🎯 T2 hit: {} ltp={} t2={}", symbol, ltp, t2);
                exitTrade(trade, ltp, "TARGET_2");
            } else if (ltp >= t1) {
                log.info("[HIGHRR-MGR] ✅ T1 hit: {} ltp={} t1={}", symbol, ltp, t1);
                exitTrade(trade, ltp, "TARGET_1");
            }
        } else {
            // ── SHORT: SL above entry, targets below ────────────────────────
            if (ltp >= sl) {
                log.info("[HIGHRR-MGR] 🛑 SL hit: {} ltp={} sl={}", symbol, ltp, sl);
                exitTrade(trade, ltp, "STOP_LOSS");
            } else if (ltp <= t2) {
                log.info("[HIGHRR-MGR] 🎯 T2 hit: {} ltp={} t2={}", symbol, ltp, t2);
                exitTrade(trade, ltp, "TARGET_2");
            } else if (ltp <= t1) {
                log.info("[HIGHRR-MGR] ✅ T1 hit: {} ltp={} t1={}", symbol, ltp, t1);
                exitTrade(trade, ltp, "TARGET_1");
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // FORCE CLOSE ALL (13:30 PM time exit, called by HighRROrderExecutionService)
    // ══════════════════════════════════════════════════════════════════════════

    public void forceCloseAll(String reason) {
        if (activeTrades.isEmpty()) return;
        log.warn("[HIGHRR-MGR] Force closing {} active trades. Reason: {}", activeTrades.size(), reason);
        new ArrayList<>(activeTrades.values()).forEach(t -> exitTrade(t, t.fillPrice().doubleValue(), reason));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // EXIT LOGIC
    // ══════════════════════════════════════════════════════════════════════════

    private void exitTrade(HighRRTrade trade, double ltpRaw, String reason) {
        String symbol  = trade.symbol();
        boolean isLong = trade.direction() == TradeDirection.LONG;

        // Apply exit slippage
        double exitPriceD = isLong
                ? ltpRaw * (1.0 - EXIT_SLIP)
                : ltpRaw * (1.0 + EXIT_SLIP);
        BigDecimal exitPrice = BigDecimal.valueOf(exitPriceD).setScale(2, RoundingMode.HALF_UP);

        // Gross P&L
        BigDecimal grossPnl = isLong
                ? exitPrice.subtract(trade.fillPrice()).multiply(BigDecimal.valueOf(trade.quantity()))
                : trade.fillPrice().subtract(exitPrice).multiply(BigDecimal.valueOf(trade.quantity()));

        // Brokerage (flat ₹40 per trade — paper simulation)
        BigDecimal brokerage = BigDecimal.valueOf(40.0);
        BigDecimal netPnl    = grossPnl.subtract(brokerage);

        // Remove from active tracking first (prevent duplicate triggers)
        activeTrades.remove(symbol);

        // Apply P&L to paper account
        paperAccount.applyPnl(netPnl);

        // Release risk slot
        riskManagement.onTradeClosed(symbol, netPnl, STRATEGY, false);

        // Notify strategy engine to free the signal slot
        if (strategyEngine != null) {
            strategyEngine.onSignalClosed(symbol);
        }

        // ── Dashboard snapshot — record closed trade for history ─────────────
        // This is the ONLY change to the close path.
        // Captures a snapshot of the completed trade so DashboardController can
        // display it. Read-only from dashboard, zero impact on trading logic.
        captureClosedTrade(trade, exitPrice, netPnl, reason);

        log.info("[HIGHRR-MGR] 🔒 CLOSED: {} | dir={} | entry=₹{} | exit=₹{} | net=₹{} | reason={}",
                symbol, trade.direction(), trade.fillPrice(), exitPrice, netPnl, reason);
    }

    /**
     * Records a closed trade snapshot for dashboard visibility.
     * Called only from exitTrade(). No effect on trading logic.
     */
    private void captureClosedTrade(HighRRTrade trade, BigDecimal exitPrice,
                                    BigDecimal netPnl, String reason) {
        closedTrades.add(new HighRRClosedTrade(
                trade.symbol(),
                trade.direction(),
                trade.fillPrice(),
                exitPrice,
                trade.stopLoss(),
                trade.target(),
                trade.target2(),
                trade.quantity(),
                trade.entryTime(),
                Instant.now(),
                reason,
                netPnl
        ));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SHUTDOWN
    // ══════════════════════════════════════════════════════════════════════════

    @PreDestroy
    public void onShutdown() {
        if (!activeTrades.isEmpty()) {
            log.warn("[HIGHRR-MGR] App shutdown — force closing {} HighRR positions", activeTrades.size());
            forceCloseAll("APP_SHUTDOWN");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DASHBOARD READ-ONLY GETTERS
    // ══════════════════════════════════════════════════════════════════════════

    /** Active trades currently being monitored. */
    public Collection<HighRRTrade> getActiveTrades() {
        return Collections.unmodifiableCollection(activeTrades.values());
    }

    /** All trades closed today (SL hit, target hit, time stop, force close). */
    public List<HighRRClosedTrade> getClosedTrades() {
        LocalDate today = LocalDate.now(IST);
        return closedTrades.stream()
                .filter(t -> today.equals(t.closedAt().atZone(IST).toLocalDate()))
                .collect(Collectors.toList());
    }

    /** All closed trades this session (since app start). */
    public List<HighRRClosedTrade> getAllClosedTrades() {
        return Collections.unmodifiableList(closedTrades);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // INNER TYPES
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Active trade record. Immutable — all state is captured at entry.
     * Created by HighRROrderExecutionService and passed to registerTrade().
     */
    public record HighRRTrade(
            String         symbol,
            TradeDirection direction,
            BigDecimal     fillPrice,     // actual fill price (entry + slippage)
            BigDecimal     stopLoss,
            BigDecimal     target,        // T1 = 2R
            BigDecimal     target2,       // T2 = 3R
            int            quantity,
            String         orderId,
            Instant        entryTime,
            int            timeStopMinutes
    ) {}

    /**
     * Closed trade snapshot — created when a trade exits for any reason.
     * Read-only from DashboardController. Contains all info needed for
     * dashboard display: entry, exit, P&L, exit reason, timestamps.
     */
    public record HighRRClosedTrade(
            String         symbol,
            TradeDirection direction,
            BigDecimal     entryPrice,
            BigDecimal     exitPrice,
            BigDecimal     stopLoss,
            BigDecimal     target1,
            BigDecimal     target2,
            int            quantity,
            Instant        entryTime,
            Instant        closedAt,
            String         exitReason,
            BigDecimal     netPnl
    ) {}
}