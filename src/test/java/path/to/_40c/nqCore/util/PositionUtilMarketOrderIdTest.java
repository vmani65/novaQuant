package path.to._40c.nqCore.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zerodhatech.kiteconnect.utils.Constants;
import com.zerodhatech.models.Order;
import com.zerodhatech.models.OrderParams;
import com.zerodhatech.models.OrderResponse;

import path.to._40c.nqCore.gateway.KiteGateway;
import path.to._40c.nqCore.gateway.KiteOrderStream;
import path.to._40c.nqCore.util.PositionUtil.ExecResult;

/**
 * Pins the orderId contract of the MARKET path after the PENDING_CLOSE work: a possibly-live
 * order (non-terminal after the confirm budget — the 2026-08-04 trade-73 shape) MUST surface
 * its orderId so the reconciler can settle it later, while a definitively terminal 0-fill
 * (REJECTED) must NOT — legacy behavior that ComputeUtil.neverTraded's orphan classification
 * and the fill-fetch retries in post-trade enrichment rely on (a rejected leg with a recorded
 * id would otherwise burn 3×10s retry cycles fetching fills that don't exist).
 */
class PositionUtilMarketOrderIdTest {

    private static final String INS = "NIFTY2681124650CE";
    private static final int QTY = 650;

    private KiteGateway gw;
    private PositionUtil util;

    @BeforeEach
    void setUp() {
        gw = mock(KiteGateway.class);
        KiteOrderStream stream = mock(KiteOrderStream.class);
        util = new PositionUtil(gw, stream, null, null);
        when(gw.getOrderTrades(anyString())).thenReturn(List.of());
        when(gw.placeOrder(any(OrderParams.class), anyString())).thenAnswer(inv -> {
            OrderResponse r = new OrderResponse();
            r.orderId = "MKT1";
            return r;
        });
    }

    @Test
    @DisplayName("MARKET still OPEN after confirm budget → orderId retained and flagged possibly-live")
    void nonTerminalMarketKeepsOrderId() {
        when(gw.getOrderHistory("MKT1")).thenReturn(List.of(order("OPEN", 0, 0.0)));

        ExecResult res = util.placeAggressiveOrder(null, INS, Constants.TRANSACTION_TYPE_BUY, QTY, "EXIT");

        assertThat(res.aggregateOrderIds()).isEqualTo("MKT1");
        assertThat(res.terminalStatus()).isEqualTo("OPEN");
        assertThat(res.fullyFilled()).isFalse();
        assertThat(PositionUtil.closeOrderMayBeLive(res)).isTrue();
    }

    @Test
    @DisplayName("MARKET REJECTED with zero fills → no orderId recorded, not possibly-live (legacy semantics)")
    void terminalRejectedMarketDropsOrderId() {
        when(gw.getOrderHistory("MKT1")).thenReturn(List.of(order(Constants.ORDER_REJECTED, 0, 0.0)));

        ExecResult res = util.placeAggressiveOrder(null, INS, Constants.TRANSACTION_TYPE_BUY, QTY, "EXIT");

        assertThat(res.aggregateOrderIds()).isEmpty();
        assertThat(res.terminalStatus()).isEqualTo(Constants.ORDER_REJECTED);
        assertThat(PositionUtil.closeOrderMayBeLive(res)).isFalse();
    }

    private static Order order(String status, int filledQty, double avgPrice) {
        Order o = new Order();
        o.status = status;
        o.filledQuantity = String.valueOf(filledQty);
        o.averagePrice = String.valueOf(avgPrice);
        return o;
    }
}
