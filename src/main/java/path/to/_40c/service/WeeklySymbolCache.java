package path.to._40c.service;

import org.springframework.stereotype.Component;

import path.to._40c.entity.SymbolConfig;

import java.util.concurrent.atomic.AtomicReference;

@Component
public class WeeklySymbolCache {
    private final AtomicReference<SymbolConfig> ref = new AtomicReference<>();

    public SymbolConfig get() { return ref.get(); }
    public void set(SymbolConfig cfg) { ref.set(cfg); }
    public void clear() { ref.set(null); }
}
