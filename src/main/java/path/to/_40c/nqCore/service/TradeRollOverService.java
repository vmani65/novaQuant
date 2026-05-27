package path.to._40c.nqCore.service;

import static path.to._40c.nqCore.util.Constants.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.zerodhatech.models.LTPQuote;

import path.to._40c.nqCore.entity.Trade;
import path.to._40c.nqCore.entity.WeeklyOrderBook;
import path.to._40c.nqCore.pojo.WeeklyPojo;
import path.to._40c.nqCore.repo.TradeRepository;
import path.to._40c.nqCore.util.ComputeUtil;
import path.to._40c.nqCore.util.TradeUtil;
import path.to._40c.nqCore.util.TradeUtil.ExecResult;

@Service
public class TradeRollOverService {

	private static final Logger log = LoggerFactory.getLogger(TradeRollOverService.class);

    private final TradeRepository tradeRepository;
    private final TradeUtil tradeUtil;
    private final ComputeUtil computeUtil;
    private final PostTradeService postTradeService;

    public TradeRollOverService(TradeRepository tradeRepository, TradeUtil tradeUtil, ComputeUtil computeUtil,
                                PostTradeService postTradeService) {
        this.tradeRepository = tradeRepository;
        this.tradeUtil = tradeUtil;
        this.computeUtil = computeUtil;
        this.postTradeService = postTradeService;
    }

    /**
     * 1. Close the Live Trade first.
     * 2. Proceed with opening new trades only if all closes succeeded.
     * 3. Open live trades. Create a childOrderBook and add to the Parent and Save
     */
    public void rollOver(String signalPrice) {
        Trade tradeToRollOver = tradeUtil.findLiveTradesWithLiveOrderBooks();
        if (tradeToRollOver != null) log.info("Live Trade being rolled over is: {}", tradeToRollOver);
        else log.info("No Live trades to rollover.");
        if (tradeToRollOver != null) {
            Instant closeStart = Instant.now();
            String[] liveIns = tradeToRollOver.getWeeklyOrderBook().stream().map(WeeklyOrderBook::getTradedSymbol).toArray(String[]::new);
            Map<String, LTPQuote> ltpOfToCloseTrade = tradeUtil.getLTP(liveIns);
            if (ltpOfToCloseTrade.isEmpty()) {
                log.error("LTP map is empty for close leg — aborting rollover (auth missing or Kite error)");
                return;
            }
            AtomicBoolean allClosesSucceeded = new AtomicBoolean(true);

            IntStream.range(0, tradeToRollOver.getWeeklyOrderBook().size()).parallel().forEach(i -> {
                WeeklyOrderBook toClose = tradeToRollOver.getWeeklyOrderBook().get(i);
                log.debug("WeeklyOrderBook to rollover is: {}", toClose);
                String oppositeTransaction = BUY.equals(toClose.getTransactionType()) ? SELL : BUY;
                LTPQuote q = ltpOfToCloseTrade.get(toClose.getTradedSymbol());
                if (q != null) {
                    if (BUY.equals(oppositeTransaction))
                        toClose.setBuyIntendedPrice(q.lastPrice);
                    else
                        toClose.setSellIntendedPrice(q.lastPrice);
                }
                try {
                    ExecResult er = tradeUtil.placeAggressiveOrder(toClose.getMarginCalcSymbol(), oppositeTransaction, toClose.getQuantity(), "EXIT");
                    if (er.aggregateOrderIds() != null && !er.aggregateOrderIds().isEmpty()) {
                        toClose.setTradeCloseOrderId(er.aggregateOrderIds());
                    }
                    if (er.fullyFilled()) {
                        toClose.setTradeStatus(CLOSED);
                    } else {
                        log.error("[EXIT] {} rollover close NOT fully filled: filled={}/{} term={}",
                            toClose.getMarginCalcSymbol(), er.totalFilled(), er.totalRequested(), er.terminalStatus());
                        toClose.setTradeStatus(FAILED);
                        allClosesSucceeded.set(false);
                    }
                } catch (Exception e) {
                    log.error("Exception closing order during rollover for {}: {}", toClose.getMarginCalcSymbol(), e.getMessage(), e);
                    allClosesSucceeded.set(false);
                }
            });

            long closeMs = Duration.between(closeStart, Instant.now()).toMillis();
            if (!allClosesSucceeded.get()) {
                log.error("Rollover aborted - not all positions closed successfully");
                log.info("[PERFORMANCE] rollover | close={}ms | open=0ms | total={}ms (aborted)", closeMs, closeMs);
                return;
            }
            tradeUtil.setTradeExecPricesForRollOver(tradeToRollOver, true, false);
            Instant openStart = Instant.now();
            List<WeeklyOrderBook> childOrderBook = new ArrayList<WeeklyOrderBook>();
            double rolloverPrice = signalPrice != null ? Double.valueOf(signalPrice) : 0.0;
            tradeToRollOver.setEntrySignalPrice(Math.round(((tradeToRollOver.getEntrySignalPrice() != null ? tradeToRollOver.getEntrySignalPrice() : 0.0) + rolloverPrice) * 100.0) / 100.0);
            tradeToRollOver.setExitSignalPrice( Math.round(((tradeToRollOver.getExitSignalPrice()  != null ? tradeToRollOver.getExitSignalPrice()  : 0.0) + rolloverPrice) * 100.0) / 100.0);
            List<WeeklyPojo> weeklyPojo = computeUtil.buildInstrument(signalPrice, tradeToRollOver, true);
            String[] ltpIns = weeklyPojo.stream().map(WeeklyPojo::getTradedSymbol).toArray(String[]::new);
            log.debug("OpenTrade ltpIns is: {}", Arrays.toString(ltpIns));
            Map<String, LTPQuote> ltpOfToOpenTrade = tradeUtil.getLTP(ltpIns);
            if (ltpOfToOpenTrade.isEmpty()) {
                log.error("LTP map is empty for open leg — aborting rollover open (close already executed, manual intervention needed)");
                long abortOpenMs = Duration.between(openStart, Instant.now()).toMillis();
                log.info("[PERFORMANCE] rollover | close={}ms | open={}ms | total={}ms (aborted at open LTP)", closeMs, abortOpenMs, closeMs + abortOpenMs);
                return;
            }
            IntStream.range(0, weeklyPojo.size()).parallel().forEach(i -> {
                WeeklyPojo w = weeklyPojo.get(i);
                log.debug("WeeklyPojo to place order is: {}", w);
                int totalQty = w.getLots() * LOT_SIZE;
                try {
                    ExecResult er = tradeUtil.placeAggressiveOrder(w.getMarginCalcSymbol(), w.getTransactionType(), totalQty, "ENTRY");
                    if (er.aggregateOrderIds() != null && !er.aggregateOrderIds().isEmpty()) {
                        w.setTradeOpenOrderId(er.aggregateOrderIds());
                    }
                    w.setOpenFullyFilled(er.fullyFilled());
                    if (!er.fullyFilled()) {
                        log.error("[ENTRY] {} rollover open NOT fully filled: filled={}/{} term={}",
                            w.getMarginCalcSymbol(), er.totalFilled(), er.totalRequested(), er.terminalStatus());
                    }
                } catch (Exception e) {
                    log.error("Exception opening order during rollover for {}: {}", w.getMarginCalcSymbol(), e.getMessage(), e);
                }
            });
            long openMs = Duration.between(openStart, Instant.now()).toMillis();
            log.info("[PERFORMANCE] rollover | close={}ms | open={}ms | total={}ms (excl. fill retrieval)", closeMs, openMs, closeMs + openMs);

            weeklyPojo.forEach(pojo -> {
                WeeklyOrderBook b = new WeeklyOrderBook();
                b.setMarginCalcSymbol(pojo.getMarginCalcSymbol());
                b.setTradedSymbol(pojo.getTradedSymbol());
                b.setTransactionType(pojo.getTransactionType());
                b.setTradeOpenOrderId(pojo.getTradeOpenOrderId());
                b.setTrade(pojo.getParentTrade());
                b.setMoneyness(pojo.getMoneyness());
                b.setLots(pojo.getLots());
                b.setQuantity(pojo.getLots() * LOT_SIZE);
                b.setTradeStatus(Boolean.TRUE.equals(pojo.getOpenFullyFilled()) ? LIVE : FAILED);
                LTPQuote q = ltpOfToOpenTrade.get(pojo.getTradedSymbol());
                if (q != null) {
                    if (BUY.equals(pojo.getTransactionType()))
                        b.setBuyIntendedPrice(q.lastPrice);
                    else
                        b.setSellIntendedPrice(q.lastPrice);
                }
                childOrderBook.add(b);
            });
            tradeToRollOver.setWeeklyOrderBook(childOrderBook);
            tradeUtil.setTradeExecPricesForRollOver(tradeToRollOver, false, true);
            var liveTrade = tradeRepository.save(tradeToRollOver);
            log.info("Live Trade after rollOver completed is: {}", liveTrade);
            postTradeService.afterOpen(liveTrade);
        }
    }
}
