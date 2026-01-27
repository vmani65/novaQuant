package path.to._40c.service;

import static path.to._40c.util.Constants.*;

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

import path.to._40c.controller.SignalController.Signal;
import path.to._40c.entity.Trade;
import path.to._40c.entity.WeeklyOrderBook;
import path.to._40c.repo.TradeRepository;
import path.to._40c.util.ComputeUtil;
import path.to._40c.util.TradeUtil;

@Service
public class TradeClosingService {

	private static final Logger log = LoggerFactory.getLogger(TradeClosingService.class);
	
    @Autowired
    private TradeRepository tradeRepository;     
        
    @Autowired
    private TradeUtil tradeUtil;
    
    @Autowired
    private ComputeUtil computeUtil;
    
    public Trade closeTrade(String signalPrice, Signal signal, boolean updateApiAction) {
        Trade tradeToClose = tradeUtil.findLiveTradesWithLiveOrderBooks();        
        if(tradeToClose == null) {
            log.info("No Live trades to close.");
            return null;
        }        
        tradeToClose.setExitSignalPrice(Double.valueOf(signalPrice));
        log.info("Live Trade being closed is: " + tradeToClose);        
        String[] liveIns = tradeToClose.getWeeklyOrderBook().stream().map(WeeklyOrderBook::getTradedSymbol).toArray(String[]::new);        
        Map<String, LTPQuote> ltp = tradeUtil.getLTP(liveIns);               
        IntStream.range(0, tradeToClose.getWeeklyOrderBook().size()).parallel().forEach(i -> {
            WeeklyOrderBook w = tradeToClose.getWeeklyOrderBook().get(i);
            log.debug("WeeklyOrderBook to close is: {}", w);            
            String oppositeTransaction = BUY.equals(w.getTransactionType()) ? SELL : BUY;            
            try {
                if (w.getQuantity() >= MAX_SIZE_PER_ORDER) {
                    List<BulkOrderResponse> o = tradeUtil.placeAutoSliceOrder(w.getMarginCalcSymbol(),ltp.get(w.getTradedSymbol()).lastPrice,oppositeTransaction,w.getQuantity());                    
                    if (o != null && !o.isEmpty()) {
                        log.info("Auto-sliced order placed for {} ({} qty, {} slices)",w.getMarginCalcSymbol(), w.getQuantity(), o.size());
                        synchronized (w) {
                            w.setTradeCloseOrderId(o.stream().map(a -> a.orderId).collect(Collectors.joining(", ")));
                            w.setTradeStatus(CLOSED);
                        }
                    } else {
                        log.error("Auto-slice close order failed for {} ({} qty) - returned null/empty",w.getMarginCalcSymbol(), w.getQuantity());
                        synchronized (w) {
                            w.setTradeStatus(FAILED);
                        }
                    }
                } else {
                    Order o = tradeUtil.placeOrder(w.getMarginCalcSymbol(),ltp.get(w.getTradedSymbol()).lastPrice,oppositeTransaction,w.getQuantity());                    
                    if (o != null && o.orderId != null) {
                        log.info("Direct order placed for {} ({} qty, orderId={})", w.getMarginCalcSymbol(), w.getQuantity(), o.orderId);
                        synchronized (w) {
                            w.setTradeCloseOrderId(o.orderId);
                            w.setTradeStatus(CLOSED);
                        }
                    } else {
                        log.error("Close order failed for {} ({} qty) - returned null", w.getMarginCalcSymbol(), w.getQuantity());
                        synchronized (w) {
                            w.setTradeStatus(FAILED);
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Exception closing order for {} ({} qty): {}", 
                    w.getMarginCalcSymbol(), w.getQuantity(), e.getMessage(), e);
                synchronized (w) {
                    w.setTradeStatus(FAILED);
                }
            }
        });
        if(updateApiAction) {
            tradeToClose.setLastApiAction(signal.action);
            tradeToClose.setLastApiSignalType(signal.signalType);        	
        }
        if (tradeToClose.getWeeklyOrderBook().stream().allMatch(ob -> CLOSED.equals(ob.getTradeStatus()))) {
            tradeToClose.setTradeStatus(CLOSED);
        } else {
            tradeToClose.setTradeStatus(FAILED);
            log.error("Trade closing failed - not all orders were closed successfully");
        }
        tradeToClose.setTradeCloseDtTime(computeUtil.getDtTimeNow());        
        log.info("Trade closing completed : " + tradeToClose);
        Trade closedTrade = tradeRepository.save(tradeToClose);  
        return closedTrade;
    }
}