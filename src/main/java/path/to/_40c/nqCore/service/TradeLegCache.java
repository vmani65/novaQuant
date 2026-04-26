package path.to._40c.nqCore.service;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import path.to._40c.nqCore.pojo.TradeLegConfig;
import path.to._40c.nqCore.repo.TradeLegConfigRepository;

@Service
public class TradeLegCache {
	
	private final TradeLegConfigRepository repository;
    private final AtomicReference<List<TradeLegConfig>> longLegsCache = new AtomicReference<>(List.of());
    private final AtomicReference<List<TradeLegConfig>> shortLegsCache = new AtomicReference<>(List.of());

    public TradeLegCache(TradeLegConfigRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void init() {
        refreshCache(); 
    }

    public List<TradeLegConfig> getLongLegs() {
        return longLegsCache.get();
    }

    public List<TradeLegConfig> getShortLegs() {
        return shortLegsCache.get();
    }

    public void refreshCache() {
        List<TradeLegConfig> long_ = repository.findLongLegs(); 
        longLegsCache.set(Collections.unmodifiableList(long_));
        List<TradeLegConfig> short_ = repository.findShortLegs(); 
        shortLegsCache.set(Collections.unmodifiableList(short_));
    }
}
