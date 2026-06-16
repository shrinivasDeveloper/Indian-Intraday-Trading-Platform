package com.trading.ai.btst;

import java.math.BigDecimal;

/**
 * BtstPosition — overnight position persisted to Redis.
 *
 * Serialized as pipe-delimited string:
 *   symbol|direction|entry|sl|t1|t2|qty|score|sector|pattern|date
 *
 * Survives daily restart. Loaded by BtstAiStrategy.onStartup().
 */
public record BtstPosition(
        String     symbol,
        String     direction,
        BigDecimal entryPrice,
        BigDecimal sl,
        BigDecimal t1,
        BigDecimal t2,
        int        qty,
        int        confidenceScore,
        String     sector,
        String     pattern,
        String     date        // entry date yyyy-MM-dd
) {
    private static final String SEP = "|";

    /** Serialize to Redis string */
    public String toRedis() {
        return String.join(SEP,
                symbol,
                direction,
                entryPrice.toPlainString(),
                sl.toPlainString(),
                t1.toPlainString(),
                t2.toPlainString(),
                String.valueOf(qty),
                String.valueOf(confidenceScore),
                sector  != null ? sector  : "Other",
                pattern != null ? pattern : "—",
                date    != null ? date    : ""
        );
    }

    /** Deserialize from Redis string */
    public static BtstPosition fromRedis(String raw) {
        String[] p = raw.split("\\|", -1);
        if (p.length < 11) throw new IllegalArgumentException("Invalid BTST Redis format: " + raw);
        return new BtstPosition(
                p[0],                           // symbol
                p[1],                           // direction
                new BigDecimal(p[2]),            // entryPrice
                new BigDecimal(p[3]),            // sl
                new BigDecimal(p[4]),            // t1
                new BigDecimal(p[5]),            // t2
                Integer.parseInt(p[6]),          // qty
                Integer.parseInt(p[7]),          // confidenceScore
                p[8],                            // sector
                p[9],                            // pattern
                p[10]                            // date
        );
    }
}