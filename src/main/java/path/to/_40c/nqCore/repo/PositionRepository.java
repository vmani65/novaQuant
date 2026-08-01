package path.to._40c.nqCore.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.entity.WeeklyLeg;

@Repository
public interface PositionRepository extends JpaRepository<Position, Long> {

	Position findFirstByStatusOrderByIdDesc(String status);

	List<Position> findAllByStatusOrderByIdAsc(String status);

	Position findFirstByStrategyNameAndStatusOrderByIdDesc(String strategyName, String status);

	Position findFirstByStrategyNameOrderByIdDesc(String strategyName);

	Position findFirstByOrderByIdDesc();

	List<Position> findByPeakMarginNotNull();

	@Query("SELECT DISTINCT p.strategyName FROM Position p ORDER BY p.strategyName")
    List<String> findDistinctStrategyNames();

    List<Position> findAllByOrderByOpenedAtAsc();

    List<Position> findByStrategyNameOrderByOpenedAtAsc(String strategyName);

    /**
     * Live legs currently held at the broker (leg LIVE under a LIVE/PARTIAL parent) whose
     * instrument matches the given prefix pattern — the raw material for strike-occupancy.
     * excludeStrategy removes one strategy's own legs from the result (null = exclude nothing);
     * legs of positions with a null strategyName are always counted as occupied.
     */
    @Query("SELECT l FROM Position p JOIN p.legs l "
            + "WHERE l.status = 'LIVE' AND p.status IN ('LIVE', 'PARTIAL') "
            + "AND (:excludeStrategy IS NULL OR p.strategyName IS NULL OR p.strategyName <> :excludeStrategy) "
            + "AND l.instrument LIKE :instrumentPattern")
    List<WeeklyLeg> findLiveLegsForOccupancy(@Param("excludeStrategy") String excludeStrategy,
            @Param("instrumentPattern") String instrumentPattern);
}
