package path.to._40c.pojo;

import java.util.List;

import com.zerodhatech.models.BulkOrderResponse;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import path.to._40c.entity.Trade;

@Getter
@Setter
@ToString(exclude = "orderResponse")
public class WeeklyPojo {

    private String tradedSymbol;
    private String marginCalcSymbol;
    private String transactionType;
    private String moneyness;
    private String optionType;
    private Trade parentTrade;
    private int lots;
    private String tradeOpenOrderId;
    private List<BulkOrderResponse> orderResponse;

    public WeeklyPojo() {}
}
