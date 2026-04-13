package com.trading.marketdata.client;

import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ZerodhaMarketDataClient — JAR-verified market data retrieval.
 *
 * JAR-VERIFIED METHOD SIGNATURES:
 *
 *   getHistoricalData(Date, Date, String, String, boolean, boolean) → HistoricalData
 *     params: fromDate, toDate, instrumentToken(String), interval, continuous, oi
 *     NOTE: instrumentToken must be String.valueOf(long)
 *
 *   getInstruments(String) → List<Instrument>
 *   getQuote(String[])     → Map<String, Quote>
 *   getOHLC(String[])      → Map<String, OHLCQuote>
 *   getLTP(String[])       → Map<String, LTPQuote>
 *
 * JAR-VERIFIED INSTRUMENT FIELDS:
 *   instrument_token → long  (getter: getInstrument_token())
 *   tradingsymbol    → String (getter: getTradingsymbol())
 *   name             → String (getter: getName())
 *   instrument_type  → String (getter: getInstrument_type())
 *   exchange         → String (getter: getExchange())
 *   expiry           → Date   (getter: getExpiry())
 *   lot_size         → int    (getter: getLot_size())
 *   last_price       → double (getter: getLast_price())
 *   tick_size        → double (getter: getTick_size())
 *
 * JAR-VERIFIED HISTORICALDATA FIELDS:
 *   timeStamp        → String
 *   open             → double
 *   high             → double
 *   low              → double
 *   close            → double
 *   volume           → long
 *   oi               → long
 *   dataArrayList    → List<HistoricalData>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ZerodhaMarketDataClient {

    private final KiteConnect kiteConnect;

    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 2000))
    public List<Instrument> getInstruments(String exchange) {
        try {
            List<Instrument> list = kiteConnect.getInstruments(exchange);
            log.info("[MDC] Fetched {} instruments from {}", list.size(), exchange);
            return list;
        } catch (KiteException e) {
            throw new RuntimeException("Instruments[" + exchange + "] [" + e.code + "]: " + e.message, e);
        } catch (IOException e) {
            throw new RuntimeException("Instruments[" + exchange + "] network: " + e.getMessage(), e);
        }
    }

    /**
     * JAR-VERIFIED signature:
     *   getHistoricalData(Date from, Date to, String instrumentToken,
     *                     String interval, boolean continuous, boolean oi)
     *
     * NOTE: instrumentToken is String (not long) — must use String.valueOf(token).
     * NOTE: oi=false for equity instruments.
     */
    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public HistoricalData getHistoricalData(long token, String interval,
                                            Date from, Date to, boolean continuous) {
        try {
            // JAR-VERIFIED: token param is String in getHistoricalData
            return kiteConnect.getHistoricalData(
                    from, to,
                    String.valueOf(token),  // long → String ✓
                    interval,
                    continuous,
                    false);                 // oi = false for equity
        } catch (KiteException e) {
            throw new RuntimeException("Historical [" + e.code + "]: " + e.message, e);
        } catch (Exception e) {
            throw new RuntimeException("Historical error: " + e.getMessage(), e);
        }
    }

    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 500))
    public Map<String, Quote> getQuotes(String[] instruments) {
        try {
            return kiteConnect.getQuote(instruments);
        } catch (KiteException e) {
            throw new RuntimeException("[" + e.code + "]: " + e.message, e);
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 500))
    public Map<String, OHLCQuote> getOHLC(String[] instruments) {
        try {
            return kiteConnect.getOHLC(instruments);
        } catch (KiteException e) {
            throw new RuntimeException("[" + e.code + "]: " + e.message, e);
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 500))
    public Map<String, LTPQuote> getLTP(String[] instruments) {
        try {
            return kiteConnect.getLTP(instruments);
        } catch (KiteException e) {
            throw new RuntimeException("[" + e.code + "]: " + e.message, e);
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /**
     * Resolve equity tokens for Nifty500.
     *
     * JAR-VERIFIED Instrument getters:
     *   getInstrument_type()  → String  ("EQ", "BE", etc.)
     *   getTradingsymbol()    → String
     *   getInstrument_token() → long
     *   getName()             → String
     */
    public Map<String, Long> resolveNifty500Tokens(List<String> niftySymbols,
                                                   List<Instrument> instrumentList) {
        Map<String, Long> resolved = new HashMap<>();

        // JAR-VERIFIED: getInstrument_type() getter name
        Set<String> instrumentSymbols = instrumentList.stream()
                .filter(i -> "EQ".equalsIgnoreCase(i.getInstrument_type()))
                .map(Instrument::getTradingsymbol)
                .collect(Collectors.toSet());

        for (String symbol : niftySymbols) {
            if (instrumentSymbols.contains(symbol)) {
                // JAR-VERIFIED: getTradingsymbol() and getInstrument_token() getters
                instrumentList.stream()
                        .filter(i -> symbol.equals(i.getTradingsymbol()))
                        .findFirst()
                        .ifPresent(inst -> resolved.put(symbol, inst.getInstrument_token()));
            } else {
                log.debug("[MDC] Nifty500 symbol missing in instrument list: {}", symbol);
            }
        }

        log.info("[MDC] Nifty500 resolved tokens: {} / {} total",
                resolved.size(), niftySymbols.size());
        return resolved;
    }
}