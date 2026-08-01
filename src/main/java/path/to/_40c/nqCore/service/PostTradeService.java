package path.to._40c.nqCore.service;

import static path.to._40c.nqCore.util.Constants.LIVE;
import static path.to._40c.nqCore.util.Constants.PARTIAL;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.repo.PositionRepository;
import path.to._40c.nqCore.util.ComputeUtil;
import path.to._40c.nqCore.util.PositionUtil;

/**
 * Post-trade enrichment in two phases. Phase 1 (postTradeExecutor, NO transaction): slow
 * broker I/O — fill prices, margin/brokerage, exact charges — mutating only the detached
 * entity. Phase 2 (trade-exec queue, short TransactionTemplate): re-fetch, merge, compute,
 * save. The old single-@Transactional shape held a SQLite write transaction across tens of
 * seconds of broker polling, which collided with the next queued trade's save the moment two
 * strategies fired back-to-back (instant SQLITE_BUSY via lock-upgrade deadlock avoidance).
 * Routing every position/capital write through the queue makes the app single-writer by
 * construction.
 *
 * Ordering note: phase-2 tasks enqueue when their phase-1 I/O completes, so two closes'
 * capital updates may commit in either order — harmless, capital addition is commutative
 * and the equity curve sorts by openedAt.
 */
@Service
@Slf4j
public class PostTradeService {
    private final PositionUtil positionUtil;
    private final ComputeUtil computeUtil;
    private final PositionRepository positionRepository;
    private final TradeExecutionQueue executionQueue;
    private final TransactionTemplate transactionTemplate;

    public PostTradeService(PositionUtil positionUtil, ComputeUtil computeUtil, PositionRepository positionRepository,
            TradeExecutionQueue executionQueue, TransactionTemplate transactionTemplate) {
        this.positionUtil = positionUtil;
        this.computeUtil = computeUtil;
        this.positionRepository = positionRepository;
        this.executionQueue = executionQueue;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Async phase 1 for a just-opened trade: enrich legs with fill prices, margin/brokerage
     * estimates and exact brokerage from the broker (no DB access), then hand the write to
     * the trade-exec queue. Fire-and-forget on the queue future — this thread must never
     * join into the queue.
     */
    @Async("postTradeExecutor")
    public void afterOpen(Position liveTrade) {
        if (liveTrade == null || !(LIVE.equals(liveTrade.getStatus()) || PARTIAL.equals(liveTrade.getStatus()))) {
            log.warn("afterOpen skipped - trade is not LIVE/PARTIAL (status={})",
                liveTrade != null ? liveTrade.getStatus() : "null");
            return;
        }
        try {
            positionUtil.setTradeExecutedPrices(liveTrade);
            positionUtil.calcMarginAndBrokerage(liveTrade);
            positionUtil.applyActualCharges(liveTrade);
        } catch (Exception e) {
            log.error("Exception during post-open broker enrichment — merging whatever was fetched", e);
        }
        executionQueue.submit("post-open:trade#" + liveTrade.getId(), () -> {
            mergeAndSaveOpen(liveTrade);
            return true;
        });
    }

    /**
     * Async phase 1 for a just-closed trade: fetch close-side fills and exact charges (no DB
     * access), then hand outcome/PnL/capital computation and the save to the trade-exec queue.
     * Earlier-segment legs are skipped by the enrichment (already enriched, orderIDs are
     * stale-day).
     */
    @Async("postTradeExecutor")
    public void afterClose(Position closedTrade) {
        if (closedTrade == null) return;
        try {
            positionUtil.setTradeExecutedPrices(closedTrade);
            positionUtil.applyActualCharges(closedTrade);
        } catch (Exception e) {
            log.error("Exception during post-close broker enrichment — merging whatever was fetched", e);
        }
        executionQueue.submit("post-close:trade#" + closedTrade.getId(), () -> {
            mergeAndSaveClose(closedTrade);
            return true;
        });
    }

    /**
     * Queue-thread phase 2 (open): merge enriched fields into a fresh copy of the trade,
     * update peakMargin, capture per-slice fills, save. The TransactionTemplate keeps the
     * Hibernate session open for the lazy fills collection and bounds the write transaction
     * to milliseconds.
     */
    private void mergeAndSaveOpen(Position liveTrade) {
        transactionTemplate.executeWithoutResult(txStatus -> {
            Position t = positionRepository.findById(liveTrade.getId()).orElse(null);
            if (t == null) {
                positionRepository.save(liveTrade);
                return;
            }
            liveTrade.getLegs().forEach(liveW ->
                t.getLegs().stream()
                    .filter(dbW -> dbW.getId().equals(liveW.getId()))
                    .findFirst()
                    .ifPresent(dbW -> {
                        if (liveW.getBuyFillPrice()   != null) dbW.setBuyFillPrice(liveW.getBuyFillPrice());
                        if (liveW.getSellFillPrice()  != null) dbW.setSellFillPrice(liveW.getSellFillPrice());
                        if (liveW.getMarginRequired() != null) dbW.setMarginRequired(liveW.getMarginRequired());
                        if (liveW.getOpenCharges()    != null) dbW.setOpenCharges(liveW.getOpenCharges());
                        if (liveW.getCloseCharges()   != null) dbW.setCloseCharges(liveW.getCloseCharges());
                    })
            );
            positionUtil.calcPeakMargin(t);
            positionUtil.captureSliceFills(t);
            positionRepository.save(t);
        });
        log.info("Post-open calc completed for trade id={}", liveTrade.getId());
    }

    /**
     * Queue-thread phase 2 (close): merge close-side fills/charges into a fresh copy, compute
     * outcome, PnL and the global capital update, capture per-slice fills, save. Running here
     * serializes the capital read-modify-write with every other trade mutation.
     */
    private void mergeAndSaveClose(Position closedTrade) {
        transactionTemplate.executeWithoutResult(txStatus -> {
            Position t = positionRepository.findById(closedTrade.getId()).orElse(null);
            if (t == null) {
                positionRepository.save(closedTrade);
                return;
            }
            log.info("Position(Parent+All Child) used for computing post close calc: {}", t);
            closedTrade.getLegs().forEach(closedW ->
                t.getLegs().stream()
                    .filter(dbW -> dbW.getId().equals(closedW.getId()))
                    .findFirst()
                    .ifPresent(dbW -> {
                        if (closedW.getBuyFillPrice()  != null) dbW.setBuyFillPrice(closedW.getBuyFillPrice());
                        if (closedW.getSellFillPrice() != null) dbW.setSellFillPrice(closedW.getSellFillPrice());
                        if (closedW.getCloseCharges()  != null) dbW.setCloseCharges(closedW.getCloseCharges());
                    })
            );
            computeUtil.calcTradeOutcome(t);
            computeUtil.calcPnL(t);
            computeUtil.recalculateCapital(t);
            positionUtil.captureSliceFills(t);
            positionRepository.save(t);
        });
        log.info("Post-close calc completed for trade id={}", closedTrade.getId());
    }
}
