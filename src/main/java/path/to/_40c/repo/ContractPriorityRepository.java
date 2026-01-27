package path.to._40c.repo;

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
        return buildContractPriorities(findByPositionSide("LONG"));
    }
    
    default List<ContractPriority> findShortContractQty() {
        return buildContractPriorities(findByPositionSide("SHORT"));
    }
    
    private static List<ContractPriority> buildContractPriorities(List<PositionSizeMatrix> rows) {
        List<ContractPriority> out = new ArrayList<>();
        for (PositionSizeMatrix r : rows) {
            addIfPositive(out, r, r.getAtm(), "ATM");
            addIfPositive(out, r, r.getAtmPlus150(), "ATM+150");
            addIfPositive(out, r, r.getAtmPlus200(), "ATM+200");
            addIfPositive(out, r, r.getAtmPlus250(), "ATM+250");
            addIfPositive(out, r, r.getAtmMinus150(), "ATM-150");
            addIfPositive(out, r, r.getAtmMinus200(), "ATM-200");
            addIfPositive(out, r, r.getAtmMinus250(), "ATM-250");
        }
        return out;
    }
    
    private static void addIfPositive(List<ContractPriority> list, PositionSizeMatrix r, Integer lots, String strike) {
        if (lots != null && lots > 0) {
            list.add(new ContractPriority(r.getPositionSide(), r.getOptionType(), r.getActionType(), lots, strike));
        }
    }
}
