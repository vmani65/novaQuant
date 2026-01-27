package path.to._40c.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import path.to._40c.entity.ExpiryInfo;

@Repository
public interface ExpiryInfoRepository extends JpaRepository<ExpiryInfo, Long> {
    
    @Modifying
    @Transactional
    @Query("DELETE FROM ExpiryInfo")
    void deleteAll();
    
    @Query("SELECT e FROM ExpiryInfo e ORDER BY e.id DESC")
    ExpiryInfo findLatest();
}
