package com.trading.shared.risk;

import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Margin;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * AccountMarginGuard - FIX for a confirmed real, platform-wide gap
 * found during a full production-readiness review: AI, News, Swing,
 * and Hero-or-Zero each independently assume their own capital is
 * available, with ZERO awareness of what the OTHER strategies have
 * already committed the SAME real Zerodha account's margin to that
 * day. Each strategy could only discover "insufficient margin"
 * reactively, at the point of order rejection.
 *
 * This is a genuinely SHARED, cross-strategy safeguard - not a
 * violation of Hero-or-Zero's "no shared business logic" requirement
 * from earlier, since capital/margin is a real, singular, shared
 * resource across the WHOLE Zerodha account by definition (unlike
 * sector classification or momentum logic, which are strategy-
 * specific business decisions). Every strategy calls this SAME
 * service before placing a real order - not to change WHICH stock/
 * option each strategy decides to trade (zero impact on any
 * strategy's own signal/selection logic), only to check, right
 * before execution, whether the broker account can actually support
 * one more order.
 *
 * Verified against the real Kite Connect SDK's actual Margin model
 * (Margin.Available.liveBalance - confirmed via direct bytecode
 * inspection, not assumed) - this is real, live account data, not a
 * strategy-side estimate.
 */
@Service
@Slf4j
public class AccountMarginGuard {

    private final KiteConnect kiteConnect;

    // Cached for a few seconds - avoids hammering Kite's margins API
    // when multiple strategies check in quick succession (e.g. AI
    // evaluating 5 candidates in the same scan cycle).
    private volatile BigDecimal cachedAvailable = null;
    private volatile Instant cachedAt = Instant.EPOCH;
    private static final long CACHE_TTL_SECONDS = 5;

    public AccountMarginGuard(KiteConnect kiteConnect) {
        this.kiteConnect = kiteConnect;
    }

    public record MarginCheckResult(boolean sufficient, BigDecimal availableMargin,
                                    BigDecimal requiredAmount, String reason) {}

    /**
     * Checks if the account has enough real, available margin for a
     * new order of the given amount, BEFORE that order is placed.
     * Fails OPEN (returns sufficient=true) if the margin check itself
     * fails for any reason (API error, auth issue, etc.) - a margin-
     * check failure should never be the reason a strategy silently
     * stops trading; the broker's own order-rejection is still the
     * final, authoritative safety net either way.
     */
    public MarginCheckResult checkSufficientMargin(BigDecimal requiredAmount, String strategyName) {
        try {
            BigDecimal available = getAvailableMargin();
            boolean sufficient = available.compareTo(requiredAmount) >= 0;
            if (!sufficient) {
                log.warn("[MARGIN-GUARD] {} - INSUFFICIENT margin: need Rs.{}, only Rs.{} " +
                                "available (checked across the whole account, not just this strategy) - " +
                                "skipping order attempt rather than letting it fail at the broker",
                        strategyName, requiredAmount, available);
            } else {
                log.debug("[MARGIN-GUARD] {} - margin OK: need Rs.{}, Rs.{} available",
                        strategyName, requiredAmount, available);
            }
            return new MarginCheckResult(sufficient, available, requiredAmount,
                    sufficient ? null : "Insufficient account margin");
        } catch (KiteException | Exception e) {
            log.warn("[MARGIN-GUARD] Margin check failed for {} (failing OPEN - broker's own " +
                    "order rejection remains the final safety net): {}", strategyName, e.getMessage());
            return new MarginCheckResult(true, null, requiredAmount,
                    "Margin check unavailable - proceeding, broker will reject if genuinely insufficient");
        }
    }

    private BigDecimal getAvailableMargin() throws KiteException, IOException, org.json.JSONException {
        Instant now = Instant.now();
        if (cachedAvailable != null && now.isBefore(cachedAt.plusSeconds(CACHE_TTL_SECONDS))) {
            return cachedAvailable;
        }
        Margin margin = kiteConnect.getMargins("equity");
        // FIX: liveBalance is actually a String field (confirmed via
        // direct bytecode inspection, not assumed) - same pattern as
        // Instrument.getStrike() and Order.averagePrice found earlier
        // this session. BigDecimal.valueOf() only accepts double/long;
        // must use the String constructor instead.
        BigDecimal available = new BigDecimal(margin.available.liveBalance);
        cachedAvailable = available;
        cachedAt = now;
        return available;
    }
}