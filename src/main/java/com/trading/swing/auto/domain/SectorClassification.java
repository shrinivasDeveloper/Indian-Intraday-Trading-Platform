package com.trading.swing.auto.domain;

/**
 * One stock's sector classification, with its SOURCE explicitly tracked
 * — never silently blending real, official data with a keyword guess.
 */
public record SectorClassification(
        String symbol,
        String companyName,
        String sector,        // the "Sector" tier (22-sector NSE taxonomy, when OFFICIAL)
        String industry,      // present only when source == OFFICIAL
        Source source
) {
    public enum Source {
        /** From NSE Indices Ltd.'s own published Nifty Total Market
         *  constituent file — real, revenue-segment-based classification. */
        OFFICIAL,
        /** Stock falls outside the 750-stock official coverage — keyword-
         *  matched against company name as a lower-confidence fallback. */
        KEYWORD_FALLBACK
    }
}