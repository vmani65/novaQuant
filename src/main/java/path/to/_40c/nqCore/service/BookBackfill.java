package path.to._40c.nqCore.service;

import java.util.List;

import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.repo.PositionRepository;

import static path.to._40c.nqCore.util.Constants.SYNTH_WEEKLY;

/**
 * One-time startup migration for the POSITION.BOOK column: rows created before the
 * two-book split read null after the DDL update and are all weekly synthetic trades,
 * so stamp them SYNTH_WEEKLY. Without this, book-scoped queries would never see a
 * legacy LIVE position and the close path would strand it at the broker.
 */
@Component
@Slf4j
public class BookBackfill {

    private final PositionRepository positionRepository;

    public BookBackfill(PositionRepository positionRepository) {
        this.positionRepository = positionRepository;
    }

    @PostConstruct
    public void backfillNullBooks() {
        List<Position> nullBooked = positionRepository.findByBookIsNull();
        if (!nullBooked.isEmpty()) {
            nullBooked.forEach(p -> p.setBook(SYNTH_WEEKLY));
            positionRepository.saveAll(nullBooked);
            log.info("Backfilled book=SYNTH_WEEKLY on {} pre-existing position rows", nullBooked.size());
        }
    }
}
