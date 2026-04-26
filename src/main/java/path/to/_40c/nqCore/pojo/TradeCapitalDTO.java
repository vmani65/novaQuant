package path.to._40c.nqCore.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class TradeCapitalDTO {

    private Double currentCapital;
    private Double additionalCapital;
    private Double ceilingToHit;
    private Integer nrmlCostPerLot;
    private Integer definedRiskPerLot;

    public TradeCapitalDTO() {}
}
