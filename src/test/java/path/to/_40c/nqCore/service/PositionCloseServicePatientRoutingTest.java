package path.to._40c.nqCore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static path.to._40c.nqCore.util.Constants.BUY;
import static path.to._40c.nqCore.util.Constants.CLOSED;
import static path.to._40c.nqCore.util.Constants.LIVE;
import static path.to._40c.nqCore.util.Constants.LONG_MONTHLY;
import static path.to._40c.nqCore.util.Constants.PARTIAL;
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
import path.to._40c.nqCore.util.ExecMode;
import path.to._40c.nqCore.util.PositionUtil;
import path.to._40c.nqCore.util.PositionUtil.ExecResult;

/**
 * Execution-mode routing (PATIENT_EXECUTION_PLAN.md §3.1): exactly ONE close shape earns
 * patience — the signal-driven close of a LIVE LONG_MONTHLY position, whose thin deep-ITM book
 * is the whole reason patient mode exists. Every other close must stay AGGRESSIVE: the liquid
 * weekly book, orphan flattens (they run immediately before a new open, where speed beats
 * spread), and the PARTIAL fallback inside a signal close (semantically an orphan flatten).
 * A wrong PATIENT here would slow a pre-entry flatten by minutes; a wrong AGGRESSIVE would pay
 * the thin-book spread the whole design exists to avoid — both directions are pinned.
 */
class PositionCloseServicePatientRoutingTest {

    private static final String MONTHLY_INS = "NIFTY26AUG23850CE";
    private static final String WEEKLY_INS  = "NIFTY2681124650CE";

    private PositionUtil util;
    private PositionCloseService service;

    @BeforeEach
    void setUp() {
        PositionRepository repo = mock(PositionRepository.class);
        util = mock(PositionUtil.class);
        ComputeUtil compute = mock(ComputeUtil.class);
        service = new PositionCloseService(repo, util, compute,
                mock(PendingCloseReconciler.class), mock(PendingOpenReconciler.class));
        when(repo.save(any(Position.class))).thenAnswer(inv -> inv.getArgument(0));
        when(compute.getDtTimeNow()).thenReturn("08-08-2026 11:00:00.000");
        when(util.getQuote(any(String[].class))).thenReturn(Map.of(
                "NFO:" + MONTHLY_INS, new Quote(), "NFO:" + WEEKLY_INS, new Quote()));
        when(util.placeAggressiveOrder(any(), anyString(), anyString(), anyInt(), anyString(), any(ExecMode.class)))
                .thenReturn(new ExecResult("C-1", 130, 130, 464.0, true, Constants.ORDER_COMPLETE));
    }

    @Test
    @DisplayName("signal-driven close of a LIVE monthly position routes PATIENT")
    void liveMonthlySignalCloseRoutesPatient() {
        when(util.findLiveTradesWithLiveOrderBooks(LONG_MONTHLY))
                .thenReturn(position(LONG_MONTHLY, LIVE, MONTHLY_INS));

        Position closed = service.closeMonthlyTrade("24600", signal(), true);

        assertThat(closed.getStatus()).isEqualTo(CLOSED);
        verify(util).placeAggressiveOrder(any(), eq(MONTHLY_INS), eq(SELL), eq(130), eq("EXIT"),
                eq(ExecMode.PATIENT));
    }

    @Test
    @DisplayName("signal-driven close of the weekly book stays AGGRESSIVE")
    void liveWeeklySignalCloseStaysAggressive() {
        when(util.findLiveTradesWithLiveOrderBooks(SYNTH_WEEKLY))
                .thenReturn(position(SYNTH_WEEKLY, LIVE, WEEKLY_INS));

        service.closeWeeklyTrade("24600", signal(), true);

        verify(util).placeAggressiveOrder(any(), eq(WEEKLY_INS), eq(SELL), eq(130), eq("EXIT"),
                eq(ExecMode.AGGRESSIVE));
    }

    @Test
    @DisplayName("monthly orphan flatten before a new open stays AGGRESSIVE — speed beats spread pre-entry")
    void monthlyOrphanFlattenStaysAggressive() {
        when(util.findPartialTradesWithLiveOrderBooks(LONG_MONTHLY))
                .thenReturn(position(LONG_MONTHLY, PARTIAL, MONTHLY_INS));

        service.closeMonthlyOrphanIfAny("24600", signal());

        verify(util).placeAggressiveOrder(any(), eq(MONTHLY_INS), eq(SELL), eq(130), eq("EXIT"),
                eq(ExecMode.AGGRESSIVE));
    }

    @Test
    @DisplayName("signal close falling back to a PARTIAL position stays AGGRESSIVE — it's an orphan flatten in disguise")
    void partialFallbackInsideSignalCloseStaysAggressive() {
        when(util.findLiveTradesWithLiveOrderBooks(LONG_MONTHLY)).thenReturn(null);
        when(util.findPartialTradesWithLiveOrderBooks(LONG_MONTHLY))
                .thenReturn(position(LONG_MONTHLY, PARTIAL, MONTHLY_INS));

        service.closeMonthlyTrade("24600", signal(), true);

        verify(util).placeAggressiveOrder(any(), eq(MONTHLY_INS), eq(SELL), eq(130), eq("EXIT"),
                eq(ExecMode.AGGRESSIVE));
    }

    private static Position position(String book, String status, String instrument) {
        WeeklyLeg leg = new WeeklyLeg();
        leg.setInstrument(instrument);
        leg.setExchangeSymbol("NFO:" + instrument);
        leg.setSide(BUY);
        leg.setQuantity(130);
        leg.setLots(2);
        leg.setStatus(LIVE);
        Position p = new Position();
        p.setBook(book);
        p.setStatus(status);
        p.setLegs(List.of(leg));
        return p;
    }

    private static Signal signal() {
        return new Signal("RIDETHETIDE", "longExit", "CE", "08-Aug-2026 11.00.00 AM", "24600");
    }
}
