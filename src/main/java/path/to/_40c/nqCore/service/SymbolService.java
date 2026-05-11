package path.to._40c.nqCore.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static path.to._40c.nqCore.util.Constants.ZONE_ID;
import path.to._40c.nqCore.entity.SymbolConfig;
import path.to._40c.nqCore.repo.SymbolConfigRepository;

@Service
public class SymbolService {

    private static final Logger log = LoggerFactory.getLogger(SymbolService.class);
    private final SymbolConfigRepository repo;
    private final WeeklySymbolCache cache;

    public SymbolService(SymbolConfigRepository repo, WeeklySymbolCache cache) {
        this.repo = repo; this.cache = cache;
    }

    @Transactional
    public void saveSymbols(String thisWeek, String rollover, String rolloverDay) {
        repo.deleteAllInBatch();
        SymbolConfig cfg = new SymbolConfig(thisWeek, rollover);
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
            SymbolConfig saved = repo.save(cfg);
            cache.set(saved);
        });
    }

    @Transactional
    public void markRolloverComplete() {
        repo.findById(1L).ifPresent(cfg -> {
            cfg.setRolloverComplete(true);
            SymbolConfig saved = repo.save(cfg);
            cache.set(saved);
        });
    }

    public Optional<SymbolConfig> get() {
        return repo.findById(1L);
    }
    
    public boolean isMissing() {
        return repo.count() == 0;
    }
    
    @Transactional(readOnly = true)
    public SymbolConfig current() {
        SymbolConfig c = cache.get();
        if (c != null) return c;
        return repo.findById(1L).orElse(null);
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

    @EventListener(ApplicationReadyEvent.class)
    public void warmCache() {
        repo.findById(1L).ifPresent(cache::set);
    }
}
