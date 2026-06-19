package path.to._40c.nqCore.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
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

    @Column(name = "defined_risk_per_lot")
    private Integer definedRiskPerLot;

    @Column(name = "current_risk_per_lot")
    private Integer currentRiskPerLot;

    @Column(name = "possible_lots", nullable = true)
    private Integer possibleLots;

    /** Derived (not persisted): max peak_margin/lot across trades closed in last 30 days. */
    @Transient
    private Integer highestNrmlCostPerLot;

    /** Derived (not persisted): mean peak_margin/lot across trades closed in last 30 days. */
    @Transient
    private Integer averageNrmlCostPerLot;

    /** Derived (not persisted): min peak_margin/lot across trades closed in last 30 days. */
    @Transient
    private Integer lowestNrmlCostPerLot;

    public TradeCapital() {}

    public TradeCapital(Long id, Double currentCapital, Double ceilingToHit,
                        Integer definedRiskPerLot, Integer possibleLots) {
        this.id = 1L;
        this.currentCapital    = currentCapital;
        this.ceilingToHit      = ceilingToHit;
        this.definedRiskPerLot = definedRiskPerLot;
        this.possibleLots      = possibleLots;
    }
}
