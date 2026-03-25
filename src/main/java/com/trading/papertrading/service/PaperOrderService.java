package com.trading.papertrading.service;

import com.trading.domain.enums.TradeDirection;
import com.trading.papertrading.model.PaperTrade;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Simulates Zerodha order execution for paper trading.
 *
 * Mirrors ZerodhaOrderClient but uses configurable slippage instead of real orders.
 * All "fills" are immediate at LTP ± slippage — realistic intraday simulation.
 *
 * Slippage model:
 *   Entry market order : +0.05% (long) / -0.05% (short)
 *   SL exit           : -0.10% (long) / +0.10% (short) — extra slippage on SL
 *   Target exit       : -0.05% (long) / +0.05% (short)
 *   EOD force close   : -0.15% (long) / +0.15% (short)
 *
 * Brokerage: flat ₹40 per round trip (Zerodha Flat Plan).
 */
@Service
@Slf4j
public class PaperOrderService {

    @Value("${paper-trading.entry-slip-pct:0.0005}")
    private double entrySlipPct;

    @Value("${paper-trading.sl-slip-pct:0.001}")
    private double slSlipPct;

    @Value("${paper-trading.target-slip-pct:0.0005}")
    private double targetSlipPct;

    @Value("${paper-trading.eod-slip-pct:0.0015}")
    private double eodSlipPct;

    @Value("${paper-trading.brokerage:40.0}")
    private double brokerage;

    // ── Entry fill ────────────────────────────────────────────────────

    /**
     * Simulate market order fill at LTP ± slippage.
     * @param ltp   current market price
     * @param dir   trade direction
     * @return simulated fill price (rounded to 2dp)
     */
    public BigDecimal simulateEntryFill(BigDecimal ltp, TradeDirection dir) {
        double price = ltp.doubleValue();
        double filled = dir == TradeDirection.LONG
                ? price * (1 + entrySlipPct)    // long: buy slightly higher
                : price * (1 - entrySlipPct);   // short: sell slightly lower
        return BigDecimal.valueOf(filled).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Simulate SL exit fill — worst-case slippage (SL hits on a gap candle).
     */
    public BigDecimal simulateSlFill(BigDecimal slPrice, TradeDirection dir) {
        double price = slPrice.doubleValue();
        double filled = dir == TradeDirection.LONG
                ? price * (1 - slSlipPct)        // long exit: sell below SL
                : price * (1 + slSlipPct);        // short exit: buy above SL
        return BigDecimal.valueOf(filled).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Simulate target exit fill.
     */
    public BigDecimal simulateTargetFill(BigDecimal targetPrice, TradeDirection dir) {
        double price = targetPrice.doubleValue();
        double filled = dir == TradeDirection.LONG
                ? price * (1 - targetSlipPct)
                : price * (1 + targetSlipPct);
        return BigDecimal.valueOf(filled).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Simulate EOD force-close fill — higher slippage as market is thin.
     */
    public BigDecimal simulateEodFill(BigDecimal ltp, TradeDirection dir) {
        double price = ltp.doubleValue();
        double filled = dir == TradeDirection.LONG
                ? price * (1 - eodSlipPct)
                : price * (1 + eodSlipPct);
        return BigDecimal.valueOf(filled).setScale(2, RoundingMode.HALF_UP);
    }

    // ── P&L calculation ───────────────────────────────────────────────

    /**
     * Calculate gross P&L from fill prices.
     */
    public BigDecimal grossPnl(BigDecimal entryFill, BigDecimal exitFill,
                               int qty, TradeDirection dir) {
        BigDecimal diff = dir == TradeDirection.LONG
                ? exitFill.subtract(entryFill)
                : entryFill.subtract(exitFill);
        return diff.multiply(BigDecimal.valueOf(qty));
    }

    /**
     * Calculate slippage cost (entry + exit combined).
     */
    public BigDecimal slippageCost(BigDecimal rawEntry, BigDecimal fillEntry,
                                   BigDecimal rawExit, BigDecimal fillExit,
                                   int qty) {
        BigDecimal entryCost = rawEntry.subtract(fillEntry).abs().multiply(BigDecimal.valueOf(qty));
        BigDecimal exitCost  = rawExit.subtract(fillExit).abs().multiply(BigDecimal.valueOf(qty));
        return entryCost.add(exitCost);
    }

    /**
     * Calculate brokerage (flat ₹40 per round trip).
     */
    public BigDecimal brokerageCost() {
        return BigDecimal.valueOf(brokerage);
    }

    /**
     * Calculate net P&L after slippage and brokerage.
     */
    public BigDecimal netPnl(BigDecimal grossPnl, BigDecimal slippage) {
        return grossPnl.subtract(slippage).subtract(brokerageCost());
    }

    // ── Position size ────────────────────────────────────────────────

    /**
     * Calculate paper trading quantity using same 1% risk rule as live.
     *
     * @param capital  current virtual capital
     * @param entry    entry price
     * @param sl       stop loss price
     * @return quantity (min 1)
     */
    public int calculateQty(BigDecimal capital, BigDecimal entry, BigDecimal sl,
                            double riskPct, double maxPosPct) {
        BigDecimal slDist = entry.subtract(sl).abs();
        if (slDist.compareTo(BigDecimal.ZERO) == 0) return 0;

        int riskQty = (int) (capital.doubleValue() * riskPct / slDist.doubleValue());
        int maxQty  = (int) (capital.doubleValue() * maxPosPct / entry.doubleValue());
        int qty     = Math.min(riskQty, maxQty);

        log.debug("Paper qty: capital={} entry={} sl={} slDist={} riskQty={} maxQty={} final={}",
                capital, entry, sl, slDist, riskQty, maxQty, qty);
        return Math.max(1, qty);
    }

    /**
     * Log paper trade fill for audit trail.
     */
    public void logFill(PaperTrade t, String event) {
        log.info("[PAPER] {} {} {} qty={} fill={} sl={} target={} pnl={}",
                event, t.getDirection(), t.getTradingSymbol(),
                t.getQuantity(),
                t.getFillPrice(),
                t.getCurrentSl(),
                t.getTarget(),
                t.getNetPnl() != null ? t.pnlDisplay() : "open");
    }
}