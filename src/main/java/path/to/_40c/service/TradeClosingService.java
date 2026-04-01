package path.to._40c.service;

import static path.to._40c.util.Constants.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.zerodhatech.models.BulkOrderResponse;
import com.zerodhatech.models.LTPQuote;
import com.zerodhatech.models.OrderResponse;

import path.to._40c.controller.SignalController.Signal;
import path.to._40c.entity.Trade;
import path.to._40c.entity.WeeklyOrderBook;
import path.to._40c.repo.TradeRepository;
import path.to._40c.util.ComputeUtil;
import path.to._40c.util.TradeUtil;

@Service
public class TradeClosingService {

	private static final Logger log = LoggerFactory.getLogger(TradeClosingService.class);

    private final TradeRepository tradeRepository;
    private final TradeUtil tradeUtil;
    private final ComputeUtil computeUtil;

    public TradeClosingService(TradeRepository tradeRepository, TradeUtil tradeUtil, ComputeUtil computeUtil) {
        this.tradeRepository = tradeRepository;
        this.tradeUtil = tradeUtil;
        this.computeUtil = computeUtil;
    }

    public Trade closeTrade(String signalPrice, Signal signal, boolean updateApiAction) {
        Trade tradeToClose = tradeUtil.findLiveTradesWithLiveOrderBooks();
        if(tradeToClose == null) {
            log.info("No Live trades to close.");
            return null;
        }
        tradeToClose.setExitSignalPrice(Double.valueOf(signalPrice));
        log.info("Live Trade being closed is: {}", tradeToClose);
        String[] liveIns = tradeToClose.getWeeklyOrderBook().stream().map(WeeklyOrderBook::getTradedSymbol).toArray(String[]::new);
        Map<String, LTPQuote> ltp = tradeUtil.getLTP(liveIns);
        if (ltp.isEmpty()) {
            log.error("LTP map is empty — aborting trade close (auth missing or Kite error)");
            tradeToClose.setTradeStatus(FAILED);
            tradeRepository.save(tradeToClose);
            return null;
        }
        IntStream.range(0, tradeToClose.getWeeklyOrderBook().size()).parallel().forEach(i -> {
            WeeklyOrderBook w = tradeToClose.getWeeklyOrderBook().get(i);
            log.debug("WeeklyOrderBook to close is: {}", w);
            String oppositeTransaction = BUY.equals(w.getTransactionType()) ? SELL : BUY;
            try {
                if (w.getQuantity() >= MAX_SIZE_PER_ORDER) {
                    List<BulkOrderResponse> o = tradeUtil.placeAutoSliceOrder(w.getMarginCalcSymbol(),ltp.get(w.getTradedSymbol()).lastPrice,oppositeTransaction,w.getQuantity());
                    if (o != null && !o.isEmpty()) {
                        log.info("Auto-sliced order placed for {} ({} qty, {} slices)",w.getMarginCalcSymbol(), w.getQuantity(), o.size());
                        synchronized (w) {
                            w.setTradeCloseOrderId(o.stream().map(a -> a.orderId).collect(Collectors.joining(", ")));
                            w.setTradeStatus(CLOSED);
                        }
                    } else {
                        log.error("Auto-slice close order failed for {} ({} qty) - returned null/empty",w.getMarginCalcSymbol(), w.getQuantity());
                        synchronized (w) {
                            w.setTradeStatus(FAILED);
                        }
                    }
                } else {
                    OrderResponse o = tradeUtil.placeOrder(w.getMarginCalcSymbol(),ltp.get(w.getTradedSymbol()).lastPrice,oppositeTransaction,w.getQuantity());
                    if (o != null && o.orderId != null) {
                        log.info("Direct order placed for {} ({} qty, orderId={})", w.getMarginCalcSymbol(), w.getQuantity(), o.orderId);
                        synchronized (w) {
                            w.setTradeCloseOrderId(o.orderId);
                            w.setTradeStatus(CLOSED);
                        }
                    } else {
                        log.error("Close order failed for {} ({} qty) - returned null", w.getMarginCalcSymbol(), w.getQuantity());
                        synchronized (w) {
                            w.setTradeStatus(FAILED);
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Exception closing order for {} ({} qty): {}",
                    w.getMarginCalcSymbol(), w.getQuantity(), e.getMessage(), e);
                synchronized (w) {
                    w.setTradeStatus(FAILED);
                }
            }
        });
        if(updateApiAction) {
            tradeToClose.setLastApiAction(signal.action);
            tradeToClose.setLastApiSignalType(signal.signalType);
        }
        if (tradeToClose.getWeeklyOrderBook().stream().allMatch(ob -> CLOSED.equals(ob.getTradeStatus()))) {
            tradeToClose.setTradeStatus(CLOSED);
        } else {
            tradeToClose.setTradeStatus(FAILED);
            log.error("Trade closing failed - not all orders were closed successfully");
        }
        tradeToClose.setTradeCloseDtTime(computeUtil.getDtTimeNow());
        log.info("Trade closing completed: {}", tradeToClose);
        Trade closedTrade = tradeRepository.save(tradeToClose);
        return closedTrade;
    }

    /**
     * closeTradeOnLargeMove — exit strategy for large gap / illiquid market conditions.
     *
     * Triggered when:
     *   - The underlying moves so fast that LTP-based market orders risk extreme slippage
     *   - Option spreads widen significantly (bid-ask > threshold) making market orders dangerous
     *   - Circuit breaker / exchange halt scenarios where one leg may be frozen
     *
     * Key differences from closeTrade():
     *   - Does NOT use LTP for order price. Instead fetches live order book depth (Level 2)
     *     and places limit orders at best bid (for SELL) / best ask (for BUY) to avoid chasing
     *   - Legs are closed SEQUENTIALLY, not in parallel — priority order:
     *       1. Close the loss-making leg first (stop the bleed)
     *       2. Close the profit leg after confirmation of step 1
     *   - Each leg gets a configurable retry window (e.g. 30s) before falling back to market order
     *   - If a leg is completely illiquid (no bids/asks), flag it as MANUAL_INTERVENTION_REQUIRED
     *     and alert via notification — do not place a blind market order
     *   - Partial fills must be tracked: if only part of the qty fills within the retry window,
     *     place a follow-up order for the remaining qty at market
     *   - A maximum slippage threshold (e.g. 2% from signal price) should be enforced —
     *     if limit order would exceed this, escalate to alert instead of auto-executing
     *
     * Parameters needed (not yet wired):
     *   - signalPrice     : the exit signal price from TradingView
     *   - signal          : Signal metadata (action, signalType, strategyName)
     *   - updateApiAction : whether to update lastApiAction on the trade record
     *   - maxSlippagePct  : maximum acceptable slippage % before abandoning auto-exit (e.g. 2.0)
     *   - retryWindowSec  : seconds to wait for limit order fill before retrying (e.g. 30)
     *
     * TODO: Implement once order book depth API (kite.getOrderDepth) usage is confirmed
     * TODO: Implement notification/alert mechanism for MANUAL_INTERVENTION_REQUIRED cases
     * TODO: Implement partial fill tracking using order status polling
     */
    public Trade closeTradeOnLargeMove(String signalPrice, Signal signal, boolean updateApiAction) {
        log.warn("closeTradeOnLargeMove called — NOT YET IMPLEMENTED. Falling back to normal closeTrade.");
        // TODO: implement large move exit logic per above specification
        return closeTrade(signalPrice, signal, updateApiAction);
    }
}
