package path.to._40c.nqCore.service;

import static path.to._40c.nqCore.util.Constants.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.zerodhatech.models.BulkOrderResponse;
import com.zerodhatech.models.LTPQuote;
import com.zerodhatech.models.OrderResponse;

import path.to._40c.nqCore.entity.SymbolConfig;
import path.to._40c.nqCore.entity.Trade;
import path.to._40c.nqCore.entity.WeeklyOrderBook;
import path.to._40c.nqCore.pojo.WeeklyPojo;
import path.to._40c.nqCore.repo.TradeRepository;
import path.to._40c.nqCore.util.ComputeUtil;
import path.to._40c.nqCore.util.TradeUtil;

/**
 * Re-centres a live trade at the new ATM when profit >= 500 points: closes current LIVE legs,
 * accumulates realized points, opens new legs at the new ATM (using rolloverSymbol if the
 * weekly rollover already happened today, else thisWeekSymbol). Trade stays LIVE.
 *
 * Per-segment close/open prices are accumulated independently so calcPnL can sum across
 * recenters; calcTradeOutcome adds realizedPoints to the current segment's (exit - entry).
 */
@Service
public class ProfitRecenterService {

    private static final Logger log = LoggerFactory.getLogger(ProfitRecenterService.class);

    private final TradeRepository  tradeRepository;
    private final TradeUtil        tradeUtil;
    private final ComputeUtil      computeUtil;
    private final SymbolService    symbolService;
    private final PostTradeService postTradeService;

    public ProfitRecenterService(TradeRepository tradeRepository, TradeUtil tradeUtil,
                                 ComputeUtil computeUtil, SymbolService symbolService,
                                 PostTradeService postTradeService) {
        this.tradeRepository  = tradeRepository;
        this.tradeUtil        = tradeUtil;
        this.computeUtil      = computeUtil;
        this.symbolService    = symbolService;
        this.postTradeService = postTradeService;
    }

    public void realizeProfits(String currentPrice) {
        Trade trade = tradeUtil.findLiveTradesWithLiveOrderBooks();
        if (trade == null) {
            log.info("realizeProfits: no live trade — skipping.");
            return;
        }
        log.info("realizeProfits start | tradeId={} direction={} entryPrice={} currentPrice={}",
                trade.getId(), trade.getSignalType(), trade.getEntrySignalPrice(), currentPrice);

        String[] liveSymbols = trade.getWeeklyOrderBook().stream()
                .map(WeeklyOrderBook::getTradedSymbol).toArray(String[]::new);

        Map<String, LTPQuote> ltpClose = tradeUtil.getLTP(liveSymbols);
        if (ltpClose.isEmpty()) {
            log.error("realizeProfits: LTP map empty for close leg — aborting (no orders placed)");
            return;
        }

        AtomicBoolean allClosed = new AtomicBoolean(true);
        List<WeeklyOrderBook> legsBeingClosed = new ArrayList<>(trade.getWeeklyOrderBook());

        IntStream.range(0, legsBeingClosed.size()).parallel().forEach(i -> {
            WeeklyOrderBook leg      = legsBeingClosed.get(i);
            String          opposite = BUY.equals(leg.getTransactionType()) ? SELL : BUY;
            try {
                if (leg.getQuantity() >= MAX_SIZE_PER_ORDER) {
                    List<BulkOrderResponse> o = tradeUtil.placeAutoSliceOrder(
                            leg.getMarginCalcSymbol(),
                            ltpClose.get(leg.getTradedSymbol()).lastPrice,
                            opposite, leg.getQuantity());
                    if (o != null && !o.isEmpty()) {
                        log.info("Auto-sliced close order placed for {} ({} qty, {} slices)",
                                leg.getMarginCalcSymbol(), leg.getQuantity(), o.size());
                        leg.setTradeCloseOrderId(o.stream().map(a -> a.orderId)
                                .collect(Collectors.joining(", ")));
                        leg.setTradeStatus(CLOSED);
                    } else {
                        log.error("realizeProfits close failed for {} — null/empty", leg.getMarginCalcSymbol());
                        allClosed.set(false);
                    }
                } else {
                    OrderResponse o = tradeUtil.placeOrder(
                            leg.getMarginCalcSymbol(),
                            ltpClose.get(leg.getTradedSymbol()).lastPrice,
                            opposite, leg.getQuantity());
                    if (o != null && o.orderId != null) {
                        log.info("Close order placed for {} (qty={} orderId={})",
                                leg.getMarginCalcSymbol(), leg.getQuantity(), o.orderId);
                        leg.setTradeCloseOrderId(o.orderId);
                        leg.setTradeStatus(CLOSED);
                    } else {
                        log.error("realizeProfits close failed for {} — null response", leg.getMarginCalcSymbol());
                        allClosed.set(false);
                    }
                }
            } catch (Exception e) {
                log.error("Exception closing {} during realizeProfits: {}",
                        leg.getMarginCalcSymbol(), e.getMessage(), e);
                allClosed.set(false);
            }
        });

        if (!allClosed.get()) {
            log.error("realizeProfits: not all legs closed — aborting recenter. Saving partial state.");
            tradeRepository.save(trade);
            return;
        }

        accumulateExecPrices(legsBeingClosed, true);

        double newPrice = Double.parseDouble(currentPrice);
        double entry    = trade.getEntrySignalPrice() != null ? trade.getEntrySignalPrice() : 0.0;
        double segment  = LONG.equals(trade.getSignalType())
                ? newPrice - entry
                : entry - newPrice;
        double realized = trade.getRealizedPoints() != null ? trade.getRealizedPoints() : 0.0;
        trade.setRealizedPoints(Math.round((realized + segment) * 100.0) / 100.0);
        trade.setEntrySignalPrice(newPrice);
        log.info("realizeProfits: segment={}pts totalRealized={}pts newBaseline={}",
                segment, trade.getRealizedPoints(), newPrice);

        symbolService.checkAndPromoteRolloverSymbol();
        boolean useRollover = isRolloverComplete();
        List<WeeklyPojo> newLegs = computeUtil.buildInstrument(currentPrice, trade, useRollover);
        log.info("realizeProfits: opening {} new legs (useRollover={})", newLegs.size(), useRollover);

        String[] openSymbols = newLegs.stream().map(WeeklyPojo::getTradedSymbol).toArray(String[]::new);
        Map<String, LTPQuote> ltpOpen = tradeUtil.getLTP(openSymbols);
        if (ltpOpen.isEmpty()) {
            log.error("realizeProfits: LTP map empty for open leg — close already executed, manual intervention needed");
            tradeRepository.save(trade);
            return;
        }

        IntStream.range(0, newLegs.size()).parallel().forEach(i -> {
            WeeklyPojo pojo     = newLegs.get(i);
            int        totalQty = pojo.getLots() * LOT_SIZE;
            try {
                if (totalQty >= MAX_SIZE_PER_ORDER) {
                    List<BulkOrderResponse> o = tradeUtil.placeAutoSliceOrder(
                            pojo.getMarginCalcSymbol(),
                            ltpOpen.get(pojo.getTradedSymbol()).lastPrice,
                            pojo.getTransactionType(), totalQty);
                    if (o != null && !o.isEmpty()) {
                        log.info("Auto-sliced open order placed for {} ({} qty, {} slices)",
                                pojo.getMarginCalcSymbol(), totalQty, o.size());
                        pojo.setTradeOpenOrderId(o.stream().map(a -> a.orderId)
                                .collect(Collectors.joining(", ")));
                    } else {
                        log.error("realizeProfits open failed for {} — null/empty", pojo.getMarginCalcSymbol());
                    }
                } else {
                    OrderResponse o = tradeUtil.placeOrder(
                            pojo.getMarginCalcSymbol(),
                            ltpOpen.get(pojo.getTradedSymbol()).lastPrice,
                            pojo.getTransactionType(), totalQty);
                    if (o != null && o.orderId != null) {
                        log.info("Open order placed for {} (qty={} orderId={})",
                                pojo.getMarginCalcSymbol(), totalQty, o.orderId);
                        pojo.setTradeOpenOrderId(o.orderId);
                    } else {
                        log.error("realizeProfits open failed for {} — null response", pojo.getMarginCalcSymbol());
                    }
                }
            } catch (Exception e) {
                log.error("Exception opening {} during realizeProfits: {}",
                        pojo.getMarginCalcSymbol(), e.getMessage(), e);
            }
        });

        List<WeeklyOrderBook> newChildren = new ArrayList<>();
        newLegs.forEach(pojo -> {
            WeeklyOrderBook b = new WeeklyOrderBook();
            b.setMarginCalcSymbol(pojo.getMarginCalcSymbol());
            b.setTradedSymbol(pojo.getTradedSymbol());
            b.setTransactionType(pojo.getTransactionType());
            b.setTradeOpenOrderId(pojo.getTradeOpenOrderId());
            b.setTrade(pojo.getParentTrade());
            b.setMoneyness(pojo.getMoneyness());
            b.setLots(pojo.getLots());
            b.setQuantity(pojo.getLots() * LOT_SIZE);
            b.setTradeStatus(pojo.getTradeOpenOrderId() != null ? LIVE : FAILED);
            newChildren.add(b);
        });

        trade.setWeeklyOrderBook(newChildren);

        accumulateExecPrices(newChildren, false);

        Trade saved = tradeRepository.save(trade);
        log.info("realizeProfits complete | tradeId={} realizedPoints={} newEntryPrice={}",
                saved.getId(), saved.getRealizedPoints(), saved.getEntrySignalPrice());

        postTradeService.afterOpen(saved);
    }

    /**
     * Fetch fills for the given legs and accumulate (+=) into bought/soldPrice.
     * isClose=false → BUY adds to boughtPrice, SELL adds to soldPrice (open event).
     * isClose=true  → BUY adds to soldPrice, SELL adds to boughtPrice (close event — opposite txn).
     * += (not =) preserves prices from prior segments so calcPnL sums correctly across recenters.
     */
    private void accumulateExecPrices(List<WeeklyOrderBook> legs, boolean isClose) {
        legs.forEach(w -> {
            String orderId = isClose ? w.getTradeCloseOrderId() : w.getTradeOpenOrderId();
            if (orderId == null || orderId.isBlank()) {
                log.warn("accumulateExecPrices: null orderId for {} (isClose={})",
                        w.getMarginCalcSymbol(), isClose);
                return;
            }
            List<com.zerodhatech.models.Trade> fills = tradeUtil.getOrderTrades(orderId);
            if (fills == null || fills.isEmpty()) {
                log.warn("accumulateExecPrices: empty fills for orderId={} — retrying", orderId);
                TradeUtil.sleep();
                fills = tradeUtil.getOrderTrades(orderId);
            }
            if (fills == null || fills.isEmpty()) {
                log.error("accumulateExecPrices: still no fills for orderId={} — price not recorded",
                        orderId);
                return;
            }
            double avg = TradeUtil.weightedAvgFillPrice(fills);

            if (BUY.equals(w.getTransactionType())) {
                if (!isClose) {
                    double prev = w.getBoughtPrice() != null ? w.getBoughtPrice() : 0.0;
                    w.setBoughtPrice(Math.round((prev + avg) * 100.0) / 100.0);
                } else {
                    double prev = w.getSoldPrice() != null ? w.getSoldPrice() : 0.0;
                    w.setSoldPrice(Math.round((prev + avg) * 100.0) / 100.0);
                }
            } else {
                if (!isClose) {
                    double prev = w.getSoldPrice() != null ? w.getSoldPrice() : 0.0;
                    w.setSoldPrice(Math.round((prev + avg) * 100.0) / 100.0);
                } else {
                    double prev = w.getBoughtPrice() != null ? w.getBoughtPrice() : 0.0;
                    w.setBoughtPrice(Math.round((prev + avg) * 100.0) / 100.0);
                }
            }
            log.debug("accumulateExecPrices: {} {} avgFill={} isClose={} boughtPrice={} soldPrice={}",
                    w.getMarginCalcSymbol(), w.getTransactionType(), avg, isClose,
                    w.getBoughtPrice(), w.getSoldPrice());
        });
    }

    /** True if today's weekly rollover already ran (so new legs should use rolloverSymbol). */
    private boolean isRolloverComplete() {
        SymbolConfig cfg = symbolService.current();
        return cfg != null && Boolean.TRUE.equals(cfg.getRolloverComplete());
    }
}
