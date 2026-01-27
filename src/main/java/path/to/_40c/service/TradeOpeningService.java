package path.to._40c.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.zerodhatech.models.BulkOrderResponse;
import com.zerodhatech.models.LTPQuote;
import com.zerodhatech.models.Order;

import path.to._40c.entity.Trade;
import path.to._40c.entity.WeeklyOrderBook;
import path.to._40c.pojo.WeeklyPojo;
import path.to._40c.repo.TradeRepository;
import path.to._40c.util.ComputeUtil;
import path.to._40c.util.TradeUtil;

import static path.to._40c.util.Constants.*;

@Service
public class TradeOpeningService {
	
	private static final Logger log = LoggerFactory.getLogger(TradeOpeningService.class);
	
    @Autowired
    private TradeRepository tradeRepository;         
    
    @Autowired
    private TradeUtil tradeUtil;
    
    @Autowired
    private ComputeUtil computeUtil;    
    
    public Trade openTrade(String signalPrice, String type, Trade trade) {
    	List<WeeklyOrderBook> childOrderBook = new ArrayList<WeeklyOrderBook>(); 
    	trade.setEntrySignalPrice(Double.valueOf(signalPrice));
    	trade.setSignalType(CE.equals(type) ? LONG : SHORT);
    	List<WeeklyPojo> weeklyPojo = computeUtil.buildInstrument(signalPrice, type, trade, false);
    	String[] ltpIns = weeklyPojo.stream().map(WeeklyPojo::getTradedSymbol).toArray(String[]::new); log.debug("OpenTrade ltpIns is :"+ltpIns);
    	Map<String, LTPQuote> ltp = tradeUtil.getLTP(ltpIns);
    	IntStream.range(0, weeklyPojo.size()).parallel().forEach(i -> {
    	    WeeklyPojo w = weeklyPojo.get(i); 
    	    log.debug("WeeklyPojo to place order is: {}", w);    	    
    	    int totalQty = w.getLots() * LOT_SIZE;    	    
    	    try {
    	        if (totalQty >= MAX_SIZE_PER_ORDER) {
    	            List<BulkOrderResponse> o = tradeUtil.placeAutoSliceOrder(w.getMarginCalcSymbol(),ltp.get(w.getTradedSymbol()).lastPrice,w.getTransactionType(),totalQty);    	            
    	            if (o != null && !o.isEmpty()) {
    	                log.info("Auto-sliced order placed for {} ({} qty, {} slices)", w.getMarginCalcSymbol(), totalQty, o.size());
    	                synchronized (w) {
    	                    w.setTradeOpenOrderId(o.stream().map(a -> a.orderId).collect(Collectors.joining(", ")));
    	                }
    	            } else {
    	                log.error("Auto-slice order failed for {} (returned null/empty)", w.getMarginCalcSymbol());
    	            }
    	        } else {
    	            Order o = tradeUtil.placeOrder(w.getMarginCalcSymbol(),ltp.get(w.getTradedSymbol()).lastPrice,w.getTransactionType(),totalQty);    	            
    	            if (o != null && o.orderId != null) {
    	                log.info("Direct order placed for {} ({} qty, orderId={})",w.getMarginCalcSymbol(), totalQty, o.orderId);
    	                synchronized (w) {
    	                    w.setTradeOpenOrderId(o.orderId);
    	                }
    	            } else {
    	                log.error("Order placement failed for {} ({} qty) - returned null",w.getMarginCalcSymbol(), totalQty);
    	            }
    	        }
    	    } catch (Exception e) {
    	        log.error("Exception placing order for {} ({} qty): {}", w.getMarginCalcSymbol(), totalQty, e.getMessage(), e);
    	    }
    	});

    	weeklyPojo.forEach(pojo -> {
    		WeeklyOrderBook b = new WeeklyOrderBook();
    		b.setMarginCalcSymbol(pojo.getMarginCalcSymbol());
    		b.setTradedSymbol(pojo.getTradedSymbol());
    		b.setTransactionType(pojo.getTransactionType());
    		b.setTrade(pojo.getParentTrade());
    		b.setMoneyness(pojo.getMoneyness());
    		b.setTradeOpenOrderId(pojo.getTradeOpenOrderId());
    		b.setLots(pojo.getLots());
    		b.setQuantity(pojo.getLots() * LOT_SIZE);
    		b.setTradeStatus(b.getTradeOpenOrderId() != null ? LIVE : FAILED);
    		childOrderBook.add(b);
    	});
    	trade.setWeeklyOrderBook(childOrderBook);
    	if (trade.getWeeklyOrderBook().stream().allMatch(ob -> ob.getTradeOpenOrderId() != null)) {
    		trade.setTradeStatus(LIVE);
        } else {
        	trade.setTradeStatus(FAILED);
            log.error("Trade opening operation failed - not all order openings succeeded");
        }
    	tradeUtil.calcMarginAndBrokerage(trade);
    	var liveTrade = tradeRepository.save(trade);    	
    	log.info("Live Trade Being Opened: "+trade);
    	return liveTrade;
    }    
}
