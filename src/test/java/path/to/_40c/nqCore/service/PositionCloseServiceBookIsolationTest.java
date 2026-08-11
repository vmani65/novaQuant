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
import path.to._40c.nqCore.util.ExecMode;
import path.to._40c.nqCore.util.PositionUtil;
import path.to._40c.nqCore.util.PositionUtil.ExecResult;

/**
 * The core book-isolation invariant under one-position-per-signal: both books' legs live
 * on the SAME row, so a book's close must select only ITS OWN legs — the other book's
 * legs on the shared row are untouchable, and the row's status stays LIVE (roll-up) until
 * the last book closes. Also pins that a close finding nothing (the disabled-dormant or
 * never-opened case) is a clean no-op, and that the orphan sweep only ever asks the
 * finder for its own book.
 */
class PositionCloseServiceBookIsolationTest {

    private static final String WEEKLY_CE = "NIFTY2681224500CE";
    private static final String MONTHLY_CE = "NIFTY26AUG24500CE";

    private PositionUtil util;
    private PositionCloseService service;
    private Position shared;
    private WeeklyLeg weeklyLeg;
    private WeeklyLeg monthlyLeg;

    @BeforeEach
    void setUp() {
        PositionRepository repo = mock(PositionRepository.class);
        util = mock(PositionUtil.class);
        ComputeUtil compute = mock(ComputeUtil.class);
        service = new PositionCloseService(repo, util, compute,
                mock(PendingCloseReconciler.class), mock(PendingOpenReconciler.class));
        when(repo.save(any(Position.class))).thenAnswer(inv -> inv.getArgument(0));
        when(compute.getDtTimeNow()).thenReturn("07-08-2026 10:30:00.000");

        weeklyLeg = leg(WEEKLY_CE, SYNTH_WEEKLY);
        monthlyLeg = leg(MONTHLY_CE, LONG_MONTHLY);
        shared = new Position();
        shared.setStatus(LIVE);
        shared.setLegs(List.of(weeklyLeg, monthlyLeg));
        when(util.findLiveTradesWithLiveOrderBooks(SYNTH_WEEKLY)).thenReturn(shared);
        when(util.findLiveTradesWithLiveOrderBooks(LONG_MONTHLY)).thenReturn(shared);
        when(util.getQuote(any(String[].class))).thenReturn(Map.of(
                "NFO:" + WEEKLY_CE, new Quote(), "NFO:" + MONTHLY_CE, new Quote()));
        when(util.placeAggressiveOrder(any(), anyString(), anyString(), anyInt(), anyString(), any(ExecMode.class)))
                .thenReturn(new ExecResult("X-1", 650, 650, 100.0, true, Constants.ORDER_COMPLETE));
    }

    @Test
    @DisplayName("closeWeeklyTrade closes ONLY the weekly leg; the monthly leg on the shared row stays LIVE and so does the row")
    void weeklyCloseTouchesOnlyWeeklyLegs() {
        Position closed = service.closeWeeklyTrade("24600", signal(), true);

        assertThat(closed).isSameAs(shared);
        assertThat(weeklyLeg.getStatus()).isEqualTo(CLOSED);
        assertThat(monthlyLeg.getStatus()).as("monthly leg untouched").isEqualTo(LIVE);
        assertThat(closed.getStatus()).as("row stays LIVE while the monthly leg is open").isEqualTo(LIVE);
        assertThat(closed.getClosedAt()).as("closedAt deferred to the last book").isNull();
        verify(util).placeAggressiveOrder(any(), org.mockito.ArgumentMatchers.eq(WEEKLY_CE), anyString(), anyInt(), anyString(), any(ExecMode.class));
        verify(util, never()).placeAggressiveOrder(any(), org.mockito.ArgumentMatchers.eq(MONTHLY_CE), anyString(), anyInt(), anyString(), any(ExecMode.class));
        verify(util, never()).findLiveTradesWithLiveOrderBooks(LONG_MONTHLY);
    }

    @Test
    @DisplayName("closeMonthlyTrade closes ONLY the monthly leg; the weekly leg stays LIVE")
    void monthlyCloseTouchesOnlyMonthlyLegs() {
        Position closed = service.closeMonthlyTrade("24600", signal(), true);

        assertThat(closed).isSameAs(shared);
        assertThat(monthlyLeg.getStatus()).isEqualTo(CLOSED);
        assertThat(weeklyLeg.getStatus()).as("weekly leg untouched").isEqualTo(LIVE);
        assertThat(closed.getStatus()).isEqualTo(LIVE);
        verify(util, never()).placeAggressiveOrder(any(), org.mockito.ArgumentMatchers.eq(WEEKLY_CE), anyString(), anyInt(), anyString(), any(ExecMode.class));
        verify(util, never()).findLiveTradesWithLiveOrderBooks(SYNTH_WEEKLY);
    }

    @Test
    @DisplayName("the LAST book's close makes the shared row CLOSED with closedAt stamped")
    void lastBookCloseMakesRowTerminal() {
        service.closeWeeklyTrade("24600", signal(), true);
        Position closed = service.closeMonthlyTrade("24600", signal(), true);

        assertThat(weeklyLeg.getStatus()).isEqualTo(CLOSED);
        assertThat(monthlyLeg.getStatus()).isEqualTo(CLOSED);
        assertThat(closed.getStatus()).isEqualTo(CLOSED);
        assertThat(closed.getClosedAt()).isEqualTo("07-08-2026 10:30:00.000");
    }

    @Test
    @DisplayName("a book with no live legs closes as a clean no-op even while the other book is LIVE on the row")
    void closeWithNoLegsInOwnBookIsNoOp() {
        when(util.findLiveTradesWithLiveOrderBooks(LONG_MONTHLY)).thenReturn(null);

        Position closed = service.closeMonthlyTrade("24600", signal(), true);

        assertThat(closed).isNull();
        assertThat(weeklyLeg.getStatus()).isEqualTo(LIVE);
        verify(util, never()).placeAggressiveOrder(any(), anyString(), anyString(), anyInt(), anyString(), any(ExecMode.class));
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

    private static WeeklyLeg leg(String instrument, String book) {
        WeeklyLeg leg = new WeeklyLeg();
        leg.setInstrument(instrument);
        leg.setExchangeSymbol("NFO:" + instrument);
        leg.setBook(book);
        leg.setSide(BUY);
        leg.setQuantity(650);
        leg.setLots(10);
        leg.setStatus(LIVE);
        return leg;
    }

    private static Signal signal() {
        return new Signal("RIDETHETIDE", "longExit", "CE", "07-Aug-2026 10.30.00 AM", "24600");
    }
}
