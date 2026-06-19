package path.to._40c.nqCore.service;

import static path.to._40c.nqCore.util.Constants.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.zerodhatech.models.Quote;

import path.to._40c.nqCore.controller.SignalController.Signal;
import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.entity.WeeklyLeg;
import path.to._40c.nqCore.repo.PositionRepository;
import path.to._40c.nqCore.util.ComputeUtil;
import path.to._40c.nqCore.util.PositionUtil;
import path.to._40c.nqCore.util.PositionUtil.ExecResult;

@Service
public class PositionClosingService {

	private static final Logger log = LoggerFactory.getLogger(PositionClosingService.class);

    private final PositionRepository positionRepository;
    private final PositionUtil positionUtil;
    private final ComputeUtil computeUtil;

    public PositionClosingService(PositionRepository positionRepository, PositionUtil positionUtil, ComputeUtil computeUtil) {
        this.positionRepository = positionRepository;
        this.positionUtil = positionUtil;
        this.computeUtil = computeUtil;
    }

    public Position closeTrade(String signalPrice, Signal signal, boolean updateApiAction) {
        Position tradeToClose = positionUtil.findLiveTradesWithLiveOrderBooks();
        if(tradeToClose == null) {
            log.info("No Live trades to close.");
            return null;
        }
        double closePrice = Double.parseDouble(signalPrice);
        tradeToClose.setExitSpot(Math.round(((tradeToClose.getExitSpot() != null ? tradeToClose.getExitSpot() : 0.0) + closePrice) * 100.0) / 100.0);
        log.info("Live Position being closed is: {}", tradeToClose);
        String[] liveIns = tradeToClose.getLegs().stream().map(WeeklyLeg::getExchangeSymbol).toArray(String[]::new);
        Map<String, Quote> quotes = positionUtil.getQuote(liveIns);
        if (quotes.isEmpty()) {
            log.error("Quote map is empty — aborting trade close (auth missing or Kite error)");
            tradeToClose.setStatus(FAILED);
            positionRepository.save(tradeToClose);
            return null;
        }
        List<CompletableFuture<Void>> futs = tradeToClose.getLegs().stream()
            .map(w -> CompletableFuture.runAsync(() -> {
                log.debug("WeeklyLeg to close is: {}", w);
                String oppositeTransaction = BUY.equals(w.getSide()) ? SELL : BUY;
                Quote q = quotes.get(w.getExchangeSymbol());
                if (q != null) {
                    if (BUY.equals(oppositeTransaction))
                        w.setBuyIntendedPrice(q.lastPrice);
                    else
                        w.setSellIntendedPrice(q.lastPrice);
                }
                try {
                    ExecResult er = positionUtil.placeAggressiveOrder(q, w.getInstrument(), oppositeTransaction, w.getQuantity(), "EXIT");
                    if (er.aggregateOrderIds() != null && !er.aggregateOrderIds().isEmpty()) {
                        w.setCloseOrderId(er.aggregateOrderIds());
                    }
                    w.setStatus(er.fullyFilled() ? CLOSED : FAILED);
                    if (!er.fullyFilled()) {
                        log.error("[EXIT] {} ({} qty) NOT fully closed: filled={}/{} term={}",
                            w.getInstrument(), w.getQuantity(), er.totalFilled(), er.totalRequested(), er.terminalStatus());
                    }
                } catch (Exception e) {
                    log.error("Exception closing order for {} ({} qty): {}",
                        w.getInstrument(), w.getQuantity(), e.getMessage(), e);
                    w.setStatus(FAILED);
                }
            }, PositionUtil.LEG_EXEC))
            .toList();
        CompletableFuture.allOf(futs.toArray(new CompletableFuture[0])).join();
        if(updateApiAction) {
            tradeToClose.setLastSignalAction(signal.action);
            tradeToClose.setLastSignalLeg(signal.signalType);
        }
        if (tradeToClose.getLegs().stream().allMatch(ob -> CLOSED.equals(ob.getStatus()))) {
            tradeToClose.setStatus(CLOSED);
        } else {
            tradeToClose.setStatus(FAILED);
            log.error("Position closing failed - not all orders were closed successfully");
        }
        tradeToClose.setClosedAt(computeUtil.getDtTimeNow());
        log.info("Position closing completed: {}", tradeToClose);
        Position closedTrade = positionRepository.save(tradeToClose);
        return closedTrade;
    }
}
