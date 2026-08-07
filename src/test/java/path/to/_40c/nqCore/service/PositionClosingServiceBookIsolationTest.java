package path.to._40c.nqCore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static path.to._40c.nqCore.util.Constants.BUY;
import static path.to._40c.nqCore.util.Constants.CLOSED;
import static path.to._40c.nqCore.util.Constants.LIVE;
import static path.to._40c.nqCore.util.Constants.LONG_MONTHLY;
import static path.to._40c.nqCore.util.Constants.SELL;
import static path.to._40c.nqCore.util.Constants.SYNTH_WEEKLY;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zerodhatech.kiteconnect.utils.Constants;
import com.zerodhatech.models.Quote;

import path.to._40c.nqCore.controller.SignalController.Signal;
import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.entity.WeeklyLeg;
import path.to._40c.nqCore.repo.PositionRepository;
import path.to._40c.nqCore.util.ComputeUtil;
import path.to._40c.nqCore.util.PositionUtil;
import path.to._40c.nqCore.util.PositionUtil.ExecResult;

/**
 * The core two-book audit BLOCKER, pinned at the closing-service level: before this
 * change the close path selected THE latest LIVE position bookless, so with two books
 * live a monthly close would grab and flatten the weekly synthetic (or vice versa).
 * Each public close method may only ever ask the position finder for ITS OWN book —
 * the other book's LIVE position must be structurally unreachable from here. Also pins
 * that a close finding nothing (the disabled-dormant or never-opened case) is a clean
 * no-op rather than an error.
 */
class PositionClosingServiceBookIsolationTest {

    private static final String WEEKLY_CE = "NIFTY2681224500CE";
    private static final String MONTHLY_CE = "NIFTY26AUG24500CE";

    private PositionUtil util;
    private PositionClosingService service;
    private Position weeklyLive;
    private Position monthlyLive;

    @BeforeEach
    void setUp() {
        PositionRepository repo = mock(PositionRepository.class);
        util = mock(PositionUtil.class);
        ComputeUtil compute = mock(ComputeUtil.class);
        service = new PositionClosingService(repo, util, compute,
                mock(PendingCloseReconciler.class), mock(PendingOpenReconciler.class));
        when(repo.save(any(Position.class))).thenAnswer(inv -> inv.getArgument(0));
        when(compute.getDtTimeNow()).thenReturn("07-08-2026 10:30:00.000");

        weeklyLive = position(SYNTH_WEEKLY, WEEKLY_CE);
        monthlyLive = position(LONG_MONTHLY, MONTHLY_CE);
        when(util.findLiveTradesWithLiveOrderBooks(SYNTH_WEEKLY)).thenReturn(weeklyLive);
        when(util.findLiveTradesWithLiveOrderBooks(LONG_MONTHLY)).thenReturn(monthlyLive);
        when(util.getQuote(any(String[].class))).thenReturn(Map.of(
                "NFO:" + WEEKLY_CE, new Quote(), "NFO:" + MONTHLY_CE, new Quote()));
        when(util.placeAggressiveOrder(any(), anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(new ExecResult("X-1", 650, 650, 100.0, true, Constants.ORDER_COMPLETE));
    }

    @Test
    @DisplayName("closeWeeklyTrade closes the weekly position and never even looks at the monthly book")
    void weeklyCloseTouchesOnlyWeeklyBook() {
        Position closed = service.closeWeeklyTrade("24600", signal(), true);

        assertThat(closed).isSameAs(weeklyLive);
        assertThat(closed.getStatus()).isEqualTo(CLOSED);
        assertThat(monthlyLive.getStatus()).as("monthly book untouched").isEqualTo(LIVE);
        verify(util).findLiveTradesWithLiveOrderBooks(SYNTH_WEEKLY);
        verify(util, never()).findLiveTradesWithLiveOrderBooks(LONG_MONTHLY);
    }

    @Test
    @DisplayName("closeMonthlyTrade closes the monthly position and never even looks at the weekly book")
    void monthlyCloseTouchesOnlyMonthlyBook() {
        Position closed = service.closeMonthlyTrade("24600", signal(), true);

        assertThat(closed).isSameAs(monthlyLive);
        assertThat(closed.getStatus()).isEqualTo(CLOSED);
        assertThat(weeklyLive.getStatus()).as("weekly book untouched").isEqualTo(LIVE);
        verify(util).findLiveTradesWithLiveOrderBooks(LONG_MONTHLY);
        verify(util, never()).findLiveTradesWithLiveOrderBooks(SYNTH_WEEKLY);
    }

    @Test
    @DisplayName("a book with nothing open closes as a clean no-op even while the OTHER book has a LIVE position")
    void closeWithNoPositionInOwnBookIsNoOp() {
        when(util.findLiveTradesWithLiveOrderBooks(LONG_MONTHLY)).thenReturn(null);
        when(util.findPartialTradesWithLiveOrderBooks(LONG_MONTHLY)).thenReturn(null);

        Position closed = service.closeMonthlyTrade("24600", signal(), true);

        assertThat(closed).isNull();
        assertThat(weeklyLive.getStatus()).isEqualTo(LIVE);
        verify(util, never()).placeAggressiveOrder(any(), anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("orphan sweep is book-scoped too: the weekly sweep can never flatten a monthly PARTIAL")
    void orphanSweepIsBookScoped() {
        when(util.findPartialTradesWithLiveOrderBooks(SYNTH_WEEKLY)).thenReturn(null);

        Position closed = service.closeWeeklyOrphanIfAny("24600", signal());

        assertThat(closed).isNull();
        verify(util).findPartialTradesWithLiveOrderBooks(SYNTH_WEEKLY);
        verify(util, never()).findPartialTradesWithLiveOrderBooks(LONG_MONTHLY);
    }

    private static Position position(String book, String instrument) {
        WeeklyLeg leg = new WeeklyLeg();
        leg.setInstrument(instrument);
        leg.setExchangeSymbol("NFO:" + instrument);
        leg.setSide(BUY);
        leg.setQuantity(650);
        leg.setLots(10);
        leg.setStatus(LIVE);
        Position p = new Position();
        p.setBook(book);
        p.setStatus(LIVE);
        p.setLegs(List.of(leg));
        return p;
    }

    private static Signal signal() {
        return new Signal("RIDETHETIDE", "longExit", "CE", "07-Aug-2026 10.30.00 AM", "24600");
    }
}
