package path.to._40c.service;

import path.to._40c.entity.TradeCapital;
import path.to._40c.repo.TradeCapitalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TradeCapitalService {

    private static final Logger log = LoggerFactory.getLogger(TradeCapitalService.class);

    private final TradeCapitalRepository tradeCapitalRepository;

    public TradeCapitalService(TradeCapitalRepository tradeCapitalRepository) {
        this.tradeCapitalRepository = tradeCapitalRepository;
    }

    public TradeCapital getTradeCapital() {
        return tradeCapitalRepository.findById(1L)
                .orElseGet(() -> {
                    log.info("TradeCapital record not found, creating new one with default values");
                    TradeCapital newCapital = new TradeCapital();
                    newCapital.setId(1L);
                    newCapital.setCurrentCapital(0.0);
                    newCapital.setCeilingToHit(0.0);
                    newCapital.setNrmlCostPerLot(0);
                    newCapital.setDefinedRiskPerLot(0);
                    return tradeCapitalRepository.save(newCapital);
                });
    }

    @Transactional
    public TradeCapital saveTradeCapital(TradeCapital tradeCapital) {
        tradeCapital.setId(1L);
        return tradeCapitalRepository.save(tradeCapital);
    }

    @Transactional
    public TradeCapital addCapital(Double additionalCapital) {
        TradeCapital tradeCapital = getTradeCapital();
        double updatedCapital = tradeCapital.getCurrentCapital() + additionalCapital;
        tradeCapital.setCurrentCapital(updatedCapital);
        return tradeCapitalRepository.save(tradeCapital);
    }

    @Transactional
    public TradeCapital updateCurrentCapital(Double currentCapital) {
        TradeCapital tradeCapital = getTradeCapital();
        tradeCapital.setCurrentCapital(currentCapital);
        return tradeCapitalRepository.save(tradeCapital);
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
