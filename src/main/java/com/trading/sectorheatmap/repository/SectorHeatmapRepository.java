package com.trading.sectorheatmap.repository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SectorHeatmapRepository - INDEPENDENT persistence for the sector
 * heatmap module. Own dedicated table (sector_heatmap_stocks), self-
 * healing schema (same proven pattern already used throughout this
 * codebase) - zero shared tables with any existing strategy.
 *
 * FIX for "should be fixed and consistent across every application
 * restart": stock-to-sector assignments are persisted here on first
 * successful fetch, then loaded FROM THIS TABLE on every subsequent
 * startup - never re-fetched live on every restart. A background
 * refresh (see SectorHeatmapDataService) only updates this table on a
 * slow, deliberate schedule (weekly - sector classification genuinely
 * doesn't change day to day), guaranteeing the heatmap shows the exact
 * same stock-to-sector mapping across restarts, exactly as required.
 */
@Repository
@Slf4j
public class SectorHeatmapRepository {

    private final JdbcTemplate jdbc;

    public SectorHeatmapRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void ensureTableExists() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS sector_heatmap_stocks (
                symbol          VARCHAR(30)  NOT NULL PRIMARY KEY,
                company_name    VARCHAR(200),
                sector          VARCHAR(60)  NOT NULL,
                exchange        VARCHAR(10)  NOT NULL DEFAULT 'NSE',
                last_updated_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
                                             ON UPDATE CURRENT_TIMESTAMP,
                INDEX idx_sector (sector)
            )
            """);
    }

    /** Loaded once on startup, and refreshed only by the periodic
     *  background job - never rebuilt from scratch on every restart. */
    public Map<String, String> loadSymbolToSector() {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT symbol, sector FROM sector_heatmap_stocks");
            for (var row : rows) {
                result.put((String) row.get("symbol"), (String) row.get("sector"));
            }
        } catch (Exception e) {
            log.warn("[SECTOR-HEATMAP] Failed to load persisted mapping (OK on first run, " +
                    "will populate after first successful fetch): {}", e.getMessage());
        }
        return result;
    }

    public Map<String, String> loadSymbolToCompanyName() {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT symbol, company_name FROM sector_heatmap_stocks");
            for (var row : rows) {
                result.put((String) row.get("symbol"), (String) row.get("company_name"));
            }
        } catch (Exception e) {
            log.debug("[SECTOR-HEATMAP] loadSymbolToCompanyName failed (non-fatal): {}",
                    e.getMessage());
        }
        return result;
    }

    /** Upserts every stock's mapping in one batch - called only by the
     *  periodic background refresh, never on every startup. */
    public void saveAll(Map<String, String[]> symbolToNameAndSector, String exchange) {
        try {
            List<Object[]> batchArgs = symbolToNameAndSector.entrySet().stream()
                    .map(e -> new Object[]{e.getKey(), e.getValue()[0], e.getValue()[1], exchange})
                    .toList();
            jdbc.batchUpdate("""
                INSERT INTO sector_heatmap_stocks (symbol, company_name, sector, exchange)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE company_name = VALUES(company_name),
                                        sector = VALUES(sector),
                                        exchange = VALUES(exchange)
                """, batchArgs);
            log.info("[SECTOR-HEATMAP] Persisted {} stock-to-sector mappings", batchArgs.size());
        } catch (Exception e) {
            log.error("[SECTOR-HEATMAP] Failed to persist mapping - heatmap will continue " +
                    "using whatever was already loaded in memory: {}", e.getMessage());
        }
    }

    public int countPersisted() {
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sector_heatmap_stocks", Integer.class);
            return count != null ? count : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}