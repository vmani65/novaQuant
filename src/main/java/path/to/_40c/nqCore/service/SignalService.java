package path.to._40c.nqCore.service;

import static path.to._40c.nqCore.util.Constants.LIVE;
import static path.to._40c.nqCore.util.Constants.ZONE_ID;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import path.to._40c.nqCore.controller.SignalController.Signal;
import path.to._40c.nqCore.entity.Trade;
import path.to._40c.nqCore.repo.TradeRepository;

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
	    Trade liveTrade = tradeRepository.findFirstByTradeStatusOrderByIdDesc(LIVE);
	    if (liveTrade != null) {
	        return liveTrade;
	    }
	    return tradeRepository.findFirstByOrderByIdDesc();
	}

	public boolean handleFlip(String signalPrice, String type, Signal signal) {
	   Instant start = Instant.now();
	   Trade trade = new Trade(signal);
	   // Rollover check runs before the async task so prepareOpen sees the correct symbol.
	   checkAndPromoteRolloverSymbol();
	   // buildInstrument is pure computation; getLTP(open) is independent of the close result.
	   // Fire both concurrently with closeTrade — close takes ~400-600ms, prep takes ~100ms,
	   // so the future is always complete before join() is reached.
	   CompletableFuture<TradeOpeningService.OpenPrep> openPrepFuture =
	       CompletableFuture.supplyAsync(() -> openingService.prepareOpen(signalPrice, type, trade));
	   Trade closedTrade = closingService.closeTrade(signalPrice, signal, false);
	   TradeOpeningService.OpenPrep prep = null;
	   try {
	       prep = openPrepFuture.join();
	   } catch (Exception e) {
	       log.error("Open-leg pre-fetch failed — falling back to inline open: {}", e.getMessage());
	   }
	   Trade liveTrade = (prep != null)
	       ? openingService.openTrade(signalPrice, type, trade, prep)
	       : openingService.openTrade(signalPrice, type, trade);
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
	   checkAndPromoteRolloverSymbol();   // must run before afterClose to avoid SQLite BUSY on concurrent writes
	   postTradeService.afterClose(closedTrade);
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
	   checkAndPromoteRolloverSymbol();   // must run before afterClose to avoid SQLite BUSY on concurrent writes
	   postTradeService.afterClose(closedTrade);
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
	   symbolService.checkAndPromoteRolloverSymbol();
	}

	public boolean handleRollOver(String signalPrice) {
	   Instant start = Instant.now();
	   rollOverService.rollOver(signalPrice);
	   log.info("Time taken to complete trade rollover is : {} ms", String.format("%,d", Duration.between(start, Instant.now()).toMillis()));
	   return true;
	}
}
