package path.to._40c.nqCore.service;

import org.springframework.stereotype.Component;

import path.to._40c.nqCore.entity.WeeklySymbolConfig;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds one config per expiry scope. get()/set() remain the WEEKLY accessors so every
 * existing engine path (ComputeUtil.buildInstrument, rollover promotion) is untouched;
 * the MONTHLY slot is config-only until the monthly strategy is wired into the engine.
 */
@Component
public class WeeklySymbolCache {
    private final AtomicReference<WeeklySymbolConfig> ref = new AtomicReference<>();
    private final AtomicReference<WeeklySymbolConfig> monthlyRef = new AtomicReference<>();

    public WeeklySymbolConfig get() { return ref.get(); }
    public void set(WeeklySymbolConfig cfg) { ref.set(cfg); }
    public void clear() { ref.set(null); }

    public WeeklySymbolConfig getMonthly() { return monthlyRef.get(); }
    public void setMonthly(WeeklySymbolConfig cfg) { monthlyRef.set(cfg); }
}
