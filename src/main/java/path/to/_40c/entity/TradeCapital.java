package path.to._40c.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "TRADE_CAPITAL")
public class TradeCapital {

    @Id
    private Long id = 1L;

    @Column(name = "current_capital", nullable = false)
    private Double currentCapital;

    @Column(name = "ceiling_to_hit", nullable = false)
    private Double ceilingToHit;
    
    @Column(name = "nrml_cost_per_lot", nullable = false)
    private Integer nrmlCostPerLot;
    
    @Column(name = "defined_risk_per_lot")
    private Integer definedRiskPerLot;
    
    @Column(name = "current_risk_per_lot")
    private Integer currentRiskPerLot;
    
    @Column(name = "possible_lots", nullable = true)
    private Integer possibleLots;

    public TradeCapital(){}

	public TradeCapital(Long id, Double currentCapital, Double ceilingToHit, Integer nrmlCostPerLot, Integer definedRiskPerLot, Integer possibleLots) {
		this.id = 1L;
		this.currentCapital = currentCapital;
		this.ceilingToHit = ceilingToHit;
		this.nrmlCostPerLot = nrmlCostPerLot;
		this.definedRiskPerLot = definedRiskPerLot;
		this.possibleLots = possibleLots;
	}

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public Double getCurrentCapital() {
		return currentCapital;
	}

	public void setCurrentCapital(Double currentCapital) {
		this.currentCapital = currentCapital;
	}

	public Double getCeilingToHit() {
		return ceilingToHit;
	}

	public void setCeilingToHit(Double ceilingToHit) {
		this.ceilingToHit = ceilingToHit;
	}

	public Integer getNrmlCostPerLot() {
		return nrmlCostPerLot;
	}

	public void setNrmlCostPerLot(Integer nrmlCostPerLot) {
		this.nrmlCostPerLot = nrmlCostPerLot;
	}

	public Integer getDefinedRiskPerLot() {
		return definedRiskPerLot;
	}

	public void setDefinedRiskPerLot(Integer definedRiskPerLot) {
		this.definedRiskPerLot = definedRiskPerLot;
	}
	
	public Integer getCurrentRiskPerLot() {
		return currentRiskPerLot;
	}

	public void setCurrentRiskPerLot(Integer currentRiskPerLot) {
		this.currentRiskPerLot = currentRiskPerLot;
	}

	public Integer getPossibleLots() {
		return possibleLots;
	}

	public void setPossibleLots(Integer possibleLots) {
		this.possibleLots = possibleLots;
	}

	@Override
	public String toString() {
		return "TradeCapital [id=" + id + ", currentCapital=" + currentCapital + ", ceilingToHit=" + ceilingToHit
				+ ", nrmlCostPerLot=" + nrmlCostPerLot + ", definedRiskPerLot=" + definedRiskPerLot + ", currentRiskPerLot=" + currentRiskPerLot + ", possibleLots=" + possibleLots + "]";
	}
}