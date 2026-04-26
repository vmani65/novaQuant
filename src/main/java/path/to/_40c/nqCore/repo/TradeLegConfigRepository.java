package path.to._40c.nqCore.repo;

import static path.to._40c.nqCore.util.Constants.ATM;
import static path.to._40c.nqCore.util.Constants.LONG;
import static path.to._40c.nqCore.util.Constants.SHORT;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import path.to._40c.nqCore.entity.PositionSizeMatrix;
import path.to._40c.nqCore.pojo.TradeLegConfig;

public interface TradeLegConfigRepository extends JpaRepository<PositionSizeMatrix, Long> {

	@Query("SELECT p FROM position_size_matrix p WHERE p.positionSide = :side")
    List<PositionSizeMatrix> findByPositionSide(@Param("side") String side);
    
    default List<TradeLegConfig> findLongLegs() {
        return buildTradeLegConfigs(findByPositionSide(LONG));
    }

    default List<TradeLegConfig> findShortLegs() {
        return buildTradeLegConfigs(findByPositionSide(SHORT));
    }

    private static List<TradeLegConfig> buildTradeLegConfigs(List<PositionSizeMatrix> rows) {
        List<TradeLegConfig> out = new ArrayList<>();
        for (PositionSizeMatrix r : rows) {
            boolean isLong = LONG.equals(r.getPositionSide());
            addIfPositive(out, r, r.getAtm(),     ATM);
            addIfPositive(out, r, r.getOffset1(), isLong ? "ATM-50"  : "ATM+50");
            addIfPositive(out, r, r.getOffset2(), isLong ? "ATM-100" : "ATM+100");
            addIfPositive(out, r, r.getOffset3(), isLong ? "ATM-150" : "ATM+150");
        }
        return out;
    }
    
    private static void addIfPositive(List<TradeLegConfig> list, PositionSizeMatrix r, Integer lots, String strike) {
        if (lots != null && lots > 0) {
            list.add(new TradeLegConfig(r.getPositionSide(), r.getOptionType(), r.getActionType(), lots, strike));
        }
    }
}
