package path.to._40c.nqCore.service;

import static path.to._40c.nqCore.util.Constants.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.zerodhatech.models.Quote;

import path.to._40c.nqCore.entity.SymbolConfig;
import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.entity.WeeklyLeg;
import path.to._40c.nqCore.pojo.LegOrder;
import path.to._40c.nqCore.repo.PositionRepository;
import path.to._40c.nqCore.util.ComputeUtil;
import path.to._40c.nqCore.util.PositionUtil;
import path.to._40c.nqCore.util.PositionUtil.ExecResult;

/**
 * Re-centres a live trade at the new ATM when profit >= 500 points: closes current LIVE legs,
 * banks the closed segment's points into bankedPoints and resets baselineSpot to the new strike,
 * opens new legs at the new ATM (using rolloverSymbol if the weekly rollover already happened
 * today, else thisWeekSymbol). Position stays LIVE. entrySpot is never mutated here.
 *
 * Per-segment close/open prices are accumulated independently so calcPnL can sum across
 * recenters; calcTradeOutcome adds bankedPoints to the current segment's (baseline - exit).
 */
@Service
@Slf4j
public class ProfitRecenterService {
    /**
     * Server-side backstop: ignore a recenter trigger whose effective profit (measured from the
     * current baselineSpot) is below this floor. Matches nQTicker's Gate-1 hysteresis close (450pts)
     * so genuine liquidity-driven recenters still pass, while a stale or corrupt trigger can never
     * churn the position again.
     */
    private static final double RECENTER_MIN_PROFIT = 450.0;

    private final PositionRepository  positionRepository;
    private final PositionUtil        positionUtil;
    private final ComputeUtil      computeUtil;
    private final WeeklySymbolService    weeklySymbolService;
    private final PostTradeService postTradeService;

    public ProfitRecenterService(PositionRepository positionRepository, PositionUtil positionUtil,
                                 ComputeUtil computeUtil, WeeklySymbolService weeklySymbolService,
                                 PostTradeService postTradeService) {
        this.positionRepository  = positionRepository;
        this.positionUtil        = positionUtil;
        this.computeUtil      = computeUtil;
        this.weeklySymbolService    = weeklySymbolService;
        this.postTradeService = postTradeService;
    }

    /**
     * Re-centres the SYNTH_WEEKLY book's live trade at the new ATM — hard book fence from
     * the two-book audit: without it a +500-pt trigger would close a LONG_MONTHLY leg and
     * reopen it as a weekly. Aborts (no orders) if the effective profit from the
     * current baselineSpot is below RECENTER_MIN_PROFIT — a backstop against a corrupt/stale baseline,
     * bad currentPrice, or duplicate fire, since nQTicker's liquidity gate can't be re-checked here.
     * On pass: banks the closed segment's points (measured from baselineSpot) into bankedPoints,
     * resets baselineSpot to currentPrice, then closes the old legs and opens new ones at the new ATM.
     * Each closed leg is stamped with expectedPnl = qty × the segment's spot points, the denominator
     * calcPnL later uses for that leg's pnlCapturePct (its share of the segment move).
     */
    public void realizeProfitsWeekly(String currentPrice) {
        Position trade = positionUtil.findLiveTradesWithLiveOrderBooks(SYNTH_WEEKLY);
        if (trade == null) {
            log.info("realizeProfits: no live SYNTH_WEEKLY trade — skipping.");
            return;
        }
        double newPrice = Double.parseDouble(currentPrice);
        double base = trade.getBaselineSpot() != null ? trade.getBaselineSpot()
                : (trade.getEntrySpot() != null ? trade.getEntrySpot() : 0.0);
        double effectiveProfit = SHORT.equals(trade.getDirection()) ? base - newPrice : newPrice - base;
        log.info("realizeProfits start | tradeId={} direction={} baseline={} currentPrice={} effectiveProfit={}pts",
                trade.getId(), trade.getDirection(), base, currentPrice, Math.round(effectiveProfit * 100.0) / 100.0);

        if (effectiveProfit < RECENTER_MIN_PROFIT) {
            log.warn("realizeProfits ABORTED — effectiveProfit {}pts < {}pts floor. Ignoring trigger (no orders placed).",
                    Math.round(effectiveProfit * 100.0) / 100.0, RECENTER_MIN_PROFIT);
            return;
        }

        Instant closeStart = Instant.now();
        String[] liveSymbols = trade.getLegs().stream()
                .map(WeeklyLeg::getExchangeSymbol).toArray(String[]::new);

        Map<String, Quote> quotesClose = positionUtil.getQuote(liveSymbols);
        if (quotesClose.isEmpty()) {
            log.error("realizeProfits: quote map empty for close leg — aborting (no orders placed)");
            return;
        }

        AtomicBoolean allClosed = new AtomicBoolean(true);
        List<WeeklyLeg> legsBeingClosed = new ArrayList<>(trade.getLegs());

        List<CompletableFuture<Void>> closeFuts = legsBeingClosed.stream()
            .map(leg -> CompletableFuture.runAsync(() -> {
                String opposite = BUY.equals(leg.getSide()) ? SELL : BUY;
                Quote q = quotesClose.get(leg.getExchangeSymbol());
                if (q != null) {
                    if (BUY.equals(opposite))
                        leg.setBuyIntendedPrice(q.lastPrice);
                    else
                        leg.setSellIntendedPrice(q.lastPrice);
                }
                try {
                    ExecResult er = positionUtil.placeAggressiveOrder(q, leg.getInstrument(), opposite, leg.getQuantity(), "EXIT");
                    if (er.aggregateOrderIds() != null && !er.aggregateOrderIds().isEmpty()) {
                        leg.setCloseOrderId(er.aggregateOrderIds());
                    }
                    if (er.fullyFilled()) {
                        leg.setStatus(CLOSED);
                    } else {
                        log.error("[EXIT] {} recenter close NOT fully filled: filled={}/{} term={}",
                            leg.getInstrument(), er.totalFilled(), er.totalRequested(), er.terminalStatus());
                        leg.setStatus(FAILED);
                        allClosed.set(false);
                    }
                } catch (Exception e) {
                    log.error("Exception closing {} during realizeProfits: {}",
                            leg.getInstrument(), e.getMessage(), e);
                    allClosed.set(false);
                }
            }, PositionUtil.LEG_EXEC))
            .toList();
        CompletableFuture.allOf(closeFuts.toArray(new CompletableFuture[0])).join();

        long closeMs = Duration.between(closeStart, Instant.now()).toMillis();
        if (!allClosed.get()) {
            log.error("realizeProfits: not all legs closed — aborting recenter. Saving partial state.");
            log.info("[PERFORMANCE] recenter | close={}ms | open=0ms | total={}ms (aborted)", closeMs, closeMs);
            positionRepository.save(trade);
            return;
        }

        accumulateExecPrices(legsBeingClosed, true);

        double segment = LONG.equals(trade.getDirection()) ? newPrice - base : base - newPrice;
        double banked  = trade.getBankedPoints() != null ? trade.getBankedPoints() : 0.0;
        trade.setBankedPoints(Math.round((banked + segment) * 100.0) / 100.0);
        trade.setBaselineSpot(newPrice);
        legsBeingClosed.forEach(leg -> leg.setExpectedPnl(ComputeUtil.rnd(leg.getQuantity() * segment)));
        log.info("realizeProfits: segment={}pts bankedPoints={} newBaseline={}",
                Math.round(segment * 100.0) / 100.0, trade.getBankedPoints(), newPrice);

        Instant openStart = Instant.now();
        weeklySymbolService.checkAndPromoteRolloverSymbol();
        boolean useRollover = isRolloverComplete();
        List<LegOrder> newLegs = computeUtil.buildWeeklyInstrument(currentPrice, trade, useRollover);
        log.info("realizeProfits: opening {} new legs (useRollover={})", newLegs.size(), useRollover);

        String[] openSymbols = newLegs.stream().map(LegOrder::getExchangeSymbol).toArray(String[]::new);
        Map<String, Quote> quotesOpen = positionUtil.getQuote(openSymbols);
        if (quotesOpen.isEmpty()) {
            log.error("realizeProfits: quote map empty for open leg — close already executed, manual intervention needed");
            long abortOpenMs = Duration.between(openStart, Instant.now()).toMillis();
            log.info("[PERFORMANCE] recenter | close={}ms | open={}ms | total={}ms (aborted at open quote)", closeMs, abortOpenMs, closeMs + abortOpenMs);
            positionRepository.save(trade);
            return;
        }

        List<CompletableFuture<Void>> openFuts = newLegs.stream()
            .map(pojo -> CompletableFuture.runAsync(() -> {
                int totalQty = pojo.getLots() * LOT_SIZE;
                try {
                    ExecResult er = positionUtil.placeAggressiveOrder(quotesOpen.get(pojo.getExchangeSymbol()), pojo.getInstrument(), pojo.getSide(), totalQty, "ENTRY");
                    if (er.aggregateOrderIds() != null && !er.aggregateOrderIds().isEmpty()) {
                        pojo.setOpenOrderId(er.aggregateOrderIds());
                    }
                    pojo.setOpenFullyFilled(er.fullyFilled());
                    if (!er.fullyFilled()) {
                        log.error("[ENTRY] {} recenter open NOT fully filled: filled={}/{} term={}",
                            pojo.getInstrument(), er.totalFilled(), er.totalRequested(), er.terminalStatus());
                    }
                } catch (Exception e) {
                    log.error("Exception opening {} during realizeProfits: {}",
                            pojo.getInstrument(), e.getMessage(), e);
                }
            }, PositionUtil.LEG_EXEC))
            .toList();
        CompletableFuture.allOf(openFuts.toArray(new CompletableFuture[0])).join();
        long openMs = Duration.between(openStart, Instant.now()).toMillis();
        log.info("[PERFORMANCE] recenter | close={}ms | open={}ms | total={}ms (excl. fill retrieval)", closeMs, openMs, closeMs + openMs);

        List<WeeklyLeg> newChildren = new ArrayList<>();
        newLegs.forEach(pojo -> {
            WeeklyLeg b = new WeeklyLeg();
            b.setInstrument(pojo.getInstrument());
            b.setExchangeSymbol(pojo.getExchangeSymbol());
            b.setSide(pojo.getSide());
            b.setOpenOrderId(pojo.getOpenOrderId());
            b.setPosition(pojo.getParentPosition());
            b.setMoneyness(pojo.getMoneyness());
            b.setLots(pojo.getLots());
            b.setQuantity(pojo.getLots() * LOT_SIZE);
            b.setStatus(Boolean.TRUE.equals(pojo.getOpenFullyFilled()) ? LIVE : FAILED);
            Quote q = quotesOpen.get(pojo.getExchangeSymbol());
            if (q != null) {
                if (BUY.equals(pojo.getSide()))
                    b.setBuyIntendedPrice(q.lastPrice);
                else
                    b.setSellIntendedPrice(q.lastPrice);
            }
            newChildren.add(b);
        });

        trade.setLegs(newChildren);

        accumulateExecPrices(newChildren, false);

        Position saved = positionRepository.save(trade);
        log.info("realizeProfits complete | tradeId={} bankedPoints={} newBaseline={}",
                saved.getId(), saved.getBankedPoints(), saved.getBaselineSpot());

        postTradeService.afterOpen(saved);
    }

    /**
     * LONG_MONTHLY recenter — intentionally a no-op, by policy rather than omission:
     * gamma convexity is the entire point of the bought monthly leg, and re-striking at
     * +500 points would sell that convexity off. This method exists as the documented
     * monthly counterpart of realizeProfitsWeekly so no trigger ever routes a monthly
     * position through the weekly recenter.
     */
    public void realizeProfitsMonthly() {
        log.info("realizeProfitsMonthly: no-op — LONG_MONTHLY never recenters (convexity is kept, not re-struck)");
    }

    /**
     * Fetch fills for the given legs and accumulate (+=) into bought/sellFillPrice.
     * isClose=false → BUY adds to buyFillPrice, SELL adds to sellFillPrice (open event).
     * isClose=true  → BUY adds to sellFillPrice, SELL adds to buyFillPrice (close event — opposite txn).
     * += (not =) preserves prices from prior segments so calcPnL sums correctly across recenters.
     */
    private void accumulateExecPrices(List<WeeklyLeg> legs, boolean isClose) {
        legs.forEach(w -> {
            String orderId = isClose ? w.getCloseOrderId() : w.getOpenOrderId();
            if (orderId == null || orderId.isBlank()) {
                log.warn("accumulateExecPrices: null orderId for {} (isClose={})",
                        w.getInstrument(), isClose);
                return;
            }
            List<com.zerodhatech.models.Trade> fills = positionUtil.getOrderTrades(orderId);
            if (fills == null || fills.isEmpty()) {
                log.warn("accumulateExecPrices: empty fills for orderId={} — retrying", orderId);
                PositionUtil.sleep();
                fills = positionUtil.getOrderTrades(orderId);
            }
            if (fills == null || fills.isEmpty()) {
                log.error("accumulateExecPrices: still no fills for orderId={} — price not recorded",
                        orderId);
                return;
            }
            double avg = PositionUtil.weightedAvgFillPrice(fills);

            if (BUY.equals(w.getSide())) {
                if (!isClose) {
                    double prev = w.getBuyFillPrice() != null ? w.getBuyFillPrice() : 0.0;
                    w.setBuyFillPrice(Math.round((prev + avg) * 100.0) / 100.0);
                } else {
                    double prev = w.getSellFillPrice() != null ? w.getSellFillPrice() : 0.0;
                    w.setSellFillPrice(Math.round((prev + avg) * 100.0) / 100.0);
                }
            } else {
                if (!isClose) {
                    double prev = w.getSellFillPrice() != null ? w.getSellFillPrice() : 0.0;
                    w.setSellFillPrice(Math.round((prev + avg) * 100.0) / 100.0);
                } else {
                    double prev = w.getBuyFillPrice() != null ? w.getBuyFillPrice() : 0.0;
                    w.setBuyFillPrice(Math.round((prev + avg) * 100.0) / 100.0);
                }
            }
            log.debug("accumulateExecPrices: {} {} avgFill={} isClose={} buyFillPrice={} sellFillPrice={}",
                    w.getInstrument(), w.getSide(), avg, isClose,
                    w.getBuyFillPrice(), w.getSellFillPrice());
        });
    }

    /** True if today's weekly rollover already ran (so new legs should use rolloverSymbol). */
    private boolean isRolloverComplete() {
        SymbolConfig cfg = weeklySymbolService.current();
        return cfg != null && Boolean.TRUE.equals(cfg.getRolloverComplete());
    }
}
