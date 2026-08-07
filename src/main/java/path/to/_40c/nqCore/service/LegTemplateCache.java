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
import static path.to._40c.nqCore.util.Constants.LONG_MONTHLY;
import static path.to._40c.nqCore.util.Constants.SHORT;
import static path.to._40c.nqCore.util.Constants.SYNTH_WEEKLY;

/**
 * In-memory cache of LegTemplate rows grouped by book and direction.
 * getLongLegs()/getShortLegs() are the SYNTH_WEEKLY accessors so the weekly synthetic
 * keeps trading exactly its own template; LONG_MONTHLY rows are held in their own slots
 * and consumed only by the monthly build path.
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
        backfillNullBooks();
        refreshCache();
    }

    public List<LegTemplate> getLongLegs()  { return longLegs.get(); }
    public List<LegTemplate> getShortLegs() { return shortLegs.get(); }

    public List<LegTemplate> getMonthlyLongLegs()  { return monthlyLongLegs.get(); }
    public List<LegTemplate> getMonthlyShortLegs() { return monthlyShortLegs.get(); }

    public void refreshCache() {
        longLegs.set(Collections.unmodifiableList(repository.findByDirectionAndBook(LONG, SYNTH_WEEKLY)));
        shortLegs.set(Collections.unmodifiableList(repository.findByDirectionAndBook(SHORT, SYNTH_WEEKLY)));
        monthlyLongLegs.set(Collections.unmodifiableList(repository.findByDirectionAndBook(LONG, LONG_MONTHLY)));
        monthlyShortLegs.set(Collections.unmodifiableList(repository.findByDirectionAndBook(SHORT, LONG_MONTHLY)));
    }

    /**
     * Rows created before the BOOK column existed read null after the DDL update; they
     * are all weekly synthetic legs, so stamp them SYNTH_WEEKLY once at startup. Without
     * this, the book-scoped queries would return empty and the engine would build
     * zero-leg positions. Note: a DB carrying the short-lived SCOPE column (phase-1 test
     * DBs only — never deployed) is NOT auto-migrated; monthly rows must be re-entered.
     */
    private void backfillNullBooks() {
        List<LegTemplate> all = repository.findAll();
        List<LegTemplate> nullBooked = all.stream().filter(t -> t.getBook() == null).toList();
        if (!nullBooked.isEmpty()) {
            nullBooked.forEach(t -> t.setBook(SYNTH_WEEKLY));
            repository.saveAll(nullBooked);
            log.info("Backfilled book=SYNTH_WEEKLY on {} pre-existing leg_template rows", nullBooked.size());
        }
    }
}
