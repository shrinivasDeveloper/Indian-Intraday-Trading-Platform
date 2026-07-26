package com.trading.herozero.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * HeroZeroExpiryOverrideService - manual expiry-date selection for the
 * Hero-Zero strategy (per explicit user request).
 *
 * COMPLETELY SELF-CONTAINED: own table (auto-created on first use),
 * zero imports from and zero changes to any existing Hero-Zero class.
 * The scheduler's automatic expiry computation remains byte-untouched;
 * this service simply stores what the user picked on the dashboard,
 * persisted in MySQL so a restart or crash NEVER loses the selection.
 * The value remains until manually changed or cleared.
 *
 * INTEGRATION (one line, when desired): wherever the scheduler computes
 * the expiry date for an index, consult
 *     expiryOverrideService.getOverride(indexName)
 * and use the returned date if present, else fall back to the existing
 * automatic computation. Until that line is added, this feature stores
 * and displays the selection without altering trading behavior.
 */
@Service
@Slf4j
public class HeroZeroExpiryOverrideService {

    private final JdbcTemplate jdbc;

    public HeroZeroExpiryOverrideService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        ensureTableExists();
    }

    private void ensureTableExists() {
        try {
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS hero_zero_expiry_overrides (
                    index_name   VARCHAR(20) NOT NULL PRIMARY KEY,
                    expiry_date  DATE        NOT NULL,
                    updated_at   TIMESTAMP   NOT NULL
                )
                """);
        } catch (Exception e) {
            log.error("[HERO-ZERO-EXPIRY] Could not create override table - manual expiry " +
                    "selection will not persist: {}", e.getMessage());
        }
    }

    /** The single integration point for the strategy: the manually-set
     *  expiry for this index, if one exists. */
    public Optional<LocalDate> getOverride(String indexName) {
        try {
            LocalDate d = jdbc.queryForObject(
                    "SELECT expiry_date FROM hero_zero_expiry_overrides WHERE index_name = ?",
                    LocalDate.class, indexName);
            return Optional.ofNullable(d);
        } catch (Exception e) {
            return Optional.empty(); // no row = no override, normal case
        }
    }

    /** Dashboard-facing: set/replace the manual expiry for an index.
     *  Rejects past dates - a past expiry can never be tradeable. */
    public synchronized boolean setOverride(String indexName, LocalDate expiryDate) {
        try {
            if (expiryDate.isBefore(LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")))) {
                log.warn("[HERO-ZERO-EXPIRY] Rejected past expiry date {} for {}", expiryDate, indexName);
                return false;
            }
            int updated = jdbc.update("""
                INSERT INTO hero_zero_expiry_overrides (index_name, expiry_date, updated_at)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE expiry_date = VALUES(expiry_date),
                                        updated_at = VALUES(updated_at)
                """, indexName, expiryDate, java.sql.Timestamp.from(Instant.now()));
            log.info("[HERO-ZERO-EXPIRY] Manual expiry set via dashboard: {} -> {}", indexName, expiryDate);
            return updated > 0;
        } catch (Exception e) {
            log.error("[HERO-ZERO-EXPIRY] setOverride failed for {}: {}", indexName, e.getMessage());
            return false;
        }
    }

    /** Dashboard-facing: remove the manual override (fall back to the
     *  strategy's automatic computation). */
    public synchronized boolean clearOverride(String indexName) {
        try {
            jdbc.update("DELETE FROM hero_zero_expiry_overrides WHERE index_name = ?", indexName);
            log.info("[HERO-ZERO-EXPIRY] Manual expiry cleared for {}", indexName);
            return true;
        } catch (Exception e) {
            log.error("[HERO-ZERO-EXPIRY] clearOverride failed for {}: {}", indexName, e.getMessage());
            return false;
        }
    }

    /** Dashboard-facing: everything currently set, for display. */
    public List<Map<String, Object>> getAllOverrides() {
        try {
            return jdbc.queryForList(
                    "SELECT index_name, expiry_date, updated_at FROM hero_zero_expiry_overrides " +
                            "ORDER BY index_name");
        } catch (Exception e) {
            return List.of();
        }
    }
}