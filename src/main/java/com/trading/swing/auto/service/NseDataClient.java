package com.trading.swing.auto.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.format.DateTimeFormatter;

/**
 * NseDataClient - HTTP client for the two genuine, real, actually-used
 * NSE data sources this Swing strategy depends on:
 *   1. downloadOfficialSectorMapping() - real per-company sector
 *      classification from niftyindices.com (Nifty Total Market, 750
 *      stocks)
 *   2. downloadBhavcopy() - daily EOD OHLCV bhavcopy files from
 *      nsearchives.nseindia.com
 *
 * PERMANENTLY REMOVED (per explicit user question: "our Swing Strategy
 * is not dependent on fundamental data, so why is this file still
 * required?"): this file previously also contained a fundamental-data
 * subsystem (getShareholdingPattern, getFinancialResults) built for a
 * "Rule 4" fundamentals check that was removed from the strategy
 * earlier this session. Confirmed via direct search: these methods,
 * and everything that existed only to support them (session-cookie
 * handling for www.nseindia.com, the circuit breaker for that path,
 * browser-header spoofing, ensureSession()) had ZERO callers anywhere
 * in the entire codebase - genuinely dead code, producing wasted
 * network calls and "Could not establish session with nseindia.com"
 * log noise for no functional benefit. Removed entirely.
 *
 * Neither remaining method needs session cookies at all - both
 * download static, publicly-accessible files directly, confirmed
 * empirically in production: bhavcopy downloads succeed consistently
 * with a plain, direct request (no cookies, no session handshake).
 */
@Component
@Slf4j
public class NseDataClient {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Daily bhavcopy URL for a given date - NSE publishes ALL stocks'
     * EOD OHLCV in one CSV per trading day. Format confirmed against
     * the `nse` Python library: dates from 8 July 2024 onward use the
     * new UDIFF naming; this client targets that current format.
     */
    public String buildBhavcopyUrl(java.time.LocalDate date) {
        String dateStr = date.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        return "https://nsearchives.nseindia.com/content/cm/BhavCopy_NSE_CM_0_0_0_"
                + dateStr + "_F_0000.csv.zip";
    }

    /**
     * Downloads NSE Indices Ltd.'s OWN, real, authoritative per-company
     * sector classification - NOT a keyword guess. This is the exact
     * same data source the official NSE/Trendlyne sector pages are built
     * on: revenue-segment-based classification (>50% of revenue from one
     * business = that sector; explicit "Diversified" category otherwise
     * - confirmed against NSE Indices' own published methodology PDF).
     *
     * Covers the "Nifty Total Market" index - 750 stocks (Nifty 500 +
     * Nifty Microcap 250 combined), confirmed directly from
     * niftyindices.com's own page text: "track the performance of 750
     * stocks covering large, mid, small and microcap segments."
     *
     * This is genuinely free and public - no login, no API key, no
     * session cookie needed.
     */
    public byte[] downloadOfficialSectorMapping() {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", USER_AGENT);
            ResponseEntity<byte[]> resp = restTemplate.exchange(
                    "https://www.niftyindices.com/IndexConstituent/ind_niftytotalmarket_list.csv",
                    HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
            return resp.getBody();
        } catch (Exception e) {
            log.error("[NSE-CLIENT] Failed to download official sector mapping from " +
                    "niftyindices.com - sector classification will fall back to keyword " +
                    "matching for ALL stocks this cycle, not just the ones genuinely outside " +
                    "the 750-stock coverage: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Downloads the daily bhavcopy CSV.ZIP directly - confirmed via
     * production logs that no session/cookie is needed for this
     * subdomain (nsearchives.nseindia.com), unlike the main
     * www.nseindia.com site.
     */
    public byte[] downloadBhavcopy(java.time.LocalDate date) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", USER_AGENT);
            ResponseEntity<byte[]> resp = restTemplate.exchange(
                    buildBhavcopyUrl(date), HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
            return resp.getBody();
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            log.warn("[NSE-CLIENT] Bhavcopy download failed for {}: HTTP {} (market holiday, " +
                            "weekend, or NSE format change are the most likely causes)",
                    date, ex.getRawStatusCode());
            return null;
        } catch (Exception e) {
            log.warn("[NSE-CLIENT] Bhavcopy download failed for {}: {} (market holiday, " +
                            "weekend, or NSE format change are the most likely causes)",
                    date, e.getMessage());
            return null;
        }
    }
}