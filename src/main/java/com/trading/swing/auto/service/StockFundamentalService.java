package com.trading.swing.auto.service;

import com.trading.swing.auto.domain.FundamentalSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StockFundamentalService - Rule 4 data source, rebuilt to use Yahoo
 * Finance instead of NSE's blocked API (confirmed blocked at TLS
 * fingerprint level - not fixable from Java without spoofing).
 *
 * Yahoo Finance is free, requires no API key, supports Indian stocks
 * with .NS (NSE) and .BO (BSE) suffixes - confirmed from research:
 *   - insidersPercentHeld = promoter holding proxy
 *   - institutionsPercentHeld = FII+DII combined institutional proxy
 *   - incomeStatementHistoryQuarterly = revenue/profit for growth
 *
 * Rate limit: ~2000 calls/hour (unofficial, generous for our use case
 * since auto-selection only runs once at 3pm and evaluates a limited
 * set of momentum-passing candidates, not the full universe).
 *
 * Cache: results cached for the trading day (refreshed at midnight)
 * to avoid re-fetching the same symbol multiple times in one cycle.
 */
@Service
@Slf4j
public class StockFundamentalService {

    private static final String YF_BASE =
            "https://query1.finance.yahoo.com/v10/finance/quoteSummary/";
    private static final String MODULES =
            "modules=majorHoldersBreakdown,incomeStatementHistoryQuarterly";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private final RestTemplate restTemplate = new RestTemplate();

    // Day-level cache: symbol -> snapshot. Cleared at midnight by
    // BhavcopyBackfillService or on application restart.
    private final Map<String, FundamentalSnapshot> cache = new ConcurrentHashMap<>();

    /**
     * Fetches fundamental snapshot for a stock, trying NSE suffix (.NS)
     * first, then BSE (.BO) as fallback. Returns a snapshot with null
     * fields if Yahoo Finance has no data for this symbol - callers
     * must handle null fields explicitly, never treat missing data as
     * a passing value (the Rule 4 mandatory gate in
     * AutoStockSelectionEngine correctly checks for null and skips).
     */
    public FundamentalSnapshot fetch(String symbol) {
        if (cache.containsKey(symbol)) {
            return cache.get(symbol);
        }

        FundamentalSnapshot result = tryFetch(symbol + ".NS");
        if (result == null || !result.isComplete()) {
            FundamentalSnapshot bse = tryFetch(symbol + ".BO");
            if (bse != null && bse.isComplete()) result = bse;
        }

        if (result == null) {
            result = new FundamentalSnapshot(symbol, null, null, null, null, null, null, null);
        }
        cache.put(symbol, result);

        if (result.promoterHoldingPct() == null) {
            log.debug("[FUNDAMENTAL-YF] {} - no insider holding data from Yahoo Finance " +
                            "(symbol may not exist in YF, or data not available for this ticker)",
                    symbol);
        } else {
            log.debug("[FUNDAMENTAL-YF] {} - promoter(insider)={} institutional={} " +
                            "salesGrowth={} profitGrowth={}",
                    symbol, result.promoterHoldingPct(), result.fiiHoldingPct(),
                    result.salesGrowthPct(), result.profitGrowthPct());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private FundamentalSnapshot tryFetch(String ticker) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", USER_AGENT);
            headers.set("Accept", "application/json");

            String url = YF_BASE + ticker + "?" + MODULES;
            ResponseEntity<Map> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);

            if (resp.getBody() == null) return null;
            Map<String, Object> body = (Map<String, Object>) resp.getBody();
            Map<String, Object> quoteSummary =
                    (Map<String, Object>) body.get("quoteSummary");
            if (quoteSummary == null) return null;
            List<Object> resultList = (List<Object>) quoteSummary.get("result");
            if (resultList == null || resultList.isEmpty()) return null;

            Map<String, Object> data = (Map<String, Object>) resultList.get(0);

            // -- majorHoldersBreakdown -----------------------------------
            Map<String, Object> holders =
                    (Map<String, Object>) data.get("majorHoldersBreakdown");

            BigDecimal insiderPct   = extractPct(holders, "insidersPercentHeld");
            BigDecimal instPct      = extractPct(holders, "institutionsPercentHeld");

            // Yahoo Finance doesn't separately expose FII vs DII for Indian
            // stocks - institutionsPercentHeld is their combined proxy. We
            // use this for the FII field (the spec's scoring benefits from
            // institutional holding being higher), and set DII to null since
            // we genuinely can't split it from this source.
            BigDecimal publicPct = null;
            if (insiderPct != null && instPct != null) {
                BigDecimal total = insiderPct.add(instPct);
                if (total.compareTo(BigDecimal.ONE) <= 0) {
                    publicPct = BigDecimal.ONE.subtract(total)
                            .multiply(BigDecimal.valueOf(100))
                            .setScale(2, RoundingMode.HALF_UP);
                }
            }
            BigDecimal insiderPctDisplay = insiderPct != null
                    ? insiderPct.multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP)
                    : null;
            BigDecimal instPctDisplay = instPct != null
                    ? instPct.multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP)
                    : null;

            // -- incomeStatementHistoryQuarterly -------------------------
            BigDecimal salesGrowth = null, profitGrowth = null;
            Map<String, Object> ish =
                    (Map<String, Object>) data.get("incomeStatementHistoryQuarterly");
            if (ish != null) {
                List<Map<String, Object>> statements =
                        (List<Map<String, Object>>) ish.get("incomeStatementHistory");
                if (statements != null && statements.size() >= 5) {
                    // Compare most recent quarter to same quarter one year ago
                    // (index 0 vs index 4) - avoids seasonal distortion
                    BigDecimal latestRev  = extractRaw(statements.get(0), "totalRevenue");
                    BigDecimal yearAgoRev = extractRaw(statements.get(4), "totalRevenue");
                    BigDecimal latestNet  = extractRaw(statements.get(0), "netIncome");
                    BigDecimal yearAgoNet = extractRaw(statements.get(4), "netIncome");
                    salesGrowth  = computeGrowth(latestRev, yearAgoRev);
                    profitGrowth = computeGrowth(latestNet, yearAgoNet);
                }
            }

            String sym = ticker.replaceAll("\\.(NS|BO)$", "");
            return new FundamentalSnapshot(sym, insiderPctDisplay, instPctDisplay,
                    null, null, publicPct, salesGrowth, profitGrowth);

        } catch (Exception e) {
            log.debug("[FUNDAMENTAL-YF] {} fetch failed: {}", ticker, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private BigDecimal extractPct(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object val = map.get(key);
        if (val == null) return null;
        try {
            // Yahoo Finance returns percentages as decimals (0.60 = 60%)
            return new BigDecimal(val.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private BigDecimal extractRaw(Map<String, Object> stmt, String key) {
        if (stmt == null) return null;
        Object wrapper = stmt.get(key);
        if (wrapper instanceof Map) {
            Object raw = ((Map<?, ?>) wrapper).get("raw");
            if (raw != null) {
                try { return new BigDecimal(raw.toString()); }
                catch (NumberFormatException ignored) {}
            }
        }
        return null;
    }

    private BigDecimal computeGrowth(BigDecimal latest, BigDecimal prior) {
        if (latest == null || prior == null || prior.signum() == 0) return null;
        return latest.subtract(prior)
                .divide(prior.abs(), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    /** Called by BhavcopyBackfillService at midnight / next startup. */
    public void clearCache() {
        cache.clear();
        log.debug("[FUNDAMENTAL-YF] Cache cleared for new trading day");
    }
}