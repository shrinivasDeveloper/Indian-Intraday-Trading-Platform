package com.trading.auth.service;

import com.trading.auth.model.ZerodhaToken;
import com.trading.auth.repository.TokenRepository;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuthService {

    private final KiteConnect     kiteConnect;
    private final TokenRepository tokenRepository;

    @Value("${zerodha.api-secret}") private String apiSecret;
    @Value("${zerodha.account-id}") private String accountId;

    // ── Generate daily token ───────────────────────────────────────────

    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 2000, multiplier = 2))
    @Transactional
    public ZerodhaToken generateDailyToken(String requestToken) {
        log.info("Generating session for account={}", accountId);
        try {
            User user = kiteConnect.generateSession(requestToken, apiSecret);
            kiteConnect.setAccessToken(user.accessToken);
            kiteConnect.setPublicToken(user.publicToken);
            log.info("Session generated userId={}", user.userId);

            ZerodhaToken token = ZerodhaToken.builder()
                    .accountId(accountId)
                    .accessToken(user.accessToken)
                    .publicToken(user.publicToken)
                    .userId(user.userId)
                    .tokenDate(LocalDate.now())
                    .expiresAt(LocalDate.now().atTime(23, 59, 59).toInstant(ZoneOffset.UTC))
                    .createdAt(Instant.now())
                    .build();

            // Delete old token for today then save new one
            deleteTokenSafely(accountId, LocalDate.now());
            tokenRepository.save(token);
            return token;

        } catch (KiteException e) {
            throw new AuthException(
                    "Token generation failed [" + e.code + "]: " + e.message);
        } catch (IOException e) {
            throw new AuthException("Network error: " + e.getMessage());
        }
    }

    // ── Validate token ────────────────────────────────────────────────
    // Uses REQUIRES_NEW so it always has its own transaction
    // Safe to call from background threads

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean validateToken() {
        try {
            kiteConnect.getProfile();
            return true;
        } catch (KiteException e) {
            if (e.code == 403) {
                log.warn("Token invalid (403) — deleting from DB");
                deleteTokenSafely(accountId, LocalDate.now());
            }
            return false;
        } catch (IOException e) {
            log.warn("Profile fetch failed (network): {}", e.getMessage());
            return false; // network issue — don't delete token
        }
    }

    // ── Delete token safely in its own transaction ────────────────────
    // REQUIRES_NEW = always runs in a fresh transaction
    // Safe to call from anywhere — @PostConstruct, background threads, etc.

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteTokenSafely(String accId, LocalDate date) {
        try {
            tokenRepository.deleteByAccountIdAndTokenDate(accId, date);
        } catch (Exception e) {
            log.warn("Token delete failed (non-critical): {}", e.getMessage());
        }
    }

    // ── Get today's token ─────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ZerodhaToken getTodayToken() {
        return tokenRepository
                .findByAccountIdAndTokenDate(accountId, LocalDate.now())
                .orElseThrow(() ->
                        new AuthException("No token for today. Login required."));
    }

    // ── Refresh KiteConnect with stored token ─────────────────────────

    public void refreshKiteConnect(ZerodhaToken token) {
        kiteConnect.setAccessToken(token.getAccessToken());
        kiteConnect.setPublicToken(token.getPublicToken());
        log.info("KiteConnect refreshed");
    }

    // ── Logout ────────────────────────────────────────────────────────

    public void serverSideLogout() {
        try {
            kiteConnect.logout();
            log.info("Zerodha logout done");
        } catch (KiteException e) {
            log.warn("Logout KiteException: {}", e.message);
        } catch (IOException e) {
            log.warn("Logout IOException: {}", e.getMessage());
        }
    }

    // ── Login URL ─────────────────────────────────────────────────────

    public String getLoginUrl() {
        return kiteConnect.getLoginURL();
    }
}