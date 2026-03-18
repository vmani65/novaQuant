package path.to._40c.entity;

import jakarta.persistence.*;

@Entity(name = "position_size_matrix")
@Table(name = "POSITION_SIZE_MATRIX")
public class PositionSizeMatrix {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "POSITION_SIDE", length = 10, nullable = false)
    private String positionSide;      // LONG / SHORT

    @Column(name = "OPTION_TYPE", length = 10, nullable = false)
    private String optionType;        // CALL / PUT

    @Column(name = "ACTION_TYPE", length = 10, nullable = false)
    private String actionType;        // BUY / SELL

    @Column(name = "ATM")
    private Integer atm;

    @Column(name = "OFFSET1")
    private Integer offset1;

    @Column(name = "OFFSET2")
    private Integer offset2;

    @Column(name = "OFFSET3")
    private Integer offset3;

	public PositionSizeMatrix() {
		super();
	}

	public PositionSizeMatrix(String positionSide, String optionType, String actionType,
	                          Integer atm, Integer offset1, Integer offset2, Integer offset3) {
		this.positionSide = positionSide;
		this.optionType = optionType;
		this.actionType = actionType;
		this.atm = atm;
		this.offset1 = offset1;
		this.offset2 = offset2;
		this.offset3 = offset3;
	}

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getPositionSide() {
		return positionSide;
	}

	public void setPositionSide(String positionSide) {
		this.positionSide = positionSide;
	}

	public String getOptionType() {
		return optionType;
	}

	public void setOptionType(String optionType) {
		this.optionType = optionType;
	}

	public String getActionType() {
		return actionType;
	}

	public void setActionType(String actionType) {
		this.actionType = actionType;
	}

	public Integer getAtm() { return atm; }
	public void setAtm(Integer atm) { this.atm = atm; }

	public Integer getOffset1() { return offset1; }
	public void setOffset1(Integer offset1) { this.offset1 = offset1; }

	public Integer getOffset2() { return offset2; }
	public void setOffset2(Integer offset2) { this.offset2 = offset2; }

	public Integer getOffset3() { return offset3; }
	public void setOffset3(Integer offset3) { this.offset3 = offset3; }

	@Override
	public String toString() {
		return "PositionSizeMatrix [id=" + id + ", positionSide=" + positionSide + ", optionType=" + optionType
				+ ", actionType=" + actionType + ", atm=" + atm
				+ ", offset1=" + offset1 + ", offset2=" + offset2 + ", offset3=" + offset3 + "]";
	}
}
