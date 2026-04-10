package path.to._40c.service;

import static path.to._40c.util.Constants.*;

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

import path.to._40c.entity.SymbolConfig;
import path.to._40c.entity.Trade;
import path.to._40c.entity.WeeklyOrderBook;
import path.to._40c.pojo.WeeklyPojo;
import path.to._40c.repo.TradeRepository;
import path.to._40c.util.ComputeUtil;
import path.to._40c.util.TradeUtil;

/**
 * Re-centres a live trade at the new ATM when profit >= 500 points.
 *
 * Flow:
 *   1. Close all current LIVE legs at market.
 *   2. Accumulate close execution prices only on the just-closed legs
 *      (NOT all CLOSED legs — avoids double-counting across multiple recenters).
 *   3. Add segment points to realizedPoints; reset entrySignalPrice to currentPrice.
 *   4. Open new legs at the new ATM (thisWeekSymbol normally; rolloverSymbol
 *      if rolloverComplete=true — i.e., weekly rollover already happened today).
 *   5. Accumulate open execution prices only on the just-opened legs.
 *   6. Recalculate margin/brokerage for new LIVE legs only.
 *   7. Save — trade stays LIVE.
 *
 * P&L correctness across N recenters:
 *   - Each recenter's legs accumulate their own soldPrice/boughtPrice independently.
 *   - calcPnL groups all legs by moneyness; accumulated prices sum naturally.
 *   - calcTradeOutcome: totalPoints = realizedPoints + (exit - entry of final segment).
 */
@Service
public class ProfitRecenterService {

    private static final Logger log = LoggerFactory.getLogger(ProfitRecenterService.class);

    private final TradeRepository tradeRepository;
    private final TradeUtil       tradeUtil;
    private final ComputeUtil     computeUtil;
    private final SymbolService   symbolService;

    public ProfitRecenterService(TradeRepository tradeRepository, TradeUtil tradeUtil,
                                 ComputeUtil computeUtil, SymbolService symbolService) {
        this.tradeRepository = tradeRepository;
        this.tradeUtil       = tradeUtil;
        this.computeUtil     = computeUtil;
        this.symbolService   = symbolService;
    }

    public void realizeProfits(String currentPrice) {
        Trade trade = tradeUtil.findLiveTradesWithLiveOrderBooks();
        if (trade == null) {
            log.info("realizeProfits: no live trade — skipping.");
            return;
        }
        log.info("realizeProfits start | tradeId={} direction={} entryPrice={} currentPrice={}",
                trade.getId(), trade.getSignalType(), trade.getEntrySignalPrice(), currentPrice);

        // ── 1. Close all current LIVE legs ────────────────────────────────────
        String[] liveSymbols = trade.getWeeklyOrderBook().stream()
                .map(WeeklyOrderBook::getTradedSymbol).toArray(String[]::new);

        Map<String, LTPQuote> ltpClose = tradeUtil.getLTP(liveSymbols);
        if (ltpClose.isEmpty()) {
            log.error("realizeProfits: LTP map empty for close leg — aborting (no orders placed)");
            return;
        }

        AtomicBoolean allClosed = new AtomicBoolean(true);
        // Snapshot exactly the legs being closed in this recenter — needed for step 2
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

        // ── 2. Accumulate close prices for ONLY the just-closed legs ─────────
        // IMPORTANT: must NOT use setTradeExecPricesForRollOver (which processes all CLOSED legs)
        // because that would double-count prices from previous recenter segments.
        accumulateExecPrices(legsBeingClosed, true);

        // ── 3. Accumulate realized points; reset entry price to new baseline ──
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

        // ── 4. Open new legs at new ATM ───────────────────────────────────────
        // Use rolloverSymbol if the weekly rollover already completed today;
        // otherwise use thisWeekSymbol. buildInstrument handles the lookup.
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

        // ── 5. Build new WeeklyOrderBook children ─────────────────────────────
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

        // Trade.setWeeklyOrderBook does addAll (not replace) — keeps prior CLOSED legs
        // in the list so calcPnL can accumulate prices across all segments.
        trade.setWeeklyOrderBook(newChildren);

        // ── 6. Accumulate open prices for ONLY the just-opened legs ──────────
        accumulateExecPrices(newChildren, false);

        // ── 7. Margin/brokerage — calcMarginAndBrokerage filters to LIVE only ─
        tradeUtil.calcMarginAndBrokerage(trade);

        Trade saved = tradeRepository.save(trade);
        log.info("realizeProfits complete | tradeId={} realizedPoints={} newEntryPrice={}",
                saved.getId(), saved.getRealizedPoints(), saved.getEntrySignalPrice());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Fetches and accumulates execution prices for a specific list of legs only.
     *
     * isClose=false (open event):
     *   BUY leg  → boughtPrice += avgFillPrice
     *   SELL leg → soldPrice   += avgFillPrice
     *
     * isClose=true (close event):
     *   BUY leg  → soldPrice   += avgFillPrice  (the BUY was opened; now being sold to close)
     *   SELL leg → boughtPrice += avgFillPrice  (the SELL was opened; now being bought to close)
     *
     * Accumulation (+=) rather than assignment (=) preserves prices from prior segments,
     * which is essential for calcPnL to sum correctly across multiple recenters.
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
            if (fills == null || fills.isEmpty() || fills.get(0) == null) {
                log.error("accumulateExecPrices: still no fills for orderId={} — price not recorded",
                        orderId);
                return;
            }
            double avg = fills.get(0).averagePrice != null
                    ? Double.parseDouble(fills.get(0).averagePrice) : 0.0;

            if (BUY.equals(w.getTransactionType())) {
                if (!isClose) {
                    double prev = w.getBoughtPrice() != null ? w.getBoughtPrice() : 0.0;
                    w.setBoughtPrice(Math.round((prev + avg) * 100.0) / 100.0);
                } else {
                    double prev = w.getSoldPrice() != null ? w.getSoldPrice() : 0.0;
                    w.setSoldPrice(Math.round((prev + avg) * 100.0) / 100.0);
                }
            } else { // SELL
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

    /**
     * Returns true if the weekly rollover has already been completed today.
     * In that case, new legs should use rolloverSymbol (the new week's expiry),
     * not thisWeekSymbol (the old week which was just rolled out of).
     */
    private boolean isRolloverComplete() {
        SymbolConfig cfg = symbolService.current();
        return cfg != null && Boolean.TRUE.equals(cfg.getRolloverComplete());
    }
}
