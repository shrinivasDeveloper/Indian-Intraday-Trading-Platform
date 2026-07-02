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
 * BhavcopyParser - parses NSE's daily UDiFF bhavcopy ZIP/CSV.
 *
 * Column headers verified against NSE's UDiFF standard documentation
 * and cross-confirmed by an independent source describing the exact
 * same header row: TradDt, BizDt, Sgmt, Src, FinInstrmTp, FinInstrmId,
 * ISIN, TckrSymb, SctySrs, XpryDt, ..., OpnPric, HghPric, LwPric,
 * ClsPric, LastPric, PrvsClsgPric, ..., TtlTradgVol, ...
 *
 * Only FinInstrmTp = "STK" rows are genuine cash-market equity bars -
 * the same file also contains other instrument types mixed in (the
 * "Unified" part of UDiFF).
 *
 * FIX (found per explicit request: "we don't have all the stocks -
 * Photon, Parameshwari Silk etc are missing"). SctySrs was previously
 * filtered to EQ/BE only, which silently excluded every NSE SME/Emerge
 * -listed stock - confirmed directly from Zerodha's own developer
 * forum: SME stocks trade under series SM (and ST for compulsory-
 * delivery SME stocks), not EQ/BE. This is genuinely different from
 * Kite Connect's OWN instrument dump (where Zerodha collapses
 * everything to instrument_type="EQ" regardless of real series) - this
 * bhavcopy file is NSE's own official daily record and DOES carry the
 * real, accurate series code, so this filter was a real, fixable gap.
 *
 * Expanded to accept every genuinely tradable EQUITY series while
 * still explicitly excluding non-equity instruments that would corrupt
 * sector/momentum math if included (debt, government securities,
 * mutual fund units, rights-issue partly-paid shares):
 *   EQ - normal equity, most stocks
 *   BE - trade-to-trade / surveillance, delivery-only
 *   BZ - stricter surveillance (LODR non-compliance), delivery-only
 *   SM - NSE SME / Emerge main board
 *   ST - NSE SME with compulsory delivery
 * Deliberately still excludes: GC/GS (govt securities), MF (mutual
 * funds/ETFs), N*//* (debt instruments), E1 (partly-paid rights
        * shares), IL (institutional-only SLB) - none of these are genuine
 * tradable common-equity stocks this module should ever select.
 */
         @Component
         @Slf4j
         public class BhavcopyParser {

         private static final java.util.Set<String> TRADABLE_EQUITY_SERIES =
         java.util.Set.of("EQ", "BE", "BZ", "SM", "ST");

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
         if (!TRADABLE_EQUITY_SERIES.contains(series)) continue; // only genuine tradable equity series

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