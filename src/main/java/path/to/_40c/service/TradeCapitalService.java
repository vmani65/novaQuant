package path.to._40c.service;

import path.to._40c.entity.TradeCapital;
import path.to._40c.repo.TradeCapitalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TradeCapitalService {

    private static final Logger log = LoggerFactory.getLogger(TradeCapitalService.class);
    
    @Autowired
    private TradeCapitalRepository tradeCapitalRepository;
    
    /**
     * Get the singleton TradeCapital record. Creates a new one if it doesn't exist.
     */
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
    
    /**
     * Save or update the TradeCapital record
     */
    @Transactional
    public TradeCapital saveTradeCapital(TradeCapital tradeCapital) {
        tradeCapital.setId(1L);
        return tradeCapitalRepository.save(tradeCapital);
    }
    
    /**
     * Add additional capital to current capital
     */
    @Transactional
    public TradeCapital addCapital(Double additionalCapital) {
        TradeCapital tradeCapital = getTradeCapital();
        double updatedCapital = tradeCapital.getCurrentCapital() + additionalCapital;
        tradeCapital.setCurrentCapital(updatedCapital);
        return tradeCapitalRepository.save(tradeCapital);
    }
    
    /**
     * Update current capital directly
     */
    @Transactional
    public TradeCapital updateCurrentCapital(Double currentCapital) {
        TradeCapital tradeCapital = getTradeCapital();
        tradeCapital.setCurrentCapital(currentCapital);        
        return tradeCapitalRepository.save(tradeCapital);
    }
    
    /**
     * Check if current capital has reached the ceiling
     */
    public boolean hasReachedCeiling() {
        TradeCapital tradeCapital = getTradeCapital();
        return tradeCapital.getCurrentCapital() >= tradeCapital.getCeilingToHit();
    }
    
    /**
     * Calculate how many lots can be traded based on current capital and risk
     */
    public int calculateLotSize() {
        TradeCapital tradeCapital = getTradeCapital();        
        if (tradeCapital.getDefinedRiskPerLot() == null || tradeCapital.getDefinedRiskPerLot() == 0) 
            return 1;
        int calculatedLots = (int) (tradeCapital.getCurrentCapital() / tradeCapital.getDefinedRiskPerLot());        
        return Math.max(1, calculatedLots);
    }
    
    /**
     * Get capital utilization percentage
     */
    public double getCapitalUtilization() {
        TradeCapital tradeCapital = getTradeCapital();        
        if (tradeCapital.getCeilingToHit() == null || tradeCapital.getCeilingToHit() == 0)
            return 0.0;
        return (tradeCapital.getCurrentCapital() / tradeCapital.getCeilingToHit()) * 100;
    }
}