package path.to._40c.service;

import java.util.Optional;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import path.to._40c.entity.SymbolConfig;
import path.to._40c.repo.SymbolConfigRepository;

@Service
public class SymbolService {
    private final SymbolConfigRepository repo;
    private final WeeklySymbolCache cache;

    public SymbolService(SymbolConfigRepository repo, WeeklySymbolCache cache) {
        this.repo = repo; this.cache = cache;
    }

    @Transactional
    public void saveSymbols(String thisWeek, String rollover) {
        repo.deleteAllInBatch();
        SymbolConfig cfg = new SymbolConfig(thisWeek, rollover);
        repo.save(cfg);
        cache.set(cfg);
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
    
    @EventListener(ApplicationReadyEvent.class)
    public void warmCache() {
        repo.findById(1L).ifPresent(cache::set);
    }
}
