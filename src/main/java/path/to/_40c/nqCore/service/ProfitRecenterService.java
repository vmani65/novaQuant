package path.to._40c.nqCore.service;

import static path.to._40c.nqCore.util.Constants.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.zerodhatech.models.LTPQuote;

import path.to._40c.nqCore.entity.WeeklySymbolConfig;
import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.entity.WeeklyLeg;
import path.to._40c.nqCore.pojo.LegOrder;
import path.to._40c.nqCore.repo.PositionRepository;
import path.to._40c.nqCore.util.ComputeUtil;
import path.to._40c.nqCore.util.PositionUtil;
import path.to._40c.nqCore.util.PositionUtil.ExecResult;

/**
 * Re-centres a live trade at the new ATM when profit >= 500 points: closes current LIVE legs,
 * accumulates realized points, opens new legs at the new ATM (using rolloverSymbol if the
 * weekly rollover already happened today, else thisWeekSymbol). Position stays LIVE.
 *
 * Per-segment close/open prices are accumulated independently so calcPnL can sum across
 * recenters; calcTradeOutcome adds realizedPoints to the current segment's (exit - entry).
 */
@Service
public class ProfitRecenterService {

    private static final Logger log = LoggerFactory.getLogger(ProfitRecenterService.class);

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

    public void realizeProfits(String currentPrice) {
        Position trade = positionUtil.findLiveTradesWithLiveOrderBooks();
        if (trade == null) {
            log.info("realizeProfits: no live trade — skipping.");
            return;
        }
        log.info("realizeProfits start | tradeId={} direction={} entryPrice={} currentPrice={}",
                trade.getId(), trade.getDirection(), trade.getEntrySpot(), currentPrice);

        Instant closeStart = Instant.now();
        String[] liveSymbols = trade.getLegs().stream()
                .map(WeeklyLeg::getExchangeSymbol).toArray(String[]::new);

        Map<String, LTPQuote> ltpClose = positionUtil.getLTP(liveSymbols);
        if (ltpClose.isEmpty()) {
            log.error("realizeProfits: LTP map empty for close leg — aborting (no orders placed)");
            return;
        }

        AtomicBoolean allClosed = new AtomicBoolean(true);
        List<WeeklyLeg> legsBeingClosed = new ArrayList<>(trade.getLegs());

        IntStream.range(0, legsBeingClosed.size()).parallel().forEach(i -> {
            WeeklyLeg leg      = legsBeingClosed.get(i);
            String          opposite = BUY.equals(leg.getSide()) ? SELL : BUY;
            LTPQuote q = ltpClose.get(leg.getExchangeSymbol());
            if (q != null) {
                if (BUY.equals(opposite))
                    leg.setBuyIntendedPrice(q.lastPrice);
                else
                    leg.setSellIntendedPrice(q.lastPrice);
            }
            try {
                ExecResult er = positionUtil.placeAggressiveOrder(leg.getInstrument(), opposite, leg.getQuantity(), "EXIT");
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
        });

        long closeMs = Duration.between(closeStart, Instant.now()).toMillis();
        if (!allClosed.get()) {
            log.error("realizeProfits: not all legs closed — aborting recenter. Saving partial state.");
            log.info("[PERFORMANCE] recenter | close={}ms | open=0ms | total={}ms (aborted)", closeMs, closeMs);
            positionRepository.save(trade);
            return;
        }

        accumulateExecPrices(legsBeingClosed, true);

        double newPrice = Double.parseDouble(currentPrice);
        double entry    = trade.getEntrySpot() != null ? trade.getEntrySpot() : 0.0;
        double segment  = LONG.equals(trade.getDirection())
                ? newPrice - entry
                : entry - newPrice;
        double realized = trade.getRealizedPoints() != null ? trade.getRealizedPoints() : 0.0;
        trade.setRealizedPoints(Math.round((realized + segment) * 100.0) / 100.0);
        trade.setEntrySpot(newPrice);
        log.info("realizeProfits: segment={}pts totalRealized={}pts newBaseline={}",
                segment, trade.getRealizedPoints(), newPrice);

        Instant openStart = Instant.now();
        weeklySymbolService.checkAndPromoteRolloverSymbol();
        boolean useRollover = isRolloverComplete();
        List<LegOrder> newLegs = computeUtil.buildInstrument(currentPrice, trade, useRollover);
        log.info("realizeProfits: opening {} new legs (useRollover={})", newLegs.size(), useRollover);

        String[] openSymbols = newLegs.stream().map(LegOrder::getExchangeSymbol).toArray(String[]::new);
        Map<String, LTPQuote> ltpOpen = positionUtil.getLTP(openSymbols);
        if (ltpOpen.isEmpty()) {
            log.error("realizeProfits: LTP map empty for open leg — close already executed, manual intervention needed");
            long abortOpenMs = Duration.between(openStart, Instant.now()).toMillis();
            log.info("[PERFORMANCE] recenter | close={}ms | open={}ms | total={}ms (aborted at open LTP)", closeMs, abortOpenMs, closeMs + abortOpenMs);
            positionRepository.save(trade);
            return;
        }

        IntStream.range(0, newLegs.size()).parallel().forEach(i -> {
            LegOrder pojo     = newLegs.get(i);
            int        totalQty = pojo.getLots() * LOT_SIZE;
            try {
                ExecResult er = positionUtil.placeAggressiveOrder(pojo.getInstrument(), pojo.getSide(), totalQty, "ENTRY");
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
        });
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
            LTPQuote q = ltpOpen.get(pojo.getExchangeSymbol());
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
        log.info("realizeProfits complete | tradeId={} realizedPoints={} newEntryPrice={}",
                saved.getId(), saved.getRealizedPoints(), saved.getEntrySpot());

        postTradeService.afterOpen(saved);
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
        WeeklySymbolConfig cfg = weeklySymbolService.current();
        return cfg != null && Boolean.TRUE.equals(cfg.getRolloverComplete());
    }
}
