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
import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.repo.PositionRepository;

@Service
public class SignalService {

	private static final Logger log = LoggerFactory.getLogger(SignalService.class);

	private final PositionOpeningService openingService;
	private final PositionClosingService closingService;
	private final PositionRolloverService rollOverService;
	private final PostTradeService postTradeService;
	private final PositionRepository positionRepository;
	private final WeeklySymbolService weeklySymbolService;

	private final HttpClient httpClient = HttpClient.newHttpClient();

	@Value("${nq.ticker.url:http://localhost:9192}")
	private String nqTickerUrl;

	public SignalService(PositionOpeningService openingService, PositionClosingService closingService,
			PositionRolloverService rollOverService, PostTradeService postTradeService,
			PositionRepository positionRepository, WeeklySymbolService weeklySymbolService) {
		this.openingService = openingService;
		this.closingService = closingService;
		this.rollOverService = rollOverService;
		this.postTradeService = postTradeService;
		this.positionRepository = positionRepository;
		this.weeklySymbolService = weeklySymbolService;
	}

	public Position getLastTrade() {
	    Position liveTrade = positionRepository.findFirstByStatusOrderByIdDesc(LIVE);
	    if (liveTrade != null) {
	        return liveTrade;
	    }
	    return positionRepository.findFirstByOrderByIdDesc();
	}

	/**
	 * Flip = close current + open new in opposite direction. Rollover symbol check runs first
	 * so prepareOpen sees the correct symbol; then open prep runs concurrently with closeTrade
	 * (prep ~100ms, close ~400-600ms) so the future is ready by the time we join().
	 */
	public boolean handleFlip(String signalPrice, String type, Signal signal) {
	   Instant start = Instant.now();
	   Position trade = new Position(signal);
	   checkAndPromoteRolloverSymbol();
	   CompletableFuture<PositionOpeningService.OpenPrep> openPrepFuture =
	       CompletableFuture.supplyAsync(() -> openingService.prepareOpen(signalPrice, type, trade));
	   Position closedTrade = closingService.closeTrade(signalPrice, signal, false);
	   Instant closeEnd = Instant.now();
	   long closeMs = Duration.between(start, closeEnd).toMillis();
	   PositionOpeningService.OpenPrep prep = null;
	   try {
	       prep = openPrepFuture.join();
	   } catch (Exception e) {
	       log.error("Open-leg pre-fetch failed — falling back to inline open: {}", e.getMessage());
	   }
	   Position liveTrade = (prep != null)
	       ? openingService.openTrade(signalPrice, type, trade, prep)
	       : openingService.openTrade(signalPrice, type, trade);
	   long openMs = Duration.between(closeEnd, Instant.now()).toMillis();
	   log.info("[PERFORMANCE] flip | close={}ms | open={}ms | total={}ms", closeMs, openMs, closeMs + openMs);
	   postTradeService.afterOpen(liveTrade);
	   postTradeService.afterClose(closedTrade);
	   return true;
	}

	public boolean handleTradeOpen(String signalPrice, String type, Signal signal) {
	   Instant start = Instant.now();
	   Position trade = new Position(signal);
	   Position liveTrade = openingService.openTrade(signalPrice, type, trade);
	   log.info("[PERFORMANCE] open | exec={}ms", Duration.between(start, Instant.now()).toMillis());
	   postTradeService.afterOpen(liveTrade);
	   return true;
	}

	/**
	 * Close handler. 9:15 AM longExit is delegated to nQTicker's open buffer (which calls
	 * back via /api/execute-close once the open-window target or deadline hits); other
	 * close paths run inline. Rollover-symbol promotion runs before afterClose to avoid
	 * SQLite BUSY from concurrent writes.
	 */
	public boolean handleTradeClose(String signalPrice, String type, Signal signal) {
	   Instant start = Instant.now();

	   if ("longExit".equals(signal.action) && isOpenBufferTime()) {
	       if (armNqTickerBuffer(signalPrice)) {
	           log.info("9:15 AM long exit delegated to nQTicker open buffer | openPrice={} | {}ms",
	                   signalPrice, Duration.between(start, Instant.now()).toMillis());
	           return true;
	       }
	       log.warn("nQTicker arm-buffer call failed — falling back to immediate close");
	   }

	   Position closedTrade = closingService.closeTrade(signalPrice, signal, true);
	   log.info("[PERFORMANCE] close | exec={}ms", Duration.between(start, Instant.now()).toMillis());
	   checkAndPromoteRolloverSymbol();
	   postTradeService.afterClose(closedTrade);
	   return true;
	}

	/** Called by /api/execute-close — nQTicker open-buffer callback. Closes directly, no re-check. */
	public void executeCloseImmediate(String signalPrice) {
	   Instant start = Instant.now();
	   Signal signal = new Signal("open-buffer", "longExit", "CE", "", signalPrice);
	   Position closedTrade = closingService.closeTrade(signalPrice, signal, true);
	   log.info("execute-close completed in {}ms", Duration.between(start, Instant.now()).toMillis());
	   checkAndPromoteRolloverSymbol();
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
	               HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(3)).GET().build(),
	               HttpResponse.BodyHandlers.ofString());
	       boolean ok = resp.statusCode() == 200;
	       if (!ok) log.warn("arm-buffer returned HTTP {} — {}", resp.statusCode(), resp.body());
	       return ok;
	   } catch (Exception e) {
	       log.error("arm-buffer HTTP call failed — is nqTicker open-buffer running on {}?", nqTickerUrl, e);
	       return false;
	   }
	}

	private void checkAndPromoteRolloverSymbol() {
	   weeklySymbolService.checkAndPromoteRolloverSymbol();
	}

	public boolean handleRollOver(String signalPrice) {
	   rollOverService.rollOver(signalPrice);
	   return true;
	}
}
