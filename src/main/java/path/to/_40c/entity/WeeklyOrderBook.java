package path.to._40c.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "WEEKLY_ORDER_BOOK")
public class WeeklyOrderBook extends BaseChildEntity{
	
    @ManyToOne
    @JoinColumn(name = "trade_id", nullable = false)
    private Trade trade;

    public WeeklyOrderBook(){
    	expectedPnL = 0.0d;
    	actualPnL = 0.0d;    	
    }
    
    public Trade getTrade() {
        return trade;
    }

    public void setTrade(Trade trade) {
        this.trade = trade;
    }

}
