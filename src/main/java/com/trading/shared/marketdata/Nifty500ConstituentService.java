package com.trading.shared.marketdata;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Nifty500ConstituentService - FIX for a confirmed real gap, found from
 * direct user cross-check: "We have more than 500 stocks mapped... why
 * aren't all of them being added?"
 *
 * CONFIRMED via direct code inspection: InstrumentCacheService's
 * NIFTY500_SYMBOLS was a HARDCODED array of only 297 symbols, despite
 * its name - meaning the real, current ~500 Nifty 500 index constituents
 * were never fully represented, directly causing gaps like NH (a real
 * stock, not in the hardcoded list) showing "live price unavailable" on
 * News's dashboard. A static array also inevitably drifts out of date
 * as index constituents change over time (quarterly rebalancing).
 *
 * FIX: fetches NSE's own, real, current Nifty 500 constituent list
 * directly (same proven URL pattern already working for sector
 * classification - niftyindices.com, confirmed live in production:
 * "Parsed 751 stocks... 0 rows skipped"). Refreshes daily before
 * market open, so the subscription list stays genuinely current
 * without ever needing manual updates again.
 *
 * DELIBERATELY SAFE, NEVER WORSE THAN BEFORE: if this fetch fails for
 * any reason, falls back to the EXISTING hardcoded 297-symbol list
 * (kept, unchanged, as InstrumentCacheService's own fallback) rather
 * than ever leaving the subscription list empty.
 */
@Service
@Slf4j
public class Nifty500ConstituentService {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private final RestTemplate restTemplate = new RestTemplate();

    /** Real, current Nifty 500 symbols - empty until first successful
     *  fetch. Callers (InstrumentCacheService) must fall back to their
     *  own static list if this is empty, never assume it's populated. */
    private volatile Set<String> currentConstituents = new LinkedHashSet<>();

    public Set<String> getConstituents() {
        return currentConstituents; // safe to read concurrently - volatile reference swap only
    }

    /**
     * Fetch immediately on startup too, not just the daily 8 AM cron -
     * so this fix takes effect right away on deployment rather than
     * requiring a wait until the next scheduled cycle.
     */
    @jakarta.annotation.PostConstruct
    public void fetchOnStartup() {
        refresh();
    }

    /**
     * Refreshes daily at 8:00 AM IST - well before the 8:45 AM login and
     * market open, so the instrument cache build (which depends on this)
     * always has the freshest possible list ready in time.
     */
    @Scheduled(cron = "0 0 8 * * MON-FRI", zone = "Asia/Kolkata")
    public void refresh() {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", USER_AGENT);
            ResponseEntity<byte[]> resp = restTemplate.exchange(
                    "https://www.niftyindices.com/IndexConstituent/ind_nifty500list.csv",
                    HttpMethod.GET, new HttpEntity<>(headers), byte[].class);

            byte[] body = resp.getBody();
            if (body == null || body.length == 0) {
                log.warn("[NIFTY500-CONSTITUENTS] Empty response from niftyindices.com - " +
                        "keeping previous list ({} symbols) rather than wiping it on a " +
                        "transient failure", currentConstituents.size());
                return;
            }

            Set<String> parsed = parseSymbols(body);
            if (parsed.isEmpty()) {
                log.warn("[NIFTY500-CONSTITUENTS] Parsed 0 symbols from response - keeping " +
                        "previous list ({} symbols) rather than wiping it, likely a format " +
                        "change or malformed response", currentConstituents.size());
                return;
            }

            currentConstituents = parsed;
            log.info("[NIFTY500-CONSTITUENTS] Refreshed - {} real, current Nifty 500 " +
                            "constituents loaded from NSE directly (was {} in the old hardcoded " +
                            "fallback list, if this is the first successful fetch)",
                    parsed.size(), 297);
        } catch (Exception e) {
            log.error("[NIFTY500-CONSTITUENTS] Failed to download from niftyindices.com - " +
                    "instrument subscription will fall back to the static 297-symbol list " +
                    "this cycle, not the full current index: {}", e.getMessage());
        }
    }

    private Set<String> parseSymbols(byte[] csvBytes) {
        Set<String> symbols = new LinkedHashSet<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(csvBytes), StandardCharsets.UTF_8))) {

            String headerLine = reader.readLine();
            if (headerLine == null) return symbols;

            String[] headers = headerLine.split(",", -1);
            int symbolCol = -1;
            for (int i = 0; i < headers.length; i++) {
                if (headers[i].trim().equalsIgnoreCase("symbol")) {
                    symbolCol = i;
                    break;
                }
            }
            if (symbolCol == -1) {
                log.warn("[NIFTY500-CONSTITUENTS] No 'Symbol' column found in CSV header - " +
                        "niftyindices.com may have changed their format");
                return symbols;
            }

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] cols = line.split(",", -1);
                if (cols.length <= symbolCol) continue;
                String symbol = cols[symbolCol].trim().toUpperCase();
                if (!symbol.isEmpty()) symbols.add(symbol);
            }
        } catch (Exception e) {
            log.error("[NIFTY500-CONSTITUENTS] CSV parsing failed: {}", e.getMessage());
        }
        return symbols;
    }
}