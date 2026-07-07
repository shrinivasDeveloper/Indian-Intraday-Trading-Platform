package com.trading.swing.service;

import com.trading.swing.repository.ManualSwingTradeRepository;
import com.trading.swing.domain.ManualSwingTrade;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Holding;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * HoldingsReconciliationService - FIX for a confirmed real gap, found
 * from direct user report: a position closed OUTSIDE this app (e.g.
 * manually via Zerodha's own app/web interface directly) was showing
 * as ACTIVE forever, with zero automatic correction - the app only
 * ever reconciled trades stuck in ORDER_PLACED (an order IT initiated),
 * never checked whether an already-ACTIVE position genuinely still
 * exists at the broker.
 *
 * Runs periodically, comparing every trade this app believes is ACTIVE
 * against Zerodha's REAL holdings (kiteConnect.getHoldings()) - if a
 * symbol the app thinks is ACTIVE no longer appears there at all (or
 * appears with zero quantity), it's corrected to CLOSED with a clear,
 * distinct exit reason ("CLOSED_EXTERNALLY_RECONCILED") so it's never
 * confused with a normal, price-tracked exit.
 *
 * Deliberately conservative: only ever CLOSES a stale record, never
 * OPENS one - if the broker shows a holding this app has no record of
 * at all, that's a different scenario (a position bought entirely
 * outside this app) and is intentionally left alone here, only logged
 * for visibility.
 */
@Service
@Slf4j
public class HoldingsReconciliationService {

    private final KiteConnect kiteConnect;
    private final ManualSwingTradeRepository repo;

    public HoldingsReconciliationService(KiteConnect kiteConnect, ManualSwingTradeRepository repo) {
        this.kiteConnect = kiteConnect;
        this.repo = repo;
    }

    /**
     * Runs every 15 minutes during market hours - frequent enough to
     * catch a manually-closed position within a reasonable window,
     * infrequent enough to stay well clear of Kite's rate limits for
     * an API this cheap to call (getHoldings is a single, lightweight
     * request regardless of position count).
     */
    @Scheduled(cron = "0 */15 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void reconcile() {
        List<ManualSwingTrade> activeInApp = repo.findActive();
        if (activeInApp.isEmpty()) return; // nothing to check - common case, cheap exit

        try {
            List<Holding> realHoldings = kiteConnect.getHoldings();
            Set<String> realSymbolsWithQuantity = realHoldings.stream()
                    .filter(h -> h.quantity > 0)
                    .map(h -> h.tradingSymbol.toUpperCase())
                    .collect(Collectors.toSet());

            for (ManualSwingTrade trade : activeInApp) {
                String symbol = trade.getSymbol().toUpperCase();
                if (!realSymbolsWithQuantity.contains(symbol)) {
                    log.warn("[HOLDINGS-RECONCILE] {} (tradeId={}) is marked ACTIVE in our " +
                                    "database, but the broker no longer shows this holding at all - " +
                                    "this position was almost certainly closed OUTSIDE this app " +
                                    "(e.g. manually via Zerodha directly). Correcting our record to " +
                                    "CLOSED now, since continuing to treat this as an open position " +
                                    "we're monitoring would be tracking something that no longer exists.",
                            symbol, trade.getTradeId());
                    repo.markClosedExternally(trade.getTradeId());
                }
            }
        } catch (KiteException | Exception e) {
            log.debug("[HOLDINGS-RECONCILE] Reconciliation check failed (non-fatal, will retry " +
                    "on next scheduled run): {}", e.getMessage());
        }
    }
}