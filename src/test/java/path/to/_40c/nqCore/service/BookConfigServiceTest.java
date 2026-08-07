package path.to._40c.nqCore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static path.to._40c.nqCore.util.Constants.LONG_MONTHLY;
import static path.to._40c.nqCore.util.Constants.SYNTH_WEEKLY;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import path.to._40c.nqCore.entity.BookConfig;
import path.to._40c.nqCore.repo.BookConfigRepository;

/**
 * Business rules of the book toggles: fresh install seeds SYNTH_WEEKLY=enabled (the
 * proven live book) and LONG_MONTHLY=disabled (nothing monthly trades until the owner
 * flips the switch); existing rows are NEVER overwritten by the seed (a restart must
 * not silently re-enable a book the owner turned off); unknown books read as disabled
 * (fail-closed) and cannot be toggled.
 */
class BookConfigServiceTest {

    private BookConfigRepository repo;
    private BookConfigService service;
    private List<BookConfig> store;

    @BeforeEach
    void setUp() {
        store = new ArrayList<>();
        repo = mock(BookConfigRepository.class);
        when(repo.findById(any(String.class))).thenAnswer(inv ->
                store.stream().filter(c -> c.getBook().equals(inv.getArgument(0))).findFirst());
        when(repo.save(any(BookConfig.class))).thenAnswer(inv -> {
            BookConfig c = inv.getArgument(0);
            store.removeIf(x -> x.getBook().equals(c.getBook()));
            store.add(c);
            return c;
        });
        when(repo.findAll()).thenAnswer(inv -> new ArrayList<>(store));
        service = new BookConfigService(repo);
    }

    @Test
    @DisplayName("fresh install seeds SYNTH_WEEKLY enabled and LONG_MONTHLY disabled")
    void freshInstallSeedsSafeDefaults() {
        service.init();

        assertThat(service.isEnabled(SYNTH_WEEKLY)).isTrue();
        assertThat(service.isEnabled(LONG_MONTHLY)).isFalse();
    }

    @Test
    @DisplayName("restart never overwrites the owner's saved toggle states")
    void restartPreservesExistingToggles() {
        store.add(new BookConfig(SYNTH_WEEKLY, false));
        store.add(new BookConfig(LONG_MONTHLY, true));

        service.init();

        assertThat(service.isEnabled(SYNTH_WEEKLY)).isFalse();
        assertThat(service.isEnabled(LONG_MONTHLY)).isTrue();
    }

    @Test
    @DisplayName("unknown books read as disabled — fail-closed")
    void unknownBookReadsDisabled() {
        service.init();

        assertThat(service.isEnabled("WING_BOOK")).isFalse();
        assertThat(service.isEnabled(null)).isFalse();
    }

    @Test
    @DisplayName("setEnabled persists, updates the cache, and rejects unknown books")
    void setEnabledPersistsAndValidates() {
        service.init();

        service.setEnabled(LONG_MONTHLY, true);
        assertThat(service.isEnabled(LONG_MONTHLY)).isTrue();
        assertThat(store.stream().filter(c -> c.getBook().equals(LONG_MONTHLY)).findFirst()
                .orElseThrow().getEnabled()).isTrue();

        assertThatThrownBy(() -> service.setEnabled("WEEKLY", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SYNTH_WEEKLY or LONG_MONTHLY");
    }

    @Test
    @DisplayName("all() reports both books in fixed weekly-first order")
    void allReportsBothBooksWeeklyFirst() {
        service.init();

        assertThat(service.all().keySet()).containsExactly(SYNTH_WEEKLY, LONG_MONTHLY);
    }
}
