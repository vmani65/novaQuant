package path.to._40c.nqCore.service;

import org.springframework.stereotype.Component;

import path.to._40c.nqCore.entity.WeeklySymbolConfig;

import java.util.concurrent.atomic.AtomicReference;

@Component
public class WeeklySymbolCache {
    private final AtomicReference<WeeklySymbolConfig> ref = new AtomicReference<>();

    public WeeklySymbolConfig get() { return ref.get(); }
    public void set(WeeklySymbolConfig cfg) { ref.set(cfg); }
    public void clear() { ref.set(null); }
}
