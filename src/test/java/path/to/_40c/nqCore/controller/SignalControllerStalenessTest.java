package path.to._40c.nqCore.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static path.to._40c.nqCore.util.Constants.OUTPUT_FORMAT;
import static path.to._40c.nqCore.util.Constants.ZONE_ID;

import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import path.to._40c.nqCore.controller.SignalController.Signal;
import path.to._40c.nqCore.service.SignalService;
import path.to._40c.nqCore.util.ComputeUtil;

/**
 * The staleness gate (2026-08-12 incident): a restart wipes the in-memory dedup cache and
 * AmiBroker re-transmits its last signal, so a fresh app accepted a 17-hour-old flip — only
 * missing Kite auth kept its orders off the broker. The gate rejects signals whose bar time
 * is older than the threshold, BEFORE sequence validation and WITHOUT advancing prev — so
 * the sequence gate then also rejects the follow-on signals that assume the stale one
 * executed (the exact incident chain, replayed here as a test). Fail-open on unparseable
 * times: nothing that works today may be rejected on format grounds.
 */
class SignalControllerStalenessTest {

    private SignalService signalService;
    private SignalController controller;

    @BeforeEach
    void setUp() {
        signalService = mock(SignalService.class);
        ComputeUtil util = mock(ComputeUtil.class);
        when(util.toStd(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(signalService.handleFlip(anyString(), anyString(), any(Signal.class))).thenReturn(true);
        when(signalService.handleTradeOpen(anyString(), anyString(), any(Signal.class))).thenReturn(true);
        when(signalService.handleTradeClose(anyString(), anyString(), any(Signal.class))).thenReturn(true);
        controller = new SignalController(signalService, util);
        ReflectionTestUtils.setField(controller, "maxSignalAgeMinutes", 45L);
    }

    private static String barTimeMinutesAgo(long minutes) {
        return OUTPUT_FORMAT.format(LocalDateTime.now(ZoneId.of(ZONE_ID)).minusMinutes(minutes));
    }

    @Test
    @DisplayName("a fresh signal (2 min after its bar) passes the gate and executes")
    void freshSignalPasses() {
        controller.handleShortEntry("PE", "24500", "SwingMaster", barTimeMinutesAgo(2));

        verify(signalService).handleTradeOpen(anyString(), anyString(), any(Signal.class));
    }

    @Test
    @DisplayName("the 08:47 incident chain: stale flip rejected, prev untouched, follow-on longExit killed by the sequence gate")
    void staleFlipRejectedAndSequenceProtectionSurvives() {
        controller.handleShortEntry("PE", "24530", "SwingMaster", barTimeMinutesAgo(3));
        verify(signalService).handleTradeOpen(anyString(), anyString(), any(Signal.class));

        controller.handleFlip("CE", "24540", "SwingMaster", barTimeMinutesAgo(17 * 60));
        verify(signalService, never()).handleFlip(anyString(), anyString(), any(Signal.class));

        controller.handleLongExit("CE", "24490", "SwingMaster", barTimeMinutesAgo(1));
        verify(signalService, never()).handleTradeClose(anyString(), anyString(), any(Signal.class));
    }

    @Test
    @DisplayName("borderline-but-legitimate age (40 min) still executes")
    void borderlineAgePasses() {
        controller.handleShortEntry("PE", "24500", "SwingMaster", barTimeMinutesAgo(40));

        verify(signalService).handleTradeOpen(anyString(), anyString(), any(Signal.class));
    }

    @Test
    @DisplayName("unparseable time fails OPEN — the signal executes exactly as before the gate existed")
    void unparseableTimeFailsOpen() {
        controller.handleShortEntry("PE", "24500", "SwingMaster", "not-a-timestamp");

        verify(signalService).handleTradeOpen(anyString(), anyString(), any(Signal.class));
    }

    @Test
    @DisplayName("threshold 0 disables the gate entirely — even a 17-hour-old signal executes")
    void zeroThresholdDisablesGate() {
        ReflectionTestUtils.setField(controller, "maxSignalAgeMinutes", 0L);

        controller.handleShortEntry("PE", "24500", "SwingMaster", barTimeMinutesAgo(17 * 60));

        verify(signalService).handleTradeOpen(anyString(), anyString(), any(Signal.class));
    }
}
