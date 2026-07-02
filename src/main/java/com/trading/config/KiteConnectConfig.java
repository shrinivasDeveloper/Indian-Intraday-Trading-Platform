package com.trading.config;

import com.zerodhatech.kiteconnect.KiteConnect;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

import java.net.InetSocketAddress;
import java.net.Proxy;

/**
 * KiteConnectConfig - FIX (found after Zerodha started rejecting orders
 * with "403: No IPs configured for this app - Add allowed IPs on the
 * Kite developer console"). Zerodha requires a whitelisted, STABLE
 * outbound IP before accepting order placement. Railway's default
 * outbound IP is dynamic - the fix is routing outbound Kite API calls
 * through a proxy service with a genuinely static IP (staticip.in),
 * then whitelisting THAT proxy's IP on Kite Developer Console.
 *
 * REVISED (found via repeated real-world testing): the original
 * approach of pointing KiteConnect DIRECTLY at staticip.in with
 * java.net.Authenticator-based credentials kept failing with "Failed
 * to authenticate with proxy", even after fixing Java's
 * jdk.http.auth.tunneling.disabledSchemes default (a genuine, separate
 * issue, also fixed). Root cause: OkHttp (which KiteConnect uses
 * internally) does not automatically consult java.net.Authenticator
 * for PROXY authentication unless explicitly wired via
 * OkHttpClient.Builder.proxyAuthenticator(...) - and KiteConnect's
 * public constructor has no way to inject that.
 *
 * FIX: KiteConnect now points at LocalProxyRelay (127.0.0.1) instead -
 * a small, self-contained local relay that handles the REAL,
 * authenticated CONNECT tunnel to staticip.in itself, with the Basic-
 * auth header written explicitly in plain socket code (guaranteed
 * correct, no framework making its own decisions about when to send
 * credentials). From KiteConnect's point of view, it's talking to a
 * plain, unauthenticated local proxy - sidestepping the OkHttp/
 * Authenticator uncertainty entirely.
 *
 * When proxy.enabled=false (default), behavior is 100% unchanged from
 * before this fix - the original no-proxy constructor path runs
 * exactly as it did previously.
 */
@Configuration
@Slf4j
public class KiteConnectConfig {

    @Value("${zerodha.api-key}")  private String apiKey;
    @Value("${zerodha.user-id}")  private String userId;

    @Value("${kite.proxy.enabled:false}")   private boolean proxyEnabled;
    @Value("${kite.proxy.host:}")           private String proxyHost;
    @Value("${kite.proxy.port:0}")          private int proxyPort;

    @Bean
    @DependsOn("localProxyRelay")
    public KiteConnect kiteConnect() {
        if (!proxyEnabled) {
            // UNCHANGED from before this fix - exact same code path as
            // the original file when proxy is not configured/enabled.
            KiteConnect kite = new KiteConnect(apiKey);
            kite.setUserId(userId);
            return kite;
        }

        if (proxyHost == null || proxyHost.isBlank() || proxyPort <= 0) {
            log.error("[KITE-CONFIG] kite.proxy.enabled=true but host/port not properly " +
                    "configured (host='{}', port={}) - falling back to DIRECT connection. " +
                    "Order placement will likely fail with the IP-whitelist 403 until this " +
                    "is fixed.", proxyHost, proxyPort);
            KiteConnect kite = new KiteConnect(apiKey);
            kite.setUserId(userId);
            return kite;
        }

        // Point KiteConnect at the LOCAL relay (unauthenticated from its
        // perspective) - LocalProxyRelay.java handles the actual
        // authenticated tunnel to staticip.in. This requires
        // LocalProxyRelay to have already started (Spring bean
        // initialization order: both are @Component/@Configuration
        // beans, LocalProxyRelay's @PostConstruct runs independently -
        // in practice this is fine since KiteConnect only actually
        // CONNECTS through the proxy lazily, on first real API call,
        // by which point Spring context startup has completed and
        // LocalProxyRelay's listener thread is already running).
        Proxy proxy = new Proxy(Proxy.Type.HTTP,
                new InetSocketAddress("127.0.0.1", LocalProxyRelay.getLocalPort()));
        log.info("[KITE-CONFIG] Routing KiteConnect through LOCAL relay 127.0.0.1:{} - which " +
                        "itself forwards to the authenticated static-IP proxy {}:{}. The proxy's " +
                        "EGRESS IP (not 127.0.0.1, not Railway's own IP) must be the one whitelisted " +
                        "on Kite Developer Console.",
                LocalProxyRelay.getLocalPort(), proxyHost, proxyPort);

        // VERIFIED: KiteConnect(String apiKey, Proxy proxy, boolean enableDebugLog)
        // constructor confirmed present in the SDK jar bytecode.
        KiteConnect kite = new KiteConnect(apiKey, proxy, false);
        kite.setUserId(userId);
        return kite;
    }
}