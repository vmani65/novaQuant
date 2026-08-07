package path.to._40c.nqCore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static path.to._40c.nqCore.entity.SymbolConfig.MONTHLY_ID;
import static path.to._40c.nqCore.entity.SymbolConfig.WEEKLY_ID;
import static path.to._40c.nqCore.util.Constants.MONTHLY;
import static path.to._40c.nqCore.util.Constants.NFO;
import static path.to._40c.nqCore.util.Constants.ZONE_ID;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.zerodhatech.models.Instrument;

import path.to._40c.nqCore.entity.SymbolConfig;
import path.to._40c.nqCore.gateway.KiteGateway;
import path.to._40c.nqCore.repo.SymbolConfigRepository;
import path.to._40c.nqCore.service.MonthlySymbolService.MonthlyContract;

/**
 * MonthlySymbolService owns ONLY the monthly calendar: row id=2, the monthly cache
 * slot, and the automated contract-advance (mirror of WeeklySymbolService, whose
 * mechanism is operator-driven). Pinned here:
 * - row ops touch id=2 only — the weekly row is never read or written;
 * - monthly expiry = the LATEST NIFTY option expiry within a calendar month;
 * - trade the current monthly while DTE >= 10, else the NEXT monthly — exact at the
 *   boundary (DTE 10 keeps the contract, DTE 9 advances);
 * - the tradingsymbol prefix is derived by cutting the exchange-reported strike +
 *   option type off a real symbol and verified by reconstruction — never digit regex,
 *   which weekly prefixes like 26811 (strike 24650 → NIFTY2681124650CE) make ambiguous;
 * - sync rewrites the row only when it differs, and an empty/broken NFO dump leaves
 *   the existing config untouched (fail-safe: never blank the symbols).
 */
class MonthlySymbolServiceTest {

    private static final ZoneId IST = ZoneId.of(ZONE_ID);

    private SymbolConfigRepository repo;
    private MonthlySymbolCache cache;
    private KiteGateway gateway;
    private MonthlySymbolService service;
    private final LocalDate today = LocalDate.now(IST);

    @BeforeEach
    void setUp() {
        repo = mock(SymbolConfigRepository.class);
        cache = new MonthlySymbolCache();
        gateway = mock(KiteGateway.class);
        service = new MonthlySymbolService(repo, cache, gateway);
        when(repo.save(any(SymbolConfig.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repo.findById(MONTHLY_ID)).thenReturn(Optional.empty());
    }

    // ---------------------------------------------------------------
    // Row + cache ownership
    // ---------------------------------------------------------------

    @Test
    @DisplayName("save upserts row id=2 only and fills the monthly cache slot — weekly row never touched")
    void saveUpsertsMonthlyRowOnly() {
        service.saveSymbols("26AUG", "26SEP", null);

        ArgumentCaptor<SymbolConfig> saved = ArgumentCaptor.forClass(SymbolConfig.class);
        verify(repo).save(saved.capture());
        SymbolConfig cfg = saved.getValue();
        assertThat(cfg.getId()).isEqualTo(MONTHLY_ID);
        assertThat(cfg.getScope()).isEqualTo(MONTHLY);
        assertThat(cfg.getThisWeekSymbol()).isEqualTo("26AUG");
        assertThat(cfg.getRolloverSymbol()).isEqualTo("26SEP");
        assertThat(cache.get()).isSameAs(cfg);
        verify(repo, never()).findById(WEEKLY_ID);
    }

    @Test
    @DisplayName("warmCache fills the monthly slot from row id=2 and leaves it empty when no row exists")
    void warmCacheFillsMonthlySlot() {
        service.warmCache();
        assertThat(cache.get()).isNull();

        SymbolConfig monthly = new SymbolConfig(MONTHLY_ID, MONTHLY, "26AUG", "26SEP");
        when(repo.findById(MONTHLY_ID)).thenReturn(Optional.of(monthly));
        service.warmCache();
        assertThat(cache.get()).isSameAs(monthly);
        verify(repo, never()).findById(WEEKLY_ID);
    }

    // ---------------------------------------------------------------
    // Contract resolution + sync
    // ---------------------------------------------------------------

    @Test
    @DisplayName("monthly expiry is the latest expiry of each month; weeklies in between are ignored")
    void monthlyIsLatestExpiryOfMonth() {
        LocalDate base = today.plusDays(40).withDayOfMonth(1);
        List<Instrument> chain = new ArrayList<>();
        chain.addAll(options("W1", base.plusDays(10)));
        chain.addAll(options("CURM", base.plusDays(20)));
        chain.addAll(options("NXTM", base.plusMonths(1).plusDays(20)));
        when(gateway.getInstruments(NFO)).thenReturn(chain);

        List<MonthlyContract> monthlies = service.resolveMonthlyContracts();

        assertThat(monthlies).extracting(MonthlyContract::prefix).containsExactly("CURM", "NXTM");
    }

    @Test
    @DisplayName("DTE exactly 10 keeps the current monthly; DTE 9 advances to the next")
    void dteBoundaryIsExact() {
        when(gateway.getInstruments(NFO)).thenReturn(chainWithCurrentMonthlyAt(today.plusDays(10)));
        assertThat(service.resolveMonthlyContracts().get(0).prefix()).isEqualTo("CURM");

        MonthlySymbolService fresh = new MonthlySymbolService(repo, cache, gateway);
        when(gateway.getInstruments(NFO)).thenReturn(chainWithCurrentMonthlyAt(today.plusDays(9)));
        assertThat(fresh.resolveMonthlyContracts().get(0).prefix()).isEqualTo("NXTM");
    }

    @Test
    @DisplayName("prefix derivation cuts strike+type using the exchange strike field, ambiguous weekly digits included")
    void prefixDerivationIsRegexFree() {
        assertThat(MonthlySymbolService.derivePrefix(instrument("NIFTY2681124650CE", "24650.0", "CE", today)))
                .contains("26811");
        assertThat(MonthlySymbolService.derivePrefix(instrument("NIFTY26AUG24500PE", "24500.0", "PE", today)))
                .contains("26AUG");
        assertThat(MonthlySymbolService.derivePrefix(instrument("NIFTY26AUG24500PE", "99999.0", "PE", today)))
                .as("strike that does not reconstruct the symbol is rejected")
                .isEmpty();
        assertThat(MonthlySymbolService.derivePrefix(instrument("BANKNIFTY26AUG50000CE", "50000.0", "CE", today)))
                .as("symbols not starting with NIFTY are rejected")
                .isEmpty();
    }

    @Test
    @DisplayName("sync rewrites the monthly row when it differs from the DTE-correct pair")
    void syncRewritesStaleConfig() {
        when(gateway.getInstruments(NFO)).thenReturn(chainWithCurrentMonthlyAt(today.plusDays(9)));
        when(repo.findById(MONTHLY_ID)).thenReturn(
                Optional.of(new SymbolConfig(MONTHLY_ID, MONTHLY, "CURM", "NXTM")));

        service.syncTradedContract();

        ArgumentCaptor<SymbolConfig> saved = ArgumentCaptor.forClass(SymbolConfig.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getThisWeekSymbol()).isEqualTo("NXTM");
        assertThat(saved.getValue().getRolloverSymbol()).isEqualTo("FARM");
    }

    @Test
    @DisplayName("sync is a no-op when the row already points at the right pair")
    void syncSkipsWhenConfigCorrect() {
        when(gateway.getInstruments(NFO)).thenReturn(chainWithCurrentMonthlyAt(today.plusDays(20)));
        when(repo.findById(MONTHLY_ID)).thenReturn(
                Optional.of(new SymbolConfig(MONTHLY_ID, MONTHLY, "CURM", "NXTM")));

        service.syncTradedContract();

        verify(repo, never()).save(any(SymbolConfig.class));
    }

    @Test
    @DisplayName("an empty NFO dump leaves the existing row untouched — never blank the symbols")
    void emptyDumpIsFailSafe() {
        when(gateway.getInstruments(NFO)).thenReturn(List.of());

        service.syncTradedContract();

        verify(repo, never()).save(any(SymbolConfig.class));
    }

    /**
     * Current monthly at the given expiry, next and far monthlies at +35/+70 days —
     * 35-day spacing guarantees distinct calendar months from any starting date.
     */
    private List<Instrument> chainWithCurrentMonthlyAt(LocalDate currentMonthlyExpiry) {
        List<Instrument> chain = new ArrayList<>();
        chain.addAll(options("CURM", currentMonthlyExpiry));
        chain.addAll(options("NXTM", currentMonthlyExpiry.plusDays(35)));
        chain.addAll(options("FARM", currentMonthlyExpiry.plusDays(70)));
        return chain;
    }

    private List<Instrument> options(String prefix, LocalDate expiry) {
        return List.of(
                instrument("NIFTY" + prefix + "24500CE", "24500.0", "CE", expiry),
                instrument("NIFTY" + prefix + "24500PE", "24500.0", "PE", expiry));
    }

    private static Instrument instrument(String tradingsymbol, String strike, String type, LocalDate expiry) {
        Instrument i = new Instrument();
        i.tradingsymbol = tradingsymbol;
        i.name = "NIFTY";
        i.instrument_type = type;
        i.strike = strike;
        i.segment = "NFO-OPT";
        i.exchange = "NFO";
        i.expiry = Date.from(expiry.atStartOfDay(IST).toInstant());
        return i;
    }
}
