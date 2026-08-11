package path.to._40c.nqCore.util;

import static org.assertj.core.api.Assertions.assertThat;
import static path.to._40c.nqCore.util.Constants.CLOSED;
import static path.to._40c.nqCore.util.Constants.FAILED;
import static path.to._40c.nqCore.util.Constants.LIVE;
import static path.to._40c.nqCore.util.Constants.LONG_MONTHLY;
import static path.to._40c.nqCore.util.Constants.PARTIAL;
import static path.to._40c.nqCore.util.Constants.PENDING_CLOSE;
import static path.to._40c.nqCore.util.Constants.PENDING_OPEN;
import static path.to._40c.nqCore.util.Constants.SYNTH_WEEKLY;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.entity.WeeklyLeg;

/**
 * The single status truth table of the one-position-per-signal model. Every status write
 * in the system routes through rollUpStatus, so each rule is pinned here — especially the
 * mixed-book rows the old per-book model could never produce: a PARTIAL must mean "some
 * BOOK's open half-failed", never "one book closed while the other is still open".
 */
class LegScopeTest {

    @Test
    @DisplayName("any PENDING_OPEN leg wins over everything — an unconfirmed entry owns the row")
    void pendingOpenWins() {
        assertThat(LegScope.rollUpStatus(row(
                leg(SYNTH_WEEKLY, LIVE, true),
                leg(LONG_MONTHLY, PENDING_OPEN, false)))).isEqualTo(PENDING_OPEN);
    }

    @Test
    @DisplayName("any PENDING_CLOSE leg → PENDING_CLOSE, even while the other book is still LIVE")
    void pendingCloseWinsOverLive() {
        assertThat(LegScope.rollUpStatus(row(
                leg(SYNTH_WEEKLY, PENDING_CLOSE, true),
                leg(LONG_MONTHLY, LIVE, true)))).isEqualTo(PENDING_CLOSE);
    }

    @Test
    @DisplayName("a book with a LIVE leg AND a never-traded FAILED leg is orphan-shaped → PARTIAL")
    void sameBookOrphanShapeIsPartial() {
        assertThat(LegScope.rollUpStatus(row(
                leg(SYNTH_WEEKLY, LIVE, true),
                leg(SYNTH_WEEKLY, FAILED, false)))).isEqualTo(PARTIAL);
    }

    @Test
    @DisplayName("a dead book (all never-traded) beside a healthy LIVE book is NOT an orphan → LIVE")
    void deadBookBesideHealthyBookIsLive() {
        assertThat(LegScope.rollUpStatus(row(
                leg(SYNTH_WEEKLY, LIVE, true),
                leg(SYNTH_WEEKLY, LIVE, true),
                leg(LONG_MONTHLY, FAILED, false)))).isEqualTo(LIVE);
    }

    @Test
    @DisplayName("one book CLOSED while the other is LIVE → the row stays LIVE (mid-fan-out / 9:15 split close)")
    void mixedClosedAndLiveStaysLive() {
        assertThat(LegScope.rollUpStatus(row(
                leg(SYNTH_WEEKLY, CLOSED, true),
                leg(LONG_MONTHLY, LIVE, true)))).isEqualTo(LIVE);
    }

    @Test
    @DisplayName("rolled row: old CLOSED legs beside new LIVE legs → LIVE")
    void rolledRowStaysLive() {
        assertThat(LegScope.rollUpStatus(row(
                leg(SYNTH_WEEKLY, CLOSED, true),
                leg(SYNTH_WEEKLY, LIVE, true)))).isEqualTo(LIVE);
    }

    @Test
    @DisplayName("all traded legs CLOSED → CLOSED, never-traded legs don't block it")
    void allTradedClosedIsClosed() {
        assertThat(LegScope.rollUpStatus(row(
                leg(SYNTH_WEEKLY, CLOSED, true),
                leg(LONG_MONTHLY, CLOSED, true),
                leg(LONG_MONTHLY, FAILED, false)))).isEqualTo(CLOSED);
    }

    @Test
    @DisplayName("a traded leg whose CLOSE failed → FAILED (broker still holds it — trade-73 class visibility)")
    void closeFailedLegMakesRowFailed() {
        assertThat(LegScope.rollUpStatus(row(
                leg(SYNTH_WEEKLY, CLOSED, true),
                leg(SYNTH_WEEKLY, FAILED, true)))).isEqualTo(FAILED);
    }

    @Test
    @DisplayName("nothing ever traded → FAILED")
    void nothingTradedIsFailed() {
        assertThat(LegScope.rollUpStatus(row(
                leg(SYNTH_WEEKLY, FAILED, false),
                leg(LONG_MONTHLY, FAILED, false)))).isEqualTo(FAILED);
    }

    @Test
    @DisplayName("isTerminal: only CLOSED/FAILED legs; any LIVE or PENDING blocks the accounting gate")
    void terminalGate() {
        assertThat(LegScope.isTerminal(row(leg(SYNTH_WEEKLY, CLOSED, true), leg(LONG_MONTHLY, FAILED, false)))).isTrue();
        assertThat(LegScope.isTerminal(row(leg(SYNTH_WEEKLY, CLOSED, true), leg(LONG_MONTHLY, LIVE, true)))).isFalse();
        assertThat(LegScope.isTerminal(row(leg(SYNTH_WEEKLY, PENDING_CLOSE, true)))).isFalse();
        assertThat(LegScope.isTerminal(new Position())).isFalse();
    }

    @Test
    @DisplayName("bookOf: a null-book legacy leg counts as SYNTH_WEEKLY; hasPendingCloseLegs is book-scoped")
    void bookScoping() {
        WeeklyLeg legacy = leg(null, LIVE, true);
        assertThat(LegScope.bookOf(legacy)).isEqualTo(SYNTH_WEEKLY);
        Position p = row(leg(SYNTH_WEEKLY, PENDING_CLOSE, true), leg(LONG_MONTHLY, LIVE, true));
        assertThat(LegScope.hasPendingCloseLegs(p, SYNTH_WEEKLY)).isTrue();
        assertThat(LegScope.hasPendingCloseLegs(p, LONG_MONTHLY)).isFalse();
        assertThat(LegScope.of(p, LONG_MONTHLY)).hasSize(1);
    }

    private static Position row(WeeklyLeg... legs) {
        Position p = new Position();
        p.setLegs(List.of(legs));
        return p;
    }

    private static WeeklyLeg leg(String book, String status, boolean traded) {
        WeeklyLeg l = new WeeklyLeg();
        l.setBook(book);
        l.setStatus(status);
        if (traded) {
            l.setBuyFillPrice(100.0);
        }
        return l;
    }
}
