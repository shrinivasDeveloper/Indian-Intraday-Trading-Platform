package com.trading.swing.auto.service;

import com.trading.swing.auto.domain.DailyBar;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * BhavcopyParser — parses NSE's daily UDiFF bhavcopy ZIP/CSV.
 *
 * Column headers verified against NSE's UDiFF standard documentation
 * and cross-confirmed by an independent source describing the exact
 * same header row: TradDt, BizDt, Sgmt, Src, FinInstrmTp, FinInstrmId,
 * ISIN, TckrSymb, SctySrs, XpryDt, ..., OpnPric, HghPric, LwPric,
 * ClsPric, LastPric, PrvsClsgPric, ..., TtlTradgVol, ...
 *
 * Only FinInstrmTp = "STK" rows are genuine cash-market equity bars —
 * the same file also contains other instrument types mixed in (the
 * "Unified" part of UDiFF). SctySrs is further filtered to EQ/BE, the
 * only two series this module's instrument search actually trades.
 */
@Component
@Slf4j
public class BhavcopyParser {

    public List<DailyBar> parse(byte[] zipBytes, LocalDate tradeDate) {
        List<DailyBar> bars = new ArrayList<>();
        if (zipBytes == null) return bars;

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry = zis.getNextEntry();
            if (entry == null) {
                log.warn("[BHAVCOPY-PARSER] ZIP for {} had no entries", tradeDate);
                return bars;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(zis, StandardCharsets.UTF_8));
            String headerLine = reader.readLine();
            if (headerLine == null) return bars;
            Map<String, Integer> colIndex = buildColumnIndex(headerLine);

            int symCol   = colIndex.get("TckrSymb");
            int srsCol   = colIndex.get("SctySrs");
            int typeCol  = colIndex.get("FinInstrmTp");
            int openCol  = colIndex.get("OpnPric");
            int highCol  = colIndex.get("HghPric");
            int lowCol   = colIndex.get("LwPric");
            int closeCol = colIndex.get("ClsPric");
            int volCol   = colIndex.get("TtlTradgVol");

            String line;
            int skipped = 0;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] cols = splitCsvLine(line);
                try {
                    if (cols.length <= Math.max(symCol, Math.max(srsCol, volCol))) { skipped++; continue; }
                    String instrType = cols[typeCol].trim();
                    if (!"STK".equals(instrType)) continue; // skip F&O/index rows in the same file

                    String series = cols[srsCol].trim();
                    if (!"EQ".equals(series) && !"BE".equals(series)) continue; // only tradable equity series

                    String symbol = cols[symCol].trim();
                    BigDecimal open  = parseDecimal(cols[openCol]);
                    BigDecimal high  = parseDecimal(cols[highCol]);
                    BigDecimal low   = parseDecimal(cols[lowCol]);
                    BigDecimal close = parseDecimal(cols[closeCol]);
                    long volume = parseLong(cols[volCol]);

                    if (symbol.isBlank() || open == null || close == null) { skipped++; continue; }

                    bars.add(new DailyBar(symbol, tradeDate, open, high, low, close, volume, series));
                } catch (Exception e) {
                    skipped++; // one bad row must never abort the whole day's parse
                }
            }
            log.info("[BHAVCOPY-PARSER] Parsed {} equity bars for {} ({} rows skipped)",
                    bars.size(), tradeDate, skipped);
        } catch (Exception e) {
            log.error("[BHAVCOPY-PARSER] Failed to parse bhavcopy for {}: {}", tradeDate, e.getMessage());
        }
        return bars;
    }

    private Map<String, Integer> buildColumnIndex(String headerLine) {
        String[] headers = splitCsvLine(headerLine);
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < headers.length; i++) {
            index.put(headers[i].trim(), i);
        }
        return index;
    }

    private String[] splitCsvLine(String line) {
        return line.split(",", -1);
    }

    private BigDecimal parseDecimal(String s) {
        if (s == null || s.isBlank()) return null;
        try { return new BigDecimal(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    private long parseLong(String s) {
        if (s == null || s.isBlank()) return 0L;
        try { return (long) Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return 0L; }
    }
}