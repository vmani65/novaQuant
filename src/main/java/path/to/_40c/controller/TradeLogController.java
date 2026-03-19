package path.to._40c.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import path.to._40c.entity.BaseChildEntity;
import path.to._40c.entity.Trade;
import path.to._40c.repo.TradeRepository;

@Controller
public class TradeLogController {

    @Autowired
    private TradeRepository tradeRepository;

    @GetMapping("/tradeLog")
    public String tradeLogPage() {
        return "tradeLog";
    }

    @GetMapping("/api/trades")
    @ResponseBody
    public List<Map<String, Object>> getTrades() {
        return tradeRepository.findAll(Sort.by(Sort.Direction.DESC, "id"))
                .stream()
                .map(this::toMap)
                .collect(Collectors.toList());
    }

    private Map<String, Object> toMap(Trade t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("tradeOpenDtTime", t.getTradeOpenDtTime());
        m.put("tradeCloseDtTime", t.getTradeCloseDtTime());
        m.put("signalType", t.getSignalType());
        m.put("tradeStatus", t.getTradeStatus());
        m.put("tradeOutcome", t.getTradeOutcome());
        m.put("entrySignalPrice", t.getEntrySignalPrice());
        m.put("exitSignalPrice", t.getExitSignalPrice());
        m.put("pointsByTrade", t.getPointsByTrade());
        m.put("actualPnL", t.getActualPnL());
        m.put("expectedPnL", t.getExpectedPnL());
        m.put("brokerage", t.getBrokerage());
        m.put("lots", t.getLots());
        m.put("statergyName", t.getStatergyName());
        m.put("lastApiAction", t.getLastApiAction());
        m.put("message", t.getMessage());
        m.put("weekly", t.getWeeklyOrderBook().stream().map(this::childToMap).collect(Collectors.toList()));
        m.put("monthly", t.getMonthlyOrderBook().stream().map(this::childToMap).collect(Collectors.toList()));
        return m;
    }

    private Map<String, Object> childToMap(BaseChildEntity c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("tradedSymbol", c.getTradedSymbol());
        m.put("transactionType", c.getTransactionType());
        m.put("moneyness", c.getMoneyness());
        m.put("lots", c.getLots());
        m.put("quantity", c.getQuantity());
        m.put("soldPrice", c.getSoldPrice());
        m.put("boughtPrice", c.getBoughtPrice());
        m.put("ltp", c.getLtp());
        m.put("actualPnL", c.getActualPnL());
        m.put("expectedPnL", c.getExpectedPnL());
        m.put("tradeStatus", c.getTradeStatus());
        m.put("marginToTrade", c.getMarginToTrade());
        m.put("tradeOpenOrderId", c.getTradeOpenOrderId());
        m.put("tradeCloseOrderId", c.getTradeCloseOrderId());
        return m;
    }
}
