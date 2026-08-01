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
public class PositionOpeningService {
    private final PositionRepository positionRepository;
    private final PositionUtil positionUtil;
    private final ComputeUtil computeUtil;
    private final MarginPreCheckService marginPreCheck;

    public PositionOpeningService(PositionRepository positionRepository, PositionUtil positionUtil, ComputeUtil computeUtil,
            MarginPreCheckService marginPreCheck) {
        this.positionRepository = positionRepository;
        this.positionUtil = positionUtil;
        this.computeUtil = computeUtil;
        this.marginPreCheck = marginPreCheck;
    }

    /**
     * Instruments + quotes (incl. depth + LTP) pre-fetched by the flip path's async task
     * while close orders execute. One getQuote round-trip covers both LTP-for-intent and
     * bid/ask for the LIMIT walk in placeGraduatedLimit (D₁).
     */
    public record OpenPrep(List<LegOrder> pojos, Map<String, Quote> quotes) {}

    /**
     * Called by handleFlip's CompletableFuture concurrently with closeTrade.
     * Builds instruments and fetches quotes for the open leg so both are ready
     * the moment the close completes (~100ms work vs ~500ms close execution).
     */
    public OpenPrep prepareOpen(String signalPrice, String type, Position trade) {
        trade.setEntrySpot(Double.valueOf(signalPrice));
        trade.setBaselineSpot(Double.valueOf(signalPrice));
        trade.setDirection(CE.equals(type) ? LONG : SHORT);
        List<LegOrder> pojos = computeUtil.buildInstrument(signalPrice, trade, false);
        String[] symbols = pojos.stream().map(LegOrder::getExchangeSymbol).toArray(String[]::new);
        Map<String, Quote> quotes = positionUtil.getQuote(symbols);
        return new OpenPrep(pojos, quotes);
    }

    /**
     * Standard open path used by handleTradeOpen — builds instruments and fetches quotes inline.
     */
    public Position openTrade(String signalPrice, String type, Position trade) {
    	trade.setEntrySpot(Double.valueOf(signalPrice));
    	trade.setBaselineSpot(Double.valueOf(signalPrice));
    	trade.setDirection(CE.equals(type) ? LONG : SHORT);
    	List<LegOrder> legOrder = computeUtil.buildInstrument(signalPrice, trade, false);
    	String[] ltpIns = legOrder.stream().map(LegOrder::getExchangeSymbol).toArray(String[]::new);
	    log.debug("OpenTrade ltpIns is: {}", (Object) ltpIns);
    	Map<String, Quote> quotes = positionUtil.getQuote(ltpIns);
    	return placeAndSave(trade, legOrder, quotes);
    }

    /**
     * Optimised flip path — skips buildInstrument and getQuote since both were
     * pre-computed by prepareOpen while the close orders were executing on Zerodha.
     */
    public Position openTrade(String signalPrice, String type, Position trade, OpenPrep prep) {
        trade.setEntrySpot(Double.valueOf(signalPrice));
        trade.setBaselineSpot(Double.valueOf(signalPrice));
        trade.setDirection(CE.equals(type) ? LONG : SHORT);
        return placeAndSave(trade, prep.pojos(), prep.quotes());
    }

    private Position placeAndSave(Position trade, List<LegOrder> legOrder, Map<String, Quote> quotes) {
    	List<WeeklyLeg> childOrderBook = new ArrayList<>();
    	MarginPreCheckService.MarginCheck marginCheck = marginPreCheck.check(legOrder);
    	if (!marginCheck.allowed()) {
    	    log.error("MARGIN BLOCKED — open aborted before any order was placed | strategy={} | {}",
    	            trade.getStrategyName(), marginCheck.reason());
    	    trade.setLegs(failedLegs(legOrder));
    	    trade.setStatus(FAILED);
    	    trade.setMessage(marginCheck.reason());
    	    return positionRepository.save(trade);
    	}
    	if (quotes.isEmpty()) {
    	    log.error("Quote map is empty — aborting trade open for all instruments");
    	    trade.setLegs(failedLegs(legOrder));
    	    trade.setStatus(FAILED);
    	    return positionRepository.save(trade);
    	}
    	List<CompletableFuture<Void>> futs = legOrder.stream()
    	    .map(w -> CompletableFuture.runAsync(() -> {
    	        log.debug("LegOrder to place order is: {}", w);
    	        int totalQty = w.getLots() * LOT_SIZE;
    	        try {
    	            ExecResult er = positionUtil.placeAggressiveOrder(quotes.get(w.getExchangeSymbol()), w.getInstrument(), w.getSide(), totalQty, "ENTRY");
    	            if (er.aggregateOrderIds() != null && !er.aggregateOrderIds().isEmpty()) {
    	                w.setOpenOrderId(er.aggregateOrderIds());
    	            }
    	            w.setOpenFilledQty(er.totalFilled());
    	            if (!er.fullyFilled()) {
    	                log.error("[ENTRY] {} ({} qty) NOT fully filled: filled={}/{} term={}",
    	                    w.getInstrument(), totalQty, er.totalFilled(), er.totalRequested(), er.terminalStatus());
    	                w.setOpenFullyFilled(false);
    	            } else {
    	                w.setOpenFullyFilled(true);
    	            }
    	        } catch (Exception e) {
    	            log.error("Exception placing order for {} ({} qty): {}", w.getInstrument(), totalQty, e.getMessage(), e);
    	        }
    	    }, PositionUtil.LEG_EXEC))
    	    .toList();
    	CompletableFuture.allOf(futs.toArray(new CompletableFuture[0])).join();
    	legOrder.forEach(pojo -> {
    		WeeklyLeg b = new WeeklyLeg();
    		b.setInstrument(pojo.getInstrument());
    		b.setExchangeSymbol(pojo.getExchangeSymbol());
    		b.setSide(pojo.getSide());
    		b.setPosition(pojo.getParentPosition());
    		b.setMoneyness(pojo.getMoneyness());
    		b.setStrike(pojo.getStrike());
    		b.setOpenOrderId(pojo.getOpenOrderId());
    		int filledQty = pojo.getOpenFilledQty();
    		if (filledQty > 0) {
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
    	boolean anyFilled = legOrder.stream().anyMatch(p -> p.getOpenFilledQty() > 0);
    	if (allFullyFilled) {
    		trade.setStatus(LIVE);
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

    /** Placeholder FAILED legs for an open aborted before any order reached the broker. */
    private static List<WeeklyLeg> failedLegs(List<LegOrder> legOrder) {
    	return legOrder.stream().map(pojo -> {
    	    WeeklyLeg b = new WeeklyLeg();
    	    b.setInstrument(pojo.getInstrument()); b.setExchangeSymbol(pojo.getExchangeSymbol());
    	    b.setSide(pojo.getSide()); b.setPosition(pojo.getParentPosition());
    	    b.setMoneyness(pojo.getMoneyness()); b.setStrike(pojo.getStrike()); b.setLots(pojo.getLots());
    	    b.setQuantity(pojo.getLots() * LOT_SIZE); b.setStatus(FAILED);
    	    return b;
    	}).collect(Collectors.toList());
    }
}
