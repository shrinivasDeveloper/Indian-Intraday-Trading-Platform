package com.trading.swing.auto.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * OfficialSectorMappingParser - parses NSE Indices Ltd.'s real
 * ind_niftytotalmarket_list.csv. Confirmed column structure (Company
 * Name, Industry, Symbol, Series, ISIN Code) via multiple independent
 * sources describing this exact, well-known file format.
 *
 * "Industry" here is the file's actual column name - it corresponds to
 * the "Sector" tier in NSE's 4-tier taxonomy (Macro-Economic Sector >
 * Sector > Industry > Basic Industry) for this particular broad-index
 * constituent file; the file does not expose the full 4-tier breakdown,
 * only this one classification column per stock.
 */
@Component
@Slf4j
public class OfficialSectorMappingParser {

    /** symbol -> sector name, from the real file. */
    public Map<String, String> parse(byte[] csvBytes) {
        Map<String, String> result = new HashMap<>();
        if (csvBytes == null) return result;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(csvBytes), StandardCharsets.UTF_8))) {

            String headerLine = reader.readLine();
            if (headerLine == null) return result;
            String[] headers = splitCsvLine(headerLine);

            int industryCol = -1, symbolCol = -1;
            for (int i = 0; i < headers.length; i++) {
                String h = headers[i].trim().toLowerCase();
                if (h.contains("industry")) industryCol = i;
                if (h.equals("symbol")) symbolCol = i;
            }

            if (industryCol == -1 || symbolCol == -1) {
                log.error("[OFFICIAL-SECTOR-PARSER] Expected columns not found - header was: {}. " +
                                "NSE may have changed the file format; this parser needs updating.",
                        headerLine);
                return result;
            }

            String line;
            int parsed = 0, skipped = 0;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] cols = splitCsvLine(line);
                if (cols.length <= Math.max(industryCol, symbolCol)) { skipped++; continue; }

                String symbol = cols[symbolCol].trim().toUpperCase();
                String industry = cols[industryCol].trim();
                if (symbol.isEmpty() || industry.isEmpty()) { skipped++; continue; }

                result.put(symbol, industry);
                parsed++;
            }
            log.info("[OFFICIAL-SECTOR-PARSER] Parsed {} stocks with real, official sector " +
                    "classification ({} rows skipped)", parsed, skipped);
        } catch (Exception e) {
            log.error("[OFFICIAL-SECTOR-PARSER] Failed to parse official sector mapping: {}",
                    e.getMessage());
        }
        return result;
    }

    private String[] splitCsvLine(String line) {
        return line.split(",", -1);
    }
}