package com.trading.auth.playwright;

import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator;
import com.trading.auth.service.AuthException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.security.Key;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.*;

/**
 * Zerodha auto-login using direct HTTP API.
 *
 * Correct flow (discovered from live logs):
 *   1. POST /api/login            → get request_id
 *   2. POST /api/twofa            → 2FA accepted
 *   3. GET  /connect/login        → redirects to /connect/finish?sess_id=...
 *   4. GET  /connect/finish       → redirects to your callback with request_token
 *   5. Capture request_token from final redirect
 */
@Component
@Slf4j
public class ZerodhaPlaywrightLogin {

    @Value("${zerodha.api-key}")      private String apiKey;
    @Value("${zerodha.user-id}")      private String userId;
    @Value("${zerodha.password}")     private String password;
    @Value("${zerodha.totp-secret:}") private String totpSecret;

    private static final String BASE         = "https://kite.zerodha.com";
    private static final String LOGIN_URL    = BASE + "/api/login";
    private static final String TWOFA_URL    = BASE + "/api/twofa";
    private static final String KITE_URL     = BASE + "/connect/login?v=3&api_key=%s";

    private static final Pattern TOKEN_PATTERN =
            Pattern.compile("[?&]request_token=([a-zA-Z0-9]+)");
    private static final Pattern REQUEST_ID_PATTERN =
            Pattern.compile("\"request_id\"\\s*:\\s*\"([^\"]+)\"");

    // Static — shared across all Spring bean instances
    private static final AtomicReference<String> PENDING_TOKEN =
            new AtomicReference<>();

    public static void notifyTokenReceived(String token) {
        PENDING_TOKEN.set(token);
        log.info("Token received via callback: {}...",
                token.substring(0, Math.min(8, token.length())));
    }

    public String performLogin() {
        log.info("HTTP auto-login starting for user={}", userId);
        PENDING_TOKEN.set(null);

        try {
            CookieManager cookieManager = new CookieManager();
            cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);

            // NEVER auto-redirect — we handle each redirect manually
            HttpClient client = HttpClient.newBuilder()
                    .cookieHandler(cookieManager)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();

            // ── Step 1: POST login ────────────────────────────────────
            log.info("Step 1: POST credentials...");
            String loginBody = "user_id="  + URLEncoder.encode(userId,   "UTF-8")
                    + "&password=" + URLEncoder.encode(password, "UTF-8");

            HttpResponse<String> loginResp = client.send(
                    buildPost(LOGIN_URL, loginBody),
                    HttpResponse.BodyHandlers.ofString());

            log.info("Step 1: status={}", loginResp.statusCode());
            if (loginResp.statusCode() != 200)
                throw new AuthException("Login step 1 failed HTTP "
                        + loginResp.statusCode() + ": " + loginResp.body());

            String requestId = extract(loginResp.body(), REQUEST_ID_PATTERN);
            if (requestId == null)
                throw new AuthException(
                        "request_id not found: " + loginResp.body());
            log.info("Step 1: request_id={}... ✅",
                    requestId.substring(0, Math.min(8, requestId.length())));

            // ── Step 2: Generate TOTP ─────────────────────────────────
            String otp = resolveOtp();
            log.info("Step 2: TOTP={}", otp);

            // ── Step 3: POST 2FA ──────────────────────────────────────
            log.info("Step 3: POST 2FA...");
            String twoFaBody = "user_id="     + URLEncoder.encode(userId,    "UTF-8")
                    + "&request_id="  + URLEncoder.encode(requestId, "UTF-8")
                    + "&twofa_value=" + URLEncoder.encode(otp,       "UTF-8")
                    + "&twofa_type=totp";

            HttpResponse<String> twoFaResp = client.send(
                    buildPost(TWOFA_URL, twoFaBody),
                    HttpResponse.BodyHandlers.ofString());

            log.info("Step 3: status={}", twoFaResp.statusCode());
            if (twoFaResp.statusCode() != 200
                    || twoFaResp.body().contains("\"status\":\"error\""))
                throw new AuthException("2FA failed ["
                        + twoFaResp.statusCode() + "]: " + twoFaResp.body());
            log.info("Step 3: 2FA accepted ✅");

            // ── Step 4: GET /connect/login ────────────────────────────
            // This redirects to /connect/finish?sess_id=...
            log.info("Step 4: GET /connect/login...");
            String kiteLoginUrl = String.format(KITE_URL, apiKey);

            HttpResponse<String> kiteResp = client.send(
                    buildGet(kiteLoginUrl),
                    HttpResponse.BodyHandlers.ofString());

            log.info("Step 4: status={}", kiteResp.statusCode());

            // Check if request_token is already here
            String requestToken = findToken(kiteResp);
            if (requestToken != null) {
                log.info("Login SUCCESS ✅ at Step 4 token={}...",
                        requestToken.substring(0, Math.min(8, requestToken.length())));
                return requestToken;
            }

            // ── Step 5: Follow redirect to /connect/finish ────────────
            // Zerodha redirects: /connect/login → /connect/finish?sess_id=...
            String finishUrl = kiteResp.headers()
                    .firstValue("location").orElse(null);
            if (finishUrl == null) {
                log.warn("Step 5: No location header — checking body...");
                // Try callback via PENDING_TOKEN
                return waitForCallback();
            }

            log.info("Step 5: Following redirect to {}", finishUrl);

            // Make finishUrl absolute if relative
            if (finishUrl.startsWith("/"))
                finishUrl = BASE + finishUrl;

            HttpResponse<String> finishResp = client.send(
                    buildGet(finishUrl),
                    HttpResponse.BodyHandlers.ofString());

            log.info("Step 5: finish status={}", finishResp.statusCode());

            // Check for token in finish response
            requestToken = findToken(finishResp);
            if (requestToken != null) {
                log.info("Login SUCCESS ✅ at Step 5 token={}...",
                        requestToken.substring(0, Math.min(8, requestToken.length())));
                return requestToken;
            }

            // ── Step 6: Follow next redirect (to your callback URL) ───
            String callbackUrl = finishResp.headers()
                    .firstValue("location").orElse(null);

            if (callbackUrl != null) {
                log.info("Step 6: Redirect to callback={}", callbackUrl);
                requestToken = extract(callbackUrl, TOKEN_PATTERN);
                if (requestToken != null) {
                    log.info("Login SUCCESS ✅ at Step 6 token={}...",
                            requestToken.substring(0, Math.min(8, requestToken.length())));
                    return requestToken;
                }

                // Follow callback URL to get final redirect
                if (callbackUrl.startsWith("/"))
                    callbackUrl = BASE + callbackUrl;

                // If callback points to localhost — extract token from URL only
                // (don't actually HTTP call localhost from background thread)
                if (callbackUrl.contains("localhost")
                        || callbackUrl.contains("127.0.0.1")) {
                    requestToken = extract(callbackUrl, TOKEN_PATTERN);
                    if (requestToken != null) {
                        log.info("Login SUCCESS ✅ from callback URL token={}...",
                                requestToken.substring(0,
                                        Math.min(8, requestToken.length())));
                        return requestToken;
                    }
                } else {
                    HttpResponse<String> callbackResp = client.send(
                            buildGet(callbackUrl),
                            HttpResponse.BodyHandlers.ofString());
                    requestToken = findToken(callbackResp);
                    if (requestToken != null) {
                        log.info("Login SUCCESS ✅ at Step 6 follow token={}...",
                                requestToken.substring(0,
                                        Math.min(8, requestToken.length())));
                        return requestToken;
                    }
                }
            }

            // ── Step 7: Wait for Spring callback ─────────────────────
            log.info("Step 7: Waiting for Spring /api/auth/callback (15s)...");
            return waitForCallback();

        } catch (AuthException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuthException("Login interrupted");
        } catch (Exception e) {
            log.error("HTTP login error: {}", e.getMessage(), e);
            throw new AuthException("HTTP login failed: " + e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private String waitForCallback() throws InterruptedException {
        int waited = 0;
        while (PENDING_TOKEN.get() == null && waited < 15000) {
            Thread.sleep(500);
            waited += 500;
        }
        String token = PENDING_TOKEN.getAndSet(null);
        if (token != null) {
            log.info("Login SUCCESS ✅ via Spring callback token={}...",
                    token.substring(0, Math.min(8, token.length())));
            return token;
        }
        throw new AuthException(
                "request_token not captured after all steps. "
                        + "2FA passed. Check redirect URL in developers.kite.trade "
                        + "is http://localhost:8081/api/auth/callback");
    }

    private String findToken(HttpResponse<String> resp) {
        // Check Location header
        String location = resp.headers()
                .firstValue("location").orElse(null);
        if (location != null) {
            log.debug("findToken location={}", location);
            String t = extract(location, TOKEN_PATTERN);
            if (t != null) return t;
        }
        // Check body
        String t = extract(resp.body(), TOKEN_PATTERN);
        if (t != null) return t;
        // Check URI
        return extract(resp.uri().toString(), TOKEN_PATTERN);
    }

    private HttpRequest buildPost(String url, String body) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent",   "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("X-Kite-Version", "3")
                .header("Referer",      "https://kite.zerodha.com/")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private HttpRequest buildGet(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent",   "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("X-Kite-Version", "3")
                .header("Referer",      "https://kite.zerodha.com/")
                .GET()
                .build();
    }

    private String extract(String input, Pattern pattern) {
        if (input == null || input.isBlank()) return null;
        Matcher m = pattern.matcher(input);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Resolves OTP. ZERODHA_TOTP_SECRET can be:
     *   A) Raw Base32 secret from Zerodha: WVZLEK3LYZNLHQBOQUZXXBWTB6DRHLJU
     *   B) Base64(Base32Decoded): tXKyK2vGWrPALoUze4bTD4cTrTQ=
     * Both are auto-detected and handled correctly.
     */
    private String resolveOtp() throws Exception {
        if (totpSecret == null || totpSecret.isBlank()) {
            String env = System.getenv("ZERODHA_OTP");
            if (env != null && !env.isBlank()) return env;
            throw new AuthException(
                    "No OTP source. Set ZERODHA_TOTP_SECRET in .env");
        }

        TimeBasedOneTimePasswordGenerator totp =
                new TimeBasedOneTimePasswordGenerator();

        byte[] keyBytes;
        // Base32 detection: only uppercase A-Z and digits 2-7
        if (totpSecret.matches("[A-Z2-7=]+")) {
            log.info("TOTP: Base32 secret detected");
            keyBytes = decodeBase32(totpSecret);
        } else {
            log.info("TOTP: Base64 secret detected");
            keyBytes = Base64.getDecoder().decode(totpSecret);
        }

        Key key = new SecretKeySpec(keyBytes, totp.getAlgorithm());
        String code = String.format("%06d",
                totp.generateOneTimePassword(key, Instant.now()));
        log.info("TOTP generated: {}", code);
        return code;
    }

    private byte[] decodeBase32(String input) {
        String s = input.toUpperCase().replace("=", "");
        int bits = 0, value = 0, index = 0;
        byte[] output = new byte[s.length() * 5 / 8];
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int v = (c >= 'A' && c <= 'Z') ? c - 'A'
                    : (c >= '2' && c <= '7') ? c - '2' + 26
                    : 0;
            value = (value << 5) | v;
            bits += 5;
            if (bits >= 8) {
                output[index++] = (byte)(value >>> (bits - 8));
                bits -= 8;
            }
        }
        return java.util.Arrays.copyOf(output, index);
    }
}