package path.to._40c.nqCore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static path.to._40c.nqCore.util.Constants.BUY;
import static path.to._40c.nqCore.util.Constants.CE;
import static path.to._40c.nqCore.util.Constants.LONG;
import static path.to._40c.nqCore.util.Constants.MONTHLY;
import static path.to._40c.nqCore.util.Constants.PE;
import static path.to._40c.nqCore.util.Constants.SELL;
import static path.to._40c.nqCore.util.Constants.SHORT;
import static path.to._40c.nqCore.util.Constants.WEEKLY;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import path.to._40c.nqCore.entity.LegTemplate;
import path.to._40c.nqCore.repo.LegTemplateRepository;

/**
 * Covers the phase-1 scope split of {@link LegTemplateCache}: rows that predate the SCOPE
 * column read null after the DDL update and must be stamped WEEKLY at startup (they are all
 * weekly synthetic legs), and refreshCache must route rows into four (direction, scope) slots
 * so the engine accessors getLongLegs()/getShortLegs() keep serving exactly the weekly
 * synthetic while MONTHLY rows stay config-only. A missed backfill would leave the scoped
 * queries empty and the engine building zero-leg positions — the failure mode these tests pin.
 */
class LegTemplateCacheScopeTest {

    private LegTemplateRepository repository;
    private LegTemplateCache cache;
    private List<LegTemplate> store;

    @BeforeEach
    void setUp() {
        store = new ArrayList<>();
        repository = mock(LegTemplateRepository.class);
        when(repository.findAll()).thenAnswer(inv -> new ArrayList<>(store));
        when(repository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(repository.findByDirectionAndScope(anyString(), anyString())).thenAnswer(inv -> {
            String direction = inv.getArgument(0);
            String scope = inv.getArgument(1);
            return store.stream()
                    .filter(t -> direction.equals(t.getDirection()) && scope.equals(t.getScope()))
                    .toList();
        });
        cache = new LegTemplateCache(repository);
    }

    @Test
    @DisplayName("init backfills pre-column null-scope rows to WEEKLY and leaves scoped rows alone")
    void initBackfillsPreColumnRowsToWeekly() {
        LegTemplate legacyLongCe = tpl(LONG, CE, BUY, null);
        LegTemplate legacyLongPe = tpl(LONG, PE, SELL, null);
        LegTemplate legacyShortPe = tpl(SHORT, PE, BUY, null);
        LegTemplate legacyShortCe = tpl(SHORT, CE, SELL, null);
        LegTemplate monthlyLongCe = tpl(LONG, CE, BUY, MONTHLY);
        store.addAll(List.of(legacyLongCe, legacyLongPe, legacyShortPe, legacyShortCe, monthlyLongCe));

        cache.init();

        assertThat(legacyLongCe.getScope()).isEqualTo(WEEKLY);
        assertThat(legacyLongPe.getScope()).isEqualTo(WEEKLY);
        assertThat(legacyShortPe.getScope()).isEqualTo(WEEKLY);
        assertThat(legacyShortCe.getScope()).isEqualTo(WEEKLY);
        assertThat(monthlyLongCe.getScope()).isEqualTo(MONTHLY);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LegTemplate>> saved = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(saved.capture());
        assertThat(saved.getValue())
                .containsExactlyInAnyOrder(legacyLongCe, legacyLongPe, legacyShortPe, legacyShortCe);

        assertThat(cache.getLongLegs()).containsExactlyInAnyOrder(legacyLongCe, legacyLongPe);
        assertThat(cache.getShortLegs()).containsExactlyInAnyOrder(legacyShortPe, legacyShortCe);
        assertThat(cache.getMonthlyLongLegs()).containsExactly(monthlyLongCe);
    }

    @Test
    @DisplayName("init with every row already scoped writes nothing back")
    void initWithAllRowsScopedSavesNothing() {
        store.add(tpl(LONG, CE, BUY, WEEKLY));
        store.add(tpl(LONG, CE, BUY, MONTHLY));

        cache.init();

        verify(repository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("refresh routes rows into the four (direction, scope) slots")
    void refreshRoutesRowsIntoFourSlots() {
        LegTemplate weeklyLong = tpl(LONG, CE, BUY, WEEKLY);
        LegTemplate weeklyShort = tpl(SHORT, PE, BUY, WEEKLY);
        LegTemplate monthlyLong = tpl(LONG, CE, BUY, MONTHLY);
        LegTemplate monthlyShort = tpl(SHORT, PE, BUY, MONTHLY);
        store.addAll(List.of(weeklyLong, weeklyShort, monthlyLong, monthlyShort));

        cache.refreshCache();

        assertThat(cache.getLongLegs()).containsExactly(weeklyLong);
        assertThat(cache.getShortLegs()).containsExactly(weeklyShort);
        assertThat(cache.getMonthlyLongLegs()).containsExactly(monthlyLong);
        assertThat(cache.getMonthlyShortLegs()).containsExactly(monthlyShort);
    }

    @Test
    @DisplayName("engine accessors never serve MONTHLY rows even when no WEEKLY rows exist")
    void engineAccessorsNeverServeMonthlyRows() {
        store.add(tpl(LONG, CE, BUY, MONTHLY));
        store.add(tpl(SHORT, PE, BUY, MONTHLY));

        cache.init();

        assertThat(cache.getLongLegs()).isEmpty();
        assertThat(cache.getShortLegs()).isEmpty();
        assertThat(cache.getMonthlyLongLegs()).hasSize(1);
        assertThat(cache.getMonthlyShortLegs()).hasSize(1);
    }

    @Test
    @DisplayName("cached leg lists are unmodifiable")
    void cachedListsAreUnmodifiable() {
        store.add(tpl(LONG, CE, BUY, WEEKLY));
        cache.refreshCache();

        assertThatThrownBy(() -> cache.getLongLegs().add(tpl(LONG, PE, SELL, WEEKLY)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static LegTemplate tpl(String direction, String optionType, String side, String scope) {
        LegTemplate t = new LegTemplate(direction, optionType, side, 0, 10);
        t.setScope(scope);
        return t;
    }
}
