package path.to._40c.nqCore.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * One-time startup migration for the one-position-per-signal restructure: every leg gains
 * a BOOK column, backfilled from the leg's parent row's legacy POSITION.BOOK (the entity
 * no longer maps that column, so this runs as plain SQL via JdbcTemplate — @Transactional
 * would be ignored inside @PostConstruct). Rows older than the two-book split have a null
 * legacy book and are weekly synthetic trades — COALESCE stamps them SYNTH_WEEKLY. A fresh
 * database has no legacy POSITION.BOOK column at all; the fallback stamps SYNTH_WEEKLY
 * directly (and is a no-op there anyway — a fresh DB has no legs). Idempotent: only null
 * leg books are touched, so restarts are no-ops. Without this, leg-scoped queries would
 * never see a legacy LIVE leg and the close path would strand it at the broker.
 */
@Component
@Slf4j
public class LegBookBackfill {

    private final JdbcTemplate jdbcTemplate;

    public LegBookBackfill(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void backfillNullLegBooks() {
        int updated;
        try {
            updated = jdbcTemplate.update(
                    "UPDATE WEEKLY_LEG SET BOOK = COALESCE("
                    + "(SELECT p.BOOK FROM POSITION p WHERE p.ID = WEEKLY_LEG.POSITION_ID), 'SYNTH_WEEKLY') "
                    + "WHERE BOOK IS NULL");
        } catch (Exception e) {
            log.info("Legacy POSITION.BOOK column absent (fresh database) — stamping null leg books SYNTH_WEEKLY directly");
            updated = jdbcTemplate.update("UPDATE WEEKLY_LEG SET BOOK = 'SYNTH_WEEKLY' WHERE BOOK IS NULL");
        }
        if (updated > 0) {
            log.info("Backfilled BOOK on {} pre-existing leg rows from their position's legacy book", updated);
        }
    }
}
