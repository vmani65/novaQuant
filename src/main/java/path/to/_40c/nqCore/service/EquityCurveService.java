package path.to._40c.nqCore.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * Per-strategy contribution to the shared capital pool: trade count, wins/losses,
     * Σ actual P&L and Σ charges per strategy, over trades whose P&L has been computed.
     * Backs the capital panel's attribution table.
     */
    public List<Map<String, Object>> getStrategyPnlSummary() {
        Map<String, List<Position>> byStrategy = positionRepository.findAllByOrderByOpenedAtAsc().stream()
                .filter(t -> t.getStrategyName() != null && t.getActualPnl() != null)
                .collect(Collectors.groupingBy(Position::getStrategyName, LinkedHashMap::new, Collectors.toList()));

        List<Map<String, Object>> rows = new ArrayList<>();
        byStrategy.forEach((name, trades) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("strategyName", name);
            row.put("trades", trades.size());
            row.put("wins", trades.stream().filter(t -> "WIN".equals(t.getResult())).count());
            row.put("losses", trades.stream().filter(t -> "LOSS".equals(t.getResult())).count());
            row.put("totalPnl", Math.round(trades.stream().mapToDouble(Position::getActualPnl).sum() * 100.0) / 100.0);
            row.put("totalCharges", Math.round(trades.stream()
                    .mapToDouble(t -> t.getTotalCharges() != null ? t.getTotalCharges() : 0.0).sum() * 100.0) / 100.0);
            row.put("lastTradeAt", trades.get(trades.size() - 1).getOpenedAt());
            rows.add(row);
        });
        rows.sort(Comparator.comparingDouble((Map<String, Object> r) -> (Double) r.get("totalPnl")).reversed());
        return rows;
    }

    /**
     * "All" uses the real account capital chain straight from the DB (it includes deposits
     * and every strategy's P&L — the true account history). A single-strategy view must NOT
     * slice that chain: with several strategies interleaved, each trade's ending_capital
     * embeds the other strategies' P&L, so a sliced curve lies. Instead the strategy curve
     * is rebuilt as a synthetic chain — first trade's starting capital + cumulative Σ of
     * that strategy's own actual P&L — preserving the UI invariant
     * equity[i] − startingCapitals[i] = trade P&L.
     */
    public EquityCurve getEquityCurveData(String strategy) {
        if ("All".equalsIgnoreCase(strategy)) {
            return buildEquityCurve(positionRepository.findAllByOrderByOpenedAtAsc(), false);
        }
        return buildEquityCurve(positionRepository.findByStrategyNameOrderByOpenedAtAsc(strategy), true);
    }

    private static final DateTimeFormatter PARSE_FMT = DateTimeFormatter.ofPattern(DATE_FORMAT);

    private EquityCurve buildEquityCurve(List<Position> trades, boolean perStrategyChain) {
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

        double syntheticEquity = startingEquity;
        for (Position trade : sorted) {
            dates.add(formatDate(trade.getOpenedAt()));
            if (perStrategyChain) {
                double tradePnl = trade.getActualPnl() != null ? trade.getActualPnl()
                        : (trade.getStartingCapital() != null ? trade.getEndingCapital() - trade.getStartingCapital() : 0.0);
                startingCapitals.add(syntheticEquity);
                syntheticEquity = Math.round((syntheticEquity + tradePnl) * 100.0) / 100.0;
                equityValues.add(syntheticEquity);
            } else {
                equityValues.add(trade.getEndingCapital());
                startingCapitals.add(trade.getStartingCapital() != null ? trade.getStartingCapital() : 0.0);
            }
            lotSizes.add(trade.getLots() != null ? trade.getLots() : 0);
            outcomes.add(trade.getResult() != null ? trade.getResult() : "");
            points.add(trade.getPointsPnl() != null ? trade.getPointsPnl() : 0.0);
            charges.add(trade.getTotalCharges() != null ? trade.getTotalCharges() : 0.0);
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
