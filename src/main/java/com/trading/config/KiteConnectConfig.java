package com.trading.config;

import com.zerodhatech.kiteconnect.KiteConnect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KiteConnectConfig {

    @Value("${zerodha.api-key}")  private String apiKey;
    @Value("${zerodha.user-id}")  private String userId;

    @Bean
    public KiteConnect kiteConnect() {
        // VERIFIED: KiteConnect(apiKey) constructor + setUserId()
        KiteConnect kite = new KiteConnect(apiKey);
        kite.setUserId(userId);
        // Note: setEnableLogging() does NOT exist in this SDK version
        return kite;
    }
}
