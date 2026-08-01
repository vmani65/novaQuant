package path.to._40c.nqCore.service;

import static path.to._40c.nqCore.util.Constants.LOT_SIZE;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.zerodhatech.kiteconnect.utils.Constants;
import com.zerodhatech.models.CombinedMarginData;
import com.zerodhatech.models.MarginCalculationParams;

import lombok.extern.slf4j.Slf4j;
import path.to._40c.nqCore.gateway.KiteGateway;
import path.to._40c.nqCore.pojo.LegOrder;

/**
 * Pre-trade margin gate for fresh position opens. The 2026-07-20 incident — a margin-reject
 * mid-open that orphaned one filled leg — becomes N× more likely with several strategies
 * sharing one funds pool, so before ANY open order is placed the planned basket's margin
 * (with hedge benefit, considering existing positions) is compared against net available
 * funds plus a safety buffer.
 *
 * Failure philosophy: fail CLOSED only on a definitive answer ("you need X, you have Y");
 * fail OPEN (allow, log loudly) whenever the broker APIs error or return nothing — this
 * guard exists to stop known-insufficient opens, not to add a new outage mode. Rollover
 * and recenter re-opens are intentionally NOT gated: their closes have already executed,
 * and refusing the reopen would strand the book flat mid-flow.
 */
@Service
@Slf4j
public class MarginPreCheckService {

    public record MarginCheck(boolean allowed, Double requiredWithBuffer, Double available, String reason) {}

    private final KiteGateway kiteGateway;

    @Value("${order.margin.precheck.enabled:true}")
    private boolean enabled;

    @Value("${order.margin.precheck.buffer-pct:5.0}")
    private double bufferPct;

    public MarginPreCheckService(KiteGateway kiteGateway) {
        this.kiteGateway = kiteGateway;
    }

    /**
     * Checks whether the planned legs fit inside available funds. Allowed when disabled,
     * on any broker-API failure (fail-open), or when available >= required × (1 + buffer).
     */
    public MarginCheck check(List<LegOrder> legOrders) {
        if (!enabled) {
            return new MarginCheck(true, null, null, "margin pre-check disabled");
        }
        try {
            List<MarginCalculationParams> params = legOrders.stream().map(MarginPreCheckService::toParam).toList();
            CombinedMarginData combined = kiteGateway.getCombinedMarginCalculation(params, true);
            Double required = combined != null && combined.initialMargin != null ? combined.initialMargin.total : null;
            if (required == null || required <= 0.0) {
                log.error("Margin pre-check: basket margin API returned nothing usable — FAIL-OPEN (order proceeds unchecked)");
                return new MarginCheck(true, null, null, "margin API unavailable — fail-open");
            }
            Double available = kiteGateway.getAvailableFunds();
            if (available == null) {
                log.error("Margin pre-check: funds API returned null — FAIL-OPEN (order proceeds unchecked)");
                return new MarginCheck(true, required, null, "funds API unavailable — fail-open");
            }
            double needed = Math.round(required * (1 + bufferPct / 100.0) * 100.0) / 100.0;
            if (available >= needed) {
                log.info("Margin pre-check PASSED | required={} (+{}% buffer → {}) | available={}",
                        required, bufferPct, needed, available);
                return new MarginCheck(true, needed, available, "sufficient funds");
            }
            String reason = String.format("MARGIN BLOCKED: need ₹%.2f (₹%.2f + %.1f%% buffer) but only ₹%.2f available",
                    needed, required, bufferPct, available);
            return new MarginCheck(false, needed, available, reason);
        } catch (Exception e) {
            log.error("Margin pre-check threw — FAIL-OPEN (order proceeds unchecked)", e);
            return new MarginCheck(true, null, null, "pre-check error — fail-open");
        }
    }

    private static MarginCalculationParams toParam(LegOrder order) {
        MarginCalculationParams p = new MarginCalculationParams();
        p.exchange = Constants.EXCHANGE_NFO;
        p.variety = Constants.VARIETY_REGULAR;
        p.product = Constants.PRODUCT_NRML;
        p.orderType = Constants.ORDER_TYPE_MARKET;
        p.quantity = order.getLots() * LOT_SIZE;
        p.tradingSymbol = order.getInstrument();
        p.transactionType = order.getSide();
        return p;
    }
}
