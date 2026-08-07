package path.to._40c.nqCore.service;

import static path.to._40c.nqCore.util.Constants.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.zerodhatech.models.Quote;

import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.entity.WeeklyLeg;
import path.to._40c.nqCore.pojo.LegOrder;
import path.to._40c.nqCore.repo.PositionRepository;
import path.to._40c.nqCore.util.ComputeUtil;
import path.to._40c.nqCore.util.PositionUtil;
import path.to._40c.nqCore.util.PositionUtil.ExecResult;

/**
 * Rolls a book's LIVE position onto its next contract: close every leg in hand, re-open
 * the same structure at the current ATM on the target contract, and re-strike the
 * points accounting exactly like a recenter — bank the closed segment's points into
 * bankedPoints and reset baselineSpot to the roll spot. entrySpot/exitSpot are left
 * untouched (immutable original-entry / final-exit reference for reporting).
 *
 * Book isolation is structural: each public method looks up ONLY its own book's LIVE
 * position, so the weekly expiry-day roll can never touch a monthly position and the
 * monthly roll can never touch the weekly synthetic.
 *
 * - rollOverWeekly: 14:47 expiry-day trigger; new legs use the weekly ROLLOVER symbol.
 * - rollOverMonthly: nqTicker-triggered (cadence logic lives there); new legs use the
 *   monthly symbol row's CURRENT contract, which MonthlySymbolService keeps pointed
 *   at the DTE-correct series — callers sync it before rolling. Closed legs are
 *   stamped with the book's futures-equivalent expected factor (qty/2) so capture
 *   stays on the same scale as the rest of LONG_MONTHLY accounting.
 */
@Service
@Slf4j
public class PositionRolloverService {
    private final PositionRepository positionRepository;
    private final PositionUtil positionUtil;
    private final ComputeUtil computeUtil;
    private final PostTradeService postTradeService;

    public PositionRolloverService(PositionRepository positionRepository, PositionUtil positionUtil, ComputeUtil computeUtil,
                                PostTradeService postTradeService) {
        this.positionRepository = positionRepository;
        this.positionUtil = positionUtil;
        this.computeUtil = computeUtil;
        this.postTradeService = postTradeService;
    }

    /** Weekly expiry-day roll of the SYNTH_WEEKLY book — new legs on the weekly rollover symbol. */
    public void rollOverWeekly(String signalPrice) {
        rollOverBook(SYNTH_WEEKLY, signalPrice, 1.0,
                trade -> computeUtil.buildWeeklyInstrument(signalPrice, trade, true));
    }

    /**
     * Monthly roll of the LONG_MONTHLY book — sell whatever is in hand, buy the same
     * structure at the current ATM on the latest monthly contract (per the symbol row,
     * synced by MonthlySymbolService before this is called). expectedFactor 0.5 keeps
     * the closed segment's per-leg expected PnL on the futures-equivalent scale.
     */
    public void rollOverMonthly(String signalPrice) {
        rollOverBook(LONG_MONTHLY, signalPrice, 0.5,
                trade -> computeUtil.buildMonthlyInstrument(signalPrice, trade));
    }

    /**
     * 1. Close the Live Position first.
     * 2. Proceed with opening new trades only if all closes succeeded.
     * 3. Open live trades. Create a childOrderBook and add to the Parent and Save
     *
     * Re-strike accounting is identical to a recenter: bank the closed segment's points into
     * bankedPoints and reset baselineSpot to the rollover spot. Each closed leg is stamped
     * with expectedPnl = qty × expectedFactor × the segment's spot points, the denominator
     * calcPnL later uses for that leg's pnlCapturePct (its share of the segment move).
     *
     * No-churn guard: if the target legs are exactly the instruments already held (same
     * contract, same strikes), the roll is skipped — closing and re-buying the identical
     * position would only pay the spread twice.
     */
    private void rollOverBook(String book, String signalPrice, double expectedFactor,
                              Function<Position, List<LegOrder>> legBuilder) {
        Position tradeToRollOver = positionUtil.findLiveTradesWithLiveOrderBooks(book);
        if (tradeToRollOver == null) {
            log.info("No Live {} trades to rollover.", book);
            return;
        }
        log.info("Live {} Position being rolled over is: {}", book, tradeToRollOver);
        List<LegOrder> legOrder = legBuilder.apply(tradeToRollOver);
        Set<String> targetIns = legOrder.stream().map(LegOrder::getInstrument).collect(Collectors.toSet());
        Set<String> heldIns = tradeToRollOver.getLegs().stream().map(WeeklyLeg::getInstrument).collect(Collectors.toSet());
        if (targetIns.equals(heldIns)) {
            log.warn("Rollover {} skipped — position already holds the target contracts at this ATM ({}); nothing to roll",
                    book, targetIns);
            return;
        }

        Instant closeStart = Instant.now();
        String[] liveIns = tradeToRollOver.getLegs().stream().map(WeeklyLeg::getExchangeSymbol).toArray(String[]::new);
        Map<String, Quote> quotesOfToCloseTrade = positionUtil.getQuote(liveIns);
        if (quotesOfToCloseTrade.isEmpty()) {
            log.error("Quote map is empty for close leg — aborting rollover (auth missing or Kite error)");
            return;
        }
        AtomicBoolean allClosesSucceeded = new AtomicBoolean(true);

        List<CompletableFuture<Void>> closeFuts = tradeToRollOver.getLegs().stream()
            .map(toClose -> CompletableFuture.runAsync(() -> {
                log.debug("WeeklyLeg to rollover is: {}", toClose);
                String oppositeTransaction = BUY.equals(toClose.getSide()) ? SELL : BUY;
                Quote q = quotesOfToCloseTrade.get(toClose.getExchangeSymbol());
                if (q != null) {
                    if (BUY.equals(oppositeTransaction))
                        toClose.setBuyIntendedPrice(q.lastPrice);
                    else
                        toClose.setSellIntendedPrice(q.lastPrice);
                }
                try {
                    ExecResult er = positionUtil.placeAggressiveOrder(q, toClose.getInstrument(), oppositeTransaction, toClose.getQuantity(), "EXIT");
                    if (er.aggregateOrderIds() != null && !er.aggregateOrderIds().isEmpty()) {
                        toClose.setCloseOrderId(er.aggregateOrderIds());
                    }
                    toClose.setCloseSpreadPaid(PositionUtil.effectiveSpreadPaid(q, oppositeTransaction, er.weightedAvgFillPrice()));
                    PositionUtil.alertIfMonthlySpreadExcessive(book, toClose.getInstrument(), "ROLL-EXIT", toClose.getCloseSpreadPaid());
                    if (er.fullyFilled()) {
                        toClose.setStatus(CLOSED);
                    } else {
                        log.error("[EXIT] {} rollover close NOT fully filled: filled={}/{} term={}",
                            toClose.getInstrument(), er.totalFilled(), er.totalRequested(), er.terminalStatus());
                        toClose.setStatus(FAILED);
                        allClosesSucceeded.set(false);
                    }
                } catch (Exception e) {
                    log.error("Exception closing order during rollover for {}: {}", toClose.getInstrument(), e.getMessage(), e);
                    allClosesSucceeded.set(false);
                }
            }, PositionUtil.LEG_EXEC))
            .toList();
        CompletableFuture.allOf(closeFuts.toArray(new CompletableFuture[0])).join();

        long closeMs = Duration.between(closeStart, Instant.now()).toMillis();
        if (!allClosesSucceeded.get()) {
            log.error("Rollover aborted - not all positions closed successfully");
            log.info("[PERFORMANCE] rollover {} | close={}ms | open=0ms | total={}ms (aborted)", book, closeMs, closeMs);
            return;
        }
        positionUtil.setTradeExecPricesForRollOver(tradeToRollOver, true, false);
        Instant openStart = Instant.now();
        List<WeeklyLeg> childOrderBook = new ArrayList<WeeklyLeg>();
        double rolloverPrice = signalPrice != null ? Double.valueOf(signalPrice) : 0.0;
        double base = tradeToRollOver.getBaselineSpot() != null ? tradeToRollOver.getBaselineSpot() : rolloverPrice;
        double segment = SHORT.equals(tradeToRollOver.getDirection()) ? base - rolloverPrice : rolloverPrice - base;
        double banked = tradeToRollOver.getBankedPoints() != null ? tradeToRollOver.getBankedPoints() : 0.0;
        tradeToRollOver.setBankedPoints(Math.round((banked + segment) * 100.0) / 100.0);
        tradeToRollOver.setBaselineSpot(rolloverPrice);
        tradeToRollOver.getLegs().forEach(leg -> leg.setExpectedPnl(ComputeUtil.rnd(leg.getQuantity() * expectedFactor * segment)));
        log.info("rollover {} re-strike | segment={}pts bankedPoints={} newBaseline={} expectedFactor={}",
            book, Math.round(segment * 100.0) / 100.0, tradeToRollOver.getBankedPoints(), rolloverPrice, expectedFactor);
        String[] ltpIns = legOrder.stream().map(LegOrder::getExchangeSymbol).toArray(String[]::new);
        log.debug("OpenTrade ltpIns is: {}", Arrays.toString(ltpIns));
        Map<String, Quote> quotesOfToOpenTrade = positionUtil.getQuote(ltpIns);
        if (quotesOfToOpenTrade.isEmpty()) {
            log.error("Quote map is empty for open leg — aborting rollover open (close already executed, manual intervention needed)");
            long abortOpenMs = Duration.between(openStart, Instant.now()).toMillis();
            log.info("[PERFORMANCE] rollover {} | close={}ms | open={}ms | total={}ms (aborted at open quote)", book, closeMs, abortOpenMs, closeMs + abortOpenMs);
            return;
        }
        List<CompletableFuture<Void>> openFuts = legOrder.stream()
            .map(w -> CompletableFuture.runAsync(() -> {
                log.debug("LegOrder to place order is: {}", w);
                int totalQty = w.getLots() * LOT_SIZE;
                try {
                    ExecResult er = positionUtil.placeAggressiveOrder(quotesOfToOpenTrade.get(w.getExchangeSymbol()), w.getInstrument(), w.getSide(), totalQty, "ENTRY");
                    if (er.aggregateOrderIds() != null && !er.aggregateOrderIds().isEmpty()) {
                        w.setOpenOrderId(er.aggregateOrderIds());
                    }
                    w.setOpenSpreadPaid(PositionUtil.effectiveSpreadPaid(
                            quotesOfToOpenTrade.get(w.getExchangeSymbol()), w.getSide(), er.weightedAvgFillPrice()));
                    PositionUtil.alertIfMonthlySpreadExcessive(book, w.getInstrument(), "ROLL-ENTRY", w.getOpenSpreadPaid());
                    w.setOpenFullyFilled(er.fullyFilled());
                    if (!er.fullyFilled()) {
                        log.error("[ENTRY] {} rollover open NOT fully filled: filled={}/{} term={}",
                            w.getInstrument(), er.totalFilled(), er.totalRequested(), er.terminalStatus());
                    }
                } catch (Exception e) {
                    log.error("Exception opening order during rollover for {}: {}", w.getInstrument(), e.getMessage(), e);
                }
            }, PositionUtil.LEG_EXEC))
            .toList();
        CompletableFuture.allOf(openFuts.toArray(new CompletableFuture[0])).join();
        long openMs = Duration.between(openStart, Instant.now()).toMillis();
        log.info("[PERFORMANCE] rollover {} | close={}ms | open={}ms | total={}ms (excl. fill retrieval)", book, closeMs, openMs, closeMs + openMs);

        legOrder.forEach(pojo -> {
            WeeklyLeg b = new WeeklyLeg();
            b.setInstrument(pojo.getInstrument());
            b.setExchangeSymbol(pojo.getExchangeSymbol());
            b.setSide(pojo.getSide());
            b.setOpenOrderId(pojo.getOpenOrderId());
            b.setOpenSpreadPaid(pojo.getOpenSpreadPaid());
            b.setPosition(pojo.getParentPosition());
            b.setMoneyness(pojo.getMoneyness());
            b.setLots(pojo.getLots());
            b.setQuantity(pojo.getLots() * LOT_SIZE);
            b.setStatus(Boolean.TRUE.equals(pojo.getOpenFullyFilled()) ? LIVE : FAILED);
            Quote q = quotesOfToOpenTrade.get(pojo.getExchangeSymbol());
            if (q != null) {
                if (BUY.equals(pojo.getSide()))
                    b.setBuyIntendedPrice(q.lastPrice);
                else
                    b.setSellIntendedPrice(q.lastPrice);
            }
            childOrderBook.add(b);
        });
        tradeToRollOver.setLegs(childOrderBook);
        positionUtil.setTradeExecPricesForRollOver(tradeToRollOver, false, true);
        var liveTrade = positionRepository.save(tradeToRollOver);
        log.info("Live {} Position after rollOver completed is: {}", book, liveTrade);
        postTradeService.afterOpen(liveTrade);
    }
}
