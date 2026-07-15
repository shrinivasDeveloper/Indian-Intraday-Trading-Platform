package com.trading.ai.engine;

import com.trading.ai.data.AiMarketDataService;
import com.trading.ai.model.AiCandidate;
import com.trading.ai.model.AiPrediction;
import com.trading.ai.model.AiTradeDecision;
import com.trading.domain.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;

/**
 * AiReasoningEngine
 *
 * The AI module's multi-layer reasoning brain.
 * Replaces simple score-threshold comparison with structured thinking
 * across 6 layers — exactly how an experienced trader evaluates a setup.
 *
 * NO external APIs. NO Claude. NO rules engine.
 * Pure contextual integration of everything the platform already knows.
 *
 * LAYER 1 — Market Environment Quality
 *   Question: Is today's market worth trading in?
 *   Inputs: regime, breadth, VIX, session quality
 *
 * LAYER 2 — Stock Movement Quality
 *   Question: Is this stock genuinely moving or random noise?
 *   Inputs: RVOL, momentum, buy pressure, MACD, volume trend
 *
 * LAYER 3 — Fundamental Catalyst
 *   Question: Is there a reason this stock is moving?
 *   Inputs: news score from MySQL (written by NewsTradingStrategy)
 *   Zero import of news strategy — reads shared MySQL table only
 *
 * LAYER 4 — Timing Quality
 *   Question: Is NOW the right time to enter?
 *   Inputs: session time, pattern age in candles
 *
 * LAYER 5 — Risk/Reward Quality
 *   Question: Is this trade genuinely worth the risk?
 *   Inputs: RR ratio, SL placement quality, sector alignment
 *
 * LAYER 6 — Comparative Selection
 *   Question: Among all candidates, which ONE is most compelling?
 *   Picks the best candidate or none if composite < threshold
 *
 * COMPOSITE FORMULA:
 *   score = env×0.20 + move×0.25 + fundamental×0.20
 *         + timing×0.15 + pattern×0.10 + rr×0.10
 *
 * INDEPENDENCE:
 *   Zero imports from strategy.highrr, strategy.smc, strategy.news, strategy.channel
 *   News context read from MySQL only — no Java coupling
 */
@Service
@ConditionalOnProperty(name = "ai.trading.enabled", havingValue = "true")
@Slf4j
public class AiReasoningEngine {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final AiMarketDataService aiData;
    private final JdbcTemplate        jdbc;

    // ── Ranking weights — comparative ranking only (sum to 1.0) ─────────
    // Pattern/RR/Env/Direction removed — handled upstream in confidence engine
    private static final double W_MOVE        = 0.35;
    private static final double W_TIMING      = 0.20;
    private static final double W_FUNDAMENTAL = 0.20;
    private static final double W_ML_CONF     = 0.15;
    private static final double W_QUALITY     = 0.10;

    public AiReasoningEngine(AiMarketDataService aiData, JdbcTemplate jdbc) {
        this.aiData = aiData;
        this.jdbc   = jdbc;
        createTablesIfNeeded();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MAIN ENTRY POINT
    // Called from AiTradingSystem after AiRiskAssessmentEngine
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Reason about ALL candidates together and return the single best one.
     * Returns null only if candidates list is empty.
     *
     * @param candidates  candidates that passed risk assessment
     * @param decisions   their corresponding risk decisions (SL/T1/T2 computed)
     * @param snapshot    market context from AiMarketUnderstandingEngine
     */
    /**
     * FIX (found via direct user report: genuinely eligible stocks -
     * scored >=80, non-choppy - were producing zero trades AND zero
     * "why didn't it trade" entries, vanishing without any trace).
     * CONFIRMED root cause: candidates with a null or malformed feature
     * vector were being silently dropped inside the loop below with a
     * bare "continue" - no logging, no reason recorded anywhere. Since
     * the watchlist's "Eligible" label is written earlier, in a
     * completely different method (AiTradingSystem's scanning loop),
     * before this check even runs, a stock could show "Eligible" on
     * the dashboard while being silently discarded here moments later.
     * This map now records exactly which symbols were skipped this way
     * and why, so AiTradingSystem can surface a real, visible reason
     * instead of the silent gap.
     */
    private volatile java.util.Map<String, String> lastSkippedSymbols = new java.util.HashMap<>();

    public java.util.Map<String, String> getLastSkippedSymbols() {
        return lastSkippedSymbols;
    }

    public AiReasoningResult selectBest(
            List<AiCandidate>     candidates,
            List<AiTradeDecision> decisions,
            AiMarketUnderstandingEngine.MarketSnapshot snapshot) {

        if (candidates.isEmpty()) return null;

        LocalTime now = LocalTime.now(IST);
        List<AiReasoningResult> results = new ArrayList<>();
        java.util.Map<String, String> skipped = new java.util.HashMap<>();

        for (int i = 0; i < candidates.size(); i++) {
            AiCandidate     candidate = candidates.get(i);
            AiTradeDecision decision  = decisions.get(i);
            double[]        f         = candidate.getFeatureVector().getFeatures();
            if (f == null || f.length < 60) {
                String reason = f == null
                        ? "Feature vector was null at ranking stage - likely a data/computation " +
                        "timing issue right after scoring"
                        : "Feature vector had only " + f.length + " of the required 60+ values - " +
                        "likely incomplete data for this symbol at this exact moment";
                log.warn("[AI-REASON] {} silently dropped from ranking (was previously marked " +
                        "Eligible on the watchlist): {}", candidate.getSymbol(), reason);
                skipped.put(candidate.getSymbol(), reason);
                continue;
            }

            double moveScore        = computeMoveScore(f, candidate);
            double timingScore      = computeTimingScore(now, f, candidate);
            double fundamentalScore = computeFundamentalScore(candidate.getSymbol(), candidate.getRvol());
            double mlConfScore      = Math.min(1.0, decision.getConfidence());
            double qualityScore     = Math.min(1.0, decision.getTradeQualityScore() / 100.0);

            double rankScore = moveScore * W_MOVE + timingScore * W_TIMING
                    + fundamentalScore * W_FUNDAMENTAL
                    + mlConfScore * W_ML_CONF + qualityScore * W_QUALITY;

            String reasoning    = buildReasoning(candidate, snapshot, 0, moveScore, fundamentalScore, timingScore, 0, 0, rankScore);
            String bullScenario = buildBullScenario(candidate, decision, f);
            String bearScenario = buildBearScenario(candidate, decision, f);

            results.add(new AiReasoningResult(candidate, decision, rankScore,
                    0, moveScore, fundamentalScore, timingScore, 0, 0,
                    mlConfScore, qualityScore, reasoning, bullScenario, bearScenario));

            log.debug("[AI-REASON] {} rank={} (move={} timing={} fund={} ml={} quality={})",
                    candidate.getSymbol(), String.format("%.3f", rankScore),
                    String.format("%.2f", moveScore), String.format("%.2f", timingScore),
                    String.format("%.2f", fundamentalScore), String.format("%.2f", mlConfScore),
                    String.format("%.2f", qualityScore));
        }

        lastSkippedSymbols = skipped;
        if (results.isEmpty()) return null;

        results.sort(Comparator.comparingDouble(AiReasoningResult::composite).reversed());
        AiReasoningResult best = results.get(0);

        // No minimum threshold — all candidates were already qualified upstream
        // by the confidence engine (score >= 70 or >= 80). Best always proceeds.

        log.info("[AI-REASON] ✅ Selected: {} rank={} | {}",
                best.candidate().getSymbol(),
                String.format("%.3f", best.composite()), best.reasoning());

        if (results.size() > 1) {
            log.info("[AI-REASON] Others: {}",
                    results.subList(1, results.size()).stream()
                            .map(r -> r.candidate().getSymbol() + "=" + String.format("%.2f", r.composite()))
                            .toList());
        }

        persistReasoning(best);
        return best;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LAYER 1 — MARKET ENVIRONMENT QUALITY
    // ═══════════════════════════════════════════════════════════════════════

    private double computeEnvironmentScore(
            AiMarketUnderstandingEngine.MarketSnapshot snapshot) {
        double score = 0;

        // Regime quality
        score += switch (snapshot.regime()) {
            case "TRENDING"  -> 1.0;
            case "RANGING"   -> 0.50;
            case "VOLATILE"  -> 0.45;
            default          -> 0.0;
        };

        // Breadth conviction
        double breadth = snapshot.breadthRatio();
        if (breadth > 1.40 || breadth < 0.60) score += 0.3;       // strong conviction
        else if (breadth > 1.20 || breadth < 0.80) score += 0.15; // moderate
        // neutral breadth adds nothing

        // VIX quality scoring
        // Low VIX (<12)   = calm market = BEST for institutional moves
        // Normal (12-18)  = ideal trading conditions
        // Elevated (18-25) = caution
        // High (>25)      = fear/crisis = penalise
        double vix = snapshot.vix();
        if      (vix < 12)               score += 0.25; // FIX: calm is ideal, was 0
        else if (vix >= 12 && vix <= 18) score += 0.20;
        else if (vix > 18 && vix <= 22)  score += 0.10;
        else if (vix > 22 && vix <= 25)  score += 0.00;
        else                              score -= 0.15; // fear — hard penalty

        // Session quality from MarketUnderstandingEngine
        score += snapshot.sessionQuality() * 0.3;

        // Trend strength
        score += Math.min(0.2, snapshot.trendStrength() / 100.0 * 0.2);

        // FIX: sector confirmation score
        // How many sectors confirm Nifty direction (0=none, 1=all)
        // 0.8+ = broad market move → strong environment
        // 0.5  = mixed sectors     → normal
        // 0.2- = divergence        → reduce environment quality
        double sectorConf = snapshot.sectorConfirmationScore();
        if      (sectorConf >= 0.7) score += 0.2;
        else if (sectorConf >= 0.5) score += 0.1;
        else if (sectorConf < 0.3)  score -= 0.1; // sector divergence — penalise

        return Math.max(0.0, Math.min(1.0, score / 2.2)); // normalise to 0-1
    }

    /**
     * FIX: Counter-trend direction penalty.
     * Called from selectBest() to penalise setups that trade AGAINST market direction.
     * LONG in a BEARISH market = -0.15 penalty on composite (significant).
     * SHORT in a BULLISH market = -0.15 penalty on composite.
     * In RANGING/SIDEWAYS markets = no penalty (both directions valid).
     */
    private double computeDirectionPenalty(AiCandidate candidate,
                                           AiMarketUnderstandingEngine.MarketSnapshot snapshot) {
        String direction   = candidate.getSuggestedDirection();
        double marketDir   = snapshot.niftyDirection(); // +1 = BULLISH, -1 = BEARISH, 0 = SIDEWAYS

        if (Math.abs(marketDir) < 0.3) return 0.0;                          // SIDEWAYS — no penalty
        if (marketDir < -0.3 && "LONG".equals(direction))  return -0.15;   // BEARISH market, LONG trade
        if (marketDir > 0.3  && "SHORT".equals(direction)) return -0.15;   // BULLISH market, SHORT trade
        return 0.0; // direction aligned — no penalty
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LAYER 2 — STOCK MOVEMENT QUALITY
    // ═══════════════════════════════════════════════════════════════════════

    private double computeMoveScore(double[] f, AiCandidate candidate) {
        double score = 0;

        // RVOL — institutional participation
        double rvol = f[16]; // normalised RVOL
        double rawRvol = candidate.getRvol();
        if      (rawRvol >= 3.0) score += 1.0;
        else if (rawRvol >= 2.0) score += 0.75;
        else if (rawRvol >= 1.5) score += 0.50;
        else if (rawRvol >= 1.2) score += 0.25;
        else                     score += 0.0;

        // Volume spike
        if (f[17] > 0) score += 0.3;

        // Buy pressure — what % of candles close in upper half
        double buyPressure = f[19];
        if      (buyPressure > 0.70) score += 0.5;
        else if (buyPressure > 0.55) score += 0.25;
        else if (buyPressure < 0.40) score -= 0.2; // selling pressure

        // MACD direction
        double macd = f[14];
        if (macd > 0.3) score += 0.3;
        else if (macd < -0.3) score -= 0.2;

        // 5-candle momentum
        double mom5 = f[10];
        if (Math.abs(mom5) > 0.5) score += 0.3;
        else if (Math.abs(mom5) > 0.2) score += 0.1;

        // Volume trend — rising = conviction
        if (f[18] > 0) score += 0.2;

        // f[21] = Relative Strength vs Nifty
        // Stock outperforming index = genuine institutional interest, not just index drift
        // Penalise stocks that are lagging the index — low quality setups
        double rs = f[21];
        if      (rs > 0.5)  score += 0.40; // strong outperformance (+1.5%+ vs Nifty)
        else if (rs > 0.2)  score += 0.20; // mild outperformance
        else if (rs < -0.3) score -= 0.20; // stock lagging index — avoid

        return Math.max(0.0, Math.min(1.0, score / 2.9)); // normalise (increased denominator)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LAYER 3 — FUNDAMENTAL CATALYST
    // Reads from MySQL — zero import of NewsStrategy
    // ═══════════════════════════════════════════════════════════════════════

    private double computeFundamentalScore(String symbol, double rvol) {
        // Try to read news context written by NewsTradingStrategy
        NewsContext news = readNewsContext(symbol);

        if (news != null && news.score() >= 65) {
            // Genuine news catalyst
            double base = news.score() / 100.0;

            // Category bonus
            double categoryBonus = switch (news.category()) {
                case "EARNINGS"           -> 0.20;
                case "MERGER_ACQUISITION" -> 0.20;
                case "RBI_POLICY"         -> 0.10;
                case "SECTOR_NEWS"        -> 0.05;
                default                   -> 0.0;
            };

            // Corroboration bonus
            double corroborationBonus = news.corroborated() ? 0.10 : 0.0;

            // Freshness penalty — older news loses conviction
            double freshness = 1.0 - Math.min(0.5, news.ageMinutes() / 120.0);

            double score = (base + categoryBonus + corroborationBonus) * freshness;
            log.debug("[AI-REASON] {} news: score={} cat={} age={}min corr={} → fundamental={}",
                    symbol, news.score(), news.category(),
                    news.ageMinutes(), news.corroborated(),
                    String.format("%.2f", score));
            return Math.min(1.0, score);
        }

        // No news — technical move only
        // High RVOL with no news = possible insider activity or sector rotation
        if (rvol > 2.5) return 0.60; // unexplained high volume — worth watching
        if (rvol > 2.0) return 0.55;
        return 0.50; // neutral — no catalyst found
    }

    private NewsContext readNewsContext(String symbol) {
        try {
            List<NewsContext> results = jdbc.query(
                    """
                    SELECT score, category, sentiment, age_minutes,
                           corroborated, headline
                    FROM news_scored_items
                    WHERE symbol = ?
                      AND scored_at > NOW() - INTERVAL 90 MINUTE
                    ORDER BY score DESC
                    LIMIT 1
                    """,
                    (rs, row) -> new NewsContext(
                            rs.getInt("score"),
                            rs.getString("category"),
                            rs.getString("sentiment"),
                            rs.getLong("age_minutes"),
                            rs.getBoolean("corroborated"),
                            rs.getString("headline")
                    ),
                    symbol
            );
            return results.isEmpty() ? null : results.get(0);
        } catch (Exception e) {
            // Table may not exist yet — return null, AI trades on technical only
            log.trace("[AI-REASON] News table not available for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LAYER 4 — TIMING QUALITY
    // ═══════════════════════════════════════════════════════════════════════

    private double computeTimingScore(LocalTime now, double[] f,
                                      AiCandidate candidate) {
        double score = 0;

        // ── Session window quality — NSE intraday trading windows ─────────
        // Matches exactly the trading window quality tiers:
        //   9:30 – 11:00 ← PRIME      highest momentum, patterns fresh
        //   11:00 – 12:30 ← GOOD      trend established, volume active
        //   12:30 – 13:30 ← MODERATE  lunch zone, spreads widen
        //   13:30 – 14:40 ← ACCEPTABLE late session, closing momentum
        //   14:40+ ← BLOCKED           window closed in Gate 2

        int totalMin = now.getHour() * 60 + now.getMinute();

        if      (totalMin >= 570 && totalMin < 660)  score += 1.00; // 9:30-11:00  PRIME
        else if (totalMin >= 660 && totalMin < 750)  score += 0.85; // 11:00-12:30 GOOD
        else if (totalMin >= 750 && totalMin < 810)  score += 0.50; // 12:30-13:30 MODERATE
        else if (totalMin >= 810 && totalMin < 880)  score += 0.25; // 13:30-14:40 ACCEPTABLE
        else                                         score += 0.00; // outside window

        // ── Pattern age — how many candles since pattern formed ───────────
        // Fresh patterns score higher — stale patterns risk entry after move
        int patternAge = computePatternAgeCandles(candidate);
        if      (patternAge <= 1) score += 0.5;  // fresh — best
        else if (patternAge <= 2) score += 0.3;  // acceptable
        else if (patternAge <= 4) score += 0.1;  // getting stale
        else                     score += 0.0;   // stale — avoid

        // ── RSI timing — avoid entering at extremes ───────────────────────
        double rsi = f[13]; // normalised RSI (0=oversold, 1=overbought)
        if (rsi > 0.8 && "LONG".equals(candidate.getSuggestedDirection()))
            score -= 0.3; // overbought — longs risky
        if (rsi < 0.2 && "SHORT".equals(candidate.getSuggestedDirection()))
            score -= 0.3; // oversold — shorts risky

        return Math.max(0.0, Math.min(1.0, score / 1.5)); // normalise to 0-1
    }

    private int computePatternAgeCandles(AiCandidate candidate) {
        double[] f = candidate.getFeatureVector().getFeatures();
        if (f == null || f.length < 60) return 5;

        // Check pattern features (Group I: 54-59)
        // If pattern detected, estimate age from candle momentum decay
        boolean sweepLow  = f[54] > 0;
        boolean sweepHigh = f[55] < 0; // FIX: SweepHigh now negative-signed
        boolean srFlip    = f[56] > 0;

        if (!sweepLow && !sweepHigh && !srFlip) return 5; // no pattern = stale

        // Use momentum decay to estimate age
        // Strong momentum (f[10] > 0.3) = fresh pattern
        // Weak momentum (f[10] < 0.1)   = older pattern
        double mom = Math.abs(f[10]);
        if (mom > 0.4) return 1;
        if (mom > 0.2) return 2;
        if (mom > 0.1) return 3;
        return 4;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LAYER 5 — RISK/REWARD QUALITY
    // ═══════════════════════════════════════════════════════════════════════

    private double computeRRScore(AiTradeDecision decision, double[] f) {
        double score = 0;

        // RR ratio quality
        double rr = decision.getRrRatio();
        if      (rr >= 3.5) score += 1.0;
        else if (rr >= 3.0) score += 0.90;
        else if (rr >= 2.5) score += 0.75;
        else if (rr >= 2.0) score += 0.55;
        else                score += 0.0; // below 2R = not worth it

        // SL quality — is it placed at real structure?
        double supportStrength = f[6]; // normalised touch count
        if      (supportStrength > 0.7) score += 0.4; // 3+ touch major level
        else if (supportStrength > 0.4) score += 0.2; // 2 touch
        else                            score += 0.0; // single touch = weak SL

        // Sector alignment bonus
        double sectorChange   = f[28]; // normalised sector change
        String direction      = decision.getDirection();
        if ("LONG".equals(direction)  && sectorChange > 0.3) score += 0.2;
        if ("SHORT".equals(direction) && sectorChange < -0.3) score += 0.2;

        // EMA alignment with trade direction
        double emaStack = f[3];
        if ("LONG".equals(direction)  && emaStack > 0.5) score += 0.2;
        if ("SHORT".equals(direction) && emaStack < -0.5) score += 0.2;

        // f[47] = Supply/Demand zone proximity
        // LONG at demand zone = institutional backing below = strong SL quality
        // LONG at supply zone = institutional selling above = weak setup
        double sdZone = f[47];
        if ("LONG".equals(direction)  && sdZone > 0.5)  score += 0.30; // at demand zone
        if ("SHORT".equals(direction) && sdZone < -0.5) score += 0.30; // at supply zone
        if ("LONG".equals(direction)  && sdZone < -0.5) score -= 0.25; // LONG at supply — risky
        if ("SHORT".equals(direction) && sdZone > 0.5)  score -= 0.25; // SHORT at demand — risky

        return Math.max(0.0, Math.min(1.0, score / 2.1)); // normalise (increased denominator)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LAYER 6 — PATTERN QUALITY
    // ═══════════════════════════════════════════════════════════════════════

    private double computePatternScore(double[] f, AiCandidate candidate) {
        double score = 0;

        // Liquidity sweep — highest quality pattern
        boolean sweepLow  = f[54] > 0;
        boolean sweepHigh = f[55] < 0; // FIX: SweepHigh now negative-signed
        if (sweepLow || sweepHigh) score += 1.0;

        // Trendline touch — FIX: f[58] now negative for falling-trendline SHORT
        boolean trendlineTouch = f[58] != 0;
        if (trendlineTouch) score += 0.5;

        // SR flip — strong structure change
        boolean srFlip = f[56] > 0;
        // FIX (per explicit user request: "hitting more stoploss...
        // please fix" for S/R flip). Per the general theory that a LONE
        // S/R flip - confirmed on the first retest with no other
        // confirming signal - is prone to false positives (price
        // "flips" briefly then continues through). Now requires
        // confluence with at least one other signal (sweep or
        // trendline touch) for the FULL bonus; a lone S/R flip with
        // nothing else confirming it gets a reduced bonus instead.
        // This is a scoring-level adjustment only, within this file -
        // f[56]'s own underlying value (however it gets computed) is
        // completely untouched, since that detection logic lives
        // outside this file.
        if (srFlip) {
            boolean hasConfluence = sweepLow || sweepHigh || trendlineTouch;
            score += hasConfluence ? 0.7 : 0.35;
        }

        // Channel position — prefer entries at extremes not midpoint
        double channelPos = f[57];
        if ("LONG".equals(candidate.getSuggestedDirection())) {
            if (channelPos < 0.3) score += 0.3; // near support = good long entry
        } else {
            if (channelPos > 0.7) score += 0.3; // near resistance = good short entry
        }

        // AI pattern confidence (f[59])
        score += f[59] * 0.3;

        // ── Daily Pattern Features f[60-69] — HTF strict validation ──────
        // f[60]=BOS  f[61]=CHOCH  f[62]=OrderBlock  f[63]=FVG  f[64]=AccumDist
        // f[65]=Triple  f[66]=H&S  f[67]=Triangle  f[68]=Channel  f[69]=Trendline
        // Each confirmed daily pattern that aligns with direction adds to score
        String dir = candidate.getSuggestedDirection();
        if (f.length > 69) {
            int bullDaily = 0, bearDaily = 0;
            for (int k = 60; k <= 69; k++) {
                if (f[k] > 0.5)  bullDaily++;
                if (f[k] < -0.5) bearDaily++;
            }
            if ("LONG".equals(dir)) {
                score += Math.min(0.6, bullDaily * 0.15);  // up to +0.6 from aligned daily patterns
                score -= Math.min(0.3, bearDaily * 0.10);  // penalty for contradicting daily patterns
            } else {
                score += Math.min(0.6, bearDaily * 0.15);
                score -= Math.min(0.3, bullDaily * 0.10);
            }
            // BOS on daily = highest quality SMC signal
            if ("LONG".equals(dir)  && f[60] > 0.8) score += 0.20;
            if ("SHORT".equals(dir) && f[60] < -0.8) score += 0.20;
            // CHOCH = trend change confirmed on daily
            if ("LONG".equals(dir)  && f[61] > 0.8) score += 0.15;
            if ("SHORT".equals(dir) && f[61] < -0.8) score += 0.15;
            // Order Block = institutional level = strong SL backing
            // FIX (per explicit user request: "hitting more stoploss...
            // please fix" for Order Block). Per the general theory that
            // not every impulsive move's origin candle reflects a
            // genuine institutional order block - many are false
            // positives in choppy or low-volume conditions. Now
            // requires the SAME move to also show BOS or CHOCH
            // confirmation (the other 2 daily SMC signals already
            // treated as highest-quality in this file) before awarding
            // this specific bonus. Order Block still participates fully
            // in the generic 10-pattern daily loop above, completely
            // unchanged - only this isolated, specific bonus is gated.
            boolean dailyStructureConfirmed =
                    ("LONG".equals(dir) && (f[60] > 0.8 || f[61] > 0.8)) ||
                            ("SHORT".equals(dir) && (f[60] < -0.8 || f[61] < -0.8));
            if (dailyStructureConfirmed) {
                if ("LONG".equals(dir)  && f[62] > 0.8) score += 0.15;
                if ("SHORT".equals(dir) && f[62] < -0.8) score += 0.15;
            }
        }

        // If no patterns at all — still possible to trade on pure momentum
        if (!sweepLow && !sweepHigh && !srFlip && !trendlineTouch) {
            // Pure momentum trade — lower but nonzero
            score = 0.35;
        }

        return Math.max(0.0, Math.min(1.0, score / 3.5)); // normalise (expanded for new patterns)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // REASONING NARRATIVE BUILDER
    // ═══════════════════════════════════════════════════════════════════════

    private String buildReasoning(
            AiCandidate candidate,
            AiMarketUnderstandingEngine.MarketSnapshot snapshot,
            double env, double move, double fund, double time,
            double pattern, double rr, double composite) {

        StringBuilder sb = new StringBuilder();

        // Market context
        sb.append(snapshot.regime()).append(" market");
        if (snapshot.breadthRatio() > 1.3) sb.append(", broad buying");
        else if (snapshot.breadthRatio() < 0.75) sb.append(", broad selling");

        // Stock movement
        double rvol = candidate.getRvol();
        if      (rvol >= 3.0) sb.append(". Exceptional volume ").append(String.format("%.1f", rvol)).append("x");
        else if (rvol >= 2.0) sb.append(". Strong institutional volume ").append(String.format("%.1f", rvol)).append("x");
        else if (rvol >= 1.5) sb.append(". Above-average volume ").append(String.format("%.1f", rvol)).append("x");

        // News
        NewsContext news = readNewsContext(candidate.getSymbol());
        if (news != null && news.score() >= 65) {
            sb.append(". News catalyst: ").append(news.category())
                    .append(" score=").append(news.score());
            if (news.corroborated()) sb.append(" (corroborated)");
        } else {
            sb.append(". Technical setup — no news");
        }

        // Pattern
        double[] f = candidate.getFeatureVector().getFeatures();
        if (f != null && f.length >= 60) {
            if (f[54] > 0) sb.append(". Liquidity sweep low confirmed");
            else if (f[55] < 0) sb.append(". Liquidity sweep high confirmed"); // FIX: now negative-signed
            if (f[56] > 0) sb.append(". S/R flip detected");
        }

        // Sector
        if (!candidate.getSector().isEmpty()) {
            sb.append(". Sector: ").append(candidate.getSector());
            if (candidate.getSectorChange() > 0.3)
                sb.append(" leading +").append(String.format("%.1f", candidate.getSectorChange())).append("%");
            else if (candidate.getSectorChange() < -0.3)
                sb.append(" lagging ").append(String.format("%.1f", candidate.getSectorChange())).append("%");
        }

        // Composite summary
        sb.append(String.format(". Composite score: %.2f/1.00", composite));

        return sb.toString();
    }

    private String buildBullScenario(AiCandidate c, AiTradeDecision d, double[] f) {
        return String.format(
                "Price holds above support %.2f and breaks resistance %.2f " +
                        "with volume continuation toward T1 %.2f",
                d.getStopLoss().doubleValue() + (d.getEntryPrice().doubleValue()
                        - d.getStopLoss().doubleValue()) * 0.3,
                d.getEntryPrice().doubleValue() * 1.005,
                d.getTarget1().doubleValue()
        );
    }

    private String buildBearScenario(AiCandidate c, AiTradeDecision d, double[] f) {
        return String.format(
                "Setup fails if price breaks below SL %.2f on volume — " +
                        "exit immediately, no averaging",
                d.getStopLoss().doubleValue()
        );
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PERSISTENCE — stores reasoning for review and learning
    // ═══════════════════════════════════════════════════════════════════════

    private void persistReasoning(AiReasoningResult result) {
        try {
            jdbc.update("""
                INSERT INTO ai_reasoning_log
                  (symbol, direction, composite_score, env_score, move_score,
                   fundamental_score, timing_score, pattern_score, rr_score,
                   ml_conf_score, quality_score,
                   reasoning, bull_scenario, bear_scenario, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
                """,
                    result.candidate().getSymbol(),
                    result.candidate().getSuggestedDirection(),
                    result.composite(),
                    result.envScore(),
                    result.moveScore(),
                    result.fundamentalScore(),
                    result.timingScore(),
                    result.patternScore(),
                    result.rrScore(),
                    result.mlConfScore(),
                    result.qualityScore(),
                    result.reasoning(),
                    result.bullScenario(),
                    result.bearScenario()
            );
        } catch (Exception ignored) {}
    }

    private void createTablesIfNeeded() {
        try {
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ai_reasoning_log (
                    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
                    symbol            VARCHAR(20),
                    direction         VARCHAR(10),
                    composite_score   DOUBLE,
                    env_score         DOUBLE,
                    move_score        DOUBLE,
                    fundamental_score DOUBLE,
                    timing_score      DOUBLE,
                    pattern_score     DOUBLE,
                    rr_score          DOUBLE,
                    ml_conf_score     DOUBLE,
                    quality_score     DOUBLE,
                    reasoning         TEXT,
                    bull_scenario     TEXT,
                    bear_scenario     TEXT,
                    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    INDEX idx_symbol (symbol),
                    INDEX idx_created (created_at)
                ) ENGINE=InnoDB
                """);
        } catch (Exception ignored) {}
    }

    // ═══════════════════════════════════════════════════════════════════════
    // INNER TYPES
    // ═══════════════════════════════════════════════════════════════════════

    /** Result of reasoning about one candidate */
    public record AiReasoningResult(
            AiCandidate     candidate,
            AiTradeDecision decision,
            double          composite,
            double          envScore,
            double          moveScore,
            double          fundamentalScore,
            double          timingScore,
            double          patternScore,
            double          rrScore,
            double          mlConfScore,
            double          qualityScore,
            String          reasoning,
            String          bullScenario,
            String          bearScenario
    ) {
        public boolean isTrade() { return composite >= 0.58; }
    }

    /** News context read from MySQL — zero coupling to NewsStrategy */
    private record NewsContext(
            int     score,
            String  category,
            String  sentiment,
            long    ageMinutes,
            boolean corroborated,
            String  headline
    ) {}
}