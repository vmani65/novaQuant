package path.to._40c.nqCore.gateway;

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

import static path.to._40c.nqCore.util.Constants.BUY;
import static path.to._40c.nqCore.util.Constants.CE;
import static path.to._40c.nqCore.util.Constants.SELL;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.zerodhatech.models.BulkOrderResponse;
import com.zerodhatech.models.CombinedMarginData;
import com.zerodhatech.models.ContractNote;
import com.zerodhatech.models.ContractNoteParams;
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

    // Current simulated NIFTY spot — starts at a realistic level, drifts per getLTP call.
    // Spot is seeded near 24500 so ATM options at typical signal strikes price realistically.
    private volatile double niftySpot = 24500.0;

    // -----------------------------------------------------------------------
    // LTP  — dynamic, spot-driven option pricing
    // -----------------------------------------------------------------------

    @Override
    public Map<String, LTPQuote> getLTP(String[] instruments) {
        // Drift ±50 per fetch — simulates a 1-2 min candle on NIFTY
        niftySpot = Math.max(23000, Math.min(26000, niftySpot + (random.nextDouble() * 100 - 50)));
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
     * Realistic weekly NIFTY option pricing:
     *  - Intrinsic:  max(0, spot-strike) for CE; max(0, strike-spot) for PE
     *  - Time value: 80–130 pts at ATM (models 1-2 day weekly expiry with ~16% IV)
     *  - ATM decay:  exp(-|spot-strike| / 200) — OTM options lose value faster than
     *                longer-dated contracts; 50-pt OTM retains ~78%, 100-pt retains ~61%
     */
    private double calcOptionLTP(String instrument, double spot) {
        Matcher m = STRIKE_PATTERN.matcher(instrument);
        if (!m.find()) {
            return Math.round((90 + random.nextDouble() * 40) * 100.0) / 100.0;
        }
        double strike = Double.parseDouble(m.group(1));
        boolean isCE = CE.equals(m.group(2));

        double intrinsic  = isCE ? Math.max(0, spot - strike) : Math.max(0, strike - spot);
        double timeValue  = 80 + random.nextDouble() * 50;          // 80–130 for ATM weekly
        double atmDecay   = Math.exp(-Math.abs(spot - strike) / 200.0);
        double price      = intrinsic + timeValue * atmDecay;
        price = Math.max(5.0, Math.min(800.0, price));
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
    // Virtual contract note + basket margin (mock Kite /charges/orders, /margins/basket)
    // -----------------------------------------------------------------------

    @Override
    public CombinedMarginData getCombinedMarginCalculation(List<MarginCalculationParams> params,
                                                           boolean considerPositions) {
        List<MarginCalculationData> perOrder = getMarginCalculation(params);
        double sumTotal = perOrder.stream().mapToDouble(d -> d.total).sum();
        double finalTotal = Math.round(sumTotal * 0.93 * 100.0) / 100.0;

        MarginCalculationData initial = new MarginCalculationData();
        initial.total = Math.round(sumTotal * 100.0) / 100.0;
        MarginCalculationData finalM  = new MarginCalculationData();
        finalM.total = finalTotal;

        CombinedMarginData combined = new CombinedMarginData();
        combined.initialMargin = initial;
        combined.finalMargin   = finalM;
        combined.orders        = perOrder;
        log.info("[MOCK] getCombinedMarginCalculation: legs={} initial={} final={}",
                params.size(), initial.total, finalM.total);
        return combined;
    }

    @Override
    public List<ContractNote> getVirtualContractNote(List<ContractNoteParams> params) {
        List<ContractNote> result = new ArrayList<>();
        for (ContractNoteParams p : params) {
            double turnover    = p.averagePrice * p.quantity;
            double brokerage   = 20.0;
            double stt         = SELL.equals(p.transactionType) ? Math.round(turnover * 0.0005 * 100.0) / 100.0 : 0.0;
            double exch        = Math.round(turnover * 0.000035 * 100.0) / 100.0;
            double sebi        = Math.round(turnover * 0.0000001 * 100.0) / 100.0;
            double gstTotal    = Math.round((brokerage + exch + sebi) * 0.18 * 100.0) / 100.0;
            double total       = Math.round((brokerage + stt + exch + sebi + gstTotal) * 100.0) / 100.0;

            MarginCalculationData parent  = new MarginCalculationData();
            MarginCalculationData.Charges charges = parent.new Charges();
            charges.brokerage              = brokerage;
            charges.transactionTax         = stt;
            charges.transactionTaxType     = "stt";
            charges.exchangeTurnoverCharge = exch;
            charges.SEBITurnoverCharge     = sebi;
            charges.stampDuty              = 0.0;
            MarginCalculationData.GST gst  = parent.new GST();
            gst.total                      = gstTotal;
            charges.gst                    = gst;
            charges.total                  = total;

            ContractNote note      = new ContractNote();
            note.tradingSymbol     = p.tradingSymbol;
            note.transactionType   = p.transactionType;
            note.exchange          = p.exchange;
            note.variety           = p.variety;
            note.product           = p.product;
            note.orderType         = p.orderType;
            note.quantity          = p.quantity;
            note.price             = p.averagePrice;
            note.charges           = charges;
            result.add(note);
            log.info("[MOCK] getVirtualContractNote: {} {} qty={} px={} → total={}",
                    p.tradingSymbol, p.transactionType, p.quantity, p.averagePrice, total);
        }
        return result;
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
    public Map<String, Object> testConnection() {
        log.info("[MOCK] testConnection → OK");
        return Map.of("status", "OK", "message", "[MOCK] Connection healthy.");
    }

    @Override
    public void invalidateCache() {
        log.debug("[MOCK] invalidateCache called (no-op)");
    }
}
