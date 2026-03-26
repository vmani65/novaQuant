package path.to._40c.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import path.to._40c.entity.SymbolConfig;
import path.to._40c.service.SignalService;
import path.to._40c.service.SymbolService;

import java.time.LocalDate;
import java.time.ZoneId;

@RestController
@RequestMapping("/api")
public class RollOverTriggerController {

    private static final Logger log = LoggerFactory.getLogger(RollOverTriggerController.class);

    private final SignalService signalService;
    private final SymbolService symbolService;

    public RollOverTriggerController(SignalService signalService, SymbolService symbolService) {
        this.signalService = signalService;
        this.symbolService = symbolService;
    }

    /**
     * Called by rollOverTrigger.afl at exactly 14:47 IST each trading day.
     * Compares today's IST date against the configured rollover day.
     * If they match, triggers rollover and marks it complete. Otherwise logs and returns.
     *
     * Example: GET /api/rollover-trigger?currentPrice=22450.50
     */
    @GetMapping("/rollover-trigger")
    public void handleRollOverTrigger(@RequestParam String currentPrice) {
        String sanitisedPrice = currentPrice.replace(",", "").trim();
        log.info("RollOver trigger received from AFL | currentPrice={}", sanitisedPrice);

        SymbolConfig cfg = symbolService.current();

        if (cfg == null || cfg.getRolloverDay() == null) {
            log.info("RollOver skipped — no rollover day configured");
            return;
        }

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));

        if (!today.equals(cfg.getRolloverDay())) {
            log.info("RollOver skipped — today={} does not match rollover day={}", today, cfg.getRolloverDay());
            return;
        }

        if (Boolean.TRUE.equals(cfg.getRolloverComplete())) {
            log.info("RollOver skipped — already completed for date={}", cfg.getRolloverDay());
            return;
        }

        log.info("RollOver day matched — initiating rollover | date={} | currentPrice={}", today, sanitisedPrice);
        signalService.handleRollOver(sanitisedPrice);
        symbolService.markRolloverComplete();
        log.info("RollOver completed and marked complete");
    }
}
