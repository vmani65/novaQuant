package path.to._40c.nqCore.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.zerodhatech.models.Instrument;

import lombok.extern.slf4j.Slf4j;
import path.to._40c.nqCore.entity.WeeklySymbolConfig;
import path.to._40c.nqCore.gateway.KiteGateway;

import static path.to._40c.nqCore.util.Constants.MONTHLY;
import static path.to._40c.nqCore.util.Constants.NFO;
import static path.to._40c.nqCore.util.Constants.NIFTY;
import static path.to._40c.nqCore.util.Constants.ZONE_ID;

/**
 * The LONG_MONTHLY calendar roll (plan §3.3.1): trade the current monthly while its DTE
 * is >= {@link #MIN_DTE}; below that, new positions use the NEXT monthly (decay steepens
 * in the last ~10 days). This service owns the automation:
 *
 * - caches the NFO instrument dump once per IST day (the gateway call is a full-exchange
 *   download — never fetch it per signal);
 * - monthly expiry = the LATEST NIFTY option expiry within each calendar month (weeklies
 *   land earlier in the month by construction);
 * - the traded contract's symbol prefix is derived from a REAL tradingsymbol by cutting
 *   the exchange-reported strike and option type off it — never by regex over the digits,
 *   which weekly prefixes like 26811 make ambiguous — and is verified by reconstruction;
 * - promotion rewrites the MONTHLY symbol row (id=2) via WeeklySymbolService.saveSymbols,
 *   so the UI, cache, and DB stay one source of truth.
 *
 * Promotion only ever changes which contract the NEXT monthly open uses. An open
 * position is untouched by design: exits trade the instruments stored on its legs, so
 * a position opened near month-end simply holds its entry contract across the roll
 * boundary (no mid-position rolling in v1).
 *
 * Fail-safe: if the dump is unavailable (auth not done yet, Kite outage) the configured
 * symbols stay exactly as they are — this service only ever improves the config, never
 * blanks it.
 */
@Service
@Slf4j
public class MonthlyRollService {

    /** Below this many days to expiry, new monthly positions move to the next contract. */
    static final int MIN_DTE = 10;

    private final KiteGateway kiteGateway;
    private final WeeklySymbolService weeklySymbolService;
    private final AtomicReference<List<Instrument>> chain = new AtomicReference<>(List.of());
    private final AtomicReference<LocalDate> chainLoadedOn = new AtomicReference<>();

    public MonthlyRollService(KiteGateway kiteGateway, WeeklySymbolService weeklySymbolService) {
        this.kiteGateway = kiteGateway;
        this.weeklySymbolService = weeklySymbolService;
    }

    /** A resolved monthly contract: its expiry and the tradingsymbol prefix (e.g. 26AUG). */
    public record MonthlyContract(LocalDate expiry, String prefix) {}

    /**
     * Daily tick at 08:40 IST (after the usual auth window): refresh the dump and run the
     * promotion check so the roll happens before the first signal of the day.
     */
    @Scheduled(cron = "0 40 8 * * MON-FRI", zone = ZONE_ID)
    public void dailyRollCheck() {
        checkAndPromoteMonthly();
    }

    /**
     * Ensures the MONTHLY symbol row points at the DTE-correct contract pair. Called by
     * the daily tick, the manual endpoint, and defensively before every monthly open.
     * Any failure leaves the existing config in place.
     */
    public synchronized void checkAndPromoteMonthly() {
        try {
            List<MonthlyContract> monthlies = resolveMonthlyContracts();
            if (monthlies.size() < 2) {
                log.warn("Monthly roll check: {} monthly contracts resolvable from the NFO dump — config left unchanged",
                        monthlies.size());
                return;
            }
            MonthlyContract current = monthlies.get(0);
            MonthlyContract next = monthlies.get(1);
            Optional<WeeklySymbolConfig> existing = weeklySymbolService.getMonthly();
            if (existing.isPresent()
                    && current.prefix().equals(existing.get().getThisWeekSymbol())
                    && next.prefix().equals(existing.get().getRolloverSymbol())) {
                return;
            }
            log.warn("MONTHLY ROLL: promoting monthly symbols {} -> current={} (expiry {}, DTE {}) next={}",
                    existing.map(c -> c.getThisWeekSymbol() + "/" + c.getRolloverSymbol()).orElse("<unset>"),
                    current.prefix(), current.expiry(), dte(current.expiry()), next.prefix());
            weeklySymbolService.saveSymbols(MONTHLY, current.prefix(), next.prefix(), null);
        } catch (Exception e) {
            log.error("Monthly roll check failed — existing monthly symbols left unchanged", e);
        }
    }

    /**
     * Monthly contracts in trading order: index 0 is the contract new positions should
     * use (earliest monthly expiry with DTE >= MIN_DTE), index 1 the one after it.
     */
    public List<MonthlyContract> resolveMonthlyContracts() {
        Map<YearMonth, LocalDate> monthlyExpiries = niftyOptionChain().stream()
                .map(i -> toLocalDate(i.expiry))
                .distinct()
                .collect(Collectors.toMap(YearMonth::from, e -> e, (a, b) -> a.isAfter(b) ? a : b));
        return monthlyExpiries.values().stream()
                .filter(expiry -> dte(expiry) >= MIN_DTE)
                .sorted()
                .map(expiry -> prefixFor(expiry).map(p -> new MonthlyContract(expiry, p)))
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * Prefix for one expiry, derived from a real tradingsymbol: NIFTY + PREFIX + STRIKE +
     * CE/PE, where STRIKE comes from the exchange's own strike field. The cut is verified
     * by reconstructing the symbol; instruments that don't round-trip are skipped.
     */
    private Optional<String> prefixFor(LocalDate expiry) {
        return niftyOptionChain().stream()
                .filter(i -> expiry.equals(toLocalDate(i.expiry)))
                .map(MonthlyRollService::derivePrefix)
                .flatMap(Optional::stream)
                .findFirst();
    }

    static Optional<String> derivePrefix(Instrument i) {
        String symbol = i.tradingsymbol;
        String type = i.instrument_type;
        if (symbol == null || type == null || !symbol.startsWith(NIFTY) || !symbol.endsWith(type)) {
            return Optional.empty();
        }
        String strikeToken = strikeToken(i.strike);
        if (strikeToken == null) return Optional.empty();
        int prefixEnd = symbol.length() - type.length() - strikeToken.length();
        if (prefixEnd <= NIFTY.length()) return Optional.empty();
        String prefix = symbol.substring(NIFTY.length(), prefixEnd);
        if (!(NIFTY + prefix + strikeToken + type).equals(symbol)) return Optional.empty();
        return Optional.of(prefix);
    }

    /** Exchange strike "24500.0" → tradingsymbol token "24500" (fractional strikes kept as-is). */
    private static String strikeToken(String strike) {
        if (strike == null || strike.isBlank()) return null;
        try {
            double d = Double.parseDouble(strike);
            return d == Math.floor(d) ? String.valueOf((long) d) : strike;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** NIFTY index option rows of the cached dump, refreshed once per IST day. */
    private List<Instrument> niftyOptionChain() {
        LocalDate today = LocalDate.now(ZoneId.of(ZONE_ID));
        if (!today.equals(chainLoadedOn.get()) || chain.get().isEmpty()) {
            List<Instrument> dump = kiteGateway.getInstruments(NFO);
            List<Instrument> nifty = dump.stream()
                    .filter(i -> NIFTY.equals(i.name))
                    .filter(i -> i.expiry != null)
                    .filter(i -> "CE".equals(i.instrument_type) || "PE".equals(i.instrument_type))
                    .toList();
            if (nifty.isEmpty()) {
                log.warn("NFO dump returned no NIFTY options ({} rows total) — keeping previous chain of {}",
                        dump.size(), chain.get().size());
            } else {
                chain.set(nifty);
                chainLoadedOn.set(today);
                log.info("NFO chain cached: {} NIFTY option rows, {} distinct expiries",
                        nifty.size(), nifty.stream().map(i -> toLocalDate(i.expiry)).distinct().count());
            }
        }
        return chain.get();
    }

    private static long dte(LocalDate expiry) {
        return ChronoUnit.DAYS.between(LocalDate.now(ZoneId.of(ZONE_ID)), expiry);
    }

    private static LocalDate toLocalDate(java.util.Date d) {
        return d.toInstant().atZone(ZoneId.of(ZONE_ID)).toLocalDate();
    }
}
