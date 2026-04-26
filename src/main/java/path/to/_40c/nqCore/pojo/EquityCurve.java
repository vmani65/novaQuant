package path.to._40c.nqCore.pojo;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class EquityCurve {

    private Double startingEquity;
    private Double currentEquity;
    private List<String> dates;
    private List<Double> equity;
    private List<Integer> lotSize;
    private List<String> outcomes;
    private List<Double> points;

    public EquityCurve() {}
}
