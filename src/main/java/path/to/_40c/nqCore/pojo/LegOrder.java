package path.to._40c.nqCore.pojo;

import java.util.List;

import com.zerodhatech.models.BulkOrderResponse;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import path.to._40c.nqCore.entity.Position;

/**
 * Transient order specification for one leg of a multi-leg position.
 *
 * Carries the pre-placement intent (instrument + side + qty + strike characteristics)
 * and is populated with post-placement results (orderId + fill status) as the order
 * goes through the broker. Persisted as a WeeklyLeg row once the trade is saved.
 *
 * One synthetic position is built from N LegOrder instances (typically 2: BUY CE + SELL PE
 * for a LONG synthetic, or SELL CE + BUY PE for a SHORT synthetic).
 */
@Getter
@Setter
@ToString(exclude = "orderResponse")
public class LegOrder {

    private String exchangeSymbol;
    private String instrument;
    private String side;
    private String moneyness;
    private String optionType;
    private Position parentPosition;
    private int lots;
    private String openOrderId;
    private Boolean openFullyFilled;
    private List<BulkOrderResponse> orderResponse;

    public LegOrder() {}
}
