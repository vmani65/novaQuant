package path.to._40c.nqCore.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static path.to._40c.nqCore.util.Constants.BUY;
import static path.to._40c.nqCore.util.Constants.CE;
import static path.to._40c.nqCore.util.Constants.LONG;
import static path.to._40c.nqCore.util.Constants.LONG_MONTHLY;
import static path.to._40c.nqCore.util.Constants.MONTHLY;
import static path.to._40c.nqCore.util.Constants.PE;
import static path.to._40c.nqCore.util.Constants.SELL;
import static path.to._40c.nqCore.util.Constants.SHORT;
import static path.to._40c.nqCore.util.Constants.SYNTH_WEEKLY;
import static path.to._40c.nqCore.util.Constants.WEEKLY;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import path.to._40c.nqCore.entity.LegTemplate;
import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.entity.SymbolConfig;
import path.to._40c.nqCore.pojo.LegOrder;
import path.to._40c.nqCore.repo.TradeCapitalRepository;
import path.to._40c.nqCore.service.LegTemplateCache;
import path.to._40c.nqCore.service.MonthlySymbolCache;
import path.to._40c.nqCore.service.WeeklySymbolCache;

/**
 * Pins the two-book build isolation in ComputeUtil. buildWeeklyInstrument must consume
 * only the weekly slots (weekly symbol prefix, SYNTH_WEEKLY leg templates) and never
 * consult a monthly accessor even with both slots fully populated — a leak here would
 * mean a live weekly signal silently trading monthly contracts or monthly lot sizes.
 * buildMonthlyInstrument mirrors that with the monthly slots, and must fail LOUDLY
 * (IllegalStateException, caught by the fan-out's per-book isolation) when the monthly
 * book is unconfigured rather than building a wrong instrument.
 */
class ComputeUtilBuildInstrumentScopeTest {

    private static final String WEEKLY_PREFIX = "26812";
    private static final String WEEKLY_ROLLOVER_PREFIX = "26819";
    private static final String MONTHLY_PREFIX = "26AUG";

    private WeeklySymbolCache symbolCache;
    private MonthlySymbolCache monthlySymbolCache;
    private LegTemplateCache templateCache;
    private ComputeUtil computeUtil;
    private Position trade;

    @BeforeEach
    void setUp() {
        symbolCache = mock(WeeklySymbolCache.class);
        monthlySymbolCache = mock(MonthlySymbolCache.class);
        templateCache = mock(LegTemplateCache.class);
        computeUtil = new ComputeUtil(symbolCache, monthlySymbolCache, templateCache, mock(TradeCapitalRepository.class));

        when(symbolCache.get()).thenReturn(
                new SymbolConfig(SymbolConfig.WEEKLY_ID, WEEKLY, WEEKLY_PREFIX, WEEKLY_ROLLOVER_PREFIX));
        when(monthlySymbolCache.get()).thenReturn(
                new SymbolConfig(SymbolConfig.MONTHLY_ID, MONTHLY, MONTHLY_PREFIX, "26SEP"));
        when(templateCache.getLongLegs()).thenReturn(List.of(
                tpl(LONG, CE, BUY, SYNTH_WEEKLY, 10), tpl(LONG, PE, SELL, SYNTH_WEEKLY, 10)));
        when(templateCache.getShortLegs()).thenReturn(List.of(
                tpl(SHORT, PE, BUY, SYNTH_WEEKLY, 10), tpl(SHORT, CE, SELL, SYNTH_WEEKLY, 10)));
        when(templateCache.getMonthlyLongLegs()).thenReturn(List.of(tpl(LONG, CE, BUY, LONG_MONTHLY, 2)));
        when(templateCache.getMonthlyShortLegs()).thenReturn(List.of(tpl(SHORT, PE, BUY, LONG_MONTHLY, 2)));

        trade = new Position();
    }

    // ---------------------------------------------------------------
    // Weekly build isolation
    // ---------------------------------------------------------------

    @Test
    @DisplayName("weekly LONG build uses the weekly synthetic pair from the weekly slots only")
    void weeklyLongUsesWeeklySlotsOnly() {
        trade.setDirection(LONG);

        List<LegOrder> orders = computeUtil.buildWeeklyInstrument("24501", trade, false);

        assertThat(orders).hasSize(2);
        assertThat(orders).extracting(LegOrder::getExchangeSymbol).containsExactly(
                "NFO:NIFTY" + WEEKLY_PREFIX + "24500" + CE,
                "NFO:NIFTY" + WEEKLY_PREFIX + "24500" + PE);
        assertThat(orders).extracting(LegOrder::getSide).containsExactly(BUY, SELL);
        assertThat(orders).extracting(LegOrder::getLots).containsOnly(10);
        verify(templateCache, never()).getMonthlyLongLegs();
        verify(templateCache, never()).getMonthlyShortLegs();
        verify(monthlySymbolCache, never()).get();
    }

    @Test
    @DisplayName("weekly SHORT build uses the weekly short templates, never the monthly slots")
    void weeklyShortUsesWeeklyShortTemplates() {
        trade.setDirection(SHORT);

        List<LegOrder> orders = computeUtil.buildWeeklyInstrument("24474", trade, false);

        assertThat(orders).extracting(LegOrder::getExchangeSymbol).containsExactly(
                "NFO:NIFTY" + WEEKLY_PREFIX + "24450" + PE,
                "NFO:NIFTY" + WEEKLY_PREFIX + "24450" + CE);
        verify(templateCache, never()).getMonthlyShortLegs();
        verify(monthlySymbolCache, never()).get();
    }

    @Test
    @DisplayName("weekly rollover build switches to the weekly rollover prefix, still never the monthly slot")
    void weeklyRolloverUsesWeeklyRolloverPrefix() {
        trade.setDirection(LONG);

        List<LegOrder> orders = computeUtil.buildWeeklyInstrument("24500", trade, true);

        assertThat(orders).extracting(LegOrder::getExchangeSymbol).containsExactly(
                "NFO:NIFTY" + WEEKLY_ROLLOVER_PREFIX + "24500" + CE,
                "NFO:NIFTY" + WEEKLY_ROLLOVER_PREFIX + "24500" + PE);
        verify(monthlySymbolCache, never()).get();
    }

    // ---------------------------------------------------------------
    // Monthly build
    // ---------------------------------------------------------------

    @Test
    @DisplayName("monthly LONG build is a single bought CE on the monthly contract at monthly lots")
    void monthlyLongBuildsSingleBoughtCe() {
        trade.setDirection(LONG);

        List<LegOrder> orders = computeUtil.buildMonthlyInstrument("24501", trade);

        assertThat(orders).hasSize(1);
        LegOrder o = orders.get(0);
        assertThat(o.getExchangeSymbol()).isEqualTo("NFO:NIFTY" + MONTHLY_PREFIX + "24500" + CE);
        assertThat(o.getSide()).isEqualTo(BUY);
        assertThat(o.getLots()).isEqualTo(2);
        verify(templateCache, never()).getLongLegs();
    }

    @Test
    @DisplayName("monthly SHORT build is a single bought PE — never the weekly templates")
    void monthlyShortBuildsSingleBoughtPe() {
        trade.setDirection(SHORT);

        List<LegOrder> orders = computeUtil.buildMonthlyInstrument("24474", trade);

        assertThat(orders).hasSize(1);
        assertThat(orders.get(0).getExchangeSymbol()).isEqualTo("NFO:NIFTY" + MONTHLY_PREFIX + "24450" + PE);
        assertThat(orders.get(0).getSide()).isEqualTo(BUY);
        verify(templateCache, never()).getShortLegs();
    }

    @Test
    @DisplayName("monthly build with no monthly templates fails loudly instead of building a wrong instrument")
    void monthlyBuildWithoutTemplatesThrows() {
        when(templateCache.getMonthlyLongLegs()).thenReturn(List.of());
        trade.setDirection(LONG);

        assertThatThrownBy(() -> computeUtil.buildMonthlyInstrument("24501", trade))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no LONG leg templates");
    }

    @Test
    @DisplayName("monthly build with no monthly symbol config fails loudly")
    void monthlyBuildWithoutSymbolThrows() {
        when(monthlySymbolCache.get()).thenReturn(null);
        trade.setDirection(LONG);

        assertThatThrownBy(() -> computeUtil.buildMonthlyInstrument("24501", trade))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no monthly symbol");
    }

    private static LegTemplate tpl(String direction, String optionType, String side, String book, int lots) {
        LegTemplate t = new LegTemplate(direction, optionType, side, 0, lots);
        t.setBook(book);
        return t;
    }
}
