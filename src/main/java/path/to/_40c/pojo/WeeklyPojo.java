package path.to._40c.pojo;

import java.util.List;

import com.zerodhatech.models.BulkOrderResponse;

import path.to._40c.entity.Trade;

public class WeeklyPojo {
	private String tradedSymbol;
	private String marginCalcSymbol;
	private String transactionType;
	private String moneyness;
	private Trade parentTrade;
	private int lots;
	private String tradeOpenOrderId;
	private List<BulkOrderResponse> orderResponse;
	
	public WeeklyPojo(String tradedSymbol, String marginCalcSymbol, String transactionType, String moneyness, Trade parentTrade) {
		super();
		this.tradedSymbol = tradedSymbol;
		this.marginCalcSymbol = marginCalcSymbol;
		this.transactionType = transactionType;
		this.moneyness = moneyness;
		this.parentTrade = parentTrade;
	}
	
	public WeeklyPojo() {
		
	}

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

	public String getTransactionType() {
		return transactionType;
	}

	public void setTransactionType(String transactionType) {
		this.transactionType = transactionType;
	}

	public String getMoneyness() {
		return moneyness;
	}

	public void setMoneyness(String moneyness) {
		this.moneyness = moneyness;
	}

	public Trade getParentTrade() {
		return parentTrade;
	}

	public void setParentTrade(Trade parentTrade) {
		this.parentTrade = parentTrade;
	}

	public int getLots() {
		return lots;
	}

	public void setLots(int lots) {
		this.lots = lots;
	}

	public String getTradeOpenOrderId() {
		return tradeOpenOrderId;
	}

	public void setTradeOpenOrderId(String tradeOpenOrderId) {
		this.tradeOpenOrderId = tradeOpenOrderId;
	}

	public List<BulkOrderResponse> getOrderResponse() {
		return orderResponse;
	}

	public void setOrderResponse(List<BulkOrderResponse> orderResponse) {
		this.orderResponse = orderResponse;
	}

	@Override
	public String toString() {
		return "WeeklyPojo [tradedSymbol=" + tradedSymbol + ", marginCalcSymbol=" + marginCalcSymbol
				+ ", transactionType=" + transactionType + ", moneyness=" + moneyness + ", parentTrade=" + parentTrade
				+ ", lots=" + lots + ", tradeOpenOrderId=" + tradeOpenOrderId + ", orderResponse=" + orderResponse
				+ "]";
	}
}

