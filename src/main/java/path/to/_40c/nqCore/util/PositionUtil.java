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
import static path.to._40c.nqCore.util.Constants.NIFTY_OPT_TICK;
import static path.to._40c.nqCore.util.Constants.PARTIAL;
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
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zerodhatech.kiteconnect.utils.Constants;
import com.zerodhatech.models.BulkOrderResponse;
import com.zerodhatech.models.ContractNote;
import com.zerodhatech.models.ContractNoteParams;
import com.zerodhatech.models.MarginCalculationData;
import com.zerodhatech.models.MarginCalculationParams;
import com.zerodhatech.models.Order;
import com.zerodhatech.models.OrderParams;
import com.zerodhatech.models.OrderResponse;
import com.zerodhatech.models.Quote;

import jakarta.persistence.EntityManager;
import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.entity.WeeklyLeg;
import path.to._40c.nqCore.entity.LegFill;
import path.to._40c.nqCore.gateway.KiteGateway;
import path.to._40c.nqCore.gateway.KiteOrderStream;
import path.to._40c.nqCore.repo.PositionRepository;

@Service
@Slf4j
public class PositionUtil {
    /**
     * Shared virtual-thread executor for parallel leg operations across services.
     * Used by PositionOpeningService, PositionClosingService, PositionRolloverService,
     * ProfitRecenterService — replaces FJP common pool for blocking I/O fan-out.
     * Static lifetime; virtual-thread executors hold negligible resources.
     */
    public static final java.util.concurrent.ExecutorService LEG_EXEC =
        java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    private final KiteGateway kiteGateway;
    private final KiteOrderStream orderStream;
    private final PositionRepository positionRepository;
    private final EntityManager entityManager;

    /**
     * When true, placeAggressiveOrder uses graduated LIMIT walk (mid → walk → MARKET fallback)
     * instead of pure MARKET. Default false; production sets true via application.properties.
     */
    @org.springframework.beans.factory.annotation.Value("${order.execution.use-limit-walk:false}")
    private boolean useLimitWalk;

    public PositionUtil(KiteGateway kiteGateway, KiteOrderStream orderStream,
                        PositionRepository positionRepository, EntityManager entityManager) {
        this.kiteGateway = kiteGateway;
        this.orderStream = orderStream;
        this.positionRepository = positionRepository;
        this.entityManager = entityManager;
    }

    /** Capture fill prices for legs that don't already have them. Idempotent. */
    public void setTradeExecutedPrices(Position t) {
        if (t != null) {
            t.getLegs().forEach(w -> {
                if (isPriceAlreadyCaptured(w)) return;
                boolean isLive = LIVE.equals(w.getStatus());
                String orderId = isLive ? w.getOpenOrderId() : w.getCloseOrderId();
                if (orderId == null || orderId.isBlank()) return;
                MDC.put(MDC_LEG_KEY, (isLive ? "ENTRY" : "EXIT") + ":" + w.getInstrument() + " | ");
                try {
                    log.debug("Fetching executed prices for trade id={} orderId={}", t.getId(), orderId);
                    List<com.zerodhatech.models.Trade> trades = fetchWithRetry(orderId, "executed prices");
                    if (trades != null && !trades.isEmpty()) {
                        double avgPrice = weightedAvgFillPrice(trades);
                        log.debug("Average Price (weighted across {} fills): {}", trades.size(), avgPrice);
                        if (BUY.equals(w.getSide())) {
                            if (LIVE.equals(w.getStatus()))   w.setBuyFillPrice(avgPrice);
                            if (CLOSED.equals(w.getStatus())) w.setSellFillPrice(avgPrice);
                        }
                        if (SELL.equals(w.getSide())) {
                            if (LIVE.equals(w.getStatus()))   w.setSellFillPrice(avgPrice);
                            if (CLOSED.equals(w.getStatus())) w.setBuyFillPrice(avgPrice);
                        }
                    }
                } finally {
                    MDC.remove(MDC_LEG_KEY);
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
                    log.debug("Fetching executed prices for rollover trade id={} orderId={}", t.getId(), orderId);
                    List<com.zerodhatech.models.Trade> trades = fetchWithRetry(orderId, "rollover executed prices");
                    if (trades != null && !trades.isEmpty()) {
                        double avgPrice = weightedAvgFillPrice(trades);
                        log.debug("Average Price (weighted across {} fills): {}", trades.size(), avgPrice);
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

        var realFuture     = CompletableFuture.supplyAsync(() -> getMarginCalculation(realParams), LEG_EXEC);
        var oppositeFuture = CompletableFuture.supplyAsync(() -> getMarginCalculation(oppositeParams), LEG_EXEC);
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

    public Map<String, Quote> getQuote(String[] ins) {
        return kiteGateway.getQuote(ins);
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
        log.debug("Fetching trades for orderId: {}", orderId);
        List<com.zerodhatech.models.Trade> allTrades = new ArrayList<>();
        Arrays.stream(orderId.split("\\s*,\\s*"))
              .filter(id -> id != null && !id.trim().isEmpty())
              .forEach(id -> {
                  List<com.zerodhatech.models.Trade> orderTrades = kiteGateway.getOrderTrades(id);
                  allTrades.addAll(orderTrades);
                  log.debug("Fetched {} trades for orderId: {}", orderTrades.size(), id);
              });
        int totalQty = allTrades.stream().filter(t -> t != null).mapToInt(t -> parseIntSafe(t.quantity)).sum();
        log.info("fills orderId={}: {} fills, qty={}, wAvg={}", orderId, allTrades.size(), totalQty, weightedAvgFillPrice(allTrades));
        allTrades.forEach(trade -> log.debug("Fill[tradeId={}, orderId={}, symbol={}, type={}, qty={}, price={}, fillTime={}]",
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
     *
     * Routes by size and config:
     *   qty ≥ MAX_SIZE_PER_ORDER → Kite broker-side auto-slice (MARKET per slice)
     *   useLimitWalk=true        → graduated LIMIT (mid → walk → MARKET fallback), saves spread
     *   default                  → pure MARKET protection=1 (legacy behavior)
     *
     * Either way, the final fallback is MARKET, so this method never leaves a leg un-hedged.
     *
     * Logging: every line emitted while a leg executes carries an MDC tag
     * "&lt;context&gt;:&lt;instrument&gt; | " for attribution, and the leg's step-by-step story is
     * flushed as one contiguous multi-line INFO block when the leg finishes (ERROR block
     * if it dies mid-flight), so parallel legs never interleave their summaries.
     */
    public ExecResult placeAggressiveOrder(Quote q, String ins, String txn, int qty, String contextLabel) {
        MDC.put(MDC_LEG_KEY, contextLabel + ":" + ins + " | ");
        ExecTrace trace = new ExecTrace();
        ExecResult result = null;
        try {
            result = ScopedValue.where(EXEC_TRACE, trace).call(() -> {
                if (qty >= MAX_SIZE_PER_ORDER) {
                    log.warn("qty={} >= MAX_SIZE_PER_ORDER — using auto-slice MARKET path", qty);
                    trace.record("qty %d >= MAX_SIZE_PER_ORDER -> auto-slice MARKET", qty);
                    return autoSliceFallback(ins, txn, qty, contextLabel);
                } else if (useLimitWalk) {
                    return placeGraduatedLimit(q, ins, txn, qty, contextLabel);
                } else {
                    return placeMarketCore(ins, txn, qty, contextLabel);
                }
            });
            return result;
        } finally {
            if (result != null) {
                log.info("done in {}ms | filled {}/{} avg={} term={}{}",
                        trace.elapsedMs(), result.totalFilled(), qty,
                        result.weightedAvgFillPrice(), result.terminalStatus(), trace.render());
            } else {
                log.error("ABORTED by exception after {}ms — steps completed before failure:{}",
                        trace.elapsedMs(), trace.render());
            }
            MDC.remove(MDC_LEG_KEY);
        }
    }

    // ─── Tick walk schedule (used only when useLimitWalk=true) ─────────────────────
    /** Walk deadlines from t0 (ms). At each deadline we check fill, then modify if unfilled. */
    private static final long[]   WALK_DELAYS_MS  = {500L, 1500L, 2500L};
    /** Aggression at each step: fraction of half-spread from mid toward the marketable quote.
     *  0.25 = quarter, 0.50 = at ask/bid, 1.00 = past quote (rounded down to tick anyway). */
    private static final double[] WALK_AGGRESSION = {0.25, 0.50, 1.00};

    /**
     * Graduated-LIMIT execution: places LIMIT at midpoint, walks toward the marketable quote
     * at three timed steps, then falls back to MARKET. Every failure path (no quote, weird
     * spread, placement error, walk exhausted) falls back to MARKET, so a leg can never end
     * up un-hedged.
     *
     * Expected wall-clock: ≤ 2.5s before MARKET fallback. Real-world benefit: 30-50% less
     * slippage vs pure MARKET on typical ATM NIFTY weekly options (mid is reachable on ~half
     * the orders; the rest walk a tick or two before crossing).
     *
     * Quote freshness: a flip pre-fetches the entry quote while the close orders execute, so by
     * walk time it can be seconds stale. The walk refreshes the quote at the start (anchoring the
     * initial LIMIT to the live book) and re-anchors mid/half-spread to the current book at every
     * step, so a trending market is chased rather than repriced inside a now-dead spread — the
     * 2026-06-25 ~6pt run-away, where every step sat in the stale spread and only MARKET filled.
     * A failed/unusable refresh keeps the last-known reference; only when neither the fresh nor the
     * passed-in quote has usable depth does it fall back to MARKET.
     *
     * Walk-exhausted path (over-fill safety): when the walk ends without a full fill, the LIMIT
     * is cancelled and its TRUE settled fill is re-read via confirmTerminalFill BEFORE the MARKET
     * top-up is sized. A Kite cancel is asynchronous and NOT atomic with matching: a LIMIT the
     * walk repriced to cross the spread keeps filling between the cancel request and the exchange
     * acting on it. Sizing the top-up from the pre-cancel snapshot double-counts those in-flight
     * fills — the LIMIT settles more than the snapshot shows AND the MARKET adds the stale
     * remainder on top. That was the 2026-06-19 over-fill: snapshot 195 → MARKET 455, but the
     * LIMIT actually settled 520, so the real position was 520+455=975 against an intended 650.
     * Sizing from the confirmed post-cancel fill (no further fills possible once dead) closes it;
     * a residual combined > qty is logged as OVERFILL for manual review.
     */
    private ExecResult placeGraduatedLimit(Quote q, String ins, String txn, int qty, String contextLabel) {
        ExecTrace trace = EXEC_TRACE.get();
        boolean isBuy = BUY.equals(txn);
        Quote fresh = refreshQuote(ins);
        double[] bk = midHalfSpread(fresh);
        Quote ref = fresh;
        if (bk == null) { bk = midHalfSpread(q); ref = q; }
        if (bk == null) {
            log.warn("no usable quote/depth — MARKET fallback");
            trace.record("no usable quote/depth -> MARKET fallback");
            return placeMarketCore(ins, txn, qty, contextLabel);
        }
        double mid = bk[0], halfSp = bk[1];
        trace.quote("bid=%s ask=%s ltp=%s mid=%s spread=%s", bk[2], bk[3], ref.lastPrice,
                Math.round(mid * 1000.0) / 1000.0, Math.round((bk[3] - bk[2]) * 100.0) / 100.0);

        OrderParams params = buildAggressiveOrderParams();
        params.orderType       = Constants.ORDER_TYPE_LIMIT;
        params.validity        = Constants.VALIDITY_DAY;
        params.transactionType = txn;
        params.tradingsymbol   = ins;
        params.quantity        = qty;
        params.price           = roundToTick(mid, NIFTY_OPT_TICK);

        OrderResponse resp = kiteGateway.placeOrder(params, Constants.VARIETY_REGULAR);
        if (resp == null || resp.orderId == null) {
            log.error("LIMIT placeOrder null — MARKET fallback");
            trace.record("LIMIT placeOrder returned null -> MARKET fallback");
            return placeMarketCore(ins, txn, qty, contextLabel);
        }
        log.info("LIMIT @ {} placed orderId={}", params.price, resp.orderId);
        trace.record("LIMIT @ %s placed orderId=%s", params.price, resp.orderId);

        // D₂: WS-await if stream healthy, else legacy sleep-then-poll.
        // The REST peekOrderState after the wait is still the source of truth — the WS
        // event just lets us short-circuit the wait. On WS error mid-flight, we degrade
        // to sleep-poll for the remaining steps of THIS attempt (awaitFut=null sentinel).
        final boolean wsHealthy = orderStream != null && orderStream.isHealthy();
        java.util.concurrent.CompletableFuture<com.zerodhatech.models.Order> awaitFut =
                wsHealthy ? orderStream.awaitTerminal(resp.orderId) : null;

        long t0 = System.currentTimeMillis();
        for (int step = 0; step < WALK_DELAYS_MS.length; step++) {
            long deadline = t0 + WALK_DELAYS_MS[step];
            long waitMs = deadline - System.currentTimeMillis();

            if (awaitFut != null && waitMs > 0) {
                try {
                    awaitFut.get(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                    // event arrived (or already cached) — fall through to REST confirm
                } catch (java.util.concurrent.TimeoutException te) {
                    // step deadline reached without event
                } catch (Exception e) {
                    log.warn("WS await error: {} — falling back to sleep-poll for remaining steps", e.getMessage());
                    trace.record("WS await error: %s -> sleep-poll for remaining steps", e.getMessage());
                    awaitFut = null;
                }
            } else if (waitMs > 0) {
                sleepMillis(waitMs);
            }

            AttemptResult ar = peekOrderState(resp.orderId);
            if (ar.filledQty() >= qty && isTerminal(ar.status())) {
                trace.record("FILLED %d/%d avg=%s (step %d, mode=%s)",
                        ar.filledQty(), qty, ar.avgFillPrice(), step, awaitFut != null ? "WS" : "REST");
                if (orderStream != null) orderStream.cancel(resp.orderId);
                return new ExecResult(resp.orderId, ar.filledQty(), qty, ar.avgFillPrice(), true, ORDER_COMPLETE);
            }
            double[] cur = midHalfSpread(refreshQuote(ins));
            if (cur != null) { mid = cur[0]; halfSp = cur[1]; }
            double newPx = roundToTick(isBuy
                    ? mid + halfSp * WALK_AGGRESSION[step]
                    : mid - halfSp * WALK_AGGRESSION[step], NIFTY_OPT_TICK);
            boolean mod = kiteGateway.modifyOrder(resp.orderId, newPx, qty, Constants.VARIETY_REGULAR);
            log.debug("walk step={} newPx={} filledSoFar={}/{} modified={}", step, newPx, ar.filledQty(), qty, mod);
            trace.record("step%d -> %s filled %d/%d%s", step, newPx, ar.filledQty(), qty, mod ? "" : " (modify FAILED)");

            // Re-arm awaiter for next step — modify resets the order's terminal state.
            if (awaitFut != null) awaitFut = orderStream.awaitTerminal(resp.orderId);
        }

        AttemptResult finalAr = peekOrderState(resp.orderId);
        if (finalAr.filledQty() >= qty && isTerminal(finalAr.status())) {
            trace.record("FILLED %d/%d avg=%s (post-walk check)", finalAr.filledQty(), qty, finalAr.avgFillPrice());
            if (orderStream != null) orderStream.cancel(resp.orderId);
            return new ExecResult(resp.orderId, finalAr.filledQty(), qty, finalAr.avgFillPrice(), true, ORDER_COMPLETE);
        }
        if (orderStream != null) orderStream.cancel(resp.orderId);
        kiteGateway.cancelOrder(resp.orderId, Constants.VARIETY_REGULAR);

        AttemptResult confirmed = confirmTerminalFill(resp.orderId);
        int filledByLimit  = confirmed.filledQty();
        double limitAvg    = filledByLimit > 0 ? confirmed.avgFillPrice() : 0.0;
        if (!isTerminal(confirmed.status())) {
            log.error("LIMIT {} not confirmed terminal after cancel (status={}, filled={}/{}) — sizing "
                    + "top-up from last-known fill; residual over-fill risk", resp.orderId,
                    confirmed.status(), filledByLimit, qty);
            trace.record("cancel NOT confirmed terminal (status=%s) — top-up sized from filled=%d (over-fill risk)",
                    confirmed.status(), filledByLimit);
        }

        int remaining = Math.max(0, qty - filledByLimit);
        log.warn("LIMIT walk exhausted, cancelled with confirmed fill {}/{} — MARKET for remaining qty={}",
                filledByLimit, qty, remaining);
        if (remaining == 0) {
            if (filledByLimit > qty) {
                log.error("OVER-FILL: LIMIT {} settled {}/{} (exceeds requested) — no top-up placed; position "
                        + "oversized by {}, manual review required", resp.orderId, filledByLimit, qty, filledByLimit - qty);
                trace.record("OVER-FILL filled=%d > requested=%d -> no top-up", filledByLimit, qty);
            } else {
                trace.record("FILLED %d/%d avg=%s via LIMIT (walk exhausted, confirmed after cancel)", filledByLimit, qty, limitAvg);
            }
            return new ExecResult(resp.orderId, filledByLimit, qty, limitAvg, filledByLimit >= qty,
                    filledByLimit > qty ? "OVERFILL" : ORDER_COMPLETE);
        }
        trace.record("walk exhausted -> cancelled LIMIT (confirmed filled=%d), MARKET for remaining %d", filledByLimit, remaining);
        ExecResult mkt = placeMarketCore(ins, txn, remaining, contextLabel);

        int combined  = filledByLimit + mkt.totalFilled();
        double avg    = combined > 0
                ? (filledByLimit * limitAvg + mkt.totalFilled() * mkt.weightedAvgFillPrice()) / combined
                : 0.0;
        String ids    = filledByLimit > 0 ? resp.orderId + ", " + mkt.aggregateOrderIds() : mkt.aggregateOrderIds();
        if (combined > qty) {
            log.error("OVER-FILL: {} settled {}/{} (LIMIT {} + MARKET {}) — position oversized by {}, "
                    + "manual review/unwind required", ins, combined, qty, filledByLimit, mkt.totalFilled(), combined - qty);
            trace.record("OVER-FILL combined=%d > requested=%d (limit=%d market=%d)", combined, qty, filledByLimit, mkt.totalFilled());
        }
        boolean full  = combined >= qty;
        return new ExecResult(ids, combined, qty, avg, full,
                combined > qty ? "OVERFILL" : (full ? ORDER_COMPLETE : (combined > 0 ? "PARTIAL" : "FAILED")));
    }

    // ─── Cancel-confirmation (closes the cancel-vs-fill over-fill race) ─────────────
    /** Max polls of getOrderHistory waiting for a cancelled order to reach a terminal state. */
    private static final int  CANCEL_CONFIRM_POLLS   = 6;
    /** Gap between cancel-confirmation polls (ms). 6 × 150ms ≈ 0.9s worst case on the rare walk-exhausted path. */
    private static final long CANCEL_CONFIRM_GAP_MS  = 150L;

    /**
     * After a cancel request, polls the order until it reaches a terminal state and returns its
     * TRUE settled fill. A Kite cancel is asynchronous and not atomic with matching, so a
     * marketable LIMIT can keep filling between the cancel request and the exchange acting on it.
     * Reading filledQuantity only once the order is CANCELLED/COMPLETE/REJECTED is what makes the
     * subsequent MARKET top-up correctly sized — sizing it from a pre-cancel snapshot double-buys
     * the in-flight fills (the 650-intended-vs-975-filled over-fill).
     *
     * If the order never confirms terminal within the budget, returns the last read (non-terminal)
     * state; the caller logs the residual over-fill risk and proceeds conservatively.
     */
    private AttemptResult confirmTerminalFill(String orderId) {
        AttemptResult ar = peekOrderState(orderId);
        for (int i = 0; i < CANCEL_CONFIRM_POLLS && !isTerminal(ar.status()); i++) {
            sleepMillis(CANCEL_CONFIRM_GAP_MS);
            ar = peekOrderState(orderId);
        }
        return ar;
    }

    /** Pure MARKET path (extracted from old placeAggressiveOrder). Reused as the safety fallback. */
    private ExecResult placeMarketCore(String ins, String txn, int qty, String contextLabel) {
        ExecTrace trace = EXEC_TRACE.get();
        OrderParams params = buildAggressiveOrderParams();
        params.orderType        = Constants.ORDER_TYPE_MARKET;
        params.validity         = Constants.VALIDITY_DAY;
        params.transactionType  = txn;
        params.tradingsymbol    = ins;
        params.quantity         = qty;
        params.marketProtection = 1;
        OrderResponse resp = kiteGateway.placeOrder(params, Constants.VARIETY_REGULAR);
        if (resp == null || resp.orderId == null) {
            log.error("MARKET placeOrder returned null");
            trace.record("MARKET placeOrder returned null -> PLACE_FAILED");
            return new ExecResult("", 0, qty, 0.0, false, "PLACE_FAILED");
        }
        log.info("MARKET protection=1 placed: orderId={}", resp.orderId);
        trace.record("MARKET placed orderId=%s", resp.orderId);

        AttemptResult ar = confirmMarketFill(resp.orderId, qty, ins);
        boolean full = ar.filledQty() >= qty;
        String term  = full ? ORDER_COMPLETE : (ar.filledQty() > 0 ? "PARTIAL" : (ar.status() != null ? ar.status() : "FAILED"));
        String ids   = ar.filledQty() > 0 ? resp.orderId : "";
        trace.record("MARKET %s %d/%d avg=%s", term, ar.filledQty(), qty, ar.avgFillPrice());
        return new ExecResult(ids, ar.filledQty(), qty, ar.avgFillPrice(), full, term);
    }

    // ─── MARKET fill confirmation (closes the under-report-as-FAILED gap) ────────────
    /**
     * Confirmation budget for a MARKET order. Deliberately longer than verifyAttempt's
     * because a MARKET fill plus Kite's order-state / tradebook propagation can lag a
     * second or two. 10 × 250ms ≈ 2.5s worst case, paid only while the order has not yet
     * confirmed COMPLETE.
     */
    private static final int  MARKET_CONFIRM_POLLS  = 10;
    private static final long MARKET_CONFIRM_GAP_MS = 250L;

    /**
     * Confirms a MARKET order's TRUE settled state before the caller may treat it as failed.
     * verifyAttempt reads only getOrderHistory and gives up after ~600ms; on 2026-06-25 the
     * order-history snapshot still showed OPEN/0 after that window while the broker had already
     * filled 650, so the leg was declared FAILED even though the short executed. This method
     * (1) polls to a terminal state with a longer budget and (2) reconciles against the
     * tradebook (getOrderTrades) — the authoritative record of what actually executed at the
     * broker — trusting it over the order-history snapshot whenever the two disagree. A MARKET
     * DAY order placed in-session virtually always fills, so an empty tradebook here is the rare
     * genuine reject, not the default assumption.
     *
     * If the order reaches a terminal state short of full, the filled portion is taken from the
     * tradebook (genuine partial/reject). If the poll budget is exhausted with no terminal state,
     * the tradebook is still trusted over the lagging snapshot; only a truly empty tradebook is
     * reported unconfirmed, with an error so a possibly-live leg is reconciled before re-entry.
     */
    private AttemptResult confirmMarketFill(String orderId, int qty, String ins) {
        ExecTrace trace = EXEC_TRACE.get();
        AttemptResult lastHist = new AttemptResult(orderId, 0, 0.0, "UNKNOWN");
        for (int i = 0; i < MARKET_CONFIRM_POLLS; i++) {
            AttemptResult hist = peekOrderState(orderId);
            if (hist.status() != null && !"UNKNOWN".equals(hist.status())) lastHist = hist;

            List<com.zerodhatech.models.Trade> trades = kiteGateway.getOrderTrades(orderId);
            int traded = tradedQty(trades);
            if (traded >= qty) {
                double vwap = weightedAvgFillPrice(trades);
                trace.record("MARKET reconciled via tradebook: filled %d/%d avg=%s (hist=%s)", traded, qty, vwap, lastHist.status());
                return new AttemptResult(orderId, traded, vwap, ORDER_COMPLETE);
            }
            if (isTerminal(lastHist.status())) {
                if (traded > 0) return new AttemptResult(orderId, traded, weightedAvgFillPrice(trades), lastHist.status());
                return lastHist;
            }
            sleepMillis(MARKET_CONFIRM_GAP_MS);
        }
        List<com.zerodhatech.models.Trade> trades = kiteGateway.getOrderTrades(orderId);
        int traded = tradedQty(trades);
        if (traded > 0) {
            double vwap = weightedAvgFillPrice(trades);
            log.warn("MARKET orderId={} not terminal after {} polls — tradebook shows {}/{} avg={}, trusting tradebook over hist status={}",
                    orderId, MARKET_CONFIRM_POLLS, traded, qty, vwap, lastHist.status());
            trace.record("MARKET budget exhausted; tradebook %d/%d avg=%s trusted over hist=%s", traded, qty, vwap, lastHist.status());
            return new AttemptResult(orderId, traded, vwap, traded >= qty ? ORDER_COMPLETE : lastHist.status());
        }
        log.error("MARKET orderId={} UNCONFIRMED after {} polls — hist status={} filled={}, tradebook empty; "
                + "leg may still be live at broker — reconcile before re-entry", orderId, MARKET_CONFIRM_POLLS, lastHist.status(), lastHist.filledQty());
        trace.record("MARKET UNCONFIRMED after %d polls (hist=%s, tradebook empty)", MARKET_CONFIRM_POLLS, lastHist.status());
        return lastHist;
    }

    /** Σ tradedQuantity across an order's tradebook — authoritative executed qty. */
    private static int tradedQty(List<com.zerodhatech.models.Trade> trades) {
        if (trades == null) return 0;
        int sum = 0;
        for (com.zerodhatech.models.Trade t : trades) {
            if (t == null || t.quantity == null) continue;
            try { sum += (int) Math.round(Double.parseDouble(t.quantity.trim())); }
            catch (NumberFormatException e) { /* skip malformed */ }
        }
        return sum;
    }

    /** Top-of-book {mid, halfSpread, bid, ask} from a quote, or null if depth is missing/unusable. */
    private static double[] midHalfSpread(Quote q) {
        if (q == null || q.depth == null
            || q.depth.buy == null || q.depth.buy.isEmpty()
            || q.depth.sell == null || q.depth.sell.isEmpty()) return null;
        double bid = q.depth.buy.get(0).getPrice();
        double ask = q.depth.sell.get(0).getPrice();
        if (bid <= 0 || ask <= 0 || ask < bid || (ask - bid) > (ask + bid) * 0.05) return null;
        return new double[]{ (bid + ask) / 2.0, (ask - bid) / 2.0, bid, ask };
    }

    /** Fresh top-of-book quote for the walk chase; null on any failure so the caller keeps its last-known reference. */
    private Quote refreshQuote(String ins) {
        String key = Constants.EXCHANGE_NFO + ":" + ins;
        try {
            Map<String, Quote> m = kiteGateway.getQuote(new String[]{key});
            if (m == null || m.isEmpty()) return null;
            Quote qq = m.get(key);
            return qq != null ? qq : m.values().iterator().next();
        } catch (Exception e) {
            log.warn("walk: quote refresh failed for {} — keeping last-known reference: {}", ins, e.getMessage());
            return null;
        }
    }

    /** Single-shot status read (no retry loop) — used inside the LIMIT walk where the loop itself is the retry. */
    private AttemptResult peekOrderState(String orderId) {
        List<Order> history = kiteGateway.getOrderHistory(orderId);
        if (history == null || history.isEmpty()) return new AttemptResult(orderId, 0, 0.0, "UNKNOWN");
        Order last = history.get(history.size() - 1);
        return new AttemptResult(orderId, parseIntSafe(last.filledQuantity), parseDoubleSafe(last.averagePrice), last.status);
    }

    /**
     * Rounds price to the nearest tick, then re-rounds to 2 decimals: tick multiples have
     * ≤2 decimals, but the binary-float product carries artifacts
     * (1842 * 0.05 = 92.10000000000001) into the price sent to Kite.
     */
    private static double roundToTick(double price, double tick) {
        return Math.round(Math.round(price / tick) * tick * 100.0) / 100.0;
    }

    /** Fallback for qty >= MAX_SIZE_PER_ORDER — Kite broker-side auto-slice using MARKET orders. */
    private ExecResult autoSliceFallback(String ins, String txn, int qty, String contextLabel) {
        ExecTrace trace = EXEC_TRACE.get();
        List<BulkOrderResponse> bulk = placeAutoSliceOrder(ins, 0.0, txn, qty);
        if (bulk == null || bulk.isEmpty()) {
            trace.record("auto-slice returned no orders -> FAILED");
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
            trace.record("slice orderId=%s %s filled %d avg=%s", b.orderId, ar.status(), ar.filledQty(), ar.avgFillPrice());
        }
        double avg = totalFilled > 0 ? Math.round((weightedSum / totalFilled) * 100.0) / 100.0 : 0.0;
        boolean full = totalFilled == qty;
        String term = full ? ORDER_COMPLETE : (totalFilled > 0 ? "PARTIAL" : "FAILED");
        trace.record("autoslice %s %d/%d avg=%s", term, totalFilled, qty, avg);
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
            log.error("orderId={} getOrderHistory empty after retries", orderId);
            return new AttemptResult(orderId, 0, 0.0, "UNKNOWN");
        }
        int filledQty = parseIntSafe(last.filledQuantity);
        double avgPrice = parseDoubleSafe(last.averagePrice);
        log.debug("orderId={} status={} filled={}/{} avgPx={} statusMsg={}",
                orderId, last.status, last.filledQuantity, last.quantity, avgPrice, last.statusMessage);
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

    /** MDC key carrying the per-leg log tag ("&lt;context&gt;:&lt;instrument&gt; | "), rendered via %X{leg} in logback. */
    private static final String MDC_LEG_KEY = "leg";

    /**
     * Per-leg ExecTrace, bound by placeAggressiveOrder for the duration of one leg's execution so
     * the private pipeline methods (LIMIT walk, MARKET core, auto-slice, fill confirms) append to
     * the same trace without it being threaded through every signature. Legs execute on separate
     * virtual threads, so bindings never overlap; reading outside a binding throws
     * NoSuchElementException, which flags a call path that bypassed placeAggressiveOrder.
     */
    private static final ScopedValue<ExecTrace> EXEC_TRACE = ScopedValue.newInstance();

    /**
     * Per-leg execution trace: accumulates step lines with +ms offsets during one
     * placeAggressiveOrder call and renders them as a single multi-line block, so the
     * complete story of each leg appears contiguously in the log even when several legs
     * execute in parallel. Reaches the pipeline methods via the EXEC_TRACE scoped value.
     * Confined to the leg's own thread — not thread-safe by design.
     */
    private static final class ExecTrace {
        private final long t0 = System.currentTimeMillis();
        private final StringBuilder block = new StringBuilder();

        /** Records the pre-placement quote snapshot (no time offset — it anchors the walk). */
        void quote(String fmt, Object... args) {
            add("quote", fmt, args);
        }

        /** Records one execution step, stamped with milliseconds elapsed since leg start. */
        void record(String fmt, Object... args) {
            add("+" + (System.currentTimeMillis() - t0) + "ms", fmt, args);
        }

        long elapsedMs() {
            return System.currentTimeMillis() - t0;
        }

        String render() {
            return block.toString();
        }

        private void add(String label, String fmt, Object... args) {
            block.append(System.lineSeparator())
                 .append("    ").append(String.format("%-9s ", label))
                 .append(String.format(fmt, args));
        }
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
    public Position findLiveTradesWithLiveOrderBooks() {
        Session session = entityManager.unwrap(Session.class);
        session.enableFilter("liveOrderBooks").setParameter("status", LIVE);
        Position trades = positionRepository.findFirstByStatusOrderByIdDesc(LIVE);
        session.disableFilter("liveOrderBooks");
        return trades;
    }

    /**
     * Finds the latest PARTIAL position (an open where only some legs filled) with only its
     * still-LIVE orphan legs loaded, so the closing path can flatten exactly what is held
     * at the broker.
     */
    @Transactional
    public Position findPartialTradesWithLiveOrderBooks() {
        Session session = entityManager.unwrap(Session.class);
        session.enableFilter("liveOrderBooks").setParameter("status", LIVE);
        Position trades = positionRepository.findFirstByStatusOrderByIdDesc(PARTIAL);
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
