package com.trading.notification.service;

import com.trading.events.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * NotificationService — Telegram alerts.
 *
 * CLEANUP: removed onTradeApproved(TradeApprovedEvent) and
 * onTradeResult(TradeExecutionResultEvent) — both events were published
 * exclusively by TradeExecutionService, which served the other strategies
 * (SCPS/ORB/HighRR/SMC) and has been deleted along with them. AI and News
 * have their own independent execution paths (AiLiveOrderExecutionService)
 * with their own logging — they never published these events to begin with.
 * onCircuitBreaker is untouched — circuit breaker alerts remain relevant
 * regardless of which strategies are active.
 */
@Service
@Slf4j
public class NotificationService {

    @Value("${notifications.telegram.enabled:false}") private boolean telegramEnabled;
    @Value("${notifications.telegram.bot-token:}")    private String  botToken;
    @Value("${notifications.telegram.chat-id:}")      private String  chatId;

    private final RestTemplate restTemplate = new RestTemplate();

    @EventListener
    @Async("tradingExecutor")
    public void onCircuitBreaker(CircuitBreakerEvent event) {
        String msg = String.format("🚨 CIRCUIT BREAKER [%s]: %s",
                event.getEventType(), event.getReason());
        sendTelegram(msg);
        log.warn(msg);
    }

    private void sendTelegram(String text) {
        if (!telegramEnabled || botToken.isBlank() || chatId.isBlank()) return;
        try {
            String url = String.format(
                    "https://api.telegram.org/bot%s/sendMessage", botToken);
            restTemplate.postForObject(url,
                    Map.of("chat_id", chatId, "text", text), String.class);
        } catch (Exception e) {
            log.warn("Telegram notification failed: {}", e.getMessage());
        }
    }
}