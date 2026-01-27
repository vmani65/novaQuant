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

    @Column(name = "ATM_PLUS_150")
    private Integer atmPlus150;

    @Column(name = "ATM_PLUS_200")
    private Integer atmPlus200;
    
    @Column(name = "ATM_PLUS_250")
    private Integer atmPlus250;

    @Column(name = "ATM_MINUS_150")
    private Integer atmMinus150;

    @Column(name = "ATM_MINUS_200")
    private Integer atmMinus200;
    
    @Column(name = "ATM_MINUS_250")
    private Integer atmMinus250;

	public PositionSizeMatrix() {
		super();
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

	public Integer getAtm() {
		return atm;
	}

	public void setAtm(Integer atm) {
		this.atm = atm;
	}

	public Integer getAtmPlus150() {
		return atmPlus150;
	}

	public void setAtmPlus150(Integer atmPlus150) {
		this.atmPlus150 = atmPlus150;
	}

	public Integer getAtmPlus200() {
		return atmPlus200;
	}

	public void setAtmPlus200(Integer atmPlus200) {
		this.atmPlus200 = atmPlus200;
	}

	public Integer getAtmPlus250() {
		return atmPlus250;
	}

	public void setAtmPlus250(Integer atmPlus250) {
		this.atmPlus250 = atmPlus250;
	}

	public Integer getAtmMinus150() {
		return atmMinus150;
	}

	public void setAtmMinus150(Integer atmMinus150) {
		this.atmMinus150 = atmMinus150;
	}

	public Integer getAtmMinus200() {
		return atmMinus200;
	}

	public void setAtmMinus200(Integer atmMinus200) {
		this.atmMinus200 = atmMinus200;
	}

	public Integer getAtmMinus250() {
		return atmMinus250;
	}

	public void setAtmMinus250(Integer atmMinus250) {
		this.atmMinus250 = atmMinus250;
	}

	@Override
	public String toString() {
		return "PositionSizeMatrix [id=" + id + ", positionSide=" + positionSide + ", optionType=" + optionType
				+ ", actionType=" + actionType + ", atm=" + atm + ", atmPlus150=" + atmPlus150 + ", atmPlus200="
				+ atmPlus200 + ", atmPlus250=" + atmPlus250 + ", atmMinus150=" + atmMinus150 + ", atmMinus200="
				+ atmMinus200 + ", atmMinus250=" + atmMinus250 + "]";
	}
}
