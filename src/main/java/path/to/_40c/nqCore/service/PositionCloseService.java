package path.to._40c.nqCore.service;

import static path.to._40c.nqCore.util.Constants.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.zerodhatech.models.Quote;

import path.to._40c.nqCore.controller.SignalController.Signal;
import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.entity.WeeklyLeg;
import path.to._40c.nqCore.repo.PositionRepository;
import path.to._40c.nqCore.util.ComputeUtil;
import path.to._40c.nqCore.util.ExecMode;
import path.to._40c.nqCore.util.PositionUtil;
import path.to._40c.nqCore.util.PositionUtil.ExecResult;

@Service
@Slf4j
public class PositionCloseService {
    private final PositionRepository positionRepository;
    private final PositionUtil positionUtil;
    private final ComputeUtil computeUtil;
    private final PendingCloseReconciler pendingCloseReconciler;
    private final PendingOpenReconciler pendingOpenReconciler;

    public PositionCloseService(PositionRepository positionRepository, PositionUtil positionUtil,
            ComputeUtil computeUtil, PendingCloseReconciler pendingCloseReconciler,
            PendingOpenReconciler pendingOpenReconciler) {
        this.positionRepository = positionRepository;
        this.positionUtil = positionUtil;
        this.computeUtil = computeUtil;
        this.pendingCloseReconciler = pendingCloseReconciler;
        this.pendingOpenReconciler = pendingOpenReconciler;
    }

    /** Closes the SYNTH_WEEKLY book's live position. See closeTrade for semantics. */
    public Position closeWeeklyTrade(String signalPrice, Signal signal, boolean updateApiAction) {
        return closeTrade(SYNTH_WEEKLY, signalPrice, signal, updateApiAction);
    }

    /** Closes the LONG_MONTHLY book's live position. See closeTrade for semantics. */
    public Position closeMonthlyTrade(String signalPrice, Signal signal, boolean updateApiAction) {
        return closeTrade(LONG_MONTHLY, signalPrice, signal, updateApiAction);
    }

    /** Flattens SYNTH_WEEKLY orphan legs, if any. See closeOrphanIfAny for semantics. */
    public Position closeWeeklyOrphanIfAny(String signalPrice, Signal signal) {
        return closeOrphanIfAny(SYNTH_WEEKLY, signalPrice, signal);
    }

    /** Flattens LONG_MONTHLY orphan legs, if any. See closeOrphanIfAny for semantics. */
    public Position closeMonthlyOrphanIfAny(String signalPrice, Signal signal) {
        return closeOrphanIfAny(LONG_MONTHLY, signalPrice, signal);
    }

    /**
     * Closes the given book's live position at signalPrice. exitSpot is the literal final-exit
     * spot: the current open segment (baselineSpot -> exitSpot) is the last contribution to
     * pointsPnl, while all prior re-strike segments are already accumulated in bankedPoints.
     * Only positions stamped with this book are candidates — the other book's LIVE position
     * is invisible here.
     */
    private Position closeTrade(String book, String signalPrice, Signal signal, boolean updateApiAction) {
        pendingCloseReconciler.resolveBeforeSignal();
        pendingOpenReconciler.resolveBeforeSignal();
        Position tradeToClose = positionUtil.findLiveTradesWithLiveOrderBooks(book);
        if (tradeToClose == null) {
            tradeToClose = positionUtil.findPartialTradesWithLiveOrderBooks(book);
            if (tradeToClose != null) {
                log.warn("ORPHAN: no LIVE {} trade, but PARTIAL trade id={} has orphan legs — closing them now",
                        book, tradeToClose.getId());
            }
        }
        if(tradeToClose == null) {
            log.info("No Live {} trades to close.", book);
            return null;
        }
        boolean liveClose = LIVE.equals(tradeToClose.getStatus());
        return doClose(tradeToClose, signalPrice, signal, updateApiAction, liveClose);
    }

    /**
     * Flattens the orphan legs of the given book's PARTIAL position (an open where only some
     * legs filled), if one exists. Called by entry-signal handling before a new position is
     * opened, so a fresh open never coexists with untracked broker positions from a failed
     * earlier open of the same book.
     */
    private Position closeOrphanIfAny(String book, String signalPrice, Signal signal) {
        pendingCloseReconciler.resolveBeforeSignal();
        pendingOpenReconciler.resolveBeforeSignal();
        Position partialTrade = positionUtil.findPartialTradesWithLiveOrderBooks(book);
        if (partialTrade == null) {
            return null;
        }
        log.warn("ORPHAN: PARTIAL {} trade id={} found before new open — closing its orphan legs first",
                book, partialTrade.getId());
        return doClose(partialTrade, signalPrice, signal, false, false);
    }

    /**
     * Execution-mode routing (PATIENT_EXECUTION_PLAN.md §3.1): a signal-driven close of a LIVE
     * LONG_MONTHLY position requests PATIENT — thin monthly books deserve minutes of fair-priced
     * resting over crossing the spread. Everything else stays AGGRESSIVE: the weekly book (liquid
     * ATM), orphan flattens (they run immediately before a new open — speed beats spread), and the
     * PARTIAL fallback inside closeTrade (semantically an orphan flatten). Availability (flag,
     * config validity, IST cutoff) is re-checked inside placeAggressiveOrder, which silently
     * degrades PATIENT to the aggressive path — intent is decided here, availability there.
     */
    private static ExecMode closeMode(Position tradeToClose, boolean patientEligible) {
        return patientEligible && LONG_MONTHLY.equals(tradeToClose.getBook())
                ? ExecMode.PATIENT : ExecMode.AGGRESSIVE;
    }

    private Position doClose(Position tradeToClose, String signalPrice, Signal signal, boolean updateApiAction,
            boolean patientEligible) {
        ExecMode mode = closeMode(tradeToClose, patientEligible);
        if (mode == ExecMode.PATIENT) {
            log.info("PATIENT close requested for {} trade id={} (signal-driven LIVE close)",
                    tradeToClose.getBook(), tradeToClose.getId());
        }
        double closePrice = Double.parseDouble(signalPrice);
        tradeToClose.setExitSpot(Math.round(closePrice * 100.0) / 100.0);
        log.info("Live Position being closed is: {}", tradeToClose);
        String[] liveIns = tradeToClose.getLegs().stream().map(WeeklyLeg::getExchangeSymbol).toArray(String[]::new);
        Map<String, Quote> quotes = positionUtil.getQuote(liveIns);
        if (quotes.isEmpty()) {
            log.error("Quote map is empty — aborting trade close (auth missing or Kite error)");
            if (PARTIAL.equals(tradeToClose.getStatus())) {
                log.warn("ORPHAN: trade id={} stays PARTIAL — orphan close will retry on the next signal", tradeToClose.getId());
                return null;
            }
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
                    ExecResult er = positionUtil.placeAggressiveOrder(q, w.getInstrument(), oppositeTransaction, w.getQuantity(), "EXIT", mode);
                    applyCloseResult(tradeToClose, w, q, oppositeTransaction, er);
                } catch (Exception e) {
                    log.error("Exception closing order for {} ({} qty): {}",
                        w.getInstrument(), w.getQuantity(), e.getMessage(), e);
                    w.setStatus(FAILED);
                }
            }, PositionUtil.LEG_EXEC))
            .toList();
        CompletableFuture.allOf(futs.toArray(new CompletableFuture[0])).join();
        return finalizeClose(tradeToClose, signal, updateApiAction);
    }

    /**
     * Records one leg's close-execution outcome: order ids, spread paid, liquidity alert, and the
     * CLOSED / PENDING_CLOSE / FAILED leg status. Extracted from doClose's placement lambda so the
     * interleaved monthly flip (MonthlyFlipService) can feed its aggregate slice result through
     * the identical bookkeeping. Package-visible for that single caller.
     */
    void applyCloseResult(Position tradeToClose, WeeklyLeg w, Quote q, String oppositeTransaction, ExecResult er) {
        if (er.aggregateOrderIds() != null && !er.aggregateOrderIds().isEmpty()) {
            w.setCloseOrderId(er.aggregateOrderIds());
        }
        w.setCloseSpreadPaid(PositionUtil.effectiveSpreadPaid(q, oppositeTransaction, er.weightedAvgFillPrice()));
        PositionUtil.alertIfMonthlySpreadExcessive(tradeToClose.getBook(), w.getInstrument(), "EXIT", w.getCloseSpreadPaid());
        if (er.fullyFilled()) {
            w.setStatus(CLOSED);
        } else if (PositionUtil.closeOrderMayBeLive(er)) {
            w.setStatus(PENDING_CLOSE);
            log.error("[EXIT] {} ({} qty) close order {} still working at broker (term={}, filled={}/{}) — "
                    + "leg PENDING_CLOSE, reconciler will settle it from the tradebook",
                w.getInstrument(), w.getQuantity(), er.aggregateOrderIds(), er.terminalStatus(),
                er.totalFilled(), er.totalRequested());
        } else {
            w.setStatus(FAILED);
            log.error("[EXIT] {} ({} qty) NOT fully closed: filled={}/{} term={}",
                w.getInstrument(), w.getQuantity(), er.totalFilled(), er.totalRequested(), er.terminalStatus());
        }
    }

    /**
     * Resolves the position status from its legs (all CLOSED → CLOSED, any PENDING_CLOSE →
     * PENDING_CLOSE with closedAt deferred, else FAILED), stamps the signal action when asked,
     * and saves. Extracted from doClose's tail; shared with MonthlyFlipService.
     */
    Position finalizeClose(Position tradeToClose, Signal signal, boolean updateApiAction) {
        if(updateApiAction) {
            tradeToClose.setLastSignalAction(signal.action);
            tradeToClose.setLastSignalLeg(signal.signalType);
        }
        if (tradeToClose.getLegs().stream().allMatch(ob -> CLOSED.equals(ob.getStatus()))) {
            tradeToClose.setStatus(CLOSED);
            tradeToClose.setClosedAt(computeUtil.getDtTimeNow());
        } else if (tradeToClose.getLegs().stream().anyMatch(ob -> PENDING_CLOSE.equals(ob.getStatus()))) {
            tradeToClose.setStatus(PENDING_CLOSE);
            log.error("Position close not confirmed - a close order is still working at the broker; "
                    + "position PENDING_CLOSE (closedAt deferred), reconciler will finalize");
        } else {
            tradeToClose.setStatus(FAILED);
            tradeToClose.setClosedAt(computeUtil.getDtTimeNow());
            log.error("Position closing failed - not all orders were closed successfully");
        }
        log.info("Position closing completed: {}", tradeToClose);
        Position closedTrade = positionRepository.save(tradeToClose);
        return closedTrade;
    }
}
