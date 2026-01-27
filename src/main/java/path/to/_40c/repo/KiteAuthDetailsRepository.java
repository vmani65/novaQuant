package path.to._40c.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import path.to._40c.entity.KiteAuthDetails;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface KiteAuthDetailsRepository extends JpaRepository<KiteAuthDetails, Long> {
    Optional<KiteAuthDetails> findByAuthDate(LocalDate authDate);
}
