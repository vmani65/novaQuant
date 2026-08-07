package path.to._40c.nqCore.service;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;
import path.to._40c.nqCore.entity.SymbolConfig;
import path.to._40c.nqCore.repo.SymbolConfigRepository;

/**
 * Shared row + cache lifecycle for one calendar's symbol config: upsert, read, and
 * startup warm-up against the calendar's fixed row id. This is the mergeable half of
 * the two symbol services — the contract-ADVANCE mechanisms are deliberately NOT here,
 * because they are genuinely different per calendar (weekly: operator symbols +
 * date-gated promotion; monthly: NFO-chain DTE rule) and live in the subclasses.
 * WeeklySymbolService and MonthlySymbolService stay distinct injectable types so
 * wiring the wrong calendar into a consumer is a compile error.
 */
@Slf4j
public abstract class SymbolService {

    protected final SymbolConfigRepository repo;
    protected final SymbolCache cache;
    private final long rowId;
    private final String scope;
    private final String label;

    protected SymbolService(SymbolConfigRepository repo, SymbolCache cache, long rowId, String scope, String label) {
        this.repo = repo;
        this.cache = cache;
        this.rowId = rowId;
        this.scope = scope;
        this.label = label;
    }

    /**
     * Upserts this calendar's row. Rollover-complete resets on every save: a freshly
     * saved symbol pair means the configured advance is pending again.
     */
    @Transactional
    public void saveSymbols(String currentSymbol, String rolloverSymbol, String rolloverDay) {
        SymbolConfig cfg = repo.findById(rowId)
                .orElseGet(() -> new SymbolConfig(rowId, scope, currentSymbol, rolloverSymbol));
        cfg.setScope(scope);
        cfg.setThisWeekSymbol(currentSymbol);
        cfg.setRolloverSymbol(rolloverSymbol);
        cfg.setRolloverComplete(false);
        cfg.setRolloverDay(rolloverDay != null && !rolloverDay.isBlank()
                ? LocalDate.parse(rolloverDay) : null);
        SymbolConfig saved = repo.save(cfg);
        cache.set(saved);
        log.info("{} symbols saved | current={} rollover={} rolloverDay={}",
                label, currentSymbol, rolloverSymbol, cfg.getRolloverDay());
    }

    public Optional<SymbolConfig> get() {
        return repo.findById(rowId);
    }

    public boolean isMissing() {
        return repo.findById(rowId).isEmpty();
    }

    @Transactional(readOnly = true)
    public SymbolConfig current() {
        SymbolConfig c = cache.get();
        if (c != null) return c;
        return repo.findById(rowId).orElse(null);
    }

    /** Warms this calendar's slot at startup; afterLoad lets a calendar fix up the row first. */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void warmCache() {
        repo.findById(rowId).ifPresent(cfg -> cache.set(afterLoad(cfg)));
    }

    /** Hook for calendar-specific fixups while warming (weekly backfills a null scope). */
    protected SymbolConfig afterLoad(SymbolConfig cfg) {
        return cfg;
    }

    protected long rowId() {
        return rowId;
    }
}
