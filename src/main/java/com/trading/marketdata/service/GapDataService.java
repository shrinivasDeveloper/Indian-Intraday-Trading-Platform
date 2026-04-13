package com.trading.marketdata.service;

import com.trading.domain.Candle;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GapDataService — tracks opening gap data for each symbol.
 *
 * STATUS: Dormant utility component (@Component, not @Service).
 *   All consumers of gap data (ORBStrategy, SevenGateScannerService etc)
 *   have been deleted. This class no longer listens to candle events.
 *
 *   Changed from @Service to @Component for two reasons:
 *     1. The @EventListener onCandle() was firing on EVERY 5-minute candle
 *        for ALL 400+ symbols and accumulating data that nobody ever reads.
 *        This was wasting CPU and memory on every candle event.
 *     2. @Component still allows explicit injection if a future strategy
 *        needs gap data — call setPrevClose() and getGapData() directly.
 *
 *   TO RE-ACTIVATE: Change @Component back to @Service and add
 *   @EventListener + @Async back to onCandle().
 *
 * Gap types:
 *   GAP_AND_GO  — gapped up/down and holding above/below prev close
 *   GAP_FILLED  — gapped but price returned to prev close level
 *   NO_GAP      — no significant gap (< 0.5%)
 */
@Component
@Slf4j
public class GapDataService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public enum GapType {
        GAP_AND_GO,
        GAP_FILLED,
        NO_GAP
    }

    public record GapData(
            String     symbol,
            BigDecimal prevClose,
            BigDecimal openPrice,
            BigDecimal gapPct,
            GapType    type,
            boolean    gapUp,
            boolean    gapDown
    ) {}

    private final Map<String, GapData>    gapDataMap   = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> prevCloseMap = new ConcurrentHashMap<>();

    /**
     * Manually set the previous close for a symbol.
     * Call this from a strategy or scanner before market open if gap data is needed.
     */
    public void setPrevClose(String symbol, BigDecimal prevClose) {
        prevCloseMap.put(symbol.toUpperCase(), prevClose);
    }

    /**
     * Manually register a candle for gap calculation.
     * Call this from a strategy that needs gap data.
     */
    public void processCandle(Candle c) {
        if (!"5minute".equals(c.getTimeframe())) return;

        ZonedDateTime candleTime = c.getCandleTime().atZone(IST);
        LocalTime     lt         = candleTime.toLocalTime();

        if (lt.equals(LocalTime.of(9, 15))) {
            String     sym       = c.getTradingSymbol();
            BigDecimal prevClose = prevCloseMap.get(sym);
            if (prevClose == null || prevClose.compareTo(BigDecimal.ZERO) == 0) {
                prevClose = c.getOpen();
            }
            calculateGap(sym, prevClose, c.getOpen(), c.getClose());
        }

        GapData existing = gapDataMap.get(c.getTradingSymbol());
        if (existing != null && existing.type() != GapType.NO_GAP) {
            updateGapStatus(c);
        }

        if (lt.isAfter(LocalTime.of(15, 20))) {
            prevCloseMap.put(c.getTradingSymbol(), c.getClose());
        }
    }

    public GapData getGapData(String symbol) {
        return gapDataMap.get(symbol.toUpperCase());
    }

    public GapType getGapType(String symbol) {
        GapData data = gapDataMap.get(symbol.toUpperCase());
        return data != null ? data.type() : GapType.NO_GAP;
    }

    private void calculateGap(String sym, BigDecimal prevClose,
                              BigDecimal open, BigDecimal close) {
        if (prevClose.compareTo(BigDecimal.ZERO) == 0) return;

        BigDecimal gapPct = open.subtract(prevClose)
                .divide(prevClose, MathContext.DECIMAL32)
                .multiply(BigDecimal.valueOf(100));

        double  gap     = gapPct.doubleValue();
        boolean gapUp   = gap > 0.5;
        boolean gapDown = gap < -0.5;

        GapType type = GapType.NO_GAP;
        if (gapUp || gapDown) {
            boolean holding = gapUp
                    ? close.compareTo(prevClose) > 0
                    : close.compareTo(prevClose) < 0;
            type = holding ? GapType.GAP_AND_GO : GapType.GAP_FILLED;
        }

        gapDataMap.put(sym.toUpperCase(), new GapData(
                sym, prevClose, open, gapPct, type, gapUp, gapDown));
    }

    private void updateGapStatus(Candle c) {
        GapData data = gapDataMap.get(c.getTradingSymbol().toUpperCase());
        if (data == null) return;

        if (data.gapUp() && c.getLow().compareTo(data.prevClose()) <= 0) {
            gapDataMap.put(data.symbol().toUpperCase(), new GapData(
                    data.symbol(), data.prevClose(), data.openPrice(),
                    data.gapPct(), GapType.GAP_FILLED, data.gapUp(), data.gapDown()));
        } else if (data.gapDown() && c.getHigh().compareTo(data.prevClose()) >= 0) {
            gapDataMap.put(data.symbol().toUpperCase(), new GapData(
                    data.symbol(), data.prevClose(), data.openPrice(),
                    data.gapPct(), GapType.GAP_FILLED, data.gapUp(), data.gapDown()));
        }
    }
}