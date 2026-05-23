package path.to._40c.nqCore.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@MappedSuperclass
@Getter
@Setter
@ToString
public abstract class BaseChildEntity extends BaseEntity {

    @Column(name = "TRADED_SYMBOL")
    protected String tradedSymbol;

    @Column(name = "MARGIN_CALC_SYMBOL")
    protected String marginCalcSymbol;

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

    @Column(name = "BUY_INTENDED_PRICE")
    protected Double buyIntendedPrice;

    @Column(name = "SELL_INTENDED_PRICE")
    protected Double sellIntendedPrice;
}
