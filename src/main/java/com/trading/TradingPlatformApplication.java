package com.trading;

import com.trading.config.StrategyConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableRetry
@EnableScheduling
//@EnableConfigurationProperties(StrategyConfig.class) // ✅ ADDED THIS LINE
public class TradingPlatformApplication {
    public static void main(String[] args) {
        SpringApplication.run(TradingPlatformApplication.class, args);
    }
}
