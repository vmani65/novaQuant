package path.to._40c.nqCore.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import path.to._40c.nqCore.entity.Strategy;

@Repository
public interface StrategyRepository extends JpaRepository<Strategy, String> {
}
