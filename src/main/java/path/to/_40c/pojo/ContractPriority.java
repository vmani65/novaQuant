package path.to._40c.pojo;

public class ContractPriority {
    private final String positionSide;   // LONG
    private final String optionType;     // CALL / PUT
    private final String actionType;     // BUY / SELL
    private final int lots; 
    private final String strike;

    public ContractPriority(String positionSide, String optionType, String actionType, int lots, String strike) {
        this.positionSide = positionSide;
        this.optionType = optionType;
        this.actionType = actionType;
        this.lots = lots;
        this.strike = strike;
    }

    public String getPositionSide() { return positionSide; }
    public String getOptionType() { return optionType; }
    public String getActionType() { return actionType; }
    public int getLots() { return lots; }
	public String getStrike() { return strike;	}

	@Override
	public String toString() {
		return "ContractPriority [positionSide=" + positionSide + ", optionType=" + optionType + ", actionType="
				+ actionType + ", lots=" + lots + ", strike=" + strike + "]";
	}
}
