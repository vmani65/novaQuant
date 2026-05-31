package path.to._40c.nqCore.util;

import static com.zerodhatech.kiteconnect.utils.Constants.ORDER_CANCELLED;
import static com.zerodhatech.kiteconnect.utils.Constants.ORDER_COMPLETE;
import static com.zerodhatech.kiteconnect.utils.Constants.ORDER_REJECTED;

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
import com.zerodhatech.models.Order;
import com.zerodhatech.models.OrderParams;
import com.zerodhatech.models.OrderResponse;

import jakarta.persistence.EntityManager;
import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.entity.WeeklyLeg;
import path.to._40c.nqCore.entity.LegFill;
import path.to._40c.nqCore.gateway.KiteGateway;
import path.to._40c.nqCore.repo.PositionRepository;

@Service
public class PositionUtil {

	private static final Logger log = LoggerFactory.getLogger(PositionUtil.class);

    private final KiteGateway kiteGateway;
    private final PositionRepository positionRepository;
    private final EntityManager entityManager;

    public PositionUtil(KiteGateway kiteGateway, PositionRepository positionRepository, EntityManager entityManager) {
        this.kiteGateway = kiteGateway;
        this.positionRepository = positionRepository;
        this.entityManager = entityManager;
    }

    /** Capture fill prices for legs that don't already have them. Idempotent. */
    public void setTradeExecutedPrices(Position t) {
        if (t != null) {
            t.getLegs().forEach(w -> {
                if (isPriceAlreadyCaptured(w)) return;
                String orderId = LIVE.equals(w.getStatus()) ? w.getOpenOrderId() : w.getCloseOrderId();
                log.info("Fetching executed prices for trade: {} OrderID: {}", t, orderId);
                List<com.zerodhatech.models.Trade> trades = fetchWithRetry(orderId, "executed prices");
                if (trades != null && !trades.isEmpty()) {
                    double avgPrice = weightedAvgFillPrice(trades);
                    log.info("Average Price (weighted across {} fills): {}", trades.size(), avgPrice);
                    if (BUY.equals(w.getSide())) {
                        if (LIVE.equals(w.getStatus()))   w.setBuyFillPrice(avgPrice);
                        if (CLOSED.equals(w.getStatus())) w.setSellFillPrice(avgPrice);
                    }
                    if (SELL.equals(w.getSide())) {
                        if (LIVE.equals(w.getStatus()))   w.setSellFillPrice(avgPrice);
                        if (CLOSED.equals(w.getStatus())) w.setBuyFillPrice(avgPrice);
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
    private boolean isPriceAlreadyCaptured(WeeklyLeg w) {
        boolean isBuy  = BUY.equals(w.getSide());
        boolean isLive = LIVE.equals(w.getStatus());
        Double target = isLive
                ? (isBuy ? w.getBuyFillPrice() : w.getSellFillPrice())
                : (isBuy ? w.getSellFillPrice()   : w.getBuyFillPrice());
        return target != null;
    }

    public void setTradeExecPricesForRollOver(Position t, boolean rollOverClose, boolean rollOverOpen) {
        if (t != null) {
            t.getLegs().stream()
                .filter(w -> rollOverClose ? CLOSED.equals(w.getStatus()) : LIVE.equals(w.getStatus()))
                .forEach(w -> {
                    String orderId = rollOverOpen ? w.getOpenOrderId() : w.getCloseOrderId();
                    log.info("Fetching executed prices for rollover trade: {} OrderID: {}", t, orderId);
                    List<com.zerodhatech.models.Trade> trades = fetchWithRetry(orderId, "rollover executed prices");
                    log.info("Position Details for OrderID: {} is {}", orderId, (trades != null && !trades.isEmpty()) ? trades : "");
                    if (trades != null && !trades.isEmpty()) {
                        double avgPrice = weightedAvgFillPrice(trades);
                        log.info("Average Price (weighted across {} fills): {}", trades.size(), avgPrice);
                        if (BUY.equals(w.getSide())) {
                            if (rollOverOpen) {
                                w.setBuyFillPrice(Math.round(((w.getBuyFillPrice() != null ? w.getBuyFillPrice() : 0.0) + avgPrice) * 100.0) / 100.0);
                            }
                            if (rollOverClose) {
                                w.setSellFillPrice(Math.round(((w.getSellFillPrice() != null ? w.getSellFillPrice() : 0.0) + avgPrice) * 100.0) / 100.0);
                            }
                        }
                        if (SELL.equals(w.getSide())) {
                            if (rollOverOpen) {
                                w.setSellFillPrice(Math.round(((w.getSellFillPrice() != null ? w.getSellFillPrice() : 0.0) + avgPrice) * 100.0) / 100.0);
                            }
                            if (rollOverClose) {
                                w.setBuyFillPrice(Math.round(((w.getBuyFillPrice() != null ? w.getBuyFillPrice() : 0.0) + avgPrice) * 100.0) / 100.0);
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
    public void calcMarginAndBrokerage(Position trade) {
        if (trade == null) return;

        List<MarginCalculationParams> realParams     = new ArrayList<>();
        List<MarginCalculationParams> oppositeParams = new ArrayList<>();
        trade.getLegs().stream()
                .filter(c -> LIVE.equals(c.getStatus()))
                .forEach(c -> {
                    realParams.add(buildParam(c, c.getSide()));
                    oppositeParams.add(buildParam(c, opposite(c.getSide())));
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

        realMargins.forEach(m -> trade.getLegs().stream()
                .filter(w -> m.tradingSymbol.equals(w.getInstrument()) && LIVE.equals(w.getStatus()))
                .findFirst()
                .ifPresent(w -> {
                    w.setMarginRequired(ComputeUtil.rnd(m.total));
                    w.setOpenCharges(ComputeUtil.rnd(totalChargesForSliceCount(m.charges, sliceCount(w, true))));
                }));
        oppositeMargins.forEach(m -> trade.getLegs().stream()
                .filter(w -> m.tradingSymbol.equals(w.getInstrument()) && LIVE.equals(w.getStatus()))
                .findFirst()
                .ifPresent(w -> w.setCloseCharges(ComputeUtil.rnd(totalChargesForSliceCount(m.charges, sliceCount(w, false))))));
    }

    private MarginCalculationParams buildParam(WeeklyLeg c, String side) {
        var p = initCalcParam(c.getQuantity());
        p.tradingSymbol   = c.getInstrument();
        p.transactionType = side;
        return p;
    }

    private static String opposite(String txn) {
        return BUY.equals(txn) ? SELL : BUY;
    }

    /** Slice count from stored comma-separated orderIds, or estimated from qty if not yet placed. */
    static int sliceCount(WeeklyLeg w, boolean openSide) {
        String orderId = openSide ? w.getOpenOrderId() : w.getCloseOrderId();
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
     * LIVE leg → openCharges, CLOSED leg → closeCharges. Idempotent.
     */
    public void applyActualCharges(Position trade) {
        if (trade == null) return;
        List<WeeklyLeg>   legs   = new ArrayList<>();
        List<ContractNoteParams> params = new ArrayList<>();
        trade.getLegs().forEach(w -> {
            boolean isLive = LIVE.equals(w.getStatus());
            boolean isBuy  = BUY.equals(w.getSide());
            String  orderId = isLive ? w.getOpenOrderId() : w.getCloseOrderId();
            Double  avgPrice = isLive
                    ? (isBuy ? w.getBuyFillPrice() : w.getSellFillPrice())
                    : (isBuy ? w.getSellFillPrice()   : w.getBuyFillPrice());
            if (orderId == null || orderId.isBlank() || avgPrice == null || avgPrice <= 0.0) return;
            ContractNoteParams p = new ContractNoteParams();
            p.orderID         = orderId;
            p.tradingSymbol   = w.getInstrument();
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
            WeeklyLeg w = legs.get(i);
            ContractNote    n = notes.get(i);
            if (n == null || n.charges == null) continue;
            boolean isLive = LIVE.equals(w.getStatus());
            Double exact = ComputeUtil.rnd(totalChargesForSliceCount(n.charges, sliceCount(w, isLive)));
            if (isLive) w.setOpenCharges(exact);
            else        w.setCloseCharges(exact);
        }
    }

    /** Running max of Σ(LIVE legs.marginRequired) across the trade's lifetime. */
    public void calcPeakMargin(Position trade) {
        if (trade == null || trade.getLegs() == null) return;
        double currentSegmentMargin = trade.getLegs().stream()
                .filter(w -> LIVE.equals(w.getStatus()) && w.getMarginRequired() != null)
                .mapToDouble(WeeklyLeg::getMarginRequired)
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
     * For each leg with a populated openOrderId / closeOrderId, parses the
     * comma-separated slice orderIDs, fetches per-slice fills from Kite, weighted-averages
     * each slice's fills, and INSERTs (open) or UPDATEs (close) one row per slice.
     *
     * Idempotent — re-runs are no-ops because we check existing fill rows before writing.
     * Must run inside a transaction (caller marks @Transactional) because it lazy-loads
     * the fills collection per leg.
     */
    public void captureSliceFills(Position trade) {
        if (trade == null || trade.getLegs() == null) return;
        trade.getLegs().forEach(this::captureSliceFillsForLeg);
    }

    private void captureSliceFillsForLeg(WeeklyLeg w) {
        if (w == null) return;
        String openOrderIds  = w.getOpenOrderId();
        String closeOrderIds = w.getCloseOrderId();
        boolean hasOpen  = openOrderIds  != null && !openOrderIds.isBlank();
        boolean hasClose = closeOrderIds != null && !closeOrderIds.isBlank();
        if (!hasOpen && !hasClose) return;

        boolean legIsBuy = BUY.equals(w.getSide());

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

    private void captureSliceFillsForSide(WeeklyLeg w, boolean buySide, String orderIdsStr) {
        String[] sliceIds = orderIdsStr.split("\\s*,\\s*");
        if (sliceIds.length == 0) return;

        List<com.zerodhatech.models.Trade> allFills = fetchWithRetry(orderIdsStr,
                (buySide ? "BUY" : "SELL") + " slice fills");
        if (allFills == null || allFills.isEmpty()) {
            log.warn("captureSliceFills: no fills returned for {} side of leg {} after retry (orderIds={})",
                    buySide ? "BUY" : "SELL", w.getInstrument(), orderIdsStr);
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

        Map<Integer, LegFill> byIndex = w.getFills().stream()
                .collect(Collectors.toMap(LegFill::getSliceIndex, x -> x, (a, b) -> a));

        for (int i = 0; i < slices.size(); i++) {
            SliceFillData s = slices.get(i);
            LegFill row = byIndex.get(i);
            if (row == null) {
                row = new LegFill();
                row.setWeeklyLeg(w);
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
        allTrades.forEach(trade -> log.info("Position[tradeId={}, orderId={}, symbol={}, type={}, qty={}, price={}, fillTime={}]",
                trade.tradeId, trade.orderId, trade.tradingSymbol, trade.transactionType,
                trade.quantity, trade.averagePrice, trade.fillTimestamp));
        return allTrades;
    }

    public List<BulkOrderResponse> placeAutoSliceOrder(String ins, Double price, String side, int quantity) {
        OrderParams orderParams = buildOrderParams();
        orderParams.transactionType = side;
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

    /**
     * Order pipeline shared by entries and exits.
     * For qty < MAX_SIZE_PER_ORDER: single MARKET + protection=1 + verify via getOrderHistory.
     * For qty ≥ MAX_SIZE_PER_ORDER: Kite broker-side auto-slice (MARKET per slice).
     * contextLabel ("ENTRY"|"EXIT") drives only logging.
     */
    public ExecResult placeAggressiveOrder(String ins, String txn, int qty, String contextLabel) {
        if (qty >= MAX_SIZE_PER_ORDER) {
            log.warn("[{}] {} qty={} >= MAX_SIZE_PER_ORDER — using auto-slice MARKET path", contextLabel, ins, qty);
            return autoSliceFallback(ins, txn, qty, contextLabel);
        }

        OrderParams params = buildAggressiveOrderParams();
        params.orderType        = Constants.ORDER_TYPE_MARKET;
        params.validity         = Constants.VALIDITY_DAY;
        params.transactionType  = txn;
        params.tradingsymbol    = ins;
        params.quantity         = qty;
        params.marketProtection = 1;
        OrderResponse resp = kiteGateway.placeOrder(params, Constants.VARIETY_REGULAR);
        if (resp == null || resp.orderId == null) {
            log.error("[{}] {} placeOrder returned null", contextLabel, ins);
            return new ExecResult("", 0, qty, 0.0, false, "PLACE_FAILED");
        }
        log.info("[{}] {} MARKET protection=1 placed: orderId={}", contextLabel, ins, resp.orderId);

        AttemptResult ar = verifyAttempt(resp.orderId, ins, contextLabel);
        boolean full = ar.filledQty() == qty;
        String term  = full ? ORDER_COMPLETE : (ar.filledQty() > 0 ? "PARTIAL" : (ar.status() != null ? ar.status() : "FAILED"));
        String ids   = ar.filledQty() > 0 ? resp.orderId : "";
        log.info("[{}] {} done | filled={}/{} | avgFill={} | term={} | ids={}",
                contextLabel, ins, ar.filledQty(), qty, ar.avgFillPrice(), term, ids);
        return new ExecResult(ids, ar.filledQty(), qty, ar.avgFillPrice(), full, term);
    }

    /** Fallback for qty >= MAX_SIZE_PER_ORDER — Kite broker-side auto-slice using MARKET orders. */
    private ExecResult autoSliceFallback(String ins, String txn, int qty, String contextLabel) {
        List<BulkOrderResponse> bulk = placeAutoSliceOrder(ins, 0.0, txn, qty);
        if (bulk == null || bulk.isEmpty()) {
            return new ExecResult("", 0, qty, 0.0, false, "FAILED");
        }
        StringBuilder ids = new StringBuilder();
        int totalFilled = 0;
        double weightedSum = 0.0;
        for (BulkOrderResponse b : bulk) {
            if (b.orderId == null) continue;
            AttemptResult ar = verifyAttempt(b.orderId, ins, contextLabel);
            if (ar.filledQty() > 0) {
                appendId(ids, b.orderId);
                totalFilled += ar.filledQty();
                weightedSum += ar.filledQty() * ar.avgFillPrice();
            }
        }
        double avg = totalFilled > 0 ? Math.round((weightedSum / totalFilled) * 100.0) / 100.0 : 0.0;
        boolean full = totalFilled == qty;
        String term = full ? ORDER_COMPLETE : (totalFilled > 0 ? "PARTIAL" : "FAILED");
        log.info("[{}] {} autoslice done | filled={}/{} | avgFill={} | term={} | ids={}",
                contextLabel, ins, totalFilled, qty, avg, term, ids);
        return new ExecResult(ids.toString(), totalFilled, qty, avg, full, term);
    }

    /**
     * Reads getOrderHistory; if status is non-terminal, retries up to 2× with 200ms gaps
     * (MARKET normally settles within ms but Kite's order-state propagation can lag).
     */
    private AttemptResult verifyAttempt(String orderId, String ins, String contextLabel) {
        Order last = null;
        for (int i = 0; i < 3; i++) {
            List<Order> history = kiteGateway.getOrderHistory(orderId);
            if (history != null && !history.isEmpty()) {
                last = history.get(history.size() - 1);
                if (isTerminal(last.status)) break;
            }
            sleepMillis(200);
        }
        if (last == null) {
            log.error("[{}] {} orderId={} getOrderHistory empty after retries", contextLabel, ins, orderId);
            return new AttemptResult(orderId, 0, 0.0, "UNKNOWN");
        }
        int filledQty = parseIntSafe(last.filledQuantity);
        double avgPrice = parseDoubleSafe(last.averagePrice);
        log.info("[{}] {} orderId={} status={} filled={}/{} avgPx={} statusMsg={}",
                contextLabel, ins, orderId, last.status, last.filledQuantity, last.quantity, avgPrice, last.statusMessage);
        return new AttemptResult(orderId, filledQty, avgPrice, last.status);
    }

    private static boolean isTerminal(String status) {
        return ORDER_COMPLETE.equals(status)
            || ORDER_REJECTED.equals(status)
            || ORDER_CANCELLED.equals(status);
    }

    private static OrderParams buildAggressiveOrderParams() {
        OrderParams p = new OrderParams();
        p.product  = Constants.PRODUCT_NRML;
        p.exchange = Constants.EXCHANGE_NFO;
        return p;
    }

    private static void appendId(StringBuilder sb, String id) {
        if (id == null) return;
        if (sb.length() > 0) sb.append(", ");
        sb.append(id);
    }

    private static int parseIntSafe(String s) {
        if (s == null || s.isBlank()) return 0;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 0; }
    }

    private static double parseDoubleSafe(String s) {
        if (s == null || s.isBlank()) return 0.0;
        try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return 0.0; }
    }

    static void sleepMillis(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public record ExecResult(String aggregateOrderIds, int totalFilled, int totalRequested,
                             double weightedAvgFillPrice, boolean fullyFilled, String terminalStatus) {}

    private record AttemptResult(String orderId, int filledQty, double avgFillPrice, String status) {}

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
    public Position findLiveTradesWithLiveOrderBooks() {
        Session session = entityManager.unwrap(Session.class);
        session.enableFilter("liveOrderBooks").setParameter("status", LIVE);
        Position trades = positionRepository.findFirstByStatusOrderByIdDesc(LIVE);
        session.disableFilter("liveOrderBooks");
        return trades;
    }

    @Transactional
    public Position findLiveTradesWithAllOrderBooks() {
        return positionRepository.findFirstByStatusOrderByIdDesc(LIVE);
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
