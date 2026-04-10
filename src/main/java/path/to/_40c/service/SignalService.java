package path.to._40c.service;

import static path.to._40c.util.Constants.LIVE;
import static path.to._40c.util.Constants.ZONE_ID;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

	private final HttpClient httpClient = HttpClient.newHttpClient();

	@Value("${nq.ticker.url:http://localhost:9192}")
	private String nqTickerUrl;

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

	   // 9:15 AM long exit: delegate to nQ-ticker open buffer.
	   // nQ-ticker will monitor NIFTY and call /api/execute-close when target is hit or
	   // deadline (09:28:59) is reached. That endpoint closes directly — no 9:15 re-check.
	   if ("longExit".equals(signal.action) && isOpenBufferTime()) {
	       if (armNqTickerBuffer(signalPrice)) {
	           log.info("9:15 AM long exit delegated to nQ-ticker open buffer | openPrice={} | {}ms",
	                   signalPrice, Duration.between(start, Instant.now()).toMillis());
	           return true;  // actual close fires async from nQ-ticker
	       }
	       log.warn("nQ-ticker arm-buffer call failed — falling back to immediate close");
	   }

	   Trade closedTrade = closingService.closeTrade(signalPrice, signal, true);
	   log.info("Time taken to complete trade close is : {} ms", String.format("%,d", Duration.between(start, Instant.now()).toMillis()));
	   postTradeService.afterClose(closedTrade);
	   checkAndPromoteRolloverSymbol();
	   return true;
	}

	/**
	 * Called by /api/execute-close — the callback from nQ-ticker's open buffer.
	 * Closes the trade directly, no 9:15 AM check, no re-delegation.
	 */
	public void executeCloseImmediate(String signalPrice) {
	   Instant start = Instant.now();
	   Signal signal = new Signal("open-buffer", "longExit", "CE", "", signalPrice);
	   Trade closedTrade = closingService.closeTrade(signalPrice, signal, true);
	   log.info("execute-close completed in {}ms", Duration.between(start, Instant.now()).toMillis());
	   postTradeService.afterClose(closedTrade);
	   checkAndPromoteRolloverSymbol();
	}

	private boolean isOpenBufferTime() {
	   LocalTime now = LocalTime.now(ZoneId.of(ZONE_ID));
	   return now.getHour() == 9 && now.getMinute() == 15;
	}

	private boolean armNqTickerBuffer(String openPrice) {
	   String url = nqTickerUrl + "/arm-buffer?openPrice=" + openPrice;
	   try {
	       HttpResponse<String> resp = httpClient.send(
	               HttpRequest.newBuilder().uri(URI.create(url)).GET().build(),
	               HttpResponse.BodyHandlers.ofString());
	       boolean ok = resp.statusCode() == 200;
	       if (!ok) log.warn("arm-buffer returned HTTP {} — {}", resp.statusCode(), resp.body());
	       return ok;
	   } catch (Exception e) {
	       log.error("arm-buffer HTTP call failed: {}", e.getMessage());
	       return false;
	   }
	}

	private void checkAndPromoteRolloverSymbol() {
	   LocalDate today = LocalDate.now(ZoneId.of(ZONE_ID));
	   SymbolConfig cfg = symbolService.current();
	   if (cfg == null || cfg.getRolloverDay() == null || !today.equals(cfg.getRolloverDay())) return;
	   if (Boolean.TRUE.equals(cfg.getRolloverComplete())) {
	       log.info("Rollover day — already complete, skipping symbol promotion");
	   } else {
	       log.info("Rollover day — promoting rollover symbol | {} -> thisWeek", cfg.getRolloverSymbol());
	       symbolService.promoteRolloverSymbol();
	       symbolService.markRolloverComplete();
	   }
	}

	public boolean handleRollOver(String signalPrice) {
	   Instant start = Instant.now();
	   rollOverService.rollOver(signalPrice);
	   log.info("Time taken to complete trade rollover is : {} ms", String.format("%,d", Duration.between(start, Instant.now()).toMillis()));
	   return true;
	}
}
