package path.to._40c.nqCore.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import path.to._40c.nqCore.entity.Position;

@Repository
public interface PositionRepository extends JpaRepository<Position, Long> {

	Position findFirstByStatusOrderByIdDesc(String status);

	/**
	 * Positions owning at least one leg of the given book in the given leg status, newest
	 * first. The book-scoped finder of the one-position-per-signal model: "the row holding
	 * book X's LIVE legs" replaces the old row-per-book lookup. Null-book legs never match —
	 * LegBookBackfill stamps every legacy leg at startup.
	 */
	@Query("SELECT DISTINCT p FROM Position p JOIN p.legs l WHERE l.status = :legStatus AND l.book = :book ORDER BY p.id DESC")
	List<Position> findByLegStatusAndLegBook(@Param("legStatus") String legStatus, @Param("book") String book);

	/**
	 * Positions owning at least one leg in the given leg status, newest first. The
	 * reconcilers scan by LEG status — a shared row's status may show the other book's
	 * state, so a row-status scan would miss pending legs.
	 */
	@Query("SELECT DISTINCT p FROM Position p JOIN p.legs l WHERE l.status = :legStatus ORDER BY p.id DESC")
	List<Position> findByLegStatus(@Param("legStatus") String legStatus);

	Position findFirstByOrderByIdDesc();

	List<Position> findByPeakMarginNotNull();

	@Query("SELECT DISTINCT p.strategyName FROM Position p ORDER BY p.strategyName")
    List<String> findDistinctStrategyNames();

    List<Position> findAllByOrderByOpenedAtAsc();

    List<Position> findByStrategyNameOrderByOpenedAtAsc(String strategyName);
}
