package path.to._40c.nqCore.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.extern.slf4j.Slf4j;
import path.to._40c.nqCore.entity.Strategy;
import path.to._40c.nqCore.service.StrategyRegistry;

/**
 * CRUD surface for the strategy registry, so new strategies can be registered (and toggled)
 * from the dashboard without touching SQLite by hand. Distinct from GET /api/strategies,
 * which lists historical strategy names for the equity-curve filter.
 */
@RestController
@RequestMapping("/api/strategy-registry")
@Slf4j
public class StrategyRegistryController {

    private final StrategyRegistry strategyRegistry;

    public StrategyRegistryController(StrategyRegistry strategyRegistry) {
        this.strategyRegistry = strategyRegistry;
    }

    @GetMapping
    public List<Strategy> list() {
        return strategyRegistry.all();
    }

    @PostMapping("/save")
    public ResponseEntity<Strategy> save(@RequestBody Strategy strategy) {
        if (strategy.getName() == null || strategy.getName().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        strategy.setName(strategy.getName().trim());
        Strategy saved = strategyRegistry.save(strategy);
        log.info("Strategy registry updated: {}", saved);
        return ResponseEntity.ok(saved);
    }
}
