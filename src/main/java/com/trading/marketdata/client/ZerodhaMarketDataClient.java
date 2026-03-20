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

@Component
@Slf4j
@RequiredArgsConstructor
public class ZerodhaMarketDataClient {

    private final KiteConnect kiteConnect;

    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 2000))
    public List<Instrument> getInstruments(String exchange) {
        try {
            List<Instrument> list = kiteConnect.getInstruments(exchange);
            log.info("Fetched {} instruments from {}", list.size(), exchange);
            return list;
        } catch (KiteException | IOException e) {
            throw new RuntimeException("Instruments[" + exchange + "]: " + e.getMessage(), e);
        }
    }

    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public HistoricalData getHistoricalData(long token, String interval,
                                            Date from, Date to, boolean continuous) {
        try {
            return kiteConnect.getHistoricalData(
                    from, to, String.valueOf(token), interval, continuous, false);
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
        } catch (KiteException | IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 500))
    public Map<String, OHLCQuote> getOHLC(String[] instruments) {
        try {
            return kiteConnect.getOHLC(instruments);
        } catch (KiteException | IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 500))
    public Map<String, LTPQuote> getLTP(String[] instruments) {
        try {
            return kiteConnect.getLTP(instruments);
        } catch (KiteException | IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /**
     * Resolve only equity tokens for Nifty500 dynamically.
     * Returns map of symbol -> token for subscription.
     */
    public Map<String, Long> resolveNifty500Tokens(List<String> niftySymbols, List<Instrument> instrumentList) {
        Map<String, Long> resolved = new HashMap<>();
        Set<String> instrumentSymbols = instrumentList.stream()
                .filter(i -> "EQ".equalsIgnoreCase(i.getInstrument_type()))
                .map(Instrument::getTradingsymbol)
                .collect(Collectors.toSet());

        for (String symbol : niftySymbols) {
            if (instrumentSymbols.contains(symbol)) {
                Instrument inst = instrumentList.stream()
                        .filter(i -> symbol.equals(i.getTradingsymbol()))
                        .findFirst().orElse(null);
                if (inst != null) {
                    resolved.put(symbol, inst.getInstrument_token());
                }
            } else {
                log.warn("Nifty500 symbol missing in instrument list: {}", symbol);
            }
        }

        log.info("Nifty500 resolved tokens: {} / {} total", resolved.size(), niftySymbols.size());
        return resolved;
    }
}