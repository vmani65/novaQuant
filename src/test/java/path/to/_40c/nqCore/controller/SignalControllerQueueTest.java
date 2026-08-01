package path.to._40c.nqCore.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import path.to._40c.nqCore.controller.SignalController.Signal;
import path.to._40c.nqCore.service.SignalService;
import path.to._40c.nqCore.service.StrategyRegistry;
import path.to._40c.nqCore.service.TradeExecutionQueue;
import path.to._40c.nqCore.util.ComputeUtil;

/**
 * Verifies the async signal path introduced with the trade-exec queue: HTTP-thread gates
 * (registry, dedup) filter before anything is enqueued, dispatch happens on the queue
 * thread, sequence validation runs at execution time against the ordered previous state,
 * and the endpoint returns without waiting for the trade to execute.
 */
class SignalControllerQueueTest {

    private SignalService signalService;
    private StrategyRegistry strategyRegistry;
    private TradeExecutionQueue queue;
    private SignalController controller;

    @BeforeEach
    void setUp() {
        signalService = mock(SignalService.class);
        strategyRegistry = mock(StrategyRegistry.class);
        ComputeUtil util = mock(ComputeUtil.class);
        when(util.toStd(anyString())).thenAnswer(inv -> inv.getArgument(0));
        queue = new TradeExecutionQueue();
        controller = new SignalController(signalService, util, strategyRegistry, queue);
    }

    @AfterEach
    void tearDown() {
        queue.shutdown();
    }

    /** Barrier: every previously enqueued task has fully executed once this returns. */
    private void drainQueue() {
        queue.submit("test-barrier", () -> true).join();
    }

    @Test
    @DisplayName("a signal from an unregistered strategy is never enqueued nor dispatched")
    void unregisteredStrategyRejectedBeforeEnqueue() {
        when(strategyRegistry.isActive("Ghost")).thenReturn(false);

        controller.handleLongEntry("CE", "23500", "Ghost", "31-07-2026 09:30:00");
        drainQueue();

        verifyNoInteractions(signalService);
    }

    @Test
    @DisplayName("a duplicate signal is absorbed on the HTTP thread and executes only once")
    void duplicateExecutesOnce() {
        when(strategyRegistry.isActive("StratA")).thenReturn(true);
        when(signalService.handleTradeOpen(any(), any(), any())).thenReturn(true);

        controller.handleLongEntry("CE", "23500", "StratA", "31-07-2026 09:30:00");
        controller.handleLongEntry("CE", "23500", "StratA", "31-07-2026 09:30:00");
        drainQueue();

        verify(signalService, times(1)).handleTradeOpen(eq("23500"), eq("CE"), any(Signal.class));
    }

    @Test
    @DisplayName("sequence validation runs at execution time: back-to-back entries for one strategy execute once")
    void sequenceValidatedAtExecutionTime() {
        when(strategyRegistry.isActive("StratA")).thenReturn(true);
        when(signalService.handleTradeOpen(any(), any(), any())).thenReturn(true);

        controller.handleLongEntry("CE", "23500", "StratA", "31-07-2026 09:30:00");
        controller.handleLongEntry("CE", "23600", "StratA", "31-07-2026 09:45:00");
        drainQueue();

        verify(signalService, times(1)).handleTradeOpen(any(), any(), any(Signal.class));
    }

    @Test
    @DisplayName("independent strategies each dispatch — one strategy's signal never gates another's")
    void independentStrategiesBothDispatch() {
        when(strategyRegistry.isActive(anyString())).thenReturn(true);
        when(signalService.handleTradeOpen(any(), any(), any())).thenReturn(true);

        controller.handleLongEntry("CE", "23500", "StratA", "31-07-2026 09:30:00");
        controller.handleShortEntry("PE", "23500", "StratB", "31-07-2026 09:30:00");
        drainQueue();

        verify(signalService, times(2)).handleTradeOpen(eq("23500"), anyString(), any(Signal.class));
    }

    @Test
    @DisplayName("exit and flip actions dispatch to their handlers on the queue thread")
    void exitAndFlipDispatch() {
        when(strategyRegistry.isActive("StratA")).thenReturn(true);
        when(signalService.handleTradeClose(any(), any(), any())).thenReturn(true);
        when(signalService.handleFlip(any(), any(), any())).thenReturn(true);

        controller.handleLongExit("CE", "23600", "StratA", "31-07-2026 10:00:00");
        drainQueue();
        verify(signalService, times(1)).handleTradeClose(eq("23600"), eq("CE"), any(Signal.class));

        controller.handleFlip("PE", "23400", "StratA", "31-07-2026 10:15:00");
        drainQueue();
        verify(signalService, times(1)).handleFlip(eq("23400"), eq("PE"), any(Signal.class));
    }

    @Test
    @DisplayName("the HTTP thread returns before the trade executes")
    void httpThreadDoesNotBlockOnExecution() throws Exception {
        when(strategyRegistry.isActive("StratA")).thenReturn(true);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(signalService.handleTradeOpen(any(), any(), any())).thenAnswer(inv -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return true;
        });

        long start = System.nanoTime();
        controller.handleLongEntry("CE", "23500", "StratA", "31-07-2026 09:30:00");
        long endpointMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(endpointMs).isLessThan(2000);
        release.countDown();
        drainQueue();
    }

    @Test
    @DisplayName("manual rollover returns immediately and executes on the queue")
    void rolloverQueued() {
        when(signalService.handleRollOver("23500")).thenReturn(true);

        boolean accepted = controller.handleRollOver("23,500");
        drainQueue();

        assertThat(accepted).isTrue();
        verify(signalService, times(1)).handleRollOver("23500");
    }
}
