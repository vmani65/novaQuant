package path.to._40c.repo;

import static path.to._40c.util.Constants.ATM;
import static path.to._40c.util.Constants.LONG;
import static path.to._40c.util.Constants.SHORT;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import path.to._40c.entity.PositionSizeMatrix;
import path.to._40c.pojo.ContractPriority;

public interface ContractPriorityRepository extends JpaRepository<PositionSizeMatrix, Long> {

	@Query("SELECT p FROM position_size_matrix p WHERE p.positionSide = :side")
    List<PositionSizeMatrix> findByPositionSide(@Param("side") String side);
    
    default List<ContractPriority> findLongContractQty() {
        return buildContractPriorities(findByPositionSide(LONG));
    }

    default List<ContractPriority> findShortContractQty() {
        return buildContractPriorities(findByPositionSide(SHORT));
    }

    private static List<ContractPriority> buildContractPriorities(List<PositionSizeMatrix> rows) {
        List<ContractPriority> out = new ArrayList<>();
        for (PositionSizeMatrix r : rows) {
            boolean isLong = LONG.equals(r.getPositionSide());
            addIfPositive(out, r, r.getAtm(),     ATM);
            addIfPositive(out, r, r.getOffset1(), isLong ? "ATM-50"  : "ATM+50");
            addIfPositive(out, r, r.getOffset2(), isLong ? "ATM-100" : "ATM+100");
            addIfPositive(out, r, r.getOffset3(), isLong ? "ATM-150" : "ATM+150");
        }
        return out;
    }
    
    private static void addIfPositive(List<ContractPriority> list, PositionSizeMatrix r, Integer lots, String strike) {
        if (lots != null && lots > 0) {
            list.add(new ContractPriority(r.getPositionSide(), r.getOptionType(), r.getActionType(), lots, strike));
        }
    }
}
