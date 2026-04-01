package path.to._40c.gateway;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static path.to._40c.util.Constants.BUY;
import static path.to._40c.util.Constants.CE;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.zerodhatech.models.BulkOrderResponse;
import com.zerodhatech.models.Instrument;
import com.zerodhatech.models.LTPQuote;
import com.zerodhatech.models.MarginCalculationData;
import com.zerodhatech.models.MarginCalculationParams;
import com.zerodhatech.models.OrderParams;
import com.zerodhatech.models.OrderResponse;
import com.zerodhatech.models.User;

/**
 * Mock implementation of KiteGateway for local integration testing.
 * Active when: spring.profiles.active=mock
 *
 * All values are dynamic per call:
 *  - NIFTY spot drifts randomly ±300 on each getLTP call (range 21000–24000)
 *  - Option LTPs are calculated from spot vs strike — ITM options cost more, OTM less
 *  - Execution prices include realistic slippage (±0.3%)
 *  - Margin varies with spot level (~15% of notional, NRML)
 *  - Brokerage varies ±₹5 around ₹20 per leg
 *
 * MOCK-* order IDs are written to DB so every trade looks distinct in the equity curve.
 */
@Service
@Profile("mock")
public class MockKiteGateway implements KiteGateway {

    private static final Logger log = LoggerFactory.getLogger(MockKiteGateway.class);

    // Strike parser: matches the last 4–6 digits followed by CE or PE at end of string
    // e.g. "NIFTY24D1922500CE" → group(1)=22500, group(2)=CE
    private static final Pattern STRIKE_PATTERN = Pattern.compile("(\\d{4,6})(CE|PE)$");

    private static final AtomicLong orderCounter = new AtomicLong(1000);
    private final Random random = new Random();

    // Tracks the execution price stored at order placement time, keyed by mock order ID.
    // getOrderTrades returns this so executed prices match what was "paid".
    private final ConcurrentHashMap<String, Double> executionPrices = new ConcurrentHashMap<>();

    // Current simulated NIFTY spot — drifts per getLTP call
    private volatile double niftySpot = 22500.0;

    // -----------------------------------------------------------------------
    // LTP  — dynamic, spot-driven option pricing
    // -----------------------------------------------------------------------

    @Override
    public Map<String, LTPQuote> getLTP(String[] instruments) {
        // Drift the spot ±150 on each fetch (simulates intraday price movement)
        niftySpot = Math.max(21000, Math.min(24000, niftySpot + (random.nextDouble() * 300 - 150)));
        double spot = Math.round(niftySpot * 100.0) / 100.0;

        Map<String, LTPQuote> result = new HashMap<>();
        for (String ins : instruments) {
            double price = calcOptionLTP(ins, spot);
            LTPQuote quote = new LTPQuote();
            quote.lastPrice = price;
            result.put(ins, quote);
            log.info("[MOCK] getLTP: {} | spot={} → lastPrice={}", ins, spot, price);
        }
        return result;
    }

    /**
     * Rough option pricing:
     *  - Intrinsic: max(0, spot-strike) for CE, max(0, strike-spot) for PE
     *  - Time value: 60–130, highest at ATM and decays exponentially as OTM deepens
     *  - Total: intrinsic + timeValue * e^(-|spot-strike| / 400)
     */
    private double calcOptionLTP(String instrument, double spot) {
        Matcher m = STRIKE_PATTERN.matcher(instrument);
        if (!m.find()) {
            // Unrecognised format — return a plausible random premium
            return Math.round((80 + random.nextDouble() * 80) * 100.0) / 100.0;
        }
        double strike = Double.parseDouble(m.group(1));
        boolean isCE = CE.equals(m.group(2));

        double intrinsic = isCE ? Math.max(0, spot - strike) : Math.max(0, strike - spot);
        double timeValue = 60 + random.nextDouble() * 70;  // 60–130
        double atmDecay = Math.exp(-Math.abs(spot - strike) / 400.0);
        double price = intrinsic + timeValue * atmDecay;
        // Keep price realistic — options rarely trade below ₹5 and rarely above ₹1000 for weekly NIFTY
        price = Math.max(5.0, Math.min(1000.0, price));
        return Math.round(price * 100.0) / 100.0;
    }

    // -----------------------------------------------------------------------
    // Order placement — dynamic IDs + slippage-adjusted execution prices
    // -----------------------------------------------------------------------

    @Override
    public OrderResponse placeOrder(OrderParams params, String variety) {
        String orderId = "MOCK-" + orderCounter.getAndIncrement();
        double execPrice = applySlippage(params.price, params.transactionType);
        executionPrices.put(orderId, execPrice);
        log.info("[MOCK] placeOrder: symbol={} type={} qty={} price={} execPrice={} → orderId={}",
                params.tradingsymbol, params.transactionType, params.quantity,
                params.price, execPrice, orderId);
        OrderResponse response = new OrderResponse();
        response.orderId = orderId;
        return response;
    }

    @Override
    public List<BulkOrderResponse> placeAutoSliceOrder(OrderParams params, String variety) {
        String orderId = "MOCK-" + orderCounter.getAndIncrement();
        double execPrice = applySlippage(params.price, params.transactionType);
        executionPrices.put(orderId, execPrice);
        log.info("[MOCK] placeAutoSliceOrder: symbol={} type={} qty={} price={} execPrice={} → orderId={}",
                params.tradingsymbol, params.transactionType, params.quantity,
                params.price, execPrice, orderId);
        BulkOrderResponse response = new BulkOrderResponse();
        response.orderId = orderId;
        return List.of(response);
    }

    /**
     * Simulates market order slippage:
     *  - BUY:  pays slightly more than quoted LTP (0.1–0.4% above)
     *  - SELL: receives slightly less than quoted LTP (0.1–0.4% below)
     */
    private double applySlippage(Double basePrice, String transactionType) {
        if (basePrice == null) return 0.0;
        double slippagePct = 0.001 + random.nextDouble() * 0.003;  // 0.1% – 0.4%
        double multiplier = BUY.equals(transactionType) ? (1 + slippagePct) : (1 - slippagePct);
        return Math.round(basePrice * multiplier * 100.0) / 100.0;
    }

    // -----------------------------------------------------------------------
    // Margin calculation — dynamic, spot and quantity driven
    // -----------------------------------------------------------------------

    @Override
    public List<MarginCalculationData> getMarginCalculation(List<MarginCalculationParams> params) {
        List<MarginCalculationData> result = new ArrayList<>();
        double spot = niftySpot;  // snapshot current spot for this calc batch
        for (MarginCalculationParams p : params) {
            double margin = calcMargin(p.quantity, spot);
            double brokerage = Math.round((18 + random.nextDouble() * 7) * 100.0) / 100.0; // ₹18–₹25
            MarginCalculationData data = new MarginCalculationData();
            data.tradingSymbol = p.tradingSymbol;
            data.total = margin;
            data.charges = data.new Charges();
            data.charges.total = brokerage;
            log.info("[MOCK] getMarginCalculation: symbol={} type={} qty={} spot={} → margin={} brokerage={}",
                    p.tradingSymbol, p.transactionType, p.quantity, spot, margin, brokerage);
            result.add(data);
        }
        return result;
    }

    /**
     * Realistic NRML margin estimate:
     *  Approx = spot × lotSize × lots × NRML_MULTIPLIER
     *  NRML for NIFTY options ≈ 15% of notional for short legs, less for long.
     *  Simplified: (spot * qty * 0.15) with ±5% noise.
     */
    private double calcMargin(int quantity, double spot) {
        double notional = spot * quantity;
        double baseMargin = notional * 0.15;
        double noise = 1 + (random.nextDouble() * 0.10 - 0.05); // ±5%
        return Math.round(baseMargin * noise * 100.0) / 100.0;
    }

    // -----------------------------------------------------------------------
    // Order trades — returns the execution price stored at order placement
    // -----------------------------------------------------------------------

    @Override
    public List<com.zerodhatech.models.Trade> getOrderTrades(String singleOrderId) {
        double execPrice = executionPrices.getOrDefault(singleOrderId, 120.50);
        com.zerodhatech.models.Trade trade = new com.zerodhatech.models.Trade();
        trade.orderId = singleOrderId;
        trade.averagePrice = String.valueOf(execPrice);
        trade.quantity = "65";
        log.info("[MOCK] getOrderTrades: orderId={} → averagePrice={}", singleOrderId, execPrice);
        return List.of(trade);
    }

    // -----------------------------------------------------------------------
    // Instruments
    // -----------------------------------------------------------------------

    @Override
    public List<Instrument> getInstruments(String exchange) {
        log.info("[MOCK] getInstruments: exchange={} → returning empty list", exchange);
        return Collections.emptyList();
    }

    // -----------------------------------------------------------------------
    // Auth
    // -----------------------------------------------------------------------

    @Override
    public User generateSession(String requestToken, String apiSecret) {
        log.info("[MOCK] generateSession: requestToken={} → returning fake session", requestToken);
        User user = new User();
        user.accessToken = "mock-access-" + System.currentTimeMillis();
        user.publicToken = "mock-public-" + System.currentTimeMillis();
        user.userId = "MOCK-USER";
        return user;
    }

    @Override
    public String getLoginURL() {
        String url = "http://localhost:8080/mock-login-url";
        log.info("[MOCK] getLoginURL → {}", url);
        return url;
    }

    @Override
    public void invalidateCache() {
        log.debug("[MOCK] invalidateCache called (no-op)");
    }
}
