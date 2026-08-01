package path.to._40c.nqCore.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import path.to._40c.nqCore.entity.Strategy;
import path.to._40c.nqCore.repo.PositionRepository;
import path.to._40c.nqCore.repo.StrategyRepository;

/**
 * In-memory registry of known strategies, backed by the STRATEGY table. Signal ingestion
 * consults this on every inbound signal: unknown or disabled strategy names are rejected
 * before they can touch the position book. Reads are served from an AtomicReference cache
 * (same pattern as WeeklySymbolCache) so the hot signal path never hits SQLite.
 */
@Service
@Slf4j
public class StrategyRegistry {

    private final StrategyRepository strategyRepository;
    private final PositionRepository positionRepository;
    private final AtomicReference<Map<String, Strategy>> cache = new AtomicReference<>(Map.of());

    public StrategyRegistry(StrategyRepository strategyRepository, PositionRepository positionRepository) {
        this.strategyRepository = strategyRepository;
        this.positionRepository = positionRepository;
    }

    /**
     * One-time migration seed: on first boot after this feature ships the STRATEGY table is
     * empty, so every strategy name found in the existing position history (prod: SwingMaster)
     * is registered as enabled. Subsequent boots just load the table into the cache.
     */
    @PostConstruct
    @Transactional
    public void seedAndLoad() {
        if (strategyRepository.count() == 0) {
            for (String name : positionRepository.findDistinctStrategyNames()) {
                if (name == null || name.isBlank()) continue;
                Strategy s = new Strategy();
                s.setName(name);
                strategyRepository.save(s);
                log.info("StrategyRegistry seeded from position history: {}", name);
            }
        }
        refresh();
        log.info("StrategyRegistry loaded: {}", cache.get().keySet());
    }

    /** Reloads the cache from the STRATEGY table. Call after any mutation. */
    public void refresh() {
        cache.set(strategyRepository.findAll().stream()
                .collect(Collectors.toUnmodifiableMap(Strategy::getName, Function.identity())));
    }

    /** True when the name exists in the registry, enabled or not. */
    public boolean isRegistered(String name) {
        return name != null && cache.get().containsKey(name);
    }

    /** True when the name exists and is enabled — the gate for accepting entry/exit signals. */
    public boolean isActive(String name) {
        Strategy s = name == null ? null : cache.get().get(name);
        return s != null && Boolean.TRUE.equals(s.getEnabled());
    }

    public Strategy get(String name) {
        return name == null ? null : cache.get().get(name);
    }

    public List<Strategy> all() {
        return strategyRepository.findAll();
    }

    /** Upserts a registry row and refreshes the cache. */
    public Strategy save(Strategy strategy) {
        Strategy saved = strategyRepository.save(strategy);
        refresh();
        return saved;
    }
}
