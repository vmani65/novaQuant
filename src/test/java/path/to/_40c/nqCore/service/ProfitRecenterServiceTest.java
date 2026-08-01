package path.to._40c.nqCore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zerodhatech.models.Quote;

import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.entity.Strategy;
import path.to._40c.nqCore.entity.WeeklyLeg;
import path.to._40c.nqCore.pojo.LegOrder;
import path.to._40c.nqCore.repo.PositionRepository;
import path.to._40c.nqCore.util.ComputeUtil;
import path.to._40c.nqCore.util.PositionUtil;
import path.to._40c.nqCore.util.PositionUtil.ExecResult;

/**
 * Guards the multi-strategy recenter contract: nqTicker's strategy-less trigger recenters
 * every live position whose OWN baseline shows enough profit, each strategy judged against
 * its OWN registry floor (default 450), ineligible books untouched with zero orders, and
 * direction-aware profit math (SHORT profits when spot falls).
 */
class ProfitRecenterServiceTest {

    private PositionRepository positionRepository;
    private PositionUtil positionUtil;
    private ComputeUtil computeUtil;
    private WeeklySymbolService weeklySymbolService;
    private PostTradeService postTradeService;
    private StrategyRegistry strategyRegistry;
    private ProfitRecenterService service;

    @BeforeEach
    void setUp() {
        positionRepository = mock(PositionRepository.class);
        positionUtil = mock(PositionUtil.class);
        computeUtil = mock(ComputeUtil.class);
        weeklySymbolService = mock(WeeklySymbolService.class);
        postTradeService = mock(PostTradeService.class);
        strategyRegistry = mock(StrategyRegistry.class);
        service = new ProfitRecenterService(positionRepository, positionUtil, computeUtil,
                weeklySymbolService, postTradeService, strategyRegistry);

        lenient().when(positionUtil.getQuote(any(String[].class))).thenAnswer(inv -> {
            String[] symbols = inv.getArgument(0);
            Map<String, Quote> quotes = new HashMap<>();
            for (String s : symbols) {
                Quote q = new Quote();
                q.lastPrice = 100.0;
                quotes.put(s, q);
            }
            return quotes;
        });
        lenient().when(positionUtil.placeAggressiveOrder(any(), anyString(), anyString(), anyInt(), anyString()))
                .thenAnswer(inv -> new ExecResult("MOCK-" + inv.getArgument(1), inv.getArgument(3),
                        inv.getArgument(3), 100.0, true, "COMPLETE"));
        lenient().when(positionUtil.getOrderTrades(anyString())).thenReturn(List.of(fill()));
        lenient().when(positionRepository.save(any(Position.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(weeklySymbolService.current()).thenReturn(null);
        lenient().when(computeUtil.buildInstrument(anyString(), any(Position.class), anyBoolean())).thenAnswer(inv -> {
            Position trade = inv.getArgument(1);
            return List.of(newLegOrder(trade, "NIFTY2580723500CE", "BUY"),
                           newLegOrder(trade, "NIFTY2580723500PE", "SELL"));
        });
    }

    @Test
    @DisplayName("only positions whose own baseline clears the floor recenter; others untouched")
    void recentersOnlyEligiblePositions() {
        Position eligible = livePosition("StratA", "LONG", 23000.0, "NIFTY2580723000CE", "NIFTY2580723000PE");
        Position notEnough = livePosition("StratB", "LONG", 23400.0, "NIFTY2580723400CE", "NIFTY2580723400PE");
        when(positionUtil.findAllLiveTradesWithLiveOrderBooks()).thenReturn(List.of(eligible, notEnough));

        service.realizeProfits("23500");

        verify(positionRepository, times(1)).save(eligible);
        verify(postTradeService, times(1)).afterOpen(eligible);
        assertThat(eligible.getBankedPoints()).isEqualTo(500.0);
        assertThat(eligible.getBaselineSpot()).isEqualTo(23500.0);

        org.mockito.ArgumentCaptor<String> orderedInstruments = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(positionUtil, org.mockito.Mockito.atLeastOnce()).placeAggressiveOrder(any(),
                orderedInstruments.capture(), anyString(), anyInt(), anyString());
        assertThat(orderedInstruments.getAllValues()).noneMatch(i -> i.contains("23400"));
        verify(positionRepository, never()).save(notEnough);
        assertThat(notEnough.getBankedPoints()).isEqualTo(0.0);
        assertThat(notEnough.getBaselineSpot()).isEqualTo(23400.0);
    }

    @Test
    @DisplayName("a strategy's own registry floor overrides the 450 default")
    void perStrategyFloorHonored() {
        Strategy tight = new Strategy();
        tight.setName("Tight");
        tight.setRecenterMinProfit(600.0);
        when(strategyRegistry.get("Tight")).thenReturn(tight);
        Position tightPosition = livePosition("Tight", "LONG", 23000.0, "NIFTY2580723000CE", "NIFTY2580723000PE");
        when(positionUtil.findAllLiveTradesWithLiveOrderBooks()).thenReturn(List.of(tightPosition));

        service.realizeProfits("23500");

        verify(positionUtil, never()).placeAggressiveOrder(any(), anyString(), anyString(), anyInt(), anyString());
        verify(positionRepository, never()).save(any());
        assertThat(tightPosition.getBaselineSpot()).isEqualTo(23000.0);
    }

    @Test
    @DisplayName("SHORT direction profits when spot falls — recentered on a drop")
    void shortDirectionProfitRecognized() {
        Position shortTrade = livePosition("StratC", "SHORT", 24000.0, "NIFTY2580724000CE", "NIFTY2580724000PE");
        when(positionUtil.findAllLiveTradesWithLiveOrderBooks()).thenReturn(List.of(shortTrade));

        service.realizeProfits("23500");

        verify(positionRepository, times(1)).save(shortTrade);
        assertThat(shortTrade.getBankedPoints()).isEqualTo(500.0);
        assertThat(shortTrade.getBaselineSpot()).isEqualTo(23500.0);
    }

    @Test
    @DisplayName("no live positions: no orders, no saves")
    void noLivePositionsNoop() {
        when(positionUtil.findAllLiveTradesWithLiveOrderBooks()).thenReturn(List.of());

        service.realizeProfits("23500");

        verify(positionUtil, never()).placeAggressiveOrder(any(), anyString(), anyString(), anyInt(), anyString());
        verify(positionRepository, never()).save(any());
    }

    private static com.zerodhatech.models.Trade fill() {
        com.zerodhatech.models.Trade t = new com.zerodhatech.models.Trade();
        t.averagePrice = "100.0";
        t.quantity = "65";
        return t;
    }

    private static Position livePosition(String strategy, String direction, double baseline, String ceInstrument, String peInstrument) {
        Position p = new Position();
        p.setStrategyName(strategy);
        p.setDirection(direction);
        p.setBaselineSpot(baseline);
        p.setStatus("LIVE");
        p.setLegs(List.of(
                liveLeg(p, ceInstrument, "LONG".equals(direction) ? "BUY" : "SELL"),
                liveLeg(p, peInstrument, "LONG".equals(direction) ? "SELL" : "BUY")));
        return p;
    }

    private static WeeklyLeg liveLeg(Position parent, String instrument, String side) {
        WeeklyLeg leg = new WeeklyLeg();
        leg.setPosition(parent);
        leg.setInstrument(instrument);
        leg.setExchangeSymbol("NFO:" + instrument);
        leg.setSide(side);
        leg.setQuantity(65);
        leg.setLots(1);
        leg.setStatus("LIVE");
        return leg;
    }

    private static LegOrder newLegOrder(Position trade, String instrument, String side) {
        LegOrder o = new LegOrder();
        o.setInstrument(instrument);
        o.setExchangeSymbol("NFO:" + instrument);
        o.setSide(side);
        o.setLots(1);
        o.setMoneyness("ATM");
        o.setParentPosition(trade);
        return o;
    }
}
