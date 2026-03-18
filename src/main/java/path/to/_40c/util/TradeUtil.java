package path.to._40c.util;

import static path.to._40c.util.Constants.BUY;
import static path.to._40c.util.Constants.CLOSED;
import static path.to._40c.util.Constants.LIVE;
import static path.to._40c.util.Constants.SELL;
import static path.to._40c.util.Constants.ZONE_ID;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.hibernate.Session;
import org.json.JSONException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.kiteconnect.utils.Constants;
import com.zerodhatech.models.BulkOrderResponse;
import com.zerodhatech.models.Instrument;
import com.zerodhatech.models.LTPQuote;
import com.zerodhatech.models.MarginCalculationData;
import com.zerodhatech.models.MarginCalculationParams;
import com.zerodhatech.models.Order;
import com.zerodhatech.models.OrderParams;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import path.to._40c.entity.KiteAuthDetails;
import path.to._40c.entity.Trade;
import path.to._40c.repo.KiteAuthDetailsRepository;
import path.to._40c.repo.TradeRepository;

@Service
public class TradeUtil {

	private static final Logger log = LoggerFactory.getLogger(TradeUtil.class);

    // KiteConnect cache — avoids a DB hit on every order/LTP/margin call.
    // Keyed by today's date; invalidated when new auth is saved.
    private volatile KiteConnect cachedKiteConnect = null;
    private volatile LocalDate cacheDate = null;

    @Autowired
    private KiteAuthDetailsRepository repository;
    
    @Autowired
    private TradeRepository tradeRepository; 
    
    @PersistenceContext
    private EntityManager entityManager;
    
    public void setTradeExecutedPrices(Trade t) {
        if (t != null) {
          t.getWeeklyOrderBook().forEach(w -> {
            log.info("Fetching executed prices for trade: {} OrderID: {}", t, LIVE.equals(w.getTradeStatus()) ? w.getTradeOpenOrderId() : w.getTradeCloseOrderId());
            var trades = getOrderTrades(LIVE.equals(w.getTradeStatus()) ? w.getTradeOpenOrderId() : w.getTradeCloseOrderId());
            if (trades == null || trades.isEmpty()) {
            	log.warn("Empty Trade list while fetching executed prices. This needs investigation");
            	sleep();
            	trades = getOrderTrades(LIVE.equals(w.getTradeStatus()) ? w.getTradeOpenOrderId() : w.getTradeCloseOrderId());                	
            }
            if (trades != null && !trades.isEmpty() && trades.get(0) != null) {
              var averagePrice = trades.get(0).averagePrice;
              var avgPrice = averagePrice != null ? Double.valueOf(averagePrice) : 0.0; 
              log.info("Average Price: {}", avgPrice);
                if (BUY.equals(w.getTransactionType())) {
                  if (LIVE.equals(w.getTradeStatus()))
                    w.setBoughtPrice(avgPrice);
                  if (CLOSED.equals(w.getTradeStatus()))
                    w.setSoldPrice(avgPrice);
                }
                if (SELL.equals(w.getTransactionType())) {
                  if (LIVE.equals(w.getTradeStatus()))
                    w.setSoldPrice(avgPrice);
                  if (CLOSED.equals(w.getTradeStatus()))
                    w.setBoughtPrice(avgPrice);
                }
            }
         });
       }
    }
    
    public void setTradeExecPricesForRollOver(Trade t, boolean rollOverClose, boolean rollOverOpen) {
        if(t != null) {
            t.getWeeklyOrderBook().stream().filter(w -> rollOverClose ? CLOSED.equals(w.getTradeStatus()) : LIVE.equals(w.getTradeStatus())).forEach(w -> {
                log.info("Fetching executed prices for rollover trade: {} OrderID: {}", t, rollOverOpen ? w.getTradeOpenOrderId() : w.getTradeCloseOrderId());
                var trades = getOrderTrades(rollOverOpen ? w.getTradeOpenOrderId() : w.getTradeCloseOrderId());
                if (trades == null || trades.isEmpty()) {
                	log.warn("Empty Trade list while fetching executed prices during rollover. This needs investigation");
                	sleep();
                    trades = getOrderTrades(rollOverOpen ? w.getTradeOpenOrderId() : w.getTradeCloseOrderId());                	
                }
                log.info("Trade Details for OrderID: {} is {}", rollOverOpen ? w.getTradeOpenOrderId() : w.getTradeCloseOrderId(), (trades != null && !trades.isEmpty()) ? trades : "");
                if(trades != null && !trades.isEmpty() && trades.get(0) != null) {
                    var averagePrice = trades.get(0).averagePrice;
                    var avgPrice = averagePrice != null ? Double.valueOf(averagePrice) : 0.0; 
                    log.info("Average Price: {}", avgPrice);
                    if(BUY.equals(w.getTransactionType())) {
                        if(rollOverOpen) {
                            var newPrice = (w.getBoughtPrice() != null ? w.getBoughtPrice() : 0.0) + avgPrice;
                            w.setBoughtPrice(Math.round(newPrice * 100.0) / 100.0);
                        }                            
                        if(rollOverClose) {
                            var newPrice = (w.getSoldPrice() != null ? w.getSoldPrice() : 0.0) + avgPrice;
                            w.setSoldPrice(Math.round(newPrice * 100.0) / 100.0);
                        }
                    }                        
                    if(SELL.equals(w.getTransactionType())) {
                        if(rollOverOpen) {
                            var newPrice = (w.getSoldPrice() != null ? w.getSoldPrice() : 0.0) + avgPrice;
                            w.setSoldPrice(Math.round(newPrice * 100.0) / 100.0);
                        }                            
                        if(rollOverClose) {
                            var newPrice = (w.getBoughtPrice() != null ? w.getBoughtPrice() : 0.0) + avgPrice;
                            w.setBoughtPrice(Math.round(newPrice * 100.0) / 100.0);
                        }
                    }
                }
            });
        }
    }
    
    /**
	 * 1. This method will be called only once during TRADE OPEN. 
	 * 2. It fills for both buy and sell brokerage and trade margin
	 */
    public void calcMarginAndBrokerage(Trade trade) {
    	if (trade != null) {
	    	var buyParams = new ArrayList<MarginCalculationParams>();
	    	buildMarginCalcParams(trade, buyParams, Constants.TRANSACTION_TYPE_BUY);
	    	var sellParams = new ArrayList<MarginCalculationParams>();
	    	buildMarginCalcParams(trade, sellParams, Constants.TRANSACTION_TYPE_SELL);
	    	// Fire both margin API calls in parallel on ForkJoinPool.commonPool()
	    	// (intentionally NOT postTradeExecutor — avoids deadlock when called from async thread)
	    	var buyFuture = CompletableFuture.supplyAsync(() -> getMarginCalculation(buyParams));
	    	var sellFuture = CompletableFuture.supplyAsync(() -> getMarginCalculation(sellParams));
	    	List<MarginCalculationData> tradeBuyMargins;
	    	List<MarginCalculationData> tradeSellMargins;
	    	try {
	    	    tradeBuyMargins = buyFuture.get();
	    	    tradeSellMargins = sellFuture.get();
	    	} catch (Exception e) {
	    	    log.error("Exception during parallel margin calculation", e);
	    	    return;
	    	}
	    	tradeBuyMargins.forEach(tradeBuyMargin -> {
	    		trade.getWeeklyOrderBook().forEach(weekly -> {
	    			if ((tradeBuyMargin.tradingSymbol).equals(weekly.getMarginCalcSymbol()) && LIVE.equals(weekly.getTradeStatus())) {
	    				weekly.setTradeOpenBrokerage(ComputeUtil.rnd(tradeBuyMargin.charges.total));
	        			if (BUY.equals(weekly.getTransactionType()))
	        				weekly.setMarginToTrade(ComputeUtil.rnd(tradeBuyMargin.total));
	    			}
	    		});
	    	});
	    	tradeSellMargins.forEach(tradeSellMargin -> {
	    		trade.getWeeklyOrderBook().forEach(weekly -> {
	    			if ((tradeSellMargin.tradingSymbol).equals(weekly.getMarginCalcSymbol()) && LIVE.equals(weekly.getTradeStatus())) {
	    				weekly.setTradeCloseBrokerage(ComputeUtil.rnd(tradeSellMargin.charges.total));
	        			if (SELL.equals(weekly.getTransactionType()))
	        				weekly.setMarginToTrade(ComputeUtil.rnd(tradeSellMargin.total));
	    			}
	    		});
	    	});
    	}
    }
       
    public void buildMarginCalcParams(Trade t, List<MarginCalculationParams> params, String transactionType) {
    	t.getWeeklyOrderBook().stream().filter(c -> LIVE.equals(c.getTradeStatus())).forEach(c -> {
    		var param = initCalcParam(c.getQuantity());
           	param.tradingSymbol = c.getMarginCalcSymbol();
           	param.transactionType = transactionType;
           	params.add(param);	
    	});    	
    }
    
    public MarginCalculationParams initCalcParam(int quantity) {
    	var params = new MarginCalculationParams();
    	params.exchange = Constants.EXCHANGE_NFO;
    	params.variety = Constants.VARIETY_REGULAR;
    	params.product = Constants.PRODUCT_NRML;
    	params.orderType = Constants.ORDER_TYPE_MARKET;
    	params.quantity = quantity;    	
    	return params;
    }
    
    public int roundNFToNearestATM(String price) {
        float f = Float.valueOf(price);
        int strikePrice = Math.round(f / 50f) * 50;
        log.info("roundNFToNearestATM: input={}, calculatedStrike={}", price, strikePrice);
        return strikePrice;
    }
	
    public Map<String, LTPQuote> getLTP(String[] ins) {
    	var kite = getKiteConnectObject();
    	if (kite == null) {
    		log.error("KiteConnect object is null — cannot fetch LTP (auth not set for today?)");
    		return Collections.emptyMap();
    	}
    	try {
			return kite.getLTP(ins);
		} catch (JSONException | IOException | KiteException e) {
			log.error("Exception while fetching last traded price for instruments ",e);
		}
    	return Collections.emptyMap();
    }
       
    public List<MarginCalculationData> getMarginCalculation(List<MarginCalculationParams> params) {
    	var kite = getKiteConnectObject();
    	List<MarginCalculationData> margins = new ArrayList<>();
    	if (kite == null) {
    		log.error("KiteConnect object is null — skipping margin calculation (auth not set for today?)");
    		return margins;
    	}
    	try {
    		margins = kite.getMarginCalculation(params);
		} catch (JSONException | IOException | KiteException e) {
			log.error("Exception while fetching margin calculation data ",e);
		}
    	return margins;
    }
    
    public List<com.zerodhatech.models.Trade> getOrderTrades(String orderId) {
        log.info("Fetching trades for orderId: {}", orderId);
        List<com.zerodhatech.models.Trade> allTrades = new ArrayList<>();
        var kite = getKiteConnectObject();        
        Arrays.stream(orderId.split("\\s*,\\s*")).filter(id -> id != null && !id.trim().isEmpty()).forEach(id -> {
            try {
               List<com.zerodhatech.models.Trade> orderTrades = kite.getOrderTrades(id);
               allTrades.addAll(orderTrades);
               log.debug("Fetched {} trades for orderId: {}", orderTrades.size(), id);
            } catch (JSONException | IOException | KiteException e) {
               log.error("Exception while fetching trades for orderId: {}", id, e);
            }});        
        log.info("Total trades fetched: {}", allTrades.size());
        allTrades.forEach(trade -> log.info("Trade[tradeId={}, orderId={}, symbol={}, exchange={}, type={}, qty={}, price={}, fillTime={}]",
                trade.tradeId, trade.orderId, trade.tradingSymbol, trade.exchange,trade.transactionType, trade.quantity, trade.averagePrice, trade.fillTimestamp));
        return allTrades;
    }
    
	public KiteConnect getKiteConnectObject() {
        LocalDate today = LocalDate.now(ZoneId.of(ZONE_ID));
        if (cachedKiteConnect != null && today.equals(cacheDate)) {
            return cachedKiteConnect;
        }
    	KiteConnect kiteConnect = null;
    	Optional<KiteAuthDetails> existing = repository.findByAuthDate(today);
        if (existing.isPresent()) {
        	kiteConnect = new KiteConnect(existing.get().getApiKey());
        	kiteConnect.setAccessToken(existing.get().getAccessToken());
            kiteConnect.setPublicToken(existing.get().getPublicToken());
            cachedKiteConnect = kiteConnect;
            cacheDate = today;
        }
    	return kiteConnect;
    }

    public void invalidateKiteCache() {
        cachedKiteConnect = null;
        cacheDate = null;
        log.info("KiteConnect cache invalidated");
    }
	
	/**
	 * Quantity should be less than or equal to 1755 for direct order.
	 */
	public Order placeOrder(String ins, Double price, String transactionType, int quantity) {
		Order order = null;
		var kite = getKiteConnectObject();
		OrderParams orderParams = buildOrderParams();
        orderParams.transactionType = transactionType;
		orderParams.tradingsymbol = ins;
        orderParams.quantity = quantity;
        orderParams.price = price;
        try {
			order = kite.placeOrder(orderParams, Constants.VARIETY_REGULAR);
		} catch (JSONException | IOException | KiteException e) {
			log.error("Exception while placing order ",e);
		}        
        return order;
	}
	
	/**
	 * Quantity should be greater than 1755 for order slicing.
	 */
	public List<BulkOrderResponse> placeAutoSliceOrder(String ins, Double price, String transactionType, int quantity) {
		List<BulkOrderResponse> orders = new ArrayList<BulkOrderResponse>();
		var kite = getKiteConnectObject();
		OrderParams orderParams = buildOrderParams();
        orderParams.transactionType = transactionType;
        orderParams.tradingsymbol = ins;
        orderParams.quantity = quantity;
        orderParams.price = price;
        try {
			orders = kite.placeAutoSliceOrder(orderParams, Constants.VARIETY_REGULAR);
		} catch (JSONException | IOException | KiteException e) {
			log.error("Exception while placing auto slice order ",e);
		}
        orders.forEach(o -> {
            if (o.orderId != null) log.info("BulkOrder placed orderId: {}", o.orderId);
            else log.error("BulkOrder error — code: {}, message: {}", o.bulkOrderError.code, o.bulkOrderError.message);
        });
        return orders;
	}
	
	public static OrderParams buildOrderParams(){
		OrderParams orderParams = new OrderParams();
        orderParams.orderType = Constants.ORDER_TYPE_MARKET;        
        orderParams.product = Constants.PRODUCT_NRML;
        orderParams.exchange = Constants.EXCHANGE_NFO;
        orderParams.validity = Constants.VALIDITY_DAY;
        return orderParams;		
	}
    
	public List<String> getNiftyInstruments() {
	    var kite = getKiteConnectObject();
	    try {
	        List<Instrument> instruments = kite.getInstruments("NFO");	        
	        return instruments.stream()
	            .map(i -> i.tradingsymbol)
	            .filter(symbol -> symbol.contains("NIFTY"))
	            .filter(symbol -> !symbol.contains("MIDCPNIFTY"))
	            .filter(symbol -> !symbol.contains("BANKNIFTY"))
	            .filter(symbol -> !symbol.contains("NIFTYNXT"))
	            .filter(symbol -> !symbol.contains("FINNIFTY"))
	            .filter(symbol -> !symbol.startsWith("NIFTY27"))
	            .filter(symbol -> !symbol.startsWith("NIFTY28"))
	            .filter(symbol -> !symbol.startsWith("NIFTY29"))
	            .filter(symbol -> !symbol.startsWith("NIFTY30"))	            
	            .toList();	            
	    } catch (JSONException | IOException | KiteException e) {
	        log.error("Exception while fetching Nifty Instruments ", e);
	        return Collections.emptyList();
	    }
	}
	
	@Transactional
    public Trade findLiveTradesWithLiveOrderBooks() {
        Session session = entityManager.unwrap(Session.class);
        session.enableFilter("liveOrderBooks").setParameter("status", LIVE);        
        Trade trades = tradeRepository.findByTradeStatus(LIVE);        
        session.disableFilter("liveOrderBooks");       
        return trades;
    }
    
    @Transactional
    public Trade findLiveTradesWithAllOrderBooks() {
        return tradeRepository.findByTradeStatus(LIVE);
    }
    
    @Transactional
    public Trade findTrades(String tradeStatus, boolean filterChildRecords) {
        Session session = entityManager.unwrap(Session.class);        
        if (filterChildRecords) {
            session.enableFilter("liveOrderBooks").setParameter("status", LIVE);
        }
        Trade trades = tradeRepository.findByTradeStatus(tradeStatus);        
        if (filterChildRecords) {
            session.disableFilter("liveOrderBooks");
        }        
        return trades;
    }    
    
    public static void sleep() {
        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Thread interrupted: {}", e.getMessage());
        }
    }
}