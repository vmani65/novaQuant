package path.to._40c.service;

import static path.to._40c.util.Constants.LIVE;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import path.to._40c.controller.SignalController.Signal;
import path.to._40c.entity.SymbolConfig;
import path.to._40c.entity.Trade;
import path.to._40c.repo.TradeRepository;

@Service
public class SignalService {

	private static final Logger log = LoggerFactory.getLogger(SignalService.class);

	private final TradeOpeningService openingService;
	private final TradeClosingService closingService;
	private final TradeRollOverService rollOverService;
	private final PostTradeService postTradeService;
	private final TradeRepository tradeRepository;
	private final SymbolService symbolService;

	public SignalService(TradeOpeningService openingService, TradeClosingService closingService,
			TradeRollOverService rollOverService, PostTradeService postTradeService,
			TradeRepository tradeRepository, SymbolService symbolService) {
		this.openingService = openingService;
		this.closingService = closingService;
		this.rollOverService = rollOverService;
		this.postTradeService = postTradeService;
		this.tradeRepository = tradeRepository;
		this.symbolService = symbolService;
	}

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
	   checkAndPromoteRolloverSymbol();
	   Trade liveTrade = openingService.openTrade(signalPrice, type, trade);
	   log.info("Time taken to complete flip is : {} ms", String.format("%,d", Duration.between(start, Instant.now()).toMillis()));
	   postTradeService.afterOpen(liveTrade);
	   postTradeService.afterClose(closedTrade);
	   return true;
	}

	public boolean handleTradeOpen(String signalPrice, String type, Signal signal) {
	   Instant start = Instant.now();
	   Trade trade = new Trade(signal);
	   Trade liveTrade = openingService.openTrade(signalPrice, type, trade);
	   log.info("Time taken to complete trade open is : {} ms", String.format("%,d", Duration.between(start, Instant.now()).toMillis()));
	   postTradeService.afterOpen(liveTrade);
	   return true;
	}

	public boolean handleTradeClose(String signalPrice, String type, Signal signal) {
	   Instant start = Instant.now();
	   Trade closedTrade = closingService.closeTrade(signalPrice, signal, true);
	   log.info("Time taken to complete trade close is : {} ms", String.format("%,d", Duration.between(start, Instant.now()).toMillis()));
	   postTradeService.afterClose(closedTrade);
	   checkAndPromoteRolloverSymbol();
	   return true;
	}

	private void checkAndPromoteRolloverSymbol() {
	   LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
	   SymbolConfig cfg = symbolService.current();
	   if (cfg == null || cfg.getRolloverDay() == null || !today.equals(cfg.getRolloverDay())) return;
	   if (Boolean.TRUE.equals(cfg.getRolloverComplete())) {
	       log.info("Rollover day — already complete, skipping symbol promotion");
	   } else {
	       log.info("Rollover day — promoting rollover symbol | {} -> thisWeek", cfg.getRolloverSymbol());
	       symbolService.promoteRolloverSymbol();
	   }
	}

	public boolean handleRollOver(String signalPrice) {
	   Instant start = Instant.now();
	   rollOverService.rollOver(signalPrice);
	   log.info("Time taken to complete trade rollover is : {} ms", String.format("%,d", Duration.between(start, Instant.now()).toMillis()));
	   return true;
	}
}
