package path.to._40c.nqCore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import path.to._40c.nqCore.pojo.LegOrder;
import path.to._40c.nqCore.repo.PositionRepository;
import path.to._40c.nqCore.util.ComputeUtil;
import path.to._40c.nqCore.util.PositionUtil;
import path.to._40c.nqCore.util.PositionUtil.ExecResult;

/**
 * Guards the margin gate's placement in the open flow: a blocked check aborts BEFORE any
 * order reaches the broker (the exact anti-orphan property — no leg can fill if none is
 * sent), the position is persisted FAILED with the block reason as its message, and an
 * allowed check leaves the normal open path untouched.
 */
class PositionOpeningServiceMarginTest {

    private PositionRepository positionRepository;
    private PositionUtil positionUtil;
    private ComputeUtil computeUtil;
    private MarginPreCheckService marginPreCheck;
    private PositionOpeningService service;

    @BeforeEach
    void setUp() {
        positionRepository = mock(PositionRepository.class);
        positionUtil = mock(PositionUtil.class);
        computeUtil = mock(ComputeUtil.class);
        marginPreCheck = mock(MarginPreCheckService.class);
        service = new PositionOpeningService(positionRepository, positionUtil, computeUtil, marginPreCheck);

        lenient().when(computeUtil.buildInstrument(anyString(), any(Position.class), anyBoolean())).thenAnswer(inv -> {
            Position trade = inv.getArgument(1);
            return List.of(leg(trade, "NIFTY2580723500CE", "BUY"), leg(trade, "NIFTY2580723500PE", "SELL"));
        });
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
                .thenAnswer(inv -> new ExecResult("MOCK-1", inv.getArgument(3), inv.getArgument(3), 100.0, true, "COMPLETE"));
        lenient().when(positionRepository.save(any(Position.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("blocked margin check: zero orders placed, position saved FAILED with the reason")
    void blockedCheckPlacesNoOrders() {
        when(marginPreCheck.check(any())).thenReturn(
                new MarginPreCheckService.MarginCheck(false, 420000.0, 300000.0, "MARGIN BLOCKED: need 420000, have 300000"));
        Position trade = newTrade("StratA");

        Position saved = service.openTrade("23500", "CE", trade);

        verify(positionUtil, never()).placeAggressiveOrder(any(), anyString(), anyString(), anyInt(), anyString());
        assertThat(saved.getStatus()).isEqualTo("FAILED");
        assertThat(saved.getMessage()).contains("MARGIN BLOCKED");
        assertThat(saved.getLegs()).hasSize(2);
        assertThat(saved.getLegs()).allSatisfy(l -> assertThat(l.getStatus()).isEqualTo("FAILED"));
        ArgumentCaptor<Position> persisted = ArgumentCaptor.forClass(Position.class);
        verify(positionRepository).save(persisted.capture());
        assertThat(persisted.getValue()).isSameAs(trade);
    }

    @Test
    @DisplayName("allowed margin check: the open proceeds and the position goes LIVE")
    void allowedCheckOpensNormally() {
        when(marginPreCheck.check(any())).thenReturn(
                new MarginPreCheckService.MarginCheck(true, 420000.0, 500000.0, "sufficient funds"));
        Position trade = newTrade("StratA");

        Position saved = service.openTrade("23500", "CE", trade);

        assertThat(saved.getStatus()).isEqualTo("LIVE");
        assertThat(saved.getMessage()).isNull();
        verify(positionUtil, org.mockito.Mockito.times(2))
                .placeAggressiveOrder(any(), anyString(), anyString(), anyInt(), anyString());
    }

    private static Position newTrade(String strategy) {
        Position p = new Position();
        p.setStrategyName(strategy);
        return p;
    }

    private static LegOrder leg(Position trade, String instrument, String side) {
        LegOrder o = new LegOrder();
        o.setInstrument(instrument);
        o.setExchangeSymbol("NFO:" + instrument);
        o.setSide(side);
        o.setLots(2);
        o.setMoneyness("ATM");
        o.setParentPosition(trade);
        return o;
    }
}
