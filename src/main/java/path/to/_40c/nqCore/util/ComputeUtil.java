package path.to._40c.nqCore.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import path.to._40c.nqCore.entity.Trade;
import path.to._40c.nqCore.entity.TradeCapital;
import path.to._40c.nqCore.entity.WeeklyOrderBook;
import path.to._40c.nqCore.pojo.TradeLegConfig;
import path.to._40c.nqCore.pojo.WeeklyPojo;
import path.to._40c.nqCore.repo.TradeCapitalRepository;
import path.to._40c.nqCore.service.TradeLegCache;
import path.to._40c.nqCore.service.WeeklySymbolCache;

import static path.to._40c.nqCore.util.Constants.DATE_FORMAT;
import static path.to._40c.nqCore.util.Constants.LONG;
import static path.to._40c.nqCore.util.Constants.LOSS;
import static path.to._40c.nqCore.util.Constants.NFO_COLON;
import static path.to._40c.nqCore.util.Constants.NIFTY;
import static path.to._40c.nqCore.util.Constants.SHORT;
import static path.to._40c.nqCore.util.Constants.WIN;
import static path.to._40c.nqCore.util.Constants.ZONE_ID;
import static path.to._40c.nqCore.util.Constants.INPUT_FORMATS;
import static path.to._40c.nqCore.util.Constants.OUTPUT_FORMAT;
import static path.to._40c.nqCore.util.Constants.NA;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ComputeUtil {

	private static final Logger log = LoggerFactory.getLogger(ComputeUtil.class);

    private final WeeklySymbolCache symbolCache;
    private final TradeLegCache contractCache;
    private final TradeCapitalRepository tradeCapital;

    public ComputeUtil(WeeklySymbolCache symbolCache, TradeLegCache contractCache,
            TradeCapitalRepository tradeCapital) {
        this.symbolCache = symbolCache;
        this.contractCache = contractCache;
        this.tradeCapital = tradeCapital;
    }

	public List<WeeklyPojo> buildInstrument(String signalPrice, Trade trade, boolean rollOver) {
        log.info("Starting to build instrument: signalPrice={}, tradeId={}, signalType={}, rollOver={}",
                 signalPrice, trade != null ? trade.getId() : null, trade != null ? trade.getSignalType() : null, rollOver);
        boolean isLong = LONG.equals(trade.getSignalType());
        int strikePrice = roundNFToNearestATM(signalPrice);
        List<TradeLegConfig> priorities = isLong ? contractCache.getLongLegs() : contractCache.getShortLegs();
        String symbolPrefix = rollOver ? symbolCache.get().getRolloverSymbol() : symbolCache.get().getThisWeekSymbol();
        List<WeeklyPojo> weeklyPojoList = priorities.stream()
                .map(priority -> buildWeeklyPojo(priority, strikePrice, symbolPrefix, trade))
                .collect(Collectors.toList());
        log.info("Built weeklyPojoList: size={}, rollOver={}, details={}", weeklyPojoList.size(), rollOver, weeklyPojoList);
        return weeklyPojoList;
    }

    public int roundNFToNearestATM(String price) {
        float f = Float.valueOf(price);
        int strikePrice = Math.round(f / 50f) * 50;
        log.info("roundNFToNearestATM: input={}, calculatedStrike={}", price, strikePrice);
        return strikePrice;
    }

    private WeeklyPojo buildWeeklyPojo(TradeLegConfig priority, int strikePrice, String symbolPrefix, Trade trade) {
        WeeklyPojo w = new WeeklyPojo();
        String optionSuffix = priority.getOptionType();
        int calculatedStrike = calcStrike(strikePrice, priority.getStrike());
        w.setTradedSymbol(NFO_COLON + NIFTY + symbolPrefix + calculatedStrike + optionSuffix);
        w.setMarginCalcSymbol(NIFTY + symbolPrefix + calculatedStrike + optionSuffix);
        w.setTransactionType(priority.getActionType());
        w.setMoneyness(priority.getStrike());
        w.setOptionType(optionSuffix);
        w.setLots(priority.getLots());
        w.setParentTrade(trade);
        return w;
    }

    public static int calcStrike(int price, String strikeCalcParam) {
        return switch (strikeCalcParam) {
            case "ATM+250" -> price + 250;
            case "ATM+200" -> price + 200;
            case "ATM+150" -> price + 150;
            case "ATM+100" -> price + 100;
            case "ATM+50"  -> price + 50;
            case "ATM"     -> price;
            case "ATM-50"  -> price - 50;
            case "ATM-100" -> price - 100;
            case "ATM-150" -> price - 150;
            case "ATM-200" -> price - 200;
            case "ATM-250" -> price - 250;
            default -> throw new IllegalArgumentException("Invalid Strike: " + strikeCalcParam);
        };
    }

	public void calcTradeOutcome(Trade trade) {
		if(trade != null) {
			BigDecimal entryPrice = BigDecimal.valueOf(trade.getEntrySignalPrice());
			BigDecimal exitPrice = BigDecimal.valueOf(trade.getExitSignalPrice());
			// Points for the current (final) segment only
			Double segmentPoints = 0.0d;
			if(LONG.equals(trade.getSignalType())){
				if(entryPrice.compareTo(exitPrice) < 0 || entryPrice.compareTo(exitPrice) > 0)
					segmentPoints = exitPrice.subtract(entryPrice).doubleValue();
			}
			if(SHORT.equals(trade.getSignalType())){
				if(entryPrice.compareTo(exitPrice) < 0 || entryPrice.compareTo(exitPrice) > 0)
					segmentPoints = entryPrice.subtract(exitPrice).doubleValue();
			}
			// Total = accumulated points from all prior recenter segments + current segment
			double realized = trade.getRealizedPoints() != null ? trade.getRealizedPoints() : 0.0;
			double totalPoints = realized + segmentPoints;
			trade.setPointsByTrade(totalPoints);
			trade.setTradeOutcome(totalPoints > 0 ? WIN : LOSS);
		}
	}

	public void calcPnL(Trade trade) {
		if (trade == null || trade.getPointsByTrade() == null) return;

		// Group legs by moneyness — each group is one synthetic pair (CE + PE at same strike)
		Map<String, List<WeeklyOrderBook>> pairs = trade.getWeeklyOrderBook().stream()
				.collect(Collectors.groupingBy(WeeklyOrderBook::getMoneyness));

		double totalBrokerage  = 0.0;
		double totalExpectedPnL = 0.0;
		double totalActualPnL  = 0.0;
		int    totalLots       = 0;

		for (List<WeeklyOrderBook> pairLegs : pairs.values()) {
			boolean allPresent = pairLegs.stream().allMatch(w ->
					w.getSoldPrice() != null && w.getBoughtPrice() != null &&
					w.getQuantity()  != null && w.getLots()     != null &&
					w.getTradeOpenBrokerage()  != null && w.getTradeCloseBrokerage() != null);
			if (!allPresent) continue;

			// Per-leg actual PnL is real and meaningful — set it on each leg
			pairLegs.forEach(w -> w.setActualPnL(rnd(w.getQuantity() * (w.getSoldPrice() - w.getBoughtPrice()))));

			// Pair-level aggregations
			double pairActualPnL  = pairLegs.stream().mapToDouble(WeeklyOrderBook::getActualPnL).sum();
			double pairBrokerage  = pairLegs.stream()
					.mapToDouble(w -> w.getTradeOpenBrokerage() + w.getTradeCloseBrokerage()).sum();
			// Expected PnL belongs to the pair, not individual legs.
			// Synthetic delta ≈ 1, so expected = 1 leg's quantity × pointsByTrade.
			// CE qty == PE qty by design; use the first leg as representative.
			double pairExpectedPnL = rnd(pairLegs.get(0).getQuantity() * trade.getPointsByTrade());

			totalActualPnL  += pairActualPnL;
			totalBrokerage  += pairBrokerage;
			totalExpectedPnL += pairExpectedPnL;
			totalLots       += pairLegs.get(0).getLots(); // count once per pair, not per leg
		}

		trade.setBrokerage(rnd(totalBrokerage));
		trade.setExpectedPnL(rnd(totalExpectedPnL));
		trade.setActualPnL(rnd(totalActualPnL - totalBrokerage));
		trade.setDiffPercentage(formatPnLPercent(trade.getActualPnL(), trade.getExpectedPnL()));
		trade.setLots(totalLots);
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

    public static Double rnd(double value) {
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
