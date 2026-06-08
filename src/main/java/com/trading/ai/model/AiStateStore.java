package com.trading.ai.model;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;

/**
 * AiStateStore — in-memory store of recent AI trade decisions.
 * Used by DashboardController to display AI activity in /scanner.html.
 * Holds last 50 decisions (today's session only).
 */
@Component
@ConditionalOnProperty(name = "ai.trading.enabled", havingValue = "true")
public class AiStateStore {

    private static final int MAX_DECISIONS = 50;
    private final Deque<AiTradeDecision> recentDecisions = new LinkedList<>();

    public synchronized void recordDecision(AiTradeDecision decision) {
        recentDecisions.addFirst(decision);
        if (recentDecisions.size() > MAX_DECISIONS) {
            recentDecisions.removeLast();
        }
    }

    public synchronized List<AiTradeDecision> getRecentDecisions() {
        return Collections.unmodifiableList(new ArrayList<>(recentDecisions));
    }

    public synchronized void clearForNewDay() {
        recentDecisions.clear();
    }
}