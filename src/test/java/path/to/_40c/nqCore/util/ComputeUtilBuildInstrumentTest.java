package path.to._40c.nqCore.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import path.to._40c.nqCore.entity.LegTemplate;
import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.entity.Strategy;
import path.to._40c.nqCore.entity.WeeklySymbolConfig;
import path.to._40c.nqCore.pojo.LegOrder;
import path.to._40c.nqCore.service.LegTemplateCache;
import path.to._40c.nqCore.service.StrategyRegistry;
import path.to._40c.nqCore.service.StrikeOccupancyService;
import path.to._40c.nqCore.service.WeeklySymbolCache;

/**
 * Verifies buildInstrument delegates strike selection to StrikeOccupancyService, selects
 * templates per strategy, applies the registry-level lots override onto copies (never the
 * cached rows), and builds every leg (symbols, strike column, moneyness) from the resolved
 * base strike.
 */
class ComputeUtilBuildInstrumentTest {

    private static final String THIS_WEEK = "25807";
    private static final String ROLLOVER = "25814";

    private WeeklySymbolCache symbolCache;
    private LegTemplateCache templateCache;
    private StrikeOccupancyService strikeOccupancy;
    private StrategyRegistry strategyRegistry;
    private ComputeUtil computeUtil;
    private List<LegTemplate> defaultLongTemplates;

    @BeforeEach
    void setUp() {
        symbolCache = mock(WeeklySymbolCache.class);
        templateCache = mock(LegTemplateCache.class);
        strikeOccupancy = mock(StrikeOccupancyService.class);
        strategyRegistry = mock(StrategyRegistry.class);
        computeUtil = new ComputeUtil(symbolCache, templateCache, null, strikeOccupancy, strategyRegistry);

        WeeklySymbolConfig cfg = new WeeklySymbolConfig();
        cfg.setThisWeekSymbol(THIS_WEEK);
        cfg.setRolloverSymbol(ROLLOVER);
        when(symbolCache.get()).thenReturn(cfg);
        defaultLongTemplates = List.of(
                template("CE", "BUY", 0, 10),
                template("PE", "SELL", 0, 10));
        when(templateCache.getLegs(eq("LONG"), anyString())).thenReturn(defaultLongTemplates);
    }

    @Test
    @DisplayName("legs are built at the occupancy-resolved base strike, not the raw ATM")
    void legsBuiltAtResolvedStrike() {
        when(strikeOccupancy.resolveBaseStrike(anyInt(), anyString(), anyString(), anyList())).thenReturn(23550);
        Position trade = longTrade("MS2");

        List<LegOrder> orders = computeUtil.buildInstrument("23500", trade, false);

        verify(strikeOccupancy).resolveBaseStrike(eq(23500), eq(THIS_WEEK), eq("MS2"), eq(List.of(0, 0)));
        assertThat(orders).hasSize(2);
        assertThat(orders.get(0).getInstrument()).isEqualTo("NIFTY" + THIS_WEEK + "23550CE");
        assertThat(orders.get(0).getExchangeSymbol()).isEqualTo("NFO:NIFTY" + THIS_WEEK + "23550CE");
        assertThat(orders.get(0).getSide()).isEqualTo("BUY");
        assertThat(orders.get(1).getInstrument()).isEqualTo("NIFTY" + THIS_WEEK + "23550PE");
        assertThat(orders.get(1).getSide()).isEqualTo("SELL");
        assertThat(orders).allSatisfy(o -> {
            assertThat(o.getStrike()).isEqualTo(23550);
            assertThat(o.getMoneyness()).isEqualTo("ATM");
        });
    }

    @Test
    @DisplayName("rollover builds against the rollover symbol prefix and checks occupancy there")
    void rolloverUsesRolloverPrefix() {
        when(strikeOccupancy.resolveBaseStrike(anyInt(), anyString(), anyString(), anyList())).thenReturn(23500);
        Position trade = longTrade("MS1");

        List<LegOrder> orders = computeUtil.buildInstrument("23500", trade, true);

        verify(strikeOccupancy).resolveBaseStrike(eq(23500), eq(ROLLOVER), eq("MS1"), anyList());
        assertThat(orders.get(0).getInstrument()).isEqualTo("NIFTY" + ROLLOVER + "23500CE");
    }

    @Test
    @DisplayName("template offsets are applied on top of the resolved base strike")
    void templateOffsetsAppliedToBase() {
        when(templateCache.getLegs(eq("LONG"), anyString())).thenReturn(List.of(
                template("CE", "BUY", 0, 10),
                template("PE", "SELL", -50, 10)));
        when(strikeOccupancy.resolveBaseStrike(anyInt(), anyString(), anyString(), anyList())).thenReturn(23450);
        Position trade = longTrade("MS3");

        List<LegOrder> orders = computeUtil.buildInstrument("23500", trade, false);

        verify(strikeOccupancy).resolveBaseStrike(eq(23500), eq(THIS_WEEK), eq("MS3"), eq(List.of(0, -50)));
        assertThat(orders.get(0).getStrike()).isEqualTo(23450);
        assertThat(orders.get(1).getStrike()).isEqualTo(23400);
        assertThat(orders.get(1).getMoneyness()).isEqualTo("ATM-50");
    }

    @Test
    @DisplayName("registry-level Strategy.lots override resizes every leg without mutating cached templates")
    void strategyLotsOverrideApplied() {
        Strategy small = new Strategy();
        small.setName("MS2");
        small.setLots(2);
        when(strategyRegistry.get("MS2")).thenReturn(small);
        when(strikeOccupancy.resolveBaseStrike(anyInt(), anyString(), anyString(), anyList())).thenReturn(23500);

        List<LegOrder> orders = computeUtil.buildInstrument("23500", longTrade("MS2"), false);

        assertThat(orders).allSatisfy(o -> assertThat(o.getLots()).isEqualTo(2));
        assertThat(defaultLongTemplates).allSatisfy(t -> assertThat(t.getLots()).isEqualTo(10));
    }

    @Test
    @DisplayName("no templates configured for the direction: throws instead of building a zero-leg position")
    void missingTemplatesThrow() {
        when(templateCache.getLegs(eq("LONG"), anyString())).thenReturn(List.of());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> computeUtil.buildInstrument("23500", longTrade("MS9"), false));
    }

    private static Position longTrade(String strategyName) {
        Position trade = new Position();
        trade.setDirection("LONG");
        trade.setStrategyName(strategyName);
        return trade;
    }

    private static LegTemplate template(String optionType, String side, int offsetPts, int lots) {
        LegTemplate t = new LegTemplate();
        t.setOptionType(optionType);
        t.setSide(side);
        t.setOffsetPts(offsetPts);
        t.setLots(lots);
        return t;
    }
}
