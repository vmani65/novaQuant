package path.to._40c.nqCore.pojo;

import lombok.Value;

@Value
public class TradeLegConfig {
    String positionSide;
    String optionType;
    String actionType;
    int    lots;
    String strike;
}
