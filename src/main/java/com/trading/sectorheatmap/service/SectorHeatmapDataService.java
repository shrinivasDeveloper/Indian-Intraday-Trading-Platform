package com.trading.sectorheatmap.service;

import com.trading.sectorheatmap.domain.SectorTaxonomy;
import com.trading.sectorheatmap.repository.SectorHeatmapRepository;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Quote;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SectorHeatmapDataService - the core engine for a completely
 * independent sector heatmap module.
 *
 * INDEPENDENCE (per explicit user requirement): zero imports from any
 * existing strategy (AI, News, Swing, Hero-or-Zero). Uses only:
 *   - KiteConnect (the same shared bean every strategy uses for
 *     genuinely neutral broker connectivity - not strategy logic)
 *   - Its own fresh, independent fetch of NSE's real constituent data
 *     (same proven URL/CSV pattern already working elsewhere in this
 *     codebase, but implemented fresh here, not calling into any
 *     existing strategy's parser class)
 *   - SectorTaxonomy (this module's own official NSE Industry->Sector
 *     reference data)
 *
 * FIX for "should map all 500+ stocks... fixed and consistent across
 * every restart": stock-to-sector mapping is fetched ONCE, persisted,
 * then loaded from the database on every subsequent startup - never
 * rebuilt from a live fetch on every restart (see refreshMapping()'s
 * weekly schedule below).
 *
 * Live price/% change, by contrast, genuinely SHOULD update
 * continuously during market hours - that's the whole point of a
 * heatmap. This uses KiteConnect.getQuote() directly, which returns
 * both live LTP and the day's real OHLC (open) in a single call -
 * confirmed via bytecode inspection of the actual SDK - meaning this
 * module needs zero WebSocket/tick-tracking infrastructure of its own.
 */
@Service
@Slf4j
public class SectorHeatmapDataService {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private final KiteConnect kiteConnect;
    private final SectorHeatmapRepository repository;
    private final RestTemplate restTemplate = new RestTemplate();

    // In-memory, loaded from DB at startup - the STABLE, restart-
    // consistent mapping. Never mutated by a live price update, only
    // by the deliberate weekly refresh below.
    private volatile Map<String, String> symbolToSector = new LinkedHashMap<>();
    private volatile Map<String, String> symbolToCompanyName = new LinkedHashMap<>();

    // Live price snapshot - genuinely refreshes every cycle, separate
    // from the stable sector mapping above.
    private final Map<String, StockSnapshot> livePrices = new ConcurrentHashMap<>();

    public record StockSnapshot(
            String symbol, String companyName, String sector,
            double ltp, double dayOpen, double changePct
    ) {}

    public SectorHeatmapDataService(KiteConnect kiteConnect, SectorHeatmapRepository repository) {
        this.kiteConnect = kiteConnect;
        this.repository = repository;
    }

    @PostConstruct
    public void loadPersistedMappingOnStartup() {
        symbolToSector = repository.loadSymbolToSector();
        symbolToCompanyName = repository.loadSymbolToCompanyName();
        if (symbolToSector.isEmpty()) {
            log.info("[SECTOR-HEATMAP] No persisted mapping found - fetching for the first " +
                    "time now (this is the ONLY startup-time fetch; all future startups will " +
                    "load from the database instead)");
            refreshMapping();
        } else {
            log.info("[SECTOR-HEATMAP] Loaded {} persisted stock-to-sector mappings from " +
                            "database - consistent with every prior restart, per requirement",
                    symbolToSector.size());
        }
    }

    /**
     * Refreshes the STABLE stock-to-sector mapping. Runs weekly (Sunday
     * 6 AM IST - well outside market hours) - deliberately infrequent,
     * since real sector classification doesn't change day to day, and
     * frequent re-fetching would risk the mapping silently shifting
     * between restarts, violating the "fixed and consistent" requirement.
     */
    @Scheduled(cron = "0 0 6 * * SUN", zone = "Asia/Kolkata")
    public void refreshMapping() {
        try {
            Map<String, String[]> fetched = fetchRealStockIndustryData();
            if (fetched.isEmpty()) {
                log.warn("[SECTOR-HEATMAP] Fetch returned 0 stocks - keeping existing mapping " +
                                "({} stocks) rather than wiping it on a transient failure",
                        symbolToSector.size());
                return;
            }

            Map<String, String> newSymbolToSector = new LinkedHashMap<>();
            Map<String, String> newSymbolToName = new LinkedHashMap<>();
            for (var entry : fetched.entrySet()) {
                newSymbolToName.put(entry.getKey(), entry.getValue()[0]);
                newSymbolToSector.put(entry.getKey(), entry.getValue()[1]);
            }

            repository.saveAll(fetched, "NSE");
            symbolToSector = newSymbolToSector;
            symbolToCompanyName = newSymbolToName;

            log.info("[SECTOR-HEATMAP] Refreshed - {} stocks mapped across {} sectors",
                    newSymbolToSector.size(), new HashSet<>(newSymbolToSector.values()).size());
        } catch (Exception e) {
            log.error("[SECTOR-HEATMAP] Mapping refresh failed - keeping existing mapping " +
                    "({} stocks): {}", symbolToSector.size(), e.getMessage());
        }
    }

    /**
     * Fetches real, current stock->Industry data directly from NSE
     * (niftyindices.com's own Nifty Total Market constituent list - the
     * broadest official list, genuinely covering 500+ stocks), then maps
     * each Industry to its official parent Sector via SectorTaxonomy.
     * Deliberately independent - does not call into any existing
     * strategy's parser class, even though the URL pattern is the same
     * proven one already working elsewhere in this codebase.
     */
    private Map<String, String[]> fetchRealStockIndustryData() {
        Map<String, String[]> result = new LinkedHashMap<>();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", USER_AGENT);
            ResponseEntity<byte[]> resp = restTemplate.exchange(
                    "https://www.niftyindices.com/IndexConstituent/ind_niftytotalmarket_list.csv",
                    HttpMethod.GET, new HttpEntity<>(headers), byte[].class);

            byte[] body = resp.getBody();
            if (body == null || body.length == 0) return result;

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new ByteArrayInputStream(body), StandardCharsets.UTF_8))) {

                String headerLine = reader.readLine();
                if (headerLine == null) return result;
                String[] headers2 = headerLine.split(",", -1);
                int nameCol = -1, industryCol = -1, symbolCol = -1;
                for (int i = 0; i < headers2.length; i++) {
                    String h = headers2[i].trim().toLowerCase();
                    if (h.equals("company name")) nameCol = i;
                    else if (h.equals("industry")) industryCol = i;
                    else if (h.equals("symbol")) symbolCol = i;
                }
                if (symbolCol == -1 || industryCol == -1) {
                    log.warn("[SECTOR-HEATMAP] Expected columns not found in CSV header - " +
                            "niftyindices.com may have changed their format");
                    return result;
                }

                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    String[] cols = line.split(",", -1);
                    if (cols.length <= Math.max(symbolCol, industryCol)) continue;
                    String symbol = cols[symbolCol].trim().toUpperCase();
                    String industry = cols[industryCol].trim();
                    String name = nameCol >= 0 && cols.length > nameCol
                            ? cols[nameCol].trim() : symbol;
                    if (symbol.isEmpty()) continue;
                    String sector = SectorTaxonomy.sectorFor(industry);
                    result.put(symbol, new String[]{name, sector});
                }
            }
        } catch (Exception e) {
            log.error("[SECTOR-HEATMAP] Failed to fetch from niftyindices.com: {}", e.getMessage());
        }
        return result;
    }

    /**
     * Refreshes LIVE prices for every mapped stock, in batches of 200
     * (Kite's practical quote-request batch size, matching the same
     * batching already used elsewhere in this codebase for WebSocket
     * subscriptions). Runs every minute during market hours - genuinely
     * independent polling, not reusing any strategy's WebSocket tick
     * stream.
     */
    @Scheduled(fixedRate = 60000)
    public void refreshLivePrices() {
        if (symbolToSector.isEmpty()) return;

        List<String> symbols = new ArrayList<>(symbolToSector.keySet());
        int batchSize = 200;
        for (int i = 0; i < symbols.size(); i += batchSize) {
            List<String> batch = symbols.subList(i, Math.min(i + batchSize, symbols.size()));
            String[] keys = batch.stream().map(s -> "NSE:" + s).toArray(String[]::new);
            try {
                Map<String, Quote> quotes = kiteConnect.getQuote(keys);
                for (String symbol : batch) {
                    Quote q = quotes.get("NSE:" + symbol);
                    if (q == null || q.ohlc == null || q.ohlc.open <= 0) continue;
                    double changePct = (q.lastPrice - q.ohlc.open) / q.ohlc.open * 100.0;
                    livePrices.put(symbol, new StockSnapshot(
                            symbol, symbolToCompanyName.getOrDefault(symbol, symbol),
                            symbolToSector.get(symbol), q.lastPrice, q.ohlc.open, changePct));
                }
            } catch (KiteException | Exception e) {
                log.debug("[SECTOR-HEATMAP] Live price batch {}-{} failed (non-fatal, will " +
                        "retry next cycle): {}", i, i + batch.size(), e.getMessage());
            }
        }
    }

    // -- Query methods for the controller ----------------------------------

    public Set<String> getAllSectorNames() {
        return SectorTaxonomy.ALL_22_SECTORS;
    }

    public List<StockSnapshot> getStocksInSector(String sector, boolean ascending) {
        Comparator<StockSnapshot> cmp = Comparator.comparingDouble(StockSnapshot::changePct);
        if (!ascending) cmp = cmp.reversed();
        return livePrices.values().stream()
                .filter(s -> s.sector().equalsIgnoreCase(sector))
                .sorted(cmp)
                .toList();
    }

    /** FIX (found via direct user testing: clicking a sector showing
     *  "+0.00%" revealed "No live data yet for this sector" - meaning
     *  ZERO stocks, not a genuinely flat sector. The old double-only
     *  return made these indistinguishable. Now includes stockCount so
     *  the controller/dashboard can tell "no data" apart from "flat." */
    public record SectorChange(double changePct, int stockCount) {}

    public Map<String, SectorChange> getSectorAverageChange() {
        Map<String, List<Double>> bySector = new LinkedHashMap<>();
        for (StockSnapshot s : livePrices.values()) {
            bySector.computeIfAbsent(s.sector(), k -> new ArrayList<>()).add(s.changePct());
        }
        Map<String, SectorChange> result = new LinkedHashMap<>();
        for (String sector : SectorTaxonomy.ALL_22_SECTORS) {
            List<Double> changes = bySector.getOrDefault(sector, List.of());
            double avg = changes.isEmpty() ? 0.0
                    : changes.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double rounded = BigDecimal.valueOf(avg).setScale(2, RoundingMode.HALF_UP).doubleValue();
            result.put(sector, new SectorChange(rounded, changes.size()));
        }
        return result;
    }

    public int getMappedStockCount() {
        return symbolToSector.size();
    }

    public int getLivePriceCount() {
        return livePrices.size();
    }
}