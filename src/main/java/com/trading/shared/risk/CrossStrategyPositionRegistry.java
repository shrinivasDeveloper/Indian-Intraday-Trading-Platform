package com.trading.shared.risk;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CrossStrategyPositionRegistry - FIX for a confirmed real gap found
 * during a full production-readiness review: AI has zero awareness of
 * Swing's open positions, Swing has zero awareness of AI's, and so on.
 * Each strategy's own internal duplicate-order protection is solid
 * (verified extensively this session) - but nothing previously
 * prevented, say, AI and Swing's auto-selection from BOTH deciding to
 * buy the same stock the same day, completely independently, each
 * unaware the other was doing it too.
 *
 * DELIBERATELY WARNING-ONLY, NOT BLOCKING: this registry does not
 * prevent any strategy from trading a symbol another strategy already
 * holds - doing so would mean changing each strategy's own core
 * selection/entry decision logic, which was explicitly not wanted.
 * Instead, it gives every strategy VISIBILITY into concentrated
 * exposure via a clear log line, so you're aware of it, without
 * altering any strategy's trading behavior at all.
 *
 * Genuinely shared, in-memory only (not persisted) - a real position
 * that survives a restart is already tracked in each strategy's own
 * database table; this registry only needs to reflect the CURRENT
 * process's live view, rebuilt naturally as each strategy's own
 * restart-recovery logic re-registers its positions on startup.
 */
@Service
@Slf4j
public class CrossStrategyPositionRegistry {

    // symbol (normalized, uppercase) -> strategy name currently holding it
    private final Map<String, String> heldBy = new ConcurrentHashMap<>();

    /**
     * Call this right after a buy order is confirmed filled. Does NOT
     * block or reject anything - if another strategy already holds
     * this symbol, both registrations coexist (last-write-wins in the
     * map for display purposes), but the WARNING below gives you
     * visibility either way.
     */
    public void registerPosition(String symbol, String strategyName) {
        String normalized = symbol.toUpperCase();
        String existingHolder = heldBy.get(normalized);
        if (existingHolder != null && !existingHolder.equals(strategyName)) {
            log.warn("[CROSS-STRATEGY] CONCENTRATED EXPOSURE: {} is being bought by {} while " +
                            "{} already holds a position in it. Both trades are proceeding as normal " +
                            "(this is advisory only, not a block) - you now have exposure to {} from " +
                            "TWO independent strategies simultaneously.",
                    normalized, strategyName, existingHolder, normalized);
        }
        heldBy.put(normalized, strategyName);
    }

    /** Call this when a position is exited/closed. */
    public void releasePosition(String symbol, String strategyName) {
        heldBy.remove(symbol.toUpperCase(), strategyName);
    }

    /**
     * Call this BEFORE placing a new entry, purely for visibility -
     * logs a warning if another strategy already holds this symbol,
     * but always returns normally either way (never throws, never
     * blocks the caller's own decision to proceed).
     */
    public void checkAndWarnIfHeldElsewhere(String symbol, String myStrategyName) {
        String existingHolder = heldBy.get(symbol.toUpperCase());
        if (existingHolder != null && !existingHolder.equals(myStrategyName)) {
            log.warn("[CROSS-STRATEGY] {} is about to enter {}, which {} already holds - " +
                    "proceeding (advisory only), but you will have exposure to this symbol " +
                    "from multiple strategies at once.", myStrategyName, symbol, existingHolder);
        }
    }

    /** Read-only visibility for a dashboard, if ever wanted. */
    public Map<String, String> getCurrentHoldings() {
        return Map.copyOf(heldBy);
    }
}