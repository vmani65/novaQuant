package path.to._40c.nqCore.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * One row per leg in a strategy's template. The leg-list for a direction is the recipe
 * for how to construct a multi-leg position when a signal for that direction fires.
 *
 * Example — long synthetic at ATM (2 rows):
 *   LONG / CE / BUY  / offset=0   / lots=10
 *   LONG / PE / SELL / offset=0   / lots=10
 *
 * Example — add ATM-50 wings (2 more rows for a 4-leg LONG):
 *   LONG / CE / BUY  / offset=-50 / lots=5
 *   LONG / PE / SELL / offset=-50 / lots=5
 *
 * offsetPts is signed: -50 = strike 50pts below ATM, +50 = strike 50pts above ATM.
 * Strike at signal time = ATM + offsetPts. lots > 0.
 */
@Entity
@Table(name = "LEG_TEMPLATE")
@Getter
@Setter
@ToString
public class LegTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "DIRECTION", length = 10, nullable = false)
    private String direction;

    @Column(name = "OPTION_TYPE", length = 10, nullable = false)
    private String optionType;

    @Column(name = "SIDE", length = 10, nullable = false)
    private String side;

    @Column(name = "OFFSET_PTS", nullable = false)
    private Integer offsetPts = 0;

    @Column(name = "LOTS", nullable = false)
    private Integer lots;

    public LegTemplate() {}

    public LegTemplate(String direction, String optionType, String side, Integer offsetPts, Integer lots) {
        this.direction  = direction;
        this.optionType = optionType;
        this.side       = side;
        this.offsetPts  = offsetPts == null ? 0 : offsetPts;
        this.lots       = lots;
    }
}
