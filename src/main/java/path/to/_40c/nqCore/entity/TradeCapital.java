package path.to._40c.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Entity
@Table(name = "TRADE_CAPITAL")
@Getter
@Setter
@ToString
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

    public TradeCapital() {}

    public TradeCapital(Long id, Double currentCapital, Double ceilingToHit,
                        Integer nrmlCostPerLot, Integer definedRiskPerLot, Integer possibleLots) {
        this.id = 1L;
        this.currentCapital    = currentCapital;
        this.ceilingToHit      = ceilingToHit;
        this.nrmlCostPerLot    = nrmlCostPerLot;
        this.definedRiskPerLot = definedRiskPerLot;
        this.possibleLots      = possibleLots;
    }
}
