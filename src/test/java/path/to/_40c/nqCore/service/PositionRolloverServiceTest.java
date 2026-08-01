package path.to._40c.nqCore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import org.mockito.ArgumentCaptor;

import com.zerodhatech.models.Quote;

import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.entity.WeeklyLeg;
import path.to._40c.nqCore.entity.WeeklySymbolConfig;
import path.to._40c.nqCore.pojo.LegOrder;
import path.to._40c.nqCore.repo.PositionRepository;
import path.to._40c.nqCore.util.ComputeUtil;
import path.to._40c.nqCore.util.PositionUtil;
import path.to._40c.nqCore.util.PositionUtil.ExecResult;

/**
 * Guards the multi-strategy rollover contract: EVERY live position rolls (not just the
 * latest), a failed close aborts only that position's roll while others proceed, positions
 * already on the rollover symbol are skipped as successes, and the boolean result is true
 * only when nothing failed — the trigger uses it to gate symbol promotion.
 */
class PositionRolloverServiceTest {

    private static final String OLD = "25807";
    private static final String NEW = "25814";

    private PositionRepository positionRepository;
    private PositionUtil positionUtil;
    private ComputeUtil computeUtil;
    private PostTradeService postTradeService;
    private PositionRolloverService service;

    /** Instruments whose close order should report NOT fully filled. */
    private java.util.Set<String> failingCloseInstruments;

    @BeforeEach
    void setUp() {
        positionRepository = mock(PositionRepository.class);
        positionUtil = mock(PositionUtil.class);
        computeUtil = mock(ComputeUtil.class);
        postTradeService = mock(PostTradeService.class);
        WeeklySymbolCache symbolCache = new WeeklySymbolCache();
        symbolCache.set(new WeeklySymbolConfig(OLD, NEW));
        failingCloseInstruments = new java.util.HashSet<>();
        service = new PositionRolloverService(positionRepository, positionUtil, computeUtil, postTradeService, symbolCache);

        when(positionUtil.getQuote(any(String[].class))).thenAnswer(inv -> {
            String[] symbols = inv.getArgument(0);
            Map<String, Quote> quotes = new HashMap<>();
            for (String s : symbols) {
                Quote q = new Quote();
                q.lastPrice = 100.0;
                quotes.put(s, q);
            }
            return quotes;
        });
        when(positionUtil.placeAggressiveOrder(any(), anyString(), anyString(), anyInt(), anyString()))
                .thenAnswer(inv -> {
                    String instrument = inv.getArgument(1);
                    int qty = inv.getArgument(3);
                    boolean filled = !failingCloseInstruments.contains(instrument);
                    return new ExecResult("MOCK-" + instrument, filled ? qty : 0, qty, 100.0, filled, filled ? "COMPLETE" : "REJECTED");
                });
        when(positionRepository.save(any(Position.class))).thenAnswer(inv -> inv.getArgument(0));
        when(computeUtil.buildInstrument(anyString(), any(Position.class), eq(true))).thenAnswer(inv -> {
            Position trade = inv.getArgument(1);
            String base = inv.getArgument(0).toString().substring(0, 5);
            return List.of(newLegOrder(trade, "NIFTY" + NEW + base + "CE", "BUY"),
                           newLegOrder(trade, "NIFTY" + NEW + base + "PE", "SELL"));
        });
    }

    @Test
    @DisplayName("every live position rolls: banked points per position, new legs on rollover symbol, all saved")
    void rollsEveryLivePosition() {
        Position longA = livePosition("StratA", "LONG", 23000.0, "NIFTY" + OLD + "23000CE", "NIFTY" + OLD + "23000PE");
        Position shortB = livePosition("StratB", "SHORT", 24000.0, "NIFTY" + OLD + "24000CE", "NIFTY" + OLD + "24000PE");
        when(positionUtil.findAllLiveTradesWithLiveOrderBooks()).thenReturn(List.of(longA, shortB));

        boolean allRolled = service.rollOver("23500");

        assertThat(allRolled).isTrue();
        ArgumentCaptor<Position> saved = ArgumentCaptor.forClass(Position.class);
        verify(positionRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).containsExactly(longA, shortB);
        verify(postTradeService, times(2)).afterOpen(any(Position.class));

        assertThat(longA.getBankedPoints()).isEqualTo(500.0);
        assertThat(longA.getBaselineSpot()).isEqualTo(23500.0);
        assertThat(shortB.getBankedPoints()).isEqualTo(500.0);
        assertThat(shortB.getBaselineSpot()).isEqualTo(23500.0);

        assertThat(longA.getLegs()).hasSize(4);
        assertThat(longA.getLegs().stream().filter(l -> "CLOSED".equals(l.getStatus()))).hasSize(2);
        assertThat(longA.getLegs().stream()
                .filter(l -> "LIVE".equals(l.getStatus()))
                .allMatch(l -> l.getInstrument().startsWith("NIFTY" + NEW))).isTrue();
    }

    @Test
    @DisplayName("a failed close aborts only that position; others roll; result is false")
    void failedCloseAbortsOnlyThatPosition() {
        Position broken = livePosition("StratA", "LONG", 23000.0, "NIFTY" + OLD + "23000CE", "NIFTY" + OLD + "23000PE");
        Position healthy = livePosition("StratB", "LONG", 23100.0, "NIFTY" + OLD + "23100CE", "NIFTY" + OLD + "23100PE");
        when(positionUtil.findAllLiveTradesWithLiveOrderBooks()).thenReturn(List.of(broken, healthy));
        failingCloseInstruments.add("NIFTY" + OLD + "23000CE");

        boolean allRolled = service.rollOver("23500");

        assertThat(allRolled).isFalse();
        ArgumentCaptor<Position> saved = ArgumentCaptor.forClass(Position.class);
        verify(positionRepository, times(1)).save(saved.capture());
        assertThat(saved.getValue()).isSameAs(healthy);
        verify(postTradeService, times(1)).afterOpen(healthy);
        assertThat(broken.getBankedPoints()).isEqualTo(0.0);
        assertThat(broken.getLegs()).hasSize(2);
    }

    @Test
    @DisplayName("a position already entirely on the rollover symbol is skipped as success")
    void skipsPositionAlreadyOnRolloverSymbol() {
        Position alreadyRolled = livePosition("StratA", "LONG", 23000.0, "NIFTY" + NEW + "23000CE", "NIFTY" + NEW + "23000PE");
        Position pending = livePosition("StratB", "LONG", 23100.0, "NIFTY" + OLD + "23100CE", "NIFTY" + OLD + "23100PE");
        when(positionUtil.findAllLiveTradesWithLiveOrderBooks()).thenReturn(List.of(alreadyRolled, pending));

        boolean allRolled = service.rollOver("23500");

        assertThat(allRolled).isTrue();
        verify(positionUtil, never()).placeAggressiveOrder(any(), eq("NIFTY" + NEW + "23000CE"), anyString(), anyInt(), anyString());
        verify(positionRepository, times(1)).save(pending);
        assertThat(alreadyRolled.getLegs()).hasSize(2);
        assertThat(alreadyRolled.getBankedPoints()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("no live positions: nothing placed, result true (trigger may promote a flat book)")
    void noLivePositionsIsSuccess() {
        when(positionUtil.findAllLiveTradesWithLiveOrderBooks()).thenReturn(List.of());

        assertThat(service.rollOver("23500")).isTrue();
        verify(positionUtil, never()).placeAggressiveOrder(any(), anyString(), anyString(), anyInt(), anyString());
        verify(positionRepository, never()).save(any());
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
