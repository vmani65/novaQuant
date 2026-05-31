package path.to._40c.nqCore.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.zerodhatech.models.LTPQuote;

import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.entity.WeeklyLeg;
import path.to._40c.nqCore.pojo.LegOrder;
import path.to._40c.nqCore.repo.PositionRepository;
import path.to._40c.nqCore.util.ComputeUtil;
import path.to._40c.nqCore.util.PositionUtil;
import path.to._40c.nqCore.util.PositionUtil.ExecResult;

import static path.to._40c.nqCore.util.Constants.*;

@Service
public class PositionOpeningService {

	private static final Logger log = LoggerFactory.getLogger(PositionOpeningService.class);

    private final PositionRepository positionRepository;
    private final PositionUtil positionUtil;
    private final ComputeUtil computeUtil;

    public PositionOpeningService(PositionRepository positionRepository, PositionUtil positionUtil, ComputeUtil computeUtil) {
        this.positionRepository = positionRepository;
        this.positionUtil = positionUtil;
        this.computeUtil = computeUtil;
    }

    /**
     * Instruments + LTP pre-fetched by the flip path's async task while close orders execute.
     */
    public record OpenPrep(List<LegOrder> pojos, Map<String, LTPQuote> ltp) {}

    /**
     * Called by handleFlip's CompletableFuture concurrently with closeTrade.
     * Builds instruments and fetches LTP for the open leg so both are ready
     * the moment the close completes (~100ms work vs ~500ms close execution).
     */
    public OpenPrep prepareOpen(String signalPrice, String type, Position trade) {
        trade.setEntrySpot(Double.valueOf(signalPrice));
        trade.setDirection(CE.equals(type) ? LONG : SHORT);
        List<LegOrder> pojos = computeUtil.buildInstrument(signalPrice, trade, false);
        String[] symbols = pojos.stream().map(LegOrder::getExchangeSymbol).toArray(String[]::new);
        Map<String, LTPQuote> ltp = positionUtil.getLTP(symbols);
        return new OpenPrep(pojos, ltp);
    }

    /**
     * Standard open path used by handleTradeOpen — builds instruments and fetches LTP inline.
     */
    public Position openTrade(String signalPrice, String type, Position trade) {
    	trade.setEntrySpot(Double.valueOf(signalPrice));
    	trade.setDirection(CE.equals(type) ? LONG : SHORT);
    	List<LegOrder> legOrder = computeUtil.buildInstrument(signalPrice, trade, false);
    	String[] ltpIns = legOrder.stream().map(LegOrder::getExchangeSymbol).toArray(String[]::new);
	    log.debug("OpenTrade ltpIns is: {}", (Object) ltpIns);
    	Map<String, LTPQuote> ltp = positionUtil.getLTP(ltpIns);
    	return placeAndSave(trade, legOrder, ltp);
    }

    /**
     * Optimised flip path — skips buildInstrument and getLTP since both were
     * pre-computed by prepareOpen while the close orders were executing on Zerodha.
     */
    public Position openTrade(String signalPrice, String type, Position trade, OpenPrep prep) {
        trade.setEntrySpot(Double.valueOf(signalPrice));
        trade.setDirection(CE.equals(type) ? LONG : SHORT);
        return placeAndSave(trade, prep.pojos(), prep.ltp());
    }

    private Position placeAndSave(Position trade, List<LegOrder> legOrder, Map<String, LTPQuote> ltp) {
    	List<WeeklyLeg> childOrderBook = new ArrayList<>();
    	if (ltp.isEmpty()) {
    	    log.error("LTP map is empty — aborting trade open for all instruments");
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
    	IntStream.range(0, legOrder.size()).parallel().forEach(i -> {
    	    LegOrder w = legOrder.get(i);
    	    log.debug("LegOrder to place order is: {}", w);
    	    int totalQty = w.getLots() * LOT_SIZE;
    	    try {
    	        ExecResult er = positionUtil.placeAggressiveOrder(w.getInstrument(), w.getSide(), totalQty, "ENTRY");
    	        if (er.aggregateOrderIds() != null && !er.aggregateOrderIds().isEmpty()) {
    	            synchronized (w) {
    	                w.setOpenOrderId(er.aggregateOrderIds());
    	            }
    	        }
    	        if (!er.fullyFilled()) {
    	            log.error("[ENTRY] {} ({} qty) NOT fully filled: filled={}/{} term={}",
    	                w.getInstrument(), totalQty, er.totalFilled(), er.totalRequested(), er.terminalStatus());
    	            synchronized (w) {
    	                w.setOpenFullyFilled(false);
    	            }
    	        } else {
    	            synchronized (w) {
    	                w.setOpenFullyFilled(true);
    	            }
    	        }
    	    } catch (Exception e) {
    	        log.error("Exception placing order for {} ({} qty): {}", w.getInstrument(), totalQty, e.getMessage(), e);
    	    }
    	});
    	legOrder.forEach(pojo -> {
    		WeeklyLeg b = new WeeklyLeg();
    		b.setInstrument(pojo.getInstrument());
    		b.setExchangeSymbol(pojo.getExchangeSymbol());
    		b.setSide(pojo.getSide());
    		b.setPosition(pojo.getParentPosition());
    		b.setMoneyness(pojo.getMoneyness());
    		b.setOpenOrderId(pojo.getOpenOrderId());
    		b.setLots(pojo.getLots());
    		b.setQuantity(pojo.getLots() * LOT_SIZE);
    		b.setStatus(Boolean.TRUE.equals(pojo.getOpenFullyFilled()) ? LIVE : FAILED);
    		LTPQuote q = ltp.get(pojo.getExchangeSymbol());
    		if (q != null) {
    			if (BUY.equals(pojo.getSide()))
					b.setBuyIntendedPrice(q.lastPrice);
    			else
					b.setSellIntendedPrice(q.lastPrice);
    		}
    		childOrderBook.add(b);
    	});
    	trade.setLegs(childOrderBook);
    	if (trade.getLegs().stream().allMatch(ob -> LIVE.equals(ob.getStatus()))) {
    		trade.setStatus(LIVE);
        } else {
        	trade.setStatus(FAILED);
            log.error("Position opening operation failed - not all legs fully filled");
        }
    	var liveTrade = positionRepository.save(trade);
    	log.info("Live Position Being Opened: {}", trade);
    	return liveTrade;
    }
}
