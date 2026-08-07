package path.to._40c.nqCore.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import static path.to._40c.nqCore.util.Constants.WEEKLY;

/**
 * One row per expiry scope. Row id=1 (scope WEEKLY) drives the synthetic strategy's
 * weekly contracts and the rollover automation; row id=2 (scope MONTHLY) holds the
 * monthly-option-buying strategy's contract symbols. Fixed ids keep the long-standing
 * findById(1L) weekly paths untouched while the two scopes save independently.
 */
@Entity
@Table(name = "WEEKLY_SYMBOL")
@Getter
@Setter
@ToString
public class SymbolConfig {

    public static final long WEEKLY_ID  = 1L;
    public static final long MONTHLY_ID = 2L;

    @Id
    private Long id = WEEKLY_ID;

    @Column(name = "this_week_symbol", nullable = false)
    private String thisWeekSymbol;

    @Column(name = "rollover_symbol", nullable = false)
    private String rolloverSymbol;

    @Column(name = "rollover_day")
    private LocalDate rolloverDay;

    @Column(name = "rollover_complete")
    private Boolean rolloverComplete = false;

    @Column(name = "scope")
    private String scope = WEEKLY;

    public SymbolConfig() {}

    public SymbolConfig(String thisWeekSymbol, String rolloverSymbol) {
        this.id              = WEEKLY_ID;
        this.thisWeekSymbol  = thisWeekSymbol;
        this.rolloverSymbol  = rolloverSymbol;
        this.scope           = WEEKLY;
    }

    public SymbolConfig(long id, String scope, String thisWeekSymbol, String rolloverSymbol) {
        this.id              = id;
        this.scope           = scope;
        this.thisWeekSymbol  = thisWeekSymbol;
        this.rolloverSymbol  = rolloverSymbol;
    }
}
