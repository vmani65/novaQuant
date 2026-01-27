package path.to._40c.service;

import static path.to._40c.util.Constants.LIVE;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import path.to._40c.controller.SignalController.Signal;
import path.to._40c.entity.Trade;
import path.to._40c.repo.TradeRepository;
import path.to._40c.util.ComputeUtil;
import path.to._40c.util.TradeUtil;

@Service
public class SignalService {

	private static final Logger log = LoggerFactory.getLogger(SignalService.class);

	@Autowired
	TradeOpeningService openingService;
	
	@Autowired
	TradeClosingService closingService;
	
	@Autowired
	TradeRollOverService rollOverService;
    
	@Autowired
    private TradeUtil tradeUtil;
	
	@Autowired
    private ComputeUtil computeUtil;
    
    @Autowired
    private TradeRepository tradeRepository; 
    
    @PersistenceContext
    private EntityManager entityManager;
    
	public Trade getLastTrade() {
	    Trade liveTrade = tradeRepository.findByTradeStatus(LIVE);	    
	    if (liveTrade != null) {
	        return liveTrade;
	    }
	    return tradeRepository.findFirstByOrderByIdDesc();
	}
	
	public boolean handleFlip(String signalPrice, String type, Signal signal) {
	   Instant start = Instant.now();		
	   Trade trade = new Trade(signal);
       Trade closedTrade = closingService.closeTrade(signalPrice, signal, false);
       Trade liveTrade =openingService.openTrade(signalPrice, type, trade);	       
	   log.info("Time taken to complete flip is : {} ms", String.format("%,d", Duration.between(start, Instant.now()).toMillis()));
	   doAfterOpenCalc(liveTrade);
	   tradeRepository.save(liveTrade);
	   if(closedTrade != null) 
		   doAfterCloseCalc(closedTrade);	 	   
	   return true;
	}
	
	public boolean handleTradeOpen(String signalPrice, String type, Signal signal) {
	   Instant start = Instant.now();	
	   Trade trade = new Trade(signal);
	   Trade liveTrade =openingService.openTrade(signalPrice, type, trade);
	   log.info("Time taken to complete trade open is : {} ms", String.format("%,d", Duration.between(start, Instant.now()).toMillis()));
	   doAfterOpenCalc(liveTrade);
	   tradeRepository.save(liveTrade);
	   return true;
	}
	
	public boolean handleTradeClose(String signalPrice, String type, Signal signal) {
	   Instant start = Instant.now();		
	   Trade closedTrade = closingService.closeTrade(signalPrice, signal, true);
	   log.info("Time taken to complete trade close is : {} ms", String.format("%,d", Duration.between(start, Instant.now()).toMillis()));
	   if(closedTrade != null) 
		   doAfterCloseCalc(closedTrade);
	   return true;
	}	
	
	public boolean handleRollOver(String signalPrice) {
	   Instant start = Instant.now();	
	   rollOverService.rollOver(signalPrice);
	   log.info("Time taken to complete trade rollover is : {} ms", String.format("%,d", Duration.between(start, Instant.now()).toMillis()));
	   return true;
	}	
	
	private void doAfterOpenCalc(Trade liveTrade) {
	   try {
		   tradeUtil.setTradeExecutedPrices(liveTrade);
		   tradeUtil.calcMarginAndBrokerage(liveTrade);
	   } catch (Exception e) {
			log.error("Exception while performing post trade open calculations ", e);	
	   }
	}
	
	private void doAfterCloseCalc(Trade closedTrade) {
	   try {
		   Trade t = tradeRepository.findById(closedTrade.getId()).orElse(null);
		   log.info("Trade(Parent+All Child) used for computing post close calc : " + t);
		   tradeUtil.setTradeExecutedPrices(t);
		   computeUtil.calcTradeOutcome(t);
		   computeUtil.calcPnL(t);
		   computeUtil.recalculateCapital(t);
		   tradeRepository.save(t); 
		} catch (Exception e) {
			log.error("Exception while performing post trade close calculations ", e);	
		}
	}
}
