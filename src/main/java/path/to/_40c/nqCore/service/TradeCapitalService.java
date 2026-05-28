package path.to._40c.nqCore.service;

import static path.to._40c.nqCore.util.Constants.DATE_FORMAT;
import static path.to._40c.nqCore.util.Constants.ZONE_ID;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

import path.to._40c.nqCore.entity.TradeCapital;
import path.to._40c.nqCore.repo.TradeCapitalRepository;
import path.to._40c.nqCore.repo.TradeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TradeCapitalService {

    private static final Logger log = LoggerFactory.getLogger(TradeCapitalService.class);

    private final TradeCapitalRepository tradeCapitalRepository;
    private final TradeRepository tradeRepository;

    public TradeCapitalService(TradeCapitalRepository tradeCapitalRepository, TradeRepository tradeRepository) {
        this.tradeCapitalRepository = tradeCapitalRepository;
        this.tradeRepository = tradeRepository;
    }

    public TradeCapital getTradeCapital() {
        TradeCapital capital = tradeCapitalRepository.findById(1L)
                .orElseGet(() -> {
                    log.info("TradeCapital record not found, creating new one with default values");
                    TradeCapital newCapital = new TradeCapital();
                    newCapital.setId(1L);
                    newCapital.setCurrentCapital(0.0);
                    newCapital.setCeilingToHit(0.0);
                    newCapital.setDefinedRiskPerLot(0);
                    return tradeCapitalRepository.save(newCapital);
                });
        populateNrmlCostStats(capital);
        return capital;
    }

    /**
     * Computes highest / average / lowest of (peakMargin / lots) across trades closed in the last 30 days
     * and writes them onto the entity's transient fields. Skips trades with null peakMargin,
     * null/zero lots, or unparseable close datetime. Leaves transients null if no qualifying trades.
     */
    private void populateNrmlCostStats(TradeCapital capital) {
        LocalDateTime cutoff = LocalDateTime.now(ZoneId.of(ZONE_ID)).minusDays(30);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern(DATE_FORMAT);
        List<Integer> perLotValues = tradeRepository.findByPeakMarginNotNull().stream()
                .filter(t -> t.getLots() != null && t.getLots() > 0 && t.getTradeCloseDtTime() != null)
                .filter(t -> {
                    try { return LocalDateTime.parse(t.getTradeCloseDtTime(), fmt).isAfter(cutoff); }
                    catch (DateTimeParseException e) { return false; }
                })
                .map(t -> (int)(t.getPeakMargin() / t.getLots()))
                .toList();
        if (perLotValues.isEmpty()) return;
        int sum = 0, min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (Integer v : perLotValues) {
            sum += v;
            if (v < min) min = v;
            if (v > max) max = v;
        }
        capital.setHighestNrmlCostPerLot(max);
        capital.setAverageNrmlCostPerLot(sum / perLotValues.size());
        capital.setLowestNrmlCostPerLot(min);
    }

    @Transactional
    public TradeCapital saveTradeCapital(TradeCapital tradeCapital) {
        tradeCapital.setId(1L);
        TradeCapital saved = tradeCapitalRepository.save(tradeCapital);
        populateNrmlCostStats(saved);
        return saved;
    }

    @Transactional
    public TradeCapital addCapital(Double additionalCapital) {
        TradeCapital tradeCapital = getTradeCapital();
        double updatedCapital = tradeCapital.getCurrentCapital() + additionalCapital;
        tradeCapital.setCurrentCapital(updatedCapital);
        TradeCapital saved = tradeCapitalRepository.save(tradeCapital);
        populateNrmlCostStats(saved);
        return saved;
    }

    @Transactional
    public TradeCapital updateCurrentCapital(Double currentCapital) {
        TradeCapital tradeCapital = getTradeCapital();
        tradeCapital.setCurrentCapital(currentCapital);
        TradeCapital saved = tradeCapitalRepository.save(tradeCapital);
        populateNrmlCostStats(saved);
        return saved;
    }

    public boolean hasReachedCeiling() {
        TradeCapital tradeCapital = getTradeCapital();
        return tradeCapital.getCurrentCapital() >= tradeCapital.getCeilingToHit();
    }

    public int calculateLotSize() {
        TradeCapital tradeCapital = getTradeCapital();
        if (tradeCapital.getDefinedRiskPerLot() == null || tradeCapital.getDefinedRiskPerLot() == 0)
            return 1;
        int calculatedLots = (int) (tradeCapital.getCurrentCapital() / tradeCapital.getDefinedRiskPerLot());
        return Math.max(1, calculatedLots);
    }

    public double getCapitalUtilization() {
        TradeCapital tradeCapital = getTradeCapital();
        if (tradeCapital.getCeilingToHit() == null || tradeCapital.getCeilingToHit() == 0)
            return 0.0;
        return (tradeCapital.getCurrentCapital() / tradeCapital.getCeilingToHit()) * 100;
    }
}
