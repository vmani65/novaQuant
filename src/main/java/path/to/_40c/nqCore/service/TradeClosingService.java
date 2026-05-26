package path.to._40c.nqCore.service;

import static path.to._40c.nqCore.util.Constants.*;

import java.util.Map;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.zerodhatech.models.LTPQuote;

import path.to._40c.nqCore.controller.SignalController.Signal;
import path.to._40c.nqCore.entity.Trade;
import path.to._40c.nqCore.entity.WeeklyOrderBook;
import path.to._40c.nqCore.repo.TradeRepository;
import path.to._40c.nqCore.util.ComputeUtil;
import path.to._40c.nqCore.util.TradeUtil;
import path.to._40c.nqCore.util.TradeUtil.ExecResult;

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
        double closePrice = Double.parseDouble(signalPrice);
        tradeToClose.setExitSignalPrice(Math.round(((tradeToClose.getExitSignalPrice() != null ? tradeToClose.getExitSignalPrice() : 0.0) + closePrice) * 100.0) / 100.0);
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
            LTPQuote q = ltp.get(w.getTradedSymbol());
            if (q != null) {
                if (BUY.equals(oppositeTransaction))
                    w.setBuyIntendedPrice(q.lastPrice);
                else
                    w.setSellIntendedPrice(q.lastPrice);
            }
            try {
                ExecResult er = tradeUtil.placeAggressiveOrder(w.getMarginCalcSymbol(), oppositeTransaction, w.getQuantity(), "EXIT");
                synchronized (w) {
                    if (er.aggregateOrderIds() != null && !er.aggregateOrderIds().isEmpty()) {
                        w.setTradeCloseOrderId(er.aggregateOrderIds());
                    }
                    w.setTradeStatus(er.fullyFilled() ? CLOSED : FAILED);
                }
                if (!er.fullyFilled()) {
                    log.error("[EXIT] {} ({} qty) NOT fully closed: filled={}/{} term={}",
                        w.getMarginCalcSymbol(), w.getQuantity(), er.totalFilled(), er.totalRequested(), er.terminalStatus());
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
}
