package path.to._40c.nqCore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import path.to._40c.nqCore.entity.Strategy;
import path.to._40c.nqCore.entity.WeeklyLeg;
import path.to._40c.nqCore.repo.PositionRepository;

/**
 * Guards the strike-picker contract: ATM when free, nearest free strike walking outward
 * (+50 before −50) when occupied, template offsets respected, per-strategy search cap
 * honored, legacy legs without the STRIKE column parsed from the instrument, and loud
 * failure when every candidate inside the cap is taken.
 */
class StrikeOccupancyServiceTest {

    private static final String PREFIX = "25807";
    private static final List<Integer> ATM_ONLY_OFFSETS = List.of(0, 0);

    private PositionRepository positionRepository;
    private StrategyRegistry strategyRegistry;
    private StrikeOccupancyService service;

    @BeforeEach
    void setUp() {
        positionRepository = mock(PositionRepository.class);
        strategyRegistry = mock(StrategyRegistry.class);
        service = new StrikeOccupancyService(positionRepository, strategyRegistry);
    }

    @Test
    @DisplayName("nothing occupied: the ATM itself is returned")
    void atmFreeWhenNothingOccupied() {
        occupy();
        assertThat(service.resolveBaseStrike(23500, PREFIX, "MS1", ATM_ONLY_OFFSETS)).isEqualTo(23500);
    }

    @Test
    @DisplayName("ATM occupied by another strategy: shifts to ATM+50 first")
    void atmOccupiedShiftsToPlus50() {
        occupy(leg(23500, "NIFTY2580723500CE"), leg(23500, "NIFTY2580723500PE"));
        assertThat(service.resolveBaseStrike(23500, PREFIX, "MS2", ATM_ONLY_OFFSETS)).isEqualTo(23550);
    }

    @Test
    @DisplayName("ATM and ATM+50 occupied: falls back to ATM-50")
    void plusSideOccupiedFallsBackToMinus50() {
        occupy(leg(23500, "NIFTY2580723500CE"), leg(23550, "NIFTY2580723550CE"));
        assertThat(service.resolveBaseStrike(23500, PREFIX, "MS3", ATM_ONLY_OFFSETS)).isEqualTo(23450);
    }

    @Test
    @DisplayName("walks outward past fully occupied inner ring")
    void walksOutwardPastInnerRing() {
        occupy(leg(23500, null), leg(23550, null), leg(23450, null), leg(23600, null), leg(23400, null));
        assertThat(service.resolveBaseStrike(23500, PREFIX, "MS4", ATM_ONLY_OFFSETS)).isEqualTo(23650);
    }

    @Test
    @DisplayName("every strike inside the default ±150 cap occupied: throws instead of stacking")
    void exhaustionThrows() {
        occupy(leg(23350, null), leg(23400, null), leg(23450, null), leg(23500, null),
               leg(23550, null), leg(23600, null), leg(23650, null));
        assertThatThrownBy(() -> service.resolveBaseStrike(23500, PREFIX, "MS5", ATM_ONLY_OFFSETS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No free strike");
    }

    @Test
    @DisplayName("a strategy's own maxStrikeOffset caps the search below the default")
    void strategyMaxOffsetHonored() {
        Strategy tight = new Strategy();
        tight.setName("Tight");
        tight.setMaxStrikeOffset(50);
        when(strategyRegistry.get("Tight")).thenReturn(tight);
        occupy(leg(23500, null), leg(23550, null), leg(23450, null));

        assertThatThrownBy(() -> service.resolveBaseStrike(23500, PREFIX, "Tight", ATM_ONLY_OFFSETS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("±50");
    }

    @Test
    @DisplayName("legacy legs without the STRIKE column are parsed from the instrument")
    void legacyLegsParsedFromInstrument() {
        occupy(leg(null, "NIFTY2580723500CE"), leg(null, "NIFTY2580723500PE"));
        assertThat(service.resolveBaseStrike(23500, PREFIX, "MS6", ATM_ONLY_OFFSETS)).isEqualTo(23550);
    }

    @Test
    @DisplayName("a candidate is only free when every template offset lands on a free strike")
    void templateOffsetsRespected() {
        occupy(leg(23550, null));
        List<Integer> offsets = Arrays.asList(0, 50);
        assertThat(service.resolveBaseStrike(23500, PREFIX, "MS7", offsets)).isEqualTo(23450);
    }

    @Test
    @DisplayName("the requesting strategy's own legs are excluded via the repository query")
    void ownStrategyExcludedInQuery() {
        occupy();
        service.resolveBaseStrike(23500, PREFIX, "MS1", ATM_ONLY_OFFSETS);
        verify(positionRepository).findLiveLegsForOccupancy(eq("MS1"), eq("NIFTY" + PREFIX + "%"));
    }

    @Test
    @DisplayName("snapshot lists all live legs across strategies without exclusion")
    void snapshotQueriesWithoutExclusion() {
        when(positionRepository.findLiveLegsForOccupancy(isNull(), anyString())).thenReturn(List.of());
        assertThat(service.snapshot()).isEmpty();
        verify(positionRepository).findLiveLegsForOccupancy(isNull(), eq("NIFTY%"));
    }

    @Test
    @DisplayName("strike parsing handles weekly and monthly instrument formats, rejects junk")
    void parseStrikeFormats() {
        assertThat(StrikeOccupancyService.parseStrike("NIFTY2580723500CE")).isEqualTo(23500);
        assertThat(StrikeOccupancyService.parseStrike("NIFTY25AUG23500PE")).isEqualTo(23500);
        assertThat(StrikeOccupancyService.parseStrike("NIFTY25807FUT")).isNull();
        assertThat(StrikeOccupancyService.parseStrike("BANKEX")).isNull();
        assertThat(StrikeOccupancyService.parseStrike(null)).isNull();
    }

    private void occupy(WeeklyLeg... legs) {
        when(positionRepository.findLiveLegsForOccupancy(anyString(), anyString()))
                .thenReturn(Arrays.asList(legs));
    }

    private static WeeklyLeg leg(Integer strike, String instrument) {
        WeeklyLeg l = new WeeklyLeg();
        l.setStrike(strike);
        l.setInstrument(instrument);
        l.setStatus("LIVE");
        return l;
    }
}
