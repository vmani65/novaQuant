package path.to._40c.nqCore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.pojo.EquityCurve;
import path.to._40c.nqCore.repo.PositionRepository;

/**
 * Guards capital attribution. With two strategies interleaved on one shared pool, each
 * trade's ending_capital embeds the other strategy's P&L - so the "All" view must use the
 * real DB chain, while a single-strategy view must rebuild a synthetic chain from that
 * strategy's own P&L only, preserving equity[i] - startingCapitals[i] = trade P&L
 * (the invariant the dashboard's Total P&L computation relies on).
 *
 * Fixture: pool starts at 100,000. StratA +500, then StratB -1,000, then StratA +1,200.
 * Global chain: 100,500 / 99,500 / 100,700. StratA's honest curve: 100,500 / 101,700
 * (a sliced global chain would falsely show StratA dipping with StratB's loss).
 */
class EquityCurveServiceTest {

    private PositionRepository positionRepository;
    private EquityCurveService service;

    private Position a1;
    private Position b1;
    private Position a2;

    @BeforeEach
    void setUp() {
        positionRepository = mock(PositionRepository.class);
        service = new EquityCurveService(positionRepository);

        a1 = trade("StratA", "01-07-2026 10:00:00.000", 100000.0, 100500.0, 500.0, "WIN");
        b1 = trade("StratB", "02-07-2026 10:00:00.000", 100500.0, 99500.0, -1000.0, "LOSS");
        a2 = trade("StratA", "03-07-2026 10:00:00.000", 99500.0, 100700.0, 1200.0, "WIN");

        when(positionRepository.findAllByOrderByOpenedAtAsc()).thenReturn(List.of(a1, b1, a2));
        when(positionRepository.findByStrategyNameOrderByOpenedAtAsc("StratA")).thenReturn(List.of(a1, a2));
        when(positionRepository.findByStrategyNameOrderByOpenedAtAsc("StratB")).thenReturn(List.of(b1));
    }

    @Test
    @DisplayName("All view keeps the real account capital chain")
    void allViewUsesDbChain() {
        EquityCurve curve = service.getEquityCurveData("All");
        assertThat(curve.getEquity()).containsExactly(100500.0, 99500.0, 100700.0);
        assertThat(curve.getStartingCapitals()).containsExactly(100000.0, 100500.0, 99500.0);
        assertThat(curve.getCurrentEquity()).isEqualTo(100700.0);
    }

    @Test
    @DisplayName("single-strategy view is a synthetic chain of that strategy's own P&L")
    void strategyViewIsOwnPnlChain() {
        EquityCurve curve = service.getEquityCurveData("StratA");
        assertThat(curve.getStartingEquity()).isEqualTo(100000.0);
        assertThat(curve.getStartingCapitals()).containsExactly(100000.0, 100500.0);
        assertThat(curve.getEquity()).containsExactly(100500.0, 101700.0);
        assertThat(curve.getCurrentEquity()).isEqualTo(101700.0);
    }

    @Test
    @DisplayName("per-strategy chain preserves equity[i] - startingCapitals[i] = trade P&L")
    void strategyChainPreservesPerTradePnl() {
        EquityCurve curve = service.getEquityCurveData("StratA");
        double totalPnl = 0;
        for (int i = 0; i < curve.getEquity().size(); i++) {
            totalPnl += curve.getEquity().get(i) - curve.getStartingCapitals().get(i);
        }
        assertThat(totalPnl).isEqualTo(1700.0);
    }

    @Test
    @DisplayName("strategy P&L summary aggregates per strategy and sorts by net P&L")
    void strategyPnlSummary() {
        List<Map<String, Object>> rows = service.getStrategyPnlSummary();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("strategyName")).isEqualTo("StratA");
        assertThat(rows.get(0).get("totalPnl")).isEqualTo(1700.0);
        assertThat(rows.get(0).get("trades")).isEqualTo(2);
        assertThat(rows.get(0).get("wins")).isEqualTo(2L);
        assertThat(rows.get(1).get("strategyName")).isEqualTo("StratB");
        assertThat(rows.get(1).get("totalPnl")).isEqualTo(-1000.0);
        assertThat(rows.get(1).get("losses")).isEqualTo(1L);
    }

    private static Position trade(String strategy, String openedAt, Double starting, Double ending,
            Double actualPnl, String result) {
        Position p = new Position();
        p.setStrategyName(strategy);
        p.setOpenedAt(openedAt);
        p.setStartingCapital(starting);
        p.setEndingCapital(ending);
        p.setActualPnl(actualPnl);
        p.setResult(result);
        p.setLots(10);
        p.setStatus("CLOSED");
        return p;
    }
}
