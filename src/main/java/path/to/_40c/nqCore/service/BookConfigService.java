package path.to._40c.nqCore.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import path.to._40c.nqCore.entity.BookConfig;
import path.to._40c.nqCore.repo.BookConfigRepository;

import static path.to._40c.nqCore.util.Constants.LONG_MONTHLY;
import static path.to._40c.nqCore.util.Constants.SYNTH_WEEKLY;

/**
 * DB-backed per-book enable/disable switches with an in-memory cache read on every
 * signal. Seeds SYNTH_WEEKLY=enabled (the proven live book) and LONG_MONTHLY=disabled
 * (nothing monthly can trade until the owner flips the switch in the UI). Missing row
 * or cache miss reads as DISABLED — fail-closed.
 */
@Service
@Slf4j
public class BookConfigService {

    private final BookConfigRepository repository;
    private final Map<String, Boolean> cache = new ConcurrentHashMap<>();

    public BookConfigService(BookConfigRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void init() {
        seedIfAbsent(SYNTH_WEEKLY, true);
        seedIfAbsent(LONG_MONTHLY, false);
        refreshCache();
        log.info("Book toggles loaded: {}", all());
    }

    public boolean isEnabled(String book) {
        return book != null && Boolean.TRUE.equals(cache.get(book));
    }

    /** Both books' states in fixed execution order (weekly first) for the UI/API. */
    public Map<String, Boolean> all() {
        Map<String, Boolean> out = new LinkedHashMap<>();
        out.put(SYNTH_WEEKLY, isEnabled(SYNTH_WEEKLY));
        out.put(LONG_MONTHLY, isEnabled(LONG_MONTHLY));
        return out;
    }

    @Transactional
    public void setEnabled(String book, boolean enabled) {
        if (!SYNTH_WEEKLY.equals(book) && !LONG_MONTHLY.equals(book)) {
            throw new IllegalArgumentException("book must be SYNTH_WEEKLY or LONG_MONTHLY (got '" + book + "')");
        }
        BookConfig cfg = repository.findById(book).orElseGet(() -> new BookConfig(book, enabled));
        cfg.setEnabled(enabled);
        repository.save(cfg);
        cache.put(book, enabled);
        log.warn("Book toggle changed: {} -> {}", book, enabled ? "ENABLED" : "DISABLED");
    }

    private void seedIfAbsent(String book, boolean enabledDefault) {
        if (repository.findById(book).isEmpty()) {
            repository.save(new BookConfig(book, enabledDefault));
            log.info("Seeded book config {} enabled={}", book, enabledDefault);
        }
    }

    private void refreshCache() {
        repository.findAll().forEach(c -> cache.put(c.getBook(), Boolean.TRUE.equals(c.getEnabled())));
    }
}
