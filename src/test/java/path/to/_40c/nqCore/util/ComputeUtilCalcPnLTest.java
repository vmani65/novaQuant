package path.to._40c.nqCore.util;

import static org.assertj.core.api.Assertions.assertThat;
import static path.to._40c.nqCore.util.Constants.NA;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.entity.WeeklyLeg;

/**
 * Verifies the per-leg capture accounting in {@link ComputeUtil#calcPnL}: every leg's
 * expectedPnl is qty × the spot points of the leg's own segment — recenter/rollover stamp
 * it when they close a segment, and calcPnL stamps still-null legs (the final segment)
 * with qty × (pointsPnl − bankedPoints). pnlCapturePct = actual/expected is then the
 * leg's share of its segment's move, so the CE+PE legs of a pair sum to ≈100% minus
 * slippage, and near-scratch segments render N/A instead of a denominator-noise ratio.
 */
class ComputeUtilCalcPnLTest {

    private static final int QTY = 75;

    private ComputeUtil computeUtil;

    @BeforeEach
    void setUp() {
        computeUtil = new ComputeUtil(null, null, null);
    }

    /**
     * Builds a closed leg with fills and charges populated the way a completed
     * open+close cycle leaves them.
     */
    private static WeeklyLeg leg(String moneyness, String side, double buyFill, double sellFill) {
        WeeklyLeg w = new WeeklyLeg();
        w.setMoneyness(moneyness);
        w.setSide(side);
        w.setBuyFillPrice(buyFill);
        w.setSellFillPrice(sellFill);
        w.setQuantity(QTY);
        w.setLots(1);
        w.setOpenCharges(50.0);
        w.setCloseCharges(50.0);
        return w;
    }

    private static Position position(double pointsPnl, Double bankedPoints, WeeklyLeg... legs) {
        Position trade = new Position();
        trade.setPointsPnl(pointsPnl);
        trade.setBankedPoints(bankedPoints);
        trade.setLegs(List.of(legs));
        return trade;
    }

    @Test
    @DisplayName("single segment: both legs share the full-move denominator and their pcts sum to 100%")
    void singleSegmentLegsSplitTheMove() {
        WeeklyLeg ce = leg("ATM", "BUY", 200.0, 380.0);
        WeeklyLeg pe = leg("ATM", "SELL", 30.0, 150.0);
        Position trade = position(300.0, 0.0, ce, pe);

        computeUtil.calcPnL(trade);

        assertThat(ce.getExpectedPnl()).isEqualTo(QTY * 300.0);
        assertThat(pe.getExpectedPnl()).isEqualTo(QTY * 300.0);
        assertThat(ce.getActualPnl()).isEqualTo(13500.0);
        assertThat(pe.getActualPnl()).isEqualTo(9000.0);
        assertThat(ce.getPnlCapturePct()).isEqualTo("60.0%");
        assertThat(pe.getPnlCapturePct()).isEqualTo("40.0%");
        assertThat(trade.getExpectedPnl()).isEqualTo(QTY * 300.0);
        assertThat(trade.getActualPnl()).isEqualTo(13500.0 + 9000.0 - 200.0);
        assertThat(trade.getPnlCapturePct()).isEqualTo("99.1%");
    }

    @Test
    @DisplayName("recentered trade: stamped earlier-segment expected survives, null legs get the final segment")
    void recenteredTradeUsesPerSegmentDenominators() {
        WeeklyLeg seg1Ce = leg("ATM", "BUY", 200.0, 560.0);
        WeeklyLeg seg1Pe = leg("ATM", "SELL", 40.0, 150.0);
        seg1Ce.setExpectedPnl(QTY * 500.0);
        seg1Pe.setExpectedPnl(QTY * 500.0);
        WeeklyLeg seg2Ce = leg("ATM", "BUY", 180.0, 240.0);
        WeeklyLeg seg2Pe = leg("ATM", "SELL", 80.0, 120.0);
        Position trade = position(600.0, 500.0, seg1Ce, seg1Pe, seg2Ce, seg2Pe);

        computeUtil.calcPnL(trade);

        assertThat(seg1Ce.getExpectedPnl()).isEqualTo(QTY * 500.0);
        assertThat(seg1Pe.getExpectedPnl()).isEqualTo(QTY * 500.0);
        assertThat(seg2Ce.getExpectedPnl()).isEqualTo(QTY * 100.0);
        assertThat(seg2Pe.getExpectedPnl()).isEqualTo(QTY * 100.0);
        assertThat(seg1Ce.getPnlCapturePct()).isEqualTo("72.0%");
        assertThat(seg1Pe.getPnlCapturePct()).isEqualTo("22.0%");
        assertThat(seg2Ce.getPnlCapturePct()).isEqualTo("60.0%");
        assertThat(seg2Pe.getPnlCapturePct()).isEqualTo("40.0%");
        assertThat(trade.getExpectedPnl()).isEqualTo(QTY * 600.0);
    }

    @Test
    @DisplayName("losing segment: legs show their share of the loss, not an inverted slippage ratio")
    void losingSegmentSharesAreCoherent() {
        WeeklyLeg ce = leg("ATM", "BUY", 300.0, 180.0);
        WeeklyLeg pe = leg("ATM", "SELL", 220.0, 140.0);
        Position trade = position(-200.0, 0.0, ce, pe);

        computeUtil.calcPnL(trade);

        assertThat(ce.getExpectedPnl()).isEqualTo(QTY * -200.0);
        assertThat(ce.getActualPnl()).isEqualTo(QTY * -120.0);
        assertThat(pe.getActualPnl()).isEqualTo(QTY * -80.0);
        assertThat(ce.getPnlCapturePct()).isEqualTo("60.0%");
        assertThat(pe.getPnlCapturePct()).isEqualTo("40.0%");
    }

    @Test
    @DisplayName("near-scratch segment renders N/A instead of a denominator-noise ratio")
    void nearScratchSegmentRendersNa() {
        WeeklyLeg ce = leg("ATM", "BUY", 200.0, 198.0);
        WeeklyLeg pe = leg("ATM", "SELL", 150.0, 151.0);
        Position trade = position(0.5, 0.0, ce, pe);

        computeUtil.calcPnL(trade);

        assertThat(ce.getExpectedPnl()).isEqualTo(37.5);
        assertThat(ce.getPnlCapturePct()).isEqualTo(NA);
        assertThat(pe.getPnlCapturePct()).isEqualTo(NA);
    }

    @Test
    @DisplayName("orphan-origin trade: surviving leg computed alone, no expected/points, result from PnL sign")
    void orphanOriginTradeComputesSurvivingLegOnly() {
        WeeklyLeg pe = leg("ATM", "BUY", 199.445, 149.175);
        pe.setQuantity(650);
        pe.setLots(10);
        WeeklyLeg ce = new WeeklyLeg();
        ce.setMoneyness("ATM");
        ce.setSide("SELL");
        ce.setStatus(Constants.FAILED);
        Position trade = new Position();
        trade.setDirection(Constants.SHORT);
        trade.setExitSpot(24218.0);
        trade.setBaselineSpot(24149.0);
        trade.setLegs(List.of(pe, ce));

        computeUtil.calcTradeOutcome(trade);
        computeUtil.calcPnL(trade);

        assertThat(trade.getPointsPnl()).isNull();
        assertThat(pe.getActualPnl()).isEqualTo(-32675.5);
        assertThat(pe.getExpectedPnl()).isNull();
        assertThat(trade.getExpectedPnl()).isNull();
        assertThat(trade.getActualPnl()).isEqualTo(-32675.5 - 100.0);
        assertThat(trade.getPnlCapturePct()).isEqualTo(NA);
        assertThat(trade.getLots()).isEqualTo(10);
        assertThat(trade.getResult()).isEqualTo(Constants.LOSS);
    }

    @Test
    @DisplayName("pair group with a missing fill is skipped entirely and leaves pct unset")
    void incompletePairGroupIsSkipped() {
        WeeklyLeg ce = leg("ATM", "BUY", 200.0, 380.0);
        WeeklyLeg pe = leg("ATM", "SELL", 30.0, 150.0);
        pe.setSellFillPrice(null);
        Position trade = position(300.0, 0.0, ce, pe);

        computeUtil.calcPnL(trade);

        assertThat(ce.getExpectedPnl()).isNull();
        assertThat(ce.getPnlCapturePct()).isNull();
        assertThat(trade.getActualPnl()).isEqualTo(0.0);
        assertThat(trade.getPnlCapturePct()).isEqualTo(NA);
    }
}
