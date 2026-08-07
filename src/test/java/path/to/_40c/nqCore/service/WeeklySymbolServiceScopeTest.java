package path.to._40c.nqCore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static path.to._40c.nqCore.entity.WeeklySymbolConfig.MONTHLY_ID;
import static path.to._40c.nqCore.entity.WeeklySymbolConfig.WEEKLY_ID;
import static path.to._40c.nqCore.util.Constants.MONTHLY;
import static path.to._40c.nqCore.util.Constants.WEEKLY;

import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import path.to._40c.nqCore.entity.WeeklySymbolConfig;
import path.to._40c.nqCore.repo.WeeklySymbolConfigRepository;

/**
 * Covers the phase-1 scope-aware upsert of {@link WeeklySymbolService}: WEEKLY saves land on
 * row id=1 and the weekly cache slot, MONTHLY saves land on row id=2 and the monthly slot,
 * and neither save may touch the other scope's row — the two Symbols-UI forms save
 * independently. Also pins that the rollover promotion paths stay hardwired to the WEEKLY
 * row (the monthly row's rolloverDay is informational until the monthly book is wired in),
 * and that warmCache backfills the pre-column null scope on row id=1.
 */
class WeeklySymbolServiceScopeTest {

    private WeeklySymbolConfigRepository repo;
    private WeeklySymbolCache cache;
    private WeeklySymbolService service;

    @BeforeEach
    void setUp() {
        repo = mock(WeeklySymbolConfigRepository.class);
        cache = new WeeklySymbolCache();
        service = new WeeklySymbolService(repo, cache);
        when(repo.save(any(WeeklySymbolConfig.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("WEEKLY save upserts row id=1, parses rolloverDay, and fills only the weekly cache slot")
    void weeklySaveUpsertsRowOne() {
        when(repo.findById(WEEKLY_ID)).thenReturn(Optional.empty());

        service.saveSymbols(WEEKLY, "26812", "26819", "2026-08-11");

        ArgumentCaptor<WeeklySymbolConfig> saved = ArgumentCaptor.forClass(WeeklySymbolConfig.class);
        verify(repo).save(saved.capture());
        WeeklySymbolConfig cfg = saved.getValue();
        assertThat(cfg.getId()).isEqualTo(WEEKLY_ID);
        assertThat(cfg.getScope()).isEqualTo(WEEKLY);
        assertThat(cfg.getThisWeekSymbol()).isEqualTo("26812");
        assertThat(cfg.getRolloverSymbol()).isEqualTo("26819");
        assertThat(cfg.getRolloverDay()).isEqualTo(LocalDate.of(2026, 8, 11));
        assertThat(cfg.getRolloverComplete()).isFalse();
        assertThat(cache.get()).isSameAs(cfg);
        assertThat(cache.getMonthly()).isNull();
    }

    @Test
    @DisplayName("MONTHLY save upserts row id=2 and leaves the weekly row and cache slot untouched")
    void monthlySaveUpsertsRowTwoLeavingWeeklyAlone() {
        WeeklySymbolConfig weekly = new WeeklySymbolConfig(WEEKLY_ID, WEEKLY, "26812", "26819");
        cache.set(weekly);
        when(repo.findById(MONTHLY_ID)).thenReturn(Optional.empty());

        service.saveSymbols(MONTHLY, "26AUG", "26SEP", null);

        ArgumentCaptor<WeeklySymbolConfig> saved = ArgumentCaptor.forClass(WeeklySymbolConfig.class);
        verify(repo).save(saved.capture());
        WeeklySymbolConfig cfg = saved.getValue();
        assertThat(cfg.getId()).isEqualTo(MONTHLY_ID);
        assertThat(cfg.getScope()).isEqualTo(MONTHLY);
        assertThat(cfg.getRolloverDay()).isNull();
        assertThat(cache.getMonthly()).isSameAs(cfg);
        assertThat(cache.get()).isSameAs(weekly);
        verify(repo, never()).findById(WEEKLY_ID);
    }

    @Test
    @DisplayName("lowercase scope input normalizes to MONTHLY")
    void lowercaseScopeNormalizesToMonthly() {
        when(repo.findById(MONTHLY_ID)).thenReturn(Optional.empty());

        service.saveSymbols("monthly", "26AUG", "26SEP", null);

        ArgumentCaptor<WeeklySymbolConfig> saved = ArgumentCaptor.forClass(WeeklySymbolConfig.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(MONTHLY_ID);
        assertThat(saved.getValue().getScope()).isEqualTo(MONTHLY);
    }

    @Test
    @DisplayName("unknown scope falls back to WEEKLY at the service layer (controller rejects it earlier)")
    void unknownScopeFallsBackToWeekly() {
        when(repo.findById(WEEKLY_ID)).thenReturn(Optional.empty());

        service.saveSymbols("DAILY", "26812", "26819", null);

        ArgumentCaptor<WeeklySymbolConfig> saved = ArgumentCaptor.forClass(WeeklySymbolConfig.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(WEEKLY_ID);
        assertThat(saved.getValue().getScope()).isEqualTo(WEEKLY);
    }

    @Test
    @DisplayName("re-save of an existing row updates it in place, resets rolloverComplete, blanks the day")
    void resaveResetsRolloverCompleteOnExistingRow() {
        WeeklySymbolConfig existing = new WeeklySymbolConfig(WEEKLY_ID, WEEKLY, "26805", "26812");
        existing.setRolloverComplete(true);
        existing.setRolloverDay(LocalDate.of(2026, 8, 4));
        when(repo.findById(WEEKLY_ID)).thenReturn(Optional.of(existing));

        service.saveSymbols(WEEKLY, "26812", "26819", "");

        assertThat(existing.getThisWeekSymbol()).isEqualTo("26812");
        assertThat(existing.getRolloverSymbol()).isEqualTo("26819");
        assertThat(existing.getRolloverComplete()).isFalse();
        assertThat(existing.getRolloverDay()).isNull();
        verify(repo).save(existing);
    }

    @Test
    @DisplayName("rollover promotion and completion touch only the WEEKLY row; the monthly slot is inert")
    void promotionTouchesOnlyWeeklyRow() {
        WeeklySymbolConfig weekly = new WeeklySymbolConfig(WEEKLY_ID, WEEKLY, "26812", "26819");
        WeeklySymbolConfig monthly = new WeeklySymbolConfig(MONTHLY_ID, MONTHLY, "26AUG", "26SEP");
        cache.setMonthly(monthly);
        when(repo.findById(WEEKLY_ID)).thenReturn(Optional.of(weekly));

        service.promoteRolloverSymbol();
        service.markRolloverComplete();

        assertThat(weekly.getThisWeekSymbol()).isEqualTo("26819");
        assertThat(weekly.getRolloverComplete()).isTrue();
        assertThat(monthly.getThisWeekSymbol()).isEqualTo("26AUG");
        assertThat(cache.getMonthly()).isSameAs(monthly);
        verify(repo, never()).findById(MONTHLY_ID);
    }

    @Test
    @DisplayName("warmCache backfills the null scope on row id=1 and warms both cache slots")
    void warmCacheBackfillsNullScopeAndWarmsBothSlots() {
        WeeklySymbolConfig weekly = new WeeklySymbolConfig("26812", "26819");
        weekly.setScope(null);
        WeeklySymbolConfig monthly = new WeeklySymbolConfig(MONTHLY_ID, MONTHLY, "26AUG", "26SEP");
        when(repo.findById(WEEKLY_ID)).thenReturn(Optional.of(weekly));
        when(repo.findById(MONTHLY_ID)).thenReturn(Optional.of(monthly));

        service.warmCache();

        assertThat(weekly.getScope()).isEqualTo(WEEKLY);
        verify(repo).save(weekly);
        assertThat(cache.get()).isNotNull();
        assertThat(cache.get().getThisWeekSymbol()).isEqualTo("26812");
        assertThat(cache.getMonthly()).isSameAs(monthly);
    }

    @Test
    @DisplayName("warmCache without a monthly row leaves the monthly slot empty and skips the redundant save")
    void warmCacheWithoutMonthlyRow() {
        WeeklySymbolConfig weekly = new WeeklySymbolConfig(WEEKLY_ID, WEEKLY, "26812", "26819");
        when(repo.findById(WEEKLY_ID)).thenReturn(Optional.of(weekly));
        when(repo.findById(MONTHLY_ID)).thenReturn(Optional.empty());

        service.warmCache();

        assertThat(cache.get()).isSameAs(weekly);
        assertThat(cache.getMonthly()).isNull();
        verify(repo, never()).save(any(WeeklySymbolConfig.class));
    }
}
