package path.to._40c.nqCore.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import path.to._40c.nqCore.entity.Position;

@Repository
public interface PositionRepository extends JpaRepository<Position, Long> {

	Position findFirstByStatusOrderByIdDesc(String status);

	Position findFirstByOrderByIdDesc();

	List<Position> findByPeakMarginNotNull();

	@Query("SELECT DISTINCT p.strategyName FROM Position p ORDER BY p.strategyName")
    List<String> findDistinctStrategyNames();

    List<Position> findAllByOrderByOpenedAtAsc();

    List<Position> findByStrategyNameOrderByOpenedAtAsc(String strategyName);
}
