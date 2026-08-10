package path.to._40c.nqCore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static path.to._40c.nqCore.util.Constants.BUY;
import static path.to._40c.nqCore.util.Constants.CLOSED;
import static path.to._40c.nqCore.util.Constants.LIVE;
import static path.to._40c.nqCore.util.Constants.LONG;
import static path.to._40c.nqCore.util.Constants.SELL;
import static path.to._40c.nqCore.util.Constants.SYNTH_WEEKLY;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zerodhatech.kiteconnect.utils.Constants;
import com.zerodhatech.models.Quote;

import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.entity.WeeklyLeg;
import path.to._40c.nqCore.pojo.LegOrder;
import path.to._40c.nqCore.repo.PositionRepository;
import path.to._40c.nqCore.util.ComputeUtil;
import path.to._40c.nqCore.util.PositionUtil;
import path.to._40c.nqCore.util.PositionUtil.ExecResult;

/**
 * The SYNTH_WEEKLY position roll after the weekly/monthly symmetry restructure: the
 * caller promotes the symbol row FIRST (WeeklySymbolService.syncTradedContract), then
 * the roll moves the position onto whatever the CURRENT slot says. Two behaviors are
 * load-bearing now that the controller's date gate is gone:
 * - a trigger fire on a non-roll day finds the held legs already on the current
 *   contract and does NOTHING — no closes, no re-strike, regardless of ATM drift;
 * - a genuine contract change closes both synthetic legs, banks the segment at the
 *   delta-1 scale (expectedFactor 1.0), and re-opens the pair on the new contract.
 */
class PositionRolloverServiceWeeklyTest {

    private static final String OLD_CE = "NIFTY2681224500CE";
    private static final String OLD_PE = "NIFTY2681224500PE";
    private static final String NEW_CE = "NIFTY2681924800CE";
    private static final String NEW_PE = "NIFTY2681924800PE";
    private static final int QTY = 650;

    private PositionUtil util;
    private ComputeUtil compute;
    private PostTradeService postTrade;
    private PositionRolloverService service;
    private Position weekly;
    private WeeklyLeg heldCe;
    private WeeklyLeg heldPe;

    @BeforeEach
    void setUp() {
        PositionRepository repo = mock(PositionRepository.class);
        util = mock(PositionUtil.class);
        compute = mock(ComputeUtil.class);
        postTrade = mock(PostTradeService.class);
        service = new PositionRolloverService(repo, util, compute, postTrade);
        when(repo.save(any(Position.class))).thenAnswer(inv -> inv.getArgument(0));

        heldCe = leg(OLD_CE, BUY);
        heldPe = leg(OLD_PE, SELL);
        weekly = new Position();
        weekly.setBook(SYNTH_WEEKLY);
        weekly.setDirection(LONG);
        weekly.setStatus(LIVE);
        weekly.setEntrySpot(24500.0);
        weekly.setBaselineSpot(24500.0);
        weekly.setBankedPoints(0.0);
        weekly.setLegs(List.of(heldCe, heldPe));
        when(util.findLiveTradesWithLiveOrderBooks(SYNTH_WEEKLY)).thenReturn(weekly);
        when(util.getQuote(any(String[].class))).thenReturn(Map.of(
                "NFO:" + OLD_CE, new Quote(), "NFO:" + OLD_PE, new Quote(),
                "NFO:" + NEW_CE, new Quote(), "NFO:" + NEW_PE, new Quote()));
    }

    @Test
    @DisplayName("trigger fire on a non-roll day: position already on the current contract → nothing trades, even with ATM drift")
    void sameContractSkipsRollEvenWhenAtmDrifted() {
        when(compute.weeklyContractPrefix()).thenReturn("NIFTY26812");

        service.rollOverWeekly("24800");

        verify(compute, never()).buildWeeklyInstrument(anyString(), any(Position.class));
        verify(util, never()).placeAggressiveOrder(any(), anyString(), anyString(), anyInt(), anyString());
        assertThat(heldCe.getStatus()).isEqualTo(LIVE);
        assertThat(weekly.getBankedPoints()).isEqualTo(0.0);
        verify(postTrade, never()).afterOpen(any());
    }

    @Test
    @DisplayName("contract change: closes both synthetic legs, banks the segment at delta-1 scale, re-opens the pair on the new contract")
    void contractChangeRollsBothLegs() {
        when(compute.weeklyContractPrefix()).thenReturn("NIFTY26819");
        when(compute.buildWeeklyInstrument(eq("24800"), any(Position.class)))
                .thenReturn(List.of(order(NEW_CE, BUY), order(NEW_PE, SELL)));
        when(util.placeAggressiveOrder(any(), anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(new ExecResult("W-ROLL", QTY, QTY, 150.0, true, Constants.ORDER_COMPLETE));

        service.rollOverWeekly("24800");

        assertThat(weekly.getBankedPoints()).isEqualTo(300.0);
        assertThat(weekly.getBaselineSpot()).isEqualTo(24800.0);
        assertThat(weekly.getEntrySpot()).as("entrySpot immutable").isEqualTo(24500.0);
        assertThat(heldCe.getStatus()).isEqualTo(CLOSED);
        assertThat(heldPe.getStatus()).isEqualTo(CLOSED);
        assertThat(heldCe.getExpectedPnl()).as("weekly expectedFactor is 1.0").isEqualTo(QTY * 300.0);
        assertThat(weekly.getStatus()).isEqualTo(LIVE);
        List<WeeklyLeg> newLegs = weekly.getLegs().stream()
                .filter(l -> l.getInstrument().startsWith("NIFTY26819")).toList();
        assertThat(newLegs).extracting(WeeklyLeg::getInstrument).containsExactlyInAnyOrder(NEW_CE, NEW_PE);
        assertThat(newLegs).extracting(WeeklyLeg::getStatus).containsOnly(LIVE);
        verify(postTrade).afterOpen(weekly);
    }

    private static WeeklyLeg leg(String instrument, String side) {
        WeeklyLeg l = new WeeklyLeg();
        l.setInstrument(instrument);
        l.setExchangeSymbol("NFO:" + instrument);
        l.setSide(side);
        l.setQuantity(QTY);
        l.setLots(10);
        l.setStatus(LIVE);
        return l;
    }

    private static LegOrder order(String instrument, String side) {
        LegOrder w = new LegOrder();
        w.setInstrument(instrument);
        w.setExchangeSymbol("NFO:" + instrument);
        w.setSide(side);
        w.setLots(10);
        w.setMoneyness("ATM");
        w.setOptionType(instrument.endsWith("CE") ? "CE" : "PE");
        return w;
    }
}
