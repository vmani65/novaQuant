package path.to._40c.nqCore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import path.to._40c.nqCore.controller.SignalController.Signal;
import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.repo.PositionRepository;

/**
 * Guards the 9:15 open-buffer flow under multiple strategies. The nqTicker callback carries
 * no strategy, so the pending set recorded at delegation time is the contract: every
 * strategy that delegated gets its OWN scoped close on callback, the set drains exactly
 * once, a stray/post-restart callback falls back to the legacy any-strategy close, and
 * delegation happens only for longExit inside the 9:15 window with a successful arm.
 */
class SignalServiceOpenBufferTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private PositionClosingService closingService;
    private PostTradeService postTradeService;
    private WeeklySymbolService weeklySymbolService;
    private NqTickerClient nqTickerClient;
    private SignalService service;

    @BeforeEach
    void setUp() {
        PositionOpeningService openingService = mock(PositionOpeningService.class);
        closingService = mock(PositionClosingService.class);
        PositionRolloverService rollOverService = mock(PositionRolloverService.class);
        postTradeService = mock(PostTradeService.class);
        PositionRepository positionRepository = mock(PositionRepository.class);
        weeklySymbolService = mock(WeeklySymbolService.class);
        nqTickerClient = mock(NqTickerClient.class);
        service = new SignalService(openingService, closingService, rollOverService, postTradeService,
                positionRepository, weeklySymbolService, nqTickerClient);
        lenient().when(closingService.closeTrade(anyString(), any(), anyBoolean())).thenReturn(new Position());
    }

    private void clockAt(int hour, int minute) {
        service.setClockForTesting(Clock.fixed(
                LocalDateTime.of(2026, 7, 31, hour, minute, 30).atZone(IST).toInstant(), IST));
    }

    @Test
    @DisplayName("two strategies delegate at 9:15; the callback closes each strategy's own book")
    void delegatesAndClosesPerStrategy() {
        clockAt(9, 15);
        when(nqTickerClient.armBuffer("23500")).thenReturn(true);

        assertThat(service.handleTradeClose("23500", "CE", exitSignal("StratA"))).isTrue();
        assertThat(service.handleTradeClose("23500", "CE", exitSignal("StratB"))).isTrue();
        verifyNoInteractions(closingService);
        verify(nqTickerClient, times(2)).armBuffer("23500");

        service.executeCloseImmediate("23480");

        ArgumentCaptor<Signal> signals = ArgumentCaptor.forClass(Signal.class);
        verify(closingService, times(2)).closeTrade(eq("23480"), signals.capture(), eq(true));
        assertThat(signals.getAllValues())
                .extracting(s -> s.strategyName)
                .containsExactlyInAnyOrder("StratA", "StratB");
        assertThat(signals.getAllValues()).allSatisfy(s -> assertThat(s.action).isEqualTo("longExit"));
        verify(postTradeService, times(2)).afterClose(any(Position.class));
        verify(weeklySymbolService, times(2)).checkAndPromoteRolloverSymbol();
    }

    @Test
    @DisplayName("the pending set drains once: a second callback falls back to the legacy any-strategy close")
    void pendingDrainsExactlyOnce() {
        clockAt(9, 15);
        when(nqTickerClient.armBuffer("23500")).thenReturn(true);
        service.handleTradeClose("23500", "CE", exitSignal("StratA"));

        service.executeCloseImmediate("23480");
        service.executeCloseImmediate("23490");

        ArgumentCaptor<Signal> signals = ArgumentCaptor.forClass(Signal.class);
        verify(closingService, times(2)).closeTrade(anyString(), signals.capture(), eq(true));
        assertThat(signals.getAllValues().get(0).strategyName).isEqualTo("StratA");
        assertThat(signals.getAllValues().get(1).strategyName).isEqualTo("open-buffer");
    }

    @Test
    @DisplayName("outside the 9:15 window the close runs inline — no delegation")
    void outsideWindowClosesInline() {
        clockAt(10, 0);
        Signal signal = exitSignal("StratA");

        service.handleTradeClose("23500", "CE", signal);

        verifyNoInteractions(nqTickerClient);
        verify(closingService, times(1)).closeTrade(eq("23500"), eq(signal), eq(true));
        verify(postTradeService, times(1)).afterClose(any(Position.class));
    }

    @Test
    @DisplayName("arm failure at 9:15 falls back to an inline close and records nothing pending")
    void armFailureFallsBackInline() {
        clockAt(9, 15);
        when(nqTickerClient.armBuffer("23500")).thenReturn(false);
        Signal signal = exitSignal("StratA");

        service.handleTradeClose("23500", "CE", signal);
        verify(closingService, times(1)).closeTrade(eq("23500"), eq(signal), eq(true));

        service.executeCloseImmediate("23480");
        ArgumentCaptor<Signal> signals = ArgumentCaptor.forClass(Signal.class);
        verify(closingService, times(2)).closeTrade(anyString(), signals.capture(), eq(true));
        assertThat(signals.getAllValues().get(1).strategyName).isEqualTo("open-buffer");
    }

    @Test
    @DisplayName("shortExit at 9:15 is never delegated — the buffer is a longExit-only flow")
    void shortExitNeverDelegated() {
        clockAt(9, 15);
        Signal signal = new Signal("StratA", "shortExit", "PE", "31-07-2026 09:15:00", "23500");

        service.handleTradeClose("23500", "PE", signal);

        verifyNoInteractions(nqTickerClient);
        verify(closingService, times(1)).closeTrade(eq("23500"), eq(signal), eq(true));
    }

    @Test
    @DisplayName("a strategy delegating twice before the callback closes only once")
    void duplicateDelegationClosesOnce() {
        clockAt(9, 15);
        when(nqTickerClient.armBuffer(anyString())).thenReturn(true);
        service.handleTradeClose("23500", "CE", exitSignal("StratA"));
        service.handleTradeClose("23505", "CE", exitSignal("StratA"));

        service.executeCloseImmediate("23480");

        verify(closingService, times(1)).closeTrade(eq("23480"), any(Signal.class), eq(true));
    }

    private static Signal exitSignal(String strategy) {
        return new Signal(strategy, "longExit", "CE", "31-07-2026 09:15:00", "23500");
    }
}
