package path.to._40c.nqCore.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static path.to._40c.nqCore.util.Constants.LONG_MONTHLY;
import static path.to._40c.nqCore.util.Constants.SYNTH_WEEKLY;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import path.to._40c.nqCore.repo.PositionRepository;
import path.to._40c.nqCore.util.ComputeUtil;
import path.to._40c.nqCore.util.PositionUtil;

/**
 * The two automation fences from the two-book audit, pinned at their service entry
 * points. Both automations previously grabbed THE latest LIVE position bookless — on a
 * day with a LIVE monthly position, the 14:47 weekly rollover or a +500-pt recenter
 * would have closed the monthly leg and reopened it as a WEEKLY contract. After the
 * fence: both ask the finder for SYNTH_WEEKLY only, and the documented monthly
 * counterparts (rollOverMonthly / realizeProfitsMonthly) place no orders at all.
 */
class BookFenceTest {

    private PositionUtil util;
    private PositionRolloverService rolloverService;
    private ProfitRecenterService recenterService;

    @BeforeEach
    void setUp() {
        util = mock(PositionUtil.class);
        PositionRepository repo = mock(PositionRepository.class);
        ComputeUtil compute = mock(ComputeUtil.class);
        rolloverService = new PositionRolloverService(repo, util, compute, mock(PostTradeService.class),
                realCloseService());
        recenterService = new ProfitRecenterService(repo, util, compute,
                mock(WeeklySymbolService.class), mock(PostTradeService.class));
    }

    /** Real close service (mocked deps) so the roll's per-leg close bookkeeping runs for real. */
    private static PositionCloseService realCloseService() {
        return new PositionCloseService(mock(PositionRepository.class), mock(PositionUtil.class),
                mock(ComputeUtil.class), mock(PendingCloseReconciler.class), mock(PendingOpenReconciler.class));
    }

    @Test
    @DisplayName("weekly rollover asks the finder for SYNTH_WEEKLY only — a LIVE monthly position is invisible to it")
    void rolloverIsFencedToWeeklyBook() {
        when(util.findLiveTradesWithLiveOrderBooks(SYNTH_WEEKLY)).thenReturn(null);

        rolloverService.rollOverWeekly("24500");

        verify(util).findLiveTradesWithLiveOrderBooks(SYNTH_WEEKLY);
        verify(util, never()).findLiveTradesWithLiveOrderBooks(LONG_MONTHLY);
        verify(util, never()).placeAggressiveOrder(any(), anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("recenter asks the finder for SYNTH_WEEKLY only")
    void recenterIsFencedToWeeklyBook() {
        when(util.findLiveTradesWithLiveOrderBooks(SYNTH_WEEKLY)).thenReturn(null);

        recenterService.realizeProfitsWeekly("25000");

        verify(util).findLiveTradesWithLiveOrderBooks(SYNTH_WEEKLY);
        verify(util, never()).findLiveTradesWithLiveOrderBooks(LONG_MONTHLY);
    }

    @Test
    @DisplayName("monthly rollover asks the finder for LONG_MONTHLY only — a LIVE weekly position is invisible to it")
    void monthlyRolloverIsFencedToMonthlyBook() {
        when(util.findLiveTradesWithLiveOrderBooks(LONG_MONTHLY)).thenReturn(null);

        rolloverService.rollOverMonthly("24500");

        verify(util).findLiveTradesWithLiveOrderBooks(LONG_MONTHLY);
        verify(util, never()).findLiveTradesWithLiveOrderBooks(SYNTH_WEEKLY);
        verify(util, never()).placeAggressiveOrder(any(), anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("realizeProfitsMonthly is a documented no-op by policy (convexity kept) — no lookups, no orders")
    void monthlyRecenterPlacesNothing() {
        recenterService.realizeProfitsMonthly();

        verifyNoInteractions(util);
    }
}
