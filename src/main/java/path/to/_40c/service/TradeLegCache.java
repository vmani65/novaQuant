package path.to._40c.service;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import path.to._40c.pojo.ContractPriority;
import path.to._40c.repo.ContractPriorityRepository;

@Service
public class ContractPriorityCache {
	
	private final ContractPriorityRepository repository;
    private final AtomicReference<List<ContractPriority>> longPriorityCache = new AtomicReference<>(List.of());
    private final AtomicReference<List<ContractPriority>> shortPriorityCache = new AtomicReference<>(List.of());

    public ContractPriorityCache(ContractPriorityRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void init() {
        refreshCache(); 
    }

    public List<ContractPriority> getLongPriorities() {
        return longPriorityCache.get();
    }

    public List<ContractPriority> getShortPriorities() {
        return shortPriorityCache.get();
    }

    public void refreshCache() {
        List<ContractPriority> long_ = repository.findLongContractQty(); 
        longPriorityCache.set(Collections.unmodifiableList(long_));
        List<ContractPriority> short_ = repository.findShortContractQty(); 
        shortPriorityCache.set(Collections.unmodifiableList(short_));
    }
}
