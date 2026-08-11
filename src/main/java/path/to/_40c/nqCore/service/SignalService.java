package path.to._40c.nqCore.service;

import static path.to._40c.nqCore.util.Constants.CE;
import static path.to._40c.nqCore.util.Constants.LIVE;
import static path.to._40c.nqCore.util.Constants.LONG_MONTHLY;
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

import path.to._40c.nqCore.controller.SignalController.Action;
import path.to._40c.nqCore.controller.SignalController.Signal;
import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.repo.PositionRepository;
import path.to._40c.nqCore.util.LegScope;

/**
 * Fans every accepted signal out to the two execution books, SYNTH_WEEKLY first then
 * LONG_MONTHLY, strictly sequentially (SQLite write contention) and error-isolated: an
 * exception inside one book's execution is logged and swallowed so it can never block
 * the other book. Book toggles gate NEW positions only — a disabled book with an open
 * position still receives exits and flip-closes until its natural close (then dormant).
 *
 * One POSITION per signal: the fan-out creates a single Position and passes the same
 * instance to both books, each appending its own legs. Post-trade enrichment
 * (afterOpen/afterClose) runs ONCE per row at the END of the fan-out — never inside a
 * book branch, where the async enrichment would race the other book's save of the same
 * row and could be clobbered by a stale in-memory copy.
 */
@Service
@Slf4j
public class SignalService {

	/** One book's contribution to a signal: the row it closed (if any) and the row it opened onto (if any). */
	private record BookResult(Position closed, Position opened) {
		private static final BookResult NONE = new BookResult(null, null);
	}
	private final PositionOpenService openingService;
	private final PositionCloseService closingService;
	private final PositionRolloverService rollOverService;
	private final PostTradeService postTradeService;
	private final PositionRepository positionRepository;
	private final WeeklySymbolService weeklySymbolService;
	private final BookConfigService bookConfigService;
	private final MonthlySymbolService monthlySymbolService;
	private final MonthlyFlipService monthlyFlipService;

	private final HttpClient httpClient = HttpClient.newHttpClient();

	@Value("${nq.ticker.url:http://localhost:9192}")
	private String nqTickerUrl;

	public SignalService(PositionOpenService openingService, PositionCloseService closingService,
			PositionRolloverService rollOverService, PostTradeService postTradeService,
			PositionRepository positionRepository, WeeklySymbolService weeklySymbolService,
			BookConfigService bookConfigService, MonthlySymbolService monthlySymbolService,
			MonthlyFlipService monthlyFlipService) {
		this.openingService = openingService;
		this.closingService = closingService;
		this.rollOverService = rollOverService;
		this.postTradeService = postTradeService;
		this.positionRepository = positionRepository;
		this.weeklySymbolService = weeklySymbolService;
		this.bookConfigService = bookConfigService;
		this.monthlySymbolService = monthlySymbolService;
		this.monthlyFlipService = monthlyFlipService;
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
	 * close without opening a new one. Both books close legs on the OLD signal's row and
	 * open legs onto the ONE new row created here; post-trade runs once per row at the end.
	 */
	public boolean handleFlip(String signalPrice, String type, Signal signal) {
	   Instant overallStart = Instant.now();
	   weeklySymbolService.syncTradedContract();
	   Position newTrade = new Position(signal);
	   Instant weeklyStart = Instant.now();
	   BookResult weekly = runIsolated(SYNTH_WEEKLY, "flip", () ->
	       bookConfigService.isEnabled(SYNTH_WEEKLY)
	           ? flipWeekly(signalPrice, type, signal, newTrade)
	           : new BookResult(closeOnlyForDisabledBook(SYNTH_WEEKLY, signalPrice, signal), null));
	   long weeklyMs = Duration.between(weeklyStart, Instant.now()).toMillis();
	   Instant monthlyStart = Instant.now();
	   BookResult monthly = runIsolated(LONG_MONTHLY, "flip", () ->
	       bookConfigService.isEnabled(LONG_MONTHLY)
	           ? flipMonthly(signalPrice, type, signal, newTrade)
	           : new BookResult(closeOnlyForDisabledBook(LONG_MONTHLY, signalPrice, signal), null));
	   long monthlyMs = Duration.between(monthlyStart, Instant.now()).toMillis();
	   log.info("[PERFORMANCE] flip OVERALL | SYNTH_WEEKLY={}ms | LONG_MONTHLY={}ms | total={}ms",
	           weeklyMs, monthlyMs, Duration.between(overallStart, Instant.now()).toMillis());
	   runPostTrade(weekly, monthly);
	   return true;
	}

	/**
	 * Entry fan-out. Only enabled books open; each book first flattens its own orphan
	 * legs from an earlier PARTIAL open so the fresh open never stacks on top of
	 * untracked broker positions of that book. Both books append their legs to the ONE
	 * row created here; post-trade runs once per row at the end.
	 */
	public boolean handleTradeOpen(String signalPrice, String type, Signal signal) {
	   Instant overallStart = Instant.now();
	   Position newTrade = new Position(signal);
	   Instant weeklyStart = Instant.now();
	   BookResult weekly = runIsolated(SYNTH_WEEKLY, "open", () -> {
	       if (!bookConfigService.isEnabled(SYNTH_WEEKLY)) {
	           log.info("SYNTH_WEEKLY disabled — skipping open");
	           return BookResult.NONE;
	       }
	       Instant start = Instant.now();
	       Position orphanClosed = closingService.closeWeeklyOrphanIfAny(signalPrice, signal);
	       Position liveTrade = openingService.openWeeklyTrade(signalPrice, type, newTrade);
	       log.info("[PERFORMANCE] open SYNTH_WEEKLY | exec={}ms", Duration.between(start, Instant.now()).toMillis());
	       return new BookResult(orphanClosed, liveTrade);
	   });
	   long weeklyMs = Duration.between(weeklyStart, Instant.now()).toMillis();
	   Instant monthlyStart = Instant.now();
	   BookResult monthly = runIsolated(LONG_MONTHLY, "open", () -> {
	       if (!bookConfigService.isEnabled(LONG_MONTHLY)) {
	           log.info("LONG_MONTHLY disabled — skipping open");
	           return BookResult.NONE;
	       }
	       Instant start = Instant.now();
	       monthlySymbolService.syncTradedContract();
	       Position orphanClosed = closingService.closeMonthlyOrphanIfAny(signalPrice, signal);
	       Position liveTrade = openingService.openMonthlyTrade(signalPrice, type, newTrade);
	       log.info("[PERFORMANCE] open LONG_MONTHLY | exec={}ms", Duration.between(start, Instant.now()).toMillis());
	       return new BookResult(orphanClosed, liveTrade);
	   });
	   long monthlyMs = Duration.between(monthlyStart, Instant.now()).toMillis();
	   log.info("[PERFORMANCE] open OVERALL | SYNTH_WEEKLY={}ms | LONG_MONTHLY={}ms | total={}ms",
	           weeklyMs, monthlyMs, Duration.between(overallStart, Instant.now()).toMillis());
	   runPostTrade(weekly, monthly);
	   return true;
	}

	/**
	 * Close fan-out. Closes run regardless of the toggle (a disabled book's open position
	 * is still managed to natural close). A 9:15 AM longExit is delegated WHOLE — both
	 * books — to nQTicker's open buffer: nothing closes at signal time, the signal's row
	 * stays fully LIVE, and the /api/execute-close callback runs the same both-book
	 * fan-out with nQTicker's live price, so both books record the SAME exit spot
	 * (the AFL 9:15 bar price is stale by the time the exit actually trades). If arming
	 * the buffer fails, both books close inline on the signal price immediately.
	 */
	public boolean handleTradeClose(String signalPrice, String type, Signal signal) {
	   Instant start = Instant.now();
	   if (Action.LONG_EXIT.getValue().equals(signal.action) && isOpenBufferTime()) {
	       if (armNqTickerBuffer(signalPrice)) {
	           log.info("9:15 AM long exit delegated to nQTicker open buffer for both books | openPrice={} | {}ms",
	                   signalPrice, Duration.between(start, Instant.now()).toMillis());
	           return true;
	       }
	       log.warn("nQTicker arm-buffer call failed — closing both books immediately");
	   }
	   closeBothBooks(signalPrice, signal);
	   return true;
	}

	/**
	 * The both-book close fan-out shared by the signal path and the open-buffer callback:
	 * weekly then monthly, error-isolated. Rollover-symbol promotion runs after the order
	 * legs and before the once-per-row post-trade pass to avoid SQLite BUSY from
	 * concurrent writes.
	 */
	private void closeBothBooks(String signalPrice, Signal signal) {
	   Instant start = Instant.now();
	   Position weeklyClosed = runIsolated(SYNTH_WEEKLY, "close", () ->
	           closingService.closeWeeklyTrade(signalPrice, signal, true));
	   long weeklyMs = Duration.between(start, Instant.now()).toMillis();
	   log.info("[PERFORMANCE] close SYNTH_WEEKLY | exec={}ms", weeklyMs);
	   Instant monthlyStart = Instant.now();
	   Position monthlyClosed = runIsolated(LONG_MONTHLY, "close", () ->
	           closingService.closeMonthlyTrade(signalPrice, signal, true));
	   long monthlyMs = Duration.between(monthlyStart, Instant.now()).toMillis();
	   log.info("[PERFORMANCE] close LONG_MONTHLY | exec={}ms", monthlyMs);
	   log.info("[PERFORMANCE] close OVERALL | SYNTH_WEEKLY={}ms | LONG_MONTHLY={}ms | total={}ms",
	           weeklyMs, monthlyMs, Duration.between(start, Instant.now()).toMillis());
	   weeklySymbolService.syncTradedContract();
	   runPostTrade(new BookResult(weeklyClosed, null), new BookResult(monthlyClosed, null));
	}

	/**
	 * The once-per-row post-trade pass, run at the END of every fan-out: afterClose for each
	 * DISTINCT closed row (both books normally closed legs on the same signal's row — it must
	 * be enriched and accounted exactly once), then afterOpen for each distinct opened row.
	 * PostTradeService re-fetches by id, so a stale earlier-book copy of a shared row can
	 * never clobber the later book's writes.
	 */
	private void runPostTrade(BookResult weekly, BookResult monthly) {
	   java.util.LinkedHashMap<Long, Position> closed = new java.util.LinkedHashMap<>();
	   java.util.LinkedHashMap<Long, Position> opened = new java.util.LinkedHashMap<>();
	   for (BookResult r : java.util.Arrays.asList(weekly, monthly)) {
	       if (r == null) continue;
	       if (r.closed() != null && r.closed().getId() != null) closed.put(r.closed().getId(), r.closed());
	       if (r.opened() != null && r.opened().getId() != null) opened.put(r.opened().getId(), r.opened());
	   }
	   closed.values().forEach(postTradeService::afterClose);
	   opened.values().forEach(postTradeService::afterOpen);
	}

	/**
	 * Called by /api/execute-close — the nQTicker open-buffer callback. Runs the same
	 * both-book close fan-out as handleTradeClose, so BOTH books exit on nQTicker's
	 * live price rather than the stale 9:15 bar signal price.
	 */
	public void executeCloseImmediate(String signalPrice) {
	   Instant start = Instant.now();
	   Signal signal = new Signal("open-buffer", Action.LONG_EXIT.getValue(), CE, "", signalPrice);
	   closeBothBooks(signalPrice, signal);
	   log.info("execute-close completed in {}ms", Duration.between(start, Instant.now()).toMillis());
	}

	/**
	 * SYNTH_WEEKLY flip: open prep runs concurrently with the close (prep ~100ms, close
	 * ~400-600ms) so the future is ready by the time we join(). A close that leaves a weekly
	 * leg PENDING_CLOSE (its order still working at the broker) is settled before the opposite
	 * entry is placed — closeWeeklyOrphanIfAny first resolves the pending (cancelling the
	 * working order) and then flattens whatever the broker still holds, so the new entry can
	 * never stack on top of an unconfirmed close of the same strike. The new weekly legs land
	 * on the signal's shared row (newTrade); post-trade runs at the fan-out's end.
	 */
	private BookResult flipWeekly(String signalPrice, String type, Signal signal, Position newTrade) {
	   Instant start = Instant.now();
	   CompletableFuture<PositionOpenService.OpenPrep> openPrepFuture =
	       CompletableFuture.supplyAsync(() -> openingService.prepareWeeklyOpen(signalPrice, type, newTrade));
	   Position closedTrade = closingService.closeWeeklyTrade(signalPrice, signal, false);
	   if (closedTrade != null && LegScope.hasPendingCloseLegs(closedTrade, SYNTH_WEEKLY)) {
	       log.warn("flip: weekly close on trade id={} left a PENDING_CLOSE leg — settling it before the opposite entry", closedTrade.getId());
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
	       ? openingService.openTrade(signalPrice, type, newTrade, prep)
	       : openingService.openWeeklyTrade(signalPrice, type, newTrade);
	   long openMs = Duration.between(closeEnd, Instant.now()).toMillis();
	   log.info("[PERFORMANCE] flip SYNTH_WEEKLY | close={}ms | open={}ms | total={}ms", closeMs, openMs, closeMs + openMs);
	   return new BookResult(closedTrade, liveTrade);
	}

	/**
	 * LONG_MONTHLY flip: sequential close-then-open — a single bought leg's build+quote
	 * is cheap, so the weekly path's async prep is not worth the moving parts here.
	 * Same PENDING_CLOSE settle-before-re-entry rule as the weekly flip; the new monthly
	 * leg lands on the signal's shared row (newTrade).
	 *
	 * When the interleaved flip is available (its flag + live patient mode,
	 * PATIENT_EXECUTION_PLAN.md §4), MonthlyFlipService replaces this body with the sliced
	 * close-confirm-open loop; a null outcome (unexpected position shape) falls back here.
	 */
	private BookResult flipMonthly(String signalPrice, String type, Signal signal, Position newTrade) {
	   Instant start = Instant.now();
	   monthlySymbolService.syncTradedContract();
	   if (monthlyFlipService.interleaveAvailable()) {
	       MonthlyFlipService.FlipOutcome outcome = monthlyFlipService.flip(signalPrice, type, signal, newTrade);
	       if (outcome != null) {
	           log.info("[PERFORMANCE] flip LONG_MONTHLY (interleaved) | total={}ms",
	                   Duration.between(start, Instant.now()).toMillis());
	           return new BookResult(outcome.closed(), outcome.opened());
	       }
	       log.warn("interleaved flip declined this position shape — legacy monthly flip path");
	   }
	   Position closedTrade = closingService.closeMonthlyTrade(signalPrice, signal, false);
	   if (closedTrade != null && LegScope.hasPendingCloseLegs(closedTrade, LONG_MONTHLY)) {
	       log.warn("flip: monthly close on trade id={} left a PENDING_CLOSE leg — settling it before the opposite entry", closedTrade.getId());
	       closedTrade = closingService.closeMonthlyOrphanIfAny(signalPrice, signal);
	   }
	   Instant closeEnd = Instant.now();
	   Position liveTrade = openingService.openMonthlyTrade(signalPrice, type, newTrade);
	   log.info("[PERFORMANCE] flip LONG_MONTHLY | close={}ms | open={}ms",
	           Duration.between(start, closeEnd).toMillis(), Duration.between(closeEnd, Instant.now()).toMillis());
	   return new BookResult(closedTrade, liveTrade);
	}

	/** Disabled-book flip semantics: close the book's legs (natural close), never open. */
	private Position closeOnlyForDisabledBook(String book, String signalPrice, Signal signal) {
	   Position closed = SYNTH_WEEKLY.equals(book)
	       ? closingService.closeWeeklyTrade(signalPrice, signal, true)
	       : closingService.closeMonthlyTrade(signalPrice, signal, true);
	   if (closed != null) {
	       log.info("{} disabled — flip expressed as close-only; trade id={} legs managed to natural close, book now dormant",
	               book, closed.getId());
	   }
	   return closed;
	}

	/**
	 * Per-book error isolation: an exception in one book's execution is logged and
	 * swallowed (returning null) so it can never block the other book or reject the
	 * signal stream. Books run strictly sequentially — this is a straight call, not
	 * an async hop.
	 */
	private <T> T runIsolated(String book, String action, Supplier<T> task) {
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

	/**
	 * 14:47 weekly-roll trigger — mirror of handleMonthlyRollOver: syncs the traded
	 * weekly contract first (date-gated promotion, so the symbol row advances on expiry
	 * day even if the position roll fails and the trade stream continues on the new
	 * contract), then rolls the SYNTH_WEEKLY book's position onto it (sell in-hand, buy
	 * current ATM on the current contract). Monthly book untouched. Failures are logged,
	 * never thrown — a broken roll trigger must not take the endpoint down.
	 */
	public boolean handleWeeklyRollOver(String signalPrice) {
	   Instant start = Instant.now();
	   try {
	       weeklySymbolService.syncTradedContract();
	       rollOverService.rollOverWeekly(signalPrice);
	   } catch (Exception e) {
	       log.error("[BOOK-ISOLATED] SYNTH_WEEKLY rollover failed: {}", e.getMessage(), e);
	       return false;
	   }
	   log.info("[PERFORMANCE] rollover SYNTH_WEEKLY trigger | total={}ms", Duration.between(start, Instant.now()).toMillis());
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
