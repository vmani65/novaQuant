package path.to._40c.nqCore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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

import com.zerodhatech.models.Instrument;

import path.to._40c.nqCore.entity.WeeklySymbolConfig;
import path.to._40c.nqCore.gateway.KiteGateway;
import path.to._40c.nqCore.service.MonthlyRollService.MonthlyContract;

/**
 * Business rules of the LONG_MONTHLY calendar roll (plan §3.3.1):
 * - monthly expiry = the LATEST NIFTY option expiry within a calendar month;
 * - trade the current monthly while DTE >= 10, else the NEXT monthly — pinned exactly at
 *   the boundary (DTE 10 keeps the contract, DTE 9 promotes);
 * - the tradingsymbol prefix is derived by cutting the exchange-reported strike + option
 *   type off a real symbol and verified by reconstruction — never by digit regex, which
 *   weekly prefixes like 26811 (strike 24650 → NIFTY2681124650CE) make ambiguous;
 * - promotion rewrites the MONTHLY symbol row only when it differs, and an empty/broken
 *   NFO dump leaves the existing config untouched (fail-safe: never blank the symbols).
 */
class MonthlyRollServiceTest {

    private static final ZoneId IST = ZoneId.of(ZONE_ID);

    private KiteGateway gateway;
    private WeeklySymbolService symbolService;
    private MonthlyRollService service;
    private final LocalDate today = LocalDate.now(IST);

    @BeforeEach
    void setUp() {
        gateway = mock(KiteGateway.class);
        symbolService = mock(WeeklySymbolService.class);
        service = new MonthlyRollService(gateway, symbolService);
        when(symbolService.getMonthly()).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("monthly expiry is the latest expiry of each month; weeklies in between are ignored")
    void monthlyIsLatestExpiryOfMonth() {
        // Anchor to the 1st of a month safely in the future so the weekly/monthly pairing
        // never straddles a month boundary regardless of today's date.
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
    @DisplayName("DTE exactly 10 keeps the current monthly; DTE 9 promotes to the next")
    void dteBoundaryIsExact() {
        when(gateway.getInstruments(NFO)).thenReturn(chainWithCurrentMonthlyAt(today.plusDays(10)));
        assertThat(service.resolveMonthlyContracts().get(0).prefix()).isEqualTo("CURM");

        MonthlyRollService fresh = new MonthlyRollService(gateway, symbolService);
        when(gateway.getInstruments(NFO)).thenReturn(chainWithCurrentMonthlyAt(today.plusDays(9)));
        assertThat(fresh.resolveMonthlyContracts().get(0).prefix()).isEqualTo("NXTM");
    }

    @Test
    @DisplayName("prefix derivation cuts strike+type using the exchange strike field, ambiguous weekly digits included")
    void prefixDerivationIsRegexFree() {
        assertThat(MonthlyRollService.derivePrefix(instrument("NIFTY2681124650CE", "24650.0", "CE", today)))
                .contains("26811");
        assertThat(MonthlyRollService.derivePrefix(instrument("NIFTY26AUG24500PE", "24500.0", "PE", today)))
                .contains("26AUG");
        assertThat(MonthlyRollService.derivePrefix(instrument("NIFTY26AUG24500PE", "99999.0", "PE", today)))
                .as("strike that does not reconstruct the symbol is rejected")
                .isEmpty();
        assertThat(MonthlyRollService.derivePrefix(instrument("BANKNIFTY26AUG50000CE", "50000.0", "CE", today)))
                .as("symbols not starting with NIFTY are rejected")
                .isEmpty();
    }

    @Test
    @DisplayName("promotion rewrites the MONTHLY symbol row when it differs from the DTE-correct pair")
    void promotionRewritesStaleConfig() {
        when(gateway.getInstruments(NFO)).thenReturn(chainWithCurrentMonthlyAt(today.plusDays(9)));
        when(symbolService.getMonthly()).thenReturn(
                Optional.of(new WeeklySymbolConfig(WeeklySymbolConfig.MONTHLY_ID, MONTHLY, "CURM", "NXTM")));

        service.checkAndPromoteMonthly();

        verify(symbolService).saveSymbols(MONTHLY, "NXTM", "FARM", null);
    }

    @Test
    @DisplayName("promotion is a no-op when the config already points at the right pair")
    void promotionSkipsWhenConfigCorrect() {
        when(gateway.getInstruments(NFO)).thenReturn(chainWithCurrentMonthlyAt(today.plusDays(20)));
        when(symbolService.getMonthly()).thenReturn(
                Optional.of(new WeeklySymbolConfig(WeeklySymbolConfig.MONTHLY_ID, MONTHLY, "CURM", "NXTM")));

        service.checkAndPromoteMonthly();

        verify(symbolService, never()).saveSymbols(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("an empty NFO dump leaves the existing config untouched — never blank the symbols")
    void emptyDumpIsFailSafe() {
        when(gateway.getInstruments(NFO)).thenReturn(List.of());

        service.checkAndPromoteMonthly();

        verify(symbolService, never()).saveSymbols(anyString(), anyString(), anyString(), any());
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
