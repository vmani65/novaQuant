package path.to._40c.nqCore.util;

import static org.assertj.core.api.Assertions.assertThat;
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

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import path.to._40c.nqCore.entity.LegTemplate;
import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.entity.WeeklySymbolConfig;
import path.to._40c.nqCore.pojo.LegOrder;
import path.to._40c.nqCore.repo.TradeCapitalRepository;
import path.to._40c.nqCore.service.LegTemplateCache;
import path.to._40c.nqCore.service.WeeklySymbolCache;

/**
 * Pins the phase-1 engine invariant: with BOTH scope slots populated (weekly and monthly
 * symbols, weekly and monthly leg templates), buildInstrument must consume only the WEEKLY
 * slots — weekly symbol prefix, weekly leg templates — and never consult a monthly accessor.
 * The monthly config is inert until the LONG_MONTHLY book is wired in; a leak here would
 * mean a live weekly signal silently trading monthly contracts or monthly lot sizes.
 */
class ComputeUtilBuildInstrumentScopeTest {

    private static final String WEEKLY_PREFIX = "26812";
    private static final String WEEKLY_ROLLOVER_PREFIX = "26819";

    private WeeklySymbolCache symbolCache;
    private LegTemplateCache templateCache;
    private ComputeUtil computeUtil;
    private Position trade;

    @BeforeEach
    void setUp() {
        symbolCache = mock(WeeklySymbolCache.class);
        templateCache = mock(LegTemplateCache.class);
        computeUtil = new ComputeUtil(symbolCache, templateCache, mock(TradeCapitalRepository.class));

        when(symbolCache.get()).thenReturn(
                new WeeklySymbolConfig(WeeklySymbolConfig.WEEKLY_ID, WEEKLY, WEEKLY_PREFIX, WEEKLY_ROLLOVER_PREFIX));
        when(symbolCache.getMonthly()).thenReturn(
                new WeeklySymbolConfig(WeeklySymbolConfig.MONTHLY_ID, MONTHLY, "26AUG", "26SEP"));
        when(templateCache.getLongLegs()).thenReturn(List.of(
                tpl(LONG, CE, BUY, WEEKLY, 10), tpl(LONG, PE, SELL, WEEKLY, 10)));
        when(templateCache.getShortLegs()).thenReturn(List.of(
                tpl(SHORT, PE, BUY, WEEKLY, 10), tpl(SHORT, CE, SELL, WEEKLY, 10)));
        when(templateCache.getMonthlyLongLegs()).thenReturn(List.of(tpl(LONG, CE, BUY, MONTHLY, 20)));
        when(templateCache.getMonthlyShortLegs()).thenReturn(List.of(tpl(SHORT, PE, BUY, MONTHLY, 20)));

        trade = new Position();
    }

    @Test
    @DisplayName("LONG signal builds the weekly synthetic pair from the weekly slots only")
    void longSignalUsesWeeklySlotsOnly() {
        trade.setDirection(LONG);

        List<LegOrder> orders = computeUtil.buildInstrument("24501", trade, false);

        assertThat(orders).hasSize(2);
        assertThat(orders).extracting(LegOrder::getExchangeSymbol).containsExactly(
                "NFO:NIFTY" + WEEKLY_PREFIX + "24500" + CE,
                "NFO:NIFTY" + WEEKLY_PREFIX + "24500" + PE);
        assertThat(orders).extracting(LegOrder::getSide).containsExactly(BUY, SELL);
        assertThat(orders).extracting(LegOrder::getLots).containsOnly(10);
        verify(templateCache, never()).getMonthlyLongLegs();
        verify(templateCache, never()).getMonthlyShortLegs();
        verify(symbolCache, never()).getMonthly();
    }

    @Test
    @DisplayName("SHORT signal uses the weekly short templates, never the monthly slots")
    void shortSignalUsesWeeklyShortTemplates() {
        trade.setDirection(SHORT);

        List<LegOrder> orders = computeUtil.buildInstrument("24474", trade, false);

        assertThat(orders).extracting(LegOrder::getExchangeSymbol).containsExactly(
                "NFO:NIFTY" + WEEKLY_PREFIX + "24450" + PE,
                "NFO:NIFTY" + WEEKLY_PREFIX + "24450" + CE);
        verify(templateCache, never()).getMonthlyShortLegs();
        verify(symbolCache, never()).getMonthly();
    }

    @Test
    @DisplayName("rollover build switches to the weekly rollover prefix, still never the monthly slot")
    void rolloverUsesWeeklyRolloverPrefix() {
        trade.setDirection(LONG);

        List<LegOrder> orders = computeUtil.buildInstrument("24500", trade, true);

        assertThat(orders).extracting(LegOrder::getExchangeSymbol).containsExactly(
                "NFO:NIFTY" + WEEKLY_ROLLOVER_PREFIX + "24500" + CE,
                "NFO:NIFTY" + WEEKLY_ROLLOVER_PREFIX + "24500" + PE);
        verify(symbolCache, never()).getMonthly();
    }

    private static LegTemplate tpl(String direction, String optionType, String side, String scope, int lots) {
        LegTemplate t = new LegTemplate(direction, optionType, side, 0, lots);
        t.setScope(scope);
        return t;
    }
}
