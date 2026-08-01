package path.to._40c.nqCore.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Registry row for a signal-emitting strategy. Every inbound signal's strategyName must match
 * an enabled row here, so a typo'd or retired AFL can never open an untracked book. Config
 * columns (lots, maxStrikeOffset, recenterMinProfit) are per-strategy overrides consumed by
 * later phases of the multi-strategy build; null means "use the global default".
 */
@Entity
@Table(name = "STRATEGY")
@Getter
@Setter
@ToString
public class Strategy {

    @Id
    @Column(name = "NAME")
    private String name;

    @Column(name = "ENABLED")
    private Boolean enabled = Boolean.TRUE;

    @Column(name = "LOTS")
    private Integer lots;

    @Column(name = "MAX_STRIKE_OFFSET")
    private Integer maxStrikeOffset = 150;

    @Column(name = "RECENTER_MIN_PROFIT")
    private Double recenterMinProfit = 450.0;
}
