package path.to._40c.nqCore.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import path.to._40c.nqCore.entity.BaseLegEntity;
import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.repo.PositionRepository;

@Controller
public class PositionLogController {

    private final PositionRepository positionRepository;

    public PositionLogController(PositionRepository positionRepository) {
        this.positionRepository = positionRepository;
    }

    @GetMapping("/tradeLog")
    public String tradeLogPage() {
        return "tradeLog";
    }

    @GetMapping("/api/trades")
    @ResponseBody
    public List<Map<String, Object>> getTrades() {
        return positionRepository.findAll(Sort.by(Sort.Direction.DESC, "id"))
                .stream()
                .map(this::toMap)
                .collect(Collectors.toList());
    }

    private Map<String, Object> toMap(Position t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("book", t.getBook());
        m.put("openedAt", t.getOpenedAt());
        m.put("closedAt", t.getClosedAt());
        m.put("direction", t.getDirection());
        m.put("status", t.getStatus());
        m.put("result", t.getResult());
        m.put("entrySpot", t.getEntrySpot());
        m.put("exitSpot", t.getExitSpot());
        m.put("pointsPnl", t.getPointsPnl());
        m.put("actualPnl", t.getActualPnl());
        m.put("expectedPnl", t.getExpectedPnl());
        m.put("totalCharges", t.getTotalCharges());
        m.put("pnlCapturePct", t.getPnlCapturePct());
        m.put("lots", t.getLots());
        m.put("baselineSpot", t.getBaselineSpot());
        m.put("bankedPoints", t.getBankedPoints());
        m.put("strategyName", t.getStrategyName());
        m.put("startingCapital", t.getStartingCapital());
        m.put("endingCapital", t.getEndingCapital());
        m.put("lastSignalAction", t.getLastSignalAction());
        m.put("message", t.getMessage());
        m.put("legs", t.getLegs().stream().map(this::childToMap).collect(Collectors.toList()));
        return m;
    }

    private Map<String, Object> childToMap(BaseLegEntity c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("exchangeSymbol", c.getExchangeSymbol());
        m.put("side", c.getSide());
        m.put("moneyness", c.getMoneyness());
        m.put("lots", c.getLots());
        m.put("quantity", c.getQuantity());
        m.put("sellFillPrice", c.getSellFillPrice());
        m.put("buyFillPrice", c.getBuyFillPrice());
        m.put("ltp", c.getLtp());
        m.put("expectedPnl", c.getExpectedPnl());
        m.put("actualPnl", c.getActualPnl());
        m.put("pnlCapturePct", c.getPnlCapturePct());
        m.put("openCharges", c.getOpenCharges());
        m.put("closeCharges", c.getCloseCharges());
        m.put("status", c.getStatus());
        m.put("marginRequired", c.getMarginRequired());
        m.put("openOrderId", c.getOpenOrderId());
        m.put("closeOrderId", c.getCloseOrderId());
        return m;
    }
}
