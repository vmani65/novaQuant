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
import com.zerodhatech.models.Depth;
import com.zerodhatech.models.Instrument;
import com.zerodhatech.models.LTPQuote;
import com.zerodhatech.models.MarginCalculationData;
import com.zerodhatech.models.MarginCalculationParams;
import com.zerodhatech.models.MarketDepth;
import com.zerodhatech.models.Order;
import com.zerodhatech.models.OrderParams;
import com.zerodhatech.models.OrderResponse;
import com.zerodhatech.models.Quote;
import com.zerodhatech.models.User;

import static com.zerodhatech.kiteconnect.utils.Constants.ORDER_COMPLETE;

/**
 * Mock KiteGateway for local testing (active when spring.profiles.active=mock).
 * Simulates spot drift, slippage, margin/brokerage, and MOCK-* order IDs so flows
 * exercise end-to-end without hitting Zerodha.
 */
@Service
@Profile("mock")
public class MockKiteGateway implements KiteGateway {

    private static final Logger log = LoggerFactory.getLogger(MockKiteGateway.class);

    /** Parses trailing strike+option-type from instrument symbols: e.g. NIFTY24D1922500CE → 22500/CE. */
    private static final Pattern STRIKE_PATTERN = Pattern.compile("(\\d{4,6})(CE|PE)$");

    private static final AtomicLong orderCounter = new AtomicLong(1000);
    private final Random random = new Random();

    /** Execution price recorded per mock order ID; replayed by getOrderTrades for consistency. */
    private final ConcurrentHashMap<String, Double> executionPrices = new ConcurrentHashMap<>();

    /** Mock WS counterpart — used by placeOrder to inject synthetic fill events that
     *  drive PositionUtil.placeGraduatedLimit's D₂ WS-await path. */
    private final MockKiteOrderStream orderStream;

    public MockKiteGateway(MockKiteOrderStream orderStream) {
        this.orderStream = orderStream;
    }

    /** Simulated NIFTY spot — drifts ±50 per getLTP call, clamped to [23000, 26000]. */
    private volatile double niftySpot = 24500.0;

    @Override
    public Map<String, LTPQuote> getLTP(String[] instruments) {
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
     * Approximates weekly NIFTY option price as intrinsic + time-value × ATM-decay.
     * Time value 80–130 pts at ATM (≈16% IV, 1-2d expiry); decay = exp(-|spot-strike|/200)
     * so 50-pt OTM retains ~78%, 100-pt OTM ~61%. Clamped to [5, 800].
     */
    private double calcOptionLTP(String instrument, double spot) {
        Matcher m = STRIKE_PATTERN.matcher(instrument);
        if (!m.find()) {
            return Math.round((90 + random.nextDouble() * 40) * 100.0) / 100.0;
        }
        double strike = Double.parseDouble(m.group(1));
        boolean isCE = CE.equals(m.group(2));

        double intrinsic  = isCE ? Math.max(0, spot - strike) : Math.max(0, strike - spot);
        double timeValue  = 80 + random.nextDouble() * 50;
        double atmDecay   = Math.exp(-Math.abs(spot - strike) / 200.0);
        double price      = intrinsic + timeValue * atmDecay;
        price = Math.max(5.0, Math.min(800.0, price));
        return Math.round(price * 100.0) / 100.0;
    }

    @Override
    public List<Order> getOrderHistory(String orderId) {
        Order o = new Order();
        o.orderId = orderId;
        o.status = ORDER_COMPLETE;
        Double execPrice = executionPrices.get(orderId);
        o.averagePrice = execPrice != null ? String.valueOf(execPrice) : "0";
        o.filledQuantity = "65";
        o.quantity = "65";
        o.pendingQuantity = "0";
        log.info("[MOCK] getOrderHistory: orderId={} → status={} filled={}/{}",
                orderId, o.status, o.filledQuantity, o.quantity);
        return List.of(o);
    }

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
        // D₂: inject a synthetic terminal-fill event so the WS-await path completes
        // within ~50ms, mirroring real Kite WS behavior.
        orderStream.scheduleSyntheticFill(orderId, params.tradingsymbol,
                params.transactionType, params.quantity, execPrice);
        return response;
    }

    @Override
    public Map<String, Quote> getQuote(String[] instruments) {
        niftySpot = Math.max(23000, Math.min(26000, niftySpot + (random.nextDouble() * 100 - 50)));
        double spot = Math.round(niftySpot * 100.0) / 100.0;
        Map<String, Quote> result = new HashMap<>();
        for (String ins : instruments) {
            double mid = calcOptionLTP(ins, spot);
            double halfSpread = Math.max(0.5, mid * 0.005);
            Quote q = new Quote();
            q.lastPrice = mid;
            q.depth = new MarketDepth();
            q.depth.buy = new ArrayList<>();
            q.depth.sell = new ArrayList<>();
            Depth bid = new Depth();
            bid.setPrice(Math.round((mid - halfSpread) * 20.0) / 20.0);
            bid.setQuantity(1000);
            Depth ask = new Depth();
            ask.setPrice(Math.round((mid + halfSpread) * 20.0) / 20.0);
            ask.setQuantity(1000);
            q.depth.buy.add(bid);
            q.depth.sell.add(ask);
            result.put(ins, q);
            log.info("[MOCK] getQuote: {} | mid={} bid={} ask={}", ins, mid, bid.getPrice(), ask.getPrice());
        }
        return result;
    }

    @Override
    public boolean modifyOrder(String orderId, double newPrice, int newQty, String variety) {
        executionPrices.put(orderId, newPrice);
        log.info("[MOCK] modifyOrder: orderId={} newPrice={} newQty={}", orderId, newPrice, newQty);
        return true;
    }

    @Override
    public boolean cancelOrder(String orderId, String variety) {
        log.info("[MOCK] cancelOrder: orderId={}", orderId);
        return true;
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

    /** Market-order slippage: BUYs pay 0.1–0.4% above LTP, SELLs receive 0.1–0.4% below. */
    private double applySlippage(Double basePrice, String side) {
        if (basePrice == null) return 0.0;
        double slippagePct = 0.001 + random.nextDouble() * 0.003;
        double multiplier = BUY.equals(side) ? (1 + slippagePct) : (1 - slippagePct);
        return Math.round(basePrice * multiplier * 100.0) / 100.0;
    }

    @Override
    public List<MarginCalculationData> getMarginCalculation(List<MarginCalculationParams> params) {
        List<MarginCalculationData> result = new ArrayList<>();
        double spot = niftySpot;
        for (MarginCalculationParams p : params) {
            double margin = calcMargin(p.quantity, spot);
            double brokerage = Math.round((18 + random.nextDouble() * 7) * 100.0) / 100.0;
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

    /** Approximates NRML margin as 15% of notional (spot × qty) with ±5% noise. */
    private double calcMargin(int quantity, double spot) {
        double notional = spot * quantity;
        double baseMargin = notional * 0.15;
        double noise = 1 + (random.nextDouble() * 0.10 - 0.05);
        return Math.round(baseMargin * noise * 100.0) / 100.0;
    }

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

    /**
     * Synthetic per-order charges matching Zerodha's F&O formula: brokerage ₹20 flat,
     * STT 0.05% of turnover on sells of options, exchange/SEBI on turnover, GST 18% on
     * (brokerage+exch+SEBI). Calibrated to live API responses (BUY ~₹27, SELL ~₹42).
     */
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
            note.transactionType = p.transactionType;
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

    @Override
    public List<Instrument> getInstruments(String exchange) {
        log.info("[MOCK] getInstruments: exchange={} → returning empty list", exchange);
        return Collections.emptyList();
    }

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
