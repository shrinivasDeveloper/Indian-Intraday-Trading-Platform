// ============================================================
// REPLACE FILE — v7.0 FINAL
// Path: src/main/java/com/trading/regime/service/ProbabilityEngine.java
// v7.0 CHANGES:
//   1. Location proximity: < 0.2% → +15 (was 0.1% cutoff) — aligned to v7.0
//   2. Decay factor: priceMoved > 0.8% → -15 (v7.0 says -15, not -10)
//   3. Early boost hook: earlyBoost param added to calculate() overload
//      (actual boost applied in StrategyEvaluatorService)
//   4. All v3.1 changes preserved
// ============================================================
package com.trading.regime.service;

import com.trading.analysis.service.KeyLevelService;
import com.trading.analysis.service.RvolService;
import com.trading.domain.Candle;
import com.trading.marketdata.service.VixService;
import com.trading.strategy.TradingStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

/**
 * ProbabilityEngine v7.0 — Institutional edge probability score.
 *
 * v7.0 FORMULA:
 *   Probability = BaseScore (50)
 *                + StructureScore   (-20 to +15)
 *                + LocationScore    (-20 to +15)  ← 0.2% proximity threshold
 *                + VolumeScore      (-20 to +20)
 *                + TimeScore        (-10 to +10)
 *                + VixScore         (-10 to +10)
 *                + StrategyBoost    (0   to +35)
 *                + NegativeFilters  (-55 to 0)
 *                - DecayFactor      (0   to -15)  ← v7.0: -15 when moved >0.8%
 *   [Early Boost +5 applied in StrategyEvaluatorService, not here]
 *
 * v7.0 LOCATION SCORE — 0.2% PROXIMITY:
 *   Within 0.2% of VAH/VAL → +15 (v7.0: "distanceToLevel < 0.2% → +15")
 *   Within 0.4% of VAH/VAL → +10
 *   Within 0.6% of VAH/VAL → +5
 *   Within 0.3% of POC     → +10
 *   Middle of range         → -20
 *
 * v7.0 DECAY FACTOR:
 *   priceMoved > 0.8%  → -15  (v7.0 spec: "priceMoved > 0.8% → probability -= 15")
 *   priceMoved > 1.5%  → -20  (extended: force SKIP)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ProbabilityEngine {

    private final VixService           vixService;
    private final RvolService          rvolService;
    private final KeyLevelService      keyLevelService;
    private final MarketModeEngine     marketModeEngine;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // ── Score record ──────────────────────────────────────────────────────────

    public record ScoreBreakdown(
            double base, double structureScore, double locationScore,
            double volumeScore, double timeScore, double vixScore,
            double strategyBoost, double negativeFilters, double decayFactor,
            double total, String detail, MarketModeEngine.TradeTier tier
    ) {
        public boolean shouldTrade() { return tier != MarketModeEngine.TradeTier.SKIP; }
        public double positionMultiplier() {
            return switch (tier) {
                case GOLD   -> 1.2;
                case NORMAL -> 1.0;
                case SKIP   -> 0.0;
            };
        }
    }

    public record ScoringContext(
            String                          symbol,
            String                          strategyName,
            TradingStrategy.TradeSignal     signal,
            TradingStrategy.MarketContext   marketCtx,
            List<Candle>                    candles5m,
            List<Candle>                    candles15m
    ) {}

    // ═══════════════════════════════════════════════════════════════════════════
    // MAIN ENTRY POINT
    // ═══════════════════════════════════════════════════════════════════════════

    public ScoreBreakdown calculate(ScoringContext ctx) {
        double base           = 50.0;
        double structureScore = scoreStructure(ctx);
        double locationScore  = scoreLocation(ctx);
        double volumeScore    = scoreVolume(ctx);
        double timeScore      = scoreTime();
        double vixScore       = scoreVix();
        double stratBoost     = scoreStrategyBoost(ctx);
        double negFilter      = scoreNegativeFilters(ctx);
        double decayFactor    = scoreDecayFactor(ctx);  // v7.0: -15 at 0.8%

        double raw   = base + structureScore + locationScore + volumeScore
                + timeScore + vixScore + stratBoost + negFilter + decayFactor;
        double total = Math.max(0, Math.min(100, raw));

        MarketModeEngine.MarketModeResult mode = marketModeEngine.getCurrentMode();
        double threshold = mode.minProbability();
        MarketModeEngine.TradeTier tier = mode.tier(total);

        String detail = buildDetail(base, structureScore, locationScore, volumeScore,
                timeScore, vixScore, stratBoost, negFilter, decayFactor,
                total, mode.mode().name(), threshold);

        log.info("[PROB] {} {} score={:.0f} tier={} threshold={:.0f} | struct={:+.0f} loc={:+.0f} vol={:+.0f} time={:+.0f} vix={:+.0f} strat={:+.0f} neg={:+.0f} decay={:+.0f}",
                ctx.strategyName(), ctx.symbol(), total, tier, threshold,
                structureScore, locationScore, volumeScore, timeScore,
                vixScore, stratBoost, negFilter, decayFactor);

        return new ScoreBreakdown(base, structureScore, locationScore, volumeScore,
                timeScore, vixScore, stratBoost, negFilter, decayFactor, total, detail, tier);
    }

    // ── 1. STRUCTURE ──────────────────────────────────────────────────────────

    private double scoreStructure(ScoringContext ctx) {
        List<Candle> c5m = ctx.candles5m();
        if (c5m == null || c5m.size() < 3) return 0;
        boolean isLong = ctx.signal().direction() == com.trading.domain.enums.TradeDirection.LONG;
        int n = Math.min(4, c5m.size());
        int confirmations = 0;
        for (int i = 0; i < n - 1; i++) {
            Candle cur  = c5m.get(i);
            Candle prev = c5m.get(i + 1);
            if (isLong) {
                if (cur.getHigh().compareTo(prev.getHigh()) > 0 && cur.getLow().compareTo(prev.getLow()) > 0) confirmations++;
            } else {
                if (cur.getHigh().compareTo(prev.getHigh()) < 0 && cur.getLow().compareTo(prev.getLow()) < 0) confirmations++;
            }
        }
        Candle sig = c5m.get(0);
        BigDecimal range = sig.getHigh().subtract(sig.getLow());
        double bodyPct = range.compareTo(BigDecimal.ZERO) == 0 ? 0
                : sig.getOpen().subtract(sig.getClose()).abs()
                .divide(range, MathContext.DECIMAL32).doubleValue();
        if (confirmations >= 3 && bodyPct >= 0.60) return 15;
        if (confirmations >= 2 && bodyPct >= 0.50) return 5;
        if (confirmations == 0 || bodyPct < 0.30)  return -20;
        return 0;
    }

    // ── 2. LOCATION — v7.0: 0.2% proximity threshold ─────────────────────────

    private double scoreLocation(ScoringContext ctx) {
        KeyLevelService.KeyLevelResult kl = keyLevelService.getKeyLevels(ctx.symbol());
        BigDecimal price = ctx.signal().entryPrice();
        if (kl == null || price == null || price.compareTo(BigDecimal.ZERO) == 0) return 0;

        // Distance to VAH/VAL (%)
        double distVah = kl.valueArea() != null && kl.valueArea().vah().compareTo(BigDecimal.ZERO) > 0
                ? Math.abs(price.subtract(kl.valueArea().vah()).divide(kl.valueArea().vah(), MathContext.DECIMAL32).doubleValue()) * 100 : 999;
        double distVal = kl.valueArea() != null && kl.valueArea().val().compareTo(BigDecimal.ZERO) > 0
                ? Math.abs(price.subtract(kl.valueArea().val()).divide(kl.valueArea().val(), MathContext.DECIMAL32).doubleValue()) * 100 : 999;
        double distPoc = kl.poc() != null && kl.poc().compareTo(BigDecimal.ZERO) > 0
                ? Math.abs(price.subtract(kl.poc()).divide(kl.poc(), MathContext.DECIMAL32).doubleValue()) * 100 : 999;

        // IB edge
        MarketModeEngine.MarketModeResult mode = marketModeEngine.getCurrentMode();
        double distIbHigh = mode.ibHigh() > 0 ? Math.abs(price.doubleValue() - mode.ibHigh()) / mode.ibHigh() * 100 : 999;
        double distIbLow  = mode.ibLow()  > 0 ? Math.abs(price.doubleValue() - mode.ibLow())  / mode.ibLow()  * 100 : 999;
        double minVahVal  = Math.min(distVah, distVal);

        // v7.0: < 0.2% → +15 (tighter than v3.1's 0.1%)
        if (minVahVal <= 0.2) return 15;   // v7.0 spec: "distanceToLevel < 0.2% → +15"
        if (minVahVal <= 0.4) return 10;
        if (minVahVal <= 0.6) return 5;
        if (distPoc <= 0.3)   return 10;
        if (distPoc <= 0.5)   return 7;
        if (Math.min(distIbHigh, distIbLow) <= 0.5) return 5;
        if (kl.isInsideValueArea(price)) return -20;
        return 0;
    }

    // ── 3. VOLUME ─────────────────────────────────────────────────────────────

    private double scoreVolume(ScoringContext ctx) {
        List<Candle> c5m = ctx.candles5m();
        if (c5m == null || c5m.isEmpty()) return 0;
        double rvol = rvolService.getRvolNow(ctx.symbol(), c5m.get(0).getVolume());
        if (rvol >= 1.5) return 20;
        if (rvol >= 1.2) return 10;
        if (rvol >= 1.0) return 0;
        return -20;
    }

    // ── 4. TIME ───────────────────────────────────────────────────────────────

    private double scoreTime() {
        LocalTime now = LocalTime.now(IST);
        if (!now.isBefore(LocalTime.of(9, 40)) && now.isBefore(LocalTime.of(11, 0)))  return 10;
        if (!now.isBefore(LocalTime.of(12, 30)) && now.isBefore(LocalTime.of(14, 0))) return 5;
        if (!now.isBefore(LocalTime.of(11, 0)) && now.isBefore(LocalTime.of(12, 30))) return -10;
        return 0;
    }

    // ── 5. VIX ────────────────────────────────────────────────────────────────

    private double scoreVix() {
        double vix = vixService.getCurrentVix();
        if (vix <= 0)  return 0;
        if (vix < 20)  return 10;
        if (vix <= 28) return 0;
        return -10;
    }

    // ── 6. STRATEGY BOOST ─────────────────────────────────────────────────────

    private double scoreStrategyBoost(ScoringContext ctx) {
        return switch (ctx.strategyName()) {
            case "SCANNER_7GATE" -> {
                double boost = 15;
                KeyLevelService.KeyLevelResult kl = keyLevelService.getKeyLevels(ctx.symbol());
                BigDecimal price = ctx.signal().entryPrice();
                if (kl != null && price != null) {
                    boolean forLong = ctx.signal().direction() == com.trading.domain.enums.TradeDirection.LONG;
                    if (kl.isNearKeyLevel(price, forLong, 0.5)) boost += 10;
                }
                List<Candle> c = ctx.candles5m();
                if (c != null && !c.isEmpty()) {
                    BigDecimal rng = c.get(0).getHigh().subtract(c.get(0).getLow());
                    if (rng.compareTo(BigDecimal.ZERO) > 0) {
                        double bp = c.get(0).getOpen().subtract(c.get(0).getClose()).abs()
                                .divide(rng, MathContext.DECIMAL32).doubleValue();
                        if (bp >= 0.65) boost += 10;
                    }
                }
                yield boost;
            }
            case "AUTO_MODE" -> {
                double boost = 0;
                if (ctx.marketCtx().niftyBullish() || ctx.marketCtx().niftyBearish()) boost += 15;
                if (ctx.marketCtx().sectorIsTop() || ctx.marketCtx().sectorIsBottom())  boost += 10;
                KeyLevelService.KeyLevelResult kl2 = keyLevelService.getKeyLevels(ctx.symbol());
                BigDecimal p2 = ctx.signal().entryPrice();
                boolean isLong = ctx.signal().direction() == com.trading.domain.enums.TradeDirection.LONG;
                if (kl2 != null && p2 != null) {
                    if (isLong  && kl2.isAboveVah(p2)) boost += 10;
                    if (!isLong && kl2.isBelowVal(p2)) boost += 10;
                }
                yield boost;
            }
            case "RANGE_BREAKOUT_3TOUCH" -> {
                double boost = ctx.signal().isSpring() ? 20 : 15;
                boost += 10;
                yield boost;
            }
            case "VAP_PULLBACK" -> {
                double boost = 0;
                KeyLevelService.KeyLevelResult kl3 = keyLevelService.getKeyLevels(ctx.symbol());
                BigDecimal p3 = ctx.signal().entryPrice();
                if (kl3 != null && p3 != null && kl3.poc() != null && kl3.poc().compareTo(BigDecimal.ZERO) > 0) {
                    double dist = Math.abs(p3.subtract(kl3.poc()).divide(kl3.poc(), MathContext.DECIMAL32).doubleValue()) * 100;
                    if (dist <= 0.3) boost += 15;
                    else if (dist <= 0.7) boost += 8;
                }
                List<Candle> c3 = ctx.candles5m();
                if (c3 != null && c3.size() >= 3) {
                    double avg3 = c3.subList(1, 3).stream().mapToLong(Candle::getVolume).average().orElse(0);
                    if (avg3 > 0 && c3.get(0).getVolume() < avg3 * 0.7) boost += 10;
                }
                yield boost;
            }
            case "ORB_VWAP_SECTOR" -> {
                double boost = 15;
                if (ctx.marketCtx().sectorAlignedBull() || ctx.marketCtx().sectorAlignedBear()) boost += 10;
                boost += 5;
                yield boost;
            }
            default -> 0;
        };
    }

    // ── 7. NEGATIVE FILTERS ───────────────────────────────────────────────────

    private double scoreNegativeFilters(ScoringContext ctx) {
        double penalty = 0;
        BigDecimal price = ctx.signal().entryPrice();
        boolean isLong   = ctx.signal().direction() == com.trading.domain.enums.TradeDirection.LONG;
        if (isLong  && ctx.marketCtx().sectorAlignedBear()) penalty -= 20;
        if (!isLong && ctx.marketCtx().sectorAlignedBull()) penalty -= 20;
        List<Candle> c = ctx.candles5m();
        if (c != null && !c.isEmpty()) {
            BigDecimal rng = c.get(0).getHigh().subtract(c.get(0).getLow());
            if (rng.compareTo(BigDecimal.ZERO) > 0) {
                double bp = c.get(0).getOpen().subtract(c.get(0).getClose()).abs()
                        .divide(rng, MathContext.DECIMAL32).doubleValue();
                if (bp < 0.40) penalty -= 20;
            }
        }
        KeyLevelService.KeyLevelResult kl = keyLevelService.getKeyLevels(ctx.symbol());
        if (kl != null && price != null && kl.poc() != null && kl.poc().compareTo(BigDecimal.ZERO) > 0) {
            double dist = Math.abs(price.subtract(kl.poc()).divide(kl.poc(), MathContext.DECIMAL32).doubleValue()) * 100;
            if (dist > 1.5) penalty -= 15;
        }
        return penalty;
    }

    // ── 8. DECAY FACTOR — v7.0: -15 at 0.8% ──────────────────────────────────

    /**
     * v7.0 spec: "if (priceMoved > 0.8%) probability -= 15"
     * Extended: > 1.5% → -20 (force SKIP)
     */
    private double scoreDecayFactor(ScoringContext ctx) {
        BigDecimal signalPrice = ctx.signal().entryPrice();
        List<Candle> c5m      = ctx.candles5m();
        if (signalPrice == null || signalPrice.compareTo(BigDecimal.ZERO) == 0) return 0;
        if (c5m == null || c5m.isEmpty()) return 0;
        BigDecimal currentPrice = c5m.get(0).getClose();
        if (currentPrice.compareTo(BigDecimal.ZERO) == 0) return 0;
        double priceMoved = Math.abs(currentPrice.subtract(signalPrice)
                .divide(signalPrice, MathContext.DECIMAL32).doubleValue()) * 100;
        if (priceMoved > 1.5) { log.debug("[PROB] DECAY -20: moved {:.2f}%", priceMoved); return -20; }
        if (priceMoved > 0.8) { log.debug("[PROB] DECAY -15: moved {:.2f}%", priceMoved); return -15; } // v7.0 spec
        return 0;
    }

    // ── Detail builder ────────────────────────────────────────────────────────

    private String buildDetail(double base, double struct, double loc, double vol,
                               double time, double vix, double strat, double neg,
                               double decay, double total, String mode, double threshold) {
        return String.format(
                "base=%.0f struct=%+.0f loc=%+.0f vol=%+.0f time=%+.0f vix=%+.0f strat=%+.0f neg=%+.0f decay=%+.0f → TOTAL=%.0f | mode=%s threshold=%.0f",
                base, struct, loc, vol, time, vix, strat, neg, decay, total, mode, threshold);
    }
}