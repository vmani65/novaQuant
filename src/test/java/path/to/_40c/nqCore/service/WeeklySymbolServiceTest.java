package path.to._40c.nqCore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import path.to._40c.nqCore.entity.WeeklyLeg;
import path.to._40c.nqCore.entity.WeeklySymbolConfig;
import path.to._40c.nqCore.repo.PositionRepository;
import path.to._40c.nqCore.repo.WeeklySymbolConfigRepository;

/**
 * Guards the rollover-day promotion rule: the after-close hook may promote the rollover
 * symbol and mark rollover complete ONLY once no live leg (any strategy) remains on the
 * expiring thisWeek symbol. Promoting on the first strategy's close would flip
 * rolloverComplete while another book is still on the old expiry, making the 14:47 trigger
 * skip and stranding that book in expiring contracts.
 */
class WeeklySymbolServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private WeeklySymbolConfigRepository repo;
    private PositionRepository positionRepository;
    private WeeklySymbolConfig cfg;
    private WeeklySymbolService service;

    @BeforeEach
    void setUp() {
        repo = mock(WeeklySymbolConfigRepository.class);
        positionRepository = mock(PositionRepository.class);
        WeeklySymbolCache cache = new WeeklySymbolCache();
        cfg = new WeeklySymbolConfig("25807", "25814");
        cfg.setRolloverDay(LocalDate.now(IST));
        cache.set(cfg);
        service = new WeeklySymbolService(repo, cache, positionRepository);
        lenient().when(repo.findById(1L)).thenReturn(Optional.of(cfg));
        lenient().when(repo.save(any(WeeklySymbolConfig.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("promotion deferred while any live leg remains on the expiring symbol")
    void deferredWhileOldExpiryLegsLive() {
        when(positionRepository.findLiveLegsForOccupancy(isNull(), anyString()))
                .thenReturn(List.of(new WeeklyLeg(), new WeeklyLeg()));

        service.checkAndPromoteRolloverSymbol();

        assertThat(cfg.getThisWeekSymbol()).isEqualTo("25807");
        assertThat(cfg.getRolloverComplete()).isFalse();
    }

    @Test
    @DisplayName("promotes and marks complete once no live leg remains on the expiring symbol")
    void promotesWhenNoOldExpiryLegsLeft() {
        when(positionRepository.findLiveLegsForOccupancy(isNull(), anyString())).thenReturn(List.of());

        service.checkAndPromoteRolloverSymbol();

        assertThat(cfg.getThisWeekSymbol()).isEqualTo("25814");
        assertThat(cfg.getRolloverComplete()).isTrue();
    }

    @Test
    @DisplayName("the occupancy check queries the expiring thisWeek symbol specifically")
    void queriesExpiringSymbolPrefix() {
        when(positionRepository.findLiveLegsForOccupancy(isNull(), anyString())).thenReturn(List.of());

        service.checkAndPromoteRolloverSymbol();

        org.mockito.Mockito.verify(positionRepository).findLiveLegsForOccupancy(isNull(), org.mockito.ArgumentMatchers.eq("NIFTY25807%"));
    }

    @Test
    @DisplayName("not rollover day: no promotion, no occupancy query")
    void notRolloverDayNoop() {
        cfg.setRolloverDay(LocalDate.now(IST).minusDays(1));

        service.checkAndPromoteRolloverSymbol();

        verifyNoInteractions(positionRepository);
        assertThat(cfg.getThisWeekSymbol()).isEqualTo("25807");
    }

    @Test
    @DisplayName("already complete: no re-promotion, no occupancy query")
    void alreadyCompleteNoop() {
        cfg.setRolloverComplete(true);
        cfg.setThisWeekSymbol("25814");

        service.checkAndPromoteRolloverSymbol();

        verifyNoInteractions(positionRepository);
    }
}
