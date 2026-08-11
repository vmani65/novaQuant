package path.to._40c.nqCore.service;

import static path.to._40c.nqCore.util.Constants.LIVE;
import static path.to._40c.nqCore.util.Constants.PARTIAL;
import static path.to._40c.nqCore.util.Constants.PENDING_OPEN;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.repo.PositionRepository;
import path.to._40c.nqCore.util.ComputeUtil;
import path.to._40c.nqCore.util.LegScope;
import path.to._40c.nqCore.util.PositionUtil;

/**
 * The once-per-signal post-trade pass. Both methods take the caller's row only for its ID
 * and re-fetch the FULL row inside the transaction — under one-position-per-signal the
 * caller's copy may be a stale, leg-filtered snapshot from one book's session, and
 * enriching a re-fetched row is the only way every book's legs are covered and no
 * concurrent write is clobbered. Runs on the single-threaded postTradeExecutor, so
 * passes for the same signal serialize.
 */
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
     * Async: enrich the row's open side — fill prices, margin/brokerage estimates, exact
     * brokerage from /charges/orders, running peakMargin (total and weekly-per-lot), and
     * per-slice fills for slippage analytics. All enrichment helpers are idempotent and
     * leg-status-driven, so a second pass over already-enriched legs (the other book's)
     * is a no-op. @Transactional keeps the Hibernate session open for the lazy fills
     * collection.
     */
    @Async("postTradeExecutor")
    @Transactional
    public void afterOpen(Position liveTrade) {
        if (liveTrade == null || liveTrade.getId() == null) {
            log.warn("afterOpen skipped - no persisted trade");
            return;
        }
        try {
            Position t = positionRepository.findById(liveTrade.getId()).orElse(null);
            if (t == null) {
                log.error("afterOpen: trade id={} not found", liveTrade.getId());
                return;
            }
            if (!(LIVE.equals(t.getStatus()) || PARTIAL.equals(t.getStatus()) || PENDING_OPEN.equals(t.getStatus()))) {
                log.warn("afterOpen skipped - trade id={} is not LIVE/PARTIAL/PENDING_OPEN (status={})", t.getId(), t.getStatus());
                return;
            }
            positionUtil.setTradeExecutedPrices(t);
            positionUtil.calcMarginAndBrokerage(t);
            positionUtil.applyActualCharges(t);
            positionUtil.calcPeakMargin(t);
            positionUtil.captureSliceFills(t);
            positionRepository.save(t);
            log.info("Post-open calc completed for trade id={}", t.getId());
        } catch (Exception e) {
            log.error("Exception while performing post trade open calculations", e);
        }
    }

    /**
     * Async: enrich the row's just-closed legs, then — ONLY when the row is terminal (every
     * leg CLOSED/FAILED, no unconfirmed order anywhere) — run the signal's accounting once:
     * outcome, P&L and the single capital-chain step. A row left non-terminal (the other
     * book's legs still open, or a PENDING_* leg) gets its enrichment now and its accounting
     * later, when the last close or the reconciler makes it terminal and calls back here.
     * The status is re-derived from the FULL leg set (a book-scoped close only saw the legs
     * its session loaded) — except an explicit PARTIAL is never downgraded to LIVE, because
     * the close reconciler stamps PARTIAL to mark revived orphan legs the roll-up alone
     * cannot recognise. @Transactional keeps the session open for the lazy fills collection.
     */
    @Async("postTradeExecutor")
    @Transactional
    public void afterClose(Position closedTrade) {
        if (closedTrade == null || closedTrade.getId() == null) return;
        try {
            Position t = positionRepository.findById(closedTrade.getId()).orElse(null);
            if (t == null) {
                log.error("afterClose: trade id={} not found", closedTrade.getId());
                return;
            }
            log.info("Position(Parent+All Child) used for computing post close calc: {}", t);
            String fresh = LegScope.rollUpStatus(t);
            if (!(PARTIAL.equals(t.getStatus()) && LIVE.equals(fresh))) {
                t.setStatus(fresh);
            }
            positionUtil.setTradeExecutedPrices(t);
            positionUtil.applyActualCharges(t);
            if (LegScope.isTerminal(t)) {
                if (t.getClosedAt() == null) {
                    t.setClosedAt(computeUtil.getDtTimeNow());
                }
                computeUtil.calcTradeOutcome(t);
                computeUtil.calcPnL(t);
                if (t.getEndingCapital() == null) {
                    computeUtil.recalculateCapital(t);
                } else {
                    log.info("afterClose: capital already stamped for trade id={} — chain not re-advanced", t.getId());
                }
            } else {
                log.info("afterClose: trade id={} not yet terminal (status={}) — enrichment done, accounting deferred",
                        t.getId(), t.getStatus());
            }
            positionUtil.captureSliceFills(t);
            positionRepository.save(t);
            log.info("Post-close calc completed for trade id={}", t.getId());
        } catch (Exception e) {
            log.error("Exception while performing post trade close calculations", e);
        }
    }
}
