package path.to._40c.nqCore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static path.to._40c.nqCore.util.Constants.BUY;
import static path.to._40c.nqCore.util.Constants.CLOSED;
import static path.to._40c.nqCore.util.Constants.FAILED;
import static path.to._40c.nqCore.util.Constants.LIVE;
import static path.to._40c.nqCore.util.Constants.LONG;
import static path.to._40c.nqCore.util.Constants.PARTIAL;
import static path.to._40c.nqCore.util.Constants.PENDING_CLOSE;
import static path.to._40c.nqCore.util.Constants.PENDING_OPEN;
import static path.to._40c.nqCore.util.Constants.SELL;
import static path.to._40c.nqCore.util.Constants.SYNTH_WEEKLY;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zerodhatech.kiteconnect.utils.Constants;
import com.zerodhatech.models.Quote;

import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.entity.WeeklyLeg;
import path.to._40c.nqCore.pojo.LegOrder;
import path.to._40c.nqCore.repo.PositionRepository;
import path.to._40c.nqCore.util.ComputeUtil;
import path.to._40c.nqCore.util.PositionUtil;
import path.to._40c.nqCore.util.PositionUtil.ExecResult;

/**
 * Rollover failure-path bookkeeping (2026-08-08 audit gap 1): a roll that fails mid-flight
 * must leave the DATABASE agreeing with the broker. Every abort path saves the row first —
 * closeOrderIds and CLOSED/PENDING_CLOSE/FAILED leg states survive, so a retried trigger or
 * the next signal can never re-sell a leg the broker already closed. The close step uses the
 * same applyCloseResult bookkeeping as a signal close (an unconfirmed close rests as
 * PENDING_CLOSE for the reconciler); the open step uses the same recordOpenResult semantics
 * as a signal open (working order → PENDING_OPEN, partial fill → trim-to-filled + row
 * PARTIAL, no fill → FAILED).
 */
class PositionRolloverServiceResilienceTest {

    private static final String OLD_CE = "NIFTY2681224500CE";
    private static final String OLD_PE = "NIFTY2681224500PE";
    private static final String NEW_CE = "NIFTY2681924800CE";
    private static final String NEW_PE = "NIFTY2681924800PE";
    private static final int QTY = 650;

    private PositionRepository repo;
    private PositionUtil util;
    private ComputeUtil compute;
    private PostTradeService postTrade;
    private PositionRolloverService service;
    private Position weekly;
    private WeeklyLeg heldCe;
    private WeeklyLeg heldPe;

    private final ExecResult fullFill = new ExecResult("FULL1", QTY, QTY, 150.0, true, Constants.ORDER_COMPLETE);

    @BeforeEach
    void setUp() {
        repo = mock(PositionRepository.class);
        util = mock(PositionUtil.class);
        compute = mock(ComputeUtil.class);
        postTrade = mock(PostTradeService.class);
        PositionCloseService realCloser = new PositionCloseService(mock(PositionRepository.class),
                mock(PositionUtil.class), mock(ComputeUtil.class),
                mock(PendingCloseReconciler.class), mock(PendingOpenReconciler.class));
        service = new PositionRolloverService(repo, util, compute, postTrade, realCloser);
        when(repo.save(any(Position.class))).thenAnswer(inv -> inv.getArgument(0));

        heldCe = leg(OLD_CE, BUY);
        heldPe = leg(OLD_PE, SELL);
        weekly = new Position();
        weekly.setDirection(LONG);
        weekly.setStatus(LIVE);
        weekly.setEntrySpot(24500.0);
        weekly.setBaselineSpot(24500.0);
        weekly.setBankedPoints(0.0);
        weekly.setLegs(List.of(heldCe, heldPe));
        when(util.findLiveTradesWithLiveOrderBooks(SYNTH_WEEKLY)).thenReturn(weekly);
        when(util.getQuote(any(String[].class))).thenReturn(Map.of(
                "NFO:" + OLD_CE, new Quote(), "NFO:" + OLD_PE, new Quote(),
                "NFO:" + NEW_CE, new Quote(), "NFO:" + NEW_PE, new Quote()));
        when(compute.weeklyContractPrefix()).thenReturn("NIFTY26819");
        when(compute.buildWeeklyInstrument(eq("24800"), any(Position.class)))
                .thenReturn(List.of(order(NEW_CE, BUY), order(NEW_PE, SELL)));
    }

    @Test
    @DisplayName("close order still working: leg PENDING_CLOSE with its orderId, row SAVED, banking untouched, no re-open")
    void unconfirmedCloseSavesPendingCloseAndAborts() {
        when(util.placeAggressiveOrder(any(), anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(new ExecResult("C-WORK", 300, QTY, 150.0, false, "OPEN"));

        service.rollOverWeekly("24800");

        assertThat(heldCe.getStatus()).isEqualTo(PENDING_CLOSE);
        assertThat(heldPe.getStatus()).isEqualTo(PENDING_CLOSE);
        assertThat(heldCe.getCloseOrderId()).isEqualTo("C-WORK");
        assertThat(weekly.getStatus()).isEqualTo(PENDING_CLOSE);
        assertThat(weekly.getBaselineSpot()).as("banking must not re-base on an aborted roll").isEqualTo(24500.0);
        assertThat(weekly.getBankedPoints()).isEqualTo(0.0);
        assertThat(weekly.getLegs()).as("no new legs materialized").hasSize(2);
        verify(repo).save(weekly);
        verify(util, times(2)).placeAggressiveOrder(any(), anyString(), anyString(), anyInt(), anyString());
        verify(postTrade).afterClose(weekly);
        verify(postTrade, never()).afterOpen(any());
    }

    @Test
    @DisplayName("close placement throws: leg FAILED, row SAVED, no re-open")
    void closeExceptionSavesFailedAndAborts() {
        when(util.placeAggressiveOrder(any(), anyString(), anyString(), anyInt(), anyString()))
                .thenThrow(new RuntimeException("kite down"));

        service.rollOverWeekly("24800");

        assertThat(heldCe.getStatus()).isEqualTo(FAILED);
        assertThat(heldPe.getStatus()).isEqualTo(FAILED);
        assertThat(weekly.getLegs()).hasSize(2);
        verify(repo).save(weekly);
        verify(postTrade).afterClose(weekly);
        verify(postTrade, never()).afterOpen(any());
    }

    @Test
    @DisplayName("open-side quote failure after the closes executed: CLOSED legs and banked points SAVED")
    void openQuoteFailureSavesExecutedCloses() {
        when(util.getQuote(any(String[].class)))
                .thenReturn(Map.of("NFO:" + OLD_CE, new Quote(), "NFO:" + OLD_PE, new Quote()))
                .thenReturn(Map.of());
        when(util.placeAggressiveOrder(any(), anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(fullFill);

        service.rollOverWeekly("24800");

        assertThat(heldCe.getStatus()).isEqualTo(CLOSED);
        assertThat(heldCe.getCloseOrderId()).isEqualTo("FULL1");
        assertThat(weekly.getBankedPoints()).as("segment banked before the open abort").isEqualTo(300.0);
        assertThat(weekly.getBaselineSpot()).isEqualTo(24800.0);
        assertThat(weekly.getLegs()).as("no new legs materialized").hasSize(2);
        verify(repo).save(weekly);
        verify(postTrade).afterClose(weekly);
        verify(postTrade, never()).afterOpen(any());
    }

    @Test
    @DisplayName("roll-open partial fill: new leg trimmed to the FILLED quantity, row promoted PARTIAL")
    void openPartialFillTrimsToFilled() {
        when(util.placeAggressiveOrder(any(), anyString(), anyString(), anyInt(), anyString()))
                .thenAnswer(inv -> ((String) inv.getArgument(1)).startsWith("NIFTY26812")
                        ? fullFill
                        : new ExecResult("O-PART", 325, QTY, 150.0, false, PARTIAL));

        service.rollOverWeekly("24800");

        List<WeeklyLeg> newLegs = weekly.getLegs().stream()
                .filter(l -> l.getInstrument().startsWith("NIFTY26819")).toList();
        assertThat(newLegs).hasSize(2);
        assertThat(newLegs).allSatisfy(l -> {
            assertThat(l.getStatus()).isEqualTo(LIVE);
            assertThat(l.getQuantity()).as("trimmed to filled, not template").isEqualTo(325);
            assertThat(l.getLots()).isEqualTo(5);
        });
        assertThat(weekly.getStatus()).as("orphan sweep must see the half-failed roll-open").isEqualTo(PARTIAL);
        verify(postTrade).afterOpen(weekly);
    }

    @Test
    @DisplayName("roll-open order still working: leg PENDING_OPEN at template qty for the reconciler")
    void openStillWorkingGoesPendingOpen() {
        when(util.placeAggressiveOrder(any(), anyString(), anyString(), anyInt(), anyString()))
                .thenAnswer(inv -> ((String) inv.getArgument(1)).startsWith("NIFTY26812")
                        ? fullFill
                        : new ExecResult("O-WORK", 0, QTY, 0.0, false, "OPEN"));

        service.rollOverWeekly("24800");

        List<WeeklyLeg> newLegs = weekly.getLegs().stream()
                .filter(l -> l.getInstrument().startsWith("NIFTY26819")).toList();
        assertThat(newLegs).allSatisfy(l -> {
            assertThat(l.getStatus()).isEqualTo(PENDING_OPEN);
            assertThat(l.getQuantity()).isEqualTo(QTY);
        });
        assertThat(weekly.getStatus()).isEqualTo(PENDING_OPEN);
    }

    private static WeeklyLeg leg(String instrument, String side) {
        WeeklyLeg l = new WeeklyLeg();
        l.setInstrument(instrument);
        l.setExchangeSymbol("NFO:" + instrument);
        l.setBook(SYNTH_WEEKLY);
        l.setSide(side);
        l.setQuantity(QTY);
        l.setLots(10);
        l.setStatus(LIVE);
        l.setOpenOrderId("OPEN-" + instrument);
        return l;
    }

    private static LegOrder order(String instrument, String side) {
        LegOrder w = new LegOrder();
        w.setInstrument(instrument);
        w.setExchangeSymbol("NFO:" + instrument);
        w.setBook(SYNTH_WEEKLY);
        w.setSide(side);
        w.setLots(10);
        w.setMoneyness("ATM");
        w.setOptionType(instrument.endsWith("CE") ? "CE" : "PE");
        return w;
    }
}
