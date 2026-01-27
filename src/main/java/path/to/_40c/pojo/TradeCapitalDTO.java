package path.to._40c.pojo;

public class TradeCapitalDTO {
    
    private Double currentCapital;
    private Double additionalCapital;
    private Double ceilingToHit;
    private Integer nrmlCostPerLot;
    private Integer definedRiskPerLot;

    public TradeCapitalDTO() {}

    public TradeCapitalDTO(Double currentCapital, Double additionalCapital, 
                          Double ceilingToHit, Integer nrmlCostPerLot, Integer definedRiskPerLot) {
        this.currentCapital = currentCapital;
        this.additionalCapital = additionalCapital;
        this.ceilingToHit = ceilingToHit;
        this.nrmlCostPerLot = nrmlCostPerLot;
        this.definedRiskPerLot = definedRiskPerLot;
    }

    public Double getCurrentCapital() {
        return currentCapital;
    }

    public void setCurrentCapital(Double currentCapital) {
        this.currentCapital = currentCapital;
    }

    public Double getAdditionalCapital() {
        return additionalCapital;
    }

    public void setAdditionalCapital(Double additionalCapital) {
        this.additionalCapital = additionalCapital;
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

    @Override
    public String toString() {
        return "TradeCapitalDTO{" + "currentCapital=" + currentCapital + ", additionalCapital=" + additionalCapital + ", ceilingToHit=" + ceilingToHit +
                ", nrmlCostPerLot=" + nrmlCostPerLot + ", definedRiskPerLot=" + definedRiskPerLot +'}';
    }
}

