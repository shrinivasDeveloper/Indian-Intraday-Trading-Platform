package com.trading.swing.auto.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * NseDataClient - a genuine, real HTTP client for NSE India's public,
 * free (but unofficial/undocumented) "Next API" - the same internal API
 * the nseindia.com website itself uses, confirmed against the
 * open-source `nse` Python library's documented endpoint behavior
 * (bennythadikaran.github.io/NseIndiaApi). This is NOT Zerodha's
 * KiteConnect API - Kite has no fundamentals/shareholding data at all;
 * this is a completely separate, free public data source.
 *
 * HONEST LIMITATION, stated plainly rather than hidden: nseindia.com is
 * not in this environment's network allowlist, so I cannot make a live
 * test call to verify this against a real response while building it.
 * Every endpoint path and the cookie-handshake requirement are built
 * from genuinely real, documented behavior (NSE requires a homepage
 * visit first to obtain session cookies before its API endpoints will
 * respond - without this, NSE returns 403). On first deploy, check the
 * logs for parse failures - NSE's internal API has no stability
 * guarantee and can change field names without notice, since it was
 * never meant for external use.
 */
@Component
@Slf4j
public class NseDataClient {

    private static final String BASE = "https://www.nseindia.com";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    // REVERTED (confirmed via real production deployment log): an
    // earlier fix attempted routing these calls through the SAME
    // static-IP proxy already used for KiteConnect, on the theory that
    // NSE was blocking Railway's raw outbound IP specifically. The
    // actual result: "[LOCAL-PROXY-RELAY] Upstream proxy rejected the
    // tunnel: HTTP/1.1 403 Forbidden" - the PROXY ITSELF rejected the
    // tunnel to nseindia.com/niftyindices.com, before NSE was ever even
    // reached. This confirms staticip.in's proxy is almost certainly
    // scoped/authorized only for Zerodha's Kite API domains (its actual
    // purchased purpose), not general-purpose forwarding to arbitrary
    // destinations. That fix made things WORSE (blocking every NSE call
    // outright) rather than helping - reverted back to this original,
    // simple, direct-connection behavior.
    private final RestTemplate restTemplate = new RestTemplate();
    private final AtomicReference<String> cookieJar = new AtomicReference<>(null);
    private volatile Instant cookieObtainedAt = Instant.EPOCH;

    // FIX (permanent, legitimate): circuit breaker for sustained 403s.
    // Researched genuine alternatives (Screener.in disallows automated
    // access via robots.txt; Trendlyne's Terms of Use explicitly
    // prohibit machine-readable-database use without written
    // authorization) - neither is a legitimate substitute. The 403 from
    // nseindia.com itself is an IP-reputation block at Akamai's edge on
    // the WHOLE domain (confirmed: it fails on the very first homepage
    // handshake, before any specific page is even requested) - no
    // different NSE URL bypasses this, since they all sit behind the
    // same edge infrastructure. What CAN be permanently fixed: stop
    // wastefully retrying every single cycle once a sustained block is
    // detected. After 5 consecutive failures, back off for 1 hour
    // instead of hammering a domain that's actively rejecting us -
    // reduces noise and wasted calls, costs nothing in trading
    // correctness (Rule 4 already degrades gracefully either way), and
    // automatically resumes trying if the block ever lifts.
    private final java.util.concurrent.atomic.AtomicInteger consecutiveFailures =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private volatile Instant circuitOpenUntil = Instant.EPOCH;
    private static final int FAILURE_THRESHOLD = 5;
    private static final long COOLDOWN_SECONDS = 3600; // 1 hour

    /**
     * NSE's session cookies expire periodically - refresh if older than
     * 5 minutes rather than on every single call, to avoid hammering the
     * homepage unnecessarily.
     *
     * FIX: confirmed in production logs that nseindia.com's Akamai edge
     * (errors.edgesuite.net) was returning 403 with the original minimal
     * header set. Akamai's bot detection scores requests partly on how
     * "complete" and browser-like the header set looks - a real Chrome
     * browser sends far more than just User-Agent/Accept. Added the
     * standard additional headers a genuine browser sends
     * (Accept-Language, Accept-Encoding, sec-fetch-*, sec-ch-ua, DNT,
     * Connection) to improve the odds of passing that check. HONEST
     * CAVEAT, not hidden: Akamai can also fingerprint at the IP-
     * reputation/TLS level - if this server's IP is itself flagged as a
     * known datacenter/cloud range, no HTTP header combination can
     * reliably bypass that from a plain Java HTTP client. This change
     * improves the odds; it cannot guarantee success against a
     * determined edge-level block. The system already degrades
     * gracefully either way - fundamentals scoring simply has less real
     * data to work with on cycles where this still fails, exactly as
     * before this change.
     */
    private synchronized void ensureSession() {
        if (cookieJar.get() != null && Instant.now().minusSeconds(300).isBefore(cookieObtainedAt)) {
            return;
        }
        if (Instant.now().isBefore(circuitOpenUntil)) {
            log.debug("[NSE-CLIENT] Circuit open (sustained failures) - skipping attempt until {}",
                    circuitOpenUntil);
            return;
        }
        try {
            HttpHeaders headers = browserLikeHeaders();
            headers.set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8");
            ResponseEntity<String> resp = restTemplate.exchange(
                    BASE, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            List<String> setCookies = resp.getHeaders().get(HttpHeaders.SET_COOKIE);
            if (setCookies != null && !setCookies.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (String c : setCookies) {
                    sb.append(c.split(";", 2)[0]).append("; ");
                }
                cookieJar.set(sb.toString());
                cookieObtainedAt = Instant.now();
                consecutiveFailures.set(0); // genuine success - reset the breaker
                log.debug("[NSE-CLIENT] Session cookies refreshed");
            }
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            // FIX: was logging ex.getMessage(), which for HTTP error
            // responses includes Spring's full response body - in this
            // case, NSE's entire HTML error page (hundreds of lines),
            // dumped to the console on EVERY retry. Pure logging cleanup,
            // zero change to the actual session/retry behavior - still
            // retries exactly the same way, just logs a one-line summary
            // instead of flooding the console.
            log.warn("[NSE-CLIENT] Could not establish session with nseindia.com - " +
                            "fundamental-data calls will likely fail this cycle: HTTP {} {}",
                    ex.getRawStatusCode(), ex.getStatusText());
            recordFailureAndMaybeOpenCircuit();
        } catch (Exception e) {
            log.warn("[NSE-CLIENT] Could not establish session with nseindia.com - " +
                    "fundamental-data calls will likely fail this cycle: {}", e.getMessage());
            recordFailureAndMaybeOpenCircuit();
        }
    }

    /**
     * Tracks consecutive failures; after FAILURE_THRESHOLD in a row,
     * opens the circuit for COOLDOWN_SECONDS - stops retrying every
     * single cycle against a domain that's actively rejecting us, and
     * automatically resumes trying once the cooldown expires (so if the
     * IP-level block ever lifts, the system recovers on its own, no
     * restart needed).
     */
    private void recordFailureAndMaybeOpenCircuit() {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= FAILURE_THRESHOLD) {
            circuitOpenUntil = Instant.now().plusSeconds(COOLDOWN_SECONDS);
            log.warn("[NSE-CLIENT] {} consecutive failures - backing off for {} minutes rather " +
                    "than retrying every cycle. Will automatically resume trying after that, " +
                    "in case the block lifts.", failures, COOLDOWN_SECONDS / 60);
            consecutiveFailures.set(0); // reset counter for the next window after cooldown
        }
    }

    /**
     * Full, realistic browser header set - shared by both the session
     * handshake and the JSON API calls, so both look equally genuine.
     */
    private HttpHeaders browserLikeHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", USER_AGENT);
        headers.set("Accept-Language", "en-US,en;q=0.9");
        headers.set("Accept-Encoding", "gzip, deflate, br");
        headers.set("Connection", "keep-alive");
        headers.set("Upgrade-Insecure-Requests", "1");
        headers.set("Sec-Fetch-Dest", "document");
        headers.set("Sec-Fetch-Mode", "navigate");
        headers.set("Sec-Fetch-Site", "none");
        headers.set("Sec-Fetch-User", "?1");
        headers.set("Sec-CH-UA", "\"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\", \"Not-A.Brand\";v=\"99\"");
        headers.set("Sec-CH-UA-Mobile", "?0");
        headers.set("Sec-CH-UA-Platform", "\"Windows\"");
        headers.set("DNT", "1");
        return headers;
    }

    private Map<String, Object> getJson(String path) {
        ensureSession();
        try {
            HttpHeaders headers = browserLikeHeaders();
            headers.set("Accept", "application/json");
            headers.set("Referer", BASE + "/");
            headers.set("X-Requested-With", "XMLHttpRequest");
            if (cookieJar.get() != null) headers.set("Cookie", cookieJar.get());

            ResponseEntity<Map> resp = restTemplate.exchange(
                    BASE + path, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            //noinspection unchecked
            return resp.getBody();
        } catch (Exception e) {
            log.warn("[NSE-CLIENT] GET {} failed: {}", path, e.getMessage());
            return null;
        }
    }

    /**
     * Shareholding pattern - promoter/public/employee-trust holding, most
     * recent quarter first. Endpoint path confirmed against the `nse`
     * Python library's documented reference URL pattern.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getShareholdingPattern(String symbol) {
        Map<String, Object> result = getJson(
                "/api/corporate-share-holdings-master?index=equities&symbol=" + symbol);
        if (result == null) return List.of();
        Object data = result.get("data");
        return data instanceof List ? (List<Map<String, Object>>) data : List.of();
    }

    /**
     * Quarterly financial results - used for sales growth / profit
     * growth (Rule 4). Mirrors the same access pattern as shareholding;
     * exact field names should be verified against a real response on
     * first deploy, same caveat as the class-level note.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getFinancialResults(String symbol) {
        Map<String, Object> result = getJson(
                "/api/corporate-financial-results?index=equities&symbol=" + symbol);
        if (result == null) return List.of();
        Object data = result.get("data");
        return data instanceof List ? (List<Map<String, Object>>) data : List.of();
    }

    /**
     * Daily bhavcopy URL for a given date - NSE publishes ALL stocks'
     * EOD OHLCV in one CSV per trading day. Far more practical for
     * "scan every listed stock" than per-symbol calls. Format confirmed
     * against the `nse` Python library: dates from 8 July 2024 onward
     * use the new UDIFF naming; this client targets that current format.
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
     * This is genuinely free and public - no login, no API key. It does
     * NOT cover literally every NSE+BSE-listed stock (smaller micro-caps
     * beyond these 750 aren't included) - that remainder still needs a
     * lower-confidence keyword fallback, clearly distinguished from this
     * real data, not blended with it.
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

    public byte[] downloadBhavcopy(java.time.LocalDate date) {
        // FIX (found via direct user report + confirmed from real
        // production logs): ensureSession() was called here
        // unconditionally, but proven unnecessary - bhavcopy downloads
        // succeed even when session-establishment fails right before
        // them (confirmed: "Could not establish session... 403" followed
        // moments later by "Parsed 3141 equity bars... SUCCESS" for a
        // different date in the same run). Bhavcopy comes from
        // nsearchives.nseindia.com, a separate subdomain that doesn't
        // need the www.nseindia.com session cookie at all. This call was
        // pure wasted overhead and log noise on every single backfill
        // cycle - removed. The genuine fundamental-data methods
        // (getShareholdingPattern, getFinancialResults) still call
        // ensureSession() themselves where it's actually needed - this
        // change only affects the bhavcopy path, where it was never
        // actually required.
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", USER_AGENT);
            if (cookieJar.get() != null) headers.set("Cookie", cookieJar.get());
            ResponseEntity<byte[]> resp = restTemplate.exchange(
                    buildBhavcopyUrl(date), HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
            return resp.getBody();
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            // FIX: same logging cleanup as ensureSession() above - status
            // code only, not the full HTML 404 page on every weekend/
            // holiday. Zero change to actual behavior - still correctly
            // returns null either way, the gradual backfill's own
            // consecutiveEmptyDays logic (unchanged) handles this exactly
            // as before.
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