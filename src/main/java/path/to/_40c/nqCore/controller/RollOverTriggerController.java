package path.to._40c.nqCore.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static path.to._40c.nqCore.util.Constants.ZONE_ID;

import path.to._40c.nqCore.entity.SymbolConfig;
import path.to._40c.nqCore.service.ProfitRecenterService;
import path.to._40c.nqCore.service.SignalService;
import path.to._40c.nqCore.service.SymbolService;

import java.time.LocalDate;
import java.time.ZoneId;

@RestController
@RequestMapping("/api")
public class RollOverTriggerController {

    private static final Logger log = LoggerFactory.getLogger(RollOverTriggerController.class);

    private final SignalService          signalService;
    private final SymbolService          symbolService;
    private final ProfitRecenterService  profitRecenterService;

    public RollOverTriggerController(SignalService signalService, SymbolService symbolService,
                                     ProfitRecenterService profitRecenterService) {
        this.signalService         = signalService;
        this.symbolService         = symbolService;
        this.profitRecenterService = profitRecenterService;
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

        LocalDate today = LocalDate.now(ZoneId.of(ZONE_ID));

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
        symbolService.promoteRolloverSymbol();
        symbolService.markRolloverComplete();
        log.info("RollOver completed — symbol promoted and marked complete");
    }

    /**
     * Called by nQ-ticker ProfitRecenterConsumer when NIFTY profit >= 500 points.
     * Closes the current live legs and re-opens at the new ATM. Trade stays LIVE.
     *
     * Example: GET /api/realize-profits?currentPrice=24500.0
     */
    @GetMapping("/realize-profits")
    public void handleRealizeProfits(@RequestParam String currentPrice) {
        String sanitisedPrice = currentPrice.replace(",", "").trim();
        log.info("Realize-profits trigger received from nQ-ticker | currentPrice={}", sanitisedPrice);
        profitRecenterService.realizeProfits(sanitisedPrice);
        log.info("Realize-profits completed");
    }

    /**
     * Called by nQ-ticker OpenBufferConsumer when the 9:15 AM open buffer fires.
     * Closes the trade directly — no 9:15 AM check, no re-delegation to nQ-ticker.
     *
     * This is the second leg of the open buffer flow:
     *   AFL longExit → nqCore detects 9:15 → arms nQ-ticker buffer
     *   → nQ-ticker fires this endpoint when target hit or 09:28:59 deadline reached
     *
     * Example: GET /api/execute-close?currentPrice=22463.5
     */
    @GetMapping("/execute-close")
    public void handleExecuteClose(@RequestParam String currentPrice) {
        String sanitisedPrice = currentPrice.replace(",", "").trim();
        log.info("execute-close received from nQ-ticker open buffer | currentPrice={}", sanitisedPrice);
        signalService.executeCloseImmediate(sanitisedPrice);
        log.info("execute-close completed");
    }
}
