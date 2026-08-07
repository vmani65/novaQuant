package path.to._40c.nqCore.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import path.to._40c.nqCore.entity.TradeCapital;
import path.to._40c.nqCore.pojo.EquityCurve;
import path.to._40c.nqCore.pojo.TradeCapitalDTO;
import path.to._40c.nqCore.service.EquityCurveService;
import path.to._40c.nqCore.service.TradeCapitalService;

@RestController
@RequestMapping("/api")
@Slf4j
public class EquityCurveController {
    private final EquityCurveService equityCurveService;
    private final TradeCapitalService tradeCapitalService;

    public EquityCurveController(EquityCurveService equityCurveService, TradeCapitalService tradeCapitalService) {
        this.equityCurveService = equityCurveService;
        this.tradeCapitalService = tradeCapitalService;
    }

    @GetMapping("/strategies")
    public ResponseEntity<List<String>> getAvailableStrategies() {
        return ResponseEntity.ok(equityCurveService.getAllStrategyNames());
    }

    @GetMapping("/equity-curve")
    public ResponseEntity<EquityCurve> getEquityCurve(
            @RequestParam(defaultValue = "All") String strategy,
            @RequestParam(defaultValue = "All") String book) {
        return ResponseEntity.ok(equityCurveService.getEquityCurveData(strategy, book));
    }

    @GetMapping("/capital")
    public ResponseEntity<TradeCapital> getTradeCapital() {
        try {
            TradeCapital capital = tradeCapitalService.getTradeCapital();
            return ResponseEntity.ok(capital);
        } catch (Exception e) {
            log.error("Error fetching trade capital", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/capital/save")
    public ResponseEntity<Map<String, Object>> saveTradeCapital(@RequestBody TradeCapitalDTO capitalDTO) {
        Map<String, Object> response = new HashMap<>();
        try {
            TradeCapital tradeCapital = tradeCapitalService.getTradeCapital();

            if (capitalDTO.getCurrentCapital() != null) {
                tradeCapital.setCurrentCapital(capitalDTO.getCurrentCapital());
            }

            if (capitalDTO.getAdditionalCapital() != null && capitalDTO.getAdditionalCapital() > 0) {
                double updatedCapital = tradeCapital.getCurrentCapital() + capitalDTO.getAdditionalCapital();
                tradeCapital.setCurrentCapital(updatedCapital);
                log.info("Added {} to current capital. New total: {}", capitalDTO.getAdditionalCapital(), updatedCapital);
            }

            if (capitalDTO.getCeilingToHit() != null) {
                tradeCapital.setCeilingToHit(capitalDTO.getCeilingToHit());
            }

            if (capitalDTO.getDefinedRiskPerLot() != null) {
                tradeCapital.setDefinedRiskPerLot(capitalDTO.getDefinedRiskPerLot());
            }

            TradeCapital savedCapital = tradeCapitalService.saveTradeCapital(tradeCapital);
            response.put("success", true);
            response.put("message", "Capital settings saved successfully!");
            response.put("data", savedCapital);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error saving trade capital", e);
            response.put("success", false);
            response.put("message", "Error saving capital: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @PostMapping("/capital/add")
    public ResponseEntity<Map<String, Object>> addCapital(@RequestParam Double amount) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (amount == null || amount <= 0) {
                response.put("success", false);
                response.put("message", "Amount must be greater than zero");
                return ResponseEntity.badRequest().body(response);
            }
            TradeCapital updatedCapital = tradeCapitalService.addCapital(amount);
            response.put("success", true);
            response.put("message", "Added ₹" + amount + " to capital successfully!");
            response.put("data", updatedCapital);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error adding capital", e);
            response.put("success", false);
            response.put("message", "Error adding capital: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @GetMapping("/capital/ceiling-status")
    public ResponseEntity<Map<String, Object>> checkCeilingStatus() {
        Map<String, Object> response = new HashMap<>();
        try {
            TradeCapital capital = tradeCapitalService.getTradeCapital();
            boolean hasReached = tradeCapitalService.hasReachedCeiling();
            double utilization = tradeCapitalService.getCapitalUtilization();
            int calculatedLots = tradeCapitalService.calculateLotSize();
            response.put("currentCapital", capital.getCurrentCapital());
            response.put("ceilingToHit", capital.getCeilingToHit());
            response.put("hasReachedCeiling", hasReached);
            response.put("utilizationPercentage", utilization);
            response.put("recommendedLotSize", calculatedLots);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error checking ceiling status", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
