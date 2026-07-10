package path.to._40c.nqCore.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import path.to._40c.nqCore.entity.LegTemplate;
import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.entity.TradeCapital;
import path.to._40c.nqCore.entity.WeeklyLeg;
import path.to._40c.nqCore.pojo.LegOrder;
import path.to._40c.nqCore.repo.TradeCapitalRepository;
import path.to._40c.nqCore.service.LegTemplateCache;
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
@Slf4j
public class ComputeUtil {
    private final WeeklySymbolCache symbolCache;
    private final LegTemplateCache templateCache;
    private final TradeCapitalRepository tradeCapital;

    public ComputeUtil(WeeklySymbolCache symbolCache, LegTemplateCache templateCache,
            TradeCapitalRepository tradeCapital) {
        this.symbolCache = symbolCache;
        this.templateCache = templateCache;
        this.tradeCapital = tradeCapital;
    }

	public List<LegOrder> buildInstrument(String signalPrice, Position trade, boolean rollOver) {
        log.info("Starting to build instrument: signalPrice={}, tradeId={}, signalType={}, rollOver={}",
                 signalPrice, trade != null ? trade.getId() : null, trade != null ? trade.getDirection() : null, rollOver);
        boolean isLong = LONG.equals(trade.getDirection());
        int atm = roundNFToNearestATM(signalPrice);
        List<LegTemplate> templates = isLong ? templateCache.getLongLegs() : templateCache.getShortLegs();
        String symbolPrefix = rollOver ? symbolCache.get().getRolloverSymbol() : symbolCache.get().getThisWeekSymbol();
        List<LegOrder> orders = templates.stream()
                .map(tpl -> buildLegOrder(tpl, atm, symbolPrefix, trade))
                .collect(Collectors.toList());
        log.info("Built leg orders: size={}, rollOver={}, details={}", orders.size(), rollOver, orders);
        return orders;
    }

    public int roundNFToNearestATM(String price) {
        float f = Float.valueOf(price);
        int strikePrice = Math.round(f / 50f) * 50;
        log.info("roundNFToNearestATM: input={}, calculatedStrike={}", price, strikePrice);
        return strikePrice;
    }

    private LegOrder buildLegOrder(LegTemplate tpl, int atm, String symbolPrefix, Position position) {
        LegOrder w = new LegOrder();
        String optionSuffix = tpl.getOptionType();
        int strike = atm + tpl.getOffsetPts();
        w.setExchangeSymbol(NFO_COLON + NIFTY + symbolPrefix + strike + optionSuffix);
        w.setInstrument(NIFTY + symbolPrefix + strike + optionSuffix);
        w.setSide(tpl.getSide());
        w.setMoneyness(formatMoneyness(tpl.getOffsetPts()));
        w.setOptionType(optionSuffix);
        w.setLots(tpl.getLots());
        w.setParentPosition(position);
        return w;
    }

    /** Format signed offset_pts as a stable label used for per-strike leg grouping in calcPnL. */
    private static String formatMoneyness(int offsetPts) {
        if (offsetPts == 0) return "ATM";
        return offsetPts > 0 ? "ATM+" + offsetPts : "ATM" + offsetPts;
    }

	/**
	 * Sets pointsPnl = bankedPoints (from all prior recenter + rollover segments) + the current
	 * (final) segment's points: (exit - baseline) for LONG, (baseline - exit) for SHORT, where
	 * baseline is the strike-center of the legs being closed. Sets result WIN/LOSS by sign.
	 * Falls back to entrySpot if baselineSpot is absent (legacy rows pre-dating the baseline split).
	 */
	public void calcTradeOutcome(Position trade) {
		if(trade != null) {
			double baseline = trade.getBaselineSpot() != null ? trade.getBaselineSpot()
					: (trade.getEntrySpot() != null ? trade.getEntrySpot() : 0.0);
			BigDecimal basePrice = BigDecimal.valueOf(baseline);
			BigDecimal exitPrice = BigDecimal.valueOf(trade.getExitSpot());
			Double segmentPoints = 0.0d;
			if(LONG.equals(trade.getDirection())){
				if(basePrice.compareTo(exitPrice) < 0 || basePrice.compareTo(exitPrice) > 0)
					segmentPoints = exitPrice.subtract(basePrice).doubleValue();
			}
			if(SHORT.equals(trade.getDirection())){
				if(basePrice.compareTo(exitPrice) < 0 || basePrice.compareTo(exitPrice) > 0)
					segmentPoints = basePrice.subtract(exitPrice).doubleValue();
			}
			double banked = trade.getBankedPoints() != null ? trade.getBankedPoints() : 0.0;
			double totalPoints = banked + segmentPoints;
			trade.setPointsPnl(totalPoints);
			trade.setResult(totalPoints > 0 ? WIN : LOSS);
		}
	}

	/**
	 * Groups legs by moneyness (each group is one CE+PE pair across all segments at that
	 * strike offset), computes per-leg actualPnl = qty × (sold − bought), aggregates pair-level
	 * actual/expected PnL and brokerage, then sets trade-level brokerage, expectedPnl,
	 * actualPnl (= total actual − brokerage), pnlCapturePct, and lots.
	 *
	 * Per-leg expectedPnl = qty × the spot points of the leg's own segment — the full move
	 * available to its CE+PE pair (synthetic delta ≈ 1). Recenter/rollover stamp it when they
	 * close a segment; legs still null here belong to the final segment and get
	 * qty × (pointsPnl − bankedPoints). Per-leg pnlCapturePct = actual/expected is therefore
	 * the leg's share of its segment's move, and the two legs of a pair sum to ≈100% minus
	 * slippage. Pair-level expectedPnl uses the first leg's quantity since CE qty == PE qty
	 * by design. Skips any pair group with missing prices or brokerage.
	 */
	public void calcPnL(Position trade) {
		if (trade == null || trade.getPointsPnl() == null) return;

		Map<String, List<WeeklyLeg>> pairs = trade.getLegs().stream()
				.collect(Collectors.groupingBy(WeeklyLeg::getMoneyness));

		double banked = trade.getBankedPoints() != null ? trade.getBankedPoints() : 0.0;
		double finalSegmentPoints = trade.getPointsPnl() - banked;

		double totalBrokerage  = 0.0;
		double totalExpectedPnL = 0.0;
		double totalActualPnL  = 0.0;
		int    totalLots       = 0;

		for (List<WeeklyLeg> pairLegs : pairs.values()) {
			boolean allPresent = pairLegs.stream().allMatch(w ->
					w.getSellFillPrice() != null && w.getBuyFillPrice() != null &&
					w.getQuantity()  != null && w.getLots()     != null &&
					w.getOpenCharges()  != null && w.getCloseCharges() != null);
			if (!allPresent) continue;

			pairLegs.forEach(w -> {
				double actualLegPnL = rnd(w.getQuantity() * (w.getSellFillPrice() - w.getBuyFillPrice()));
				w.setActualPnl(actualLegPnL);
				if (w.getExpectedPnl() == null) {
					w.setExpectedPnl(rnd(w.getQuantity() * finalSegmentPoints));
				}
				w.setPnlCapturePct(formatPnLPercent(actualLegPnL, w.getExpectedPnl()));
			});

			double pairActualPnL  = pairLegs.stream().mapToDouble(WeeklyLeg::getActualPnl).sum();
			double pairBrokerage  = pairLegs.stream()
					.mapToDouble(w -> w.getOpenCharges() + w.getCloseCharges()).sum();
			double pairExpectedPnL = rnd(pairLegs.get(0).getQuantity() * trade.getPointsPnl());

			totalActualPnL  += pairActualPnL;
			totalBrokerage  += pairBrokerage;
			totalExpectedPnL += pairExpectedPnL;
			totalLots       += pairLegs.get(0).getLots();
		}

		trade.setTotalCharges(rnd(totalBrokerage));
		trade.setExpectedPnl(rnd(totalExpectedPnL));
		trade.setActualPnl(rnd(totalActualPnL - totalBrokerage));
		trade.setPnlCapturePct(formatPnLPercent(trade.getActualPnl(), trade.getExpectedPnl()));
		trade.setLots(totalLots);
	}

	/**
	 * Below this |expected| (₹) a capture ratio is denominator noise — a near-scratch segment
	 * (~1.3 spot points at 1 lot) turns any slippage into a huge meaningless percentage,
	 * so N/A is rendered instead.
	 */
	private static final double MIN_EXPECTED_FOR_PCT = 100.0;

	public static String formatPnLPercent(Double actual, Double expected) {
        if (actual == null || expected == null) return NA;
        if (!Double.isFinite(actual) || !Double.isFinite(expected)) return NA;
        if (Math.abs(expected) < MIN_EXPECTED_FOR_PCT) return NA;
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

	public void recalculateCapital(Position closedTrade) {
		TradeCapital capital = tradeCapital.getTradeCapital();
		closedTrade.setStartingCapital(capital.getCurrentCapital());
		closedTrade.setEndingCapital(capital.getCurrentCapital() + closedTrade.getActualPnl());
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
