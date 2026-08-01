package path.to._40c.nqCore.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import path.to._40c.nqCore.entity.WeeklySymbolConfig;
import path.to._40c.nqCore.service.ProfitRecenterService;
import path.to._40c.nqCore.service.SignalService;
import path.to._40c.nqCore.service.TradeExecutionQueue;
import path.to._40c.nqCore.service.WeeklySymbolService;

/**
 * Guards the trigger's promotion gate: the symbol is promoted and rollover marked complete
 * ONLY when every position rolled. On partial failure rolloverComplete stays unset so the
 * trigger can be re-fired — the alternative silently strands the failed book on the
 * expiring contract.
 */
class RollOverTriggerControllerTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private SignalService signalService;
    private WeeklySymbolService weeklySymbolService;
    private TradeExecutionQueue queue;
    private RollOverTriggerController controller;
    private WeeklySymbolConfig cfg;

    @BeforeEach
    void setUp() {
        signalService = mock(SignalService.class);
        weeklySymbolService = mock(WeeklySymbolService.class);
        ProfitRecenterService profitRecenterService = mock(ProfitRecenterService.class);
        queue = new TradeExecutionQueue();
        controller = new RollOverTriggerController(signalService, weeklySymbolService, profitRecenterService, queue);
        cfg = new WeeklySymbolConfig("25807", "25814");
        cfg.setRolloverDay(LocalDate.now(IST));
        lenient().when(weeklySymbolService.current()).thenReturn(cfg);
    }

    @AfterEach
    void tearDown() {
        queue.shutdown();
    }

    /** Barrier: the queued trigger task has fully executed once this returns. */
    private void drainQueue() {
        queue.submit("test-barrier", () -> true).join();
    }

    @Test
    @DisplayName("all positions rolled: symbol promoted and rollover marked complete")
    void successPromotesAndMarksComplete() {
        when(signalService.handleRollOver(anyString())).thenReturn(true);

        controller.handleRollOverTrigger("23,500.50");
        drainQueue();

        verify(signalService, times(1)).handleRollOver("23500.50");
        verify(weeklySymbolService, times(1)).promoteRolloverSymbol();
        verify(weeklySymbolService, times(1)).markRolloverComplete();
    }

    @Test
    @DisplayName("any roll failure: symbol NOT promoted, rolloverComplete NOT set — retry stays possible")
    void failureBlocksPromotion() {
        when(signalService.handleRollOver(anyString())).thenReturn(false);

        controller.handleRollOverTrigger("23500");
        drainQueue();

        verify(signalService, times(1)).handleRollOver("23500");
        verify(weeklySymbolService, never()).promoteRolloverSymbol();
        verify(weeklySymbolService, never()).markRolloverComplete();
    }

    @Test
    @DisplayName("already complete for the day: rollover not re-fired")
    void alreadyCompleteSkips() {
        cfg.setRolloverComplete(true);

        controller.handleRollOverTrigger("23500");
        drainQueue();

        verify(signalService, never()).handleRollOver(anyString());
    }

    @Test
    @DisplayName("wrong day: rollover not fired")
    void wrongDaySkips() {
        cfg.setRolloverDay(LocalDate.now(IST).minusDays(1));

        controller.handleRollOverTrigger("23500");
        drainQueue();

        verify(signalService, never()).handleRollOver(anyString());
        verify(weeklySymbolService, never()).promoteRolloverSymbol();
    }
}
