package path.to._40c.nqCore.entity;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.*;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.Filter;
import path.to._40c.nqCore.controller.SignalController;

import static path.to._40c.nqCore.util.Constants.DATE_FORMAT;
import static path.to._40c.nqCore.util.Constants.INPUT_FORMATS;
import static path.to._40c.nqCore.util.Constants.LIVE_ORDER_BOOKS;
import static path.to._40c.nqCore.util.Constants.ZONE_ID;

import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

/**
 * ONE position per signal: the signal is the opportunity (direction, entry/exit spot, total
 * sizing, capital chain, cumulative P&L) and this row is what was done with it. Execution
 * detail lives on the WeeklyLeg children — each leg carries its BOOK (SYNTH_WEEKLY /
 * LONG_MONTHLY), so one row holds both books' legs across all segments (open, recenters,
 * rollovers, close). Row status is the roll-up of all legs (LegScope.rollUpStatus).
 *
 * Spot/points accounting (the baseline/banked scheme, one chain per book):
 * entrySpot and exitSpot are immutable — the spot at original entry (set once on open/flip,
 * never mutated by recenter/rollover; reporting + signal-dedup only) and the spot at final
 * close (set once by PositionCloseService; reporting only). baselineSpot/bankedPoints are
 * the SYNTH_WEEKLY chain: the spot at which the current weekly legs were struck, re-based by
 * every weekly recenter and rollover, with the closed segments' points accumulating in
 * bankedPoints. monthlyBaselineSpot/monthlyBankedPoints are the LONG_MONTHLY chain, re-based
 * only by monthly rollovers. Both chains telescope to the same total spot move (entry→exit);
 * they differ in segment boundaries, which is what per-leg expectedPnl stamping needs.
 * calcTradeOutcome computes pointsPnl = banked + current segment on the weekly chain when
 * weekly legs exist, else the monthly chain. peakMargin is the running max of
 * Σ(LIVE legs.marginRequired) across ALL books; weeklyMarginPerLot tracks the weekly book's
 * margin per synthetic lot for the NRML sizing stats (monthly premium must not poison them).
 */
@FilterDef(name = LIVE_ORDER_BOOKS, parameters = @ParamDef(name = "status", type = String.class))
@Entity
@Table(name = "POSITION")
@Getter
@Setter
@ToString(exclude = {"legs"})
public class Position extends BaseEntity {

    @Column(name = "STRATEGY_ID")
    private String strategyId = "RIDETHETIDE";

    @Column(name = "ACCOUNT")
    private String account = "ZERODHAVINOTH";

    @Column(name = "ENTRY_SPOT")
    private Double entrySpot;

    @Column(name = "EXIT_SPOT")
    private Double exitSpot;

    @Column(name = "BASELINE_SPOT")
    private Double baselineSpot;

    @Column(name = "BANKED_POINTS")
    private Double bankedPoints = 0.0;

    @Column(name = "MONTHLY_BASELINE_SPOT")
    private Double monthlyBaselineSpot;

    @Column(name = "MONTHLY_BANKED_POINTS")
    private Double monthlyBankedPoints = 0.0;

    @Column(name = "DIRECTION")
    private String direction;

    @Column(name = "POINTS_PNL")
    private Double pointsPnl;

    @Column(name = "RESULT")
    private String result;

    @Column(name = "STARTING_CAPITAL")
    private Double startingCapital;

    @Column(name = "ENDING_CAPITAL")
    private Double endingCapital;

    @Column(name = "EXPECTED_PNL")
    private Double expectedPnl;

    @Column(name = "ACTUAL_PNL")
    private Double actualPnl;

    @Column(name = "TOTAL_CHARGES")
    private Double totalCharges;

    @Column(name = "LOTS")
    private Integer lots;

    @Column(name = "PNL_CAPTURE_PCT")
    private String pnlCapturePct;

    @Column(name = "STATUS")
    private String status;

    @Column(name = "OPENED_AT")
    private String openedAt;

    @Column(name = "CLOSED_AT")
    private String closedAt;

    @Column(name = "LAST_SIGNAL_ACTION")
    private String lastSignalAction;

    @Column(name = "LAST_SIGNAL_LEG")
    private String lastSignalLeg;

    @Column(name = "STRATEGY_NAME")
    private String strategyName;

    @Column(name = "SIGNAL_AT")
    private String signalAt;

    @Column(name = "MESSAGE")
    private String message;

    @Column(name = "PEAK_MARGIN")
    private Double peakMargin;

    @Column(name = "WEEKLY_MARGIN_PER_LOT")
    private Double weeklyMarginPerLot;

    @Setter(AccessLevel.NONE)
    @OneToMany(mappedBy = "position", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @Filter(name = LIVE_ORDER_BOOKS, condition = "STATUS = :status")
    private List<WeeklyLeg> legs;

    public Position() {
        this.legs = new ArrayList<>();
    }

    public Position(SignalController.Signal signal) {
        this.lastSignalAction = signal.action;
        this.lastSignalLeg    = signal.signalType;
        this.strategyName     = signal.strategyName;
        this.signalAt         = normalizeSignalTime(signal.time);
        this.legs             = new ArrayList<>();
    }

    /**
     * Converts AmiBroker-style signal timestamps (e.g. "01-Jun-2026 11.15.00 AM") into
     * the same DATE_FORMAT used for opened_at / closed_at ("01-06-2026 11:15:00.000"),
     * so all three time fields on a position read in the same convention. Falls back
     * to the raw input if no known format matches.
     */
    private static String normalizeSignalTime(String t) {
        if (t == null || t.isBlank()) return t;
        String trimmed = t.trim();
        DateTimeFormatter out = DateTimeFormatter.ofPattern(DATE_FORMAT);
        for (DateTimeFormatter in : INPUT_FORMATS) {
            try {
                return LocalDateTime.parse(trimmed, in).format(out);
            } catch (DateTimeParseException ignored) {}
        }
        return t;
    }

    /** addAll semantics (not replace) — Lombok setter suppressed above so prior CLOSED legs survive across rollover/recenter. */
    public void setLegs(List<WeeklyLeg> legs) {
        this.legs.addAll(legs);
    }

    @PrePersist
    public void onCreate() {
        this.openedAt = LocalDateTime.now(ZoneId.of(ZONE_ID))
                .format(DateTimeFormatter.ofPattern(DATE_FORMAT));
    }
}
