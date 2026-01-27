package path.to._40c.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import path.to._40c.entity.Trade;
import path.to._40c.pojo.EquityCurve;
import path.to._40c.repo.TradeRepository;

import static path.to._40c.util.Constants.DATE_FORMAT;

@Service
public class EquityCurveService {

    @Autowired
    private TradeRepository tradeRepository;
    
    private static final double STARTING_EQUITY = 100000.0;

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

    private EquityCurve buildEquityCurve(List<Trade> trades) {
        double currentEquity = STARTING_EQUITY;        
        List<String> dates = new ArrayList<>();
        List<Double> equityValues = new ArrayList<>();
        List<Integer> lotSizes = new ArrayList<>();
        
        for (Trade trade : trades) {
            dates.add(formatDate(trade.getTradeOpenDtTime()));
            currentEquity += trade.getActualPnL();
            equityValues.add(currentEquity);
            lotSizes.add(trade.getLots());
        }        
        return new EquityCurve(STARTING_EQUITY,currentEquity,dates,equityValues,lotSizes);
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
