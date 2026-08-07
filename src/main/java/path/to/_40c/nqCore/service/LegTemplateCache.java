package path.to._40c.nqCore.service;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import path.to._40c.nqCore.entity.LegTemplate;
import path.to._40c.nqCore.repo.LegTemplateRepository;

import static path.to._40c.nqCore.util.Constants.LONG;
import static path.to._40c.nqCore.util.Constants.MONTHLY;
import static path.to._40c.nqCore.util.Constants.SHORT;
import static path.to._40c.nqCore.util.Constants.WEEKLY;

/**
 * In-memory cache of LegTemplate rows grouped by scope and direction.
 * getLongLegs()/getShortLegs() stay the WEEKLY accessors so ComputeUtil.buildInstrument
 * keeps trading exactly the weekly synthetic; MONTHLY rows are held in their own slots
 * and are not consumed by the engine until the monthly strategy is wired in.
 * Refresh on app startup and after any CRUD operation on leg_template.
 */
@Service
@Slf4j
public class LegTemplateCache {

    private final LegTemplateRepository repository;
    private final AtomicReference<List<LegTemplate>> longLegs  = new AtomicReference<>(List.of());
    private final AtomicReference<List<LegTemplate>> shortLegs = new AtomicReference<>(List.of());
    private final AtomicReference<List<LegTemplate>> monthlyLongLegs  = new AtomicReference<>(List.of());
    private final AtomicReference<List<LegTemplate>> monthlyShortLegs = new AtomicReference<>(List.of());

    public LegTemplateCache(LegTemplateRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void init() {
        backfillNullScopes();
        refreshCache();
    }

    public List<LegTemplate> getLongLegs()  { return longLegs.get(); }
    public List<LegTemplate> getShortLegs() { return shortLegs.get(); }

    public List<LegTemplate> getMonthlyLongLegs()  { return monthlyLongLegs.get(); }
    public List<LegTemplate> getMonthlyShortLegs() { return monthlyShortLegs.get(); }

    public void refreshCache() {
        longLegs.set(Collections.unmodifiableList(repository.findByDirectionAndScope(LONG, WEEKLY)));
        shortLegs.set(Collections.unmodifiableList(repository.findByDirectionAndScope(SHORT, WEEKLY)));
        monthlyLongLegs.set(Collections.unmodifiableList(repository.findByDirectionAndScope(LONG, MONTHLY)));
        monthlyShortLegs.set(Collections.unmodifiableList(repository.findByDirectionAndScope(SHORT, MONTHLY)));
    }

    /**
     * Rows created before the SCOPE column existed read null after the DDL update; they
     * are all weekly synthetic legs, so stamp them WEEKLY once at startup. Without this,
     * the scoped queries would return empty and the engine would build zero-leg positions.
     */
    private void backfillNullScopes() {
        List<LegTemplate> all = repository.findAll();
        List<LegTemplate> nullScoped = all.stream().filter(t -> t.getScope() == null).toList();
        if (!nullScoped.isEmpty()) {
            nullScoped.forEach(t -> t.setScope(WEEKLY));
            repository.saveAll(nullScoped);
            log.info("Backfilled scope=WEEKLY on {} pre-existing leg_template rows", nullScoped.size());
        }
    }
}
