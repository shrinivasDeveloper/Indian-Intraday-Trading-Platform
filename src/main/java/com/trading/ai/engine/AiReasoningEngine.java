package com.trading.ai.engine;

import com.trading.ai.model.*;
import com.trading.regime.service.MarketDirectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.*;
import java.time.LocalTime;
import java.util.*;

/**
 * AiReasoningEngine
 *
 * The core intelligence layer. Sends top candidates to Claude Sonnet and
 * receives structured trade decisions with full reasoning.
 *
 * WHY CLAUDE (not rule-based scoring):
 *   Numeric scoring can rank opportunities but cannot reason about:
 *   - Whether two conflicting signals cancel each other out
 *   - Whether the risk is worth taking given current market regime
 *   - Whether the setup "makes sense" given the broader context
 *   - Whether a news catalyst is significant enough to override technical weakness
 *   - Which of two similar setups has better asymmetry
 *
 *   Claude can evaluate all these factors simultaneously, form a narrative,
 *   challenge its own initial conclusion, and produce a calibrated probability.
 *
 * PROMPT DESIGN:
 *   The prompt gives Claude the role of a professional NSE intraday trader.
 *   It receives:
 *     1. Market context (Nifty direction, ATR, breadth, VIX, time of day)
 *     2. Top N candidates with their feature summaries
 *     3. Current portfolio state (open positions, daily P&L)
 *     4. Instructions to reason, challenge, and return structured JSON
 *
 *   Claude returns a JSON array, one entry per candidate, with:
 *     - trade: true/false
 *     - direction: LONG/SHORT
 *     - confidence: 0.0–1.0
 *     - tradeQualityScore: 0–100
 *     - opportunityScore: 0–100
 *     - riskScore: 0–100
 *     - probabilityOfSuccess: 0.0–1.0
 *     - rrRatio: double
 *     - reasoning: string (plain English explanation)
 *     - bullScenario: string
 *     - bearScenario: string
 *     - dominantFactor: string (what drove the decision most)
 *     - exitPlan: string
 *     - reject_reason: string (if trade=false)
 *
 * LATENCY:
 *   Claude Sonnet API responds in ~1–3 seconds for a 15-candidate prompt.
 *   This is acceptable because the 5m candle fires every 5 minutes.
 *   Each API call costs ~0.002 USD (15 candidates × ~300 tokens input = 4500 tokens).
 *   At 54 cycles/day × 0.002 = ~$0.11/day.
 */
@Service
@ConditionalOnProperty(name = "ai.trading.enabled", havingValue = "true")
@Slf4j
@RequiredArgsConstructor
public class AiReasoningEngine {

    private static final String CLAUDE_API_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL          = "claude-sonnet-4-20250514";
    private static final int    MAX_TOKENS     = 4096;

    @Value("${ai.trading.claude-api-key:}")
    private String claudeApiKey;

    private final ObjectMapper objectMapper;

    /**
     * Send top candidates to Claude for deep reasoning.
     * Returns a list of AiReasonedOpportunity — one per candidate.
     * Candidates Claude rejects are included with trade=false and reject_reason set.
     */
    public List<AiReasonedOpportunity> reason(
            List<AiCandidate> candidates,
            MarketDirectionService.MarketDirectionResult marketDir,
            LocalTime now
    ) {
        if (candidates.isEmpty()) return Collections.emptyList();
        if (claudeApiKey == null || claudeApiKey.isBlank()) {
            log.warn("[AI-REASON] No Claude API key configured — using numeric fallback");
            return numericFallback(candidates);
        }

        String prompt = buildPrompt(candidates, marketDir, now);

        try {
            String response = callClaudeApi(prompt);
            return parseResponse(response, candidates);
        } catch (Exception e) {
            log.error("[AI-REASON] Claude API error: {} — using numeric fallback", e.getMessage());
            return numericFallback(candidates);
        }
    }

    // ── Prompt builder ────────────────────────────────────────────────────────

    private String buildPrompt(List<AiCandidate> candidates,
                               MarketDirectionService.MarketDirectionResult dir,
                               LocalTime now) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an experienced NSE intraday trader. Analyze these trading opportunities ")
                .append("and decide which ones to trade today.\n\n");

        // Market context
        sb.append("=== MARKET CONTEXT ===\n");
        if (dir != null) {
            sb.append("Nifty Direction: ").append(dir.direction()).append("\n");
            sb.append("Nifty ATR%: ").append(String.format("%.2f", dir.niftyAtrPct())).append("\n");
            sb.append("Market Label: ").append(dir.direction().name()).append("\n");
        }
        sb.append("Time: ").append(now).append(" IST\n");
        sb.append("Session: ").append(getSessionPhase(now)).append("\n\n");

        // Candidates
        sb.append("=== CANDIDATES (").append(candidates.size()).append(") ===\n\n");

        for (int i = 0; i < candidates.size(); i++) {
            AiCandidate c = candidates.get(i);
            sb.append("CANDIDATE ").append(i+1).append(": ").append(c.getSymbol()).append("\n");
            sb.append("  LTP: ₹").append(c.getLtp()).append("\n");
            sb.append("  Pre-score: ").append(String.format("%.1f", c.getNumericScore())).append("/100\n");
            sb.append("  Direction: ").append(c.getSuggestedDirection()).append("\n");
            sb.append("  Sector: ").append(c.getSector()).append("\n");
            sb.append("  HTF Trend: ").append(c.getHtfTrend()).append("\n");
            sb.append("  EMA Stack: ").append(c.getEmaStackDesc()).append("\n");
            sb.append("  RVOL: ").append(String.format("%.2f", c.getRvol())).append("×\n");
            sb.append("  Distance from Support: ").append(String.format("%.2f", c.getDistFromSupport())).append("%\n");
            sb.append("  Distance from Resistance: ").append(String.format("%.2f", c.getDistFromResistance())).append("%\n");
            sb.append("  Support Strength: ").append(c.getSupportStrength()).append("/100\n");
            sb.append("  S/R Flip (Breakout Retest): ").append(c.isSrFlip()).append("\n");
            sb.append("  Liquidity Sweep: ").append(c.isLiquiditySweep()).append("\n");
            sb.append("  Trendline Touch: ").append(c.isTrendlineTouch()).append("\n");
            sb.append("  Channel Boundary: ").append(c.getChannelPosition()).append("\n");
            sb.append("  5m Return: ").append(String.format("%.2f", c.getReturn5m())).append("%\n");
            sb.append("  15m Return: ").append(String.format("%.2f", c.getReturn15m())).append("%\n");
            sb.append("  1H Return: ").append(String.format("%.2f", c.getReturn1h())).append("%\n");
            sb.append("  Volume Spike: ").append(c.isVolumeSpike()).append("\n");
            sb.append("  Sector Strength: ").append(String.format("%.2f", c.getSectorChange())).append("%\n");
            sb.append("  News: ").append(c.getNewsSummary()).append("\n");
            sb.append("  AI Win Rate (historical): ").append(String.format("%.0f", c.getHistoricalWinRate()*100)).append("%\n");
            sb.append("\n");
        }

        sb.append("=== YOUR TASK ===\n")
                .append("For each candidate:\n")
                .append("1. Evaluate the opportunity from a professional trader's perspective\n")
                .append("2. Generate a bullish scenario AND a bearish scenario\n")
                .append("3. Challenge your initial opinion — what could go wrong?\n")
                .append("4. Decide: trade or pass?\n")
                .append("5. If trade: assign confidence (0-1), quality score (0-100), probability of success\n")
                .append("6. Explain your dominant reasoning factor\n\n")
                .append("RULES:\n")
                .append("- Minimum RR 2.0, prefer 3.0+\n")
                .append("- Reject if market direction conflicts with trade direction\n")
                .append("- Reject if setup is mid-air (no S/R, trendline, or structural context)\n")
                .append("- Reject if RVOL < 0.8 (no participation)\n")
                .append("- Prioritize: liquidity sweeps > breakout retests > S/R bounces > trendline touches\n")
                .append("- Maximum 5 TRADE=true decisions total\n\n")
                .append("Return ONLY valid JSON array. No preamble. No explanation outside JSON.\n\n")
                .append("JSON format:\n")
                .append("[\n")
                .append("  {\n")
                .append("    \"symbol\": \"RELIANCE\",\n")
                .append("    \"trade\": true,\n")
                .append("    \"direction\": \"LONG\",\n")
                .append("    \"confidence\": 0.72,\n")
                .append("    \"tradeQualityScore\": 76,\n")
                .append("    \"opportunityScore\": 78,\n")
                .append("    \"riskScore\": 28,\n")
                .append("    \"probabilityOfSuccess\": 0.64,\n")
                .append("    \"rrRatio\": 2.8,\n")
                .append("    \"reasoning\": \"Liquidity sweep below equal lows with strong reversal candle. HTF bullish, sector leading. RVOL 2.1x confirms institutional participation.\",\n")
                .append("    \"bullScenario\": \"...\",\n")
                .append("    \"bearScenario\": \"...\",\n")
                .append("    \"dominantFactor\": \"Liquidity sweep + HTF alignment + RVOL confirmation\",\n")
                .append("    \"exitPlan\": \"Trail SL to breakeven after T1. Exit if closes below sweep low.\"\n")
                .append("  },\n")
                .append("  {\n")
                .append("    \"symbol\": \"INFY\",\n")
                .append("    \"trade\": false,\n")
                .append("    \"confidence\": 0.31,\n")
                .append("    \"tradeQualityScore\": 38,\n")
                .append("    \"reasoning\": \"\",\n")
                .append("    \"bullScenario\": \"\",\n")
                .append("    \"bearScenario\": \"\",\n")
                .append("    \"dominantFactor\": \"\",\n")
                .append("    \"exitPlan\": \"\",\n")
                .append("    \"reject_reason\": \"Mid-air entry — no nearby S/R. Volume below average.\"\n")
                .append("  }\n")
                .append("]\n");

        return sb.toString();
    }

    // ── Claude API call ───────────────────────────────────────────────────────

    private String callClaudeApi(String prompt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", MODEL);
        body.put("max_tokens", MAX_TOKENS);
        body.put("messages", List.of(
                Map.of("role", "user", "content", prompt)
        ));

        String json = objectMapper.writeValueAsString(body);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(CLAUDE_API_URL))
                .header("Content-Type", "application/json")
                .header("x-api-key", claudeApiKey)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> resp = HttpClient.newHttpClient()
                .send(req, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() != 200) {
            throw new RuntimeException("Claude API returned " + resp.statusCode() + ": " + resp.body());
        }

        // Parse response.content[0].text
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(resp.body(), Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) parsed.get("content");
        if (content == null || content.isEmpty()) throw new RuntimeException("Empty Claude response");
        return (String) content.get(0).get("text");
    }

    // ── Response parser ───────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<AiReasonedOpportunity> parseResponse(String responseText,
                                                      List<AiCandidate> candidates) {
        try {
            // Strip any leading text before '['
            int start = responseText.indexOf('[');
            int end   = responseText.lastIndexOf(']');
            if (start < 0 || end < 0) throw new RuntimeException("No JSON array in response");
            String jsonArray = responseText.substring(start, end+1);

            List<Map<String, Object>> items = objectMapper.readValue(jsonArray, List.class);
            Map<String, AiCandidate> candMap = new HashMap<>();
            candidates.forEach(c -> candMap.put(c.getSymbol(), c));

            List<AiReasonedOpportunity> result = new ArrayList<>();
            for (Map<String, Object> item : items) {
                String symbol = (String) item.getOrDefault("symbol", "");
                AiCandidate cand = candMap.get(symbol);
                if (cand == null) continue;

                result.add(AiReasonedOpportunity.builder()
                        .candidate(cand)
                        .shouldTrade((Boolean) item.getOrDefault("trade", false))
                        .direction(String.valueOf(item.getOrDefault("direction", cand.getSuggestedDirection())))
                        .confidence(toDouble(item.get("confidence"), 0))
                        .tradeQualityScore(toInt(item.get("tradeQualityScore"), 0))
                        .opportunityScore(toInt(item.get("opportunityScore"), 0))
                        .riskScore(toInt(item.get("riskScore"), 50))
                        .probabilityOfSuccess(toDouble(item.get("probabilityOfSuccess"), 0))
                        .rrRatio(toDouble(item.get("rrRatio"), 0))
                        .reasoning(String.valueOf(item.getOrDefault("reasoning", "")))
                        .bullScenario(String.valueOf(item.getOrDefault("bullScenario", "")))
                        .bearScenario(String.valueOf(item.getOrDefault("bearScenario", "")))
                        .dominantFactor(String.valueOf(item.getOrDefault("dominantFactor", "")))
                        .exitPlan(String.valueOf(item.getOrDefault("exitPlan", "")))
                        .rejectReason(String.valueOf(item.getOrDefault("reject_reason", "")))
                        .build());
            }
            log.info("[AI-REASON] Claude responded: {}/{} candidates to trade",
                    result.stream().filter(AiReasonedOpportunity::isShouldTrade).count(),
                    result.size());
            return result;

        } catch (Exception e) {
            log.error("[AI-REASON] Failed to parse Claude response: {}", e.getMessage());
            return numericFallback(candidates);
        }
    }

    // ── Numeric fallback (when Claude API unavailable) ────────────────────────

    private List<AiReasonedOpportunity> numericFallback(List<AiCandidate> candidates) {
        List<AiReasonedOpportunity> result = new ArrayList<>();
        for (AiCandidate c : candidates) {
            double score = c.getNumericScore();
            boolean trade = score >= 65;
            result.add(AiReasonedOpportunity.builder()
                    .candidate(c)
                    .shouldTrade(trade)
                    .direction(c.getSuggestedDirection())
                    .confidence(score / 100.0)
                    .tradeQualityScore((int) score)
                    .opportunityScore((int) score)
                    .riskScore(100 - (int) score)
                    .probabilityOfSuccess(score / 100.0 * 0.8)
                    .rrRatio(2.5)
                    .reasoning("Numeric fallback — Claude API unavailable. Score: " + (int)score)
                    .rejectReason(trade ? "" : "Score below threshold (numeric fallback)")
                    .build());
        }
        return result;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String getSessionPhase(LocalTime t) {
        if (t.isBefore(LocalTime.of(10, 0)))  return "OPENING (9:30-10:00) — high volatility";
        if (t.isBefore(LocalTime.of(11, 30))) return "MORNING (10:00-11:30) — trend developing";
        if (t.isBefore(LocalTime.of(13, 0)))  return "MIDDAY (11:30-13:00) — lower volume";
        if (t.isBefore(LocalTime.of(14, 30))) return "AFTERNOON (13:00-14:30) — momentum plays";
        return "LATE SESSION (14:30+) — avoid new entries";
    }

    private double toDouble(Object v, double def) {
        if (v == null) return def;
        if (v instanceof Number) return ((Number)v).doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (Exception e) { return def; }
    }

    private int toInt(Object v, int def) {
        if (v == null) return def;
        if (v instanceof Number) return ((Number)v).intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return def; }
    }
}