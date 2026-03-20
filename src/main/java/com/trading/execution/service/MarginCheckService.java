package com.trading.execution.service;

import com.trading.execution.client.ZerodhaOrderClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;

@Service
@Slf4j
@RequiredArgsConstructor
public class MarginCheckService {

    private final ZerodhaOrderClient orderClient;

    public record MarginResult(boolean sufficient, int recommendedQty,
                                double required, double available) {}

    public MarginResult check(String symbol, String direction, int qty,
                               BigDecimal entryPrice) {
        try {
            ZerodhaOrderClient.MarginResult m =
                orderClient.calculateMargin(symbol, qty, entryPrice.doubleValue());

            if (m.sufficient()) {
                return new MarginResult(true, qty, m.required(), m.available());
            }

            if (m.required() > 0) {
                double perUnit   = m.required() / qty;
                int affordable   = (int) Math.floor(m.available() / perUnit);
                log.warn("Margin short for {}: reducing qty {} → {}", symbol, qty, affordable);
                return new MarginResult(affordable > 0, Math.max(0, affordable),
                    m.required(), m.available());
            }

            return new MarginResult(false, 0, m.required(), m.available());

        } catch (Exception e) {
            log.warn("Margin check failed for {} — allowing with formula qty: {}", symbol, e.getMessage());
            return new MarginResult(true, qty, 0, 0);
        }
    }
}
