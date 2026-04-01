package path.to._40c.gateway;

import java.util.List;
import java.util.Map;

import com.zerodhatech.models.BulkOrderResponse;
import com.zerodhatech.models.Instrument;
import com.zerodhatech.models.LTPQuote;
import com.zerodhatech.models.MarginCalculationData;
import com.zerodhatech.models.MarginCalculationParams;
import com.zerodhatech.models.OrderParams;
import com.zerodhatech.models.OrderResponse;
import com.zerodhatech.models.User;

/**
 * Abstraction over the Kite Connect SDK.
 *
 * Two implementations:
 *  - RealKiteGateway  (active when spring.profiles.active != mock) — hits real Zerodha APIs
 *  - MockKiteGateway  (active when spring.profiles.active=mock)    — returns fake data for local testing
 *
 * Switch profiles in application.properties:
 *   spring.profiles.active=mock     ← safe local testing, no real orders placed
 *   spring.profiles.active=default  ← production, real orders placed
 */
public interface KiteGateway {

    /** Fetch last traded price for one or more instruments. Returns empty map on auth/network failure. */
    Map<String, LTPQuote> getLTP(String[] instruments);

    /** Place a single market order. Returns null on failure. */
    OrderResponse placeOrder(OrderParams params, String variety);

    /** Place an auto-sliced order for large quantities. Returns empty list on failure. */
    List<BulkOrderResponse> placeAutoSliceOrder(OrderParams params, String variety);

    /** Fetch margin + brokerage for a basket of params. Returns empty list on failure. */
    List<MarginCalculationData> getMarginCalculation(List<MarginCalculationParams> params);

    /** Fetch executed trades for a single orderId. Returns empty list on failure. */
    List<com.zerodhatech.models.Trade> getOrderTrades(String singleOrderId);

    /** Fetch all instruments for an exchange. Returns empty list on failure. */
    List<Instrument> getInstruments(String exchange);

    /** Generate a Kite session from a request token. Returns null on failure. */
    User generateSession(String requestToken, String apiSecret);

    /** Get the Kite login URL. */
    String getLoginURL();

    /**
     * Invalidate the cached KiteConnect session.
     * Called after new auth is saved so the next call picks up the fresh access token.
     * No-op in MockKiteGateway.
     */
    void invalidateCache();
}
