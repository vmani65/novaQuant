package path.to._40c.nqCore.util;

import static path.to._40c.nqCore.util.Constants.CLOSED;
import static path.to._40c.nqCore.util.Constants.FAILED;
import static path.to._40c.nqCore.util.Constants.LIVE;
import static path.to._40c.nqCore.util.Constants.PARTIAL;
import static path.to._40c.nqCore.util.Constants.PENDING_CLOSE;
import static path.to._40c.nqCore.util.Constants.PENDING_OPEN;
import static path.to._40c.nqCore.util.Constants.SYNTH_WEEKLY;

import java.util.List;

import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.entity.WeeklyLeg;

/**
 * The one-position-per-signal helpers: book-scoped leg selection and the single status
 * roll-up truth table. Every operational path (close, rollover, recenter, flip) selects
 * legs through {@link #of}, and every status write goes through {@link #rollUpStatus} —
 * one row now represents both books, so no path may ever reason from the row status or
 * touch legs outside its own book.
 */
public final class LegScope {

    private LegScope() {}

    /** The book that owns a leg; a null book is a pre-restructure weekly leg. */
    public static String bookOf(WeeklyLeg leg) {
        return leg.getBook() != null ? leg.getBook() : SYNTH_WEEKLY;
    }

    /** The given book's legs of this position (whatever subset the caller's session loaded). */
    public static List<WeeklyLeg> of(Position position, String book) {
        return position.getLegs().stream().filter(l -> book.equals(bookOf(l))).toList();
    }

    /**
     * A leg that never existed at the broker: FAILED at open with no fills on either side.
     * Fill prices are the discriminator, not the order id — an id may be recorded for an
     * order that was placed but never filled. Distinct from a leg whose CLOSE failed —
     * that one has an open-side fill price and still represents a real broker position.
     */
    public static boolean neverTraded(WeeklyLeg leg) {
        return FAILED.equals(leg.getStatus())
                && leg.getBuyFillPrice() == null && leg.getSellFillPrice() == null;
    }

    /**
     * A FAILED leg that shows evidence of having traded at the broker — an open-side fill
     * price OR a recorded open order id. Fill prices arrive via ASYNC enrichment, but a
     * production LIVE leg always carries its openOrderId synchronously, so a close-FAILED
     * leg is recognisable even before its fills are enriched. Distinct from neverTraded
     * (fills only), which classifies P&L and orphan shape.
     */
    private static boolean failedWithTradeEvidence(WeeklyLeg leg) {
        return FAILED.equals(leg.getStatus())
                && (leg.getBuyFillPrice() != null || leg.getSellFillPrice() != null
                        || (leg.getOpenOrderId() != null && !leg.getOpenOrderId().isBlank()));
    }

    /**
     * The row status derived from ALL legs, first match wins:
     * 1. any leg PENDING_OPEN → PENDING_OPEN (an entry is unconfirmed at the broker)
     * 2. any leg PENDING_CLOSE → PENDING_CLOSE (an exit is unconfirmed; capital deferred)
     * 3. any book holding both a LIVE leg and a never-traded FAILED leg → PARTIAL
     *    (that book's open half-failed; its live legs are orphans to flatten)
     * 4. any leg LIVE → LIVE
     * 5. any FAILED leg with trade evidence → FAILED (a close failed or an entry filled
     *    then died — the broker may still hold it; the row must never read CLOSED)
     * 6. any leg CLOSED → CLOSED
     * 7. nothing ever traded → FAILED
     * Must see the FULL leg set — callers holding a session-filtered position re-fetch or
     * evaluate before the filter narrows the collection.
     */
    public static String rollUpStatus(Position position) {
        List<WeeklyLeg> legs = position.getLegs();
        if (legs.stream().anyMatch(l -> PENDING_OPEN.equals(l.getStatus()))) return PENDING_OPEN;
        if (legs.stream().anyMatch(l -> PENDING_CLOSE.equals(l.getStatus()))) return PENDING_CLOSE;
        boolean anyBookOrphaned = legs.stream().map(LegScope::bookOf).distinct().anyMatch(book -> {
            List<WeeklyLeg> bookLegs = of(position, book);
            return bookLegs.stream().anyMatch(l -> LIVE.equals(l.getStatus()))
                    && bookLegs.stream().anyMatch(LegScope::neverTraded);
        });
        if (anyBookOrphaned) return PARTIAL;
        if (legs.stream().anyMatch(l -> LIVE.equals(l.getStatus()))) return LIVE;
        if (legs.stream().anyMatch(LegScope::failedWithTradeEvidence)) return FAILED;
        if (legs.stream().anyMatch(l -> CLOSED.equals(l.getStatus()))) return CLOSED;
        return FAILED;
    }

    /**
     * True when every leg has reached a final state (CLOSED or FAILED) — the gate for the
     * once-per-signal post-close accounting (outcome, P&L, capital chain). A leg still
     * LIVE / PENDING_OPEN / PENDING_CLOSE means part of the signal's money is unconfirmed,
     * so the accounting waits.
     */
    public static boolean isTerminal(Position position) {
        return !position.getLegs().isEmpty() && position.getLegs().stream()
                .allMatch(l -> CLOSED.equals(l.getStatus()) || FAILED.equals(l.getStatus()));
    }

    /** True when the given book has a close order unconfirmed at the broker on this position. */
    public static boolean hasPendingCloseLegs(Position position, String book) {
        return of(position, book).stream().anyMatch(l -> PENDING_CLOSE.equals(l.getStatus()));
    }
}
