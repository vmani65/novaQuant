package path.to._40c.nqCore.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.entity.WeeklyLeg;
import path.to._40c.nqCore.pojo.EquityCurve;
import path.to._40c.nqCore.repo.PositionRepository;
import path.to._40c.nqCore.util.LegScope;

import static path.to._40c.nqCore.util.Constants.DATE_FORMAT;

@Service
public class EquityCurveService {

    private final PositionRepository positionRepository;

    public EquityCurveService(PositionRepository positionRepository) {
        this.positionRepository = positionRepository;
    }

    public List<String> getAllStrategyNames() {
        return positionRepository.findDistinctStrategyNames();
    }

    /**
     * book filter: "All" is the account-level curve that ties to the broker ledger — one
     * point per signal off the row's capital chain. SYNTH_WEEKLY / LONG_MONTHLY isolate one
     * book: rows are selected by holding that book's legs, and since the capital chain is
     * signal-level (not per book), the isolated curve is a SYNTHETIC cumulative chain of the
     * book's own leg P&L (fills minus that book's charges), seeded at the first row's
     * startingCapital. Legacy single-book rows chart identically under both schemes.
     */
    public EquityCurve getEquityCurveData(String strategy, String book) {
        List<Position> trades;

        if ("All".equalsIgnoreCase(strategy)) {
            trades = positionRepository.findAllByOrderByOpenedAtAsc();
        } else {
            trades = positionRepository.findByStrategyNameOrderByOpenedAtAsc(strategy);
        }
        if (book != null && !"All".equalsIgnoreCase(book)) {
            List<Position> bookTrades = trades.stream()
                    .filter(t -> t.getLegs() != null && t.getLegs().stream()
                            .anyMatch(l -> book.equals(LegScope.bookOf(l)) && !LegScope.neverTraded(l)))
                    .collect(Collectors.toList());
            return buildBookEquityCurve(bookTrades, book);
        }

        return buildEquityCurve(trades);
    }

    private static final DateTimeFormatter PARSE_FMT = DateTimeFormatter.ofPattern(DATE_FORMAT);

    private EquityCurve buildEquityCurve(List<Position> trades) {
        List<String> dates            = new ArrayList<>();
        List<Double> equityValues     = new ArrayList<>();
        List<Double> startingCapitals = new ArrayList<>();
        List<Integer> lotSizes        = new ArrayList<>();
        List<String> outcomes         = new ArrayList<>();
        List<Double> points           = new ArrayList<>();
        List<Double> charges          = new ArrayList<>();

        List<Position> sorted = trades.stream()
            .filter(t -> t.getEndingCapital() != null && t.getOpenedAt() != null)
            .sorted(Comparator.comparing(t -> {
                try { return LocalDateTime.parse(t.getOpenedAt(), PARSE_FMT); }
                catch (Exception e) { return LocalDateTime.MIN; }
            }))
            .collect(Collectors.toList());

        double startingEquity = 0.0;
        if (!sorted.isEmpty()) {
            Position first = sorted.get(0);
            startingEquity = first.getStartingCapital() != null ? first.getStartingCapital() : 0.0;
        }

        for (Position trade : sorted) {
            dates.add(formatDate(trade.getOpenedAt()));
            equityValues.add(trade.getEndingCapital());
            startingCapitals.add(trade.getStartingCapital() != null ? trade.getStartingCapital() : 0.0);
            lotSizes.add(trade.getLots() != null ? trade.getLots() : 0);
            outcomes.add(trade.getResult() != null ? trade.getResult() : "");
            points.add(trade.getPointsPnl() != null ? trade.getPointsPnl() : 0.0);
            charges.add(trade.getTotalCharges() != null ? trade.getTotalCharges() : 0.0);
        }

        double currentEquity = equityValues.isEmpty() ? startingEquity
                : equityValues.get(equityValues.size() - 1);
        return new EquityCurve(startingEquity, currentEquity, dates, equityValues, startingCapitals, lotSizes, outcomes, points, charges);
    }

    /**
     * Single-book curve: same array contract as the account curve (the UI derives per-trade
     * P&L as equity[i] − startingCapitals[i]), but each step is the book's OWN leg P&L —
     * Σ(book legs' actualPnl) − Σ(book legs' open+close charges) — accumulated from the
     * first qualifying row's startingCapital. Rows must be accounted (endingCapital set) so
     * only settled signals chart, matching the account curve's inclusion rule.
     */
    private EquityCurve buildBookEquityCurve(List<Position> trades, String book) {
        List<String> dates            = new ArrayList<>();
        List<Double> equityValues     = new ArrayList<>();
        List<Double> startingCapitals = new ArrayList<>();
        List<Integer> lotSizes        = new ArrayList<>();
        List<String> outcomes         = new ArrayList<>();
        List<Double> points           = new ArrayList<>();
        List<Double> charges          = new ArrayList<>();

        List<Position> sorted = trades.stream()
            .filter(t -> t.getEndingCapital() != null && t.getOpenedAt() != null)
            .sorted(Comparator.comparing(t -> {
                try { return LocalDateTime.parse(t.getOpenedAt(), PARSE_FMT); }
                catch (Exception e) { return LocalDateTime.MIN; }
            }))
            .collect(Collectors.toList());

        double startingEquity = 0.0;
        if (!sorted.isEmpty()) {
            Position first = sorted.get(0);
            startingEquity = first.getStartingCapital() != null ? first.getStartingCapital() : 0.0;
        }

        double running = startingEquity;
        for (Position trade : sorted) {
            List<WeeklyLeg> bookLegs = LegScope.of(trade, book).stream()
                    .filter(l -> !LegScope.neverTraded(l)).toList();
            double legPnl = bookLegs.stream()
                    .filter(l -> l.getActualPnl() != null)
                    .mapToDouble(WeeklyLeg::getActualPnl).sum();
            double legCharges = bookLegs.stream()
                    .mapToDouble(l -> (l.getOpenCharges() != null ? l.getOpenCharges() : 0.0)
                            + (l.getCloseCharges() != null ? l.getCloseCharges() : 0.0)).sum();
            int legLots = bookLegs.stream()
                    .filter(l -> l.getLots() != null)
                    .mapToInt(WeeklyLeg::getLots).max().orElse(0);

            dates.add(formatDate(trade.getOpenedAt()));
            startingCapitals.add(running);
            running += legPnl - legCharges;
            equityValues.add(running);
            lotSizes.add(legLots);
            outcomes.add(trade.getResult() != null ? trade.getResult() : "");
            points.add(trade.getPointsPnl() != null ? trade.getPointsPnl() : 0.0);
            charges.add(legCharges);
        }

        double currentEquity = equityValues.isEmpty() ? startingEquity
                : equityValues.get(equityValues.size() - 1);
        return new EquityCurve(startingEquity, currentEquity, dates, equityValues, startingCapitals, lotSizes, outcomes, points, charges);
    }

    private String formatDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            return "";
        }
        try {
            DateTimeFormatter inputFormatter = DateTimeFormatter.ofPattern(DATE_FORMAT);
            LocalDateTime dateTime = LocalDateTime.parse(dateStr, inputFormatter);
            DateTimeFormatter outputFormatter = DateTimeFormatter.ofPattern("dd MMM yy");
            return dateTime.format(outputFormatter);
        } catch (Exception e) {
            return dateStr;
        }
    }
}
