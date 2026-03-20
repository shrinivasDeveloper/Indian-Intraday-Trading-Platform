package com.trading.notification.service;

import com.trading.events.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Service
@Slf4j
public class NotificationService {

    @Value("${notifications.telegram.enabled:false}") private boolean telegramEnabled;
    @Value("${notifications.telegram.bot-token:}")    private String  botToken;
    @Value("${notifications.telegram.chat-id:}")      private String  chatId;

    private final RestTemplate restTemplate = new RestTemplate();

    @EventListener
    @Async("tradingExecutor")
    public void onTradeApproved(TradeApprovedEvent event) {
        String msg = String.format(
            "✅ TRADE APPROVED: %s %s qty=%d entry=%.2f sl=%.2f target=%.2f score=%.1f",
            event.getDirection(), event.getTradingSymbol(),
            event.getQuantity(),
            event.getEntryPrice().doubleValue(),
            event.getStopLoss().doubleValue(),
            event.getTarget().doubleValue(),
            event.getProbabilityScore().doubleValue());
        sendTelegram(msg);
        log.info(msg);
    }

    @EventListener
    @Async("tradingExecutor")
    public void onTradeResult(TradeExecutionResultEvent event) {
        String msg = String.format("📊 TRADE %s: %s pnl=%.2f reason=%s",
            event.getStatus(), event.getTradingSymbol(),
            event.getNetPnl() != null ? event.getNetPnl().doubleValue() : 0,
            event.getExitReason() != null ? event.getExitReason() : "");
        sendTelegram(msg);
        log.info(msg);
    }

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
