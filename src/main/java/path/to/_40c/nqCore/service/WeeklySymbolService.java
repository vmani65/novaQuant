package path.to._40c.nqCore.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static path.to._40c.nqCore.util.Constants.ZONE_ID;
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

    @Transactional
    public void saveSymbols(String thisWeek, String rollover, String rolloverDay) {
        repo.deleteAllInBatch();
        WeeklySymbolConfig cfg = new WeeklySymbolConfig(thisWeek, rollover);
        if (rolloverDay != null && !rolloverDay.isBlank()) {
            cfg.setRolloverDay(java.time.LocalDate.parse(rolloverDay));
        }
        repo.save(cfg);
        cache.set(cfg);
    }

    @Transactional
    public void promoteRolloverSymbol() {
        repo.findById(1L).ifPresent(cfg -> {
            log.info("Promoting rollover symbol to this week: thisWeek {} -> {}", cfg.getThisWeekSymbol(), cfg.getRolloverSymbol());
            cfg.setThisWeekSymbol(cfg.getRolloverSymbol());
            WeeklySymbolConfig saved = repo.save(cfg);
            cache.set(saved);
        });
    }

    @Transactional
    public void markRolloverComplete() {
        repo.findById(1L).ifPresent(cfg -> {
            cfg.setRolloverComplete(true);
            WeeklySymbolConfig saved = repo.save(cfg);
            cache.set(saved);
        });
    }

    public Optional<WeeklySymbolConfig> get() {
        return repo.findById(1L);
    }
    
    public boolean isMissing() {
        return repo.count() == 0;
    }
    
    @Transactional(readOnly = true)
    public WeeklySymbolConfig current() {
        WeeklySymbolConfig c = cache.get();
        if (c != null) return c;
        return repo.findById(1L).orElse(null);
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

    @EventListener(ApplicationReadyEvent.class)
    public void warmCache() {
        repo.findById(1L).ifPresent(cache::set);
    }
}
