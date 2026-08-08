package path.to._40c.nqCore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static path.to._40c.nqCore.util.Constants.BUY;
import static path.to._40c.nqCore.util.Constants.LIVE;
import static path.to._40c.nqCore.util.Constants.LONG_MONTHLY;
import static path.to._40c.nqCore.util.Constants.SELL;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import com.zerodhatech.kiteconnect.utils.Constants;
import com.zerodhatech.models.Quote;

import path.to._40c.nqCore.controller.SignalController.Signal;
import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.entity.WeeklyLeg;
import path.to._40c.nqCore.pojo.LegOrder;
import path.to._40c.nqCore.service.MonthlyFlipService.FlipOutcome;
import path.to._40c.nqCore.service.PositionOpenService.OpenPrep;
import path.to._40c.nqCore.util.ExecMode;
import path.to._40c.nqCore.util.PositionUtil;
import path.to._40c.nqCore.util.PositionUtil.ExecResult;

/**
 * Interleaved monthly flip orchestration (PATIENT_EXECUTION_PLAN.md §4). The load-bearing rules:
 *
 *  1. INTERLEAVE ORDER — close slice k's fill is CONFIRMED before open slice k is placed
 *     (the 2026-06-19 lesson applied to flips: never size or sequence off unconfirmed fills).
 *  2. INVARIANT — opened never exceeds confirmed-closed, and a stalled close stops all further
 *     opens symmetrically; the stalled slice's resting order keeps the aggregate non-terminal
 *     so the PENDING_CLOSE machinery owns the tail.
 *  3. AGGREGATION — one ExecResult per side with comma-joined slice ids, because LEG_FILL
 *     telemetry and readCloseOrderState split on exactly that string.
 *  4. SAFETY ABORT — the target leg is prepared and quoted BEFORE any close order, so a dead
 *     quote feed aborts with the held position fully intact.
 */
class MonthlyFlipServiceTest {

    private static final String CE = "NIFTY26AUG23850CE";
    private static final String PE = "NIFTY26SEP24600PE";
    private static final String EX_CE = "NFO:" + CE;
    private static final String EX_PE = "NFO:" + PE;

    private PositionUtil util;
    private PositionOpenService opening;
    private PositionCloseService closing;
    private MonthlyFlipService service;
    private Position held;
    private WeeklyLeg heldLeg;
    private LegOrder target;
    private OpenPrep prep;

    @BeforeEach
    void setUp() {
        util = mock(PositionUtil.class);
        opening = mock(PositionOpenService.class);
        closing = mock(PositionCloseService.class);
        service = new MonthlyFlipService(util, opening, closing,
                mock(PendingCloseReconciler.class), mock(PendingOpenReconciler.class));
        ReflectionTestUtils.setField(service, "interleaveEnabled", true);
        ReflectionTestUtils.setField(service, "sliceLots", 1);
        ReflectionTestUtils.setField(service, "flipWindowMs", 600_000L);
        when(util.patientModeAvailable()).thenReturn(true);

        heldLeg = new WeeklyLeg();
        heldLeg.setInstrument(CE);
        heldLeg.setExchangeSymbol(EX_CE);
        heldLeg.setSide(BUY);
        heldLeg.setQuantity(130);
        heldLeg.setLots(2);
        heldLeg.setStatus(LIVE);
        held = new Position();
        held.setBook(LONG_MONTHLY);
        held.setStatus(LIVE);
        held.setLegs(List.of(heldLeg));
        when(util.findLiveTradesWithLiveOrderBooks(LONG_MONTHLY)).thenReturn(held);
        when(util.getQuote(any(String[].class))).thenReturn(Map.of(EX_CE, quote(462.0, 464.0)));

        target = new LegOrder();
        target.setInstrument(PE);
        target.setExchangeSymbol(EX_PE);
        target.setSide(BUY);
        target.setLots(2);
        target.setMoneyness("ATM");
        target.setOptionType("PE");
        prep = new OpenPrep(List.of(target), Map.of(EX_PE, quote(178.0, 180.0)));
        when(opening.prepareMonthlyOpen(anyString(), anyString(), any(Position.class))).thenReturn(prep);

        when(closing.finalizeClose(any(Position.class), any(Signal.class), eq(false)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(opening.savePreparedOpen(any(Position.class), any(OpenPrep.class), anyMap()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("clean flip: close-confirm-open interleave per slice, aggregates carry comma-joined ids and weighted averages")
    void cleanTwoSliceFlipInterleaves() {
        stubClose(0, new ExecResult("C0", 65, 65, 464.0, true, Constants.ORDER_COMPLETE));
        stubClose(1, new ExecResult("C1", 65, 65, 463.0, true, Constants.ORDER_COMPLETE));
        stubOpen(0, new ExecResult("O0", 65, 65, 180.0, true, Constants.ORDER_COMPLETE));
        stubOpen(1, new ExecResult("O1", 65, 65, 181.0, true, Constants.ORDER_COMPLETE));

        FlipOutcome outcome = service.flip("24600", "PE", signal());

        InOrder io = inOrder(util);
        io.verify(util).placeAggressiveOrder(any(), eq(CE), eq(SELL), eq(65), eq("EXIT[0]"), eq(ExecMode.PATIENT), anyLong());
        io.verify(util).placeAggressiveOrder(any(), eq(PE), eq(BUY), eq(65), eq("ENTRY[0]"), eq(ExecMode.AGGRESSIVE));
        io.verify(util).placeAggressiveOrder(any(), eq(CE), eq(SELL), eq(65), eq("EXIT[1]"), eq(ExecMode.PATIENT), anyLong());
        io.verify(util).placeAggressiveOrder(any(), eq(PE), eq(BUY), eq(65), eq("ENTRY[1]"), eq(ExecMode.AGGRESSIVE));

        ExecResult closeAgg = capturedCloseAgg();
        assertThat(closeAgg.aggregateOrderIds()).isEqualTo("C0, C1");
        assertThat(closeAgg.totalFilled()).isEqualTo(130);
        assertThat(closeAgg.fullyFilled()).isTrue();
        assertThat(closeAgg.terminalStatus()).isEqualTo(Constants.ORDER_COMPLETE);
        assertThat(closeAgg.weightedAvgFillPrice()).isEqualTo(463.5);

        ExecResult openAgg = capturedOpenAgg();
        assertThat(openAgg.aggregateOrderIds()).isEqualTo("O0, O1");
        assertThat(openAgg.totalFilled()).isEqualTo(130);
        assertThat(openAgg.fullyFilled()).isTrue();
        assertThat(openAgg.weightedAvgFillPrice()).isEqualTo(180.5);

        assertThat(outcome.closed()).isSameAs(held);
        assertThat(outcome.opened()).isNotNull();
        assertThat(held.getExitSpot()).isEqualTo(24600.0);
        assertThat(heldLeg.getSellIntendedPrice()).as("SELL close stamps sell intended price").isEqualTo(463.0);
    }

    @Test
    @DisplayName("stalled second close slice: no further opens, aggregate stays non-terminal for PENDING_CLOSE")
    void stalledCloseSliceStopsOpening() {
        stubClose(0, new ExecResult("C0", 65, 65, 464.0, true, Constants.ORDER_COMPLETE));
        stubClose(1, new ExecResult("C1", 0, 65, 0.0, false, "OPEN"));
        stubOpen(0, new ExecResult("O0", 65, 65, 180.0, true, Constants.ORDER_COMPLETE));

        service.flip("24600", "PE", signal());

        verify(util, times(1)).placeAggressiveOrder(any(), eq(PE), anyString(), anyInt(), anyString(), any(ExecMode.class));

        ExecResult closeAgg = capturedCloseAgg();
        assertThat(closeAgg.aggregateOrderIds()).isEqualTo("C0, C1");
        assertThat(closeAgg.totalFilled()).isEqualTo(65);
        assertThat(closeAgg.fullyFilled()).isFalse();
        assertThat(closeAgg.terminalStatus()).isEqualTo("OPEN");
        assertThat(PositionUtil.closeOrderMayBeLive(closeAgg))
                .as("resting slice keeps the whole close reconciler-owned").isTrue();

        ExecResult openAgg = capturedOpenAgg();
        assertThat(openAgg.totalRequested()).as("open intent = what was actually closed").isEqualTo(65);
        assertThat(openAgg.fullyFilled()).as("65 opened of 65 intended — complete, not orphan-PARTIAL").isTrue();
    }

    @Test
    @DisplayName("first close slice stalls: nothing opened, no new position row at all")
    void firstSliceStallOpensNothing() {
        stubClose(0, new ExecResult("C0", 0, 65, 0.0, false, "OPEN"));

        FlipOutcome outcome = service.flip("24600", "PE", signal());

        verify(util, never()).placeAggressiveOrder(any(), eq(PE), anyString(), anyInt(), anyString(), any(ExecMode.class));
        verify(opening, never()).savePreparedOpen(any(), any(), anyMap());
        assertThat(outcome.opened()).isNull();
        ExecResult closeAgg = capturedCloseAgg();
        assertThat(closeAgg.totalFilled()).isZero();
        assertThat(PositionUtil.closeOrderMayBeLive(closeAgg)).isTrue();
    }

    @Test
    @DisplayName("open slice failure: opens stop, remaining close slices still run (legacy-equivalent exit)")
    void openFailureFinishesClosing() {
        stubClose(0, new ExecResult("C0", 65, 65, 464.0, true, Constants.ORDER_COMPLETE));
        stubClose(1, new ExecResult("C1", 65, 65, 463.0, true, Constants.ORDER_COMPLETE));
        stubOpen(0, new ExecResult("", 0, 65, 0.0, false, "PLACE_FAILED"));

        service.flip("24600", "PE", signal());

        verify(util).placeAggressiveOrder(any(), eq(CE), eq(SELL), eq(65), eq("EXIT[1]"), eq(ExecMode.PATIENT), anyLong());
        verify(util, times(1)).placeAggressiveOrder(any(), eq(PE), anyString(), anyInt(), anyString(), any(ExecMode.class));

        ExecResult closeAgg = capturedCloseAgg();
        assertThat(closeAgg.totalFilled()).isEqualTo(130);
        assertThat(closeAgg.fullyFilled()).isTrue();
        ExecResult openAgg = capturedOpenAgg();
        assertThat(openAgg.totalFilled()).isZero();
        assertThat(openAgg.terminalStatus()).isEqualTo("PLACE_FAILED");
    }

    @Test
    @DisplayName("per-slice patient budget splits the flip window across remaining slices")
    void perSliceBudgetSplitsWindow() {
        stubClose(0, new ExecResult("C0", 65, 65, 464.0, true, Constants.ORDER_COMPLETE));
        stubClose(1, new ExecResult("C1", 65, 65, 463.0, true, Constants.ORDER_COMPLETE));
        stubOpen(0, new ExecResult("O0", 65, 65, 180.0, true, Constants.ORDER_COMPLETE));
        stubOpen(1, new ExecResult("O1", 65, 65, 181.0, true, Constants.ORDER_COMPLETE));

        service.flip("24600", "PE", signal());

        ArgumentCaptor<Long> budget = ArgumentCaptor.forClass(Long.class);
        verify(util, times(2)).placeAggressiveOrder(any(), eq(CE), eq(SELL), eq(65), anyString(),
                eq(ExecMode.PATIENT), budget.capture());
        assertThat(budget.getAllValues().get(0)).as("slice 1 of 2 gets half the 600s window")
                .isBetween(290_000L, 300_000L);
        assertThat(budget.getAllValues().get(1)).as("slice 2 gets the remaining window")
                .isBetween(550_000L, 600_000L);
    }

    @Test
    @DisplayName("no LIVE position: plain orphan-sweep + open, no slicing")
    void noHeldPositionRunsPlainOpen() {
        when(util.findLiveTradesWithLiveOrderBooks(LONG_MONTHLY)).thenReturn(null);
        Position orphan = new Position();
        Position opened = new Position();
        when(closing.closeMonthlyOrphanIfAny(anyString(), any(Signal.class))).thenReturn(orphan);
        when(opening.openMonthlyTrade(anyString(), anyString(), any(Position.class))).thenReturn(opened);

        FlipOutcome outcome = service.flip("24600", "PE", signal());

        assertThat(outcome.closed()).isSameAs(orphan);
        assertThat(outcome.opened()).isSameAs(opened);
        verify(util, never()).placeAggressiveOrder(any(), anyString(), anyString(), anyInt(), anyString(),
                any(ExecMode.class), anyLong());
    }

    @Test
    @DisplayName("dead held-leg quote: flip aborts BEFORE any order, held position untouched")
    void deadQuoteAbortsBeforeAnyOrder() {
        when(util.getQuote(any(String[].class))).thenReturn(Map.of());

        FlipOutcome outcome = service.flip("24600", "PE", signal());

        assertThat(outcome).isNotNull();
        assertThat(outcome.closed()).isNull();
        assertThat(outcome.opened()).isNull();
        assertThat(held.getStatus()).isEqualTo(LIVE);
        assertThat(held.getExitSpot()).isNull();
        verify(util, never()).placeAggressiveOrder(any(), anyString(), anyString(), anyInt(), anyString(),
                any(ExecMode.class), anyLong());
        verify(closing, never()).applyCloseResult(any(), any(), any(), anyString(), any());
        verify(closing, never()).finalizeClose(any(), any(), eq(false));
    }

    @Test
    @DisplayName("unexpected multi-leg monthly position: declines (null) so the caller uses the legacy flip")
    void multiLegFallsBackToLegacy() {
        WeeklyLeg second = new WeeklyLeg();
        second.setInstrument(PE);
        second.setExchangeSymbol(EX_PE);
        second.setSide(SELL);
        second.setQuantity(65);
        second.setStatus(LIVE);
        held.setLegs(List.of(second));

        FlipOutcome outcome = service.flip("24600", "PE", signal());

        assertThat(outcome).isNull();
        verify(util, never()).placeAggressiveOrder(any(), anyString(), anyString(), anyInt(), anyString(),
                any(ExecMode.class), anyLong());
    }

    @Test
    @DisplayName("interleaveAvailable requires its own flag AND live patient mode")
    void interleaveAvailabilityGates() {
        assertThat(service.interleaveAvailable()).isTrue();
        when(util.patientModeAvailable()).thenReturn(false);
        assertThat(service.interleaveAvailable()).isFalse();
        when(util.patientModeAvailable()).thenReturn(true);
        ReflectionTestUtils.setField(service, "interleaveEnabled", false);
        assertThat(service.interleaveAvailable()).isFalse();
    }

    // ─── helpers ────────────────────────────────────────────────────────────────────────────

    private void stubClose(int slice, ExecResult er) {
        when(util.placeAggressiveOrder(any(), eq(CE), eq(SELL), eq(65), eq("EXIT[" + slice + "]"),
                eq(ExecMode.PATIENT), anyLong())).thenReturn(er);
    }

    private void stubOpen(int slice, ExecResult er) {
        when(util.placeAggressiveOrder(any(), eq(PE), eq(BUY), eq(65), eq("ENTRY[" + slice + "]"),
                eq(ExecMode.AGGRESSIVE))).thenReturn(er);
    }

    private ExecResult capturedCloseAgg() {
        ArgumentCaptor<ExecResult> cap = ArgumentCaptor.forClass(ExecResult.class);
        verify(closing).applyCloseResult(eq(held), eq(heldLeg), any(Quote.class), eq(SELL), cap.capture());
        return cap.getValue();
    }

    @SuppressWarnings("unchecked")
    private ExecResult capturedOpenAgg() {
        ArgumentCaptor<Map<String, ExecResult>> cap = ArgumentCaptor.forClass(Map.class);
        verify(opening).savePreparedOpen(any(Position.class), eq(prep), cap.capture());
        return cap.getValue().get(EX_PE);
    }

    private static Signal signal() {
        return new Signal("RIDETHETIDE", "flip", "PE", "08-Aug-2026 11.00.00 AM", "24600");
    }

    private static Quote quote(double bid, double ask) {
        Quote q = new Quote();
        q.lastPrice = (bid + ask) / 2.0;
        com.zerodhatech.models.Depth b = new com.zerodhatech.models.Depth();
        b.setPrice(bid);
        b.setQuantity(1000);
        com.zerodhatech.models.Depth s = new com.zerodhatech.models.Depth();
        s.setPrice(ask);
        s.setQuantity(1000);
        com.zerodhatech.models.MarketDepth d = new com.zerodhatech.models.MarketDepth();
        d.buy = List.of(b);
        d.sell = List.of(s);
        q.depth = d;
        return q;
    }
}
