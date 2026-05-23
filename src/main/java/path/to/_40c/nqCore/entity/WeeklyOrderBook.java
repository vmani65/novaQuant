package path.to._40c.nqCore.entity;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "WEEKLY_ORDER_BOOK")
@Getter
@Setter
public class WeeklyOrderBook extends BaseChildEntity {

    @ManyToOne
    @JoinColumn(name = "trade_id", nullable = false)
    private Trade trade;

    @OneToMany(mappedBy = "weeklyOrderBook", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<WeeklyOrderFill> fills = new ArrayList<>();

    public WeeklyOrderBook() {
        expectedPnL = 0.0d;
        actualPnL   = 0.0d;
    }
}
