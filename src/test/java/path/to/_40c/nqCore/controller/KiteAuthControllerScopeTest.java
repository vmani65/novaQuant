package path.to._40c.nqCore.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static path.to._40c.nqCore.util.Constants.BUY;
import static path.to._40c.nqCore.util.Constants.CE;
import static path.to._40c.nqCore.util.Constants.LONG;
import static path.to._40c.nqCore.util.Constants.MONTHLY;
import static path.to._40c.nqCore.util.Constants.SELL;
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
import path.to._40c.nqCore.service.KiteAuthService;
import path.to._40c.nqCore.service.LegTemplateCache;
import path.to._40c.nqCore.service.WeeklySymbolService;

/**
 * Covers the phase-1 scope handling on {@link KiteAuthController}: /symbol/save must reject
 * anything but WEEKLY/MONTHLY (normalizing case and whitespace first), and the leg-template
 * CRUD must thread scope through create/update without ever silently re-scoping a row. The
 * high-value case is the scope-less PUT: validate() mutates the request body's null scope to
 * WEEKLY as a side effect, so the controller snapshots the requested scope before validating —
 * dropping that snapshot would flip every MONTHLY leg back to WEEKLY on any edit from a stale
 * UI form, which is exactly the regression the review fix closed.
 */
class KiteAuthControllerScopeTest {

    private WeeklySymbolService weeklySymbolService;
    private LegTemplateRepository legTemplateRepository;
    private LegTemplateCache legTemplateCache;
    private KiteAuthController controller;

    @BeforeEach
    void setUp() {
        weeklySymbolService = mock(WeeklySymbolService.class);
        legTemplateRepository = mock(LegTemplateRepository.class);
        legTemplateCache = mock(LegTemplateCache.class);
        controller = new KiteAuthController(mock(KiteAuthDetailsRepository.class), mock(KiteAuthService.class),
                weeklySymbolService, legTemplateRepository, legTemplateCache, mock(KiteGateway.class));
        when(legTemplateRepository.save(any(LegTemplate.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ---------------------------------------------------------------
    // POST /symbol/save
    // ---------------------------------------------------------------

    @Test
    @DisplayName("symbol save rejects an unknown scope with 400 and never reaches the service")
    void symbolSaveRejectsUnknownScope() {
        ResponseEntity<Map<String, Object>> resp = controller.save("26812", "26819", null, "DAILY");

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().get("success")).isEqualTo(false);
        assertThat((String) resp.getBody().get("message")).contains("scope must be WEEKLY or MONTHLY");
        verify(weeklySymbolService, never()).saveSymbols(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("symbol save normalizes case/whitespace on scope and trims the symbols")
    void symbolSaveNormalizesScopeAndTrimsSymbols() {
        ResponseEntity<Map<String, Object>> resp = controller.save(" 26AUG ", " 26SEP ", null, " monthly ");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat((String) resp.getBody().get("message")).isEqualTo("Monthly symbols saved.");
        verify(weeklySymbolService).saveSymbols(MONTHLY, "26AUG", "26SEP", null);
    }

    @Test
    @DisplayName("symbol save without a scope defaults to WEEKLY")
    void symbolSaveDefaultsToWeekly() {
        ResponseEntity<Map<String, Object>> resp = controller.save("26812", "26819", "2026-08-11", null);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat((String) resp.getBody().get("message")).isEqualTo("Weekly symbols saved.");
        verify(weeklySymbolService).saveSymbols(WEEKLY, "26812", "26819", "2026-08-11");
    }

    // ---------------------------------------------------------------
    // Leg-template CRUD
    // ---------------------------------------------------------------

    @Test
    @DisplayName("create persists a MONTHLY leg, discards any client-sent id, and refreshes the cache")
    void createPersistsMonthlyScope() {
        LegTemplate body = leg(LONG, CE, BUY, 20, MONTHLY);
        body.setId(99L);

        ResponseEntity<Map<String, Object>> resp = controller.createLegTemplate(body);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        ArgumentCaptor<LegTemplate> saved = ArgumentCaptor.forClass(LegTemplate.class);
        verify(legTemplateRepository).save(saved.capture());
        assertThat(saved.getValue().getId()).isNull();
        assertThat(saved.getValue().getScope()).isEqualTo(MONTHLY);
        verify(legTemplateCache).refreshCache();
    }

    @Test
    @DisplayName("create without a scope defaults the row to WEEKLY")
    void createDefaultsMissingScopeToWeekly() {
        ResponseEntity<Map<String, Object>> resp = controller.createLegTemplate(leg(LONG, CE, BUY, 10, null));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        ArgumentCaptor<LegTemplate> saved = ArgumentCaptor.forClass(LegTemplate.class);
        verify(legTemplateRepository).save(saved.capture());
        assertThat(saved.getValue().getScope()).isEqualTo(WEEKLY);
    }

    @Test
    @DisplayName("create rejects an invalid scope with 400 and touches neither DB nor cache")
    void createRejectsInvalidScope() {
        ResponseEntity<Map<String, Object>> resp = controller.createLegTemplate(leg(LONG, CE, BUY, 10, "DAILY"));

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat((String) resp.getBody().get("message")).contains("scope must be WEEKLY or MONTHLY");
        verify(legTemplateRepository, never()).save(any(LegTemplate.class));
        verify(legTemplateCache, never()).refreshCache();
    }

    @Test
    @DisplayName("scope-less PUT preserves the row's existing MONTHLY scope (review fix)")
    void updateWithoutScopePreservesExistingMonthly() {
        LegTemplate existing = leg(LONG, CE, BUY, 1, MONTHLY);
        existing.setId(7L);
        when(legTemplateRepository.findById(7L)).thenReturn(Optional.of(existing));

        ResponseEntity<Map<String, Object>> resp = controller.updateLegTemplate(7L, leg(LONG, CE, BUY, 2, null));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(existing.getScope()).isEqualTo(MONTHLY);
        assertThat(existing.getLots()).isEqualTo(2);
        verify(legTemplateRepository).save(existing);
        verify(legTemplateCache).refreshCache();
    }

    @Test
    @DisplayName("scope-less PUT on a legacy null-scope row lands on WEEKLY")
    void updateWithoutScopeOnLegacyRowDefaultsWeekly() {
        LegTemplate existing = leg(LONG, CE, BUY, 10, null);
        existing.setId(3L);
        when(legTemplateRepository.findById(3L)).thenReturn(Optional.of(existing));

        controller.updateLegTemplate(3L, leg(LONG, CE, BUY, 10, ""));

        assertThat(existing.getScope()).isEqualTo(WEEKLY);
    }

    @Test
    @DisplayName("PUT with an explicit scope moves the leg between scopes")
    void updateMovesLegBetweenScopes() {
        LegTemplate existing = leg(LONG, CE, SELL, 10, WEEKLY);
        existing.setId(5L);
        when(legTemplateRepository.findById(5L)).thenReturn(Optional.of(existing));

        controller.updateLegTemplate(5L, leg(LONG, CE, BUY, 20, MONTHLY));

        assertThat(existing.getScope()).isEqualTo(MONTHLY);
        assertThat(existing.getSide()).isEqualTo(BUY);
        assertThat(existing.getLots()).isEqualTo(20);
    }

    @Test
    @DisplayName("PUT on an unknown id returns 404 without refreshing the cache")
    void updateUnknownIdReturns404() {
        when(legTemplateRepository.findById(42L)).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> resp = controller.updateLegTemplate(42L, leg(LONG, CE, BUY, 10, WEEKLY));

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

    private static LegTemplate leg(String direction, String optionType, String side, int lots, String scope) {
        LegTemplate t = new LegTemplate(direction, optionType, side, 0, lots);
        t.setScope(scope);
        return t;
    }
}
