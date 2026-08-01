package path.to._40c.nqCore.service;

import static path.to._40c.nqCore.util.Constants.NIFTY;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;
import path.to._40c.nqCore.entity.Strategy;
import path.to._40c.nqCore.entity.WeeklyLeg;
import path.to._40c.nqCore.repo.PositionRepository;

/**
 * Strike-occupancy for multi-strategy trading. Zerodha nets positions per tradingsymbol, so
 * two strategies holding legs at the same strike+expiry become indistinguishable (or cancel
 * outright when opposite-direction) at the broker. Occupancy is DERIVED from live weekly_leg
 * rows on every check — never a separately maintained table — so it can never drift from what
 * is actually held. Correctness of check-then-open depends on the TradeExecutionQueue: all
 * opens are serialized, so no other strategy can take a strike between the check and the order.
 *
 * A strategy's own legs are always excluded from its occupancy view: a strategy holds at most
 * one position, and its own legs are either being closed in the same queue task (flip,
 * recenter, rollover — where the DB still shows them LIVE) or are orphans about to be swept.
 */
@Service
@Slf4j
public class StrikeOccupancyService {

    /** NIFTY strike grid spacing. */
    private static final int STRIKE_STEP = 50;

    /** Search cap when the strategy has no maxStrikeOffset configured. */
    private static final int DEFAULT_MAX_OFFSET = 150;

    private final PositionRepository positionRepository;
    private final StrategyRegistry strategyRegistry;

    public StrikeOccupancyService(PositionRepository positionRepository, StrategyRegistry strategyRegistry) {
        this.positionRepository = positionRepository;
        this.strategyRegistry = strategyRegistry;
    }

    /**
     * Picks the base strike for a new synthetic position: the ATM if free, else the nearest
     * free strike walking outward in 50-pt steps (+50, −50, +100, −100 …) up to the strategy's
     * maxStrikeOffset. A candidate is free when candidate+offset is unoccupied for every leg
     * template offset. A synthetic future's payoff is strike-independent, so a shift costs
     * only marginal liquidity/margin. Throws IllegalStateException when nothing is free inside
     * the cap — the caller must fail the open loudly rather than stack strikes at the broker.
     */
    public int resolveBaseStrike(int atm, String symbolPrefix, String strategyName, List<Integer> templateOffsets) {
        Set<Integer> occupied = occupiedStrikes(symbolPrefix, strategyName);
        if (occupied.isEmpty()) {
            return atm;
        }
        int maxOffset = maxOffsetFor(strategyName);
        for (int shift = 0; shift <= maxOffset; shift += STRIKE_STEP) {
            int[] candidates = shift == 0 ? new int[]{0} : new int[]{shift, -shift};
            for (int signedShift : candidates) {
                int candidate = atm + signedShift;
                if (isFree(candidate, templateOffsets, occupied)) {
                    if (signedShift != 0) {
                        log.warn("STRIKE OCCUPIED | atm={} not free for strategy={} (occupied={}) — shifted to {}",
                                atm, strategyName, occupied, candidate);
                    }
                    return candidate;
                }
            }
        }
        throw new IllegalStateException("No free strike for strategy=" + strategyName + " within ±" + maxOffset
                + " of ATM " + atm + " on " + symbolPrefix + " (occupied=" + occupied + ")");
    }

    /**
     * Strikes currently held at the broker on the given expiry prefix, excluding the given
     * strategy's own legs (null = exclude nothing). Prefers the stored STRIKE column and falls
     * back to parsing the instrument for legs persisted before the column existed.
     */
    public Set<Integer> occupiedStrikes(String symbolPrefix, String excludeStrategy) {
        List<WeeklyLeg> legs = positionRepository.findLiveLegsForOccupancy(excludeStrategy, NIFTY + symbolPrefix + "%");
        Set<Integer> occupied = new TreeSet<>();
        for (WeeklyLeg leg : legs) {
            Integer strike = leg.getStrike() != null ? leg.getStrike() : parseStrike(leg.getInstrument());
            if (strike != null) {
                occupied.add(strike);
            } else {
                log.error("OCCUPANCY BLIND SPOT | live leg id={} instrument={} has no parsable strike — it cannot be avoided",
                        leg.getId(), leg.getInstrument());
            }
        }
        return occupied;
    }

    /**
     * Strike-map rows for the dashboard: every live leg across all strategies and expiries,
     * sorted by strike — the "who occupies what" table.
     */
    public List<Map<String, Object>> snapshot() {
        List<WeeklyLeg> legs = new ArrayList<>(positionRepository.findLiveLegsForOccupancy(null, NIFTY + "%"));
        legs.sort((a, b) -> {
            Integer sa = a.getStrike() != null ? a.getStrike() : parseStrike(a.getInstrument());
            Integer sb = b.getStrike() != null ? b.getStrike() : parseStrike(b.getInstrument());
            return Integer.compare(sa == null ? 0 : sa, sb == null ? 0 : sb);
        });
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WeeklyLeg leg : legs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("strike", leg.getStrike() != null ? leg.getStrike() : parseStrike(leg.getInstrument()));
            m.put("instrument", leg.getInstrument());
            m.put("side", leg.getSide());
            m.put("quantity", leg.getQuantity());
            m.put("strategyName", leg.getPosition() != null ? leg.getPosition().getStrategyName() : null);
            m.put("direction", leg.getPosition() != null ? leg.getPosition().getDirection() : null);
            m.put("positionId", leg.getPosition() != null ? leg.getPosition().getId() : null);
            m.put("positionStatus", leg.getPosition() != null ? leg.getPosition().getStatus() : null);
            rows.add(m);
        }
        return rows;
    }

    /** A candidate base strike is free when candidate+offset is unoccupied for every template offset. */
    private static boolean isFree(int candidate, List<Integer> templateOffsets, Set<Integer> occupied) {
        if (templateOffsets == null || templateOffsets.isEmpty()) {
            return !occupied.contains(candidate);
        }
        return templateOffsets.stream().noneMatch(offset -> occupied.contains(candidate + offset));
    }

    private int maxOffsetFor(String strategyName) {
        Strategy s = strategyRegistry.get(strategyName);
        return s != null && s.getMaxStrikeOffset() != null ? s.getMaxStrikeOffset() : DEFAULT_MAX_OFFSET;
    }

    /**
     * Fallback strike extraction for legs persisted before the STRIKE column existed:
     * NIFTY strikes are the 5 digits immediately before the trailing CE/PE
     * (e.g. NIFTY2580723500CE → 23500).
     */
    static Integer parseStrike(String instrument) {
        if (instrument == null || instrument.length() < 7) return null;
        if (!instrument.endsWith("CE") && !instrument.endsWith("PE")) return null;
        String digits = instrument.substring(instrument.length() - 7, instrument.length() - 2);
        for (int i = 0; i < digits.length(); i++) {
            if (!Character.isDigit(digits.charAt(i))) return null;
        }
        return Integer.parseInt(digits);
    }
}
