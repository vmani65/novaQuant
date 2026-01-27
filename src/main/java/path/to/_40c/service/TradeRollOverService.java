package path.to._40c.service;

import static path.to._40c.util.Constants.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
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

@Service
public class TradeRollOverService {

	private static final Logger log = LoggerFactory.getLogger(TradeRollOverService.class);
	
    @Autowired
    private TradeRepository tradeRepository;         
    
    @Autowired
    private TradeUtil tradeUtil;
    
    @Autowired
    private ComputeUtil computeUtil;    
     
    /**
     * 1. Close the Live Trade first. 
     * 2. Proceed with opening new trades only if all closes succeeded.
     * 3. Open live trades. Create a childOrderBook and add to the Parent and Save
     */
    public void rollOver(String signalPrice) {
        Trade tradeToRollOver = tradeUtil.findLiveTradesWithLiveOrderBooks();
        String type = LONG.equals(tradeToRollOver.getSignalType()) ? CE : PE;
        log.info(tradeToRollOver != null ? "Live Trade being rolled over is: " + tradeToRollOver : "No Live trades to rollover.");        
        if (tradeToRollOver != null) {
            String[] liveIns = tradeToRollOver.getWeeklyOrderBook().stream().map(WeeklyOrderBook::getTradedSymbol).toArray(String[]::new);
            Map<String, LTPQuote> ltpOfToCloseTrade = tradeUtil.getLTP(liveIns);            
            AtomicBoolean allClosesSucceeded = new AtomicBoolean(true);
            
            IntStream.range(0, tradeToRollOver.getWeeklyOrderBook().size()).parallel().forEach(i -> {
                WeeklyOrderBook toClose = tradeToRollOver.getWeeklyOrderBook().get(i);
                log.debug("WeeklyOrderBook to rollover is: {}", toClose);                
                String oppositeTransaction = BUY.equals(toClose.getTransactionType()) ? SELL : BUY;                
                try {
                    if (toClose.getQuantity() >= MAX_SIZE_PER_ORDER) {
                        List<BulkOrderResponse> o = tradeUtil.placeAutoSliceOrder(toClose.getMarginCalcSymbol(),ltpOfToCloseTrade.get(toClose.getTradedSymbol()).lastPrice,oppositeTransaction,toClose.getQuantity());                        
                        if (o != null && !o.isEmpty()) {
                            log.info("Auto-sliced order placed for {} ({} qty, {} slices)", toClose.getMarginCalcSymbol(), toClose.getQuantity(), o.size());
                            synchronized (toClose) {
                                toClose.setTradeCloseOrderId(o.stream().map(a -> a.orderId).collect(Collectors.joining(", ")));
                                toClose.setTradeStatus(CLOSED);
                            }
                        } else {
                            log.error("Rollover close failed for {} - returned null/empty", toClose.getMarginCalcSymbol());
                            allClosesSucceeded.set(false);
                        }
                    } else {
                        Order o = tradeUtil.placeOrder(toClose.getMarginCalcSymbol(),ltpOfToCloseTrade.get(toClose.getTradedSymbol()).lastPrice,oppositeTransaction,toClose.getQuantity());                        
                        if (o != null && o.orderId != null) {
                            log.info("Direct order placed for {} ({} qty, orderId={})", toClose.getMarginCalcSymbol(), toClose.getQuantity(), o.orderId);
                            synchronized (toClose) {
                                toClose.setTradeCloseOrderId(o.orderId);
                                toClose.setTradeStatus(CLOSED);
                            }
                        } else {
                            log.error("Rollover close failed for {} ({} qty) - returned null", toClose.getMarginCalcSymbol(), toClose.getQuantity());
                            allClosesSucceeded.set(false);
                        }
                    }
                } catch (Exception e) {
                    log.error("Exception closing order during rollover for {}: {}", toClose.getMarginCalcSymbol(), e.getMessage(), e);
                    allClosesSucceeded.set(false);
                }
            });
            
            if (!allClosesSucceeded.get()) {
                log.error("Rollover aborted - not all positions closed successfully");
                return;
            }            
            tradeUtil.setTradeExecPricesForRollOver(tradeToRollOver, true, false);            
            List<WeeklyOrderBook> childOrderBook = new ArrayList<WeeklyOrderBook>();
            tradeToRollOver.setEntrySignalPrice(Math.round(((tradeToRollOver.getEntrySignalPrice() != null ? tradeToRollOver.getEntrySignalPrice() : 0.0) + (signalPrice != null ? Double.valueOf(signalPrice) : 0.0)) * 100.0) / 100.0);            
            List<WeeklyPojo> weeklyPojo = computeUtil.buildInstrument(signalPrice, type, tradeToRollOver, true);
            String[] ltpIns = weeklyPojo.stream().map(WeeklyPojo::getTradedSymbol).toArray(String[]::new);
            log.debug("OpenTrade ltpIns is: {}", Arrays.toString(ltpIns));
            Map<String, LTPQuote> ltpOfToOpenTrade = tradeUtil.getLTP(ltpIns);            
            IntStream.range(0, weeklyPojo.size()).parallel().forEach(i -> {
                WeeklyPojo w = weeklyPojo.get(i);
                log.debug("WeeklyPojo to place order is: {}", w);                
                int totalQty = w.getLots() * LOT_SIZE;                
                try {
                    if (totalQty >= MAX_SIZE_PER_ORDER) {
                        List<BulkOrderResponse> o = tradeUtil.placeAutoSliceOrder( w.getMarginCalcSymbol(), ltpOfToOpenTrade.get(w.getTradedSymbol()).lastPrice, w.getTransactionType(), totalQty);                        
                        if (o != null && !o.isEmpty()) {
                            log.info("Auto-sliced order placed for {} ({} qty, {} slices)", w.getMarginCalcSymbol(), totalQty, o.size());
                            synchronized (w) {
                                w.setTradeOpenOrderId(o.stream().map(a -> a.orderId).collect(Collectors.joining(", ")));
                            }
                        } else {
                            log.error("Rollover open failed for {} - returned null/empty", w.getMarginCalcSymbol());
                        }
                    } else {
                        Order o = tradeUtil.placeOrder(w.getMarginCalcSymbol(), ltpOfToOpenTrade.get(w.getTradedSymbol()).lastPrice, w.getTransactionType(),totalQty);                        
                        if (o != null && o.orderId != null) {
                            log.info("Direct order placed for {} ({} qty, orderId={})", w.getMarginCalcSymbol(), totalQty, o.orderId);
                            synchronized (w) {
                                w.setTradeOpenOrderId(o.orderId);
                            }
                        } else {
                            log.error("Rollover open failed for {} ({} qty) - returned null", w.getMarginCalcSymbol(), totalQty);
                        }
                    }
                } catch (Exception e) {
                    log.error("Exception opening order during rollover for {}: {}", w.getMarginCalcSymbol(), e.getMessage(), e);
                }
            });
            
            weeklyPojo.forEach(pojo -> {
                WeeklyOrderBook b = new WeeklyOrderBook();
                b.setMarginCalcSymbol(pojo.getMarginCalcSymbol());
                b.setTradedSymbol(pojo.getTradedSymbol());
                b.setTransactionType(pojo.getTransactionType());
                b.setTradeOpenOrderId(pojo.getTradeOpenOrderId());
                b.setTrade(pojo.getParentTrade());
                b.setMoneyness(pojo.getMoneyness());
                b.setLots(pojo.getLots());
                b.setQuantity(pojo.getLots() * LOT_SIZE);
                b.setTradeStatus(b.getTradeOpenOrderId() != null ? LIVE : FAILED);
                childOrderBook.add(b);
            });            
            tradeToRollOver.setWeeklyOrderBook(childOrderBook);
            tradeUtil.calcMarginAndBrokerage(tradeToRollOver);
            tradeUtil.setTradeExecPricesForRollOver(tradeToRollOver, false, true);
            var liveTrade = tradeRepository.save(tradeToRollOver);
            log.info("Live Trade after rollOver completed is: {}", liveTrade);
        }
    }
}
