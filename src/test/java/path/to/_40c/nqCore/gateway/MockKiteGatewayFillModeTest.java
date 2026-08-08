package path.to._40c.nqCore.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zerodhatech.kiteconnect.utils.Constants;
import com.zerodhatech.models.Order;
import com.zerodhatech.models.OrderParams;
import com.zerodhatech.models.OrderResponse;

/**
 * Mock-gateway upgrade for patient/sliced flows (PATIENT_EXECUTION_PLAN.md §7.1). The old mock
 * hardcoded every fill to qty 65 and treated cancelOrder as a no-op — which silently breaks any
 * 2-lot monthly or multi-slice click-through: a 130-qty order read back as 65 makes the walk
 * "top up" a phantom remainder. These tests pin the new contract: history/trades echo the REAL
 * requested qty, cancel actually cancels a resting order, and fill modes (NEVER /
 * AFTER_MODIFIES:n) let the E2E script drive the patient loop's resting and concession paths.
 */
class MockKiteGatewayFillModeTest {

    private static final String INS = "NIFTY26AUG23850CE";

    private MockKiteOrderStream stream;
    private MockKiteGateway gw;

    @BeforeEach
    void setUp() {
        stream = new MockKiteOrderStream();
        stream.setSyntheticFillDelayMs(0);
        gw = new MockKiteGateway(stream);
        gw.setFillMode("INSTANT");
    }

    @AfterEach
    void tearDown() {
        stream.shutdown();
    }

    @Test
    @DisplayName("INSTANT (legacy default): fills immediately but echoes the REAL qty, not 65")
    void instantFillEchoesRealQty() {
        String id = place(130, 463.0);

        Order o = gw.getOrderHistory(id).get(0);
        assertThat(o.status).isEqualTo(Constants.ORDER_COMPLETE);
        assertThat(o.filledQuantity).isEqualTo("130");
        assertThat(o.quantity).isEqualTo("130");
        assertThat(gw.getOrderTrades(id).get(0).quantity).isEqualTo("130");
    }

    @Test
    @DisplayName("NEVER: order rests OPEN with no fills and no trades — the patient REST scenario")
    void neverModeRestsOpen() {
        gw.setFillMode("NEVER");
        String id = place(130, 463.0);

        Order o = gw.getOrderHistory(id).get(0);
        assertThat(o.status).isEqualTo("OPEN");
        assertThat(o.filledQuantity).isEqualTo("0");
        assertThat(o.quantity).isEqualTo("130");
        assertThat(gw.getOrderTrades(id)).as("no trades while resting").isEmpty();
    }

    @Test
    @DisplayName("cancelOrder actually cancels a resting order (no longer a no-op)")
    void cancelMarksRestingOrderCancelled() {
        gw.setFillMode("NEVER");
        String id = place(130, 463.0);

        assertThat(gw.cancelOrder(id, Constants.VARIETY_REGULAR)).isTrue();

        Order o = gw.getOrderHistory(id).get(0);
        assertThat(o.status).isEqualTo(Constants.ORDER_CANCELLED);
        assertThat(o.filledQuantity).isEqualTo("0");
        assertThat(gw.getOrderTrades(id)).isEmpty();
    }

    @Test
    @DisplayName("AFTER_MODIFIES:2 fills on the second reprice at the modified price — the concession-walk scenario")
    void afterModifiesFillsOnNthReprice() {
        gw.setFillMode("AFTER_MODIFIES:2");
        String id = place(130, 463.0);

        assertThat(gw.getOrderHistory(id).get(0).status).isEqualTo("OPEN");
        gw.modifyOrder(id, 461.85, 130, Constants.VARIETY_REGULAR);
        assertThat(gw.getOrderHistory(id).get(0).status).as("one modify is not enough").isEqualTo("OPEN");
        gw.modifyOrder(id, 460.70, 130, Constants.VARIETY_REGULAR);

        Order o = gw.getOrderHistory(id).get(0);
        assertThat(o.status).isEqualTo(Constants.ORDER_COMPLETE);
        assertThat(o.filledQuantity).isEqualTo("130");
        assertThat(o.averagePrice).as("fills at the price that got it done").isEqualTo("460.7");
        assertThat(gw.getOrderTrades(id).get(0).quantity).isEqualTo("130");
    }

    @Test
    @DisplayName("unknown order ids keep the legacy 65-qty COMPLETE fallback")
    void unknownIdKeepsLegacyFallback() {
        Order o = gw.getOrderHistory("MOCK-99999").get(0);
        assertThat(o.status).isEqualTo(Constants.ORDER_COMPLETE);
        assertThat(o.filledQuantity).isEqualTo("65");
        assertThat(gw.getOrderTrades("MOCK-99999").get(0).quantity).isEqualTo("65");
    }

    @Test
    @DisplayName("invalid fill-mode value degrades to INSTANT instead of breaking the mock profile")
    void invalidModeDegradesToInstant() {
        gw.setFillMode("garbage");
        String id = place(65, 100.0);
        assertThat(gw.getOrderHistory(id).get(0).status).isEqualTo(Constants.ORDER_COMPLETE);
    }

    private String place(int qty, double price) {
        OrderParams p = new OrderParams();
        p.tradingsymbol = INS;
        p.transactionType = Constants.TRANSACTION_TYPE_SELL;
        p.quantity = qty;
        p.price = price;
        p.orderType = Constants.ORDER_TYPE_LIMIT;
        OrderResponse r = gw.placeOrder(p, Constants.VARIETY_REGULAR);
        return r.orderId;
    }
}
