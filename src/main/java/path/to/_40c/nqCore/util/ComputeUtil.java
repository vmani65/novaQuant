package path.to._40c.nqCore.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import path.to._40c.nqCore.entity.LegTemplate;
import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.entity.TradeCapital;
import path.to._40c.nqCore.entity.WeeklyLeg;
import path.to._40c.nqCore.entity.SymbolConfig;
import path.to._40c.nqCore.pojo.LegOrder;
import path.to._40c.nqCore.repo.TradeCapitalRepository;
import path.to._40c.nqCore.service.LegTemplateCache;
import path.to._40c.nqCore.service.MonthlySymbolCache;
import path.to._40c.nqCore.service.WeeklySymbolCache;

import static path.to._40c.nqCore.util.Constants.DATE_FORMAT;
import static path.to._40c.nqCore.util.Constants.LONG;
import static path.to._40c.nqCore.util.Constants.LONG_MONTHLY;
import static path.to._40c.nqCore.util.Constants.LOSS;
import static path.to._40c.nqCore.util.Constants.NFO_COLON;
import static path.to._40c.nqCore.util.Constants.NIFTY;
import static path.to._40c.nqCore.util.Constants.SHORT;
import static path.to._40c.nqCore.util.Constants.SYNTH_WEEKLY;
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
    private final WeeklySymbolCache weeklySymbolCache;
    private final MonthlySymbolCache monthlySymbolCache;
    private final LegTemplateCache templateCache;
    private final TradeCapitalRepository tradeCapital;

    public ComputeUtil(WeeklySymbolCache weeklySymbolCache, MonthlySymbolCache monthlySymbolCache,
            LegTemplateCache templateCache, TradeCapitalRepository tradeCapital) {
        this.weeklySymbolCache = weeklySymbolCache;
        this.monthlySymbolCache = monthlySymbolCache;
        this.templateCache = templateCache;
        this.tradeCapital = tradeCapital;
    }

	/**
	 * SYNTH_WEEKLY build: weekly leg templates + the weekly symbol slot's current
	 * contract (advanced on rollover day by WeeklySymbolService.syncTradedContract;
	 * callers that roll sync first, so the current slot IS the target — same rule as
	 * the monthly build). Throws when the weekly book is unconfigured so the fan-out's
	 * per-book error isolation fails ONLY this book, loudly, instead of trading a
	 * wrong instrument.
	 */
	public List<LegOrder> buildWeeklyInstrument(String signalPrice, Position trade) {
        log.info("Starting to build weekly instrument: signalPrice={}, tradeId={}, signalType={}",
                 signalPrice, trade != null ? trade.getId() : null, trade != null ? trade.getDirection() : null);
        boolean isLong = LONG.equals(trade.getDirection());
        int atm = roundNFToNearestATM(signalPrice);
        List<LegTemplate> templates = isLong ? templateCache.getLongLegs() : templateCache.getShortLegs();
        if (templates.isEmpty())
            throw new IllegalStateException("SYNTH_WEEKLY has no " + (isLong ? LONG : SHORT)
                    + " leg templates configured — add weekly legs in the manifestation UI");
        SymbolConfig weeklyCfg = weeklySymbolCache.get();
        if (weeklyCfg == null)
            throw new IllegalStateException("SYNTH_WEEKLY has no weekly symbol configured — save weekly symbols first");
        String symbolPrefix = weeklyCfg.getThisWeekSymbol();
        List<LegOrder> orders = templates.stream()
                .map(tpl -> buildLegOrder(tpl, atm, symbolPrefix, trade, SYNTH_WEEKLY))
                .collect(Collectors.toList());
        log.info("Built weekly leg orders: size={}, details={}", orders.size(), orders);
        return orders;
    }

	/**
	 * LONG_MONTHLY build: monthly leg templates + the monthly symbol slot's current
	 * contract (kept DTE-correct by MonthlySymbolService). No rollover-prefix variant —
	 * the monthly roll syncs the contract first, so the current slot IS the target.
	 * Throws when the monthly book is unconfigured so the fan-out's per-book error
	 * isolation fails ONLY this book, loudly, instead of trading a wrong instrument.
	 */
	public List<LegOrder> buildMonthlyInstrument(String signalPrice, Position trade) {
        log.info("Starting to build monthly instrument: signalPrice={}, tradeId={}, signalType={}",
                 signalPrice, trade != null ? trade.getId() : null, trade != null ? trade.getDirection() : null);
        boolean isLong = LONG.equals(trade.getDirection());
        int atm = roundNFToNearestATM(signalPrice);
        List<LegTemplate> templates = isLong ? templateCache.getMonthlyLongLegs() : templateCache.getMonthlyShortLegs();
        if (templates.isEmpty())
            throw new IllegalStateException("LONG_MONTHLY has no " + (isLong ? LONG : SHORT)
                    + " leg templates configured — add monthly legs in the manifestation UI");
        SymbolConfig monthlyCfg = monthlySymbolCache.get();
        if (monthlyCfg == null)
            throw new IllegalStateException("LONG_MONTHLY has no monthly symbol configured — save monthly symbols first");
        String symbolPrefix = monthlyCfg.getThisWeekSymbol();
        List<LegOrder> orders = templates.stream()
                .map(tpl -> buildLegOrder(tpl, atm, symbolPrefix, trade, LONG_MONTHLY))
                .collect(Collectors.toList());
        log.info("Built monthly leg orders: size={}, details={}", orders.size(), orders);
        return orders;
    }

	/**
	 * Contract prefix (e.g. NIFTY26812) the next weekly build will trade, or null when
	 * the weekly book is unconfigured. The rollover no-churn guard compares held legs
	 * against this: legs already on this contract mean there is nothing to roll.
	 */
	public String weeklyContractPrefix() {
        SymbolConfig cfg = weeklySymbolCache.get();
        return cfg == null || cfg.getThisWeekSymbol() == null ? null : NIFTY + cfg.getThisWeekSymbol();
    }

	/** Monthly counterpart of weeklyContractPrefix (e.g. NIFTY26AUG), null when unconfigured. */
	public String monthlyContractPrefix() {
        SymbolConfig cfg = monthlySymbolCache.get();
        return cfg == null || cfg.getThisWeekSymbol() == null ? null : NIFTY + cfg.getThisWeekSymbol();
    }

    public int roundNFToNearestATM(String price) {
        float f = Float.valueOf(price);
        int strikePrice = Math.round(f / 50f) * 50;
        log.info("roundNFToNearestATM: input={}, calculatedStrike={}", price, strikePrice);
        return strikePrice;
    }

    private LegOrder buildLegOrder(LegTemplate tpl, int atm, String symbolPrefix, Position position, String book) {
        LegOrder w = new LegOrder();
        String optionSuffix = tpl.getOptionType();
        int strike = atm + tpl.getOffsetPts();
        w.setBook(book);
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
	 * Sets pointsPnl = banked points (from all prior recenter + rollover segments) + the current
	 * (final) segment's points: (exit - baseline) for LONG, (baseline - exit) for SHORT. The
	 * chain is per book — the SYNTH_WEEKLY chain (baselineSpot/bankedPoints) when weekly legs
	 * traded, else the LONG_MONTHLY chain — and both telescope to the same total spot move, so
	 * pointsPnl is the signal's spot move regardless of which chain computed it. Falls back to
	 * entrySpot if the baseline is absent (legacy rows pre-dating the baseline split).
	 * Orphan-origin trades (a PARTIAL open whose surviving legs were later flattened) are skipped:
	 * synthetic points assume a complete CE+PE pair, so pointsPnl stays null and calcPnL derives
	 * the WIN/LOSS result from actual leg PnL instead.
	 *
	 * result is WIN/LOSS by points sign only when weekly legs traded (the delta-1 synthetic
	 * tracks spot). A monthly-only signal leaves result null here: a bought option's rupee
	 * outcome diverges from the spot sign (theta can turn a small spot-points winner into a
	 * rupee loser), so calcPnL derives WIN/LOSS from net actual PnL instead.
	 */
	public void calcTradeOutcome(Position trade) {
		if(trade != null) {
			if (trade.getLegs().stream().anyMatch(LegScope::neverTraded)) {
				log.info("Orphan-origin trade id={} — synthetic points skipped; result derives from leg PnL in calcPnL", trade.getId());
				return;
			}
			boolean weeklyTraded = trade.getLegs().stream().anyMatch(l -> SYNTH_WEEKLY.equals(LegScope.bookOf(l)));
			Double chainBaseline = weeklyTraded ? trade.getBaselineSpot()
					: (trade.getMonthlyBaselineSpot() != null ? trade.getMonthlyBaselineSpot() : trade.getBaselineSpot());
			Double chainBanked = weeklyTraded ? trade.getBankedPoints()
					: (trade.getMonthlyBankedPoints() != null ? trade.getMonthlyBankedPoints() : trade.getBankedPoints());
			double baseline = chainBaseline != null ? chainBaseline
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
			double banked = chainBanked != null ? chainBanked : 0.0;
			double totalPoints = banked + segmentPoints;
			trade.setPointsPnl(totalPoints);
			if (!weeklyTraded) {
				log.info("Monthly-only trade id={} — pointsPnl={} recorded; WIN/LOSS derives from rupee PnL in calcPnL",
						trade.getId(), totalPoints);
				return;
			}
			trade.setResult(totalPoints > 0 ? WIN : LOSS);
		}
	}

	/**
	 * Runs per BOOK over the shared row, then sums: within each book, groups that book's legs
	 * by moneyness (each group is one CE+PE pair across all segments at that strike offset —
	 * per book, because a weekly ATM leg and a monthly ATM leg must never pair), computes
	 * per-leg actualPnl = qty × (sold − bought), aggregates pair-level actual/expected PnL and
	 * brokerage, then sets trade-level brokerage, expectedPnl, actualPnl (= total actual −
	 * brokerage), pnlCapturePct, and lots as CROSS-BOOK CUMULATIVE totals — the signal's
	 * numbers, per the one-position-per-signal model. Each book's expected-PnL denominator
	 * uses its OWN banked chain and factor (monthly 0.5 futures-equivalent, weekly 1.0).
	 *
	 * Per-leg expectedPnl = qty × the spot points of the leg's own segment — the full move
	 * available to its CE+PE pair (synthetic delta ≈ 1). Recenter/rollover stamp it when they
	 * close a segment; legs still null here belong to the final segment and get
	 * qty × (pointsPnl − bankedPoints). Per-leg pnlCapturePct = actual/expected is therefore
	 * the leg's share of its segment's move, and the two legs of a pair sum to ≈100% minus
	 * slippage. Pair-level expectedPnl uses the first leg's quantity since CE qty == PE qty
	 * by design. Skips any pair group with missing prices or brokerage.
	 *
	 * Orphan-origin trades (a PARTIAL open — see neverTraded) are the exception to pair
	 * completeness: legs that never existed at the broker are excluded from the group, actual
	 * PnL and charges are computed from the surviving legs alone, expectedPnl/capture stay
	 * null (a single leg has no full-move denominator), and WIN/LOSS falls back to the sign
	 * of net actual PnL since calcTradeOutcome leaves result null for these trades.
	 *
	 * LONG_MONTHLY (single bought leg, 2 monthly lots ≈ delta 1 per futures-equivalent):
	 * expected PnL uses (qty / 2) × points — the futures-equivalent yardstick — so both books'
	 * pnlCapturePct read on the same scale: a monthly leg fully capturing the move reads ~100%,
	 * gamma can push it above, theta below. WIN/LOSS comes from the rupee fallback below since
	 * calcTradeOutcome leaves result null for this book.
	 */
	public void calcPnL(Position trade) {
		if (trade == null) return;
		boolean orphanOrigin = trade.getLegs().stream().anyMatch(LegScope::neverTraded);
		if (trade.getPointsPnl() == null && !orphanOrigin) return;

		double totalBrokerage  = 0.0;
		double totalExpectedPnL = 0.0;
		double totalActualPnL  = 0.0;
		int    totalLots       = 0;
		boolean anyFullPair    = false;
		boolean anyLegComputed = false;

		for (String book : List.of(SYNTH_WEEKLY, LONG_MONTHLY)) {
			List<WeeklyLeg> bookLegs = LegScope.of(trade, book);
			if (bookLegs.isEmpty()) continue;

			double banked = LONG_MONTHLY.equals(book)
					? (trade.getMonthlyBankedPoints() != null ? trade.getMonthlyBankedPoints()
							: (trade.getBankedPoints() != null ? trade.getBankedPoints() : 0.0))
					: (trade.getBankedPoints() != null ? trade.getBankedPoints() : 0.0);
			Double finalSegmentPoints = trade.getPointsPnl() != null ? trade.getPointsPnl() - banked : null;
			double expectedQtyFactor = LONG_MONTHLY.equals(book) ? 0.5 : 1.0;

			Map<String, List<WeeklyLeg>> pairs = bookLegs.stream()
					.collect(Collectors.groupingBy(WeeklyLeg::getMoneyness));

			for (List<WeeklyLeg> pairLegs : pairs.values()) {
				List<WeeklyLeg> traded = pairLegs.stream().filter(w -> !LegScope.neverTraded(w)).toList();
				if (traded.isEmpty()) continue;
				boolean allPresent = traded.stream().allMatch(w ->
						w.getSellFillPrice() != null && w.getBuyFillPrice() != null &&
						w.getQuantity()  != null && w.getLots()     != null &&
						w.getOpenCharges()  != null && w.getCloseCharges() != null);
				if (!allPresent) continue;

				boolean fullPair = traded.size() == pairLegs.size() && finalSegmentPoints != null;
				final Double segmentForBook = finalSegmentPoints;
				traded.forEach(w -> {
					double actualLegPnL = rnd(w.getQuantity() * (w.getSellFillPrice() - w.getBuyFillPrice()));
					w.setActualPnl(actualLegPnL);
					if (fullPair) {
						if (w.getExpectedPnl() == null) {
							w.setExpectedPnl(rnd(w.getQuantity() * expectedQtyFactor * segmentForBook));
						}
						w.setPnlCapturePct(formatPnLPercent(actualLegPnL, w.getExpectedPnl()));
					}
				});
				anyLegComputed = true;

				totalActualPnL += traded.stream().mapToDouble(WeeklyLeg::getActualPnl).sum();
				totalBrokerage += traded.stream()
						.mapToDouble(w -> w.getOpenCharges() + w.getCloseCharges()).sum();
				totalLots      += traded.get(0).getLots();
				if (fullPair) {
					totalExpectedPnL += rnd(traded.get(0).getQuantity() * expectedQtyFactor * trade.getPointsPnl());
					anyFullPair = true;
				}
			}
		}

		trade.setTotalCharges(rnd(totalBrokerage));
		trade.setExpectedPnl(anyFullPair ? rnd(totalExpectedPnL) : null);
		trade.setActualPnl(rnd(totalActualPnL - totalBrokerage));
		trade.setPnlCapturePct(formatPnLPercent(trade.getActualPnl(), trade.getExpectedPnl()));
		trade.setLots(totalLots);
		if (trade.getResult() == null && anyLegComputed) {
			trade.setResult(trade.getActualPnl() > 0 ? WIN : LOSS);
		}
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

	/**
	 * ONE capital-chain step per signal: startingCapital/endingCapital stamp the row with the
	 * account capital before and after the signal's CUMULATIVE actualPnl (all books), and
	 * currentCapital advances once. Runs only when the row is terminal (PostTradeService
	 * gates on LegScope.isTerminal), so a signal with an unconfirmed leg never half-stamps.
	 * The sizing fields (possibleLots, currentRiskPerLot) are weekly-margin-regime math —
	 * definedRiskPerLot means NRML margin per synthetic lot — so they update only when the
	 * signal traded weekly legs, sized by the WEEKLY lots (a monthly "lot" costs ~premium,
	 * an order of magnitude less), and an unset/zero definedRiskPerLot skips them instead
	 * of poisoning possibleLots via Infinity-cast or an unboxing NPE.
	 */
	public void recalculateCapital(Position closedTrade) {
		TradeCapital capital = tradeCapital.getTradeCapital();
		closedTrade.setStartingCapital(capital.getCurrentCapital());
		closedTrade.setEndingCapital(capital.getCurrentCapital() + closedTrade.getActualPnl());
		capital.setCurrentCapital(closedTrade.getEndingCapital());
		int weeklyLots = LegScope.of(closedTrade, SYNTH_WEEKLY).stream()
				.filter(w -> !LegScope.neverTraded(w) && w.getLots() != null)
				.mapToInt(WeeklyLeg::getLots).max().orElse(0);
		if (weeklyLots <= 0) {
			tradeCapital.save(capital);
			return;
		}
		Integer riskPerLot = capital.getDefinedRiskPerLot();
		if (riskPerLot == null || riskPerLot <= 0) {
			log.warn("recalculateCapital: definedRiskPerLot is {} — capital chain updated, lot sizing skipped", riskPerLot);
			tradeCapital.save(capital);
			return;
		}
		int possibleLots = (int) (capital.getCurrentCapital() / riskPerLot);
		capital.setPossibleLots(possibleLots > weeklyLots ? possibleLots : 0);
		capital.setCurrentRiskPerLot((int)(closedTrade.getEndingCapital() / riskPerLot));
		tradeCapital.save(capital);
	}

    public String getDtTimeNow() {
    	return LocalDateTime.now(ZoneId.of(ZONE_ID)).format(DateTimeFormatter.ofPattern(DATE_FORMAT));
    }
}
