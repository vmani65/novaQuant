package path.to._40c.nqCore.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import path.to._40c.nqCore.entity.KiteAuthDetails;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface KiteAuthDetailsRepository extends JpaRepository<KiteAuthDetails, Long> {
    Optional<KiteAuthDetails> findByAuthDate(LocalDate authDate);
    void deleteByAuthDateBefore(LocalDate date);
}
