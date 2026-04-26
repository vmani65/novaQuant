package path.to._40c.nqCore.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Entity(name = "position_size_matrix")
@Table(name = "POSITION_SIZE_MATRIX")
@Getter
@Setter
@ToString
public class PositionSizeMatrix {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "POSITION_SIDE", length = 10, nullable = false)
    private String positionSide;

    @Column(name = "OPTION_TYPE", length = 10, nullable = false)
    private String optionType;

    @Column(name = "ACTION_TYPE", length = 10, nullable = false)
    private String actionType;

    @Column(name = "ATM")
    private Integer atm;

    @Column(name = "OFFSET1")
    private Integer offset1;

    @Column(name = "OFFSET2")
    private Integer offset2;

    @Column(name = "OFFSET3")
    private Integer offset3;

    public PositionSizeMatrix() {}

    public PositionSizeMatrix(String positionSide, String optionType, String actionType,
                              Integer atm, Integer offset1, Integer offset2, Integer offset3) {
        this.positionSide = positionSide;
        this.optionType   = optionType;
        this.actionType   = actionType;
        this.atm          = atm;
        this.offset1      = offset1;
        this.offset2      = offset2;
        this.offset3      = offset3;
    }
}
