package com.trading;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableRetry
@EnableScheduling
public class TradingPlatformApplication {
    public static void main(String[] args) {
        // FIX (found while debugging: "Failed to authenticate with proxy" -
        // curl succeeded with the EXACT same credentials/host/port that
        // Java's own networking stack rejected). Confirmed root cause,
        // not a guess: since Java 8u111, the JVM disables Basic
        // authentication for HTTPS CONNECT tunneling through a proxy BY
        // DEFAULT - a documented Oracle security hardening decision
        // (CVE-2016-5597) that predates this app entirely and has
        // nothing to do with the proxy/credentials themselves being
        // wrong. This affects BOTH KiteConnect's internal OkHttp client
        // AND java.net.http.HttpClient (used elsewhere in this app) for
        // any HTTPS-through-proxy connection using Basic auth.
        //
        // MUST be set here, as the very first line of main() - before
        // SpringApplication.run() builds any bean, including
        // KiteConnectConfig. Confirmed from real-world bug reports
        // (Maven/Jenkins/JBoss users hitting this exact same issue) that
        // setting this property too late in the JVM lifecycle does NOT
        // work reliably - some JVM networking classes cache this value
        // on first use.
        //
        // staticip.in's proxy specifically requires Basic auth (confirmed
        // via curl -x with Basic credentials succeeding), so this must be
        // explicitly re-enabled for this app to authenticate with it at all.
        System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
        System.setProperty("jdk.http.auth.proxying.disabledSchemes", "");

        SpringApplication.run(TradingPlatformApplication.class, args);
    }
}