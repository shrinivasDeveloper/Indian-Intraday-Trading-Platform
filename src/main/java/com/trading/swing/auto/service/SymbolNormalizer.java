package com.trading.swing.auto.service;

/**
 * SymbolNormalizer - shared, single source of truth for stripping
 * Kite/exchange trading-symbol suffixes that don't appear in NSE's
 * bhavcopy plain-symbol format.
 *
 * FIX (found per explicit request: "Photon, Parameshwari Silk etc are
 * missing" from the swing strategy). Confirmed real, not speculative,
 * from Zerodha's own Kite Connect developer forum, where multiple
 * developers directly reported this exact mismatch (e.g. Kite's
 * "NSE:NDTV-BE" vs bhavcopy's plain "NDTV"; BSE corporate-action
 * stocks can carry a trailing "*"). Without normalizing consistently
 * EVERYWHERE a symbol is used as a lookup key, a stock could be
 * correctly classified into a sector in one class, yet silently fail
 * to match its own price history in another - exactly the invisible
 * failure mode this fixes.
 *
 * Extracted as a shared static utility (rather than duplicating the
 * same logic in both SectorPerformanceService and
 * AutoStockSelectionEngine) specifically so the two can never drift
 * out of sync with each other again.
 */
public final class SymbolNormalizer {

    private SymbolNormalizer() {}

    public static String normalize(String rawSymbol) {
        if (rawSymbol == null) return null;
        String s = rawSymbol.toUpperCase().trim();
        for (String suffix : new String[]{"-BE", "-SM", "-ST", "-BZ"}) {
            if (s.endsWith(suffix)) {
                s = s.substring(0, s.length() - suffix.length());
                break; // a symbol carries at most one series suffix
            }
        }
        if (s.endsWith("*")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}