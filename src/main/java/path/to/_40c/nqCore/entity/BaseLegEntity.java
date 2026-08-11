package path.to._40c.nqCore.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@MappedSuperclass
@Getter
@Setter
@ToString
public abstract class BaseLegEntity extends BaseEntity {

    /**
     * Execution book that owns this leg: SYNTH_WEEKLY or LONG_MONTHLY. The leg is the
     * per-book unit under the one-position-per-signal model — every book-scoped operation
     * (close, rollover, recenter, flip) selects legs by this column. Instrument prefix can
     * never be the discriminator: in monthly-expiry week both books hold the identical
     * contract. Stamped at build time by ComputeUtil's instrument builders; legacy null
     * rows are backfilled from the old POSITION.BOOK at startup (LegBookBackfill).
     */
    @Column(name = "BOOK", length = 15)
    protected String book;

    @Column(name = "EXCHANGE_SYMBOL")
    protected String exchangeSymbol;

    @Column(name = "INSTRUMENT")
    protected String instrument;

    @Column(name = "SIDE")
    protected String side;

    @Column(name = "SELL_FILL_PRICE")
    protected Double sellFillPrice;

    @Column(name = "BUY_FILL_PRICE")
    protected Double buyFillPrice;

    @Column(name = "LTP")
    protected Double ltp;

    @Column(name = "EXPECTED_PNL")
    protected Double expectedPnl;

    @Column(name = "ACTUAL_PNL")
    protected Double actualPnl;

    @Column(name = "PNL_CAPTURE_PCT")
    protected String pnlCapturePct;

    @Column(name = "MONEYNESS")
    protected String moneyness;

    @Column(name = "LOTS")
    protected Integer lots;

    @Column(name = "QUANTITY")
    protected Integer quantity;

    @Column(name = "MARGIN_REQUIRED")
    protected Double marginRequired;

    @Column(name = "STATUS")
    protected String status;

    @Column(name = "OPEN_CHARGES")
    protected Double openCharges;

    @Column(name = "CLOSE_CHARGES")
    protected Double closeCharges;

    @Column(name = "OPEN_ORDER_ID")
    protected String openOrderId;

    @Column(name = "CLOSE_ORDER_ID")
    protected String closeOrderId;

    @Column(name = "BUY_INTENDED_PRICE")
    protected Double buyIntendedPrice;

    @Column(name = "SELL_INTENDED_PRICE")
    protected Double sellIntendedPrice;

    /**
     * Effective spread paid at ENTRY: avg fill vs the quote midpoint at order time, in
     * points per unit, signed so paying up is positive. The monthly liquidity guard
     * (plan §3.3.2) evaluates LONG_MONTHLY slippage against this.
     */
    @Column(name = "OPEN_SPREAD_PAID")
    protected Double openSpreadPaid;

    /** Effective spread paid at EXIT vs the quote midpoint, points per unit (positive = paid up). */
    @Column(name = "CLOSE_SPREAD_PAID")
    protected Double closeSpreadPaid;
}
