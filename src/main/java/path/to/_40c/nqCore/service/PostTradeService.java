package path.to._40c.nqCore.service;

import static path.to._40c.nqCore.util.Constants.LIVE;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import path.to._40c.nqCore.entity.Trade;
import path.to._40c.nqCore.repo.TradeRepository;
import path.to._40c.nqCore.util.ComputeUtil;
import path.to._40c.nqCore.util.TradeUtil;

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
     * Async: enrich newly-opened legs with fill prices, margin/brokerage estimates, exact
     * brokerage from /charges/orders, then update running peakMargin and capture per-slice
     * fills for slippage analytics. Re-fetches and merges to avoid clobbering concurrent
     * close-side writes on the same trade. @Transactional keeps the Hibernate session open
     * for the lazy fills collection.
     */
    @Async("postTradeExecutor")
    @Transactional
    public void afterOpen(Trade liveTrade) {
        if (liveTrade == null || !LIVE.equals(liveTrade.getTradeStatus())) {
            log.warn("afterOpen skipped - trade is not LIVE (status={})",
                liveTrade != null ? liveTrade.getTradeStatus() : "null");
            return;
        }
        try {
            tradeUtil.setTradeExecutedPrices(liveTrade);
            tradeUtil.calcMarginAndBrokerage(liveTrade);
            tradeUtil.applyActualCharges(liveTrade);
            Trade t = tradeRepository.findById(liveTrade.getId()).orElse(null);
            if (t == null) { tradeRepository.save(liveTrade); return; }
            liveTrade.getWeeklyOrderBook().forEach(liveW ->
                t.getWeeklyOrderBook().stream()
                    .filter(dbW -> dbW.getId().equals(liveW.getId()))
                    .findFirst()
                    .ifPresent(dbW -> {
                        if (liveW.getBoughtPrice()        != null) dbW.setBoughtPrice(liveW.getBoughtPrice());
                        if (liveW.getSoldPrice()           != null) dbW.setSoldPrice(liveW.getSoldPrice());
                        if (liveW.getMarginToTrade()       != null) dbW.setMarginToTrade(liveW.getMarginToTrade());
                        if (liveW.getTradeOpenBrokerage()  != null) dbW.setTradeOpenBrokerage(liveW.getTradeOpenBrokerage());
                        if (liveW.getTradeCloseBrokerage() != null) dbW.setTradeCloseBrokerage(liveW.getTradeCloseBrokerage());
                    })
            );
            tradeUtil.calcPeakMargin(t);
            tradeUtil.captureSliceFills(t);
            tradeRepository.save(t);
            log.info("Post-open calc completed for trade id={}", liveTrade.getId());
        } catch (Exception e) {
            log.error("Exception while performing post trade open calculations", e);
        }
    }

    /**
     * Async: enrich just-closed legs then merge into the full trade for outcome/PnL/capital.
     * Earlier-segment legs are skipped here (already enriched, orderIDs are stale-day).
     * @Transactional keeps the Hibernate session open for the lazy fills collection.
     */
    @Async("postTradeExecutor")
    @Transactional
    public void afterClose(Trade closedTrade) {
        if (closedTrade == null) return;
        try {
            tradeUtil.setTradeExecutedPrices(closedTrade);
            tradeUtil.applyActualCharges(closedTrade);

            Trade t = tradeRepository.findById(closedTrade.getId()).orElse(null);
            if (t == null) { tradeRepository.save(closedTrade); return; }
            log.info("Trade(Parent+All Child) used for computing post close calc: {}", t);

            closedTrade.getWeeklyOrderBook().forEach(closedW ->
                t.getWeeklyOrderBook().stream()
                    .filter(dbW -> dbW.getId().equals(closedW.getId()))
                    .findFirst()
                    .ifPresent(dbW -> {
                        if (closedW.getBoughtPrice()        != null) dbW.setBoughtPrice(closedW.getBoughtPrice());
                        if (closedW.getSoldPrice()           != null) dbW.setSoldPrice(closedW.getSoldPrice());
                        if (closedW.getTradeCloseBrokerage() != null) dbW.setTradeCloseBrokerage(closedW.getTradeCloseBrokerage());
                    })
            );

            computeUtil.calcTradeOutcome(t);
            computeUtil.calcPnL(t);
            computeUtil.recalculateCapital(t);
            tradeUtil.captureSliceFills(t);
            tradeRepository.save(t);
            log.info("Post-close calc completed for trade id={}", closedTrade.getId());
        } catch (Exception e) {
            log.error("Exception while performing post trade close calculations", e);
        }
    }
}
