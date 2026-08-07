package path.to._40c.nqCore.service;

import org.springframework.stereotype.Component;

import path.to._40c.nqCore.entity.SymbolConfig;

import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory slot for the MONTHLY calendar's symbol row (id=2) — the contract the
 * LONG_MONTHLY book trades. Mirror of WeeklySymbolCache; each calendar owns exactly
 * one cache with one slot.
 */
@Component
public class MonthlySymbolCache {
    private final AtomicReference<SymbolConfig> ref = new AtomicReference<>();

    public SymbolConfig get() { return ref.get(); }
    public void set(SymbolConfig cfg) { ref.set(cfg); }
    public void clear() { ref.set(null); }
}
