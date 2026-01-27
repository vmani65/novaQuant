package path.to._40c.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;

@MappedSuperclass
public abstract class BaseChildEntity extends BaseEntity {

    @Column(name = "TRADED_SYMBOL")
    protected String tradedSymbol;
    
    @Column(name = "MARGIN_CALC_SYMBOL")
    protected String marginCalcSymbol;
    
    @Column(name = "Td_STREAM_SYMBOL")
    protected String tdStreamSymbol;
    
    @Column(name = "TRANSACTION_TYPE")
    protected String transactionType;
   
	@Column(name = "SOLD_PRICE")
    protected Double soldPrice;
    
	@Column(name = "BOUGHT_PRICE")
    protected Double boughtPrice;
	
	@Column(name = "LTP")
    protected Double ltp;
       
    @Column(name = "EXPECTED_P_L")
    protected Double expectedPnL;
    
    @Column(name = "ACTUAL_P_L")
    protected Double actualPnL;
    
    @Column(name = "DIFF_PERCENTAGE")
    protected String diffPercentage;  
    
    @Column(name = "MONEYNESS")
    protected String moneyness;
    
	@Column(name = "LOTS")
    protected Integer lots;

    @Column(name = "QUANTITY")
    protected Integer quantity;

    @Column(name = "MARGIN_TO_TRADE")
    protected Double marginToTrade;
    
    @Column(name = "TRADE_STATUS")
    protected String tradeStatus;
    
    @Column(name = "TRADE_OPEN_BROKERAGE")
    protected Double tradeOpenBrokerage;
    
    @Column(name = "TRADE_CLOSE_BROKERAGE")
    protected Double tradeCloseBrokerage;
    
    @Column(name = "TRADE_OPEN_ORDER_ID")
    protected String tradeOpenOrderId;
    
    @Column(name = "TRADE_CLOSE_ORDER_ID")
    protected String tradeCloseOrderId;
    
    @Column(name = "TRADE_OPEN_EXE_STARTTIME")
    protected String tradeOpenExeStartTime;
    
    @Column(name = "TRADE_OPEN_EXE_ENDTIME")
    protected String tradeOpenExeEndTime;
    
    @Column(name = "TRADE_CLOSE_EXE_STARTTIME")
    protected String tradeCloseExeStartTime;
    
    @Column(name = "TRADE_CLOSE_EXE_ENDTIME")
    protected String tradeCloseExeEndTime;

	public String getTradedSymbol() {
		return tradedSymbol;
	}

	public void setTradedSymbol(String tradedSymbol) {
		this.tradedSymbol = tradedSymbol;
	}

	public String getMarginCalcSymbol() {
		return marginCalcSymbol;
	}

	public void setMarginCalcSymbol(String marginCalcSymbol) {
		this.marginCalcSymbol = marginCalcSymbol;
	}
	
	public String getTdStreamSymbol() {
		return tdStreamSymbol;
	}

	public void setTDStreamSymbol(String tdStreamSymbol) {
		this.tdStreamSymbol = tdStreamSymbol;
	}

	public String getTransactionType() {
		return transactionType;
	}

	public void setTransactionType(String transactionType) {
		this.transactionType = transactionType;
	}

	public Double getSoldPrice() {
		return soldPrice;
	}

	public void setSoldPrice(Double soldPrice) {
		this.soldPrice = soldPrice;
	}

	public Double getBoughtPrice() {
		return boughtPrice;
	}

	public void setBoughtPrice(Double boughtPrice) {
		this.boughtPrice = boughtPrice;
	}

	public Double getLtp() {
		return ltp;
	}

	public void setLtp(Double ltp) {
		this.ltp = ltp;
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

	public String getDiffPercentage() {
		return diffPercentage;
	}

	public void setDiffPercentage(String diffPercentage) {
		this.diffPercentage = diffPercentage;
	}

	public String getMoneyness() {
		return moneyness;
	}

	public void setMoneyness(String moneyness) {
		this.moneyness = moneyness;
	}

	public Integer getLots() {
		return lots;
	}

	public void setLots(Integer lots) {
		this.lots = lots;
	}

	public Integer getQuantity() {
		return quantity;
	}

	public void setQuantity(Integer quantity) {
		this.quantity = quantity;
	}

	public Double getMarginToTrade() {
		return marginToTrade;
	}

	public void setMarginToTrade(Double marginToTrade) {
		this.marginToTrade = marginToTrade;
	}

	public String getTradeStatus() {
		return tradeStatus;
	}

	public void setTradeStatus(String tradeStatus) {
		this.tradeStatus = tradeStatus;
	}

	public void setTdStreamSymbol(String tdStreamSymbol) {
		this.tdStreamSymbol = tdStreamSymbol;
	}

	public Double getTradeOpenBrokerage() {
		return tradeOpenBrokerage;
	}

	public void setTradeOpenBrokerage(Double tradeOpenBrokerage) {
		this.tradeOpenBrokerage = tradeOpenBrokerage;
	}

	public Double getTradeCloseBrokerage() {
		return tradeCloseBrokerage;
	}

	public void setTradeCloseBrokerage(Double tradeCloseBrokerage) {
		this.tradeCloseBrokerage = tradeCloseBrokerage;
	}

	public String getTradeOpenOrderId() {
		return tradeOpenOrderId;
	}

	public void setTradeOpenOrderId(String tradeOpenOrderId) {
		this.tradeOpenOrderId = tradeOpenOrderId;
	}

	public String getTradeCloseOrderId() {
		return tradeCloseOrderId;
	}

	public void setTradeCloseOrderId(String tradeCloseOrderId) {
		this.tradeCloseOrderId = tradeCloseOrderId;
	}

	public String getTradeOpenExeStartTime() {
		return tradeOpenExeStartTime;
	}

	public void setTradeOpenExeStartTime(String tradeOpenExeStartTime) {
		this.tradeOpenExeStartTime = tradeOpenExeStartTime;
	}

	public String getTradeOpenExeEndTime() {
		return tradeOpenExeEndTime;
	}

	public void setTradeOpenExeEndTime(String tradeOpenExeEndTime) {
		this.tradeOpenExeEndTime = tradeOpenExeEndTime;
	}

	public String getTradeCloseExeStartTime() {
		return tradeCloseExeStartTime;
	}

	public void setTradeCloseExeStartTime(String tradeCloseExeStartTime) {
		this.tradeCloseExeStartTime = tradeCloseExeStartTime;
	}

	public String getTradeCloseExeEndTime() {
		return tradeCloseExeEndTime;
	}

	public void setTradeCloseExeEndTime(String tradeCloseExeEndTime) {
		this.tradeCloseExeEndTime = tradeCloseExeEndTime;
	}

	@Override
	public String toString() {
		return "BaseChildEntity [tradedSymbol=" + tradedSymbol + ", marginCalcSymbol=" + marginCalcSymbol
				+ ", tdStreamSymbol=" + tdStreamSymbol + ", transactionType=" + transactionType + ", soldPrice="
				+ soldPrice + ", boughtPrice=" + boughtPrice + ", ltp=" + ltp + ", expectedPnL=" + expectedPnL
				+ ", actualPnL=" + actualPnL + ", diffPercentage=" + diffPercentage + ", moneyness=" + moneyness
				+ ", lotSize=" + lots + ", quantity=" + quantity + ", marginToTrade=" + marginToTrade
				+ ", tradeStatus=" + tradeStatus + ", tradeOpenBrokerage=" + tradeOpenBrokerage
				+ ", tradeCloseBrokerage=" + tradeCloseBrokerage + ", tradeOpenOrderId=" + tradeOpenOrderId
				+ ", tradeCloseOrderId=" + tradeCloseOrderId + ", tradeOpenExeStartTime=" + tradeOpenExeStartTime
				+ ", tradeOpenExeEndTime=" + tradeOpenExeEndTime + ", tradeCloseExeStartTime=" + tradeCloseExeStartTime
				+ ", tradeCloseExeEndTime=" + tradeCloseExeEndTime + "]";
	}
}
