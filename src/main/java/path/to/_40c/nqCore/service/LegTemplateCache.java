package path.to._40c.nqCore.service;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import path.to._40c.nqCore.entity.LegTemplate;
import path.to._40c.nqCore.repo.LegTemplateRepository;

/**
 * In-memory cache of LegTemplate rows. Refresh on app startup and after any CRUD operation
 * on leg_template. Selection is per strategy: a strategy that has its own rows for a
 * direction uses exactly those; otherwise it falls back to the shared default rows
 * (strategyName = null), so existing single-strategy prod templates keep working unchanged.
 */
@Service
public class LegTemplateCache {

    private final LegTemplateRepository repository;
    private final AtomicReference<List<LegTemplate>> all = new AtomicReference<>(List.of());

    public LegTemplateCache(LegTemplateRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void init() {
        refreshCache();
    }

    /**
     * Template rows for one direction and strategy: the strategy's own rows if any exist,
     * else the default (null-strategy) rows. Returned lists are read-only cache entries —
     * callers must copy before mutating (e.g. a lots override).
     */
    public List<LegTemplate> getLegs(String direction, String strategyName) {
        List<LegTemplate> templates = all.get();
        if (strategyName != null) {
            List<LegTemplate> own = templates.stream()
                    .filter(t -> direction.equals(t.getDirection()) && strategyName.equals(t.getStrategyName()))
                    .toList();
            if (!own.isEmpty()) return own;
        }
        return templates.stream()
                .filter(t -> direction.equals(t.getDirection()) && t.getStrategyName() == null)
                .toList();
    }

    public void refreshCache() {
        all.set(Collections.unmodifiableList(repository.findAll()));
    }
}
