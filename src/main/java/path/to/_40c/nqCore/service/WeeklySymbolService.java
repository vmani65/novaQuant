package path.to._40c.nqCore.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static path.to._40c.nqCore.util.Constants.WEEKLY;
import static path.to._40c.nqCore.util.Constants.ZONE_ID;
import static path.to._40c.nqCore.entity.SymbolConfig.WEEKLY_ID;

import path.to._40c.nqCore.entity.SymbolConfig;
import path.to._40c.nqCore.repo.SymbolConfigRepository;

/**
 * Owns the WEEKLY calendar's symbol lifecycle — row id=1 and the weekly cache slot,
 * nothing monthly. Mirror of MonthlySymbolService: each calendar's service answers
 * "what contract do I trade" and "when does it advance" with its own mechanism.
 * Weekly's mechanism is operator-driven: symbols + rolloverDay entered in the UI,
 * promotion date-gated at the configured rollover day (14:47 trigger or the
 * opportunistic checks in the signal path).
 */
@Service
@Slf4j
public class WeeklySymbolService {
    private final SymbolConfigRepository repo;
    private final WeeklySymbolCache cache;

    public WeeklySymbolService(SymbolConfigRepository repo, WeeklySymbolCache cache) {
        this.repo = repo; this.cache = cache;
    }

    /**
     * Upserts the weekly row (id=1). Rollover-complete resets on every save: a freshly
     * saved symbol pair means the configured roll is pending again.
     */
    @Transactional
    public void saveSymbols(String currentSymbol, String rolloverSymbol, String rolloverDay) {
        SymbolConfig cfg = repo.findById(WEEKLY_ID)
                .orElseGet(() -> new SymbolConfig(WEEKLY_ID, WEEKLY, currentSymbol, rolloverSymbol));
        cfg.setScope(WEEKLY);
        cfg.setThisWeekSymbol(currentSymbol);
        cfg.setRolloverSymbol(rolloverSymbol);
        cfg.setRolloverComplete(false);
        cfg.setRolloverDay(rolloverDay != null && !rolloverDay.isBlank()
                ? LocalDate.parse(rolloverDay) : null);
        SymbolConfig saved = repo.save(cfg);
        cache.set(saved);
        log.info("Weekly symbols saved | current={} rollover={} rolloverDay={}",
                currentSymbol, rolloverSymbol, cfg.getRolloverDay());
    }

    @Transactional
    public void promoteRolloverSymbol() {
        repo.findById(WEEKLY_ID).ifPresent(cfg -> {
            log.info("Promoting rollover symbol to this week: thisWeek {} -> {}", cfg.getThisWeekSymbol(), cfg.getRolloverSymbol());
            cfg.setThisWeekSymbol(cfg.getRolloverSymbol());
            SymbolConfig saved = repo.save(cfg);
            cache.set(saved);
        });
    }

    @Transactional
    public void markRolloverComplete() {
        repo.findById(WEEKLY_ID).ifPresent(cfg -> {
            cfg.setRolloverComplete(true);
            SymbolConfig saved = repo.save(cfg);
            cache.set(saved);
        });
    }

    public Optional<SymbolConfig> get() {
        return repo.findById(WEEKLY_ID);
    }

    public boolean isMissing() {
        return repo.findById(WEEKLY_ID).isEmpty();
    }

    @Transactional(readOnly = true)
    public SymbolConfig current() {
        SymbolConfig c = cache.get();
        if (c != null) return c;
        return repo.findById(WEEKLY_ID).orElse(null);
    }

    public void checkAndPromoteRolloverSymbol() {
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
     * Warms the weekly slot and backfills the scope column on the pre-existing weekly
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
    }
}
