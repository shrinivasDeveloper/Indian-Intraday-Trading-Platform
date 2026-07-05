package com.trading.herozero.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * HeroZeroSchedulerConfig - dedicated scheduler thread, completely
 * separate from AI/News/Swing's own scheduler threads (each of which
 * already has its own dedicated pool in this codebase, following the
 * same isolation principle established earlier this session).
 */
@Configuration
public class HeroZeroSchedulerConfig {

    @Bean(name = "heroZeroTaskScheduler")
    public ThreadPoolTaskScheduler heroZeroTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("hero-zero-scheduler-");
        scheduler.initialize();
        return scheduler;
    }
}