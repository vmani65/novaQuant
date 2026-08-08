package path.to._40c.nqCore.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.zerodhatech.models.Quote;

import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.entity.WeeklyLeg;
import path.to._40c.nqCore.pojo.LegOrder;
import path.to._40c.nqCore.repo.PositionRepository;
import path.to._40c.nqCore.util.ComputeUtil;
import path.to._40c.nqCore.util.PositionUtil;
import path.to._40c.nqCore.util.PositionUtil.ExecResult;

import static path.to._40c.nqCore.util.Constants.*;

@Service
@Slf4j
public class PositionOpenService {
    private final PositionRepository positionRepository;
    private final PositionUtil positionUtil;
    private final ComputeUtil computeUtil;

    public PositionOpenService(PositionRepository positionRepository, PositionUtil positionUtil, ComputeUtil computeUtil) {
        this.positionRepository = positionRepository;
        this.positionUtil = positionUtil;
        this.computeUtil = computeUtil;
    }

    /**
     * Instruments + quotes (incl. depth + LTP) pre-fetched by the flip path's async task
     * while close orders execute. One getQuote round-trip covers both LTP-for-intent and
     * bid/ask for the LIMIT walk in placeGraduatedLimit (D₁).
     */
    public record OpenPrep(List<LegOrder> pojos, Map<String, Quote> quotes) {}

    /**
     * Called by the weekly flip path's CompletableFuture concurrently with the weekly close.
     * Builds instruments and fetches quotes for the open leg so both are ready
     * the moment the close completes (~100ms work vs ~500ms close execution).
     */
    public OpenPrep prepareWeeklyOpen(String signalPrice, String type, Position trade) {
        stampForOpen(trade, signalPrice, type, SYNTH_WEEKLY);
        List<LegOrder> pojos = computeUtil.buildWeeklyInstrument(signalPrice, trade, false);
        String[] symbols = pojos.stream().map(LegOrder::getExchangeSymbol).toArray(String[]::new);
        Map<String, Quote> quotes = positionUtil.getQuote(symbols);
        return new OpenPrep(pojos, quotes);
    }

    /**
     * SYNTH_WEEKLY open — weekly templates + weekly symbol, quotes fetched inline.
     */
    public Position openWeeklyTrade(String signalPrice, String type, Position trade) {
    	stampForOpen(trade, signalPrice, type, SYNTH_WEEKLY);
    	List<LegOrder> legOrder = computeUtil.buildWeeklyInstrument(signalPrice, trade, false);
    	String[] ltpIns = legOrder.stream().map(LegOrder::getExchangeSymbol).toArray(String[]::new);
	    log.debug("OpenTrade ltpIns is: {}", (Object) ltpIns);
    	Map<String, Quote> quotes = positionUtil.getQuote(ltpIns);
    	return placeAndSave(trade, legOrder, quotes);
    }

    /**
     * LONG_MONTHLY open — single bought leg on the monthly contract. No async prep
     * variant: one leg's build+quote is cheap and the monthly flip closes inline.
     * Throws IllegalStateException when the monthly book is unconfigured (fan-out
     * isolates the failure to this book).
     */
    public Position openMonthlyTrade(String signalPrice, String type, Position trade) {
        stampForOpen(trade, signalPrice, type, LONG_MONTHLY);
        List<LegOrder> legOrder = computeUtil.buildMonthlyInstrument(signalPrice, trade);
        String[] ltpIns = legOrder.stream().map(LegOrder::getExchangeSymbol).toArray(String[]::new);
        Map<String, Quote> quotes = positionUtil.getQuote(ltpIns);
        return placeAndSave(trade, legOrder, quotes);
    }

    /**
     * Optimised weekly flip path — skips buildWeeklyInstrument and getQuote since both
     * were pre-computed by prepareWeeklyOpen while the close orders were executing on
     * Zerodha. The book was stamped by prepareWeeklyOpen on the same trade instance.
     */
    public Position openTrade(String signalPrice, String type, Position trade, OpenPrep prep) {
        stampForOpen(trade, signalPrice, type, SYNTH_WEEKLY);
        return placeAndSave(trade, prep.pojos(), prep.quotes());
    }

    /**
     * Monthly analogue of prepareWeeklyOpen, used by the interleaved flip
     * (PATIENT_EXECUTION_PLAN.md §4): builds the target monthly leg and fetches its quote
     * BEFORE any close order is placed, so an unconfigured book or dead quote feed aborts
     * the flip while the held position is still intact. Stamps book/spots/direction on the
     * trade exactly like the weekly prep.
     */
    public OpenPrep prepareMonthlyOpen(String signalPrice, String type, Position trade) {
        stampForOpen(trade, signalPrice, type, LONG_MONTHLY);
        List<LegOrder> pojos = computeUtil.buildMonthlyInstrument(signalPrice, trade);
        String[] symbols = pojos.stream().map(LegOrder::getExchangeSymbol).toArray(String[]::new);
        Map<String, Quote> quotes = positionUtil.getQuote(symbols);
        return new OpenPrep(pojos, quotes);
    }

    /**
     * Persists a position whose entry orders were executed by the caller (the interleaved
     * monthly flip places its own slice orders and hands ONE aggregate ExecResult per leg).
     * Runs the exact same result-recording, materialization and status rules as placeAndSave —
     * trim-to-filled on partials, PENDING_OPEN on possibly-live orders, PARTIAL/FAILED position
     * statuses — so a flip-opened position is indistinguishable from a normally-opened one.
     * Legs without a result entry (keyed by exchange symbol) are treated as never-placed FAILED.
     */
    public Position savePreparedOpen(Position trade, OpenPrep prep, Map<String, ExecResult> resultsByExchangeSymbol) {
        prep.pojos().forEach(w -> {
            ExecResult er = resultsByExchangeSymbol.get(w.getExchangeSymbol());
            if (er != null) {
                recordOpenResult(w, prep.quotes().get(w.getExchangeSymbol()), er);
            } else {
                log.error("savePreparedOpen: no ExecResult for {} — leg will be FAILED", w.getExchangeSymbol());
            }
        });
        return materializeAndSave(trade, prep.pojos(), prep.quotes());
    }

    private void stampForOpen(Position trade, String signalPrice, String type, String book) {
        trade.setBook(book);
        trade.setEntrySpot(Double.valueOf(signalPrice));
        trade.setBaselineSpot(Double.valueOf(signalPrice));
        trade.setDirection(CE.equals(type) ? LONG : SHORT);
    }

    private Position placeAndSave(Position trade, List<LegOrder> legOrder, Map<String, Quote> quotes) {
    	if (quotes.isEmpty()) {
    	    log.error("Quote map is empty — aborting trade open for all instruments");
    	    trade.setLegs(legOrder.stream().map(pojo -> {
    	        WeeklyLeg b = new WeeklyLeg();
    	        b.setInstrument(pojo.getInstrument()); b.setExchangeSymbol(pojo.getExchangeSymbol());
    	        b.setSide(pojo.getSide()); b.setPosition(pojo.getParentPosition());
    	        b.setMoneyness(pojo.getMoneyness()); b.setLots(pojo.getLots());
    	        b.setQuantity(pojo.getLots() * LOT_SIZE); b.setStatus(FAILED);
    	        return b;
    	    }).collect(Collectors.toList()));
    	    trade.setStatus(FAILED);
    	    return positionRepository.save(trade);
    	}
    	List<CompletableFuture<Void>> futs = legOrder.stream()
    	    .map(w -> CompletableFuture.runAsync(() -> {
    	        log.debug("LegOrder to place order is: {}", w);
    	        int totalQty = w.getLots() * LOT_SIZE;
    	        try {
    	            ExecResult er = positionUtil.placeAggressiveOrder(quotes.get(w.getExchangeSymbol()), w.getInstrument(), w.getSide(), totalQty, "ENTRY");
    	            recordOpenResult(w, quotes.get(w.getExchangeSymbol()), er);
    	        } catch (Exception e) {
    	            log.error("Exception placing order for {} ({} qty): {}", w.getInstrument(), totalQty, e.getMessage(), e);
    	        }
    	    }, PositionUtil.LEG_EXEC))
    	    .toList();
    	CompletableFuture.allOf(futs.toArray(new CompletableFuture[0])).join();
    	return materializeAndSave(trade, legOrder, quotes);
    }

    /**
     * Records one leg's execution outcome onto its LegOrder pojo: order ids, filled qty,
     * possibly-still-live flag, spread paid vs the order-time mid, and the fully-filled marker.
     * Extracted from placeAndSave's placement lambda so the interleaved flip can feed an
     * externally-executed aggregate ExecResult through the identical bookkeeping.
     */
    private static void recordOpenResult(LegOrder w, Quote q, ExecResult er) {
        if (er.aggregateOrderIds() != null && !er.aggregateOrderIds().isEmpty()) {
            w.setOpenOrderId(er.aggregateOrderIds());
        }
        w.setOpenFilledQty(er.totalFilled());
        w.setOpenOrderMayBeLive(PositionUtil.closeOrderMayBeLive(er));
        w.setOpenSpreadPaid(PositionUtil.effectiveSpreadPaid(q, w.getSide(), er.weightedAvgFillPrice()));
        if (!er.fullyFilled()) {
            log.error("[ENTRY] {} ({} qty) NOT fully filled: filled={}/{} term={}",
                w.getInstrument(), w.getLots() * LOT_SIZE, er.totalFilled(), er.totalRequested(), er.terminalStatus());
            w.setOpenFullyFilled(false);
        } else {
            w.setOpenFullyFilled(true);
        }
    }

    /**
     * Materializes WeeklyLeg rows from the recorded LegOrder outcomes and resolves per-leg and
     * position statuses (LIVE trim-to-filled / PENDING_OPEN / PARTIAL / FAILED), then saves.
     * Extracted verbatim from placeAndSave's tail; shared by the normal open paths and
     * savePreparedOpen.
     */
    private Position materializeAndSave(Position trade, List<LegOrder> legOrder, Map<String, Quote> quotes) {
    	List<WeeklyLeg> childOrderBook = new ArrayList<>();
    	legOrder.forEach(pojo -> {
    		WeeklyLeg b = new WeeklyLeg();
    		b.setInstrument(pojo.getInstrument());
    		b.setExchangeSymbol(pojo.getExchangeSymbol());
    		b.setSide(pojo.getSide());
    		b.setPosition(pojo.getParentPosition());
    		b.setMoneyness(pojo.getMoneyness());
    		b.setOpenOrderId(pojo.getOpenOrderId());
    		b.setOpenSpreadPaid(pojo.getOpenSpreadPaid());
    		PositionUtil.alertIfMonthlySpreadExcessive(trade.getBook(), pojo.getInstrument(), "ENTRY", pojo.getOpenSpreadPaid());
    		int filledQty = pojo.getOpenFilledQty();
    		boolean mayStillFill = Boolean.TRUE.equals(pojo.getOpenOrderMayBeLive())
    				&& !Boolean.TRUE.equals(pojo.getOpenFullyFilled());
    		if (mayStillFill) {
    			b.setLots(pojo.getLots());
    			b.setQuantity(pojo.getLots() * LOT_SIZE);
    			b.setStatus(PENDING_OPEN);
    			log.error("[ENTRY] {} open order {} still working at broker (filled={}/{}) — leg PENDING_OPEN, "
    					+ "reconciler will settle it from the tradebook",
    				b.getInstrument(), b.getOpenOrderId(), filledQty, b.getQuantity());
    		} else if (filledQty > 0) {
    			b.setLots(filledQty / LOT_SIZE);
    			b.setQuantity(filledQty);
    			b.setStatus(LIVE);
    		} else {
    			b.setLots(pojo.getLots());
    			b.setQuantity(pojo.getLots() * LOT_SIZE);
    			b.setStatus(FAILED);
    		}
    		Quote q = quotes.get(pojo.getExchangeSymbol());
    		if (q != null) {
    			if (BUY.equals(pojo.getSide()))
					b.setBuyIntendedPrice(q.lastPrice);
    			else
					b.setSellIntendedPrice(q.lastPrice);
    		}
    		childOrderBook.add(b);
    	});
    	trade.setLegs(childOrderBook);
    	boolean allFullyFilled = legOrder.stream().allMatch(p -> Boolean.TRUE.equals(p.getOpenFullyFilled()));
    	boolean anyPendingOpen = childOrderBook.stream().anyMatch(l -> PENDING_OPEN.equals(l.getStatus()));
    	boolean anyFilled = legOrder.stream().anyMatch(p -> p.getOpenFilledQty() > 0);
    	if (allFullyFilled) {
    		trade.setStatus(LIVE);
    	} else if (anyPendingOpen) {
    		trade.setStatus(PENDING_OPEN);
            log.error("Position open not confirmed - an entry order is still working at the broker; "
                    + "position PENDING_OPEN, reconciler will settle it from the tradebook");
        } else if (anyFilled) {
        	trade.setStatus(PARTIAL);
            log.error("ORPHAN: position opened PARTIALLY - filled legs will be closed on next signal. Legs: {}",
                trade.getLegs().stream()
                    .map(l -> l.getInstrument() + " " + l.getSide() + " " + l.getStatus() + " qty=" + l.getQuantity())
                    .collect(Collectors.joining(", ")));
        } else {
        	trade.setStatus(FAILED);
            log.error("Position opening operation failed - no legs filled");
        }
    	var liveTrade = positionRepository.save(trade);
    	log.info("Live Position Being Opened: {}", trade);
    	return liveTrade;
    }
}
