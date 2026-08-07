package path.to._40c.nqCore.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static path.to._40c.nqCore.util.Constants.MONTHLY;
import static path.to._40c.nqCore.util.Constants.WEEKLY;
import static path.to._40c.nqCore.util.Constants.ZONE_ID;
import static path.to._40c.nqCore.entity.WeeklySymbolConfig.MONTHLY_ID;
import static path.to._40c.nqCore.entity.WeeklySymbolConfig.WEEKLY_ID;

import path.to._40c.nqCore.entity.WeeklySymbolConfig;
import path.to._40c.nqCore.repo.WeeklySymbolConfigRepository;

@Service
@Slf4j
public class WeeklySymbolService {
    private final WeeklySymbolConfigRepository repo;
    private final WeeklySymbolCache cache;

    public WeeklySymbolService(WeeklySymbolConfigRepository repo, WeeklySymbolCache cache) {
        this.repo = repo; this.cache = cache;
    }

    /**
     * Upserts the row for one scope only (WEEKLY id=1, MONTHLY id=2), leaving the other
     * scope's row untouched so the two sections of the Symbols UI save independently.
     * Rollover-complete resets on every save: a freshly saved symbol pair means the
     * configured roll is pending again.
     */
    @Transactional
    public void saveSymbols(String scope, String currentSymbol, String rolloverSymbol, String rolloverDay) {
        String normalizedScope = MONTHLY.equalsIgnoreCase(scope) ? MONTHLY : WEEKLY;
        long id = MONTHLY.equals(normalizedScope) ? MONTHLY_ID : WEEKLY_ID;
        WeeklySymbolConfig cfg = repo.findById(id)
                .orElseGet(() -> new WeeklySymbolConfig(id, normalizedScope, currentSymbol, rolloverSymbol));
        cfg.setScope(normalizedScope);
        cfg.setThisWeekSymbol(currentSymbol);
        cfg.setRolloverSymbol(rolloverSymbol);
        cfg.setRolloverComplete(false);
        cfg.setRolloverDay(rolloverDay != null && !rolloverDay.isBlank()
                ? LocalDate.parse(rolloverDay) : null);
        WeeklySymbolConfig saved = repo.save(cfg);
        if (MONTHLY.equals(normalizedScope)) cache.setMonthly(saved); else cache.set(saved);
        log.info("Symbols saved | scope={} current={} rollover={} rolloverDay={}",
                normalizedScope, currentSymbol, rolloverSymbol, cfg.getRolloverDay());
    }

    @Transactional
    public void promoteRolloverSymbol() {
        repo.findById(WEEKLY_ID).ifPresent(cfg -> {
            log.info("Promoting rollover symbol to this week: thisWeek {} -> {}", cfg.getThisWeekSymbol(), cfg.getRolloverSymbol());
            cfg.setThisWeekSymbol(cfg.getRolloverSymbol());
            WeeklySymbolConfig saved = repo.save(cfg);
            cache.set(saved);
        });
    }

    @Transactional
    public void markRolloverComplete() {
        repo.findById(WEEKLY_ID).ifPresent(cfg -> {
            cfg.setRolloverComplete(true);
            WeeklySymbolConfig saved = repo.save(cfg);
            cache.set(saved);
        });
    }

    public Optional<WeeklySymbolConfig> get() {
        return repo.findById(WEEKLY_ID);
    }

    public Optional<WeeklySymbolConfig> getMonthly() {
        return repo.findById(MONTHLY_ID);
    }

    public boolean isMissing() {
        return repo.findById(WEEKLY_ID).isEmpty();
    }

    @Transactional(readOnly = true)
    public WeeklySymbolConfig current() {
        WeeklySymbolConfig c = cache.get();
        if (c != null) return c;
        return repo.findById(WEEKLY_ID).orElse(null);
    }

    public void checkAndPromoteRolloverSymbol() {
        LocalDate today = LocalDate.now(ZoneId.of(ZONE_ID));
        WeeklySymbolConfig cfg = current();
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
     * Warms both scope slots and backfills the scope column on the pre-existing weekly
     * row (created before the column existed, so it reads null after the DDL update).
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void warmCache() {
        repo.findById(WEEKLY_ID).ifPresent(cfg -> {
            if (cfg.getScope() == null) {
                cfg.setScope(WEEKLY);
                cfg = repo.save(cfg);
                log.info("Backfilled scope=WEEKLY on symbol row id=1");
            }
            cache.set(cfg);
        });
        repo.findById(MONTHLY_ID).ifPresent(cache::setMonthly);
    }
}
