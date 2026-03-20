package com.trading.marketdata.service;

import com.trading.domain.Candle;
import com.trading.events.CandleCompleteEvent;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Observes and stores opening gap data during 9:15-9:40 observation period.
 * Used by Gate 3 (compression) for gap-and-go / gap-filled classification.
 */
@Service
@Slf4j
public class GapDataService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public enum GapType {
        GAP_AND_GO,   // Gapped up/down and holding
        GAP_FILLED,   // Gapped but filled back to prev close
        NO_GAP        // No significant gap
    }

    public record GapData(
            String     symbol,
            BigDecimal prevClose,
            BigDecimal openPrice,
            BigDecimal gapPct,       // positive = gap up, negative = gap down
            GapType    type,
            boolean    gapUp,
            boolean    gapDown
    ) {}

    // symbol → gap data
    private final Map<String, GapData>    gapDataMap   = new ConcurrentHashMap<>();
    // symbol → prev day close (updated at EOD)
    private final Map<String, BigDecimal> prevCloseMap = new ConcurrentHashMap<>();

    @EventListener
    @Async("tradingExecutor")
    public void onCandle(CandleCompleteEvent event) {
        Candle c = event.getCandle();

        // Only process 5-minute candles
        if (!"5minute".equals(c.getTimeframe())) return;

        ZonedDateTime candleTime = c.getCandleTime().atZone(IST);
        LocalTime     lt         = candleTime.toLocalTime();

        // Store first candle of day for gap calculation (9:15 candle)
        if (lt.equals(LocalTime.of(9, 15))) {
            String     sym       = c.getTradingSymbol();
            BigDecimal prevClose = prevCloseMap.get(sym);
            if (prevClose == null || prevClose.compareTo(BigDecimal.ZERO) == 0) {
                prevClose = c.getOpen(); // fallback
            }
            calculateGap(sym, prevClose, c.getOpen(), c.getClose());
        }

        // Update gap type throughout the day
        // If price comes back to prevClose, gap is "filled"
        GapData existing = gapDataMap.get(c.getTradingSymbol());
        if (existing != null && existing.type() != GapType.NO_GAP) {
            updateGapStatus(c);
        }

        // Store previous close from last candle of day (14:55 candle)
        if (lt.isAfter(LocalTime.of(15, 20))) {
            prevCloseMap.put(c.getTradingSymbol(), c.getClose());
        }
    }

    public void setPrevClose(String symbol, BigDecimal prevClose) {
        prevCloseMap.put(symbol.toUpperCase(), prevClose);
    }

    public GapData getGapData(String symbol) {
        return gapDataMap.get(symbol.toUpperCase());
    }

    public GapType getGapType(String symbol) {
        GapData data = gapDataMap.get(symbol.toUpperCase());
        return data != null ? data.type() : GapType.NO_GAP;
    }

    private void calculateGap(String sym, BigDecimal prevClose, BigDecimal open, BigDecimal close) {
        if (prevClose.compareTo(BigDecimal.ZERO) == 0) return;

        BigDecimal gapPct = open.subtract(prevClose)
                .divide(prevClose, MathContext.DECIMAL32)
                .multiply(BigDecimal.valueOf(100));

        double gap = gapPct.doubleValue();
        boolean gapUp   = gap > 0.5;
        boolean gapDown = gap < -0.5;

        GapType type = GapType.NO_GAP;
        if (gapUp || gapDown) {
            // Check if gap is holding
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

        // Check if gap has been filled
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