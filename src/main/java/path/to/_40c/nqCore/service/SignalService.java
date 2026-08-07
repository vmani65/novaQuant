package path.to._40c.nqCore.service;

import static path.to._40c.nqCore.util.Constants.LIVE;
import static path.to._40c.nqCore.util.Constants.LONG_MONTHLY;
import static path.to._40c.nqCore.util.Constants.PENDING_CLOSE;
import static path.to._40c.nqCore.util.Constants.SYNTH_WEEKLY;
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
import java.util.function.Supplier;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import path.to._40c.nqCore.controller.SignalController.Signal;
import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.repo.PositionRepository;

/**
 * Fans every accepted signal out to the two execution books, SYNTH_WEEKLY first then
 * LONG_MONTHLY, strictly sequentially (SQLite write contention) and error-isolated: an
 * exception inside one book's execution is logged and swallowed so it can never block
 * the other book. Book toggles gate NEW positions only — a disabled book with an open
 * position still receives exits and flip-closes until its natural close (then dormant).
 */
@Service
@Slf4j
public class SignalService {
	private final PositionOpenService openingService;
	private final PositionCloseService closingService;
	private final PositionRolloverService rollOverService;
	private final PostTradeService postTradeService;
	private final PositionRepository positionRepository;
	private final WeeklySymbolService weeklySymbolService;
	private final BookConfigService bookConfigService;
	private final MonthlySymbolService monthlySymbolService;

	private final HttpClient httpClient = HttpClient.newHttpClient();

	@Value("${nq.ticker.url:http://localhost:9192}")
	private String nqTickerUrl;

	public SignalService(PositionOpenService openingService, PositionCloseService closingService,
			PositionRolloverService rollOverService, PostTradeService postTradeService,
			PositionRepository positionRepository, WeeklySymbolService weeklySymbolService,
			BookConfigService bookConfigService, MonthlySymbolService monthlySymbolService) {
		this.openingService = openingService;
		this.closingService = closingService;
		this.rollOverService = rollOverService;
		this.postTradeService = postTradeService;
		this.positionRepository = positionRepository;
		this.weeklySymbolService = weeklySymbolService;
		this.bookConfigService = bookConfigService;
		this.monthlySymbolService = monthlySymbolService;
	}

	/**
	 * Latest LIVE position across ALL books, falling back to the latest row overall.
	 * Used only to seed SignalController's per-strategy last-signal state at startup:
	 * both books execute the same signal stream, so the newest row of either book
	 * carries the newest lastSignalAction/lastSignalLeg for the strategy.
	 */
	public Position getLastTrade() {
	    Position liveTrade = positionRepository.findFirstByStatusOrderByIdDesc(LIVE);
	    if (liveTrade != null) {
	        return liveTrade;
	    }
	    return positionRepository.findFirstByOrderByIdDesc();
	}

	/**
	 * Flip fan-out. An enabled book flips (close current + open opposite); a disabled
	 * book expresses the flip as close-only, managing any open position to its natural
	 * close without opening a new one.
	 */
	public boolean handleFlip(String signalPrice, String type, Signal signal) {
	   Instant overallStart = Instant.now();
	   checkAndPromoteRolloverSymbol();
	   Instant weeklyStart = Instant.now();
	   runIsolated(SYNTH_WEEKLY, "flip", () -> {
	       if (bookConfigService.isEnabled(SYNTH_WEEKLY)) flipWeekly(signalPrice, type, signal);
	       else closeOnlyForDisabledBook(SYNTH_WEEKLY, signalPrice, signal);
	       return null;
	   });
	   long weeklyMs = Duration.between(weeklyStart, Instant.now()).toMillis();
	   Instant monthlyStart = Instant.now();
	   runIsolated(LONG_MONTHLY, "flip", () -> {
	       if (bookConfigService.isEnabled(LONG_MONTHLY)) flipMonthly(signalPrice, type, signal);
	       else closeOnlyForDisabledBook(LONG_MONTHLY, signalPrice, signal);
	       return null;
	   });
	   long monthlyMs = Duration.between(monthlyStart, Instant.now()).toMillis();
	   log.info("[PERFORMANCE] flip OVERALL | SYNTH_WEEKLY={}ms | LONG_MONTHLY={}ms | total={}ms",
	           weeklyMs, monthlyMs, Duration.between(overallStart, Instant.now()).toMillis());
	   return true;
	}

	/**
	 * Entry fan-out. Only enabled books open; each book first flattens its own orphan
	 * legs from an earlier PARTIAL open so the fresh open never stacks on top of
	 * untracked broker positions of that book.
	 */
	public boolean handleTradeOpen(String signalPrice, String type, Signal signal) {
	   Instant overallStart = Instant.now();
	   Instant weeklyStart = Instant.now();
	   runIsolated(SYNTH_WEEKLY, "open", () -> {
	       if (!bookConfigService.isEnabled(SYNTH_WEEKLY)) {
	           log.info("SYNTH_WEEKLY disabled — skipping open");
	           return null;
	       }
	       Instant start = Instant.now();
	       Position orphanClosed = closingService.closeWeeklyOrphanIfAny(signalPrice, signal);
	       Position liveTrade = openingService.openWeeklyTrade(signalPrice, type, new Position(signal));
	       log.info("[PERFORMANCE] open SYNTH_WEEKLY | exec={}ms", Duration.between(start, Instant.now()).toMillis());
	       postTradeService.afterOpen(liveTrade);
	       if (orphanClosed != null) {
	           postTradeService.afterClose(orphanClosed);
	       }
	       return null;
	   });
	   long weeklyMs = Duration.between(weeklyStart, Instant.now()).toMillis();
	   Instant monthlyStart = Instant.now();
	   runIsolated(LONG_MONTHLY, "open", () -> {
	       if (!bookConfigService.isEnabled(LONG_MONTHLY)) {
	           log.info("LONG_MONTHLY disabled — skipping open");
	           return null;
	       }
	       Instant start = Instant.now();
	       monthlySymbolService.syncTradedContract();
	       Position orphanClosed = closingService.closeMonthlyOrphanIfAny(signalPrice, signal);
	       Position liveTrade = openingService.openMonthlyTrade(signalPrice, type, new Position(signal));
	       log.info("[PERFORMANCE] open LONG_MONTHLY | exec={}ms", Duration.between(start, Instant.now()).toMillis());
	       postTradeService.afterOpen(liveTrade);
	       if (orphanClosed != null) {
	           postTradeService.afterClose(orphanClosed);
	       }
	       return null;
	   });
	   long monthlyMs = Duration.between(monthlyStart, Instant.now()).toMillis();
	   log.info("[PERFORMANCE] open OVERALL | SYNTH_WEEKLY={}ms | LONG_MONTHLY={}ms | total={}ms",
	           weeklyMs, monthlyMs, Duration.between(overallStart, Instant.now()).toMillis());
	   return true;
	}

	/**
	 * Close fan-out. Closes run regardless of the toggle (a disabled book's open position
	 * is still managed to natural close). The 9:15 AM longExit delegation to nQTicker's
	 * open buffer is WEEKLY-ONLY: LONG_MONTHLY always closes inline at signal time, while
	 * the weekly close may be deferred to the /api/execute-close callback. Rollover-symbol
	 * promotion runs after the order legs and before afterClose to avoid SQLite BUSY from
	 * concurrent writes (same ordering as the single-book version).
	 */
	public boolean handleTradeClose(String signalPrice, String type, Signal signal) {
	   Instant start = Instant.now();
	   Position weeklyClosed = runIsolated(SYNTH_WEEKLY, "close", () -> closeWeeklyForSignal(signalPrice, signal, start));
	   long weeklyMs = Duration.between(start, Instant.now()).toMillis();
	   log.info("[PERFORMANCE] close SYNTH_WEEKLY | exec={}ms", weeklyMs);
	   Instant monthlyStart = Instant.now();
	   Position monthlyClosed = runIsolated(LONG_MONTHLY, "close", () ->
	           closingService.closeMonthlyTrade(signalPrice, signal, true));
	   long monthlyMs = Duration.between(monthlyStart, Instant.now()).toMillis();
	   log.info("[PERFORMANCE] close LONG_MONTHLY | exec={}ms", monthlyMs);
	   log.info("[PERFORMANCE] close OVERALL | SYNTH_WEEKLY={}ms | LONG_MONTHLY={}ms | total={}ms",
	           weeklyMs, monthlyMs, Duration.between(start, Instant.now()).toMillis());
	   checkAndPromoteRolloverSymbol();
	   postTradeService.afterClose(weeklyClosed);
	   postTradeService.afterClose(monthlyClosed);
	   return true;
	}

	/** Called by /api/execute-close — nQTicker open-buffer callback (weekly-only delegation). */
	public void executeCloseImmediate(String signalPrice) {
	   Instant start = Instant.now();
	   Signal signal = new Signal("open-buffer", "longExit", "CE", "", signalPrice);
	   Position closedTrade = closingService.closeWeeklyTrade(signalPrice, signal, true);
	   log.info("execute-close completed in {}ms", Duration.between(start, Instant.now()).toMillis());
	   checkAndPromoteRolloverSymbol();
	   postTradeService.afterClose(closedTrade);
	}

	/**
	 * SYNTH_WEEKLY flip: open prep runs concurrently with the close (prep ~100ms, close
	 * ~400-600ms) so the future is ready by the time we join(). A close that ends
	 * PENDING_CLOSE (its order still working at the broker) is settled before the opposite
	 * entry is placed — closeWeeklyOrphanIfAny first resolves the pending (cancelling the
	 * working order) and then flattens whatever the broker still holds, so the new entry can
	 * never stack on top of an unconfirmed close of the same strike.
	 */
	private void flipWeekly(String signalPrice, String type, Signal signal) {
	   Instant start = Instant.now();
	   Position trade = new Position(signal);
	   CompletableFuture<PositionOpenService.OpenPrep> openPrepFuture =
	       CompletableFuture.supplyAsync(() -> openingService.prepareWeeklyOpen(signalPrice, type, trade));
	   Position closedTrade = closingService.closeWeeklyTrade(signalPrice, signal, false);
	   if (closedTrade != null && PENDING_CLOSE.equals(closedTrade.getStatus())) {
	       log.warn("flip: close of trade id={} is PENDING_CLOSE — settling it before the opposite entry", closedTrade.getId());
	       closedTrade = closingService.closeWeeklyOrphanIfAny(signalPrice, signal);
	   }
	   Instant closeEnd = Instant.now();
	   long closeMs = Duration.between(start, closeEnd).toMillis();
	   PositionOpenService.OpenPrep prep = null;
	   try {
	       prep = openPrepFuture.join();
	   } catch (Exception e) {
	       log.error("Open-leg pre-fetch failed — falling back to inline open: {}", e.getMessage());
	   }
	   Position liveTrade = (prep != null)
	       ? openingService.openTrade(signalPrice, type, trade, prep)
	       : openingService.openWeeklyTrade(signalPrice, type, trade);
	   long openMs = Duration.between(closeEnd, Instant.now()).toMillis();
	   log.info("[PERFORMANCE] flip SYNTH_WEEKLY | close={}ms | open={}ms | total={}ms", closeMs, openMs, closeMs + openMs);
	   postTradeService.afterOpen(liveTrade);
	   postTradeService.afterClose(closedTrade);
	}

	/**
	 * LONG_MONTHLY flip: sequential close-then-open — a single bought leg's build+quote
	 * is cheap, so the weekly path's async prep is not worth the moving parts here.
	 * Same PENDING_CLOSE settle-before-re-entry rule as the weekly flip.
	 */
	private void flipMonthly(String signalPrice, String type, Signal signal) {
	   Instant start = Instant.now();
	   monthlySymbolService.syncTradedContract();
	   Position closedTrade = closingService.closeMonthlyTrade(signalPrice, signal, false);
	   if (closedTrade != null && PENDING_CLOSE.equals(closedTrade.getStatus())) {
	       log.warn("flip: monthly close of trade id={} is PENDING_CLOSE — settling it before the opposite entry", closedTrade.getId());
	       closedTrade = closingService.closeMonthlyOrphanIfAny(signalPrice, signal);
	   }
	   Instant closeEnd = Instant.now();
	   Position liveTrade = openingService.openMonthlyTrade(signalPrice, type, new Position(signal));
	   log.info("[PERFORMANCE] flip LONG_MONTHLY | close={}ms | open={}ms",
	           Duration.between(start, closeEnd).toMillis(), Duration.between(closeEnd, Instant.now()).toMillis());
	   postTradeService.afterOpen(liveTrade);
	   postTradeService.afterClose(closedTrade);
	}

	/** Disabled-book flip semantics: close any open position (natural close), never open. */
	private void closeOnlyForDisabledBook(String book, String signalPrice, Signal signal) {
	   Position closed = SYNTH_WEEKLY.equals(book)
	       ? closingService.closeWeeklyTrade(signalPrice, signal, true)
	       : closingService.closeMonthlyTrade(signalPrice, signal, true);
	   if (closed != null) {
	       log.info("{} disabled — flip expressed as close-only; trade id={} managed to natural close, book now dormant",
	               book, closed.getId());
	       postTradeService.afterClose(closed);
	   }
	}

	/** Weekly close with the 9:15 open-buffer delegation. Returns null when delegated. */
	private Position closeWeeklyForSignal(String signalPrice, Signal signal, Instant start) {
	   if ("longExit".equals(signal.action) && isOpenBufferTime()) {
	       if (armNqTickerBuffer(signalPrice)) {
	           log.info("9:15 AM long exit delegated to nQTicker open buffer | openPrice={} | {}ms",
	                   signalPrice, Duration.between(start, Instant.now()).toMillis());
	           return null;
	       }
	       log.warn("nQTicker arm-buffer call failed — falling back to immediate close");
	   }
	   return closingService.closeWeeklyTrade(signalPrice, signal, true);
	}

	/**
	 * Per-book error isolation: an exception in one book's execution is logged and
	 * swallowed (returning null) so it can never block the other book or reject the
	 * signal stream. Books run strictly sequentially — this is a straight call, not
	 * an async hop.
	 */
	private Position runIsolated(String book, String action, Supplier<Position> task) {
	   try {
	       return task.get();
	   } catch (Exception e) {
	       log.error("[BOOK-ISOLATED] {} {} failed — the other book is unaffected: {}", book, action, e.getMessage(), e);
	       return null;
	   }
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

	/** 14:47 expiry-day trigger — rolls the SYNTH_WEEKLY book only (hard fence). */
	public boolean handleRollOver(String signalPrice) {
	   rollOverService.rollOverWeekly(signalPrice);
	   return true;
	}

	/**
	 * nQTicker monthly-roll trigger — syncs the traded monthly contract to the DTE rule,
	 * then rolls the LONG_MONTHLY book's position onto it (sell in-hand, buy current ATM
	 * on the latest contract). Weekly book untouched. Failures are logged, never thrown —
	 * a broken roll trigger must not take the endpoint down.
	 */
	public boolean handleMonthlyRollOver(String signalPrice) {
	   Instant start = Instant.now();
	   try {
	       monthlySymbolService.syncTradedContract();
	       rollOverService.rollOverMonthly(signalPrice);
	   } catch (Exception e) {
	       log.error("[BOOK-ISOLATED] LONG_MONTHLY rollover failed: {}", e.getMessage(), e);
	       return false;
	   }
	   log.info("[PERFORMANCE] rollover LONG_MONTHLY trigger | total={}ms", Duration.between(start, Instant.now()).toMillis());
	   return true;
	}
}
