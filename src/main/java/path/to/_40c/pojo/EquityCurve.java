package path.to._40c.pojo;

import java.util.List;

public class EquityCurve {
	
    private Double startingEquity;
    private Double currentEquity;
    private List<String> dates;
    private List<Double> equity;
    private List<Integer> lotSize;
    
	public EquityCurve() {
		super();
	}
	
	public EquityCurve(Double startingEquity, Double currentEquity, List<String> dates, List<Double> equity, List<Integer> lotSize) {
		super();
		this.startingEquity = startingEquity;
		this.currentEquity = currentEquity;
		this.dates = dates;
		this.equity = equity;
		this.lotSize = lotSize;
	}
	
	public Double getStartingEquity() {
		return startingEquity;
	}
	public void setStartingEquity(Double startingEquity) {
		this.startingEquity = startingEquity;
	}
	public Double getCurrentEquity() {
		return currentEquity;
	}
	public void setCurrentEquity(Double currentEquity) {
		this.currentEquity = currentEquity;
	}
	public List<String> getDates() {
		return dates;
	}
	public void setDates(List<String> dates) {
		this.dates = dates;
	}
	public List<Double> getEquity() {
		return equity;
	}
	public void setEquity(List<Double> equity) {
		this.equity = equity;
	}
	public List<Integer> getLotSize() {
		return lotSize;
	}
	public void setLotSize(List<Integer> lotSize) {
		this.lotSize = lotSize;
	}
	
	@Override
	public String toString() {
		return "EquityCurve [startingEquity=" + startingEquity + ", currentEquity=" + currentEquity + ", dates="
				+ dates + ", equity=" + equity + ", lotSize=" + lotSize + "]";
	}    
}

