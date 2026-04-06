package path.to._40c.service;

import static path.to._40c.util.Constants.LIVE;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import static path.to._40c.util.Constants.LIVE;

import path.to._40c.entity.Trade;
import path.to._40c.repo.TradeRepository;
import path.to._40c.util.ComputeUtil;
import path.to._40c.util.TradeUtil;

@Service
public class PostTradeService {

    private static final Logger log = LoggerFactory.getLogger(PostTradeService.class);

    private final TradeUtil tradeUtil;
    private final ComputeUtil computeUtil;
    private final TradeRepository tradeRepository;

    public PostTradeService(TradeUtil tradeUtil, ComputeUtil computeUtil, TradeRepository tradeRepository) {
        this.tradeUtil = tradeUtil;
        this.computeUtil = computeUtil;
        this.tradeRepository = tradeRepository;
    }

    /**
     * Runs asynchronously after a trade is opened.
     * Fetches actual execution prices, calculates margin/brokerage, then persists.
     * The save lives here (not in SignalService) so the enriched data is guaranteed
     * to be set before the record is written.
     */
    @Async("postTradeExecutor")
    public void afterOpen(Trade liveTrade) {
        if(liveTrade == null || !LIVE.equals(liveTrade.getTradeStatus())){
            log.warn("afterOpen skipped - trade is not LIVE (status={})",
                liveTrade !=null ? liveTrade.getTradeStatus() : "null");
            return;
        }    
        try {
            tradeUtil.setTradeExecutedPrices(liveTrade);
            tradeUtil.calcMarginAndBrokerage(liveTrade);
            tradeRepository.save(liveTrade);
            log.info("Post-open calc completed for trade id={}", liveTrade.getId());
        } catch (Exception e) {
            log.error("Exception while performing post trade open calculations", e);
        }
    }

    /**
     * Runs asynchronously after a trade is closed.
     * Re-fetches the full trade (parent + all children) from DB, then calculates
     * execution prices, outcome, PnL, and capital, then persists.
     */
    @Async("postTradeExecutor")
    public void afterClose(Trade closedTrade) {
        if (closedTrade == null) return;
        try {
            Trade t = tradeRepository.findById(closedTrade.getId()).orElse(null);
            log.info("Trade(Parent+All Child) used for computing post close calc: {}", t);
            tradeUtil.setTradeExecutedPrices(t);
            computeUtil.calcTradeOutcome(t);
            computeUtil.calcPnL(t);
            computeUtil.recalculateCapital(t);
            tradeRepository.save(t);
            log.info("Post-close calc completed for trade id={}", closedTrade.getId());
        } catch (Exception e) {
            log.error("Exception while performing post trade close calculations", e);
        }
    }
}
