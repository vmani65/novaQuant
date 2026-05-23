package path.to._40c.nqCore.util;

import static path.to._40c.nqCore.util.Constants.BUY;
import static path.to._40c.nqCore.util.Constants.CLOSED;
import static path.to._40c.nqCore.util.Constants.DATE_FORMAT;
import static path.to._40c.nqCore.util.Constants.LIVE;
import static path.to._40c.nqCore.util.Constants.MAX_SIZE_PER_ORDER;
import static path.to._40c.nqCore.util.Constants.NFO;
import static path.to._40c.nqCore.util.Constants.NIFTY;
import static path.to._40c.nqCore.util.Constants.SELL;
import static path.to._40c.nqCore.util.Constants.ZONE_ID;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zerodhatech.kiteconnect.utils.Constants;
import com.zerodhatech.models.BulkOrderResponse;
import com.zerodhatech.models.ContractNote;
import com.zerodhatech.models.ContractNoteParams;
import com.zerodhatech.models.LTPQuote;
import com.zerodhatech.models.MarginCalculationData;
import com.zerodhatech.models.MarginCalculationParams;
import com.zerodhatech.models.OrderParams;
import com.zerodhatech.models.OrderResponse;

import jakarta.persistence.EntityManager;
import path.to._40c.nqCore.entity.Trade;
import path.to._40c.nqCore.entity.WeeklyOrderBook;
import path.to._40c.nqCore.entity.WeeklyOrderFill;
import path.to._40c.nqCore.gateway.KiteGateway;
import path.to._40c.nqCore.repo.TradeRepository;

@Service
public class TradeUtil {

	private static final Logger log = LoggerFactory.getLogger(TradeUtil.class);

    private final KiteGateway kiteGateway;
    private final TradeRepository tradeRepository;
    private final EntityManager entityManager;

    public TradeUtil(KiteGateway kiteGateway, TradeRepository tradeRepository, EntityManager entityManager) {
        this.kiteGateway = kiteGateway;
        this.tradeRepository = tradeRepository;
        this.entityManager = entityManager;
    }

    /** Capture fill prices for legs that don't already have them. Idempotent. */
    public void setTradeExecutedPrices(Trade t) {
        if (t != null) {
            t.getWeeklyOrderBook().forEach(w -> {
                if (isPriceAlreadyCaptured(w)) return;
                String orderId = LIVE.equals(w.getTradeStatus()) ? w.getTradeOpenOrderId() : w.getTradeCloseOrderId();
                log.info("Fetching executed prices for trade: {} OrderID: {}", t, orderId);
                List<com.zerodhatech.models.Trade> trades = fetchWithRetry(orderId, "executed prices");
                if (trades != null && !trades.isEmpty()) {
                    double avgPrice = weightedAvgFillPrice(trades);
                    log.info("Average Price (weighted across {} fills): {}", trades.size(), avgPrice);
                    if (BUY.equals(w.getTransactionType())) {
                        if (LIVE.equals(w.getTradeStatus()))   w.setBoughtPrice(avgPrice);
                        if (CLOSED.equals(w.getTradeStatus())) w.setSoldPrice(avgPrice);
                    }
                    if (SELL.equals(w.getTransactionType())) {
                        if (LIVE.equals(w.getTradeStatus()))   w.setSoldPrice(avgPrice);
                        if (CLOSED.equals(w.getTradeStatus())) w.setBoughtPrice(avgPrice);
                    }
                }
            });
        }
    }

    /** Σ(qty × price) / Σ(qty) across all fills. Handles multi-slice auto-orders correctly. */
    public static double weightedAvgFillPrice(List<com.zerodhatech.models.Trade> trades) {
        if (trades == null || trades.isEmpty()) return 0.0;
        double totalQty = 0.0;
        double totalValue = 0.0;
        for (com.zerodhatech.models.Trade tr : trades) {
            if (tr == null || tr.averagePrice == null || tr.quantity == null) continue;
            try {
                double q = Double.parseDouble(tr.quantity);
                double p = Double.parseDouble(tr.averagePrice);
                totalQty   += q;
                totalValue += q * p;
            } catch (NumberFormatException e) {
                log.warn("weightedAvgFillPrice: malformed trade record (qty={} price={}) — skipping",
                        tr.quantity, tr.averagePrice);
            }
        }
        return totalQty > 0.0 ? Math.round((totalValue / totalQty) * 100.0) / 100.0 : 0.0;
    }

    /** True if the price side relevant to this leg's status is already populated. */
    private boolean isPriceAlreadyCaptured(WeeklyOrderBook w) {
        boolean isBuy  = BUY.equals(w.getTransactionType());
        boolean isLive = LIVE.equals(w.getTradeStatus());
        Double target = isLive
                ? (isBuy ? w.getBoughtPrice() : w.getSoldPrice())
                : (isBuy ? w.getSoldPrice()   : w.getBoughtPrice());
        return target != null;
    }

    public void setTradeExecPricesForRollOver(Trade t, boolean rollOverClose, boolean rollOverOpen) {
        if (t != null) {
            t.getWeeklyOrderBook().stream()
                .filter(w -> rollOverClose ? CLOSED.equals(w.getTradeStatus()) : LIVE.equals(w.getTradeStatus()))
                .forEach(w -> {
                    String orderId = rollOverOpen ? w.getTradeOpenOrderId() : w.getTradeCloseOrderId();
                    log.info("Fetching executed prices for rollover trade: {} OrderID: {}", t, orderId);
                    List<com.zerodhatech.models.Trade> trades = fetchWithRetry(orderId, "rollover executed prices");
                    log.info("Trade Details for OrderID: {} is {}", orderId, (trades != null && !trades.isEmpty()) ? trades : "");
                    if (trades != null && !trades.isEmpty()) {
                        double avgPrice = weightedAvgFillPrice(trades);
                        log.info("Average Price (weighted across {} fills): {}", trades.size(), avgPrice);
                        if (BUY.equals(w.getTransactionType())) {
                            if (rollOverOpen) {
                                w.setBoughtPrice(Math.round(((w.getBoughtPrice() != null ? w.getBoughtPrice() : 0.0) + avgPrice) * 100.0) / 100.0);
                            }
                            if (rollOverClose) {
                                w.setSoldPrice(Math.round(((w.getSoldPrice() != null ? w.getSoldPrice() : 0.0) + avgPrice) * 100.0) / 100.0);
                            }
                        }
                        if (SELL.equals(w.getTransactionType())) {
                            if (rollOverOpen) {
                                w.setSoldPrice(Math.round(((w.getSoldPrice() != null ? w.getSoldPrice() : 0.0) + avgPrice) * 100.0) / 100.0);
                            }
                            if (rollOverClose) {
                                w.setBoughtPrice(Math.round(((w.getBoughtPrice() != null ? w.getBoughtPrice() : 0.0) + avgPrice) * 100.0) / 100.0);
                            }
                        }
                    }
                });
        }
    }

    /**
     * Set margin + open/close brokerage estimate for LIVE legs via two parallel /margins/orders calls
     * (real-txn and opposite-txn baskets). Uniform-direction baskets are avoided — Kite collapses them
     * as straddles and redistributes margin. Runs on ForkJoinPool to avoid postTradeExecutor deadlock.
     */
    public void calcMarginAndBrokerage(Trade trade) {
        if (trade == null) return;

        List<MarginCalculationParams> realParams     = new ArrayList<>();
        List<MarginCalculationParams> oppositeParams = new ArrayList<>();
        trade.getWeeklyOrderBook().stream()
                .filter(c -> LIVE.equals(c.getTradeStatus()))
                .forEach(c -> {
                    realParams.add(buildParam(c, c.getTransactionType()));
                    oppositeParams.add(buildParam(c, opposite(c.getTransactionType())));
                });
        if (realParams.isEmpty()) return;

        var realFuture     = CompletableFuture.supplyAsync(() -> getMarginCalculation(realParams));
        var oppositeFuture = CompletableFuture.supplyAsync(() -> getMarginCalculation(oppositeParams));
        List<MarginCalculationData> realMargins;
        List<MarginCalculationData> oppositeMargins;
        try {
            realMargins     = realFuture.get();
            oppositeMargins = oppositeFuture.get();
        } catch (Exception e) {
            log.error("Exception during parallel margin calculation", e);
            return;
        }

        realMargins.forEach(m -> trade.getWeeklyOrderBook().stream()
                .filter(w -> m.tradingSymbol.equals(w.getMarginCalcSymbol()) && LIVE.equals(w.getTradeStatus()))
                .findFirst()
                .ifPresent(w -> {
                    w.setMarginToTrade(ComputeUtil.rnd(m.total));
                    w.setTradeOpenBrokerage(ComputeUtil.rnd(totalChargesForSliceCount(m.charges, sliceCount(w, true))));
                }));
        oppositeMargins.forEach(m -> trade.getWeeklyOrderBook().stream()
                .filter(w -> m.tradingSymbol.equals(w.getMarginCalcSymbol()) && LIVE.equals(w.getTradeStatus()))
                .findFirst()
                .ifPresent(w -> w.setTradeCloseBrokerage(ComputeUtil.rnd(totalChargesForSliceCount(m.charges, sliceCount(w, false))))));
    }

    private MarginCalculationParams buildParam(WeeklyOrderBook c, String transactionType) {
        var p = initCalcParam(c.getQuantity());
        p.tradingSymbol   = c.getMarginCalcSymbol();
        p.transactionType = transactionType;
        return p;
    }

    private static String opposite(String txn) {
        return BUY.equals(txn) ? SELL : BUY;
    }

    /** Slice count from stored comma-separated orderIds, or estimated from qty if not yet placed. */
    static int sliceCount(WeeklyOrderBook w, boolean openSide) {
        String orderId = openSide ? w.getTradeOpenOrderId() : w.getTradeCloseOrderId();
        if (orderId != null && !orderId.isBlank()) {
            return orderId.split("\\s*,\\s*").length;
        }
        Integer qty = w.getQuantity();
        if (qty == null || qty <= MAX_SIZE_PER_ORDER) return 1;
        return (int) Math.ceil((double) qty / MAX_SIZE_PER_ORDER);
    }

    /**
     * Total charges adjusted for auto-slicing: brokerage scales with order count (Zerodha charges
     * per-order), turnover-based charges (STT/exch/SEBI) don't, GST is recomputed.
     */
    static double totalChargesForSliceCount(MarginCalculationData.Charges c, int sliceCount) {
        if (c == null) return 0.0;
        if (sliceCount <= 1) return c.total;
        double brokerage = c.brokerage * sliceCount;
        double exch      = c.exchangeTurnoverCharge;
        double sebi      = c.SEBITurnoverCharge;
        double gst       = (brokerage + exch + sebi) * 0.18;
        return brokerage + c.transactionTax + exch + sebi + c.stampDuty + gst;
    }

    /**
     * Overwrite brokerage estimates with EOD-exact charges from /charges/orders.
     * LIVE leg → tradeOpenBrokerage, CLOSED leg → tradeCloseBrokerage. Idempotent.
     */
    public void applyActualCharges(Trade trade) {
        if (trade == null) return;
        List<WeeklyOrderBook>   legs   = new ArrayList<>();
        List<ContractNoteParams> params = new ArrayList<>();
        trade.getWeeklyOrderBook().forEach(w -> {
            boolean isLive = LIVE.equals(w.getTradeStatus());
            boolean isBuy  = BUY.equals(w.getTransactionType());
            String  orderId = isLive ? w.getTradeOpenOrderId() : w.getTradeCloseOrderId();
            Double  avgPrice = isLive
                    ? (isBuy ? w.getBoughtPrice() : w.getSoldPrice())
                    : (isBuy ? w.getSoldPrice()   : w.getBoughtPrice());
            if (orderId == null || orderId.isBlank() || avgPrice == null || avgPrice <= 0.0) return;
            ContractNoteParams p = new ContractNoteParams();
            p.orderID         = orderId;
            p.tradingSymbol   = w.getMarginCalcSymbol();
            p.exchange        = Constants.EXCHANGE_NFO;
            p.transactionType = isLive
                    ? (isBuy ? Constants.TRANSACTION_TYPE_BUY : Constants.TRANSACTION_TYPE_SELL)
                    : (isBuy ? Constants.TRANSACTION_TYPE_SELL : Constants.TRANSACTION_TYPE_BUY);
            p.variety      = Constants.VARIETY_REGULAR;
            p.product      = Constants.PRODUCT_NRML;
            p.orderType    = Constants.ORDER_TYPE_MARKET;
            p.quantity     = w.getQuantity();
            p.averagePrice = avgPrice;
            params.add(p);
            legs.add(w);
        });
        if (params.isEmpty()) return;

        List<ContractNote> notes = kiteGateway.getVirtualContractNote(params);
        if (notes == null || notes.size() != legs.size()) {
            log.warn("applyActualCharges: response size mismatch (expected {} got {}) — skipping",
                    legs.size(), notes == null ? 0 : notes.size());
            return;
        }
        for (int i = 0; i < legs.size(); i++) {
            WeeklyOrderBook w = legs.get(i);
            ContractNote    n = notes.get(i);
            if (n == null || n.charges == null) continue;
            boolean isLive = LIVE.equals(w.getTradeStatus());
            Double exact = ComputeUtil.rnd(totalChargesForSliceCount(n.charges, sliceCount(w, isLive)));
            if (isLive) w.setTradeOpenBrokerage(exact);
            else        w.setTradeCloseBrokerage(exact);
        }
    }

    /** Running max of Σ(LIVE legs.marginToTrade) across the trade's lifetime. */
    public void calcPeakMargin(Trade trade) {
        if (trade == null || trade.getWeeklyOrderBook() == null) return;
        double currentSegmentMargin = trade.getWeeklyOrderBook().stream()
                .filter(w -> LIVE.equals(w.getTradeStatus()) && w.getMarginToTrade() != null)
                .mapToDouble(WeeklyOrderBook::getMarginToTrade)
                .sum();
        if (currentSegmentMargin <= 0.0) return;
        double currentPeak = trade.getPeakMargin() != null ? trade.getPeakMargin() : 0.0;
        if (currentSegmentMargin > currentPeak) {
            trade.setPeakMargin(ComputeUtil.rnd(currentSegmentMargin));
        }
    }

    /**
     * Persist per-slice fill data into WEEKLY_ORDER_FILL for slippage analytics.
     *
     * For each leg with a populated tradeOpenOrderId / tradeCloseOrderId, parses the
     * comma-separated slice orderIDs, fetches per-slice fills from Kite, weighted-averages
     * each slice's fills, and INSERTs (open) or UPDATEs (close) one row per slice.
     *
     * Idempotent — re-runs are no-ops because we check existing fill rows before writing.
     * Must run inside a transaction (caller marks @Transactional) because it lazy-loads
     * the fills collection per leg.
     */
    public void captureSliceFills(Trade trade) {
        if (trade == null || trade.getWeeklyOrderBook() == null) return;
        trade.getWeeklyOrderBook().forEach(this::captureSliceFillsForLeg);
    }

    private void captureSliceFillsForLeg(WeeklyOrderBook w) {
        if (w == null) return;
        String openOrderIds  = w.getTradeOpenOrderId();
        String closeOrderIds = w.getTradeCloseOrderId();
        boolean hasOpen  = openOrderIds  != null && !openOrderIds.isBlank();
        boolean hasClose = closeOrderIds != null && !closeOrderIds.isBlank();
        if (!hasOpen && !hasClose) return;

        boolean legIsBuy = BUY.equals(w.getTransactionType());

        if (hasOpen) {
            boolean openSideIsBuy = legIsBuy;
            boolean alreadyCaptured = w.getFills().stream()
                    .anyMatch(f -> openSideIsBuy ? f.getBuyFillPrice() != null : f.getSellFillPrice() != null);
            if (!alreadyCaptured) captureSliceFillsForSide(w, openSideIsBuy, openOrderIds);
        }
        if (hasClose) {
            boolean closeSideIsBuy = !legIsBuy;
            boolean alreadyCaptured = w.getFills().stream()
                    .anyMatch(f -> closeSideIsBuy ? f.getBuyFillPrice() != null : f.getSellFillPrice() != null);
            if (!alreadyCaptured) captureSliceFillsForSide(w, closeSideIsBuy, closeOrderIds);
        }
    }

    private void captureSliceFillsForSide(WeeklyOrderBook w, boolean buySide, String orderIdsStr) {
        String[] sliceIds = orderIdsStr.split("\\s*,\\s*");
        if (sliceIds.length == 0) return;

        List<com.zerodhatech.models.Trade> allFills = fetchWithRetry(orderIdsStr,
                (buySide ? "BUY" : "SELL") + " slice fills");
        if (allFills == null || allFills.isEmpty()) {
            log.warn("captureSliceFills: no fills returned for {} side of leg {} after retry (orderIds={})",
                    buySide ? "BUY" : "SELL", w.getMarginCalcSymbol(), orderIdsStr);
            return;
        }

        Map<String, List<com.zerodhatech.models.Trade>> bySliceId = allFills.stream()
                .filter(t -> t != null && t.orderId != null)
                .collect(Collectors.groupingBy(t -> t.orderId));

        List<SliceFillData> slices = new ArrayList<>();
        for (String sliceId : sliceIds) {
            String id = sliceId.trim();
            if (id.isEmpty()) continue;
            List<com.zerodhatech.models.Trade> sliceFills = bySliceId.getOrDefault(id, Collections.emptyList());
            if (sliceFills.isEmpty()) continue;
            SliceFillData computed = computeSliceFill(id, sliceFills);
            if (computed != null) slices.add(computed);
        }
        if (slices.isEmpty()) return;

        slices.sort(Comparator.comparing(SliceFillData::time,
                Comparator.nullsLast(Comparator.naturalOrder())));

        Map<Integer, WeeklyOrderFill> byIndex = w.getFills().stream()
                .collect(Collectors.toMap(WeeklyOrderFill::getSliceIndex, x -> x, (a, b) -> a));

        for (int i = 0; i < slices.size(); i++) {
            SliceFillData s = slices.get(i);
            WeeklyOrderFill row = byIndex.get(i);
            if (row == null) {
                row = new WeeklyOrderFill();
                row.setWeeklyOrderBook(w);
                row.setSliceIndex(i);
                w.getFills().add(row);
                byIndex.put(i, row);
            }
            String timeStr = formatFillTime(s.time());
            if (buySide) {
                row.setBuySliceQty(s.qty());
                row.setBuySliceOrderId(s.orderId());
                row.setBuyFillPrice(s.price());
                row.setBuyFillTime(timeStr);
            } else {
                row.setSellSliceQty(s.qty());
                row.setSellSliceOrderId(s.orderId());
                row.setSellFillPrice(s.price());
                row.setSellFillTime(timeStr);
            }
        }
    }

    private static SliceFillData computeSliceFill(String sliceOrderId,
                                                  List<com.zerodhatech.models.Trade> sliceFills) {
        double totalQty = 0.0;
        double totalValue = 0.0;
        java.util.Date earliestTime = null;
        for (com.zerodhatech.models.Trade tr : sliceFills) {
            if (tr == null || tr.averagePrice == null || tr.quantity == null) continue;
            try {
                double q = Double.parseDouble(tr.quantity);
                double p = Double.parseDouble(tr.averagePrice);
                totalQty += q;
                totalValue += q * p;
                if (tr.fillTimestamp != null &&
                        (earliestTime == null || tr.fillTimestamp.before(earliestTime))) {
                    earliestTime = tr.fillTimestamp;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        if (totalQty <= 0.0) return null;
        return new SliceFillData(
                sliceOrderId,
                (int) totalQty,
                Math.round((totalValue / totalQty) * 100.0) / 100.0,
                earliestTime != null ? earliestTime.toInstant() : null);
    }

    private static String formatFillTime(Instant t) {
        if (t == null) return null;
        return LocalDateTime.ofInstant(t, ZoneId.of(ZONE_ID))
                .format(DateTimeFormatter.ofPattern(DATE_FORMAT));
    }

    private record SliceFillData(String orderId, int qty, double price, Instant time) {}

    public MarginCalculationParams initCalcParam(int quantity) {
    	var params = new MarginCalculationParams();
    	params.exchange = Constants.EXCHANGE_NFO;
    	params.variety = Constants.VARIETY_REGULAR;
    	params.product = Constants.PRODUCT_NRML;
    	params.orderType = Constants.ORDER_TYPE_MARKET;
    	params.quantity = quantity;
    	return params;
    }

    public Map<String, LTPQuote> getLTP(String[] ins) {
        return kiteGateway.getLTP(ins);
    }

    public List<MarginCalculationData> getMarginCalculation(List<MarginCalculationParams> params) {
        return kiteGateway.getMarginCalculation(params);
    }

    /**
     * Fetches executed trades for one or more comma-separated order IDs.
     * Splits the ID string (auto-slice orders produce multiple IDs), calls gateway
     * once per single ID, and aggregates results.
     */
    public List<com.zerodhatech.models.Trade> getOrderTrades(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            log.warn("getOrderTrades called with null/blank orderId — skipping");
            return new ArrayList<>();
        }
        log.info("Fetching trades for orderId: {}", orderId);
        List<com.zerodhatech.models.Trade> allTrades = new ArrayList<>();
        Arrays.stream(orderId.split("\\s*,\\s*"))
              .filter(id -> id != null && !id.trim().isEmpty())
              .forEach(id -> {
                  List<com.zerodhatech.models.Trade> orderTrades = kiteGateway.getOrderTrades(id);
                  allTrades.addAll(orderTrades);
                  log.debug("Fetched {} trades for orderId: {}", orderTrades.size(), id);
              });
        log.info("Total trades fetched: {}", allTrades.size());
        allTrades.forEach(trade -> log.info("Trade[tradeId={}, orderId={}, symbol={}, type={}, qty={}, price={}, fillTime={}]",
                trade.tradeId, trade.orderId, trade.tradingSymbol, trade.transactionType,
                trade.quantity, trade.averagePrice, trade.fillTimestamp));
        return allTrades;
    }

    public OrderResponse placeOrder(String ins, Double price, String transactionType, int quantity) {
        OrderParams orderParams = buildOrderParams();
        orderParams.transactionType = transactionType;
        orderParams.tradingsymbol = ins;
        orderParams.quantity = quantity;
        orderParams.price = price;
        OrderResponse order = kiteGateway.placeOrder(orderParams, Constants.VARIETY_REGULAR);
        if (order != null) log.info("Order placed: orderId={}", order.orderId);
        return order;
    }

    public List<BulkOrderResponse> placeAutoSliceOrder(String ins, Double price, String transactionType, int quantity) {
        OrderParams orderParams = buildOrderParams();
        orderParams.transactionType = transactionType;
        orderParams.tradingsymbol = ins;
        orderParams.quantity = quantity;
        orderParams.price = price;
        List<BulkOrderResponse> orders = kiteGateway.placeAutoSliceOrder(orderParams, Constants.VARIETY_REGULAR);
        orders.forEach(o -> {
            if (o.orderId != null) log.info("BulkOrder placed orderId: {}", o.orderId);
            else log.error("BulkOrder error — code: {}, message: {}", o.bulkOrderError.code, o.bulkOrderError.message);
        });
        return orders;
    }

    public List<String> getNiftyInstruments() {
        return kiteGateway.getInstruments(NFO).stream()
            .map(i -> i.tradingsymbol)
            .filter(symbol -> symbol.contains(NIFTY))
            .filter(symbol -> !symbol.contains("MIDCPNIFTY"))
            .filter(symbol -> !symbol.contains("BANKNIFTY"))
            .filter(symbol -> !symbol.contains("NIFTYNXT"))
            .filter(symbol -> !symbol.contains("FINNIFTY"))
            .filter(symbol -> !symbol.startsWith("NIFTY27"))
            .filter(symbol -> !symbol.startsWith("NIFTY28"))
            .filter(symbol -> !symbol.startsWith("NIFTY29"))
            .filter(symbol -> !symbol.startsWith("NIFTY30"))
            .toList();
    }

    public static OrderParams buildOrderParams() {
        OrderParams orderParams = new OrderParams();
        orderParams.orderType = Constants.ORDER_TYPE_MARKET;
        orderParams.product = Constants.PRODUCT_NRML;
        orderParams.exchange = Constants.EXCHANGE_NFO;
        orderParams.validity = Constants.VALIDITY_DAY;
        orderParams.marketProtection = 1;
        return orderParams;
    }

    @Transactional
    public Trade findLiveTradesWithLiveOrderBooks() {
        Session session = entityManager.unwrap(Session.class);
        session.enableFilter("liveOrderBooks").setParameter("status", LIVE);
        Trade trades = tradeRepository.findFirstByTradeStatusOrderByIdDesc(LIVE);
        session.disableFilter("liveOrderBooks");
        return trades;
    }

    @Transactional
    public Trade findLiveTradesWithAllOrderBooks() {
        return tradeRepository.findFirstByTradeStatusOrderByIdDesc(LIVE);
    }

    private List<com.zerodhatech.models.Trade> fetchWithRetry(String orderId, String context) {
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            List<com.zerodhatech.models.Trade> trades = getOrderTrades(orderId);
            if (trades != null && !trades.isEmpty()) return trades;
            if (attempt < maxAttempts) {
                log.warn("Empty fills for orderId={} ({}) — attempt {}/{}, retrying in 10s", orderId, context, attempt, maxAttempts);
                sleep();
            } else {
                log.error("Empty fills for orderId={} ({}) after {} attempts — execution price will default to 0", orderId, context, maxAttempts);
            }
        }
        return new ArrayList<>();
    }

    public static void sleep() {
        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Thread interrupted: {}", e.getMessage());
        }
    }
}
