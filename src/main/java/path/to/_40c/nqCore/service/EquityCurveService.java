package path.to._40c.nqCore.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.pojo.EquityCurve;
import path.to._40c.nqCore.repo.PositionRepository;

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

    public EquityCurve getEquityCurveData(String strategy) {
        List<Position> trades;

        if ("All".equalsIgnoreCase(strategy)) {
            trades = positionRepository.findAllByOrderByOpenedAtAsc();
        } else {
            trades = positionRepository.findByStrategyNameOrderByOpenedAtAsc(strategy);
        }

        return buildEquityCurve(trades);
    }

    private static final DateTimeFormatter PARSE_FMT = DateTimeFormatter.ofPattern(DATE_FORMAT);

    private EquityCurve buildEquityCurve(List<Position> trades) {
        List<String> dates        = new ArrayList<>();
        List<Double> equityValues = new ArrayList<>();
        List<Integer> lotSizes    = new ArrayList<>();
        List<String> outcomes     = new ArrayList<>();
        List<Double> points       = new ArrayList<>();
        List<Double> charges      = new ArrayList<>();

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
            lotSizes.add(trade.getLots() != null ? trade.getLots() : 0);
            outcomes.add(trade.getResult() != null ? trade.getResult() : "");
            points.add(trade.getPointsPnl() != null ? trade.getPointsPnl() : 0.0);
            charges.add(trade.getTotalCharges() != null ? trade.getTotalCharges() : 0.0);
        }

        double currentEquity = equityValues.isEmpty() ? startingEquity
                : equityValues.get(equityValues.size() - 1);
        return new EquityCurve(startingEquity, currentEquity, dates, equityValues, lotSizes, outcomes, points, charges);
    }

    private String formatDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            return "";
        }
        try {
            DateTimeFormatter inputFormatter = DateTimeFormatter.ofPattern(DATE_FORMAT);
            LocalDateTime dateTime = LocalDateTime.parse(dateStr, inputFormatter);
            DateTimeFormatter outputFormatter = DateTimeFormatter.ofPattern("dd MMM");
            return dateTime.format(outputFormatter);
        } catch (Exception e) {
            return dateStr;
        }
    }
}
