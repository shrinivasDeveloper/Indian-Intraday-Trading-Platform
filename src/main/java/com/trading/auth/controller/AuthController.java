package com.trading.auth.controller;

import com.trading.auth.model.ZerodhaToken;
import com.trading.auth.playwright.ZerodhaPlaywrightLogin;
import com.trading.auth.service.AuthService;
import com.trading.marketdata.service.MarketDataStartupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@Slf4j
@RequiredArgsConstructor
public class AuthController {

    private final AuthService              authService;
    private final MarketDataStartupService startupService;
    private final ZerodhaPlaywrightLogin   playwrightLogin;

    /**
     * Zerodha redirects here after login — both manual and Playwright auto-login.
     *
     * Flow for auto-login:
     * 1. Playwright fills credentials + TOTP in headless browser
     * 2. Zerodha redirects to http://localhost:8081/api/auth/callback?request_token=XXX
     * 3. Spring Security allows it (in permitAll)
     * 4. This method fires on http-nio thread
     * 5. Calls ZerodhaPlaywrightLogin.notifyTokenReceived() — STATIC method
     *    so it updates the shared PENDING_TOKEN regardless of bean instance
     * 6. Playwright's polling loop on auth-bg thread picks it up
     * 7. Token saved to DB, pipeline started
     */
    @GetMapping("/callback")
    public ResponseEntity<String> callback(
            @RequestParam("request_token") String requestToken,
            @RequestParam(value = "status", defaultValue = "unknown") String status) {

        log.info("Callback received: status={} token={}...",
                status, requestToken.substring(0, Math.min(8, requestToken.length())));

        if (!"success".equals(status))
            return ResponseEntity.badRequest().body("Login failed: " + status);

        // Notify Playwright's polling loop via STATIC method
        // This works regardless of which bean instance is used
        ZerodhaPlaywrightLogin.notifyTokenReceived(requestToken);

        return processToken(requestToken);
    }

    @PostMapping("/token")
    public ResponseEntity<String> receiveToken(@RequestParam String requestToken) {
        return processToken(requestToken);
    }

    @GetMapping("/login-url")
    public ResponseEntity<String> loginUrl() {
        return ResponseEntity.ok(authService.getLoginUrl());
    }

    @GetMapping("/status")
    public ResponseEntity<String> status() {
        try {
            authService.getTodayToken();
            return ResponseEntity.ok("AUTHENTICATED");
        } catch (Exception e) {
            return ResponseEntity.ok("UNAUTHENTICATED — " + authService.getLoginUrl());
        }
    }

    @DeleteMapping("/logout")
    public ResponseEntity<String> logout() {
        authService.serverSideLogout();
        return ResponseEntity.ok("Logged out successfully");
    }

    @PostMapping("/trigger-auto-login")
    public ResponseEntity<String> triggerAutoLogin() {
        try {
            log.info("Manual auto-login triggered");
            String       requestToken = playwrightLogin.performLogin();
            ZerodhaToken token        = authService.generateDailyToken(requestToken);
            startupService.onTokenReady(token);
            return ResponseEntity.ok("Auto-login successful! Pipeline started.");
        } catch (Exception e) {
            log.error("Manual auto-login failed: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body("Failed: " + e.getMessage()
                            + "\n\nManual URL: " + authService.getLoginUrl());
        }
    }

    private ResponseEntity<String> processToken(String requestToken) {
        try {
            ZerodhaToken token = authService.generateDailyToken(requestToken);
            startupService.onTokenReady(token);
            log.info("Pipeline started successfully ✅");
            return ResponseEntity.ok("Authenticated. Trading pipeline started.");
        } catch (Exception e) {
            log.error("Auth failed: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body("Auth failed: " + e.getMessage());
        }
    }
}