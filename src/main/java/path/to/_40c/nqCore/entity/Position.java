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
import static path.to._40c.nqCore.util.Constants.ZONE_ID;

import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

@FilterDef(name = "liveOrderBooks", parameters = @ParamDef(name = "status", type = String.class))
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

    /**
     * Cumulative points captured from all profit-recenter segments before the current one.
     * Updated by ProfitRecenterService on each recenter. Zero for positions with no recenters.
     * Used by calcTradeOutcome: totalPoints = realizedPoints + (exit - entry of current segment).
     */
    @Column(name = "REALIZED_POINTS")
    private Double realizedPoints = 0.0;

    /** Running max of Σ(LIVE legs.marginRequired) across the position's lifetime. */
    @Column(name = "PEAK_MARGIN")
    private Double peakMargin;

    @Setter(AccessLevel.NONE)
    @OneToMany(mappedBy = "position", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @Filter(name = "liveOrderBooks", condition = "STATUS = :status")
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
