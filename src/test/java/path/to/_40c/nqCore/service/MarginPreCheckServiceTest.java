package path.to._40c.nqCore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.zerodhatech.models.CombinedMarginData;
import com.zerodhatech.models.MarginCalculationData;
import com.zerodhatech.models.MarginCalculationParams;

import path.to._40c.nqCore.gateway.KiteGateway;
import path.to._40c.nqCore.pojo.LegOrder;
import path.to._40c.nqCore.service.MarginPreCheckService.MarginCheck;

/**
 * Guards the pre-trade margin gate's failure philosophy: block ONLY on a definitive
 * "insufficient" answer; every API failure mode (null basket, null funds, exception)
 * fails OPEN so a broker blip can never become a missed trade; buffer applied on top of
 * the basket margin; disabled = no broker calls at all.
 */
class MarginPreCheckServiceTest {

    private KiteGateway kiteGateway;
    private MarginPreCheckService service;

    @BeforeEach
    void setUp() {
        kiteGateway = mock(KiteGateway.class);
        service = new MarginPreCheckService(kiteGateway);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "bufferPct", 5.0);
    }

    @Test
    @DisplayName("sufficient funds: allowed, buffer included in the reported requirement")
    void sufficientFundsAllowed() {
        stubBasketMargin(400000.0);
        when(kiteGateway.getAvailableFunds()).thenReturn(500000.0);

        MarginCheck check = service.check(legs());

        assertThat(check.allowed()).isTrue();
        assertThat(check.requiredWithBuffer()).isEqualTo(420000.0);
        assertThat(check.available()).isEqualTo(500000.0);
    }

    @Test
    @DisplayName("insufficient funds: blocked with amounts in the reason")
    void insufficientFundsBlocked() {
        stubBasketMargin(400000.0);
        when(kiteGateway.getAvailableFunds()).thenReturn(300000.0);

        MarginCheck check = service.check(legs());

        assertThat(check.allowed()).isFalse();
        assertThat(check.reason()).contains("MARGIN BLOCKED").contains("420000").contains("300000");
    }

    @Test
    @DisplayName("boundary: available exactly equal to required-with-buffer is allowed")
    void exactBoundaryAllowed() {
        stubBasketMargin(400000.0);
        when(kiteGateway.getAvailableFunds()).thenReturn(420000.0);

        assertThat(service.check(legs()).allowed()).isTrue();
    }

    @Test
    @DisplayName("funds inside the buffer zone: raw margin would fit but buffer blocks")
    void bufferZoneBlocked() {
        stubBasketMargin(400000.0);
        when(kiteGateway.getAvailableFunds()).thenReturn(410000.0);

        assertThat(service.check(legs()).allowed()).isFalse();
    }

    @Test
    @DisplayName("disabled: allowed without touching any broker API")
    void disabledSkipsBrokerEntirely() {
        ReflectionTestUtils.setField(service, "enabled", false);

        assertThat(service.check(legs()).allowed()).isTrue();
        verifyNoInteractions(kiteGateway);
    }

    @Test
    @DisplayName("basket margin API returns null: FAIL-OPEN")
    void nullBasketFailsOpen() {
        when(kiteGateway.getCombinedMarginCalculation(anyList(), anyBoolean())).thenReturn(null);

        MarginCheck check = service.check(legs());

        assertThat(check.allowed()).isTrue();
        assertThat(check.reason()).contains("fail-open");
    }

    @Test
    @DisplayName("funds API returns null: FAIL-OPEN")
    void nullFundsFailsOpen() {
        stubBasketMargin(400000.0);
        when(kiteGateway.getAvailableFunds()).thenReturn(null);

        MarginCheck check = service.check(legs());

        assertThat(check.allowed()).isTrue();
        assertThat(check.reason()).contains("fail-open");
    }

    @Test
    @DisplayName("any exception in the check: FAIL-OPEN")
    void exceptionFailsOpen() {
        when(kiteGateway.getCombinedMarginCalculation(anyList(), anyBoolean()))
                .thenThrow(new RuntimeException("kite down"));

        assertThat(service.check(legs()).allowed()).isTrue();
    }

    @Test
    @DisplayName("basket params carry the planned legs' symbols, sides and quantities, considering existing positions")
    void basketParamsBuiltFromLegs() {
        stubBasketMargin(400000.0);
        when(kiteGateway.getAvailableFunds()).thenReturn(500000.0);

        service.check(legs());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MarginCalculationParams>> params = ArgumentCaptor.forClass((Class) List.class);
        verify(kiteGateway).getCombinedMarginCalculation(params.capture(), eq(true));
        assertThat(params.getValue()).hasSize(2);
        assertThat(params.getValue().get(0).tradingSymbol).isEqualTo("NIFTY2580723500CE");
        assertThat(params.getValue().get(0).transactionType).isEqualTo("BUY");
        assertThat(params.getValue().get(0).quantity).isEqualTo(130);
        assertThat(params.getValue().get(1).transactionType).isEqualTo("SELL");
    }

    private void stubBasketMargin(double initialTotal) {
        MarginCalculationData initial = new MarginCalculationData();
        initial.total = initialTotal;
        CombinedMarginData combined = new CombinedMarginData();
        combined.initialMargin = initial;
        when(kiteGateway.getCombinedMarginCalculation(any(), anyBoolean())).thenReturn(combined);
    }

    private static List<LegOrder> legs() {
        return List.of(leg("NIFTY2580723500CE", "BUY", 2), leg("NIFTY2580723500PE", "SELL", 2));
    }

    private static LegOrder leg(String instrument, String side, int lots) {
        LegOrder o = new LegOrder();
        o.setInstrument(instrument);
        o.setExchangeSymbol("NFO:" + instrument);
        o.setSide(side);
        o.setLots(lots);
        return o;
    }
}
