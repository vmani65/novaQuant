package path.to._40c.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "MONTHLY_ORDER_BOOK")
@Getter
@Setter
public class MonthlyOrderBook extends BaseChildEntity {

    @ManyToOne
    @JoinColumn(name = "trade_id", nullable = false)
    private Trade trade;

    public MonthlyOrderBook() {
        expectedPnL = 0.0d;
        actualPnL   = 0.0d;
    }
}
