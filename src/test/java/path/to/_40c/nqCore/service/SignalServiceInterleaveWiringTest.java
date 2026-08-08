package path.to._40c.nqCore.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static path.to._40c.nqCore.util.Constants.LONG_MONTHLY;
import static path.to._40c.nqCore.util.Constants.SYNTH_WEEKLY;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import path.to._40c.nqCore.controller.SignalController.Signal;
import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.repo.PositionRepository;
import path.to._40c.nqCore.service.MonthlyFlipService.FlipOutcome;

/**
 * flipMonthly's routing between the interleaved flip (PATIENT_EXECUTION_PLAN.md §4) and the
 * legacy close→open: the interleave replaces the legacy body only when available, a declined
 * interleave (null outcome) falls back to legacy, and both books' fan-out stays intact.
 */
class SignalServiceInterleaveWiringTest {

    private PositionOpenService opening;
    private PositionCloseService closing;
    private PostTradeService postTrade;
    private BookConfigService bookConfig;
    private MonthlyFlipService flipService;
    private SignalService service;

    @BeforeEach
    void setUp() {
        opening = mock(PositionOpenService.class);
        closing = mock(PositionCloseService.class);
        postTrade = mock(PostTradeService.class);
        bookConfig = mock(BookConfigService.class);
        flipService = mock(MonthlyFlipService.class);
        service = new SignalService(opening, closing, mock(PositionRolloverService.class), postTrade,
                mock(PositionRepository.class), mock(WeeklySymbolService.class), bookConfig,
                mock(MonthlySymbolService.class), flipService);
        when(bookConfig.isEnabled(SYNTH_WEEKLY)).thenReturn(false);
        when(bookConfig.isEnabled(LONG_MONTHLY)).thenReturn(true);
    }

    @Test
    @DisplayName("interleave available: MonthlyFlipService replaces the legacy monthly flip body")
    void interleaveReplacesLegacyFlip() {
        Position closed = new Position();
        Position opened = new Position();
        when(flipService.interleaveAvailable()).thenReturn(true);
        when(flipService.flip(anyString(), anyString(), any(Signal.class)))
                .thenReturn(new FlipOutcome(closed, opened));

        service.handleFlip("24600", "PE", signal());

        verify(flipService).flip(eq("24600"), eq("PE"), any(Signal.class));
        verify(closing, never()).closeMonthlyTrade(anyString(), any(Signal.class), eq(false));
        verify(opening, never()).openMonthlyTrade(anyString(), anyString(), any(Position.class));
        verify(postTrade).afterOpen(opened);
        verify(postTrade).afterClose(closed);
    }

    @Test
    @DisplayName("interleave declines (null outcome): legacy monthly flip runs")
    void declinedInterleaveFallsBackToLegacy() {
        when(flipService.interleaveAvailable()).thenReturn(true);
        when(flipService.flip(anyString(), anyString(), any(Signal.class))).thenReturn(null);
        when(opening.openMonthlyTrade(anyString(), anyString(), any(Position.class))).thenReturn(new Position());

        service.handleFlip("24600", "PE", signal());

        verify(closing).closeMonthlyTrade(eq("24600"), any(Signal.class), eq(false));
        verify(opening).openMonthlyTrade(eq("24600"), eq("PE"), any(Position.class));
    }

    @Test
    @DisplayName("interleave unavailable: MonthlyFlipService is never consulted for execution")
    void unavailableInterleaveUsesLegacy() {
        when(flipService.interleaveAvailable()).thenReturn(false);
        when(opening.openMonthlyTrade(anyString(), anyString(), any(Position.class))).thenReturn(new Position());

        service.handleFlip("24600", "PE", signal());

        verify(flipService, never()).flip(anyString(), anyString(), any(Signal.class));
        verify(closing).closeMonthlyTrade(eq("24600"), any(Signal.class), eq(false));
    }

    private static Signal signal() {
        return new Signal("RIDETHETIDE", "flip", "PE", "08-Aug-2026 11.00.00 AM", "24600");
    }
}
