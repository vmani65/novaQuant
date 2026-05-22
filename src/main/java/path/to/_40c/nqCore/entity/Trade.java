package path.to._40c.nqCore.entity;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
import static path.to._40c.nqCore.util.Constants.ZONE_ID;

import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

@FilterDef(name = "liveOrderBooks", parameters = @ParamDef(name = "status", type = String.class))
@Entity
@Table(name = "TRADE")
@Getter
@Setter
@ToString(exclude = {"weeklyOrderBook", "monthlyOrderBook"})
public class Trade extends BaseEntity {

    @Column(name = "STRATEGY_ID")
    private String strategyId = "RIDETHETIDE";

    @Column(name = "ACCOUNT")
    private String account = "ZERODHAVINOTH";

    @Column(name = "ENTRY_SIGNAL_PRICE")
    private Double entrySignalPrice;

    @Column(name = "EXIT_SIGNAL_PRICE")
    private Double exitSignalPrice;

    @Column(name = "SIGNAL_TYPE")
    private String signalType;

    @Column(name = "POINTS_BY_TRADE")
    private Double pointsByTrade;

    @Column(name = "TRADE_OUTCOME")
    private String tradeOutcome;

    @Column(name = "STARTING_CAPITAL")
    private Double startingCapital;

    @Column(name = "ENDING_CAPITAL")
    private Double endingCapital;

    @Column(name = "EXPECTED_P_L")
    private Double expectedPnL;

    @Column(name = "ACTUAL_P_L")
    private Double actualPnL;

    @Column(name = "BROKERAGE")
    private Double brokerage;

    @Column(name = "LOTS")
    private Integer lots;

    @Column(name = "DIFF_PERCENTAGE")
    private String diffPercentage;

    @Column(name = "TRADE_STATUS")
    private String tradeStatus;

    @Column(name = "TRADE_OPEN_DATETIME")
    private String tradeOpenDtTime;

    @Column(name = "TRADE_CLOSE_DATETIME")
    private String tradeCloseDtTime;

    @Column(name = "LAST_API_ACTION")
    private String lastApiAction;

    @Column(name = "LAST_API_SIGNAL_TYPE")
    private String lastApiSignalType;

    @Column(name = "STATERGY_NAME")
    private String statergyName;

    @Column(name = "API_TIME")
    private String apiTime;

    @Column(name = "MESSAGE")
    private String message;

    /**
     * Cumulative points captured from all profit-recenter segments before the current one.
     * Updated by ProfitRecenterService on each recenter. Zero for trades with no recenters.
     * Used by calcTradeOutcome: totalPoints = realizedPoints + (exit - entry of current segment).
     */
    @Column(name = "REALIZED_POINTS")
    private Double realizedPoints = 0.0;

    /** Running max of Σ(LIVE legs.marginToTrade) across the trade's lifetime. */
    @Column(name = "PEAK_MARGIN")
    private Double peakMargin;

    @Setter(AccessLevel.NONE)
    @OneToMany(mappedBy = "trade", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @Filter(name = "liveOrderBooks", condition = "TRADE_STATUS = :status")
    private List<WeeklyOrderBook> weeklyOrderBook;

    @Setter(AccessLevel.NONE)
    @OneToMany(mappedBy = "trade", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @Filter(name = "liveOrderBooks", condition = "TRADE_STATUS = :status")
    private List<MonthlyOrderBook> monthlyOrderBook;

    public Trade() {
        this.weeklyOrderBook  = new ArrayList<>();
        this.monthlyOrderBook = new ArrayList<>();
    }

    public Trade(SignalController.Signal signal) {
        this.lastApiAction     = signal.action;
        this.lastApiSignalType = signal.signalType;
        this.statergyName      = signal.strategyName;
        this.apiTime           = signal.time;
        this.weeklyOrderBook   = new ArrayList<>();
        this.monthlyOrderBook  = new ArrayList<>();
    }

    // addAll semantics preserved — Lombok setter suppressed on these two fields above
    public void setWeeklyOrderBook(List<WeeklyOrderBook> weeklyOrderBook) {
        this.weeklyOrderBook.addAll(weeklyOrderBook);
    }

    public void setMonthlyOrderBook(List<MonthlyOrderBook> monthlyOrderBook) {
        this.monthlyOrderBook.addAll(monthlyOrderBook);
    }

    @PrePersist
    public void onCreate() {
        this.tradeOpenDtTime = LocalDateTime.now(ZoneId.of(ZONE_ID))
                .format(DateTimeFormatter.ofPattern(DATE_FORMAT));
    }
}
