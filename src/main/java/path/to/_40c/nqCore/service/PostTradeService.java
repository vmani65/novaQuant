package path.to._40c.nqCore.service;

import static path.to._40c.nqCore.util.Constants.LIVE;
import static path.to._40c.nqCore.util.Constants.PARTIAL;
import static path.to._40c.nqCore.util.Constants.PENDING_CLOSE;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.repo.PositionRepository;
import path.to._40c.nqCore.util.ComputeUtil;
import path.to._40c.nqCore.util.PositionUtil;

@Service
@Slf4j
public class PostTradeService {
    private final PositionUtil positionUtil;
    private final ComputeUtil computeUtil;
    private final PositionRepository positionRepository;

    public PostTradeService(PositionUtil positionUtil, ComputeUtil computeUtil, PositionRepository positionRepository) {
        this.positionUtil = positionUtil;
        this.computeUtil = computeUtil;
        this.positionRepository = positionRepository;
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
            Position t = positionRepository.findById(liveTrade.getId()).orElse(null);
            if (t == null) { positionRepository.save(liveTrade); return; }
            liveTrade.getLegs().forEach(liveW ->
                t.getLegs().stream()
                    .filter(dbW -> dbW.getId().equals(liveW.getId()))
                    .findFirst()
                    .ifPresent(dbW -> {
                        if (liveW.getBuyFillPrice()        != null) dbW.setBuyFillPrice(liveW.getBuyFillPrice());
                        if (liveW.getSellFillPrice()           != null) dbW.setSellFillPrice(liveW.getSellFillPrice());
                        if (liveW.getMarginRequired()       != null) dbW.setMarginRequired(liveW.getMarginRequired());
                        if (liveW.getOpenCharges()  != null) dbW.setOpenCharges(liveW.getOpenCharges());
                        if (liveW.getCloseCharges() != null) dbW.setCloseCharges(liveW.getCloseCharges());
                    })
            );
            positionUtil.calcPeakMargin(t);
            positionUtil.captureSliceFills(t);
            positionRepository.save(t);
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
    public void afterClose(Position closedTrade) {
        if (closedTrade == null) return;
        if (PENDING_CLOSE.equals(closedTrade.getStatus())) {
            log.warn("afterClose deferred — trade id={} is PENDING_CLOSE; PendingCloseReconciler runs it after the close settles",
                closedTrade.getId());
            return;
        }
        try {
            positionUtil.setTradeExecutedPrices(closedTrade);
            positionUtil.applyActualCharges(closedTrade);

            Position t = positionRepository.findById(closedTrade.getId()).orElse(null);
            if (t == null) { positionRepository.save(closedTrade); return; }
            log.info("Position(Parent+All Child) used for computing post close calc: {}", t);

            closedTrade.getLegs().forEach(closedW ->
                t.getLegs().stream()
                    .filter(dbW -> dbW.getId().equals(closedW.getId()))
                    .findFirst()
                    .ifPresent(dbW -> {
                        if (closedW.getBuyFillPrice()        != null) dbW.setBuyFillPrice(closedW.getBuyFillPrice());
                        if (closedW.getSellFillPrice()           != null) dbW.setSellFillPrice(closedW.getSellFillPrice());
                        if (closedW.getCloseCharges() != null) dbW.setCloseCharges(closedW.getCloseCharges());
                    })
            );

            computeUtil.calcTradeOutcome(t);
            computeUtil.calcPnL(t);
            computeUtil.recalculateCapital(t);
            positionUtil.captureSliceFills(t);
            positionRepository.save(t);
            log.info("Post-close calc completed for trade id={}", closedTrade.getId());
        } catch (Exception e) {
            log.error("Exception while performing post trade close calculations", e);
        }
    }
}
