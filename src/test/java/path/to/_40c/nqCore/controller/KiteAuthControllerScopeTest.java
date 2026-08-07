package path.to._40c.nqCore.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static path.to._40c.nqCore.util.Constants.BUY;
import static path.to._40c.nqCore.util.Constants.CE;
import static path.to._40c.nqCore.util.Constants.LONG;
import static path.to._40c.nqCore.util.Constants.LONG_MONTHLY;
import static path.to._40c.nqCore.util.Constants.MONTHLY;
import static path.to._40c.nqCore.util.Constants.SELL;
import static path.to._40c.nqCore.util.Constants.SYNTH_WEEKLY;
import static path.to._40c.nqCore.util.Constants.WEEKLY;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import path.to._40c.nqCore.entity.LegTemplate;
import path.to._40c.nqCore.gateway.KiteGateway;
import path.to._40c.nqCore.repo.KiteAuthDetailsRepository;
import path.to._40c.nqCore.repo.LegTemplateRepository;
import path.to._40c.nqCore.service.BookConfigService;
import path.to._40c.nqCore.service.KiteAuthService;
import path.to._40c.nqCore.service.LegTemplateCache;
import path.to._40c.nqCore.service.MonthlySymbolService;
import path.to._40c.nqCore.service.WeeklySymbolService;

/**
 * Covers {@link KiteAuthController}'s two config dimensions: /symbol/save carries the
 * CALENDAR (scope WEEKLY|MONTHLY — which contract series a symbol row describes), while
 * the leg-template CRUD carries the BOOK (SYNTH_WEEKLY|LONG_MONTHLY — which execution
 * book a leg belongs to). The high-value case is the book-less PUT: validate() mutates
 * the request body's null book to SYNTH_WEEKLY as a side effect, so the controller
 * snapshots the requested book before validating — dropping that snapshot would flip
 * every LONG_MONTHLY leg back to SYNTH_WEEKLY on any edit from a stale UI form.
 * Also covers the book enable/disable endpoints backing the UI toggles.
 */
class KiteAuthControllerScopeTest {

    private WeeklySymbolService weeklySymbolService;
    private MonthlySymbolService monthlySymbolService;
    private LegTemplateRepository legTemplateRepository;
    private LegTemplateCache legTemplateCache;
    private BookConfigService bookConfigService;
    private KiteAuthController controller;

    @BeforeEach
    void setUp() {
        weeklySymbolService = mock(WeeklySymbolService.class);
        monthlySymbolService = mock(MonthlySymbolService.class);
        legTemplateRepository = mock(LegTemplateRepository.class);
        legTemplateCache = mock(LegTemplateCache.class);
        bookConfigService = mock(BookConfigService.class);
        controller = new KiteAuthController(mock(KiteAuthDetailsRepository.class), mock(KiteAuthService.class),
                weeklySymbolService, monthlySymbolService, legTemplateRepository, legTemplateCache,
                mock(KiteGateway.class), bookConfigService);
        when(legTemplateRepository.save(any(LegTemplate.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ---------------------------------------------------------------
    // POST /symbol/save — calendar scope routes to the calendar's own service
    // ---------------------------------------------------------------

    @Test
    @DisplayName("symbol save rejects an unknown scope with 400 and never reaches either service")
    void symbolSaveRejectsUnknownScope() {
        ResponseEntity<Map<String, Object>> resp = controller.save("26812", "26819", null, "DAILY");

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().get("success")).isEqualTo(false);
        assertThat((String) resp.getBody().get("message")).contains("scope must be WEEKLY or MONTHLY");
        verify(weeklySymbolService, never()).saveSymbols(anyString(), anyString(), any());
        verify(monthlySymbolService, never()).saveSymbols(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("MONTHLY scope routes to MonthlySymbolService with trimmed symbols — weekly service untouched")
    void symbolSaveRoutesMonthlyScope() {
        ResponseEntity<Map<String, Object>> resp = controller.save(" 26AUG ", " 26SEP ", null, " monthly ");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat((String) resp.getBody().get("message")).isEqualTo("Monthly symbols saved.");
        verify(monthlySymbolService).saveSymbols("26AUG", "26SEP", null);
        verify(weeklySymbolService, never()).saveSymbols(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("missing scope defaults to WEEKLY and routes to WeeklySymbolService — monthly service untouched")
    void symbolSaveDefaultsToWeekly() {
        ResponseEntity<Map<String, Object>> resp = controller.save("26812", "26819", "2026-08-11", null);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat((String) resp.getBody().get("message")).isEqualTo("Weekly symbols saved.");
        verify(weeklySymbolService).saveSymbols("26812", "26819", "2026-08-11");
        verify(monthlySymbolService, never()).saveSymbols(anyString(), anyString(), any());
    }

    // ---------------------------------------------------------------
    // Leg-template CRUD — execution book (SYNTH_WEEKLY | LONG_MONTHLY)
    // ---------------------------------------------------------------

    @Test
    @DisplayName("create persists a LONG_MONTHLY leg, discards any client-sent id, and refreshes the cache")
    void createPersistsMonthlyBook() {
        LegTemplate body = leg(LONG, CE, BUY, 20, LONG_MONTHLY);
        body.setId(99L);

        ResponseEntity<Map<String, Object>> resp = controller.createLegTemplate(body);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        ArgumentCaptor<LegTemplate> saved = ArgumentCaptor.forClass(LegTemplate.class);
        verify(legTemplateRepository).save(saved.capture());
        assertThat(saved.getValue().getId()).isNull();
        assertThat(saved.getValue().getBook()).isEqualTo(LONG_MONTHLY);
        verify(legTemplateCache).refreshCache();
    }

    @Test
    @DisplayName("create without a book defaults the row to SYNTH_WEEKLY")
    void createDefaultsMissingBookToSynthWeekly() {
        ResponseEntity<Map<String, Object>> resp = controller.createLegTemplate(leg(LONG, CE, BUY, 10, null));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        ArgumentCaptor<LegTemplate> saved = ArgumentCaptor.forClass(LegTemplate.class);
        verify(legTemplateRepository).save(saved.capture());
        assertThat(saved.getValue().getBook()).isEqualTo(SYNTH_WEEKLY);
    }

    @Test
    @DisplayName("create rejects an invalid book with 400 and touches neither DB nor cache")
    void createRejectsInvalidBook() {
        ResponseEntity<Map<String, Object>> resp = controller.createLegTemplate(leg(LONG, CE, BUY, 10, "WEEKLY"));

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat((String) resp.getBody().get("message")).contains("book must be SYNTH_WEEKLY or LONG_MONTHLY");
        verify(legTemplateRepository, never()).save(any(LegTemplate.class));
        verify(legTemplateCache, never()).refreshCache();
    }

    @Test
    @DisplayName("book-less PUT preserves the row's existing LONG_MONTHLY book (review fix)")
    void updateWithoutBookPreservesExistingMonthly() {
        LegTemplate existing = leg(LONG, CE, BUY, 1, LONG_MONTHLY);
        existing.setId(7L);
        when(legTemplateRepository.findById(7L)).thenReturn(Optional.of(existing));

        ResponseEntity<Map<String, Object>> resp = controller.updateLegTemplate(7L, leg(LONG, CE, BUY, 2, null));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(existing.getBook()).isEqualTo(LONG_MONTHLY);
        assertThat(existing.getLots()).isEqualTo(2);
        verify(legTemplateRepository).save(existing);
        verify(legTemplateCache).refreshCache();
    }

    @Test
    @DisplayName("book-less PUT on a legacy null-book row lands on SYNTH_WEEKLY")
    void updateWithoutBookOnLegacyRowDefaultsSynthWeekly() {
        LegTemplate existing = leg(LONG, CE, BUY, 10, null);
        existing.setId(3L);
        when(legTemplateRepository.findById(3L)).thenReturn(Optional.of(existing));

        controller.updateLegTemplate(3L, leg(LONG, CE, BUY, 10, ""));

        assertThat(existing.getBook()).isEqualTo(SYNTH_WEEKLY);
    }

    @Test
    @DisplayName("PUT with an explicit book moves the leg between books")
    void updateMovesLegBetweenBooks() {
        LegTemplate existing = leg(LONG, CE, SELL, 10, SYNTH_WEEKLY);
        existing.setId(5L);
        when(legTemplateRepository.findById(5L)).thenReturn(Optional.of(existing));

        controller.updateLegTemplate(5L, leg(LONG, CE, BUY, 20, LONG_MONTHLY));

        assertThat(existing.getBook()).isEqualTo(LONG_MONTHLY);
        assertThat(existing.getSide()).isEqualTo(BUY);
        assertThat(existing.getLots()).isEqualTo(20);
    }

    @Test
    @DisplayName("PUT on an unknown id returns 404 without refreshing the cache")
    void updateUnknownIdReturns404() {
        when(legTemplateRepository.findById(42L)).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> resp = controller.updateLegTemplate(42L, leg(LONG, CE, BUY, 10, SYNTH_WEEKLY));

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        verify(legTemplateCache, never()).refreshCache();
    }

    @Test
    @DisplayName("DELETE on an unknown id returns 404; a real delete refreshes the cache")
    void deleteRefreshesCacheOnlyWhenRowExists() {
        when(legTemplateRepository.existsById(42L)).thenReturn(false);
        assertThat(controller.deleteLegTemplate(42L).getStatusCode().value()).isEqualTo(404);
        verify(legTemplateRepository, never()).deleteById(anyLong());

        when(legTemplateRepository.existsById(7L)).thenReturn(true);
        assertThat(controller.deleteLegTemplate(7L).getStatusCode().value()).isEqualTo(200);
        verify(legTemplateRepository).deleteById(7L);
        verify(legTemplateCache).refreshCache();
    }

    // ---------------------------------------------------------------
    // Book toggle endpoints
    // ---------------------------------------------------------------

    @Test
    @DisplayName("book toggle normalizes the path variable and reports the new state map")
    void bookToggleNormalizesAndReports() {
        when(bookConfigService.all()).thenReturn(Map.of(SYNTH_WEEKLY, true, LONG_MONTHLY, true));

        ResponseEntity<Map<String, Object>> resp = controller.setBookEnabled(" long_monthly ", true);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(bookConfigService).setEnabled(LONG_MONTHLY, true);
        assertThat(resp.getBody().get("success")).isEqualTo(true);
    }

    @Test
    @DisplayName("book toggle on an unknown book returns 400 with the service's message")
    void bookToggleRejectsUnknownBook() {
        doThrow(new IllegalArgumentException("book must be SYNTH_WEEKLY or LONG_MONTHLY (got 'WEEKLY')"))
                .when(bookConfigService).setEnabled("WEEKLY", true);

        ResponseEntity<Map<String, Object>> resp = controller.setBookEnabled("weekly", true);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat((String) resp.getBody().get("message")).contains("SYNTH_WEEKLY or LONG_MONTHLY");
    }

    private static LegTemplate leg(String direction, String optionType, String side, int lots, String book) {
        LegTemplate t = new LegTemplate(direction, optionType, side, 0, lots);
        t.setBook(book);
        return t;
    }
}
