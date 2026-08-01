package path.to._40c.nqCore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import path.to._40c.nqCore.entity.LegTemplate;
import path.to._40c.nqCore.repo.LegTemplateRepository;

/**
 * Guards template selection: a strategy with its own rows uses exactly those; a strategy
 * without falls back to the shared defaults (strategyName = null); the null strategy
 * (legacy callers) always gets the defaults.
 */
class LegTemplateCacheTest {

    private LegTemplateCache cache;

    @BeforeEach
    void setUp() {
        LegTemplateRepository repository = mock(LegTemplateRepository.class);
        when(repository.findAll()).thenReturn(List.of(
                template("LONG", "CE", "BUY", 10, null),
                template("LONG", "PE", "SELL", 10, null),
                template("SHORT", "PE", "BUY", 10, null),
                template("SHORT", "CE", "SELL", 10, null),
                template("LONG", "CE", "BUY", 2, "MS1"),
                template("LONG", "PE", "SELL", 2, "MS1")));
        cache = new LegTemplateCache(repository);
        cache.refreshCache();
    }

    @Test
    @DisplayName("a strategy with its own rows gets exactly those rows")
    void ownRowsWin() {
        List<LegTemplate> legs = cache.getLegs("LONG", "MS1");
        assertThat(legs).hasSize(2);
        assertThat(legs).allSatisfy(t -> {
            assertThat(t.getStrategyName()).isEqualTo("MS1");
            assertThat(t.getLots()).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("a strategy without its own rows falls back to the shared defaults")
    void fallbackToDefaults() {
        List<LegTemplate> legs = cache.getLegs("LONG", "MS2");
        assertThat(legs).hasSize(2);
        assertThat(legs).allSatisfy(t -> assertThat(t.getStrategyName()).isNull());
    }

    @Test
    @DisplayName("own rows are per direction — MS1 SHORT falls back to defaults")
    void fallbackPerDirection() {
        List<LegTemplate> legs = cache.getLegs("SHORT", "MS1");
        assertThat(legs).hasSize(2);
        assertThat(legs).allSatisfy(t -> {
            assertThat(t.getStrategyName()).isNull();
            assertThat(t.getDirection()).isEqualTo("SHORT");
        });
    }

    @Test
    @DisplayName("null strategy always gets the default set")
    void nullStrategyGetsDefaults() {
        List<LegTemplate> legs = cache.getLegs("LONG", null);
        assertThat(legs).hasSize(2);
        assertThat(legs).allSatisfy(t -> assertThat(t.getStrategyName()).isNull());
    }

    private static LegTemplate template(String direction, String optionType, String side, int lots, String strategyName) {
        LegTemplate t = new LegTemplate(direction, optionType, side, 0, lots);
        t.setStrategyName(strategyName);
        return t;
    }
}
