package path.to._40c.nqCore.util;

import static path.to._40c.nqCore.util.Constants.BUY;
import static path.to._40c.nqCore.util.Constants.CLOSED;
import static path.to._40c.nqCore.util.Constants.LIVE;
import static path.to._40c.nqCore.util.Constants.NFO;
import static path.to._40c.nqCore.util.Constants.NIFTY;
import static path.to._40c.nqCore.util.Constants.SELL;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zerodhatech.kiteconnect.utils.Constants;
import com.zerodhatech.models.BulkOrderResponse;
import com.zerodhatech.models.LTPQuote;
import com.zerodhatech.models.MarginCalculationData;
import com.zerodhatech.models.MarginCalculationParams;
import com.zerodhatech.models.OrderParams;
import com.zerodhatech.models.OrderResponse;

import jakarta.persistence.EntityManager;
import path.to._40c.nqCore.entity.Trade;
import path.to._40c.nqCore.gateway.KiteGateway;
import path.to._40c.nqCore.repo.TradeRepository;

@Service
public class TradeUtil {

	private static final Logger log = LoggerFactory.getLogger(TradeUtil.class);

    private final KiteGateway kiteGateway;
    private final TradeRepository tradeRepository;
    private final EntityManager entityManager;

    public TradeUtil(KiteGateway kiteGateway, TradeRepository tradeRepository, EntityManager entityManager) {
        this.kiteGateway = kiteGateway;
        this.tradeRepository = tradeRepository;
        this.entityManager = entityManager;
    }

    // -----------------------------------------------------------------------
    // Execution price enrichment
    // -----------------------------------------------------------------------

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

    // -----------------------------------------------------------------------
    // Margin & brokerage
    // -----------------------------------------------------------------------

    /**
     * Called only during TRADE OPEN. Fills buy/sell brokerage and trade margin for all live legs.
     * The two Kite API calls (buy params, sell params) run in parallel on ForkJoinPool.commonPool()
     * — intentionally NOT postTradeExecutor to avoid deadlock when called from an async thread.
     */
    public void calcMarginAndBrokerage(Trade trade) {
    	if (trade != null) {
	    	var buyParams = new ArrayList<MarginCalculationParams>();
	    	buildMarginCalcParams(trade, buyParams, Constants.TRANSACTION_TYPE_BUY);
	    	var sellParams = new ArrayList<MarginCalculationParams>();
	    	buildMarginCalcParams(trade, sellParams, Constants.TRANSACTION_TYPE_SELL);
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

    // -----------------------------------------------------------------------
    // Kite API delegates — all callers go through KiteGateway
    // -----------------------------------------------------------------------

    public Map<String, LTPQuote> getLTP(String[] ins) {
        return kiteGateway.getLTP(ins);
    }

    public List<MarginCalculationData> getMarginCalculation(List<MarginCalculationParams> params) {
        return kiteGateway.getMarginCalculation(params);
    }

    /**
     * Fetches executed trades for one or more comma-separated order IDs.
     * Splits the ID string (auto-slice orders produce multiple IDs), calls gateway
     * once per single ID, and aggregates results.
     */
    public List<com.zerodhatech.models.Trade> getOrderTrades(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            log.warn("getOrderTrades called with null/blank orderId — skipping");
            return new ArrayList<>();
        }
        log.info("Fetching trades for orderId: {}", orderId);
        List<com.zerodhatech.models.Trade> allTrades = new ArrayList<>();
        Arrays.stream(orderId.split("\\s*,\\s*"))
              .filter(id -> id != null && !id.trim().isEmpty())
              .forEach(id -> {
                  List<com.zerodhatech.models.Trade> orderTrades = kiteGateway.getOrderTrades(id);
                  allTrades.addAll(orderTrades);
                  log.debug("Fetched {} trades for orderId: {}", orderTrades.size(), id);
              });
        log.info("Total trades fetched: {}", allTrades.size());
        allTrades.forEach(trade -> log.info("Trade[tradeId={}, orderId={}, symbol={}, type={}, qty={}, price={}, fillTime={}]",
                trade.tradeId, trade.orderId, trade.tradingSymbol, trade.transactionType,
                trade.quantity, trade.averagePrice, trade.fillTimestamp));
        return allTrades;
    }

    public OrderResponse placeOrder(String ins, Double price, String transactionType, int quantity) {
        OrderParams orderParams = buildOrderParams();
        orderParams.transactionType = transactionType;
        orderParams.tradingsymbol = ins;
        orderParams.quantity = quantity;
        orderParams.price = price;
        OrderResponse order = kiteGateway.placeOrder(orderParams, Constants.VARIETY_REGULAR);
        if (order != null) log.info("Order placed: orderId={}", order.orderId);
        return order;
    }

    public List<BulkOrderResponse> placeAutoSliceOrder(String ins, Double price, String transactionType, int quantity) {
        OrderParams orderParams = buildOrderParams();
        orderParams.transactionType = transactionType;
        orderParams.tradingsymbol = ins;
        orderParams.quantity = quantity;
        orderParams.price = price;
        List<BulkOrderResponse> orders = kiteGateway.placeAutoSliceOrder(orderParams, Constants.VARIETY_REGULAR);
        orders.forEach(o -> {
            if (o.orderId != null) log.info("BulkOrder placed orderId: {}", o.orderId);
            else log.error("BulkOrder error — code: {}, message: {}", o.bulkOrderError.code, o.bulkOrderError.message);
        });
        return orders;
    }

    public List<String> getNiftyInstruments() {
        return kiteGateway.getInstruments(NFO).stream()
            .map(i -> i.tradingsymbol)
            .filter(symbol -> symbol.contains(NIFTY))
            .filter(symbol -> !symbol.contains("MIDCPNIFTY"))
            .filter(symbol -> !symbol.contains("BANKNIFTY"))
            .filter(symbol -> !symbol.contains("NIFTYNXT"))
            .filter(symbol -> !symbol.contains("FINNIFTY"))
            .filter(symbol -> !symbol.startsWith("NIFTY27"))
            .filter(symbol -> !symbol.startsWith("NIFTY28"))
            .filter(symbol -> !symbol.startsWith("NIFTY29"))
            .filter(symbol -> !symbol.startsWith("NIFTY30"))
            .toList();
    }

    // -----------------------------------------------------------------------
    // Order params builder
    // -----------------------------------------------------------------------

    public static OrderParams buildOrderParams() {
        OrderParams orderParams = new OrderParams();
        orderParams.orderType = Constants.ORDER_TYPE_MARKET;
        orderParams.product = Constants.PRODUCT_NRML;
        orderParams.exchange = Constants.EXCHANGE_NFO;
        orderParams.validity = Constants.VALIDITY_DAY;
        orderParams.marketProtection = 1;
        return orderParams;
    }

    // -----------------------------------------------------------------------
    // DB helpers
    // -----------------------------------------------------------------------

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

    public static void sleep() {
        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Thread interrupted: {}", e.getMessage());
        }
    }
}
