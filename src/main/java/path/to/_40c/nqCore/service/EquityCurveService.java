package path.to._40c.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import path.to._40c.entity.Trade;
import path.to._40c.pojo.EquityCurve;
import path.to._40c.repo.TradeRepository;

import static path.to._40c.util.Constants.DATE_FORMAT;

@Service
public class EquityCurveService {

    private final TradeRepository tradeRepository;

    public EquityCurveService(TradeRepository tradeRepository) {
        this.tradeRepository = tradeRepository;
    }

    public List<String> getAllStrategyNames() {
        return tradeRepository.findDistinctStrategyNames();
    }

    public EquityCurve getEquityCurveData(String strategy) {
        List<Trade> trades;

        if ("All".equalsIgnoreCase(strategy)) {
            trades = tradeRepository.findAllByOrderByTradeOpenDtTimeAsc();
        } else {
            trades = tradeRepository.findByStatergyNameOrderByTradeOpenDtTimeAsc(strategy);
        }

        return buildEquityCurve(trades);
    }

    private static final DateTimeFormatter PARSE_FMT = DateTimeFormatter.ofPattern(DATE_FORMAT);

    private EquityCurve buildEquityCurve(List<Trade> trades) {
        List<String> dates        = new ArrayList<>();
        List<Double> equityValues = new ArrayList<>();
        List<Integer> lotSizes    = new ArrayList<>();
        List<String> outcomes     = new ArrayList<>();
        List<Double> points       = new ArrayList<>();

        List<Trade> sorted = trades.stream()
            .filter(t -> t.getEndingCapital() != null && t.getTradeOpenDtTime() != null)
            .sorted(Comparator.comparing(t -> {
                try { return LocalDateTime.parse(t.getTradeOpenDtTime(), PARSE_FMT); }
                catch (Exception e) { return LocalDateTime.MIN; }
            }))
            .collect(Collectors.toList());

        double startingEquity = 0.0;
        if (!sorted.isEmpty()) {
            Trade first = sorted.get(0);
            startingEquity = first.getStartingCapital() != null ? first.getStartingCapital() : 0.0;
        }

        for (Trade trade : sorted) {
            dates.add(formatDate(trade.getTradeOpenDtTime()));
            equityValues.add(trade.getEndingCapital());
            lotSizes.add(trade.getLots() != null ? trade.getLots() : 0);
            outcomes.add(trade.getTradeOutcome() != null ? trade.getTradeOutcome() : "");
            points.add(trade.getPointsByTrade() != null ? trade.getPointsByTrade() : 0.0);
        }

        double currentEquity = equityValues.isEmpty() ? startingEquity
                : equityValues.get(equityValues.size() - 1);
        return new EquityCurve(startingEquity, currentEquity, dates, equityValues, lotSizes, outcomes, points);
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
