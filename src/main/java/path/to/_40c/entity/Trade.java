package path.to._40c.entity;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.*;

import org.hibernate.annotations.Filter;
import path.to._40c.controller.SignalController;

import static path.to._40c.util.Constants.DATE_FORMAT;
import static path.to._40c.util.Constants.ZONE_ID;

import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

@FilterDef(name = "liveOrderBooks", parameters = @ParamDef(name = "status", type = String.class))
@Entity
@Table(name = "TRADE")
public class Trade extends BaseEntity{

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
    
	@OneToMany(mappedBy = "trade", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @Filter(name = "liveOrderBooks", condition = "TRADE_STATUS = :status")
    private List<WeeklyOrderBook> weeklyOrderBook;
    
    @OneToMany(mappedBy = "trade", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @Filter(name = "liveOrderBooks", condition = "TRADE_STATUS = :status")
    private List<MonthlyOrderBook> monthlyOrderBook;

    public Trade() {
    	this.weeklyOrderBook = new ArrayList<WeeklyOrderBook>();
    	this.monthlyOrderBook = new ArrayList<MonthlyOrderBook>();
    }
    
    public Trade(SignalController.Signal signal) {
    	this.lastApiAction = signal.action;
    	this.lastApiSignalType = signal.signalType;
    	this.statergyName = signal.strategyName;
    	this.apiTime = signal.time;
    	this.weeklyOrderBook = new ArrayList<WeeklyOrderBook>();
    	this.monthlyOrderBook = new ArrayList<MonthlyOrderBook>();
    }
    
	public String getStrategyId() {
		return strategyId;
	}

	public void setStrategyId(String strategyId) {
		this.strategyId = strategyId;
	}

	public String getAccount() {
		return account;
	}

	public void setAccount(String account) {
		this.account = account;
	}

	public Double getEntrySignalPrice() {
		return entrySignalPrice;
	}

	public void setEntrySignalPrice(Double entrySignalPrice) {
		this.entrySignalPrice = entrySignalPrice;
	}

	public Double getExitSignalPrice() {
		return exitSignalPrice;
	}

	public void setExitSignalPrice(Double exitSignalPrice) {
		this.exitSignalPrice = exitSignalPrice;
	}

	public String getSignalType() {
		return signalType;
	}

	public void setSignalType(String signalType) {
		this.signalType = signalType;
	}

	public Double getPointsByTrade() {
		return pointsByTrade;
	}

	public void setPointsByTrade(Double pointsByTrade) {
		this.pointsByTrade = pointsByTrade;
	}

	public String getTradeOutcome() {
		return tradeOutcome;
	}

	public void setTradeOutcome(String tradeOutcome) {
		this.tradeOutcome = tradeOutcome;
	}

	public Double getStartingCapital() {
		return startingCapital;
	}

	public void setStartingCapital(Double startingCapital) {
		this.startingCapital = startingCapital;
	}

	public Double getEndingCapital() {
		return endingCapital;
	}

	public void setEndingCapital(Double endingCapital) {
		this.endingCapital = endingCapital;
	}

	public Double getExpectedPnL() {
		return expectedPnL;
	}

	public void setExpectedPnL(Double expectedPnL) {
		this.expectedPnL = expectedPnL;
	}

	public Double getActualPnL() {
		return actualPnL;
	}

	public void setActualPnL(Double actualPnL) {
		this.actualPnL = actualPnL;
	}

	public Double getBrokerage() {
		return brokerage;
	}

	public void setBrokerage(Double brokerage) {
		this.brokerage = brokerage;
	}
	
	public Integer getLots() {
		return lots;
	}

	public void setLots(Integer lots) {
		this.lots = lots;
	}

	public String getDiffPercentage() {
		return diffPercentage;
	}

	public void setDiffPercentage(String diffPercentage) {
		this.diffPercentage = diffPercentage;
	}

	public String getTradeStatus() {
		return tradeStatus;
	}

	public void setTradeStatus(String tradeStatus) {
		this.tradeStatus = tradeStatus;
	}

	public String getTradeOpenDtTime() {
		return tradeOpenDtTime;
	}

	public void setTradeOpenDtTime(String tradeOpenDtTime) {
		this.tradeOpenDtTime = tradeOpenDtTime;
	}

	public String getTradeCloseDtTime() {
		return tradeCloseDtTime;
	}

	public void setTradeCloseDtTime(String tradeCloseDtTime) {
		this.tradeCloseDtTime = tradeCloseDtTime;
	}
	
	public String getLastApiAction() {
		return lastApiAction;
	}

	public void setLastApiAction(String lastApiAction) {
		this.lastApiAction = lastApiAction;
	}

	public String getLastApiSignalType() {
		return lastApiSignalType;
	}

	public void setLastApiSignalType(String lastApiSignalType) {
		this.lastApiSignalType = lastApiSignalType;
	}

	public String getStatergyName() {
		return statergyName;
	}

	public void setStatergyName(String statergyName) {
		this.statergyName = statergyName;
	}

	public String getApiTime() {
		return apiTime;
	}

	public void setApiTime(String apiTime) {
		this.apiTime = apiTime;
	}
	
	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}
	
	public List<WeeklyOrderBook> getWeeklyOrderBook() {
		return weeklyOrderBook;
	}

	public void setWeeklyOrderBook(List<WeeklyOrderBook> weeklyOrderBook) {
		this.weeklyOrderBook.addAll(weeklyOrderBook);
	}

	public List<MonthlyOrderBook> getMonthlyOrderBook() {
		return monthlyOrderBook;
	}

	public void setMonthlyOrderBook(List<MonthlyOrderBook> monthlyOrderBook) {
		this.monthlyOrderBook.addAll(monthlyOrderBook);
	}

	@PrePersist
	public void onCreate() {
		this.tradeOpenDtTime = LocalDateTime.now(ZoneId.of(ZONE_ID)).format(DateTimeFormatter.ofPattern(DATE_FORMAT));
	}

	@Override
	public String toString() {
		return "Trade [strategyId=" + strategyId + ", account=" + account + ", entrySignalPrice=" + entrySignalPrice
				+ ", exitSignalPrice=" + exitSignalPrice + ", signalType=" + signalType + ", pointsByTrade="
				+ pointsByTrade + ", tradeOutcome=" + tradeOutcome + ", startingCapital=" + startingCapital
				+ ", endingCapital=" + endingCapital + ", expectedPnL=" + expectedPnL + ", actualPnL=" + actualPnL
				+ ", brokerage=" + brokerage + ", lots=" + lots + ", diffPercentage=" + diffPercentage
				+ ", tradeStatus=" + tradeStatus + ", tradeOpenDtTime=" + tradeOpenDtTime + ", tradeCloseDtTime="
				+ tradeCloseDtTime + ", lastApiAction=" + lastApiAction + ", lastApiSignalType=" + lastApiSignalType
				+ ", statergyName=" + statergyName + ", apiTime=" + apiTime + ", message=" + message
				+ ", weeklyOrderBook=" + weeklyOrderBook + ", monthlyOrderBook=" + monthlyOrderBook + "]";
	}
} 