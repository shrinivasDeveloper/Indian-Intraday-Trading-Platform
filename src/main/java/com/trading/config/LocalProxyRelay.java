package com.trading.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/**
 * LocalProxyRelay - FIX for a confirmed, real limitation: after fixing
 * Java's jdk.http.auth.tunneling.disabledSchemes default (a genuine,
 * documented issue), "Failed to authenticate with proxy" persisted
 * unchanged. This points to a SECOND, structural issue: OkHttp (which
 * KiteConnect uses internally, confirmed from its jar) does not
 * automatically consult java.net.Authenticator for PROXY
 * authentication unless explicitly wired via
 * OkHttpClient.Builder.proxyAuthenticator(...) - and KiteConnect's
 * public constructor only accepts a raw java.net.Proxy, with no way
 * to inject a custom OkHttp authenticator.
 *
 * This class sidesteps that uncertainty entirely: it runs a tiny,
 * fully self-contained local TCP relay that KiteConnect connects to
 * as an UNAUTHENTICATED proxy (so no framework-specific auth
 * negotiation is needed on that hop at all), while THIS class handles
 * the real, authenticated CONNECT tunnel to staticip.in itself, with
 * the Basic-auth header written explicitly and directly - guaranteed
 * correct because it's plain, low-level socket code with no
 * intermediate library making its own decisions about when/how to
 * send credentials.
 *
 * When kite.proxy.enabled=false, this component does nothing at all -
 * zero behavior change for local development or any setup that
 * doesn't need a static-IP proxy.
 */
@Component
@Slf4j
public class LocalProxyRelay {

    @Value("${kite.proxy.enabled:false}")   private boolean proxyEnabled;
    @Value("${kite.proxy.host:}")           private String upstreamHost;
    @Value("${kite.proxy.port:0}")          private int upstreamPort;
    @Value("${kite.proxy.username:}")       private String proxyUsername;
    @Value("${kite.proxy.password:}")       private String proxyPassword;

    private static final int LOCAL_PORT = 18899;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile boolean running = false;

    /** The port KiteConnect should connect to - always localhost, always
     *  unauthenticated from KiteConnect's point of view. */
    public static int getLocalPort() {
        return LOCAL_PORT;
    }

    @PostConstruct
    public void start() {
        if (!proxyEnabled || upstreamHost == null || upstreamHost.isBlank() || upstreamPort <= 0) {
            log.debug("[LOCAL-PROXY-RELAY] Disabled or not configured - not starting");
            return;
        }

        Thread listenerThread = new Thread(this::runListener, "local-proxy-relay-listener");
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    private void runListener() {
        try (ServerSocket server = new ServerSocket(LOCAL_PORT, 50, InetAddress.getLoopbackAddress())) {
            running = true;
            log.info("[LOCAL-PROXY-RELAY] Listening on 127.0.0.1:{} - forwarding to authenticated " +
                            "upstream proxy {}:{}. KiteConnect should be configured to use " +
                            "127.0.0.1:{} as its proxy (unauthenticated from its own point of view).",
                    LOCAL_PORT, upstreamHost, upstreamPort, LOCAL_PORT);
            while (running) {
                Socket client = server.accept();
                pool.submit(() -> handleClient(client));
            }
        } catch (IOException e) {
            log.error("[LOCAL-PROXY-RELAY] Listener failed - order placement will likely fail " +
                    "with the same proxy-auth error as before this fix: {}", e.getMessage());
        }
    }

    /**
     * Reads a single CRLF-terminated line from a raw InputStream, one
     * byte at a time - deliberately NOT using BufferedReader.
     *
     * FIX (found during production cross-check, a real and serious bug):
     * BufferedReader internally reads in large chunks (default 8KB)
     * looking for line breaks. If the TLS ClientHello bytes that follow
     * the CONNECT headers arrive close together with those headers (very
     * plausible under real network timing), BufferedReader can read PAST
     * the blank line and silently buffer the first chunk of the actual
     * TLS handshake internally. Switching to a raw client.getInputStream()
     * read afterward (for the binary relay phase) would then MISS those
     * already-consumed bytes entirely - corrupting the TLS stream in a
     * way that fails intermittently depending on exact packet timing,
     * not deterministically. Reading one byte at a time here guarantees
     * the underlying stream position is exactly where we expect it to
     * be when we hand off to raw relaying - zero ambiguity, zero risk of
     * silently losing part of the TLS handshake.
     */
    private String readLineRaw(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int prev = -1, curr;
        while ((curr = in.read()) != -1) {
            if (prev == '\r' && curr == '\n') {
                return sb.substring(0, sb.length() - 1); // drop the trailing \r we already appended
            }
            sb.append((char) curr);
            prev = curr;
        }
        return sb.length() > 0 ? sb.toString() : null; // stream ended
    }

    private void handleClient(Socket client) {
        try (Socket upstream = new Socket()) {
            upstream.connect(new InetSocketAddress(upstreamHost, upstreamPort), 10_000);
            client.setTcpNoDelay(true);
            upstream.setTcpNoDelay(true);

            InputStream clientIn = client.getInputStream();
            OutputStream upstreamOut = upstream.getOutputStream();
            InputStream upstreamIn = upstream.getInputStream();
            OutputStream clientOut = client.getOutputStream();

            // Read the CONNECT request line KiteConnect/OkHttp sent us
            // (e.g. "CONNECT api.kite.trade:443 HTTP/1.1") - byte-safe,
            // see readLineRaw() docstring for why this can't be BufferedReader.
            String requestLine = readLineRaw(clientIn);
            if (requestLine == null || !requestLine.startsWith("CONNECT")) {
                log.warn("[LOCAL-PROXY-RELAY] Unexpected non-CONNECT request - closing: {}",
                        requestLine);
                client.close();
                return;
            }
            // Drain remaining headers from the client's CONNECT request
            String line;
            while ((line = readLineRaw(clientIn)) != null && !line.isEmpty()) { /* discard */ }

            // Build and send our OWN CONNECT request to the real upstream
            // proxy, with the Basic-auth header written explicitly and
            // directly here - guaranteed correct, no framework guessing.
            String credentials = Base64.getEncoder().encodeToString(
                    (proxyUsername + ":" + proxyPassword).getBytes(StandardCharsets.UTF_8));
            String target = requestLine.substring("CONNECT ".length(), requestLine.lastIndexOf(' '));
            String upstreamRequest = "CONNECT " + target + " HTTP/1.1\r\n" +
                    "Host: " + target + "\r\n" +
                    "Proxy-Authorization: Basic " + credentials + "\r\n" +
                    "Proxy-Connection: Keep-Alive\r\n\r\n";
            upstreamOut.write(upstreamRequest.getBytes(StandardCharsets.ISO_8859_1));
            upstreamOut.flush();

            // Read the upstream's response headers - same byte-safe
            // reading, same reason: must not lose any bytes of the real
            // TLS response that could follow immediately after.
            String statusLine = readLineRaw(upstreamIn);
            StringBuilder responseHeaders = new StringBuilder(
                    statusLine != null ? statusLine : "").append("\r\n");
            String hLine;
            while ((hLine = readLineRaw(upstreamIn)) != null && !hLine.isEmpty()) {
                responseHeaders.append(hLine).append("\r\n");
            }
            responseHeaders.append("\r\n");
            clientOut.write(responseHeaders.toString().getBytes(StandardCharsets.ISO_8859_1));
            clientOut.flush();

            if (statusLine == null || !statusLine.contains("200")) {
                log.warn("[LOCAL-PROXY-RELAY] Upstream proxy rejected the tunnel: {}", statusLine);
                client.close();
                return;
            }

            // Tunnel established - now blindly relay raw bytes both ways
            // (this is the actual TLS-encrypted Kite traffic; we never
            // decrypt or inspect it, purely a byte-for-byte relay). Using
            // clientIn/upstreamIn directly - the SAME stream objects used
            // above for header reading, never re-fetched via a fresh
            // getInputStream() call, so no byte can possibly be lost in
            // the handoff between header-parsing and raw relay phases.
            Thread t1 = new Thread(() -> relay(upstreamIn, clientOut));
            Thread t2 = new Thread(() -> relay(clientIn, upstreamOut));
            t1.start();
            t2.start();
            t1.join();
            t2.join();

        } catch (Exception e) {
            log.warn("[LOCAL-PROXY-RELAY] Connection handling failed - Kite API call through " +
                    "this tunnel will fail: {}", e.getMessage());
        } finally {
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    private void relay(InputStream in, OutputStream out) {
        byte[] buffer = new byte[8192];
        try {
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
            // Normal on connection close from either side
        }
    }
}