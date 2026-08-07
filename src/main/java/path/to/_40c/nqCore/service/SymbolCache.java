package path.to._40c.nqCore.service;

import java.util.concurrent.atomic.AtomicReference;

import path.to._40c.nqCore.entity.SymbolConfig;

/**
 * Single-slot in-memory holder for one calendar's symbol row. The implementation is
 * shared here; WeeklySymbolCache and MonthlySymbolCache stay as distinct injectable
 * TYPES on purpose — wiring the wrong calendar's cache into a consumer must be a
 * compile error, not a runtime surprise (a one-class merge with qualifiers would
 * happily compile a weekly build reading monthly contracts).
 */
public abstract class SymbolCache {
    private final AtomicReference<SymbolConfig> ref = new AtomicReference<>();

    public SymbolConfig get() { return ref.get(); }
    public void set(SymbolConfig cfg) { ref.set(cfg); }
    public void clear() { ref.set(null); }
}
