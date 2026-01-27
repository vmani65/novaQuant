package path.to._40c.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "SYMBOL")
public class SymbolConfig {

    @Id
    private Long id = 1L;

    @Column(name = "this_week_symbol", nullable = false)
    private String thisWeekSymbol;

    @Column(name = "rollover_symbol", nullable = false)
    private String rolloverSymbol;

    public SymbolConfig() {}
    public SymbolConfig(String thisWeekSymbol, String rolloverSymbol) {
        this.id = 1L;
        this.thisWeekSymbol = thisWeekSymbol;
        this.rolloverSymbol = rolloverSymbol;
    }
	
    public Long getId() {
		return id;
	}
	
	public String getThisWeekSymbol() {
		return thisWeekSymbol;
	}
	
	public void setThisWeekSymbol(String thisWeekSymbol) {
		this.thisWeekSymbol = thisWeekSymbol;
	}
	
	public String getRolloverSymbol() {
		return rolloverSymbol;
	}
	
	public void setRolloverSymbol(String rolloverSymbol) {
		this.rolloverSymbol = rolloverSymbol;
	}
	
	@Override
	public String toString() {
		return "SymbolConfig [id=" + id + ", thisWeekSymbol=" + thisWeekSymbol + ", rolloverSymbol=" + rolloverSymbol + "]";
	}    
}

