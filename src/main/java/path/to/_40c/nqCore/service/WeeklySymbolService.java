package path.to._40c.nqCore.service;

import java.time.LocalDate;
import java.time.ZoneId;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static path.to._40c.nqCore.util.Constants.WEEKLY;
import static path.to._40c.nqCore.util.Constants.ZONE_ID;
import static path.to._40c.nqCore.entity.SymbolConfig.WEEKLY_ID;

import path.to._40c.nqCore.entity.SymbolConfig;
import path.to._40c.nqCore.repo.SymbolConfigRepository;

/**
 * The WEEKLY calendar's symbol service — row id=1 and the weekly cache slot. Row
 * lifecycle (save/get/current/warm) is inherited from SymbolService; what lives here
 * is weekly's own contract-advance mechanism: operator-entered symbols + rolloverDay,
 * with promotion date-gated at that day (14:47 trigger or the opportunistic checks in
 * the signal path).
 */
@Service
@Slf4j
public class WeeklySymbolService extends SymbolService {

    public WeeklySymbolService(SymbolConfigRepository repo, WeeklySymbolCache cache) {
        super(repo, cache, WEEKLY_ID, WEEKLY, "Weekly");
    }

    @Transactional
    public void promoteRolloverSymbol() {
        repo.findById(rowId()).ifPresent(cfg -> {
            log.info("Promoting rollover symbol to this week: thisWeek {} -> {}", cfg.getThisWeekSymbol(), cfg.getRolloverSymbol());
            cfg.setThisWeekSymbol(cfg.getRolloverSymbol());
            SymbolConfig saved = repo.save(cfg);
            cache.set(saved);
        });
    }

    @Transactional
    public void markRolloverComplete() {
        repo.findById(rowId()).ifPresent(cfg -> {
            cfg.setRolloverComplete(true);
            SymbolConfig saved = repo.save(cfg);
            cache.set(saved);
        });
    }

    /**
     * Weekly counterpart of MonthlySymbolService.syncTradedContract — the WEEKLY
     * calendar's contract-advance rule: on the operator-configured rollover day, promote
     * rolloverSymbol into thisWeekSymbol exactly once (the rolloverComplete latch). Any
     * other day, or an unconfigured day, is a no-op. Called by the signal paths, the
     * recenter, and the 14:47 roll trigger BEFORE the position roll — so the symbol row
     * always advances on expiry day even when the position roll itself fails, and every
     * subsequent open lands on the new contract.
     */
    public synchronized void syncTradedContract() {
        LocalDate today = LocalDate.now(ZoneId.of(ZONE_ID));
        SymbolConfig cfg = current();
        if (cfg == null || cfg.getRolloverDay() == null || !today.equals(cfg.getRolloverDay())) return;
        if (Boolean.TRUE.equals(cfg.getRolloverComplete())) {
            log.info("Rollover day — already complete, skipping symbol promotion");
        } else {
            log.info("Rollover day — promoting rollover symbol | {} -> thisWeek", cfg.getRolloverSymbol());
            promoteRolloverSymbol();
            markRolloverComplete();
        }
    }

    /**
     * Backfills the scope column on the pre-existing weekly row while warming (created
     * before the column existed, so it reads null after the DDL update).
     */
    @Override
    protected SymbolConfig afterLoad(SymbolConfig cfg) {
        if (cfg.getScope() == null) {
            cfg.setScope(WEEKLY);
            cfg = repo.save(cfg);
            log.info("Backfilled scope=WEEKLY on symbol row id=1");
        }
        return cfg;
    }
}
