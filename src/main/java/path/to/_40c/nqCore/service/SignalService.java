package path.to._40c.nqCore.service;

import static path.to._40c.nqCore.util.Constants.LIVE;
import static path.to._40c.nqCore.util.Constants.ZONE_ID;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import path.to._40c.nqCore.controller.SignalController.Signal;
import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.repo.PositionRepository;

@Service
@Slf4j
public class SignalService {
	private final PositionOpeningService openingService;
	private final PositionClosingService closingService;
	private final PositionRolloverService rollOverService;
	private final PostTradeService postTradeService;
	private final PositionRepository positionRepository;
	private final WeeklySymbolService weeklySymbolService;
	private final NqTickerClient nqTickerClient;

	/**
	 * Strategies whose 9:15 longExit was delegated to nqTicker's open buffer and are awaiting
	 * the /api/execute-close callback. The callback carries no strategy, so this set is the
	 * only record of WHOSE positions it must close. In-memory by design: if the app restarts
	 * between arm and callback the set is lost and the callback falls back to the legacy
	 * any-strategy close — logged loudly when that happens.
	 */
	private final Set<String> pendingOpenBufferStrategies = ConcurrentHashMap.newKeySet();

	/** IST clock; replaceable in tests to pin the 9:15 open-buffer window. */
	private Clock clock = Clock.system(ZoneId.of(ZONE_ID));

	public SignalService(PositionOpeningService openingService, PositionClosingService closingService,
			PositionRolloverService rollOverService, PostTradeService postTradeService,
			PositionRepository positionRepository, WeeklySymbolService weeklySymbolService,
			NqTickerClient nqTickerClient) {
		this.openingService = openingService;
		this.closingService = closingService;
		this.rollOverService = rollOverService;
		this.postTradeService = postTradeService;
		this.positionRepository = positionRepository;
		this.weeklySymbolService = weeklySymbolService;
		this.nqTickerClient = nqTickerClient;
	}

	void setClockForTesting(Clock clock) {
		this.clock = clock;
	}

	/**
	 * Last trade per strategy for seeding the sequence validator on restart: for each strategy
	 * name in position history, the LIVE position if one exists, else the most recent position.
	 * The old single-trade variant seeded only one strategy, leaving every other strategy's
	 * sequence validator blind after a restart.
	 */
	public List<Position> getLastTradePerStrategy() {
	    List<Position> lastTrades = new ArrayList<>();
	    for (String name : positionRepository.findDistinctStrategyNames()) {
	        if (name == null || name.isBlank()) continue;
	        Position live = positionRepository.findFirstByStrategyNameAndStatusOrderByIdDesc(name, LIVE);
	        Position last = live != null ? live : positionRepository.findFirstByStrategyNameOrderByIdDesc(name);
	        if (last != null) {
	            lastTrades.add(last);
	        }
	    }
	    return lastTrades;
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

	/**
	 * Entry handler. Flattens any orphan legs left by an earlier PARTIAL open before the new
	 * position is placed, so the fresh open never stacks on top of untracked broker positions.
	 */
	public boolean handleTradeOpen(String signalPrice, String type, Signal signal) {
	   Instant start = Instant.now();
	   Position orphanClosed = closingService.closeOrphanIfAny(signalPrice, signal);
	   Position trade = new Position(signal);
	   Position liveTrade = openingService.openTrade(signalPrice, type, trade);
	   log.info("[PERFORMANCE] open | exec={}ms", Duration.between(start, Instant.now()).toMillis());
	   postTradeService.afterOpen(liveTrade);
	   if (orphanClosed != null) {
	       postTradeService.afterClose(orphanClosed);
	   }
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
	       if (nqTickerClient.armBuffer(signalPrice)) {
	           pendingOpenBufferStrategies.add(signal.strategyName);
	           log.info("9:15 AM long exit delegated to nQTicker open buffer | strategy={} | openPrice={} | pending={} | {}ms",
	                   signal.strategyName, signalPrice, pendingOpenBufferStrategies,
	                   Duration.between(start, Instant.now()).toMillis());
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

	/**
	 * Called by /api/execute-close — nQTicker open-buffer callback. The callback carries no
	 * strategy, so the pending set recorded at delegation time decides whose positions close:
	 * one close per delegated strategy, each scoped by its own Signal. Sequence state needs no
	 * update here — it already advanced when the original longExit was accepted at arm time.
	 * An empty pending set (restart between arm and callback, or a stray callback) falls back
	 * to the legacy any-strategy close so a real broker position is never left hanging.
	 */
	public void executeCloseImmediate(String signalPrice) {
	   Instant start = Instant.now();
	   List<String> strategies = new ArrayList<>(new LinkedHashSet<>(pendingOpenBufferStrategies));
	   pendingOpenBufferStrategies.clear();
	   if (strategies.isEmpty()) {
	       log.warn("execute-close with NO pending open-buffer strategies (restart or stray callback) — falling back to any-strategy close");
	       Signal signal = new Signal("open-buffer", "longExit", "CE", "", signalPrice);
	       Position closedTrade = closingService.closeTrade(signalPrice, signal, true);
	       checkAndPromoteRolloverSymbol();
	       postTradeService.afterClose(closedTrade);
	   } else {
	       for (String strategyName : strategies) {
	           Signal signal = new Signal(strategyName, "longExit", "CE", "", signalPrice);
	           Position closedTrade = closingService.closeTrade(signalPrice, signal, true);
	           checkAndPromoteRolloverSymbol();
	           postTradeService.afterClose(closedTrade);
	       }
	   }
	   log.info("execute-close completed for strategies={} in {}ms",
	           strategies.isEmpty() ? "[fallback]" : strategies, Duration.between(start, Instant.now()).toMillis());
	}

	private boolean isOpenBufferTime() {
	   LocalTime now = LocalTime.now(clock);
	   return now.getHour() == 9 && now.getMinute() == 15;
	}

	private void checkAndPromoteRolloverSymbol() {
	   weeklySymbolService.checkAndPromoteRolloverSymbol();
	}

	/** Rolls every live position; true only when all rolled (or none live). */
	public boolean handleRollOver(String signalPrice) {
	   return rollOverService.rollOver(signalPrice);
	}
}
