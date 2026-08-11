package path.to._40c.nqCore.service;

import static path.to._40c.nqCore.util.Constants.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.zerodhatech.models.Quote;

import path.to._40c.nqCore.controller.SignalController.Signal;
import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.entity.WeeklyLeg;
import path.to._40c.nqCore.repo.PositionRepository;
import path.to._40c.nqCore.util.ComputeUtil;
import path.to._40c.nqCore.util.ExecMode;
import path.to._40c.nqCore.util.LegScope;
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
     * Closes the given book's live legs at signalPrice. The row is the signal's shared
     * position — the finder returns whatever row holds this book's LIVE legs regardless of
     * row status, and ONLY that book's legs are closed; the other book's legs on the same
     * row are untouchable here. exitSpot is the literal final-exit spot: the current open
     * segment is the last contribution to pointsPnl, while prior re-strike segments are
     * already accumulated in the book's banked chain.
     */
    private Position closeTrade(String book, String signalPrice, Signal signal, boolean updateApiAction) {
        pendingCloseReconciler.resolveBeforeSignal();
        pendingOpenReconciler.resolveBeforeSignal();
        Position tradeToClose = positionUtil.findLiveTradesWithLiveOrderBooks(book);
        if(tradeToClose == null) {
            log.info("No Live {} trades to close.", book);
            return null;
        }
        boolean liveClose = LIVE.equals(tradeToClose.getStatus());
        if (!liveClose) {
            log.warn("ORPHAN-ish: {} live legs found on trade id={} with row status {} — closing them aggressively",
                    book, tradeToClose.getId(), tradeToClose.getStatus());
        }
        return doClose(tradeToClose, book, signalPrice, signal, updateApiAction, liveClose);
    }

    /**
     * Flattens the given book's orphan legs on a PARTIAL position (an open where only some
     * of the book's legs filled), if one exists. Called by entry-signal handling before a
     * new position is opened, so a fresh open never coexists with untracked broker
     * positions from a failed earlier open of the same book.
     */
    private Position closeOrphanIfAny(String book, String signalPrice, Signal signal) {
        pendingCloseReconciler.resolveBeforeSignal();
        pendingOpenReconciler.resolveBeforeSignal();
        Position partialTrade = positionUtil.findPartialTradesWithLiveOrderBooks(book);
        if (partialTrade == null) {
            return null;
        }
        log.warn("ORPHAN: PARTIAL trade id={} has live {} legs before new open — closing its orphan legs first",
                partialTrade.getId(), book);
        return doClose(partialTrade, book, signalPrice, signal, false, false);
    }

    /**
     * Execution-mode routing (PATIENT_EXECUTION_PLAN.md §3.1): a signal-driven close of the
     * LIVE LONG_MONTHLY legs requests PATIENT — thin monthly books deserve minutes of
     * fair-priced resting over crossing the spread. Everything else stays AGGRESSIVE: the
     * weekly book (liquid ATM) and orphan flattens (they run immediately before a new open —
     * speed beats spread). Availability (flag, config validity, IST cutoff) is re-checked
     * inside placeAggressiveOrder, which silently degrades PATIENT to the aggressive path —
     * intent is decided here, availability there.
     */
    private static ExecMode closeMode(String book, boolean patientEligible) {
        return patientEligible && LONG_MONTHLY.equals(book) ? ExecMode.PATIENT : ExecMode.AGGRESSIVE;
    }

    private Position doClose(Position tradeToClose, String book, String signalPrice, Signal signal,
            boolean updateApiAction, boolean patientEligible) {
        ExecMode mode = closeMode(book, patientEligible);
        if (mode == ExecMode.PATIENT) {
            log.info("PATIENT close requested for {} legs of trade id={} (signal-driven LIVE close)",
                    book, tradeToClose.getId());
        }
        double closePrice = Double.parseDouble(signalPrice);
        tradeToClose.setExitSpot(Math.round(closePrice * 100.0) / 100.0);
        List<WeeklyLeg> bookLegs = LegScope.of(tradeToClose, book);
        log.info("Closing {} legs of position id={}: {}", book, tradeToClose.getId(),
                bookLegs.stream().map(WeeklyLeg::getInstrument).collect(Collectors.joining(", ")));
        String[] liveIns = bookLegs.stream().map(WeeklyLeg::getExchangeSymbol).toArray(String[]::new);
        Map<String, Quote> quotes = positionUtil.getQuote(liveIns);
        if (quotes.isEmpty()) {
            log.error("Quote map is empty — aborting {} close (auth missing or Kite error); "
                    + "legs stay LIVE for the next attempt", book);
            return null;
        }
        List<CompletableFuture<Void>> futs = bookLegs.stream()
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
                    ExecResult er = positionUtil.placeAggressiveOrder(q, w.getInstrument(), oppositeTransaction, w.getQuantity(), EXIT, mode);
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
     * interleaved monthly flip (MonthlyFlipService) and the rollover close (PositionRolloverService)
     * feed their results through the identical bookkeeping. Package-visible for those callers.
     */
    void applyCloseResult(Position tradeToClose, WeeklyLeg w, Quote q, String oppositeTransaction, ExecResult er) {
        if (er.aggregateOrderIds() != null && !er.aggregateOrderIds().isEmpty()) {
            w.setCloseOrderId(er.aggregateOrderIds());
        }
        w.setCloseSpreadPaid(PositionUtil.effectiveSpreadPaid(q, oppositeTransaction, er.weightedAvgFillPrice()));
        PositionUtil.alertIfMonthlySpreadExcessive(LegScope.bookOf(w), w.getInstrument(), EXIT, w.getCloseSpreadPaid());
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
     * Resolves the row status as the roll-up over ALL legs (LegScope.rollUpStatus — a shared
     * row stays LIVE while the other book's legs are still open, goes PENDING_CLOSE while any
     * close is unconfirmed, and CLOSES only when every leg is done), stamps the signal action
     * when asked, stamps closedAt on the terminal transition, and saves. Shared with
     * MonthlyFlipService.
     */
    Position finalizeClose(Position tradeToClose, Signal signal, boolean updateApiAction) {
        if(updateApiAction) {
            tradeToClose.setLastSignalAction(signal.action);
            tradeToClose.setLastSignalLeg(signal.signalType);
        }
        String rolledUp = LegScope.rollUpStatus(tradeToClose);
        tradeToClose.setStatus(rolledUp);
        if (PENDING_CLOSE.equals(rolledUp)) {
            log.error("Position close not confirmed - a close order is still working at the broker; "
                    + "position PENDING_CLOSE (closedAt deferred), reconciler will finalize");
        } else if (LegScope.isTerminal(tradeToClose)) {
            if (tradeToClose.getClosedAt() == null) {
                tradeToClose.setClosedAt(computeUtil.getDtTimeNow());
            }
            if (FAILED.equals(rolledUp)) {
                log.error("Position closing failed - not all orders were closed successfully");
            }
        } else {
            log.info("Position id={} stays {} — the other book's legs are still open on this signal's row",
                    tradeToClose.getId(), rolledUp);
        }
        log.info("Position closing completed: {}", tradeToClose);
        Position closedTrade = positionRepository.save(tradeToClose);
        return closedTrade;
    }
}
