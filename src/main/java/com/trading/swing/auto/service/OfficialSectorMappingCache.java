package com.trading.swing.auto.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OfficialSectorMappingCache - holds the real, NSE-Indices-sourced
 * symbol->sector mapping in memory, refreshed once a day (this data
 * changes annually per NSE's own review cycle, confirmed in their
 * methodology doc - daily refresh would just hit their server for
 * identical data).
 */
@Service
@Slf4j
public class OfficialSectorMappingCache {

    private final NseDataClient nseClient;
    private final OfficialSectorMappingParser parser;
    private final AtomicReference<Map<String, String>> cache =
            new AtomicReference<>(Collections.emptyMap());

    public OfficialSectorMappingCache(NseDataClient nseClient, OfficialSectorMappingParser parser) {
        this.nseClient = nseClient;
        this.parser = parser;
    }

    @PostConstruct
    public void init() {
        refresh();
    }

    @Scheduled(cron = "0 0 6 * * *", zone = "Asia/Kolkata") // once daily, well before market open
    public void refresh() {
        byte[] csv = nseClient.downloadOfficialSectorMapping();
        Map<String, String> mapping = parser.parse(csv);
        if (!mapping.isEmpty()) {
            cache.set(mapping);
            log.info("[OFFICIAL-SECTOR-CACHE] Refreshed - {} stocks with real, official " +
                    "sector classification now cached", mapping.size());
        } else {
            log.warn("[OFFICIAL-SECTOR-CACHE] Refresh returned empty - keeping previous cache " +
                    "({} stocks) rather than wiping it on a transient failure", cache.get().size());
        }
    }

    /** Returns the official sector for a symbol, or null if it's outside
     *  the 750-stock coverage (caller must fall back to keyword matching,
     *  clearly distinguished - never silently treated as equally confident). */
    public String getOfficialSector(String symbol) {
        return cache.get().get(symbol.toUpperCase());
    }

    public int size() {
        return cache.get().size();
    }
}