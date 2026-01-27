package path.to._40c.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import path.to._40c.entity.Trade;
import path.to._40c.entity.TradeCapital;
import path.to._40c.pojo.ContractPriority;
import path.to._40c.pojo.WeeklyPojo;
import path.to._40c.repo.TradeCapitalRepository;
import path.to._40c.service.ContractPriorityCache;
import path.to._40c.service.WeeklySymbolCache;

import static path.to._40c.util.Constants.CE;
import static path.to._40c.util.Constants.DATE_FORMAT;
import static path.to._40c.util.Constants.LONG;
import static path.to._40c.util.Constants.LOSS;
import static path.to._40c.util.Constants.NFO_COLON;
import static path.to._40c.util.Constants.NIFTY;
import static path.to._40c.util.Constants.SHORT;
import static path.to._40c.util.Constants.WIN;
import static path.to._40c.util.Constants.ZONE_ID;
import static path.to._40c.util.Constants.INPUT_FORMATS;
import static path.to._40c.util.Constants.OUTPUT_FORMAT;
import static path.to._40c.util.Constants.LOT_SIZE;
import static path.to._40c.util.Constants.NA;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Service
public class ComputeUtil {

	private static final Logger log = LoggerFactory.getLogger(ComputeUtil.class);

	@Autowired
    private WeeklySymbolCache symbolCache;
    
    @Autowired
    private ContractPriorityCache contractCache;
    
    @Autowired
    private TradeCapitalRepository tradeCapital;
	
	public List<WeeklyPojo> buildInstrument(String signalPrice, String type, Trade trade, boolean rollOver) {
        log.info("Starting to build instruction for the order: signalPrice={}, type={}, tradeId={}, rollOver={}", 
                 signalPrice, type, trade != null ? trade.getId() : null, rollOver);        
        boolean isLong = LONG.equals(trade.getSignalType());
        int strikePrice = Integer.valueOf(isLong ? roundNFToNearestATMForCE(signalPrice) : roundNFToNearestATMForPE(signalPrice));        
        List<ContractPriority> priorities = isLong ? contractCache.getLongPriorities() : contractCache.getShortPriorities();        
        String symbolPrefix = rollOver ? symbolCache.get().getRolloverSymbol() : symbolCache.get().getThisWeekSymbol();        
        List<WeeklyPojo> weeklyPojoList = priorities.stream().map(priority -> buildWeeklyPojo(priority, strikePrice, type, symbolPrefix, trade, rollOver)).collect(Collectors.toList());        
        log.info("Final built weeklyPojoList size: {} {} Pojo Details: {}", weeklyPojoList.size(), rollOver ? "With ChildRollover" : "",weeklyPojoList);        
        return weeklyPojoList;
    }
	
	public int roundNFToNearestATMForCE(String price) {
        float f = Float.valueOf(price);
        int strikePrice = (int) (Math.floor(f/50f)) * 50;
        log.info("roundNFToNearestATMForCE calculated strike price is " + strikePrice);
        return strikePrice;
    }

    public int roundNFToNearestATMForPE(String price) {
        float f = Float.valueOf(price);
        int strikePrice = (int) (Math.ceil(f/50f)) * 50;
        log.info("roundNFToNearestATMForPE calculated strike price is " + strikePrice);
        return strikePrice;
    }
    
    private WeeklyPojo buildWeeklyPojo(ContractPriority priority, int strikePrice, String type, String symbolPrefix, Trade trade, boolean rollOver) {
        WeeklyPojo w = new WeeklyPojo();
        int calculatedStrike = calcStrike(strikePrice, priority.getStrike(), rollOver, type);        
        w.setTradedSymbol(NFO_COLON + NIFTY + symbolPrefix + calculatedStrike + type);
        w.setMarginCalcSymbol(NIFTY + symbolPrefix + calculatedStrike + type);
        w.setTransactionType(priority.getActionType());
        w.setMoneyness(priority.getStrike());
        w.setLots(priority.getLots());
        w.setParentTrade(trade);        
        return w;
    }
	
    public static int calcStrike(int price, String strikeCalcParam, boolean roundToHundred, String type) {
        int basePrice = price;
        if (roundToHundred && price % 100 == 50) {
            basePrice = CE.equals(type) ? price - 50 : price + 50;
        }        
        int result = switch (strikeCalcParam) {
            case "ATM+250" -> basePrice + 250;
            case "ATM+200" -> basePrice + 200;
            case "ATM+150" -> basePrice + 150;
            case "ATM" -> basePrice;
            case "ATM-150" -> basePrice - 150;
            case "ATM-200" -> basePrice - 200;
            case "ATM-250" -> basePrice - 250;
            default -> throw new IllegalArgumentException("Invalid Strike: " + strikeCalcParam);
        };        
        if (roundToHundred && result % 100 == 50 && 
            (strikeCalcParam.contains("150") || strikeCalcParam.contains("250"))) {
            result = strikeCalcParam.contains("+") ? result + 50 : result - 50;
        }        
        return result;
    }
    
	public void calcTradeOutcome(Trade trade) {
		if(trade != null) {
			BigDecimal entryPrice = BigDecimal.valueOf(trade.getEntrySignalPrice());
			BigDecimal exitPrice = BigDecimal.valueOf(trade.getExitSignalPrice());
			Double points = 0.0d;
			if(LONG.equals(trade.getSignalType())){
				if(entryPrice.compareTo(exitPrice) < 0 || entryPrice.compareTo(exitPrice) > 0)
					points = exitPrice.subtract(entryPrice).doubleValue();
				else
					points = 0.0d;
			}
			if(SHORT.equals(trade.getSignalType())){
				if(entryPrice.compareTo(exitPrice) < 0 || entryPrice.compareTo(exitPrice) > 0)
					points = entryPrice.subtract(exitPrice).doubleValue();				
				else
					points = 0.0d;			
			}
			trade.setPointsByTrade(points);
			trade.setTradeOutcome(points > 0 ? WIN :LOSS);
		}
	}
	
	public void calcPnL(Trade trade) {		
		if(trade != null) {
			AtomicReference<Double> totalBrokerage = new AtomicReference<>(0.0);
			AtomicReference<Double> expectedPnL = new AtomicReference<>(0.0);
			AtomicReference<Double> actualPnL = new AtomicReference<>(0.0);
			AtomicReference<Integer> lots = new AtomicReference<>(0);
			trade.getWeeklyOrderBook().forEach(w -> {
				if(w.getSoldPrice() != null && w.getBoughtPrice() != null && trade.getPointsByTrade() != null 
						&& w.getTradeOpenBrokerage() !=null && w.getTradeCloseBrokerage() != null && w.getLots() != null) {
					Double d = w.getSoldPrice() - w.getBoughtPrice();
					w.setExpectedPnL(round1dp(LOT_SIZE * trade.getPointsByTrade()));
					w.setActualPnL(round1dp(LOT_SIZE*d));
					w.setDiffPercentage(formatPnLPercent(w.getActualPnL(), w.getExpectedPnL()));
					totalBrokerage.updateAndGet(b -> b + w.getTradeOpenBrokerage() + w.getTradeCloseBrokerage());
			        expectedPnL.updateAndGet(e -> e + w.getExpectedPnL());
			        actualPnL.updateAndGet(p -> p + w.getActualPnL());
			        lots.updateAndGet(l -> l + w.getLots());
				}
			});		
			trade.setBrokerage(round1dp(totalBrokerage.get()));
			trade.setExpectedPnL(round1dp(expectedPnL.get()));		
			trade.setActualPnL(round1dp(actualPnL.get() - totalBrokerage.get()));
			trade.setDiffPercentage(formatPnLPercent(trade.getActualPnL(), trade.getExpectedPnL()));
			trade.setLots(lots.get());
		}
	}
	
	public static String formatPnLPercent(Double actual, Double expected) {
        if (actual == null || expected == null) return NA;
        if (!Double.isFinite(actual) || !Double.isFinite(expected)) return NA;
        if (Math.abs(expected) < 1e-9) return NA;
        double pct = (actual / expected) * 100.0;
        return format1dpPercent(pct);
    }

    public static String formatPnLPercent(Double actual) {
        if (actual == null || !Double.isFinite(actual)) return NA;
        return format1dpPercent(actual);
    }

    private static String format1dpPercent(double value) {
        java.math.BigDecimal bd = new java.math.BigDecimal(Double.toString(value));
        bd = bd.setScale(1, java.math.RoundingMode.HALF_UP);
        return String.format("%.1f%%", bd.doubleValue());
    }
    
    public static Double round1dp(double value) {
        java.math.BigDecimal bd = new java.math.BigDecimal(Double.toString(value));
        bd = bd.setScale(1, java.math.RoundingMode.HALF_UP);
        double result = bd.doubleValue();
        if (Double.doubleToRawLongBits(result) == Double.doubleToRawLongBits(-0.0d)) {
            result = 0.0d;
        }
        return result;
    }
    
	public String toStd(String dateTimeStr) {
		if (dateTimeStr == null || dateTimeStr.trim().isEmpty()) return dateTimeStr;		    
		    String trimmed = dateTimeStr.trim();		    
		return INPUT_FORMATS.stream()
		    .map(formatter -> {
		       try {
		           return LocalDateTime.parse(trimmed, formatter).format(OUTPUT_FORMAT);
		       } catch (DateTimeParseException e) {
		           return null;
		       }
		    }).filter(result -> result != null).findFirst().orElseGet(() -> {
		       log.warn("Unable to parse date format, storing as-is: '{}'", dateTimeStr);
		       return dateTimeStr;
		});
	}
	
	public void recalculateCapital(Trade closedTrade) {
		TradeCapital capital = tradeCapital.getTradeCapital();
		closedTrade.setStartingCapital(capital.getCurrentCapital());
		closedTrade.setEndingCapital(capital.getCurrentCapital() + closedTrade.getActualPnL());
		capital.setCurrentCapital(closedTrade.getEndingCapital());
		int currentLots = closedTrade.getLots();
		int possibleLots = (int) (capital.getCurrentCapital() / capital.getDefinedRiskPerLot());
		capital.setPossibleLots(possibleLots > currentLots ? possibleLots : 0);
		capital.setCurrentRiskPerLot((int)(closedTrade.getEndingCapital() / capital.getDefinedRiskPerLot()));
		tradeCapital.save(capital);
	}
	
    public String getDtTimeNow() {
    	return LocalDateTime.now(ZoneId.of(ZONE_ID)).format(DateTimeFormatter.ofPattern(DATE_FORMAT));
    }
}