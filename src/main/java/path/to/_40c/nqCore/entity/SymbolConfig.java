package path.to._40c.nqCore.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Entity
@Table(name = "SYMBOL")
@Getter
@Setter
@ToString
public class SymbolConfig {

    @Id
    private Long id = 1L;

    @Column(name = "this_week_symbol", nullable = false)
    private String thisWeekSymbol;

    @Column(name = "rollover_symbol", nullable = false)
    private String rolloverSymbol;

    @Column(name = "rollover_day")
    private LocalDate rolloverDay;

    @Column(name = "rollover_complete")
    private Boolean rolloverComplete = false;

    public SymbolConfig() {}

    public SymbolConfig(String thisWeekSymbol, String rolloverSymbol) {
        this.id              = 1L;
        this.thisWeekSymbol  = thisWeekSymbol;
        this.rolloverSymbol  = rolloverSymbol;
    }
}
