package path.to._40c.nqCore.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import path.to._40c.nqCore.entity.LegTemplate;

@Repository
public interface LegTemplateRepository extends JpaRepository<LegTemplate, Long> {

    List<LegTemplate> findByDirectionAndScope(String direction, String scope);

    List<LegTemplate> findAllByOrderByDirectionAscOptionTypeAscOffsetPtsAsc();
}
