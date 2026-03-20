package com.trading.auth.service;

import com.trading.auth.model.ZerodhaToken;
import com.trading.auth.playwright.ZerodhaPlaywrightLogin;
import com.trading.auth.repository.TokenRepository;
import com.trading.marketdata.service.MarketDataStartupService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
@Slf4j
@RequiredArgsConstructor
public class DailyLoginScheduler {

    private final AuthService              authService;
    private final ZerodhaPlaywrightLogin   playwrightLogin;
    private final TokenRepository          tokenRepository;
    private final MarketDataStartupService startupService;

    @Value("${zerodha.account-id}")
    private String accountId;

    @Value("${zerodha.auto-login:false}")
    private boolean autoLogin;

    // Java 17 compatible thread pool — replaces Thread.ofVirtual()
    private final ExecutorService backgroundExecutor =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                t.setName("auth-bg");
                return t;
            });

    // ── Startup — runs in background, never blocks main thread ────────

    @PostConstruct
    public void onStartup() {
        backgroundExecutor.submit(() -> {
            try {
                Thread.sleep(3000); // wait for Spring context to fully load
                log.info("Auth startup check...");

                tokenRepository
                        .findByAccountIdAndTokenDate(accountId, LocalDate.now())
                        .ifPresentOrElse(
                                token -> {
                                    try {
                                        authService.refreshKiteConnect(token);
                                        if (authService.validateToken()) {
                                            log.info("Startup: stored token valid — starting pipeline");
                                            startupService.onTokenReady(token);
                                        } else {
                                            log.warn("Startup: stored token invalid or expired");
                                            handleNoToken();
                                        }
                                    } catch (Exception e) {
                                        log.warn("Startup token error: {}", e.getMessage());
                                        handleNoToken();
                                    }
                                },
                                () -> {
                                    log.warn("Startup: no token found for today ({})",
                                            LocalDate.now());
                                    handleNoToken();
                                }
                        );
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("Auth startup error: {}", e.getMessage());
                printManualLoginInstructions();
            }
        });
    }

    // ── Daily scheduled check: 08:45 IST ─────────────────────────────

    @Scheduled(cron = "0 45 8 * * MON-FRI", zone = "Asia/Kolkata")
    public void scheduledDailyCheck() {
        try {
            if (tokenRepository
                    .findByAccountIdAndTokenDate(accountId, LocalDate.now())
                    .isEmpty()) {
                log.info("Scheduled: no token for today — attempting login");
                if (autoLogin) autoLoginInBackground();
                else printManualLoginInstructions();
            } else {
                log.info("Scheduled: token already exists for today ✅");
            }
        } catch (Exception e) {
            log.error("Scheduled login check failed: {}", e.getMessage());
        }
    }

    // ── EOD cleanup: 15:35 IST ────────────────────────────────────────

    @Scheduled(cron = "0 35 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void endOfDayLogout() {
        try {
            authService.serverSideLogout();
            // Safe delete in own transaction
            tokenRepository.deleteByAccountIdAndTokenDate(accountId, LocalDate.now());
            log.info("EOD: logout complete, token deleted");
        } catch (Exception e) {
            log.warn("EOD logout error (non-critical): {}", e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private void handleNoToken() {
        if (autoLogin) autoLoginInBackground();
        else printManualLoginInstructions();
    }

    private void autoLoginInBackground() {
        backgroundExecutor.submit(() -> {
            try {
                log.info("Starting Playwright auto-login...");
                String       requestToken = playwrightLogin.performLogin();
                ZerodhaToken token        = authService.generateDailyToken(requestToken);
                startupService.onTokenReady(token);
                log.info("Auto-login successful — pipeline started ✅");
            } catch (Exception e) {
                log.error("Auto-login FAILED: {}", e.getMessage());
                printManualLoginInstructions();
            }
        });
    }

    private void printManualLoginInstructions() {
        String loginUrl = "";
        try { loginUrl = authService.getLoginUrl(); }
        catch (Exception ignored) {}

        log.warn("╔══════════════════════════════════════════════════════╗");
        log.warn("║         ZERODHA MANUAL LOGIN REQUIRED                ║");
        log.warn("╠══════════════════════════════════════════════════════╣");
        log.warn("║  Open in browser:                                    ║");
        log.warn("║  {}", loginUrl);
        log.warn("║                                                      ║");
        log.warn("║  Login → TOTP → redirects to:                       ║");
        log.warn("║  http://localhost:8081/api/auth/callback             ║");
        log.warn("╚══════════════════════════════════════════════════════╝");
    }
}