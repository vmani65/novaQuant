package path.to._40c.nqCore.service;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import path.to._40c.nqCore.entity.LegTemplate;
import path.to._40c.nqCore.repo.LegTemplateRepository;

import static path.to._40c.nqCore.util.Constants.LONG;
import static path.to._40c.nqCore.util.Constants.SHORT;

/**
 * In-memory cache of LegTemplate rows grouped by direction (LONG / SHORT).
 * Refresh on app startup and after any CRUD operation on leg_template.
 */
@Service
public class LegTemplateCache {

    private final LegTemplateRepository repository;
    private final AtomicReference<List<LegTemplate>> longLegs  = new AtomicReference<>(List.of());
    private final AtomicReference<List<LegTemplate>> shortLegs = new AtomicReference<>(List.of());

    public LegTemplateCache(LegTemplateRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void init() {
        refreshCache();
    }

    public List<LegTemplate> getLongLegs()  { return longLegs.get(); }
    public List<LegTemplate> getShortLegs() { return shortLegs.get(); }

    public void refreshCache() {
        longLegs.set(Collections.unmodifiableList(repository.findByDirection(LONG)));
        shortLegs.set(Collections.unmodifiableList(repository.findByDirection(SHORT)));
    }
}
