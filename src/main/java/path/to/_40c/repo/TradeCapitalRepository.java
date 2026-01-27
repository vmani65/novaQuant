package path.to._40c.repo;

import path.to._40c.entity.TradeCapital;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface TradeCapitalRepository extends JpaRepository<TradeCapital, Long> {
    
    @Query("SELECT tc FROM TradeCapital tc WHERE tc.id = 1")
    TradeCapital getTradeCapital();
    
    @Query("SELECT CASE WHEN tc.currentCapital >= tc.ceilingToHit THEN true ELSE false END FROM TradeCapital tc WHERE tc.id = 1")
    boolean hasReachedCeiling();
    
    @Query("SELECT tc.currentCapital FROM TradeCapital tc WHERE tc.id = 1")
    Double getCurrentCapital();
    
    @Query("SELECT tc.ceilingToHit FROM TradeCapital tc WHERE tc.id = 1")
    Double getCeilingToHit();
}

