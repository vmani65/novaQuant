package path.to._40c.nqCore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static path.to._40c.nqCore.entity.SymbolConfig.MONTHLY_ID;
import static path.to._40c.nqCore.entity.SymbolConfig.WEEKLY_ID;
import static path.to._40c.nqCore.util.Constants.WEEKLY;
import static path.to._40c.nqCore.util.Constants.ZONE_ID;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import path.to._40c.nqCore.entity.SymbolConfig;
import path.to._40c.nqCore.repo.SymbolConfigRepository;

/**
 * WeeklySymbolService owns ONLY the weekly calendar: row id=1 and the weekly cache
 * slot. After the calendar-symmetry refactor it must never read or write the monthly
 * row (id=2) — MonthlySymbolService owns that — so every test here also pins that the
 * monthly row is untouched. Weekly's contract-advance mechanism is operator-driven:
 * saved symbols + rolloverDay, promotion date-gated.
 */
class WeeklySymbolServiceTest {

    private SymbolConfigRepository repo;
    private WeeklySymbolCache cache;
    private WeeklySymbolService service;

    @BeforeEach
    void setUp() {
        repo = mock(SymbolConfigRepository.class);
        cache = new WeeklySymbolCache();
        service = new WeeklySymbolService(repo, cache);
        when(repo.save(any(SymbolConfig.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("save upserts row id=1, parses rolloverDay, resets rolloverComplete, fills the weekly cache slot")
    void saveUpsertsWeeklyRow() {
        when(repo.findById(WEEKLY_ID)).thenReturn(Optional.empty());

        service.saveSymbols("26812", "26819", "2026-08-11");

        ArgumentCaptor<SymbolConfig> saved = ArgumentCaptor.forClass(SymbolConfig.class);
        verify(repo).save(saved.capture());
        SymbolConfig cfg = saved.getValue();
        assertThat(cfg.getId()).isEqualTo(WEEKLY_ID);
        assertThat(cfg.getScope()).isEqualTo(WEEKLY);
        assertThat(cfg.getThisWeekSymbol()).isEqualTo("26812");
        assertThat(cfg.getRolloverSymbol()).isEqualTo("26819");
        assertThat(cfg.getRolloverDay()).isEqualTo(LocalDate.of(2026, 8, 11));
        assertThat(cfg.getRolloverComplete()).isFalse();
        assertThat(cache.get()).isSameAs(cfg);
        verify(repo, never()).findById(MONTHLY_ID);
    }

    @Test
    @DisplayName("re-save of an existing row updates it in place and blanks an empty rolloverDay")
    void resaveResetsRolloverCompleteOnExistingRow() {
        SymbolConfig existing = new SymbolConfig(WEEKLY_ID, WEEKLY, "26805", "26812");
        existing.setRolloverComplete(true);
        existing.setRolloverDay(LocalDate.of(2026, 8, 4));
        when(repo.findById(WEEKLY_ID)).thenReturn(Optional.of(existing));

        service.saveSymbols("26812", "26819", "");

        assertThat(existing.getThisWeekSymbol()).isEqualTo("26812");
        assertThat(existing.getRolloverSymbol()).isEqualTo("26819");
        assertThat(existing.getRolloverComplete()).isFalse();
        assertThat(existing.getRolloverDay()).isNull();
        verify(repo).save(existing);
    }

    @Test
    @DisplayName("syncTradedContract on the configured rollover day promotes exactly once — the latch blocks a re-fire")
    void syncPromotesOnRolloverDayExactlyOnce() {
        SymbolConfig weekly = new SymbolConfig(WEEKLY_ID, WEEKLY, "26812", "26819");
        weekly.setRolloverDay(LocalDate.now(ZoneId.of(ZONE_ID)));
        when(repo.findById(WEEKLY_ID)).thenReturn(Optional.of(weekly));

        service.syncTradedContract();

        assertThat(weekly.getThisWeekSymbol()).isEqualTo("26819");
        assertThat(weekly.getRolloverComplete()).isTrue();

        service.syncTradedContract();

        verify(repo, times(2)).save(any(SymbolConfig.class));
    }

    @Test
    @DisplayName("syncTradedContract off the rollover day is a no-op — the operator's current symbol keeps trading")
    void syncIsNoOpOffRolloverDay() {
        SymbolConfig weekly = new SymbolConfig(WEEKLY_ID, WEEKLY, "26812", "26819");
        weekly.setRolloverDay(LocalDate.now(ZoneId.of(ZONE_ID)).plusDays(2));
        when(repo.findById(WEEKLY_ID)).thenReturn(Optional.of(weekly));

        service.syncTradedContract();

        assertThat(weekly.getThisWeekSymbol()).isEqualTo("26812");
        verify(repo, never()).save(any(SymbolConfig.class));
    }

    @Test
    @DisplayName("syncTradedContract with no rollover day configured is a no-op")
    void syncIsNoOpWithoutConfiguredDay() {
        SymbolConfig weekly = new SymbolConfig(WEEKLY_ID, WEEKLY, "26812", "26819");
        when(repo.findById(WEEKLY_ID)).thenReturn(Optional.of(weekly));

        service.syncTradedContract();

        verify(repo, never()).save(any(SymbolConfig.class));
    }

    @Test
    @DisplayName("promotion and completion touch only row id=1")
    void promotionTouchesOnlyWeeklyRow() {
        SymbolConfig weekly = new SymbolConfig(WEEKLY_ID, WEEKLY, "26812", "26819");
        when(repo.findById(WEEKLY_ID)).thenReturn(Optional.of(weekly));

        service.promoteRolloverSymbol();
        service.markRolloverComplete();

        assertThat(weekly.getThisWeekSymbol()).isEqualTo("26819");
        assertThat(weekly.getRolloverComplete()).isTrue();
        assertThat(cache.get()).isSameAs(weekly);
        verify(repo, never()).findById(MONTHLY_ID);
    }

    @Test
    @DisplayName("warmCache backfills a null scope on row id=1 and warms only the weekly slot")
    void warmCacheBackfillsNullScope() {
        SymbolConfig weekly = new SymbolConfig("26812", "26819");
        weekly.setScope(null);
        when(repo.findById(WEEKLY_ID)).thenReturn(Optional.of(weekly));

        service.warmCache();

        assertThat(weekly.getScope()).isEqualTo(WEEKLY);
        verify(repo).save(weekly);
        assertThat(cache.get()).isNotNull();
        verify(repo, never()).findById(MONTHLY_ID);
    }

    @Test
    @DisplayName("warmCache with an already-scoped row skips the redundant save")
    void warmCacheSkipsRedundantSave() {
        SymbolConfig weekly = new SymbolConfig(WEEKLY_ID, WEEKLY, "26812", "26819");
        when(repo.findById(WEEKLY_ID)).thenReturn(Optional.of(weekly));

        service.warmCache();

        assertThat(cache.get()).isSameAs(weekly);
        verify(repo, never()).save(any(SymbolConfig.class));
    }
}
