package path.to._40c.nqCore.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * One row per slice of an auto-sliced order, capturing both the BUY-side and SELL-side
 * fill for slippage analysis. Slice count stays constant between a leg's open and close
 * (same total qty), so a single row holds both sides of the same logical slice.
 *
 * Fields use BUY/SELL prefix (matching buyFillPrice/sellFillPrice and buyIntendedPrice/
 * sellIntendedPrice in the parent leg) rather than OPEN/CLOSE — for a BUY leg the open
 * event populates buy_* and close populates sell_*; for a SELL leg the reverse.
 *
 * Slices are ordered by fill timestamp ascending (per side independently), so slice_index 0
 * is the earliest-filled slice on each side. Pairing under timestamp ordering means the
 * row's buy_slice_qty and sell_slice_qty may differ for the "remainder" slice — analytics
 * should use MIN(buy_slice_qty, sell_slice_qty) when computing per-slice qty-weighted PnL.
 */
@Entity
@Table(name = "LEG_FILL",
       uniqueConstraints = @UniqueConstraint(name = "uk_leg_fill_slice",
                                              columnNames = {"weekly_leg_id", "SLICE_INDEX"}))
@Getter
@Setter
public class LegFill extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "weekly_leg_id", nullable = false)
    private WeeklyLeg weeklyLeg;

    @Column(name = "SLICE_INDEX", nullable = false)
    private Integer sliceIndex;

    @Column(name = "BUY_SLICE_QTY")
    private Integer buySliceQty;

    @Column(name = "BUY_SLICE_ORDER_ID")
    private String buySliceOrderId;

    @Column(name = "BUY_FILL_PRICE")
    private Double buyFillPrice;

    @Column(name = "BUY_FILL_TIME")
    private String buyFillTime;

    @Column(name = "SELL_SLICE_QTY")
    private Integer sellSliceQty;

    @Column(name = "SELL_SLICE_ORDER_ID")
    private String sellSliceOrderId;

    @Column(name = "SELL_FILL_PRICE")
    private Double sellFillPrice;

    @Column(name = "SELL_FILL_TIME")
    private String sellFillTime;
}
