package path.to._40c.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import path.to._40c.entity.Trade;

@Repository
public interface TradeRepository extends JpaRepository<Trade, Long> {
	
	Trade findByTradeStatus(String status);
    
	Trade findFirstByOrderByIdDesc();
    
	@Query("SELECT DISTINCT t.statergyName FROM Trade t ORDER BY t.statergyName")
    List<String> findDistinctStrategyNames();
    
    List<Trade> findAllByOrderByTradeOpenDtTimeAsc();
    
    List<Trade> findByStatergyNameOrderByTradeOpenDtTimeAsc(String strategyName);
}
