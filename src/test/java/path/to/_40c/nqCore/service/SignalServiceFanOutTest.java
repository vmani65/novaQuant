package path.to._40c.nqCore.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static path.to._40c.nqCore.util.Constants.CE;
import static path.to._40c.nqCore.util.Constants.LIVE;
import static path.to._40c.nqCore.util.Constants.LONG_MONTHLY;
import static path.to._40c.nqCore.util.Constants.SYNTH_WEEKLY;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import path.to._40c.nqCore.controller.SignalController.Signal;
import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.repo.PositionRepository;

/**
 * Business rules of the per-book signal fan-out:
 * - every signal reaches BOTH books, SYNTH_WEEKLY strictly before LONG_MONTHLY;
 * - an exception inside one book's execution never blocks the other book;
 * - toggles gate NEW positions only — a disabled book still receives exits, and a flip
 *   on a disabled book degrades to close-only (manage to natural close, open nothing);
 * - the weekly rollover trigger reaches rollOverWeekly and nothing monthly.
 * All broker work is behind mocked opening/closing services — this is pure orchestration.
 */
class SignalServiceFanOutTest {

    private PositionOpeningService openingService;
    private PositionClosingService closingService;
    private PositionRolloverService rollOverService;
    private PostTradeService postTradeService;
    private BookConfigService bookConfig;
    private MonthlyContractService monthlyContractService;
    private SignalService service;

    private final Position weeklyLive = livePosition(SYNTH_WEEKLY);
    private final Position monthlyLive = livePosition(LONG_MONTHLY);

    @BeforeEach
    void setUp() {
        openingService = mock(PositionOpeningService.class);
        closingService = mock(PositionClosingService.class);
        rollOverService = mock(PositionRolloverService.class);
        postTradeService = mock(PostTradeService.class);
        bookConfig = mock(BookConfigService.class);
        monthlyContractService = mock(MonthlyContractService.class);
        service = new SignalService(openingService, closingService, rollOverService, postTradeService,
                mock(PositionRepository.class), mock(WeeklySymbolService.class), bookConfig, monthlyContractService);

        when(bookConfig.isEnabled(SYNTH_WEEKLY)).thenReturn(true);
        when(bookConfig.isEnabled(LONG_MONTHLY)).thenReturn(true);
        when(openingService.prepareWeeklyOpen(anyString(), anyString(), any(Position.class)))
                .thenReturn(new PositionOpeningService.OpenPrep(List.of(), Map.of()));
        when(openingService.openTrade(anyString(), anyString(), any(Position.class), any()))
                .thenReturn(weeklyLive);
        when(openingService.openWeeklyTrade(anyString(), anyString(), any(Position.class)))
                .thenReturn(weeklyLive);
        when(openingService.openMonthlyTrade(anyString(), anyString(), any(Position.class)))
                .thenReturn(monthlyLive);
    }

    @Test
    @DisplayName("flip with both books enabled: weekly flips first, then monthly flips — each via its own book methods")
    void flipFansOutToBothBooksWeeklyFirst() {
        service.handleFlip("24500", CE, signal("flip"));

        InOrder order = inOrder(closingService, openingService);
        order.verify(closingService).closeWeeklyTrade(eq("24500"), any(Signal.class), eq(false));
        order.verify(closingService).closeMonthlyTrade(eq("24500"), any(Signal.class), eq(false));
        order.verify(openingService).openMonthlyTrade(eq("24500"), eq(CE), any(Position.class));
        verify(openingService).openTrade(eq("24500"), eq(CE), any(Position.class), any());
    }

    @Test
    @DisplayName("flip with LONG_MONTHLY disabled: monthly degrades to close-only, opens nothing")
    void flipOnDisabledMonthlyClosesOnly() {
        when(bookConfig.isEnabled(LONG_MONTHLY)).thenReturn(false);
        when(closingService.closeMonthlyTrade(anyString(), any(Signal.class), anyBoolean())).thenReturn(monthlyLive);

        service.handleFlip("24500", CE, signal("flip"));

        verify(closingService).closeMonthlyTrade(eq("24500"), any(Signal.class), eq(true));
        verify(openingService, never()).openMonthlyTrade(anyString(), anyString(), any(Position.class));
        verify(postTradeService).afterClose(monthlyLive);
        verify(openingService).openTrade(eq("24500"), eq(CE), any(Position.class), any());
    }

    @Test
    @DisplayName("a weekly-book failure is isolated: the monthly book still executes the flip")
    void weeklyFailureNeverBlocksMonthly() {
        when(closingService.closeWeeklyTrade(anyString(), any(Signal.class), anyBoolean()))
                .thenThrow(new RuntimeException("weekly quote outage"));

        boolean result = service.handleFlip("24500", CE, signal("flip"));

        assertThat(result).isTrue();
        verify(closingService).closeMonthlyTrade(eq("24500"), any(Signal.class), eq(false));
        verify(openingService).openMonthlyTrade(eq("24500"), eq(CE), any(Position.class));
    }

    @Test
    @DisplayName("a monthly-book failure (e.g. unconfigured book) is isolated and the signal still succeeds")
    void monthlyFailureNeverBlocksSignal() {
        when(openingService.openMonthlyTrade(anyString(), anyString(), any(Position.class)))
                .thenThrow(new IllegalStateException("LONG_MONTHLY has no monthly symbol configured"));

        boolean result = service.handleTradeOpen("24500", CE, signal("longEntry"));

        assertThat(result).isTrue();
        verify(openingService).openWeeklyTrade(eq("24500"), eq(CE), any(Position.class));
        verify(postTradeService).afterOpen(weeklyLive);
    }

    @Test
    @DisplayName("entry with LONG_MONTHLY disabled opens weekly only — no monthly orphan sweep, no monthly open")
    void entrySkipsDisabledMonthly() {
        when(bookConfig.isEnabled(LONG_MONTHLY)).thenReturn(false);

        service.handleTradeOpen("24500", CE, signal("longEntry"));

        verify(openingService).openWeeklyTrade(eq("24500"), eq(CE), any(Position.class));
        verify(openingService, never()).openMonthlyTrade(anyString(), anyString(), any(Position.class));
        verify(closingService, never()).closeMonthlyOrphanIfAny(anyString(), any(Signal.class));
    }

    @Test
    @DisplayName("entry fan-out sweeps each book's own orphans before its open")
    void entrySweepsPerBookOrphans() {
        service.handleTradeOpen("24500", CE, signal("longEntry"));

        InOrder weekly = inOrder(closingService, openingService);
        weekly.verify(closingService).closeWeeklyOrphanIfAny(eq("24500"), any(Signal.class));
        weekly.verify(openingService).openWeeklyTrade(eq("24500"), eq(CE), any(Position.class));
        InOrder monthly = inOrder(closingService, openingService);
        monthly.verify(closingService).closeMonthlyOrphanIfAny(eq("24500"), any(Signal.class));
        monthly.verify(openingService).openMonthlyTrade(eq("24500"), eq(CE), any(Position.class));
    }

    @Test
    @DisplayName("monthly opens run the DTE roll check first; a disabled monthly book never does")
    void monthlyOpenRunsRollCheckFirst() {
        service.handleTradeOpen("24500", CE, signal("longEntry"));
        InOrder order = inOrder(monthlyContractService, openingService);
        order.verify(monthlyContractService).syncTradedContract();
        order.verify(openingService).openMonthlyTrade(eq("24500"), eq(CE), any(Position.class));

        when(bookConfig.isEnabled(LONG_MONTHLY)).thenReturn(false);
        service.handleFlip("24501", CE, signal("flip"));
        verify(monthlyContractService).syncTradedContract();
    }

    @Test
    @DisplayName("close reaches BOTH books even when both toggles are off — open positions are managed to natural close")
    void closeRunsForDisabledBooks() {
        when(bookConfig.isEnabled(SYNTH_WEEKLY)).thenReturn(false);
        when(bookConfig.isEnabled(LONG_MONTHLY)).thenReturn(false);
        when(closingService.closeWeeklyTrade(anyString(), any(Signal.class), anyBoolean())).thenReturn(weeklyLive);
        when(closingService.closeMonthlyTrade(anyString(), any(Signal.class), anyBoolean())).thenReturn(monthlyLive);

        service.handleTradeClose("24500", "PE", signal("shortExit"));

        verify(closingService).closeWeeklyTrade(eq("24500"), any(Signal.class), eq(true));
        verify(closingService).closeMonthlyTrade(eq("24500"), any(Signal.class), eq(true));
        verify(postTradeService).afterClose(weeklyLive);
        verify(postTradeService).afterClose(monthlyLive);
    }

    @Test
    @DisplayName("a weekly close failure still lets the monthly close run")
    void closeIsolatesWeeklyFailure() {
        when(closingService.closeWeeklyTrade(anyString(), any(Signal.class), anyBoolean()))
                .thenThrow(new RuntimeException("kite down"));
        when(closingService.closeMonthlyTrade(anyString(), any(Signal.class), anyBoolean())).thenReturn(monthlyLive);

        boolean result = service.handleTradeClose("24500", "PE", signal("shortExit"));

        assertThat(result).isTrue();
        verify(closingService).closeMonthlyTrade(eq("24500"), any(Signal.class), eq(true));
        verify(postTradeService).afterClose(monthlyLive);
    }

    @Test
    @DisplayName("the 14:47 rollover trigger rolls the weekly book only")
    void rolloverTriggerIsWeeklyOnly() {
        service.handleRollOver("24500");

        verify(rollOverService).rollOverWeekly("24500");
        verify(rollOverService, never()).rollOverMonthly(anyString());
    }

    @Test
    @DisplayName("the monthly rollover trigger syncs the traded contract first, rolls monthly only, and never throws")
    void monthlyRolloverTriggerIsMonthlyOnly() {
        boolean ok = service.handleMonthlyRollOver("24500");

        assertThat(ok).isTrue();
        InOrder order = inOrder(monthlyContractService, rollOverService);
        order.verify(monthlyContractService).syncTradedContract();
        order.verify(rollOverService).rollOverMonthly("24500");
        verify(rollOverService, never()).rollOverWeekly(anyString());

        org.mockito.Mockito.doThrow(new IllegalStateException("no monthly templates"))
                .when(rollOverService).rollOverMonthly(anyString());
        assertThat(service.handleMonthlyRollOver("24501")).isFalse();
    }

    private static Signal signal(String action) {
        return new Signal("RIDETHETIDE", action, action.startsWith("short") ? "PE" : CE, "07-Aug-2026 10.30.00 AM", "24500");
    }

    private static Position livePosition(String book) {
        Position p = new Position();
        p.setBook(book);
        p.setStatus(LIVE);
        return p;
    }
}
