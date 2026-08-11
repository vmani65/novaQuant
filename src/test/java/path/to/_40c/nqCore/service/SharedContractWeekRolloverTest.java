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
import static path.to._40c.nqCore.util.Constants.BUY;
import static path.to._40c.nqCore.util.Constants.CLOSED;
import static path.to._40c.nqCore.util.Constants.LIVE;
import static path.to._40c.nqCore.util.Constants.LONG;
import static path.to._40c.nqCore.util.Constants.LONG_MONTHLY;
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
 * The last-week-of-month collision under ONE position per signal: in the monthly-expiry
 * week the weekly book trades the MONTHLY-named contract, so the shared row holds three
 * legs where the weekly CE is literally the same instrument string as the monthly CE.
 * The leg's BOOK column is the ONLY discriminator — an instrument-prefix mix-up would
 * roll the wrong book's legs, and no other week can catch it.
 *
 * The test fires the two nqTicker triggers in operational order (post-symbol-sync
 * state, i.e. what each roll sees after its handleXRollOver sync step):
 * 1. weekly roll → ONLY the weekly-booked pair moves to the next weekly contract
 *    (26904), banking into the WEEKLY chain; the monthly leg on the identical contract
 *    string is untouched — status, expectedPnl and the monthly chain all unchanged;
 * 2. monthly roll → ONLY the monthly-booked leg moves to the next monthly contract
 *    (26SEP), banking into the MONTHLY chain; the freshly rolled weekly pair stays
 *    exactly as step 1 left it.
 */
class SharedContractWeekRolloverTest {

    private static final String SHARED_CE = "NIFTY26AUG24500CE";
    private static final String SHARED_PE = "NIFTY26AUG24500PE";
    private static final String NEXT_WEEK_CE = "NIFTY2690424650CE";
    private static final String NEXT_WEEK_PE = "NIFTY2690424650PE";
    private static final String NEXT_MONTH_CE = "NIFTY26SEP24650CE";
    private static final int WEEKLY_QTY = 650;
    private static final int MONTHLY_QTY = 130;

    private PositionUtil util;
    private ComputeUtil compute;
    private PostTradeService postTrade;
    private PositionRolloverService service;
    private Position shared;
    private WeeklyLeg weeklyCe;
    private WeeklyLeg weeklyPe;
    private WeeklyLeg monthlyCe;

    @BeforeEach
    void setUp() {
        PositionRepository repo = mock(PositionRepository.class);
        util = mock(PositionUtil.class);
        compute = mock(ComputeUtil.class);
        postTrade = mock(PostTradeService.class);
        service = new PositionRolloverService(repo, util, compute, postTrade);
        when(repo.save(any(Position.class))).thenAnswer(inv -> inv.getArgument(0));

        weeklyCe = leg(SHARED_CE, SYNTH_WEEKLY, BUY, WEEKLY_QTY, 10);
        weeklyPe = leg(SHARED_PE, SYNTH_WEEKLY, SELL, WEEKLY_QTY, 10);
        monthlyCe = leg(SHARED_CE, LONG_MONTHLY, BUY, MONTHLY_QTY, 2);

        shared = new Position();
        shared.setDirection(LONG);
        shared.setStatus(LIVE);
        shared.setEntrySpot(24500.0);
        shared.setBaselineSpot(24500.0);
        shared.setBankedPoints(0.0);
        shared.setMonthlyBaselineSpot(24500.0);
        shared.setMonthlyBankedPoints(0.0);
        shared.setLegs(List.of(weeklyCe, weeklyPe, monthlyCe));

        when(util.findLiveTradesWithLiveOrderBooks(SYNTH_WEEKLY)).thenReturn(shared);
        when(util.findLiveTradesWithLiveOrderBooks(LONG_MONTHLY)).thenReturn(shared);
        when(util.getQuote(any(String[].class))).thenReturn(Map.of(
                "NFO:" + SHARED_CE, new Quote(), "NFO:" + SHARED_PE, new Quote(),
                "NFO:" + NEXT_WEEK_CE, new Quote(), "NFO:" + NEXT_WEEK_PE, new Quote(),
                "NFO:" + NEXT_MONTH_CE, new Quote()));
        when(util.placeAggressiveOrder(any(), anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(new ExecResult("ROLL-OK", WEEKLY_QTY, WEEKLY_QTY, 200.0, true, Constants.ORDER_COMPLETE));
    }

    @Test
    @DisplayName("shared-contract week: weekly trigger rolls ONLY weekly-booked legs; monthly trigger then rolls ONLY the monthly leg")
    void weeklyThenMonthlyRollEachTouchOnlyTheirOwnBook() {
        when(compute.weeklyContractPrefix()).thenReturn("NIFTY26904");
        when(compute.buildWeeklyInstrument(eq("24650"), any(Position.class)))
                .thenReturn(List.of(order(NEXT_WEEK_CE, SYNTH_WEEKLY, BUY, 10), order(NEXT_WEEK_PE, SYNTH_WEEKLY, SELL, 10)));

        service.rollOverWeekly("24650");

        assertThat(weeklyCe.getStatus()).isEqualTo(CLOSED);
        assertThat(weeklyPe.getStatus()).isEqualTo(CLOSED);
        List<WeeklyLeg> newWeeklyLegs = legsOn(shared, "NIFTY26904");
        assertThat(newWeeklyLegs).extracting(WeeklyLeg::getInstrument)
                .containsExactlyInAnyOrder(NEXT_WEEK_CE, NEXT_WEEK_PE);
        assertThat(newWeeklyLegs).extracting(WeeklyLeg::getStatus).containsOnly(LIVE);
        assertThat(newWeeklyLegs).extracting(WeeklyLeg::getBook).containsOnly(SYNTH_WEEKLY);
        assertThat(shared.getBankedPoints()).isEqualTo(150.0);
        assertThat(shared.getBaselineSpot()).isEqualTo(24650.0);
        assertThat(weeklyCe.getExpectedPnl()).as("weekly stamps at delta-1 scale").isEqualTo(WEEKLY_QTY * 150.0);

        assertThat(monthlyCe.getStatus()).as("monthly leg on the IDENTICAL contract must be untouched").isEqualTo(LIVE);
        assertThat(monthlyCe.getExpectedPnl()).as("weekly re-strike must not stamp the monthly leg").isNull();
        assertThat(shared.getMonthlyBankedPoints()).isEqualTo(0.0);
        assertThat(shared.getMonthlyBaselineSpot()).isEqualTo(24500.0);
        verify(util, never()).findLiveTradesWithLiveOrderBooks(LONG_MONTHLY);
        verify(util, times(4)).placeAggressiveOrder(any(), anyString(), anyString(), anyInt(), anyString());
        verify(postTrade).afterOpen(shared);

        when(compute.monthlyContractPrefix()).thenReturn("NIFTY26SEP");
        when(compute.buildMonthlyInstrument(eq("24650"), any(Position.class)))
                .thenReturn(List.of(order(NEXT_MONTH_CE, LONG_MONTHLY, BUY, 2)));

        service.rollOverMonthly("24650");

        assertThat(monthlyCe.getStatus()).isEqualTo(CLOSED);
        List<WeeklyLeg> newMonthlyLegs = legsOn(shared, "NIFTY26SEP");
        assertThat(newMonthlyLegs).extracting(WeeklyLeg::getInstrument).containsExactly(NEXT_MONTH_CE);
        assertThat(newMonthlyLegs).extracting(WeeklyLeg::getStatus).containsOnly(LIVE);
        assertThat(newMonthlyLegs).extracting(WeeklyLeg::getBook).containsOnly(LONG_MONTHLY);
        assertThat(shared.getMonthlyBankedPoints()).isEqualTo(150.0);
        assertThat(shared.getMonthlyBaselineSpot()).isEqualTo(24650.0);
        assertThat(monthlyCe.getExpectedPnl()).as("monthly stamps at qty/2 scale").isEqualTo(MONTHLY_QTY * 0.5 * 150.0);

        assertThat(legsOn(shared, "NIFTY26904")).as("rolled weekly pair must stay as step 1 left it")
                .extracting(WeeklyLeg::getStatus).containsOnly(LIVE);
        assertThat(shared.getBankedPoints()).as("weekly chain untouched by the monthly roll").isEqualTo(150.0);
        assertThat(shared.getBaselineSpot()).isEqualTo(24650.0);
        verify(util, times(1)).findLiveTradesWithLiveOrderBooks(LONG_MONTHLY);
        verify(util, times(6)).placeAggressiveOrder(any(), anyString(), anyString(), anyInt(), anyString());
        verify(postTrade, times(2)).afterOpen(shared);
    }

    @Test
    @DisplayName("shared-contract week: a monthly trigger whose sync has NOT advanced the slot yet skips — the shared contract is never double-rolled")
    void monthlyRollBeforeSlotAdvanceSkipsOnSharedContract() {
        when(compute.monthlyContractPrefix()).thenReturn("NIFTY26AUG");

        service.rollOverMonthly("24650");

        verify(compute, never()).buildMonthlyInstrument(anyString(), any(Position.class));
        verify(util, never()).placeAggressiveOrder(any(), anyString(), anyString(), anyInt(), anyString());
        assertThat(monthlyCe.getStatus()).isEqualTo(LIVE);
        assertThat(weeklyCe.getStatus()).isEqualTo(LIVE);
    }

    private static WeeklyLeg leg(String instrument, String book, String side, int qty, int lots) {
        WeeklyLeg l = new WeeklyLeg();
        l.setInstrument(instrument);
        l.setExchangeSymbol("NFO:" + instrument);
        l.setBook(book);
        l.setSide(side);
        l.setQuantity(qty);
        l.setLots(lots);
        l.setStatus(LIVE);
        return l;
    }

    private static LegOrder order(String instrument, String book, String side, int lots) {
        LegOrder w = new LegOrder();
        w.setInstrument(instrument);
        w.setExchangeSymbol("NFO:" + instrument);
        w.setBook(book);
        w.setSide(side);
        w.setLots(lots);
        w.setMoneyness("ATM");
        w.setOptionType(instrument.endsWith("CE") ? "CE" : "PE");
        return w;
    }

    private static List<WeeklyLeg> legsOn(Position p, String contractPrefix) {
        return p.getLegs().stream().filter(l -> l.getInstrument().startsWith(contractPrefix)).toList();
    }
}
