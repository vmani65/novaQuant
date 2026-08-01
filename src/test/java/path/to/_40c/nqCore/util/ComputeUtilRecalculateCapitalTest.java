package path.to._40c.nqCore.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.entity.TradeCapital;
import path.to._40c.nqCore.repo.TradeCapitalRepository;

/**
 * Guards recalculateCapital's graceful degradation, found by the fresh-DB simulation: a
 * missing trade_capital row (or uncomputed P&L) must SKIP the capital chain — never throw —
 * because this runs inside the post-close merge and an exception there destroys the whole
 * trade's outcome/P&L persistence, not just the capital stats.
 */
class ComputeUtilRecalculateCapitalTest {

    private TradeCapitalRepository tradeCapitalRepository;
    private ComputeUtil computeUtil;

    @BeforeEach
    void setUp() {
        tradeCapitalRepository = mock(TradeCapitalRepository.class);
        computeUtil = new ComputeUtil(null, null, tradeCapitalRepository, null, null);
    }

    @Test
    @DisplayName("no trade_capital row (fresh install): skips without throwing, saves nothing")
    void missingCapitalRowSkips() {
        when(tradeCapitalRepository.getTradeCapital()).thenReturn(null);
        Position trade = closedTrade(1500.0, 2);

        assertThatCode(() -> computeUtil.recalculateCapital(trade)).doesNotThrowAnyException();

        assertThat(trade.getStartingCapital()).isNull();
        assertThat(trade.getEndingCapital()).isNull();
        verify(tradeCapitalRepository, never()).save(any());
    }

    @Test
    @DisplayName("actualPnl not computed: skips without throwing, saves nothing")
    void nullPnlSkips() {
        when(tradeCapitalRepository.getTradeCapital()).thenReturn(capital(2000000.0, 200000));
        Position trade = closedTrade(null, 2);

        assertThatCode(() -> computeUtil.recalculateCapital(trade)).doesNotThrowAnyException();

        assertThat(trade.getEndingCapital()).isNull();
        verify(tradeCapitalRepository, never()).save(any());
    }

    @Test
    @DisplayName("happy path: capital chained, lot stats computed, row saved")
    void happyPathChainsCapital() {
        TradeCapital pool = capital(2000000.0, 200000);
        when(tradeCapitalRepository.getTradeCapital()).thenReturn(pool);
        Position trade = closedTrade(1500.0, 2);

        computeUtil.recalculateCapital(trade);

        assertThat(trade.getStartingCapital()).isEqualTo(2000000.0);
        assertThat(trade.getEndingCapital()).isEqualTo(2001500.0);
        assertThat(pool.getCurrentCapital()).isEqualTo(2001500.0);
        assertThat(pool.getCurrentRiskPerLot()).isEqualTo(10);
        verify(tradeCapitalRepository).save(pool);
    }

    @Test
    @DisplayName("definedRiskPerLot unset/zero: capital still chained, lot stats skipped, no exception")
    void zeroRiskPerLotStillChainsCapital() {
        TradeCapital pool = capital(2000000.0, 0);
        when(tradeCapitalRepository.getTradeCapital()).thenReturn(pool);
        Position trade = closedTrade(1500.0, 2);

        assertThatCode(() -> computeUtil.recalculateCapital(trade)).doesNotThrowAnyException();

        assertThat(trade.getEndingCapital()).isEqualTo(2001500.0);
        verify(tradeCapitalRepository).save(pool);
    }

    private static TradeCapital capital(Double current, int riskPerLot) {
        TradeCapital c = new TradeCapital();
        c.setCurrentCapital(current);
        c.setDefinedRiskPerLot(riskPerLot);
        return c;
    }

    private static Position closedTrade(Double actualPnl, Integer lots) {
        Position p = new Position();
        p.setActualPnl(actualPnl);
        p.setLots(lots);
        p.setStatus("CLOSED");
        return p;
    }
}
